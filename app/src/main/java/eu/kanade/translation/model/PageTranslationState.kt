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
    data object Done : PageLifecycle
    data object Textless : PageLifecycle
    data class Failed(val stage: PageStage, val retryCount: Int, val reason: String?) : PageLifecycle
}

val PageTranslation.hasRenderedResult: Boolean
    get() = renderedImageName != null || cleanedImageName != null

val PageTranslation.isStageRunning: Boolean
    get() = ocrStatus == StageStatus.RUNNING ||
        translationStatus == StageStatus.RUNNING ||
        inpaintStatus == StageStatus.RUNNING ||
        renderStatus == StageStatus.RUNNING

val PageTranslation.isStageFailed: Boolean
    get() = ocrStatus == StageStatus.FAILED ||
        translationStatus == StageStatus.FAILED ||
        inpaintStatus == StageStatus.FAILED ||
        renderStatus == StageStatus.FAILED

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
        isTextlessTerminal ||
        hasRecognizedTranslation

val PageTranslation.shouldSkipSequentialScheduling: Boolean
    get() = hasRenderedResult || hasRecognizedTranslation

val PageTranslation.lifecycle: PageLifecycle
    get() = when {
        hasRenderedResult -> PageLifecycle.Done
        isTextlessTerminal -> PageLifecycle.Textless
        renderStatus == StageStatus.FAILED -> PageLifecycle.Failed(PageStage.Render, retryCount, errorMessage)
        inpaintStatus == StageStatus.FAILED -> PageLifecycle.Failed(PageStage.Inpaint, retryCount, errorMessage)
        translationStatus == StageStatus.FAILED -> PageLifecycle.Failed(PageStage.Translation, retryCount, errorMessage)
        ocrStatus == StageStatus.FAILED -> PageLifecycle.Failed(PageStage.Ocr, retryCount, errorMessage)
        renderStatus == StageStatus.RUNNING -> PageLifecycle.Running(PageStage.Render)
        inpaintStatus == StageStatus.RUNNING -> PageLifecycle.Running(PageStage.Inpaint)
        translationStatus == StageStatus.RUNNING -> PageLifecycle.Running(PageStage.Translation)
        ocrStatus == StageStatus.RUNNING -> PageLifecycle.Running(PageStage.Ocr)
        else -> PageLifecycle.Pending
    }
