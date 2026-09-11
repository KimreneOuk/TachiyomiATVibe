package eu.kanade.translation.pipeline.batch

import com.hippo.unifile.FakeUniFile
import com.hippo.unifile.UniFile
import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.LeaseAcquisition
import eu.kanade.translation.OcrStagePatch
import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.StagePatchResult
import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.ArtifactStage
import eu.kanade.translation.artifact.ArtifactStageStatus
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactStore
import eu.kanade.translation.artifact.ChapterAttemptLedgerDocument
import eu.kanade.translation.artifact.FailureCategory
import eu.kanade.translation.artifact.RunConfigSnapshot
import eu.kanade.translation.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.ocrBlockFingerprints
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.translator.TranslatorComputeClass
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * T924 wave-3 slice B (wave-2 review R2 + gap 9): a checkpoint-REJECTED page
 * mid-run must leave the chapter DURABLY restartable —
 *
 *  (a) prior pages' checkpoints survive a simulated restart and stay reusable
 *      (never re-OCR'd);
 *  (b) the failed page's candidate is torn down per the B0 idiom (the
 *      candidate-held OCR never committed, so it must not survive) while the
 *      durable failure record does survive;
 *  (c) a durable failure record exists for the failed page carrying the typed
 *      rejection reason, and repeated consecutive unresolved failures respect
 *      the legacy attempt cap ([ChapterAttemptLedgerDocument
 *      .MAX_CONSECUTIVE_UNRESOLVED] = 3) — the record is capped, never
 *      unbounded;
 *  (d) the next attempt re-OCRs only the failed page and the never-reached
 *      remainder.
 *
 * Harness idioms follow `OcrPreflightCoordinatorTest` (M1): real
 * `ChapterTranslationStore` over `FakeUniFile`/`@TempDir`, a fake
 * [NativeLaneWorker] that merges OCR under the BATCH lease token; rejection is
 * injected by handing the coordinator a STALE candidate generation id, which
 * the `checkpointOcr` CLOSE transaction rejects with the typed reason
 * "candidate generation changed".
 */
class OcrPreflightRejectedMidRunDurabilityTest {

    @TempDir
    lateinit var mangaDir: File

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

    private fun openArtifactStore(): ChapterTranslationStore =
        ChapterTranslationStore.openArtifact(root(), "Chapter 1.json")

    private fun frozenConfig(): RunConfigSnapshot = ChapterProfileBatchCoordinator.frozenRunConfig(
        sourceLang = "ja",
        targetLang = "en",
        ocrEngine = "FakeOcrEngine",
        inpaintMode = "OFF",
        providerKey = "fake:provider",
    )

    private fun orderedPages(vararg pageKeys: String): List<PageKey> =
        pageKeys.mapIndexed { index, pageKey -> pageKey to index }

    private fun sourcePairs(pages: List<PageKey>): List<Pair<String, String>> =
        pages.map { (pageKey, _) -> pageKey to hex64("source-$pageKey") }

