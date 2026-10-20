package eu.kanade.translation.processing.execution

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.processing.finalizePostOcrStage
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Regression coverage for the prepared-page terminal predicate.
 *
 * These tests drive the PRODUCTION terminal predicate [isPreparedPageTerminal]
 * — the shared runner helper that classifies whether a page needs further
 * work. A mutation that flips a predicate condition fails the matching test.
 */
class PreparedPageRuntimeBoundaryTest {

    private fun block(text: String, id: String = text): TranslationBlock = TranslationBlock(
        blockId = id,
        text = text,
        width = 100f,
        height = 50f,
        x = 10f,
        y = 20f,
        symHeight = 40f,
        symWidth = 80f,
        angle = 0f,
    )

    /**
     * Drives the REAL production [isPreparedPageTerminal] predicate. A fresh
     * textless page (ocrStatus=READY, no blocks, renderStatus=SKIPPED) must be
     * terminal. If the predicate's condition flips, this test fails.
     */
    @Test
    fun `production terminal predicate classifies fresh textless pages as terminal`() {
        val textless = PageTranslation(sourceFileName = "p-tl").apply {
            ocrStatus = StageStatus.READY
            blocks = mutableListOf()
        }
        finalizePostOcrStage(textless, inpaintAlreadyRan = false)
        isPreparedPageTerminal(textless) shouldBe true
    }

    @Test
    fun `production terminal predicate rejects failed pages`() {
        val failed = PageTranslation(sourceFileName = "p-fail").apply {
            ocrStatus = StageStatus.READY
            inpaintStatus = StageStatus.FAILED
        }
        isPreparedPageTerminal(failed) shouldBe false
    }

    @Test
    fun `production terminal predicate classifies a fresh page with blocks as non-terminal`() {
        val fresh = PageTranslation(sourceFileName = "p0").apply {
            ocrStatus = StageStatus.READY
            inpaintStatus = StageStatus.READY
            renderStatus = StageStatus.PENDING
            blocks = mutableListOf(block("text"))
            cleanedImageName = "p0.cleaned.v1.jpg"
        }
        isPreparedPageTerminal(fresh) shouldBe false
    }
}
