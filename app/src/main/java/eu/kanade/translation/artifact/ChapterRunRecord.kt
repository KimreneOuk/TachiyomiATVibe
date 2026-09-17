package eu.kanade.translation.artifact

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * T924 Stage 1 (T924-SC-01/SC-02): one durable record per Batch run attempt
 * over a chapter (schemas contract §1.1). Published at RUN_SNAPSHOT and
 * updated only at persisted phase transitions (transition semantics are owned
 * by the state/transactions contract, T924-ST-*). The record is identity and
 * provenance only — never a fingerprint input beyond its declared fingerprint
 * fields (T924-FP-01).
 *
 * Serialized only through the shared [ArtifactDocumentJson] instance
 * (T924-SC-06). Operational fields (runId, timestamps, phaseCounters) are
 * never fingerprint inputs (T924-FP-01).
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
 * Complete frozen settings captured at RUN_SNAPSHOT (schemas contract §1.1).
 * Settings never mutate under a run (invalidation matrix row 13); a policy
 * change starts a new run with a new snapshot.
 */
@Serializable
data class RunConfigSnapshot(
    val sourceLang: String,
    val targetLang: String,
    /** OCR engine and model identity (T924-FP-02 OCR config inputs). */
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
     * T924-FF-01d: historical A/B flag value, once read at dispatch and
     * frozen into the run snapshot. The flag completed its lifecycle
     * (zero-legacy wave) and every new snapshot freezes `true`; the field
     * stays in the schema — nullable so pre-field records (written with the
     * flag as a phaseCounters key only) decode unchanged — and it still
     * participates in frozenRunConfigFingerprint by construction, so
     * flag-era records keep their original fingerprints and resume
     * unchanged (per-page checkpoint reuse is unaffected, being keyed by
     * content fingerprints).
     */
    val flagProfilePipeline: Boolean? = null,
)

/** Analysis policy fields frozen at RUN_SNAPSHOT (values owned by T924-AP-*). */
@Serializable
data class AnalysisPolicySnapshot(
    val overlapPages: Int = 1,
)

/** Envelope policy fields frozen at RUN_SNAPSHOT (values owned by T924-AP-*). */
@Serializable
data class EnvelopePolicySnapshot(
    /**
     * T924 envelope packing caps. maxBlocks stays the structural budget; the
     * token budgets may still split dense chapters into SMALLER envelopes.
     *
     * Director decision (2026-09-17, hard cap): maxPages is FIXED at 5 —
     * "5 pages fixed, no matter what". On-device evidence 2026-09-17: an
     * 8-page/64-block envelope of a dense chapter truncated at the output
     * cap on every retry attempt and the whole envelope was discarded as
     * ambiguous (protocol); 5-page envelopes bound the per-call output so
     * the budget holds.
     */
    val maxBlocks: Int = 64,
    val maxPages: Int = 5,
)

/**
 * The durable per-run record (schemas contract §1.1). Field declaration order
 * is the canonical byte order (T924-SC-06); new optional fields are appended
 * at the end only.
 */
@Serializable
data class ChapterRunRecord(
    val schemaVersion: Int = SCHEMA_VERSION,
    val kind: String = KIND,
    /** Transaction identity; NEVER a fingerprint input (T924-FP-01). */
    val runId: String,
    val state: ChapterRunState,
    val frozenConfig: RunConfigSnapshot,
    /** Length-prefixed hash over [frozenConfig] (T924-SC-08 style). */
    val frozenRunConfigFingerprint: String,
    /** SHA-256 over the ordered (pageKey, sourceSha256) pairs, length-prefixed. */
    val orderedSourceDigest: String,
    /** Null before an OCR corpus exists (T924-FP-03). */
    val ocrCorpusFingerprint: String? = null,
    val analysisPolicyFingerprint: String,
    /** Excluded from translation validity (T924-FP-06, matrix row 7). */
    val envelopePolicyFingerprint: String,
    /** Installed at PROFILE_FROZEN (schemas contract §1.8). */
    val profilePointer: ProfilePointer? = null,
    /** Installed at ENVELOPE_PLAN. */
    val envelopePlanPointer: SidecarPointer? = null,
    /** Operational counters only; excluded from all fingerprints (T924-FP-01). */
    val phaseCounters: Map<String, Int> = emptyMap(),
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
) {
    /** T924-SC-01/SC-02 semantic validation; null when the document is usable. */
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

        /** T924-SC-02 schema bound: phaseCounters keys (T, tunable). */
        const val MAX_PHASE_COUNTER_KEYS = 32

        /** T924-SC-02 schema bound: serialized frozenConfig size (T, tunable). */
        const val MAX_FROZEN_CONFIG_SERIALIZED_BYTES = 64 * 1024
    }
}
