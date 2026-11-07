package eu.kanade.translation.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Reader display and projection gates for colorize-only pages: a colored
 * ORIGINAL with OCR/translation/inpaint deliberately SKIPPED must display and
 * project as terminal without any cleaned image existing.
 */
class ColorizeOnlyDisplayTest {

    @Test
    fun `colorize-only complete page displays its colored image when colorization is enabled`() {
        val page = colorizeOnlyPage()

        page.isColorizeOnlyComplete shouldBe true
        page.displayImageName(colorizationEnabled = true) shouldBe COLORED_NAME
        page.displayImageName(colorizationEnabled = false) shouldBe null
    }

    @Test
    fun `cleaned-based colored page keeps existing gate behavior`() {
        val page = cleanedColoredPage()

        page.displayImageName(colorizationEnabled = true) shouldBe COLORED_NAME
        page.displayImageName(colorizationEnabled = false) shouldBe CLEANED_NAME
    }

    @Test
    fun `coloredFromOriginal without skipped stages is not terminal but keeps displaying`() {
        // A re-OCR'd colorize-only page is mid-refresh: its colored original is
        // still valid (derived from the unchanged source file), but the page no
        // longer counts as colorize-only terminal.
        val page = colorizeOnlyPage(ocrStatus = StageStatus.READY)

        page.isColorizeOnlyComplete shouldBe false
        page.displayImageName(colorizationEnabled = true) shouldBe COLORED_NAME
    }

    @Test
    fun `colorize-only predicate rejects failed or unpublished coloring`() {
        colorizeOnlyPage(coloringStatus = StageStatus.FAILED).isColorizeOnlyComplete shouldBe false
        colorizeOnlyPage(coloredImageName = null).isColorizeOnlyComplete shouldBe false
        colorizeOnlyPage(coloredFromOriginal = false).isColorizeOnlyComplete shouldBe false
    }

    @Test
    fun `resetColoring clears the colorize-only marker`() {
        val page = colorizeOnlyPage()

        page.resetColoring()

        page.coloredFromOriginal shouldBe false
        page.isColorizeOnlyComplete shouldBe false
    }

    @Test
    fun `colorize-only page projects as terminal display-ready`() {
        val page = colorizeOnlyPage()

        val projection = page.toPageDisplayProjection()
        projection.state shouldBe PageDisplayState.COLORIZE_ONLY_COMPLETE
        projection.displayReady shouldBe true
        projection.processed shouldBe true
        projection.isColorizeOnly shouldBe true
        projection.isTextless shouldBe false

        page.lifecycle shouldBe PageLifecycle.Done
        page.hasRenderedResult shouldBe true
    }

    @Test
    fun `translated page projection is unchanged by the colorize-only branch`() {
        val page = translatedPage()

        val projection = page.toPageDisplayProjection()
        projection.state shouldBe PageDisplayState.DISPLAY_READY
        projection.displayReady shouldBe true
        projection.isColorizeOnly shouldBe false
    }

    private fun colorizeOnlyPage(
        coloringStatus: String = StageStatus.READY,
        coloredImageName: String? = COLORED_NAME,
        coloredFromOriginal: Boolean = true,
        ocrStatus: String = StageStatus.SKIPPED,
    ) = PageTranslation(
        coloredImageName = coloredImageName,
        coloredFromOriginal = coloredFromOriginal,
        ocrStatus = ocrStatus,
        translationStatus = StageStatus.SKIPPED,
        inpaintStatus = StageStatus.SKIPPED,
        originalImageFallback = true,
        coloringStatus = coloringStatus,
    )

    private fun cleanedColoredPage() = PageTranslation(
        blocks = mutableListOf(translatedBlock()),
        cleanedImageName = CLEANED_NAME,
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        coloringStatus = StageStatus.READY,
        coloredImageName = COLORED_NAME,
    )

    private fun translatedPage() = PageTranslation(
        blocks = mutableListOf(translatedBlock()),
        cleanedImageName = CLEANED_NAME,
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
    )

    private fun translatedBlock() = TranslationBlock(
        text = "source",
        translation = "translated",
        width = 10f,
        height = 10f,
        x = 1f,
        y = 1f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )

    private companion object {
        const val CLEANED_NAME = "page.cleaned.v1.jpg"
        const val COLORED_NAME = "page.colored.v1.jpg"
    }
}
