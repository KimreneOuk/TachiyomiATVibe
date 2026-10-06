package eu.kanade.translation.pipeline.batch.envelope

import eu.kanade.translation.context.ChapterContextService
import eu.kanade.translation.context.ContextRequest
import eu.kanade.translation.context.LaneCapability
import eu.kanade.translation.diagnostics.TranslationTraceLeaseKind
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceProvider
import eu.kanade.translation.diagnostics.TranslationTraceSite
import eu.kanade.translation.engines.translator.AdmissionPriority
import eu.kanade.translation.engines.translator.BatchRequestSublimitGate
import eu.kanade.translation.engines.translator.ProviderFailure
import eu.kanade.translation.engines.translator.ProviderFailureKind
import eu.kanade.translation.engines.translator.ProviderFailureRetryability
import eu.kanade.translation.engines.translator.ProviderRequestClock
import eu.kanade.translation.engines.translator.ProviderRequestKey
import eu.kanade.translation.engines.translator.ProviderRequestMetadata
import eu.kanade.translation.engines.translator.ProviderRequestPausedException
import eu.kanade.translation.engines.translator.SharedBatchRequestSublimitGate
import eu.kanade.translation.engines.translator.SystemProviderRequestClock
import eu.kanade.translation.engines.translator.contextual.ContextualRequestProtocol
import eu.kanade.translation.engines.translator.contextual.ContextualTextTranslator
import eu.kanade.translation.engines.translator.contextual.GlobalEnvelopePlanner
import eu.kanade.translation.engines.translator.contextual.TranslationContextChunk
import eu.kanade.translation.engines.translator.contextual.TranslationContextChunkPlanner
import eu.kanade.translation.engines.translator.contextual.TranslationResponseFaithfulness
import eu.kanade.translation.engines.translator.retry.AiChunkOutcome
import eu.kanade.translation.engines.translator.retry.AiTranslationRetryPolicy
import eu.kanade.translation.engines.translator.retry.MAX_EMPTY_GEMINI_RESPONSE_REISSUES
import eu.kanade.translation.engines.translator.retry.translateAiChunkWithAdaptiveRetry
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.recordAttemptFailure
import eu.kanade.translation.persistence.artifact.ArtifactStage
import eu.kanade.translation.persistence.artifact.ArtifactStageStatus
import eu.kanade.translation.persistence.artifact.AttemptOrigin
import eu.kanade.translation.persistence.artifact.DurableFailureMetadata
import eu.kanade.translation.persistence.artifact.EnvelopePlan
import eu.kanade.translation.persistence.artifact.FailureCategory
import eu.kanade.translation.persistence.artifact.PlannedEnvelope
import eu.kanade.translation.persistence.artifact.isSha256Hex
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.LeaseAcquisition
import eu.kanade.translation.persistence.chapter.PageWriteOrigin
import eu.kanade.translation.persistence.chapter.StagePatchResult
import eu.kanade.translation.persistence.chapter.TranslationBlockPatch
import eu.kanade.translation.persistence.chapter.TranslationStagePatch
import eu.kanade.translation.persistence.chapter.ocrBlockFingerprints
import eu.kanade.translation.persistence.chapter.ocrFingerprint
import eu.kanade.translation.pipeline.batch.BatchContextFrontier
import eu.kanade.translation.pipeline.batch.BatchPageTraceRegistry
import eu.kanade.translation.util.ShortHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Executes contextual translation envelopes serially for one chapter.
 *
 * Before dispatch, every affected page is revalidated under a newly acquired
 * BATCH lease against its page version, candidate generation, dependency
 * fingerprint, artifact version, OCR identity, and checkpoint. User edits
 * and manual-authoritative pages are preserved. Identity
 * drift triggers a deterministic re-plan of the undispatched suffix from fresh
 * store state; committed history is left intact.
 *
 * Requests use the shared retry machinery, batch sublimit, and provider
 * governor. Commits are page-atomic: a page advances only when every planned
 * block is accepted. Refusals discard the response and pause; protocol
 * failures keep complete pages, record missing pages as durable retryable
 * failures, and continue until the zero-commit breaker trips. Every commit
 * carries the envelope-plan fingerprint and full compare-and-set checks. A
 * rejected commit does not advance the rolling-context frontier or
 * progress. Durable page state allows a later process to re-plan pending work.
 */