    private fun coordinator(
        store: ChapterTranslationStore,
        worker: FakePreflightOcrWorker,
        pages: List<PageKey>,
    ): ChapterProfileBatchCoordinator = ChapterProfileBatchCoordinator(
        store = store,
        nativeWorker = worker,
        frozenConfig = frozenConfig(),
        orderedSourcePairs = sourcePairs(pages),
        releaseBatchLease = { pageKey -> store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
    )

    /**
     * M1-idiom OCR lane. When [rejectCheckpointAt] matches the page (and the
     * one-shot budget is not spent), the returned reference carries a STALE
     * candidate generation id — the coordinator's `checkpointOcr` CLOSE then
     * rejects with the typed store reason "candidate generation changed",
     * exactly as a raced real transaction would.
     */
    private inner class FakePreflightOcrWorker(
        private val store: ChapterTranslationStore,
        private val rejectCheckpointAt: String? = null,
        private val rejectOnce: Boolean = false,
    ) : NativeLaneWorker {
        val ocrPages = mutableListOf<String>()
        val rejections = AtomicInteger(0)

        override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? {
            ocrPages += pageKey
            val lease = store.tryAcquirePageStageLease(pageKey, PageStage.Ocr, PageWriteOrigin.BATCH)
                .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
            val before = store.snapshot(pageKey)
            val resultPage = ocrPage(pageKey, "source-$pageKey").apply {
                sourceFingerprint = hex64("source-$pageKey")
                detectionFingerprint = hex64("detection-${hex64("source-$pageKey")}")
                ocrFingerprint = hex64("ocr-${hex64("source-$pageKey")}")
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
                description = "t924 rejected-mid-run fake ocr",
            ).shouldBeInstanceOf<StagePatchResult.Accepted>()
            val after = store.snapshot(pageKey)
            val staleCandidate = rejectCheckpointAt == pageKey &&
                (!rejectOnce || rejections.getAndIncrement() == 0)
            return OcrReadyPageRef(
                pageKey = pageKey,
                pageIndex = pageIndex,
                generation = after.generation,
                blockFingerprints = emptyList(),
                leaseToken = lease.token,
                candidateGenerationId = if (staleCandidate) {
                    "stale-candidate-generation"
                } else {
                    after.candidateGenerationId
                },
                dependencyFingerprint = after.dependencyFingerprint,
                artifactPageVersion = after.artifactPageVersion,
                nativeHandoff = "decoded-bitmap-$pageKey",
            )
        }

        override suspend fun runInpaintStage(pageKey: String) {}

        override fun releaseNativeHandoff(ref: OcrReadyPageRef) {}
    }

    private fun durableOcrFailure(
        store: ChapterTranslationStore,
        pageKey: String,
    ) = store.durableFailuresSnapshot()["$pageKey:${ArtifactStage.OCR.name}"]

    @Test
    fun `checkpoint rejected mid run stays restartable and records the failure durably`() = runTest {
        val store = lazyStore()
        store.preRegisterPages(listOf("p1", "p2", "p3"))
        val worker = FakePreflightOcrWorker(store, rejectCheckpointAt = "p2", rejectOnce = true)
        val pages = orderedPages("p1", "p2", "p3")

        val failed = coordinator(store, worker, pages).runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        // The pass is an honest FAILED anchored at the rejected page; p1
        // checkpointed, p2 was reached and rejected, p3 was never admitted.
        failed.status shouldBe BatchPass1Status.FAILED
        failed.anchorPageKey shouldBe "p2"
        worker.ocrPages shouldContainExactly listOf("p1", "p2")

        // (a) precondition of the restart: p1's checkpoint is durable.
        val manifestAfterFailure = artifactStore().readManifest().shouldNotBeNull()
        manifestAfterFailure.ocrCheckpoints.keys shouldBe setOf("p1")

        // (c) the durable failure record: anchored at p2, OCR stage, typed
        // rejection reason, first consecutive unresolved charge.
        val failure = durableOcrFailure(store, "p2").shouldNotBeNull()
        failure.pageKey shouldBe "p2"
        failure.stage shouldBe ArtifactStage.OCR
        failure.retryCount shouldBe 1
        failure.category shouldBe FailureCategory.PROTOCOL
        failure.status shouldBe ArtifactStageStatus.FAILED_RETRYABLE
        failure.lastFailureMessage shouldBe "candidate generation changed"
        failure.nextEligibleRetryAtEpochMs.shouldBeNull()

        // The page patch rode the SAME atomic publication: p2 is visibly
        // failed with the typed reason (legacy persistUnexpectedBatchStageFailure
        // idiom), while later stages stay untouched.
        val p2 = store.state.value.getValue("p2")
        p2.ocrStatus shouldBe StageStatus.FAILED
        // The durable error surface is the stage error (errorMessage is a
        // transient body property whose setter routes into ocrError when the
        // stage is FAILED; only constructor properties survive copies).
        p2.activeError shouldBe "candidate generation changed"
        p2.translationStatus shouldBe StageStatus.PENDING
        p2.renderStatus shouldBe StageStatus.PENDING

        // (b) B0 teardown: the failed page's candidate-held OCR (never
        // checkpointed) was cancelled before the ledger record was installed
        // (cancelCandidate strips candidate-owned stage records), and NOTHING
        // was promoted to a committed display on any page.
        manifestAfterFailure.pages.values.forEach { pageRecord ->
            pageRecord.committed.shouldBeNull()
        }
        store.pageLeaseOwner("p2").shouldBeNull()

        // ---- simulated restart: fresh stores over the SAME document set. ----
        val resumedStore = openArtifactStore()
        val resumedWorker = FakePreflightOcrWorker(resumedStore)

        val resumed = coordinator(resumedStore, resumedWorker, pages)
            .runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        // (d) only p2 (rejected) and p3 (never reached) were re-OCR'd; p1 was
        // reused from its surviving checkpoint, never re-decoded.
        resumed.status shouldBe BatchPass1Status.PAUSED
        // Stage 5 slice A: a COMPLETE corpus continues into the analysis
        // phase, which stops at the typed no-transport CONFIGURATION gate.
        resumed.reason shouldBe ChapterProfileBatchCoordinator.ANALYSIS_NO_TRANSPORT_REASON
        resumedWorker.ocrPages shouldContainExactly listOf("p2", "p3")
        resumedStore.pageLeaseOwner("p1").shouldBeNull()

        val manifest = artifactStore().readManifest().shouldNotBeNull()
        manifest.ocrCheckpoints.keys shouldBe setOf("p1", "p2", "p3")
        val record = (
            artifactStore().readRunRecord(manifest.activeRun.shouldNotBeNull())
                as ChapterArtifactStore.RunRecordRead.Usable
            ).record
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_REUSED] shouldBe 1
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_DONE] shouldBe 3
        record.ocrCorpusFingerprint.shouldNotBeNull()
    }

    @Test
    fun `repeated checkpoint rejections respect the consecutive unresolved attempt cap`() = runTest {
        val pages = orderedPages("p1", "p2", "p3")
        val cap = ChapterAttemptLedgerDocument.MAX_CONSECUTIVE_UNRESOLVED

        // Run 1 starts from a lazy store; every later run is a fresh store over
        // the SAME documents (simulated restart between attempts).
        var store = lazyStore().also { it.preRegisterPages(listOf("p1", "p2", "p3")) }
        repeat(cap + 1) { attempt ->
            val worker = FakePreflightOcrWorker(store, rejectCheckpointAt = "p2")
            val outcome = coordinator(store, worker, pages).runPass1(pages, TranslatorComputeClass.REMOTE_IO)
            outcome.status shouldBe BatchPass1Status.FAILED
            outcome.anchorPageKey shouldBe "p2"

            val failure = durableOcrFailure(store, "p2").shouldNotBeNull()
            val expectedCharge = attempt + 1
            if (expectedCharge <= cap) {
                // Uncapped charge: the consecutive count advances.
                failure.retryCount shouldBe expectedCharge
                failure.category shouldBe FailureCategory.PROTOCOL
                failure.lastFailureMessage shouldBe "candidate generation changed"
            } else {
                // Cap reached: the count STOPS (never unlimited) and the record
                // is re-stamped as an INTERRUPTED-class, manual-retry-only
                // failure — the legacy applyAttemptCapPause vocabulary.
                failure.retryCount shouldBe cap
                failure.category shouldBe FailureCategory.INTERRUPTED
                failure.lastFailureMessage shouldContain "attempt cap reached"
                failure.lastFailureMessage shouldContain "manual retry required"
                failure.lastFailureMessage shouldContain "candidate generation changed"
            }
            failure.status shouldBe ArtifactStageStatus.FAILED_RETRYABLE

            // The anchor page is visibly failed and its lease is released
            // (B0 teardown ran before the ledger record was installed).
            store.state.value.getValue("p2").ocrStatus shouldBe StageStatus.FAILED
            artifactStore().readManifest().shouldNotBeNull().let { manifest ->
                manifest.ocrCheckpoints.keys shouldBe setOf("p1")
            }
            store.pageLeaseOwner("p2").shouldBeNull()

            if (attempt < cap) {
                store = openArtifactStore()
            }
        }
    }
}
