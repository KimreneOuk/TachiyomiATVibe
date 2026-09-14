package eu.kanade.translation.artifact

import eu.kanade.translation.model.InpaintMaskBox
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.security.MessageDigest

/**
 * T930 Slice A1: tests the CommitPoint contract codification.
 *
 * Asserts:
 * 1. The 6 mandatory commit points are codified.
 * 2. Stageable mutations (candidate open, intermediate candidate persist/stage, candidate cancel) have null commitPoint.
 * 3. Terminal promotion, OCR checkpoint close, and chapter phase records carry their respective CommitPoint.
 */
class ChapterCommitPointContractTest {

    private val layout = ChapterArtifactLayout("Chapter 1")

    private fun hex64(tag: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(tag.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun block(text: String) = TranslationBlock(
        text = text,
        translation = "trans-$text",
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )

    private fun ocrPage(text: String = "source") = PageTranslation(
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
        inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 1)),
    )

    private fun identity(tag: String) = LegacySourceIdentity(
        sha256 = "sha-$tag",
        lengthBytes = tag.length.toLong(),
        lastModifiedMs = 1L,
    )

    private fun legacySnapshot(page: PageTranslation) = LegacyChapterSnapshot(
        pages = mapOf("page.jpg" to LegacyPageFacts(page, CleanedFileState.VALID)),
        glossary = emptyMap(),
        legacyIdentity = identity("v1"),
        sourceFileName = "Chapter 1.json",
        glossaryFileName = null,
        glossaryIdentity = null,
        migratedByVersionCode = 63L,
        migratedAtEpochMs = 42L,
    )

    private fun createStore(): Pair<ChapterArtifactStore, FakeChapterDocumentIo> {
        val io = FakeChapterDocumentIo()
        val store = ChapterArtifactStore(
            AtomicChapterDocuments(io),
            layout,
            object : CleanedImageProbe {
                override fun probe(input: InputStream): ProbedImage? = ProbedImage(100, 100)
            },
        )
        return store to io
    }

    @Test
    fun `commit points enum contains exactly the 6 required boundaries`() {
        val expected = listOf(
            CommitPoint.OCR_CHECKPOINT_CLOSE,
            CommitPoint.PAGE_TERMINAL_PROMOTION,
            CommitPoint.CHAPTER_PHASE_RECORD,
            CommitPoint.CHAPTER_COMPLETE,
            CommitPoint.USER_STOP_DRAIN,
            CommitPoint.EXPLICIT_FLUSH,
        )
        CommitPoint.values().toList() shouldContainExactlyInAnyOrder expected
        expected.forEach { point ->
            CommitPoint.isCommitPoint(point) shouldBe true
            CommitPoint.isStageable(point) shouldBe false
            point.isMandatoryDurable shouldBe true
        }
        CommitPoint.isCommitPoint(null) shouldBe false
        CommitPoint.isStageable(null) shouldBe true
    }

