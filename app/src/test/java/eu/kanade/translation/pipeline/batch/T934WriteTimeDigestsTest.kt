package eu.kanade.translation.pipeline.batch

import com.hippo.unifile.FakeUniFile
import com.hippo.unifile.UniFile
import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.LeaseAcquisition
import eu.kanade.translation.OcrStagePatch
import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.StagePatchResult
import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactStore
import eu.kanade.translation.artifact.ChapterRunRecord
import eu.kanade.translation.artifact.RunConfigSnapshot
import eu.kanade.translation.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.ocrBlockFingerprints
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.translator.TranslatorComputeClass
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * T934 R2a — write-time digests + typed adoption failures.
 *
 * Pins the golden-standard resume model the Director accepted:
 *  - R2a.1: the per-page source SHA-256 is recorded DURABLY at first
 *    admission (the checkpoint transaction stamps
 *    `manifest.sourceShaByPageKey`), never derived at run end.
 *  - R2a.2/R2a.3: run start consumes the RECORDED digests — a second run
 *    over unchanged sources performs ZERO source re-hashes (the fake
 *    decode/hash seam is never invoked) and keeps runId/digest identity
 *    even when the dispatch-time hash observation is withheld entirely.
 *  - R2a.4/R2a.5: adoption cliffs are TYPED and counted in the run record:
 *    a changed source is detected at consumption (SHA_MISMATCH — the stale
 *    checkpoint is not reused) and a lost checkpoint sidecar
 *    (SIDE_CAR_UNREADABLE — a dangling manifest pointer cannot prove source
 *    equality) both fail closed into a re-OCR, never a silent drop.
 *
 * Harness idioms follow `OcrPreflightCoordinatorTest` (M1): real
 * `ChapterTranslationStore` over `FakeUniFile`/`@TempDir`, a fake
 * [NativeLaneWorker] whose `runOcrStage` IS the decode seam — every
 * invocation necessarily re-hashes the source bytes (counted in
 * [FakeDigestWorker.hashCalls]).
 */
class T934WriteTimeDigestsTest {

    @TempDir
    lateinit var mangaDir: File

    /** A dispatch-time hash failure placeholder (non-64-hex by design). */
    private val unhashed = "source-fingerprint-unavailable"

    private fun hex64(tag: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
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
        imgHeight = 160f,
        decodeSampleSize = 1,
        ocrStatus = StageStatus.READY,
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        sourceFingerprint = hex64("source-$pageKey"),
        detectionFingerprint = hex64("detection-$pageKey"),
        ocrFingerprint = hex64("ocr-$pageKey"),
        inpaintMaskBoxes = listOf(eu.kanade.translation.model.InpaintMaskBox(0, 0, 10, 10, 1)),
    )

    private fun root(): UniFile = FakeUniFile(parent = null, backing = mangaDir)

    private fun artifactStore(): ChapterArtifactStore =
        ChapterArtifactStore(
            AtomicChapterDocuments(UniFileChapterDocumentIo(root())),
            ChapterArtifactLayout("Chapter 1"),
        )

    private fun lazyStore(): ChapterTranslationStore = ChapterTranslationStore.lazy(
        fileCreator = { root().createFile("Chapter 1.json")!! },
        artifactParent = root(),
        artifactFileName = "Chapter 1.json",
    )

    private fun frozenConfig(): RunConfigSnapshot = ChapterProfileBatchCoordinator.frozenRunConfig(
        sourceLang = "ja",
        targetLang = "en",
        ocrEngine = "FakeOcrEngine",
        inpaintMode = "OFF",
        providerKey = "fake:provider",
    )

    private fun orderedPages(vararg pageKeys: String): List<PageKey> =
        pageKeys.mapIndexed { index, pageKey -> pageKey to index }

    private fun sourcePairs(pages: List<PageKey>, tag: String = "source"): List<Pair<String, String>> =
        pages.map { (pageKey, _) -> pageKey to hex64("$tag-$pageKey") }

