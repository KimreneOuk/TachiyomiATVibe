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
    fun `rendered text page from old inpaint revision is schedulable`() {
        val page = translatedPage().apply {
            cleanedImageName = "001.cleaned.png"
            renderedImageName = "001.rendered.webp"
            inpaintRevision = 0
        }

        page.lifecycle shouldBe PageLifecycle.NeedsRender
        page.shouldSkipAutoScheduling shouldBe false
        page.displayImageName shouldBe null
    }

    @Test
    fun `rendered text page from current inpaint revision is done`() {
        val page = translatedPage().apply {
            cleanedImageName = "001.cleaned.png"
            renderedImageName = "001.rendered.webp"
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
        }

        page.lifecycle shouldBe PageLifecycle.Done
        page.shouldSkipAutoScheduling shouldBe true
        page.displayImageName shouldBe "001.rendered.webp"
    }

    @Test
    fun `legacy unknown rendered page with downsample is stale`() {
        val page = translatedPage().apply {
            cleanedImageName = "001.cleaned.png"
            renderedImageName = "001.rendered.webp"
            decodeSampleSize = 2
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
        }

        page.lifecycle shouldBe PageLifecycle.NeedsRender
        page.shouldSkipAutoScheduling shouldBe false
        page.displayImageName shouldBe null
    }

    @Test
    fun `size limited rendered page is trusted when explicitly marked`() {
        val page = translatedPage().apply {
            cleanedImageName = "001.cleaned.png"
            renderedImageName = "001.rendered.png"
            renderQuality = RenderQuality.SIZE_LIMITED
            decodeSampleSize = 2
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
        }

        page.lifecycle shouldBe PageLifecycle.Done
        page.shouldSkipAutoScheduling shouldBe true
        page.displayImageName shouldBe "001.rendered.png"
    }

    @Test
    fun `forced retry resets failed page without a result`() {
        val page = PageTranslation(
            ocrStatus = StageStatus.FAILED,
            renderStatus = StageStatus.FAILED,
            renderedImageName = "001.rendered.webp",
            decodeSampleSize = 2,
            retryCount = StageStatus.MAX_STAGE_RETRIES,
            errorMessage = "old failure",
        )

        page.prepareForcedRetry()

        page.ocrStatus shouldBe StageStatus.RUNNING
        page.translationStatus shouldBe StageStatus.PENDING
        page.inpaintStatus shouldBe StageStatus.PENDING
        page.renderStatus shouldBe StageStatus.PENDING
        page.retryCount shouldBe 0
        page.errorMessage shouldBe null
        page.renderedImageName shouldBe null
    }

    @Test
    fun `forced retry keeps trusted rendered output visible while retry runs`() {
        val page = translatedPage().apply {
            renderedImageName = "001.rendered.png"
            renderQuality = RenderQuality.FULL
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
            retryCount = StageStatus.MAX_STAGE_RETRIES
            errorMessage = "old failure"
        }

        page.prepareForcedRetry()

        page.ocrStatus shouldBe StageStatus.RUNNING
        page.retryCount shouldBe 0
        page.errorMessage shouldBe null
        page.renderedImageName shouldBe "001.rendered.png"
        page.displayImageName shouldBe "001.rendered.png"
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
