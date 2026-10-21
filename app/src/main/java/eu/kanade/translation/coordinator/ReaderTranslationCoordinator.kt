package eu.kanade.translation.coordinator

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.engines.translator.TranslatorComputeClass
import eu.kanade.translation.model.PageRejectionKind
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.SinglePageOutcome
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.persistence.ActiveChapterStoreRegistry
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.LeaseAcquisition
import eu.kanade.translation.persistence.PageWriteOrigin
import eu.kanade.translation.processing.MemoryPressurePolicy
import eu.kanade.translation.processing.TranslationProcessor
import eu.kanade.translation.processing.TranslationStageSignal
import eu.kanade.translation.processing.adaptive.DevicePermitPriority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Single-page translation coordinator for reader viewport demands.
 *
 * Enforces:
 * 1. Manual translation requests take absolute priority over auto prefetch requests.
 * 2. Strictly single-page in-flight concurrency (memory bound).
 * 3. Deduplication: skips pages already translated or in flight.
 * 4. Yields when batch translation owns the chapter lock.
 * 5. Direct commit of completed pages to [ChapterTranslationStore].
 */
internal class ReaderTranslationCoordinator(
    private val processor: TranslationProcessor,
    private val isBatchActiveForChapter: (String) -> Boolean = { false },
    private val resolveSessionStore: suspend (Manga, Chapter, HttpSource) -> ChapterTranslationStore? = { _, _, _ -> null },
    private val admitReaderSession: (Long) -> Boolean = { true },
    private val beforeOpenSession: suspend (Long) -> Unit = {},
    private val manualStreamProvider: (Manga, Chapter, HttpSource, String) -> (() -> java.io.InputStream)? = { _, _, _, _ -> null },
    private val isAutoTranslateEnabled: () -> Boolean = { true },
    private val prepareEngines: suspend () -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val activeStoresProvider: () -> ActiveChapterStoreRegistry? = { null },
    private val batchCoordinator: BatchTranslationCoordinator? = null,
    private val sessionCoordinator: TranslationSessionCoordinator? = null,
    private val clearPendingRequests: () -> Unit = {},
    private val disposeBatchTracker: (Long) -> Unit = {},
    private val unregisterActiveStore: suspend (Long) -> Unit = {},
    private val retryPendingRekeysAfterReaderLeave: suspend () -> Unit = {},
    autoStart: Boolean = true,
) : Closeable {

    data class PageRequest(
        val session: TranslationSession,
        val chapterKey: String,
        val pageKey: String,
        val isManual: Boolean,
        val streamFn: (() -> java.io.InputStream)?,
        val force: Boolean = false,
    )

    private val mutex = Mutex()
    private val readerTeardownMutex = Mutex()
    private val activeStores get() = activeStoresProvider()
    private val manualQueue = LinkedHashMap<String, PageRequest>()
    private val autoQueue = LinkedHashMap<String, PageRequest>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val queuedKeys = ConcurrentHashMap.newKeySet<String>()
    private val requestJobs = ConcurrentHashMap<String, Job>()
    private val requestChapterKeys = ConcurrentHashMap<String, String>()
    private val automaticRequestKeys = ConcurrentHashMap.newKeySet<String>()
    private val automaticQueuedKeys = ConcurrentHashMap.newKeySet<String>()
    private val autoEnabledChapters = ConcurrentHashMap.newKeySet<String>()
    private val sessionBindings = ConcurrentHashMap<String, SessionBinding>()
    private val sessionBindingMutex = Mutex()
    private val readerGeneration = AtomicLong()
    private val chapterCancellations = ConcurrentHashMap.newKeySet<String>()
    private val viewportLocks = ConcurrentHashMap<String, Mutex>()
    private val viewportRevisions = ConcurrentHashMap<String, AtomicLong>()
    private val viewportStates = ConcurrentHashMap<String, ViewportState>()
    private val chapterCancellationJobs = ConcurrentHashMap<String, Job>()
    private val chapterCancellationLock = Any()
    private val outcomeStates = ConcurrentHashMap<String, MutableStateFlow<Map<String, SinglePageOutcome>>>()
    private val outcomeLock = Any()
    private val staleOutcomes = MutableStateFlow<Map<String, SinglePageOutcome>>(emptyMap())

    @Volatile
    private var activeSessionChapterKey: String? = null

    @Volatile
    private var readerStopInFlight = false

    // Event channel to wake up worker on new work instead of polling delay
    private val triggerChannel = Channel<Unit>(capacity = Channel.CONFLATED)

    private val _activePageKey = MutableStateFlow<String?>(null)
    val activePageKey: StateFlow<String?> = _activePageKey.asStateFlow()

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    /**
     * TachiyomiAT: in-memory, per-attempt pipeline stage signals for live
     * reader feedback (status pill). Pure state machine extracted so the stale
     * token fence is unit-testable without a coordinator. Keyed by FULL page
     * key ("chapterKey:pageKey") so concurrent chapters can never
     * cross-contaminate each other's indicators.
     */
    private val stageBoard = TranslationStageSignalBoard()
    val pageStageSignalFlow = stageBoard.flow

    private var workerJob: Job? = null

    private data class SessionBinding(
        val token: ReaderSessionToken,
        val session: TranslationSession,
        val computeClass: TranslatorComputeClass,
    )

    private data class ViewportState(
        val token: ReaderSessionToken,
        val pageIndex: Int,
        val pageCount: Int,
        val resolve: (Int) -> ReaderPageWork?,
    )

    init {
        if (autoStart) {
            startWorker()
        }
    }

    fun startWorker() {
        if (workerJob != null) return
        workerJob = scope.launch {
            for (trigger in triggerChannel) {
                while (isActive) {
                    val nextRequest = mutex.withLock {
                        val manualKey = manualQueue.keys.firstOrNull()
                        val req = if (manualKey != null) {
                            manualQueue.remove(manualKey)
                        } else {
                            val autoKey = autoQueue.keys.firstOrNull()
                            if (autoKey != null) autoQueue.remove(autoKey) else null
                        }
                        req?.let { request ->
                            val fullKey = "${request.chapterKey}:${request.pageKey}"
                            queuedKeys.remove(fullKey)
                            if (!request.isManual) automaticQueuedKeys.remove(fullKey)
                        }
                        updatePendingCountLocked()
                        req
                    } ?: break

                    val fullKey = "${nextRequest.chapterKey}:${nextRequest.pageKey}"

                    // Check mutually exclusive batch gate
                    if (isBatchActiveForChapter(nextRequest.chapterKey)) {
                        logcat(LogPriority.INFO) {
                            "ReaderTranslationCoordinator: yielding $fullKey because batch is active on chapter"
                        }
                        continue
                    }

                    // Check if already completed in store
                    val currentState = nextRequest.session.store.state.value[nextRequest.pageKey]
                    if (!nextRequest.force &&
                        (currentState?.translationStatus == StageStatus.READY || currentState?.isTextlessTerminal == true)
                    ) {
                        logcat(LogPriority.DEBUG) {
                            "ReaderTranslationCoordinator: page $fullKey already complete, skipping"
                        }
                        publishOutcome(nextRequest.chapterKey, nextRequest.pageKey, SinglePageOutcome.Completed)
                        continue
                    }

                    _activePageKey.value = nextRequest.pageKey
                    inFlight.add(fullKey)
                    requestChapterKeys[fullKey] = nextRequest.chapterKey
                    if (!nextRequest.isManual) automaticRequestKeys.add(fullKey)
                    val requestJob = scope.launch(start = CoroutineStart.LAZY) {
                        val outcome = try {
                            prepareEngines()
                            processRequest(nextRequest)
                        } catch (c: CancellationException) {
                            throw c
                        } catch (t: Throwable) {
                            logcat(LogPriority.ERROR, t) {
                                "ReaderTranslationCoordinator: unexpected failure processing $fullKey"
                            }
                            SinglePageOutcome.Failed(nextRequest.pageKey, t.message ?: "Translation failed")
                        }
                        if (isActive) publishOutcome(nextRequest.chapterKey, nextRequest.pageKey, outcome)
                    }
                    requestJobs[fullKey] = requestJob
                    requestJob.start()
                    try {
                        requestJob.join()
                    } finally {
                        requestJobs.remove(fullKey, requestJob)
                        requestChapterKeys.remove(fullKey)
                        automaticRequestKeys.remove(fullKey)
                        inFlight.remove(fullKey)
                        _activePageKey.value = null
                    }
                }
            }
        }
    }

    suspend fun switchReaderSession(chapterId: Long?): SessionAdmission {
        val sessions = checkNotNull(sessionCoordinator) { "Reader session admission is not wired" }
        val batches = checkNotNull(batchCoordinator) { "Batch coordinator is not wired" }
        return withContext(Dispatchers.IO) {
            sessions.switchBatchToReader(
                intent = ReaderSessionIntent(chapterId = chapterId, confirmBatchSwitch = true),
                pauseBatch = batches::pauseForSessionSwitch,
                joinBatch = batches::cancelTranslatorJobAndJoinForSession,
                onJoinTimeout = { timeoutMs ->
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT reader admission waiting for batch unwind after $timeoutMs ms"
                    }
                },
            )
        }
    }

    fun onMemoryPressure(level: Int) {
        val batches = batchCoordinator
        batches?.onMemoryPressure(level, MemoryPressurePolicy.classify(level))
        reconcileAll(admissionGuard = { chapterId ->
            batches?.isChapterBatchActive(chapterId, includePaused = true) != true
        })
    }

    suspend fun openSession(manga: Manga, chapter: Chapter, source: HttpSource): TranslationSession? {
        val chapterId = chapter.id ?: return null
        beforeOpenSession(chapterId)
        val store = resolveSessionStore(manga, chapter, source) ?: return null
        return TranslationSession("${source.id}:${manga.id}:$chapterId", manga, chapter, source, store)
    }

    /** Binds a reader token to its live store and invalidates an older chapter binding. */
    suspend fun bindSession(
        session: TranslationSession,
        computeClass: TranslatorComputeClass,
    ): ReaderSessionToken? {
        val chapterId = session.chapter.id ?: return null
        val chapterKey = chapterId.toString()
        if (chapterKey in chapterCancellations || !admitReaderSession(chapterId) || isBatchActiveForChapter(chapterKey)) return null
        chapterCancellationJobs[chapterKey]?.join()
        return sessionBindingMutex.withLock {
            if (readerStopInFlight ||
                chapterKey in chapterCancellations ||
                !admitReaderSession(chapterId) ||
                isBatchActiveForChapter(chapterKey)
            ) {
                return@withLock null
            }
            val previousChapterKey = activeSessionChapterKey
            if (previousChapterKey != null && previousChapterKey != chapterKey) {
                chapterCancellationJobs[previousChapterKey]?.join()
                cancelChapter(previousChapterKey)
            }
            if (chapterKey in chapterCancellations || readerStopInFlight) return@withLock null
            val existing = sessionBindings[chapterKey]
            if (existing != null &&
                existing.session.key == session.key &&
                existing.session.store === session.store &&
                existing.computeClass == computeClass
            ) {
                activeSessionChapterKey = chapterKey
                return@withLock existing.token
            }
            if (existing != null) cancelChapter(chapterKey)
            val token = ReaderSessionToken(session.key, readerGeneration.incrementAndGet())
            sessionBindings[chapterKey] = SessionBinding(token, session, computeClass)
            activeSessionChapterKey = chapterKey
            token
        }
    }

    fun sessionFor(token: ReaderSessionToken): TranslationSession? =
        sessionBindings.values.firstOrNull { it.token == token }?.session

    fun sessionForChapter(chapterKey: String): TranslationSession? = sessionBindings[chapterKey]?.session

    fun sessionTokenForChapter(chapterKey: String): ReaderSessionToken? = sessionBindings[chapterKey]?.token

    /** Invalidates a session synchronously while the caller holds a chapter admission fence. */
    fun invalidateSession(chapterKey: String) {
        sessionBindings.remove(chapterKey)
        if (activeSessionChapterKey == chapterKey) activeSessionChapterKey = null
    }

    /** Cancels page work without waiting for non-cooperative provider calls to unwind. */
    fun cancelChapterAsync(chapterKey: String) {
        chapterCancellations.add(chapterKey)
        invalidateSession(chapterKey)
        synchronized(chapterCancellationLock) {
            if (chapterCancellationJobs[chapterKey]?.isActive == true) return
            val job = scope.launch(start = CoroutineStart.LAZY) { cancelChapter(chapterKey) }
            chapterCancellationJobs[chapterKey] = job
            job.invokeOnCompletion {
                synchronized(chapterCancellationLock) {
                    if (chapterCancellationJobs[chapterKey] === job) chapterCancellationJobs.remove(chapterKey)
                }
            }
            job.start()
        }
    }

    suspend fun submitPage(
        token: ReaderSessionToken,
        work: ReaderPageWork,
        trigger: ReaderTrigger,
        force: Boolean = false,
    ): ReaderSubmission {
        val session = sessionFor(token) ?: return rejectedSubmission(trigger, PageRejectionKind.SESSION_DENIED, "reader session is no longer active")
        val chapterId = session.chapter.id ?: return rejectedSubmission(trigger, PageRejectionKind.OTHER, "chapter id missing")
        val chapterKey = chapterId.toString()
        if (isBatchActiveForChapter(chapterKey)) return rejectedSubmission(trigger, PageRejectionKind.SESSION_DENIED, "batch owns chapter")
        val isManual = trigger == ReaderTrigger.MANUAL_TAP
        val previousOutcome = outcomeFor(chapterId, work.pageKey)
        if (previousOutcome != null && !force && !isRetryable(previousOutcome, isManual)) {
            return ReaderSubmission.Existing(previousOutcome)
        }
        val accepted = enqueuePage(
            PageRequest(
                session = session,
                chapterKey = chapterKey,
                pageKey = work.pageKey,
                isManual = isManual,
                streamFn = work.streamFn,
                force = force,
            ),
        )
        return if (accepted) ReaderSubmission.Queued(work.pageKey) else ReaderSubmission.Existing(outcomeFor(chapterId, work.pageKey))
    }

    fun updateViewport(
        token: ReaderSessionToken,
        pageIndex: Int,
        pageCount: Int,
        resolve: (Int) -> ReaderPageWork?,
    ) {
        val session = sessionFor(token) ?: return
        val chapterId = session.chapter.id ?: return
        val chapterKey = chapterId.toString()
        if (isBatchActiveForChapter(chapterKey) || !isAutoTranslateEnabled()) return
        viewportStates[chapterKey] = ViewportState(token, pageIndex, pageCount, resolve)
        val revision = viewportRevisions.computeIfAbsent(chapterKey) { AtomicLong() }.incrementAndGet()
        setAutoEnabled(chapterKey, true)
        scope.launch {
            viewportLocks.computeIfAbsent(chapterKey) { Mutex() }.withLock {
                if (viewportRevisions[chapterKey]?.get() != revision || sessionFor(token) == null) return@withLock
                val visibleOrNear = (pageIndex..minOf(pageIndex + 1, pageCount - 1))
                    .mapNotNull(resolve)
                    .distinctBy { it.pageKey }
                if (viewportRevisions[chapterKey]?.get() != revision || sessionFor(token) == null) return@withLock
                updateViewportWindow(chapterKey, visibleOrNear.mapTo(linkedSetOf()) { it.pageKey })
                visibleOrNear.forEach { work ->
                    enqueuePage(
                        PageRequest(
                            session = session,
                            chapterKey = chapterKey,
                            pageKey = work.pageKey,
                            isManual = false,
                            streamFn = work.streamFn,
                        ),
                    )
                }
            }
        }
    }

    fun setAutoEnabled(token: ReaderSessionToken, enabled: Boolean): Boolean {
        val chapterKey = sessionFor(token)?.chapter?.id?.toString() ?: return false
        return setAutoEnabled(chapterKey, enabled)
    }

    fun reconcile(token: ReaderSessionToken, resolve: (Int) -> ReaderPageWork?) {
        val chapterKey = sessionFor(token)?.chapter?.id?.toString() ?: return
        val state = viewportStates[chapterKey]?.takeIf { it.token == token } ?: return
        updateViewport(token, state.pageIndex, state.pageCount, resolve)
    }

    fun reconcileChapter(chapterKey: String) {
        val state = viewportStates[chapterKey] ?: return
        reconcile(state.token, state.resolve)
    }

    fun reconcileAll(admissionGuard: (Long) -> Boolean = { true }) {
        sessionBindings.values.forEach { binding ->
            val chapterId = binding.session.chapter.id ?: return@forEach
            if (!admissionGuard(chapterId)) return@forEach
            val state = viewportStates[chapterId.toString()] ?: return@forEach
            reconcile(binding.token, state.resolve)
        }
    }

    fun translatePage(manga: Manga, chapter: Chapter, source: HttpSource, pageKey: String, force: Boolean = false) {
        scope.launch {
            val session = openSession(manga, chapter, source)
            if (session == null) {
                chapter.id?.let { publishOutcome(it.toString(), pageKey, SinglePageOutcome.Failed(pageKey, "store unavailable")) }
                return@launch
            }
            val token = bindSession(session, TranslatorComputeClass.REMOTE_IO)
            if (token == null) {
                chapter.id?.let {
                    publishOutcome(
                        it.toString(),
                        pageKey,
                        SinglePageOutcome.Rejected(null, PageRejectionKind.SESSION_DENIED, "reader session denied"),
                    )
                }
                return@launch
            }
            val work = ReaderPageWork(pageKey, -1, manualStreamProvider(manga, chapter, source, pageKey))
            submitPage(token, work, ReaderTrigger.MANUAL_TAP, force)
        }
    }

    private suspend fun processRequest(request: PageRequest): SinglePageOutcome {
        val stream = request.streamFn ?: return SinglePageOutcome.Failed(request.pageKey, "Page image stream is unavailable")
        val origin = if (request.isManual) PageWriteOrigin.MANUAL else PageWriteOrigin.AUTO
        val store = request.session.store
        val lease = when (val acquired = store.tryAcquirePageStageLease(request.pageKey, PageStage.Ocr, origin)) {
            is LeaseAcquisition.Granted -> acquired.lease
            is LeaseAcquisition.Denied -> return SinglePageOutcome.Rejected(
                owner = acquired.owner,
                kind = PageRejectionKind.ALREADY_OWNED,
                diagnosticDetail = acquired.reason,
            )
        }
        // TachiyomiAT: transient stage signals for the reader status pill.
        // The full key includes the chapter key, so concurrent chapters can
        // never bleed into each other's indicators; the attempt token fences a
        // superseded attempt's reporter callbacks from overwriting a newer
        // attempt's stage (spec §8 stale-callback rule).
        val fullStageKey = "${request.chapterKey}:${request.pageKey}"
        val stageAttempt = stageBoard.nextAttemptToken()
        try {
            // Fence against writes that happen while decode/native/remote work is running.
            // Refreshing this at commit time would let an older candidate overwrite them.
            val expected = store.snapshot(request.pageKey).toPrecondition().copy(leaseToken = lease.token)
            stageBoard.publish(fullStageKey, TranslationStageSignal.PREPARING, stageAttempt)
            val stageReporter = { stage: TranslationStageSignal ->
                stageBoard.publish(fullStageKey, stage, stageAttempt)
            }
            val result = processor.processSinglePage(
                pageKey = request.pageKey,
                streamFn = stream,
                recognitionEngine = processor.recognitionEngine,
                translator = processor.textTranslator,
                companionDir = processor.companionDirectory(
                    request.session.manga,
                    request.session.chapter,
                    request.session.source,
                ),
                chapterId = request.session.chapter.id,
                chapterName = request.session.chapter.name,
                priority = if (request.isManual) DevicePermitPriority.FOREGROUND else DevicePermitPriority.BACKGROUND,
                stageReporter = stageReporter,
            )
            val candidate = when (result) {
                is TranslationProcessor.ProcessResult.Success -> result.page
                is TranslationProcessor.ProcessResult.Failure -> result.page ?: PageTranslation(
                    sourceFileName = request.pageKey,
                ).apply {
                    ocrStatus = StageStatus.FAILED
                    errorMessage = result.errorMessage
                }
            }
            val write = store.updatePageGuarded(
                pageKey = request.pageKey,
                expected = expected,
                description = "reader:${result::class.simpleName}",
            ) { candidate.detachedCopy() }
            if (write is ChapterTranslationStore.PatchResult.Rejected) {
                return SinglePageOutcome.Rejected(
                    owner = origin,
                    kind = PageRejectionKind.PERSISTENCE_FAILED,
                    diagnosticDetail = write.reason,
                )
            }
            return when (result) {
                is TranslationProcessor.ProcessResult.Success -> SinglePageOutcome.Completed
                is TranslationProcessor.ProcessResult.Failure -> SinglePageOutcome.Failed(request.pageKey, result.errorMessage)
            }
        } finally {
            withContext(NonCancellable) {
                store.releasePageStageLease(request.pageKey, origin)
            }
            // TachiyomiAT: drop this attempt's stage signal so the pill does not
            // outlive the attempt. Removal only happens when the stored token
            // is still this attempt's — a retried attempt owns the entry then.
            stageBoard.clear("${request.chapterKey}:${request.pageKey}", stageAttempt)
        }
    }

    fun outcomeFor(chapterId: Long, pageKey: String): SinglePageOutcome? =
        outcomeStates[chapterId.toString()]?.value?.get(pageKey)

    fun outcomes(token: ReaderSessionToken): StateFlow<Map<String, SinglePageOutcome>> {
        val chapterKey = sessionFor(token)?.chapter?.id?.toString() ?: return staleOutcomes.asStateFlow()
        return outcomesForChapter(chapterKey.toLong())
    }

    fun outcomesForChapter(chapterId: Long): StateFlow<Map<String, SinglePageOutcome>> =
        outcomeStates.computeIfAbsent(chapterId.toString()) { MutableStateFlow(emptyMap()) }.asStateFlow()

    private fun isRetryable(outcome: SinglePageOutcome, manual: Boolean): Boolean = when (outcome) {
        is SinglePageOutcome.Paused -> (outcome.nextEligibleRetryAtEpochMs ?: 0L) <= System.currentTimeMillis()
        is SinglePageOutcome.Stalled, is SinglePageOutcome.Failed -> manual
        else -> false
    }

    private fun clearOutcome(chapterKey: String, pageKey: String) {
        synchronized(outcomeLock) {
            outcomeStates[chapterKey]?.let { state ->
                if (pageKey in state.value) state.value = state.value - pageKey
            }
        }
    }

    private fun publishOutcome(chapterKey: String, pageKey: String, outcome: SinglePageOutcome) {
        synchronized(outcomeLock) {
            val state = outcomeStates.computeIfAbsent(chapterKey) { MutableStateFlow(emptyMap()) }
            val updated = LinkedHashMap(state.value)
            updated[pageKey] = outcome
            while (updated.size > OUTCOME_RETENTION) updated.remove(updated.keys.first())
            state.value = updated
        }
    }

    private fun rejectedSubmission(
        trigger: ReaderTrigger,
        kind: PageRejectionKind,
        detail: String,
    ) = ReaderSubmission.Rejected(
        SinglePageOutcome.Rejected(
            owner = if (trigger == ReaderTrigger.MANUAL_TAP) PageWriteOrigin.MANUAL else PageWriteOrigin.AUTO,
            kind = kind,
            diagnosticDetail = detail,
        ),
    )

    /**
     * Enqueues a page for translation.
     * Manual requests preempt all auto requests.
     */
    suspend fun enqueuePage(request: PageRequest): Boolean = mutex.withLock {
        val fullKey = "${request.chapterKey}:${request.pageKey}"

        if (!request.isManual && request.chapterKey !in autoEnabledChapters) return false

        // Deduplication against in-flight
        if (inFlight.contains(fullKey)) {
            return false
        }

        if (request.isManual) {
            if (fullKey in manualQueue) return false
            // Remove from auto if present and add to manual
            if (autoQueue.remove(fullKey) != null) {
                queuedKeys.remove(fullKey)
                automaticQueuedKeys.remove(fullKey)
            }
            clearOutcome(request.chapterKey, request.pageKey)
            manualQueue[fullKey] = request
            queuedKeys.add(fullKey)
        } else {
            // Auto requests are ignored if already queued as manual
            if (!manualQueue.containsKey(fullKey) && !autoQueue.containsKey(fullKey)) {
                clearOutcome(request.chapterKey, request.pageKey)
                autoQueue[fullKey] = request
                queuedKeys.add(fullKey)
                automaticQueuedKeys.add(fullKey)
            } else {
                return false
            }
        }

        updatePendingCountLocked()
        triggerChannel.trySend(Unit)
        true
    }

    /** Enables automatic demand for a chapter, or removes/cancels its automatic work. */
    fun setAutoEnabled(chapterKey: String, enabled: Boolean): Boolean {
        if (enabled) {
            autoEnabledChapters.add(chapterKey)
            return false
        }
        autoEnabledChapters.remove(chapterKey)
        val hadAutomaticDemand = automaticRequestKeys.any { it.startsWith("$chapterKey:") } ||
            automaticQueuedKeys.any { it.startsWith("$chapterKey:") }
        scope.launch {
            updateViewportWindow(chapterKey, emptySet())
            val keys = automaticRequestKeys.filter { it.startsWith("$chapterKey:") }
            cancelRequestsAndJoin(keys)
        }
        return hadAutomaticDemand
    }

    /**
     * Updates viewport window: retains auto prefetch requests only for pages in [visibleOrNearPageKeys].
     */
    suspend fun updateViewportWindow(chapterKey: String, visibleOrNearPageKeys: Set<String>) = mutex.withLock {
        val removed = autoQueue.keys.filter { key ->
            key.startsWith("$chapterKey:") && key.substringAfter("$chapterKey:") !in visibleOrNearPageKeys
        }
        removed.forEach { key ->
            autoQueue.remove(key)
            queuedKeys.remove(key)
            automaticQueuedKeys.remove(key)
        }
        updatePendingCountLocked()
    }

    /** Fire-and-forget counterpart for the reader's synchronous cancel button. */
    fun cancelPageAsync(chapterKey: String, pageKey: String): Boolean {
        val fullKey = "$chapterKey:$pageKey"
        val wasQueuedOrRunning = fullKey in queuedKeys || fullKey in inFlight
        scope.launch { cancelPage(chapterKey, pageKey) }
        return wasQueuedOrRunning
    }

    suspend fun cancelPage(chapterKey: String, pageKey: String): Boolean {
        val fullKey = "$chapterKey:$pageKey"
        val wasQueued = mutex.withLock {
            val manualRemoved = manualQueue.remove(fullKey) != null
            val autoRemoved = autoQueue.remove(fullKey) != null
            queuedKeys.remove(fullKey)
            automaticQueuedKeys.remove(fullKey)
            updatePendingCountLocked()
            manualRemoved || autoRemoved
        }
        val active = requestChapterKeys.containsKey(fullKey)
        cancelRequestsAndJoin(listOf(fullKey))
        triggerChannel.trySend(Unit)
        return wasQueued || active
    }

    /** Cancels all queued and executing work for a chapter before its store is evicted. */
    suspend fun cancelChapter(chapterKey: String) {
        chapterCancellations.add(chapterKey)
        try {
            invalidateSession(chapterKey)
            viewportStates.remove(chapterKey)
            viewportRevisions.remove(chapterKey)
            mutex.withLock {
                val queued = (manualQueue.keys + autoQueue.keys).filter { key ->
                    key.startsWith("$chapterKey:")
                }
                queued.forEach { key ->
                    manualQueue.remove(key)
                    autoQueue.remove(key)
                    queuedKeys.remove(key)
                    automaticQueuedKeys.remove(key)
                }
                autoEnabledChapters.remove(chapterKey)
                updatePendingCountLocked()
            }
            cancelRequestsAndJoin(requestChapterKeys.filterValues { it == chapterKey }.keys)
            outcomeStates.remove(chapterKey)
        } finally {
            chapterCancellations.remove(chapterKey)
        }
    }

    private suspend fun cancelRequestsAndJoin(keys: Collection<String>) {
        for (key in keys) {
            while (requestChapterKeys.containsKey(key) && requestJobs[key] == null) yield()
            requestJobs[key]?.cancelAndJoin()
        }
    }

    /** Drains every reader request before a global reader teardown. */
    suspend fun cancelAllAndJoin() {
        readerStopInFlight = true
        try {
            sessionBindingMutex.withLock {
                val chapters = mutex.withLock {
                    (manualQueue.values + autoQueue.values).mapTo(linkedSetOf()) { it.chapterKey }
                }
                chapters += requestChapterKeys.values
                chapters += autoEnabledChapters
                chapters += sessionBindings.keys
                chapters.forEach { cancelChapter(it) }
            }
        } finally {
            readerStopInFlight = false
        }
    }

    /** Schedules joined reader teardown on the coordinator lifetime. */
    fun stopReaderTranslations(reason: String) {
        scope.launch(start = CoroutineStart.DEFAULT) {
            readerTeardownMutex.withLock {
                sessionCoordinator?.abortPausingToBatch()
                cancelAllPageTranslations(cancelBatchQueue = false, reason = reason)
                if (!isAnyBatchTranslationActive()) {
                    batchCoordinator?.stop(reason, closeEngines = false)
                }
                sessionCoordinator?.finishSession(TranslationSessionState.READER_SESSION)
            }
        }
    }

    fun requestReaderStop(reason: String): Deferred<Unit> =
        scope.async(start = CoroutineStart.DEFAULT) { awaitReaderStop(reason) }

    suspend fun awaitReaderStop(reason: String) {
        withContext(Dispatchers.IO) {
            readerTeardownMutex.withLock {
                sessionCoordinator?.abortPausingToBatch()
                cancelAllAndJoin()
                val chapterIdsToEvict = activeStores?.chapterIds()
                    ?.filterNot { isChapterBatchActive(it, includePaused = true) }
                    .orEmpty()
                chapterIdsToEvict.forEach { unregisterActiveStore(it) }
                sessionCoordinator?.finishSession(TranslationSessionState.READER_SESSION)
            }
            retryPendingRekeysAfterReaderLeave()
        }
    }

    fun cancelPageTranslation(chapterId: Long, pageKey: String): Boolean =
        cancelPageAsync(chapterId.toString(), pageKey)

    suspend fun cancelRunningChapterForReplace(chapterId: Long) {
        val chapter = batchCoordinator?.queuedTranslationOrNull(chapterId)?.chapter ?: return
        cancelChapter(chapterId.toString())
        activeStores?.get(chapterId)?.clearTransientQueuePages("Replaced by another chapter's batch")
        batchCoordinator.removeFromQueue(chapter)
    }

    suspend fun cancelPageTranslations(chapterId: Long, reason: String = "Translation cancelled") {
        cancelChapter(chapterId.toString())
        if (isChapterBatchActive(chapterId, includePaused = true)) return
        disposeBatchTracker(chapterId)
        activeStores?.get(chapterId)?.clearTransientQueuePages(reason)
        withContext(Dispatchers.IO) { unregisterActiveStore(chapterId) }
    }

    suspend fun cancelAllPageTranslations(
        cancelBatchQueue: Boolean = false,
        reason: String = "All translation cancelled",
    ) {
        cancelAllAndJoin()
        val chapterIdsToEvict = activeStores?.chapterIds()
            ?.filter { cancelBatchQueue || !isChapterBatchActive(it, includePaused = true) }
            .orEmpty()
        chapterIdsToEvict.mapNotNull { activeStores?.get(it) }.forEach { store ->
            store.clearTransientQueuePages(reason)
        }
        chapterIdsToEvict.forEach { unregisterActiveStore(it) }
        if (cancelBatchQueue) {
            batchCoordinator?.clearQueue()
            clearPendingRequests()
            sessionCoordinator?.finishSession()
        } else {
            sessionCoordinator?.finishSession(TranslationSessionState.READER_SESSION)
        }
    }

    suspend fun cancelAllPageTranslationsOffMain(
        cancelBatchQueue: Boolean = false,
        reason: String = "All translation cancelled",
    ) = withContext(Dispatchers.IO) {
        cancelAllPageTranslations(cancelBatchQueue, reason)
    }

    private fun isAnyBatchTranslationActive(): Boolean = batchCoordinator?.queueState?.value?.any {
        it.status == Translation.State.QUEUE || it.status == Translation.State.TRANSLATING
    } == true

    private fun isChapterBatchActive(chapterId: Long, includePaused: Boolean): Boolean =
        batchCoordinator?.queueState?.value?.any { translation ->
            translation.chapter.id == chapterId &&
                (
                    translation.status == Translation.State.QUEUE ||
                        translation.status == Translation.State.TRANSLATING ||
                        (includePaused && translation.status == Translation.State.PAUSED)
                    )
        } == true

    private fun updatePendingCountLocked() {
        _pendingCount.value = manualQueue.size + autoQueue.size
    }

    override fun close() {
        triggerChannel.close()
        workerJob?.cancel()
        workerJob = null
        manualQueue.clear()
        autoQueue.clear()
        queuedKeys.clear()
        requestJobs.values.forEach(Job::cancel)
        requestJobs.clear()
        requestChapterKeys.clear()
        automaticRequestKeys.clear()
        automaticQueuedKeys.clear()
        autoEnabledChapters.clear()
        sessionBindings.clear()
        chapterCancellations.clear()
        outcomeStates.clear()
        viewportStates.clear()
        viewportRevisions.clear()
        viewportLocks.clear()
        activeSessionChapterKey = null
        updatePendingCountLocked()
    }

    private companion object {
        const val OUTCOME_RETENTION = 32
    }
}

