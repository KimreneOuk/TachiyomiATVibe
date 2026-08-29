package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReaderTranslationOverlayBindingTest {

    private val translatedBlock = TranslationBlock(
        text = "source",
        translation = "target",
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )

    @Test
    fun `translated image without a current clean result has no overlay binding`() {
        val translation = PageTranslation(
            blocks = mutableListOf(translatedBlock),
            imgWidth = 1200f,
            imgHeight = 1800f,
        )

        selectReaderTranslationOverlayBinding(true, translation) shouldBe
            ReaderTranslationOverlayBinding(emptyList(), 0, 0)
    }

    @Test
    fun `original image never receives translated text`() {
        val translation = PageTranslation(blocks = mutableListOf(translatedBlock))

        selectReaderTranslationOverlayBinding(false, translation) shouldBe
            ReaderTranslationOverlayBinding(emptyList(), 0, 0)
    }
}
