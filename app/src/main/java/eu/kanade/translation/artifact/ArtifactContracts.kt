package eu.kanade.translation.artifact

import kotlinx.serialization.Serializable

/**
 * TachiyomiAT: explicit per-stage artifact status vocabulary for the chapter
 * artifact manifest (artifact lifecycle contract §2). Distinct from the legacy
 * [eu.kanade.translation.model.StageStatus] strings, which remain the working
 * vocabulary of the live pipeline until the store transaction phase.
 */
enum class ArtifactStageStatus {
    /** No artifact exists. */
    ABSENT,

    /** Candidate work is active. */
    RUNNING,

    /** Terminal successful status: complete and reusable. */
    READY,

    /** Terminal success with no accepted source text, where applicable. */
    TEXTLESS,

    /** Terminal only when a documented dependency rule makes the stage unnecessary. */
    SKIPPED,

    /** Incomplete and eligible for retry. */
    FAILED_RETRYABLE,

    /** Incomplete until input/configuration/user action changes. */
    FAILED_TERMINAL,

    /** Payload may exist but provenance no longer matches. */
    STALE,

    /** Metadata, file, or payload validation failed. */
    CORRUPT,

    /**
     * Diagnostic candidate state: some outputs are valid and some are missing.
     * Never promotable and never counted as ready.
     */
    PARTIAL,
}

/** Pipeline stages addressable in the manifest and the immutable sidecar tree. */
enum class ArtifactStage {
    DETECTION,
    OCR,
    INPAINT,
    TRANSLATION,
    LAYOUT,
}

/** Provenance class of an artifact or generation (lifecycle contract §§12, 14). */
enum class ArtifactOrigin {
    /** Produced by an ordered chapter batch run. */
    BATCH,

    /** Produced by a reader single-page request; never authoritative batch context. */
    READER_ADHOC,

    /** Produced by the legacy flat pipeline before this schema existed. */
    LEGACY,

    /** Provenance cannot be proven from stored revisions and current assets. */
    UNKNOWN,
}

/** Durable failure categories recorded in the manifest (lifecycle contract §13). */
enum class FailureCategory {
    TRANSIENT,
    PROVIDER_REFUSAL,
    CONFIGURATION,
    SOURCE,
    PROTOCOL,

    /**
     *  Phase 3: the provider call never completed because the process
     * died mid-call — repeatedly, per the attempt-ledger cap. NOT a provider
     * fault and never auto-retryable: the user must explicitly force a retry.
     */
    INTERRUPTED,
    LEGACY_UNKNOWN,
}

/** Lifecycle state of a candidate generation record (lifecycle contract §13). */
enum class GenerationLifecycle {
    ACTIVE,
    COMMITTED,
    SUPERSEDED,
    CANCELLED,
}

/** Kind of display base a committed bundle renders over (lifecycle contract §9). */
enum class DisplayBaseKind {
    /** Validated cleaned image produced by the inpaint stage. */
    CLEANED_IMAGE,

    /** Original source image for a legitimate no-erase case. */
    ORIGINAL_SOURCE,
}

/**
 * Stable page source identity (lifecycle contract §3).
 *
 * Complete only when the byte hash and decoded geometry are recorded. Legacy
 * migration cannot prove any of these, so every unprovable component stays
 * explicitly null rather than being silently defaulted.
 */
@Serializable
data class SourceIdentity(
    val pageKey: String,
    val sha256: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val orientation: String? = null,
) {
    val isComplete: Boolean
        get() = sha256 != null && width != null && height != null && orientation != null
}

/** Immutable reference to the display base of a committed bundle. */
@Serializable
data class DisplayBaseReference(
    val kind: DisplayBaseKind,
    /** File name inside the chapter image layout; null for [DisplayBaseKind.ORIGINAL_SOURCE]. */
    val fileName: String? = null,
    /** Whether the physical file was validated (exists, decodes, dimensions match) when recorded. */
    val validated: Boolean = false,
    /** True when the name resolves inside the legacy companion image directory. */
    val legacyLayout: Boolean = false,
)

