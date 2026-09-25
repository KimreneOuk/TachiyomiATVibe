package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.artifact.ArtifactStage
import eu.kanade.translation.artifact.ArtifactStageStatus
import eu.kanade.translation.artifact.DurableFailureMetadata
import eu.kanade.translation.artifact.FailureCategory
import eu.kanade.translation.model.BatchExpectedFingerprints
import eu.kanade.translation.model.BatchStage
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.recordAttemptFailure
import eu.kanade.translation.pipeline.LeaseAcquisition
import eu.kanade.translation.pipeline.PageWriteOrigin
import eu.kanade.translation.storage.ChapterTranslationStore
import eu.kanade.translation.translator.ProviderFailure
import eu.kanade.translation.translator.ProviderFailureKind
import java.util.concurrent.ConcurrentHashMap

//   ChapterTranslationStore's lease-fence rejection reason (emitted by
// pageWriteRejection for every guarded write). The only string that proves the
// cached token no longer matches the lease table; absent-lease rejects carry
// different reasons ("page lease required") and are never healed.
private const val LEASE_TOKEN_CHANGED = "page lease token changed"

private fun leaseStageFor(stage: BatchStage?): PageStage = when (stage) {
    BatchStage.DETECTION, BatchStage.OCR, null -> PageStage.Ocr
    BatchStage.TRANSLATION -> PageStage.Translation
    BatchStage.INPAINT -> PageStage.Inpaint
    BatchStage.LAYOUT -> PageStage.Render
}

//  Phase 20.2: moved verbatim from TranslationPipeline.kt with the batch
// write gate (its only caller, `persistAiFailure`).
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

//  Phase 20.2: moved verbatim from TranslationPipeline (was a private nested
// data class; `internal` top-level keeps the same module-scoped reachability).
internal data class BatchWriteIdentity(
    val generation: Long,
    var pageVersion: Long,
    val leaseToken: Long,
    var candidateGenerationId: String?,
    var dependencyFingerprint: String?,
    var artifactPageVersion: Long?,
)

/**
 *  Phase 20.2: the batch write gate moved verbatim from
 * `TranslationPipeline.translateBatch` ( phase 20). Owns the per-page
 * lease/identity bookkeeping (`batchWriteIdentities`) and every guarded durable
 * write the batch path performs. The identity map and the durable-failure page
 * set are the SAME instances the batch shell holds (shared state, injected);
 * pipeline-provided collaborators arrive as constructor lambdas.
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
) {

    // Same-name wiring for the injected pipeline collaborator: the moved body
    // calls it with a named argument, which a function-typed value cannot serve.
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
        var identity = batchWriteIdentities[pageKey]
            ?: return ChapterTranslationStore.PatchResult.Rejected("batch page lease missing")
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
        if (result is ChapterTranslationStore.PatchResult.Rejected) {
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
                refreshBatchIdentity(pageKey, live)
                result = store.updatePageGuarded(
                    pageKey = pageKey,
                    expected = expected(),
                    description = description,
                    update = { current -> stampBatchProvenance(update(current), stage) },
                )
            } else if (result.reason == LEASE_TOKEN_CHANGED) {
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
                val granted = when (
                    val reAcquired = store.tryAcquirePageStageLease(
                        pageKey,
                        leaseStageFor(stage),
                        PageWriteOrigin.BATCH,
                    )
                ) {
                    is LeaseAcquisition.Granted -> reAcquired.lease
                    is LeaseAcquisition.Denied -> null
                }
                if (granted != null &&
                    granted.generation == identity.generation &&
                    granted.candidateGenerationId == identity.candidateGenerationId
                ) {
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
                }
            }
        }
        if (result is ChapterTranslationStore.PatchResult.Accepted) {
            identity.pageVersion = result.snapshot.pageVersion
            identity.candidateGenerationId = result.snapshot.candidateGenerationId
            identity.dependencyFingerprint = result.snapshot.dependencyFingerprint
            identity.artifactPageVersion = result.snapshot.artifactPageVersion
        }
        return result
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
