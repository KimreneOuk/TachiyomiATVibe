package eu.kanade.translation.persistence.artifact

import eu.kanade.translation.model.PageDisplayProjection
import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.hasCommittedDisplay

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
