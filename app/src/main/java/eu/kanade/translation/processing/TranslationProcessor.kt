package eu.kanade.translation.processing

import android.content.Context
import android.graphics.Bitmap
import com.hippo.unifile.UniFile
import eu.kanade.translation.engines.translator.TextTranslator
import eu.kanade.translation.engines.vision.ocr.PageRecognitionEngine
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.pipeline.CleanedPublication
import eu.kanade.translation.pipeline.DecodedPage
import eu.kanade.translation.pipeline.PageDecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.InputStream

/**
 * Shared single-page AI processor used by both reader and batch translation coordinators.
 *
 * Consolidates:
 * 1. Image decoding & downscaling for memory bounds.
 * 2. Text block detection and OCR recognition.
 * 3. Text translation via [TextTranslator].
 * 4. Neural / OpenCV background inpainting via [PageRecognitionEngine].
 * 5. Verified cleaned image persistence.
 */
internal class TranslationProcessor(
    private val context: Context,
    private val cleanedPublication: CleanedPublication? = null,
) {
    sealed interface ProcessResult {
        data class Success(
            val page: PageTranslation,
            val cleanedBitmap: Bitmap? = null,
        ) : ProcessResult

        data class Failure(
            val pageKey: String,
            val failedStage: String,
            val errorMessage: String,
            val cause: Throwable? = null,
        ) : ProcessResult
    }

    /**
     * Decodes a bitmap from an input stream with memory governance.
     */
    suspend fun decodeImage(
        fileName: String,
        streamFn: () -> InputStream,
        recognitionEngine: () -> PageRecognitionEngine,
    ): DecodedPage? = PageDecode.decodePageBitmapForTranslation(
        context = context,
        recognitionEngine = recognitionEngine,
        fileName = fileName,
        streamFn = streamFn,
    )

    /**
     * Executes OCR text block detection and recognition on [bitmap].
     * Marks page textless if no blocks are found.
     */
    suspend fun analyze(
        pageKey: String,
        bitmap: Bitmap,
        recognitionEngine: PageRecognitionEngine,
    ): PageTranslation = withContext(Dispatchers.Default) {
        val pageTranslation = recognitionEngine.analyze(bitmap).apply {
            this.sourceFileName = pageKey
            this.imgWidth = bitmap.width.toFloat()
            this.imgHeight = bitmap.height.toFloat()
            this.ocrStatus = StageStatus.READY
            this.updatedAt = System.currentTimeMillis()
        }

        if (pageTranslation.blocks.isEmpty()) {
            logcat(LogPriority.INFO) { "TranslationProcessor: page $pageKey has no text detected (textless)" }
            pageTranslation.ocrStatus = StageStatus.READY
            pageTranslation.translationStatus = StageStatus.READY
            pageTranslation.inpaintStatus = StageStatus.READY
            pageTranslation.renderStatus = StageStatus.READY
        } else {
            pageTranslation.translationStatus = StageStatus.PENDING
            pageTranslation.inpaintStatus = StageStatus.PENDING
        }

        pageTranslation
    }

    /**
     * Translates the text blocks of a single page in place.
     */
    suspend fun translatePage(
        pageKey: String,
        page: PageTranslation,
        translator: TextTranslator,
    ) = withContext(Dispatchers.IO) {
        if (page.blocks.isEmpty() || page.isTextlessTerminal) {
            page.translationStatus = StageStatus.READY
            return@withContext
        }
        page.translationStatus = StageStatus.RUNNING
        try {
            translator.translatePage(pageKey, page)
            page.translationStatus = StageStatus.READY
            page.translationError = null
        } catch (t: Throwable) {
            logcat(LogPriority.ERROR, t) { "TranslationProcessor: translation failed for page $pageKey" }
            page.translationStatus = StageStatus.FAILED
            page.translationError = t.message ?: "Translation failed"
            throw t
        }
    }

    /**
     * Translates a batch of pages together (e.g. for batch translation chunking).
     */
    suspend fun translateBatch(
        pages: MutableMap<String, PageTranslation>,
        translator: TextTranslator,
    ) = withContext(Dispatchers.IO) {
        val pagesToTranslate = pages.filterValues { it.blocks.isNotEmpty() && !it.isTextlessTerminal }.toMutableMap()
        if (pagesToTranslate.isEmpty()) {
            pages.values.forEach { it.translationStatus = StageStatus.READY }
            return@withContext
        }
        pagesToTranslate.values.forEach { it.translationStatus = StageStatus.RUNNING }
        try {
            translator.translate(pagesToTranslate)
            pagesToTranslate.values.forEach {
                it.translationStatus = StageStatus.READY
                it.translationError = null
            }
        } catch (t: Throwable) {
            logcat(LogPriority.ERROR, t) { "TranslationProcessor: batch translation failed for ${pages.size} pages" }
            pagesToTranslate.values.forEach {
                it.translationStatus = StageStatus.FAILED
                it.translationError = t.message ?: "Batch translation failed"
            }
            throw t
        }
    }

    /**
     * Performs inpainting on [bitmap] to remove original text and optionally persists
     * the cleaned image to [companionDir].
     */
    suspend fun inpaint(
        pageKey: String,
        bitmap: Bitmap,
        page: PageTranslation,
        recognitionEngine: PageRecognitionEngine,
        companionDir: UniFile? = null,
    ): Bitmap? = withContext(Dispatchers.Default) {
        if (page.blocks.isEmpty() || page.isTextlessTerminal) {
            page.inpaintStatus = StageStatus.READY
            return@withContext null
        }
        page.inpaintStatus = StageStatus.RUNNING
        val cleaned = try {
            recognitionEngine.inpaint(bitmap, page)
        } catch (oom: OutOfMemoryError) {
            logcat(LogPriority.ERROR, oom) { "TranslationProcessor: OOM during inpaint for page $pageKey" }
            recognitionEngine.reclaimPooledMemory()
            recognitionEngine.forceReleaseNativeBuffers()
            page.inpaintStatus = StageStatus.FAILED
            page.inpaintError = "Out of memory during inpainting"
            page.originalImageFallback = true
            return@withContext null
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "TranslationProcessor: inpaint failed for page $pageKey" }
            page.inpaintStatus = StageStatus.FAILED
            page.inpaintError = t.message ?: "Inpainting failed"
            page.originalImageFallback = true
            return@withContext null
        }

        if (cleaned != null) {
            page.cleanedBitmap = cleaned
            page.inpaintStatus = StageStatus.READY
            page.inpaintError = null

            // Persist cleaned image if companion directory is available
            if (companionDir != null && cleanedPublication != null) {
                try {
                    val result = withContext(Dispatchers.IO) {
                        cleanedPublication.writeVerifiedCleanedImage(companionDir, pageKey, cleaned)
                    }
                    page.cleanedImageName = result.name
                    page.cleanedImageContentHash = result.contentSha256
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "TranslationProcessor: failed to persist cleaned image for $pageKey" }
                    // Keep in-memory cleaned bitmap; image fallback if write fails
                    page.originalImageFallback = true
                }
            }
        } else {
            // Null cleaned bitmap is treated as non-fatal fallback to original image
            page.inpaintStatus = StageStatus.READY
            page.originalImageFallback = true
        }

        cleaned
    }

    /**
     * Executes end-to-end translation processing for a single page:
     * Decode -> Analyze (OCR) -> Translate -> Inpaint.
     */
    suspend fun processSinglePage(
        pageKey: String,
        streamFn: () -> InputStream,
        recognitionEngine: PageRecognitionEngine,
        translator: TextTranslator,
        companionDir: UniFile? = null,
        inpaintingEnabled: Boolean = true,
    ): ProcessResult = withContext(Dispatchers.Default) {
        try {
            // 1. Decode
            val decoded = decodeImage(pageKey, streamFn) { recognitionEngine }
                ?: return@withContext ProcessResult.Failure(
                    pageKey = pageKey,
                    failedStage = "DECODE",
                    errorMessage = "Failed to decode page image for $pageKey",
                )

            val bitmap = decoded.bitmap

            // 2. OCR Analyze
            val page = analyze(pageKey, bitmap, recognitionEngine)

            // Textless shortcut
            if (page.blocks.isEmpty() || page.isTextlessTerminal) {
                return@withContext ProcessResult.Success(page = page, cleanedBitmap = null)
            }

            // 3. Translate
            translatePage(pageKey, page, translator)

            // 4. Inpaint (if enabled)
            val cleaned = if (inpaintingEnabled) {
                inpaint(pageKey, bitmap, page, recognitionEngine, companionDir)
            } else {
                page.inpaintStatus = StageStatus.READY
                page.originalImageFallback = true
                null
            }

            ProcessResult.Success(page = page, cleanedBitmap = cleaned)
        } catch (t: Throwable) {
            logcat(LogPriority.ERROR, t) { "TranslationProcessor: processSinglePage failed for $pageKey" }
            ProcessResult.Failure(
                pageKey = pageKey,
                failedStage = "PIPELINE",
                errorMessage = t.message ?: "Translation processing failed",
                cause = t,
            )
        }
    }
}
