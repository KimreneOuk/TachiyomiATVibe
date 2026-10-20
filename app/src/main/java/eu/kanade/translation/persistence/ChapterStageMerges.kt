package eu.kanade.translation.persistence

import eu.kanade.translation.persistence.ChapterTranslationStore.PatchPrecondition
import eu.kanade.translation.persistence.ChapterTranslationStore.PatchResult
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.PublishedPageTranslation
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
