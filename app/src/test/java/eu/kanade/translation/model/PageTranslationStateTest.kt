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
    fun `forced retry clears trusted rendered output so retry cannot show stale result`() {
        val page = translatedPage().apply {
            renderedImageName = "001.rendered.png"
            cleanedImageName = "001.cleaned.png"
            renderQuality = RenderQuality.FULL
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
            retryCount = StageStatus.MAX_STAGE_RETRIES
            errorMessage = "old failure"
        }

        page.prepareForcedRetry()

        page.ocrStatus shouldBe StageStatus.RUNNING
        page.retryCount shouldBe 0
        page.errorMessage shouldBe null
        page.renderedImageName shouldBe null
        page.cleanedImageName shouldBe null
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

    // ── shouldSurfaceError: only real FAILED stages surface the red error ────

    @Test
    fun `shouldSurfaceError is true for a page with a failed stage and no result`() {
        val page = PageTranslation(
            blocks = mutableListOf(),
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.FAILED,
            errorMessage = "Translation incomplete: 0/2 blocks translated",
        )

        page.shouldSurfaceError shouldBe true
    }

    @Test
    fun `shouldSurfaceError is false for a cancelled page even with an error message`() {
        // The stranded-page sweep / per-page cancel writes "Translation
        // cancelled" / "Page was stranded...". Those are NOT failures and must
        // not paint the red error overlay.
        val page = PageTranslation(
            ocrStatus = StageStatus.CANCELLED,
            translationStatus = StageStatus.CANCELLED,
            inpaintStatus = StageStatus.CANCELLED,
            renderStatus = StageStatus.CANCELLED,
            errorMessage = "Page was stranded mid-translation; reset as cancelled on chapter reopen",
        )

        page.shouldSurfaceError shouldBe false
    }

    @Test
    fun `shouldSurfaceError is false for a partial page`() {
        // PARTIAL produced output (some blocks); its explanatory message names
        // how many blocks translated but is not a failure to paint red.
        val page = PageTranslation(
            blocks = mutableListOf(
                TranslationBlock(
                    text = "源", translation = "source",
                    width = 10f, height = 10f, x = 0f, y = 0f,
                    symHeight = 1f, symWidth = 1f, angle = 0f,
                ),
            ),
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.PARTIAL,
            errorMessage = "Translation partial: 1/2 blocks translated",
        )

        page.shouldSurfaceError shouldBe false
    }

    @Test
    fun `shouldSurfaceError is false for a textless terminal page`() {
        // Textless (OCR found nothing) is clean success; it carries no error
        // and must not be treated as a failure.
        val page = PageTranslation(
            blocks = mutableListOf(),
            ocrStatus = StageStatus.READY,
            inpaintStatus = StageStatus.READY,
        )

        page.isTextlessTerminal shouldBe true
        page.shouldSurfaceError shouldBe false
    }

    @Test
    fun `shouldSurfaceError is false once a rendered result exists even if a stage failed`() {
        // A page that produced a rendered image shows the image, not a red
        // error — even if some stage is FAILED (e.g. a stale render from a
        // prior run while the current retry failed).
        val page = translatedPage().apply {
            renderedImageName = "001.rendered.png"
            renderQuality = RenderQuality.FULL
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
            translationStatus = StageStatus.FAILED
            errorMessage = "stale"
        }

        page.shouldSurfaceError shouldBe false
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
