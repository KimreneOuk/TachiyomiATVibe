package eu.kanade.translation.recognition

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import eu.kanade.translation.inpainting.AOTInpainting
import eu.kanade.translation.inpainting.InpaintingMode
import eu.kanade.translation.inpainting.PageInpaintingEngine
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageTranslationHelper
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocr.TextRecognizer
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.rendering.RenderColorEstimator

class MlKitFullPageRecognitionEngine(language: TextRecognizerLanguage) : PageRecognitionEngine {

    private val textRecognizer = TextRecognizer(language)
    private val pageInpainter = PageInpaintingEngine(InpaintingMode.FAST, AOTInpainting())

    override suspend fun analyze(bitmap: Bitmap): PageTranslation {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = textRecognizer.recognize(image)
        val blocks = result.textBlocks.filter { it.boundingBox != null && it.text.length > 1 }
        return convertToPageTranslation(blocks, bitmap, image.width, image.height)
    }

    override suspend fun inpaint(bitmap: Bitmap, pageTranslation: PageTranslation): Bitmap? {
        return pageInpainter.inpaint(bitmap, pageTranslation)

        // TachiyomiAT: a page with ZERO text blocks is a successful recognition
        // of a textless image (splash page, art spread). Mark inpaint READY and
        // bail — this is NOT a failure and must not increment retryCount, or
        // auto-translate's dedup gate will re-enqueue the page on every
        // navigation (the reported "keeps reprocessing the same image" bug).

        // ML Kit mode has no neural inpainter, but there ARE text blocks to
        // clean. Record the skip as a retryable failure (with a clear reason)
        // so auto-translate's bounded retry can re-attempt it once the ONNX
        // engine recovers, instead of silently marking inpaint FAILED forever.

    }

    private fun convertToPageTranslation(blocks: List<Text.TextBlock>, bitmap: Bitmap, width: Int, height: Int): PageTranslation {
        val translation = PageTranslation(
            imgWidth = width.toFloat(),
            imgHeight = height.toFloat(),
            recognitionEngine = "mlkit",
            detectionCount = blocks.size,
            ocrStatus = StageStatus.RUNNING,
            updatedAt = System.currentTimeMillis(),
        )
        for (block in blocks) {
            val bounds = block.boundingBox!!
            val symBounds = block.lines.first().elements.first().symbols.first().boundingBox!!
            val angle = block.lines.first().angle
            val isVertical = angle > 85f
            // TachiyomiAT: route through the shared estimator so ML Kit pages get
            // the SAME fixed inverted/gray-snap logic as the ONNX path. The legacy
            // local computeContrastColors() had correct constants but diverged
            // from the ROI path's (buggy) copy — consolidating removes the
            // divergence and the gray-text bug everywhere.
            val contrastColors = RenderColorEstimator.estimate(
                bitmap,
                bounds.left, bounds.top, bounds.right, bounds.bottom,
            )
                translation.blocks.add(
                    TranslationBlock(
                        text = block.text,
                        width = bounds.width().toFloat(),
                        height = bounds.height().toFloat(),
                        symWidth = symBounds.width().toFloat(),
                        symHeight = symBounds.height().toFloat(),
                        angle = angle,
                        x = bounds.left.toFloat(),
                        y = bounds.top.toFloat(),
                        direction = if (isVertical) "TTB" else "LTR",
                        textColor = contrastColors.first,
                        strokeColor = contrastColors.second,
                        strokeWidth = contrastColors.third,
                    ),
                )
            }
        // TachiyomiAT: ML Kit emits one block per TextBlock with NO dedupe, so
        // overlapping TextBlocks (a common ML Kit artefact on dense pages) both
        // survive and render on top of each other. Apply the same geometric
        // dedupe the ONNX path uses so both engines produce a clean block list.
        // Runs before ocrBlockCount is set so the count reflects post-dedupe.
        if (translation.blocks.size > 1) {
            val deduped = PageTranslationHelper.dedupeGeometricOverlaps(translation.blocks.toList())
            if (deduped.size < translation.blocks.size) {
                translation.blocks.clear()
                translation.blocks.addAll(deduped)
            }
        }
        translation.ocrBlockCount = translation.blocks.size
        translation.ocrStatus = StageStatus.READY
        translation.updatedAt = System.currentTimeMillis()
        return translation
    }

    override fun close() {
        textRecognizer.close()
        pageInpainter.close()
    }
}
