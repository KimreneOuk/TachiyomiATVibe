package eu.kanade.translation.translator.contextual

import eu.kanade.translation.artifact.AnalysisChunkCoverage
import eu.kanade.translation.artifact.AnalysisChunkResult
import eu.kanade.translation.artifact.AnalysisChunkStatus
import eu.kanade.translation.artifact.AnalyzerProvenance
import eu.kanade.translation.artifact.EvidenceRef
import eu.kanade.translation.artifact.FactConflictState
import eu.kanade.translation.artifact.FactProvenance
import eu.kanade.translation.artifact.FactScope
import eu.kanade.translation.artifact.FactType
import eu.kanade.translation.artifact.EvidenceStrength
import eu.kanade.translation.artifact.ProfileFact
import eu.kanade.translation.artifact.ProfileScene
import java.text.Normalizer

/**
 * T924 Stage 5 slice B (WP5, T924-ST-09): the DETERMINISTIC pure reconciler
 * from the durable [AnalysisChunkResult] set to the frozen-profile CONTENT
 * (schemas contract §1.4 lists). No I/O, no provider calls, no bitmaps; fully
 * unit-testable in isolation. The caller (the coordinator's freeze step)
 * assembles the operational [eu.kanade.translation.artifact.ChapterTranslationProfile]
 * envelope (version / fingerprints / run id / freeze timestamp) around this
 * content and publishes it via the T924-TX-22 transaction.
 *
 * Reconcile rules (binding):
 *  - Every chunk record is VALIDATED before use (INV-04/22): a chunk failing
 *    [AnalysisChunkResult.validationError], a non-[AnalysisChunkStatus.VALID]
 *    status, or a non-contiguous chunk-ordinal sequence REJECTS the reconcile
 *    — partial/malformed data never advances.
 *  - Chunks with [AnalysisChunkCoverage.MISSING_ONLY] (wave-4 F-W4-3) are
 *    PENDING: they contribute NOTHING to canon (neither records, scenes,
 *    evidence, nor provenance); they only increment the pending count.
 *  - Conflicts are RETAINED, never averaged (design §6.3): two records that
 *    share a canonical source form but propose different target forms produce
 *    one CONFLICTING fact per distinct target variant in `unresolvedFacts` —
 *    nothing enters canon for that source form.
 *  - Nothing auto-promotes to series scope (design §6.4): every fact is
 *    `CANONICAL_CHAPTER_WIDE` + `CHAPTER_ANALYSIS`;
 *    `seriesUpdateCandidates` and `correctionCandidates` stay EMPTY in this
 *    slice (§7.1 corrections belong to a later run that observes committed
 *    translations).
 *  - Evidence refs are chunk-level (the chunk DTO carries no per-record
 *    attribution), so each fact carries the deduplicated, sorted evidence of
 *    the chunks that proposed it, labeled `STRONG_CONTEXTUAL` (or `WEAK` with
 *    a note when a chunk carried no evidence refs).
 *
 * Merge ordering (fully deterministic — the documented sort keys):
 *  1. Chunks are consumed in list order == chunk-ordinal order (publication
 *     enforces `chunkOrdinal == index`); records within a chunk in list order.
 *  2. Identity/term grouping keys are the NFC/LF-normalized (T924-SC-09)
 *     source form; target-variant order is first-seen.
 *  3. Output lists are SORTED before fact-id assignment:
 *     `entities` by (type name, canonicalSourceForm, canonicalTargetForm);
 *     `terms` by (canonicalSourceForm, canonicalTargetForm);
 *     `unresolvedFacts` by (type name, canonicalSourceForm, canonicalTargetForm, note).
 *     `scenes` by (firstNaturalPageIndex, lastNaturalPageIndex,
 *     producing chunk ordinal, index within the chunk).
 *  4. `factId`s are then assigned `f-1..f-N` across entities, terms,
 *     unresolved facts, in that order; `sceneId`s `s-1..s-M` in scene order.
 *     Scene participants are remapped onto the final fact ids; participants
 *     whose entity did not resolve into canon (conflicted/absent) are dropped.
 *  5. Schema bounds (T924-SC-02) are enforced by deterministic TRUNCATION of
 *     the sorted lists (first N kept) — a bound overflow must never fail the
 *     freeze.
 */
internal object ProfileReconciler {

    /** Reconcile outcome; [Rejected] pauses the run BEFORE any freeze. */
    sealed interface ReconcileOutcome {

