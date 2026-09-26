package eu.kanade.translation.engines.vision.ocr

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.OcrModel

class OcrModelCatalogTest {

    @Test
    fun `japanese supports mangaocr paddleocr and mlkit`() {
        OcrModelCatalog.entriesFor(TextRecognizerLanguage.JAPANESE).map { it.model } shouldBe listOf(
            OcrModel.MANGAOCR,
            OcrModel.PADDLEOCR_V6_SMALL,
            OcrModel.MLKIT,
        )
    }

    @Test
    fun `non japanese languages do not support mangaocr`() {
        listOf(
            TextRecognizerLanguage.CHINESE,
            TextRecognizerLanguage.KOREAN,
            TextRecognizerLanguage.ENGLISH,
            TextRecognizerLanguage.SPANISH,
            TextRecognizerLanguage.PORTUGUESE,
            TextRecognizerLanguage.INDONESIAN,
            TextRecognizerLanguage.FRENCH,
            TextRecognizerLanguage.GERMAN,
            TextRecognizerLanguage.ITALIAN,
            TextRecognizerLanguage.VIETNAMESE,
            TextRecognizerLanguage.RUSSIAN,
        ).forEach { language ->
            val expected = if (language == TextRecognizerLanguage.CHINESE ||
                language == TextRecognizerLanguage.ENGLISH
            ) {
                listOf(OcrModel.MLKIT, OcrModel.PADDLEOCR_V6_SMALL)
            } else {
                listOf(OcrModel.MLKIT)
            }
            OcrModelCatalog.entriesFor(language).map { it.model } shouldBe expected
        }
    }

    @Test
    fun `invalid selections coerce to language defaults`() {
        OcrModelCatalog.coerce(OcrModel.MANGAOCR, TextRecognizerLanguage.CHINESE) shouldBe OcrModel.MLKIT
        OcrModelCatalog.coerce(
            OcrModel.PADDLEOCR_V6_SMALL,
            TextRecognizerLanguage.JAPANESE,
        ) shouldBe OcrModel.PADDLEOCR_V6_SMALL
        OcrModelCatalog.coerce(OcrModel.MLKIT, TextRecognizerLanguage.JAPANESE) shouldBe OcrModel.MLKIT
    }

    @Test
    fun `defaultFor picks MangaOcr for Japanese and ML Kit otherwise`() {
        OcrModelCatalog.defaultFor(TextRecognizerLanguage.JAPANESE) shouldBe OcrModel.MANGAOCR
        OcrModelCatalog.defaultFor(TextRecognizerLanguage.CHINESE) shouldBe OcrModel.MLKIT
        OcrModelCatalog.defaultFor(TextRecognizerLanguage.KOREAN) shouldBe OcrModel.MLKIT
        OcrModelCatalog.defaultFor(TextRecognizerLanguage.ENGLISH) shouldBe OcrModel.MLKIT
        // New Latin-script languages default to ML Kit too.
        OcrModelCatalog.defaultFor(TextRecognizerLanguage.SPANISH) shouldBe OcrModel.MLKIT
        OcrModelCatalog.defaultFor(TextRecognizerLanguage.PORTUGUESE) shouldBe OcrModel.MLKIT
        OcrModelCatalog.defaultFor(TextRecognizerLanguage.INDONESIAN) shouldBe OcrModel.MLKIT
        OcrModelCatalog.defaultFor(TextRecognizerLanguage.RUSSIAN) shouldBe OcrModel.MLKIT
    }

    @Test
    fun `isCompatible is false for MangaOcr outside Japanese`() {
        OcrModelCatalog.isCompatible(OcrModel.MANGAOCR, TextRecognizerLanguage.JAPANESE) shouldBe true
        OcrModelCatalog.isCompatible(OcrModel.MANGAOCR, TextRecognizerLanguage.CHINESE) shouldBe false
        // ML Kit and PaddleOCR advertise no language restriction → compatible everywhere.
        OcrModelCatalog.isCompatible(OcrModel.MLKIT, TextRecognizerLanguage.KOREAN) shouldBe true
        OcrModelCatalog.isCompatible(OcrModel.PADDLEOCR_V6_SMALL, TextRecognizerLanguage.ENGLISH) shouldBe true
    }

    @Test
    fun `labelsFor maps each supported model to its display label`() {
        val labels = OcrModelCatalog.labelsFor(TextRecognizerLanguage.JAPANESE)

        labels[OcrModel.MANGAOCR] shouldBe "MangaOCR"
        labels[OcrModel.PADDLEOCR_V6_SMALL] shouldBe "PaddleOCR v6 small"
        labels[OcrModel.MLKIT] shouldBe "ML Kit"
        // MangaOCR must NOT appear for a non-Japanese language.
        OcrModelCatalog.labelsFor(TextRecognizerLanguage.CHINESE).containsKey(OcrModel.MANGAOCR) shouldBe false
    }
}
