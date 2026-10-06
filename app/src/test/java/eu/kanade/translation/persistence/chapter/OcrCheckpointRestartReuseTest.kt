package eu.kanade.translation.persistence.chapter

import com.hippo.unifile.FakeUniFile
import com.hippo.unifile.UniFile
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.persistence.artifact.ArtifactOrigin
import eu.kanade.translation.persistence.artifact.AtomicChapterDocuments
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactLayout
import eu.kanade.translation.persistence.artifact.ChapterArtifactManifest
import eu.kanade.translation.persistence.artifact.PageOcrCheckpoint
import eu.kanade.translation.persistence.artifact.SidecarPointer
import eu.kanade.translation.persistence.artifact.SourceIdentity
import eu.kanade.translation.persistence.artifact.StageFingerprints
import eu.kanade.translation.persistence.artifact.UniFileChapterDocumentIo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

/**
 *  Stage 1 M1 milestone proof (contracts-state-transactions.md §2,
 * OCR page → [ChapterTranslationStore.checkpointOcr] →
 * lease release → simulated process restart (fresh stores over the same
 * document set) → the checkpointed OCR is reusable by Manual/Auto
 * (READER_ADHOC) AND Batch origins alike, and a fingerprint-mismatched
 * checkpoint is refused, never silently adopted.
 */
class OcrCheckpointRestartReuseTest {

    @TempDir
    lateinit var mangaDir: File

