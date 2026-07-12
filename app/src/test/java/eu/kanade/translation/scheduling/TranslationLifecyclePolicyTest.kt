package eu.kanade.translation.scheduling

import eu.kanade.translation.model.PageLifecycle
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TranslationLifecyclePolicyTest {

    @Test
    fun `null page is schedulable and classified as Pending`() {
        TranslationLifecyclePolicy.shouldSchedule(null) shouldBe true
        TranslationLifecyclePolicy.classify(null) shouldBe PageLifecycle.Pending
    }

    @Test
    fun `translated text without rendered image needs render and is schedulable`() {
        val page = translatedPage()

        TranslationLifecyclePolicy.classify(page) shouldBe PageLifecycle.NeedsRender
        TranslationLifecyclePolicy.shouldSchedule(page) shouldBe true
    }

    @Test
    fun `translated text with only cleaned image still needs render and is schedulable`() {
        val page = translatedPage().apply {
            cleanedImageName = "001.cleaned.png"
        }

        TranslationLifecyclePolicy.classify(page) shouldBe PageLifecycle.NeedsRender
        TranslationLifecyclePolicy.shouldSchedule(page) shouldBe true
    }

    @Test
    fun `cancelled page is schedulable and does not count as retry exhaustion`() {
        val page = PageTranslation(
            ocrStatus = StageStatus.CANCELLED,
            errorMessage = "Translation cancelled",
        ).apply { attemptCount = StageStatus.MAX_STAGE_RETRIES }

        TranslationLifecyclePolicy.classify(page) shouldBe PageLifecycle.Cancelled
        TranslationLifecyclePolicy.reasons(page).exhausted shouldBe false
        TranslationLifecyclePolicy.shouldSchedule(page) shouldBe true
    }

    @Test
    fun `retry exhaustion blocks auto scheduling for failed pages`() {
        // TachiyomiAT: exhaustion keys on attemptCount (distinct attempts), not
        // retryCount (per-stage increments). One failed attempt is NOT exhausted.
        val retryable = PageTranslation(
            ocrStatus = StageStatus.FAILED,
        ).apply { attemptCount = StageStatus.MAX_STAGE_RETRIES - 1 }
        val exhausted = PageTranslation(
            ocrStatus = StageStatus.FAILED,
        ).apply { attemptCount = StageStatus.MAX_STAGE_RETRIES }

        TranslationLifecyclePolicy.shouldSchedule(retryable) shouldBe true
        TranslationLifecyclePolicy.shouldSchedule(exhausted) shouldBe false
        TranslationLifecyclePolicy.reasons(exhausted).firstReason() shouldBe "retries-exhausted"
    }

    @Test
    fun `running and rendered pages are skipped with a readable reason`() {
        val running = PageTranslation(ocrStatus = StageStatus.RUNNING)
        val rendered = PageTranslation(cleanedImageName = "001.cleaned.webp")
        // Textless-terminal requires ocrStatus=READY, empty blocks, AND inpaint
        // out of PENDING/RUNNING (see isTextlessTerminal). A default page has
        // inpaintStatus=PENDING so it is NOT terminal.
        val textless = PageTranslation(
            ocrStatus = StageStatus.READY,
            inpaintStatus = StageStatus.READY,
            blocks = mutableListOf(),
        )

        TranslationLifecyclePolicy.shouldSchedule(running) shouldBe false
        TranslationLifecyclePolicy.reasons(running).firstReason() shouldBe "already-running"

        TranslationLifecyclePolicy.shouldSchedule(rendered) shouldBe false
        TranslationLifecyclePolicy.reasons(rendered).firstReason() shouldBe "rendered-result-exists"

        TranslationLifecyclePolicy.shouldSchedule(textless) shouldBe false
        TranslationLifecyclePolicy.reasons(textless).firstReason() shouldBe "textless"
    }

    @Test
    fun `rendered translated page from old inpaint revision is scheduled again`() {
        val stale = translatedPage().apply {
            cleanedImageName = "001.cleaned.png"
            inpaintRevision = 0
        }
        val current = stale.copy(inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION)

        TranslationLifecyclePolicy.classify(stale) shouldBe PageLifecycle.NeedsRender
        TranslationLifecyclePolicy.shouldSchedule(stale) shouldBe true
        TranslationLifecyclePolicy.shouldSchedule(current) shouldBe false
    }

    @Test
    fun `downsampled page with current inpaint revision is complete`() {
        val stale = translatedPage().apply {
            cleanedImageName = "001.cleaned.png"
            decodeSampleSize = 2
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
        }

        TranslationLifecyclePolicy.classify(stale) shouldBe PageLifecycle.Done
        TranslationLifecyclePolicy.shouldSchedule(stale) shouldBe false
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
