package eu.kanade.translation.engines.translator.contextual

import eu.kanade.translation.persistence.artifact.AnalysisChunkResult
import eu.kanade.translation.persistence.artifact.AnalysisChunkStatus
import eu.kanade.translation.persistence.artifact.AnalyzerProvenance
import eu.kanade.translation.persistence.artifact.ArtifactDocumentJson
import eu.kanade.translation.persistence.artifact.EvidenceRef
import eu.kanade.translation.persistence.artifact.SidecarPointer
import eu.kanade.translation.persistence.artifact.StageFingerprints
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The pure
 * `PlannedAnalysisChunk` → `AnalysisChunkResult` mapping — identity/sets/
 * fingerprint preservation, the DTO's status initialization boundary
 * (INVALID+reason as the PENDING-analog parked state), the S1 DTO validation
 * boundaries (ocrArtifactRefs count == core + overlap; evidence-ref
 * excerpt-hash shape), the planner-side evidence-resolution boundary
 * (refs = core ∪ overlap only, V1/V9 subset), and the canonical JSON
 * round-trip.
 */
class AnalysisChunkMappingTest {

    private fun hex64(tag: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(tag.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun page(index: Int, blockCount: Int) = ChunkPlannerPage(
        pageKey = "p$index",
        naturalPageIndex = index,
        contentFingerprint = StageFingerprints.canonicalFingerprint(listOf("page-content", index)),
        blockIds = (0 until blockCount).map { blockIndex -> "p${index}_b$blockIndex" },
        estimatedInputTokens = 16,
    )

    /** Two chunks: [p0,p1] (no overlap) and [p2] with overlap [p1]. */
    private fun plannedChunks(): List<PlannedAnalysisChunk> {
        val result = AnalysisChunkPlanner.plan(
            pages = listOf(page(0, 4), page(1, 4), page(2, 4)),
            policy = AnalysisChunkPolicy(maxCorePages = 2, overlapPages = 1),
        )
        check(result is AnalysisChunkPlanResult.Success) { "expected Success: $result" }
        return result.chunks
    }

    private fun provenance() = AnalyzerProvenance(
        providerId = "openai-compatible",
        modelId = "test-model",
        promptVersion = 1,
        analysisSchemaVersion = 1,
        credentialFingerprint = null,
    )

    private fun refsFor(chunk: PlannedAnalysisChunk): List<SidecarPointer> =
        chunk.contributingPageKeys.map { pageKey ->
            SidecarPointer(
                fileName = "ocr-$pageKey.json",
                schemaVersion = 1,
                contentFingerprint = hex64("ocr-$pageKey"),
            )
        }

    @Test
    fun `mapping preserves ids, contributing sets, order and fingerprints`() {
        val chunks = plannedChunks()
        chunks.size shouldBe 2
        val overlapChunk = chunks[1]
        overlapChunk.corePageKeys shouldBe listOf("p2")
        overlapChunk.contextOverlapPageKeys shouldBe listOf("p1")

        val result = overlapChunk.toAnalysisChunkResult(
            analysisSchemaVersion = 1,
            analyzerProvenance = provenance(),
            ocrArtifactRefs = refsFor(overlapChunk),
            createdAtEpochMs = 1_757_050_000_000,
        )
        result.chunkId shouldBe overlapChunk.chunkId
        result.chunkId shouldContain "-1-"
        result.chunkOrdinal shouldBe overlapChunk.chunkOrdinal
        // Contributing set = core-then-context, verbatim.
        result.corePageKeys shouldBe overlapChunk.corePageKeys
        result.contextOverlapPageKeys shouldBe overlapChunk.contextOverlapPageKeys
        result.contributingCorpusFingerprint shouldBe overlapChunk.contributingCorpusFingerprint
        result.ocrArtifactRefs.map { it.fileName } shouldBe
            listOf("ocr-p2.json", "ocr-p1.json")
        result.analysisSchemaVersion shouldBe 1
        result.analyzerProvenance shouldBe provenance()
        result.validationError().shouldBeNull()
    }

    @Test
    fun `status initialization - null reason maps to VALID, reason maps to INVALID`() {
        val chunk = plannedChunks().first()
        val valid = chunk.toAnalysisChunkResult(
            analysisSchemaVersion = 1,
            analyzerProvenance = provenance(),
            ocrArtifactRefs = refsFor(chunk),
            createdAtEpochMs = 1_757_050_000_000,
        )
        valid.status shouldBe AnalysisChunkStatus.VALID
        valid.validationFailureReason.shouldBeNull()
        valid.validationError().shouldBeNull()

        val failed = chunk.toAnalysisChunkResult(
            analysisSchemaVersion = 1,
            analyzerProvenance = provenance(),
            ocrArtifactRefs = refsFor(chunk),
            validationFailureReason = "schema violation: missing entities",
            createdAtEpochMs = 1_757_050_000_000,
        )
        // INVALID is the DTO's parked PENDING-analog: persisted, never consumed.
        failed.status shouldBe AnalysisChunkStatus.INVALID
        failed.validationFailureReason shouldBe "schema violation: missing entities"
        failed.validationError().shouldBeNull()
    }

    @Test
    fun `dto boundary - VALID with a failure reason and count mismatch are rejected`() {
        val chunk = plannedChunks().first()

        val validWithReason = chunk.toAnalysisChunkResult(
            analysisSchemaVersion = 1,
            analyzerProvenance = provenance(),
            ocrArtifactRefs = refsFor(chunk),
            createdAtEpochMs = 1_757_050_000_000,
        ).copy(status = AnalysisChunkStatus.VALID, validationFailureReason = "x")
        validWithReason.validationError().shouldNotBeNull() shouldContain
            "validationFailureReason present on a VALID chunk"

        // ocrArtifactRefs size must equal core + overlap (S1 DTO validation).
        val shortRefs = chunk.toAnalysisChunkResult(
            analysisSchemaVersion = 1,
            analyzerProvenance = provenance(),
            ocrArtifactRefs = refsFor(chunk).dropLast(1),
            createdAtEpochMs = 1_757_050_000_000,
        )
        shortRefs.ocrArtifactRefs.size shouldBe
            shortRefs.corePageKeys.size + shortRefs.contextOverlapPageKeys.size - 1
        shortRefs.validationError().shouldNotBeNull() shouldContain "ocrArtifactRefs count"
    }

    @Test
    fun `dto boundary - malformed excerpt hash in an evidence ref is rejected`() {
        val chunk = plannedChunks().first()
        val badHash = chunk.toAnalysisChunkResult(
            analysisSchemaVersion = 1,
            analyzerProvenance = provenance(),
            ocrArtifactRefs = refsFor(chunk),
            evidenceRefs = listOf(
                EvidenceRef(
                    pageKey = chunk.corePageKeys.first(),
                    stableBlockId = "${chunk.corePageKeys.first()}_b0",
                    sourceExcerptHash = "NOT-A-SHA256",
                ),
            ),
            createdAtEpochMs = 1_757_050_000_000,
        )
        badHash.validationError().shouldNotBeNull() shouldContain "sourceExcerptHash is not sha256 hex"

        val goodHash = badHash.copy(
            evidenceRefs = listOf(
                EvidenceRef(
                    pageKey = chunk.corePageKeys.first(),
                    stableBlockId = "${chunk.corePageKeys.first()}_b0",
                    sourceExcerptHash = hex64("excerpt"),
                ),
            ),
        )
        goodHash.validationError().shouldBeNull()
    }

    @Test
    fun `evidence boundary - refs must resolve into core or overlap pages`() {
        val chunk = plannedChunks()[1] // core p2, overlap p1
        val good = EvidenceRef("p2", "p2_b1", hex64("e1"))
        val overlap = EvidenceRef("p1", "p1_b0", hex64("e2"))
        chunk.evidenceBoundaryError(listOf(good, overlap)).shouldBeNull()
        // The same refs pass the planner predicate directly (V1/V9 subset).
        chunk.evidenceResolves(good.pageKey, good.stableBlockId) shouldBe true
        chunk.evidenceResolves(overlap.pageKey, overlap.stableBlockId) shouldBe true

        // A page outside the contributing set.
        val stranger = EvidenceRef("p9", "p9_b0", hex64("e3"))
        chunk.evidenceBoundaryError(listOf(good, stranger)) shouldContain
            "evidence ref does not resolve into the contributing set: p9/p9_b0"
        // A real block id cited under the WRONG page (prefix guard).
        val misattributed = EvidenceRef("p1", "p2_b0", hex64("e4"))
        chunk.evidenceBoundaryError(listOf(misattributed)) shouldContain
            "evidence ref does not resolve into the contributing set: p1/p2_b0"
        // A block id that belongs to no contributing page.
        val unknownBlock = EvidenceRef("p2", "p2_b99", hex64("e5"))
        chunk.evidenceBoundaryError(listOf(unknownBlock)).shouldNotBeNull()
    }

    @Test
    fun `mapped chunk round-trips through the canonical ArtifactDocumentJson`() {
        val chunk = plannedChunks()[1]
        val result = chunk.toAnalysisChunkResult(
            analysisSchemaVersion = 1,
            analyzerProvenance = provenance(),
            ocrArtifactRefs = refsFor(chunk),
            evidenceRefs = listOf(EvidenceRef("p2", "p2_b0", hex64("excerpt"))),
            conflictNotes = listOf("two candidate names for the protagonist"),
            createdAtEpochMs = 1_757_050_000_000,
        )
        result.validationError().shouldBeNull()

        val encoded = ArtifactDocumentJson.encodeToString(AnalysisChunkResult.serializer(), result)
        val decoded = ArtifactDocumentJson.decodeFromString(AnalysisChunkResult.serializer(), encoded)
        decoded shouldBe result
        decoded.status shouldBe AnalysisChunkStatus.VALID
        decoded.isSemanticallyValid shouldBe true
    }
}