    private fun hex64(tag: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(tag.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun block(text: String = "source", blockId: String? = "b1") = TranslationBlock(
        blockId = blockId,
        text = text,
        translation = "",
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )

    private fun ocrPage(pageKey: String, text: String = "source") = PageTranslation(
        sourceFileName = pageKey,
        blocks = mutableListOf(block(text)),
        imgWidth = 100f,
        imgHeight = 100f,
        decodeSampleSize = 1,
        ocrStatus = StageStatus.READY,
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        sourceFingerprint = hex64("source-$pageKey"),
        detectionFingerprint = hex64("detection-$pageKey"),
        ocrFingerprint = hex64("ocr-$pageKey"),
        inpaintMaskBoxes = listOf(eu.kanade.translation.model.InpaintMaskBox(0, 0, 10, 10, 1)),
    )

    private fun root(): UniFile = FakeUniFile(parent = null, backing = mangaDir)

    private fun artifactStore(): ChapterArtifactEngine =
        ChapterArtifactEngine(
            AtomicChapterDocuments(UniFileChapterDocumentIo(root())),
            ChapterArtifactLayout("Chapter 1"),
        )

    private fun lazyStore(): ChapterTranslationStore = ChapterTranslationStore.lazy(
        artifactParentResolver = { root().createFile("Chapter 1.json")!! },
        artifactParent = root(),
        artifactFileName = "Chapter 1.json",
    )

    private fun ChapterArtifactManifest.pageRecord(pageKey: String) = pages.getValue(pageKey)

    /**
     * Batch lane: acquire the lease, run the OCR merge under the lease token,
     * checkpoint, and only then release the lease ( order).
     */
    private suspend fun mergeAndCheckpoint(
        store: ChapterTranslationStore,
        pageKey: String = "p1",
        text: String = "source",
    ): ChapterTranslationStore.PageSnapshot {
        val lease = store.tryAcquirePageStageLease(pageKey, PageStage.Ocr, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
        val before = store.snapshot(pageKey)
        store.mergeOcr(
            OcrStagePatch(
                pageKey = pageKey,
                generation = before.generation,
                expectedPageVersion = before.pageVersion,
                expectedPriorOcrFingerprints = before.page?.ocrBlockFingerprints().orEmpty(),
                ocrResult = ocrPage(pageKey, text),
                expectedLeaseToken = lease.token,
            ),
            description = "m1 batch ocr persist",
        ).shouldBeInstanceOf<StagePatchResult.Accepted>()
        val after = store.snapshot(pageKey)
        val candidateGenerationId = after.candidateGenerationId.shouldNotBeNull()
        val dependencyFingerprint = after.dependencyFingerprint.shouldNotBeNull()
        store.checkpointOcr(
            pageKey = pageKey,
            generation = after.generation,
            expectedPageVersion = after.pageVersion,
            expectedLeaseToken = lease.token,
            expectedCandidateGenerationId = candidateGenerationId,
            expectedArtifactPageVersion = after.artifactPageVersion,
            expectedDependencyFingerprint = dependencyFingerprint,
            sourceSha256 = hex64("source-$pageKey"),
            sourceOrientation = "PORTRAIT",
        ).shouldBeInstanceOf<CheckpointOcrResult.Committed>()
        // The lease is released strictly AFTER the checkpoint committed.
        store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH)
        store.pageLeaseOwner(pageKey) shouldBe null
        return after
    }

    @Test
    fun `fresh ocr checkpoint records current mask basis without promoting cleaned output revision`() = runTest {
        val store = lazyStore()
        store.preRegisterPages(listOf("p1"))
        // Production shape: a fresh OCR result keeps the never-inpainted
        // revision default (only inpaint publication stamps
        // CURRENT_INPAINT_REVISION); the checkpoint records its current mask
        // basis without certifying a cleaned image that was never published.
        val lease = store.tryAcquirePageStageLease("p1", PageStage.Ocr, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
        val before = store.snapshot("p1")
        store.mergeOcr(
            OcrStagePatch(
                pageKey = "p1",
                generation = before.generation,
                expectedPageVersion = before.pageVersion,
                expectedPriorOcrFingerprints = before.page?.ocrBlockFingerprints().orEmpty(),
                ocrResult = PageTranslation(
                    sourceFileName = "p1",
                    blocks = mutableListOf(block("source")),
                    imgWidth = 100f,
                    imgHeight = 100f,
                    decodeSampleSize = 1,
                    ocrStatus = StageStatus.READY,
                    sourceFingerprint = hex64("source-p1"),
                    detectionFingerprint = hex64("detection-p1"),
                    ocrFingerprint = hex64("ocr-p1"),
                    inpaintMaskBoxes = listOf(eu.kanade.translation.model.InpaintMaskBox(0, 0, 10, 10, 1)),
                ),
                expectedLeaseToken = lease.token,
            ),
            description = "fresh ocr persist",
        ).shouldBeInstanceOf<StagePatchResult.Accepted>()
        val after = store.snapshot("p1")
        after.page?.inpaintRevision shouldBe 0

        store.checkpointOcr(
            pageKey = "p1",
            generation = after.generation,
            expectedPageVersion = after.pageVersion,
            expectedLeaseToken = lease.token,
            expectedCandidateGenerationId = after.candidateGenerationId.shouldNotBeNull(),
            expectedArtifactPageVersion = after.artifactPageVersion,
            expectedDependencyFingerprint = after.dependencyFingerprint.shouldNotBeNull(),
            sourceSha256 = hex64("source-p1"),
            sourceOrientation = "PORTRAIT",
        ).shouldBeInstanceOf<CheckpointOcrResult.Committed>()

        // The checkpoint carries the current OCR-mask basis and reads back
        // Usable, while neither the OCR snapshot nor the live page claims a
        // current cleaned-output revision.
        val artifact = artifactStore()
        val pointer = artifact.readManifest().shouldNotBeNull().ocrCheckpoints.getValue("p1")
        val read = artifact.readOcrCheckpoint(pointer)
            .shouldBeInstanceOf<ChapterArtifactEngine.OcrCheckpointRead.Usable>()
        read.checkpoint.inpaintMaskRevision shouldBe PageTranslation.CURRENT_INPAINT_REVISION
        // Snapshot state keeps its real cleaned-output revision.
        artifact.readPageSnapshot(read.checkpoint.ocrPageSnapshotPointer.fileName)
            .shouldNotBeNull().inpaintRevision shouldBe 0
        store.snapshot("p1").page.shouldNotBeNull().inpaintRevision shouldBe 0
        store.releasePageStageLease("p1", PageWriteOrigin.BATCH)
    }

    @Test
    fun `m1_ checkpointed ocr survives a process restart and is reused by reader and batch origins`() = runTest {
        val store = lazyStore()
        store.preRegisterPages(listOf("p1"))
        val after = mergeAndCheckpoint(store)
        val candidateGenerationId = after.candidateGenerationId.shouldNotBeNull()

        // ---- simulated process restart: fresh store instances over the SAME
        // document set; all in-memory state (leases, live pages) is gone. ----
        val artifact = artifactStore()
        val manifest = artifact.readManifest().shouldNotBeNull()
        val pointer = manifest.ocrCheckpoints["p1"].shouldNotBeNull()
        val read = artifact.readOcrCheckpoint(pointer)
            .shouldBeInstanceOf<ChapterArtifactEngine.OcrCheckpointRead.Usable>()
        // Origin-neutral checkpoint: provenance recorded, ownership none.
        read.checkpoint.producedByOrigin shouldBe ArtifactOrigin.BATCH
        read.checkpoint.producerGenerationId shouldBe candidateGenerationId
        val checkpointSnapshot = artifact.readPageSnapshot(read.checkpoint.ocrPageSnapshotPointer.fileName)
            .shouldNotBeNull()
        checkpointSnapshot.blocks.single().text shouldBe "source"
        // No BATCH candidate ownership survives the checkpoint; no committed
        // display was touched (there is none yet).
        manifest.pageRecord("p1").candidate shouldBe null
        manifest.pageRecord("p1").committed shouldBe null

        // ---- Origin-neutral reuse: a fresh READER_ADHOC (Manual/Auto) candidate
        // opens from the checkpoint — impossible while a BATCH candidate held the
        // page before. ----
        val pageVersion = manifest.pageRecord("p1").pageVersion
        val readerOpen = artifact.openCandidate(
            manifest,
            "p1",
            ArtifactOrigin.READER_ADHOC,
            expectedPageVersion = pageVersion,
            dependencyFingerprint = read.checkpoint.ocrContentFingerprint,
            nowEpochMs = 600L,
        ).shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        val readerGenerationId = readerOpen.generationId.shouldNotBeNull()
        val readerPersist = artifact.persistLiveCandidate(
            manifest = readerOpen.manifest,
            pageKey = "p1",
            generationId = readerGenerationId,
            expectedPageVersion = readerOpen.manifest.pageRecord("p1").pageVersion,
            expectedDependencyFingerprint = read.checkpoint.ocrContentFingerprint,
            pageSnapshot = checkpointSnapshot,
            origin = ArtifactOrigin.READER_ADHOC,
            nowEpochMs = 601L,
        ).shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        val readerPromoted = artifact.promoteLiveCandidate(
            manifest = readerPersist.manifest,
            pageKey = "p1",
            generationId = readerGenerationId,
            expectedPageVersion = readerPersist.manifest.pageRecord("p1").pageVersion,
            expectedDependencyFingerprint = read.checkpoint.ocrContentFingerprint,
            pageSnapshot = checkpointSnapshot,
            origin = ArtifactOrigin.READER_ADHOC,
            nowEpochMs = 602L,
        ).shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        readerPromoted.manifest.pageRecord("p1").candidate shouldBe null

        // ----.1 adopt-committed after restart: Batch checkpoints the
        // reader-committed page WITHOUT any active candidate; the committed
        // display is untouched. ----
        val reopened = ChapterTranslationStore.openArtifact(root(), "Chapter 1.json")
        val adoptLease = reopened.tryAcquirePageStageLease("p1", PageStage.Ocr, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
        val adoptSnapshot = reopened.snapshot("p1")
        val adoptCommittedBefore = reopened.artifactManifest.shouldNotBeNull()
            .pageRecord("p1").committed.shouldNotBeNull()
        val adopt = reopened.checkpointOcr(
            pageKey = "p1",
            generation = adoptSnapshot.generation,
            expectedPageVersion = adoptSnapshot.pageVersion,
            expectedLeaseToken = adoptLease.token,
            expectedCandidateGenerationId = null,
            expectedArtifactPageVersion = adoptSnapshot.artifactPageVersion,
            expectedDependencyFingerprint = null,
            sourceSha256 = hex64("source-p1"),
            sourceOrientation = "PORTRAIT",
        ).shouldBeInstanceOf<CheckpointOcrResult.Committed>()
        reopened.releasePageStageLease("p1", PageWriteOrigin.BATCH)
        adopt.manifest.pageRecord("p1").committed shouldBe adoptCommittedBefore
        adopt.manifest.pageRecord("p1").candidate shouldBe null
        adopt.manifest.ocrCheckpoints.keys shouldBe setOf("p1")

        // ---- Drift refusal: a checkpoint whose OCR content fingerprint does
        // not match the committed bundle is REJECTED, never adopted. ----
        val driftCheckpoint = PageOcrCheckpoint(
            pageKey = "p1",
            naturalPageIndex = null,
            sourceIdentity = SourceIdentity(
                pageKey = "p1",
                sha256 = hex64("source-p1"),
                width = 100,
                height = 100,
                orientation = "PORTRAIT",
            ),
            detectionFingerprint = hex64("detection-p1"),
            ocrFingerprint = hex64("ocr-p1"),
            ocrContentFingerprint = hex64("drifted-ocr-content"),
            ocrPageSnapshotPointer = SidecarPointer(
                fileName = "Chapter 1_artifacts/artifacts/p1-ocr/f-drift.json",
                schemaVersion = 1,
                // The snapshot pointer itself is well formed; only the CONTENT
                // fingerprint drifts, so the rejection lands on the drift rule.
                contentFingerprint = StageFingerprints.pageSnapshot(checkpointSnapshot),
            ),
            inpaintMaskRevision = PageTranslation.CURRENT_INPAINT_REVISION,
            producedByOrigin = ArtifactOrigin.BATCH,
            producerGenerationId = null,
            checkpointedAtEpochMs = 700L,
        )
        val driftOutcome = artifactStore().checkpointOcr(
            manifest = adopt.manifest,
            pageKey = "p1",
            expectedPageVersion = adopt.manifest.pageRecord("p1").pageVersion,
            expectedDependencyFingerprint = null,
            ocrSnapshot = checkpointSnapshot,
            checkpoint = driftCheckpoint,
            nowEpochMs = 701L,
        )
        driftOutcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
            .reason shouldContain "drift"
        // The prior manifest stays authoritative: the reader-committed display
        // pointer and the installed checkpoint are unchanged.
        artifactStore().readManifest().shouldNotBeNull() shouldBe adopt.manifest

        // ---- Batch reuse on the final durable state: a fresh BATCH candidate
        // opens from the (adopted) checkpoint, too. ----
        artifact.openCandidate(
            adopt.manifest,
            "p1",
            ArtifactOrigin.BATCH,
            expectedPageVersion = adopt.manifest.pageRecord("p1").pageVersion,
            dependencyFingerprint = read.checkpoint.ocrContentFingerprint,
            nowEpochMs = 702L,
        ).shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
    }

    @Test
    fun `m1_ checkpoint without a held lease is rejected and the prior manifest stays authoritative`() = runTest {
        val store = lazyStore()
        store.preRegisterPages(listOf("p1"))
        val before = store.snapshot("p1")
        store.mergeOcr(
            OcrStagePatch(
                pageKey = "p1",
                generation = before.generation,
                expectedPageVersion = before.pageVersion,
                expectedPriorOcrFingerprints = emptyList(),
                ocrResult = ocrPage("p1"),
            ),
            description = "unleased ocr persist",
        ).shouldBeInstanceOf<StagePatchResult.Accepted>()
        val after = store.snapshot("p1")
        val manifestBefore = artifactStore().readManifest().shouldNotBeNull()

        // No lease is held: the checkpoint is refused outright.
        val result = store.checkpointOcr(
            pageKey = "p1",
            generation = after.generation,
            expectedPageVersion = after.pageVersion,
            expectedLeaseToken = 12345L,
            expectedCandidateGenerationId = after.candidateGenerationId,
            expectedArtifactPageVersion = after.artifactPageVersion,
            expectedDependencyFingerprint = after.dependencyFingerprint,
            sourceSha256 = hex64("source-p1"),
            sourceOrientation = "PORTRAIT",
        )
        result.shouldBeInstanceOf<CheckpointOcrResult.Rejected>()
        artifactStore().readManifest().shouldNotBeNull() shouldBe manifestBefore
        manifestBefore.ocrCheckpoints shouldBe emptyMap()
    }

    @Test
    fun `m1_ stale token candidate generation or dependency fingerprint fence the checkpoint`() = runTest {
        val store = lazyStore()
        store.preRegisterPages(listOf("p1"))
        mergeAndCheckpoint(store)

        // Re-run the page: a fresh BATCH lease + candidate for the second pass.
        val lease = store.tryAcquirePageStageLease("p1", PageStage.Ocr, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
        val before = store.snapshot("p1")
        store.mergeOcr(
            OcrStagePatch(
                pageKey = "p1",
                generation = before.generation,
                expectedPageVersion = before.pageVersion,
                expectedPriorOcrFingerprints = before.page?.ocrBlockFingerprints().orEmpty(),
                ocrResult = ocrPage("p1", text = "source-v2"),
                expectedLeaseToken = lease.token,
            ),
            description = "second pass ocr persist",
        ).shouldBeInstanceOf<StagePatchResult.Accepted>()
        val after = store.snapshot("p1")
        val manifest = artifactStore().readManifest().shouldNotBeNull()

        suspend fun attempt(
            token: Long = lease.token,
            candidateGenerationId: String? = after.candidateGenerationId,
            dependencyFingerprint: String? = after.dependencyFingerprint,
        ) = store.checkpointOcr(
            pageKey = "p1",
            generation = after.generation,
            expectedPageVersion = after.pageVersion,
            expectedLeaseToken = token,
            expectedCandidateGenerationId = candidateGenerationId,
            expectedArtifactPageVersion = after.artifactPageVersion,
            expectedDependencyFingerprint = dependencyFingerprint,
            sourceSha256 = hex64("source-p1"),
            sourceOrientation = "PORTRAIT",
        )

        // Changed lease token.
        attempt(token = lease.token + 1).shouldBeInstanceOf<CheckpointOcrResult.Rejected>()
        // Changed candidate generation.
        attempt(candidateGenerationId = "g-stale").shouldBeInstanceOf<CheckpointOcrResult.Rejected>()
        // Changed dependency fingerprint (no grace clause on this path).
        attempt(dependencyFingerprint = "stale-deps").shouldBeInstanceOf<CheckpointOcrResult.Rejected>()

        // Every rejection left the prior manifest authoritative; the FIRST
        // checkpoint is still the installed one.
        artifactStore().readManifest().shouldNotBeNull() shouldBe manifest
        manifest.ocrCheckpoints.keys shouldBe setOf("p1")
        val firstPointer = manifest.ocrCheckpoints.getValue("p1")
        manifest.pageRecord("p1").candidate.shouldNotBeNull()

        // The correctly-fenced checkpoint still succeeds afterwards.
        attempt().shouldBeInstanceOf<CheckpointOcrResult.Committed>()
        store.releasePageStageLease("p1", PageWriteOrigin.BATCH)
        artifactStore().readManifest().shouldNotBeNull().ocrCheckpoints
            .getValue("p1").contentFingerprint shouldNotBe firstPointer.contentFingerprint
    }
}
