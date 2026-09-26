package eu.kanade.translation.engines.translator.analysis

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
 * response term (schemas contract §1.3 [eu.kanade.translation.persistence.artifact.ExtractedTerm]).
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
 * response entity ([eu.kanade.translation.persistence.artifact.ExtractedEntity]). Gender,
 * pronoun and conflict facts are VALIDATED here (V1..V9) but deliberately not
 * persisted on the chunk: the frozen-profile reconcile stage (slice B) is
 * their consumer; the chunk DTO carries relationships + conflict notes.
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
 * durable [eu.kanade.translation.persistence.artifact.ProfileScene] with natural page
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
