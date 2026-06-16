package eu.kanade.translation.recognition

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocr.TextRecognizer
import eu.kanade.translation.ocr.TextRecognizerLanguage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class MlKitFullPageRecognitionEngine(language: TextRecognizerLanguage) : PageRecognitionEngine {

    private val textRecognizer = TextRecognizer(language)

    override suspend fun analyze(bitmap: Bitmap): PageTranslation {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = textRecognizer.recognize(image)
        val blocks = result.textBlocks.filter { it.boundingBox != null && it.text.length > 1 }
        return convertToPageTranslation(blocks, bitmap, image.width, image.height)
    }

    override suspend fun inpaint(bitmap: Bitmap, pageTranslation: PageTranslation): Bitmap? {
        // TachiyomiAT: ML Kit mode has no neural inpainter. Record the skip as a
        // retryable failure (with a clear reason) so auto-translate's bounded retry
        // can re-attempt it once the ONNX engine recovers, instead of silently
        // marking inpaint FAILED forever.
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
            val contrastColors = computeContrastColors(bitmap, bounds.left, bounds.top, bounds.right, bounds.bottom)
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
        val newX = kotlin.math.min(a.x, b.x)
        val newY = a.y
        val newWidth = kotlin.math.max(a.x + a.width, b.x + b.width) - newX
        val newHeight = kotlin.math.max(a.y + a.height, b.y + b.height) - newY
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
            strokeWidth = max(a.strokeWidth, b.strokeWidth),
        )
    }

    private fun computeContrastColors(bitmap: Bitmap, x1: Int, y1: Int, x2: Int, y2: Int): Triple<Long, Long, Float> {
        val boxW = max(1, x2 - x1)
        val boxH = max(1, y2 - y1)
        val pad = max(12, min(boxW, boxH) / 2)
        val left = (x1 - pad).coerceIn(0, bitmap.width)
        val top = (y1 - pad).coerceIn(0, bitmap.height)
        val right = (x2 + pad).coerceIn(left, bitmap.width)
        val bottom = (y2 + pad).coerceIn(top, bitmap.height)
        val cropW = right - left
        val cropH = bottom - top
        if (cropW <= 0 || cropH <= 0) return PYTHON_DEFAULT_TEXT_COLORS

        val pixels = IntArray(cropW * cropH)
        bitmap.getPixels(pixels, 0, cropW, left, top, cropW, cropH)
        val step = max(1, pixels.size / 1000)

        var center0 = floatArrayOf(0f, 0f, 0f)
        var center1 = floatArrayOf(255f, 255f, 255f)
        var count0 = 0
        var count1 = 0

        repeat(5) {
            var sum0 = floatArrayOf(0f, 0f, 0f)
            var sum1 = floatArrayOf(0f, 0f, 0f)
            count0 = 0
            count1 = 0

            for (i in pixels.indices step step) {
                val pixel = pixels[i]
                val r = (pixel shr 16 and 0xFF).toFloat()
                val g = (pixel shr 8 and 0xFF).toFloat()
                val b = (pixel and 0xFF).toFloat()
                val d0 = (r - center0[0]).pow(2) + (g - center0[1]).pow(2) + (b - center0[2]).pow(2)
                val d1 = (r - center1[0]).pow(2) + (g - center1[1]).pow(2) + (b - center1[2]).pow(2)
                if (d0 < d1) {
                    sum0[0] += r
                    sum0[1] += g
                    sum0[2] += b
                    count0++
                } else {
                    sum1[0] += r
                    sum1[1] += g
                    sum1[2] += b
                    count1++
                }
            }

            if (count0 > 0) {
                center0[0] = sum0[0] / count0
                center0[1] = sum0[1] / count0
                center0[2] = sum0[2] / count0
            }
            if (count1 > 0) {
                center1[0] = sum1[0] / count1
                center1[1] = sum1[1] / count1
                center1[2] = sum1[2] / count1
            }
        }

        val bgColor = if (count0 >= count1) center0 else center1
        val brightness = 0.299f * bgColor[0] + 0.587f * bgColor[1] + 0.114f * bgColor[2]
        return if (brightness < 85f) INVERTED_TEXT_COLORS else PYTHON_DEFAULT_TEXT_COLORS
    }

    companion object {
        private val PYTHON_DEFAULT_TEXT_COLORS = Triple(0xFF000000, 0xFFFFFFFF, 2.0f)
        private val INVERTED_TEXT_COLORS = Triple(0xFFFFFFFF, 0xFF000000, 2.0f)
    }

    override fun close() {
        textRecognizer.close()
    }
}