/**
 * TachiyomiAT: in-memory, per-attempt pipeline stage signals for live reader
 * feedback (the status pill). Atomic primitives — never persisted, never part
 * of the durable translation store. Carriers carry the attempt token so a
 * stale reporter callback arriving after a retry started can never overwrite
 * the newer attempt's stage (spec §8 stale-callback rule).
 */
internal data class PageStageSignal(
    val stage: TranslationStageSignal,
    val attemptToken: Long,
)

internal class TranslationStageSignalBoard {
    private val signals = MutableStateFlow<Map<String, PageStageSignal>>(emptyMap())
    val flow: StateFlow<Map<String, PageStageSignal>> = signals.asStateFlow()

    private val attemptCounter = AtomicLong()

    fun nextAttemptToken(): Long = attemptCounter.incrementAndGet()

    fun publish(fullKey: String, stage: TranslationStageSignal, attemptToken: Long) {
        val current = signals.value[fullKey]
        // Stale fence: a callback from an old attempt must never overwrite the
        // newer attempt's stage.
        if (current != null && current.attemptToken > attemptToken) return
        signals.value = signals.value + (fullKey to PageStageSignal(stage, attemptToken))
    }

    fun clear(fullKey: String, attemptToken: Long) {
        val current = signals.value[fullKey] ?: return
        if (current.attemptToken == attemptToken) {
            signals.value = signals.value - fullKey
        }
    }
}
