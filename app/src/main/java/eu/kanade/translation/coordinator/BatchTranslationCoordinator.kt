package eu.kanade.translation.coordinator

import com.hippo.unifile.UniFile
import eu.kanade.translation.engines.translator.TextTranslator
import eu.kanade.translation.engines.vision.ocr.PageRecognitionEngine
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.toDraft
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.processing.TranslationProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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
 * Ordered batch translation coordinator for full chapter execution.
 *
 * Architecture:
 * 1. Ordered source inventory -> pre-register totals in [ChapterTranslationStore].
 * 2. Durable progress rebuilding: scans existing valid committed page bundles and skips them.
 * 3. Full-chapter OCR barrier: detects and recognizes text across all remaining pages first.
 * 4. Bounded 2-task concurrency: translation chunk loop and sequential inpainting loop overlap.
 * 5. Fail-closed resume safety: errors or cancellation never wipe committed bundles on disk.
 */
internal class BatchTranslationCoordinator(
    private val processor: TranslationProcessor,
    private val recognitionEngineProvider: () -> PageRecognitionEngine,
    private val translatorProvider: () -> TextTranslator,
    private val prepareEngines: suspend () -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : Closeable {

    data class BatchProgress(
        val chapterKey: String,
        val completedPages: Int,
        val totalPages: Int,
        val currentStage: String,
        val isFinished: Boolean = false,
        val error: String? = null,
    ) {
        val fraction: Float get() = if (totalPages > 0) completedPages.toFloat() / totalPages else 0f
    }

    private val activeChapters = ConcurrentHashMap.newKeySet<String>()
    private val chapterJobs = ConcurrentHashMap<String, Job>()
    private val _progressFlows = ConcurrentHashMap<String, MutableStateFlow<BatchProgress>>()

    fun isChapterActive(chapterKey: String): Boolean = activeChapters.contains(chapterKey)

    fun getProgressFlow(chapterKey: String): StateFlow<BatchProgress>? =
        _progressFlows[chapterKey]?.asStateFlow()

    /**
     * Translates an entire chapter in batch mode.
     * Rebuilds progress from durable state, skips completed pages, runs OCR barrier,
     * then executes bounded 2-task translation/inpainting overlap.
     */
    suspend fun translateChapter(
        chapterKey: String,
        orderedPageKeys: List<String>,
        streamProvider: (String) -> InputStream,
        store: ChapterTranslationStore,
        companionDir: UniFile? = null,
        inpaintingEnabled: Boolean = true,
        chunkSize: Int = 5,
        onProgress: ((BatchProgress) -> Unit)? = null,
    ): BatchProgress {
        if (!activeChapters.add(chapterKey)) {
            logcat(LogPriority.WARN) { "BatchTranslationCoordinator: chapter $chapterKey already active" }
            return _progressFlows[chapterKey]?.value ?: BatchProgress(chapterKey, 0, orderedPageKeys.size, "ALREADY_ACTIVE")
        }

        val progress = MutableStateFlow(
            BatchProgress(
                chapterKey = chapterKey,
                completedPages = 0,
                totalPages = orderedPageKeys.size,
                currentStage = "INITIALIZING",
            )
        )
        _progressFlows[chapterKey] = progress
        onProgress?.invoke(progress.value)

        try {
            return executeBatch(
                chapterKey = chapterKey,
                orderedPageKeys = orderedPageKeys,
                streamProvider = streamProvider,
                store = store,
                companionDir = companionDir,
                inpaintingEnabled = inpaintingEnabled,
                chunkSize = chunkSize,
                progress = progress,
                onProgress = onProgress,
            )
        } finally {
            activeChapters.remove(chapterKey)
        }
    }

    private suspend fun executeBatch(
        chapterKey: String,
        orderedPageKeys: List<String>,
        streamProvider: (String) -> InputStream,
        store: ChapterTranslationStore,
        companionDir: UniFile?,
        inpaintingEnabled: Boolean,
        chunkSize: Int,
        progress: MutableStateFlow<BatchProgress>,
        onProgress: ((BatchProgress) -> Unit)?,
    ): BatchProgress {
        val totalCount = orderedPageKeys.size
        val currentState = store.state.value

        // 1. Resume scan: identify already-completed pages from durable store
        val completedKeys = orderedPageKeys.filter { key ->
            val page = currentState[key]
            page?.translationStatus == StageStatus.READY || page?.isTextlessTerminal == true
        }.toSet()

        var completedCount = completedKeys.size
        publish(progress, BatchProgress(chapterKey, completedCount, totalCount, "RESUME_CHECK"), onProgress)
        logcat(LogPriority.INFO) {
            "BatchTranslationCoordinator: chapter $chapterKey has $completedCount/$totalCount pages already completed"
        }

        val remainingKeys = orderedPageKeys.filter { it !in completedKeys }
        if (remainingKeys.isEmpty()) {
            val finalProgress = BatchProgress(chapterKey, totalCount, totalCount, "COMPLETED", isFinished = true)
            publish(progress, finalProgress, onProgress)
            return finalProgress
        }

        prepareEngines()
        val recognitionEngine = recognitionEngineProvider()
        val translator = translatorProvider()

        // 2. Full-Chapter OCR Barrier
        publish(progress, BatchProgress(chapterKey, completedCount, totalCount, "OCR_BARRIER"), onProgress)
        val analyzedPages = LinkedHashMap<String, PageTranslation>()

        for (pageKey in remainingKeys) {
            val existing = store.state.value[pageKey]
            if (existing != null && existing.ocrStatus == StageStatus.READY && existing.blocks.isNotEmpty()) {
                // Reuse existing OCR blocks from store
                analyzedPages[pageKey] = existing.toDraft()
            } else {
                val decoded = processor.decodeImage(pageKey, { streamProvider(pageKey) }) { recognitionEngine }
                if (decoded == null) {
                    val err = "Failed to decode page image for $pageKey during OCR pass"
                    logcat(LogPriority.ERROR) { err }
                    store.updatePageFromCurrentSnapshot(pageKey, "batch:decode-failed") { current ->
                        (current ?: PageTranslation(sourceFileName = pageKey)).apply {
                            ocrStatus = StageStatus.FAILED
                            translationStatus = StageStatus.FAILED
                            inpaintStatus = StageStatus.FAILED
                            errorMessage = err
                        }
                    }
                    val failProgress = BatchProgress(chapterKey, completedCount, totalCount, "FAILED", isFinished = true, error = err)
                    publish(progress, failProgress, onProgress)
                    return failProgress
                }

                val page = try {
                    processor.analyze(pageKey, decoded.bitmap, recognitionEngine)
                } finally {
                    processor.releaseDecoded(decoded)
                }
                analyzedPages[pageKey] = page

                // Commit OCR state
                store.updatePageFromCurrentSnapshot(pageKey, "batch:ocr") { page }

                // Textless pages are complete immediately!
                if (page.blocks.isEmpty() || page.isTextlessTerminal) {
                    completedCount++
                    publish(progress, BatchProgress(chapterKey, completedCount, totalCount, "OCR_BARRIER"), onProgress)
                }
            }
        }

        // Filter pages needing translation and inpaint
        val pagesNeedingWork = analyzedPages.filterValues { it.blocks.isNotEmpty() && !it.isTextlessTerminal }
        if (pagesNeedingWork.isEmpty()) {
            val finalProgress = BatchProgress(chapterKey, totalCount, totalCount, "COMPLETED", isFinished = true)
            publish(progress, finalProgress, onProgress)
            return finalProgress
        }

        // 3. Bounded 2-Task Concurrency: Translation loop + Sequential Inpainting loop
        publish(progress, BatchProgress(chapterKey, completedCount, totalCount, "TRANSLATING_AND_INPAINTING"), onProgress)
        val inpaintChannel = Channel<PageTranslation>(capacity = 2)

        coroutineScope {
            // Task 1: Translation loop (chunks of up to chunkSize)
            val translationTask = launch(Dispatchers.Default) {
                val entries = pagesNeedingWork.entries.toList()
                for (chunk in entries.chunked(chunkSize)) {
                    val chunkMap = chunk.associate { it.key to it.value.detachedCopy() }.toMutableMap()
                    processor.translateBatch(chunkMap, translator)

                    for ((pageKey, translatedPage) in chunkMap) {
                        store.updatePageFromCurrentSnapshot(pageKey, "batch:translate") { translatedPage }
                        inpaintChannel.send(translatedPage)
                    }
                }
                inpaintChannel.close()
            }

            // Task 2: Inpainting loop (bounded sequential processing)
            val inpaintingTask = launch(Dispatchers.Default) {
                for (translatedPage in inpaintChannel) {
                    val pageKey = checkNotNull(translatedPage.sourceFileName)

                    if (inpaintingEnabled) {
                        val decoded = processor.decodeImage(pageKey, { streamProvider(pageKey) }) { recognitionEngine }
                        if (decoded != null) {
                            try {
                                processor.inpaint(pageKey, decoded.bitmap, translatedPage, recognitionEngine, companionDir)
                            } finally {
                                processor.releaseDecoded(decoded)
                            }
                        } else {
                            translatedPage.inpaintStatus = StageStatus.READY
                            translatedPage.originalImageFallback = true
                        }
                    } else {
                        translatedPage.inpaintStatus = StageStatus.READY
                        translatedPage.originalImageFallback = true
                    }

                    // Commit final bundle
                    try {
                        store.updatePageFromCurrentSnapshot(pageKey, "batch:commit") { translatedPage }
                    } finally {
                        processor.releasePageBitmaps(translatedPage)
                    }

                    completedCount++
                    publish(
                        progress,
                        BatchProgress(
                            chapterKey = chapterKey,
                            completedPages = completedCount,
                            totalPages = totalCount,
                            currentStage = "INPAINTING",
                        ),
                        onProgress,
                    )
                }
            }

            translationTask.join()
            inpaintingTask.join()
        }

        val finalProgress = BatchProgress(chapterKey, totalCount, totalCount, "COMPLETED", isFinished = true)
        publish(progress, finalProgress, onProgress)
        return finalProgress
    }

    private fun publish(
        progress: MutableStateFlow<BatchProgress>,
        value: BatchProgress,
        onProgress: ((BatchProgress) -> Unit)?,
    ) {
        progress.value = value
        onProgress?.invoke(value)
    }

    override fun close() {
        chapterJobs.values.forEach { it.cancel() }
        chapterJobs.clear()
        activeChapters.clear()
    }
}
