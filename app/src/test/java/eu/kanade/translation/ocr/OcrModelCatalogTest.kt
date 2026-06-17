package eu.kanade.translation.ocr

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
        ).forEach { language ->
            OcrModelCatalog.entriesFor(language).map { it.model } shouldBe listOf(
                OcrModel.MLKIT,
                OcrModel.PADDLEOCR_V6_SMALL,
            )
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
}
