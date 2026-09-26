package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.model.BatchPhase
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * B2 regression coverage: the batch progress sheet's old single "Render" row
 * counted renderStatus == READY as rendered, but READY is set after color
 * estimation only. The reader's actual gate is the shared display projection
 * (cleaned file present + translation displayable + renderStatus READY +
 * translated blocks), so a color-estimated page with a missing cleaned image
 * showed as "rendered" in the indicator while the reader displayed the
 * original + a grayed overlay.
 *
 * The fix splits RENDER (color) from DISPLAY (reader-viewable). These tests
 * drive the real production counter via
 * [TranslationBatchProgressTracker.computeSnapshot] — the same entry point the
 * tracker and progress sheet use — so the DISPLAY counts are asserted against
 * [PageDisplayProjection] semantics directly, including the committed-pointer
 * behavior that a live-page-only mirror cannot express.
 */
class DisplayReadyStageCountTest {

    @Test
    fun `color-estimated page with missing cleaned image counts as RENDER succeeded but not DISPLAY succeeded`() {
        // renderStatus READY (color estimated) but no cleanedImageName -> reader shows original.
        val page = PageTranslation(
            renderStatus = StageStatus.READY,
            translationStatus = StageStatus.READY,
            ocrStatus = StageStatus.READY,
            inpaintStatus = StageStatus.READY,
            blocks = mutableListOf(),
        )

        val counts = countsFor(page)

        // RENDER (color estimation) is done.
        counts.getValue(BatchPhase.RENDER).succeeded shouldBe 1
        // DISPLAY (reader gate) is NOT satisfied: no cleaned image + no translated blocks.
        counts.getValue(BatchPhase.DISPLAY).succeeded shouldBe 0
        counts.getValue(BatchPhase.DISPLAY).failed shouldBe 0
        counts.getValue(BatchPhase.DISPLAY).skipped shouldBe 0
    }

    @Test
    fun `page with cleaned image and translated block counts as DISPLAY succeeded`() {
        val page = displayablePage("p0")

        val counts = countsFor(page)

        counts.getValue(BatchPhase.RENDER).succeeded shouldBe 1
        counts.getValue(BatchPhase.DISPLAY).succeeded shouldBe 1
    }

    @Test
    fun `failed page without rendered result counts as DISPLAY failed`() {
        val page = PageTranslation(
            ocrStatus = StageStatus.FAILED,
            ocrError = "decode failed",
        )

        val counts = countsFor(page)

        counts.getValue(BatchPhase.DISPLAY).failed shouldBe 1
        counts.getValue(BatchPhase.DISPLAY).succeeded shouldBe 0
    }

    @Test
    fun `textless terminal page counts as DISPLAY skipped`() {
        val page = PageTranslation(
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.SKIPPED,
            renderStatus = StageStatus.SKIPPED,
            inpaintStatus = StageStatus.SKIPPED,
            blocks = mutableListOf(),
        )

        val counts = countsFor(page)

        counts.getValue(BatchPhase.DISPLAY).skipped shouldBe 1
        counts.getValue(BatchPhase.DISPLAY).succeeded shouldBe 0
    }

    @Test
    fun `regressed candidate with committed display still counts as DISPLAY succeeded`() {
        // The newest attempt failed, but the reader keeps showing the last
        // committed display bundle. DISPLAY must follow the committed pointer
        // (succeeded, not failed), while the native phases still report the
        // live candidate's stage states.
        val committed = displayablePage("p0")
        val candidate = PageTranslation(
            ocrStatus = StageStatus.FAILED,
            translationStatus = StageStatus.FAILED,
            inpaintStatus = StageStatus.PENDING,
            renderStatus = StageStatus.PENDING,
            blocks = mutableListOf(),
        )

        val counts = countsFor(candidate, displayPageMap = mapOf("p0" to committed))

        counts.getValue(BatchPhase.DISPLAY).succeeded shouldBe 1
        counts.getValue(BatchPhase.DISPLAY).failed shouldBe 0
        counts.getValue(BatchPhase.OCR).failed shouldBe 1
        counts.getValue(BatchPhase.RENDER).succeeded shouldBe 0
    }

    /** Runs the real production counter; no in-test mirror of `count()` is used. */
    private fun countsFor(
        page: PageTranslation,
        displayPageMap: Map<String, PageTranslation>? = null,
    ) = TranslationBatchProgressTracker.computeSnapshot(
        pageMap = mapOf("p0" to page),
        chapterState = Translation.State.TRANSLATING,
        displayPageMap = displayPageMap,
    ).perStage

    private fun displayablePage(key: String) = PageTranslation(
        sourceFileName = key,
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        renderStatus = StageStatus.READY,
        cleanedImageName = "$key.cleaned.jpg",
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
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
    )
}
