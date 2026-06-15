package eu.kanade.translation.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage

class MlKitRoiOcrEngine(language: TextRecognizerLanguage) : RoiOcrEngine {

    private val textRecognizer = TextRecognizer(language)

    override suspend fun recognize(crop: Bitmap): String {
        val image = InputImage.fromBitmap(crop, 0)
        val result = textRecognizer.recognize(image)
        return result.textBlocks
            .flatMap { it.lines }
            .joinToString("\n") { it.text }
            .trim()
    }

    override fun close() = textRecognizer.close()
}
