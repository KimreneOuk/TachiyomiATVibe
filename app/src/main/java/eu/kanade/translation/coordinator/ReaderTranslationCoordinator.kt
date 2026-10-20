package eu.kanade.translation.coordinator

import com.hippo.unifile.UniFile
import eu.kanade.translation.engines.translator.TextTranslator
import eu.kanade.translation.engines.vision.ocr.PageRecognitionEngine
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.prepareForcedRetry
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.processing.TranslationProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

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
    private val recognitionEngineProvider: () -> PageRecognitionEngine,
    private val translatorProvider: () -> TextTranslator,
    private val isBatchActiveForChapter: (String) -> Boolean = { false },
    private val prepareEngines: suspend () -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    autoStart: Boolean = true,
) : Closeable {

    data class PageRequest(
        val chapterKey: String,
        val pageKey: String,
        val isManual: Boolean,
        val streamFn: () -> InputStream,
        val companionDir: UniFile? = null,
        val inpaintingEnabled: Boolean = true,
        val force: Boolean = false,
        val store: ChapterTranslationStore,
    )

    private val mutex = Mutex()
    private val manualQueue = LinkedHashMap<String, PageRequest>()
    private val autoQueue = LinkedHashMap<String, PageRequest>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val queuedKeys = ConcurrentHashMap.newKeySet<String>()
    private val requestJobs = ConcurrentHashMap<String, Job>()
    private val requestChapterKeys = ConcurrentHashMap<String, String>()
    private val automaticRequestKeys = ConcurrentHashMap.newKeySet<String>()
    private val automaticQueuedKeys = ConcurrentHashMap.newKeySet<String>()
    private val autoEnabledChapters = ConcurrentHashMap.newKeySet<String>()

    // Event channel to wake up worker on new work instead of polling delay
    private val triggerChannel = Channel<Unit>(capacity = Channel.CONFLATED)

    private val _activePageKey = MutableStateFlow<String?>(null)
    val activePageKey: StateFlow<String?> = _activePageKey.asStateFlow()

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private var workerJob: Job? = null

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
                    val currentState = nextRequest.store.state.value[nextRequest.pageKey]
                    if (!nextRequest.force && currentState?.translationStatus == StageStatus.READY &&
                        (currentState.hasRenderedResult || currentState.isTextlessTerminal)
                    ) {
                        logcat(LogPriority.DEBUG) {
                            "ReaderTranslationCoordinator: page $fullKey already complete, skipping"
                        }
                        continue
                    }

                    _activePageKey.value = nextRequest.pageKey
                    inFlight.add(fullKey)
                    requestChapterKeys[fullKey] = nextRequest.chapterKey
                    if (!nextRequest.isManual) automaticRequestKeys.add(fullKey)
                    val requestJob = scope.launch(start = CoroutineStart.LAZY) {
                        try {
                            prepareEngines()
                            processRequest(nextRequest)
                        } catch (c: CancellationException) {
                            throw c
                        } catch (t: Throwable) {
                            logcat(LogPriority.ERROR, t) {
                                "ReaderTranslationCoordinator: unexpected failure processing $fullKey"
                            }
                        }
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

    private suspend fun processRequest(request: PageRequest) {
        val recognitionEngine = recognitionEngineProvider()
        val translator = translatorProvider()

        val result = processor.processSinglePage(
            pageKey = request.pageKey,
            streamFn = request.streamFn,
            recognitionEngine = recognitionEngine,
            translator = translator,
            companionDir = request.companionDir,
            inpaintingEnabled = request.inpaintingEnabled,
        )

        when (result) {
            is TranslationProcessor.ProcessResult.Success -> {
                request.store.updatePageFromCurrentSnapshot(
                    pageKey = request.pageKey,
                    description = "reader:commit",
                ) {
                    result.page
                }
                logcat(LogPriority.INFO) {
                    "ReaderTranslationCoordinator: committed completed page ${request.pageKey}"
                }
            }
            is TranslationProcessor.ProcessResult.Failure -> {
                request.store.updatePageFromCurrentSnapshot(
                    pageKey = request.pageKey,
                    description = "reader:fail",
                ) { current ->
                    (current ?: PageTranslation(sourceFileName = request.pageKey)).apply {
                        this.ocrStatus = StageStatus.FAILED
                        this.translationStatus = StageStatus.FAILED
                        this.inpaintStatus = StageStatus.FAILED
                        this.renderStatus = StageStatus.FAILED
                        this.errorMessage = result.errorMessage
                    }
                }
                logcat(LogPriority.WARN) {
                    "ReaderTranslationCoordinator: committed failed page ${request.pageKey}: ${result.errorMessage}"
                }
            }
        }
    }

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
            // Remove from auto if present and add to manual
            if (autoQueue.remove(fullKey) != null) {
                queuedKeys.remove(fullKey)
                automaticQueuedKeys.remove(fullKey)
            }
            manualQueue[fullKey] = request
            queuedKeys.add(fullKey)
        } else {
            // Auto requests are ignored if already queued as manual
            if (!manualQueue.containsKey(fullKey)) {
                autoQueue[fullKey] = request
                queuedKeys.add(fullKey)
                automaticQueuedKeys.add(fullKey)
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
    }

    private suspend fun cancelRequestsAndJoin(keys: Collection<String>) {
        for (key in keys) {
            while (requestChapterKeys.containsKey(key) && requestJobs[key] == null) yield()
            requestJobs[key]?.cancelAndJoin()
        }
    }

    /** Drains every reader request before a global reader teardown. */
    suspend fun cancelAllAndJoin() {
        val chapters = mutex.withLock {
            (manualQueue.values + autoQueue.values).mapTo(linkedSetOf()) { it.chapterKey }
        }
        chapters += requestChapterKeys.values
        chapters += autoEnabledChapters
        chapters.forEach { cancelChapter(it) }
    }

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
        updatePendingCountLocked()
    }
}
