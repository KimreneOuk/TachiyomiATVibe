package eu.kanade.translation.persistence

import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageFingerprints
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.persistence.ChapterArtifactEngine.DestructiveManifestReceipt
import eu.kanade.translation.persistence.ChapterArtifactEngine.TransactionOutcome
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
class ChapterCandidateTransactions internal constructor(
    private val documents: AtomicChapterDocuments,
    private val layout: ChapterArtifactLayout,
    private val engine: ChapterArtifactEngine,
) {
    private val io: ChapterDocumentIo get() = documents.rawIo()

    /** Neutral outcome details for opening an artifact candidate. */
    sealed interface CandidateOpenState {
        data class Reused(
            val pageKey: String,
            val dependencyFingerprint: String,
        ) : CandidateOpenState

        data class FingerprintMismatch(
            val pageKey: String,
            val expectedFingerprint: String,
            val activeFingerprint: String?,
        ) : CandidateOpenState

        data class CandidateAlreadyActive(
            val pageKey: String,
            val requestedOrigin: ArtifactOrigin,
            val activeOrigin: ArtifactOrigin,
        ) : CandidateOpenState
    }

    /** Persists one immutable live candidate snapshot and advances its pointer. */
    fun persistLiveCandidate(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        generationId: String,
        expectedPageVersion: Long,
        expectedDependencyFingerprint: String,
        pageSnapshot: PageTranslation,
        origin: ArtifactOrigin,
        sourceIdentity: SourceIdentity? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
        durableFailure: DurableFailureMetadata? = null,
    ): TransactionOutcome {
        val rejection = candidateWriteRejection(
            manifest,
            pageKey,
            generationId,
            expectedPageVersion,
            expectedDependencyFingerprint,
        )
        if (rejection != null) return TransactionOutcome.Rejected(rejection)
        val page = manifest.pages.getValue(pageKey)
        if (page.candidate?.origin != origin) {
            return TransactionOutcome.Rejected(
                "candidate provenance mismatch: pageKey=$pageKey expected=${page.candidate?.origin} actual=$origin",
            )
        }
        val fileName = page.candidate?.pageSnapshotFileName
            ?: layout.candidatePageSnapshotFile(pageKey, generationId)
        val pageSnapshotFingerprint = StageFingerprints.pageSnapshot(pageSnapshot)
        if (!documents.publishJson(fileName, pageSnapshot.detachedCopy())) {
            return TransactionOutcome.Rejected("candidate page snapshot publication failed: pageKey=$pageKey")
        }
        val failureKey = durableFailure?.let { "${it.pageKey}:${it.stage.name}" }
        val failureRecord = durableFailure?.let { failure ->
            StageArtifactRecord(
                status = failure.status,
                fingerprint = expectedDependencyFingerprint,
                origin = origin,
                // Durable failure metadata is page-level retry state, not candidate work product.
                generationId = null,
                updatedAtEpochMs = nowEpochMs,
            )
        }
        val updatedPage = page.copy(
            source = sourceIdentity ?: page.source,
            candidate = page.candidate.copy(
                pageSnapshotFileName = fileName,
                pageSnapshotFingerprint = pageSnapshotFingerprint,
            ),
            pageVersion = page.pageVersion + 1,
        ).let { candidatePage ->
            if (durableFailure == null) candidatePage else candidatePage.withStage(durableFailure.stage, failureRecord)
        }
        val updated = manifest.copy(
            pages = manifest.pages + (
                pageKey to updatedPage
                ),
            durableFailures = if (failureKey == null) {
                manifest.durableFailures
            } else {
                manifest.durableFailures + (failureKey to durableFailure)
            },
            updatedAtEpochMs = nowEpochMs,
        )
        if (!engine.publishManifestInternal(updated)) {
            val reason = "manifest publication failed; candidate snapshot pointer unchanged"
            return TransactionOutcome.Rejected(reason)
        }
        return TransactionOutcome.Committed(updated, generationId)
    }

    /**
     * Candidate snapshot plus retry/terminal metadata in one manifest
     * publication. The immutable candidate sidecar is written first, then a
     * single manifest pointer update makes both pieces visible together.
     */
    fun persistLiveCandidateAndFailure(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        generationId: String,
        expectedPageVersion: Long,
        expectedDependencyFingerprint: String,
        pageSnapshot: PageTranslation,
        origin: ArtifactOrigin,
        failure: DurableFailureMetadata,
        sourceIdentity: SourceIdentity? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome = persistLiveCandidate(
        manifest = manifest,
        pageKey = pageKey,
        generationId = generationId,
        expectedPageVersion = expectedPageVersion,
        expectedDependencyFingerprint = expectedDependencyFingerprint,
        pageSnapshot = pageSnapshot,
        origin = origin,
        sourceIdentity = sourceIdentity,
        nowEpochMs = nowEpochMs,
        durableFailure = failure,
    )

    /** Commits the live candidate and atomically moves the committed pointer. */
    fun promoteLiveCandidate(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        generationId: String,
        expectedPageVersion: Long,
        expectedDependencyFingerprint: String,
        pageSnapshot: PageTranslation,
        origin: ArtifactOrigin,
        sourceIdentity: SourceIdentity? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome = promoteLiveCandidateTransaction(
        manifest = manifest,
        pageKey = pageKey,
        generationId = generationId,
        expectedPageVersion = expectedPageVersion,
        expectedDependencyFingerprint = expectedDependencyFingerprint,
        pageSnapshot = pageSnapshot,
        origin = origin,
        sourceIdentity = sourceIdentity,
        nowEpochMs = nowEpochMs,
        persistCandidateSnapshot = true,
    )

    /**
     * Fuses the candidate snapshot and terminal promotion into one recoverable publication.
     * Used only when the store already knows this mutation is immediately promotable. The
     * transaction still publishes committed snapshot, generation record, and final manifest in
     * that order; the existing two-step candidate protocol remains for in-progress mutations.
     * If terminal publication fails, it restores the candidate snapshot and pointer so reopening
     * retains the same recoverable work as the two-step path.
     */
    fun promoteLiveCandidateImmediately(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        generationId: String,
        expectedPageVersion: Long,
        expectedDependencyFingerprint: String,
        pageSnapshot: PageTranslation,
        origin: ArtifactOrigin,
        sourceIdentity: SourceIdentity? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome = promoteLiveCandidateTransaction(
        manifest = manifest,
        pageKey = pageKey,
        generationId = generationId,
        expectedPageVersion = expectedPageVersion,
        expectedDependencyFingerprint = expectedDependencyFingerprint,
        pageSnapshot = pageSnapshot,
        origin = origin,
        sourceIdentity = sourceIdentity,
        nowEpochMs = nowEpochMs,
        persistCandidateSnapshot = false,
    )

    private fun promoteLiveCandidateTransaction(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        generationId: String,
        expectedPageVersion: Long,
        expectedDependencyFingerprint: String,
        pageSnapshot: PageTranslation,
        origin: ArtifactOrigin,
        sourceIdentity: SourceIdentity?,
        nowEpochMs: Long,
        persistCandidateSnapshot: Boolean,
    ): TransactionOutcome {
        val rejection = candidateWriteRejection(
            manifest,
            pageKey,
            generationId,
            expectedPageVersion,
            expectedDependencyFingerprint,
        )
        if (rejection != null) return TransactionOutcome.Rejected(rejection)
        val page = manifest.pages.getValue(pageKey)
        val resolvedPage = page.copy(source = sourceIdentity ?: page.source)
        val candidate = page.candidate
            ?: return TransactionOutcome.Rejected("candidate missing: pageKey=$pageKey")
        if (candidate.origin != origin) {
            return TransactionOutcome.Rejected(
                "candidate provenance mismatch: pageKey=$pageKey expected=${candidate.origin} actual=$origin",
            )
        }
        pageSnapshot.cleanedImageName?.let { cleanedName ->
            if (!ChapterArtifactLayout.isSafeSegment(cleanedName)) {
                return TransactionOutcome.Rejected("unsafe cleaned display file name: pageKey=$pageKey")
            }
        }
        if (persistCandidateSnapshot) {
            val candidateFile = candidate.pageSnapshotFileName
                ?: layout.candidatePageSnapshotFile(pageKey, generationId)
            val candidateMatches = documents.readValidated<PageTranslation>(candidateFile)?.let {
                it == pageSnapshot
            } == true
            if (!candidateMatches && !documents.publishJson(candidateFile, pageSnapshot.detachedCopy())) {
                return TransactionOutcome.Rejected("candidate page snapshot publication failed: pageKey=$pageKey")
            }
        }
        val committedFile = layout.committedPageSnapshotFile(pageKey, generationId)
        if (!documents.publishJson(committedFile, pageSnapshot.detachedCopy())) {
            return TransactionOutcome.Rejected("committed page snapshot publication failed: pageKey=$pageKey")
        }
        val pageSnapshotFingerprint = StageFingerprints.pageSnapshot(pageSnapshot)
        val displayBase = DisplayBaseReference(
            kind = if (pageSnapshot.cleanedImageName != null) DisplayBaseKind.CLEANED_IMAGE else DisplayBaseKind.ORIGINAL_SOURCE,
            fileName = pageSnapshot.cleanedImageName,
            validated = pageSnapshot.cleanedImageName != null,
            legacyLayout = false,
        )
        val committed = CommittedBundleMetadata(
            generationId = generationId,
            bundleFingerprint = StageFingerprints.committedBundle(
                sourceIdentity = resolvedPage.source,
                displayBase = displayBase,
                translationFingerprint = pageSnapshotFingerprint,
                layoutFingerprint = null,
            ),
            displayBase = displayBase,
            translationFingerprint = pageSnapshotFingerprint,
            origin = origin,
            provisional = false,
            hasManualEdits = pageSnapshot.blocks.any { it.userEditedAt != null },
            promotedAtEpochMs = nowEpochMs,
            pageSnapshotFileName = committedFile,
        )
        val promotedTranslation = resolvedPage.translation?.copy(
            status = if (pageSnapshot.isTextlessTerminal) ArtifactStageStatus.TEXTLESS else ArtifactStageStatus.READY,
            fingerprint = pageSnapshotFingerprint,
            origin = origin,
            generationId = null,
            artifactFileName = committedFile,
            updatedAtEpochMs = nowEpochMs,
        ) ?: StageArtifactRecord(
            status = if (pageSnapshot.isTextlessTerminal) ArtifactStageStatus.TEXTLESS else ArtifactStageStatus.READY,
            fingerprint = pageSnapshotFingerprint,
            origin = origin,
            artifactFileName = committedFile,
            updatedAtEpochMs = nowEpochMs,
        )
        val updated = manifest.copy(
            pages = manifest.pages + (
                pageKey to page.copy(
                    source = resolvedPage.source,
                    committed = committed,
                    previousCommitted = page.committed,
                    candidate = null,
                    displayState = if (pageSnapshot.isTextlessTerminal) {
                        PageDisplayState.TEXTLESS_COMPLETE
                    } else {
                        PageDisplayState.DISPLAY_READY
                    },
                    translation = promotedTranslation,
                    pageVersion = page.pageVersion + if (persistCandidateSnapshot) 1 else 2,
                )
                ),
            activeCandidateGenerationIds = manifest.activeCandidateGenerationIds - generationId,
            durableFailures = manifest.durableFailures - "$pageKey:${ArtifactStage.TRANSLATION.name}",
            updatedAtEpochMs = nowEpochMs,
        )
        if (!engine.publishManifestInternal(updated)) {
            return TransactionOutcome.Rejected("manifest publication failed; committed pointer unchanged")
        }
        return TransactionOutcome.Committed(updated, generationId)
    }

    /** Cancels a live candidate while retaining the committed pointer. */
    fun cancelLiveCandidate(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        generationId: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome = cancelCandidate(manifest, pageKey, generationId, nowEpochMs)

    /** Explicit user reset: remove committed/candidate pointers durably. */
    fun demoteLivePage(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome {
        engine.staleManifestRejection(manifest)?.let { return TransactionOutcome.Rejected(it) }
        val page = manifest.pages[pageKey]
            ?: return TransactionOutcome.Rejected("page missing: pageKey=$pageKey")
        val candidateGenerationId = page.candidate?.generationId

        val updated = manifest.copy(
            pages = manifest.pages + (
                pageKey to page.copy(
                    committed = null,
                    previousCommitted = null,
                    candidate = null,
                    displayState = PageDisplayState.ORIGINAL_ONLY,
                    pageVersion = page.pageVersion + 1,
                )
                ),
            activeCandidateGenerationIds = candidateGenerationId?.let { manifest.activeCandidateGenerationIds - it }
                ?: manifest.activeCandidateGenerationIds,
            durableFailures = manifest.durableFailures.filterValues { it.pageKey != pageKey },
            updatedAtEpochMs = nowEpochMs,
        )
        // A user reset uses checked publication so backup recovery cannot
        // resurrect the removed committed/candidate pointers.
        return when (val receipt = engine.publishDestructiveManifest(updated)) {
            is DestructiveManifestReceipt.Complete ->
                TransactionOutcome.Committed(updated, candidateGenerationId)
            is DestructiveManifestReceipt.PrimaryAdvancedBackupIncomplete ->
                TransactionOutcome.Committed(
                    updated,
                    candidateGenerationId,
                    unverifiedBackupReason = receipt.reason,
                )
            is DestructiveManifestReceipt.NotPublished -> TransactionOutcome.Rejected(
                "manifest publication failed; reset pointers remain authoritative",
            )
        }
    }

    /** Removes one page from the artifact-authoritative live manifest. */
    fun deleteLivePage(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome {
        engine.staleManifestRejection(manifest)?.let { return TransactionOutcome.Rejected(it) }
        if (pageKey !in manifest.pages) return TransactionOutcome.Committed(manifest)
        val updated = manifest.copy(
            pages = manifest.pages - pageKey,
            activeCandidateGenerationIds = manifest.activeCandidateGenerationIds -
                manifest.pages[pageKey]?.candidate?.generationId.orEmpty(),
            durableFailures = manifest.durableFailures.filterValues { it.pageKey != pageKey },
            updatedAtEpochMs = nowEpochMs,
        )
        // Page removal is destructive and uses checked manifest publication.
        return when (val receipt = engine.publishDestructiveManifest(updated)) {
            is DestructiveManifestReceipt.Complete ->
                TransactionOutcome.Committed(updated)
            is DestructiveManifestReceipt.PrimaryAdvancedBackupIncomplete ->
                TransactionOutcome.Committed(updated, unverifiedBackupReason = receipt.reason)
            is DestructiveManifestReceipt.NotPublished -> TransactionOutcome.Rejected(
                "manifest publication failed; page remains authoritative: pageKey=$pageKey",
            )
        }
    }

    /** Opens or idempotently reopens a candidate generation. */
    fun openCandidate(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        origin: ArtifactOrigin,
        expectedPageVersion: Long,
        dependencyFingerprint: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome {
        engine.staleManifestRejection(manifest)?.let { return TransactionOutcome.Rejected(it) }
        val page = manifest.pages[pageKey]
            ?: PageArtifactRecord(pageKey = pageKey)
        if (page.pageVersion != expectedPageVersion) {
            return TransactionOutcome.Rejected(
                "stale page version: pageKey=$pageKey expected=$expectedPageVersion actual=${page.pageVersion}",
            )
        }
        val existing = page.candidate
        var candidateOpenState: CandidateOpenState? = null
        val generationId = when {
            existing != null &&
                existing.origin == origin &&
                existing.origin != ArtifactOrigin.LEGACY &&
                existing.dependencyFingerprint == dependencyFingerprint -> {
                candidateOpenState = CandidateOpenState.Reused(
                    pageKey = pageKey,
                    dependencyFingerprint = dependencyFingerprint,
                )
                existing.generationId
            }
            existing != null && existing.origin == ArtifactOrigin.LEGACY -> engine.newGenerationId(pageKey, nowEpochMs)
            existing != null -> {
                candidateOpenState = if (existing.origin == origin) {
                    CandidateOpenState.FingerprintMismatch(
                        pageKey = pageKey,
                        expectedFingerprint = dependencyFingerprint,
                        activeFingerprint = existing.dependencyFingerprint,
                    )
                } else {
                    CandidateOpenState.CandidateAlreadyActive(
                        pageKey = pageKey,
                        requestedOrigin = origin,
                        activeOrigin = existing.origin,
                    )
                }
                return TransactionOutcome.Rejected(
                    "candidate already active with a different dependency fingerprint: pageKey=$pageKey",
                    candidateOpenState = candidateOpenState,
                )
            }
            else -> engine.newGenerationId(pageKey, nowEpochMs)
        }
        val candidate = CandidateGenerationMetadata(
            generationId = generationId,
            origin = origin,
            dependencyFingerprint = dependencyFingerprint,
            createdAtEpochMs = existing?.createdAtEpochMs ?: nowEpochMs,
            priorDisplayState = existing?.priorDisplayState ?: page.displayState,
        )

        val updated = manifest.copy(
            pages = manifest.pages + (
                pageKey to page.copy(
                    candidate = candidate,
                    displayState = if (page.committed != null) {
                        PageDisplayState.REFRESHING_WITH_COMMITTED_RESULT
                    } else {
                        PageDisplayState.CANDIDATE_RUNNING
                    },
                    pageVersion = page.pageVersion + if (existing == null || existing.origin == ArtifactOrigin.LEGACY) 1L else 0L,
                )
                ),
            activeCandidateGenerationIds = (
                manifest.activeCandidateGenerationIds -
                    existing?.generationId.orEmpty()
                ) + generationId,
            updatedAtEpochMs = nowEpochMs,
        )
        if (!engine.publishManifestInternal(updated)) {
            val reason = "manifest publication failed; prior manifest remains authoritative"
            return TransactionOutcome.Rejected(
                reason,
                candidateOpenState = candidateOpenState,
            )
        }
        return TransactionOutcome.Committed(
            updated,
            generationId,
            candidateOpenState = candidateOpenState,
        )
    }

    /**
     * Cancels a candidate: removes only candidate-owned metadata/files, keeps
     * the committed bundle (and its retained previous generation) untouched
     * (lifecycle contract §13). Files are reclaimed exclusively through store
     * reachability after the manifest update — never by name pattern alone.
     */
    fun cancelCandidate(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        generationId: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome {
        engine.staleManifestRejection(manifest)?.let { return TransactionOutcome.Rejected(it) }
        val page = manifest.pages[pageKey]
            ?: return TransactionOutcome.Rejected("page missing: pageKey=$pageKey")
        if (page.candidate?.generationId != generationId) {
            return TransactionOutcome.Rejected(
                "candidate mismatch: pageKey=$pageKey expected=$generationId actual=${page.candidate?.generationId}",
            )
        }
        fun isCandidateOwned(record: StageArtifactRecord?): Boolean =
            record != null && record.generationId == generationId

        val candidateFailureKeys = ArtifactStage.entries
            .filter { stage -> isCandidateOwned(page.stage(stage)) }
            .map { stage -> "$pageKey:${stage.name}" }

        val cleaned = page.copy(
            detection = page.detection.takeUnless(::isCandidateOwned),
            ocr = page.ocr.takeUnless(::isCandidateOwned),
            inpaint = page.inpaint.takeUnless(::isCandidateOwned),
            translation = page.translation.takeUnless(::isCandidateOwned),
            layout = page.layout.takeUnless(::isCandidateOwned),
            candidate = null,
            displayState = when {
                page.candidate?.priorDisplayState == PageDisplayState.TEXTLESS_COMPLETE -> PageDisplayState.TEXTLESS_COMPLETE
                page.displayState == PageDisplayState.TEXTLESS_COMPLETE -> PageDisplayState.TEXTLESS_COMPLETE
                page.committed != null -> PageDisplayState.DISPLAY_READY
                else -> PageDisplayState.ORIGINAL_ONLY
            },
            pageVersion = page.pageVersion + 1,
        )

        val updated = manifest.copy(
            pages = manifest.pages + (pageKey to cleaned),
            activeCandidateGenerationIds = manifest.activeCandidateGenerationIds - generationId,
            durableFailures = manifest.durableFailures - candidateFailureKeys.toSet(),
            updatedAtEpochMs = nowEpochMs,
        )
        if (!engine.publishManifestInternal(updated)) {
            val reason = "manifest publication failed; candidate remains recorded"
            return TransactionOutcome.Rejected(reason)
        }
        // Delete only the known files unlinked by this cancellation; a full
        // SAF sweep per page made relaunch slow on large chapters. Cross-page
        // orphans wait for the deferred event-driven retention sweep.
        val orphans = buildList {
            listOf(page.detection, page.ocr, page.inpaint, page.translation, page.layout)
                .filter { it?.generationId == generationId }
                .forEach { it?.artifactFileName?.let(::add) }
        }
        val retention = engine.deleteKnownOrphans(orphans, durableManifest = updated)
        return TransactionOutcome.Committed(updated, generationId, retention.deletedNames)
    }

    private fun candidateWriteRejection(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        generationId: String,
        expectedPageVersion: Long,
        expectedDependencyFingerprint: String,
    ): String? {
        engine.staleManifestRejection(manifest)?.let { return it }
        val page = manifest.pages[pageKey] ?: return "page missing: pageKey=$pageKey"
        if (page.candidate?.generationId != generationId) {
            return "candidate mismatch: pageKey=$pageKey expected=$generationId actual=${page.candidate?.generationId}"
        }
        if (page.pageVersion != expectedPageVersion) {
            return "stale page version: pageKey=$pageKey expected=$expectedPageVersion actual=${page.pageVersion}"
        }
        if (page.candidate?.dependencyFingerprint != expectedDependencyFingerprint) {
            return "dependency fingerprint changed: pageKey=$pageKey"
        }
        return null
    }

    /**
     * Process-death recovery (lifecycle contract §13): a stage persisted as
     * RUNNING without a complete commit marker becomes retryable. Committed
     * pointers are never touched.
     */
    internal fun recoverInterruptedStages(manifest: ChapterArtifactManifest): ChapterArtifactManifest {
        var changed = false
        val now = System.currentTimeMillis()
        val durableFailures = manifest.durableFailures.toMutableMap()
        val pages = manifest.pages.mapValues { (pageKey, page) ->
            var updated = page
            ArtifactStage.entries.forEach { stage ->
                val record = updated.stage(stage)
                if (record?.status == ArtifactStageStatus.RUNNING) {
                    changed = true
                    updated = updated.withStage(
                        stage,
                        record.copy(
                            status = ArtifactStageStatus.FAILED_RETRYABLE,
                            updatedAtEpochMs = now,
                        ),
                    )
                    durableFailures["$pageKey:${stage.name}"] = DurableFailureMetadata(
                        pageKey = pageKey,
                        stage = stage,
                        status = ArtifactStageStatus.FAILED_RETRYABLE,
                        category = FailureCategory.LEGACY_UNKNOWN,
                        retryCount = 0,
                        lastFailureMessage = "process interrupted",
                        lastFailedAtEpochMs = now,
                        nextEligibleRetryAtEpochMs = now,
                        failureFingerprint = record.fingerprint,
                    )
                }
            }
            if (updated !== page) updated.copy(pageVersion = page.pageVersion + 1) else updated
        }
        if (!changed) return manifest
        val recovered = manifest.copy(
            pages = pages,
            durableFailures = durableFailures,
            updatedAtEpochMs = now,
        )
        if (!engine.publishManifestInternal(recovered)) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT interrupted-stage recovery publish failed; prior manifest retained: " +
                    "chapter=${layout.chapterKey}"
            }
            return manifest
        }
        return recovered
    }

    private fun PageArtifactRecord.withStage(
        stage: ArtifactStage,
        record: StageArtifactRecord?,
    ): PageArtifactRecord = when (stage) {
        ArtifactStage.DETECTION -> copy(detection = record)
        ArtifactStage.OCR -> copy(ocr = record)
        ArtifactStage.INPAINT -> copy(inpaint = record)
        ArtifactStage.TRANSLATION -> copy(translation = record)
        ArtifactStage.LAYOUT -> copy(layout = record)
    }
}