internal class ProfileEnvelopeExecutor(
    private val store: ChapterTranslationStore,
    private val textTranslator: ContextualTextTranslator,
    /**
     * Deterministic suffix re-plan callback: rebuilds the pending set from
     * fresh store state, re-plans with the SAME pure planner, publishes the
     * superseding plan (SC-20) and returns [ReplanResult.Ready]; [ReplanResult.NothingPending]
     * when nothing translatable remains; [ReplanResult.Failed] for a typed
     * rebuild failure (drift/planner rejection — never silently drained).
     */
    private val replan: suspend (reason: String) -> ReplanResult,
    /** Batch sub-limit gate (DR-C/DR-D); overridable for tests. */
    private val sublimitGate: BatchRequestSublimitGate = SharedBatchRequestSublimitGate.instance,
    private val retryPolicy: AiTranslationRetryPolicy = AiTranslationRetryPolicy(),
    private val providerProfile: TranslationContextChunkPlanner.Profile =
        TranslationContextChunkPlanner.Profile.DEFAULT,
    private val clock: ProviderRequestClock = SystemProviderRequestClock,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    /** Runs after accepted page commits settle, with only those page keys. */
    private val onCommitSettled: suspend (List<String>) -> Unit = {},
    /** Advisory progress callback (per-envelope); the store stays authoritative. */
    private val onProgress: (Map<String, Int>) -> Unit = {},
    private val pageTraceRegistry: BatchPageTraceRegistry? = null,
) {

    /** Mutable run-of-phase counters (operational only, never fingerprinted). */
    data class Counters(
        var envelopesTotal: Int = 0,
        var envelopesDone: Int = 0,
        var envelopesSkipped: Int = 0,
        var envelopesPending: Int = 0,
        var envelopeFailures: Int = 0,
        var pagesTranslated: Int = 0,
        /**  protocol parking: pages durably FAILED for omitted blocks. */
        var pagesParked: Int = 0,
        var replans: Int = 0,
        /** Whole-page execution-time splits actually dispatched. */
        var envelopeSplits: Int = 0,
        /** Largest gap-free rolling-context page count carried. */
        var rollingContextPagesMax: Int = 0,
    ) {
        fun toMap(): Map<String, Int> = mapOf(
            "envelopesTotal" to envelopesTotal,
            "envelopesDone" to envelopesDone,
            "envelopesSkipped" to envelopesSkipped,
            "envelopesPending" to envelopesPending,
            "envelopeFailures" to envelopeFailures,
            "pagesTranslated" to pagesTranslated,
            "pagesParked" to pagesParked,
            "envelopeReplans" to replans,
            "envelopeSplits" to envelopeSplits,
            "rollingContextPagesMax" to rollingContextPagesMax,
        )
    }

    /** Typed terminal of the envelope phase ( never COMPLETE). */
    sealed interface PhaseOutcome {
        /** Every planned envelope dispatched, committed, or skipped. */
        data class Drained(val counters: Counters) : PhaseOutcome

        data class Paused(
            val counters: Counters,
            val reason: String,
            val anchorPageKey: String?,
            val failure: ProviderFailure?,
            val nextEligibleRetryAtEpochMs: Long?,
        ) : PhaseOutcome
    }

    private val counters = Counters()

    /**
     * Runs the serial envelope loop over [work]. Cancellation propagates;
     * leases held for the interrupted envelope are released before throwing.
     */
    suspend fun run(work: EnvelopeDispatchWork): PhaseOutcome {
        counters.envelopesTotal = work.plan.envelopes.size
        val frontier = buildFrontier(work)
        var current = work
        var index = 0
        var replansSinceProgress = 0
        //  protocol-parking circuit breaker: consecutive envelopes that
        // committed ZERO fully-covered pages under a PROTOCOL verdict. A
        // systematically broken provider must pause the batch (old behavior)
        // instead of parking an entire chapter page by page.
        var consecutiveZeroCommitEnvelopes = 0
        while (index < current.plan.envelopes.size) {
            currentCoroutineContext().ensureActive()
            yield() // reader-priority courtesy between provider envelopes
            val envelope = current.plan.envelopes[index]
            when (val result = dispatchEnvelope(envelope, current, frontier)) {
                is EnvelopeDispatchResult.Dispatched -> {
                    index++
                    replansSinceProgress = 0
                    counters.envelopesDone++
                    onProgress(counters.toMap())
                }
                is EnvelopeDispatchResult.Skipped -> {
                    index++
                    replansSinceProgress = 0
                    counters.envelopesSkipped++
                    counters.envelopesDone++
                    onProgress(counters.toMap())
                }
                is EnvelopeDispatchResult.PartiallyParked -> {
                    //  protocol parking: the envelope finished with a
                    // PROTOCOL verdict but is NOT a batch pause — fully-covered
                    // pages committed, stubborn pages are parked durably. The
                    // breaker only trips after
                    // [MAX_CONSECUTIVE_ZERO_COMMIT_ENVELOPES] envelopes with
                    // zero committed coverage (systematic provider garbage).
                    index++
                    replansSinceProgress = 0
                    counters.envelopesDone++
                    if (result.fullyCoveredCommitted == 0) {
                        consecutiveZeroCommitEnvelopes++
                    } else {
                        consecutiveZeroCommitEnvelopes = 0
                    }
                    if (consecutiveZeroCommitEnvelopes >= MAX_CONSECUTIVE_ZERO_COMMIT_ENVELOPES) {
                        counters.envelopeFailures++
                        counters.envelopesPending = current.plan.envelopes.size - index
                        onProgress(counters.toMap())
                        return PhaseOutcome.Paused(
                            counters = countersSnapshot(),
                            reason = "T924 envelope dispatch paused: " +
                                "$MAX_CONSECUTIVE_ZERO_COMMIT_ENVELOPES consecutive protocol " +
                                "envelopes committed zero fully-covered pages " +
                                "(systematic protocol failure; parked pages stay retryable)",
                            anchorPageKey = result.parkedPageKeys.firstOrNull(),
                            failure = result.failure,
                            nextEligibleRetryAtEpochMs = result.failure?.retryAfterAtEpochMs,
                        )
                    }
                    onProgress(counters.toMap())
                }
                is EnvelopeDispatchResult.ReplanNeeded -> {
                    replansSinceProgress++
                    if (replansSinceProgress > MAX_CONSECUTIVE_REPLANS) {
                        counters.envelopeFailures++
                        return PhaseOutcome.Paused(
                            counters = countersSnapshot(),
                            reason = "T924 envelope dispatch paused: revalidation kept drifting " +
                                "after $MAX_CONSECUTIVE_REPLANS re-plans (${result.reason})",
                            anchorPageKey = result.anchorPageKey,
                            failure = null,
                            nextEligibleRetryAtEpochMs = null,
                        )
                    }
                    counters.replans++
                    when (val next = replan(result.reason)) {
                        is ReplanResult.Ready -> {
                            counters.envelopesTotal =
                                counters.envelopesTotal - index + next.work.plan.envelopes.size
                            current = next.work
                            index = 0
                        }
                        is ReplanResult.NothingPending -> {
                            // Nothing translatable remains (all pending work
                            // resolved/removed meanwhile): the phase drains.
                            onProgress(counters.toMap())
                            return PhaseOutcome.Drained(countersSnapshot())
                        }
                        is ReplanResult.Failed -> {
                            counters.envelopeFailures++
                            return PhaseOutcome.Paused(
                                counters = countersSnapshot(),
                                reason = "T924 envelope re-plan failed: ${next.reason}",
                                anchorPageKey = result.anchorPageKey,
                                failure = null,
                                nextEligibleRetryAtEpochMs = null,
                            )
                        }
                    }
                    onProgress(counters.toMap())
                }
                is EnvelopeDispatchResult.Paused -> {
                    counters.envelopeFailures++
                    counters.envelopesPending = current.plan.envelopes.size - index
                    onProgress(counters.toMap())
                    return PhaseOutcome.Paused(
                        counters = countersSnapshot(),
                        reason = result.reason,
                        anchorPageKey = result.anchorPageKey,
                        failure = result.failure,
                        nextEligibleRetryAtEpochMs = result.nextEligibleRetryAtEpochMs,
                    )
                }
            }
        }
        counters.envelopesPending = 0
        onProgress(counters.toMap())
        return PhaseOutcome.Drained(countersSnapshot())
    }

    private fun countersSnapshot(): Counters = counters.copy()

    // ------------------------------------------------------------------
    //  per-envelope revalidation + dispatch
    // ------------------------------------------------------------------

    private sealed interface EnvelopeDispatchResult {
        /** Envelope fully processed (all pages committed). */
        data object Dispatched : EnvelopeDispatchResult

        /** Every affected page was dropped (committed/manual/user-edited): nothing to send. */
        data object Skipped : EnvelopeDispatchResult

        /**
         *  protocol parking: the envelope ended under a PROTOCOL-class
         * verdict; its fully-covered pages committed, its stubborn pages were
         * parked as durable retryable failures, and the batch CONTINUES.
         * [fullyCoveredCommitted] feeds the consecutive-zero-commit circuit
         * breaker in [run].
         */
        data class PartiallyParked(
            val fullyCoveredCommitted: Int,
            val parkedPageKeys: List<String>,
            val failure: ProviderFailure?,
        ) : EnvelopeDispatchResult

        /** Live store state drifted from the plan: deterministic suffix re-plan required. */
        data class ReplanNeeded(val reason: String, val anchorPageKey: String?) :
            EnvelopeDispatchResult

        data class Paused(
            val reason: String,
            val anchorPageKey: String?,
            val failure: ProviderFailure?,
            val nextEligibleRetryAtEpochMs: Long?,
        ) : EnvelopeDispatchResult
    }

    /** One page held for the in-flight envelope: revalidated live state + lease. */
    private class HeldPage(
        val pageKey: String,
        val work: PageDispatchWork,
        val livePage: PageTranslation,
        val snapshot: ChapterTranslationStore.PageSnapshot,
        val leaseToken: Long,
        val dispatchBlocks: List<PlannedBlock>,
    )

    private suspend fun dispatchEnvelope(
        envelope: PlannedEnvelope,
        work: EnvelopeDispatchWork,
        frontier: BatchContextFrontier,
    ): EnvelopeDispatchResult {
        val held = mutableListOf<HeldPage>()
        try {
            for (pageKey in envelope.orderedPageKeys) {
                val pageWork = work.pages[pageKey]
                    // The page may have been excluded between plan and dispatch
                    // by the replan path; treat as skipped.
                    ?: continue
                val lease = pageTraceRegistry?.withLeaseWait(
                    pageKey = pageKey,
                    site = TranslationTraceSite.BATCH_ENVELOPE_TRANSLATION,
                    leaseKind = TranslationTraceLeaseKind.TRANSLATION,
                ) {
                    store.tryAcquirePageStageLease(pageKey, PageStage.Translation, PageWriteOrigin.BATCH)
                } ?: store.tryAcquirePageStageLease(pageKey, PageStage.Translation, PageWriteOrigin.BATCH)
                val leaseToken = when (lease) {
                    is LeaseAcquisition.Granted -> lease.lease.token
                    is LeaseAcquisition.Denied -> {
                        // BATCH attaches/never preempts. A MANUAL
                        // owner wins this page; the run pauses (no preempt,
                        // no partial envelope) and resume revalidates.
                        return EnvelopeDispatchResult.Paused(
                            reason = "T924 envelope dispatch paused: page lease owned by " +
                                "${lease.owner ?: "another origin"} (${lease.reason})",
                            anchorPageKey = pageKey,
                            failure = null,
                            nextEligibleRetryAtEpochMs = null,
                        )
                    }
                }
                val snapshot = store.snapshot(pageKey)
                val live = snapshot.page
                if (live == null) {
                    // the page is not yet in `held`, so the
                    // just-acquired lease must be released before the early
                    // return — otherwise a MANUAL attempt on this page is
                    // denied for the whole replan window.
                    store.releasePageStageLeaseIfUnattached(pageKey, PageWriteOrigin.BATCH, leaseToken)
                    return replanOrPause("page $pageKey lost its live state", pageKey)
                }
                // .3 skip re-check: the page became committed /
                // manual-authoritative between plan and dispatch.
                if (pageAuthoritativelyDone(pageKey, live, pageWork.observedSourceFingerprint)) {
                    store.releasePageStageLeaseIfUnattached(pageKey, PageWriteOrigin.BATCH, leaseToken)
                    continue
                }
                // .2 live-revalidate the plan-time identities.
                val drift = revalidationDrift(pageWork, snapshot, live)
                if (drift != null) {
                    // same release-before-early-return as the
                    // lost-page path above.
                    store.releasePageStageLeaseIfUnattached(pageKey, PageWriteOrigin.BATCH, leaseToken)
                    return EnvelopeDispatchResult.ReplanNeeded(
                        reason = "page $pageKey drifted: $drift",
                        anchorPageKey = pageKey,
                    )
                }
                // .3 block-level skip: a block edited by the user since
                // the plan is authoritative — dropped from THIS dispatch
                // (never overwritten); the rest of the page still dispatches.
                val dispatchBlocks = pageWork.blocks.filter { planned ->
                    val liveBlock = live.blocks.getOrNull(planned.blockIndex)
                    liveBlock != null && liveBlock.userEditedAt == null
                }
                if (dispatchBlocks.isEmpty()) {
                    store.releasePageStageLeaseIfUnattached(pageKey, PageWriteOrigin.BATCH, leaseToken)
                    continue
                }
                held += HeldPage(
                    pageKey = pageKey,
                    work = pageWork,
                    livePage = live,
                    snapshot = snapshot,
                    leaseToken = leaseToken,
                    dispatchBlocks = dispatchBlocks,
                )
            }
            if (held.isEmpty()) {
                return EnvelopeDispatchResult.Skipped
            }

            // The gap-free rolling-context page count carried into prompts
            // (contiguous committed prefix only — the frontier never moves on
            // a rejected commit).
            counters.rollingContextPagesMax =
                maxOf(counters.rollingContextPagesMax, frontier.frontierIndex + 1)

            // Recompute token fit at dispatch time and split only at whole-page
            // boundaries.
            val initialHistory = prepareRollingHistory(held)
            val batches = splitForTokenFit(held, initialHistory.rollingContext)
            counters.envelopeSplits += (batches.fitted.size - 1).coerceAtLeast(0)

            // ---- Dispatch the fitted sub-batches SEQUENTIALLY ----
            // ---- (still hard one-in-flight: each returns before the next). ----
            var last: EnvelopeDispatchResult = EnvelopeDispatchResult.Dispatched
            batches.fitted.forEachIndexed { batchIndex, batch ->
                when (val result = dispatchSingleHeldBatch(envelope, batch, work, frontier, batchIndex)) {
                    is EnvelopeDispatchResult.Paused -> return result
                    else -> last = result
                }
            }

            // A single token-oversized page is REJECTED, never sent (page
            // atomicity invariant): pause after any fitted pages have committed.
            if (batches.oversized.isNotEmpty()) {
                return EnvelopeDispatchResult.Paused(
                    reason = "T924 envelope ${envelope.envelopeId} paused: " +
                        "${batches.oversized.size} page(s) token-oversized under the " +
                        "execution-time enriched-context recompute; page atomicity kept — " +
                        "the page(s) were NOT translated (native/render stages unaffected)",
                    anchorPageKey = batches.oversized.first().pageKey,
                    failure = ProviderFailure(
                        kind = ProviderFailureKind.PROTOCOL,
                        retryability = ProviderFailureRetryability.PAUSE,
                        safeSummary = "page token-oversized under execution-time recompute",
                        requestId = envelope.envelopeId,
                    ),
                    nextEligibleRetryAtEpochMs = null,
                )
            }
            return last
        } finally {
            // Any page still held (paused/replan paths) releases its lease.
            //   the release is attach-aware — a page the overlap
            // inpaint re-attached to (same token, mid-lane) KEEPS its record
            // so no sibling acquire can mint a fresh token and fail-close the
            // sibling's write identity; the sibling's own release clears it.
            held.forEach { page ->
                store.releasePageStageLeaseIfUnattached(page.pageKey, PageWriteOrigin.BATCH, page.leaseToken)
            }
        }
    }

    /**
     * Splits held pages into deterministic whole-page sub-batches whose
     * source lines, rolling history and estimated response fit the provider
     * window. History is always the shared durable-state construction; pages
     * are never split. Include estimated translation output in the reserve.
     */
    private fun splitForTokenFit(held: List<HeldPage>, rollingContext: String): SplitPlan {
        val constraints = TranslationContextChunkPlanner.constraintsFor(providerProfile)

        // The same finalized history is shared by all candidates in this
        // pre-dispatch fit pass. Each actual sub-batch rebuilds it after
        // earlier commits, so newly committed predecessors can then join.
        fun contextTokensFor(pages: List<HeldPage>): Int =
            if (rollingContext.isBlank()) 0 else TranslationContextChunkPlanner.estimateTokens(rollingContext)

        // Estimated provider output for the candidate batch's translations
        // (JSON block framing + target-text expansion, planner estimator).
        fun outputEstimateFor(pages: List<HeldPage>): Int =
            pages.sumOf { page ->
                page.dispatchBlocks.sumOf { block ->
                    GlobalEnvelopePlanner.estimateOutputTokens(block.sourceText)
                }
            }

        val fitted = ArrayList<List<HeldPage>>()
        val oversized = ArrayList<HeldPage>()
        var batch = ArrayList<HeldPage>()
        var batchLineTokens = 0
        fun flush() {
            if (batch.isEmpty()) return
            if (batch.size == 1) {
                val page = batch.single()
                val pageTokens = pageLineEstimate(page)
                val blocks = page.dispatchBlocks.size
                val available = promptAvailableTokens(constraints, blocks, pageCount = 1) -
                    outputEstimateFor(batch)
                if (pageTokens + contextTokensFor(batch) > available) {
                    oversized += page
                } else {
                    fitted += listOf(page)
                }
            } else {
                fitted += batch.toList()
            }
            batch = ArrayList()
            batchLineTokens = 0
        }
        for (page in held) {
            val pageTokens = pageLineEstimate(page)
            if (batch.isNotEmpty()) {
                val candidateBlocks = batch.sumOf { it.dispatchBlocks.size } + page.dispatchBlocks.size
                val candidatePages = batch + page
                val available = promptAvailableTokens(constraints, candidateBlocks, pageCount = candidatePages.size) -
                    outputEstimateFor(candidatePages)
                if (batchLineTokens + pageTokens + contextTokensFor(candidatePages) > available) {
                    flush()
                }
            }
            batch += page
            batchLineTokens += pageTokens
        }
        flush()
        return SplitPlan(fitted = fitted, oversized = oversized)
    }

    private class SplitPlan(
        val fitted: List<List<HeldPage>>,
        val oversized: List<HeldPage>,
    )

    /** Provider prompt budget for given structural shape (planner idiom). */
    private fun promptAvailableTokens(
        constraints: TranslationContextChunkPlanner.Constraints,
        blockCount: Int,
        pageCount: Int,
    ): Int = constraints.maxContextTokens - constraints.safetyMargin - constraints.minOutputTokens -
        TranslationContextChunkPlanner.batchResponseOverheadTokens(blockCount, pageCount)

    /** Source-line estimate for one page, mirroring the wire `id|text` lines. */
    private fun pageLineEstimate(page: HeldPage): Int =
        page.dispatchBlocks.sumOf { block -> estimateWireLineTokens(block.stableBlockId, block.sourceText) }

    private fun estimateWireLineTokens(stableBlockId: String, sourceText: String): Int =
        TranslationContextChunkPlanner.estimateTokens(
            stableBlockId + "|" + sourceText.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' '),
        )

    private fun prepareRollingHistory(held: List<HeldPage>) = ChapterContextService(store).prepare(
        ContextRequest(
            pageKeys = held.map { it.pageKey },
            targetLang = textTranslator.toLang.code,
            sourceLang = textTranslator.fromLang.code,
            profile = providerProfile,
            laneCapability = LaneCapability.PROFILE_BATCH,
        ),
    )

    /**
     * Dispatches ONE provider request over ONE (sub-)batch of held pages —
     * the hard one-envelope-in-flight unit. Chunk assembly uses committed
     * rolling history only. Classification and plan provenance commits follow
     * the existing batch contract.
     * Page leases stay held; [dispatchEnvelope]'s `finally` releases them.
     */
    private suspend fun dispatchSingleHeldBatch(
        envelope: PlannedEnvelope,
        batch: List<HeldPage>,
        work: EnvelopeDispatchWork,
        frontier: BatchContextFrontier,
        batchIndex: Int,
    ): EnvelopeDispatchResult {
        val held = batch // DR-A classification operates over exactly this batch
        // ---- Dispatch ONE provider envelope (hard one-in-flight invariant). ----
        val prepared = buildEnvelopeChunk(envelope, held)
        val constraints = TranslationContextChunkPlanner.constraintsFor(providerProfile)
        val protocolReserve = TranslationContextChunkPlanner.batchResponseOverheadTokens(
            prepared.chunk.blockCount,
            prepared.chunk.pages.size,
        )
        val availableOutput = constraints.maxContextTokens - constraints.safetyMargin -
            prepared.estimatedInputTokens - protocolReserve
        if (prepared.chunk.maxOutputTokens < constraints.minOutputTokens || availableOutput < constraints.minOutputTokens) {
            return EnvelopeDispatchResult.Paused(
                reason = "T924 envelope ${envelope.envelopeId} paused: " +
                    "unfulfillable token budget (available output $availableOutput < min ${constraints.minOutputTokens}, " +
                    "output cap ${prepared.chunk.maxOutputTokens}); prompt not shipped",
                anchorPageKey = held.firstOrNull()?.pageKey,
                failure = ProviderFailure(
                    kind = ProviderFailureKind.PROTOCOL,
                    retryability = ProviderFailureRetryability.PAUSE,
                    safeSummary = "unfulfillable token budget: available output tokens below minimum floor",
                    requestId = envelope.envelopeId,
                ),
                nextEligibleRetryAtEpochMs = null,
            )
        }
        val traceProvider = if (work.providerBackend.startsWith("lmstudio", ignoreCase = true)) {
            TranslationTraceProvider.LOCAL
        } else {
            TranslationTraceProvider.REMOTE
        }
        val traceRuns = held.mapNotNull { page -> pageTraceRegistry?.runForOrStart(page.pageKey) }
        val emptyResponseReissuesAlreadyUsed = held.mapNotNull { page ->
            store.durableFailure(page.pageKey)?.takeIf { failure ->
                failure.envelopeId == envelope.envelopeId
            }?.emptyGeminiResponseReissues
        }.maxOrNull() ?: 0
        val metadata = ProviderRequestMetadata(
            key = ProviderRequestKey(
                backend = work.providerBackend,
                model = work.providerModel,
                credentialScope = work.credentialScope,
            ),
            estimatedInputTokens = prepared.estimatedInputTokens,
            reservedOutputTokens = prepared.chunk.maxOutputTokens,
            operation = "translation_envelope",
            envelopeId = envelope.envelopeId,
            priority = AdmissionPriority.BACKGROUND,
            traceRuns = traceRuns,
            traceProvider = traceProvider,
        )
        suspend fun executeProviderRequest(): AiChunkOutcome {
            val traceRegistry = pageTraceRegistry
            suspend fun executeAdmitted(markAdmitted: () -> Unit): AiChunkOutcome =
                sublimitGate.executeBatch(metadata) {
                    markAdmitted()
                    held.forEach { page ->
                        runCatching {
                            store.recordAttemptStart(
                                pageKey = page.pageKey,
                                providerKeyHash = ShortHash.hash(textTranslator.javaClass.name),
                                origin = AttemptOrigin.BATCH,
                                generation = store.currentGeneration,
                                requestContextFingerprint = prepared.contextFingerprint,
                            )
                        }.onFailure {
                            logcat(LogPriority.WARN) {
                                "TachiyomiAT D9: batch attempt-ledger record failed (fail-open): " +
                                    "pageHash=${ShortHash.hash(page.pageKey)}"
                            }
                        }
                    }
                    try {
                        val request = suspend {
                            translateAiChunkWithAdaptiveRetry(
                                translator = textTranslator,
                                chunk = prepared.chunk,
                                requestedOutputTokens = prepared.chunk.maxOutputTokens,
                                profile = providerProfile,
                                label = "t924-${envelope.envelopeId}#$batchIndex",
                                retryDepth = 0,
                                retryPolicy = retryPolicy,
                                clock = clock,
                                emptyResponseReissuesAlreadyUsed = emptyResponseReissuesAlreadyUsed,
                            )
                        }
                        val result = if (traceRegistry == null) {
                            request()
                        } else {
                            traceRegistry.withProviderWindow(
                                pageKeys = held.map { it.pageKey },
                                block = request,
                            )
                        }
                        held.forEach { page -> runCatching { store.resolveAttempt(page.pageKey) } }
                        result
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        held.forEach { page -> runCatching { store.resolveAttempt(page.pageKey) } }
                        throw t
                    }
                }
            return if (traceRegistry == null) {
                executeAdmitted {}
            } else {
                traceRegistry.withProviderWindowAdmissionWait(
                    pageKeys = held.map { it.pageKey },
                    provider = traceProvider,
                    failureOutcome = { failure ->
                        when (failure) {
                            is CancellationException -> TranslationTraceOutcome.CANCELLED
                            is ProviderRequestPausedException -> TranslationTraceOutcome.PAUSE
                            else -> TranslationTraceOutcome.FAILURE
                        }
                    },
                ) { markAdmitted ->
                    executeAdmitted(markAdmitted)
                }
            }
        }
        val outcome = try {
            executeProviderRequest()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderRequestPausedException) {
            return EnvelopeDispatchResult.Paused(
                reason = "T924 envelope dispatch paused at ${envelope.envelopeId}: ${e.failure.safeSummary}",
                anchorPageKey = held.first().pageKey,
                failure = e.failure,
                nextEligibleRetryAtEpochMs = e.nextEligibleRetryAtEpochMs,
            )
        } catch (e: Exception) {
            return EnvelopeDispatchResult.Paused(
                reason = "T924 envelope dispatch paused at ${envelope.envelopeId}: " +
                    "${e::class.java.simpleName}: ${e.message ?: "no message"}",
                anchorPageKey = held.first().pageKey,
                failure = ProviderFailure(
                    kind = ProviderFailureKind.NETWORK,
                    retryability = ProviderFailureRetryability.PAUSE,
                    safeSummary = "envelope dispatch failed: ${e::class.java.simpleName}",
                    requestId = envelope.envelopeId,
                ),
                nextEligibleRetryAtEpochMs = null,
            )
        }

        // ---- DR-A Option 1 classification over the typed outcome. ----
        val refused = outcome.blockTranslations.values.any {
            TranslationResponseFaithfulness.isStructuralRefusal(it)
        }
        val fullyCovered = held.filter { page ->
            page.dispatchBlocks.all { it.stableBlockId in outcome.blockTranslations }
        }
        val partiallyCovered = held - fullyCovered.toSet()
        val ambiguousProtocol = when (outcome) {
            is AiChunkOutcome.Terminal -> outcome.failure.kind == ProviderFailureKind.PROTOCOL
            is AiChunkOutcome.Paused ->
                outcome.failure.kind == ProviderFailureKind.PROTOCOL &&
                    partiallyCovered.isNotEmpty()
            is AiChunkOutcome.Complete -> false
        }
        return when {
            // REFUSAL (structural marker or provider refusal): discard the
            // WHOLE response — nothing commits — typed terminal pause.
            refused ||
                (
                    outcome is AiChunkOutcome.Terminal &&
                        outcome.failure.kind == ProviderFailureKind.REFUSAL
                    ) ->
                run {
                    persistEmptyResponseReissueCountOnExistingFailures(
                        pages = held,
                        envelopeId = envelope.envelopeId,
                        reissues = outcome.emptyResponseReissues,
                    )
                    discardAndPause(
                        held,
                        failure = ProviderFailure(
                            kind = ProviderFailureKind.REFUSAL,
                            retryability = ProviderFailureRetryability.TERMINAL,
                            safeSummary = "provider refused translation of envelope ${envelope.envelopeId}",
                            requestId = outcome.envelopeId,
                        ),
                        reason = "T924 envelope ${envelope.envelopeId} refused; response discarded",
                    )
                }

            // For AMBIGUOUS_PROTOCOL, progress policy allows independently
            // complete pages to commit under the unchanged COMMIT policy —
            // independently complete pages still commit (page atomicity),
            // stubborn pages are parked as durable retryable failures with
            // their omitted-block evidence, and the batch CONTINUES. The
            // run loop's zero-commit breaker pauses a systematically broken
            // provider; REFUSAL above keeps the strict discard+pause.
            ambiguousProtocol ->
                commitAndParkProtocolOutcome(
                    envelope = envelope,
                    held = held,
                    fullyCovered = fullyCovered,
                    partiallyCovered = partiallyCovered,
                    outcome = outcome,
                    work = work,
                    frontier = frontier,
                )

            // MISSING_ONLY retention: independently complete pages commit
            // and advance; partially covered pages commit NOTHING. A
            // TERMINAL transport outcome still pauses after its commits.
            else -> {
                val remaining = held - fullyCovered.toSet()
                persistEmptyResponseReissueCountOnExistingFailures(
                    pages = remaining,
                    envelopeId = envelope.envelopeId,
                    reissues = outcome.emptyResponseReissues,
                )
                val committed =
                    commitPages(held, fullyCovered, outcome, work, frontier)
                if (committed is EnvelopeDispatchResult.Paused) return committed
                when (outcome) {
                    is AiChunkOutcome.Terminal -> EnvelopeDispatchResult.Paused(
                        reason = "T924 envelope ${envelope.envelopeId} terminal: " +
                            "${outcome.failure.safeSummary}; committed ${fullyCovered.size} " +
                            "page(s), ${remaining.size} remain pending",
                        anchorPageKey = remaining.firstOrNull()?.pageKey,
                        failure = outcome.failure,
                        nextEligibleRetryAtEpochMs = outcome.failure.retryAfterAtEpochMs,
                    )
                    is AiChunkOutcome.Paused -> EnvelopeDispatchResult.Paused(
                        reason = "T924 envelope ${envelope.envelopeId} paused: " +
                            "${outcome.failure.safeSummary}; committed ${fullyCovered.size} " +
                            "page(s), ${remaining.size} remain pending",
                        anchorPageKey = remaining.firstOrNull()?.pageKey,
                        failure = outcome.failure,
                        nextEligibleRetryAtEpochMs = outcome.nextEligibleRetryAtEpochMs,
                    )
                    is AiChunkOutcome.Complete -> committed
                }
            }
        }
    }

    /**
     * Carries issued identical-retry counts through an existing durable pause record without
     * changing that record's failure classification or retry status. A recordless pause stays
     * recordless; it does not create persistence solely for this counter.
     */
    private suspend fun persistEmptyResponseReissueCountOnExistingFailures(
        pages: List<HeldPage>,
        envelopeId: String,
        reissues: Int,
    ) {
        val boundedReissues = reissues.coerceAtMost(MAX_EMPTY_GEMINI_RESPONSE_REISSUES)
        if (boundedReissues <= 0) return

        for (page in pages) {
            val previous = store.durableFailure(page.pageKey)
                ?.takeIf { it.envelopeId == envelopeId }
                ?: continue
            if (previous.emptyGeminiResponseReissues >= boundedReissues) continue

            val expected = ChapterTranslationStore.PatchPrecondition(
                generation = page.snapshot.generation,
                pageVersion = page.snapshot.pageVersion,
                blockFingerprints = page.snapshot.blockFingerprints,
                leaseToken = page.leaseToken,
                candidateGenerationId = page.snapshot.candidateGenerationId,
                dependencyFingerprint = page.snapshot.dependencyFingerprint,
                artifactPageVersion = page.snapshot.artifactPageVersion,
            )
            when (
                val result = store.persistDurableStageFailure(
                    pageKey = page.pageKey,
                    expected = expected,
                    failure = previous.copy(emptyGeminiResponseReissues = boundedReissues),
                    description = "t924 envelope empty-response pause counter",
                ) { current ->
                    current ?: page.livePage.detachedCopy()
                }
            ) {
                is ChapterTranslationStore.PatchResult.Accepted -> Unit
                is ChapterTranslationStore.PatchResult.Rejected -> logcat(LogPriority.WARN) {
                    "TachiyomiAT t924 empty-response count persistence rejected: " +
                        "pageKey=${page.pageKey} reason=${result.reason}"
                }
            }
        }
    }

    /** Releases every held lease and returns a typed pause (response discarded). */
    private fun discardAndPause(
        held: List<HeldPage>,
        failure: ProviderFailure,
        reason: String,
    ): EnvelopeDispatchResult.Paused = EnvelopeDispatchResult.Paused(
        reason = reason,
        anchorPageKey = held.firstOrNull()?.pageKey,
        failure = failure,
        nextEligibleRetryAtEpochMs = failure.retryAfterAtEpochMs,
    )

    /**
     * Allows partial progress for a PROTOCOL-class verdict when blocks remain
     * missing after the controller's
     * whole → whole → missing-only → missing-only retry budget — the exact
     * on-device shape where a model silently omits specific blocks regardless
     * of envelope size). The old behavior discarded the ENTIRE response and
     * paused the WHOLE batch: a single stubborn envelope zeroed all progress
     * past that point.
     *
     * COMMIT policy stays strict — page atomicity is untouched:
     *  1. every fully-covered page commits through the SAME [commitPages]
     *      provenance ladder the MISSING_ONLY retention uses;
     *  2. every partially-covered page is PARKED — a durable, RETRYABLE
     *     TRANSLATION failure (protocol category) carrying the omitted block
     *     ids + per-block source char lengths — so it surfaces in the
     *     existing "pages need attention" UI and the retry path re-plans it
     *     (anything not READY re-plans);
     *  3. the result is NON-pausing ([EnvelopeDispatchResult.PartiallyParked])
     *     so the next envelope dispatches; [run]'s breaker restores the old
     *     pause when consecutive envelopes commit zero fully-covered pages.
     *
     * A rejected commit (store drift under the  ladder) still pauses
     * WITHOUT parking — drift may have invalidated the held identities too.
     */
    private suspend fun commitAndParkProtocolOutcome(
        envelope: PlannedEnvelope,
        held: List<HeldPage>,
        fullyCovered: List<HeldPage>,
        partiallyCovered: List<HeldPage>,
        outcome: AiChunkOutcome,
        work: EnvelopeDispatchWork,
        frontier: BatchContextFrontier,
    ): EnvelopeDispatchResult {
        val committed = commitPages(held, fullyCovered, outcome, work, frontier)
        if (committed is EnvelopeDispatchResult.Paused) return committed
        val failure = when (outcome) {
            is AiChunkOutcome.Terminal -> outcome.failure
            is AiChunkOutcome.Paused -> outcome.failure
            is AiChunkOutcome.Complete ->
                // Unreachable (Complete is never ambiguousProtocol) — typed
                // defensive shape keeps the carrier non-null.
                ProviderFailure(
                    kind = ProviderFailureKind.PROTOCOL,
                    retryability = ProviderFailureRetryability.PAUSE,
                    safeSummary = "protocol parking under complete outcome",
                    requestId = outcome.envelopeId,
                )
        }
        val parkedKeys = parkProtocolPages(partiallyCovered, outcome, failure, envelope.envelopeId)
        return EnvelopeDispatchResult.PartiallyParked(
            fullyCoveredCommitted = fullyCovered.size,
            parkedPageKeys = parkedKeys,
            // PAUSE-normalized (old ambiguous-pause idiom): the breaker pause
            // must stay resume-eligible even when the verdict came from a
            // TERMINAL-class protocol outcome.
            failure = failure.copy(retryability = ProviderFailureRetryability.PAUSE),
        )
    }

    /**
     * Parks partially-covered pages of a protocol-failed envelope as durable
     * retryable failures. The live page flips to FAILED (typed message), and
     * the manifest gains a [DurableFailureMetadata] under the page's
     * TRANSLATION stage with `missingBlockIds` + `missingBlockCharLengths`
     * (source char lengths — never the text) as the on-device diagnosis
     * record. Writes ride the same store mutex + artifact publication as the
     * standard lane's `persistAiFailure`, fenced by the snapshot captured at
     * revalidation and the still-held BATCH lease token. A rejected park is
     * fail-open: the page simply stays pending (never READY), so resume still
     * re-plans it — parking is a diagnosis upgrade, not a correctness gate.
     * Returns the keys that were durably parked.
     */
    private suspend fun parkProtocolPages(
        pages: List<HeldPage>,
        outcome: AiChunkOutcome,
        failure: ProviderFailure,
        envelopeId: String,
    ): List<String> {
        val parkedKeys = mutableListOf<String>()
        val now = nowEpochMs()
        for (page in pages) {
            val missing = page.dispatchBlocks.filter { it.stableBlockId !in outcome.blockTranslations }
            if (missing.isEmpty()) continue // caller guarantees partial coverage
            val summary = "protocol failure: envelope omitted ${missing.size} of " +
                "${page.dispatchBlocks.size} requested block translations " +
                "after the retry budget (${failure.safeSummary})"
            // Staged copy carries the incremented attempt counters the
            // durable metadata records (BatchWriteGate.persistAiFailure idiom).
            val staged = page.livePage.detachedCopy().apply {
                translationStatus = StageStatus.FAILED
                translationError = summary
                errorMessage = summary
                recordAttemptFailure()
                updatedAt = now
            }
            val priorEmptyResponseReissues = store.durableFailure(page.pageKey)
                ?.takeIf { it.envelopeId == envelopeId }
                ?.emptyGeminiResponseReissues ?: 0
            val durable = DurableFailureMetadata(
                pageKey = page.pageKey,
                stage = ArtifactStage.TRANSLATION,
                status = if (outcome.emptyResponseCapExhausted) {
                    ArtifactStageStatus.FAILED_TERMINAL
                } else {
                    ArtifactStageStatus.FAILED_RETRYABLE
                },
                category = FailureCategory.PROTOCOL,
                retryCount = staged.retryCount,
                lastFailureMessage = summary,
                lastFailedAtEpochMs = now,
                nextEligibleRetryAtEpochMs = null,
                envelopeId = envelopeId,
                missingBlockIds = missing.mapTo(linkedSetOf()) { it.stableBlockId },
                missingBlockCharLengths = missing.associate { it.stableBlockId to it.sourceText.length },
                emptyGeminiResponseReissues = maxOf(
                    priorEmptyResponseReissues,
                    outcome.emptyResponseReissues.coerceAtMost(MAX_EMPTY_GEMINI_RESPONSE_REISSUES),
                ),
            )
            val expected = ChapterTranslationStore.PatchPrecondition(
                generation = page.snapshot.generation,
                pageVersion = page.snapshot.pageVersion,
                blockFingerprints = page.snapshot.blockFingerprints,
                leaseToken = page.leaseToken,
                candidateGenerationId = page.snapshot.candidateGenerationId,
                dependencyFingerprint = page.snapshot.dependencyFingerprint,
                artifactPageVersion = page.snapshot.artifactPageVersion,
            )
            when (
                val result = store.persistDurableStageFailure(
                    pageKey = page.pageKey,
                    expected = expected,
                    failure = durable,
                    description = "t924 envelope protocol parking",
                ) { current ->
                    (current ?: staged).apply {
                        translationStatus = staged.translationStatus
                        translationError = staged.translationError
                        errorMessage = staged.errorMessage
                        retryCount = staged.retryCount
                        attemptCount = staged.attemptCount
                        updatedAt = now
                    }
                }
            ) {
                is ChapterTranslationStore.PatchResult.Accepted -> {
                    counters.pagesParked++
                    parkedKeys += page.pageKey
                    // One WARN line per parked page: the self-describing
                    // evidence for the next on-device diagnosis run.
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT t924 envelope parked page pageKey=${page.pageKey} " +
                            "missing=${missing.size}/${page.dispatchBlocks.size} omitted=" +
                            missing.joinToString(",") { block ->
                                "${block.stableBlockId}:${block.sourceText.length}c"
                            } + " envelopeId=$envelopeId"
                    }
                }
                is ChapterTranslationStore.PatchResult.Rejected ->
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT t924 envelope parking rejected (fail-open, page stays " +
                            "pending): pageKey=${page.pageKey} reason=${result.reason}"
                    }
            }
        }
        return parkedKeys
    }

    private fun replanOrPause(reason: String, pageKey: String): EnvelopeDispatchResult =
        EnvelopeDispatchResult.ReplanNeeded(reason = reason, anchorPageKey = pageKey)

    /**
     *  compares the fresh snapshot against the plan-time inputs.
     * Returns the typed drift description, or null when the page is exactly
     * as planned.
     */
    private fun revalidationDrift(
        pageWork: PageDispatchWork,
        snapshot: ChapterTranslationStore.PageSnapshot,
        live: PageTranslation,
    ): String? {
        if (snapshot.pageVersion != pageWork.planPageVersion) {
            return "pageVersion ${snapshot.pageVersion} != planned ${pageWork.planPageVersion}"
        }
        if (snapshot.candidateGenerationId != pageWork.planCandidateGenerationId) {
            return "candidateGeneration changed"
        }
        if (snapshot.dependencyFingerprint != pageWork.planDependencyFingerprint) {
            return "dependencyFingerprint changed"
        }
        if (snapshot.artifactPageVersion != pageWork.planArtifactPageVersion) {
            return "artifactPageVersion changed"
        }
        if (live.sourceFingerprint != pageWork.sourceFingerprint) {
            return "source identity changed"
        }
        for (planned in pageWork.blocks) {
            val liveBlock = live.blocks.getOrNull(planned.blockIndex) ?: continue
            if (liveBlock.ocrFingerprint() != planned.ocrFingerprint) {
                return "block ${planned.stableBlockId} OCR identity changed"
            }
            if (liveBlock.text != planned.sourceText) {
                return "block ${planned.stableBlockId} source text changed"
            }
        }
        return null
    }

    /**
     *  provenance commit for one page: the full M4 CAS ladder captured
     * at revalidation time + `envelopePlanFingerprint`.
     * A rejected commit never advances the frontier (the frontier records
     * ONLY accepted snapshots) and pauses the phase.
     */
    private suspend fun commitPages(
        held: List<HeldPage>,
        committable: List<HeldPage>,
        outcome: AiChunkOutcome,
        work: EnvelopeDispatchWork,
        frontier: BatchContextFrontier,
    ): EnvelopeDispatchResult {
        val committedPageKeys = mutableListOf<String>()
        for (page in committable) {
            val live = page.livePage
            val patches = page.dispatchBlocks.map { planned ->
                val liveBlock = live.blocks[planned.blockIndex]
                TranslationBlockPatch(
                    blockIndex = planned.blockIndex,
                    expectedOcrFingerprint = planned.ocrFingerprint,
                    expectedSourceText = planned.sourceText,
                    expectedTranslation = liveBlock.translation,
                    expectedUserEditedAt = liveBlock.userEditedAt,
                    translation = outcome.blockTranslations.getValue(planned.stableBlockId),
                )
            }
            val patch = TranslationStagePatch(
                pageKey = page.pageKey,
                generation = page.snapshot.generation,
                expectedOcrBlockFingerprints = live.ocrBlockFingerprints(),
                expectedSourceTexts = live.blocks.map { it.text },
                blocks = patches,
                translationStatus = StageStatus.READY,
                errorMessage = null,
                expectedPageVersion = page.snapshot.pageVersion,
                expectedLeaseToken = page.leaseToken,
                expectedCandidateGenerationId = page.snapshot.candidateGenerationId,
                expectedDependencyFingerprint = page.snapshot.dependencyFingerprint,
                expectedArtifactPageVersion = page.snapshot.artifactPageVersion,
                envelopePlanFingerprint = work.planFingerprint,
                sourceFingerprint = page.work.observedSourceFingerprint?.takeIf(String::isSha256Hex),
            )
            val mergeResult = if (pageTraceRegistry == null) {
                store.mergeTranslation(patch, description = "t924 translation envelope commit")
            } else {
                pageTraceRegistry.withPageRun(page.pageKey) {
                    store.mergeTranslation(patch, description = "t924 translation envelope commit")
                }
            }
            when (val result = mergeResult) {
                is StagePatchResult.Accepted -> {
                    counters.pagesTranslated++
                    committedPageKeys += page.pageKey
                    // Rolling context advances ONLY through the accepted,
                    // fully committed page (never on a rejected commit).
                    val committedPage = result.snapshot.page
                    if (committedPage != null) {
                        frontier.record(page.pageKey, committedPage)
                    }
                    logcat(LogPriority.INFO) {
                        "TachiyomiAT t924 envelope committed pageHash=${ShortHash.hash(page.pageKey)}"
                    }
                }
                is StagePatchResult.Rejected -> {
                    // Stale commit: no partial state, no frontier movement.
                    return EnvelopeDispatchResult.Paused(
                        reason = "T924 envelope commit rejected for ${page.pageKey}: ${result.reason}",
                        anchorPageKey = page.pageKey,
                        failure = ProviderFailure(
                            kind = ProviderFailureKind.PROTOCOL,
                            retryability = ProviderFailureRetryability.PAUSE,
                            safeSummary = "translation commit rejected: ${result.reason}",
                            requestId = outcome.envelopeId,
                        ),
                        nextEligibleRetryAtEpochMs = null,
                    )
                }
            }
        }
        // The commit settled — wake the overlap lane and repair any display
        // tail among these newly committed pages without waiting for FINALIZE.
        if (committedPageKeys.isNotEmpty()) onCommitSettled(committedPageKeys)
        return when {
            committable.size == held.size -> EnvelopeDispatchResult.Dispatched
            committable.isEmpty() -> EnvelopeDispatchResult.Skipped
            // Some pages stay pending (MISSING_ONLY): the envelope finished
            // its dispatch; the pending remainder is re-planned on resume.
            else -> EnvelopeDispatchResult.Dispatched
        }
    }

    /** The request chunk plus its honest execution-time input-token estimate. */
    private class PreparedChunk(
        val chunk: TranslationContextChunk,
        val estimatedInputTokens: Int,
        val contextFingerprint: String,
    )

    /**
     * Builds the ONE in-flight context chunk from the revalidated live pages:
     * detached copies only (never the live store objects), wire stable block
     * ids matching the plan, strict BATCH_V1 protocol, and the shared
     * committed rolling history. The fingerprint is computed after token
     * trimming, from the exact history emitted with this chunk.
     */
    private fun buildEnvelopeChunk(
        envelope: PlannedEnvelope,
        held: List<HeldPage>,
    ): PreparedChunk {
        val pages = linkedMapOf<String, PageTranslation>()
        val pageIndexes = linkedMapOf<String, Int>()
        var blockCount = 0
        for (page in held) {
            val wanted = page.dispatchBlocks.mapTo(hashSetOf()) { it.blockIndex }
            val blocks = page.livePage.blocks
                .mapIndexed { index, block -> index to block }
                .filter { (index, _) -> index in wanted }
                .map { (index, block) ->
                    block.detachedCopy().apply {
                        blockId = page.dispatchBlocks.first { it.blockIndex == index }.stableBlockId
                    }
                }
            blockCount += blocks.count { it.text.isNotBlank() }
            pages[page.pageKey] = page.livePage.detachedCopy().apply { this.blocks = blocks.toMutableList() }
            pageIndexes[page.pageKey] = page.work.naturalPageIndex
        }
        val preparedContext = prepareRollingHistory(held)
        val finalized = TranslationContextChunkPlanner.withRollingContext(
            chunk = TranslationContextChunk(
                pages = pages,
                blockCount = blockCount,
                rollingContext = "",
                estimatedPromptTokens = envelope.estimatedInputTokens,
                maxOutputTokens = envelope.estimatedOutputTokens,
                protocol = ContextualRequestProtocol.BATCH_V1,
                pageIndexes = pageIndexes,
            ),
            rollingContext = preparedContext.rollingContext,
            requestedOutputTokens = envelope.estimatedOutputTokens,
            profile = providerProfile,
        )
        val fingerprint = preparedContext.computeRequestContextFingerprint(
            targetLang = textTranslator.toLang.code,
            sourceLang = textTranslator.fromLang.code,
            finalizedRollingContext = finalized.rollingContext,
        )
        return PreparedChunk(
            chunk = finalized,
            estimatedInputTokens = finalized.estimatedPromptTokens,
            contextFingerprint = fingerprint,
        )
    }

    /** Whole-page authoritativeness re-check ( fresh reads). */
    private fun pageAuthoritativelyDone(
        pageKey: String,
        live: PageTranslation,
        observedSourceFingerprint: String?,
    ): Boolean {
        val observed = observedSourceFingerprint?.takeIf(String::isSha256Hex) ?: return false
        val recorded = live.sourceFingerprint?.takeIf(String::isSha256Hex) ?: return false
        if (recorded != observed) return false
        if (live.translationStatus == StageStatus.READY ||
            live.translationStatus == StageStatus.SKIPPED ||
            live.hasRenderedResult
        ) {
            return true
        }
        val committed = store.artifactManifest?.pages?.get(pageKey)?.committed ?: return false
        return committed.hasManualEdits
    }

    /** Gap-free rolling-context frontier over the planned pages. */
    private suspend fun buildFrontier(work: EnvelopeDispatchWork): BatchContextFrontier {
        val frontier = BatchContextFrontier(
            naturalPageIndexes = work.pages.mapValues { it.value.naturalPageIndex },
        )
        val pages = mutableMapOf<String, PageTranslation>()
        for (pageKey in work.pages.keys) {
            store.snapshot(pageKey).page?.let { pages[pageKey] = it }
        }
        frontier.seed(pages)
        return frontier
    }

    companion object {
        /** Livelock guard for pathological drift loops (typed pause beyond this). */
        const val MAX_CONSECUTIVE_REPLANS = 8

        /**
         *  protocol-parking breaker: after this many CONSECUTIVE
         * PROTOCOL envelopes with ZERO fully-covered pages, the batch falls
         * back to the old pause behavior instead of parking an entire
         * chapter one envelope at a time.
         */
        const val MAX_CONSECUTIVE_ZERO_COMMIT_ENVELOPES = 3
    }
}

