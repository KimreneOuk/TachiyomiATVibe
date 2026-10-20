package eu.kanade.translation.persistence

import eu.kanade.translation.persistence.ChapterTranslationStore.PatchPrecondition
import eu.kanade.translation.persistence.ChapterTranslationStore.PatchResult
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.PublishedPageTranslation
import eu.kanade.translation.model.StageFingerprints
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.isCleanedImageReady
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.stableFingerprint
import eu.kanade.translation.model.toDraft
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

internal class ChapterStageMerges(
    private val store: ChapterTranslationStore,
) {
    private val mutex get() = store.mutex

    private suspend fun applyStagePatch(
        patch: StagePatch,
        description: String,
    ): StagePatchResult {
        if (store.isDefunct) return rejectedStage(patch.pageKey, description, "store is defunct")
        return mutex.withLock {
            when (val admission = store.publicationBridge.admitMutation()) {
                MutationAdmission.Granted -> Unit
                is MutationAdmission.Rejected -> return@withLock rejectedStage(
                    patch.pageKey,
                    description,
                    "${admission.code}: ${admission.message}",
                )
            }
            val current = store.pages[patch.pageKey]?.toDraft()
            when (patch) {
                is StagePatch.Ocr -> mergeOcrLocked(current, patch.value, description)
                is StagePatch.Translation -> mergeTranslationLocked(current, patch.value, description)
                is StagePatch.Render -> mergeRenderLocked(current, patch.value, description)
            }
        }
    }

    /**
     * Detection/OCR stage merge. The patch must carry the page
     * version and prior OCR identity observed before the native recognition
     * pass; anything that touched the page since makes the writer stale and
     * the merge is rejected, so a late recognition result can never clobber
     * newer work or drop translations merged in the meantime.
     *
     * Manual target edits are authoritative for their exact source block: a
     * new block whose OCR identity matches an edited block inherits its
     * translation and edit timestamp. On a failed recognition the merge keeps
     * the existing blocks and only records the failure diagnostics.
     */
    suspend fun mergeOcr(
        patch: OcrStagePatch,
        description: String = "detection/ocr stage merge",
    ): StagePatchResult = applyStagePatch(StagePatch.Ocr(patch), description)

    private suspend fun mergeOcrLocked(
        current: PageTranslation?,
        patch: OcrStagePatch,
        description: String,
    ): StagePatchResult {
        // Same identity fence as the translation/render merges
        // (stageIdentityRejection); the OCR-specific prior-OCR check sits
        // between the dependency fingerprint and the lease clauses, exactly
        // where it sat in the previous inline chain. Reason strings and
        // ordering are behavior.
        val rejection = stageIdentityRejection(
            current,
            patch.generation,
            patch.expectedPageVersion,
            patch.expectedLeaseToken,
            patch.pageKey,
            patch.expectedCandidateGenerationId,
            patch.expectedDependencyFingerprint,
            patch.expectedArtifactPageVersion,
        ) ?: if (patch.expectedPriorOcrFingerprints != current!!.ocrBlockFingerprints()) {
            "prior OCR identity changed"
        } else {
            null
        }
        if (rejection != null) return rejectedStage(patch.pageKey, description, rejection)

        val updated = mergeOcrFields(current!!, patch.ocrResult, patch.errorMessage)
        val owned = store.ownedPage(patch.pageKey, updated)
        store.pages = store.pages.put(patch.pageKey, store.publishPage(owned))
        if (!store.pageMutations.publishLocked(current, owned, patch.toPrecondition())) {
            store.pageMutations.restorePageLocked(patch.pageKey, current)
            return rejectedStage(patch.pageKey, description, "ARTIFACT_PUBLICATION_FAILED")
        }
        return StagePatchResult.Accepted(store.snapshotLocked(patch.pageKey))
    }

    suspend fun mergeTranslation(
        patch: TranslationStagePatch,
        description: String = "translation stage merge",
    ): StagePatchResult = applyStagePatch(StagePatch.Translation(patch), description)

    suspend fun mergeRender(
        patch: RenderStagePatch,
        description: String = "render stage merge",
    ): StagePatchResult = applyStagePatch(StagePatch.Render(patch), description)

    /**
     * Checkpoints the page's current (already merged) OCR state while the
     * caller still owns its writer lease. [expectedCandidateGenerationId]
     * non-null selects the standard CLOSE/REBASE branch against the active
     * BATCH candidate; null selects the.1 adopt-committed branch
     * (no active candidate; the committed bundle's OCR content fingerprint
     * must equal the live OCR state's). [sourceOrientation] and
     * [sourceSha256] complete the checkpoint's [SourceIdentity] — the sha
     * falls back to the live page's recorded source fingerprint; a checkpoint
     * without a provably complete source identity is rejected (fail closed).
     * Store generation, page version, and lease ownership are checked before
     * durable publication. The checkpoint pointer is installed only after a
     * committed artifact result; the caller releases its lease afterward.
     */
    suspend fun checkpointOcr(
        pageKey: String,
        generation: Long,
        expectedPageVersion: Long,
        expectedLeaseToken: Long,
        expectedCandidateGenerationId: String?,
        expectedArtifactPageVersion: Long?,
        expectedDependencyFingerprint: String?,
        sourceOrientation: String? = null,
        sourceSha256: String? = null,
        expectedPriorOcrFingerprints: List<String>? = null,
        mode: OcrCheckpointMode = OcrCheckpointMode.CLOSE,
        nowEpochMs: Long = System.currentTimeMillis(),
        description: String = "ocr checkpoint",
    ): CheckpointOcrResult {
        if (store.isDefunct) return rejectedCheckpoint(description, "store is defunct")
        return mutex.withLock {
                    when (val admission = store.publicationBridge.admitMutation()) {
                        MutationAdmission.Granted -> Unit
                        is MutationAdmission.Rejected -> return@withLock rejectedCheckpoint(
                            description,
                            "${admission.code}: ${admission.message}",
                        )
                    }
                    val artifact = store.artifactEngine
                    val manifest = store.artifactManifest
                    if (artifact == null || manifest == null) {
                        return@withLock rejectedCheckpoint(description, "artifact store was not established")
                    }
                    val current = store.pages[pageKey]
                    val artifactPage = manifest.pages[pageKey]
                    val rejection = when {
                        generation != store.generation -> "generation expected=$generation actual=${store.generation}"
                        current == null -> "page missing"
                        expectedPageVersion != current.pageVersion ->
                            "pageVersion expected=$expectedPageVersion actual=${current.pageVersion}"
                        // 02.1: the lease token is mandatory and must still
                        // fence this page — a released or stolen lease rejects.
                        store.pageLeases[pageKey] == null -> "page lease required for checkpoint"
                        store.pageLeases[pageKey]?.token != expectedLeaseToken -> "page lease token changed"
                        expectedArtifactPageVersion != null &&
                            expectedArtifactPageVersion != artifactPage?.pageVersion ->
                            "artifact pageVersion changed"
                        expectedCandidateGenerationId != null &&
                            expectedCandidateGenerationId != artifactPage?.candidate?.generationId ->
                            "candidate generation changed"
                        // 02.1 / C2: no grace clause on the checkpoint path —
                        // a missing dependency fingerprint is a programmer error.
                        expectedCandidateGenerationId != null && expectedDependencyFingerprint == null ->
                            "dependency fingerprint required for checkpoint"
                        expectedCandidateGenerationId != null &&
                            artifactPage?.candidate?.dependencyFingerprint != expectedDependencyFingerprint ->
                            "candidate dependency fingerprint changed"
                        expectedCandidateGenerationId == null && artifactPage?.candidate != null ->
                            "active candidate present; standard checkpoint branch required"
                        expectedPriorOcrFingerprints != null &&
                            expectedPriorOcrFingerprints != current.ocrBlockFingerprints() ->
                            "prior OCR identity changed"
                        else -> null
                    }
                    if (rejection != null) return@withLock rejectedCheckpoint(description, rejection)
                    val live = current?.toDraft() ?: return@withLock rejectedCheckpoint(description, "page missing")
                    // The checkpoint records which mask basis produced the OCR
                    // masks. It must not promote the cleaned-output revision:
                    // that revision is stamped only when inpaint publishes the
                    // corresponding cleaned image, and display readiness relies
                    // on it to reject stale cleaned bytes.
                    val inpaintMaskRevision = PageTranslation.CURRENT_INPAINT_REVISION
                    val resolvedSourceSha256 = sourceSha256 ?: live.sourceFingerprint
                    val sourceIdentity = SourceIdentity(
                        pageKey = pageKey,
                        sha256 = resolvedSourceSha256,
                        width = live.imgWidth.takeIf { it > 0f }?.toInt(),
                        height = live.imgHeight.takeIf { it > 0f }?.toInt(),
                        orientation = sourceOrientation,
                    )
                    if (!sourceIdentity.isComplete) {
                        return@withLock rejectedCheckpoint(description, "incomplete source identity for checkpoint")
                    }
                    val snapshotFingerprint = StageFingerprints.pageSnapshot(live)
                    val snapshotFileName = artifact.progress.ocrStageSnapshotName(pageKey, snapshotFingerprint)
                    val naturalPageIndex = artifactPage?.naturalPageIndex
                    val checkpoint = PageOcrCheckpoint(
                        pageKey = pageKey,
                        naturalPageIndex = naturalPageIndex,
                        sourceIdentity = sourceIdentity,
                        detectionFingerprint = live.detectionFingerprint,
                        ocrFingerprint = live.ocrFingerprint.orEmpty(),
                        ocrContentFingerprint = pageOcrContentFingerprint(
                            pageKey,
                            live,
                            naturalPageIndex,
                            sourceOrientation,
                            inpaintMaskRevision,
                        ),
                        ocrPageSnapshotPointer = SidecarPointer(
                            fileName = snapshotFileName,
                            schemaVersion = 1,
                            contentFingerprint = snapshotFingerprint,
                        ),
                        inpaintMaskRevision = inpaintMaskRevision,
                        priorCommittedDisplay = artifactPage?.committed?.let { committed ->
                            CommittedDisplayRef(
                                generationId = committed.generationId,
                                bundleFingerprint = committed.bundleFingerprint,
                                pageSnapshotFileName = committed.pageSnapshotFileName,
                            )
                        },
                        producedByOrigin = store.pageLeases.getValue(pageKey).origin.toArtifactOrigin(),
                        producerGenerationId = expectedCandidateGenerationId,
                        checkpointedAtEpochMs = nowEpochMs,
                    )
                    val outcome = artifact.progress.checkpointOcr(
                        manifest = manifest,
                        pageKey = pageKey,
                        expectedPageVersion = artifactPage?.pageVersion ?: 0L,
                        expectedDependencyFingerprint = expectedDependencyFingerprint,
                        ocrSnapshot = live,
                        checkpoint = checkpoint,
                        mode = mode,
                        nowEpochMs = nowEpochMs,
                    )
                    return@withLock when (outcome) {
                        is ChapterArtifactEngine.TransactionOutcome.Committed -> {
                            store.artifactManifest = outcome.manifest
                            store.stateFlow.value = store.snapshotPages()
                            store.displayPublication.displaySnapshotLocked(listOf(pageKey))
                            CheckpointOcrResult.Committed(
                                snapshot = store.snapshotLocked(pageKey),
                                manifest = outcome.manifest,
                                ocrContentFingerprint = checkpoint.ocrContentFingerprint,
                            )
                        }
                        is ChapterArtifactEngine.TransactionOutcome.Rejected ->
                            rejectedCheckpoint(description, outcome.reason)
                    }
        }
    }

    /**
     * Restores a page's saved OCR from its durable checkpoint into live
     * memory WITHOUT republishing: the checkpoint and its page-snapshot
     * sidecar are already manifest-referenced, so rebuilding the in-memory
     * blocks writes no new checkpoint or page payload and no manifest solely
     * for this operation. The checkpoint remains the durable recovery
     * authority; the first REAL mutation after hydration opens/binds the
     * candidate through the existing publication bridge.
     *
     * Shared by the single-page preflight reuse path and the chapter-resume
     * envelope rebuild. Fences (no grace, mirroring the checkpoint path):
     * store generation, page version, a HELD OCR lease whose token equals
     * [expectedLeaseToken], the observed candidate generation / dependency
     * fingerprint / artifact page version, and the CURRENT manifest
     * checkpoint pointer — plus the checkpoint's own provenance: a complete
     * source identity matching [observedSourceSha256], a readable snapshot
     * bundle whose page-snapshot fingerprint AND OCR content fingerprint both
     * equal the checkpoint's records. A live page that already carries
     * different OCR blocks (newer work or user edits) is never overwritten;
     * identical blocks short-circuit to an idempotent Accepted (repeated
     * adoption is a no-op). A non-READY checkpointed outcome is refused as
     * stale; every rejection leaves memory and durable state untouched.
     */
    suspend fun hydrateOcrFromCheckpoint(
        pageKey: String,
        generation: Long,
        expectedPageVersion: Long,
        expectedLeaseToken: Long,
        expectedCandidateGenerationId: String?,
        expectedDependencyFingerprint: String?,
        expectedArtifactPageVersion: Long?,
        checkpointPointer: SidecarPointer,
        observedSourceSha256: String?,
        description: String,
    ): StagePatchResult = mutex.withLock {
        if (store.isDefunct) return@withLock rejectedStage(pageKey, description, "store is defunct")
        val manifest = store.artifactManifest
        val artifact = store.artifactEngine
        if (manifest == null || artifact == null) {
            return@withLock rejectedStage(pageKey, description, "artifact store was not established")
        }
        // Current-snapshot fence: only the checkpoint the manifest still
        // references may back live state — never a stale file handle.
        if (manifest.ocrCheckpoints[pageKey] != checkpointPointer) {
            return@withLock rejectedStage(pageKey, description, "checkpoint pointer changed")
        }
        // The provenance verification (both fingerprints, complete source
        // identity, observed sha, READY state) is the ONE classified recovery
        // read shared with the opener's publication-free reader fallback.
        val recovery = when (val read = artifact.progress.readRecoveryCheckpoint(checkpointPointer, observedSourceSha256)) {
            is ChapterProgressRecords.CheckpointRecoveryRead.Usable -> read
            is ChapterProgressRecords.CheckpointRecoveryRead.Unusable ->
                return@withLock rejectedStage(pageKey, description, read.reason)
        }
        val checkpoint = recovery.checkpoint
        val ocrSnapshot = recovery.ocrSnapshot
        if (store.pageLeases[pageKey] == null) {
            return@withLock rejectedStage(pageKey, description, "page lease required for hydration")
        }
        if (expectedCandidateGenerationId != null && expectedDependencyFingerprint == null) {
            return@withLock rejectedStage(pageKey, description, "dependency fingerprint required for hydration")
        }
        val current = store.pages[pageKey]?.toDraft()
        val rejection = stageIdentityRejection(
            current,
            generation,
            expectedPageVersion,
            expectedLeaseToken,
            pageKey,
            expectedCandidateGenerationId,
            expectedDependencyFingerprint,
            expectedArtifactPageVersion,
        )
        if (rejection != null) return@withLock rejectedStage(pageKey, description, rejection)
        val currentBlockFingerprints = current!!.ocrBlockFingerprints()
        if (currentBlockFingerprints.isNotEmpty()) {
            if (currentBlockFingerprints == ocrSnapshot.ocrBlockFingerprints()) {
                // Already hydrated with identical content: repeated adoption
                // is a no-op — no version bump, no state churn, no writes.
                return@withLock StagePatchResult.Accepted(store.snapshotLocked(pageKey))
            }
            return@withLock rejectedStage(pageKey, description, "prior OCR identity changed")
        }
        // The OCR merge field semantics (including edited-block inheritance
        // and the textless SKIPPED propagation) stay owned by mergeOcrFields.
        val updated = mergeOcrFields(current, ocrSnapshot)
        // mergeOcrFields does not own the source identity. A synthesized
        // placeholder carries none, and the checkpoint's source identity was
        // just proven against the observed source bytes above — restore it so
        // planning/revalidation see the same identity the OCR was built from.
        updated.sourceFingerprint = checkpoint.sourceIdentity.sha256
        val owned = store.ownedPage(pageKey, updated)
        store.pages = store.pages.put(pageKey, store.publishPage(owned))
        store.stateFlow.value = store.snapshotPages()
        store.displayPublication.displaySnapshotLocked(listOf(pageKey))
        store.displayPublication.promoteDisplayIfReadyLocked(pageKey, owned)
        StagePatchResult.Accepted(store.snapshotLocked(pageKey))
    }

    /**
     * The semantic `PageOcrContentFingerprint` of the page's current OCR
     * state (  encoding). Transaction identities
     * (versions, generations, timestamps, file names) are excluded by the
     * builder.
     *
     * 1: [pageKey] MUST be the transaction pageKey, never
     * [PageTranslation.sourceFileName] — the.1 adopt side
     * ([ChapterArtifactEngine.committedOcrContentFingerprint]) canonicalizes
     * with `checkpoint.sourceIdentity.pageKey` (the transaction pageKey), so
     * both sides of the drift comparison must share one pageKey source.
     */
    private fun pageOcrContentFingerprint(
        pageKey: String,
        page: PageTranslationView,
        naturalPageIndex: Int?,
        sourceOrientation: String?,
        inpaintMaskRevision: Int,
    ): String = StageFingerprints.pageOcrContentFingerprint(
        pageKey = pageKey,
        naturalPageIndex = naturalPageIndex,
        sourceSha256 = page.sourceFingerprint.orEmpty(),
        sourceWidth = page.imgWidth.toInt(),
        sourceHeight = page.imgHeight.toInt(),
        sourceOrientation = sourceOrientation.orEmpty(),
        detectionFingerprint = page.detectionFingerprint,
        ocrFingerprint = page.ocrFingerprint.orEmpty(),
        textless = page.isTextlessTerminal,
        inpaintMaskRevision = inpaintMaskRevision,
        blocks = StageFingerprints.pageOcrContentBlocks(page),
        inpaintMaskBoxes = page.inpaintMaskBoxes,
    )

    private fun rejectedCheckpoint(description: String, reason: String): CheckpointOcrResult.Rejected {
        logcat(LogPriority.WARN) {
            "TachiyomiAT ocr checkpoint rejected: generation=${store.generation} " +
                "operation=$description reason=$reason"
        }
        return CheckpointOcrResult.Rejected(reason)
    }

    private suspend fun mergeTranslationLocked(
        current: PageTranslation?,
        patch: TranslationStagePatch,
        description: String,
    ): StagePatchResult {
        if (patch.sourceFingerprint != null && !patch.sourceFingerprint.isSha256Hex()) {
            return rejectedStage(patch.pageKey, description, "invalid observed source fingerprint")
        }
        val identityRejection = stageIdentityRejection(
            current,
            patch.generation,
            patch.expectedPageVersion,
            patch.expectedLeaseToken,
            patch.pageKey,
            patch.expectedCandidateGenerationId,
            patch.expectedDependencyFingerprint,
            patch.expectedArtifactPageVersion,
        )
            ?: ocrIdentityRejection(current, patch.expectedOcrBlockFingerprints, patch.expectedSourceTexts)
            // Provenance preconditions are opt-in. Both fields default to null,
            // so older patches keep byte-identical behavior (no extra manifest
            // reads or new
            // rejection class). Non-null fields are validated against the
            // manifest's CURRENTLY frozen profile / envelope-plan pointers;
            // a mismatch rejects the WHOLE page patch (stale-profile and
            // stale-plan protection; a rejected commit never advances any
            // frontier or page state).
            ?: translationProvenanceRejection(patch)
        if (identityRejection != null) {
            return rejectedStage(patch.pageKey, description, identityRejection)
        }
        val page = current!!.detachedCopy()
        val applied = mutableListOf<Int>()
        val rejectedTargets = mutableListOf<String>()
        patch.blocks.forEach { target ->
            val block = page.blocks.getOrNull(target.blockIndex)
            val rejection = when {
                block == null -> "block index ${target.blockIndex} missing"
                block.ocrFingerprint() != target.expectedOcrFingerprint ->
                    "block ${target.blockIndex} OCR identity changed"
                block.text != target.expectedSourceText ->
                    "block ${target.blockIndex} source changed"
                block.translation != target.expectedTranslation ->
                    "block ${target.blockIndex} translation changed"
                block.userEditedAt != target.expectedUserEditedAt ->
                    "block ${target.blockIndex} user edit changed"
                else -> null
            }
            if (rejection != null) {
                rejectedTargets += rejection
            } else {
                block!!.translation = target.translation
                applied += target.blockIndex
            }
        }
        if (applied.isEmpty()) {
            return rejectedStage(
                patch.pageKey,
                description,
                rejectedTargets.firstOrNull() ?: "no translation targets",
            )
        }
        page.translationStatus = patch.translationStatus
        page.sourceFingerprint = patch.sourceFingerprint ?: page.sourceFingerprint
        page.errorMessage = patch.errorMessage
        val updated = store.ownedPage(patch.pageKey, page)
        store.pages = store.pages.put(patch.pageKey, store.publishPage(updated))
        if (!store.pageMutations.publishLocked(current, updated, patch.toPrecondition())) {
            store.pageMutations.restorePageLocked(patch.pageKey, current)
            return rejectedStage(patch.pageKey, description, "ARTIFACT_PUBLICATION_FAILED")
        }
        return StagePatchResult.Accepted(store.snapshotLocked(patch.pageKey), applied)
    }

    /**
     * The envelope-plan precondition is active only when the patch carries its
     * nullable plan fingerprint;
     * a fully-null patch returns `null` before touching the manifest, so
     * every caller without the fingerprint keeps byte-identical merge behavior.
     *
     *  - `envelopePlanFingerprint` must equal the manifest's current
     *    `envelopePlan` pointer content fingerprint  — a commit
     *    built from a superseded plan is rejected.
     *
     * A rejected patch never mutates page state or advances a frontier.
     */
    private fun translationProvenanceRejection(patch: TranslationStagePatch): String? {
        if (patch.envelopePlanFingerprint == null) return null
        val manifest = store.artifactManifest
        patch.envelopePlanFingerprint?.let { expected ->
            val plan = manifest?.envelopePlan?.contentFingerprint
            if (plan != expected) {
                return "translation provenance rejected: envelope plan changed " +
                    "(expected=$expected current=${plan ?: "<absent>"})"
            }
        }
        return null
    }

    private suspend fun mergeRenderLocked(
        current: PageTranslation?,
        patch: RenderStagePatch,
        description: String,
    ): StagePatchResult {
        val rejection = stageIdentityRejection(
            current,
            patch.generation,
            patch.expectedPageVersion,
            patch.expectedLeaseToken,
            patch.pageKey,
            patch.expectedCandidateGenerationId,
            patch.expectedDependencyFingerprint,
            patch.expectedArtifactPageVersion,
        )
            ?: ocrIdentityRejection(current, patch.expectedOcrBlockFingerprints, null)
            ?: current?.takeUnless { it.cleanedImageName == patch.expectedCleanedImageName }
                ?.let { "cleaned image identity changed" }
            ?: current?.takeUnless { it.inpaintRevision == patch.expectedInpaintRevision }
                ?.let { "inpaint revision changed" }
            ?: current?.takeIf {
                patch.renderStatus == StageStatus.READY &&
                    !it.isCleanedImageReady &&
                    !it.originalImageFallback
            }
                ?.let { "cleaned image is not current for render completion" }
        if (rejection != null) return rejectedStage(patch.pageKey, description, rejection)

        val page = current!!.detachedCopy()
        patch.blocks.forEach { target ->
            val block = page.blocks.getOrNull(target.blockIndex)
            if (block == null) {
                return rejectedStage(patch.pageKey, description, "block index ${target.blockIndex} missing")
            }
            if (block.stableFingerprint() != target.expectedBlockFingerprint) {
                return rejectedStage(patch.pageKey, description, "render block identity changed")
            }
        }
        patch.blocks.forEach { target ->
            val block = page.blocks[target.blockIndex]
            block.textColor = target.textColor
            block.strokeColor = target.strokeColor
            block.strokeWidth = target.strokeWidth
        }
        page.renderStatus = patch.renderStatus
        patch.layoutFingerprint?.let { page.layoutFingerprint = it }
        page.errorMessage = patch.errorMessage
        val updated = store.ownedPage(patch.pageKey, page)
        store.pages = store.pages.put(patch.pageKey, store.publishPage(updated))
        if (!store.pageMutations.publishLocked(current, updated, patch.toPrecondition())) {
            store.pageMutations.restorePageLocked(patch.pageKey, current)
            return rejectedStage(patch.pageKey, description, "ARTIFACT_PUBLICATION_FAILED")
        }
        return StagePatchResult.Accepted(store.snapshotLocked(patch.pageKey), patch.blocks.map { it.blockIndex })
    }

    private fun stageIdentityRejection(
        page: PageTranslation?,
        expectedGeneration: Long,
        expectedPageVersion: Long? = null,
        expectedLeaseToken: Long? = null,
        pageKey: String? = null,
        expectedCandidateGenerationId: String? = null,
        expectedDependencyFingerprint: String? = null,
        expectedArtifactPageVersion: Long? = null,
    ): String? = when {
        expectedGeneration != store.generation -> "generation expected=$expectedGeneration actual=${store.generation}"
        page == null -> "page missing"
        expectedPageVersion != null && expectedPageVersion != page.pageVersion ->
            "pageVersion expected=$expectedPageVersion actual=${page.pageVersion}"
        expectedArtifactPageVersion != null &&
            pageKey != null &&
            expectedArtifactPageVersion != store.artifactManifest?.pages?.get(pageKey)?.pageVersion ->
            "artifact pageVersion changed"
        expectedCandidateGenerationId != null &&
            pageKey != null &&
            expectedCandidateGenerationId != store.artifactManifest?.pages?.get(pageKey)?.candidate?.generationId ->
            "candidate generation changed"
        expectedDependencyFingerprint != null &&
            pageKey != null &&
            store.artifactManifest?.pages?.get(pageKey)?.candidate?.let { candidate ->
                expectedDependencyFingerprint != candidate.dependencyFingerprint
            } == true ->
            "candidate dependency fingerprint changed"
        expectedLeaseToken != null && pageKey != null && store.pageLeases[pageKey]?.token != expectedLeaseToken ->
            "page lease token changed"
        expectedLeaseToken == null && pageKey != null && store.pageLeases[pageKey] != null ->
            "page lease token required"
        else -> null
    }

    private fun OcrStagePatch.toPrecondition() = PatchPrecondition(
        generation = generation,
        pageVersion = expectedPageVersion,
        blockFingerprints = expectedPriorOcrFingerprints,
        leaseToken = expectedLeaseToken,
        candidateGenerationId = expectedCandidateGenerationId,
        dependencyFingerprint = expectedDependencyFingerprint,
        artifactPageVersion = expectedArtifactPageVersion,
    )

    private fun TranslationStagePatch.toPrecondition() = PatchPrecondition(
        generation = generation,
        pageVersion = expectedPageVersion ?: 0L,
        leaseToken = expectedLeaseToken,
        candidateGenerationId = expectedCandidateGenerationId,
        dependencyFingerprint = expectedDependencyFingerprint,
        artifactPageVersion = expectedArtifactPageVersion,
    )

    private fun RenderStagePatch.toPrecondition() = PatchPrecondition(
        generation = generation,
        pageVersion = expectedPageVersion ?: 0L,
        leaseToken = expectedLeaseToken,
        candidateGenerationId = expectedCandidateGenerationId,
        dependencyFingerprint = expectedDependencyFingerprint,
        artifactPageVersion = expectedArtifactPageVersion,
    )

    private data class PageWriteRejection(
        val reason: String,
        val detail: PatchResult.Rejected.Detail? = null,
    )

    private fun ocrIdentityRejection(
        page: PageTranslation?,
        expectedFingerprints: List<String>,
        expectedSources: List<String>?,
    ): String? = when {
        page == null -> "page missing"
        page.ocrBlockFingerprints() != expectedFingerprints -> "OCR block identity changed"
        expectedSources != null && page.blocks.map { it.text } != expectedSources -> "OCR source changed"
        else -> null
    }

    private fun rejectedStage(pageKey: String, description: String, reason: String): StagePatchResult.Rejected {
        logcat(LogPriority.WARN) {
            "TachiyomiAT stage patch rejected: pageKey=$pageKey generation=${store.generation} " +
                "operation=$description reason=$reason"
        }
        return StagePatchResult.Rejected(reason)
    }


}

