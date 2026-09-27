package eu.kanade.translation.persistence.artifact

import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.persistence.artifact.loadArtifact
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.security.MessageDigest

/**
 * The `checkpointOcr` transaction fault-injection and
 * CAS gate (contracts-state-transactions.md  §2). Every
 * crash boundary B1-B3 and every stale-identity rejection (BX) must leave the
 * prior manifest authoritative, at most an orphan sidecar, and never a
 * dangling pointer; the committed display pointer is never touched.
 */
class CheckpointOcrTransactionTest {

    private val layout = ChapterArtifactLayout("Chapter 1")

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

    /** OCR-complete page payload: no translation work, no cleaned image yet. */
    private fun ocrPage(text: String = "source", cleanedImageName: String? = null) = PageTranslation(
        sourceFileName = "page.jpg",
        blocks = mutableListOf(block(text)),
        imgWidth = 100f,
        imgHeight = 100f,
        decodeSampleSize = 1,
        ocrStatus = StageStatus.READY,
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        sourceFingerprint = hex64("source-bytes"),
        detectionFingerprint = hex64("detection"),
        ocrFingerprint = hex64("ocr"),
        cleanedImageName = cleanedImageName,
        inpaintMaskBoxes = listOf(eu.kanade.translation.model.InpaintMaskBox(0, 0, 10, 10, 1)),
    )

    private fun store(io: FakeChapterDocumentIo) = ChapterArtifactEngine(
        AtomicChapterDocuments(io),
        layout,
        object : CleanedImageProbe {
            override fun probe(input: InputStream): ProbedImage? =
                if (input.read() == 1) null else ProbedImage(100, 100)
        },
    )

    private fun identity(tag: String) = LegacySourceIdentity(
        sha256 = "sha-$tag",
        lengthBytes = tag.length.toLong(),
        lastModifiedMs = 1L,
    )

    private fun legacySnapshot(page: PageTranslation = ocrPage()) = ArtifactSeed(
        pages = mapOf("page.jpg" to ArtifactPageFacts(page, CleanedFileState.VALID)),
        glossary = emptyMap(),
        legacyIdentity = identity("v1"),
        sourceFileName = "Chapter 1.json",
        glossaryFileName = null,
        glossaryIdentity = null,
        migratedByVersionCode = 63L,
        migratedAtEpochMs = 42L,
    )

    /**
     * Standard branch fixture: legacy committed bundle + an active BATCH
     * candidate holding the post-OCR snapshot (the B0 state of ).
     * Returns the store, the manifest carrying the active candidate, and the
     * candidate generation id.
     */
    private class Fixture(
        val store: ChapterArtifactEngine,
        val manifest: ChapterArtifactManifest,
        val generationId: String,
        val ocrSnapshot: PageTranslation,
    )

    private fun fixtureWithActiveCandidate(io: FakeChapterDocumentIo, ocrSnapshot: PageTranslation = ocrPage()): Fixture {
        val store = store(io)
        val migrated = store.loadArtifact(legacySnapshot(ocrSnapshot)).manifest
        val opened = store.openCandidate(
            migrated,
            "page.jpg",
            ArtifactOrigin.BATCH,
            expectedPageVersion = 0L,
            dependencyFingerprint = "deps-v1",
            nowEpochMs = 500L,
        ).shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        val generationId = opened.generationId.shouldNotBeNull()
        val persisted = store.persistLiveCandidate(
            manifest = opened.manifest,
            pageKey = "page.jpg",
            generationId = generationId,
            expectedPageVersion = opened.manifest.pages.getValue("page.jpg").pageVersion,
            expectedDependencyFingerprint = "deps-v1",
            pageSnapshot = ocrSnapshot,
            origin = ArtifactOrigin.BATCH,
            sourceIdentity = SourceIdentity(
                pageKey = "page.jpg",
                sha256 = hex64("source-bytes"),
                width = 100,
                height = 100,
                orientation = "PORTRAIT",
            ),
            nowEpochMs = 501L,
        ).shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        return Fixture(store, persisted.manifest, generationId, ocrSnapshot)
    }

