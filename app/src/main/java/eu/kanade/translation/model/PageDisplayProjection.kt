package eu.kanade.translation.model

import eu.kanade.translation.artifact.ArtifactOrigin
import eu.kanade.translation.artifact.ArtifactStageStatus
import eu.kanade.translation.artifact.PageArtifactRecord

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
    val batchContextComplete: Boolean,
) {
    /** Alias used by action/readability callers. */
    val canReadTranslated: Boolean get() = displayReady

    /** A textless terminal page is processed but is not translated-ready. */
    val isTextless: Boolean get() = state == PageDisplayState.TEXTLESS_COMPLETE

    companion object {
        fun from(page: PageTranslation): PageDisplayProjection = page.singlePageProjection()

        fun from(
            candidate: PageTranslation,
            committed: PageTranslation?,
        ): PageDisplayProjection = candidate.toPageDisplayProjection(committed)

        /**
         * Project the durable manifest record when a caller has no live-page
         * snapshot. The committed pointer and validated display base are both
         * required before a page can count as translated-ready.
         */
        fun from(record: PageArtifactRecord): PageDisplayProjection {
            val committed = record.committed
            val committedBaseValid = committed?.displayBase?.validated == true
            val committedDisplayState = record.displayState
            val committedStagesValid =
                record.inpaint?.status in
                    setOf(
                        ArtifactStageStatus.READY,
                        ArtifactStageStatus.SKIPPED,
                    ) &&
                    record.translation?.status in
                    setOf(
                        ArtifactStageStatus.READY,
                        ArtifactStageStatus.PARTIAL,
                    ) &&
                    record.layout?.status == ArtifactStageStatus.READY
            val hasCommittedDisplay =
                committed != null &&
                    committedBaseValid &&
                    (committedDisplayState != PageDisplayState.DISPLAY_READY || committedStagesValid) &&
                    committedDisplayState.hasCommittedDisplay
            val processed =
                committedDisplayState in
                    setOf(
                        PageDisplayState.DISPLAY_READY,
                        PageDisplayState.FAILED_WITH_COMMITTED_RESULT,
                        PageDisplayState.FAILED_NO_RESULT,
                        PageDisplayState.TEXTLESS_COMPLETE,
                    )
            val contextStagesComplete =
                record.displayState == PageDisplayState.TEXTLESS_COMPLETE ||
                    (
                        record.translation?.status in
                            setOf(
                                ArtifactStageStatus.READY,
                                ArtifactStageStatus.PARTIAL,
                            ) &&
                            record.layout?.status == ArtifactStageStatus.READY
                        )
            val batchContextComplete =
                committed != null &&
                    committed.origin != ArtifactOrigin.READER_ADHOC &&
                    record.contextCheckpointFileName != null &&
                    contextStagesComplete

            return PageDisplayProjection(
                state = committedDisplayState,
                displayReady = hasCommittedDisplay,
                processed = processed,
                batchContextComplete = batchContextComplete,
            )
        }
    }
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
    val contextPage = if (candidateChanged) this else committed
    val batchContextComplete =
        contextPage.batchContextComplete &&
            contextPage.translationOrigin != ArtifactOrigin.READER_ADHOC.name

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
        batchContextComplete = batchContextComplete,
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
        batchContextComplete =
        batchContextComplete &&
            translationOrigin != ArtifactOrigin.READER_ADHOC.name,
    )
}

private fun PageTranslation.isTranslationDisplayShapeReady(): Boolean =
    isCleanedImageReady &&
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
        translationOrigin != committed.translationOrigin ||
        batchContextComplete != committed.batchContextComplete ||
        blocks != committed.blocks
