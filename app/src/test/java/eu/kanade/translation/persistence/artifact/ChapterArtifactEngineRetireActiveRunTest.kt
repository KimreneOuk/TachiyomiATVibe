package eu.kanade.translation.persistence.artifact

import eu.kanade.translation.persistence.artifact.loadArtifact
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 *   the CAS'd `activeRun` retirement transaction. A user reset means
 * the recorded run must never short-circuit a future dispatch, so the reset
 * paths retire the manifest's `activeRun` pointer through this transaction.
 * The retired run-record SIDECAR stays on disk (retention owns deletion: the
 * pointer is gone, so the next reachability sweep reclaims the orphaned file).
 */
class ChapterArtifactEngineRetireActiveRunTest {

    private fun hex64(tag: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(tag.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    /**
     * One artifact store over one in-memory document IO (the store and the
     * retention assertions MUST share the same document IO), with ARTIFACTS
     * authority and a durably published run record.
     */
    private fun artifactWithCompleteRun(): Quadruple<ChapterArtifactEngine, SidecarPointer, ChapterRunRecord, AtomicChapterDocuments> {
        val documents = AtomicChapterDocuments(FakeChapterDocumentIo())
        val artifact = ChapterArtifactEngine(documents, ChapterArtifactLayout("Chapter 1"))
        var manifest = artifact
            .loadArtifact(ArtifactSeed(createdAtEpochMs = 1L))
            .manifest
        manifest = manifest.copy(
            cutoverAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )
        check(artifact.publishManifest(manifest)) { "fixture: authority flip publish failed" }
        val record = ChapterRunRecord(
            runId = "run-li2-store-1",
            state = ChapterRunState.RUN_SNAPSHOT,
            frozenConfig = eu.kanade.translation.pipeline.batch.ChapterProfileBatchCoordinator.frozenRunConfig(
                sourceLang = "ja",
                targetLang = "en",
                ocrEngine = "FakeOcrEngine",
                inpaintMode = "OFF",
                providerKey = "fake:provider",
            ),
            frozenRunConfigFingerprint = hex64("frozen-config"),
            orderedSourceDigest = hex64("ordered-source"),
            analysisPolicyFingerprint = hex64("analysis-policy"),
            envelopePolicyFingerprint = hex64("envelope-policy"),
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )
        val publication = artifact.publishActiveRun(
            manifest = artifact.readManifest().shouldNotBeNull(),
            record = record,
            contentFingerprint = hex64("complete-run-record"),
        ).shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        val pointer = publication.manifest.activeRun.shouldNotBeNull()
        return Quadruple(artifact, pointer, record, documents)
    }

    /** Minimal 4-tuple so the fixture can return its shared document IO too. */
    data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    @Test
    fun `retireActiveRun clears the pointer and is idempotent`() {
        val (artifact, _, _, _) = artifactWithCompleteRun()
        val durable = artifact.readManifest().shouldNotBeNull()

        val outcome = artifact.retireActiveRun(durable, "chapter data reset")
        val committed = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        committed.manifest.activeRun shouldBe null
        artifact.readManifest().shouldNotBeNull().activeRun shouldBe null

        // Idempotent: retiring again commits the unchanged manifest.
        val durableAfterFirst = artifact.readManifest().shouldNotBeNull()
        val second = artifact.retireActiveRun(durableAfterFirst, "chapter data reset")
        second.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
            .manifest shouldBe durableAfterFirst
    }

    @Test
    fun `retireActiveRun with a stale manifest snapshot recovers through the one-shot retry`() {
        val (artifact, _, _, _) = artifactWithCompleteRun()
        //  the retirement seam joined the   one-shot stale-manifest
        // retry (the batch resume teardown hit the same spurious stale-CAS
        // rejection the publish/checkpoint seams did — same contract, same
        // adaptation this test made when  wrapped checkpointOcr). A stale
        // snapshot now triggers exactly ONE fresh-read retry that retires the
        // pointer; a retry that also fails still returns Rejected.
        val stale = artifact.readManifest().shouldNotBeNull().copy(updatedAtEpochMs = 99L)

        val outcome = artifact.retireActiveRun(stale, "chapter data reset")

        val committed = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        committed.manifest.activeRun shouldBe null
        artifact.readManifest().shouldNotBeNull().activeRun shouldBe null
    }

    @Test
    fun `retireActiveRun publication failure still rejects as-is without a retry`() {
        val (artifact, pointer, _, documents) = artifactWithCompleteRun()
        val durable = artifact.readManifest().shouldNotBeNull()
        val io = documents.rawIo() as FakeChapterDocumentIo
        val layout = ChapterArtifactLayout("Chapter 1")
        // Fail ONLY the manifest promotion rename: the first attempt rejects
        // with the publication reason (never the stale CAS), so the retry
        // wrapper must return it as-is with no second attempt.
        io.ownedRenamesToFail.add(AtomicChapterDocuments.tempNameFor(layout.manifestFileName))

        val outcome = artifact.retireActiveRun(durable, "chapter data reset")

        val rejected = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        rejected.reason shouldBe "manifest publication failed; active run pointer unchanged"
        artifact.readManifest().shouldNotBeNull().activeRun.shouldNotBeNull() shouldBe pointer
    }

    @Test
    fun `the retired run record sidecar becomes a retention orphan`() {
        val (artifact, pointer, _, documents) = artifactWithCompleteRun()
        val durable = artifact.readManifest().shouldNotBeNull()
        val retirement = artifact.retireActiveRun(durable, "chapter data reset")
            .shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()

        // The sidecar file is still on disk (the transaction never deletes),
        // but the retired pointer no longer reaches it: the next retention
        // sweep reclaims the orphaned record file.
        val result = ArtifactRetention(documents.rawIo(), ChapterArtifactLayout("Chapter 1"))
            .reconcileRetention(retirement.manifest)
        result.deletedNames shouldContain pointer.fileName
    }
}
