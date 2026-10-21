package eu.kanade.tachiyomi.ui.reader

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.SinglePageOutcome
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.processing.TranslationStageSignal
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * TachiyomiAT: reader translation feedback projection for the status pill and
 * the adaptive translate button. The stage vocabulary is FIXED — these tests
 * pin it so no invented stage (e.g. a fake "rendering") can slip in.
 */
class TranslationUiPresentationTest {

    @Test
    fun `live stage signals win over everything and map onto the real pipeline`() {
        val view = PageTranslation().apply { ocrStatus = StageStatus.PENDING }
        buildTranslationPillUi(TranslationStageSignal.TRANSLATING, SinglePageOutcome.Admitted, view) shouldBe
            TranslationPillUi.Running(TranslationPillStage.TRANSLATING)
    }

    @Test
    fun `durable running view falls back onto the pill when no in-memory signal exists`() {
        val view = PageTranslation().apply {
            ocrStatus = StageStatus.RUNNING
        }
        buildTranslationPillUi(null, null, view) shouldBe
            TranslationPillUi.Running(TranslationPillStage.READING_TEXT)
    }

    @Test
    fun `durable translation running view maps to translating`() {
        val view = PageTranslation().apply {
            ocrStatus = StageStatus.READY
            translationStatus = StageStatus.RUNNING
        }
        buildTranslationPillUi(null, null, view) shouldBe
            TranslationPillUi.Running(TranslationPillStage.TRANSLATING)
    }

    @Test
    fun `completed outcome with no failing stage reads Ready`() {
        val view = PageTranslation().apply {
            ocrStatus = StageStatus.READY
            translationStatus = StageStatus.READY
            renderStatus = StageStatus.READY
        }
        buildTranslationPillUi(null, SinglePageOutcome.Completed, view) shouldBe TranslationPillUi.Ready
    }

    @Test
    fun `committed display result reads Ready even when the outcome reports a failure`() {
        // Committed truth wins over a stale in-memory outcome: the page shows a
        // cleaned image with translated blocks, so the pill must read Ready.
        val view = PageTranslation(
            blocks = mutableListOf(translatedBlock()),
            cleanedImageName = "p0_cleaned.webp",
            inpaintStatus = StageStatus.READY,
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
            translationStatus = StageStatus.READY,
            ocrStatus = StageStatus.READY,
        )
        buildTranslationPillUi(
            null,
            SinglePageOutcome.Failed("pg", "generation bump"),
            view,
        ) shouldBe TranslationPillUi.Ready
    }

    /** Minimal recognized block; display shape requires one non-blank translation. */
    private fun translatedBlock() = TranslationBlock(
        text = "source",
        translation = "target",
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )

    @Test
    fun `no committed result and a failed outcome reads Failed`() {
        buildTranslationPillUi(null, SinglePageOutcome.Failed("pg", "boom"), null) shouldBe
            TranslationPillUi.Failed
    }

    @Test
    fun `textless terminal page reads No text found`() {
        val view = PageTranslation().apply {
            ocrStatus = StageStatus.READY
            translationStatus = StageStatus.SKIPPED
            inpaintStatus = StageStatus.SKIPPED
        }
        buildTranslationPillUi(null, SinglePageOutcome.Completed, view) shouldBe TranslationPillUi.Textless
    }

    @Test
    fun `paused and stalled outcomes read as queued work`() {
        buildTranslationPillUi(null, SinglePageOutcome.Paused(nextEligibleRetryAtEpochMs = null), null) shouldBe
            TranslationPillUi.Running(TranslationPillStage.QUEUED)
        buildTranslationPillUi(null, SinglePageOutcome.Stalled("pg", 0L), null) shouldBe
            TranslationPillUi.Running(TranslationPillStage.QUEUED)
    }

    @Test
    fun `admitted outcome reads queued while the pipeline has not reported a stage yet`() {
        buildTranslationPillUi(null, SinglePageOutcome.Admitted, null) shouldBe
            TranslationPillUi.Running(TranslationPillStage.QUEUED)
    }

    @Test
    fun `forcing no result and no outcome stays idle`() {
        buildTranslationPillUi(null, null, null) shouldBe TranslationPillUi.Idle
    }

    @Test
    fun `terminal states flash only when observed as a transition`() {
        shouldFlashTerminal(TranslationPillUi.Running(TranslationPillStage.FINISHING), TranslationPillUi.Ready) shouldBe true
        shouldFlashTerminal(TranslationPillUi.Ready, TranslationPillUi.Ready) shouldBe false
        // Subscribing on a rebind must NOT replay an old terminal flash.
        shouldFlashTerminal(null, TranslationPillUi.Ready) shouldBe false
        shouldFlashTerminal(null, TranslationPillUi.Failed) shouldBe false
        // A retry that finally succeeded may flash Ready over the old failure.
        shouldFlashTerminal(TranslationPillUi.Failed, TranslationPillUi.Ready) shouldBe true
    }

    @Test
    fun `adaptive button ghosts only while controls are hidden and page is idle`() {
        shouldGhostTranslateButton(TranslateButtonMode.ADAPTIVE, menuVisible = false, translating = false) shouldBe true
        shouldGhostTranslateButton(TranslateButtonMode.ADAPTIVE, menuVisible = true, translating = false) shouldBe false
        shouldGhostTranslateButton(TranslateButtonMode.ADAPTIVE, menuVisible = false, translating = true) shouldBe false
        shouldGhostTranslateButton(TranslateButtonMode.WITH_CONTROLS, menuVisible = false, translating = false) shouldBe false
    }

    @Test
    fun `manual tap on a never-translated page starts normally`() {
        resolveTranslatePageAction(null) shouldBe TranslatePageAction.TRANSLATE
        resolveTranslatePageAction(PageTranslation()) shouldBe TranslatePageAction.TRANSLATE
    }

    @Test
    fun `manual tap on a fully processed page asks for retranslation`() {
        val done = PageTranslation(
            blocks = mutableListOf(translatedBlock()),
            cleanedImageName = "p0_cleaned.webp",
            inpaintStatus = StageStatus.READY,
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
            translationStatus = StageStatus.READY,
            ocrStatus = StageStatus.READY,
        )
        val textless = PageTranslation().apply {
            ocrStatus = StageStatus.READY
            translationStatus = StageStatus.SKIPPED
            inpaintStatus = StageStatus.SKIPPED
        }
        resolveTranslatePageAction(done) shouldBe TranslatePageAction.RETRANSLATE
        resolveTranslatePageAction(textless) shouldBe TranslatePageAction.RETRANSLATE
    }

    @Test
    fun `manual tap while a stage runs is ignored`() {
        val running = PageTranslation().apply { ocrStatus = StageStatus.RUNNING }
        resolveTranslatePageAction(running) shouldBe TranslatePageAction.IGNORE
        val translating = PageTranslation().apply { translationStatus = StageStatus.RUNNING }
        resolveTranslatePageAction(translating) shouldBe TranslatePageAction.IGNORE
    }

    @Test
    fun `manual tap on failed cancelled or partial pages keeps the start path`() {
        val failed = PageTranslation().apply {
            ocrStatus = StageStatus.READY
            translationStatus = StageStatus.FAILED
        }
        val cancelled = PageTranslation().apply { ocrStatus = StageStatus.CANCELLED }
        resolveTranslatePageAction(failed) shouldBe TranslatePageAction.TRANSLATE
        resolveTranslatePageAction(cancelled) shouldBe TranslatePageAction.TRANSLATE
    }
}
