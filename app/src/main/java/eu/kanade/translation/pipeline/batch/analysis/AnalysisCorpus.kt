package eu.kanade.translation.pipeline.batch.analysis

import eu.kanade.translation.engines.translator.analysis.AnalysisChunkRunOutcome
import eu.kanade.translation.engines.translator.analysis.AnalysisCoverageKind
import eu.kanade.translation.engines.translator.analysis.AnalysisEvidenceTexts
import eu.kanade.translation.engines.translator.contextual.PlannedAnalysisChunk
import eu.kanade.translation.persistence.artifact.AnalysisChunkCoverage
import eu.kanade.translation.persistence.artifact.ExtractedEntity
import eu.kanade.translation.persistence.artifact.ExtractedRelationship
import eu.kanade.translation.persistence.artifact.ExtractedTerm
import eu.kanade.translation.persistence.artifact.ExtractedTermKind
import eu.kanade.translation.persistence.artifact.PageRange
import eu.kanade.translation.persistence.artifact.ProfileScene
import eu.kanade.translation.persistence.artifact.SceneRegister
import eu.kanade.translation.persistence.artifact.SidecarPointer
import eu.kanade.translation.persistence.artifact.ToneFlag

/** Rebuilt OCR page entry with both durable storage and analysis wire identities. */
internal class AnalysisCorpusEntry(
    val storagePageKey: String,
    val naturalPageIndex: Int,
    val contentFingerprint: String,
    val snapshotPointer: SidecarPointer,
    val wirePageKey: String,
    val wireBlockIds: List<String>,
    val blockTexts: List<String>,
    val estimatedInputTokens: Int,
)

/** Maps durable OCR checkpoints to analysis evidence and publication records. */
internal class AnalysisCorpus(
    val entries: List<AnalysisCorpusEntry>,
    val corpusFingerprint: String,
) {
    /** Chunks are planned over STORAGE page keys; look them up here. */
    private val byStorageKey = entries.associateBy { it.storagePageKey }

    /** Wire evidence universe + source texts for one planned chunk. */
    fun evidenceTextsFor(chunk: PlannedAnalysisChunk): AnalysisEvidenceTexts {
        val blockIdsByPage = mutableMapOf<String, List<String>>()
        val textByBlockId = mutableMapOf<String, String>()
        val wirePageKeyByStorageKey = mutableMapOf<String, String>()
        for (pageKey in chunk.contributingPageKeys) {
            val entry = byStorageKey[pageKey] ?: continue
            wirePageKeyByStorageKey[pageKey] = entry.wirePageKey
            blockIdsByPage[entry.wirePageKey] = entry.wireBlockIds
            entry.wireBlockIds.forEachIndexed { index, blockId ->
                textByBlockId[blockId] = entry.blockTexts[index]
            }
        }
        return AnalysisEvidenceTexts(
            blockIdsByPage = blockIdsByPage,
            textByBlockId = textByBlockId,
            wirePageKeyByStorageKey = wirePageKeyByStorageKey,
        )
    }

    /**
     * Maps a validated response to the durable publication input: persistable
     * records only, evidence keys translated from wire `p<N>` values to stored
     * page keys, and OCR pointers kept in contributing (core-then-context) order.
     */
    fun publicationInput(
        chunk: PlannedAnalysisChunk,
        outcome: AnalysisChunkRunOutcome.Completed,
    ): AnalysisChunkPublication.AnalysisChunkPublicationInput? {
        val refs = mutableListOf<SidecarPointer>()
        for (pageKey in chunk.contributingPageKeys) {
            val entry = byStorageKey[pageKey] ?: return null
            refs += entry.snapshotPointer
        }

        fun storageKey(wireKey: String): String =
            entries.firstOrNull { it.wirePageKey == wireKey }?.storagePageKey ?: wireKey

        val scenes = outcome.response.scenes.map { scene ->
            ProfileScene(
                sceneId = scene.sceneId,
                pageRange = PageRange(
                    firstNaturalPageIndex = naturalIndexOrZero(scene.fromPageWireKey),
                    lastNaturalPageIndex = naturalIndexOrZero(scene.toPageWireKey),
                ),
                participants = scene.participants,
                toneFlags = (scene.tone + scene.contentTags).mapNotNull { name ->
                    when (name) {
                        "EXPLICIT", "INTIMATE", "VIOLENT", "COMEDIC", "SERIOUS", "ACTION" ->
                            ToneFlag.valueOf(name)
                        else -> ToneFlag.OTHER
                    }
                }.toSet(),
                register = SceneRegister.entries.firstOrNull { it.name == scene.register }
                    ?: SceneRegister.OTHER,
                narrativeContext = scene.narrative,
            )
        }
        return AnalysisChunkPublication.AnalysisChunkPublicationInput(
            provenance = outcome.provenance,
            ocrArtifactRefs = refs,
            // Coverage is durable so profile reconciliation can keep missing-only chunks pending.
            coverage = when (outcome.coverage.kind) {
                AnalysisCoverageKind.COMPLETE -> AnalysisChunkCoverage.COMPLETE
                AnalysisCoverageKind.MISSING_ONLY -> AnalysisChunkCoverage.MISSING_ONLY
            },
            terms = outcome.response.terms.map { term ->
                ExtractedTerm(
                    termId = term.termId,
                    sourceForm = term.sourceForm,
                    canonicalTarget = term.canonicalTarget,
                    aliases = term.aliases,
                    kind = ExtractedTermKind.entries.firstOrNull { it.name == term.kind }
                        ?: ExtractedTermKind.TERM,
                )
            },
            entities = outcome.response.entities.map { entity ->
                ExtractedEntity(
                    entityId = entity.entityId,
                    canonicalSourceName = entity.canonicalSourceName,
                    proposedTargetName = entity.proposedTargetName,
                    sourceNames = entity.sourceNames,
                    titles = entity.titles,
                )
            },
            relationships = outcome.response.entities.flatMap { entity ->
                entity.relationships.map { relationship ->
                    ExtractedRelationship(
                        type = relationship.type,
                        sourceEntityId = relationship.sourceEntityId,
                        targetEntityId = relationship.targetEntityId,
                    )
                }
            },
            scenes = scenes,
            narrativeSummary = outcome.response.narrativeSummary,
            conflictNotes = outcome.response.conflictNotes,
            evidenceRefs = outcome.response.evidenceRefs.map { ref ->
                ref.copy(pageKey = storageKey(ref.pageKey))
            },
        )
    }

    private fun naturalIndexOrZero(wirePageKey: String): Int =
        wirePageKey.removePrefix("p").toIntOrNull()?.coerceAtLeast(0) ?: 0
}
