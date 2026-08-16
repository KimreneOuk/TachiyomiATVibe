package eu.kanade.translation.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PageTranslationStateTest {

    @Test
    fun `tier 1 display is ready when OCR and translation are ready without cleaned image`() {
        tier1Page().isTier1DisplayReady shouldBe true
        tier1Page(translationStatus = StageStatus.PARTIAL).isTier1DisplayReady shouldBe true
    }

    @Test
    fun `tier 1 display is not ready when cleaned image is ready`() {
        tier1Page(
            cleanedImageName = "page.cleaned.jpg",
            inpaintStatus = StageStatus.READY,
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        ).isTier1DisplayReady shouldBe false
    }

    @Test
    fun `tier 1 display is not ready when OCR is not ready`() {
        tier1Page(ocrStatus = StageStatus.RUNNING).isTier1DisplayReady shouldBe false
    }

    @Test
    fun `tier 1 display is not ready when no translated blocks exist`() {
        tier1Page(blocks = mutableListOf(block(translation = ""))).isTier1DisplayReady shouldBe false
        tier1Page(translationStatus = StageStatus.PENDING).isTier1DisplayReady shouldBe false
    }

    private fun tier1Page(
        blocks: MutableList<TranslationBlock> = mutableListOf(block()),
        cleanedImageName: String? = null,
        ocrStatus: String = StageStatus.READY,
        translationStatus: String = StageStatus.READY,
        inpaintStatus: String = StageStatus.PENDING,
        inpaintRevision: Int = 0,
    ) = PageTranslation(
        blocks = blocks,
        cleanedImageName = cleanedImageName,
        ocrStatus = ocrStatus,
        translationStatus = translationStatus,
        inpaintStatus = inpaintStatus,
        inpaintRevision = inpaintRevision,
    )

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