        /** The deterministic profile content; stable across repeated runs. */
        data class Reconciled(val content: ReconciledProfileContent) : ReconcileOutcome

        /** Typed rejection reason; chunk evidence stays durable for a later run. */
        data class Rejected(val reason: String) : ReconcileOutcome
    }

    /**
     * The profile CONTENT lists plus the derived analyzer provenance (the
     * first canon-contributing chunk's provenance in ordinal order — the
     * frozen profile records the identity of the analysis stack that actually
     * produced the surviving evidence).
     */
    data class ReconciledProfileContent(
        val entities: List<ProfileFact>,
        val terms: List<ProfileFact>,
        val scenes: List<ProfileScene>,
        val unresolvedFacts: List<ProfileFact>,
        /** Always empty in slice B: nothing auto-promotes to series scope. */
        val seriesUpdateCandidates: List<ProfileFact>,
        /** Always empty in slice B: corrections are a later-run concern (§7.1). */
        val correctionCandidates: List<ProfileFact>,
        val analyzerProvenance: AnalyzerProvenance,
        /** Chunks that contributed canon (COMPLETE coverage). */
        val reconciledChunkCount: Int,
        /** Chunks held pending (MISSING_ONLY) — pending, never canon. */
        val pendingChunkCount: Int,
    )

