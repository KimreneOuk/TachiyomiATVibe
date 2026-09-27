package eu.kanade.translation.pipeline.batch.analysis

import com.hippo.unifile.FakeUniFile
import com.hippo.unifile.UniFile
import eu.kanade.translation.persistence.artifact.AnalysisChunkResult
import eu.kanade.translation.persistence.artifact.AnalysisChunkStatus
import eu.kanade.translation.persistence.artifact.AnalyzerProvenance
import eu.kanade.translation.persistence.artifact.AtomicChapterDocuments
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactLayout
import eu.kanade.translation.persistence.artifact.ChapterArtifactManifest
import eu.kanade.translation.persistence.artifact.EvidenceRef
import eu.kanade.translation.persistence.artifact.ExtractedEntity
import eu.kanade.translation.persistence.artifact.ExtractedRelationship
import eu.kanade.translation.persistence.artifact.ExtractedTerm
import eu.kanade.translation.persistence.artifact.SidecarPointer
import eu.kanade.translation.persistence.artifact.SidecarRead
import eu.kanade.translation.persistence.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.pipeline.batch.publish
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Covers crash-safe analysis chunk persistence. One validated chunk is one
 * sidecar-then-pointer transaction
 * appended in chunk-ordinal order; any rejection leaves the PRIOR manifest
 * authoritative and the failed chunk unpersisted (resume re-executes it).
 */
class AnalysisChunkPublicationTest {

    @TempDir
    lateinit var mangaDir: File

