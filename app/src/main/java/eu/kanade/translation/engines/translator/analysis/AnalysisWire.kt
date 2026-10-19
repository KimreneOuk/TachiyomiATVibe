package eu.kanade.translation.engines.translator.analysis

import kotlinx.serialization.Serializable
import eu.kanade.translation.persistence.SidecarPointer
import eu.kanade.translation.context.ProfileScene
import eu.kanade.translation.context.EvidenceRef
import eu.kanade.translation.context.AnalyzerProvenance
import eu.kanade.translation.persistence.isSha256Hex

/**
 * Typed structured-analysis wire contract. This file defines the run identity
 * inputs and response-side
 * parsed values that survive validation; the request document itself is built
 * by [AnalysisRequestBuilder] and the response is walked by
 * [AnalysisResponseValidator].
 *
 * Request and response JSON are wire payloads, not persisted documents. The
 * durable validated subset is the `AnalysisChunkResult` DTO.
 */

/**
 * Run-scoped identity for one analysis request tree (`run` and `policy`
 * `run` + `policy` blocks). Hashes are privacy-safe scope hashes — never raw
 * titles or keys. A scope the coordinator cannot prove (e.g. the manga-level
 * scope, absent from the chapter-local machine) is carried as an explicit
 * `ABSENT`-style value, never an empty string.
 */
data class AnalysisRunIdentity(
    val runId: String,
    /** `sha256:<hex>` privacy-safe manga scope hash, or [SCOPE_ABSENT]. */
    val mangaKeyHash: String,
    /** `sha256:<hex>` privacy-safe chapter scope hash. */
    val chapterKeyHash: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    /** Prompt, schema, and limits identity (`analysisPolicyFingerprint`). */
    val analysisPolicyFingerprint: String,
    /** Whole-corpus identity (`ocrCorpusFingerprint`). */
    val ocrCorpusFingerprint: String,
    val maxOutputTokens: Int,
) {
    companion object {
        /** Explicit absent-scope marker (absence is a value, never empty). */
        const val SCOPE_ABSENT = "absent:v1"
    }
}

/**
 * The contributing-set text evidence for one chunk: the request is the sole
 * authority for what the response's evidence references may resolve into
 *. Keys are WIRE identities (`p<N>` pages, `p<N>_b<M>` blocks);
 * [wirePageKeyByStorageKey] translates the planner's persisted page keys onto
 * the wire identities (the request reuses the `p<N>` stable identity scheme,
 * 03).
 */
data class AnalysisEvidenceTexts(
    /** Wire page key (`p<N>`) -> ordered stable block ids. */
    val blockIdsByPage: Map<String, List<String>>,
    /** Wire block id (`p<N>_b<M>`) -> persisted OCR source text (unsanitized). */
    val textByBlockId: Map<String, String>,
    /** Persisted page key -> wire page key (`p<N>`) for the contributing set. */
    val wirePageKeyByStorageKey: Map<String, String> = emptyMap(),
)

/**
 * One validated term record — the persistable subset of the
 * response term (schemas contract §1.3 [eu.kanade.translation.engines.translator.analysis.ExtractedTerm]).
 */
data class ValidatedTerm(
    val termId: String,
    val sourceForm: String,
    val canonicalTarget: String,
    val aliases: List<String>,
    val kind: String,
)

/**
 * One validated entity record — the persistable subset of the
 * response entity ([eu.kanade.translation.engines.translator.analysis.ExtractedEntity]). Gender,
 * pronoun and conflict facts are validated here but deliberately not persisted
 * on the chunk: frozen-profile reconciliation consumes them; the chunk DTO
 * carries relationships and conflict notes.
 */
data class ValidatedEntity(
    val entityId: String,
    val sourceNames: List<String>,
    val canonicalSourceName: String,
    val proposedTargetName: String,
    val titles: List<String>,
    val relationships: List<ValidatedRelationship>,
)

data class ValidatedRelationship(
    val type: String,
    val sourceEntityId: String,
    val targetEntityId: String,
)

/**
 * A validated scene draft: page/block bounds in WIRE identities plus the
 * model-emitted register/tone strings. The coordinator maps these onto the
 * durable [eu.kanade.translation.context.ProfileScene] with natural page
 * indexes at persistence time.
 */
data class ValidatedScene(
    val sceneId: String,
    val fromPageWireKey: String,
    val fromBlockId: String,
    val toPageWireKey: String,
    val toBlockId: String,
    val participants: List<String>,
    val tone: List<String>,
    val contentTags: List<String>,
    val register: String,
    val narrative: String?,
)

/** Coverage classification of a validated response (DR-A taxonomy input). */
enum class AnalysisCoverageKind {
    /**
     * The response parsed clean AND carries extraction content (records,
     * scenes, or narrative): the whole chunk commits.
     */
    COMPLETE,

    /**
     * DR-A Option 1 `MISSING_ONLY`: the response parsed clean (no unknown
     * ids, no duplicates, no conflicts, no version/framing error, no
     * refusal) but carried no extraction content for a chunk whose core
     * pages hold blocks. The independently complete subset — the empty
     * record set — commits, and the un-extracted remainder is marked
     * PENDING: it never blocks the chapter.
     */
    MISSING_ONLY,
}

data class AnalysisCoverage(
    val kind: AnalysisCoverageKind,
    val reasons: List<String>,
)

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

        /** Validation limits for persisted analysis-chunk content. */
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
