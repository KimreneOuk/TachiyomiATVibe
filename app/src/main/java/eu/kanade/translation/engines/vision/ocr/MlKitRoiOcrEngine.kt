package eu.kanade.translation.engines.vision.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import tachiyomi.domain.translation.pools.BitmapPool

class MlKitRoiOcrEngine(language: TextRecognizerLanguage) : RoiOcrEngine {

    private val textRecognizer = TextRecognizer(language)

    override suspend fun recognize(crop: Bitmap): String {
        val preprocessed = MlKitOcrPreprocessor.preprocessRoi(crop)
        try {
            val image = InputImage.fromBitmap(preprocessed, 0)
            val result = textRecognizer.recognize(image)
            return result.textBlocks
                .flatMap { it.lines }
                .joinToString("\n") { it.text }
                .trim()
        } finally {
            BitmapPool.putARGB8888(preprocessed)
        }
    }

    override fun close() = textRecognizer.close()
}
