package eu.kanade.translation.persistence.artifact

import kotlinx.serialization.Serializable

/**
 *  Stage 1 (schemas contract §1.4): the frozen canonical chapter
 * translation profile and its structured facts. Immutable after publication;
 * monotonic [ChapterTranslationProfile.version]; corrections never mutate the
 * frozen content (design §4.4) — they are recorded as separate candidates.
 *
 * Shared sub-types ([EvidenceRef], [ProfileFact], [ProfileScene],
 * [AnalyzerProvenance]) are reused by `AnalysisChunkResult` (schemas contract
 * §1.3). Serialized only through the shared [ArtifactDocumentJson] instance
 *
 */

/**
 * Evidence anchor (schemas contract §1.4). [stableBlockId] is the OCR
 * canonical block id (e.g. `p3_b12`); [sourceExcerptHash] is the SHA-256 of
 * the NFC/LF-normalized source excerpt.
 */
@Serializable
data class EvidenceRef(
    val pageKey: String,
    val stableBlockId: String,
    val sourceExcerptHash: String,
) {
    fun validationError(): String? = when {
        pageKey.isBlank() -> "blank pageKey"
        stableBlockId.isBlank() -> "blank stableBlockId"
        !sourceExcerptHash.isSha256Hex() -> "sourceExcerptHash is not sha256 hex"
        else -> null
    }
}

/** Inclusive natural-page-index range. */
@Serializable
data class PageRange(
    val firstNaturalPageIndex: Int,
    val lastNaturalPageIndex: Int,
) {
    fun validationError(): String? = when {
        firstNaturalPageIndex < 0 || lastNaturalPageIndex < 0 -> "negative page range bound"
        firstNaturalPageIndex > lastNaturalPageIndex -> "inverted page range"
        else -> null
    }
}

/** Inclusive block-ordinal range within one page. */
@Serializable
data class BlockRange(
    val naturalPageIndex: Int,
    val firstBlockOrdinal: Int,
    val lastBlockOrdinal: Int,
) {
    fun validationError(): String? = when {
        naturalPageIndex < 0 -> "negative naturalPageIndex"
        firstBlockOrdinal < 0 || lastBlockOrdinal < 0 -> "negative block ordinal"
        firstBlockOrdinal > lastBlockOrdinal -> "inverted block range"
        else -> null
    }
}

/** The page/block point a AVAILABLE_FROM-scoped fact becomes usable from. */
@Serializable
data class PageBlockRef(
    val naturalPageIndex: Int,
    val stableBlockId: String? = null,
) {
    fun validationError(): String? =
        if (naturalPageIndex < 0) "negative naturalPageIndex" else null
}

/** The analysis stack that produced a chunk or froze a profile. */
@Serializable
data class AnalyzerProvenance(
    val providerId: String,
    val modelId: String,
    val promptVersion: Int,
    val analysisSchemaVersion: Int,
    /** Opaque credential signature; never a raw credential. */
    val credentialFingerprint: String? = null,
) {
    fun validationError(): String? = when {
        providerId.isBlank() -> "blank providerId"
        modelId.isBlank() -> "blank modelId"
        promptVersion <= 0 -> "non-positive promptVersion"
        analysisSchemaVersion <= 0 -> "non-positive analysisSchemaVersion"
        else -> null
    }
}

/** What kind of statement the fact makes (schemas contract §1.4). */
enum class FactType { ENTITY_IDENTITY, TERM, GENDER, PRONOUN, RELATIONSHIP, TONE, NARRATIVE_STATE }

/** How directly the evidence supports the fact (design §4.3). */
enum class EvidenceStrength { EXPLICIT, STRONG_CONTEXTUAL, WEAK }

/** Chapter-wide, range-bound, or evidence-point-onward applicability. */
enum class FactScope { CANONICAL_CHAPTER_WIDE, RANGE_SCOPED, AVAILABLE_FROM }

/** Gender value of a GENDER fact; pronouns are separate PRONOUN facts (design §4.3). */
enum class ProfileGender { MALE, FEMALE, UNKNOWN, CONFLICTING }

/** Authority hierarchy of the fact source (design §6.4). */
enum class FactProvenance { USER, SERIES_CANON, CHAPTER_ANALYSIS, ROLLING_CONTEXT, LOCAL_INFERENCE }

/** Reconciliation state; weak rejected cues persist as REJECTED notes. */
enum class FactConflictState { RESOLVED, UNRESOLVED, CONFLICTING, REJECTED }

/** Scene tone/content flags (schemas contract §1.4). */
enum class ToneFlag { EXPLICIT, INTIMATE, VIOLENT, COMEDIC, SERIOUS, ACTION, OTHER }

/** Speech register of a scene (schemas contract §1.4). */
enum class SceneRegister { CASUAL, FORMAL, ARCHAIC, ROUGH, POLITE, OTHER }

