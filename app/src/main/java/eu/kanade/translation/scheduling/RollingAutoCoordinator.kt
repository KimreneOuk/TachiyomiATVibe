package eu.kanade.translation.scheduling

import eu.kanade.translation.persistence.artifact.AttemptOrigin
import eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics
import eu.kanade.translation.diagnostics.TranslationRunTrace
import eu.kanade.translation.diagnostics.TranslationScheduleState
import eu.kanade.translation.diagnostics.TranslationScheduleTrace
import eu.kanade.translation.diagnostics.TranslationStageSpan
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceLane
import eu.kanade.translation.diagnostics.TranslationTraceMode
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTracePlan
import eu.kanade.translation.diagnostics.TranslationTraceReason
import eu.kanade.translation.diagnostics.TranslationTraceStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.isTranslationDisplayReady
import eu.kanade.translation.orchestration.TranslationSession
import eu.kanade.translation.pipeline.PageWriteOrigin
import eu.kanade.translation.pipeline.batch.ChunkCompletionOutcome
import eu.kanade.translation.translator.ProviderFailure
import eu.kanade.translation.translator.ProviderFailureKind
import eu.kanade.translation.translator.ProviderFailureRetryability
import eu.kanade.translation.translator.TranslatorComputeClass
import eu.kanade.translation.util.ShortHash
import eu.kanade.translation.util.TranslationMemoryBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * TachiyomiAT ticket 03: chapter-scoped rolling auto-translation coordinator.
 *
 * Replaces generation/list-based auto scheduling with one coordinator that
 * continually reconciles the visible page plus exactly N ahead, safely overlaps
 * remote translation work, serializes local compute, and publishes a live
 * [AutoTranslationSnapshot] that always reflects real queue/executor state.
 *
 * Lane model:
 * - One native preparation lane ([TranslationExecutor.prepareSinglePage]) driven
 *   inline by the reconcile loop, so at most one native page prepares at a time.
 * - One translation/render lane ([TranslationExecutor.translatePreparedPage])
 *   driven by a single consumer of a bounded prepared-page channel (capacity 1).
 * - For [TranslatorComputeClass.REMOTE_IO], native B overlaps translation A.
 * - For [TranslatorComputeClass.LOCAL_COMPUTE], a shared compute gate
 *   ([Semaphore](1)) serializes both lanes so ML Kit never overlaps OCR/inpaint.
 *
 * Reconciliation is event-driven, not a busy poll: the loop runs a full pass,
 * admits one page into the native lane when it can, and — when nothing is
 * admissible right now — suspends on [trigger] until a window update, memory
 * recovery, stream availability, or lane completion pokes it. This guarantees
 * the loop never spins and never leaves a queued slot forgotten.
 *
 * The coordinator does NOT own store lifecycle, manual-job arbitration, or
 * batch suppression — those stay with [TranslationScheduler]. It owns only
 * rolling scheduling state, lane execution, and snapshot publication.
 */
