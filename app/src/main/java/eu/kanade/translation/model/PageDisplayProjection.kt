package eu.kanade.translation.model

/**
 * The single readiness projection shared by batch progress and the reader.
 *
 * A candidate is never a display authority. When [candidate] differs from
 * [committed], the projection keeps the committed result visible and reports
 * the candidate lifecycle separately through [state].
 */
data class PageDisplayProjection(
    val state: PageDisplayState,
    val displayReady: Boolean,
    /** The current candidate attempt reached a terminal page outcome. */
    val processed: Boolean,
) {
    /** Alias used by action/readability callers. */
    val canReadTranslated: Boolean get() = displayReady

    /** A textless terminal page is processed but is not translated-ready. */
    val isTextless: Boolean get() = state == PageDisplayState.TEXTLESS_COMPLETE
}

/**
 * Project a live candidate against the reader-facing committed snapshot.
 * [committed] is normally the value from [ChapterTranslationStore.display].
 */
fun PageTranslation.toPageDisplayProjection(committed: PageTranslation? = null): PageDisplayProjection {
    if (committed == null) return singlePageProjection()

    val committedProjection = committed.singlePageProjection()
    val candidateChanged = differsFromCommitted(committed)
    val committedReady = committedProjection.displayReady
    val candidateFailed = isStageFailed
    val candidateRunning = isStageRunning
    val state =
        when {
            candidateChanged && candidateFailed && committedReady ->
                PageDisplayState.FAILED_WITH_COMMITTED_RESULT
            candidateChanged && committedReady -> PageDisplayState.REFRESHING_WITH_COMMITTED_RESULT
            committedReady -> PageDisplayState.DISPLAY_READY
            isTextlessTerminal -> PageDisplayState.TEXTLESS_COMPLETE
            isTranslationDisplayShapeReady() -> PageDisplayState.DISPLAY_READY
            candidateFailed -> PageDisplayState.FAILED_NO_RESULT
            candidateRunning -> PageDisplayState.CANDIDATE_RUNNING
            else -> PageDisplayState.ORIGINAL_ONLY
        }
    val displayReady = committedReady || (!candidateChanged && isTranslationDisplayShapeReady())

    return PageDisplayProjection(
        state = state,
        displayReady = displayReady,
        processed =
        state in
            setOf(
                PageDisplayState.DISPLAY_READY,
                PageDisplayState.FAILED_WITH_COMMITTED_RESULT,
                PageDisplayState.FAILED_NO_RESULT,
                PageDisplayState.TEXTLESS_COMPLETE,
            ),
    )
}

private fun PageTranslation.singlePageProjection(): PageDisplayProjection {
    val displayReady = isTranslationDisplayShapeReady()
    val state =
        when {
            isTextlessTerminal -> PageDisplayState.TEXTLESS_COMPLETE
            displayReady -> PageDisplayState.DISPLAY_READY
            isStageFailed -> PageDisplayState.FAILED_NO_RESULT
            isStageRunning -> PageDisplayState.CANDIDATE_RUNNING
            else -> PageDisplayState.ORIGINAL_ONLY
        }
    return PageDisplayProjection(
        state = state,
        displayReady = displayReady,
        processed =
        state in
            setOf(
                PageDisplayState.DISPLAY_READY,
                PageDisplayState.FAILED_NO_RESULT,
                PageDisplayState.TEXTLESS_COMPLETE,
            ),
    )
}

private fun PageTranslation.isTranslationDisplayShapeReady(): Boolean =
    (isCleanedImageReady || originalImageFallback) &&
        (translationStatus == StageStatus.READY || translationStatus == StageStatus.PARTIAL) &&
        renderStatus == StageStatus.READY &&
        blocks.any { it.translation.isNotBlank() }

private fun PageTranslation.differsFromCommitted(committed: PageTranslation): Boolean =
    pageVersion != committed.pageVersion ||
        updatedAt != committed.updatedAt ||
        ocrStatus != committed.ocrStatus ||
        translationStatus != committed.translationStatus ||
        inpaintStatus != committed.inpaintStatus ||
        renderStatus != committed.renderStatus ||
        cleanedImageName != committed.cleanedImageName ||
        originalImageFallback != committed.originalImageFallback ||
        translationOrigin != committed.translationOrigin ||
        blocks != committed.blocks
