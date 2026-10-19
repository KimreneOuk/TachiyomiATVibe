package eu.kanade.translation.persistence

import eu.kanade.translation.context.*
import eu.kanade.translation.model.StageFingerprints
import eu.kanade.translation.model.TranslationProvenancePage
import eu.kanade.translation.model.OcrBlockContent
import eu.kanade.translation.model.EnvelopePlanInputPage

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * P3A publication receipts: every failed transaction must distinguish an
 * unchanged rejection (no receipt, no advanced files) from a
 * primary-advanced/replica-failed incomplete outcome carrying the actual
 * advanced manifest and orphan evidence, and a recorded receipt may only be
 * finished while the durable state it describes is still current — a newer
 * state is never overwritten to finish an older receipt.
 */
class ManifestPublicationReceiptTest {

    private val layout = ChapterArtifactLayout("Chapter 1")
    private val manifestTmpName = AtomicChapterDocuments.tempNameFor(layout.manifestFileName)
    private val hex64 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    private fun artifactStore(io: FakeChapterDocumentIo) =
        ChapterArtifactEngine(AtomicChapterDocuments(io), layout)

    private fun block() = TranslationBlock(
        text = "source",
        translation = "target",
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )

    private fun pageSnapshot() = PageTranslation(
        blocks = mutableListOf(block()),
        imgWidth = 100f,
        imgHeight = 100f,
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        renderStatus = StageStatus.READY,
        cleanedImageName = "page.cleaned.abc.jpg",
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
    )

    private fun seededSnapshot() = ArtifactSeed(
        pages = mapOf("page.jpg" to ArtifactPageFacts(pageSnapshot(), CleanedFileState.VALID)),
        createdAtEpochMs = 42L,
    )

    private fun runRecord() = ChapterRunRecord(
        runId = "run-1757050000000-a1b2c3d4",
        state = ChapterRunState.RUN_SNAPSHOT,
        frozenConfig = RunConfigSnapshot(
            sourceLang = "ja",
            targetLang = "en",
            ocrEngine = "onnx-v3",
            ocrModelHash = hex64,
            detectorModelHash = hex64,
            inpaintMode = "QUALITY",
            providerKey = "gemini:gemini-2.5",
            protocolVersion = 2,
            readingOrderVersion = 1,
        ),
        frozenRunConfigFingerprint = hex64,
        orderedSourceDigest = hex64,
        analysisPolicyFingerprint = hex64,
        envelopePolicyFingerprint = hex64,
        createdAtEpochMs = 1757050000000L,
        updatedAtEpochMs = 1757050000000L,
    )

    private fun openedCandidate(
        io: FakeChapterDocumentIo,
        store: ChapterArtifactEngine,
    ): Pair<ChapterArtifactManifest, String> {
        val seeded = store.loadArtifact(seededSnapshot()).manifest
        val opened = store.candidates.openCandidate(seeded, "page.jpg", ArtifactOrigin.BATCH, 0L, hex64)
            .shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        return opened.manifest to opened.generationId.shouldNotBeNull()
    }

    @Test
    fun `precondition rejection stays unchanged with no receipt and no writes`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val initial = store.loadArtifact(seededSnapshot()).manifest
        io.writtenNames.clear()

        val outcome = store.progress.publishActiveRun(initial, runRecord(), "not-hex")

