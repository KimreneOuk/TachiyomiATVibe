package eu.kanade.translation.persistence

import com.hippo.unifile.FakeUniFile
import com.hippo.unifile.UniFile
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
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
 * P3C "restore saved OCR without republishing": the durable checkpoint and
 * its page-snapshot sidecar are already manifest-referenced, so restoring
 * them into a reopened store's live memory must write NOTHING — no new
 * checkpoint or page payload, no manifest solely for the adoption — while
 * every fence rejects fail-closed and the first REAL mutation after
 * hydration persists through the normal publication bridge.
 */
class ChapterTranslationStoreCheckpointHydrationTest {

    @TempDir
    lateinit var mangaDir: File

    private fun hex64(tag: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(tag.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun block(text: String) = TranslationBlock(
        blockId = "b1",
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

    private fun ocrPage(pageKey: String, text: String) = PageTranslation(
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

    private fun manifestFile(): File = mangaDir.resolve("Chapter 1.manifest.json")

    /**
     * Durable relative file name -> byte size under the artifact root. The
     * open path's retention sweep may reclaim unreachable orphans left by the
     * CLOSE checkpoint, so zero-republish baselines are captured AFTER the
     * last reopen, never across one.
     */
    private fun durableFileState(): Map<String, Long> =
        mangaDir.walkTopDown().filter { it.isFile }.associate { relativeName(it) to it.length() }

    private fun relativeName(file: File): String =
        mangaDir.toPath().relativize(file.toPath()).toString().replace('\\', '/')

    /**
     * Batch-lane OCR: lease, merge, CLOSE checkpoint, release. Leaves the
     * durable resume shape: checkpoint pointer installed, candidate cleared,
     * no committed record.
     */
    private suspend fun mergeAndCheckpoint(
        store: ChapterTranslationStore,
        pageKey: String = "p1",
        text: String = "source",
    ) {
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
            description = "p3c hydration setup ocr",
        ).shouldBeInstanceOf<StagePatchResult.Accepted>()
        val after = store.snapshot(pageKey)
        store.checkpointOcr(
            pageKey = pageKey,
            generation = after.generation,
            expectedPageVersion = after.pageVersion,
            expectedLeaseToken = lease.token,
            expectedCandidateGenerationId = after.candidateGenerationId.shouldNotBeNull(),
            expectedArtifactPageVersion = after.artifactPageVersion,
            expectedDependencyFingerprint = after.dependencyFingerprint.shouldNotBeNull(),
            sourceSha256 = hex64("source-$pageKey"),
            sourceOrientation = "PORTRAIT",
        ).shouldBeInstanceOf<CheckpointOcrResult.Committed>()
        store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH)
    }

    /** Reopens nothing: hydrates [store]'s checkpointed page under a fresh BATCH lease. */
    private suspend fun hydrate(
        store: ChapterTranslationStore,
        pageKey: String = "p1",
        observedSha: String? = hex64("source-p1"),
        tokenOverride: Long? = null,
        pointerOverride: SidecarPointer? = null,
    ): StagePatchResult {
        val before = store.snapshot(pageKey)
        val lease = store.tryAcquirePageStageLease(pageKey, PageStage.Ocr, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
        val manifest = store.artifactManifest.shouldNotBeNull()
        return try {
            store.hydrateOcrFromCheckpoint(
                pageKey = pageKey,
                generation = before.generation,
                expectedPageVersion = before.pageVersion,
                expectedLeaseToken = tokenOverride ?: lease.token,
                expectedCandidateGenerationId = before.candidateGenerationId,
                expectedDependencyFingerprint = before.dependencyFingerprint,
                expectedArtifactPageVersion = before.artifactPageVersion,
                checkpointPointer = pointerOverride
                    ?: manifest.ocrCheckpoints.getValue(pageKey),
                observedSourceSha256 = observedSha,
                description = "p3c hydration test",
            )
        } finally {
            store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH)
        }
    }

    private suspend fun reopenedStore(): ChapterTranslationStore =
        ChapterTranslationStore.openArtifactSuspend(root(), "Chapter 1.json")

    @Test
    fun `hydration after reopen restores verified OCR with zero new payloads`() = runTest {
        val store = lazyStore()
        store.preRegisterPages(listOf("p1"))
        mergeAndCheckpoint(store)
        val checkpointPointer = artifactStore().readManifest().shouldNotBeNull()
            .ocrCheckpoints.getValue("p1")
        val checkpoint = artifactStore().readOcrCheckpoint(checkpointPointer)
            .shouldBeInstanceOf<ChapterArtifactEngine.OcrCheckpointRead.Usable>().checkpoint
        val checkpointedSnapshot = artifactStore()
            .readPageSnapshot(checkpoint.ocrPageSnapshotPointer.fileName).shouldNotBeNull()

        // ---- simulated process death: the reopened store holds only the
        // synthesized placeholder (no blocks); hydration rebuilds live state
        // from the ALREADY-REFERENCED durable bytes. ----
        val reopened = reopenedStore()
        reopened.snapshot("p1").page.shouldNotBeNull().blocks shouldBe emptyList()
        // Zero-republish baseline: captured AFTER the open (the open path's
        // retention sweep may reclaim CLOSE-checkpoint orphans on its own).
        val fileStateBefore = durableFileState()
        val manifestBytesBefore = manifestFile().readBytes()

        val accepted = hydrate(reopened).shouldBeInstanceOf<StagePatchResult.Accepted>()
        val hydratedPage = accepted.snapshot.page.shouldNotBeNull()
        hydratedPage.ocrStatus shouldBe StageStatus.READY
        hydratedPage.ocrBlockFingerprints() shouldBe checkpointedSnapshot.ocrBlockFingerprints()
        hydratedPage.blocks.single().text shouldBe "source"
        hydratedPage.sourceFingerprint shouldBe hex64("source-p1")

        // A second reopen must reconstruct the SAME verified OCR.
        val again = hydrate(reopenedStore()).shouldBeInstanceOf<StagePatchResult.Accepted>()
        again.snapshot.page.shouldNotBeNull().ocrBlockFingerprints() shouldBe
            hydratedPage.ocrBlockFingerprints()

        // ZERO republishing: hydration added no file, rewrote no file, and the
        // manifest bytes are unchanged; the pointer is the one the run
        // started with.
        durableFileState() shouldBe fileStateBefore
        manifestFile().readBytes() shouldBe manifestBytesBefore
        artifactStore().readManifest().shouldNotBeNull()
            .ocrCheckpoints.getValue("p1") shouldBe checkpointPointer
        // The checkpoint stays the durable recovery authority: no candidate
        // was opened and no committed record appeared solely for adoption.
        artifactStore().readManifest().shouldNotBeNull().let { manifest ->
            manifest.pageRecord("p1").candidate shouldBe null
            manifest.pageRecord("p1").committed shouldBe null
        }
    }

    @Test
    fun `repeated hydration is an idempotent in-memory no-op`() = runTest {
        val store = lazyStore()
        store.preRegisterPages(listOf("p1"))
        mergeAndCheckpoint(store)
        val reopened = reopenedStore()
        val first = hydrate(reopened).shouldBeInstanceOf<StagePatchResult.Accepted>()
        val versionAfterFirst = first.snapshot.pageVersion
        val fileState = durableFileState()

        val second = hydrate(reopened).shouldBeInstanceOf<StagePatchResult.Accepted>()

        second.snapshot.pageVersion shouldBe versionAfterFirst
        second.snapshot.page.shouldNotBeNull().blocks.single().text shouldBe "source"
        durableFileState() shouldBe fileState
    }

    @Test
    fun `first real mutation after hydration persists through the publication bridge`() = runTest {
        val store = lazyStore()
        store.preRegisterPages(listOf("p1"))
        mergeAndCheckpoint(store)
        val reopened = reopenedStore()
        val hydrated = hydrate(reopened).shouldBeInstanceOf<StagePatchResult.Accepted>().snapshot
        val filesBeforeMutation = durableFileState()

        // The first REAL mutation (a block translation) opens/binds the
        // candidate and publishes the page normally.
        val lease = reopened.tryAcquirePageStageLease("p1", PageStage.Translation, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
        val page = hydrated.page.shouldNotBeNull()
        val block = page.blocks.single()
        reopened.mergeTranslation(
            TranslationStagePatch(
                pageKey = "p1",
                generation = hydrated.generation,
                expectedOcrBlockFingerprints = page.ocrBlockFingerprints(),
                expectedSourceTexts = page.blocks.map { it.text },
                blocks = listOf(
                    TranslationBlockPatch(
                        blockIndex = 0,
                        expectedOcrFingerprint = block.ocrFingerprint(),
                        expectedSourceText = block.text,
                        expectedTranslation = block.translation,
                        expectedUserEditedAt = block.userEditedAt,
                        translation = "translated",
                    ),
                ),
                translationStatus = StageStatus.READY,
                expectedPageVersion = hydrated.pageVersion,
                expectedLeaseToken = lease.token,
            ),
            description = "p3c first real mutation after hydration",
        ).shouldBeInstanceOf<StagePatchResult.Accepted>()
        reopened.releasePageStageLease("p1", PageWriteOrigin.BATCH)

        val manifest = artifactStore().readManifest().shouldNotBeNull()
        manifest.pageRecord("p1").candidate.shouldNotBeNull()
        // Durable state ADVANCED beyond the pre-hydration file set: the
        // mutation wrote its own payload; the hydration itself did not.
        durableFileState().keys shouldNotBe filesBeforeMutation.keys
        reopened.snapshot("p1").page.shouldNotBeNull().blocks.single().translation shouldBe "translated"
    }

    @Test
    fun `hydration fences reject stale pointer missing lease and source identity drift`() = runTest {
        val store = lazyStore()
        store.preRegisterPages(listOf("p1"))
        mergeAndCheckpoint(store)
        // Settle the open path's retention sweep before capturing baselines:
        // every fence attempt below reopens the store, and a stale baseline
        // would confuse reclamation with hydration writes.
        reopenedStore()
        val manifestBefore = artifactStore().readManifest().shouldNotBeNull()
        val pointer = manifestBefore.ocrCheckpoints.getValue("p1")
        val fileState = durableFileState()

        fun assertRejected(result: StagePatchResult, expectedFragment: String) {
            val rejected = result.shouldBeInstanceOf<StagePatchResult.Rejected>()
            rejected.reason shouldContain expectedFragment
            // Every rejection leaves the durable authority untouched.
            artifactStore().readManifest().shouldNotBeNull() shouldBe manifestBefore
            durableFileState() shouldBe fileState
        }

        // Stale checkpoint pointer (not the manifest-referenced one).
        assertRejected(
            hydrate(
                reopenedStore(),
                pointerOverride = SidecarPointer(
                    fileName = pointer.fileName,
                    schemaVersion = pointer.schemaVersion,
                    contentFingerprint = hex64("stale-pointer"),
                ),
            ),
            "checkpoint pointer changed",
        )

        // No held lease: a stale token cannot match a released lease table.
        val released = reopenedStore()
        released.tryAcquirePageStageLease("p1", PageStage.Ocr, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>()
        released.releasePageStageLease("p1", PageWriteOrigin.BATCH)
        val beforeNoLease = released.snapshot("p1")
        assertRejected(
            released.hydrateOcrFromCheckpoint(
                pageKey = "p1",
                generation = beforeNoLease.generation,
                expectedPageVersion = beforeNoLease.pageVersion,
                expectedLeaseToken = 12345L,
                expectedCandidateGenerationId = beforeNoLease.candidateGenerationId,
                expectedDependencyFingerprint = beforeNoLease.dependencyFingerprint,
                expectedArtifactPageVersion = beforeNoLease.artifactPageVersion,
                checkpointPointer = pointer,
                observedSourceSha256 = hex64("source-p1"),
                description = "p3c no-lease hydration",
            ),
            "page lease required for hydration",
        )

        // Changed source identity: the checkpoint cannot back replaced bytes.
        assertRejected(
            hydrate(reopenedStore(), observedSha = hex64("replaced-source")),
            "checkpoint source identity mismatch",
        )

        // A malformed observed sha is fail-closed too.
        assertRejected(
            hydrate(reopenedStore(), observedSha = "not-a-sha"),
            "checkpoint source identity mismatch",
        )
    }

    @Test
    fun `tampered or missing checkpoint sidecars are refused without touching live state`() = runTest {
        val store = lazyStore()
        store.preRegisterPages(listOf("p1"))
        mergeAndCheckpoint(store)
        val reopened = reopenedStore()

        // Tamper: the checkpoint's OCR content fingerprint no longer matches
        // the snapshot it references (foreign/corrupt provenance).
        val tamperPointer = reopened.artifactManifest.shouldNotBeNull().ocrCheckpoints.getValue("p1")
        val sidecarFile = mangaDir.resolve(tamperPointer.fileName)
        val checkpoint = ArtifactDocumentJson.decodeFromString(
            PageOcrCheckpoint.serializer(),
            sidecarFile.readText(),
        )
        sidecarFile.writeText(
            ArtifactDocumentJson.encodeToString(
                PageOcrCheckpoint.serializer(),
                checkpoint.copy(ocrContentFingerprint = hex64("tampered-content")),
            ),
        )
        hydrate(reopened).shouldBeInstanceOf<StagePatchResult.Rejected>().reason shouldContain
            "checkpoint OCR content fingerprint mismatch"
        reopened.snapshot("p1").page.shouldNotBeNull().blocks shouldBe emptyList()

        // Missing bundle: the referenced page snapshot vanished.
        val missing = reopenedStore()
        val missingCheckpoint = ArtifactDocumentJson.decodeFromString(
            PageOcrCheckpoint.serializer(),
            mangaDir.resolve(
                missing.artifactManifest.shouldNotBeNull().ocrCheckpoints.getValue("p1").fileName,
            ).readText(),
        )
        mangaDir.resolve(missingCheckpoint.ocrPageSnapshotPointer.fileName).delete()
        hydrate(missing).shouldBeInstanceOf<StagePatchResult.Rejected>().reason shouldContain
            "checkpoint OCR page snapshot missing"
        missing.snapshot("p1").page.shouldNotBeNull().blocks shouldBe emptyList()
    }

    @Test
    fun `hydration never overwrites newer live OCR content`() = runTest {
        val store = lazyStore()
        store.preRegisterPages(listOf("p1"))
        mergeAndCheckpoint(store, text = "source-v1")
        val reopened = reopenedStore()
        hydrate(reopened).shouldBeInstanceOf<StagePatchResult.Accepted>()

        // Real newer work lands in memory (v2 OCR under the same source).
        val lease = reopened.tryAcquirePageStageLease("p1", PageStage.Ocr, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
        val before = reopened.snapshot("p1")
        reopened.mergeOcr(
            OcrStagePatch(
                pageKey = "p1",
                generation = before.generation,
                expectedPageVersion = before.pageVersion,
                expectedPriorOcrFingerprints = before.page?.ocrBlockFingerprints().orEmpty(),
                ocrResult = ocrPage("p1", "source-v2"),
                expectedLeaseToken = lease.token,
            ),
            description = "p3c newer ocr after hydration",
        ).shouldBeInstanceOf<StagePatchResult.Accepted>()
        reopened.releasePageStageLease("p1", PageWriteOrigin.BATCH)

        // Re-hydration against the OLD checkpoint must not clobber v2.
        hydrate(reopened).shouldBeInstanceOf<StagePatchResult.Rejected>().reason shouldContain
            "prior OCR identity changed"
        reopened.snapshot("p1").page.shouldNotBeNull().blocks.single().text shouldBe "source-v2"
    }
}
