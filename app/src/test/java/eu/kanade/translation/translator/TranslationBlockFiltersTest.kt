package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Guards the watermark-removal filter shared by every translator. Translators
 * are prompted to replace site-link/watermark text (e.g. "colamanga.com") with
 * the sentinel "RTMTH"; this filter then drops any block whose translation
 * still carries that sentinel so the watermark never renders.
 */
class TranslationBlockFiltersTest {

    private fun block(translation: String, text: String = "src") = TranslationBlock(
        text = text,
        translation = translation,
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )

    @Test
    fun `blocks whose translation contains the watermark sentinel are removed`() {
        val page = PageTranslation(
            blocks = mutableListOf(
                block("keep me"),
                block("RTMTH"),
                block("also keep"),
            ),
        )

        TranslationBlockFilters.removeWatermarkBlocks(mutableMapOf("001.jpg" to page))

        page.blocks.map { it.translation } shouldBe listOf("keep me", "also keep")
    }

    @Test
    fun `watermark sentinel matching is case-insensitive`() {
        val page = PageTranslation(blocks = mutableListOf(block("rtmth trailing")))

        TranslationBlockFilters.removeWatermarkBlocks(mutableMapOf("001.jpg" to page))

        page.blocks shouldBe emptyList()
    }

    @Test
    fun `sentinel embedded in larger translation is still treated as a watermark`() {
        val page = PageTranslation(blocks = mutableListOf(block("visit RTMTH now")))

        TranslationBlockFilters.removeWatermarkBlocks(mutableMapOf("001.jpg" to page))

        page.blocks shouldBe emptyList()
    }

    @Test
    fun `non-watermark blocks across multiple pages are preserved`() {
        val page1 = PageTranslation(blocks = mutableListOf(block("a"), block("b")))
        val page2 = PageTranslation(blocks = mutableListOf(block("RTMTH"), block("c")))

        TranslationBlockFilters.removeWatermarkBlocks(
            mutableMapOf("001.jpg" to page1, "002.jpg" to page2),
        )

        page1.blocks.map { it.translation } shouldBe listOf("a", "b")
        page2.blocks.map { it.translation } shouldBe listOf("c")
    }

    @Test
    fun `pages with no blocks are a no-op`() {
        val pages = mutableMapOf("001.jpg" to PageTranslation(blocks = mutableListOf()))

        TranslationBlockFilters.removeWatermarkBlocks(pages)

        pages["001.jpg"]?.blocks shouldBe emptyList()
    }
}
