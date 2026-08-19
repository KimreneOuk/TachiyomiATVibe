package eu.kanade.translation.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PageTranslationStateTest {

    @Test
    fun `isCleanedImageReady requires ready inpaint and current revision`() {
        val cleanPage = PageTranslation(
            cleanedImageName = "page.cleaned.jpg",
            inpaintStatus = StageStatus.READY,
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
            blocks = mutableListOf(block()),
        )
        cleanPage.isCleanedImageReady shouldBe true

        val pendingInpaint = cleanPage.copy(inpaintStatus = StageStatus.PENDING)
        pendingInpaint.isCleanedImageReady shouldBe false

        val staleInpaint = cleanPage.copy(inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION - 1)
        staleInpaint.isCleanedImageReady shouldBe false

        val noCleanedImage = cleanPage.copy(cleanedImageName = null)
        noCleanedImage.isCleanedImageReady shouldBe false
    }

    @Test
    fun `shouldSurfaceError only surfaces for terminal stage failures without cleaned result`() {
        val failedPage = PageTranslation(
            ocrStatus = StageStatus.FAILED,
            ocrError = "OCR error",
        )
        failedPage.shouldSurfaceError shouldBe true

        val cancelledPage = failedPage.copy(ocrStatus = StageStatus.CANCELLED)
        cancelledPage.shouldSurfaceError shouldBe false

        val cleanPageWithFailure = failedPage.copy(
            cleanedImageName = "page.cleaned.jpg",
            inpaintStatus = StageStatus.READY,
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
            blocks = mutableListOf(block()),
        )
        cleanPageWithFailure.shouldSurfaceError shouldBe false
    }

    @Test
    fun `cancelInFlightStages flips running stages to cancelled`() {
        val page = PageTranslation(
            ocrStatus = StageStatus.RUNNING,
            translationStatus = StageStatus.RUNNING,
            inpaintStatus = StageStatus.RUNNING,
            renderStatus = StageStatus.RUNNING,
        )
        page.cancelInFlightStages()

        page.ocrStatus shouldBe StageStatus.CANCELLED
        page.translationStatus shouldBe StageStatus.CANCELLED
        page.inpaintStatus shouldBe StageStatus.CANCELLED
        page.renderStatus shouldBe StageStatus.CANCELLED
    }

    @Test
    fun `recordAttemptFailure is idempotent per attempt`() {
        val page = PageTranslation()
        page.recordAttemptFailure()
        page.attemptCount shouldBe 1
        page.retryCount shouldBe 1

        // Second failure in same attempt should not charge again
        page.recordAttemptFailure()
        page.attemptCount shouldBe 1
        page.retryCount shouldBe 1

        // Reset charge for next attempt
        page.resetAttemptCharge()
        page.recordAttemptFailure()
        page.attemptCount shouldBe 2
        page.retryCount shouldBe 2
    }

    private fun block(translation: String = "translated") = TranslationBlock(
        text = "source",
        translation = translation,
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )
}
