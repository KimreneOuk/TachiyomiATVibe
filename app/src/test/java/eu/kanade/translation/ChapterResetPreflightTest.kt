package eu.kanade.translation

import eu.kanade.translation.model.InpaintMaskBox
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.persistence.chapter.ChapterResetPreflight
import eu.kanade.translation.persistence.chapter.chapterResetPreflight
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChapterResetPreflightTest {

    @Test
    fun `counts durable chapter artifacts and edits`() {
        val pages = listOf(
            PageTranslation(
                blocks = mutableListOf(
                    block(translation = "Hello"),
                    block(translation = "Edited", userEditedAt = 1L),
                ),
                cleanedImageName = "page-1.cleaned.png",
            ),
            PageTranslation(
                inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 4, 4, 1)),
            ),
            PageTranslation(
                blocks = mutableListOf(block()),
            ),
        )

        chapterResetPreflight(pages) shouldBe ChapterResetPreflight(
            ocrPages = 3,
            inpaintPages = 1,
            translatedBlocks = 2,
            manualEditBlocks = 1,
        )
    }

    @Test
    fun `empty pages have no reset data`() {
        chapterResetPreflight(listOf(PageTranslation())) shouldBe ChapterResetPreflight(0, 0, 0, 0)
    }

    private fun block(
        translation: String = "",
        userEditedAt: Long? = null,
    ): TranslationBlock = TranslationBlock(
        text = "text",
        translation = translation,
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 10f,
        symWidth = 10f,
        angle = 0f,
        userEditedAt = userEditedAt,
    )
}