/**
 * The fact representation (schemas contract §1.4); entities/terms/gender
 * facts are typed instances of this shape. Strings that carry names are
 * NFC-normalized before storage.
 */
@Serializable
data class ProfileFact(
    /** Unique within the profile; referential key for scenes/participants. */
    val factId: String,
    val type: FactType,
    /** Required for ENTITY_IDENTITY/TERM; NFC-normalized. */
    val canonicalSourceForm: String? = null,
    /** Required for ENTITY_IDENTITY/TERM; NFC-normalized. */
    val canonicalTargetForm: String? = null,
    /** Titles/honorific variants; NFC-normalized. */
    val aliases: List<String> = emptyList(),
    /** Model confidence; never a validity key by itself. */
    val confidence: Float? = null,
    val evidenceStrength: EvidenceStrength,
    /** Required unless [evidenceStrength] is WEAK (weak cues stay notes). */
    val evidenceRefs: List<EvidenceRef> = emptyList(),
    val scope: FactScope,
    /** Present iff [scope] is AVAILABLE_FROM. */
    val availableFrom: PageBlockRef? = null,
    /** Present iff [scope] is RANGE_SCOPED. */
    val applicableRange: PageRange? = null,
    /** GENDER facts only. */
    val gender: ProfileGender? = null,
    val provenance: FactProvenance,
    val conflictState: FactConflictState,
    /** Weak name/speech-style cues remain notes. */
    val note: String? = null,
) {
    fun validationError(): String? = when {
        factId.isBlank() -> "blank factId"
        requiresCanonicalForms && canonicalSourceForm.isNullOrBlank() -> "missing canonicalSourceForm"
        requiresCanonicalForms && canonicalTargetForm.isNullOrBlank() -> "missing canonicalTargetForm"
        canonicalSourceForm.length() > MAX_NAME_CHARS -> "canonicalSourceForm exceeds bound"
        canonicalTargetForm.length() > MAX_NAME_CHARS -> "canonicalTargetForm exceeds bound"
        aliases.size > MAX_ALIASES -> "too many aliases"
        aliases.any { it.length > MAX_NAME_CHARS } -> "alias exceeds bound"
        confidence != null && (confidence < 0.0f || confidence > 1.0f) -> "confidence out of range"
        evidenceRefs.size > MAX_EVIDENCE_REFS -> "too many evidenceRefs"
        evidenceRefs.any { it.validationError() != null } -> "malformed evidenceRef"
        requiresEvidence && evidenceRefs.isEmpty() -> "missing evidenceRefs for non-WEAK fact"
        availableFrom != null && scope != FactScope.AVAILABLE_FROM -> "availableFrom outside AVAILABLE_FROM scope"
        availableFrom == null && scope == FactScope.AVAILABLE_FROM -> "missing availableFrom"
        availableFrom?.validationError() != null -> "malformed availableFrom"
        applicableRange != null && scope != FactScope.RANGE_SCOPED -> "applicableRange outside RANGE_SCOPED scope"
        applicableRange == null && scope == FactScope.RANGE_SCOPED -> "missing applicableRange"
        applicableRange?.validationError() != null -> "malformed applicableRange"
        gender != null && type != FactType.GENDER -> "gender value on a non-GENDER fact"
        gender == null && type == FactType.GENDER -> "missing gender value on a GENDER fact"
        note.length() > MAX_NOTE_CHARS -> "note exceeds bound"
        else -> null
    }

    private val requiresCanonicalForms: Boolean
        get() = type == FactType.ENTITY_IDENTITY || type == FactType.TERM

    private val requiresEvidence: Boolean
        get() = evidenceStrength != EvidenceStrength.WEAK

    private fun String?.length(): Int = this?.length ?: 0

    companion object {
        /** 02 schema bounds (T, tunable). */
        const val MAX_NAME_CHARS = 128
        const val MAX_ALIASES = 32
        const val MAX_EVIDENCE_REFS = 32
        const val MAX_NOTE_CHARS = 500
    }
}

/**
 * A narrative scene over a bounded page range (schemas contract §1.4).
 * [participants] entries are factIds that must resolve to entity facts.
 */
