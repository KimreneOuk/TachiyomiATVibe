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
        blocks.isEmpty() -> when {
            renderedImageName != null && hasTrustedRenderScale -> renderedImageName
            cleanedImageName != null && decodeSampleSize <= 1 -> cleanedImageName
            else -> null
        }
        hasCurrentInpaintResult && hasTrustedRenderScale -> renderedImageName
        else -> null
    }

val PageTranslation.hasTrustedRenderScale: Boolean
    get() = renderedImageName != null && (
        renderQuality == RenderQuality.FULL ||
            renderQuality == RenderQuality.SIZE_LIMITED ||
            (renderQuality == RenderQuality.UNKNOWN && decodeSampleSize <= 1)
        )

val PageTranslation.hasCurrentInpaintResult: Boolean
    get() = blocks.isEmpty() || inpaintRevision >= PageTranslation.CURRENT_INPAINT_REVISION

/**
 * TachiyomiAT: true when this page carries a persisted inpaint mask captured by
 * the current OCR logic, so a resumed batch can safely skip re-OCR and still
 * erase detector-only + watermark regions.
 *
 * Signal: [PageTranslation.inpaintMaskBoxes] is a new persisted field whose
 * default is the empty list, so any page OCR'd before this field existed (or
 * whose OCR was skipped under the old resume gate) deserializes with an empty
 * mask and is re-OCR'd to capture a complete one. A page OCR'd by current code
 * always has a non-empty mask (unless it is genuinely textless).
 *
 * A textless page (empty blocks) is considered to have a current mask by
 * definition — it has nothing to erase — so the stage-1 resume gate treats it
 * as done instead of re-OCR'ing it forever.
 *
 * This is INDEPENDENT of [hasCurrentInpaintResult], which tracks whether the
 * inpaint OUTPUT (cleaned image) matches the current revision. A page can have
 * a current mask but a stale cleaned image (OCR'd, interrupted before inpaint)
 * — that page skips re-OCR but still runs inpaint.
 */
val PageTranslation.hasCurrentInpaintMask: Boolean
    get() = blocks.isEmpty() || inpaintMaskBoxes.isNotEmpty()

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

fun PageTranslation.prepareForcedRetry() {
    retryCount = 0
    ocrStatus = StageStatus.RUNNING
    translationStatus = StageStatus.PENDING
    inpaintStatus = StageStatus.PENDING
    renderStatus = StageStatus.PENDING
    errorMessage = null
    renderedImageName = null
    cleanedImageName = null
    renderQuality = RenderQuality.UNKNOWN
    renderedWidth = 0
    renderedHeight = 0
}

val PageTranslation.isTextlessTerminal: Boolean
    get() = ocrStatus == StageStatus.READY &&
        blocks.isEmpty() &&
        inpaintStatus != StageStatus.PENDING &&
        inpaintStatus != StageStatus.RUNNING

val PageTranslation.hasRecognizedTranslation: Boolean
    get() = ocrStatus == StageStatus.READY &&
        (translationStatus == StageStatus.READY || translationStatus == StageStatus.PARTIAL) &&
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

/**
 * TachiyomiAT: true when this page's [errorMessage] should be shown as a red
 * error in the reader overlay.
 *
 * The view holders previously surfaced ANY non-null `errorMessage` once the
 * page was idle — but cancellation, the stranded-page sweep, and textless
 * terminal pages all write an explanatory `errorMessage` ("Translation
 * cancelled", "Page was stranded mid-translation...", PARTIAL's "N/M blocks
 * translated"). Those are NOT failures and must not be painted red over a
 * page that produced output or was deliberately stopped.
 *
 * Rule: the error surfaces only when the page reached a genuine terminal
 * [PageLifecycle.Failed] state (a stage FAILED, no rendered/cleaned result to
 * show instead). PARTIAL, Textless, Cancelled, Done, and any in-flight state
 * all suppress the error — the user sees either the translated image or
 * nothing, never a stale/misleading red message.
 */
val PageTranslation.shouldSurfaceError: Boolean
    get() = !hasRenderedResult &&
        !isTextlessTerminal &&
        !isStageCancelled &&
        isStageFailed
