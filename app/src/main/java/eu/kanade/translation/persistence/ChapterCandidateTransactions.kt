package eu.kanade.translation.persistence

import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PublishedPageTranslation
import eu.kanade.translation.model.StageFingerprints
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.toDraft
import eu.kanade.translation.model.toPublishedPage
import eu.kanade.translation.persistence.ChapterArtifactEngine.IncompletePublicationReceipt
import eu.kanade.translation.persistence.ChapterArtifactEngine.SidecarPublication
import eu.kanade.translation.persistence.ChapterArtifactEngine.TransactionOutcome
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.persistence.ChapterArtifactEngine.DestructiveManifestReceipt
import kotlinx.serialization.json.JsonObject
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

    /**
     * Persists the mutable live candidate in its own immutable sidecar and
     * advances only the candidate pointer. The committed pointer is untouched
     * until [promoteLiveCandidate] succeeds.
     *
     *   /  a stale-manifest CAS rejection (the leading
     * [candidateWriteRejection] check) triggers ONE retry against the freshly
     * re-read durable manifest — this is the store transaction behind the
     * façade's stage-patch/candidate persistence, and the batch resume path
     * must not surface a spurious ARTIFACT_PUBLICATION_FAILED whole-batch
     * abort on a healthy chapter. The whole transaction re-runs against the
     * FRESH manifest, so genuine drift still rejects with its real reason.
     * Every other rejection reason is returned as-is.
     * [persistLiveCandidateAndFailure] delegates here and inherits the retry.
     */
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
    ): TransactionOutcome = engine.retryOnStaleManifest<TransactionOutcome>(
        firstAttempt = persistLiveCandidateOnce(
            manifest,
            pageKey,
            generationId,
            expectedPageVersion,
            expectedDependencyFingerprint,
            pageSnapshot,
            origin,
            sourceIdentity,
            nowEpochMs,
            durableFailure,
        ),
        callerManifest = manifest,
        seam = "persistLiveCandidate",
    ) { fresh ->
        persistLiveCandidateOnce(
            fresh,
            pageKey,
            generationId,
            expectedPageVersion,
            expectedDependencyFingerprint,
            pageSnapshot,
            origin,
            sourceIdentity,
            nowEpochMs,
            durableFailure,
        )
    }

    private fun persistLiveCandidateOnce(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        generationId: String,
        expectedPageVersion: Long,
        expectedDependencyFingerprint: String,
        pageSnapshot: PageTranslation,
        origin: ArtifactOrigin,
        sourceIdentity: SourceIdentity?,
        nowEpochMs: Long,
        durableFailure: DurableFailureMetadata?,
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
                // Generation-less by intent: a durable failure is a page-level
                // ledger fact, not candidate work product. Candidate stamping
                // would make the NEXT attempt's teardown (cancelCandidate strips
                // candidate-owned records and their ledger keys) erase the
                // previous attempt's entry before the consecutive-unresolved
                // count is charged — the attempt cap could never advance across
                // attempts or restarts. Success-path cleanup stays explicit.
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
            return TransactionOutcome.Rejected(
                reason,
                incompletePublication = IncompletePublicationReceipt(
                    baselineManifest = manifest,
                    actualAdvancedManifest = engine.readManifest(),
                    advancedFiles = listOf(fileName),
                    failureReason = reason,
                ),
            )
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

    /**
     * Commits the complete live candidate page and atomically moves the
     * committed pointer. Both snapshots are published before the manifest
     * pointer changes, so a crash can only leave an orphan candidate file,
     * never a committed pointer to a missing or partial page.
     *
     *   /  a stale-manifest CAS rejection (the leading
     * [candidateWriteRejection] check) triggers ONE retry against the freshly
     * re-read durable manifest — the batch resume's candidate-promotion
     * transaction must not surface a spurious ARTIFACT_PUBLICATION_FAILED
     * whole-batch abort on a healthy chapter. The whole transaction re-runs
     * against the FRESH manifest, so genuine drift still rejects with its real
     * reason. Every other rejection reason is returned as-is.
     */
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
        seam = "promoteLiveCandidate",
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
        seam = "promoteLiveCandidateImmediately",
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
        seam: String,
    ): TransactionOutcome = engine.retryOnStaleManifest<TransactionOutcome>(
        firstAttempt = promoteLiveCandidateOnce(
            manifest,
            pageKey,
            generationId,
            expectedPageVersion,
            expectedDependencyFingerprint,
            pageSnapshot,
            origin,
            sourceIdentity,
            nowEpochMs,
            persistCandidateSnapshot,
        ),
        callerManifest = manifest,
        seam = seam,
    ) { fresh ->
        promoteLiveCandidateOnce(
            fresh,
            pageKey,
            generationId,
            expectedPageVersion,
            expectedDependencyFingerprint,
            pageSnapshot,
            origin,
            sourceIdentity,
            nowEpochMs,
            persistCandidateSnapshot,
        )
    }

    private fun promoteLiveCandidateOnce(
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
        // P3A: files durably written by this attempt, carried as orphan
        // evidence when the checked manifest commit does not complete.
        val advancedFiles = mutableListOf<String>()
        fun incompleteReceipt(failureReason: String): IncompletePublicationReceipt = IncompletePublicationReceipt(
            baselineManifest = manifest,
            actualAdvancedManifest = engine.readManifest(),
            advancedFiles = advancedFiles.toList(),
            failureReason = failureReason,
        )
        fun rejectAndRestoreCandidate(reason: String): TransactionOutcome.Rejected {
            if (persistCandidateSnapshot) {
                return TransactionOutcome.Rejected(reason, incompletePublication = incompleteReceipt(reason))
            }

            val candidateFile = candidate.pageSnapshotFileName
                ?: layout.candidatePageSnapshotFile(pageKey, generationId)
            val candidateFingerprint = StageFingerprints.pageSnapshot(pageSnapshot)
            if (!documents.publishJson(candidateFile, pageSnapshot.detachedCopy())) {
                return TransactionOutcome.Rejected(
                    "$reason; recovery candidate snapshot publication failed: pageKey=$pageKey",
                    incompletePublication = incompleteReceipt(
                        "$reason; recovery candidate snapshot publication failed",
                    ),
                )
            }
            advancedFiles += candidateFile
            val recoverablePage = page.copy(
                source = resolvedPage.source,
                candidate = candidate.copy(
                    pageSnapshotFileName = candidateFile,
                    pageSnapshotFingerprint = candidateFingerprint,
                ),
                pageVersion = page.pageVersion + 1,
            )
            val recoverableManifest = manifest.copy(
                pages = manifest.pages + (pageKey to recoverablePage),
                updatedAtEpochMs = nowEpochMs,
            )
            if (!engine.publishManifestInternal(recoverableManifest)) {
                return TransactionOutcome.Rejected(
                    "$reason; recovery candidate manifest publication failed: pageKey=$pageKey",
                    incompletePublication = incompleteReceipt(reason),
                )
            }
            return TransactionOutcome.Rejected(
                reason = reason,
                recoverableCandidateManifest = recoverableManifest,
                incompletePublication = incompleteReceipt(reason).copy(
                    actualAdvancedManifest = recoverableManifest,
                    recoverableCandidateManifest = recoverableManifest,
                ),
            )
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
            advancedFiles += candidateFile
        }
        val committedFile = layout.committedPageSnapshotFile(pageKey, generationId)
        if (!documents.publishJson(committedFile, pageSnapshot.detachedCopy())) {
            return rejectAndRestoreCandidate("committed page snapshot publication failed: pageKey=$pageKey")
        }
        advancedFiles += committedFile
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
            status = if (pageSnapshot.isTextlessTerminal) {
                ArtifactStageStatus.TEXTLESS
            } else {
                ArtifactStageStatus.READY
            },
            fingerprint = pageSnapshotFingerprint,
            origin = origin,
            generationId = null,
            artifactFileName = committedFile,
            updatedAtEpochMs = nowEpochMs,
        ) ?: StageArtifactRecord(
            status = if (pageSnapshot.isTextlessTerminal) {
                ArtifactStageStatus.TEXTLESS
            } else {
                ArtifactStageStatus.READY
            },
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
                    // The legacy two-step path persisted the candidate (+1) before promotion
                    // (+1). Preserve that version transition while omitting the intermediate
                    // candidate snapshot and manifest publication.
                    pageVersion = page.pageVersion + if (persistCandidateSnapshot) 1 else 2,
                )
                ),
            activeCandidateGenerationIds = manifest.activeCandidateGenerationIds - generationId,
            durableFailures = manifest.durableFailures - "$pageKey:${ArtifactStage.TRANSLATION.name}",
            updatedAtEpochMs = nowEpochMs,
        )
        if (!engine.publishManifestInternal(updated)) {
            return rejectAndRestoreCandidate("manifest publication failed; committed pointer unchanged")
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
        // P3A: a user reset is destructive — its completion must leave a
        // verified same-state backup so a later backup recovery cannot
        // resurrect the removed committed/candidate pointers.
        val advancedFiles = emptyList<String>()
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
                incompletePublication = advancedFiles.takeIf { it.isNotEmpty() }?.let {
                    IncompletePublicationReceipt(
                        baselineManifest = manifest,
                        actualAdvancedManifest = engine.readManifest(),
                        advancedFiles = it,
                        failureReason = receipt.reason,
                    )
                },
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
        // P3A: page removal is destructive — same-state backup requirement
        // mirrors [demoteLivePage]. Nothing file-level advances before the
        // manifest publication here, so a refusal is an unchanged rejection.
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

    /**
     * Opens (or idempotently reopens) a candidate generation for one page.
     * This records the candidate in the artifact manifest. Preconditions bind
     * the caller to the current page version and dependency fingerprint (stale
     * workers are rejected).
     *
     *   /  a stale-manifest CAS rejection (the >8-page open
     * path's background health verify republishing after the façade cached its
     * copy) triggers ONE retry against the freshly re-read durable manifest —
     * the batch resume's first candidate open must not surface a spurious
     * ARTIFACT_PUBLICATION_FAILED whole-batch abort on a healthy chapter. The
     * whole transaction (identity checks included) re-runs against the FRESH
     * manifest, so genuine drift still rejects — with a non-stale reason, on
     * the retry attempt. Every other rejection reason is returned as-is.
     */
    fun openCandidate(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        origin: ArtifactOrigin,
        expectedPageVersion: Long,
        dependencyFingerprint: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome = engine.retryOnStaleManifest<TransactionOutcome>(
        firstAttempt = openCandidateOnce(
            manifest,
            pageKey,
            origin,
            expectedPageVersion,
            dependencyFingerprint,
            nowEpochMs,
        ),
        callerManifest = manifest,
        seam = "openCandidate",
    ) { fresh ->
        openCandidateOnce(
            fresh,
            pageKey,
            origin,
            expectedPageVersion,
            dependencyFingerprint,
            nowEpochMs,
        )
    }

    private fun openCandidateOnce(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        origin: ArtifactOrigin,
        expectedPageVersion: Long,
        dependencyFingerprint: String,
        nowEpochMs: Long,
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
                incompletePublication = IncompletePublicationReceipt(
                    baselineManifest = manifest,
                    actualAdvancedManifest = engine.readManifest(),
                    advancedFiles = emptyList(),
                    failureReason = reason,
                ),
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
            return TransactionOutcome.Rejected(
                reason,
                incompletePublication = IncompletePublicationReceipt(
                    baselineManifest = manifest,
                    actualAdvancedManifest = engine.readManifest(),
                    advancedFiles = emptyList(),
                    failureReason = reason,
                ),
            )
        }
        // Delete only the known files unlinked by this cancellation. A full
        // SAF tree sweep per page made relaunch slow on large chapters;
        // cross-page orphans stay with the chapter-boundary sweep.
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
