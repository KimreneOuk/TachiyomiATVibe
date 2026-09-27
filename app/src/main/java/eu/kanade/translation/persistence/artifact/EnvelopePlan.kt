package eu.kanade.translation.persistence.artifact

import kotlinx.serialization.Serializable

/**
 * Durable whole-chapter envelope plan. This is data only; planning belongs
 * to the contextual translator. Serialized through the shared
 * [ArtifactDocumentJson] instance.
 */

/**
 * One planned translation request over whole pages. Every pending block
 * appears exactly once chapter-wide and a page's blocks are never split
 * across envelopes (page atomicity invariant).
 */
@Serializable
data class PlannedEnvelope(
    /** Deterministic `e-<ordinal>`; a readable derivative, never a fingerprint input. */
    val envelopeId: String,
    /** Natural order. */
    val orderedPageKeys: List<String>,
    /** Reading order. */
    val blockIds: List<String>,
    val contributingCorpusFingerprint: String,
    val estimatedInputTokens: Int,
    val estimatedOutputTokens: Int,
    val structuralBlockCount: Int,
    val contributingPageCount: Int,
    val sceneRefs: List<String> = emptyList(),
    /** Bounded factIds of the frozen-profile subset sent with the envelope. */
    val profileSubsetRefs: List<String> = emptyList(),
    val crossesSceneBoundary: Boolean = false,
) {
    fun validationError(): String? = when {
        envelopeId.isBlank() -> "blank envelopeId"
        orderedPageKeys.isEmpty() || orderedPageKeys.any { it.isBlank() } -> "blank or empty orderedPageKeys"
        blockIds.isEmpty() || blockIds.any { it.isBlank() } -> "blank or empty blockIds"
        !contributingCorpusFingerprint.isSha256Hex() -> "contributingCorpusFingerprint is not sha256 hex"
        estimatedInputTokens < 0 -> "negative estimatedInputTokens"
        estimatedOutputTokens < 0 -> "negative estimatedOutputTokens"
        structuralBlockCount < 0 -> "negative structuralBlockCount"
        contributingPageCount < 0 -> "negative contributingPageCount"
        else -> null
    }
}

/**
 * The envelope-plan sidecar document. Field
 * declaration order is the canonical byte order.
 */
@Serializable
data class EnvelopePlan(
    val schemaVersion: Int = SCHEMA_VERSION,
    val kind: String = KIND,
    /** Hash over ordered plan content. */
    val planFingerprint: String,
    /** Corpus slice + pending-block set + profile subset estimate + envelope policy. */
    val planInputFingerprint: String,
    /** Pure-planner algorithm version. */
    val plannerVersion: Int,
    val envelopes: List<PlannedEnvelope>,
    /** Operational only. */
    val createdAtEpochMs: Long,
) {
    /** Returns null when this document is semantically usable. */
    fun validationError(): String? {
        if (schemaVersion != SCHEMA_VERSION) return "unsupported schemaVersion: $schemaVersion"
        if (kind != KIND) return "wrong kind: $kind"
        if (!planFingerprint.isSha256Hex()) return "planFingerprint is not sha256 hex"
        if (!planInputFingerprint.isSha256Hex()) return "planInputFingerprint is not sha256 hex"
        if (plannerVersion < 1) return "non-positive plannerVersion"
        if (envelopes.size > MAX_ENVELOPES) return "too many envelopes: ${envelopes.size}"
        envelopes.forEach { envelope ->
            envelope.validationError()?.let { return "malformed envelope ${envelope.envelopeId}: $it" }
        }
        if (createdAtEpochMs <= 0L) return "non-positive createdAtEpochMs"
        return null
    }

    val isSemanticallyValid: Boolean
        get() = validationError() == null

    companion object {
        const val SCHEMA_VERSION = 1
        const val KIND = "ENVELOPE_PLAN"

        /** Maximum number of envelopes accepted in a persisted plan. */
        const val MAX_ENVELOPES = 4096
    }
}
