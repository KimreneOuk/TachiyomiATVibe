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
    get() = cleanedImageName?.takeIf { isTranslationDisplayReady }

val PageTranslation.hasCurrentInpaintResult: Boolean
    get() = blocks.isEmpty() || inpaintRevision >= PageTranslation.CURRENT_INPAINT_REVISION

/**
 * The cleaned bitmap is safe to show only after the inpaint stage has published
 * a current-revision file. A filename in the translation store is metadata, not
 * proof that the reader can safely open the file.
 */
val PageTranslation.isCleanedImageReady: Boolean
    get() = cleanedImageName != null &&
        inpaintStatus == StageStatus.READY &&
        hasCurrentInpaintResult

/**
 * Single display-readiness predicate for translated text. Pager and webtoon
 * must use this gate so a late translation/store emission can never draw text
 * over the original image while inpaint is still pending or failed.
 */
val PageTranslation.isTranslationDisplayReady: Boolean
    get() = toPageDisplayProjection().displayReady

val PageTranslation.shouldShowTranslationOverlay: Boolean
    get() = blocks.any { it.translation.isNotBlank() }

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
    get() = isTranslationDisplayReady

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
    attemptCount = 0
    attemptCharged = false
    ocrStatus = StageStatus.RUNNING
    translationStatus = StageStatus.PENDING
    inpaintStatus = StageStatus.PENDING
    renderStatus = StageStatus.PENDING
    errorMessage = null
    cleanedImageName = null
    originalImageFallback = false
}

/**
 * TachiyomiAT: record that this page-translation attempt ended in a terminal
 * failure. Increments [PageTranslation.attemptCount] AT MOST ONCE per attempt —
 * the FIRST terminal stage to call this owns the increment; subsequent stages
 * in the same attempt (e.g. a render failure cascading from an inpaint failure)
 * are no-ops. This is the fix for the "one transient inpaint failure
 * permanently blacklists the page" bug: previously each failed stage bumped
 * [PageTranslation.retryCount], and a single reader-path attempt could touch
 * inpaint THEN render, double-counting and tripping [hasExhaustedRetries]
 * (which used to key off retryCount) after a single transient failure.
 *
 * Idempotency is tracked via the [PageTranslation.attemptCharged] flag (set the
 * first time this charges the attempt, reset by [prepareForcedRetry] and at the
 * start of each fresh attempt in [TranslationPipeline]). Callers set their own
 * stage status to FAILED before OR after calling this — the guard does NOT
 * inspect stage status, because the stage is typically already FAILED by the
 * time this runs (so an `isStageFailed` guard would always no-op, which was a
 * bug in the first version of this helper). This helper owns ONLY the
 * exhaustion counter + the diagnostic [PageTranslation.retryCount].
 *
 * Pure (no Android/ONNX/Bitmap dependency) — unit-tested in
 * [PageTranslationStateTest].
 */
fun PageTranslation.recordAttemptFailure() {
    if (attemptCharged) return
    attemptCharged = true
    attemptCount++
    retryCount++
}

/**
 * TachiyomiAT: clears the per-attempt "charged" flag so the next terminal
 * failure in a NEW attempt is counted. Called by [TranslationPipeline] at the
 * start of each fresh page-translation attempt (after [prepareForcedRetry] on
 * the forced path, or implicitly on the resume path when stages are reset to
 * RUNNING/PENDING). Without this reset, a second attempt that fails would be
 * treated as already-charged and never increment [PageTranslation.attemptCount].
 */
fun PageTranslation.resetAttemptCharge() {
    attemptCharged = false
}

val PageTranslation.isTextlessTerminal: Boolean
    get() = ocrStatus == StageStatus.READY &&
        blocks.isEmpty() &&
        translationStatus == StageStatus.SKIPPED &&
        renderStatus == StageStatus.SKIPPED &&
        (inpaintStatus == StageStatus.SKIPPED || inpaintStatus == StageStatus.READY)

val PageTranslation.hasRecognizedTranslation: Boolean
    get() = ocrStatus == StageStatus.READY &&
        (translationStatus == StageStatus.READY || translationStatus == StageStatus.PARTIAL) &&
        blocks.isNotEmpty()

val PageTranslation.hasExhaustedRetries: Boolean
    get() = isStageFailed && attemptCount >= StageStatus.MAX_STAGE_RETRIES

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
    get() = !isCleanedImageReady &&
        !isTextlessTerminal &&
        !isStageCancelled &&
        isStageFailed
