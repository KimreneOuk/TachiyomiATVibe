package eu.kanade.translation.scheduling

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.artifact.GroupCommitConfiguration
import eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceMode
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTracePlan
import eu.kanade.translation.diagnostics.TranslationTraceStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.cancelInFlightStages
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isStageFailed
import eu.kanade.translation.model.isStageRunning
import eu.kanade.translation.orchestration.ReaderSessionIntent
import eu.kanade.translation.orchestration.SessionAdmission
import eu.kanade.translation.orchestration.TranslationSession
import eu.kanade.translation.orchestration.TranslationSessionCoordinator
import eu.kanade.translation.pipeline.PageWriteOrigin
import eu.kanade.translation.storage.ChapterTranslationStore
import eu.kanade.translation.translator.TranslatorComputeClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns single-page jobs and reader rolling-auto coordination so they can be
 * cancelled on chapter change, reader exit, or translation disable.
 *
 * Job-scheduling + dedup + cancel surface extracted from
 * [eu.kanade.translation.orchestration.TranslationManager]. Per-page work is delegated to
 * [TranslationExecutor]; the store is resolved through
 * [TranslationStoreResolver] (still owned by TranslationManager).
 */
class TranslationScheduler(
    private val executor: TranslationExecutor,
    private val storeResolver: TranslationStoreResolver,
    private val immediateStoreResolver: ((Long) -> ChapterTranslationStore?)? = null,
    private val sessionCoordinator: TranslationSessionCoordinator = TranslationSessionCoordinator(),
) : java.io.Closeable {

    override fun close() {
        // The coordinator may own a separate scope, so canceling this scheduler's
        // scope alone would leave its worker alive.
        shutdownAutoCoordinator()
        scope.cancel()
    }

    companion object {
        /**
         * Max wait for cancelled jobs to unwind (their finally blocks reset a
         * stranded RUNNING status on a NonCancellable child). Bounded so chapter
         * navigation stays responsive.
         */
        private const val JOIN_TIMEOUT_MS = 2_000L

        /** Maximum number of recent manual page outcomes retained for the reader. */
        private const val MANUAL_OUTCOME_MAP_CAP = 32
    }

    // TachiyomiAT: the prior implementation launched jobs on GlobalScope and
    // discarded the Job, so orphaned jobs from a previous chapter kept holding
    // the translator's single permit, starving all later work. Keeping the scope
    // lets auto off / chapter switch / reader close cancel actually-running work.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Tracks independently-launched single-page jobs by "$chapterId:$pageKey".
    private val activePageJobs = ConcurrentHashMap<String, Job>()

    // Cancellation is attributed before a Job is cancelled because its finally block runs
    // after the cancellation signal and must preserve the initiating intent in the durable
    // page error instead of collapsing every path to "Translation cancelled".
    private val pageCancellationReasons = ConcurrentHashMap<String, String>()

    /** Recent typed outcomes let the reader distinguish failures from jobs that are still running. */
    private val manualOutcomes: MutableMap<String, SinglePageOutcome> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, SinglePageOutcome>(16, 0.75f, false) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SinglePageOutcome>): Boolean =
                    size > MANUAL_OUTCOME_MAP_CAP
            },
        )

    /** Returns the last outcome for this page; the caller maps it to reader-facing UI truth. */
    fun manualOutcomeFor(chapterId: Long, pageKey: String): SinglePageOutcome? =
        synchronized(manualOutcomes) { manualOutcomes["$chapterId:$pageKey"] }

    fun recordManualOutcome(chapterId: Long, pageKey: String, outcome: SinglePageOutcome) {
        synchronized(manualOutcomes) { manualOutcomes["$chapterId:$pageKey"] = outcome }
    }

    private val autoOwnerVersions = AtomicLong(0L)

    // At most one rolling auto coordinator owns a chapter session at a time.
    private var autoCoordinator: AutoCoordinatorOwner? = null
    private val autoCoordinatorLock = Any()
    private val retiringAutoCoordinators = LinkedHashSet<RollingAutoCoordinator>()
    private var globalAutoCancellationInFlight = false
    private var autoCancellationEpoch = 0L
    private var globalAutoCancellationEpoch = 0L
    private val chapterCancellationEpochs = mutableMapOf<Long, Long>()
    private var readerStopInFlight = false

    // One stable source pointer drives the reader-facing flow. Switching this
    // pointer keeps existing collectors attached across coordinator replacement,
    // shutdown, and chapter/session changes instead of leaving them on an old
    // coordinator's StateFlow.
    private val autoCoordinatorSource = MutableStateFlow<RollingAutoCoordinator?>(null)
    private val autoSnapshotFlow: StateFlow<AutoTranslationSnapshot?> = autoCoordinatorSource
        .flatMapLatest { coordinator -> coordinator?.snapshot ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Live auto-translation snapshot for the active chapter session. Null when
     * no coordinator is active. Observed by the reader through
     * [TranslationManager].
     */
    val autoSnapshot: StateFlow<AutoTranslationSnapshot?> get() = autoSnapshotFlow

    /**
     * Accepts a desired-window update (visible page + ahead target) and delegates to the coordinator.
     * The coordinator reconciles continually, overlaps remote translation,
     * serializes local compute, checks memory per admission, and publishes
     * [autoSnapshot].
     *
     * Manual arbitration: the supplied [pageResolver] is wrapped so that any
     * page with an active manual single-page job ([translatePage]) is hidden
     * from the coordinator (resolved to null). The coordinator defers it; when
     * the manual job completes, [translatePage]'s finally pokes [autoCoordinator]
     * reconcile and the slot becomes admissible again. This preserves the
     * manual-outranks-auto rule without duplicating executor-level dedup.
     */
    fun updateAutoWindow(
        identity: AutoChapterIdentity,
        visiblePageIndex: Int,
        configuredAheadTarget: Int,
        pageCount: Int,
        session: TranslationSession,
        pageResolver: (Int) -> RollingAutoCoordinator.PageWorkItem?,
        computeClass: TranslatorComputeClass,
    ) {
        when (val admission = sessionCoordinator.requestReaderSession(ReaderSessionIntent(identity.chapterId))) {
            is SessionAdmission.Admitted,
            is SessionAdmission.Switched,
            -> Unit

            is SessionAdmission.Rejected -> {
                logcat(LogPriority.INFO) {
                    "TachiyomiAT reader auto admission rejected at scheduler gate: " +
                        "reason=${admission.reason} chapterId=${identity.chapterId}"
                }
                return
            }
        }
        val chapterId = session.chapter.id
        val arbitratedResolver: (Int) -> RollingAutoCoordinator.PageWorkItem? = { idx ->
            val item = pageResolver(idx)
            if (item != null && chapterId != null) {
                val manualJob = activePageJobs["$chapterId:${item.pageKey}"]
                if (manualJob != null && manualJob.isActive) null else item
            } else {
                item
            }
        }
        val updateTarget = synchronized(autoCoordinatorLock) {
            if (readerStopInFlight || globalAutoCancellationInFlight) return@synchronized null
            if (identity.chapterId in chapterCancellationEpochs) return@synchronized null
            val current = autoCoordinator
            val ownerMatches = current != null &&
                current.identity == identity &&
                current.sessionKey == session.key &&
                current.store === session.store
            val owner = if (ownerMatches) {
                current!!
            } else {
                current?.let { retireLocked(it) }
                val ownerVersion = autoOwnerVersions.incrementAndGet()
                val replacement = RollingAutoCoordinator(
                    executor = executor,
                    computeClass = computeClass,
                    predecessorCoordinators = retiringAutoCoordinators.toList(),
                    ownerVersion = ownerVersion,
                )
                AutoCoordinatorOwner(identity, session.key, session.store, replacement, ownerVersion).also {
                    autoCoordinator = it
                    autoCoordinatorSource.value = replacement
                }
            }
            if (readerStopInFlight || globalAutoCancellationInFlight || owner.cancellationInFlight) {
                null
            } else {
                AutoCoordinatorUpdate(owner, current?.takeUnless { ownerMatches })
            }
        }
        updateTarget?.let { target ->
            synchronized(target.owner.callLock) {
                val stillCurrent = synchronized(autoCoordinatorLock) {
                    autoCoordinator === target.owner &&
                        !globalAutoCancellationInFlight &&
                        !target.owner.cancellationInFlight
                }
                if (stillCurrent) {
                    target.owner.coordinator.updateWindow(
                        identity = identity,
                        visiblePageIndex = visiblePageIndex,
                        configuredAheadTarget = configuredAheadTarget,
                        pageCount = pageCount,
                        session = session,
                        pageResolver = arbitratedResolver,
                    )
                }
            }
            target.predecessorToShutdown?.let(::shutdownRetiringCoordinator)
        }
    }

    /**
     * Shuts down the rolling coordinator entirely (auto-off / reader-close /
     * chapter-switch). Clears the snapshot.
     */
    fun shutdownAutoCoordinator(chapterId: Long? = null) {
        val retiring = synchronized(autoCoordinatorLock) {
            val owner = autoCoordinator ?: return@synchronized null
            if (chapterId != null && owner.identity.chapterId != chapterId) return@synchronized null
            autoCoordinator = null
            autoCoordinatorSource.value = null
            retireLocked(owner)
        }
        retiring?.let(::shutdownRetiringCoordinator)
    }

    /**
     * Re-opens admission for an already-bound rolling window after an external
     * signal such as memory recovery or reader foreground resume.
     */
    fun reconcileAutoWindow(admissionGuard: (Long) -> Boolean = { true }) {
        val owner = synchronized(autoCoordinatorLock) {
            autoCoordinator?.takeUnless {
                readerStopInFlight || globalAutoCancellationInFlight || it.cancellationInFlight
            }
        } ?: return
        if (!admissionGuard(owner.identity.chapterId)) return
        synchronized(owner.callLock) {
            val stillCurrent = synchronized(autoCoordinatorLock) {
                autoCoordinator === owner &&
                    !readerStopInFlight &&
                    !globalAutoCancellationInFlight &&
                    !owner.cancellationInFlight
            }
            if (stillCurrent) owner.coordinator.reconcile()
        }
    }

    fun cancelAutoTranslations(chapterId: Long? = null): Boolean {
        // Reserve cancellation under the coordinator lock, then invoke it
        // outside that lock. Matching updates either complete
        // before this reservation or observe it and are suppressed; no new
        // same-chapter loop can escape between capture and cancel.
        val cancellation = beginAutoCancellation(chapterId)
        val coordinatorCancelled = cancellation?.owner != null

        var cancelled = coordinatorCancelled

        try {
            cancellation?.let(::performAutoCancellation)
            // TachiyomiAT: fast non-blocking in-memory flip on the store first so the
            // reader overlay/dim clears immediately on the current frame. Durable persistence
            // is dispatched asynchronously to IO without blocking the caller.
            if (chapterId != null) {
                val inMemoryFlipped = immediateStoreResolver?.invoke(chapterId)?.fastCancelInFlightStagesInMemory(
                    origin = PageWriteOrigin.AUTO,
                    cancellationReason = "Auto translation cancelled",
                ) ?: 0
                if (inMemoryFlipped > 0) {
                    cancelled = true
                }
                if (cancelled) {
                    scope.launch {
                        try {
                            markChapterCancelledAsync(
                                chapterId,
                                origin = PageWriteOrigin.AUTO,
                                reason = "Auto translation cancelled",
                            )
                        } catch (e: Throwable) {
                            logcat(LogPriority.WARN, e) { "Failed to drain cancellation for $chapterId" }
                        }
                    }
                }
            }

            return cancelled
        } finally {
            finishAutoCancellation(cancellation)
        }
    }

    fun translatePage(manga: Manga, chapter: Chapter, source: HttpSource, pageKey: String, force: Boolean = false) {
        when (val admission = sessionCoordinator.requestReaderSession(ReaderSessionIntent(chapter.id))) {
            is SessionAdmission.Admitted,
            is SessionAdmission.Switched,
            -> Unit

            is SessionAdmission.Rejected -> {
                chapter.id?.let { chapterId ->
                    recordManualOutcome(
                        chapterId,
                        pageKey,
                        SinglePageOutcome.Rejected(null, "reader session rejected: ${admission.reason}"),
                    )
                }
                logcat(LogPriority.INFO) {
                    "TachiyomiAT reader manual admission rejected at scheduler gate: " +
                        "reason=${admission.reason} chapterId=${chapter.id} pageKey=$pageKey"
                }
                return
            }
        }
        val jobKey = "${chapter.id}:$pageKey"
        // TachiyomiAT: do NOT cancel an in-flight job for this page on a
        // duplicate request. The prior activePageJobs[jobKey]?.cancel() made
        // every repeated page-selection event (scroll, re-bind, double-tap) tear
        // down and restart the same translation, blinking the overlay and
        // re-decoding the bitmap each time. The translator dedups via
        // inFlightPageKeys once the permit is acquired, so a second launch is a
        // no-op for the expensive work.
        synchronized(activePageJobs) {
            val existing = activePageJobs[jobKey]
            if (existing != null && existing.isActive) {
                logcat(LogPriority.DEBUG) { "translatePage: skipping $jobKey (already active)" }
                return
            }
            if (existing != null) {
                activePageJobs.remove(jobKey)
            }
            logcat(LogPriority.DEBUG) { "translatePage: launching $jobKey" }
            if (GroupCommitConfiguration.enabled) {
                manualOutcomes[jobKey] = SinglePageOutcome.Admitted
            }
            // The schedule and run are created
            // BEFORE the coroutine is launched, and the lease_wait span starts
            // here so its duration is the request→coroutine-start scheduler
            // queue. The terminal run event is owned by the invoke-on-completion
            // handler below (outside the coroutine), so even a job cancelled
            // before its body starts emits exactly one idempotent run_end.
            val schedule = TranslationPipelineDiagnostics.startSchedule(
                mode = TranslationTraceMode.MANUAL,
                origin = TranslationTraceMode.MANUAL,
                chapterRaw = chapter.name,
                pages = 1,
            )
            val run = TranslationPipelineDiagnostics.startRun(
                schedule = schedule,
                pageRaw = pageKey,
                pageIndex = null,
                plan = TranslationTracePlan.FRESH,
            )
            val leaseWaitSpan = run.beginStage(TranslationTraceStage.LEASE_WAIT)
            val traceOutcome = AtomicReference<TranslationTraceOutcome?>(null)
            val job = scope.launch(TranslationTrace.elementFor(run)) {
                var cancelledMidFlight = false
                var outcome: SinglePageOutcome? = null
                try {
                    leaseWaitSpan.end()
                    outcome = executor.translateSinglePage(manga, chapter, source, pageKey, force = force)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Cancelled (chapter switch / reader exit / Stop all) after the
                    // executor set ocrStatus=RUNNING but before a terminal state: the
                    // page is stranded RUNNING because the store is never updated as
                    // the coroutine tears down. Flag for the finally to reset it.
                    cancelledMidFlight = true
                    traceOutcome.set(TranslationTraceOutcome.CANCELLED)
                    throw e
                } catch (e: Throwable) {
                    // Correlated traces carry page/chapter/manga identity; this
                    // log keeps only a bounded error type plus the throwable for
                    // native/ORT stack diagnostics.
                    traceOutcome.set(TranslationTraceOutcome.FAILURE)
                    logcat(LogPriority.ERROR, e) {
                        "TachiyomiAT single-page translation failed: " +
                            "errorType=${TranslationPipelineDiagnostics.classifyError(e).type}"
                    }
                } finally {
                    if (traceOutcome.get() == null) {
                        traceOutcome.set(mapManualTraceOutcome(outcome))
                    }
                    outcome?.let { manualOutcomes[jobKey] = it }
                    synchronized(activePageJobs) {
                        activePageJobs.remove(jobKey)
                    }
                    // The page is available to rolling auto work again after
                    // this manual job completes, so reconcile its window.
                    reconcileAutoWindow()
                    // An attached outcome means the job never owned the page —
                    // it only observed another origin's terminal commit. Keep
                    // the stranded-RUNNING reset scoped to owned work.
                    val attachFamily = when (outcome) {
                        is SinglePageOutcome.Attached -> true
                        else -> false
                    }
                    if (cancelledMidFlight && !attachFamily) {
                        val cancellationReason = pageCancellationReasons.remove(jobKey)
                            ?: "Manual translation cancelled"
                        // Reset stranded RUNNING on a NonCancellable child so the
                        // reset can't be torn down by the cancellation that triggered
                        // it. Guarded so a reset failure never masks the original CancellationException.
                        try {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                markPageCancelled(chapter, pageKey, cancellationReason)
                            }
                        } catch (resetError: Throwable) {
                            logcat(LogPriority.WARN, resetError) {
                                "TachiyomiAT reset of stranded page status failed: pageKey=$pageKey"
                            }
                        }
                    }
                }
            }
            activePageJobs[jobKey] = job
            // Completion handlers run after
            // the coroutine's finally, so the mapped outcome above is visible;
            // if the job was cancelled before the body ever ran, the handler
            // still closes run + schedule exactly once (idempotent terminals).
            job.invokeOnCompletion { _ ->
                val outcomeToken = traceOutcome.get()
                    ?: if (job.isCancelled) {
                        TranslationTraceOutcome.CANCELLED
                    } else {
                        TranslationTraceOutcome.TEARDOWN_EXCEPTION
                    }
                leaseWaitSpan.end()
                run.end(outcomeToken)
                schedule.end(outcomeToken)
            }
        }
    }

    /**
     * Maps a manual single-page outcome to its bounded trace value.
     * Attached-family jobs never owned the page; Rejected means the
     * request performed no owned work (dedup residual / persistence reject).
     */
    private fun mapManualTraceOutcome(outcome: SinglePageOutcome?): TranslationTraceOutcome = when (outcome) {
        null -> TranslationTraceOutcome.TEARDOWN_EXCEPTION
        is SinglePageOutcome.Completed -> TranslationTraceOutcome.SUCCESS
        is SinglePageOutcome.Paused -> TranslationTraceOutcome.PAUSE
        is SinglePageOutcome.Failed -> TranslationTraceOutcome.FAILURE
        is SinglePageOutcome.Stalled -> TranslationTraceOutcome.FAILURE
        is SinglePageOutcome.Attached -> TranslationTraceOutcome.ATTACHED
        is SinglePageOutcome.Rejected -> TranslationTraceOutcome.SKIP
        is SinglePageOutcome.Admitted -> TranslationTraceOutcome.TEARDOWN_EXCEPTION
    }

    /**
     * Clears a non-terminal RUNNING/PENDING status for [pageKey] left by a
     * mid-flight cancellation. Cancellation is not a stage failure and must not
     * increment retryCount.
     */
    private suspend fun markPageCancelled(
        chapter: Chapter,
        pageKey: String,
        reason: String = "Translation cancelled",
        requiredOrigin: PageWriteOrigin? = null,
    ) {
        val chapterId = chapter.id ?: return
        val store = storeResolver.resolve(chapterId) ?: return
        markPageCancelled(store, pageKey, reason, requiredOrigin)
    }

    private suspend fun markPageCancelled(
        store: ChapterTranslationStore,
        pageKey: String,
        reason: String = "Translation cancelled",
        requiredOrigin: PageWriteOrigin? = null,
    ) {
        // Peek first: if no entry or already terminal, nothing is stranded — skip
        // the write rather than creating a spurious FAILED entry.
        val existing = store.state.value[pageKey] ?: return
        if (existing.hasRenderedResult || existing.isStageFailed) return
        if (requiredOrigin != null && store.pageLeaseOwner(pageKey) != requiredOrigin) return
        //   while a batch run holds this page's BATCH-origin stage
        // lease, the batch owns the page's stage state. The cancel write below
        // builds its precondition from the CURRENT snapshot — which carries the
        // batch's own lease token — and would punch straight through the fence,
        // bumping the page version mid-OCR/translate: the flagged lane's
        // `checkpointOcr` then rejects (CHECKPOINT_REJECTED) and charges a
        // healthy page to the failure ledger, or a mid-TRANSLATE write fails
        // `mergeTranslation` and pauses the whole run after the provider call
        // was paid. Skip the write; the chapter-level "stop translation" action
        // cancels the batch translator job itself (ChapterTranslator.stop →
        // translationJob.cancel), whose teardown releases every BATCH lease —
        // so this skip never strands a cancelled page's state.
        if (store.hasActiveBatchStageLease(pageKey)) {
            logcat(LogPriority.INFO) {
                "TachiyomiAT cancel skipped: page holds an active BATCH stage lease " +
                    "writer=markPageCancelled pageKey=$pageKey"
            }
            return
        }
        // Flip in-flight stages to CANCELLED so the reader clears the overlay and
        // auto can reschedule the page later.
        store.updatePageFromCurrentSnapshot(pageKey, reason) { current ->
            // Re-check inside the lock in case it changed between peek and write.
            val cur = current ?: PageTranslation(
                sourceFileName = pageKey,
                ocrStatus = StageStatus.CANCELLED,
                updatedAt = System.currentTimeMillis(),
            ).also { it.ocrError = reason }
            if (cur.hasRenderedResult || cur.isStageFailed) return@updatePageFromCurrentSnapshot cur
            cur.apply {
                cancelInFlightStages()
                ocrError = reason
                updatedAt = System.currentTimeMillis()
            }
        }
    }

    /**
     * TachiyomiAT bug 4 fix: synchronously flip every in-flight page in [chapterId]'s
     * store to CANCELLED so the reader clears the dim/overlay immediately. The auto/
     * master-toggle-off paths previously only cancelled the jobs (and relied on each
     * job's finally block to reset the status), which left pages visibly RUNNING until
     * the coroutine unwound — sometimes never, if the worker was past its suspension
     * point in uncancellable native/HTTP code.
     *
     * Mirrors the proven per-page pattern in [cancelPageTranslation]: runBlocking on
     * [immediateStoreResolver] is acceptable because callers run on the reader/UI
     * scope and the store's own mutex is the only inner lock (no nested UI-thread
     * concerns). Pages that already reached a rendered or failed terminal state are
     * left untouched so accepted artifacts survive.
     */
    suspend fun markChapterCancelledAsync(
        chapterId: Long,
        origin: PageWriteOrigin? = null,
        reason: String = "Translation cancelled",
    ): Int = withContext(Dispatchers.IO) {
        val store = immediateStoreResolver?.invoke(chapterId) ?: return@withContext 0
        val runningKeys = store.state.value.entries
            .asSequence()
            .filter { (pageKey, page) ->
                page != null &&
                    (page!!.isStageRunning || page.ocrError == "Translation cancelled") &&
                    (origin == null || store.pageLeaseOwner(pageKey)?.let { it == origin } != false)
            }
            .map { it.key }
            .toList()
        if (runningKeys.isEmpty()) return@withContext 0
        var flipped = 0
        runningKeys.forEach { key ->
            try {
                markPageCancelled(store, key, reason, origin)
                flipped++
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Failed to mark page cancelled: $key" }
            }
        }
        flipped
    }

    fun markChapterCancelledSync(
        chapterId: Long,
        reason: String = "Translation cancelled",
        origin: PageWriteOrigin? = null,
    ): Int {
        val store = immediateStoreResolver?.invoke(chapterId) ?: return 0
        val runningKeys = store.state.value.entries
            .asSequence()
            .filter { (pageKey, page) ->
                page != null &&
                    page!!.isStageRunning &&
                    (origin == null || store.pageLeaseOwner(pageKey)?.let { it == origin } != false)
            }
            .map { it.key }
            .toList()
        if (runningKeys.isEmpty()) return 0
        store.fastCancelInFlightStagesInMemory(origin = origin, cancellationReason = reason)
        var flipped = 0
        runBlocking {
            runningKeys.forEach { key ->
                try {
                    markPageCancelled(store, key, reason, origin)
                    flipped++
                } catch (e: Throwable) {
                    logcat(LogPriority.WARN, e) { "Failed to mark page cancelled: $key" }
                }
            }
        }
        return flipped
    }

    /**
     * TachiyomiAT: evicts a single-page job its worker abandoned (stuck in
     * uncancellable native code past its deadline). The coroutine can't be
     * interrupted, but its [activePageJobs] entry reads "active" and so blocks
     * retries via dedup. Remove it (best-effort cancel first) so the page becomes
     * eligible again. Idempotent.
     */
    fun markPageJobStuck(chapterId: Long, pageKey: String) {
        val jobKey = "$chapterId:$pageKey"
        val job = synchronized(activePageJobs) { activePageJobs.remove(jobKey) }
        try {
            job?.cancel()
        } catch (_: Throwable) {}
        logcat(LogPriority.WARN) {
            "TachiyomiAT evicted stuck page job: jobKey=$jobKey (worker abandoned in native/HTTP code)"
        }
    }

    /**
     * TachiyomiAT: cancels the in-flight single-page job for one [pageKey] in
     * [chapterId], if any. This is the per-page granularity [cancelPageTranslations]
     * (chapter-scoped) is too coarse for; it backs the per-page cancel button.
     *
     * Returns true if a job was actually cancelled, false if none was running
     * (already finished or deduped).
     */
    fun cancelPageTranslation(
        chapterId: Long,
        pageKey: String,
        reason: String = "Manual page cancellation requested",
    ): Boolean {
        val jobKey = "$chapterId:$pageKey"
        val job = synchronized(activePageJobs) { activePageJobs.remove(jobKey) }
        if (job != null) pageCancellationReasons[jobKey] = reason
        job?.cancel()
        val autoCancelled = cancelAutoTranslations(chapterId)
        // The reader's stop action is synchronous. Flip the shared store before
        // returning so the UI cannot remain stuck on RUNNING while the cancelled
        // worker is still unwinding its coroutine finally block.
        immediateStoreResolver?.invoke(chapterId)?.let { store ->
            runBlocking { markPageCancelled(store, pageKey, reason) }
        }
        return job != null || autoCancelled
    }

    /**
     * Cancels all in-flight single-page jobs for [chapterId] and evicts the
     * reader page streams for that chapter. Call on reader navigating away so the
     * previous chapter can't keep holding the executor's single permit.
     *
     * TachiyomiAT: suspend + bounded join. After cancelling, waits (up to
     * [JOIN_TIMEOUT_MS]) for the jobs' finally blocks. translatePage's finally
     * resets a stranded RUNNING status on a NonCancellable child, which needs the
     * coroutine to unwind. Without waiting, the previous chapter's reset could
     * land AFTER the reader subscribed to the new chapter's store — briefly
     * surfacing chapter 1's RUNNING overlay while chapter 2 is on screen.
     *
     * NOTE: store eviction is NOT done here; the store lifecycle is owned by
     * TranslationManager. The caller evicts the store if needed.
     */
    suspend fun cancelPageTranslations(
        chapterId: Long,
        reason: String = "Reader chapter navigation",
    ) {
        // Stop the rolling coordinator's admission for the outgoing
        // chapter. A new chapter's [updateAutoWindow] (new identity) cancels
        // and resets it; cancelling here bounds the gap between navigate-away
        // and that first window update so the old chapter cannot keep holding
        // the executor permit. cancel (not shutdown) keeps the last snapshot
        // for the brief handoff window.
        val cancellation = beginAutoCancellation(chapterId)
        try {
            cancellation?.let(::performAutoCancellation)
            val prefix = "$chapterId:"
            // Chapter teardown also drops the outcomes retained for its pages.
            manualOutcomes.keys.removeAll { it.startsWith(prefix) }
            val toJoin = mutableListOf<Job>()
            synchronized(activePageJobs) {
                val iterator = activePageJobs.entries.iterator()
                while (iterator.hasNext()) {
                    val (key, job) = iterator.next()
                    if (key.startsWith(prefix)) {
                        pageCancellationReasons[key] = reason
                        job.cancel()
                        toJoin.add(job)
                        iterator.remove()
                    }
                }
            }
            if (toJoin.isNotEmpty()) {
                withTimeoutOrNull(JOIN_TIMEOUT_MS) {
                    toJoin.joinAll()
                }
            }
        } finally {
            finishAutoCancellation(cancellation)
        }
    }

    /**
     * Cancels every in-flight single-page job and rolling auto window.
     * Call on reader destroy or master translation toggle off so no orphaned work
     * keeps running.
     *
     * TachiyomiAT bug 4 fix: after cancelling jobs, synchronously flip every
     * active store's in-flight pages to CANCELLED so reader overlays clear
     * immediately. Callers that want to preserve artifacts should NOT call this;
     * use the per-chapter paths instead. The durable CANCELLED write itself is
     * owned by the caller via [ChapterTranslationStore.clearTransientQueuePages]
     * — this method only guarantees the in-memory snapshot settles synchronously.
     */
    fun cancelAllPageTranslations(reason: String = "Reader translation stop") {
        // Full reader-close / master-toggle-off teardown of the
        // rolling coordinator. shutdown (not cancel) nulls the snapshot and
        // drops scheduling state so no observer or coordinator lingers.
        val cancellation = beginAutoCancellation(null)
        try {
            cancellation?.let(::performAutoCancellation)
            shutdownAutoCoordinator()
            manualOutcomes.clear()
            val affectedChapterIds = mutableSetOf<Long>()
            synchronized(activePageJobs) {
                val iterator = activePageJobs.entries.iterator()
                while (iterator.hasNext()) {
                    val (key, job) = iterator.next()
                    pageCancellationReasons[key] = reason
                    job.cancel()
                    iterator.remove()
                    key.substringBefore(':').toLongOrNull()?.let { affectedChapterIds += it }
                }
            }
            // Flip affected chapters' in-flight pages synchronously so the
            // overlay clears without waiting for each job's finally block.
            affectedChapterIds.forEach { markChapterCancelledSync(it, reason = reason) }
        } finally {
            finishAutoCancellation(cancellation)
        }
    }

    /**
     * Reader-owned teardown boundary. Cancels and detaches every coordinator
     * and single-page job, then joins the captured ownership
     * outside all scheduler monitors. The returned suspension completes only
     * after coordinator/native/page work capable of touching reader streams has
     * terminated; the scheduler scope itself remains available for the next
     * reader session.
     */
    suspend fun awaitReaderStop(reason: String = "Reader stopped") {
        synchronized(autoCoordinatorLock) {
            readerStopInFlight = true
        }
        try {
            val cancellation = beginAutoCancellation(null)
            val jobsToJoin = LinkedHashSet<Job>()
            val coordinatorsToJoin = LinkedHashSet<RollingAutoCoordinator>()
            try {
                cancellation?.let(::performAutoCancellation)
                // Detach/publish the null owner before any suspension. This also
                // calls shutdown() synchronously, while the actual join happens
                // below without holding autoCoordinatorLock or callLock.
                shutdownAutoCoordinator()
                synchronized(autoCoordinatorLock) {
                    coordinatorsToJoin += retiringAutoCoordinators
                }
                manualOutcomes.clear()
                val affectedChapterIds = mutableSetOf<Long>()
                synchronized(activePageJobs) {
                    val iterator = activePageJobs.entries.iterator()
                    while (iterator.hasNext()) {
                        val (key, job) = iterator.next()
                        pageCancellationReasons[key] = reason
                        job.cancel()
                        jobsToJoin += job
                        iterator.remove()
                        key.substringBefore(':').toLongOrNull()?.let { affectedChapterIds += it }
                    }
                }
                affectedChapterIds.forEach { markChapterCancelledSync(it, reason = reason) }
            } finally {
                finishAutoCancellation(cancellation)
            }

            jobsToJoin.joinAll()
            coordinatorsToJoin.forEach { it.awaitTermination() }
        } finally {
            synchronized(autoCoordinatorLock) {
                readerStopInFlight = false
            }
        }
    }

    private fun beginAutoCancellation(chapterId: Long?): AutoCancellation? =
        synchronized(autoCoordinatorLock) {
            val owner = autoCoordinator?.takeIf { chapterId == null || it.identity.chapterId == chapterId }
            if (chapterId != null && owner == null) return@synchronized null
            val epoch = ++autoCancellationEpoch
            val ownsGlobal = chapterId == null && !globalAutoCancellationInFlight
            if (ownsGlobal) {
                globalAutoCancellationInFlight = true
                globalAutoCancellationEpoch = epoch
            }
            val ownsOwner = owner != null && !owner.cancellationInFlight
            if (ownsOwner) {
                owner!!.cancellationInFlight = true
                owner.cancellationEpoch = epoch
                if (chapterId != null) chapterCancellationEpochs[chapterId] = epoch
            }
            AutoCancellation(
                owner = owner,
                chapterId = chapterId,
                invokeCoordinator = ownsOwner,
                ownsOwnerCancellation = ownsOwner,
                ownsGlobalCancellation = ownsGlobal,
                epoch = epoch,
            )
        }

    private fun performAutoCancellation(cancellation: AutoCancellation) {
        if (!cancellation.invokeCoordinator) return
        val owner = cancellation.owner ?: return
        synchronized(owner.callLock) {
            owner.coordinator.cancel()
        }
    }

    private fun finishAutoCancellation(cancellation: AutoCancellation?) {
        if (cancellation == null) return
        synchronized(autoCoordinatorLock) {
            if (cancellation.ownsOwnerCancellation &&
                cancellation.owner?.cancellationEpoch == cancellation.epoch
            ) {
                cancellation.owner?.cancellationInFlight = false
                cancellation.chapterId?.let { chapterId ->
                    if (chapterCancellationEpochs[chapterId] == cancellation.epoch) {
                        chapterCancellationEpochs.remove(chapterId)
                    }
                }
            }
            if (cancellation.ownsGlobalCancellation && globalAutoCancellationEpoch == cancellation.epoch) {
                globalAutoCancellationInFlight = false
            }
        }
    }

    /** Caller holds [autoCoordinatorLock]. */
    private fun retireLocked(owner: AutoCoordinatorOwner): AutoCoordinatorOwner {
        owner.cancellationInFlight = true
        retiringAutoCoordinators += owner.coordinator
        return owner
    }

    /**
     * Cancellation is deliberately initiated outside [autoCoordinatorLock]. The
     * replacement coordinator already owns the retiring predecessor barrier, so
     * it cannot admit native work until this coordinator has actually terminated.
     */
    private fun shutdownRetiringCoordinator(owner: AutoCoordinatorOwner) {
        synchronized(owner.callLock) {
            owner.coordinator.shutdown()
        }
        scope.launch {
            owner.coordinator.awaitTermination()
            synchronized(autoCoordinatorLock) {
                retiringAutoCoordinators.remove(owner.coordinator)
            }
        }
    }

    private data class AutoCoordinatorUpdate(
        val owner: AutoCoordinatorOwner,
        val predecessorToShutdown: AutoCoordinatorOwner?,
    )

    private data class AutoCancellation(
        val owner: AutoCoordinatorOwner?,
        val chapterId: Long?,
        val invokeCoordinator: Boolean,
        val ownsOwnerCancellation: Boolean,
        val ownsGlobalCancellation: Boolean,
        val epoch: Long,
    )

    private class AutoCoordinatorOwner(
        val identity: AutoChapterIdentity,
        val sessionKey: String,
        val store: ChapterTranslationStore,
        val coordinator: RollingAutoCoordinator,
        val ownerVersion: Long,
    ) {
        val callLock = Any()
        var cancellationInFlight: Boolean = false
        var cancellationEpoch: Long = 0L
    }
}