    fun reconcile(chunks: List<AnalysisChunkResult>): ReconcileOutcome {
        // --- validation gate: every record, before any use (INV-04/22). ---
        chunks.forEachIndexed { index, chunk ->
            chunk.validationError()?.let {
                return ReconcileOutcome.Rejected(
                    "chunk ${chunk.chunkId} at position $index failed validation: $it",
                )
            }
            if (chunk.status != AnalysisChunkStatus.VALID) {
                return ReconcileOutcome.Rejected(
                    "chunk ${chunk.chunkId} at position $index is not VALID — " +
                        "re-run analysis instead of reconciling unvalidated data",
                )
            }
            if (chunk.chunkOrdinal != index) {
                return ReconcileOutcome.Rejected(
                    "chunk ordinal sequence broken at position $index: " +
                        "${chunk.chunkId} carries ordinal ${chunk.chunkOrdinal}",
                )
            }
        }
        if (chunks.isEmpty()) {
            return ReconcileOutcome.Rejected("no persisted analysis chunks to reconcile")
        }

        val canonChunks = chunks.filter { it.coverage == AnalysisChunkCoverage.COMPLETE }
        val pendingChunkCount = chunks.size - canonChunks.size

        // --- identity merge (first-seen group order, deterministic inputs). ---
        val identityGroups = linkedMapOf<String, MutableList<IdentityProposal>>()
        val termGroups = linkedMapOf<String, MutableList<TermProposal>>()
        val relationshipTriples = linkedMapOf<RelationshipKey, RelationshipProposal>()
        val sceneDrafts = mutableListOf<SceneDraft>()
        val conflictNotes = linkedMapOf<String, MutableList<AnalysisChunkResult>>()

        for (chunk in canonChunks) {
            for (entity in chunk.entities) {
                val source = normalize(entity.canonicalSourceName)
                val target = normalize(entity.proposedTargetName)
                identityGroups.getOrPut(source) { mutableListOf() } +=
                    IdentityProposal(target, aliases(entity), chunk)
            }
            for (term in chunk.terms) {
                val source = normalize(term.sourceForm)
                val target = normalize(term.canonicalTarget)
                termGroups.getOrPut(source) { mutableListOf() } +=
                    TermProposal(target, normalizeAll(term.aliases), term.kind.name, chunk)
            }
            val entityIdToSource = chunk.entities.associate { entity ->
                entity.entityId to normalize(entity.canonicalSourceName)
            }
            for (relationship in chunk.relationships) {
                val source = entityIdToSource[relationship.sourceEntityId] ?: continue
                val target = entityIdToSource[relationship.targetEntityId] ?: continue
                val key = RelationshipKey(normalize(relationship.type), source, target)
                relationshipTriples.getOrPut(key) { RelationshipProposal(key, chunk) }
            }
            chunk.scenes.forEachIndexed { sceneIndex, scene ->
                sceneDrafts += SceneDraft(scene, chunk.chunkOrdinal, sceneIndex, chunk)
            }
            for (note in chunk.conflictNotes) {
                val normalized = normalize(note)
                if (normalized.isEmpty()) continue
                conflictNotes.getOrPut(normalized) { mutableListOf() } += chunk
            }
        }

        val provenance = canonChunks.firstOrNull()?.analyzerProvenance
            ?: chunks.first().analyzerProvenance

        // --- resolve identities: agreement enters canon, variants conflict. ---
        val identityFactDrafts = mutableListOf<FactDraft>()
        val unresolvedDrafts = mutableListOf<FactDraft>()
        for ((source, proposals) in identityGroups) {
            val variants = linkedMapOf<String, MutableList<IdentityProposal>>()
            proposals.forEach { proposal ->
                variants.getOrPut(proposal.target) { mutableListOf() } += proposal
            }
            if (variants.size == 1) {
                identityFactDrafts += identityFact(source, proposals)
            } else {
                // §6.3: retain every variant as CONFLICTING; nothing in canon.
                variants.values.forEach { variantProposals ->
                    unresolvedDrafts += identityFact(source, variantProposals)
                        .copy(conflictState = FactConflictState.CONFLICTING)
                }
            }
        }

        // --- terms: same agreement/conflict rule. ---
        val termFactDrafts = mutableListOf<FactDraft>()
        for ((source, proposals) in termGroups) {
            val variants = linkedMapOf<String, MutableList<TermProposal>>()
            proposals.forEach { proposal ->
                variants.getOrPut(proposal.target) { mutableListOf() } += proposal
            }
            if (variants.size == 1) {
                termFactDrafts += termFact(source, proposals)
            } else {
                variants.values.forEach { variantProposals ->
                    unresolvedDrafts += termFact(source, variantProposals)
                        .copy(conflictState = FactConflictState.CONFLICTING)
                }
            }
        }

        // --- relationship facts (resolved within their chunk only). ---
        val relationshipFactDrafts = relationshipTriples.values.map { proposal ->
            FactDraft(
                type = FactType.RELATIONSHIP,
                source = proposal.key.source,
                target = proposal.key.target,
                aliases = emptyList(),
                evidence = evidenceOf(listOf(proposal.chunk)),
                note = "rel=${proposal.key.type}",
            )
        }

        // --- model-emitted conflict notes persist as WEAK notes (§6.3). ---
        conflictNotes.forEach { (note, chunks) ->
            unresolvedDrafts +=
                FactDraft(
                    type = FactType.NARRATIVE_STATE,
                    source = null,
                    target = null,
                    aliases = emptyList(),
                    evidence = emptyList(),
                    note = note,
                    evidenceStrength = EvidenceStrength.WEAK,
                    conflictState = FactConflictState.UNRESOLVED,
                    contributingChunks = chunks,
                )
        }

        // --- deterministic order, THEN fact-id assignment (§ sort keys). ---
        val entityDrafts = (identityFactDrafts + relationshipFactDrafts)
            .sortedWith(
                compareBy({ it.type.name }, { it.source.orEmpty() }, { it.target.orEmpty() }),
            )
        val orderedTermDrafts = termFactDrafts
            .sortedWith(compareBy({ it.source.orEmpty() }, { it.target.orEmpty() }))
        val orderedUnresolved = unresolvedDrafts
            .sortedWith(
                compareBy(
                    { it.type.name },
                    { it.source.orEmpty() },
                    { it.target.orEmpty() },
                    { it.note.orEmpty() },
                ),
            )

        // Fact-id sequence mirrors `toFact` assignment below: f-1.. across
        // entities, terms, unresolved — so scene participant remapping uses
        // the SAME deterministic sequence computed over the sorted drafts.
        var nextFactId = 1
        fun takeFactId(): String = "f-${nextFactId++}"

        val entities = entityDrafts.take(MAX_FACTS_PER_LIST)
            .map { draft -> draft.toFact(takeFactId()) }
        val terms = orderedTermDrafts.take(MAX_FACTS_PER_LIST)
            .map { draft -> draft.toFact(takeFactId()) }
        val unresolved = orderedUnresolved.take(MAX_CANDIDATES)
            .map { draft -> draft.toFact(takeFactId()) }

        // Wave-5 F-W5-2: participant remap keys on the FINAL (post-demotion)
        // fact types — an oversized identity fact demotes to NARRATIVE_STATE,
        // so participants referencing it DROP rather than point at a
        // non-entity note fact (§1.4: participants resolve to entity facts).
        val factIdsForIdentities = entities
            .filter { it.type == FactType.ENTITY_IDENTITY }
            .associate { it.canonicalSourceForm.orEmpty() to it.factId }

        val orderedScenes = sceneDrafts
            .sortedWith(
                compareBy(
                    { it.scene.pageRange.firstNaturalPageIndex },
                    { it.scene.pageRange.lastNaturalPageIndex },
                    { it.chunkOrdinal },
                    { it.sceneIndex },
                ),
            )
            .take(MAX_SCENES)
        var nextSceneId = 1
        val scenes = orderedScenes.map { draft ->
            val participants = draft.scene.participants.mapNotNull { participantId ->
                val source = draft.entityIdToSource[participantId] ?: return@mapNotNull null
                factIdsForIdentities[source]
            }.distinct()
            draft.scene.copy(
                sceneId = "s-${nextSceneId++}",
                // participants remapped onto final fact ids; unresolvable
                // (conflicted/absent) participants are dropped, never guessed.
                participants = participants,
            )
        }

        return ReconcileOutcome.Reconciled(
            ReconciledProfileContent(
                entities = entities,
                terms = terms,
                scenes = scenes,
                unresolvedFacts = unresolved,
                seriesUpdateCandidates = emptyList(),
                correctionCandidates = emptyList(),
                analyzerProvenance = provenance,
                reconciledChunkCount = canonChunks.size,
                pendingChunkCount = pendingChunkCount,
            ),
        )
    }

