package eu.kanade.translation.model

enum class PageStage {
    Ocr,
    Translation,
    Inpaint,
    Render,
}

sealed interface PageLifecycle {
    data object Pending : PageLifecycle
    data class Running(val stage: PageStage) : PageLifecycle
    data object NeedsRender : PageLifecycle
    data object Done : PageLifecycle
    data object Textless : PageLifecycle
    data object Cancelled : PageLifecycle
    data class Failed(val stage: PageStage, val retryCount: Int, val reason: String?) : PageLifecycle
}

val PageTranslation.displayImageName: String?
    get() = when {
        blocks.isEmpty() -> renderedImageName ?: cleanedImageName
        hasCurrentInpaintResult -> renderedImageName
        else -> null
    }

val PageTranslation.hasCurrentInpaintResult: Boolean
    get() = blocks.isEmpty() || inpaintRevision >= PageTranslation.CURRENT_INPAINT_REVISION

val PageTranslation.hasRenderedResult: Boolean
    get() = displayImageName != null

val PageTranslation.isStageRunning: Boolean
    get() = ocrStatus == StageStatus.RUNNING ||
        translationStatus == StageStatus.RUNNING ||
        inpaintStatus == StageStatus.RUNNING ||
        renderStatus == StageStatus.RUNNING

val PageTranslation.isStageCancelled: Boolean
    get() = ocrStatus == StageStatus.CANCELLED ||
        translationStatus == StageStatus.CANCELLED ||
        inpaintStatus == StageStatus.CANCELLED ||
        renderStatus == StageStatus.CANCELLED

val PageTranslation.isStageFailed: Boolean
    get() = ocrStatus == StageStatus.FAILED ||
        translationStatus == StageStatus.FAILED ||
        inpaintStatus == StageStatus.FAILED ||
        renderStatus == StageStatus.FAILED

/**
 * Flip every non-terminal RUNNING/PENDING stage to CANCELLED in place, used to
 * clear a page stranded in-flight after a cancellation (chapter switch, reader
 * exit, per-page cancel). Centralises the per-stage rewrite that was previously
 * copy-pasted in the scheduler's [markPageCancelled] / [markPageAutoSoftSkipped].
 * A cancel is NOT a failure, so retryCount is left untouched.
 */
fun PageTranslation.cancelInFlightStages() {
    apply {
        if (ocrStatus == StageStatus.RUNNING || ocrStatus == StageStatus.PENDING) ocrStatus = StageStatus.CANCELLED
        if (translationStatus == StageStatus.RUNNING) translationStatus = StageStatus.CANCELLED
        if (inpaintStatus == StageStatus.RUNNING) inpaintStatus = StageStatus.CANCELLED
        if (renderStatus == StageStatus.RUNNING) renderStatus = StageStatus.CANCELLED
    }
}

val PageTranslation.isTextlessTerminal: Boolean
    get() = ocrStatus == StageStatus.READY &&
        blocks.isEmpty() &&
        inpaintStatus != StageStatus.PENDING &&
        inpaintStatus != StageStatus.RUNNING

val PageTranslation.hasRecognizedTranslation: Boolean
    get() = ocrStatus == StageStatus.READY &&
        translationStatus == StageStatus.READY &&
        blocks.isNotEmpty()

val PageTranslation.hasExhaustedRetries: Boolean
    get() = isStageFailed && retryCount >= StageStatus.MAX_STAGE_RETRIES

val PageTranslation.shouldSkipAutoScheduling: Boolean
    get() = hasRenderedResult ||
        isStageRunning ||
        hasExhaustedRetries ||
        isTextlessTerminal

val PageTranslation.lifecycle: PageLifecycle
    get() = when {
        hasRenderedResult -> PageLifecycle.Done
        isTextlessTerminal -> PageLifecycle.Textless
        isStageCancelled -> PageLifecycle.Cancelled
        renderStatus == StageStatus.FAILED -> PageLifecycle.Failed(PageStage.Render, retryCount, errorMessage)
        inpaintStatus == StageStatus.FAILED -> PageLifecycle.Failed(PageStage.Inpaint, retryCount, errorMessage)
        translationStatus == StageStatus.FAILED -> PageLifecycle.Failed(PageStage.Translation, retryCount, errorMessage)
        ocrStatus == StageStatus.FAILED -> PageLifecycle.Failed(PageStage.Ocr, retryCount, errorMessage)
        renderStatus == StageStatus.RUNNING -> PageLifecycle.Running(PageStage.Render)
        inpaintStatus == StageStatus.RUNNING -> PageLifecycle.Running(PageStage.Inpaint)
        translationStatus == StageStatus.RUNNING -> PageLifecycle.Running(PageStage.Translation)
        ocrStatus == StageStatus.RUNNING -> PageLifecycle.Running(PageStage.Ocr)
        hasRecognizedTranslation -> PageLifecycle.NeedsRender
        else -> PageLifecycle.Pending
    }