    /** Builds a well-formed checkpoint DTO for [ocrSnapshot]. */
    private fun checkpointFor(
        ocrSnapshot: PageTranslation,
        store: ChapterArtifactEngine,
        producerGenerationId: String?,
        contentFingerprint: String = hex64("ocr-content"),
    ): PageOcrCheckpoint {
        val snapshotFingerprint = StageFingerprints.pageSnapshot(ocrSnapshot)
        return PageOcrCheckpoint(
            pageKey = "page.jpg",
            naturalPageIndex = null,
            sourceIdentity = SourceIdentity(
                pageKey = "page.jpg",
                sha256 = hex64("source-bytes"),
                width = 100,
                height = 100,
                orientation = "PORTRAIT",
            ),
            detectionFingerprint = ocrSnapshot.detectionFingerprint,
            ocrFingerprint = ocrSnapshot.ocrFingerprint!!,
            ocrContentFingerprint = contentFingerprint,
            ocrPageSnapshotPointer = SidecarPointer(
                fileName = store.ocrStageSnapshotName("page.jpg", snapshotFingerprint),
                schemaVersion = 1,
                contentFingerprint = snapshotFingerprint,
            ),
            inpaintMaskRevision = ocrSnapshot.inpaintRevision,
            priorCommittedDisplay = null,
            producedByOrigin = ArtifactOrigin.BATCH,
            producerGenerationId = producerGenerationId,
            checkpointedAtEpochMs = 502L,
        )
    }

    private fun checkpointTransaction(
        store: ChapterArtifactEngine,
        manifest: ChapterArtifactManifest,
        ocrSnapshot: PageTranslation,
        checkpoint: PageOcrCheckpoint,
        mode: OcrCheckpointMode = OcrCheckpointMode.CLOSE,
        expectedPageVersion: Long = manifest.pages.getValue("page.jpg").pageVersion,
    ) = store.checkpointOcr(
        manifest = manifest,
        pageKey = "page.jpg",
        expectedPageVersion = expectedPageVersion,
        expectedDependencyFingerprint = "deps-v1",
        ocrSnapshot = ocrSnapshot,
        checkpoint = checkpoint,
        mode = mode,
        nowEpochMs = 502L,
    )

    // ------------------------------------------------------------------
    // Happy paths: CLOSE ( default) and REBASE.
    // ------------------------------------------------------------------

    @Test
    fun `close installs the checkpoint pointer and clears the batch candidate in one publication`() {
        val io = FakeChapterDocumentIo()
        val fx = fixtureWithActiveCandidate(io)
        val checkpoint = checkpointFor(fx.ocrSnapshot, fx.store, fx.generationId)
        val committedBefore = fx.manifest.pages.getValue("page.jpg").committed.shouldNotBeNull()

        val outcome = checkpointTransaction(fx.store, fx.manifest, fx.ocrSnapshot, checkpoint)
            .shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()

        val record = outcome.manifest.pages.getValue("page.jpg")
        val pointer = outcome.manifest.ocrCheckpoints.getValue("page.jpg")
        pointer.contentFingerprint shouldBe checkpoint.ocrContentFingerprint
        pointer.fileName shouldBe layout.ocrCheckpointFile("page.jpg", checkpoint.ocrContentFingerprint)
        record.candidate shouldBe null
        outcome.manifest.activeCandidateGenerationIds shouldBe emptySet()
        record.pageVersion shouldBe fx.manifest.pages.getValue("page.jpg").pageVersion + 1
        // 07: committed display untouched.
        record.committed shouldBe committedBefore
        record.displayState shouldBe PageDisplayState.DISPLAY_READY
        // The page's OCR stage record now points at the checkpoint snapshot.
        record.ocr.shouldNotBeNull().artifactFileName shouldBe checkpoint.ocrPageSnapshotPointer.fileName

        // Both sidecars are durable; the closed generation's CANCELLED record
        // was published and then left the reachability graph (the retention
        // sweep reclaims unreachable generation records, cancelCandidate
        // precedent), which the committed outcome reports.
        io.files.containsKey(pointer.fileName) shouldBe true
        io.files.containsKey(checkpoint.ocrPageSnapshotPointer.fileName) shouldBe true
        outcome.deletedFiles.shouldContain(layout.generationFile(fx.generationId))

        // The pointer resolves to a usable checkpoint whose snapshot is readable.
        val read = fx.store.readOcrCheckpoint(pointer).shouldBeInstanceOf<ChapterArtifactEngine.OcrCheckpointRead.Usable>()
        read.checkpoint.producedByOrigin shouldBe ArtifactOrigin.BATCH
        fx.store.readPageSnapshot(read.checkpoint.ocrPageSnapshotPointer.fileName).shouldNotBeNull()

        // Reachability: a retention sweep keeps both pointed sidecars.
        fx.store.reconcileRetention(outcome.manifest)
        io.files.containsKey(pointer.fileName) shouldBe true
        io.files.containsKey(checkpoint.ocrPageSnapshotPointer.fileName) shouldBe true
    }

