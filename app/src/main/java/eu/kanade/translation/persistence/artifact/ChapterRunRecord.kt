package eu.kanade.translation.persistence.artifact

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Durable record for one Batch run over a chapter. It is published with the
 * frozen run snapshot and updated only at persisted state transitions. The
 * record carries identity and provenance; only its declared fingerprint
 * fields participate in validity checks. Operational fields such as run ID,
 * timestamps, and phase counters never contribute to fingerprints.
 *
 * Documents are serialized through the shared [ArtifactDocumentJson] instance.
 */
enum class ChapterRunState {
    RUN_SNAPSHOT,
    SOURCE_VALIDATION,
    OCR_PLAN,
    OCR_PREFLIGHT,
    ANALYSIS_PLAN,
    ANALYSIS_CHUNKS,
    PROFILE_RECONCILE,
    PROFILE_FROZEN,
    ENVELOPE_PLAN,
    TRANSLATE,
    NATIVE_RENDER,
    FINALIZE,
    PAUSED,
    ABORTED,
    COMPLETE,
}

/**
 * Complete settings captured at RUN_SNAPSHOT. They stay fixed for the run; a
 * policy change starts a new run with a new snapshot.
 */
@Serializable
data class RunConfigSnapshot(
    val sourceLang: String,
    val targetLang: String,
    /** OCR engine and model identity ( OCR config inputs). */
    val ocrEngine: String,
    val ocrModelHash: String,
    val detectorModelHash: String,
    val segmenterModelHash: String = "",
    /** Detector/segmenter thresholds, encoded as a stable opaque string. */
    val detectionThresholds: String = "",
    val inpaintMode: String,
    /** Provider/model/credential identity, e.g. `gemini:gemini-2.5`. */
    val providerKey: String,
    val credentialId: String = "",
    val protocolVersion: Int,
    val analysisPolicy: AnalysisPolicySnapshot = AnalysisPolicySnapshot(),
    val envelopePolicy: EnvelopePolicySnapshot = EnvelopePolicySnapshot(),
    val readingOrderVersion: Int,
    /**
     * Profile-pipeline selection frozen into the run configuration. New
     * snapshots store `true`; `null` remains valid for records written before
     * this field existed, when the value may only appear in [phaseCounters].
     * It remains part of [frozenRunConfigFingerprint] so stored run identity
     * and per-page checkpoint reuse remain stable during resume.
     */
    val flagProfilePipeline: Boolean? = null,
)

/** Analysis policy values frozen in the run snapshot. */
@Serializable
data class AnalysisPolicySnapshot(
    val overlapPages: Int = 1,
)

/** Envelope policy values frozen in the run snapshot. */
@Serializable
data class EnvelopePolicySnapshot(
    /**
     *  envelope packing caps. maxBlocks stays the structural budget; the
     * token budgets may still split dense chapters into SMALLER envelopes.
     *
     * The maximum envelope size is fixed at five pages. Larger envelopes
     * exceeded provider output limits for dense chapters; smaller envelopes
     * preserve whole-page boundaries while keeping requests within budget.
     */
    val maxBlocks: Int = 64,
    val maxPages: Int = 5,
)

/**
 * The durable per-run record. Field declaration order is the canonical byte
 * order; new optional fields are appended
 * at the end only.
 */
@Serializable
data class ChapterRunRecord(
    val schemaVersion: Int = SCHEMA_VERSION,
    val kind: String = KIND,
    /** Transaction identity; NEVER a fingerprint input. */
    val runId: String,
    val state: ChapterRunState,
    val frozenConfig: RunConfigSnapshot,
    /** Length-prefixed hash over [frozenConfig] ( style). */
    val frozenRunConfigFingerprint: String,
    /** SHA-256 over the ordered (pageKey, sourceSha256) pairs, length-prefixed. */
    val orderedSourceDigest: String,
    /** Null before an OCR corpus exists. */
    val ocrCorpusFingerprint: String? = null,
    val analysisPolicyFingerprint: String,
    /** Excluded from translation validity ( matrix row 7). */
    val envelopePolicyFingerprint: String,
    /** Installed at PROFILE_FROZEN (schemas contract §1.8). */
    val profilePointer: ProfilePointer? = null,
    /** Installed at ENVELOPE_PLAN. */
    val envelopePlanPointer: SidecarPointer? = null,
    /** Operational counters only; excluded from all fingerprints. */
    val phaseCounters: Map<String, Int> = emptyMap(),
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
) {
    /** Returns null when this document is semantically usable. */
    fun validationError(): String? {
        if (schemaVersion != SCHEMA_VERSION) return "unsupported schemaVersion: $schemaVersion"
        if (kind != KIND) return "wrong kind: $kind"
        if (runId.isBlank()) return "blank runId"
        if (!frozenRunConfigFingerprint.isSha256Hex()) return "frozenRunConfigFingerprint is not sha256 hex"
        if (!orderedSourceDigest.isSha256Hex()) return "orderedSourceDigest is not sha256 hex"
        if (ocrCorpusFingerprint?.isSha256Hex() == false) return "ocrCorpusFingerprint is not sha256 hex"
        if (!analysisPolicyFingerprint.isSha256Hex()) return "analysisPolicyFingerprint is not sha256 hex"
        if (!envelopePolicyFingerprint.isSha256Hex()) return "envelopePolicyFingerprint is not sha256 hex"
        if (profilePointer?.isWellFormed() == false) return "profilePointer malformed"
        if (envelopePlanPointer?.isWellFormed() == false) return "envelopePlanPointer malformed"
        if (phaseCounters.size > MAX_PHASE_COUNTER_KEYS) return "too many phaseCounters keys: ${phaseCounters.size}"
        if (phaseCounters.values.any { it < 0 }) return "negative phaseCounters value"
        if (createdAtEpochMs <= 0L || updatedAtEpochMs <= 0L) return "non-positive run timestamps"
        if (frozenConfigSerializedBytes() > MAX_FROZEN_CONFIG_SERIALIZED_BYTES) {
            return "frozenConfig exceeds the serialized bound: $MAX_FROZEN_CONFIG_SERIALIZED_BYTES bytes"
        }
        return null
    }

    val isSemanticallyValid: Boolean
        get() = validationError() == null

    private fun frozenConfigSerializedBytes(): Int =
        ArtifactDocumentJson.encodeToString(frozenConfig).encodeToByteArray().size

    companion object {
        const val SCHEMA_VERSION = 1
        const val KIND = "CHAPTER_RUN_RECORD"

        /** Maximum number of named phase counters stored in a run record. */
        const val MAX_PHASE_COUNTER_KEYS = 32

        /** Maximum serialized size of the frozen run configuration. */
        const val MAX_FROZEN_CONFIG_SERIALIZED_BYTES = 64 * 1024
    }
}
