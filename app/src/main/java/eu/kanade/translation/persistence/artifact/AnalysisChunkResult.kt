package eu.kanade.translation.persistence.artifact

import kotlinx.serialization.Serializable

/**
 * One validated structured analysis result over a bounded page set. This is
 * the persisted artifact; the response schema and prompt are owned by the
 * analysis engine, and the stored fields are a subset of the validated
 * response.
 *
 * Documents are serialized through the shared [ArtifactDocumentJson] instance.
 */

/** Persisted validation outcome of one chunk. */
enum class AnalysisChunkStatus { VALID, INVALID }

/**
 * Coverage classification for a validated chunk: a VALID chunk with
 * `MISSING_ONLY` coverage carries the independently complete subset (possibly
 * zero records) and stays PENDING at reconcile — never canon.
 */
enum class AnalysisChunkCoverage { COMPLETE, MISSING_ONLY }

/** Term kind of an extracted term. */
enum class ExtractedTermKind { NAME, PLACE, TERM, TITLE, ORG }

/** Persisted validated term record. */
@Serializable
data class ExtractedTerm(
    val termId: String,
    val sourceForm: String,
    val canonicalTarget: String,
    val aliases: List<String> = emptyList(),
    val kind: ExtractedTermKind = ExtractedTermKind.TERM,
)

/** Persisted validated entity record. */
@Serializable
data class ExtractedEntity(
    val entityId: String,
    val canonicalSourceName: String,
    val proposedTargetName: String,
    val sourceNames: List<String> = emptyList(),
    val titles: List<String> = emptyList(),
)

/** Persisted validated relationship between two entity ids of one chunk. */
@Serializable
data class ExtractedRelationship(
    val type: String,
    val sourceEntityId: String,
    val targetEntityId: String,
)

/**
 * The per-chunk analysis result sidecar document (schemas contract §1.3).
 * Field declaration order is the canonical byte order.
 */