    @Test
    fun `rebase closes the batch generation and opens a successor seeded with the checkpoint fingerprint`() {
        val io = FakeChapterDocumentIo()
        val fx = fixtureWithActiveCandidate(io)
        val checkpoint = checkpointFor(fx.ocrSnapshot, fx.store, fx.generationId)

        val outcome = checkpointTransaction(
            fx.store,
            fx.manifest,
            fx.ocrSnapshot,
            checkpoint,
            mode = OcrCheckpointMode.REBASE,
        ).shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()

        val record = outcome.manifest.pages.getValue("page.jpg")
        val successorId = outcome.generationId.shouldNotBeNull()
        val successor = record.candidate.shouldNotBeNull()
        successor.generationId shouldBe successorId
        successor.origin shouldBe ArtifactOrigin.BATCH
        // 03(c): the successor's fingerprint equals the checkpoint's.
        successor.dependencyFingerprint shouldBe checkpoint.ocrContentFingerprint
        outcome.manifest.activeCandidateGenerationIds shouldBe setOf(successorId)
        // The closed generation's CANCELLED record was published and then
        // left the reachability graph (retention sweep, cancelCandidate
        // precedent); the successor's ACTIVE record stays reachable.
        outcome.deletedFiles.shouldContain(layout.generationFile(fx.generationId))
        String(io.read(layout.generationFile(successorId))!!).shouldContain("\"lifecycle\":\"ACTIVE\"")
        outcome.manifest.ocrCheckpoints.getValue("page.jpg").contentFingerprint shouldBe
            checkpoint.ocrContentFingerprint

        // The successor's very first write validates against the checkpoint.
        val written = fx.store.persistLiveCandidate(
            manifest = outcome.manifest,
            pageKey = "page.jpg",
            generationId = successorId,
            expectedPageVersion = record.pageVersion,
            expectedDependencyFingerprint = checkpoint.ocrContentFingerprint,
            pageSnapshot = fx.ocrSnapshot,
            origin = ArtifactOrigin.BATCH,
            nowEpochMs = 503L,
        ).shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        written.manifest.pages.getValue("page.jpg").candidate.shouldNotBeNull()
            .generationId shouldBe successorId

        // A stale writer still holding the closed generation is fenced.
        val stale = fx.store.persistLiveCandidate(
            manifest = written.manifest,
            pageKey = "page.jpg",
            generationId = fx.generationId,
            expectedPageVersion = written.manifest.pages.getValue("page.jpg").pageVersion,
            expectedDependencyFingerprint = "deps-v1",
            pageSnapshot = fx.ocrSnapshot,
            origin = ArtifactOrigin.BATCH,
            nowEpochMs = 504L,
        )
        stale.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
    }

    // ------------------------------------------------------------------
    // 03.1: the adopt-committed branch (no active candidate).
    // ------------------------------------------------------------------

