package eu.kanade.translation.recognition

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocr.TextRecognizer
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.rendering.RenderColorEstimator
import kotlin.math.abs

class MlKitFullPageRecognitionEngine(language: TextRecognizerLanguage) : PageRecognitionEngine {

    private val textRecognizer = TextRecognizer(language)

    override suspend fun analyze(bitmap: Bitmap): PageTranslation {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = textRecognizer.recognize(image)
        val blocks = result.textBlocks.filter { it.boundingBox != null && it.text.length > 1 }
        return convertToPageTranslation(blocks, bitmap, image.width, image.height)
    }

    override suspend fun inpaint(bitmap: Bitmap, pageTranslation: PageTranslation): Bitmap? {
        // TachiyomiAT: a page with ZERO text blocks is a successful recognition
        // of a textless image (splash page, art spread). Mark inpaint READY and
        // bail — this is NOT a failure and must not increment retryCount, or
        // auto-translate's dedup gate will re-enqueue the page on every
        // navigation (the reported "keeps reprocessing the same image" bug).
        if (pageTranslation.blocks.isEmpty()) {
            pageTranslation.inpaintStatus = StageStatus.READY
            pageTranslation.errorMessage = null
            pageTranslation.updatedAt = System.currentTimeMillis()
            return null
        }
        // ML Kit mode has no neural inpainter, but there ARE text blocks to
        // clean. Record the skip as a retryable failure (with a clear reason)
        // so auto-translate's bounded retry can re-attempt it once the ONNX
        // engine recovers, instead of silently marking inpaint FAILED forever.
        pageTranslation.inpaintStatus = StageStatus.FAILED
        pageTranslation.retryCount++
        pageTranslation.errorMessage = "Inpainting unavailable in ML Kit mode"
        pageTranslation.updatedAt = System.currentTimeMillis()
        return null
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
        translation.blocks = smartMergeBlocks(translation.blocks, 50, 30, 30)
        translation.ocrBlockCount = translation.blocks.size
        translation.ocrStatus = StageStatus.READY
        translation.updatedAt = System.currentTimeMillis()
        return translation
    }

    private fun smartMergeBlocks(
        blocks: List<TranslationBlock>,
        widthThreshold: Int,
        xThreshold: Int,
        yThreshold: Int,
    ): MutableList<TranslationBlock> {
        if (blocks.isEmpty()) return mutableListOf()

        val merged = mutableListOf<TranslationBlock>()
        var current = blocks[0]
        for (i in 1 until blocks.size) {
            val next = blocks[i]
            if (shouldMergeTextBlock(current, next, widthThreshold, xThreshold, yThreshold)) {
                current = mergeTextBlock(current, next)
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    private fun shouldMergeTextBlock(
        a: TranslationBlock,
        b: TranslationBlock,
        widthThreshold: Int,
        xThreshold: Int,
        yThreshold: Int,
    ): Boolean {
        val isWidthSimilar = (b.width < a.width) || (abs(a.width - b.width) < widthThreshold)
        val isXClose = abs(a.x - b.x) < xThreshold
        val isYClose = (b.y - (a.y + a.height)) < yThreshold
        return isWidthSimilar && isXClose && isYClose
    }

    private fun mergeTextBlock(a: TranslationBlock, b: TranslationBlock): TranslationBlock {
        val newX = minOf(a.x, b.x)
        val newY = a.y
        val newWidth = maxOf(a.x + a.width, b.x + b.width) - newX
        val newHeight = maxOf(a.y + a.height, b.y + b.height) - newY
        return TranslationBlock(
            a.text + " " + b.text,
            a.translation + " " + b.translation,
            newWidth,
            newHeight,
            newX,
            newY,
            a.symHeight,
            a.symWidth,
            a.angle,
            direction = a.direction,
            textColor = a.textColor,
            strokeColor = a.strokeColor,
            strokeWidth = maxOf(a.strokeWidth, b.strokeWidth),
        )
    }

    override fun close() {
        textRecognizer.close()
    }
}
