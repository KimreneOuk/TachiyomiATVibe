package eu.kanade.translation.scheduling

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics
import eu.kanade.translation.diagnostics.TranslationRunTrace
import eu.kanade.translation.diagnostics.TranslationScheduleTrace
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceMode
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceStage
import eu.kanade.translation.engines.translator.AdmissionPriority
import eu.kanade.translation.engines.translator.LivePriorityHandle
import eu.kanade.translation.engines.translator.TranslatorComputeClass
import eu.kanade.translation.model.PageRejectionKind
import eu.kanade.translation.model.SinglePageOutcome
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.persistence.chapter.PageWriteOrigin
import eu.kanade.translation.pipeline.adaptive.DevicePermitPriority
import eu.kanade.translation.pipeline.adaptive.DevicePriorityHandle
import eu.kanade.translation.pipeline.execution.TranslationExecutor
import eu.kanade.translation.pipeline.execution.TranslationStageEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

enum class ReaderPriority { CURRENT_PAGE, PREFETCH_NEXT }
enum class ReaderTrigger { MANUAL_TAP, AUTO_VIEWPORT }
data class ReaderSessionToken(val chapterIdentity: String, val readerGeneration: Long)

/** Captures a fresh-stream factory, never an open stream. Null means source-unavailable. */
data class ReaderPageWork(val pageKey: String, val readerPageIndex: Int, val streamFn: (() -> InputStream)?)

sealed interface ReaderSubmission {
    data class Queued(val pageKey: String) : ReaderSubmission
    data class Promoted(val pageKey: String) : ReaderSubmission
    data class Existing(val outcome: SinglePageOutcome?) : ReaderSubmission
    data class Rejected(val outcome: SinglePageOutcome.Rejected) : ReaderSubmission
}

sealed interface ReaderDrainResult {
    data object Completed : ReaderDrainResult

    /** Completes after all registered worker jobs, including native stragglers, physically exit. */
    data class PendingNativeDrain(val completion: CompletableDeferred<Unit>) : ReaderDrainResult
}

/**
 * One control monitor protects demand state and worker reservations. Factories, time reads,
 * cancellation, completion callbacks and job starts run outside it. Deferred attempts require
 * a later external event; physical slot release only advances demand not yet attempted there.
 */