    private fun hex64(tag: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(tag.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun root(): UniFile = FakeUniFile(parent = null, backing = mangaDir)

    private fun artifactStore(): ChapterArtifactEngine =
        ChapterArtifactEngine(
            AtomicChapterDocuments(UniFileChapterDocumentIo(root())),
            ChapterArtifactLayout("Chapter 1"),
        )

    /** A base manifest to publish against (pre-registered pages, no chunks). */
    private suspend fun baseManifest(): ChapterArtifactManifest {
        val store = ChapterTranslationStore.lazy(
            fileCreator = { root().createFile("Chapter 1.json")!! },
            artifactParent = root(),
            artifactFileName = "Chapter 1.json",
        )
        store.preRegisterPages(listOf("p1", "p2", "p3"))
        return artifactStore().readManifest().shouldNotBeNull()
    }

    private fun validResult(
        ordinal: Int = 0,
        createdAtEpochMs: Long = 1_000L,
    ): AnalysisChunkResult = AnalysisChunkResult(
        chunkId = "chunk-$ordinal-abcdef12",
        chunkOrdinal = ordinal,
        analysisSchemaVersion = 1,
        corePageKeys = listOf("p${ordinal + 1}"),
        contextOverlapPageKeys = emptyList(),
        contributingCorpusFingerprint = hex64("corpus-$ordinal"),
        ocrArtifactRefs = listOf(
            SidecarPointer("pages/p${ordinal + 1}/snapshot.json", 1, hex64("snapshot-$ordinal")),
        ),
        terms = listOf(ExtractedTerm(termId = "t001", sourceForm = "剣", canonicalTarget = "sword")),
        entities = listOf(
            ExtractedEntity(
                entityId = "e001",
                canonicalSourceName = "カイル",
                proposedTargetName = "Kail",
                sourceNames = listOf("カイル"),
            ),
        ),
        relationships = listOf(
            ExtractedRelationship(type = "RIVAL", sourceEntityId = "e001", targetEntityId = "e002"),
        ),
        evidenceRefs = listOf(
            EvidenceRef("p${ordinal + 1}", "p${ordinal + 1}_b0", hex64("excerpt-$ordinal")),
        ),
        analyzerProvenance = AnalyzerProvenance(
            providerId = "fake",
            modelId = "fake-model",
            promptVersion = 1,
            analysisSchemaVersion = 1,
            credentialFingerprint = "sig",
        ),
        status = AnalysisChunkStatus.VALID,
        createdAtEpochMs = createdAtEpochMs,
    )

    @Test
    fun `valid chunk round-trips through the content-addressed sidecar and manifest pointer`() = runTest {
        val artifact = artifactStore()
        val manifest = baseManifest()
        val result = validResult(ordinal = 0)
        val fingerprint = AnalysisChunkPublication.contentFingerprint(result)

        val outcome = AnalysisChunkPublication.publish(artifact, manifest, result, nowEpochMs = 5_000L)
        val committed = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()

        committed.manifest.analysisChunks.shouldHaveSize(1)
        val pointer = committed.manifest.analysisChunks.single()
        pointer.fileName shouldContain "/analysis/"
        pointer.fileName shouldEndWith ".json"
        pointer.schemaVersion shouldBe AnalysisChunkResult.SCHEMA_VERSION
        pointer.contentFingerprint shouldBe fingerprint

        // Read-back through the pointer idiom: parse, kind, semantic validity.
        val read = artifact.readSidecarDocument(
            pointer = pointer,
            serializer = AnalysisChunkResult.serializer(),
            currentSchemaVersion = AnalysisChunkResult.SCHEMA_VERSION,
            expectedKind = AnalysisChunkResult.KIND,
            schemaVersionOf = { it.schemaVersion },
            kindOf = { it.kind },
            isValid = { it.isSemanticallyValid },
        )
        val document = read
            .shouldBeInstanceOf<SidecarRead.Usable<AnalysisChunkResult>>()
            .document
        document shouldBe result

        // Content-addressed idempotence: the operational timestamp is not part
        // of the identity, so an equal chunk re-publishes to the equal name.
        AnalysisChunkPublication.contentFingerprint(result.copy(createdAtEpochMs = 9_000L)) shouldBe fingerprint
    }

    @Test
    fun `chunks append in ordinal order and an out-of-order append is rejected`() = runTest {
        val artifact = artifactStore()
        var manifest = baseManifest()

        manifest = AnalysisChunkPublication.publish(artifact, manifest, validResult(0), 1_000L)
            .shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>().manifest

        // Ordinal 2 while the list holds one pointer: REJECTED, prior manifest
        // stays authoritative (the gap is never silently bridged).
        val rejected = AnalysisChunkPublication.publish(artifact, manifest, validResult(2), 2_000L)
            .shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        rejected.reason shouldStartWith AnalysisChunkPublication.ORDINAL_REJECTION
        artifact.readManifest().shouldNotBeNull().analysisChunks.shouldHaveSize(1)

        // The accepted append keeps list order == ordinal order ( prefix rule).
        manifest = AnalysisChunkPublication.publish(artifact, manifest, validResult(1), 3_000L)
            .shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>().manifest
        manifest.analysisChunks.shouldHaveSize(2)
        manifest.analysisChunks.map { it.contentFingerprint } shouldBe listOf(
            AnalysisChunkPublication.contentFingerprint(validResult(0)),
            AnalysisChunkPublication.contentFingerprint(validResult(1)),
        )
    }

    @Test
    fun `invalid chunks are never persisted`() = runTest {
        val artifact = artifactStore()
        val manifest = baseManifest()

        // INVALID status: never a durable chunk.
        val invalidStatus = validResult(0).copy(
            status = AnalysisChunkStatus.INVALID,
            validationFailureReason = "V8 excerpt hash mismatch",
        )
        AnalysisChunkPublication.publish(artifact, manifest, invalidStatus, 1_000L)
            .shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
            .reason shouldStartWith "refusing to persist a non-VALID analysis chunk"

        // VALID-marked but semantically broken: the schema gate rejects first.
        val broken = validResult(0).copy(corePageKeys = emptyList())
        AnalysisChunkPublication.publish(artifact, manifest, broken, 1_000L)
            .shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
            .reason shouldStartWith "analysis chunk invalid"

        artifact.readManifest().shouldNotBeNull().analysisChunks.shouldHaveSize(0)
    }

    @Test
    fun `stale manifest publication is rejected and the prior manifest stays authoritative`() = runTest {
        val artifact = artifactStore()
        val staleManifest = baseManifest()

        val committed = AnalysisChunkPublication.publish(artifact, staleManifest, validResult(0), 1_000L)
            .shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()

        // A crash-race caller publishing against the SUPERSEDED manifest is
        // rejected by the precondition, not merged; the durable manifest keeps
        // exactly the committed pointer list.
        AnalysisChunkPublication.publish(artifact, staleManifest, validResult(1), 2_000L)
            .shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        artifact.readManifest().shouldNotBeNull().analysisChunks shouldBe committed.manifest.analysisChunks
    }
}
