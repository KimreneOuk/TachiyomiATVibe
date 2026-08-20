package eu.kanade.translation.model

import eu.kanade.translation.artifact.ArtifactOrigin
import eu.kanade.translation.artifact.PageArtifactRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class PageWorkPlan(
    val runOcr: Boolean,
    val runTranslation: Boolean,
    val runInpaint: Boolean,
    val runRender: Boolean,
    /** Deterministic stage decisions used by an ordered batch resume. */
    @Transient
    val stageDecisions: List<StageWorkDecision> = emptyList(),
    /** Display readiness is intentionally independent from batch completeness. */
    val displayReady: Boolean = false,
    val batchContextComplete: Boolean = false,
)

/** Stages are ordered by their dependency graph, not by the reader viewport. */
enum class BatchStage {
    DETECTION,
    OCR,
    INPAINT,
    TRANSLATION,
    LAYOUT,
}

enum class StageDecision {
    REUSE,
    RUN,
    WAIT_FOR_DEPENDENCY,
    TERMINAL_COMPLETE,
    FAILED,
}

/** Machine-readable explanation for a [StageDecision]. */
enum class StageReasonCode {
    VALID_ARTIFACT,
    MISSING_ARTIFACT,
    MISSING_PAYLOAD,
    FINGERPRINT_MISMATCH,
    UNKNOWN_PROVENANCE,
    DEPENDENCY_INCOMPLETE,
    PRIOR_PAGE_INCOMPLETE,
    FAILED_STAGE,
    INTERRUPTED_STAGE,
    CANCELLED_STAGE,
    PARTIAL_ARTIFACT,
    TEXTLESS,
    NO_ERASE_REGIONS,
    CONTEXT_CHECKPOINT_INVALID,
    READER_ADHOC_NOT_BATCH_COMPLETE,
}

data class StageWorkDecision(
    val stage: BatchStage,
    val decision: StageDecision,
    val reason: StageReasonCode,
)

/** The current configuration identity expected for each stage. */
data class BatchExpectedFingerprints(
    val detection: String? = null,
    val ocr: String? = null,
    val inpaint: String? = null,
    val translation: String? = null,
    val layout: String? = null,
    /** Legacy snapshots have no provenance and must be re-established first. */
    val provenanceRequired: Boolean = false,
)

enum class ContextCheckpointState {
    TRUSTED,
    MISSING,
    CORRUPT,
    NOT_REQUIRED,
}

data class BatchContextCheckpoint(
    val state: ContextCheckpointState = ContextCheckpointState.NOT_REQUIRED,
    val hash: String? = null,
)

/** Input to the pure chapter planner. [pages] must already be natural order. */
data class BatchPlannerInput(
    val pageKey: String,
    val page: PageTranslation? = null,
    val artifact: PageArtifactRecord? = null,
    val expectedFingerprints: BatchExpectedFingerprints = BatchExpectedFingerprints(),
    /** Current source bytes hash, when the batch could read the page source. */
    val sourceFingerprint: String? = null,
    val contextCheckpoint: BatchContextCheckpoint = BatchContextCheckpoint(),
    val translationOrigin: ArtifactOrigin? = null,
)

data class BatchPageWorkPlan(
    val pageKey: String,
    val stages: List<StageWorkDecision>,
    val displayReady: Boolean,
    val batchContextComplete: Boolean,
    val firstIncompleteStage: BatchStage?,
) {
    val firstIncompleteDecision: StageWorkDecision?
        get() = stages.firstOrNull { it.decision != StageDecision.REUSE && it.decision != StageDecision.TERMINAL_COMPLETE }
}

data class BatchChapterWorkPlan(
    val pages: List<BatchPageWorkPlan>,
) {
    val firstWorkPageKey: String?
        get() = pages.firstOrNull { it.firstIncompleteDecision != null }?.pageKey
}
