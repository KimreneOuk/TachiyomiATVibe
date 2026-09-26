package eu.kanade.translation.pipeline.batch.analysis

import eu.kanade.translation.engines.translator.contextual.PlannedAnalysisChunk
import eu.kanade.translation.persistence.artifact.AnalysisChunkCoverage
import eu.kanade.translation.persistence.artifact.AnalysisChunkResult
import eu.kanade.translation.persistence.artifact.AnalysisChunkStatus
import eu.kanade.translation.persistence.artifact.AnalyzerProvenance
import eu.kanade.translation.persistence.artifact.ArtifactDocumentJson
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.EvidenceRef
import eu.kanade.translation.persistence.artifact.ExtractedEntity
import eu.kanade.translation.persistence.artifact.ExtractedRelationship
import eu.kanade.translation.persistence.artifact.ExtractedTerm
import eu.kanade.translation.persistence.artifact.ProfileScene
import eu.kanade.translation.persistence.artifact.SidecarPointer
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import java.security.MessageDigest

/**
 * Persists validated analysis chunks as immutable sidecars plus manifest pointers.
 *
 * The sidecar is published before the manifest pointer list is appended in one
 * atomic manifest publication. A crash between those writes can leave an orphan
 * sidecar, but never a pointer to a missing file. Any rejected publication keeps
 * the prior manifest authoritative, so resume retries the first missing chunk.
 *
 * Pointers are append-only in chunk ordinal order. The transaction rejects any
 * ordinal other than the current list size, which lets resume treat the existing
 * pointers as a persisted prefix.
 */
internal object AnalysisChunkPublication {

    /** Reason prefix used by the coordinator's typed diagnostics. */
    const val ORDINAL_REJECTION = "chunk ordinal out of order"

    /**
     * Publishes one validated chunk result. `expectedOrdinal` is the current
     * `analysisChunks` size — the ONLY ordinal this transaction accepts.
     */
    suspend fun publish(
        store: ChapterTranslationStore,
        manifest: eu.kanade.translation.persistence.artifact.ChapterArtifactManifest,
        result: AnalysisChunkResult,
        nowEpochMs: Long,
    ): ChapterArtifactEngine.TransactionOutcome {
        result.validationError()?.let { reason ->
            return ChapterArtifactEngine.TransactionOutcome.Rejected("analysis chunk invalid: $reason")
        }
        if (result.status != AnalysisChunkStatus.VALID) {
            //  invalid chunks are never persisted.
            return ChapterArtifactEngine.TransactionOutcome.Rejected(
                "refusing to persist a non-VALID analysis chunk: ${result.chunkId}",
            )
        }
        if (result.chunkOrdinal != manifest.analysisChunks.size) {
            return ChapterArtifactEngine.TransactionOutcome.Rejected(
                "$ORDINAL_REJECTION: expected ${manifest.analysisChunks.size}, " +
                    "got ${result.chunkOrdinal} (${result.chunkId})",
            )
        }
        return store.withArtifactEngineLocked { artifact ->
            val contentFingerprint = contentFingerprint(result)
            val fileName = artifact.analysisChunkSidecarName(contentFingerprint)
            artifact.publishSidecarPointers(
                manifest = manifest,
                sidecars = listOf(
                    artifact.jsonSidecarPublication(
                        fileName = fileName,
                        contentFingerprint = contentFingerprint,
                        document = result,
                        serializer = AnalysisChunkResult.serializer(),
                    ),
                ),
                updatePointers = { current ->
                    current.copy(
                        analysisChunks = current.analysisChunks + SidecarPointer(
                            fileName = fileName,
                            schemaVersion = AnalysisChunkResult.SCHEMA_VERSION,
                            contentFingerprint = contentFingerprint,
                        ),
                    )
                },
                nowEpochMs = nowEpochMs,
            )
        } ?: ChapterArtifactEngine.TransactionOutcome.Rejected("artifact engine unavailable")
    }

    /** Hashes canonical chunk content with its operational timestamp zeroed. */
    fun contentFingerprint(result: AnalysisChunkResult): String {
        val hashingView = result.copy(createdAtEpochMs = 0L)
        val canonical = ArtifactDocumentJson.encodeToString(
            AnalysisChunkResult.serializer(),
            hashingView,
        )
        return sha256Hex(canonical.encodeToByteArray())
    }

    /** Builds the durable record from the validated response (persistable subset). */
    fun buildResult(
        chunk: PlannedAnalysisChunk,
        response: AnalysisChunkPublicationInput,
        nowEpochMs: Long,
    ): AnalysisChunkResult {
        val validReason = response.validationFailureReason
        check(validReason == null) { "invalid chunks are never persisted" }
        return AnalysisChunkResult(
            chunkId = chunk.chunkId,
            chunkOrdinal = chunk.chunkOrdinal,
            analysisSchemaVersion = response.provenance.analysisSchemaVersion,
            corePageKeys = chunk.corePageKeys,
            contextOverlapPageKeys = chunk.contextOverlapPageKeys,
            contributingCorpusFingerprint = chunk.contributingCorpusFingerprint,
            ocrArtifactRefs = response.ocrArtifactRefs,
            terms = response.terms,
            entities = response.entities,
            relationships = response.relationships,
            scenes = response.scenes,
            narrativeSummary = response.narrativeSummary,
            conflictNotes = response.conflictNotes,
            evidenceRefs = response.evidenceRefs,
            analyzerProvenance = response.provenance,
            coverage = response.coverage,
            status = AnalysisChunkStatus.VALID,
            validationFailureReason = null,
            createdAtEpochMs = nowEpochMs,
        )
    }

    /** Everything the durable record carries, already mapped from wire types. */
    data class AnalysisChunkPublicationInput(
        val provenance: AnalyzerProvenance,
        val ocrArtifactRefs: List<SidecarPointer>,
        /** Coverage classification for the persisted chunk. */
        val coverage: AnalysisChunkCoverage = AnalysisChunkCoverage.COMPLETE,
        val terms: List<ExtractedTerm> = emptyList(),
        val entities: List<ExtractedEntity> = emptyList(),
        val relationships: List<ExtractedRelationship> = emptyList(),
        val scenes: List<ProfileScene> = emptyList(),
        val narrativeSummary: String? = null,
        val conflictNotes: List<String> = emptyList(),
        val evidenceRefs: List<EvidenceRef> = emptyList(),
        val validationFailureReason: String? = null,
    )

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            "%02x".format(byte)
        }
}
