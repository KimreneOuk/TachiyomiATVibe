package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.diagnostics.BatchDiagnosticStage
import eu.kanade.translation.diagnostics.TranslationTraceLeaseKind
import eu.kanade.translation.diagnostics.TranslationTraceSite
import eu.kanade.translation.engines.translator.ProviderFailure
import eu.kanade.translation.engines.translator.ProviderFailureKind
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.recordAttemptFailure
import eu.kanade.translation.persistence.artifact.ArtifactStage
import eu.kanade.translation.persistence.artifact.ArtifactStageStatus
import eu.kanade.translation.persistence.artifact.DurableFailureMetadata
import eu.kanade.translation.persistence.artifact.FailureCategory
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.LeaseAcquisition
import eu.kanade.translation.persistence.chapter.PageStageLease
import eu.kanade.translation.persistence.chapter.PageWriteOrigin
import eu.kanade.translation.persistence.internal.formatWriteDiagnostic
import eu.kanade.translation.pipeline.planning.BatchExpectedFingerprints
import eu.kanade.translation.pipeline.planning.BatchStage
import java.util.concurrent.ConcurrentHashMap

//   ChapterTranslationStore's legacy lease-fence rejection reason. Newer
// stores also expose a typed expected/actual mismatch detail.
private const val LEASE_TOKEN_CHANGED = "page lease token changed"

private fun ChapterTranslationStore.PatchResult.Rejected.isLeaseTokenMismatch(): Boolean =
    reason == LEASE_TOKEN_CHANGED ||
        detail is ChapterTranslationStore.PatchResult.Rejected.Detail.PageLeaseTokenMismatch

private fun ChapterTranslationStore.PatchResult.Rejected.isBatchPageLeaseMissing(): Boolean =
    detail is ChapterTranslationStore.PatchResult.Rejected.Detail.BatchPageLeaseMissing

private fun BatchWriteIdentity.matchesRun(lease: PageStageLease): Boolean =
    generation == lease.generation && candidateGenerationId == lease.candidateGenerationId

private fun ChapterTranslationStore.PageSnapshot.matchesRun(lease: PageStageLease): Boolean =
    generation == lease.generation && candidateGenerationId == lease.candidateGenerationId

private suspend fun ChapterTranslationStore.acquireBatchPageLease(
    pageKey: String,
    stage: PageStage,
    onlyIfUnowned: Boolean = false,
    pageTraceRegistry: BatchPageTraceRegistry? = null,
): LeaseAcquisition {
    val acquire = suspend {
        if (onlyIfUnowned) {
            tryAcquirePageStageLeaseIfUnowned(pageKey, stage, PageWriteOrigin.BATCH)
        } else {
            tryAcquirePageStageLease(pageKey, stage, PageWriteOrigin.BATCH)
        }
    }
    val leaseKind = when (stage) {
        PageStage.Ocr -> TranslationTraceLeaseKind.OCR
        PageStage.Translation -> TranslationTraceLeaseKind.TRANSLATION
        PageStage.Inpaint -> TranslationTraceLeaseKind.INPAINT
        PageStage.Render -> TranslationTraceLeaseKind.RENDER
    }
    return pageTraceRegistry?.withLeaseWait(
        pageKey = pageKey,
        site = TranslationTraceSite.BATCH_WRITE_GATE,
        leaseKind = leaseKind,
        block = acquire,
    ) ?: acquire()
}

private fun leaseStageFor(stage: BatchStage?): PageStage = when (stage) {
    BatchStage.DETECTION, BatchStage.OCR, null -> PageStage.Ocr
    BatchStage.TRANSLATION -> PageStage.Translation
    BatchStage.INPAINT -> PageStage.Inpaint
    BatchStage.LAYOUT -> PageStage.Render
}