    /** Committed-only fixture: candidate promoted, so no candidate remains. */
    private fun fixtureWithCommittedOnly(
        io: FakeChapterDocumentIo,
        ocrSnapshot: PageTranslation = ocrPage(),
    ): Triple<ChapterArtifactEngine, ChapterArtifactManifest, PageTranslation> {
        val fx = fixtureWithActiveCandidate(io, ocrSnapshot)
        val promoted = fx.store.promoteLiveCandidate(
            manifest = fx.manifest,
            pageKey = "page.jpg",
            generationId = fx.generationId,
            expectedPageVersion = fx.manifest.pages.getValue("page.jpg").pageVersion,
            expectedDependencyFingerprint = "deps-v1",
            pageSnapshot = fx.ocrSnapshot,
            origin = ArtifactOrigin.BATCH,
            nowEpochMs = 502L,
        ).shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        return Triple(fx.store, promoted.manifest, fx.ocrSnapshot)
    }

    @Test
    fun `adopt installs the checkpoint pointer only and never touches the committed display`() {
        val io = FakeChapterDocumentIo()
        val (store, manifest, ocrSnapshot) = fixtureWithCommittedOnly(io)
        val committedSnapshot = store.readPageSnapshot(
            manifest.pages.getValue("page.jpg").committed.shouldNotBeNull().pageSnapshotFileName,
        ).shouldNotBeNull()
        val contentFingerprint = StageFingerprints.pageOcrContentFingerprint(
            pageKey = "page.jpg",
            naturalPageIndex = null,
            sourceSha256 = committedSnapshot.sourceFingerprint.orEmpty(),
            sourceWidth = committedSnapshot.imgWidth.toInt(),
            sourceHeight = committedSnapshot.imgHeight.toInt(),
            sourceOrientation = "PORTRAIT",
            detectionFingerprint = committedSnapshot.detectionFingerprint,
            ocrFingerprint = committedSnapshot.ocrFingerprint.orEmpty(),
            textless = committedSnapshot.isTextlessTerminal,
            inpaintMaskRevision = committedSnapshot.inpaintRevision,
            blocks = StageFingerprints.pageOcrContentBlocks(committedSnapshot),
            inpaintMaskBoxes = committedSnapshot.inpaintMaskBoxes,
        )
        val checkpoint = checkpointFor(ocrSnapshot, store, producerGenerationId = null, contentFingerprint)
        val committedBefore = manifest.pages.getValue("page.jpg").committed

        val outcome = checkpointTransaction(store, manifest, ocrSnapshot, checkpoint)
            .shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()

        val record = outcome.manifest.pages.getValue("page.jpg")
        outcome.manifest.ocrCheckpoints.keys shouldBe setOf("page.jpg")
        record.candidate shouldBe null
        // 07/ committed display untouched by construction.
        record.committed shouldBe committedBefore
        record.displayState shouldBe PageDisplayState.DISPLAY_READY
    }

    @Test
    fun `adopt rejects content drift and keeps the prior manifest authoritative`() {
        val io = FakeChapterDocumentIo()
        val (store, manifest, ocrSnapshot) = fixtureWithCommittedOnly(io)
        val committedBefore = manifest.pages.getValue("page.jpg").committed

        val checkpoint = checkpointFor(
            ocrSnapshot,
            store,
            producerGenerationId = null,
            contentFingerprint = hex64("drifted-ocr-content"),
        )

        val outcome = checkpointTransaction(store, manifest, ocrSnapshot, checkpoint)
            .shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()

        outcome.reason shouldContain "drift"
        store.readManifest() shouldBe manifest
        manifest.ocrCheckpoints shouldBe emptyMap()
        io.files.keys.none { it.startsWith("${layout.ocrCheckpointsRootDirectory}/") } shouldBe true
        store.reconcileRetention(manifest)
        store.readManifest()!!.pages.getValue("page.jpg").committed shouldBe committedBefore
    }