    @Test
    fun `ocr checkpoint CLOSE is a commit point while REBASE is stageable`() {
        val (store, _) = createStore()
        val ocrSnapshot = ocrPage()
        val manifest = store.loadOrMigrate(legacySnapshot(ocrSnapshot)).manifest

        val opened = store.openCandidate(
            manifest = manifest,
            pageKey = "page.jpg",
            origin = ArtifactOrigin.BATCH,
            expectedPageVersion = 0L,
            dependencyFingerprint = "deps-v1",
            nowEpochMs = 500L,
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
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
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()

        val snapshotFingerprint = StageFingerprints.pageSnapshot(ocrSnapshot)
        val checkpoint = PageOcrCheckpoint(
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
            ocrContentFingerprint = hex64("ocr-content"),
            ocrPageSnapshotPointer = SidecarPointer(
                fileName = store.ocrStageSnapshotName("page.jpg", snapshotFingerprint),
                schemaVersion = 1,
                contentFingerprint = snapshotFingerprint,
            ),
            inpaintMaskRevision = ocrSnapshot.inpaintRevision,
            priorCommittedDisplay = null,
            producedByOrigin = ArtifactOrigin.BATCH,
            producerGenerationId = generationId,
            checkpointedAtEpochMs = 502L,
        )

        // 1. REBASE branch -> commitPoint is null (stageable)
        val rebaseOutcome = store.checkpointOcr(
            manifest = persisted.manifest,
            pageKey = "page.jpg",
            expectedPageVersion = persisted.manifest.pages.getValue("page.jpg").pageVersion,
            expectedDependencyFingerprint = "deps-v1",
            ocrSnapshot = ocrSnapshot,
            checkpoint = checkpoint,
            mode = OcrCheckpointMode.REBASE,
            nowEpochMs = 503L,
        )
        val rebaseCommitted = rebaseOutcome.shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        rebaseCommitted.commitPoint.shouldBeNull()

        // 2. CLOSE branch on a fresh store -> commitPoint is CommitPoint.OCR_CHECKPOINT_CLOSE
        val (store2, _) = createStore()
        val manifest2 = store2.loadOrMigrate(legacySnapshot(ocrSnapshot)).manifest
        val opened2 = store2.openCandidate(
            manifest = manifest2,
            pageKey = "page.jpg",
            origin = ArtifactOrigin.BATCH,
            expectedPageVersion = 0L,
            dependencyFingerprint = "deps-v1",
            nowEpochMs = 500L,
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        val generationId2 = opened2.generationId.shouldNotBeNull()
        val persisted2 = store2.persistLiveCandidate(
            manifest = opened2.manifest,
            pageKey = "page.jpg",
            generationId = generationId2,
            expectedPageVersion = opened2.manifest.pages.getValue("page.jpg").pageVersion,
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
        ).shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        val checkpoint2 = checkpoint.copy(producerGenerationId = generationId2)

        val closeOutcome = store2.checkpointOcr(
            manifest = persisted2.manifest,
            pageKey = "page.jpg",
            expectedPageVersion = persisted2.manifest.pages.getValue("page.jpg").pageVersion,
            expectedDependencyFingerprint = "deps-v1",
            ocrSnapshot = ocrSnapshot,
            checkpoint = checkpoint2,
            mode = OcrCheckpointMode.CLOSE,
            nowEpochMs = 504L,
        )
        val closeCommitted = closeOutcome.shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        closeCommitted.commitPoint shouldBe CommitPoint.OCR_CHECKPOINT_CLOSE
    }

    @Test
    fun `page-terminal promotion is a commit point while intermediate mutations are stageable`() {
        val (store, _) = createStore()
        val ocrSnapshot = ocrPage()
        val manifest = store.loadOrMigrate(legacySnapshot(ocrSnapshot)).manifest

        // Open candidate -> stageable (null commitPoint)
        val openOutcome = store.openCandidate(
            manifest = manifest,
            pageKey = "page.jpg",
            origin = ArtifactOrigin.BATCH,
            expectedPageVersion = 0L,
            dependencyFingerprint = "deps-v1",
            nowEpochMs = 500L,
        )
        val openCommitted = openOutcome.shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        openCommitted.commitPoint.shouldBeNull()
        val generationId = openCommitted.generationId.shouldNotBeNull()

        // Persist candidate -> stageable (null commitPoint)
        val persistOutcome = store.persistLiveCandidate(
            manifest = openCommitted.manifest,
            pageKey = "page.jpg",
            generationId = generationId,
            expectedPageVersion = openCommitted.manifest.pages.getValue("page.jpg").pageVersion,
            expectedDependencyFingerprint = "deps-v1",
            pageSnapshot = ocrSnapshot,
            origin = ArtifactOrigin.BATCH,
            nowEpochMs = 501L,
        )
        val persistCommitted = persistOutcome.shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        persistCommitted.commitPoint.shouldBeNull()

        // Promote candidate -> PAGE_TERMINAL_PROMOTION
        val promoteOutcome = store.promoteLiveCandidate(
            manifest = persistCommitted.manifest,
            pageKey = "page.jpg",
            generationId = generationId,
            expectedPageVersion = persistCommitted.manifest.pages.getValue("page.jpg").pageVersion,
            expectedDependencyFingerprint = "deps-v1",
            pageSnapshot = ocrSnapshot,
            origin = ArtifactOrigin.BATCH,
            nowEpochMs = 502L,
        )
        val promoteCommitted = promoteOutcome.shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        promoteCommitted.commitPoint shouldBe CommitPoint.PAGE_TERMINAL_PROMOTION
    }

    @Test
    fun `run record mutations carry CHAPTER_PHASE_RECORD commit point`() {
        val (store, _) = createStore()
        val ocrSnapshot = ocrPage()
        val manifest = store.loadOrMigrate(legacySnapshot(ocrSnapshot)).manifest

        val runRecord = ChapterRunRecord(
            runId = "run-test-1",
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
        val fp = hex64("record-fp")

        val publishOutcome = store.publishActiveRun(manifest, runRecord, fp, 1000L)
        val publishCommitted = publishOutcome.shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        publishCommitted.commitPoint shouldBe CommitPoint.CHAPTER_PHASE_RECORD

        val retireOutcome = store.retireActiveRun(publishCommitted.manifest, "test", 1001L)
        val retireCommitted = retireOutcome.shouldBeInstanceOf<ChapterArtifactStore.TransactionOutcome.Committed>()
        retireCommitted.commitPoint shouldBe CommitPoint.CHAPTER_PHASE_RECORD
    }
}