/** Typed result of a deterministic suffix re-plan. */
internal sealed interface ReplanResult {
    data class Ready(val work: EnvelopeDispatchWork) : ReplanResult

    /** No dispatchable page remains: the phase drains honestly. */
    data object NothingPending : ReplanResult

    /** The rebuild failed (store drift / planner rejection): typed pause, never a silent drain. */
    data class Failed(val reason: String) : ReplanResult
}

/**
 * Plan-time dispatch inputs for the envelope phase: the validated
 * [EnvelopePlan] plus, per DISPATCHABLE page, the exact identities
 * revalidates against. Rebuilt from fresh store state on every re-plan.
 */
internal class EnvelopeDispatchWork(
    val plan: EnvelopePlan,
    /** The SC-10 content fingerprint  commits carry. */
    val planFingerprint: String,
    /** pageKey to plan-time work; ONLY dispatchable (pending) pages appear. */
    val pages: Map<String, PageDispatchWork>,
    /** Provider identity for the Batch sub-limit bucket key (DR-D). */
    val providerBackend: String,
    val providerModel: String?,
    val credentialScope: String?,
)

/** Plan-time identities of one pending page. */
internal class PageDispatchWork(
    val pageKey: String,
    val naturalPageIndex: Int,
    /** The page's checkpoint OCR content fingerprint at plan time (FP-02). */
    val ocrContentFingerprint: String,
    val sourceFingerprint: String?,
    val planPageVersion: Long,
    val planCandidateGenerationId: String?,
    val planDependencyFingerprint: String?,
    val planArtifactPageVersion: Long?,
    /** Pending (dispatchable) blocks in reading order. */
    val blocks: List<PlannedBlock>,
    /** Fresh source digest observed for this run; null never authorizes a terminal skip. */
    val observedSourceFingerprint: String? = null,
)

/** One pending block's dispatch identity (stable wire id + plan-time OCR identity). */
internal class PlannedBlock(
    val stableBlockId: String,
    val sourceText: String,
    val ocrFingerprint: String,
    val blockIndex: Int,
)
