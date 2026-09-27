package eu.kanade.translation.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class PageDisplayReadinessTest {
    @Test
    fun `cleaned-only and translation-failed text pages select original`() {
        val cleanedOnly = readyImagePage(translationStatus = StageStatus.PENDING, renderStatus = StageStatus.PENDING)
        val failed = readyImagePage(translationStatus = StageStatus.FAILED, renderStatus = StageStatus.SKIPPED)

        cleanedOnly.displayImageName shouldBe null
        failed.displayImageName shouldBe null
    }

    @Test
    fun `explicit textless page skips downstream and is terminal success`() {
        val textless = PageTranslation(
            blocks = mutableListOf(),
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.SKIPPED,
            inpaintStatus = StageStatus.SKIPPED,
            renderStatus = StageStatus.SKIPPED,
        )

        textless.isTextlessTerminal shouldBe true
        textless.lifecycle shouldBe PageLifecycle.Textless
        textless.displayImageName shouldBe null
    }

    @Test
    fun `text-only overlay change changes fingerprint but keeps image identity`() {
        val first = readyImagePage(translation = "first").toPageView()
        val second = readyImagePage(translation = "corrected").toPageView()

        first.imageName shouldBe second.imageName
        first.overlayFingerprint shouldNotBe second.overlayFingerprint
        first shouldNotBe second
    }

    private fun readyImagePage(
        translation: String = "translated",
        translationStatus: String = StageStatus.READY,
        renderStatus: String = StageStatus.READY,
    ) = PageTranslation(
        blocks = mutableListOf(block(translation)),
        cleanedImageName = "same.cleaned.jpg",
        ocrStatus = StageStatus.READY,
        translationStatus = translationStatus,
        inpaintStatus = StageStatus.READY,
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        renderStatus = renderStatus,
    )

    private fun block(translation: String) = TranslationBlock(
        text = "source",
        translation = translation,
        width = 10f,
        height = 10f,
        x = 1f,
        y = 1f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )
}