@Serializable
data class AnalysisChunkResult(
    val schemaVersion: Int = SCHEMA_VERSION,
    val kind: String = KIND,
    /** `chunk-<ordinal>-<corpus8>`; deterministic from the plan. */
    val chunkId: String,
    /** >= 0, unique per run. */
    val chunkOrdinal: Int,
    /** Structured-response schema used ( owns values). */
    val analysisSchemaVersion: Int,
    /** Ordered, natural order; 1..[MAX_CORE_PAGES] pages. */
    val corePageKeys: List<String>,
    /** <= [MAX_OVERLAP_PAGES] adjacent pages; core ∪ overlap = contributing set. */
    val contextOverlapPageKeys: List<String> = emptyList(),
    /** `OcrCorpusFingerprint` over the contributing page set in order. */
    val contributingCorpusFingerprint: String,
    /** One per contributing page, in contributing order. */
    val ocrArtifactRefs: List<SidecarPointer>,
    val terms: List<ExtractedTerm> = emptyList(),
    val entities: List<ExtractedEntity> = emptyList(),
    val relationships: List<ExtractedRelationship> = emptyList(),
/** Scenes in this chunk. */
    val scenes: List<ProfileScene> = emptyList(),
    /** Range-scoped supporting context; never substitutes structured records. */
    val narrativeSummary: String? = null,
    val conflictNotes: List<String> = emptyList(),
    /** Every ref must resolve to a core or overlap page and an existing stableBlockId. */
    val evidenceRefs: List<EvidenceRef>,
    val analyzerProvenance: AnalyzerProvenance,
    /** Coverage classification; the default preserves reads of older records. */
    val coverage: AnalysisChunkCoverage = AnalysisChunkCoverage.COMPLETE,
    /** INVALID chunks carry [validationFailureReason] and are never consumed. */
    val status: AnalysisChunkStatus,
    val validationFailureReason: String? = null,
    /** Operational only. */
    val createdAtEpochMs: Long,
) {
    /** Returns null when this document is semantically usable. */
    fun validationError(): String? {
        if (schemaVersion != SCHEMA_VERSION) return "unsupported schemaVersion: $schemaVersion"
        if (kind != KIND) return "wrong kind: $kind"
        if (chunkId.isBlank()) return "blank chunkId"
        if (chunkOrdinal < 0) return "negative chunkOrdinal"
        if (analysisSchemaVersion <= 0) return "non-positive analysisSchemaVersion"
        if (corePageKeys.isEmpty() || corePageKeys.size > MAX_CORE_PAGES) {
            return "corePageKeys out of bounds: ${corePageKeys.size}"
        }
        if (corePageKeys.any { it.isBlank() } || contextOverlapPageKeys.any { it.isBlank() }) {
            return "blank page key in contributing set"
        }
        if (contextOverlapPageKeys.size > MAX_OVERLAP_PAGES) {
            return "too many contextOverlapPageKeys: ${contextOverlapPageKeys.size}"
        }
        if (!contributingCorpusFingerprint.isSha256Hex()) {
            return "contributingCorpusFingerprint is not sha256 hex"
        }
        val contributingCount = corePageKeys.size + contextOverlapPageKeys.size
        if (ocrArtifactRefs.size != contributingCount) {
            return "ocrArtifactRefs count ${ocrArtifactRefs.size} != contributing page count $contributingCount"
        }
        if (ocrArtifactRefs.any { !it.isWellFormed() }) return "malformed ocrArtifactRef"
        if (terms.size > MAX_RECORDS_PER_KIND) return "too many terms"
        if (entities.size > MAX_RECORDS_PER_KIND) return "too many entities"
        if (relationships.size > MAX_RECORDS_PER_KIND) return "too many relationships"
        if (scenes.size > MAX_SCENES) return "too many scenes"
        scenes.forEach { scene -> scene.validationError()?.let { return "malformed scene: $it" } }
        if ((narrativeSummary?.length ?: 0) > MAX_NARRATIVE_SUMMARY_CHARS) return "narrativeSummary exceeds bound"
        if (conflictNotes.size > MAX_CONFLICT_NOTES) return "too many conflictNotes"
        if (conflictNotes.any { it.length > MAX_CONFLICT_NOTE_CHARS }) return "conflictNote exceeds bound"
        if (evidenceRefs.size > MAX_EVIDENCE_REFS) return "too many evidenceRefs"
        evidenceRefs.forEach { ref -> ref.validationError()?.let { return "malformed evidenceRef: $it" } }
        analyzerProvenance.validationError()?.let { return "malformed analyzerProvenance: $it" }
        if (analyzerProvenance.analysisSchemaVersion != analysisSchemaVersion) {
            return "analyzerProvenance analysisSchemaVersion mismatch"
        }
        if (status == AnalysisChunkStatus.INVALID && (validationFailureReason?.length ?: 0) > MAX_FAILURE_REASON_CHARS) {
            return "validationFailureReason exceeds bound"
        }
        if (status == AnalysisChunkStatus.VALID && validationFailureReason != null) {
            return "validationFailureReason present on a VALID chunk"
        }
        if (createdAtEpochMs <= 0L) return "non-positive createdAtEpochMs"
        return null
    }

    val isSemanticallyValid: Boolean
        get() = validationError() == null

    companion object {
        const val SCHEMA_VERSION = 1
        const val KIND = "ANALYSIS_CHUNK_RESULT"

        /** 02 schema bounds (T, tunable). */
        const val MAX_CORE_PAGES = 16
        const val MAX_OVERLAP_PAGES = 2
        const val MAX_RECORDS_PER_KIND = 128
        const val MAX_SCENES = 32
        const val MAX_NARRATIVE_SUMMARY_CHARS = 2000
        const val MAX_CONFLICT_NOTES = 32
        const val MAX_CONFLICT_NOTE_CHARS = 500
        const val MAX_EVIDENCE_REFS = 512
        const val MAX_FAILURE_REASON_CHARS = 500
    }
}
