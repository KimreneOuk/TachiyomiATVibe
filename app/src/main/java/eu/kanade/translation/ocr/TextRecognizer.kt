package eu.kanade.translation.ocr

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import eu.kanade.translation.util.await
import java.io.Closeable

class TextRecognizer(val language: TextRecognizerLanguage) : Closeable {

    private val recognizer = TextRecognition.getClient(
        when (language) {
            TextRecognizerLanguage.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
            TextRecognizerLanguage.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
            TextRecognizerLanguage.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
            // All other languages use the single Latin recognizer. Russian is Cyrillic
            // — ML Kit has no Cyrillic model, so it falls back to Latin best-effort;
            // PaddleOCR v6 small (Cyrillic-aware) is the recommended engine for Russian.
            else -> TextRecognizerOptions.DEFAULT_OPTIONS
        },
    )

    suspend fun recognize(image: InputImage): Text {
        return recognizer.process(image).await()
    }

    override fun close() {
        recognizer.close()
    }
}