    @Test
    fun `adopt with an active candidate present requires the standard branch`() {
        val io = FakeChapterDocumentIo()
        val fx = fixtureWithActiveCandidate(io)
        val checkpoint = checkpointFor(fx.ocrSnapshot, fx.store, producerGenerationId = null)

        val outcome = checkpointTransaction(fx.store, fx.manifest, fx.ocrSnapshot, checkpoint)

        outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
            .reason shouldContain "standard checkpoint branch"
        fx.store.readManifest() shouldBe fx.manifest
        fx.manifest.ocrCheckpoints shouldBe emptyMap()
    }

    @Test
    fun `adopt without a committed bundle is rejected`() {
        val io = FakeChapterDocumentIo()
        val store = store(io)
        val migrated = store.loadArtifact(legacySnapshot()).manifest
        // Demote the committed bundle so neither candidate nor committed exists.
        val demoted = store.demoteLivePage(migrated, "page.jpg")
            .shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>().manifest
        val checkpoint = checkpointFor(ocrPage(), store, producerGenerationId = null)

        val outcome = checkpointTransaction(store, demoted, ocrPage(), checkpoint)

        outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
            .reason shouldContain "committed bundle missing"
    }

    // ------------------------------------------------------------------
    // /BX fault injection: every publication boundary.
    // ------------------------------------------------------------------

    @Test
    fun `checkpoint sidecar write failure leaves the candidate active and the prior manifest authoritative`() {
        val io = FakeChapterDocumentIo()
        val fx = fixtureWithActiveCandidate(io)
        val checkpoint = checkpointFor(fx.ocrSnapshot, fx.store, fx.generationId)
        io.writeNamesToFail += "/ocr/"

        val outcome = checkpointTransaction(fx.store, fx.manifest, fx.ocrSnapshot, checkpoint)

        outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        fx.store.readManifest() shouldBe fx.manifest
        // B1: no pointer, candidate still ACTIVE (close never published).
        fx.manifest.ocrCheckpoints shouldBe emptyMap()
        fx.manifest.pages.getValue("page.jpg").candidate.shouldNotBeNull()
        io.files.keys.none { it.startsWith("${layout.ocrCheckpointsRootDirectory}/") } shouldBe true
    }

    @Test
    fun `snapshot sidecar rename failure leaves at most an orphan temp`() {
        val io = FakeChapterDocumentIo()
        val fx = fixtureWithActiveCandidate(io)
        val checkpoint = checkpointFor(fx.ocrSnapshot, fx.store, fx.generationId)
        io.ownedRenamesToFail += AtomicChapterDocuments.tempNameFor(checkpoint.ocrPageSnapshotPointer.fileName)

        val outcome = checkpointTransaction(fx.store, fx.manifest, fx.ocrSnapshot, checkpoint)

        outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        fx.store.readManifest() shouldBe fx.manifest
        fx.manifest.ocrCheckpoints shouldBe emptyMap()
        io.files.containsKey(checkpoint.ocrPageSnapshotPointer.fileName) shouldBe false
        io.files.containsKey(
            AtomicChapterDocuments.tempNameFor(checkpoint.ocrPageSnapshotPointer.fileName),
        ) shouldBe true
    }

    @Test
    fun `manifest publication failure after durable sidecars keeps the pointer absent and orphans swept`() {
        val io = FakeChapterDocumentIo()
        val fx = fixtureWithActiveCandidate(io)
        val checkpoint = checkpointFor(fx.ocrSnapshot, fx.store, fx.generationId)
        io.ownedRenamesToFail += AtomicChapterDocuments.tempNameFor(layout.manifestFileName)

        val outcome = checkpointTransaction(fx.store, fx.manifest, fx.ocrSnapshot, checkpoint)

        outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        // The prior manifest stays authoritative: the pointer never dangles.
        val durable = fx.store.readManifest().shouldNotBeNull()
        durable shouldBe fx.manifest
        durable.ocrCheckpoints shouldBe emptyMap()
        durable.pages.getValue("page.jpg").candidate.shouldNotBeNull()
        // The sidecars are reachable-by-name orphans only; retention reclaims them.
        io.files.containsKey(checkpoint.ocrPageSnapshotPointer.fileName) shouldBe true
        io.files.containsKey(layout.ocrCheckpointFile("page.jpg", checkpoint.ocrContentFingerprint)) shouldBe true
        fx.store.reconcileRetention(durable)
        io.files.containsKey(checkpoint.ocrPageSnapshotPointer.fileName) shouldBe false
        io.files.containsKey(layout.ocrCheckpointFile("page.jpg", checkpoint.ocrContentFingerprint)) shouldBe false
    }

