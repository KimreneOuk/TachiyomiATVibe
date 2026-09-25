package eu.kanade.translation.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import eu.kanade.translation.inpainting.InpaintingMode
import eu.kanade.translation.inpainting.PageInpaintingEngine
import eu.kanade.translation.inpainting.aot.AOTInpainting
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
            // the same fixed inverted/gray-snap logic as the ONNX path (the legacy
            // local copy diverged and caused a gray-text bug).
            val contrastColors = RenderColorEstimator.estimate(
                bitmap,
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom,
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
        // TachiyomiAT: ML Kit emits one block per TextBlock with no dedupe, so
        // overlapping TextBlocks on dense pages render on top of each other.
        // Run before ocrBlockCount is set so the count reflects post-dedupe.
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