    // ------------------------------------------------------------------
    // Proposal/draft internals
    // ------------------------------------------------------------------

    private data class IdentityProposal(
        val target: String,
        val aliases: List<String>,
        val chunk: AnalysisChunkResult,
    )

    private data class TermProposal(
        val target: String,
        val aliases: List<String>,
        val kind: String,
        val chunk: AnalysisChunkResult,
    )

    private data class RelationshipKey(
        val type: String,
        val source: String,
        val target: String,
    )

    private data class RelationshipProposal(val key: RelationshipKey, val chunk: AnalysisChunkResult)

    private data class SceneDraft(
        val scene: ProfileScene,
        val chunkOrdinal: Int,
        val sceneIndex: Int,
        val chunk: AnalysisChunkResult,
    ) {
        val entityIdToSource: Map<String, String> =
            chunk.entities.associate { it.entityId to normalize(it.canonicalSourceName) }
    }

    /**
     * One fact before id assignment. [source]/[target] are the normalized
     * canonical forms (null for facts that have none); oversized forms demote
     * the fact to a WEAK NARRATIVE_STATE note so a bound-violating chunk
     * record can never fail the freeze.
     */
    private data class FactDraft(
        val type: FactType,
        val source: String?,
        val target: String?,
        val aliases: List<String>,
        val evidence: List<EvidenceRef>,
        val note: String?,
        val evidenceStrength: EvidenceStrength = EvidenceStrength.STRONG_CONTEXTUAL,
        val conflictState: FactConflictState = FactConflictState.RESOLVED,
        val contributingChunks: List<AnalysisChunkResult> = emptyList(),
    )

    private fun identityFact(source: String, proposals: List<IdentityProposal>): FactDraft {
        val chunks = proposals.map { it.chunk }.distinct()
        val evidence = evidenceOf(chunks)
        val allAliases = proposals.flatMap { it.aliases }
        return FactDraft(
            type = FactType.ENTITY_IDENTITY,
            source = bounded(source),
            target = bounded(proposals.first().target),
            aliases = boundedAliases(allAliases),
            evidence = evidence,
            note = listOfNotNull(
                if (evidence.isEmpty()) "no chunk-level evidence refs" else null,
                oversizedAliasNote(allAliases),
            ).joinToString("; ").ifEmpty { null },
            evidenceStrength = if (evidence.isEmpty()) EvidenceStrength.WEAK
            else EvidenceStrength.STRONG_CONTEXTUAL,
            contributingChunks = chunks,
        )
    }

    private fun termFact(source: String, proposals: List<TermProposal>): FactDraft {
        val chunks = proposals.map { it.chunk }.distinct()
        val evidence = evidenceOf(chunks)
        val kind = proposals.map { it.kind }.distinct().singleOrNull()
        val kindNote = when (kind) {
            null, "TERM" -> null
            else -> "termKind=$kind"
        }
        val boundNote = if (evidence.isEmpty()) "no chunk-level evidence refs" else null
        val allAliases = proposals.flatMap { it.aliases }
        return FactDraft(
            type = FactType.TERM,
            source = bounded(source),
            target = bounded(proposals.first().target),
            aliases = boundedAliases(allAliases),
            evidence = evidence,
            note = listOfNotNull(kindNote, boundNote, oversizedAliasNote(allAliases))
                .joinToString("; ").ifEmpty { null },
            evidenceStrength = if (evidence.isEmpty()) EvidenceStrength.WEAK
            else EvidenceStrength.STRONG_CONTEXTUAL,
            contributingChunks = chunks,
        )
    }

