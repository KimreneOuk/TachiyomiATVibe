package eu.kanade.translation.batch

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageCount
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isStageFailed
import eu.kanade.translation.model.isTextlessTerminal
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * B2 regression coverage: the batch progress sheet's old single "Render" row
 * counted renderStatus == READY as rendered, but READY is set after color
 * estimation only. The reader's actual gate is hasRenderedResult (cleaned file
 * present + translation displayable + renderStatus READY + translated blocks),
 * so a color-estimated page with a missing cleaned image showed as "rendered"
 * in the indicator while the reader displayed the original + a grayed overlay.
 *
 * The fix splits RENDER (color) from DISPLAY (reader-viewable). DISPLAY counts
 * derive from hasRenderedResult directly, not from renderStatus. These tests
 * pin the count() contract for both phases.
 */
class DisplayReadyStageCountTest {

    private fun countPages(pages: List<PageTranslation>, phase: BatchPhase): StageCount {
        // Mirror TranslationBatchProgressTracker.count() without standing up the
        // full tracker harness. The tracker calls the same logic via
        // BatchPhase.entries.associateWith { count(pageMap.values, it) }.
        if (phase == BatchPhase.DISPLAY) {
            val succeeded = pages.count { it.hasRenderedResult }
            val failed = pages.count { it.isStageFailed && !it.hasRenderedResult }
            val skipped = pages.count { it.isTextlessTerminal }
            return StageCount(succeeded, failed, skipped, pages.size)
        }
        val statuses = pages.map {
            when (phase) {
                BatchPhase.OCR -> it.ocrStatus
                BatchPhase.TRANSLATE -> it.translationStatus
                BatchPhase.INPAINT -> it.inpaintStatus
                BatchPhase.RENDER -> it.renderStatus
                BatchPhase.DISPLAY -> it.renderStatus
            }
        }
        return StageCount(
            statuses.count { it == StageStatus.READY || it == StageStatus.PARTIAL },
            statuses.count { it == StageStatus.FAILED },
            statuses.count { it == StageStatus.SKIPPED },
            statuses.size,
        )
    }

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
        val pages = listOf(page)

        // RENDER (color estimation) is done.
        countPages(pages, BatchPhase.RENDER).succeeded shouldBe 1
        // DISPLAY (reader gate) is NOT satisfied: no cleaned image + no translated blocks.
        countPages(pages, BatchPhase.DISPLAY).succeeded shouldBe 0
    }

    @Test
    fun `page with cleaned image and translated block counts as DISPLAY succeeded`() {
        val block = eu.kanade.translation.model.TranslationBlock(
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
        val page = PageTranslation(
            renderStatus = StageStatus.READY,
            translationStatus = StageStatus.READY,
            ocrStatus = StageStatus.READY,
            inpaintStatus = StageStatus.READY,
            cleanedImageName = "p0.cleaned.png",
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
            blocks = mutableListOf(block),
        )
        val pages = listOf(page)

        countPages(pages, BatchPhase.RENDER).succeeded shouldBe 1
        countPages(pages, BatchPhase.DISPLAY).succeeded shouldBe 1
    }

    @Test
    fun `failed page without rendered result counts as DISPLAY failed`() {
        val page = PageTranslation(
            ocrStatus = StageStatus.FAILED,
            ocrError = "decode failed",
        )
        val pages = listOf(page)

        countPages(pages, BatchPhase.DISPLAY).failed shouldBe 1
        countPages(pages, BatchPhase.DISPLAY).succeeded shouldBe 0
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
        val pages = listOf(page)

        countPages(pages, BatchPhase.DISPLAY).skipped shouldBe 1
        countPages(pages, BatchPhase.DISPLAY).succeeded shouldBe 0
    }
}
