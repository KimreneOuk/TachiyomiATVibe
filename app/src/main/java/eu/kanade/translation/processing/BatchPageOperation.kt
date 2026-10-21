package eu.kanade.translation.processing

import com.hippo.unifile.UniFile
import eu.kanade.translation.coordinator.BatchTranslationCoordinator
import eu.kanade.translation.engines.translator.TextTranslator
import eu.kanade.translation.engines.vision.ocr.PageRecognitionEngine
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.toDraft
import eu.kanade.translation.persistence.ChapterTranslationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.InputStream

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
internal class BatchPageOperation(
    private val processor: TranslationProcessor,
    private val recognitionEngineProvider: () -> PageRecognitionEngine,
    private val translatorProvider: () -> TextTranslator,
    private val prepareEngines: suspend () -> Unit = {},
) {

    data class Progress(
        val chapterKey: String,
        val completedPages: Int,
        val totalPages: Int,
        val currentStage: String,
        val isFinished: Boolean = false,
        val error: String? = null,
    ) {
        val fraction: Float get() = if (totalPages > 0) completedPages.toFloat() / totalPages else 0f
    }

    /**
     * Runs the page stages for one admitted chapter. Queue admission, active-run state, and the
     * progress flow remain owned by [BatchTranslationCoordinator].
     */
    suspend fun execute(
        chapterKey: String,
        orderedPageKeys: List<String>,
        streamProvider: (String) -> InputStream,
        store: ChapterTranslationStore,
        progress: MutableStateFlow<Progress>,
        companionDir: UniFile? = null,
        inpaintingEnabled: Boolean = true,
        chunkSize: Int = 5,
        onProgress: ((Progress) -> Unit)? = null,
    ): Progress = executeBatch(
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

    private suspend fun executeBatch(
        chapterKey: String,
        orderedPageKeys: List<String>,
        streamProvider: (String) -> InputStream,
        store: ChapterTranslationStore,
        companionDir: UniFile?,
        inpaintingEnabled: Boolean,
        chunkSize: Int,
        progress: MutableStateFlow<Progress>,
        onProgress: ((Progress) -> Unit)?,
    ): Progress {
        val totalCount = orderedPageKeys.size
        val currentState = store.state.value

        // 1. Resume scan: identify already-completed pages from durable store
        val completedKeys = orderedPageKeys.filter { key ->
            val page = currentState[key]
            page?.translationStatus == StageStatus.READY || page?.isTextlessTerminal == true
        }.toSet()

        var completedCount = completedKeys.size
        publish(progress, Progress(chapterKey, completedCount, totalCount, "RESUME_CHECK"), onProgress)
        logcat(LogPriority.INFO) {
            "BatchTranslationCoordinator: chapter $chapterKey has $completedCount/$totalCount pages already completed"
        }

        val remainingKeys = orderedPageKeys.filter { it !in completedKeys }
        if (remainingKeys.isEmpty()) {
            val finalProgress = Progress(chapterKey, totalCount, totalCount, "COMPLETED", isFinished = true)
            publish(progress, finalProgress, onProgress)
            return finalProgress
        }

        prepareEngines()
        val recognitionEngine = recognitionEngineProvider()
        val translator = translatorProvider()

        // 2. Full-Chapter OCR Barrier
        publish(progress, Progress(chapterKey, completedCount, totalCount, "OCR_BARRIER"), onProgress)
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
                    val failProgress = Progress(chapterKey, completedCount, totalCount, "FAILED", isFinished = true, error = err)
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
                    publish(progress, Progress(chapterKey, completedCount, totalCount, "OCR_BARRIER"), onProgress)
                }
            }
        }

        // Filter pages needing translation and inpaint
        val pagesNeedingWork = analyzedPages.filterValues { it.blocks.isNotEmpty() && !it.isTextlessTerminal }
        if (pagesNeedingWork.isEmpty()) {
            val finalProgress = Progress(chapterKey, totalCount, totalCount, "COMPLETED", isFinished = true)
            publish(progress, finalProgress, onProgress)
            return finalProgress
        }

        // 3. Bounded 2-Task Concurrency: Translation loop + Sequential Inpainting loop
        publish(progress, Progress(chapterKey, completedCount, totalCount, "TRANSLATING_AND_INPAINTING"), onProgress)
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
                        Progress(
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

        val finalProgress = Progress(chapterKey, totalCount, totalCount, "COMPLETED", isFinished = true)
        publish(progress, finalProgress, onProgress)
        return finalProgress
    }

    private fun publish(
        progress: MutableStateFlow<Progress>,
        value: Progress,
        onProgress: ((Progress) -> Unit)?,
    ) {
        progress.value = value
        onProgress?.invoke(value)
    }
}
