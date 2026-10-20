package eu.kanade.translation.coordinator

import com.hippo.unifile.UniFile
import eu.kanade.translation.engines.translator.TextTranslator
import eu.kanade.translation.engines.vision.ocr.PageRecognitionEngine
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.processing.TranslationProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        val store: ChapterTranslationStore,
    )

    private val mutex = Mutex()
    private val manualQueue = LinkedHashMap<String, PageRequest>()
    private val autoQueue = LinkedHashMap<String, PageRequest>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

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
                    if (currentState?.translationStatus == StageStatus.READY &&
                        (currentState.hasRenderedResult || currentState.isTextlessTerminal)
                    ) {
                        logcat(LogPriority.DEBUG) {
                            "ReaderTranslationCoordinator: page $fullKey already complete, skipping"
                        }
                        continue
                    }

                    _activePageKey.value = nextRequest.pageKey
                    inFlight.add(fullKey)

                    try {
                        processRequest(nextRequest)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        logcat(LogPriority.ERROR, t) {
                            "ReaderTranslationCoordinator: unexpected failure processing $fullKey"
                        }
                    } finally {
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

        // Deduplication against in-flight
        if (inFlight.contains(fullKey)) {
            return false
        }

        if (request.isManual) {
            // Remove from auto if present and add to manual
            autoQueue.remove(fullKey)
            manualQueue[fullKey] = request
        } else {
            // Auto requests are ignored if already queued as manual
            if (!manualQueue.containsKey(fullKey)) {
                autoQueue[fullKey] = request
            }
        }

        updatePendingCountLocked()
        triggerChannel.trySend(Unit)
        true
    }

    /**
     * Updates viewport window: retains auto prefetch requests only for pages in [visibleOrNearPageKeys].
     */
    suspend fun updateViewportWindow(chapterKey: String, visibleOrNearPageKeys: Set<String>) = mutex.withLock {
        autoQueue.entries.removeAll { (key, _) ->
            key.startsWith("$chapterKey:") && key.substringAfter("$chapterKey:") !in visibleOrNearPageKeys
        }
        updatePendingCountLocked()
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
        updatePendingCountLocked()
    }
}
