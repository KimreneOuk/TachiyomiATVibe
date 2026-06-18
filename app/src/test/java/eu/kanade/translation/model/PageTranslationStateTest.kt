package eu.kanade.translation.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PageTranslationStateTest {

    @Test
    fun `translated text without rendered image needs render and is schedulable`() {
        val page = translatedPage()

        page.lifecycle shouldBe PageLifecycle.NeedsRender
        page.shouldSkipAutoScheduling shouldBe false
    }

    @Test
    fun `translated text with only cleaned image still needs render and is schedulable`() {
        val page = translatedPage().apply {
            cleanedImageName = "001.cleaned.png"
        }

        page.lifecycle shouldBe PageLifecycle.NeedsRender
        page.shouldSkipAutoScheduling shouldBe false
        page.displayImageName shouldBe null
    }

    @Test
    fun `cancelled page is schedulable and does not count as retry exhaustion`() {
        val page = PageTranslation(
            ocrStatus = StageStatus.CANCELLED,
            retryCount = StageStatus.MAX_STAGE_RETRIES,
            errorMessage = "Translation cancelled",
        )

        page.lifecycle shouldBe PageLifecycle.Cancelled
        page.hasExhaustedRetries shouldBe false
        page.shouldSkipAutoScheduling shouldBe false
    }

    @Test
    fun `retry exhaustion blocks auto scheduling for failed pages`() {
        val retryable = PageTranslation(
            ocrStatus = StageStatus.FAILED,
            retryCount = StageStatus.MAX_STAGE_RETRIES - 1,
        )
        val exhausted = retryable.copy(retryCount = StageStatus.MAX_STAGE_RETRIES)

        retryable.shouldSkipAutoScheduling shouldBe false
        exhausted.shouldSkipAutoScheduling shouldBe true
    }

    private fun translatedPage(): PageTranslation {
        return PageTranslation(
            blocks = mutableListOf(
                TranslationBlock(
                    text = "source",
                    translation = "translated",
                    width = 10f,
                    height = 10f,
                    x = 0f,
                    y = 0f,
                    symHeight = 1f,
                    symWidth = 1f,
                    angle = 0f,
                ),
            ),
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.READY,
            inpaintStatus = StageStatus.READY,
            renderStatus = StageStatus.PENDING,
        )
    }
}
