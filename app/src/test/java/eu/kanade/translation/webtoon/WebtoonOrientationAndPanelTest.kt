package eu.kanade.translation.webtoon

import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.util.TranslationBlockSorter
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.TranslationReadingOrder

class WebtoonOrientationAndPanelTest {

    private fun createBlock(
        text: String,
        x: Float,
        y: Float,
        width: Float = 100f,
        height: Float = 100f,
        panelIndex: Int? = null,
        panelAssignment: String = "none",
    ): TranslationBlock {
        return TranslationBlock(
            text = text,
            width = width,
            height = height,
            x = x,
            y = y,
            symHeight = height,
            symWidth = width,
            angle = 0f,
            panelIndex = panelIndex,
            panelAssignment = panelAssignment,
        )
    }

    @Test
    fun `Japanese manga with RTL reading order sorts right-to-left within rows`() {
        val leftBlock = createBlock("Left", x = 50f, y = 100f)
        val rightBlock = createBlock("Right", x = 500f, y = 100f)

        val sorted = TranslationBlockSorter.sort(
            blocks = listOf(leftBlock, rightBlock),
            fromLang = TextRecognizerLanguage.JAPANESE,
            readingOrder = TranslationReadingOrder.AUTO,
        )

        // In Japanese manga (RTL), right block must come first
        sorted[0].text shouldBe "Right"
        sorted[1].text shouldBe "Left"
    }

    @Test
    fun `Korean webtoon with AUTO or LTR sorts left-to-right within rows`() {
        val leftBlock = createBlock("Left", x = 50f, y = 100f)
        val rightBlock = createBlock("Right", x = 500f, y = 100f)

        val sorted = TranslationBlockSorter.sort(
            blocks = listOf(leftBlock, rightBlock),
            fromLang = TextRecognizerLanguage.KOREAN,
            readingOrder = TranslationReadingOrder.AUTO,
        )

        // In Korean manhwa / LTR, left block must come first
        sorted[0].text shouldBe "Left"
        sorted[1].text shouldBe "Right"
    }

    @Test
    fun `Webtoon multi-block vertical flow sorts top-to-bottom accurately without panel assignment`() {
        // Continuous webtoon strip without panels (panelAssignment = none)
        val topBubble = createBlock("Top Bubble", x = 200f, y = 500f)
        val midBubble = createBlock("Mid Bubble", x = 200f, y = 1500f)
        val bottomBubble = createBlock("Bottom Bubble", x = 200f, y = 3500f)

        val sorted = TranslationBlockSorter.sort(
            blocks = listOf(bottomBubble, topBubble, midBubble),
            fromLang = TextRecognizerLanguage.KOREAN,
            readingOrder = TranslationReadingOrder.LTR_COMIC,
        )

        sorted[0].text shouldBe "Top Bubble"
        sorted[1].text shouldBe "Mid Bubble"
        sorted[2].text shouldBe "Bottom Bubble"
    }
}