class ReaderTranslationQueue(
    private val executor: TranslationExecutor,
    private val scope: CoroutineScope,
    private val workerDispatcher: CoroutineDispatcher,
    private val storeResolver: TranslationStoreResolver,
    private val nowEpochMs: () -> Long,
    private val cancellationWaitMs: Long = 2_000L,
    private val memoryGate: () -> Boolean = { true },
) {
    companion object {
        const val TERMINAL_OUTCOME_RETENTION = 32
        const val EXPLICIT_BACKLOG_LIMIT = 8
        const val AUTOMATIC_PHYSICAL_LIMIT = 2
    }

    private enum class State { QUEUED, RUNNING, DONE, CANCELLED }
    private enum class Gate { PROCEED, DEFER, DISPLAY_READY, ABORT }

    private class Binding(
        val token: ReaderSessionToken,
        val manga: Manga,
        val chapter: Chapter,
        val source: HttpSource,
        val computeClass: TranslatorComputeClass,
        val epoch: Long,
    ) {
        var accepting = true
        var autoEnabled = false
        val outcomes = MutableStateFlow<Map<String, SinglePageOutcome>>(emptyMap())
    }

    private class Demand(
        val binding: Binding,
        var work: ReaderPageWork,
        var explicit: Boolean,
        val force: Boolean,
        priority: ReaderPriority,
    ) {
        val paidCall = if (!explicit) OperationPaidCallLifecycle() else null
        var retainedForDrain = false
        var forceCancelled = false
        var drainTimer: Job? = null
        val priority = AtomicReference(priority)
        val stage = AtomicReference<TranslationStageEvent?>(null)

        /** Live governor handle; promotion re-reads priority at selection. */
        val liveHandle = LivePriorityHandle(
            if (priority == ReaderPriority.CURRENT_PAGE) AdmissionPriority.INTERACTIVE else AdmissionPriority.BACKGROUND,
        )

        /** Live device-permit handle; promotion restarts a fresh foreground window. */
        val deviceHandle = DevicePriorityHandle(
            if (priority == ReaderPriority.CURRENT_PAGE) DevicePermitPriority.FOREGROUND else DevicePermitPriority.BACKGROUND,
        )

        /** Promotes an already-waiting operation in place; never restarts work. */
        fun promoteHandles() {
            liveHandle.promote()
            deviceHandle.promote()
        }

        /** Why this demand is currently deferred; null while queued or running. */
        @Volatile
        var deferral: AutoDeferralReason? = null
        var state = State.QUEUED
        var job: Job? = null
        var enteredBody = false
        var deferredAtEvent = -1L
        var retryAt = 0L
        var traceOutcome = TranslationTraceOutcome.TEARDOWN_EXCEPTION
        val trace by lazy {
            val schedule = TranslationPipelineDiagnostics.startSchedule(
                mode = TranslationTraceMode.MANUAL,
                origin = if (explicit) TranslationTraceMode.MANUAL else TranslationTraceMode.AUTO,
                chapterRaw = binding.chapter.name,
                pages = 1,
            )
            OperationTrace(
                schedule,
                TranslationPipelineDiagnostics.startRun(
                    schedule = schedule,
                    pageRaw = work.pageKey,
                    pageIndex = work.readerPageIndex.takeIf { it >= 0 },
                ),
            )
        }
    }

    private data class StopAction(
        val demand: Demand,
        val worker: Job?,
        val retainedDeadline: Long? = null,
    )

    private class OperationTrace(val schedule: TranslationScheduleTrace, val run: TranslationRunTrace) {
        fun end(outcome: TranslationTraceOutcome) {
            run.end(outcome)
            schedule.end(outcome)
        }
    }

    private val control = Any()
    private var bound: Binding? = null
    private var epoch = 0L
    private var revision = 0L
    private var event = 0L
    private var closed = false
    private val pending = LinkedHashMap<String, Demand>()
    private val liveWorkers = LinkedHashMap<Job, Demand>()
    private val terminals = LinkedHashMap<String, SinglePageOutcome>()
    private var drainSignal: CompletableDeferred<Unit>? = null
    private val localPermit = Semaphore(1)
    private val staleOutcomes = MutableStateFlow<Map<String, SinglePageOutcome>>(emptyMap()).asStateFlow()

    /**
     * Bumped on every demand-lifecycle mutation (submit, defer, stage event,
     * terminal, cancel). The temporary snapshot adapter re-derives full truth
     * through [observeSlots] on every bump instead of tracking incremental
     * per-binding flows.
     */
    private val slotEvents = MutableStateFlow(0L)

    private fun pokeSlots() {
        slotEvents.value = slotEvents.value + 1L
    }

    /** Monotonic demand-mutation signal for observers of [observeSlots]. */
    val slotEventFlow: StateFlow<Long> = slotEvents.asStateFlow()

    /**
     * Atomic queue truth for one bound session: live demands plus retained
     * terminal outcomes. Stale or unbound sessions observe empty views.
     */
    fun observeSlots(session: ReaderSessionToken): ReaderQueueView = synchronized(control) {
        val b = bound?.takeIf { it.token == session }
            ?: return ReaderQueueView(emptyMap(), emptyMap())
        val now = nowEpochMs()
        val live = (pending.values + liveWorkers.values).distinct()
            .filter { it.binding === b && (it.state == State.QUEUED || it.state == State.RUNNING) }
            .associate { demand -> demand.work.pageKey to demand.observationLocked(now) }
        ReaderQueueView(live, b.outcomes.value)
    }

    private fun Demand.observationLocked(now: Long): ReaderSlotObservation {
        val phase = when {
            state == State.RUNNING -> ReaderSlotPhase.RUNNING
            retryAt > now || work.streamFn == null ||
                (deferral != null && deferredAtEvent >= event) -> ReaderSlotPhase.DEFERRED
            else -> ReaderSlotPhase.QUEUED
        }
        val reason = when {
            phase != ReaderSlotPhase.DEFERRED -> null
            work.streamFn == null -> AutoDeferralReason.SourceUnavailable
            else -> deferral
        }
        return ReaderSlotObservation(
            pageKey = work.pageKey,
            readerPageIndex = work.readerPageIndex,
            explicit = explicit,
            phase = phase,
            stage = stage.get(),
            deferral = reason,
        )
    }

    fun bindSession(
        session: ReaderSessionToken,
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        computeClass: TranslatorComputeClass,
    ) {
        require(session.chapterIdentity.isNotEmpty())
        require(session.readerGeneration >= 0)
        val now = nowEpochMs()
        val cancellations = synchronized(control) {
            check(!closed) { "reader queue is closed" }
            val old = bound
            old?.accepting = false
            val jobs = cancelLocked(preservePaid = true, now = now) { it.binding === old }
            epoch++
            revision++
            event++
            bound = Binding(session, manga, chapter, source, computeClass, epoch)
            terminals.clear()
            jobs
        }
        cancelOutside(cancellations)
        pokeSlots()
    }

    fun submitCurrent(
        session: ReaderSessionToken,
        work: ReaderPageWork,
        trigger: ReaderTrigger,
        force: Boolean = false,
    ): ReaderSubmission {
        val now = nowEpochMs()
        val retired = mutableListOf<StopAction>()
        val result = synchronized(control) {
            val b = currentLocked(session) ?: return rejectedSession()
            event++
            val explicit = trigger == ReaderTrigger.MANUAL_TAP
            val existing = pending[work.pageKey]
            if (existing != null) {
                if (existing.state == State.QUEUED) existing.work = work
                when {
                    force && existing.force && existing.state == State.QUEUED ->
                        ReaderSubmission.Queued(work.pageKey)
                    force -> {
                        if (explicitCountLocked() >= EXPLICIT_BACKLOG_LIMIT) return capacityRejection()
                        val replacement = Demand(b, work, true, true, ReaderPriority.CURRENT_PAGE)
                        // A claimed predecessor remains in liveWorkers until physical exit.
                        // An unclaimed predecessor can be replaced without executing it.
                        if (existing.job == null) {
                            existing.state = State.CANCELLED
                            retired += StopAction(existing, null)
                        }
                        pending[work.pageKey] = replacement
                        ReaderSubmission.Queued(work.pageKey)
                    }
                    explicit && !existing.explicit -> {
                        if (explicitCountLocked() >= EXPLICIT_BACKLOG_LIMIT) return capacityRejection()
                        existing.explicit = true
                        existing.priority.set(ReaderPriority.CURRENT_PAGE)
                        existing.promoteHandles()
                        ReaderSubmission.Promoted(work.pageKey)
                    }
                    else -> ReaderSubmission.Existing(terminals[work.pageKey])
                }
            } else {
                val done = terminals[work.pageKey]
                val pausedEligible = done is SinglePageOutcome.Paused &&
                    (done.nextEligibleRetryAtEpochMs ?: 0L) <= now
                if (done != null && !force && !pausedEligible) {
                    ReaderSubmission.Existing(done)
                } else if (explicit && explicitCountLocked() >= EXPLICIT_BACKLOG_LIMIT) {
                    capacityRejection()
                } else {
                    pending[work.pageKey] = Demand(b, work, explicit, force, ReaderPriority.CURRENT_PAGE)
                    ReaderSubmission.Queued(work.pageKey)
                }
            }
        }
        cancelOutside(retired)
        pump()
        pokeSlots()
        return result
    }

    fun updateViewport(
        session: ReaderSessionToken,
        pageIndex: Int,
        pageCount: Int,
        isAutoEnabled: Boolean,
        resolve: (Int) -> ReaderPageWork?,
    ) {
        val captured = synchronized(control) {
            val b = currentLocked(session) ?: return
            b.autoEnabled = isAutoEnabled
            revision++
            b to revision
        }
        scope.launch(workerDispatcher) {
            val resolved = try {
                if (pageIndex < 0 || pageIndex >= pageCount) {
                    emptyList()
                } else {
                    (pageIndex..minOf(pageIndex + 1, pageCount - 1))
                        .mapNotNull { index -> resolve(index)?.let { index to it } }
                        .distinctBy { it.second.pageKey }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logcat(LogPriority.WARN, t) { "Reader queue viewport resolution failed" }
                return@launch
            }
            val now = nowEpochMs()
            val cancellations = synchronized(control) {
                val b = currentLocked(session)
                if (b !== captured.first || revision != captured.second) return@launch
                event++
                val keys = if (b.autoEnabled) resolved.mapTo(HashSet()) { it.second.pageKey } else emptySet()
                val jobs = cancelLocked(preservePaid = !b.autoEnabled, now = now) {
                    it.binding === b &&
                        !it.explicit &&
                        (it.state == State.QUEUED || !b.autoEnabled) &&
                        it.work.pageKey !in keys
                }
                if (b.autoEnabled) {
                    resolved.forEach { (index, work) ->
                        val priority = if (index == pageIndex) ReaderPriority.CURRENT_PAGE else ReaderPriority.PREFETCH_NEXT
                        val existing = pending[work.pageKey]
                        if (existing != null) {
                            if (existing.state == State.QUEUED) existing.work = work
                            if (!existing.explicit) {
                                existing.priority.set(priority)
                                // A promoted waiting entry foregrounds in place;
                                // a re-demanded prefetch keeps its wait semantics.
                                if (priority == ReaderPriority.CURRENT_PAGE) existing.promoteHandles()
                            }
                        } else {
                            val done = terminals[work.pageKey]
                            if (done == null ||
                                (
                                    done is SinglePageOutcome.Paused &&
                                        (done.nextEligibleRetryAtEpochMs ?: 0L) <= now
                                    )
                            ) {
                                pending[work.pageKey] = Demand(b, work, false, false, priority)
                            }
                        }
                    }
                }
                jobs
            }
            cancelOutside(cancellations)
            pump()
            pokeSlots()
        }
    }

    fun setAutoEnabled(session: ReaderSessionToken, enabled: Boolean) {
        val now = nowEpochMs()
        val cancellations = synchronized(control) {
            val b = currentLocked(session) ?: return
            b.autoEnabled = enabled
            revision++
            event++
            if (enabled) {
                emptyList()
            } else {
                cancelLocked(preservePaid = true, now = now) {
                    it.binding === b && !it.explicit
                }
            }
        }
        cancelOutside(cancellations)
        pump()
        pokeSlots()
    }

    fun reconcile(
        session: ReaderSessionToken,
        resolve: ((Int) -> ReaderPageWork?)? = null,
    ) {
        if (resolve == null) {
            val valid = synchronized(control) {
                val b = currentLocked(session) ?: return
                event++
                true
            }
            if (valid) {
                pump()
                pokeSlots()
            }
            return
        }
        val captured = synchronized(control) {
            val b = currentLocked(session) ?: return
            revision++
            b to revision
        }
        scope.launch(workerDispatcher) {
            val pendingIndices = synchronized(control) {
                val b = currentLocked(session)
                if (b !== captured.first || revision != captured.second) return@launch
                pending.values
                    .filter { it.binding === b && it.state == State.QUEUED && it.work.readerPageIndex >= 0 }
                    .map { it.work.readerPageIndex to it.work.pageKey }
            }
            val resolved = try {
                pendingIndices.mapNotNull { (index, pageKey) ->
                    resolve(index)?.takeIf { it.pageKey == pageKey }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logcat(LogPriority.WARN, t) { "Reader queue reconcile resolution failed" }
                return@launch
            }
            val valid = synchronized(control) {
                val b = currentLocked(session)
                if (b !== captured.first || revision != captured.second) return@launch
                event++
                resolved.forEach { work ->
                    val existing = pending[work.pageKey]
                    if (existing != null && existing.binding === b && existing.state == State.QUEUED) {
                        existing.work = work
                    }
                }
                true
            }
            if (valid) {
                pump()
                pokeSlots()
            }
        }
    }

    fun cancelPage(session: ReaderSessionToken, pageKey: String): Boolean {
        val cancellations = synchronized(control) {
            val b = currentLocked(session) ?: return false
            val exists = pending[pageKey]?.binding === b ||
                liveWorkers.values.any {
                    it.binding === b && it.work.pageKey == pageKey && (it.state != State.CANCELLED || it.retainedForDrain)
                }
            if (!exists) return false
            cancelLocked { it.binding === b && it.work.pageKey == pageKey }
        }
        cancelOutside(cancellations)
        pokeSlots()
        return true
    }

    fun outcomes(session: ReaderSessionToken): StateFlow<Map<String, SinglePageOutcome>> = synchronized(control) {
        bound?.takeIf { it.token == session }?.outcomes?.asStateFlow() ?: staleOutcomes
    }

    suspend fun cancelSession(session: ReaderSessionToken): ReaderDrainResult {
        val now = nowEpochMs()
        val cancellations = synchronized(control) {
            val b = bound?.takeIf { it.token == session } ?: return ReaderDrainResult.Completed
            b.accepting = false
            revision++
            cancelLocked(preservePaid = true, now = now) { it.binding === b }
        }
        cancelOutside(cancellations)
        pokeSlots()
        return awaitDrain()
    }

    /**
     * Non-suspending session invalidation for control-path callers (batch
     * admission, download re-key): cancels the session's demand set and fences
     * further submissions without awaiting physical worker drain. Retained
     * paid workers keep draining under their own lifecycle contract.
     */
    fun cancelSessionAndForget(session: ReaderSessionToken) {
        val now = nowEpochMs()
        val cancellations = synchronized(control) {
            val b = bound?.takeIf { it.token == session } ?: return
            b.accepting = false
            revision++
            cancelLocked(preservePaid = true, now = now) { it.binding === b }
        }
        cancelOutside(cancellations)
        pokeSlots()
    }

    suspend fun closeAndDrain(): ReaderDrainResult {
        val now = nowEpochMs()
        val cancellations = synchronized(control) {
            closed = true
            bound?.accepting = false
            revision++
            cancelLocked(preservePaid = true, now = now) { true }
        }
        cancelOutside(cancellations)
        pokeSlots()
        return awaitDrain()
    }

    private fun currentLocked(session: ReaderSessionToken): Binding? =
        bound?.takeIf { !closed && it.accepting && it.token == session }

    private fun rejectedSession() = ReaderSubmission.Rejected(
        SinglePageOutcome.Rejected(PageWriteOrigin.MANUAL, PageRejectionKind.SESSION_DENIED),
    )

    private fun capacityRejection() = ReaderSubmission.Rejected(
        SinglePageOutcome.Rejected(null, PageRejectionKind.OTHER, "reader explicit backlog is full"),
    )

    private fun explicitCountLocked(): Int =
        (pending.values + liveWorkers.values).distinct().count {
            it.explicit && (it.state == State.QUEUED || it.state == State.RUNNING || it.job != null)
        }

    /** Claims stop policy under control; cancellation, timers and callbacks run after unlock. */
    private fun cancelLocked(
        preservePaid: Boolean = false,
        now: Long = 0L,
        matches: (Demand) -> Boolean,
    ): List<StopAction> {
        val actions = mutableListOf<StopAction>()
        (pending.values + liveWorkers.values).distinct().filter(matches).forEach { demand ->
            val decision = demand.paidCall?.requestStop(now)
            val retain = preservePaid && !demand.forceCancelled && decision?.retainWorker == true
            demand.retainedForDrain = retain
            if (!retain) demand.forceCancelled = true
            if (demand.state != State.CANCELLED && demand.state != State.DONE) {
                val wasQueued = !demand.enteredBody
                demand.state = State.CANCELLED
                if (pending[demand.work.pageKey] === demand) pending.remove(demand.work.pageKey)
                if (wasQueued) {
                    publishLocked(
                        demand,
                        SinglePageOutcome.Rejected(PageWriteOrigin.MANUAL, PageRejectionKind.OTHER, "cancelled before execution"),
                    )
                }
            }
            actions += StopAction(demand, demand.job, if (retain) decision?.deadlineEpochMs else null)
        }
        return actions
    }

    private fun cancelOutside(actions: List<StopAction>) {
        actions.forEach { action ->
            val job = action.worker
            if (action.retainedDeadline != null && job != null) {
                armDrainTimer(action.demand, job, action.retainedDeadline)
            } else if (job != null) {
                job.cancel()
            } else {
                action.demand.trace.end(TranslationTraceOutcome.CANCELLED)
            }
        }
    }

    private fun armDrainTimer(demand: Demand, worker: Job, deadline: Long) {
        val timer = scope.launch(workerDispatcher, start = CoroutineStart.LAZY) {
            val now = nowEpochMs()
            delay(if (deadline <= now) 0L else deadline - now)
            if (demand.paidCall?.claimExpiryCancellation(nowEpochMs()) == true) {
                worker.cancel(CancellationException("Paid-call drain grace expired"))
            }
        }
        val installed = synchronized(control) {
            if (liveWorkers[worker] === demand && demand.retainedForDrain && demand.drainTimer == null) {
                demand.drainTimer = timer
                true
            } else {
                false
            }
        }
        if (installed) timer.start() else timer.cancel()
    }

    private fun traceOutcome(outcome: SinglePageOutcome): TranslationTraceOutcome = when (outcome) {
        SinglePageOutcome.Completed -> TranslationTraceOutcome.SUCCESS
        is SinglePageOutcome.Paused -> TranslationTraceOutcome.PAUSE
        is SinglePageOutcome.Attached -> TranslationTraceOutcome.ATTACHED
        is SinglePageOutcome.Rejected -> TranslationTraceOutcome.SKIP
        is SinglePageOutcome.Failed, is SinglePageOutcome.Stalled -> TranslationTraceOutcome.FAILURE
        SinglePageOutcome.Admitted -> TranslationTraceOutcome.TEARDOWN_EXCEPTION
    }
    private fun publishLocked(demand: Demand, outcome: SinglePageOutcome) {
        demand.traceOutcome = traceOutcome(outcome)
        if (bound !== demand.binding) return
        terminals[demand.work.pageKey] = outcome
        while (terminals.size > TERMINAL_OUTCOME_RETENTION) terminals.remove(terminals.keys.first())
        demand.binding.outcomes.value = terminals.toMap()
    }

    /** Reservation, capacity check and lazy job registration form one atomic operation. */
    private fun pump() {
        val now = nowEpochMs()
        val claims = synchronized(control) {
            val b = bound?.takeIf { !closed && it.accepting } ?: return
            val result = mutableListOf<Pair<Job, Demand>>()
            var automatic = liveWorkers.values.count { !it.explicit }
            pending.values.forEach { demand ->
                if (demand.binding !== b || demand.state != State.QUEUED || demand.job != null) return@forEach
                if (demand.deferredAtEvent >= event || demand.retryAt > now) return@forEach
                if (liveWorkers.values.any { it.work.pageKey == demand.work.pageKey }) return@forEach
                if (!demand.explicit && automatic >= AUTOMATIC_PHYSICAL_LIMIT) return@forEach
                if (demand.work.streamFn == null) {
                    demand.deferredAtEvent = event
                    demand.deferral = AutoDeferralReason.SourceUnavailable
                    return@forEach
                }
                if (!demand.explicit) automatic++
                if (liveWorkers.isEmpty()) drainSignal = CompletableDeferred()
                val work = demand.work
                val job = scope.launch(workerDispatcher, start = CoroutineStart.LAZY) {
                    withContext(
                        TranslationTrace.elementFor(demand.trace.run) +
                            (demand.paidCall ?: kotlin.coroutines.EmptyCoroutineContext) +
                            demand.liveHandle +
                            demand.deviceHandle,
                    ) { runDemand(demand, work) }
                }
                demand.job = job
                liveWorkers[job] = demand
                result += job to demand
            }
            result
        }
        // A cancelled parent may complete a LAZY job during construction. Install handlers
        // after registration and outside control so even immediate completion is safe.
        claims.forEach { (job, demand) -> job.invokeOnCompletion { releaseWorker(job, demand) } }
        claims.forEach { (job, _) -> job.start() }
    }

    private fun releaseWorker(job: Job, demand: Demand) {
        val drained = synchronized(control) {
            liveWorkers.remove(job)
            if (demand.job === job) demand.job = null
            if (job.isCancelled && (demand.state == State.QUEUED || demand.state == State.RUNNING)) {
                val wasQueued = !demand.enteredBody
                demand.state = State.CANCELLED
                if (pending[demand.work.pageKey] === demand) pending.remove(demand.work.pageKey)
                if (wasQueued) {
                    publishLocked(
                        demand,
                        SinglePageOutcome.Rejected(
                            PageWriteOrigin.MANUAL,
                            PageRejectionKind.OTHER,
                            "cancelled before execution",
                        ),
                    )
                }
            }
            if (demand.state == State.DONE || demand.state == State.CANCELLED) demand.paidCall?.recordPhysicalExit()
            val timer = demand.drainTimer
            demand.drainTimer = null
            (if (liveWorkers.isEmpty()) drainSignal else null) to timer
        }
        drained.second?.cancel()
        val terminal = synchronized(control) {
            when {
                job.isCancelled -> TranslationTraceOutcome.CANCELLED
                demand.state == State.DONE -> demand.traceOutcome
                demand.state == State.CANCELLED -> TranslationTraceOutcome.CANCELLED
                else -> null
            }
        }
        terminal?.let { demand.trace.end(it) }
        drained.first?.complete(Unit)
        pokeSlots()
        pump()
    }

    private fun valid(demand: Demand): Boolean = synchronized(control) {
        bound === demand.binding && demand.binding.accepting && !closed && demand.state == State.QUEUED
    }

    private fun defer(demand: Demand, retryAt: Long = 0L) = synchronized(control) {
        if (demand.state == State.QUEUED || demand.state == State.RUNNING) {
            demand.state = State.QUEUED
            demand.enteredBody = false
            demand.deferredAtEvent = event
            demand.retryAt = retryAt
        }
    }

    private suspend fun runDemand(demand: Demand, work: ReaderPageWork) {
        val permit = localPermit.takeIf { demand.binding.computeClass == TranslatorComputeClass.LOCAL_COMPUTE }
        var acquired = false
        val leaseWaitSpan = demand.trace.run.beginStage(TranslationTraceStage.LEASE_WAIT)
        try {
            permit?.acquire()
            acquired = permit != null
            if (!valid(demand)) {
                leaseWaitSpan.end()
                return
            }
            when (gate(demand)) {
                Gate.ABORT -> {
                    leaseWaitSpan.end()
                    return
                }
                Gate.DEFER -> {
                    leaseWaitSpan.end()
                    defer(demand)
                    pokeSlots()
                    return
                }
                Gate.DISPLAY_READY -> {
                    leaseWaitSpan.end()
                    synchronized(control) {
                        if (valid(demand) && pending[work.pageKey] === demand) {
                            demand.state = State.DONE
                            pending.remove(work.pageKey)
                            publishLocked(demand, SinglePageOutcome.Completed)
                        }
                    }
                    pokeSlots()
                    return
                }
                Gate.PROCEED -> Unit
            }
            synchronized(control) {
                if (!valid(demand)) return
                demand.state = State.RUNNING
                demand.enteredBody = true
            }
            pokeSlots()
            leaseWaitSpan.end()
            val outcome = executor.translateSinglePage(
                manga = demand.binding.manga,
                chapter = demand.binding.chapter,
                source = demand.binding.source,
                pageKey = work.pageKey,
                force = demand.force,
                stageListener = { _, stage -> onStageEvent(demand, stage) },
                origin = PageWriteOrigin.MANUAL,
                readerStreamFn = work.streamFn,
                readerPriority = {
                    if (demand.priority.get() == ReaderPriority.CURRENT_PAGE) {
                        AdmissionPriority.INTERACTIVE
                    } else {
                        AdmissionPriority.BACKGROUND
                    }
                },
            )
            val active = currentCoroutineContext().isActive
            synchronized(control) {
                if (demand.state == State.RUNNING || demand.retainedForDrain) {
                    demand.state = State.DONE
                    demand.traceOutcome = traceOutcome(outcome)
                    val ownsPending = pending[work.pageKey] === demand
                    if (ownsPending) pending.remove(work.pageKey)
                    if (active && bound === demand.binding && (ownsPending || demand.retainedForDrain)) {
                        publishLocked(demand, outcome)
                    }
                }
            }
            pokeSlots()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "Reader queue execution or admission failed" }
            demand.deferral = AutoDeferralReason.Network
            defer(demand, nowEpochMs() + cancellationWaitMs)
            pokeSlots()
        } finally {
            if (acquired) permit?.release()
        }
    }

    /** Stage events fire on pipeline threads; publish outside the control lock. */
    private fun onStageEvent(demand: Demand, stage: TranslationStageEvent) {
        demand.stage.set(stage)
        pokeSlots()
    }

    private suspend fun gate(demand: Demand): Gate {
        val store = storeResolver.resolve(demand.binding.chapter.id)
        if (!valid(demand)) return Gate.ABORT
        val automatic = synchronized(control) { !demand.explicit }
        if (automatic) {
            if (store == null) {
                demand.deferral = AutoDeferralReason.SourceUnavailable
                return Gate.DEFER
            }
            if (!store.autoAttemptAllowed(demand.work.pageKey)) {
                demand.deferral = AutoDeferralReason.Network
                return Gate.DEFER
            }
            if (!valid(demand)) return Gate.ABORT
            if (demand.priority.get() == ReaderPriority.PREFETCH_NEXT && !memoryGate()) {
                demand.deferral = AutoDeferralReason.Memory
                return Gate.DEFER
            }
        }
        if (demand.force || store == null) return Gate.PROCEED
        val ready = try {
            store.snapshot(demand.work.pageKey).page?.hasRenderedResult == true
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "Reader queue display-ready probe failed" }
            false
        }
        if (!valid(demand)) return Gate.ABORT
        return if (ready) Gate.DISPLAY_READY else Gate.PROCEED
    }

    private suspend fun awaitDrain(): ReaderDrainResult {
        val signal = synchronized(control) {
            if (liveWorkers.isEmpty()) null else drainSignal
        } ?: return ReaderDrainResult.Completed
        withTimeoutOrNull(cancellationWaitMs) { signal.await() }
        return if (signal.isCompleted) ReaderDrainResult.Completed else ReaderDrainResult.PendingNativeDrain(signal)
    }
}