/**
 * Exact identity of the legacy flat translation file a manifest was built
 * from. The legacy file stays authoritative until the store-transaction
 * phase, so every load compares this identity and resyncs the manifest when
 * the authoritative bytes changed.
 */
@Serializable
data class LegacySourceIdentity(
    /** SHA-256 over the legacy translation file bytes at migration time. */
    val sha256: String,
    val lengthBytes: Long,
    val lastModifiedMs: Long,
)

/**
 * Mtime is diagnostic evidence only; content identity owns adoption.
 * Single canonical copy shared by `ChapterArtifactEngine` and
 * `ChapterArtifactDeletion` (previously duplicated in both).
 */
internal fun identitiesMatch(expected: LegacySourceIdentity, actual: LegacySourceIdentity): Boolean =
    expected.sha256.equals(actual.sha256, ignoreCase = true) &&
        expected.lengthBytes == actual.lengthBytes

/** Artifact metadata and provenance for one stage of one page (lifecycle contract §§4–8). */
@Serializable
data class StageArtifactRecord(
    val status: ArtifactStageStatus,
    /** Machine-readable skip reason, e.g. `NO_ERASE_REGIONS` or `TEXTLESS`. */
    val skipReason: String? = null,
    /**
     * Content-addressed fingerprint of the stage's direct inputs and
     * configuration. Null means provenance is unknown (legacy or unproven);
     * such artifacts are reusable only after the owning phase re-establishes
     * their dependencies.
     */
    val fingerprint: String? = null,
    val origin: ArtifactOrigin = ArtifactOrigin.UNKNOWN,
    /** Immutable sidecar file name under `artifacts/<pageKey>/<stage>/`, when written. */
    val artifactFileName: String? = null,
    /** Legacy payload reference (e.g. the flat record's cleaned image name), if any. */
    val legacyPayloadReference: String? = null,
    /**
     * Candidate generation that wrote this record, when it came from a Phase 3
     * transaction. Cancel uses this to remove only candidate-owned records.
     */
    val generationId: String? = null,
    val updatedAtEpochMs: Long = 0L,
)

/** Durable terminal failure/retry metadata (lifecycle contract §13). */
@Serializable
data class DurableFailureMetadata(
    val pageKey: String,
    val stage: ArtifactStage,
    val status: ArtifactStageStatus,
    val category: FailureCategory,
    val retryCount: Int,
    val lastFailureMessage: String? = null,
    val lastFailedAtEpochMs: Long,
    val nextEligibleRetryAtEpochMs: Long? = null,
    /**
     * Fingerprint of the failing configuration/source. A later configuration or
     * source change makes this mismatch, converting a terminal failure back to
     * retryable.
     */
    val failureFingerprint: String? = null,
    /** Optional contextual envelope identity for resumable AI work. */
    val envelopeId: String? = null,
    /** Stable block ids still missing from a partial envelope candidate. */
    val missingBlockIds: Set<String> = emptySet(),
    /**
     *  protocol parking: per omitted block id, the SOURCE text char
     * length at plan time. Diagnosis fingerprint only — never the text
     * itself — so a parked page's omitted blocks can be told apart (e.g.
     * one oversized block vs many tiny ones) without leaking content.
     */
    val missingBlockCharLengths: Map<String, Int> = emptyMap(),
)

/**
 * Reader-facing wording for a durable retryable failure. Provider admission
 * failures may keep their provider-specific message, but artifact/store
 * protocol failures must identify the consistency boundary that rejected the
 * page instead of falling through to the generic provider-unavailable copy.
 */
internal fun DurableFailureMetadata.toUiPauseReason(): String? {
    val message = lastFailureMessage?.takeIf { it.isNotBlank() }
        ?: "No diagnostic was recorded"
    if (category != FailureCategory.PROTOCOL) return message
    val lower = message.lowercase()
    return when {
        "page missing" in lower -> "Page manifest mismatch: $message"
        "fingerprint" in lower -> "Page fingerprint mismatch: $message"
        "checkpoint" in lower -> "Page checkpoint rejected: $message"
        else -> "Page consistency check failed: $message"
    }
}
