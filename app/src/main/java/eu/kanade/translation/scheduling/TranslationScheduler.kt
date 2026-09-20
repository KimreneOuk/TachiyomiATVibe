package eu.kanade.translation.scheduling

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.storage.ChapterTranslationStore
import eu.kanade.translation.pipeline.PageWriteOrigin
import eu.kanade.translation.orchestration.TranslationPageRequest
import eu.kanade.translation.orchestration.TranslationSession
import eu.kanade.translation.artifact.GroupCommitConfiguration
import eu.kanade.translation.model.PageLifecycle
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.cancelInFlightStages
import eu.kanade.translation.model.hasRecognizedTranslation
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isCleanedImageReady
import eu.kanade.translation.model.isStageFailed
import eu.kanade.translation.model.isStageRunning
import eu.kanade.translation.model.lifecycle
import eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceMode
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTracePlan
import eu.kanade.translation.diagnostics.TranslationTraceStage
import eu.kanade.translation.orchestration.ReaderSessionIntent
import eu.kanade.translation.orchestration.SessionAdmission
import eu.kanade.translation.orchestration.TranslationSessionCoordinator
import eu.kanade.translation.translator.TranslatorComputeClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
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
 * TachiyomiAT: owns single-page (and auto) translation jobs so they can be
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
        // Ticket 03: tear down the rolling coordinator (which owns its own
        // scope when not injected) so scheduler close cannot leak it. scope.cancel()
        // alone does not reach the coordinator's supervisor scope.
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

        /**
         * T917 D2 §2.4: cap of the [manualOutcomes] map (evict-oldest). Bounds
         * the memory a long reader session can pin to one entry per distinct
         * manual single-page intent.
         */
        private const val MANUAL_OUTCOME_MAP_CAP = 32
    }

    // TachiyomiAT: the prior implementation launched jobs on GlobalScope and
    // discarded the Job, so orphaned jobs from a previous chapter kept holding
    // the translator's single permit, starving all later work. Keeping the scope
    // lets auto off / chapter switch / reader close cancel actually-running work.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Tracks independently-launched single-page jobs by "$chapterId:$pageKey".
    // Sequential auto-prefetch is deduped separately via [queuedPageKeys]
    // (it uses no per-page coroutine).
    private val activePageJobs = ConcurrentHashMap<String, Job>()

    /**
     * T917 D2 §2.4: typed outcome of the last completed manual single-page
     * intents, keyed like [activePageJobs]. Bounded (evict-oldest, cap 32) and
     * cleared with the existing chapter-switch teardown. This is the Phase-5
     * hook the ReaderViewModel will map to the "Translating · background job"
     * chip — the reader already sees the owner's live stage states through the
     * store flow, so no UI reads it yet.
     */
    private val manualOutcomes: MutableMap<String, SinglePageOutcome> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, SinglePageOutcome>(16, 0.75f, false) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SinglePageOutcome>): Boolean =
                    size > MANUAL_OUTCOME_MAP_CAP
            },
        )

    /**
     * T917 Phase 5 (spec §5.2.2): read-only projection of the last completed
     * manual single-page intent for this identity. Presence in the map is not
     * success — the caller must treat the typed value through the pure
     * TranslationUiTruth mapper. Unknown or foreign (chapter, page) identities
     * observe nothing, and the bounded map is never mutated by this accessor.
     */
    fun manualOutcomeFor(chapterId: Long, pageKey: String): SinglePageOutcome? =
        synchronized(manualOutcomes) { manualOutcomes["$chapterId:$pageKey"] }

    fun recordManualOutcome(chapterId: Long, pageKey: String, outcome: SinglePageOutcome) {
        synchronized(manualOutcomes) { manualOutcomes["$chapterId:$pageKey"] = outcome }
    }

    // Pages queued/executing inside an ordered auto-prefetch batch. Closes the
    // gap where overlapping selection windows enqueue the same page (e.g. page 6
    // in [4,5,6], [5,6,7], and again [4,5,6]); without this, a page could run 2-4
    // times before any persisted "done" state is visible to later batches.
    private val queuedPageKeys = ConcurrentHashMap.newKeySet<String>()

    // Manager-owned auto-prefetch windows; kept here (not on the reader's
    // ViewModel scope) so they can be cancelled on chapter switch / reader close.
    private val activeAutoJobs = ConcurrentHashMap<String, Job>()
    private val autoWindowIds = AtomicLong(0L)
    private val autoOwnerVersions = AtomicLong(0L)
    private val autoGenerations = ConcurrentHashMap<Long, AtomicLong>()

    // Ticket 03: rolling auto-coordinator ownership. At most one active per
    // chapter session; replaces the generation/list scheduling path.
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
     * Ticket 03: rolling coordinator entry point. Accepts a desired-window
     * update (visible page + ahead target) and delegates to the coordinator.
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

    /**
     * T922 Phase 3 amendment §10.7 reachability inventory: the ONLY caller of
     * this method is [eu.kanade.translation.orchestration.TranslationManager.requestAutoWindow],
     * which itself has zero callers in live code (repo-wide sweep: definitions
     * plus this delegation only; the reader drives
     * [updateAutoWindow]/[RollingAutoCoordinator]). The path is dormant, so it
     * is NOT wired into the trace schema and its internal legacy logs are
     * intentionally retained for the dormant code path. Deprecation is the
     * audit marker; actual removal/re-wiring is a separate reviewed change
     * (this phase must not delete it).
     */
    @Deprecated(
        message = "Superseded by updateAutoWindow/RollingAutoCoordinator. No live callers remain; " +
            "scheduled for removal or trace wiring in a separate change (T922 §10.7).",
    )
    fun requestAutoWindow(
        session: TranslationSession,
        requests: List<TranslationPageRequest>,
    ) {
        if (requests.isEmpty()) return
        val chapterId = session.chapter.id ?: return
        // TachiyomiAT: do NOT eagerly cancel prior auto jobs for this chapter.
        // The prior cancelAutoJobsForChapter(chapterId) here tore down the
        // in-flight job on EVERY page-change. Because each translation is
        // serialized behind a single permit and takes seconds, eager cancel meant
        // scrolling n->n+1->n+2 cancelled each job mid-OCR, so the window could
        // never get ahead of the reader. Instead bump the generation below: that
        // marks already-running jobs stale, they finish the page they started
        // (work not wasted), then bail before the next page.
        val generation = autoGenerations.computeIfAbsent(chapterId) { AtomicLong(0L) }.incrementAndGet()
        // Keep reservations owned by an older window until that window releases
        // them. Removing them here races with the still-running job and lets the
        // newer window enqueue the same page a second time.

        val accepted = mutableListOf<Pair<String, TranslationPageRequest>>()
        val distinctRequests = requests.distinctBy { it.id }
            .sortedWith(compareBy<TranslationPageRequest> { it.priority }.thenBy { it.id.pageIndex })

        for (request in distinctRequests) {
            val current = session.store.state.value[request.storageKey]
            if (!TranslationLifecyclePolicy.shouldSchedule(current)) {
                logAutoDecision("skipped", request, current, request.streamAvailable)
                continue
            }

            val manualJobKey = "${request.id.chapterId}:${request.storageKey}"
            val manualJob = activePageJobs[manualJobKey]
            if (manualJob != null && manualJob.isActive) {
                logAutoDecision("skipped", request, current, request.streamAvailable, "manual-active")
                continue
            }
            if (manualJob != null) activePageJobs.remove(manualJobKey)

            val reservationKey = autoReservationKey(request)
            if (!queuedPageKeys.add(reservationKey)) {
                logAutoDecision("skipped", request, current, request.streamAvailable, "already-queued")
                continue
            }

            accepted.add(reservationKey to request)
            logAutoDecision("scheduled", request, current, request.streamAvailable)
        }

        if (accepted.isEmpty()) return

        val jobKey = "auto:$chapterId:$generation:${session.key}:${autoWindowIds.incrementAndGet()}"
        val job = scope.launch {
            val completedReservations = mutableSetOf<String>()
            try {
                // TachiyomiAT: do NOT clearTransientQueuePages here. The prior
                // call flipped every non-rendered PENDING/RUNNING/CANCELLED/FAILED
                // page to CANCELLED on every scroll — including pages a sibling
                // job from the prior generation was still translating. Staleness
                // is already handled by the generation guard below + the per-page
                // shouldSkipAutoScheduling check, so a store-wide clear is
                // redundant. Explicit cancels still do it where a full reset is wanted.
                for ((reservationKey, request) in accepted) {
                    coroutineContext.ensureActive()
                    if (autoGenerations[chapterId]?.get() != generation) {
                        logAutoDecision("cancelled", request, session.store.state.value[request.storageKey], request.streamAvailable, "stale-generation")
                        completedReservations.add(reservationKey)
                        queuedPageKeys.remove(reservationKey)
                        break
                    }
                    val current = session.store.state.value[request.storageKey]
                    if (!TranslationLifecyclePolicy.shouldSchedule(current)) {
                        logAutoDecision("skipped", request, current, request.streamAvailable, "completed-while-queued")
                        completedReservations.add(reservationKey)
                        queuedPageKeys.remove(reservationKey)
                        continue
                    }
                    val manualJobKey = "${request.id.chapterId}:${request.storageKey}"
                    val manualJob = activePageJobs[manualJobKey]
                    if (manualJob != null && manualJob.isActive) {
                        logAutoDecision("skipped", request, current, request.streamAvailable, "manual-active-while-queued")
                        completedReservations.add(reservationKey)
                        queuedPageKeys.remove(reservationKey)
                        continue
                    }
                    if (manualJob != null) activePageJobs.remove(manualJobKey)

                    markAutoPageStarting(session.store, request.storageKey, current)

                    if (current != null &&
                        current.hasRecognizedTranslation &&
                        current.renderStatus != StageStatus.READY &&
                        current.isCleanedImageReady
                    ) {
                        try {
                            // T917 D1: the legacy auto window's work is AUTO at
                            // the lease layer (never preempts, can be evicted
                            // by a reader tap).
                            executor.translateSinglePage(
                                session.manga,
                                session.chapter,
                                session.source,
                                request.storageKey,
                                force = false,
                                origin = PageWriteOrigin.AUTO,
                            )
                        } catch (e: CancellationException) {
                            markPageCancelled(session.chapter, request.storageKey)
                            logAutoDecision("cancelled", request, session.store.state.value[request.storageKey], request.streamAvailable)
                            throw e
                        } catch (e: Throwable) {
                            logAutoDecision("failed:resume-render", request, session.store.state.value[request.storageKey], request.streamAvailable)
                            logcat(LogPriority.ERROR, e) {
                                "TachiyomiAT auto resume render failed: id=${request.id} storageKey=${request.storageKey}"
                            }
                        } finally {
                            completedReservations.add(reservationKey)
                            queuedPageKeys.remove(reservationKey)
                        }
                        continue
                    }

                    val streamFn = try {
                        request.streamProvider()
                    } catch (e: CancellationException) {
                        logAutoDecision("cancelled", request, current, request.streamAvailable)
                        throw e
                    } catch (e: Throwable) {
                        logcat(LogPriority.WARN, e) {
                            "TachiyomiAT auto stream provider failed: id=${request.id} " +
                                "index=${request.id.pageIndex} storageKey=${request.storageKey}"
                        }
                        null
                    }

                    if (streamFn == null) {
                        logAutoDecision("soft-skip:no-stream", request, current, false)
                        markPageAutoSoftSkipped(session.store, request.storageKey)
                        completedReservations.add(reservationKey)
                        queuedPageKeys.remove(reservationKey)
                        continue
                    }

                    try {
                        executor.translateSinglePageFromStream(
                            session.manga,
                            session.chapter,
                            session.source,
                            request.storageKey,
                            streamFn,
                            force = false,
                        )
                    } catch (e: CancellationException) {
                        markPageCancelled(session.chapter, request.storageKey)
                        logAutoDecision("cancelled", request, session.store.state.value[request.storageKey], true)
                        throw e
                    } catch (e: Throwable) {
                        logAutoDecision("failed:pipeline", request, session.store.state.value[request.storageKey], true)
                        logcat(LogPriority.ERROR, e) {
                            "TachiyomiAT auto page translation failed: id=${request.id} " +
                                "storageKey=${request.storageKey} chapter=${session.chapter.name} " +
                                "manga=${session.manga.title} source=${session.source.id}"
                        }
                        continue
                    } finally {
                        completedReservations.add(reservationKey)
                        queuedPageKeys.remove(reservationKey)
                    }
                    if (autoGenerations[chapterId]?.get() != generation) {
                        break
                    }
                }
            } catch (e: CancellationException) {
                for ((reservationKey, request) in accepted) {
                    if (reservationKey !in completedReservations) {
                        logAutoDecision(
                            "cancelled",
                            request,
                            session.store.state.value[request.storageKey],
                            request.streamAvailable,
                        )
                    }
                }
                throw e
            } finally {
                accepted.forEach { (reservationKey, _) -> queuedPageKeys.remove(reservationKey) }
                activeAutoJobs.remove(jobKey)
            }
        }
        activeAutoJobs[jobKey] = job
    }

    fun cancelAutoTranslations(chapterId: Long? = null): Boolean {
        // Ticket 03: reserve cancellation under the pointer monitor, then
        // invoke the coordinator outside it. Matching updates either complete
        // before this reservation or observe it and are suppressed; no new
        // same-chapter loop can escape between capture and cancel.
        val cancellation = beginAutoCancellation(chapterId)
        val coordinatorCancelled = cancellation?.owner != null

        val jobPrefix = chapterId?.let { "auto:$it:" }
        val queuePrefix = chapterId?.let { "auto:$it:" }
        var cancelled = coordinatorCancelled

        try {
            cancellation?.let(::performAutoCancellation)
            if (chapterId == null) {
                autoGenerations.values.forEach { it.incrementAndGet() }
            } else {
                autoGenerations.computeIfAbsent(chapterId) { AtomicLong(0L) }.incrementAndGet()
            }

            val jobIterator = activeAutoJobs.entries.iterator()
            while (jobIterator.hasNext()) {
                val (key, job) = jobIterator.next()
                if (jobPrefix == null || key.startsWith(jobPrefix)) {
                    job.cancel()
                    jobIterator.remove()
                    cancelled = true
                }
            }

            if (queuePrefix == null) {
                queuedPageKeys.removeIf { it.startsWith("auto:") }
            } else {
                queuedPageKeys.removeIf { it.startsWith(queuePrefix) }
            }

            // TachiyomiAT: fast non-blocking in-memory flip on the store first so the
            // reader overlay/dim clears immediately on the current frame. Durable persistence
            // is dispatched asynchronously to IO without blocking the caller.
            if (chapterId != null) {
                val inMemoryFlipped = immediateStoreResolver?.invoke(chapterId)?.fastCancelInFlightStagesInMemory() ?: 0
                if (inMemoryFlipped > 0) {
                    cancelled = true
                }
                if (cancelled) {
                    scope.launch {
                        try {
                            markChapterCancelledAsync(chapterId)
                        } catch (e: Throwable) {
                            logcat(LogPriority.WARN, e) { "Failed to drain cancellation for $chapterId" }
                        }
                    }
                }
            } else {
                // Global auto cancel: flip every active store's in-flight pages.
                val affectedChapterIds = activeAutoJobs.keys.asSequence()
                    .mapNotNull { it.substringAfter("auto:").substringBefore(':').toLongOrNull() }
                    .distinct()
                    .toList()
                var anyFlipped = false
                affectedChapterIds.forEach { id ->
                    val flipped = immediateStoreResolver?.invoke(id)?.fastCancelInFlightStagesInMemory() ?: 0
                    if (flipped > 0) anyFlipped = true
                }
                if (anyFlipped) {
                    cancelled = true
                }
                if (cancelled) {
                    scope.launch {
                        affectedChapterIds.forEach { id ->
                            try {
                                markChapterCancelledAsync(id)
                            } catch (e: Throwable) {
                                logcat(LogPriority.WARN, e) { "Failed to drain cancellation for $id" }
                            }
                        }
                    }
                }
            }

            return cancelled
        } finally {
            finishAutoCancellation(cancellation)
        }
    }

    private fun autoReservationKey(request: TranslationPageRequest): String {
        val safeStorageKey = request.storageKey.replace(':', '_')
        // Generation belongs to the window, not to page ownership. A stable key
        // makes overlapping windows share one reservation while the old worker
        // is still processing its page.
        return "auto:${request.id.chapterId}:${request.id.sourceId}:${request.id.mangaId}:${request.id.pageIndex}:$safeStorageKey"
    }

    private suspend fun markAutoPageStarting(
        store: ChapterTranslationStore,
        pageKey: String,
        current: PageTranslation?,
    ) {
        if (!TranslationLifecyclePolicy.shouldSchedule(current)) return
        store.updatePageFromCurrentSnapshot(pageKey, "auto page starting") { existing ->
            val page = existing ?: PageTranslation(sourceFileName = pageKey)
            if (page.renderStatus == StageStatus.READY && page.isCleanedImageReady) return@updatePageFromCurrentSnapshot page
            page.apply {
                sourceFileName = pageKey
                errorMessage = null
                when {
                    hasRecognizedTranslation && isCleanedImageReady -> {
                        renderStatus = StageStatus.RUNNING
                    }
                    hasRecognizedTranslation -> {
                        inpaintStatus = StageStatus.RUNNING
                        renderStatus = StageStatus.PENDING
                    }
                    ocrStatus != StageStatus.READY -> {
                        ocrStatus = StageStatus.RUNNING
                        translationStatus = StageStatus.PENDING
                        inpaintStatus = StageStatus.PENDING
                        renderStatus = StageStatus.PENDING
                    }
                    translationStatus != StageStatus.READY -> {
                        translationStatus = StageStatus.RUNNING
                    }
                    else -> {
                        renderStatus = StageStatus.RUNNING
                    }
                }
                updatedAt = System.currentTimeMillis()
            }
        }
    }

    private suspend fun markPageAutoSoftSkipped(store: ChapterTranslationStore, pageKey: String) {
        store.updatePageFromCurrentSnapshot(pageKey, "auto page soft skip") { existing ->
            val page = existing ?: PageTranslation(
                sourceFileName = pageKey,
                ocrStatus = StageStatus.CANCELLED,
                updatedAt = System.currentTimeMillis(),
            )
            if (page.renderStatus == StageStatus.READY) return@updatePageFromCurrentSnapshot page
            page.apply {
                cancelInFlightStages()
                updatedAt = System.currentTimeMillis()
            }
        }
    }

    private fun logAutoDecision(
        decision: String,
        request: TranslationPageRequest,
        page: PageTranslation?,
        streamAvailable: Boolean?,
        reason: String? = null,
    ) {
        logcat(LogPriority.INFO) {
            "TachiyomiAT auto page $decision: id=${request.id} index=${request.id.pageIndex} " +
                "storageKey=${request.storageKey} lifecycle=${page?.lifecycle ?: PageLifecycle.Pending} " +
                "retry=${page?.retryCount ?: 0} streamAvailable=${streamAvailable ?: "unknown"} " +
                "cleaned=${page?.cleanedImageName != null}" +
                (reason?.let { " reason=$it" } ?: "")
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
            // T922 Phase 3 (plan §4.4 Manual): the schedule + run are created
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
                    // T922 §10.4 legacy-log migration: the raw page/chapter/manga
                    // identifiers this line used to carry are covered by the
                    // correlated trace run (sid/rid + keyed page token), so the
                    // retained log carries only a bounded errorType token plus
                    // the throwable for native/ORT stack diagnostics (default
                    // logcat tag, outside the TachiyomiAT.Translation privacy
                    // boundary).
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
                    // Ticket 03: a manual single-page job no longer holds this
                    // page, so any auto-window slot that was hidden from the
                    // rolling coordinator by [updateAutoWindow]'s manual
                    // arbitration becomes admissible again. Poke a reconcile.
                    reconcileAutoWindow()
                    // An attached outcome means the job never owned the page —
                    // it only observed another origin's terminal commit. Keep
                    // the stranded-RUNNING reset scoped to owned work.
                    val attachFamily = when (outcome) {
                        is SinglePageOutcome.Attached -> true
                        else -> false
                    }
                    if (cancelledMidFlight && !attachFamily) {
                        // Reset stranded RUNNING on a NonCancellable child so the
                        // reset can't be torn down by the cancellation that triggered
                        // it. Guarded so a reset failure never masks the original CancellationException.
                        try {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                markPageCancelled(chapter, pageKey)
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
            // Terminal ownership (amendment §10.2): completion handlers run after
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
     * T922 Phase 3: bounded trace outcome for a manual single-page outcome
     * value. Attached-family jobs never owned the page; Rejected means the
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
    private suspend fun markPageCancelled(chapter: Chapter, pageKey: String) {
        val chapterId = chapter.id ?: return
        val store = storeResolver.resolve(chapterId) ?: return
        markPageCancelled(store, pageKey)
    }

    private suspend fun markPageCancelled(store: ChapterTranslationStore, pageKey: String) {
        // Peek first: if no entry or already terminal, nothing is stranded — skip
        // the write rather than creating a spurious FAILED entry.
        val existing = store.state.value[pageKey] ?: return
        if (existing.hasRenderedResult || existing.isStageFailed) return
        // T924 LI-3: while a batch run holds this page's BATCH-origin stage
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
        store.updatePageFromCurrentSnapshot(pageKey, "auto page cancelled") { current ->
            // Re-check inside the lock in case it changed between peek and write.
            val cur = current ?: PageTranslation(
                sourceFileName = pageKey,
                ocrStatus = StageStatus.CANCELLED,
                updatedAt = System.currentTimeMillis(),
            ).also { it.ocrError = "Translation cancelled" }
            if (cur.hasRenderedResult || cur.isStageFailed) return@updatePageFromCurrentSnapshot cur
            cur.apply {
                cancelInFlightStages()
                ocrError = "Translation cancelled"
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
    suspend fun markChapterCancelledAsync(chapterId: Long): Int = withContext(Dispatchers.IO) {
        val store = immediateStoreResolver?.invoke(chapterId) ?: return@withContext 0
        val runningKeys = store.state.value.entries
            .asSequence()
            .filter { (_, page) -> page != null && (page!!.isStageRunning || page.ocrError == "Translation cancelled") }
            .map { it.key }
            .toList()
        if (runningKeys.isEmpty()) return@withContext 0
        var flipped = 0
        runningKeys.forEach { key ->
            try {
                markPageCancelled(store, key)
                flipped++
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Failed to mark page cancelled: $key" }
            }
        }
        flipped
    }

    fun markChapterCancelledSync(chapterId: Long): Int {
        val store = immediateStoreResolver?.invoke(chapterId) ?: return 0
        val runningKeys = store.state.value.entries
            .asSequence()
            .filter { (_, page) -> page != null && page!!.isStageRunning }
            .map { it.key }
            .toList()
        if (runningKeys.isEmpty()) return 0
        store.fastCancelInFlightStagesInMemory()
        var flipped = 0
        runBlocking {
            runningKeys.forEach { key ->
                try {
                    markPageCancelled(store, key)
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
        queuedPageKeys.removeIf { it.startsWith("auto:$chapterId:") && it.endsWith(":${pageKey.replace(':', '_')}") }
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
    fun cancelPageTranslation(chapterId: Long, pageKey: String): Boolean {
        val jobKey = "$chapterId:$pageKey"
        queuedPageKeys.remove(jobKey)
        queuedPageKeys.removeIf { it.startsWith("auto:$chapterId:") && it.endsWith(":${pageKey.replace(':', '_')}") }
        val job = synchronized(activePageJobs) { activePageJobs.remove(jobKey) }
        job?.cancel()
        val autoCancelled = cancelAutoTranslations(chapterId)
        // The reader's stop action is synchronous. Flip the shared store before
        // returning so the UI cannot remain stuck on RUNNING while the cancelled
        // worker is still unwinding its coroutine finally block.
        immediateStoreResolver?.invoke(chapterId)?.let { store ->
            runBlocking { markPageCancelled(store, pageKey) }
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
    suspend fun cancelPageTranslations(chapterId: Long) {
        // Ticket 03: stop the rolling coordinator's admission for the outgoing
        // chapter. A new chapter's [updateAutoWindow] (new identity) cancels
        // and resets it; cancelling here bounds the gap between navigate-away
        // and that first window update so the old chapter cannot keep holding
        // the executor permit. cancel (not shutdown) keeps the last snapshot
        // for the brief handoff window.
        val cancellation = beginAutoCancellation(chapterId)
        try {
            cancellation?.let(::performAutoCancellation)
            val prefix = "$chapterId:"
            queuedPageKeys.removeIf { it.startsWith(prefix) }
            queuedPageKeys.removeIf { it.startsWith("auto:$chapterId:") }
            // T917 D2: the chapter-switch teardown also drops this chapter's
            // recorded manual outcomes (bounded map, §2.4).
            manualOutcomes.keys.removeAll { it.startsWith(prefix) }
            val toJoin = mutableListOf<Job>()
            val autoIterator = activeAutoJobs.entries.iterator()
            while (autoIterator.hasNext()) {
                val (key, job) = autoIterator.next()
                if (key.startsWith("auto:$chapterId:")) {
                    job.cancel()
                    toJoin.add(job)
                    autoIterator.remove()
                }
            }
            synchronized(activePageJobs) {
                val iterator = activePageJobs.entries.iterator()
                while (iterator.hasNext()) {
                    val (key, job) = iterator.next()
                    if (key.startsWith(prefix)) {
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
     * Cancels every in-flight single-page job and every auto-prefetch window.
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
    fun cancelAllPageTranslations() {
        // Ticket 03: full reader-close / master-toggle-off teardown of the
        // rolling coordinator. shutdown (not cancel) nulls the snapshot and
        // drops scheduling state so no observer or coordinator lingers.
        val cancellation = beginAutoCancellation(null)
        try {
            cancellation?.let(::performAutoCancellation)
            shutdownAutoCoordinator()
            queuedPageKeys.clear()
            manualOutcomes.clear()
            val affectedChapterIds = mutableSetOf<Long>()
            val autoIterator = activeAutoJobs.entries.iterator()
            while (autoIterator.hasNext()) {
                val (key, job) = autoIterator.next()
                job.cancel()
                autoIterator.remove()
                key.substringAfter("auto:").substringBefore(':').toLongOrNull()?.let {
                    affectedChapterIds += it
                }
            }
            synchronized(activePageJobs) {
                val iterator = activePageJobs.entries.iterator()
                while (iterator.hasNext()) {
                    val (key, job) = iterator.next()
                    job.cancel()
                    iterator.remove()
                    key.substringBefore(':').toLongOrNull()?.let { affectedChapterIds += it }
                }
            }
            // TachiyomiAT bug 4 fix: flip every affected chapter's in-flight pages
            // synchronously so the dim/overlay clears without waiting on each job's
            // finally block.
            affectedChapterIds.forEach { markChapterCancelledSync(it) }
        } finally {
            finishAutoCancellation(cancellation)
        }
    }

    /**
     * Reader-owned teardown boundary. Cancels and detaches every coordinator,
     * auto-prefetch job, and single-page job, then joins the captured ownership
     * outside all scheduler monitors. The returned suspension completes only
     * after coordinator/native/page work capable of touching reader streams has
     * terminated; the scheduler scope itself remains available for the next
     * reader session.
     */
    suspend fun awaitReaderStop() {
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
                queuedPageKeys.clear()
                manualOutcomes.clear()
                val affectedChapterIds = mutableSetOf<Long>()
                val autoIterator = activeAutoJobs.entries.iterator()
                while (autoIterator.hasNext()) {
                    val (key, job) = autoIterator.next()
                    job.cancel()
                    jobsToJoin += job
                    autoIterator.remove()
                    key.substringAfter("auto:").substringBefore(':').toLongOrNull()?.let {
                        affectedChapterIds += it
                    }
                }
                synchronized(activePageJobs) {
                    val iterator = activePageJobs.entries.iterator()
                    while (iterator.hasNext()) {
                        val (key, job) = iterator.next()
                        job.cancel()
                        jobsToJoin += job
                        iterator.remove()
                        key.substringBefore(':').toLongOrNull()?.let { affectedChapterIds += it }
                    }
                }
                affectedChapterIds.forEach { markChapterCancelledSync(it) }
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