    private fun coordinator(
        store: ChapterTranslationStore,
        worker: FakeDigestWorker,
        pages: List<PageKey>,
        sourcePairsOverride: List<Pair<String, String>>? = null,
    ): ChapterProfileBatchCoordinator = ChapterProfileBatchCoordinator(
        store = store,
        nativeWorker = worker,
        frozenConfig = frozenConfig(),
        orderedSourcePairs = sourcePairsOverride ?: sourcePairs(pages),
        releaseBatchLease = { pageKey -> store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
    )

    /**
     * M1-idiom OCR lane whose decode seam IS the hasher: every `runOcrStage`
     * decodes the page (one native hash of the current bytes, counted) and
     * stamps the OCR result with the bytes' identity, exactly like the real
     * `PageDecode` lane.
     */
    private inner class FakeDigestWorker(
        private val store: ChapterTranslationStore,
    ) : NativeLaneWorker {
        val ocrPages = mutableListOf<String>()
        val hashCalls = AtomicInteger(0)
        var textFor: (String) -> String = { pageKey -> "source-$pageKey" }

        /** Current source-bytes identity, as the real lane hashes at decode time. */
        var sourceShaFor: (String) -> String = { pageKey -> hex64("source-$pageKey") }

        override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? {
            ocrPages += pageKey
            hashCalls.incrementAndGet()
            val lease = store.tryAcquirePageStageLease(pageKey, PageStage.Ocr, PageWriteOrigin.BATCH)
                .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
            val before = store.snapshot(pageKey)
            val currentSha = sourceShaFor(pageKey)
            val resultPage = ocrPage(pageKey, textFor(pageKey)).apply {
                sourceFingerprint = currentSha
                detectionFingerprint = hex64("detection-$currentSha")
                ocrFingerprint = hex64("ocr-$currentSha")
            }
            store.mergeOcr(
                OcrStagePatch(
                    pageKey = pageKey,
                    generation = before.generation,
                    expectedPageVersion = before.pageVersion,
                    expectedPriorOcrFingerprints = before.page?.ocrBlockFingerprints().orEmpty(),
                    ocrResult = resultPage,
                    expectedLeaseToken = lease.token,
                ),
                description = "t934 fake preflight ocr",
            ).shouldBeInstanceOf<StagePatchResult.Accepted>()
            val after = store.snapshot(pageKey)
            return OcrReadyPageRef(
                pageKey = pageKey,
                pageIndex = pageIndex,
                generation = after.generation,
                blockFingerprints = emptyList(),
                leaseToken = lease.token,
                candidateGenerationId = after.candidateGenerationId,
                dependencyFingerprint = after.dependencyFingerprint,
                artifactPageVersion = after.artifactPageVersion,
                nativeHandoff = "decoded-bitmap-$pageKey",
            )
        }

        override suspend fun runInpaintStage(pageKey: String) {}

        override fun releaseNativeHandoff(ref: OcrReadyPageRef) {}
    }

    private fun runRecord(store: ChapterTranslationStore): ChapterRunRecord {
        val manifest = artifactStore().readManifest().shouldNotBeNull()
        val pointer = manifest.activeRun.shouldNotBeNull()
        return (artifactStore().readRunRecord(pointer) as ChapterArtifactStore.RunRecordRead.Usable).record
    }

    private fun recordedDigests(): Map<String, String> =
        artifactStore().readManifest().shouldNotBeNull().sourceShaByPageKey

    @Test
    fun `first admission records every page source sha durably at checkpoint time`() = runTest {
        val store = lazyStore()
        store.preRegisterPages(listOf("p1", "p2", "p3"))
        val worker = FakeDigestWorker(store)
        val pages = orderedPages("p1", "p2", "p3")

        coordinator(store, worker, pages).runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        // R2a.1: the digests were stamped at ADMISSION (each checkpoint
        // publication), durably, in the manifest.
        recordedDigests() shouldBe sourcePairs(pages).toMap()

        // A resume over the SAME store consumes the record: identical digest
        // input, zero extra hashes (already all reused — no second decode).
        val resumeWorker = FakeDigestWorker(store)
        coordinator(store, resumeWorker, pages).runPass1(pages, TranslatorComputeClass.REMOTE_IO)
        resumeWorker.ocrPages shouldBe emptyList()
        resumeWorker.hashCalls.get() shouldBe 0
        recordedDigests() shouldBe sourcePairs(pages).toMap()
    }

    @Test
    fun `second run with unchanged sources performs zero source re-hash from recorded digests`() = runTest {
        val store = lazyStore()
        store.preRegisterPages(listOf("p1", "p2", "p3"))
        val firstWorker = FakeDigestWorker(store)
        val pages = orderedPages("p1", "p2", "p3")
        val first = coordinator(store, firstWorker, pages).runPass1(pages, TranslatorComputeClass.REMOTE_IO)
        val firstRecord = runRecord(store)
        firstWorker.hashCalls.get() shouldBe 3 // one decode-hash per page, once

        // ---- simulated process death: fresh store over the SAME documents,
        // and the dispatch provides NO usable hash observation (every pair is
        // the hash-failure placeholder) — the strongest zero-re-hash form. ----
        val resumedStore = ChapterTranslationStore.openArtifact(root(), "Chapter 1.json")
        val resumedWorker = FakeDigestWorker(resumedStore)
        val withheldPairs = pages.map { (pageKey, _) -> pageKey to unhashed }

        val resumed = coordinator(resumedStore, resumedWorker, pages, withheldPairs)
            .runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        // ZERO source re-hashes: no decode ran at all — the recorded digests
        // alone proved every page's identity (R2a.2/R2a.3).
        resumedWorker.ocrPages shouldBe emptyList()
        resumedWorker.hashCalls.get() shouldBe 0

        // Identity still works: the orderedSourceDigest is derived FROM the
        // recorded digests, so the run continues (same runId, same digest).
        val record = runRecord(resumedStore)
        record.runId shouldBe firstRecord.runId
        record.orderedSourceDigest shouldBe firstRecord.orderedSourceDigest
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_REUSED] shouldBe 3
        // Healthy run: the typed adoption counters are absent entirely.
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_ADOPT_FAILED].shouldBeNull()
        recordedDigests() shouldBe sourcePairs(pages).toMap()
        first.status shouldBe resumed.status
    }

    @Test
    fun `changed source detected at consumption fails closed with typed sha mismatch`() = runTest {
        val store = lazyStore()
        store.preRegisterPages(listOf("p1", "p2"))
        val pages = orderedPages("p1", "p2")
        coordinator(store, FakeDigestWorker(store), pages).runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        // The page files were replaced under the same keys: the dispatch's
        // fresh observation no longer matches the durable checkpoint identity.
        val resumedStore = ChapterTranslationStore.openArtifact(root(), "Chapter 1.json")
        val resumedWorker = FakeDigestWorker(resumedStore).apply {
            sourceShaFor = { pageKey -> hex64("replaced-source-$pageKey") }
        }
        val replacedPairs = sourcePairs(pages, tag = "replaced-source")

        val outcome = coordinator(resumedStore, resumedWorker, pages, replacedPairs)
            .runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        // Fail closed: the stale checkpoints were NOT reused — both pages
        // re-ran — and the cliff is TYPED in the record (R2a.4/R2a.5).
        resumedWorker.ocrPages shouldBe listOf("p1", "p2")
        val record = runRecord(resumedStore)
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_ADOPT_FAILED] shouldBe 2
        record.phaseCounters[CheckpointAdoptionFailure.SHA_MISMATCH.counterKey] shouldBe 2
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_REUSED] shouldBe 0

        // The re-admission stamped the NEW digests at write time.
        recordedDigests() shouldBe replacedPairs.toMap()
    }

    @Test
    fun `lost checkpoint sidecar fails closed with typed reason and re-OCRs`() = runTest {
        val store = lazyStore()
        store.preRegisterPages(listOf("p1", "p2"))
        val pages = orderedPages("p1", "p2")
        coordinator(store, FakeDigestWorker(store), pages).runPass1(pages, TranslatorComputeClass.REMOTE_IO)
        recordedDigests() shouldBe sourcePairs(pages).toMap()

        // The checkpoint SIDECARS are lost on disk (truncation / storage
        // reclamation) while the manifest pointers survive: each dangling
        // pointer can no longer prove source equality.
        val manifest = artifactStore().readManifest().shouldNotBeNull()
        pages.forEach { (pageKey, _) ->
            val sidecar = File(mangaDir, manifest.ocrCheckpoints.getValue(pageKey).fileName)
            sidecar.isFile shouldBe true
            sidecar.delete() shouldBe true
        }

        val resumedStore = ChapterTranslationStore.openArtifact(root(), "Chapter 1.json")
        val resumedWorker = FakeDigestWorker(resumedStore)

        coordinator(resumedStore, resumedWorker, pages).runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        // Fail closed, typed: the pages re-run (no silent empty adoption) and
        // the cliff is counted under SIDE_CAR_UNREADABLE, not dropped quietly.
        resumedWorker.ocrPages shouldBe listOf("p1", "p2")
        resumedWorker.hashCalls.get() shouldBe 2
        val record = runRecord(resumedStore)
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_ADOPT_FAILED] shouldBe 2
        record.phaseCounters[CheckpointAdoptionFailure.SIDE_CAR_UNREADABLE.counterKey] shouldBe 2
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_REUSED] shouldBe 0

        // The re-admission re-stamps the SAME digests at write time — the
        // durable record stays coherent with the (unchanged) sources.
        recordedDigests() shouldBe sourcePairs(pages).toMap()
    }
}
