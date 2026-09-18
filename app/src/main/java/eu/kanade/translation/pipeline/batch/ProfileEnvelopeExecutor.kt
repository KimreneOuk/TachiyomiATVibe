package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.LeaseAcquisition
import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.StagePatchResult
import eu.kanade.translation.TranslationBlockPatch
import eu.kanade.translation.TranslationStagePatch
import eu.kanade.translation.artifact.ArtifactStage
import eu.kanade.translation.artifact.ArtifactStageStatus
import eu.kanade.translation.artifact.ChapterTranslationProfile
import eu.kanade.translation.artifact.DurableFailureMetadata
import eu.kanade.translation.artifact.EnvelopePlan
import eu.kanade.translation.artifact.FailureCategory
import eu.kanade.translation.artifact.PlannedEnvelope
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.recordAttemptFailure
import eu.kanade.translation.ocrBlockFingerprints
import eu.kanade.translation.ocrFingerprint
import eu.kanade.translation.translator.AdmissionPriority
import eu.kanade.translation.translator.BatchRequestSublimitGate
import eu.kanade.translation.translator.ProviderFailure
import eu.kanade.translation.translator.ProviderFailureKind
import eu.kanade.translation.translator.ProviderFailureRetryability
import eu.kanade.translation.translator.ProviderRequestClock
import eu.kanade.translation.translator.ProviderRequestKey
import eu.kanade.translation.translator.ProviderRequestMetadata
import eu.kanade.translation.translator.ProviderRequestPausedException
import eu.kanade.translation.translator.SharedBatchRequestSublimitGate
import eu.kanade.translation.translator.SystemProviderRequestClock
import eu.kanade.translation.translator.contextual.ContextualRequestProtocol
import eu.kanade.translation.translator.contextual.ContextualTextTranslator
import eu.kanade.translation.translator.contextual.GlobalEnvelopePlanner
import eu.kanade.translation.translator.contextual.ProfileSubsetMatcher
import eu.kanade.translation.translator.contextual.StreamingChunkPlanner
import eu.kanade.translation.translator.contextual.TranslationContextChunk
import eu.kanade.translation.translator.contextual.TranslationContextChunkPlanner
import eu.kanade.translation.translator.contextual.TranslationPrompts
import eu.kanade.translation.translator.contextual.TranslationResponseFaithfulness
import eu.kanade.translation.translator.retry.AiChunkOutcome
import eu.kanade.translation.translator.retry.AiTranslationRetryPolicy
import eu.kanade.translation.translator.retry.translateAiChunkWithAdaptiveRetry
import eu.kanade.translation.util.ShortHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * T924 Stage-6 slice A (WP6, T924-ST-12 + T924-TX-21/TX-20 + DR-A Option 1):
 * the serial per-envelope translation executor of the AI profile lane.
 *
 * Invariants enforced here (gates 5.1-5.8 basis):
 *  - ONE provider envelope in flight, chapter-wide (hard serial loop — there
 *    is no concurrency construct anywhere in this class);
 *  - TX-21: BEFORE each envelope dispatch every affected page is revalidated
 *    under a freshly reacquired BATCH lease (BATCH attaches/never preempts a
 *    MANUAL owner) against the plan-time inputs: page version, candidate
 *    generation, dependency fingerprint, artifact page version, live OCR
 *    block identity, checkpoint content fingerprint, and the currently
 *    frozen profile. User-edited blocks and manual-authoritative pages are
 *    skipped, never overwritten (TX-20 `userEditedAt` fence holds at the
 *    merge regardless);
 *  - any identity drift triggers a DETERMINISTIC suffix re-plan through the
 *    injected [replan] callback (same pure planner over fresh store state);
 *    re-planning never mutates committed history, only the not-yet-dispatched
 *    remainder;
 *  - dispatch rides the EXISTING legacy typed machinery
 *    (`translateAiChunkWithAdaptiveRetry` — split/backoff included) under the
 *    shared [BatchRequestSublimitGate] (DR-C/DR-D: one Batch allowance per
 *    credential) and the shared provider governor inside the translator;
 *  - DR-A Option 1 retention: per-page COMPLETE subsets of a paused response
 *    commit and advance (page atomicity — a page commits only when EVERY
 *    planned block of that page was accepted); REFUSAL discards and pauses
 *    terminal. T934 (Director decision 2026-09-17): a PROTOCOL-class verdict
 *    (blocks still missing after the controller's retry budget) no longer
 *    discards the whole response — its fully-covered pages COMMIT, its
 *    stubborn pages are PARKED as durable retryable TRANSLATION failures
 *    (omitted block ids + per-block source char lengths recorded) and the
 *    batch CONTINUES with the next envelope, guarded by a
 *    consecutive-zero-commit circuit breaker ([MAX_CONSECUTIVE_ZERO_COMMIT_ENVELOPES]);
 *  - TX-20: every commit carries `profileContentFingerprint` +
 *    `envelopePlanFingerprint` and the full M4 CAS ladder; a rejected commit
 *    never advances the rolling-context frontier and never counts as
 *    progress;
 *  - durable progress lives in the STORE (per-page translation state), never
 *    in this loop: a fresh process resumes by re-entering the envelope phase
 *    and re-planning over the remaining pending pages.
 */
internal class ProfileEnvelopeExecutor(
    private val store: ChapterTranslationStore,
    private val textTranslator: ContextualTextTranslator,
    /** The frozen profile content fingerprint this run dispatches under (FP-05). */
    private val profileContentFingerprint: String,
    /**
     * T924 Stage-6 slice B (design §7): the LOADED frozen profile DTO. When
     * present, every envelope prompt is ENRICHED with the profile subset
     * matcher's capped subset, range-safe scene context, and the gap-free
     * rolling history (pronoun-marking rule), and the execution-time token
     * recompute + whole-page split applies. `null` — no usable frozen profile
     * — keeps the slice-A LEGACY prompt shape unchanged (degraded-but-correct).
     */
    private val frozenProfile: ChapterTranslationProfile? = null,
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
    /**
     * T934 track V: invoked after an envelope's commit settled with at least
     * one accepted page ([commitPages]) — the post-commit-settle nudge
     * (OverlapScheduler.notifyCandidatesChanged) that runs a drain pass at
     * every envelope commit boundary, so candidates deferred on write-slot
     * holds settled by THIS commit are re-admitted without waiting a whole
     * envelope cycle for the next window's open. Window-CLOSE is deliberately
     * NOT a trigger (the envelope still holds its pages' slots until the
     * commit settles); the settle is the earliest a deferral can be invalid.
     * Synchronous and non-blocking (a channel try-send); never affects the
     * commit outcome.
     */
    private val onCommitSettled: () -> Unit = {},
    /** Advisory progress callback (per-envelope); the store stays authoritative. */
    private val onProgress: (Map<String, Int>) -> Unit = {},
) {

    /** Mutable run-of-phase counters (operational only, never fingerprinted). */
    data class Counters(
        var envelopesTotal: Int = 0,
        var envelopesDone: Int = 0,
        var envelopesSkipped: Int = 0,
        var envelopesPending: Int = 0,
        var envelopeFailures: Int = 0,
        var pagesTranslated: Int = 0,
        /** T934 protocol parking: pages durably FAILED for omitted blocks. */
        var pagesParked: Int = 0,
        var replans: Int = 0,
        /** Slice B (D6): whole-page execution-time splits actually dispatched. */
        var envelopeSplits: Int = 0,
        /** Slice B (D6): envelopes sent in the ENRICHED prompt shape. */
        var promptShapeEnriched: Int = 0,
        /** Slice B (D6): envelopes sent in the LEGACY prompt shape. */
        var promptShapeLegacy: Int = 0,
        /** Slice B (D6): largest profile-subset fact count sent in ONE envelope. */
        var profileSubsetFactsMax: Int = 0,
        /** Slice B (D6): largest gap-free rolling-context page count carried. */
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
            "promptShapeEnriched" to promptShapeEnriched,
            "promptShapeLegacy" to promptShapeLegacy,
            "profileSubsetFactsMax" to profileSubsetFactsMax,
            "rollingContextPagesMax" to rollingContextPagesMax,
        )
    }

    /** Typed terminal of the envelope phase (T924-ST-12; never COMPLETE). */
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
        // T934 protocol-parking circuit breaker: consecutive envelopes that
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
                    // T934 protocol parking: the envelope finished with a
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
    // TX-21: per-envelope revalidation + dispatch
    // ------------------------------------------------------------------

    private sealed interface EnvelopeDispatchResult {
        /** Envelope fully processed (all pages committed). */
        data object Dispatched : EnvelopeDispatchResult

        /** Every affected page was dropped (committed/manual/user-edited): nothing to send. */
        data object Skipped : EnvelopeDispatchResult

        /**
         * T934 protocol parking: the envelope ended under a PROTOCOL-class
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
                val lease = store.tryAcquirePageStageLease(pageKey, PageStage.Translation, PageWriteOrigin.BATCH)
                val leaseToken = when (lease) {
                    is LeaseAcquisition.Granted -> lease.lease.token
                    is LeaseAcquisition.Denied -> {
                        // BATCH attaches/never preempts (TX-21.1). A MANUAL
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
                    // Wave-6 F-W6-1: the page is not yet in `held`, so the
                    // just-acquired lease must be released before the early
                    // return — otherwise a MANUAL attempt on this page is
                    // denied for the whole replan window.
                    store.releasePageStageLeaseIfUnattached(pageKey, PageWriteOrigin.BATCH, leaseToken)
                    return replanOrPause("page $pageKey lost its live state", pageKey)
                }
                // TX-21.3 skip re-check: the page became committed /
                // manual-authoritative between plan and dispatch.
                if (pageAuthoritativelyDone(pageKey, live)) {
                    store.releasePageStageLeaseIfUnattached(pageKey, PageWriteOrigin.BATCH, leaseToken)
                    continue
                }
                // TX-21.2 live-revalidate the plan-time identities.
                val drift = revalidationDrift(pageWork, snapshot, live)
                if (drift != null) {
                    // Wave-6 F-W6-1: same release-before-early-return as the
                    // lost-page path above.
                    store.releasePageStageLeaseIfUnattached(pageKey, PageWriteOrigin.BATCH, leaseToken)
                    return EnvelopeDispatchResult.ReplanNeeded(
                        reason = "page $pageKey drifted: $drift",
                        anchorPageKey = pageKey,
                    )
                }
                // TX-21.3 block-level skip: a block edited by the user since
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

            // D6: the gap-free rolling-context page count carried into prompts
            // (contiguous committed prefix only — the frontier never moves on
            // a rejected commit).
            counters.rollingContextPagesMax =
                maxOf(counters.rollingContextPagesMax, frontier.frontierIndex + 1)

            // ---- Slice B D5 (design §8 tail): execution-time token ----
            // ---- recompute; split at WHOLE-PAGE boundaries before sending.
            val batches = splitForTokenFit(held, frontier.rollingContext)
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
            // atomicity invariant): typed pause AFTER any fitted pages
            // committed (the slice-A commit-then-pause idiom).
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
            // T934 R1.2: the release is attach-aware — a page the overlap
            // inpaint re-attached to (same token, mid-lane) KEEPS its record
            // so no sibling acquire can mint a fresh token and fail-close the
            // sibling's write identity; the sibling's own release clears it.
            held.forEach { page ->
                store.releasePageStageLeaseIfUnattached(page.pageKey, PageWriteOrigin.BATCH, page.leaseToken)
            }
        }
    }

    /**
     * Slice B D5: splits the held pages into deterministic whole-page
     * sub-batches whose ACTUAL enriched payload (source lines + profile
     * subset + scene context + rolling history) AND estimated translation
     * response fit the provider context window. Greedy prefix packing in
     * plan order — the same planner discipline as the global planner, never
     * a page split. Pages that alone exceed the window are returned as
     * [SplitPlan.oversized] (rejected, not sent). Without a frozen profile
     * (legacy shape) this is the identity split: ONE batch, slice-A behavior
     * unchanged. The context reserve is re-derived per CANDIDATE batch
     * (wave-7a F-W7-1) — a full-range estimate is not a strict upper bound
     * because AVAILABLE_FROM facts gate on the sub-batch's own first page
     * and the subset cap can pick different entries on a narrower range.
     *
     * Device fix 2026-09-17: the candidate predicate previously reserved
     * only minOutputTokens for the response, so a 64-block envelope shipped
     * with a maxOutput that its own JSON reserve nearly exhausted — every
     * response truncated mid-JSON and the retry budget burned down to the
     * "ambiguous (protocol)" discard. The translation output is now part of
     * the reservation (planner estimator v2, per-block framing + expansion).
     */
    private fun splitForTokenFit(held: List<HeldPage>, rollingContext: String): SplitPlan {
        val constraints = TranslationContextChunkPlanner.constraintsFor(providerProfile)

        // Wave-7a F-W7-1: the context reserve is re-derived per CANDIDATE
        // batch. A single full-range estimate is not a strict upper bound:
        // AVAILABLE_FROM facts become usable at later sub-batch starts, and
        // the 24-entry cap can select different entries on a narrower range.
        // The matcher is pure and dispatch is sequential — the recompute is
        // cheap and closes both exceptions.
        fun contextTokensFor(pages: List<HeldPage>): Int =
            estimateEnrichedContextTokens(pages, rollingContext)

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

    /**
     * Upper-bound context estimate for [held]: the FULL-range profile subset
     * + scene context + rolling history, exactly as the enriched chunk
     * builder renders them.
     */
    private fun estimateEnrichedContextTokens(held: List<HeldPage>, rollingContext: String): Int {
        val profile = frozenProfile
            ?: return if (rollingContext.isBlank()) 0 else TranslationContextChunkPlanner.estimateTokens(rollingContext.trim())
        val subset = ProfileSubsetMatcher.match(profile, envelopeSourcesOf(held))
        val glossary = TranslationPrompts.characterAndTermSheetPrefix(subset)
        val rolling = TranslationPrompts.profileAwareRollingPrefix(
            rollingPairs = rollingContext,
            resolvedEntityLines = ProfileSubsetMatcher.resolvedEntityLines(profile, rollingContext),
            unresolvedLines = ProfileSubsetMatcher.unresolvedReferenceLines(profile),
        )
        return TranslationContextChunkPlanner.estimateTokens(glossary) +
            TranslationContextChunkPlanner.estimateTokens(rolling)
    }

    private fun envelopeSourcesOf(held: List<HeldPage>): List<ProfileSubsetMatcher.EnvelopeSource> =
        held.map { page ->
            ProfileSubsetMatcher.EnvelopeSource(
                naturalPageIndex = page.work.naturalPageIndex,
                sourceText = page.dispatchBlocks.joinToString("\n") { it.sourceText },
            )
        }

    /**
     * Dispatches ONE provider request over ONE (sub-)batch of held pages —
     * the hard one-envelope-in-flight unit. Chunk assembly enriches per
     * [frozenProfile] (slice B) or keeps the slice-A legacy shape; DR-A
     * Option 1 classification and TX-20 provenance commits are unchanged.
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
        val prepared = buildEnvelopeChunk(envelope, held, frontier.rollingContext)
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
        )
        val outcome = try {
            sublimitGate.executeBatch(metadata) {
                translateAiChunkWithAdaptiveRetry(
                    translator = textTranslator,
                    chunk = prepared.chunk,
                    requestedOutputTokens = prepared.chunk.maxOutputTokens,
                    profile = providerProfile,
                    label = "t924-${envelope.envelopeId}#$batchIndex",
                    retryDepth = 0,
                    retryPolicy = retryPolicy,
                    clock = clock,
                )
            }
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

            // AMBIGUOUS_PROTOCOL (T934 Director decision 2026-09-17): the
            // PROGRESS policy is relaxed under the unchanged COMMIT policy —
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
                val committed =
                    commitPages(held, fullyCovered, outcome, work, frontier)
                if (committed is EnvelopeDispatchResult.Paused) return committed
                val remaining = held - fullyCovered.toSet()
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
     * T934 (Director decision 2026-09-17): relaxed PROGRESS policy for a
     * PROTOCOL-class verdict (blocks still missing after the controller's
     * whole → whole → missing-only → missing-only retry budget — the exact
     * on-device shape where a model silently omits specific blocks regardless
     * of envelope size). The old behavior discarded the ENTIRE response and
     * paused the WHOLE batch: a single stubborn envelope zeroed all progress
     * past that point.
     *
     * COMMIT policy stays strict — page atomicity is untouched:
     *  1. every fully-covered page commits through the SAME [commitPages]
     *     TX-20 provenance ladder the MISSING_ONLY retention uses;
     *  2. every partially-covered page is PARKED — a durable, RETRYABLE
     *     TRANSLATION failure (protocol category) carrying the omitted block
     *     ids + per-block source char lengths — so it surfaces in the
     *     existing "pages need attention" UI and the retry path re-plans it
     *     (anything not READY re-plans);
     *  3. the result is NON-pausing ([EnvelopeDispatchResult.PartiallyParked])
     *     so the next envelope dispatches; [run]'s breaker restores the old
     *     pause when consecutive envelopes commit zero fully-covered pages.
     *
     * A rejected commit (store drift under the TX-20 ladder) still pauses
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
            val durable = DurableFailureMetadata(
                pageKey = page.pageKey,
                stage = ArtifactStage.TRANSLATION,
                status = ArtifactStageStatus.FAILED_RETRYABLE,
                category = FailureCategory.PROTOCOL,
                retryCount = staged.retryCount,
                lastFailureMessage = summary,
                lastFailedAtEpochMs = now,
                nextEligibleRetryAtEpochMs = null,
                envelopeId = envelopeId,
                missingBlockIds = missing.mapTo(linkedSetOf()) { it.stableBlockId },
                missingBlockCharLengths = missing.associate { it.stableBlockId to it.sourceText.length },
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
     * TX-21.2: compares the fresh snapshot against the plan-time inputs.
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
     * TX-20 provenance commit for one page: the full M4 CAS ladder captured
     * at revalidation time + `profileContentFingerprint`/`envelopePlanFingerprint`.
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
        var committedAnyPage = false
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
                profileContentFingerprint = profileContentFingerprint,
                envelopePlanFingerprint = work.planFingerprint,
            )
            when (val result = store.mergeTranslation(patch, description = "t924 profile envelope commit")) {
                is StagePatchResult.Accepted -> {
                    counters.pagesTranslated++
                    committedAnyPage = true
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
        // T934 track V: the commit settled — free write slots wake the overlap
        // lane immediately (no waiting for the next window's open).
        if (committedAnyPage) onCommitSettled()
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
    )

    /**
     * Builds the ONE in-flight context chunk from the revalidated live pages:
     * detached copies only (never the live store objects), wire stable block
     * ids matching the plan, strict BATCH_V1 protocol, rolling context from
     * the gap-free frontier.
     *
     * Slice B (design §7): with a frozen profile the chunk is ENRICHED — the
     * capped profile-subset sheet + range-safe scene context ride the
     * glossary slot, the gap-free rolling history (recent source/target
     * pairs, resolved entity ids, compact unresolved state, pronoun-marking
     * rule) rides the rolling slot, and the prompt tokens are recomputed over
     * the ACTUAL payload. Without a profile the legacy slice-A shape is kept
     * unchanged (degraded-but-correct).
     */
    private fun buildEnvelopeChunk(
        envelope: PlannedEnvelope,
        held: List<HeldPage>,
        rollingContext: String,
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
        val profile = frozenProfile
            ?: return PreparedChunk(
                chunk = TranslationContextChunkPlanner.withRollingContext(
                    chunk = TranslationContextChunk(
                        pages = pages,
                        blockCount = blockCount,
                        rollingContext = "",
                        estimatedPromptTokens = envelope.estimatedInputTokens,
                        maxOutputTokens = envelope.estimatedOutputTokens,
                        protocol = ContextualRequestProtocol.BATCH_V1,
                        pageIndexes = pageIndexes,
                    ),
                    rollingContext = rollingContext,
                    requestedOutputTokens = envelope.estimatedOutputTokens,
                    profile = providerProfile,
                    glossary = "",
                ),
                estimatedInputTokens = envelope.estimatedInputTokens,
            ).also { counters.promptShapeLegacy++ }

        // ---- Slice B enriched assembly (design §7.1-7.3). ----
        val constraints = TranslationContextChunkPlanner.constraintsFor(providerProfile)
        var subset = ProfileSubsetMatcher.match(profile, envelopeSourcesOf(held))
        var includeScenes = true
        var resolvedLines = ProfileSubsetMatcher.resolvedEntityLines(profile, rollingContext)
        var unresolvedLines = ProfileSubsetMatcher.unresolvedReferenceLines(profile)
        var pairLines = rollingContext
        var glossary = TranslationPrompts.characterAndTermSheetPrefix(subset, includeScenes)
        var rolling = TranslationPrompts.profileAwareRollingPrefix(pairLines, resolvedLines, unresolvedLines)
        var contextTokens = TranslationContextChunkPlanner.estimateTokens(glossary) +
            TranslationContextChunkPlanner.estimateTokens(rolling)

        // Deterministic bounded trim per T933 allocator order (terms 320 ->
        // safeguards 96 -> pairs 288 -> scene/style 96):
        // 1. Drop scene narratives
        // 2. Halve recent pairs, then drop pairs entirely
        // 3. Drop safeguards (unresolved and resolved entity lines)
        // 4. Halve terms subset tail, then drop terms subset
        fun rebuild() {
            glossary = TranslationPrompts.characterAndTermSheetPrefix(subset, includeScenes)
            rolling = TranslationPrompts.profileAwareRollingPrefix(pairLines, resolvedLines, unresolvedLines)
            contextTokens = TranslationContextChunkPlanner.estimateTokens(glossary) +
                TranslationContextChunkPlanner.estimateTokens(rolling)
        }
        fun pairLineCount(): Int = pairLines.lineSequence().count { it.isNotBlank() }
        // 1. Scene / style
        if (contextTokens > constraints.maxRollingContextTokens) {
            includeScenes = false
            rebuild()
        }
        // 2. Predecessor pairs (halve, then drop)
        while (contextTokens > constraints.maxRollingContextTokens && pairLineCount() > 1) {
            val keep = (pairLineCount() + 1) / 2
            pairLines = pairLines
                .lineSequence()
                .filter { it.isNotBlank() }
                .toList()
                .takeLast(keep)
                .joinToString("\n")
            rebuild()
        }
        if (contextTokens > constraints.maxRollingContextTokens && pairLines.isNotBlank()) {
            pairLines = ""
            rebuild()
        }
        // 3. Safeguards (unresolved reference & resolved entity lines)
        if (contextTokens > constraints.maxRollingContextTokens) {
            unresolvedLines = emptyList()
            rebuild()
        }
        if (contextTokens > constraints.maxRollingContextTokens) {
            resolvedLines = emptyList()
            rebuild()
        }
        // 4. Terms / sheet entries kept last
        while (contextTokens > constraints.maxRollingContextTokens && subset.entries.size > 1) {
            subset = subset.copy(entries = subset.entries.take((subset.entries.size + 1) / 2))
            rebuild()
        }
        if (contextTokens > constraints.maxRollingContextTokens && subset.entries.isNotEmpty()) {
            subset = subset.copy(entries = emptyList())
            rebuild()
        }

        val linesEstimate = held.sumOf { page -> pageLineEstimate(page) }
        val promptTokens = linesEstimate + contextTokens
        counters.promptShapeEnriched++
        counters.profileSubsetFactsMax = maxOf(counters.profileSubsetFactsMax, subset.entries.size)
        logcat(LogPriority.INFO) {
            "TachiyomiAT t924 envelope prompt shape=enriched facts=${subset.entries.size} " +
                "scenes=${if (includeScenes) subset.scenes.size else 0} " +
                "rollingPairs=${pairLineCountIf(pairLines)} rollingPages=${counters.rollingContextPagesMax} " +
                "contextTokens=$contextTokens envelopeId=${envelope.envelopeId}"
        }
        return PreparedChunk(
            chunk = TranslationContextChunk(
                pages = pages,
                blockCount = blockCount,
                rollingContext = rolling,
                glossary = glossary,
                estimatedPromptTokens = promptTokens,
                maxOutputTokens = StreamingChunkPlanner.effectiveOutputCap(
                    promptTokens,
                    envelope.estimatedOutputTokens,
                    constraints,
                    protocol = ContextualRequestProtocol.BATCH_V1,
                    blockCount = blockCount,
                    pageCount = pages.size,
                ),
                protocol = ContextualRequestProtocol.BATCH_V1,
                pageIndexes = pageIndexes,
            ),
            estimatedInputTokens = promptTokens,
        )
    }

    private fun pairLineCountIf(pairLines: String): Int =
        pairLines.lineSequence().count { it.isNotBlank() }

    /** Whole-page authoritativeness re-check (TX-21.3, fresh reads). */
    private fun pageAuthoritativelyDone(pageKey: String, live: PageTranslation): Boolean {
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
         * T934 protocol-parking breaker: after this many CONSECUTIVE
         * PROTOCOL envelopes with ZERO fully-covered pages, the batch falls
         * back to the old pause behavior instead of parking an entire
         * chapter one envelope at a time.
         */
        const val MAX_CONSECUTIVE_ZERO_COMMIT_ENVELOPES = 3
    }
}

/** Typed result of a deterministic suffix re-plan (TX-21.4). */
internal sealed interface ReplanResult {
    data class Ready(val work: EnvelopeDispatchWork) : ReplanResult

    /** No dispatchable page remains: the phase drains honestly. */
    data object NothingPending : ReplanResult

    /** The rebuild failed (store drift / planner rejection): typed pause, never a silent drain. */
    data class Failed(val reason: String) : ReplanResult
}

/**
 * Plan-time dispatch inputs for the envelope phase: the validated
 * [EnvelopePlan] plus, per DISPATCHABLE page, the exact identities TX-21
 * revalidates against. Rebuilt from fresh store state on every re-plan.
 */
internal class EnvelopeDispatchWork(
    val plan: EnvelopePlan,
    /** The SC-10 content fingerprint TX-20 commits carry. */
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
)

/** One pending block's dispatch identity (stable wire id + plan-time OCR identity). */
internal class PlannedBlock(
    val stableBlockId: String,
    val sourceText: String,
    val ocrFingerprint: String,
    val blockIndex: Int,
)
