package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.LeaseAcquisition
import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.StagePatchResult
import eu.kanade.translation.TranslationBlockPatch
import eu.kanade.translation.TranslationStagePatch
import eu.kanade.translation.artifact.ChapterArtifactStore
import eu.kanade.translation.artifact.EnvelopePlan
import eu.kanade.translation.artifact.PlannedEnvelope
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.hasRenderedResult
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
import eu.kanade.translation.translator.contextual.TranslationContextChunk
import eu.kanade.translation.translator.contextual.TranslationContextChunkPlanner
import eu.kanade.translation.translator.contextual.TranslationResponseFaithfulness
import eu.kanade.translation.translator.retry.AiChunkOutcome
import eu.kanade.translation.translator.retry.AiTranslationRetryPolicy
import eu.kanade.translation.translator.retry.translateAiChunkWithAdaptiveRetry
import eu.kanade.translation.ocrBlockFingerprints
import eu.kanade.translation.ocrFingerprint
import eu.kanade.translation.util.ShortHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * T924 Stage-6 slice A (WP6, T924-ST-12 + T924-TX-21/TX-20 + DR-A Option 1):
 * the serial per-envelope translation executor behind FF-01.
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
 *    planned block of that page was accepted); a PROTOCOL-class outcome is
 *    AMBIGUOUS: the ENTIRE envelope response is discarded and nothing
 *    partial ever commits; REFUSAL discards and pauses terminal;
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
        var replans: Int = 0,
    ) {
        fun toMap(): Map<String, Int> = mapOf(
            "envelopesTotal" to envelopesTotal,
            "envelopesDone" to envelopesDone,
            "envelopesSkipped" to envelopesSkipped,
            "envelopesPending" to envelopesPending,
            "envelopeFailures" to envelopeFailures,
            "pagesTranslated" to pagesTranslated,
            "envelopeReplans" to replans,
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
                    store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH)
                    return replanOrPause("page $pageKey lost its live state", pageKey)
                }
                // TX-21.3 skip re-check: the page became committed /
                // manual-authoritative between plan and dispatch.
                if (pageAuthoritativelyDone(pageKey, live)) {
                    store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH)
                    continue
                }
                // TX-21.2 live-revalidate the plan-time identities.
                val drift = revalidationDrift(pageWork, snapshot, live)
                if (drift != null) {
                    // Wave-6 F-W6-1: same release-before-early-return as the
                    // lost-page path above.
                    store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH)
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
                    store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH)
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

            // ---- Dispatch ONE envelope (hard one-in-flight invariant). ----
            val chunk = buildEnvelopeChunk(envelope, held, frontier.rollingContext)
            val metadata = ProviderRequestMetadata(
                key = ProviderRequestKey(
                    backend = work.providerBackend,
                    model = work.providerModel,
                    credentialScope = work.credentialScope,
                ),
                estimatedInputTokens = envelope.estimatedInputTokens,
                reservedOutputTokens = envelope.estimatedOutputTokens,
                operation = "translation_envelope",
                envelopeId = envelope.envelopeId,
                priority = AdmissionPriority.BACKGROUND,
            )
            val outcome = try {
                sublimitGate.executeBatch(metadata) {
                    translateAiChunkWithAdaptiveRetry(
                        translator = textTranslator,
                        chunk = chunk,
                        requestedOutputTokens = envelope.estimatedOutputTokens,
                        profile = providerProfile,
                        label = "t924-${envelope.envelopeId}",
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
                    (outcome is AiChunkOutcome.Terminal &&
                        outcome.failure.kind == ProviderFailureKind.REFUSAL) ->
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

                // AMBIGUOUS_PROTOCOL: parser violations survived the allowed
                // retry budget. The response as a whole is untrustworthy —
                // NOTHING partial commits from it; typed (PROTOCOL / PAUSE).
                ambiguousProtocol ->
                    discardAndPause(
                        held,
                        failure = when (outcome) {
                            is AiChunkOutcome.Terminal -> outcome.failure
                            is AiChunkOutcome.Paused -> outcome.failure
                            is AiChunkOutcome.Complete ->
                                ProviderFailure(
                                    kind = ProviderFailureKind.PROTOCOL,
                                    retryability = ProviderFailureRetryability.PAUSE,
                                    safeSummary = "defensive: complete outcome with uncovered pages",
                                    requestId = outcome.envelopeId,
                                )
                        }.copy(retryability = ProviderFailureRetryability.PAUSE),
                        reason = "T924 envelope ${envelope.envelopeId} ambiguous (protocol); response discarded",
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
        } finally {
            // Any page still held (paused/replan paths) releases its lease.
            held.forEach { page ->
                store.releasePageStageLease(page.pageKey, PageWriteOrigin.BATCH)
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
        return when {
            committable.size == held.size -> EnvelopeDispatchResult.Dispatched
            committable.isEmpty() -> EnvelopeDispatchResult.Skipped
            // Some pages stay pending (MISSING_ONLY): the envelope finished
            // its dispatch; the pending remainder is re-planned on resume.
            else -> EnvelopeDispatchResult.Dispatched
        }
    }

    /**
     * Builds the ONE in-flight context chunk from the revalidated live pages:
     * detached copies only (never the live store objects), wire stable block
     * ids matching the plan, strict BATCH_V1 protocol, rolling context from
     * the gap-free frontier. Existing prompt shapes only (profile-subset /
     * scene enrichment is slice B).
     */
    private fun buildEnvelopeChunk(
        envelope: PlannedEnvelope,
        held: List<HeldPage>,
        rollingContext: String,
    ): TranslationContextChunk {
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
        val base = TranslationContextChunk(
            pages = pages,
            blockCount = blockCount,
            rollingContext = "",
            estimatedPromptTokens = envelope.estimatedInputTokens,
            maxOutputTokens = envelope.estimatedOutputTokens,
            protocol = ContextualRequestProtocol.BATCH_V1,
            pageIndexes = pageIndexes,
        )
        return TranslationContextChunkPlanner.withRollingContext(
            chunk = base,
            rollingContext = rollingContext,
            requestedOutputTokens = envelope.estimatedOutputTokens,
            profile = providerProfile,
            glossary = "",
        )
    }

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
