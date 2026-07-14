package eu.kanade.translation.batch

import eu.kanade.translation.model.InpaintMaskBox
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BatchResumeGateDeciderTest {

    @Test
    fun `null page returns FULL`() {
        BatchResumeGateDecider.decide(null) shouldBe BatchResumeGateDecider.Decision.FULL
    }

    @Test
    fun `ocr not ready returns FULL even with mask and cleaned`() {
        val page = page(ocrStatus = StageStatus.RUNNING).withMaskAndDurableCleaned()

        BatchResumeGateDecider.decide(page) shouldBe BatchResumeGateDecider.Decision.FULL
    }

    @Test
    fun `ocr ready with mask but no cleaned returns INPAINT_ONLY`() {
        val page = page(ocrStatus = StageStatus.READY).withMask()

        BatchResumeGateDecider.decide(page) shouldBe BatchResumeGateDecider.Decision.INPAINT_ONLY
    }

    @Test
    fun `ocr ready with mask and cleaned but inpaint not ready returns INPAINT_ONLY`() {
        val page = page(ocrStatus = StageStatus.READY).withMask().apply {
            cleanedImageName = "001.cleaned.png"
            inpaintStatus = StageStatus.RUNNING
        }

        BatchResumeGateDecider.decide(page) shouldBe BatchResumeGateDecider.Decision.INPAINT_ONLY
    }

    @Test
    fun `ocr ready with mask and durable cleaned returns SKIP_ALL`() {
        val page = page(ocrStatus = StageStatus.READY).withMaskAndDurableCleaned()

        BatchResumeGateDecider.decide(page) shouldBe BatchResumeGateDecider.Decision.SKIP_ALL
    }

    @Test
    fun `mode mismatch on durable cleaned returns INPAINT_ONLY`() {
        // FAST->QUALITY switch: the durable cleaned output is stale and must be
        // re-inpainted rather than skipped, while still reusing the OCR mask.
        val page = page(ocrStatus = StageStatus.READY).withMaskAndDurableCleaned().apply {
            inpaintingModeUsed = "FAST"
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
            blocks.add(TranslationBlock(text = "x", width = 1f, height = 1f, x = 0f, y = 0f, symHeight = 1f, symWidth = 1f, angle = 0f))
        }

        BatchResumeGateDecider.decide(page, cleanedFileValid = true, inpaintModeMatches = true) shouldBe
            BatchResumeGateDecider.Decision.SKIP_ALL
        BatchResumeGateDecider.decide(page, cleanedFileValid = true, inpaintModeMatches = false) shouldBe
            BatchResumeGateDecider.Decision.INPAINT_ONLY
    }

    @Test
    fun `ocr ready with mask and cleaned but stale inpaint result returns INPAINT_ONLY`() {
        // hasCurrentInpaintResult is false when blocks non-empty and
        // inpaintRevision < CURRENT_INPAINT_REVISION (10).
        val page = page(ocrStatus = StageStatus.READY).withMask().apply {
            cleanedImageName = "001.cleaned.png"
            inpaintStatus = StageStatus.READY
            inpaintRevision = 0
        }
        page.blocks.add(TranslationBlock(text = "x", width = 1f, height = 1f, x = 0f, y = 0f, symHeight = 1f, symWidth = 1f, angle = 0f))

        BatchResumeGateDecider.decide(page) shouldBe BatchResumeGateDecider.Decision.INPAINT_ONLY
    }

    @Test
    fun `ocr ready without mask returns FULL`() {
        // Non-empty blocks + empty mask => hasCurrentInpaintMask false => FULL.
        val page = page(ocrStatus = StageStatus.READY).apply {
            cleanedImageName = "001.cleaned.png"
            inpaintStatus = StageStatus.READY
            blocks.add(TranslationBlock(text = "x", width = 1f, height = 1f, x = 0f, y = 0f, symHeight = 1f, symWidth = 1f, angle = 0f))
        }

        BatchResumeGateDecider.decide(page) shouldBe BatchResumeGateDecider.Decision.FULL
    }

    @Test
    fun `textless page with mask and durable cleaned returns SKIP_ALL`() {
        // Empty blocks => hasCurrentInpaintMask and hasCurrentInpaintResult true.
        val page = page(ocrStatus = StageStatus.READY).apply {
            cleanedImageName = "001.cleaned.png"
            inpaintStatus = StageStatus.READY
        }

        BatchResumeGateDecider.decide(page) shouldBe BatchResumeGateDecider.Decision.SKIP_ALL
    }

    private fun page(ocrStatus: String) = PageTranslation(
        imgWidth = 100f,
        imgHeight = 100f,
        ocrStatus = ocrStatus,
    )

    private fun PageTranslation.withMask(): PageTranslation = apply {
        inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 2))
    }

    private fun PageTranslation.withMaskAndDurableCleaned(): PageTranslation = apply {
        inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 2))
        cleanedImageName = "001.cleaned.png"
        inpaintStatus = StageStatus.READY
    }
}