@Serializable
data class ProfileScene(
    val sceneId: String,
    val pageRange: PageRange,
    val blockRanges: List<BlockRange> = emptyList(),
    val participants: List<String> = emptyList(),
    val toneFlags: Set<ToneFlag> = emptySet(),
    val register: SceneRegister,
    /** Range-scoped only. */
    val narrativeContext: String? = null,
) {
    fun validationError(): String? = when {
        sceneId.isBlank() -> "blank sceneId"
        pageRange.validationError() != null -> "malformed pageRange"
        blockRanges.size > MAX_BLOCK_RANGES -> "too many blockRanges"
        blockRanges.any { it.validationError() != null } -> "malformed blockRange"
        participants.size > MAX_PARTICIPANTS -> "too many participants"
        participants.any { it.isBlank() } -> "blank participant factId"
        narrativeContext.length() > MAX_NARRATIVE_CHARS -> "narrativeContext exceeds bound"
        else -> null
    }

    private fun String?.length(): Int = this?.length ?: 0

    companion object {
        /** 02 schema bounds (T, tunable). */
        const val MAX_BLOCK_RANGES = 64
        const val MAX_PARTICIPANTS = 16
        const val MAX_NARRATIVE_CHARS = 1000
    }
}

/**
 * Typed fact instances stored in the profile's [ChapterTranslationProfile.entities]
 * and [ChapterTranslationProfile.term] lists (schemas contract §1.4).
 */
typealias ProfileEntity = ProfileFact
typealias ProfileTerm = ProfileFact

/**
 * The frozen canonical chapter translation profile (schemas contract §1.4).
 * Immutable after publication; [version] is operational ordering ONLY and
 * never the sole validity key. Field declaration order is the
 * canonical byte order.
 */
@Serializable
data class ChapterTranslationProfile(
    val schemaVersion: Int = SCHEMA_VERSION,
    val kind: String = KIND,
    val version: Int,
    /** `ProfileContentFingerprint`. */
    val contentFingerprint: String,
    /** `ProfileInputFingerprint`. */
    val profileInputFingerprint: String,
    /** Operational provenance; never fingerprinted. */
    val sourceRunId: String,
    val analyzerProvenance: AnalyzerProvenance,
    val entities: List<ProfileEntity> = emptyList(),
    val terms: List<ProfileTerm> = emptyList(),
    val scenes: List<ProfileScene> = emptyList(),
    /** Ambiguity retained, never averaged away (design §6.3). */
    val unresolvedFacts: List<ProfileFact> = emptyList(),
    /** Never auto-promoted (design §6.4). */
    val seriesUpdateCandidates: List<ProfileFact> = emptyList(),
    /** Separate candidates for a future run; never mutate frozen content. */
    val correctionCandidates: List<ProfileFact> = emptyList(),
    /** Operational only. */
    val frozenAtEpochMs: Long,
) {
    /** 01/SC-02 semantic validation; null when the document is usable. */
    fun validationError(): String? {
        if (schemaVersion != SCHEMA_VERSION) return "unsupported schemaVersion: $schemaVersion"
        if (kind != KIND) return "wrong kind: $kind"
        if (version < 1) return "non-positive version"
        if (!contentFingerprint.isSha256Hex()) return "contentFingerprint is not sha256 hex"
        if (!profileInputFingerprint.isSha256Hex()) return "profileInputFingerprint is not sha256 hex"
        if (sourceRunId.isBlank()) return "blank sourceRunId"
        if (analyzerProvenance.validationError() != null) return "malformed analyzerProvenance"
        if (entities.size > MAX_FACTS_PER_LIST) return "too many entities"
        if (terms.size > MAX_FACTS_PER_LIST) return "too many terms"
        if (scenes.size > MAX_SCENES) return "too many scenes"
        if (unresolvedFacts.size > MAX_CANDIDATES) return "too many unresolvedFacts"
        if (seriesUpdateCandidates.size > MAX_CANDIDATES) return "too many seriesUpdateCandidates"
        if (correctionCandidates.size > MAX_CANDIDATES) return "too many correctionCandidates"
        val allFacts = entities + terms + unresolvedFacts + seriesUpdateCandidates + correctionCandidates
        allFacts.forEach { fact -> fact.validationError()?.let { return "malformed fact ${fact.factId}: $it" } }
        val duplicateFactId = allFacts.groupBy { it.factId }.values.firstOrNull { it.size > 1 }
        if (duplicateFactId != null) return "duplicate factId: ${duplicateFactId.first().factId}"
        val entityFactIds = (entities + terms).map { it.factId }.toSet()
        scenes.forEach { scene ->
            scene.validationError()?.let { return "malformed scene ${scene.sceneId}: $it" }
            scene.participants.forEach { participant ->
                if (participant !in entityFactIds) {
                    return "scene ${scene.sceneId} participant does not resolve to an entity fact: $participant"
                }
            }
        }
        if (frozenAtEpochMs <= 0L) return "non-positive frozenAtEpochMs"
        return null
    }

    val isSemanticallyValid: Boolean
        get() = validationError() == null

    companion object {
        const val SCHEMA_VERSION = 1
        const val KIND = "CHAPTER_TRANSLATION_PROFILE"

        /** 02 schema bounds (T, tunable). */
        const val MAX_FACTS_PER_LIST = 512
        const val MAX_SCENES = 256
        const val MAX_CANDIDATES = 128
    }
}