internal fun mergeOcrFields(
    current: PageTranslation,
    result: PageTranslation,
    errorMessage: String? = null,
): PageTranslation {
    val updated = current.detachedCopy()
    if (result.ocrStatus == StageStatus.FAILED) {
        // Failure updates diagnostics only; preserve reusable page data.
        updated.ocrStatus = StageStatus.FAILED
        updated.errorMessage = errorMessage ?: result.activeError
    } else {
        val preserveReusableInpaint =
            result.inpaintStatus == StageStatus.PENDING &&
                result.sourceFingerprint != null &&
                result.sourceFingerprint == current.sourceFingerprint &&
                result.detectionFingerprint != null &&
                result.detectionFingerprint == current.detectionFingerprint &&
                current.isCleanedImageReady
        val editedByIdentity = current.blocks
            .filter { it.userEditedAt != null }
            .associateBy { it.ocrFingerprint() }
        updated.blocks = result.blocks.map { block ->
            editedByIdentity[block.ocrFingerprint()]?.let { edited ->
                block.copy(translation = edited.translation, userEditedAt = edited.userEditedAt)
            } ?: block
        }.toMutableList()
        updated.inpaintMaskBoxes = result.inpaintMaskBoxes
        updated.allTextDetections = result.allTextDetections
        updated.ocrStatus = result.ocrStatus
        updated.inpaintStatus = result.inpaintStatus
        updated.renderStatus = result.renderStatus
        if (result.ocrStatus == StageStatus.READY &&
            result.blocks.isEmpty() &&
            result.translationStatus == StageStatus.SKIPPED
        ) {
            updated.translationStatus = StageStatus.SKIPPED
        }
        updated.detectionCount = result.detectionCount
        updated.ocrBlockCount = result.ocrBlockCount
        updated.recognitionEngine = result.recognitionEngine
        updated.decodeSampleSize = result.decodeSampleSize
        updated.imgWidth = result.imgWidth
        updated.imgHeight = result.imgHeight
        updated.originalImgWidth = result.originalImgWidth
        updated.originalImgHeight = result.originalImgHeight
        if (result.cleanedImageName != null) {
            updated.cleanedImageName = result.cleanedImageName
            updated.cleanedImageContentHash = result.cleanedImageContentHash
        }
        updated.inpaintRevision = result.inpaintRevision
        updated.inpaintingModeUsed = result.inpaintingModeUsed
        updated.detectionFingerprint = result.detectionFingerprint
        updated.ocrFingerprint = result.ocrFingerprint
        if (preserveReusableInpaint) {
            // OCR-only updates retain cleaned data when source and detector match.
            updated.inpaintMaskBoxes = current.inpaintMaskBoxes
            updated.inpaintStatus = current.inpaintStatus
            updated.inpaintError = current.inpaintError
            updated.inpaintRevision = current.inpaintRevision
            updated.inpaintingModeUsed = current.inpaintingModeUsed
            updated.inpaintFingerprint = current.inpaintFingerprint
            updated.cleanedImageName = current.cleanedImageName
            updated.cleanedImageContentHash = current.cleanedImageContentHash
        }
        errorMessage?.let { updated.errorMessage = it }
    }
    return updated
}