    /** Demotes a fact whose stored forms exceed the schema bounds to a note. */
    private fun FactDraft.boundedOrDemoted(): FactDraft =
        if ((source?.length ?: 0) <= ProfileFact.MAX_NAME_CHARS &&
            (target?.length ?: 0) <= ProfileFact.MAX_NAME_CHARS
        ) {
            this
        } else {
            copy(
                type = FactType.NARRATIVE_STATE,
                source = null,
                target = null,
                aliases = emptyList(),
                evidence = emptyList(),
                note = (note?.plus("; ") ?: "") + "oversized canonical form demoted from $type",
                evidenceStrength = EvidenceStrength.WEAK,
                conflictState = FactConflictState.UNRESOLVED,
            )
        }

    private fun FactDraft.toFact(factId: String): ProfileFact {
        val bounded = boundedOrDemoted()
        return ProfileFact(
            factId = factId,
            type = bounded.type,
            canonicalSourceForm = bounded.source,
            canonicalTargetForm = bounded.target,
            aliases = bounded.aliases,
            evidenceStrength = bounded.evidenceStrength,
            evidenceRefs = bounded.evidence,
            scope = FactScope.CANONICAL_CHAPTER_WIDE,
            provenance = FactProvenance.CHAPTER_ANALYSIS,
            conflictState = bounded.conflictState,
            note = bounded.note,
        )
    }

    /**
     * Chunk-level evidence of the contributing chunks: deduplicated and
     * sorted by (pageKey, stableBlockId, sourceExcerptHash); capped at the
     * schema bound.
     */
    private fun evidenceOf(chunks: List<AnalysisChunkResult>): List<EvidenceRef> =
        chunks.flatMap { it.evidenceRefs }
            .distinctBy { Triple(it.pageKey, it.stableBlockId, it.sourceExcerptHash) }
            .sortedWith(
                compareBy({ it.pageKey }, { it.stableBlockId }, { it.sourceExcerptHash }),
            )
            .take(ProfileFact.MAX_EVIDENCE_REFS)

    private fun aliases(entity: eu.kanade.translation.artifact.ExtractedEntity): List<String> =
        normalizeAll(entity.sourceNames + entity.titles)

    private fun boundedAliases(aliases: List<String>): List<String> =
        aliases.filter { it.isNotEmpty() }
            .distinct()
            // Wave-5 F-W5-1: an overlong alias (post-NFC) is DROPPED, never
            // fatal — ProfileFact would reject it and wedge the freeze in a
            // PERSISTENCE_REJECTED loop on every resume.
            .filter { it.length <= ProfileFact.MAX_NAME_CHARS }
            .take(ProfileFact.MAX_ALIASES)

    /** Wave-5 F-W5-1: deterministic drop marker for the fact note. */
    private fun oversizedAliasNote(aliases: List<String>): String? =
        if (aliases.any { it.isNotEmpty() && it.length > ProfileFact.MAX_NAME_CHARS }) {
            "oversized alias dropped"
        } else {
            null
        }

    private fun bounded(value: String): String? = value.takeIf { it.isNotEmpty() }

    /** T924-SC-09 text normalization: NFC + CRLF/CR to LF. Never case-folds. */
    internal fun normalize(value: String): String = Normalizer
        .normalize(value.replace("\r\n", "\n").replace("\r", "\n"), Normalizer.Form.NFC)

    private fun normalizeAll(values: List<String>): List<String> = values.map { normalize(it) }

    /** Profile schema bounds (T924-SC-02), enforced by deterministic truncation. */
    private val MAX_FACTS_PER_LIST = eu.kanade.translation.artifact.ChapterTranslationProfile.MAX_FACTS_PER_LIST
    private val MAX_SCENES = eu.kanade.translation.artifact.ChapterTranslationProfile.MAX_SCENES
    private val MAX_CANDIDATES = eu.kanade.translation.artifact.ChapterTranslationProfile.MAX_CANDIDATES
}