// Maps provider failure types to the persisted batch failure categories.
private fun ProviderFailure.toFailureCategory(): FailureCategory = when (kind) {
    ProviderFailureKind.NETWORK,
    ProviderFailureKind.RATE_LIMIT,
    ProviderFailureKind.QUOTA_EXHAUSTED,
    ProviderFailureKind.SERVER,
    -> FailureCategory.TRANSIENT
    ProviderFailureKind.REFUSAL -> FailureCategory.PROVIDER_REFUSAL
    ProviderFailureKind.AUTHENTICATION,
    ProviderFailureKind.CONFIGURATION,
    -> FailureCategory.CONFIGURATION
    ProviderFailureKind.SOURCE -> FailureCategory.SOURCE
    ProviderFailureKind.PROTOCOL -> FailureCategory.PROTOCOL
}

internal data class BatchWriteIdentity(
    val generation: Long,
    var pageVersion: Long,
    val leaseToken: Long,
    var candidateGenerationId: String?,
    var dependencyFingerprint: String?,
    var artifactPageVersion: Long?,
)

internal sealed interface BatchTranslationPublication {
    data class Committed(val snapshot: ChapterTranslationStore.PageSnapshot) : BatchTranslationPublication
    data class Superseded(val snapshot: ChapterTranslationStore.PageSnapshot) : BatchTranslationPublication
    data class Rebased(val snapshot: ChapterTranslationStore.PageSnapshot) : BatchTranslationPublication
    data class Deferred(val reason: String) : BatchTranslationPublication
    data class Failed(val reason: String) : BatchTranslationPublication
}

internal fun classifyTranslationPublicationRejection(
    rejection: ChapterTranslationStore.PatchResult.Rejected,
): BatchTranslationPublication =
    when (rejection.detail) {
        ChapterTranslationStore.PatchResult.Rejected.Detail.ArtifactPublicationFailed ->
            BatchTranslationPublication.Failed(rejection.reason)
        else -> BatchTranslationPublication.Deferred(rejection.reason)
    }

/**
 * Owns per-page lease and identity bookkeeping and every guarded durable write
 * performed by the batch path. The shell shares the identity map and durable
 * failure set with this gate; page persistence and lease release are supplied
 * by the pipeline.
 */
