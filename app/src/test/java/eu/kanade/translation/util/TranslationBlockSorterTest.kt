package eu.kanade.translation.util

import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocr.TextRecognizerLanguage
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TranslationBlockSorterTest {

    private fun createBlock(text: String, x: Float, y: Float, panelIndex: Int? = null, panelAssignment: String = "none"): TranslationBlock {
        return TranslationBlock(
            text = text,
            width = 10f, height = 10f,
            x = x, y = y,
            symHeight = 10f, symWidth = 10f,
            angle = 0f,
            panelIndex = panelIndex,
            panelAssignment = panelAssignment,
        )
    }

    @Test
    fun `test panel aware sorting groups by panel index`() {
        // Interleaved Y coordinates: A2 is below B1, but A2 is in panel 0.
        val blocks = listOf(
            createBlock("B1", x = 10f, y = 10f, panelIndex = 1, panelAssignment = "owned"),
            createBlock("A2", x = 10f, y = 20f, panelIndex = 0, panelAssignment = "owned"),
            createBlock("A1", x = 10f, y = 5f, panelIndex = 0, panelAssignment = "owned"),
            createBlock("U1", x = 10f, y = 0f), // unassigned, highest Y, but should be at end
        )

        val sorted = TranslationBlockSorter.sort(blocks, TextRecognizerLanguage.ENGLISH)

        sorted[0].text shouldBe "A1"
        sorted[1].text shouldBe "A2"
        sorted[2].text shouldBe "B1"
        sorted[3].text shouldBe "U1"
    }
}
