package eu.kanade.translation.model

import eu.kanade.translation.artifact.ArtifactOrigin
import eu.kanade.translation.artifact.DurableFailureMetadata
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

    /** Durable retryable failure; eligible once its cooldown has elapsed. */
    FAILED_RETRYABLE,

    /** Durable failure requiring explicit user/configuration/source action. */
    FAILED_TERMINAL,

    /** Legacy in-memory failure with no durable retryability metadata. */
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

    /**
     * TachiyomiAT   the chapter glossary matured past the version
     * recorded on the page's persisted translation, so its REUSE was
     * downgraded to RUN for a one-time terminology repair (phase3-design §1.2).
     */
    GLOSSARY_MATURED,
}

data class StageWorkDecision(
    val stage: BatchStage,
    val decision: StageDecision,
    val reason: StageReasonCode,
    val nextEligibleRetryAtEpochMs: Long? = null,
    val retryEligible: Boolean = false,
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

/** Input to the pure chapter planner. [pages] must already be natural order. */
data class BatchPlannerInput(
    val pageKey: String,
    val page: PageTranslation? = null,
    val artifact: PageArtifactRecord? = null,
    val expectedFingerprints: BatchExpectedFingerprints = BatchExpectedFingerprints(),
    /** Current source bytes hash, when the batch could read the page source. */
    val sourceFingerprint: String? = null,
    val translationOrigin: ArtifactOrigin? = null,
    /** Durable failure metadata, when the artifact manifest owns this page. */
    val durableFailure: DurableFailureMetadata? = null,
    /** Clock used for retry cooldown eligibility; injectable for planner tests. */
    val nowEpochMs: Long = System.currentTimeMillis(),
    /** Explicit user force-retry bypasses a retryable cooldown. */
    val forceRetry: Boolean = false,
    /**
     * TachiyomiAT   gate input: the chapter's current glossary version
     * (manifest pointer), or `null` when the gate is OFF — the standard
     * engine lane, a legacy-authority manifest, or a chapter with no glossary
     * ever published (phase3-design §1.2: absence keeps REUSE unchanged, so
     * glossary-less and standard-lane chapters stay cost-flat).
     */
    val currentGlossaryVersion: Int? = null,
)

data class BatchPageWorkPlan(
    val pageKey: String,
    val stages: List<StageWorkDecision>,
    val displayReady: Boolean,
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