internal class BatchWriteGate(
    private val store: ChapterTranslationStore,
    private val batchWriteIdentities: ConcurrentHashMap<String, BatchWriteIdentity>,
    private val durableFailurePageKeys: MutableSet<String>,
    private val expectedBatchFingerprints: BatchExpectedFingerprints,
    private val stampBatchProvenance: (PageTranslation, BatchStage?) -> PageTranslation,
    private val releaseBatchPageLeaseFn: suspend (ChapterTranslationStore, String) -> Unit,
    private val persistPageWithOomRecoveryFn: suspend (
        ChapterTranslationStore,
        String,
        PageTranslation,
        ChapterTranslationStore.PatchPrecondition?,
    ) -> ChapterTranslationStore.PatchResult,
    private val pageTraceRegistry: BatchPageTraceRegistry? = null,
    private val beforeSameLeaseRetryHook: (suspend (String, ChapterTranslationStore.PageSnapshot) -> Unit)? = null,
) {

    // Preserve named arguments at batch call sites; the injected callback is a
    // function type and does not expose parameter names.
    private suspend fun persistPageWithOomRecovery(
        store: ChapterTranslationStore,
        fileName: String,
        pageTranslation: PageTranslation,
        expectedPrecondition: ChapterTranslationStore.PatchPrecondition?,
    ): ChapterTranslationStore.PatchResult = persistPageWithOomRecoveryFn(
        store,
        fileName,
        pageTranslation,
        expectedPrecondition,
    )

    private suspend fun releaseBatchPageLease(store: ChapterTranslationStore, pageKey: String) =
        releaseBatchPageLeaseFn(store, pageKey)

    suspend fun guardedBatchUpdate(
        pageKey: String,
        description: String,
        stage: BatchStage?,
        update: (PageTranslation?) -> PageTranslation,
    ): ChapterTranslationStore.PatchResult {
        val initialIdentity = batchWriteIdentities[pageKey]
        val missingIdentity = ChapterTranslationStore.PatchResult.Rejected(
            "batch page lease missing",
            ChapterTranslationStore.PatchResult.Rejected.Detail.BatchPageLeaseMissing,
        )
        var identity: BatchWriteIdentity
        var leaseTokenMismatchHeal = "not attempted"
        if (initialIdentity == null) {
            val live = store.snapshot(pageKey)
            val leaseOwner = store.pageLeaseOwner(pageKey)
            store.recordBatchWriteGateRejectionDiagnostic(
                formatWriteDiagnostic(
                    pageKey,
                    "description" to description,
                    "stage" to stage,
                    "result" to missingIdentity.reason,
                    "leaseOwner" to leaseOwner,
                    "live" to "{generation=${live.generation}, " +
                        "pageVersion=${live.pageVersion}, leaseToken=${live.leaseToken}, " +
                        "candidateGenerationId=${live.candidateGenerationId}, " +
                        "dependencyFingerprint=${live.dependencyFingerprint}, " +
                        "artifactPageVersion=${live.artifactPageVersion}}",
                ),
            )
            if (!missingIdentity.isBatchPageLeaseMissing() ||
                live.leaseToken != null ||
                leaseOwner != null
            ) {
                return missingIdentity
            }

            // The batch identity can be absent after a prior lane releases its
            // lease just before a sibling lane's last durable publication. A
            // null lease plus no lease-table owner proves there is no active
            // page writer to preempt. Re-acquire through the same BATCH owner
            // proof as the token-mismatch heal, then adopt only if the grant
            // still describes the same generation and candidate run. The
            // lease grant carries the current page/artifact versions.
            val granted = when (
                val acquisition = store.acquireBatchPageLease(
                    pageKey,
                    leaseStageFor(stage),
                    onlyIfUnowned = true,
                    pageTraceRegistry = pageTraceRegistry,
                )
            ) {
                is LeaseAcquisition.Granted -> acquisition.lease
                is LeaseAcquisition.Denied -> {
                    store.recordBatchWriteGateRejectionDiagnostic(
                        formatWriteDiagnostic(
                            pageKey,
                            "description" to description,
                            "result" to missingIdentity.reason,
                            "ownerProofDenied" to "{owner=${acquisition.owner}, reason=${acquisition.reason}}",
                            "live" to "{generation=${live.generation}, pageVersion=${live.pageVersion}, " +
                                "candidateGenerationId=${live.candidateGenerationId}}",
                        ),
                    )
                    return missingIdentity
                }
            }
            if (!live.matchesRun(granted)) {
                store.releasePageStageLeaseIfUnattached(
                    pageKey,
                    PageWriteOrigin.BATCH,
                    granted.token,
                )
                return missingIdentity
            }
            val reacquiredIdentity = BatchWriteIdentity(
                generation = granted.generation,
                pageVersion = granted.pageVersion,
                leaseToken = granted.token,
                candidateGenerationId = granted.candidateGenerationId,
                dependencyFingerprint = granted.dependencyFingerprint,
                artifactPageVersion = granted.artifactPageVersion,
            )
            val racedIdentity = batchWriteIdentities.putIfAbsent(pageKey, reacquiredIdentity)
            identity = racedIdentity ?: reacquiredIdentity
            if (racedIdentity != null && !racedIdentity.matchesRun(granted)) {
                store.releasePageStageLeaseIfUnattached(
                    pageKey,
                    PageWriteOrigin.BATCH,
                    granted.token,
                )
                return missingIdentity
            }
        } else {
            identity = initialIdentity
        }
        fun expected() = ChapterTranslationStore.PatchPrecondition(
            generation = identity.generation,
            pageVersion = identity.pageVersion,
            leaseToken = identity.leaseToken,
            candidateGenerationId = identity.candidateGenerationId,
            dependencyFingerprint = identity.dependencyFingerprint,
            artifactPageVersion = identity.artifactPageVersion,
        )
        var result = store.updatePageGuarded(
            pageKey = pageKey,
            expected = expected(),
            description = description,
            update = { current -> stampBatchProvenance(update(current), stage) },
        )
        var firstRejectionReason: String? = null
        if (result is ChapterTranslationStore.PatchResult.Rejected) {
            firstRejectionReason = result.reason
            //  device fix (Chapter-21 batch failure): the cached identity
            // can drift behind ungated store writes (reader stranded sweep,
            // OOM-recovery retries, reuse paths that skip the post-write
            // refresh). A precondition miss while the batch STILL HOLDS the
            // page lease is our own cache being stale, not a foreign writer:
            // re-sync from the live snapshot and retry ONCE. A lease-token or
            // generation mismatch keeps the rejection — the manual lane's
            // ownership fence  must never be preempted here.
            val live = store.snapshot(pageKey)
            if (live.generation == identity.generation && live.leaseToken == identity.leaseToken) {
                // Production leaves this null. Tests use it to hold the exact
                // snapshot/retry boundary while a lease changes under the gate.
                beforeSameLeaseRetryHook?.invoke(pageKey, live)
                refreshBatchIdentity(pageKey, live)
                result = store.updatePageGuarded(
                    pageKey = pageKey,
                    expected = expected(),
                    description = description,
                    update = { current -> stampBatchProvenance(update(current), stage) },
                )
            }
            // Reclassify after the optional stale-identity retry: that retry
            // can lose its lease after the refresh snapshot even when the
            // initial rejection was only a page-version/precondition drift.
            if (result is ChapterTranslationStore.PatchResult.Rejected && result.isLeaseTokenMismatch()) {
                //   owner-proof heal: the cached token no longer
                // matches the lease table (the   residual flip — a
                // sibling batch component re-minted the slot while this write
                // identity was in flight). Only the TABLE can prove who owns
                // the page now: a denied BATCH re-acquire is real contention
                // (a MANUAL/AUTO owner holds the slot — the rejection is kept,
                // and the lane's typed conversion surfaces it exactly as
                // today), and a grant whose run identity (generation +
                // candidateGenerationId) differs is a resumed/re-planned run —
                // never healed.
                when (
                    val acquisition = store.acquireBatchPageLease(
                        pageKey = pageKey,
                        stage = leaseStageFor(stage),
                        pageTraceRegistry = pageTraceRegistry,
                    )
                ) {
                    is LeaseAcquisition.Denied -> {
                        leaseTokenMismatchHeal =
                            "denied owner=${acquisition.owner} reason=${acquisition.reason}"
                    }
                    is LeaseAcquisition.Granted -> {
                        val granted = acquisition.lease
                        if (identity.matchesRun(granted)) {
                            // The table structurally proved BATCH ownership (a grant
                            // is impossible across origins) with THIS run's identity:
                            // re-arm from the GRANTED lease — never a bare snapshot —
                            // and retry once.
                            identity = identity.copy(
                                pageVersion = granted.pageVersion,
                                leaseToken = granted.token,
                                dependencyFingerprint = granted.dependencyFingerprint,
                                artifactPageVersion = granted.artifactPageVersion,
                            )
                            batchWriteIdentities[pageKey] = identity
                            result = store.updatePageGuarded(
                                pageKey = pageKey,
                                expected = expected(),
                                description = description,
                                update = { current -> stampBatchProvenance(update(current), stage) },
                            )
                            leaseTokenMismatchHeal =
                                "granted token=${granted.token} runMatched retryResult=$result"
                        } else {
                            leaseTokenMismatchHeal =
                                "granted token=${granted.token} runMismatch=" +
                                "expectedGeneration=${identity.generation}, " +
                                "expectedCandidate=${identity.candidateGenerationId}, " +
                                "actualGeneration=${granted.generation}, " +
                                "actualCandidate=${granted.candidateGenerationId}"
                        }
                    }
                }
            }
        }
        if (result is ChapterTranslationStore.PatchResult.Accepted) {
            identity.pageVersion = result.snapshot.pageVersion
            identity.candidateGenerationId = result.snapshot.candidateGenerationId
            identity.dependencyFingerprint = result.snapshot.dependencyFingerprint
            identity.artifactPageVersion = result.snapshot.artifactPageVersion
        } else if (result is ChapterTranslationStore.PatchResult.Rejected) {
            val live = store.snapshot(pageKey)
            store.recordBatchWriteGateRejectionDiagnostic(
                formatWriteDiagnostic(
                    pageKey,
                    "description" to description,
                    "stage" to stage,
                    "result" to result.reason,
                    "firstRejectionReason" to firstRejectionReason,
                    "expected" to "{generation=${identity.generation}, pageVersion=${identity.pageVersion}, " +
                        "leaseToken=${identity.leaseToken}, candidateGenerationId=${identity.candidateGenerationId}, " +
                        "dependencyFingerprint=${identity.dependencyFingerprint}, " +
                        "artifactPageVersion=${identity.artifactPageVersion}}",
                    "live" to "{generation=${live.generation}, pageVersion=${live.pageVersion}, " +
                        "leaseToken=${live.leaseToken}, candidateGenerationId=${live.candidateGenerationId}, " +
                        "dependencyFingerprint=${live.dependencyFingerprint}, " +
                        "artifactPageVersion=${live.artifactPageVersion}}",
                    "leaseTokenMismatchHeal" to leaseTokenMismatchHeal,
                ),
            )
        }
        return result
    }

    /**
     * Persists a completed standard translation and reconciles only a typed page-version race.
     * The ordinary write gate still owns the first write and its one strict same-token retry.
     */
    suspend fun publishTranslationCompletion(
        pageKey: String,
        candidate: PageTranslation,
    ): BatchTranslationPublication {
        val prepared = stampBatchProvenance(candidate.detachedCopy(), BatchStage.TRANSLATION).apply {
            translationError = null
        }
        val result = guardedBatchUpdate(
            pageKey = pageKey,
            description = "batch translation final commit",
            stage = BatchStage.TRANSLATION,
        ) { current ->
            (current ?: prepared.detachedCopy()).apply {
                translationStatus = prepared.translationStatus
                translationError = null
                blocks = prepared.blocks.map { it.detachedCopy() }.toMutableList()
                updatedAt = System.currentTimeMillis()
            }
        }
        if (result is ChapterTranslationStore.PatchResult.Accepted) {
            return BatchTranslationPublication.Committed(result.snapshot)
        }
        result as ChapterTranslationStore.PatchResult.Rejected
        if (result.detail !is ChapterTranslationStore.PatchResult.Rejected.Detail.PageVersionMismatch) {
            return classifyTranslationPublicationRejection(result)
        }

        val expected = batchWritePrecondition(pageKey)
            ?: return BatchTranslationPublication.Deferred("batch identity missing after version drift")
        return when (
            val reconciliation = store.reconcileBatchTranslationPageVersionDrift(
                pageKey = pageKey,
                expected = expected,
                candidate = prepared,
            )
        ) {
            is ChapterTranslationStore.TranslationPublicationReconciliation.AlreadyDurable -> {
                refreshBatchIdentity(pageKey, reconciliation.snapshot)
                BatchTranslationPublication.Superseded(reconciliation.snapshot)
            }
            is ChapterTranslationStore.TranslationPublicationReconciliation.Rebased -> {
                refreshBatchIdentity(pageKey, reconciliation.snapshot)
                BatchTranslationPublication.Rebased(reconciliation.snapshot)
            }
            is ChapterTranslationStore.TranslationPublicationReconciliation.Deferred ->
                BatchTranslationPublication.Deferred(reconciliation.reason)
            is ChapterTranslationStore.TranslationPublicationReconciliation.PublicationFailed ->
                BatchTranslationPublication.Deferred("rebase publication was not accepted: ${reconciliation.reason}")
        }
    }

    fun refreshBatchIdentity(pageKey: String, snapshot: ChapterTranslationStore.PageSnapshot) {
        batchWriteIdentities[pageKey]?.let { identity ->
            identity.pageVersion = snapshot.pageVersion
            identity.candidateGenerationId = snapshot.candidateGenerationId
            identity.dependencyFingerprint = snapshot.dependencyFingerprint
            identity.artifactPageVersion = snapshot.artifactPageVersion
        }
    }

    fun batchWritePrecondition(pageKey: String): ChapterTranslationStore.PatchPrecondition? =
        batchWriteIdentities[pageKey]?.let { identity ->
            ChapterTranslationStore.PatchPrecondition(
                generation = identity.generation,
                pageVersion = identity.pageVersion,
                leaseToken = identity.leaseToken,
                candidateGenerationId = identity.candidateGenerationId,
                dependencyFingerprint = identity.dependencyFingerprint,
                artifactPageVersion = identity.artifactPageVersion,
            )
        }

    suspend fun persistAiFailure(
        pageKey: String,
        page: PageTranslation,
        failure: ProviderFailure,
        retryable: Boolean,
        partialCandidate: Boolean,
        envelopeId: String?,
        missingBlockIds: Set<String>,
    ): ChapterTranslationStore.PatchResult {
        val expected = batchWritePrecondition(pageKey)
            ?: return ChapterTranslationStore.PatchResult.Rejected("batch page lease missing")
        val now = System.currentTimeMillis()
        val failureStatus = if (retryable) {
            ArtifactStageStatus.FAILED_RETRYABLE
        } else {
            ArtifactStageStatus.FAILED_TERMINAL
        }
        val liveStatus = if (retryable && partialCandidate) {
            StageStatus.PARTIAL
        } else {
            StageStatus.FAILED
        }
        val candidate = page.detachedCopy().apply {
            translationStatus = liveStatus
            translationError = failure.safeSummary
            errorMessage = failure.safeSummary
            recordAttemptFailure()
            updatedAt = now
        }
        val durable = DurableFailureMetadata(
            pageKey = pageKey,
            stage = ArtifactStage.TRANSLATION,
            status = failureStatus,
            category = failure.toFailureCategory(),
            retryCount = candidate.retryCount,
            lastFailureMessage = failure.safeSummary,
            lastFailedAtEpochMs = now,
            nextEligibleRetryAtEpochMs = failure.retryAfterAtEpochMs,
            failureFingerprint = expectedBatchFingerprints.translation,
            envelopeId = envelopeId,
            missingBlockIds = missingBlockIds,
        )
        val result = store.persistDurableStageFailure(
            pageKey = pageKey,
            expected = expected,
            failure = durable,
            description = "batch durable translation failure",
        ) { current ->
            (current ?: candidate).apply {
                blocks = candidate.blocks.toMutableList()
                translationStatus = candidate.translationStatus
                translationError = candidate.translationError
                errorMessage = candidate.errorMessage
                retryCount = candidate.retryCount
                attemptCount = candidate.attemptCount
                updatedAt = now
            }
        }
        if (result is ChapterTranslationStore.PatchResult.Accepted) {
            refreshBatchIdentity(pageKey, result.snapshot)
            durableFailurePageKeys += pageKey
        }
        return result
    }

    suspend fun persistAiFailureOrThrow(
        pageKey: String,
        page: PageTranslation,
        failure: ProviderFailure,
        retryable: Boolean,
        partialCandidate: Boolean,
        envelopeId: String?,
        missingBlockIds: Set<String>,
    ) {
        when (
            val result = persistAiFailure(
                pageKey = pageKey,
                page = page,
                failure = failure,
                retryable = retryable,
                partialCandidate = partialCandidate,
                envelopeId = envelopeId,
                missingBlockIds = missingBlockIds,
            )
        ) {
            is ChapterTranslationStore.PatchResult.Accepted -> Unit
            is ChapterTranslationStore.PatchResult.Rejected -> {
                throw BatchPersistenceRejectedException(
                    pageKey = pageKey,
                    stage = BatchDiagnosticStage.TRANSLATION,
                )
            }
        }
    }

    suspend fun releaseBatchLease(pageKey: String) {
        batchWriteIdentities.remove(pageKey)
        releaseBatchPageLeaseFn(store, pageKey)
    }

    suspend fun persistBatchPageWithOomRecovery(
        pageKey: String,
        pageTranslation: PageTranslation,
    ): ChapterTranslationStore.PatchResult {
        val expected = batchWritePrecondition(pageKey)
            ?: return ChapterTranslationStore.PatchResult.Rejected("batch page lease missing")
        val result = persistPageWithOomRecovery(
            store,
            pageKey,
            pageTranslation,
            expectedPrecondition = expected,
        )
        //  device fix: every accepted gate write must refresh the cached
        // identity — the OOM-recovery retry inside persistPageWithOomRecovery
        // can commit under a rewritten precondition, which would otherwise
        // leave this identity stale for the next guarded write.
        if (result is ChapterTranslationStore.PatchResult.Accepted) {
            refreshBatchIdentity(pageKey, result.snapshot)
        }
        return result
    }
}
