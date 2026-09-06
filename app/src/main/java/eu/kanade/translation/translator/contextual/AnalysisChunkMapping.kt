package eu.kanade.translation.translator.contextual

import eu.kanade.translation.artifact.AnalysisChunkResult
import eu.kanade.translation.artifact.AnalysisChunkStatus
import eu.kanade.translation.artifact.AnalyzerProvenance
import eu.kanade.translation.artifact.EvidenceRef
import eu.kanade.translation.artifact.ExtractedEntity
import eu.kanade.translation.artifact.ExtractedRelationship
import eu.kanade.translation.artifact.ExtractedTerm
import eu.kanade.translation.artifact.ProfileScene
import eu.kanade.translation.artifact.SidecarPointer

/**
 * T924 WP5 handoff (wave-2 review gap 4 / deviation 8): pure mapping from the
 * planner-shaped [PlannedAnalysisChunk] (S4, pure planner output — not
 * persisted) onto the durable [AnalysisChunkResult] DTO (S1, schemas contract
 * §1.3). No IO, no coroutines; identity fields, contributing sets and
 * fingerprints are carried over verbatim, so the mapped record re-validates
 * against the DTO's own schema rules and round-trips through the canonical
 * [eu.kanade.translation.artifact.ArtifactDocumentJson] instance.
 */

/**
 * Map one planned chunk onto its durable record.
 *
 * Status initialization (the DTO's PENDING-analog): [AnalysisChunkResult]
 * has no PENDING state — a chunk document exists only once its validated
 * response is persisted, and an [AnalysisChunkStatus.INVALID] record "is
 * never consumed" (schemas contract §1.3). The parked, not-yet-usable analog
 * is therefore `INVALID` + [validationFailureReason]: a null reason maps to
 * [AnalysisChunkStatus.VALID] with no failure field, any non-null reason maps
 * to [AnalysisChunkStatus.INVALID] carrying it.
 *
 * @param ocrArtifactRefs one pointer per contributing page, in contributing
 *   order (core pages first, then context overlap — the T924-AP-03 request
 *   payload convention, wave-2 F4). The DTO enforces
 *   `size == corePageKeys.size + contextOverlapPageKeys.size`.
 */
fun PlannedAnalysisChunk.toAnalysisChunkResult(
    analysisSchemaVersion: Int,
    analyzerProvenance: AnalyzerProvenance,
    ocrArtifactRefs: List<SidecarPointer>,
    evidenceRefs: List<EvidenceRef> = emptyList(),
    terms: List<ExtractedTerm> = emptyList(),
    entities: List<ExtractedEntity> = emptyList(),
    relationships: List<ExtractedRelationship> = emptyList(),
    scenes: List<ProfileScene> = emptyList(),
    narrativeSummary: String? = null,
    conflictNotes: List<String> = emptyList(),
    validationFailureReason: String? = null,
    createdAtEpochMs: Long,
): AnalysisChunkResult = AnalysisChunkResult(
    chunkId = chunkId,
    chunkOrdinal = chunkOrdinal,
    analysisSchemaVersion = analysisSchemaVersion,
    corePageKeys = corePageKeys,
    contextOverlapPageKeys = contextOverlapPageKeys,
    contributingCorpusFingerprint = contributingCorpusFingerprint,
    ocrArtifactRefs = ocrArtifactRefs,
    terms = terms,
    entities = entities,
    relationships = relationships,
    scenes = scenes,
    narrativeSummary = narrativeSummary,
    conflictNotes = conflictNotes,
    evidenceRefs = evidenceRefs,
    analyzerProvenance = analyzerProvenance,
    status = if (validationFailureReason == null) {
        AnalysisChunkStatus.VALID
    } else {
        AnalysisChunkStatus.INVALID
    },
    validationFailureReason = validationFailureReason,
    createdAtEpochMs = createdAtEpochMs,
)

/**
 * The mapping-side evidence boundary (pure subset of V1/V9, T924-AP-05):
 * every evidence reference must resolve into this chunk's contributing set —
 * contributing page, block id belonging to that page, block id prefixed by
 * its page key. Excerpt-hash shape and recomputation (V8) stay with the DTO
 * validation ([AnalysisChunkResult.validationError]) and the WP5 validator
 * respectively. Returns the first violation, or null when every ref resolves.
 */
fun PlannedAnalysisChunk.evidenceBoundaryError(evidenceRefs: List<EvidenceRef>): String? =
    evidenceRefs.firstOrNull { ref -> !evidenceResolves(ref.pageKey, ref.stableBlockId) }
        ?.let { ref ->
            "evidence ref does not resolve into the contributing set: " +
                "${ref.pageKey}/${ref.stableBlockId}"
        }
