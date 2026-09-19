package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.artifact.AnalysisChunkCoverage
import eu.kanade.translation.artifact.AnalysisChunkResult
import eu.kanade.translation.artifact.AnalysisChunkStatus
import eu.kanade.translation.artifact.AnalyzerProvenance
import eu.kanade.translation.artifact.ArtifactDocumentJson
import eu.kanade.translation.artifact.ChapterArtifactEngine
import eu.kanade.translation.artifact.EvidenceRef
import eu.kanade.translation.artifact.ExtractedEntity
import eu.kanade.translation.artifact.ExtractedRelationship
import eu.kanade.translation.artifact.ExtractedTerm
import eu.kanade.translation.artifact.ProfileScene
import eu.kanade.translation.artifact.SidecarPointer
import eu.kanade.translation.translator.contextual.PlannedAnalysisChunk
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest

/**
 * T924 WP5 slice A — crash-safe chunk persistence (T924-SC-19/20/22, ST-08).
 *
 * One validated [AnalysisChunkResult] is ONE `publishSidecarPointers`
 * transaction: the immutable content-addressed sidecar under `analysis/` is
 * published FIRST, then the manifest `analysisChunks` pointer list is
 * appended in ONE atomic manifest publication. A crash between the two leaves
 * at most an orphan sidecar — never a pointer at a missing file — and any
 * precondition or publication failure leaves the PRIOR manifest authoritative
 * (the failed chunk stays unpersisted; resume re-executes the first missing
 * chunk, ST-08).
 *
 * WP1 deviation note (chunk-ordinal order): the pointer list is append-only
 * in chunk-ordinal order. The transaction REJECTS an out-of-order append
 * (`chunkOrdinal != analysisChunks.size`), so the list order always equals
 * the ordinal order and the ST-08 resume scan ("skip the persisted prefix")
 * is an O(size) index read.
 */
internal object AnalysisChunkPublication {

    /** Reason prefix used by the coordinator's typed diagnostics. */
    const val ORDINAL_REJECTION = "chunk ordinal out of order"

    /** Compatibility overload retained while callers migrate to the facade seam. */
    @Deprecated("Pass ChapterTranslationStore so the facade owns the engine")
    fun publish(
        artifact: ChapterArtifactEngine,
        manifest: eu.kanade.translation.artifact.ChapterArtifactManifest,
        result: AnalysisChunkResult,
        nowEpochMs: Long,
    ): ChapterArtifactEngine.TransactionOutcome = runBlocking {
        publish(
            store = ChapterTranslationStore(
                translationFile = null,
                fileCreator = null,
                artifactStore = artifact,
            ),
            manifest = manifest,
            result = result,
            nowEpochMs = nowEpochMs,
        )
    }

    /**
     * Publishes one validated chunk result. `expectedOrdinal` is the current
     * `analysisChunks` size — the ONLY ordinal this transaction accepts.
     */
    suspend fun publish(
        store: ChapterTranslationStore,
        manifest: eu.kanade.translation.artifact.ChapterArtifactManifest,
        result: AnalysisChunkResult,
        nowEpochMs: Long,
    ): ChapterArtifactEngine.TransactionOutcome {
        result.validationError()?.let { reason ->
            return ChapterArtifactEngine.TransactionOutcome.Rejected("analysis chunk invalid: $reason")
        }
        if (result.status != AnalysisChunkStatus.VALID) {
            // ST-08: invalid chunks are never persisted.
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

    /**
     * T924-SC-10-style semantic content fingerprint: SHA-256 over the
     * canonical re-encoded JSON with the operational timestamp zeroed, so
     * re-publication of an equal chunk maps to the equal (idempotent)
     * content-addressed name.
     */
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
        /** DR-A coverage classification (wave-4 F-W4-3). */
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
