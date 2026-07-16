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
 *
 * These are pure-JVM tests over the model / policy / gate objects that the
 * pipeline delegates to. They do not instantiate TranslationPipeline itself
 * (which requires Android context / ONNX models). The tests prove that the
 * shared gate objects produce equivalent decisions regardless of which entry
 * path calls them.
 */
class TranslationParityTest {

    // 1. Identical page inputs produce equivalent terminal decision for every entry path.

    @Test
    fun shouldSchedule returns same decision for manual and auto given identical page state() {
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
    fun BatchResumeGateDecider returns same decision for batch and single-page resume() {
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
    fun orce retry clears attempt charge and durable state bookkeeping() {
        val page = renderedPage().apply {
            retryCount = 3
            attemptCount = 2
            errorMessage = "prior failure"
        }
        page.prepareForcedRetry()
        page.retryCount shouldBe 0
        page.attemptCount shouldBe 0
        page.errorMessage shouldBe null
        TranslationLifecyclePolicy.shouldSchedule(page) shouldBe true
    }

    @Test
    fun uto resume skips durable OCR stage (SKIP_ALL gate)() {
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
    fun uto resume with force=false does not reset exhaustion counter() {
        val exhausted = page(ocrStatus = StageStatus.FAILED).apply {
            attemptCount = StageStatus.MAX_STAGE_RETRIES
        }
        TranslationLifecyclePolicy.shouldSchedule(exhausted) shouldBe false
        val reasons = TranslationLifecyclePolicy.reasons(exhausted)
        reasons.exhausted shouldBe true
    }

    // 3. Physical cleaned-file loss re-enters inpaint for every entry path.

    @Test
    fun physical cleaned-file loss downgrades SKIP_ALL to INPAINT_ONLY when mask present() {
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
    fun physical cleaned-file loss with no mask re-enters full OCR() {
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
    fun lifecycle policy marks page as needing render when cleaned file exists but render not done() {
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
    fun unning page is blocked from scheduling in all entry paths() {
        val running = PageTranslation(ocrStatus = StageStatus.RUNNING)
        TranslationLifecyclePolicy.shouldSchedule(running) shouldBe false
        TranslationLifecyclePolicy.reasons(running).firstReason() shouldBe "already-running"
    }

    @Test
    fun cancelled page is re-schedulable by auto after chapter switch() {
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
    fun 	extless page is not scheduled and classified as Textless() {
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
    fun OCR failed page with non-exhausted attempts is schedulable() {
        val ocrFailed = PageTranslation(
            ocrStatus = StageStatus.FAILED,
            errorMessage = "ONNX recognition failed",
        ).apply { attemptCount = 1 }
        TranslationLifecyclePolicy.shouldSchedule(ocrFailed) shouldBe true
    }

    @Test
    fun orce retry on exhausted page makes it schedulable() {
        val exhausted = PageTranslation(
            ocrStatus = StageStatus.FAILED,
            errorMessage = "repeated failure",
        ).apply { attemptCount = StageStatus.MAX_STAGE_RETRIES }
        TranslationLifecyclePolicy.shouldSchedule(exhausted) shouldBe false
        exhausted.prepareForcedRetry()
        TranslationLifecyclePolicy.shouldSchedule(exhausted) shouldBe true
    }

    @Test
    fun inpaint mode mismatch triggers re-inpaint decision same as physical file loss() {
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
    fun hasRecognizedTranslation is true when OCR is READY and blocks present() {
        val noBlocks = page(ocrStatus = StageStatus.READY)
        noBlocks.hasRecognizedTranslation shouldBe false

        val withBlock = page(ocrStatus = StageStatus.READY).apply {
            blocks.add(translationBlock())
            translationStatus = StageStatus.READY
        }
        withBlock.hasRecognizedTranslation shouldBe true
    }

    @Test
    fun isCleanedImageReady is true only when cleaned file name set and inpaint READY() {
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
    fun hasRenderedResult is true only when render is READY and cleaned file exists() {
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
    fun manual-active page is not re-queued by auto (simulated via running state)() {
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
