package eu.kanade.translation.scheduling

import eu.kanade.translation.TranslationSession
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.isTranslationDisplayReady
import eu.kanade.translation.translator.TranslatorComputeClass
import eu.kanade.translation.util.TranslationMemoryBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    // (translatePreparedPage == false). Bounded so a page that persistently
    // loses the race or has no durable cleaned image cannot tight-loop the
    // native lane; it flips to Failed(retryable) past the cap instead.
    private val reprepareAttempts = ConcurrentHashMap<Int, Int>()

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
                    cancelLocked()
                    currentIdentity = identity
                    resetState()
                }
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
    fun reconcile() = poke()

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

    /** Caller holds [lifecycleLock]. Cancels the coordination job only. */
    private fun cancelLocked() {
        coordinationJob?.cancel()
        generationCounter += 1L
        activeGeneration = generationCounter
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
            .filter { it !== existing && !it.isCompleted }
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
        val preparedChannel = Channel<PreparedWork>(capacity = 1)

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

    private suspend fun consumeTranslations(
        preparedChannel: Channel<PreparedWork>,
        computeGate: Semaphore?,
        loopGeneration: Long,
    ) {
        for (work in preparedChannel) {
            coroutineContext.ensureActive()
            if (!isWorkCurrent(work) || work.generation != loopGeneration) continue
            // The prepare phase already set Cleaning; promote to Translating
            // eagerly so the snapshot never shows a stale stage while the
            // provider request is in flight. The stage listener refines this.
            updateSlot(work.pageIndex, AutoSlotState.Translating, work.generation)
            publishSnapshot(work.generation)
            try {
                if (!isWorkCurrent(work)) continue
                val translated = if (computeGate != null) {
                    computeGate.withPermit {
                        if (!isWorkCurrent(work)) {
                            false
                        } else {
                            executor.translatePreparedPage(
                                work.session.manga,
                                work.session.chapter,
                                work.session.source,
                                work.prepared,
                                stageListenerFor(work.pageIndex, work.generation),
                            )
                        }
                    }
                } else {
                    if (!isWorkCurrent(work)) {
                        false
                    } else {
                        executor.translatePreparedPage(
                            work.session.manga,
                            work.session.chapter,
                            work.session.source,
                            work.prepared,
                            stageListenerFor(work.pageIndex, work.generation),
                        )
                    }
                }
                if (isWorkCurrent(work)) {
                    if (translated) {
                        markCompleted(work.pageIndex, work.generation)
                        clearReprepareAttempts(work.pageIndex, work.generation)
                        updateSlot(work.pageIndex, AutoSlotState.Ready, work.generation)
                    } else {
                        // translatePreparedPage returns false ONLY for a stale/race
                        // handoff or a missing cleaned image (see interface contract):
                        // the caller must re-prepare, never mark Ready. Return the
                        // slot to the admissible set so the next reconcile re-admits
                        // it into the native lane; bound the retries so a persistently
                        // stale/broken page cannot tight-loop.
                        handleStaleTranslate(work.pageIndex, work.generation)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Rolling auto translate failed: pageIndex=${work.pageIndex}" }
                if (isWorkCurrent(work)) {
                    clearReprepareAttempts(work.pageIndex, work.generation)
                    updateSlot(work.pageIndex, AutoSlotState.Failed(retryable = true), work.generation)
                }
            } finally {
                if (isWorkCurrent(work)) {
                    removeTranslateAdmitted(work.pageIndex, work.generation)
                    poke()
                }
            }
            publishSnapshot(work.generation)
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
                    publishSnapshot(spec.generation)
                }
                continue
            }

            // Admit into the native lane (serialized inline by this loop).
            if (!markNativeAdmitted(idx, spec.generation)) return false
            try {
                if (!isGenerationActive(spec.generation)) return false
                val prepared = if (computeGate != null) {
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
                if (!isGenerationActive(spec.generation)) return false
                when {
                    prepared == null -> {
                        clearReprepareAttempts(idx, spec.generation)
                        updateSlot(idx, AutoSlotState.Failed(retryable = true), spec.generation)
                    }
                    prepared.isTerminal -> {
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
                            preparedChannel.send(
                                PreparedWork(
                                    pageIndex = idx,
                                    prepared = prepared,
                                    identity = spec.identity,
                                    session = spec.session,
                                    generation = spec.generation,
                                ),
                            )
                        } else {
                            return false
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Rolling auto prepare failed: pageIndex=$idx" }
                if (isGenerationActive(spec.generation)) {
                    clearReprepareAttempts(idx, spec.generation)
                    updateSlot(idx, AutoSlotState.Failed(retryable = true), spec.generation)
                }
            } finally {
                removeNativeAdmitted(idx, spec.generation)
            }
            publishSnapshot(spec.generation)
            return true
        }
        return false
    }

    /**
     * Eligible to be admitted into the native lane now: desired, not currently
     * in either lane, not completed, and not already Failed (a failed slot
     * stays failed until an explicit retry clears it — never auto-looped).
     */
    private fun isAdmissible(idx: Int): Boolean =
        idx !in nativeAdmitted &&
            idx !in translateAdmitted &&
            idx !in completed &&
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
            }.filter { it !in desired && it !in nativeAdmitted && it !in translateAdmitted }
            for (idx in leaving) {
                slotStates.remove(idx)
                completed.remove(idx)
                reprepareAttempts.remove(idx)
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
    )

    /** One page's identity and stream, resolvable by page index. */
    data class PageWorkItem(
        val pageKey: String,
        val streamFn: (() -> InputStream)?,
    )

    private companion object {
        /**
         * Max re-prepare attempts after a stale translate handoff before the
         * slot flips to Failed(retryable). Stale handoffs are transient races,
         * so a small cap is enough; the pipeline's own retry exhaustion is the
         * ultimate backstop for genuinely broken pages.
         */
        const val MAX_REPREPARE_ATTEMPTS = 3
    }
}