    @Test
    fun `manifest temp write failure keeps the prior manifest authoritative`() {
        val io = FakeChapterDocumentIo()
        val fx = fixtureWithActiveCandidate(io)
        val checkpoint = checkpointFor(fx.ocrSnapshot, fx.store, fx.generationId)
        io.writeNamesToFail += AtomicChapterDocuments.tempNameFor(layout.manifestFileName)

        val outcome = checkpointTransaction(fx.store, fx.manifest, fx.ocrSnapshot, checkpoint)

        outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        fx.store.readManifest() shouldBe fx.manifest
        fx.manifest.ocrCheckpoints shouldBe emptyMap()
        // Orphan sidecar only.
        io.files.containsKey(layout.ocrCheckpointFile("page.jpg", checkpoint.ocrContentFingerprint)) shouldBe true
    }

    @Test
    fun `stale caller snapshot recovers through the one-shot retry and commits the checkpoint`() {
        val io = FakeChapterDocumentIo()
        val fx = fixtureWithActiveCandidate(io)
        val checkpoint = checkpointFor(fx.ocrSnapshot, fx.store, fx.generationId)
        val stale = fx.manifest.copy(updatedAtEpochMs = 999L)

        val outcome = checkpointTransaction(fx.store, stale, fx.ocrSnapshot, checkpoint)

        //   a stale caller snapshot (the façade cached the
        // pre-verify manifest while the background health verify republished)
        // is retried ONCE against the freshly re-read durable manifest — the
        // CLOSE rebuilds from the FRESH manifest, so nothing the concurrent
        // writer published is reverted, and the healthy chapter no longer
        // sees a spurious CHECKPOINT_REJECTED.
        val committed = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        val durable = fx.store.readManifest().shouldNotBeNull()
        durable shouldBe committed.manifest
        durable.ocrCheckpoints.getValue("page.jpg").contentFingerprint shouldBe checkpoint.ocrContentFingerprint
        durable.pages.getValue("page.jpg").candidate shouldBe null
        durable.pages.getValue("page.jpg").pageVersion shouldBe
            fx.manifest.pages.getValue("page.jpg").pageVersion + 1
        // Both sidecars are durable.
        io.files.containsKey(checkpoint.ocrPageSnapshotPointer.fileName) shouldBe true
        io.files.containsKey(layout.ocrCheckpointFile("page.jpg", checkpoint.ocrContentFingerprint)) shouldBe true
    }

    @Test
    fun `stale candidate identity rejections leave the prior manifest authoritative`() {
        val io = FakeChapterDocumentIo()
        val fx = fixtureWithActiveCandidate(io)
        val staleGeneration = checkpointFor(fx.ocrSnapshot, fx.store, "g-other")
        val staleDependency = checkpointFor(fx.ocrSnapshot, fx.store, fx.generationId)

        // Wrong candidate generation.
        checkpointTransaction(fx.store, fx.manifest, fx.ocrSnapshot, staleGeneration)
            .shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        // Changed dependency fingerprint (no grace clause,.1/C2).
        val staleDependencyOutcome = fx.store.checkpointOcr(
            manifest = fx.manifest,
            pageKey = "page.jpg",
            expectedPageVersion = fx.manifest.pages.getValue("page.jpg").pageVersion,
            expectedDependencyFingerprint = "deps-stale",
            ocrSnapshot = fx.ocrSnapshot,
            checkpoint = staleDependency,
            nowEpochMs = 502L,
        )
        staleDependencyOutcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        // Stale artifact page version.
        val staleVersionOutcome = checkpointTransaction(
            fx.store,
            fx.manifest,
            fx.ocrSnapshot,
            staleDependency,
            expectedPageVersion = 999L,
        )
        staleVersionOutcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()

        // Every rejection leaves the manifest and candidate untouched.
        fx.store.readManifest() shouldBe fx.manifest
        fx.manifest.pages.getValue("page.jpg").candidate.shouldNotBeNull().generationId shouldBe fx.generationId
        fx.manifest.ocrCheckpoints shouldBe emptyMap()
        io.files.keys.none { it.startsWith("${layout.ocrCheckpointsRootDirectory}/") } shouldBe true
    }

