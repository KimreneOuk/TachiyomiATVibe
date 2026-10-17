package eu.kanade.translation.persistence.artifact

import eu.kanade.translation.model.PageDisplayProjection
import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.hasCommittedDisplay

/**
 * Durable-only projections of a [PageArtifactRecord] (Phase 7 S8): both
 * answers derive exclusively from the manifest record when no live page
 * snapshot is available, so they share one committed/display-state
 * interpretation module.
 */

/**
 * Manifest fallback used by the open path ([eu.kanade.translation.persistence.chapter.ChapterStoreOpener]):
 * synthesizes the page shape implied by the manifest alone. A committed
 * pointer and validated display base are required before a page counts as
 * stage-ready.
 */
internal fun PageArtifactRecord.toArtifactPageFallback(): PageTranslation {
    val committed = committed
    val displayBase = committed?.displayBase
    val hasCommittedDisplay = committed != null &&
        (
            displayBase?.kind == DisplayBaseKind.ORIGINAL_SOURCE ||
                (
                    displayBase?.kind == DisplayBaseKind.CLEANED_IMAGE &&
                        displayBase.fileName != null &&
                        !displayBase.legacyLayout
                    )
            )
    val displayReady = displayState == PageDisplayState.DISPLAY_READY ||
        displayState == PageDisplayState.TEXTLESS_COMPLETE ||
        displayState == PageDisplayState.REFRESHING_WITH_COMMITTED_RESULT ||
        displayState == PageDisplayState.FAILED_WITH_COMMITTED_RESULT
    val ready = hasCommittedDisplay && displayReady
    val running = displayState == PageDisplayState.CANDIDATE_RUNNING
    val failed = displayState == PageDisplayState.FAILED_NO_RESULT
    val cleanedName = displayBase?.fileName?.takeIf {
        displayBase.kind == DisplayBaseKind.CLEANED_IMAGE &&
            !displayBase.legacyLayout
    }
    return PageTranslation(
        sourceFileName = pageKey,
        cleanedImageName = cleanedName,
        ocrStatus = when {
            ready -> StageStatus.READY
            running -> StageStatus.RUNNING
            failed -> StageStatus.FAILED
            else -> StageStatus.PENDING
        },
        translationStatus = when {
            ready -> StageStatus.READY
            running -> StageStatus.RUNNING
            failed -> StageStatus.FAILED
            else -> StageStatus.PENDING
        },
        inpaintStatus = when {
            ready && cleanedName != null -> StageStatus.READY
            running -> StageStatus.RUNNING
            failed -> StageStatus.FAILED
            else -> StageStatus.PENDING
        },
        renderStatus = when {
            ready && (cleanedName != null || displayBase?.kind == DisplayBaseKind.ORIGINAL_SOURCE) ->
                StageStatus.READY
            running -> StageStatus.RUNNING
            failed -> StageStatus.FAILED
            else -> StageStatus.PENDING
        },
        pageVersion = pageVersion,
        updatedAt = committed?.promotedAtEpochMs ?: 0L,
    )
}

/**
 * Project a durable artifact record when no live page snapshot is available.
 * A committed pointer and validated display base are required before a page
 * counts as translated-ready.
 */
fun PageArtifactRecord.toArtifactDisplayProjection(): PageDisplayProjection {
    val committed = committed
    val committedBaseValid = committed?.displayBase?.validated == true
    val committedDisplayState = displayState
    val committedStagesValid =
        inpaint?.status in
            setOf(
                ArtifactStageStatus.READY,
                ArtifactStageStatus.SKIPPED,
            ) &&
            translation?.status in
            setOf(
                ArtifactStageStatus.READY,
                ArtifactStageStatus.PARTIAL,
            ) &&
            layout?.status == ArtifactStageStatus.READY
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

    return PageDisplayProjection(
        state = committedDisplayState,
        displayReady = hasCommittedDisplay,
        processed = processed,
    )
}
