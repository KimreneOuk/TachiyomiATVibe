package eu.kanade.translation.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Guards against the false "translated" regression: a chapter must count as
 * translated only when a page produced real output, not when a placeholder
 * entry was written by the stranded-page sweep or a failed/aborted attempt.
 *
 * [TranslationManager.isChapterTranslated] now evaluates
 * `pages.values.any { it.hasRenderedResult || it.hasRecognizedTranslation }`,
 * so these tests exercise that exact predicate against every entry shape the
 * store can persist — mirroring what the manager reads back from disk.
 */
class ChapterTranslatedPredicateTest {

    @Test
    fun `empty placeholder page is not translated`() {
        // The exact shape the stranded-page sweep writes on chapter open:
        // all CANCELLED stages, an explanatory errorMessage, no blocks, no image.
        val placeholder = PageTranslation(
            ocrStatus = StageStatus.CANCELLED,
            translationStatus = StageStatus.CANCELLED,
            inpaintStatus = StageStatus.CANCELLED,
            renderStatus = StageStatus.CANCELLED,
            errorMessage = "Page was stranded mid-translation; reset as cancelled on chapter reopen",
        )

        placeholder.hasRenderedResult shouldBe false
        placeholder.hasRecognizedTranslation shouldBe false
        listOf(placeholder).any { it.hasRenderedResult || it.hasRecognizedTranslation } shouldBe false
    }

    @Test
    fun `all-pending fresh page is not translated`() {
        val fresh = PageTranslation() // defaults: empty blocks, all PENDING

        fresh.hasRenderedResult shouldBe false
        fresh.hasRecognizedTranslation shouldBe false
        listOf(fresh).any { it.hasRenderedResult || it.hasRecognizedTranslation } shouldBe false
    }

    @Test
    fun `only-failed page is not translated`() {
        val failed = PageTranslation(
            ocrStatus = StageStatus.FAILED,
            errorMessage = "ONNX recognition failed",
            retryCount = StageStatus.MAX_STAGE_RETRIES,
        )

        failed.hasRenderedResult shouldBe false
        failed.hasRecognizedTranslation shouldBe false
        listOf(failed).any { it.hasRenderedResult || it.hasRecognizedTranslation } shouldBe false
    }

    @Test
    fun `running page is not translated`() {
        val running = PageTranslation(ocrStatus = StageStatus.RUNNING)

        running.hasRenderedResult shouldBe false
        running.hasRecognizedTranslation shouldBe false
        listOf(running).any { it.hasRenderedResult || it.hasRecognizedTranslation } shouldBe false
    }

    @Test
    fun `page with recognized text blocks counts as translated`() {
        // hasRecognizedTranslation: ocr+translation READY + non-empty blocks.
        val recognized = PageTranslation(
            blocks = mutableListOf(block()),
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.READY,
        )

        recognized.hasRecognizedTranslation shouldBe true
        listOf(recognized).any { it.hasRenderedResult || it.hasRecognizedTranslation } shouldBe true
    }

    @Test
    fun `page with PARTIAL translation counts as translated`() {
        // hasRecognizedTranslation admits PARTIAL too: a page that rendered some
        // blocks and left others blank still produced real output, so the
        // chapter-level "is anything translated" check must count it.
        val partial = PageTranslation(
            blocks = mutableListOf(block()),
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.PARTIAL,
        )

        partial.hasRecognizedTranslation shouldBe true
        listOf(partial).any { it.hasRenderedResult || it.hasRecognizedTranslation } shouldBe true
    }

    @Test
    fun `page with rendered image counts as translated`() {
        val rendered = PageTranslation(
            blocks = mutableListOf(block()),
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.READY,
            inpaintStatus = StageStatus.READY,
            renderStatus = StageStatus.READY,
            cleanedImageName = "001.cleaned.png",
            renderedImageName = "001.rendered.png",
            renderQuality = RenderQuality.FULL,
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        )

        rendered.hasRenderedResult shouldBe true
        listOf(rendered).any { it.hasRenderedResult || it.hasRecognizedTranslation } shouldBe true
    }

    @Test
    fun `chapter with mixed placeholder and real page counts as translated`() {
        val placeholder = PageTranslation(
            ocrStatus = StageStatus.CANCELLED,
            errorMessage = "stranded",
        )
        val recognized = PageTranslation(
            blocks = mutableListOf(block()),
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.READY,
        )

        // One real page among placeholders is enough.
        listOf(placeholder, recognized).any { it.hasRenderedResult || it.hasRecognizedTranslation } shouldBe true
    }

    @Test
    fun `chapter with only placeholders is not translated`() {
        val a = PageTranslation(ocrStatus = StageStatus.CANCELLED, errorMessage = "x")
        val b = PageTranslation(ocrStatus = StageStatus.PENDING)
        val c = PageTranslation(ocrStatus = StageStatus.FAILED, retryCount = StageStatus.MAX_STAGE_RETRIES)

        listOf(a, b, c).any { it.hasRenderedResult || it.hasRecognizedTranslation } shouldBe false
    }

    @Test
    fun `empty page list is not translated`() {
        emptyList<PageTranslation>().any { it.hasRenderedResult || it.hasRecognizedTranslation } shouldBe false
    }

    /** Minimal block satisfying the non-default TranslationBlock geometry args. */
    private fun block() = TranslationBlock(
        text = "源",
        translation = "source",
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )
}