    @Test
    fun `checkpoint success clears the stale OCR durable failure entry but no other ledger key`() {
        val io = FakeChapterDocumentIo()
        val fx = fixtureWithActiveCandidate(io)

        // A prior failed attempt left OCR + TRANSLATION ledger entries on this
        // page (written through the real API on the same candidate).
        fun ledgerFailure(stage: ArtifactStage) = DurableFailureMetadata(
            pageKey = "page.jpg",
            stage = stage,
            status = ArtifactStageStatus.FAILED_RETRYABLE,
            category = FailureCategory.PROTOCOL,
            retryCount = 1,
            lastFailureMessage = "prior attempt failed",
            lastFailedAtEpochMs = 1L,
        )
        var manifest = fx.manifest
        for (stage in listOf(ArtifactStage.OCR, ArtifactStage.TRANSLATION)) {
            manifest = fx.store.persistLiveCandidate(
                manifest = manifest,
                pageKey = "page.jpg",
                generationId = fx.generationId,
                expectedPageVersion = manifest.pages.getValue("page.jpg").pageVersion,
                expectedDependencyFingerprint = "deps-v1",
                pageSnapshot = fx.ocrSnapshot,
                origin = ArtifactOrigin.BATCH,
                durableFailure = ledgerFailure(stage),
                nowEpochMs = 501L,
            ).shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>().manifest
        }
        manifest.durableFailures.keys shouldBe setOf("page.jpg:OCR", "page.jpg:TRANSLATION")

        // A later successful attempt checkpoints the OCR content.
        val outcome = checkpointTransaction(
            fx.store,
            manifest,
            fx.ocrSnapshot,
            checkpointFor(fx.ocrSnapshot, fx.store, fx.generationId),
            mode = OcrCheckpointMode.CLOSE,
        ).shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()

        // F-W3-1: the stale OCR entry is cleared on success (the page's OCR is
        // now durably checkpointed), while every other ledger key survives for
        // its own stage's success/cleanup path.
        outcome.manifest.durableFailures.keys shouldBe setOf("page.jpg:TRANSLATION")
        fx.store.readManifest().shouldNotBeNull().durableFailures.keys shouldBe setOf("page.jpg:TRANSLATION")
    }

    // ------------------------------------------------------------------
    // Read-side revision gate: a persisted checkpoint whose
    // inpaintMaskRevision is below the current revision is never Usable
    // evidence for planning.
    // ------------------------------------------------------------------

    @Test
    fun `stale inpaintMaskRevision checkpoint fails validation and reads back as absent`() {
        val io = FakeChapterDocumentIo()
        val fx = fixtureWithActiveCandidate(io)
        val stale = checkpointFor(fx.ocrSnapshot, fx.store, fx.generationId)
            .copy(inpaintMaskRevision = PageTranslation.CURRENT_INPAINT_REVISION - 1)

        stale.validationError().shouldNotBeNull() shouldContain "stale inpaintMaskRevision"

        val fileName = layout.ocrCheckpointFile("page.jpg", stale.ocrContentFingerprint)
        io.write(fileName, ArtifactDocumentJson.encodeToString(stale).toByteArray())
        fx.store.readOcrCheckpoint(
            SidecarPointer(
                fileName = fileName,
                schemaVersion = 1,
                contentFingerprint = stale.ocrContentFingerprint,
            ),
        ) shouldBe ChapterArtifactEngine.OcrCheckpointRead.Absent
    }
}
