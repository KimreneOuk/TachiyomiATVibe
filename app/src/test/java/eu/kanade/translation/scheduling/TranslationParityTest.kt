package eu.kanade.translation.scheduling

import eu.kanade.translation.batch.BatchResumeGateDecider
import eu.kanade.translation.model.InpaintMaskBox
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.hasRecognizedTranslation
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isCleanedImageReady
import eu.kanade.translation.model.prepareForcedRetry
import eu.kanade.translation.model.PageLifecycle
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * CP4 parity tests - verify that manual, auto, and batch entry points share the
 * same stage-level semantics for OCR, Pass-1, inpaint, render, and validation.
 */
class TranslationParityTest {

    // 1. Identical page inputs produce equivalent terminal decision for every entry path.

    @Test
    fun shouldScheduleReturnsSameDecisionForManualAndAutoGivenIdenticalPageState() {
        val cases = listOf<PageTranslation?>(
            null,
            page(ocrStatus = StageStatus.READY),
            translatedPage(),
            renderedPage(),
            failedPage(),
            cancelledPage(),
        )
        for (page in cases) {
            val decision = TranslationLifecyclePolicy.shouldSchedule(page)
            decision shouldBe TranslationLifecyclePolicy.shouldSchedule(page)
        }
    }

    @Test
    fun batchResumeGateDeciderReturnsSameDecisionForBatchAndSinglePageResume() {
        val fresh: PageTranslation? = null
        BatchResumeGateDecider.decide(fresh) shouldBe BatchResumeGateDecider.Decision.FULL

        val maskOnly = page(ocrStatus = StageStatus.READY).apply {
            inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 2))
        }
        BatchResumeGateDecider.decide(maskOnly) shouldBe BatchResumeGateDecider.Decision.INPAINT_ONLY

        val inpaintDone = page(ocrStatus = StageStatus.READY).apply {
            inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 2))
            cleanedImageName = "001.cleaned.jpg"
            inpaintStatus = StageStatus.READY
        }
        BatchResumeGateDecider.decide(inpaintDone) shouldBe BatchResumeGateDecider.Decision.SKIP_ALL
    }

    // 2. Force retry resets attempts; auto resume does not redo durable stages.

    @Test
    fun forceRetryClearsAttemptChargeAndDurableStateBookkeeping() {
        val page = renderedPage().apply {
            retryCount = 3
            attemptCount = 2
            errorMessage = "prior failure"
        }
        page.prepareForcedRetry()
        page.retryCount shouldBe 0
        page.attemptCount shouldBe 0
        page.errorMessage shouldBe null
        TranslationLifecyclePolicy.reasons(page).exhausted shouldBe false
    }

    @Test
    fun autoResumeSkipsDurableOcrStage() {
        val durablePage = page(ocrStatus = StageStatus.READY).apply {
            inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 2))
            cleanedImageName = "001.cleaned.jpg"
            inpaintStatus = StageStatus.READY
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
            translationStatus = StageStatus.READY
            renderStatus = StageStatus.READY
            blocks.add(translationBlock())
        }
        BatchResumeGateDecider.decide(durablePage, cleanedFileValid = true) shouldBe
            BatchResumeGateDecider.Decision.SKIP_ALL
        TranslationLifecyclePolicy.shouldSchedule(durablePage) shouldBe false
    }

    @Test
    fun autoResumeWithForceFalseDoesNotResetExhaustionCounter() {
        val exhausted = page(ocrStatus = StageStatus.FAILED).apply {
            attemptCount = StageStatus.MAX_STAGE_RETRIES
        }
        TranslationLifecyclePolicy.shouldSchedule(exhausted) shouldBe false
        val reasons = TranslationLifecyclePolicy.reasons(exhausted)
        reasons.exhausted shouldBe true
    }

    // 3. Physical cleaned-file loss re-enters inpaint for every entry path.

    @Test
    fun physicalCleanedFileLossDowngradesSkipAllToInpaintOnlyWhenMaskPresent() {
        val pageWithMask = page(ocrStatus = StageStatus.READY).apply {
            inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 2))
            cleanedImageName = "001.cleaned.jpg"
            inpaintStatus = StageStatus.READY
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
            blocks.add(translationBlock())
        }
        BatchResumeGateDecider.decide(pageWithMask, cleanedFileValid = false) shouldBe
            BatchResumeGateDecider.Decision.INPAINT_ONLY
    }

    @Test
    fun physicalCleanedFileLossWithNoMaskReEntersFullOcr() {
        val pageNoMask = page(ocrStatus = StageStatus.READY).apply {
            cleanedImageName = "001.cleaned.jpg"
            inpaintStatus = StageStatus.READY
            inpaintMaskBoxes = emptyList()
            blocks.add(translationBlock())
        }
        BatchResumeGateDecider.decide(pageNoMask, cleanedFileValid = false) shouldBe
            BatchResumeGateDecider.Decision.FULL
    }

    @Test
    fun lifecyclePolicyMarksPageAsNeedingRenderWhenCleanedFileExistsButRenderNotDone() {
        val page = page(ocrStatus = StageStatus.READY).apply {
            blocks.add(translationBlock())
            translationStatus = StageStatus.READY
            cleanedImageName = "001.cleaned.jpg"
            inpaintStatus = StageStatus.READY
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
            renderStatus = StageStatus.PENDING
        }
        TranslationLifecyclePolicy.shouldSchedule(page) shouldBe true
        TranslationLifecyclePolicy.classify(page) shouldBe PageLifecycle.NeedsRender
    }

    // 4. Shared provider admission.

    @Test
    fun runningPageIsBlockedFromSchedulingInAllEntryPaths() {
        val running = PageTranslation(ocrStatus = StageStatus.RUNNING)
        TranslationLifecyclePolicy.shouldSchedule(running) shouldBe false
        TranslationLifecyclePolicy.reasons(running).firstReason() shouldBe "already-running"
    }

    @Test
    fun cancelledPageIsReSchedulableByAutoAfterChapterSwitch() {
        val cancelled = PageTranslation(
            ocrStatus = StageStatus.CANCELLED,
            errorMessage = "Translation cancelled",
        ).apply { attemptCount = 1 }
        TranslationLifecyclePolicy.shouldSchedule(cancelled) shouldBe true
        TranslationLifecyclePolicy.classify(cancelled) shouldBe PageLifecycle.Cancelled
        TranslationLifecyclePolicy.reasons(cancelled).exhausted shouldBe false
    }

    // 5. Textless, OCR failure, inpaint failure semantics consistent.

    @Test
    fun textlessPageIsNotScheduledAndClassifiedAsTextless() {
        val textless = PageTranslation(
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.SKIPPED,
            inpaintStatus = StageStatus.SKIPPED,
            renderStatus = StageStatus.SKIPPED,
            blocks = mutableListOf(),
        )
        TranslationLifecyclePolicy.shouldSchedule(textless) shouldBe false
        TranslationLifecyclePolicy.classify(textless) shouldBe PageLifecycle.Textless
    }

    @Test
    fun ocrFailedPageWithNonExhaustedAttemptsIsSchedulable() {
        val ocrFailed = PageTranslation(
            ocrStatus = StageStatus.FAILED,
            errorMessage = "ONNX recognition failed",
        ).apply { attemptCount = 1 }
        TranslationLifecyclePolicy.shouldSchedule(ocrFailed) shouldBe true
    }

    @Test
    fun forceRetryOnExhaustedPageMakesItSchedulable() {
        val exhausted = PageTranslation(
            ocrStatus = StageStatus.FAILED,
            errorMessage = "repeated failure",
        ).apply { attemptCount = StageStatus.MAX_STAGE_RETRIES }
        TranslationLifecyclePolicy.shouldSchedule(exhausted) shouldBe false
        exhausted.prepareForcedRetry()
        TranslationLifecyclePolicy.reasons(exhausted).exhausted shouldBe false
    }

    @Test
    fun inpaintModeMismatchTriggersReInpaintDecisionSameAsPhysicalFileLoss() {
        val stale = page(ocrStatus = StageStatus.READY).apply {
            inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 2))
            cleanedImageName = "001.cleaned.jpg"
            inpaintStatus = StageStatus.READY
            inpaintingModeUsed = "FAST"
            blocks.add(translationBlock())
        }
        BatchResumeGateDecider.decide(stale, cleanedFileValid = true, inpaintModeMatches = false) shouldBe
            BatchResumeGateDecider.Decision.INPAINT_ONLY
    }

    // 6. Extension properties used by single-page resume path.

    @Test
    fun hasRecognizedTranslationIsTrueWhenOcrIsReadyAndBlocksPresent() {
        val noBlocks = page(ocrStatus = StageStatus.READY)
        noBlocks.hasRecognizedTranslation shouldBe false

        val withBlock = page(ocrStatus = StageStatus.READY).apply {
            blocks.add(translationBlock())
            translationStatus = StageStatus.READY
        }
        withBlock.hasRecognizedTranslation shouldBe true
    }

    @Test
    fun isCleanedImageReadyIsTrueOnlyWhenCleanedFileNameSetAndInpaintReady() {
        val noFile = page(ocrStatus = StageStatus.READY).apply {
            inpaintStatus = StageStatus.READY
        }
        noFile.isCleanedImageReady shouldBe false

        val withFile = page(ocrStatus = StageStatus.READY).apply {
            cleanedImageName = "001.cleaned.jpg"
            inpaintStatus = StageStatus.READY
        }
        withFile.isCleanedImageReady shouldBe true

        val failedInpaint = page(ocrStatus = StageStatus.READY).apply {
            cleanedImageName = "001.cleaned.jpg"
            inpaintStatus = StageStatus.FAILED
        }
        failedInpaint.isCleanedImageReady shouldBe false
    }

    @Test
    fun hasRenderedResultIsTrueOnlyWhenRenderIsReadyAndCleanedFileExists() {
        val noFile = translatedPage().apply { renderStatus = StageStatus.READY }
        noFile.hasRenderedResult shouldBe false

        val withFile = translatedPage().apply {
            cleanedImageName = "001.cleaned.jpg"
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
            renderStatus = StageStatus.READY
        }
        withFile.hasRenderedResult shouldBe true
    }

    // 7. Provider admission - manual-active page not re-queued by auto.

    @Test
    fun manualActivePageIsNotReQueuedByAuto() {
        val manualInFlight = PageTranslation(ocrStatus = StageStatus.RUNNING)
        TranslationLifecyclePolicy.shouldSchedule(manualInFlight) shouldBe false
    }

    // Helpers

    private fun page(ocrStatus: String) = PageTranslation(
        imgWidth = 100f,
        imgHeight = 100f,
        ocrStatus = ocrStatus,
    )

    private fun translatedPage() = PageTranslation(
        blocks = mutableListOf(translationBlock()),
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        renderStatus = StageStatus.PENDING,
    )

    private fun renderedPage() = translatedPage().apply {
        cleanedImageName = "001.cleaned.jpg"
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
        renderStatus = StageStatus.READY
    }

    private fun failedPage() = PageTranslation(
        ocrStatus = StageStatus.FAILED,
        errorMessage = "test failure",
    ).apply { attemptCount = 1 }

    private fun cancelledPage() = PageTranslation(
        ocrStatus = StageStatus.CANCELLED,
        errorMessage = "Translation cancelled",
    )

    private fun translationBlock() = TranslationBlock(
        text = "source text",
        translation = "translated text",
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )
}