class RollingAutoCoordinator(
    private val executor: TranslationExecutor,
    private val computeClass: TranslatorComputeClass,
    private val memoryGate: () -> Boolean = { TranslationMemoryBudget.hasHeadroomForPrefetch() },
    injectedScope: CoroutineScope? = null,
    private val predecessorCoordinators: List<RollingAutoCoordinator> = emptyList(),
    private val ownerVersion: Long = 0L,
    /**
     *  Phase 3 ( §2.3): bound for draining an in-flight paid call after
     * the window is cancelled, instead of tearing it down mid-call. The
     * translate+commit runs under NonCancellable inside this budget; expiry
     * cancels the call cleanly (cancellation-class: the  attempt entry
     * stays unresolved).
     */
    private val drainGraceMs: Long = PROVIDER_DRAIN_GRACE_MS,
) {

    private val ownsScope: Boolean = injectedScope == null
    private val coordinationScope: CoroutineScope =
        injectedScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _snapshot = MutableStateFlow<AutoTranslationSnapshot?>(null)
    val snapshot: StateFlow<AutoTranslationSnapshot?> = _snapshot.asStateFlow()

    private var coordinationJob: Job? = null
    private var currentIdentity: AutoChapterIdentity? = null
    private var generationCounter = 0L
    private var specVersionCounter = 0L

    // Monotonic sequence for transient scheduling state. Snapshot builders
    // capture it with their inputs so a slower same-spec build cannot publish
    // over a later stage mutation.
    private var stateSequence = 0L

    @Volatile
    private var activeGeneration = 0L

    // Every coordinator job remains owned until it really completes. This is
    // what lets immediate cancel/re-arm wait behind a cancelled native call
    // instead of launching a second loop beside it.
    private val ownedCoordinationJobs = mutableSetOf<Job>()

    // Transient scheduling state. slotStates holds the live stage of any page
    // the coordinator has admitted or deferred; publishSnapshot fills the rest
    // (Ready from the durable store, Queued for desired-but-unstarted pages).
    private val slotStates = ConcurrentHashMap<Int, AutoSlotState>()
    private val nativeAdmitted = ConcurrentHashMap.newKeySet<Int>()
    private val translateAdmitted = ConcurrentHashMap.newKeySet<Int>()
    private val completed = ConcurrentHashMap.newKeySet<Int>()

    // Per-page re-prepare attempts after a stale translate handoff
    // (translatePreparedPage == null). Bounded so a page that persistently
    // loses the race or has no durable cleaned image cannot tight-loop the
    // native lane; it flips to Failed(retryable) past the cap instead.
    private val reprepareAttempts = ConcurrentHashMap<Int, Int>()

    // A typed provider pause has already exhausted the current page's finite
    // request budget. Keep it out of the admission set until an external
    // reconcile arrives after the provider's eligible time; the completion
    // callback's internal poke must not immediately start a fresh budget.
    private val pausedTranslations = ConcurrentHashMap<Int, Long>()

    // ------------------------------------------------------------------
    //  Phase 3: correlated trace state (bounded, fail-open; no bitmaps or
    // models are retained — only trace tokens and finished-stage sums).
    // ------------------------------------------------------------------

    /** Current rolling-Auto schedule trace; guarded by [lifecycleLock]. */
    @Volatile
    private var scheduleTrace: TranslationScheduleTrace? = null

    /**
     * Runs created at admission whose terminal has not been observed yet.
     * Swept on every generation death so every started run gets exactly one
     * terminal (amendment §10.2). Keyed by page index (one live run per page).
     */
    private val activeRunTraces = ConcurrentHashMap<Int, TranslationRunTrace>()

    /**
     * The run currently executing under the drain-not-cancel
     * [NonCancellable] block. Exempt from the cancel sweep so a drained
     * in-flight call still reports its real outcome.
     */
    @Volatile
    private var drainingRun: TranslationRunTrace? = null

    @Volatile
    private var currentSpec: WindowSpec? = null

    // Serializes identity/job lifecycle mutations (updateWindow/cancel/shutdown)
    // so concurrent lifecycle calls cannot observe a half-updated identity or
    // launch two coordination loops. synchronized is reentrant, so updateWindow
    // may call the locked helpers while already holding the monitor. poke() is
    // intentionally outside the lock: trySend on a conflated channel is already
    // safe from any thread.
    private val lifecycleLock = Any()

    // Snapshot construction is deliberately split from [lifecycleLock]: page
    // resolvers, store StateFlows, and StateFlow publication are external code
    // and must not run while lifecycle state is being monitored. Lifecycle
    // transitions take this lock before [lifecycleLock], and publication takes
    // it again for the final generation/identity check, so an older build can
    // never overwrite a newer session after that check.
    private val snapshotPublicationLock = Any()

    // Conflated trigger: at most one pending wake-up is ever buffered, so rapid
    // navigation coalesces into a single reconcile pass. send/receive are
    // cancellation-cooperative so cancel() unwinds the loop promptly.
    private val trigger = Channel<Unit>(Channel.CONFLATED)

    /**
     * Updates the desired window. Safe to call repeatedly (rapid navigation);
     * the coordinator re-reconciles on each update. A new [identity] (chapter
     * switch) cancels all prior work and resets transient state. Thread-safe:
     * concurrent updates serialize on [lifecycleLock].
     */
    fun updateWindow(
        identity: AutoChapterIdentity,
        visiblePageIndex: Int,
        configuredAheadTarget: Int,
        pageCount: Int,
        session: TranslationSession,
        pageResolver: (Int) -> PageWorkItem?,
    ) {
        val (jobToStart, _) = synchronized(snapshotPublicationLock) {
            val result = synchronized(lifecycleLock) {
                if (
                    currentIdentity != identity ||
                    currentSpec?.session?.key != session.key ||
                    currentSpec?.session?.store !== session.store
                ) {
                    //  Phase 3: chapter/session identity change closes the
                    // old schedule (and its active runs) as coordinator_replaced
                    // before a fresh schedule starts for the new identity.
                    cancelLocked(TranslationTraceOutcome.COORDINATOR_REPLACED)
                    currentIdentity = identity
                    resetState()
                }
                //  Phase 3: one schedule per rolling-Auto session; repeated
                // viewport updates coalesce into schedule_state events on the
                // SAME schedule instead of opening new ones. Re-created after a
                // cancel/re-arm (the previous schedule was terminally closed).
                val existingSchedule = scheduleTrace
                if (existingSchedule == null || existingSchedule.isClosed) {
                    scheduleTrace = TranslationPipelineDiagnostics.startSchedule(
                        mode = TranslationTraceMode.AUTO,
                        origin = TranslationTraceMode.AUTO,
                        chapterRaw = identity.chapterId?.toString(),
                        pages = pageCount,
                    )
                }
                scheduleTrace?.reportState(
                    state = TranslationScheduleState.QUEUED,
                    reason = TranslationTraceReason.WINDOW_UPDATE.token,
                    queueDepth = translateAdmitted.size,
                    nativeActive = nativeAdmitted.size,
                    providerActive = translateAdmitted.size,
                )
                currentSpec = WindowSpec(
                    identity = identity,
                    visiblePageIndex = visiblePageIndex,
                    configuredAheadTarget = configuredAheadTarget,
                    pageCount = pageCount,
                    session = session,
                    pageResolver = pageResolver,
                    generation = activeGeneration,
                    version = ++specVersionCounter,
                )
                val spec = currentSpec!!
                // Publish the new anchor/version immediately. This projection
                // uses only lifecycle metadata and transient slot state; it
                // deliberately does not call the resolver/store, and marks
                // unknown targets Queued rather than inventing Ready output.
                ensureCoordinationRunningLocked() to buildAnchorSnapshotLocked(spec)
            }
            _snapshot.value = result.second
            result
        }
        jobToStart?.start()
        poke()
    }

    /**
     * Poke the reconciler. Call after a condition that should re-open
     * admission changes: memory budget recovery, a page stream becoming
     * available, or any external signal the loop would not otherwise observe.
     * Cheap and coalesced; safe to call from any thread.
     */
    fun reconcile() {
        releaseMaturedTranslationPauses()
        poke()
    }

    /**
     * Stops all lane work, cancels the coordination loop, and clears transient
     * in-flight presentation + stale retry bookkeeping. The published snapshot
     * is rebuilt from durable store truth (display-ready pages stay Ready), so
     * observers never see a stale Cleaning/Translating stage for work that has
     * stopped. The coordinator can be re-armed with [updateWindow].
     */
    fun cancel() {
        val postCancelGeneration = synchronized(snapshotPublicationLock) {
            synchronized(lifecycleLock) {
                cancelLocked()
                resetState()
                currentSpec = currentSpec?.copy(
                    generation = activeGeneration,
                    version = ++specVersionCounter,
                )
                activeGeneration
            }
        }
        publishSnapshot(postCancelGeneration)
    }

    /** Full teardown: stops work, clears all state, nulls the snapshot. */
    fun shutdown() {
        val shutdownGeneration = synchronized(snapshotPublicationLock) {
            synchronized(lifecycleLock) {
                cancelLocked()
                currentSpec = null
                currentIdentity = null
                resetState()
                activeGeneration
            }
        }
        if (ownsScope) {
            coordinationScope.cancel()
        }
        clearSnapshot(shutdownGeneration)
    }

    /**
     * Blocks until the coordination loop and its translate consumer have fully
     * terminated after [cancel]/[shutdown]. Intended for deterministic test
     * cleanup and diagnostics; production callers need not await.
     */
    suspend fun awaitTermination() {
        while (true) {
            val jobs = synchronized(lifecycleLock) {
                ownedCoordinationJobs.filterNot { it.isCompleted }
            }
            if (jobs.isEmpty()) return
            jobs.joinAll()
        }
    }

    /**
     * Caller holds [lifecycleLock]. Cancels the coordination job only.
     * [terminalOutcome] types the trace sweep: `cancelled` for user cancel /
     * shutdown, `coordinator_replaced` for a chapter/session identity change.
     */
    private fun cancelLocked(
        terminalOutcome: TranslationTraceOutcome = TranslationTraceOutcome.CANCELLED,
    ) {
        coordinationJob?.cancel()
        generationCounter += 1L
        activeGeneration = generationCounter
        sweepTracesLocked(terminalOutcome)
    }

    /**
     *  Phase 3 (amendment §10.2): every started run gets exactly one
     * terminal. Called on generation death under [lifecycleLock]: closes all
     * admitted-but-unclosed runs and the schedule itself. The currently
     * draining run is exempt — its real outcome lands when the drained call
     * finishes (its close is idempotent, so a race cannot double-emit).
     * Non-suspending; safe from the lifecycle monitor.
     */
    private fun sweepTracesLocked(outcome: TranslationTraceOutcome) {
        val drain = drainingRun
        for ((pageIndex, run) in activeRunTraces) {
            if (run === drain) continue
            run.end(outcome)
            activeRunTraces.remove(pageIndex, run)
        }
        val schedule = scheduleTrace
        // A natural teardown of a fully-successful window reports success; any
        // swept/cancelled/failed run keeps the lifecycle outcome honest.
        val scheduleOutcome =
            if (outcome == TranslationTraceOutcome.CANCELLED &&
                schedule != null &&
                schedule.hasNoFailedRuns()
            ) {
                TranslationTraceOutcome.SUCCESS
            } else {
                outcome
            }
        schedule?.end(scheduleOutcome)
        scheduleTrace = null
    }

    /**
     * Caller holds [lifecycleLock]. Returns the lazy job that must be started
     * after the lock is released, or null when an existing job is already
     * active/pending. A replacement first joins every older owned job, not
     * merely the most recent wrapper, so repeated cancel/re-arm calls cannot
     * skip an older uncancellable native operation.
     */
    private fun ensureCoordinationRunningLocked(): Job? {
        val existing = coordinationJob
        if (existing != null && !existing.isCompleted && !existing.isCancelled) {
            return existing
        }

        val predecessors = ownedCoordinationJobs
            .filter { !it.isCompleted }
        val job = coordinationScope.launch(start = CoroutineStart.LAZY) {
            predecessors.joinAll()
            predecessorCoordinators.forEach { it.awaitTermination() }
            coroutineContext.ensureActive()
            runCoordinationLoop()
        }
        ownedCoordinationJobs += job
        coordinationJob = job
        job.invokeOnCompletion {
            synchronized(lifecycleLock) {
                ownedCoordinationJobs.remove(job)
                if (coordinationJob === job) coordinationJob = null
            }
        }
        return job
    }

    private fun resetState() {
        slotStates.clear()
        nativeAdmitted.clear()
        translateAdmitted.clear()
        completed.clear()
        reprepareAttempts.clear()
        pausedTranslations.clear()
        stateSequence++
    }

    private fun poke() {
        trigger.trySend(Unit)
    }

    private suspend fun runCoordinationLoop() = coroutineScope {
        val loopGeneration = synchronized(lifecycleLock) {
            currentSpec?.takeIf { it.generation == activeGeneration }?.generation
        } ?: return@coroutineScope
        val computeGate = if (computeClass.mayOverlapNative) null else Semaphore(1)
        val channelCapacity = eu.kanade.translation.util.TranslationMemoryBudget.recommendedPrefetchCapacity()
        val preparedChannel = Channel<PreparedWork>(capacity = channelCapacity)

        // Translate/render consumer. Launched as a child of this coroutine so
        // cancel() propagates to it and coroutineScope joins it before return.
        launch { consumeTranslations(preparedChannel, computeGate, loopGeneration) }

        try {
            while (true) {
                coroutineContext.ensureActive()
                val spec = synchronized(lifecycleLock) { currentSpec } ?: break
                if (!isGenerationActive(loopGeneration) || spec.generation != loopGeneration) break
                val admitted = reconcilePass(spec, computeGate, preparedChannel)
                if (!admitted) {
                    // Nothing admissible right now (everything is in flight,
                    // completed, failed, or deferred). Suspend until a window,
                    // memory, stream, or completion signal pokes the trigger —
                    // never spin.
                    trigger.receive()
                }
            }
        } finally {
            // Drains the consumer: once the channel closes it finishes any
            // in-flight translate and exits. coroutineScope then waits for it.
            preparedChannel.close()
        }
    }

    /**
     *  Phase 3 ( design §3.2): durable attempt entry BEFORE the auto
     * paid call. Returns a typed Paused outcome when the crash-loop cap
     * refuses the AUTO entry — no provider call is billed in that case, so
     * the ledger and the bill stay consistent. Returns null when the entry
     * was written and the caller must run the call via [runAutoAttempt].
     * Write failures are fail-open (entry skipped, call proceeds).
     */
    private suspend fun recordAutoAttemptStart(work: PreparedWork): ChunkCompletionOutcome? {
        val admitted = runCatching {
            work.session.store.recordAttemptStart(
                pageKey = work.prepared.pageKey,
                providerKeyHash = ShortHash.hash(work.session.key),
                origin = AttemptOrigin.AUTO,
                generation = work.session.store.currentGeneration,
            )
        }.onFailure {
            logcat(LogPriority.WARN) {
                //  Phase 3 migration: raw pageKey removed from the log
                // (privacy contract); the correlated run's pageIndex is kept.
                "TachiyomiAT D9: auto attempt-ledger record failed (fail-open): " +
                    "pageIndex=${work.pageIndex}"
            }
        }.getOrDefault(true)
        if (admitted) return null
        val failure = ProviderFailure(
            kind = ProviderFailureKind.REFUSAL,
            retryability = ProviderFailureRetryability.PAUSE,
            safeSummary = "repeatedly interrupted before completing; manual retry required",
        )
        return ChunkCompletionOutcome.Paused(
            anchorPageKey = work.prepared.pageKey,
            retryablePageKeys = setOf(work.prepared.pageKey),
            failure = failure,
            reason = failure.safeSummary,
        )
    }

    /**
     * The paid call itself: the ledger entry resolves on any COMPLETED call
     * (any returned outcome, or a typed provider failure) and stays
     * unresolved ONLY on cancellation (process death / scope kill).
     */
    private suspend fun runAutoAttempt(work: PreparedWork): ChunkCompletionOutcome? {
        val outcome: ChunkCompletionOutcome? = try {
            executor.translatePreparedPage(
                work.session.manga,
                work.session.chapter,
                work.session.source,
                work.prepared,
                stageListenerFor(work.pageIndex, work.generation),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            resolveAutoAttempt(work)
            throw t
        }
        resolveAutoAttempt(work)
        return outcome
    }

    /** A completed auto call (success or typed provider failure): resolve. */
    private suspend fun resolveAutoAttempt(work: PreparedWork) {
        runCatching { work.session.store.resolveAttempt(work.prepared.pageKey) }
    }

    private suspend fun consumeTranslations(
        preparedChannel: Channel<PreparedWork>,
        computeGate: Semaphore?,
        loopGeneration: Long,
    ) {
        for (work in preparedChannel) {
            coroutineContext.ensureActive()
            //  Phase 3: settle the prepared-queue wait first (even for a
            // stale pickup) — queue-class stage feeds schedule maxQueueMs.
            work.preparedQueueSpan?.end()
            if (!isWorkCurrent(work) || work.generation != loopGeneration) {
                // Reviewed terminal hole (stale pickup before the try): the
                // run still gets exactly one terminal.
                work.trace?.end(TranslationTraceOutcome.STALE_HANDOFF)
                retireRunTrace(work.pageIndex, work.trace)
                continue
            }
            // The prepare phase already set Cleaning; promote to Translating
            // eagerly so the snapshot never shows a stale stage while the
            // provider request is in flight. The stage listener refines this.
            updateSlot(work.pageIndex, AutoSlotState.Translating, work.generation)
            publishSnapshot(work.generation)
            //  Phase 3: provider lane occupancy for the schedule overlap
            // accumulator (N translate overlapping N+1 native prep).
            val providerLaneToken = scheduleTrace?.enterLane(TranslationTraceLane.PROVIDER)
            try {
                if (!isWorkCurrent(work)) {
                    work.trace?.end(TranslationTraceOutcome.STALE_HANDOFF)
                    retireRunTrace(work.pageIndex, work.trace)
                    continue
                }
                //  Phase 3 ( §2.3): drain-not-cancel. The translate +
                // commit runs under NonCancellable inside [drainGraceMs], so a
                // cancelled window lets the in-flight call finish, commit and
                // resolve its  attempt entry instead of stranding the page.
                // The timeout is INNER: expiry cancels the call cleanly and is
                // cancellation-class (the attempt entry stays unresolved). The
                // outcome bookkeeping after the block stays generation-guarded,
                // so a drained result commits even though the window is gone.
                //  Phase 3: drainingRun marks the sweep exemption so a
                // cancelled window cannot race this run's real outcome.
                drainingRun = work.trace
                val translated = withContext(
                    NonCancellable + (work.trace?.let { TranslationTrace.elementFor(it) } ?: kotlin.coroutines.EmptyCoroutineContext),
                ) {
                    withTimeout(drainGraceMs) {
                        if (computeGate != null) {
                            computeGate.withPermit {
                                if (!isWorkCurrent(work)) {
                                    null
                                } else {
                                    recordAutoAttemptStart(work) ?: runAutoAttempt(work)
                                }
                            }
                        } else {
                            if (!isWorkCurrent(work)) {
                                null
                            } else {
                                recordAutoAttemptStart(work) ?: runAutoAttempt(work)
                            }
                        }
                    }
                }
                if (isWorkCurrent(work)) {
                    when (translated) {
                        is ChunkCompletionOutcome.Completed -> {
                            markCompleted(work.pageIndex, work.generation)
                            clearReprepareAttempts(work.pageIndex, work.generation)
                            clearTranslationPause(work.pageIndex, work.generation)
                            updateSlot(work.pageIndex, AutoSlotState.Ready, work.generation)
                        }
                        null -> {
                            // A null result means only a stale/race handoff or a
                            // missing cleaned image (see interface contract): the
                            // caller must re-prepare, never mark Ready. Return the
                            // slot to the admissible set for the next reconcile;
                            // bound retries so a persistently stale handoff cannot
                            // tight-loop the native lane.
                            handleStaleTranslate(work.pageIndex, work.generation)
                        }
                        is ChunkCompletionOutcome.Paused -> {
                            // The provider budget was consumed by this attempt. A
                            // completion poke must not immediately reset that
                            // budget; defer until an external reconcile after the
                            // outcome's eligible time.
                            clearReprepareAttempts(work.pageIndex, work.generation)
                            deferTranslationRetry(
                                pageIndex = work.pageIndex,
                                generation = work.generation,
                                nextEligibleRetryAtEpochMs = translated.nextEligibleRetryAtEpochMs,
                            )
                        }
                        is ChunkCompletionOutcome.Failed -> {
                            clearReprepareAttempts(work.pageIndex, work.generation)
                            val retryable = translated.failure?.retryability !=
                                ProviderFailureRetryability.TERMINAL
                            if (retryable) {
                                deferTranslationRetry(work.pageIndex, work.generation, null)
                            } else {
                                clearTranslationPause(work.pageIndex, work.generation)
                                updateSlot(
                                    work.pageIndex,
                                    AutoSlotState.Failed(retryable = false),
                                    work.generation,
                                )
                            }
                        }
                        is ChunkCompletionOutcome.Unexpected -> {
                            clearReprepareAttempts(work.pageIndex, work.generation)
                            clearTranslationPause(work.pageIndex, work.generation)
                            updateSlot(
                                work.pageIndex,
                                AutoSlotState.Failed(retryable = true),
                                work.generation,
                            )
                        }
                        is ChunkCompletionOutcome.PersistenceRejected -> {
                            clearReprepareAttempts(work.pageIndex, work.generation)
                            clearTranslationPause(work.pageIndex, work.generation)
                            updateSlot(
                                work.pageIndex,
                                AutoSlotState.Failed(retryable = true),
                                work.generation,
                            )
                        }
                    }
                }
                //  Phase 3: exactly one typed terminal for the run, mapped
                // from the translated outcome (also covers the drained result
                // of a window that died mid-flight — slot logic above stayed
                // generation-guarded, the trace still tells the truth).
                work.trace?.end(mapTranslatedTraceOutcome(work, translated))
                retireRunTrace(work.pageIndex, work.trace)
            } catch (e: TimeoutCancellationException) {
                //  Phase 3: drain grace expired — typed timeout. Caught
                // BEFORE the CancellationException catch (it is a subclass);
                // control flow is unchanged: the exception rethrows exactly as
                // before, only after the typed outcome is preserved.
                work.trace?.end(TranslationTraceOutcome.TIMEOUT, error = e)
                retireRunTrace(work.pageIndex, work.trace)
                throw e
            } catch (e: CancellationException) {
                // Consumer cancelled outside the drain block (loop unwind): the
                // run's sweep may already have fired — idempotent close keeps
                // exactly one terminal.
                work.trace?.end(TranslationTraceOutcome.CANCELLED)
                retireRunTrace(work.pageIndex, work.trace)
                throw e
            } catch (e: Throwable) {
                work.trace?.end(TranslationTraceOutcome.FAILURE, error = e)
                retireRunTrace(work.pageIndex, work.trace)
                logcat(LogPriority.ERROR, e) { "Rolling auto translate failed: pageIndex=${work.pageIndex}" }
                if (isWorkCurrent(work)) {
                    clearReprepareAttempts(work.pageIndex, work.generation)
                    updateSlot(work.pageIndex, AutoSlotState.Failed(retryable = true), work.generation)
                }
            } finally {
                drainingRun = null
                providerLaneToken?.close()
                if (isWorkCurrent(work)) {
                    removeTranslateAdmitted(work.pageIndex, work.generation)
                    poke()
                }
            }
            publishSnapshot(work.generation)
        }
    }

    /**
     *  Phase 3: pure, non-suspending mapping of a translate outcome onto
     * the bounded trace terminal. A persistence rejection is discriminated as
     * EVICTED when a MANUAL owner now holds the page lease ( preemption),
     * else PERSISTENCE_REJECTED. `null` (stale handoff / missing cleaned
     * image) maps to stale_handoff.
     */
    private fun mapTranslatedTraceOutcome(
        work: PreparedWork,
        translated: ChunkCompletionOutcome?,
    ): TranslationTraceOutcome = when (translated) {
        is ChunkCompletionOutcome.Completed -> TranslationTraceOutcome.SUCCESS
        null -> TranslationTraceOutcome.STALE_HANDOFF
        is ChunkCompletionOutcome.Paused -> TranslationTraceOutcome.PAUSE
        is ChunkCompletionOutcome.Failed -> TranslationTraceOutcome.FAILURE
        is ChunkCompletionOutcome.Unexpected -> TranslationTraceOutcome.FAILURE
        is ChunkCompletionOutcome.PersistenceRejected -> {
            val owner = runCatching {
                work.session.store.pageLeaseOwner(work.prepared.pageKey)
            }.getOrNull()
            if (owner != null && owner != PageWriteOrigin.AUTO) {
                TranslationTraceOutcome.EVICTED
            } else {
                TranslationTraceOutcome.PERSISTENCE_REJECTED
            }
        }
    }

    /**
     * Handles a stale translate handoff ([TranslationExecutor.translatePreparedPage]
     * returned false). A stale result is transient, so the slot returns to Queued
     * and is re-admitted on the next reconcile. Past [MAX_REPREPARE_ATTEMPTS] the
     * page flips to Failed(retryable) instead of tight-looping the native lane.
     */
    private fun handleStaleTranslate(pageIndex: Int, generation: Long) {
        synchronized(lifecycleLock) {
            if (!isGenerationActiveLocked(generation)) return
            val attempts = reprepareAttempts.merge(pageIndex, 1) { a, b -> a + b } ?: 1
            if (attempts > MAX_REPREPARE_ATTEMPTS) {
                reprepareAttempts.remove(pageIndex)
                slotStates[pageIndex] = AutoSlotState.Failed(retryable = true)
            } else {
                slotStates[pageIndex] = AutoSlotState.Queued
            }
            stateSequence++
        }
    }

    private fun deferTranslationRetry(
        pageIndex: Int,
        generation: Long,
        nextEligibleRetryAtEpochMs: Long?,
    ) {
        synchronized(lifecycleLock) {
            if (!isGenerationActiveLocked(generation)) return
            pausedTranslations[pageIndex] = nextEligibleRetryAtEpochMs
                ?.takeIf { it > System.currentTimeMillis() }
                ?: RETRY_AT_NEXT_RECONCILE
            updateSlot(pageIndex, AutoSlotState.Deferred(AutoDeferralReason.Network), generation)
        }
    }

    private fun clearTranslationPause(pageIndex: Int, generation: Long) {
        synchronized(lifecycleLock) {
            if (isGenerationActiveLocked(generation) && pausedTranslations.remove(pageIndex) != null) {
                stateSequence++
            }
        }
    }

    /**
     * Opens provider-paused slots only after an external reconcile and after
     * their persisted/provider retry timestamp. The internal lane-completion
     * poke deliberately does not call this method.
     */
    private fun releaseMaturedTranslationPauses() {
        synchronized(lifecycleLock) {
            val now = System.currentTimeMillis()
            val released = pausedTranslations.keys.toList().filter { pageIndex ->
                val retryAt = pausedTranslations[pageIndex] ?: return@filter false
                retryAt == RETRY_AT_NEXT_RECONCILE || retryAt <= now
            }
            if (released.isNotEmpty()) {
                released.forEach(pausedTranslations::remove)
                stateSequence++
            }
        }
    }

    /**
     * One reconciliation pass. Returns true when a page was admitted into the
     * native lane (the caller should immediately re-reconcile for the next
     * slot); false when nothing is admissible right now and the loop should
     * suspend on the trigger.
     */
    private suspend fun reconcilePass(
        spec: WindowSpec,
        computeGate: Semaphore?,
        preparedChannel: Channel<PreparedWork>,
    ): Boolean {
        if (!isGenerationActive(spec.generation)) return false
        val bounds = AutoWindowBounds(
            visiblePageIndex = spec.visiblePageIndex,
            configuredAheadTarget = spec.configuredAheadTarget,
            pageCount = spec.pageCount,
        )
        val desired = computeDesiredSet(spec, bounds)
        evictObsolete(desired, spec.generation)
        if (!isGenerationActive(spec.generation)) return false
        publishSnapshot(spec.generation)

        // Memory pressure pauses background prefetch only: the visible
        // foreground page stays highest priority and bypasses this headroom
        // gate, while hard per-page decode safety stays inside the translation
        // pipeline. Sort once and reuse for the deferral + admission scans.
        val memoryOk = memoryGate()
        val orderedDesired = desired.sorted()
        if (!memoryOk) {
            for (idx in orderedDesired) {
                if (idx == spec.visiblePageIndex) continue
                if (isAdmissible(idx) &&
                    slotStates[idx]?.deferralReason != AutoDeferralReason.Memory
                ) {
                    updateSlot(idx, AutoSlotState.Deferred(AutoDeferralReason.Memory), spec.generation)
                    scheduleTrace?.reportState(
                        state = TranslationScheduleState.DEFERRED,
                        reason = TranslationTraceReason.MEMORY_PRESSURE.token,
                        queueDepth = translateAdmitted.size,
                        nativeActive = nativeAdmitted.size,
                        providerActive = translateAdmitted.size,
                    )
                }
            }
            publishSnapshot(spec.generation)
        }

        for (idx in orderedDesired) {
            if (!isGenerationActive(spec.generation)) return false
            if (!isAdmissible(idx)) continue
            val isForeground = idx == spec.visiblePageIndex
            if (!memoryOk && !isForeground) continue

            val item = spec.pageResolver(idx)
            if (item == null) {
                // Stream not available yet. Surface the deferral and keep
                // scanning — a later page may still be eligible. The slot is
                // retried when a stream-available signal pokes reconcile().
                if (slotStates[idx]?.deferralReason != AutoDeferralReason.SourceUnavailable) {
                    updateSlot(idx, AutoSlotState.Deferred(AutoDeferralReason.SourceUnavailable), spec.generation)
                    scheduleTrace?.reportState(
                        state = TranslationScheduleState.DEFERRED,
                        reason = TranslationTraceReason.SOURCE_UNAVAILABLE.token,
                        queueDepth = translateAdmitted.size,
                        nativeActive = nativeAdmitted.size,
                        providerActive = translateAdmitted.size,
                    )
                    publishSnapshot(spec.generation)
                }
                continue
            }

            // Admit into the native lane (serialized inline by this loop).
            if (!markNativeAdmitted(idx, spec.generation)) return false
            //  Phase 3: the page run starts at native admission. The trace
            // element wraps the prepare so deep ONNX/OCR code correlates its
            // stages with this run across suspension points. scheduleTrace is
            // null when tracing is off → every call below fails open.
            val runTrace = scheduleTrace?.let {
                TranslationPipelineDiagnostics.startRun(
                    schedule = it,
                    pageRaw = item.pageKey,
                    pageIndex = idx,
                    plan = TranslationTracePlan.FRESH,
                )
            }
            //  Phase 4 (Phase 3 review F1): the run joins the terminal-sweep
            // registry and the generation liveness re-check ATOMICALLY under the
            // SAME lock the cancel sweep holds. A sweep can therefore never
            // interleave between admission and registration: either the
            // generation is alive (the run is registered and the sweep owns its
            // terminal), or the generation is already dead (the sweep already
            // ran and never saw this run, so it closes + retires it locally —
            // idempotent even if a sweep raced the put).
            var generationDeadAtRegistration = false
            synchronized(lifecycleLock) {
                if (isGenerationActiveLocked(spec.generation)) {
                    if (runTrace != null) activeRunTraces[idx] = runTrace
                } else {
                    generationDeadAtRegistration = true
                }
            }
            if (generationDeadAtRegistration) {
                runTrace?.end(TranslationTraceOutcome.CANCELLED)
                if (runTrace != null) activeRunTraces.remove(idx, runTrace)
                removeNativeAdmitted(idx, spec.generation)
                return false
            }
            scheduleTrace?.reportState(
                state = TranslationScheduleState.ADMITTED,
                reason = TranslationTraceReason.ADMITTED.token,
                queueDepth = translateAdmitted.size,
                nativeActive = nativeAdmitted.size,
                providerActive = translateAdmitted.size,
            )
            val nativeLaneToken = scheduleTrace?.enterLane(TranslationTraceLane.NATIVE)
            var sendingPrepared = false
            try {
                if (!isGenerationActive(spec.generation)) return false
                val traceContext: kotlin.coroutines.CoroutineContext =
                    runTrace?.let { TranslationTrace.elementFor(it) } ?: kotlin.coroutines.EmptyCoroutineContext
                val prepared = withContext(traceContext) {
                    if (computeGate != null) {
                        computeGate.withPermit {
                            if (!isGenerationActive(spec.generation)) {
                                null
                            } else {
                                executor.prepareSinglePage(
                                    spec.session.manga,
                                    spec.session.chapter,
                                    spec.session.source,
                                    item.pageKey,
                                    item.streamFn,
                                    force = false,
                                    stageListenerFor(idx, spec.generation),
                                )
                            }
                        }
                    } else {
                        if (!isGenerationActive(spec.generation)) {
                            null
                        } else {
                            executor.prepareSinglePage(
                                spec.session.manga,
                                spec.session.chapter,
                                spec.session.source,
                                item.pageKey,
                                item.streamFn,
                                force = false,
                                stageListenerFor(idx, spec.generation),
                            )
                        }
                    }
                }
                if (!isGenerationActive(spec.generation)) return false
                when {
                    prepared == null -> {
                        runTrace?.end(TranslationTraceOutcome.FAILURE)
                        retireRunTrace(idx, runTrace)
                        clearReprepareAttempts(idx, spec.generation)
                        updateSlot(idx, AutoSlotState.Failed(retryable = true), spec.generation)
                    }
                    prepared.isTerminal -> {
                        // Terminal prepared page: textless, render-only resume
                        // already complete, or a no-op resume — no run work left.
                        runTrace?.end(TranslationTraceOutcome.SKIP)
                        retireRunTrace(idx, runTrace)
                        markCompleted(idx, spec.generation)
                        clearReprepareAttempts(idx, spec.generation)
                        updateSlot(idx, AutoSlotState.Ready, spec.generation)
                    }
                    else -> {
                        // Mark handed-off BEFORE the send so a concurrent
                        // reconcile cannot re-admit this page between the
                        // native lane releasing it and the translate lane
                        // picking it up (the duplicate-execution race).
                        if (markTranslateAdmitted(idx, spec.generation)) {
                            // Prepared-queue wait = send→consumer-pickup. The
                            // span is settled by the consumer; the run terminal
                            // passes to the consumer as well.
                            val preparedQueueSpan = runTrace?.beginStage(
                                TranslationTraceStage.PREPARED_QUEUE,
                            )
                            sendingPrepared = true
                            preparedChannel.send(
                                PreparedWork(
                                    pageIndex = idx,
                                    prepared = prepared,
                                    identity = spec.identity,
                                    session = spec.session,
                                    generation = spec.generation,
                                    trace = runTrace,
                                    preparedQueueSpan = preparedQueueSpan,
                                ),
                            )
                        } else {
                            // Window died between prepare and handoff: the
                            // cancel sweep already typed this run; the
                            // idempotent close keeps the guarantee.
                            runTrace?.end(TranslationTraceOutcome.CANCELLED)
                            retireRunTrace(idx, runTrace)
                            return false
                        }
                    }
                }
            } catch (e: CancellationException) {
                if (sendingPrepared) {
                    // Cancelled while parked handing off (channel full / loop
                    // unwind): the work never reached the translate consumer.
                    runTrace?.end(TranslationTraceOutcome.CANCELLED_DURING_SEND)
                    retireRunTrace(idx, runTrace)
                }
                throw e
            } catch (e: Throwable) {
                runTrace?.end(TranslationTraceOutcome.FAILURE, error = e)
                retireRunTrace(idx, runTrace)
                logcat(LogPriority.ERROR, e) { "Rolling auto prepare failed: pageIndex=$idx" }
                if (isGenerationActive(spec.generation)) {
                    clearReprepareAttempts(idx, spec.generation)
                    updateSlot(idx, AutoSlotState.Failed(retryable = true), spec.generation)
                }
            } finally {
                nativeLaneToken?.close()
                removeNativeAdmitted(idx, spec.generation)
            }
            publishSnapshot(spec.generation)
            return true
        }
        return false
    }

    /**
     * Eligible to be admitted into the native lane now: desired, not currently
     * in either lane, not completed, not provider-paused, and not already
     * terminally Failed. Retryable typed failures are represented as Deferred
     * until an explicit reconcile re-opens them.
     */
    private fun isAdmissible(idx: Int): Boolean =
        idx !in nativeAdmitted &&
            idx !in translateAdmitted &&
            idx !in completed &&
            !pausedTranslations.containsKey(idx) &&
            slotStates[idx] !is AutoSlotState.Failed

    private fun computeDesiredSet(spec: WindowSpec, bounds: AutoWindowBounds): Set<Int> {
        val desired = mutableSetOf<Int>()
        val store = spec.session.store
        if (bounds.hasVisiblePage) {
            val visibleItem = spec.pageResolver(spec.visiblePageIndex)
            val visiblePage = visibleItem?.let { store.state.value[it.pageKey] }
            if (visibleItem == null || visiblePage == null || !visiblePage.isTranslationDisplayReady) {
                desired.add(spec.visiblePageIndex)
            }
        }
        for (index in bounds.aheadPageIndices) {
            val item = spec.pageResolver(index)
            val page = item?.let { store.state.value[it.pageKey] }
            if (item == null || page == null || !page.isTranslationDisplayReady) {
                desired.add(index)
            }
        }
        return desired
    }

    private fun evictObsolete(desired: Set<Int>, generation: Long) {
        // Evict transient bookkeeping for pages no longer desired. Started work
        // may finish: keep slots still owned by a lane so their durable result
        // is retained; drop them on the next pass once done. reprepareAttempts
        // is cleared for any leaving page so a page that re-enters the window
        // starts its stale-retry budget fresh.
        synchronized(lifecycleLock) {
            if (!isGenerationActiveLocked(generation)) return
            val leaving = mutableSetOf<Int>().apply {
                addAll(slotStates.keys)
                addAll(reprepareAttempts.keys)
                addAll(pausedTranslations.keys)
            }.filter { it !in desired && it !in nativeAdmitted && it !in translateAdmitted }
            for (idx in leaving) {
                slotStates.remove(idx)
                completed.remove(idx)
                reprepareAttempts.remove(idx)
                pausedTranslations.remove(idx)
            }
            if (leaving.isNotEmpty()) stateSequence++
        }
    }

    private fun isGenerationActive(generation: Long): Boolean =
        synchronized(lifecycleLock) { isGenerationActiveLocked(generation) }

    private fun isGenerationActiveLocked(generation: Long): Boolean =
        activeGeneration == generation && currentSpec?.generation == generation

    private fun isWorkCurrent(work: PreparedWork): Boolean =
        synchronized(lifecycleLock) {
            isGenerationActiveLocked(work.generation) &&
                currentSpec?.identity == work.identity &&
                currentSpec?.session?.key == work.session.key &&
                currentSpec?.session?.store === work.session.store
        }

    private fun updateSlot(pageIndex: Int, state: AutoSlotState, generation: Long) {
        synchronized(lifecycleLock) {
            if (!isGenerationActiveLocked(generation)) return
            if (slotStates[pageIndex] != state) {
                slotStates[pageIndex] = state
                stateSequence++
            }
        }
    }

    private fun markNativeAdmitted(pageIndex: Int, generation: Long): Boolean =
        synchronized(lifecycleLock) {
            if (!isGenerationActiveLocked(generation)) return false
            if (nativeAdmitted.add(pageIndex)) stateSequence++
            true
        }

    private fun removeNativeAdmitted(pageIndex: Int, generation: Long) {
        synchronized(lifecycleLock) {
            if (isGenerationActiveLocked(generation) && nativeAdmitted.remove(pageIndex)) {
                stateSequence++
            }
        }
    }

    private fun markTranslateAdmitted(pageIndex: Int, generation: Long): Boolean =
        synchronized(lifecycleLock) {
            if (!isGenerationActiveLocked(generation)) return false
            if (translateAdmitted.add(pageIndex)) stateSequence++
            true
        }

    private fun removeTranslateAdmitted(pageIndex: Int, generation: Long) {
        synchronized(lifecycleLock) {
            if (isGenerationActiveLocked(generation) && translateAdmitted.remove(pageIndex)) {
                stateSequence++
            }
        }
    }

    private fun markCompleted(pageIndex: Int, generation: Long) {
        synchronized(lifecycleLock) {
            if (isGenerationActiveLocked(generation) && completed.add(pageIndex)) {
                stateSequence++
            }
        }
    }

    /** Removes a finished run from the terminal-ownership sweep registry. */
    private fun retireRunTrace(pageIndex: Int, run: TranslationRunTrace?) {
        if (run != null) activeRunTraces.remove(pageIndex, run)
    }

    private fun clearReprepareAttempts(pageIndex: Int, generation: Long) {
        synchronized(lifecycleLock) {
            if (isGenerationActiveLocked(generation) && reprepareAttempts.remove(pageIndex) != null) {
                stateSequence++
            }
        }
    }

    /**
     * Builds the live snapshot from [slotStates] plus the durable store so it
     * tells the truth without a reconcile pass: ahead slots form the full
     * consecutive prefix [visiblePageIndex+1 .. visiblePageIndex+target], and a
     * display-ready page counts as Ready even if the coordinator never touched
     * it. readyAheadCount therefore always matches durable display results.
     */
    private fun publishSnapshot(expectedGeneration: Long? = null) {
        val inputs = synchronized(lifecycleLock) {
            val spec = currentSpec ?: return
            if (expectedGeneration != null && !isGenerationActiveLocked(expectedGeneration)) return
            SnapshotInputs(
                spec = spec,
                slotStates = slotStates.toMap(),
                stateSequence = stateSequence,
            )
        }
        val snapshot = buildSnapshot(inputs)

        // The generation/identity check and StateFlow assignment are serialized
        // with lifecycle transitions, but the assignment itself is intentionally
        // outside lifecycleLock: MutableStateFlow dispatch is external code.
        synchronized(snapshotPublicationLock) {
            val stillCurrent = synchronized(lifecycleLock) {
                isSnapshotCurrentLocked(inputs)
            }
            if (stillCurrent) {
                _snapshot.value = snapshot
            }
        }
    }

    private fun clearSnapshot(expectedGeneration: Long) {
        synchronized(snapshotPublicationLock) {
            val stillShutdown = synchronized(lifecycleLock) {
                activeGeneration == expectedGeneration && currentSpec == null && currentIdentity == null
            }
            if (stillShutdown) {
                _snapshot.value = null
            }
        }
    }

    private fun isSnapshotCurrentLocked(inputs: SnapshotInputs): Boolean =
        activeGeneration == inputs.spec.generation &&
            currentIdentity == inputs.spec.identity &&
            currentSpec?.generation == inputs.spec.generation &&
            currentSpec?.version == inputs.spec.version &&
            currentSpec?.session?.key == inputs.spec.session.key &&
            currentSpec?.session?.store === inputs.spec.session.store &&
            stateSequence == inputs.stateSequence

    private fun buildSnapshot(inputs: SnapshotInputs): AutoTranslationSnapshot {
        val spec = inputs.spec
        val bounds = AutoWindowBounds(
            visiblePageIndex = spec.visiblePageIndex,
            configuredAheadTarget = spec.configuredAheadTarget,
            pageCount = spec.pageCount,
        )
        val visibleSlot = if (!bounds.hasVisiblePage) {
            null
        } else {
            // foreground is the live work slot only. A display-ready visible
            // page needs no auto work, so foreground == null (contract L3) —
            // never synthesize a Ready foreground from the store. A not-ready
            // visible page that has not been admitted yet is modeled Queued so
            // the reader sees immediate acknowledgement.
            inputs.slotStates[spec.visiblePageIndex]?.let { AutoWindowSlot(spec.visiblePageIndex, it) }
                ?: if (needsAutoWork(spec, spec.visiblePageIndex)) {
                    AutoWindowSlot(spec.visiblePageIndex, AutoSlotState.Queued)
                } else {
                    null
                }
        }
        val aheadSlots = bounds.aheadPageIndices.map { idx ->
            AutoWindowSlot(idx, aheadSlotState(spec, idx, inputs.slotStates))
        }
        return AutoTranslationSnapshot(
            identity = spec.identity,
            visiblePageIndex = spec.visiblePageIndex,
            configuredAheadTarget = spec.configuredAheadTarget,
            availableAheadTarget = bounds.availableAheadTarget,
            foreground = visibleSlot,
            aheadSlots = aheadSlots,
            ownerVersion = ownerVersion,
            windowVersion = spec.version,
        )
    }

    /**
     * Builds the synchronous navigation projection without invoking resolver,
     * store, or StateFlow code while [lifecycleLock] is held. The asynchronous
     * publication path replaces conservative Queued states with durable truth.
     */
    private fun buildAnchorSnapshotLocked(spec: WindowSpec): AutoTranslationSnapshot {
        val bounds = AutoWindowBounds(
            visiblePageIndex = spec.visiblePageIndex,
            configuredAheadTarget = spec.configuredAheadTarget,
            pageCount = spec.pageCount,
        )
        val visibleSlot = if (bounds.hasVisiblePage) {
            AutoWindowSlot(
                spec.visiblePageIndex,
                slotStates[spec.visiblePageIndex] ?: AutoSlotState.Queued,
            )
        } else {
            null
        }
        val aheadSlots = bounds.aheadPageIndices.map { idx ->
            AutoWindowSlot(idx, slotStates[idx] ?: AutoSlotState.Queued)
        }
        return AutoTranslationSnapshot(
            identity = spec.identity,
            visiblePageIndex = spec.visiblePageIndex,
            configuredAheadTarget = spec.configuredAheadTarget,
            availableAheadTarget = bounds.availableAheadTarget,
            foreground = visibleSlot,
            aheadSlots = aheadSlots,
            ownerVersion = ownerVersion,
            windowVersion = spec.version,
        )
    }

    private fun aheadSlotState(
        spec: WindowSpec,
        idx: Int,
        capturedSlotStates: Map<Int, AutoSlotState>,
    ): AutoSlotState {
        capturedSlotStates[idx]?.let { return it }
        val storeState = storePage(spec, idx)
        if (storeState != null && storeState.isTranslationDisplayReady) {
            return AutoSlotState.Ready
        }
        // Desired-but-unstarted ahead page. The rolling model always targets a
        // consecutive prefix, so this keeps aheadSlots a prefix from visible+1.
        return AutoSlotState.Queued
    }

    private fun needsAutoWork(spec: WindowSpec, idx: Int): Boolean {
        val storeState = storePage(spec, idx)
        return storeState == null || !storeState.isTranslationDisplayReady
    }

    private fun storePage(spec: WindowSpec, idx: Int): PageTranslation? {
        val key = spec.pageResolver(idx)?.pageKey ?: return null
        return spec.session.store.state.value[key]
    }

    private fun stageListenerFor(pageIndex: Int, generation: Long): TranslationStageListener =
        TranslationStageListener { _, stage ->
            synchronized(lifecycleLock) {
                if (!isGenerationActiveLocked(generation)) return@TranslationStageListener
                // Never downgrade a slot that has already reached Ready: a late
                // stage callback from a finishing executor must not regress the
                // snapshot.
                if (slotStates[pageIndex]?.isReady == true) return@TranslationStageListener
                val mapped = when (stage) {
                    TranslationStageEvent.READING -> AutoSlotState.ReadingText
                    TranslationStageEvent.CLEANING -> AutoSlotState.Cleaning
                    TranslationStageEvent.TRANSLATING -> AutoSlotState.Translating
                    TranslationStageEvent.RENDERING -> AutoSlotState.Rendering
                }
                if (slotStates[pageIndex] != mapped) {
                    slotStates[pageIndex] = mapped
                    stateSequence++
                }
            }
            publishSnapshot(generation)
        }

    private data class WindowSpec(
        val identity: AutoChapterIdentity,
        val visiblePageIndex: Int,
        val configuredAheadTarget: Int,
        val pageCount: Int,
        val session: TranslationSession,
        val pageResolver: (Int) -> PageWorkItem?,
        val generation: Long,
        val version: Long,
    )

    private data class SnapshotInputs(
        val spec: WindowSpec,
        val slotStates: Map<Int, AutoSlotState>,
        val stateSequence: Long,
    )

    private data class PreparedWork(
        val pageIndex: Int,
        val prepared: PreparedPage,
        val identity: AutoChapterIdentity,
        val session: TranslationSession,
        val generation: Long,
        //  Phase 3: the run's terminal is owned by the translate consumer
        // once the handoff completes; the queue span measures send→pickup.
        val trace: TranslationRunTrace? = null,
        val preparedQueueSpan: TranslationStageSpan? = null,
    )

    /** One page's identity and stream, resolvable by page index. */
    data class PageWorkItem(
        val pageKey: String,
        val streamFn: (() -> InputStream)?,
    )

    companion object {
        /**
         *  Phase 3 ( §2.3): production drain grace. Bounded so a hung
         * provider call still cancels cleanly; long enough that an in-flight
         * call normally finishes and commits even after the window is gone.
         *
         *  Phase 4 ( §1.6): aligned to the drained call's legitimate
         * budget (ONNX <= 90 s + HTTP/render <= 120 s, sequential): a shorter
         * grace would cut a healthy long call cancellation-class mid-chain and
         * strand its attempt entry unresolved.
         */
        const val PROVIDER_DRAIN_GRACE_MS = 210_000L

        /** Sentinel for a pause that may be retried at the next reconcile. */
        private const val RETRY_AT_NEXT_RECONCILE = Long.MIN_VALUE

        /**
         * Max re-prepare attempts after a stale translate handoff before the
         * slot flips to Failed(retryable). Stale handoffs are transient races,
         * so a small cap is enough; the pipeline's own retry exhaustion is the
         * ultimate backstop for genuinely broken pages.
         */
        const val MAX_REPREPARE_ATTEMPTS = 3
    }
}