        val rejected = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        rejected.incompletePublication shouldBe null
        io.writtenNames shouldBe emptyList()
        store.readManifest() shouldBe initial
    }

    @Test
    fun `manifest commit failure carries the advanced sidecar files`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val initial = store.loadArtifact(seededSnapshot()).manifest
        io.ownedRenamesToFail += manifestTmpName

        val outcome = store.progress.publishActiveRun(initial, runRecord(), hex64)

        val rejected = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        val receipt = rejected.incompletePublication.shouldNotBeNull()
        receipt.advancedFiles shouldBe listOf(layout.runRecordFile(hex64))
        receipt.actualAdvancedManifest.shouldNotBeNull() shouldBe initial
        receipt.recoverableCandidateManifest shouldBe null
        receipt.failureReason shouldBe "manifest publication failed; prior manifest remains authoritative"
        // Logical rejection, but the sidecar bytes are physically durable.
        io.files.containsKey(layout.runRecordFile(hex64)) shouldBe true
        store.readManifest() shouldBe initial
    }

    @Test
    fun `second sidecar failure carries only the earlier advanced orphan`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val docs = AtomicChapterDocuments(io)
        val initial = store.loadArtifact(seededSnapshot()).manifest
        val runName = layout.runRecordFile(hex64)
        val profileName = layout.profileFile(hex64)
        io.writeNamesToFail += "/profiles/"

        val outcome = store.publishSidecarPointers(
            manifest = initial,
            sidecars = listOf(
                ChapterArtifactEngine.SidecarPublication(runName, hex64) {
                    docs.publishJson(runName, runRecord())
                },
                ChapterArtifactEngine.SidecarPublication(profileName, hex64) { false },
            ),
            updatePointers = { it },
        )

        val rejected = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        val receipt = rejected.incompletePublication.shouldNotBeNull()
        receipt.advancedFiles shouldBe listOf(runName)
        store.readManifest() shouldBe initial
    }

    @Test
    fun `first sidecar failure is an unchanged rejection with no receipt`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val initial = store.loadArtifact(seededSnapshot()).manifest
        val runName = layout.runRecordFile(hex64)

        val outcome = store.publishSidecarPointers(
            manifest = initial,
            sidecars = listOf(
                ChapterArtifactEngine.SidecarPublication(runName, hex64) { false },
            ),
            updatePointers = { it },
        )

        val rejected = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        rejected.incompletePublication shouldBe null
        store.readManifest() shouldBe initial
    }

    @Test
    fun `candidate persist failure carries the advanced candidate snapshot`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val (withCandidate, generationId) = openedCandidate(io, store)
        io.ownedRenamesToFail += manifestTmpName

        val outcome = store.candidates.persistLiveCandidate(
            manifest = withCandidate,
            pageKey = "page.jpg",
            generationId = generationId,
            expectedPageVersion = 1L,
            expectedDependencyFingerprint = hex64,
            pageSnapshot = pageSnapshot(),
            origin = ArtifactOrigin.BATCH,
        )

        val rejected = outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        val receipt = rejected.incompletePublication.shouldNotBeNull()
        receipt.advancedFiles shouldBe listOf(layout.candidatePageSnapshotFile("page.jpg", generationId))
        store.readManifest() shouldBe withCandidate
    }

    @Test
    fun `fused promotion failure restores the candidate and the receipt finishes once`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val (withCandidate, generationId) = openedCandidate(io, store)
        io.ownedRenamesToFailOnce += manifestTmpName

        val failed = store.candidates.promoteLiveCandidateImmediately(
            manifest = withCandidate,
            pageKey = "page.jpg",
            generationId = generationId,
            expectedPageVersion = 1L,
            expectedDependencyFingerprint = hex64,
            pageSnapshot = pageSnapshot(),
            origin = ArtifactOrigin.BATCH,
        )
        val rejected = failed.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        val recoverable = rejected.recoverableCandidateManifest.shouldNotBeNull()
        val receipt = rejected.incompletePublication.shouldNotBeNull()
        // The restored candidate manifest is what is actually durable now.
        receipt.actualAdvancedManifest shouldBe recoverable
        receipt.recoverableCandidateManifest shouldBe recoverable
        receipt.advancedFiles shouldBe listOf(
            layout.committedPageSnapshotFile("page.jpg", generationId),
            layout.candidatePageSnapshotFile("page.jpg", generationId),
        )
        store.readManifest() shouldBe recoverable

        val repaired = store.finishIncompletePublication(receipt) { fresh ->
            store.candidates.promoteLiveCandidateImmediately(
                manifest = fresh,
                pageKey = "page.jpg",
                generationId = generationId,
                expectedPageVersion = 2L,
                expectedDependencyFingerprint = hex64,
                pageSnapshot = pageSnapshot(),
                origin = ArtifactOrigin.BATCH,
            )
        }
        val committed = repaired.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        committed.unverifiedBackupReason shouldBe null
        store.readManifest() shouldBe committed.manifest
        committed.manifest.pages.getValue("page.jpg").committed.shouldNotBeNull()
    }

    @Test
    fun `older receipt is retired without any write once newer state is durable`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val (withCandidate, generationId) = openedCandidate(io, store)
        io.ownedRenamesToFailOnce += manifestTmpName
        val failed = store.candidates.promoteLiveCandidateImmediately(
            manifest = withCandidate,
            pageKey = "page.jpg",
            generationId = generationId,
            expectedPageVersion = 1L,
            expectedDependencyFingerprint = hex64,
            pageSnapshot = pageSnapshot(),
            origin = ArtifactOrigin.BATCH,
        )
        val receipt = (failed as ChapterArtifactEngine.TransactionOutcome.Rejected)
            .incompletePublication.shouldNotBeNull()
        val durableNow = store.readManifest().shouldNotBeNull()

        // A newer state advances after the receipt was recorded.
        check(store.publishManifest(durableNow.copy(updatedAtEpochMs = durableNow.updatedAtEpochMs + 5L)))
        val newer = store.readManifest().shouldNotBeNull()
        io.writtenNames.clear()

        val retired = store.finishIncompletePublication(receipt) { _ ->
            throw AssertionError("retry must not run against a newer durable state")
        }

        val rejected = retired.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
        rejected.reason.contains("newer artifact state") shouldBe true
        rejected.incompletePublication shouldBe null
        io.writtenNames shouldBe emptyList()
        store.readManifest() shouldBe newer
    }

    @Test
    fun `finishing a receipt whose state is already superseded by its own repair retires it`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val (withCandidate, generationId) = openedCandidate(io, store)
        io.ownedRenamesToFailOnce += manifestTmpName
        val failed = store.candidates.promoteLiveCandidateImmediately(
            manifest = withCandidate,
            pageKey = "page.jpg",
            generationId = generationId,
            expectedPageVersion = 1L,
            expectedDependencyFingerprint = hex64,
            pageSnapshot = pageSnapshot(),
            origin = ArtifactOrigin.BATCH,
        )
        val receipt = (failed as ChapterArtifactEngine.TransactionOutcome.Rejected)
            .incompletePublication.shouldNotBeNull()
        store.finishIncompletePublication(receipt) { fresh ->
            store.candidates.promoteLiveCandidateImmediately(
                manifest = fresh,
                pageKey = "page.jpg",
                generationId = generationId,
                expectedPageVersion = 2L,
                expectedDependencyFingerprint = hex64,
                pageSnapshot = pageSnapshot(),
                origin = ArtifactOrigin.BATCH,
            )
        }.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()

        val again = store.finishIncompletePublication(receipt) { _ ->
            throw AssertionError("a finished receipt must not replay its mutation")
        }
        again.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
    }

    @Test
    fun `finishing with no durable manifest is refused`() {
        val io = FakeChapterDocumentIo()
        val store = artifactStore(io)
        val initial = store.loadArtifact(seededSnapshot()).manifest
        io.ownedRenamesToFail += manifestTmpName
        val failed = store.progress.publishActiveRun(initial, runRecord(), hex64)
        val receipt = (failed as ChapterArtifactEngine.TransactionOutcome.Rejected)
            .incompletePublication.shouldNotBeNull()

        // Chapter teardown removes the authority documents.
        val plan = ChapterArtifactDeletionPlan.capture(io, "${layout.chapterKey}.json").shouldNotBeNull()
        val deletion = plan.delete()
        deletion.manifestRemoved shouldBe true

        val outcome = store.finishIncompletePublication(receipt) { _ ->
            throw AssertionError("retry must not run without a durable manifest")
        }
        outcome.shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Rejected>()
    }
}
