package eu.kanade.translation.pipeline.batch

import com.hippo.unifile.FakeUniFile
import com.hippo.unifile.UniFile
import eu.kanade.translation.storage.ChapterTranslationStore
import eu.kanade.translation.pipeline.LeaseAcquisition
import eu.kanade.translation.pipeline.OcrStagePatch
import eu.kanade.translation.pipeline.PageWriteOrigin
import eu.kanade.translation.pipeline.StagePatchResult
import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactEngine
import eu.kanade.translation.artifact.ChapterRunRecord
import eu.kanade.translation.artifact.ChapterRunState
import eu.kanade.translation.artifact.RunConfigSnapshot
import eu.kanade.translation.artifact.StageFingerprints
import eu.kanade.translation.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.pipeline.ocrBlockFingerprints
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.isTranslationDisplayReady
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.translator.TranslatorComputeClass
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * T924 Stage 3 (WP4) coordinator-shell coverage: the FF-01 flagged
 * [ChapterProfileBatchCoordinator] runs the durable machine ONLY through
 * OCR_PREFLIGHT — serial per page (one decoded page at a time), checkpointOcr
 * CLOSE, lease release strictly after the checkpoint (T924-TX-06) — then
 * STOPS with a durable diagnostic instead of redefining completion.
 *
 * Harness idioms follow `OcrCheckpointRestartReuseTest` (M1): real
 * `ChapterTranslationStore` over `FakeUniFile`/`@TempDir`, a fake
 * [NativeLaneWorker] that acquires the BATCH lease, merges OCR under the
 * lease token and returns the fencing identity — the coordinator owns the
 * checkpoint + release ordering.
 */
class OcrPreflightCoordinatorTest {

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

    private fun artifactStore(): ChapterArtifactEngine =
        ChapterArtifactEngine(
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

    private fun sourcePairs(pages: List<PageKey>): List<Pair<String, String>> =
        pages.map { (pageKey, _) -> pageKey to hex64("source-$pageKey") }

    private fun coordinator(
        store: ChapterTranslationStore,
        worker: FakePreflightOcrWorker,
        pages: List<PageKey>,
        sourcePairsOverride: List<Pair<String, String>>? = null,
    ): ChapterProfileBatchCoordinator = ChapterProfileBatchCoordinator(
        store = store,
        nativeWorker = worker,
        frozenConfig = frozenConfig(),
        orderedSourcePairs = sourcePairsOverride ?: sourcePairs(pages),
        releaseBatchLease = { pageKey -> store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
    )

    /** M1-idiom OCR lane: lease, merge under the token, hand the identity back. */
    private inner class FakePreflightOcrWorker(
        private val store: ChapterTranslationStore,
    ) : NativeLaneWorker {
        val ocrPages = mutableListOf<String>()
        val releasedHandoffs = mutableListOf<String>()
        var failAt: String? = null
        var textFor: (String) -> String = { pageKey -> "source-$pageKey" }
        /** Current source-bytes identity, as the real lane hashes at OCR time. */
        var sourceShaFor: (String) -> String = { pageKey -> hex64("source-$pageKey") }
        val maxInFlight = AtomicInteger(0)
        val inFlight = AtomicInteger(0)

        override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? {
            // The attempt is recorded before any simulated death: a kill can
            // lose the in-flight page's WORK, not the fact it was reached.
            ocrPages += pageKey
            if (pageKey == failAt) throw IllegalStateException("simulated worker death")
            val current = inFlight.incrementAndGet()
            maxInFlight.accumulateAndGet(current) { previous, candidate -> maxOf(previous, candidate) }
            try {
                val lease = store.tryAcquirePageStageLease(pageKey, PageStage.Ocr, PageWriteOrigin.BATCH)
                    .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
                val before = store.snapshot(pageKey)
                // The OCR content reflects the CURRENT source bytes: detection
                // and OCR fingerprints derive from them, exactly as a real
                // recognition pass over replaced page files would.
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
                    description = "t924 fake preflight ocr",
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
                    // A decoded handoff exists for exactly one page and must
                    // never survive past the coordinator's page boundary.
                    nativeHandoff = "decoded-bitmap-$pageKey",
                )
            } finally {
                inFlight.decrementAndGet()
            }
        }

        override suspend fun runInpaintStage(pageKey: String) {}

        override fun releaseNativeHandoff(ref: OcrReadyPageRef) {
            releasedHandoffs += ref.pageKey
        }
    }

    private fun runRecord(store: ChapterTranslationStore): ChapterRunRecord? {
        val manifest = artifactStore().readManifest().shouldNotBeNull()
        val pointer = manifest.activeRun.shouldNotBeNull()
        return (artifactStore().readRunRecord(pointer) as ChapterArtifactEngine.RunRecordRead.Usable).record
    }

    @Test
    fun `preflight checkpoints every page serially and stops with a durable diagnostic`() = runTest {
        val store = lazyStore()
        store.preRegisterPages(listOf("p1", "p2", "p3"))
        val worker = FakePreflightOcrWorker(store)
        val pages = orderedPages("p1", "p2", "p3")

        val outcome = coordinator(store, worker, pages).runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        // Stopped-not-finished: never a COMPLETED pass (which would strand the
        // untranslated pages as failed), always resumable. Stage-5 slice A:
        // the complete corpus continues into the analysis phase, which pauses
        // at the typed CONFIGURATION gate (no transport wired in this slice).
        outcome.status shouldBe BatchPass1Status.PAUSED
        outcome.needsTranslation shouldContainExactly emptyList()
        outcome.reason shouldBe ChapterProfileBatchCoordinator.ANALYSIS_NO_TRANSPORT_REASON
        outcome.completedPageKeys shouldBe setOf("p1", "p2", "p3")

        // Serial OCR only: natural order, one decoded page at a time, every
        // handoff released at its page boundary, no lease left behind.
        worker.ocrPages shouldContainExactly listOf("p1", "p2", "p3")
        worker.maxInFlight.get() shouldBe 1
        worker.releasedHandoffs shouldContainExactly listOf("p1", "p2", "p3")
        listOf("p1", "p2", "p3").forEach { pageKey ->
            store.pageLeaseOwner(pageKey).shouldBeNull()
        }

        // Durable preflight: origin-neutral checkpoints on every page.
        val manifest = artifactStore().readManifest().shouldNotBeNull()
        manifest.ocrCheckpoints.keys shouldBe setOf("p1", "p2", "p3")

        // The durable diagnostic: preflight complete + counter summary +
        // corpus fingerprint; the analysis phase recorded its CONFIGURATION
        // gate (no transport in this slice); flag frozen (FF-01d).
        val record = runRecord(store).shouldNotBeNull()
        record.state shouldBe ChapterRunState.ANALYSIS_CHUNKS
        record.runId shouldMatch Regex("run-\\d+-[0-9a-f]{8}")
        record.frozenConfig shouldBe frozenConfig()
        record.frozenRunConfigFingerprint shouldMatch Regex("[0-9a-f]{64}")
        record.frozenRunConfigFingerprint shouldBe
            ChapterProfileBatchCoordinator.runConfigFingerprint(frozenConfig())
        record.orderedSourceDigest shouldMatch Regex("[0-9a-f]{64}")
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_FLAG] shouldBe 1
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_TOTAL] shouldBe 3
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_DONE] shouldBe 3
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_REUSED] shouldBe 0
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_STOP] shouldBe 1
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_GAPS] shouldBe 0
        // 3 pages fit ONE analysis chunk window.
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_CHUNKS_TOTAL] shouldBe 1
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_SKIPPED_NO_TRANSPORT] shouldBe 1
        val corpusFingerprint = record.ocrCorpusFingerprint.shouldNotBeNull()
        corpusFingerprint shouldBe StageFingerprints.ocrCorpusFingerprint(
            pages = listOf("p1", "p2", "p3").map { pageKey ->
                pageKey to (artifactStore().readOcrCheckpoint(manifest.ocrCheckpoints.getValue(pageKey))
                    as ChapterArtifactEngine.OcrCheckpointRead.Usable
                ).checkpoint.ocrContentFingerprint
            },
            expectedPageCount = 3,
            expectedPageCountTrusted = true,
            naturalOrderProven = true,
        )

        // Completion semantics NOT redefined: pages are OCR-ready work items,
        // never visually promoted (no translation/render/display advance).
        val page = store.state.value.getValue("p1")
        page.ocrStatus shouldBe StageStatus.READY
        page.translationStatus shouldBe StageStatus.PENDING
        page.renderStatus shouldBe StageStatus.PENDING
        page.isTranslationDisplayReady shouldBe false
    }

    @Test
    fun `mid-preflight death resumes and re-ocres only the remainder`() = runTest {
        val store = lazyStore()
        store.preRegisterPages(listOf("p1", "p2", "p3"))
        val firstWorker = FakePreflightOcrWorker(store).apply { failAt = "p2" }
        val pages = orderedPages("p1", "p2", "p3")
        val firstCoordinator = coordinator(store, firstWorker, pages)

        val failed = firstCoordinator.runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        // The kill loses at most the active page: p1 stayed checkpointed, p2
        // holds no lease, no checkpoint; the outcome is an honest FAILED.
        failed.status shouldBe BatchPass1Status.FAILED
        failed.anchorPageKey shouldBe "p2"
        firstWorker.ocrPages shouldContainExactly listOf("p1", "p2")
        artifactStore().readManifest().shouldNotBeNull().ocrCheckpoints.keys shouldBe setOf("p1")
        store.pageLeaseOwner("p2").shouldBeNull()
        val interruptedRecord = runRecord(store).shouldNotBeNull()

        // ---- simulated process death: fresh stores over the SAME document
        // set; leases and live pages are gone; durable state reconciles. ----
        val resumedStore = ChapterTranslationStore.openArtifact(root(), "Chapter 1.json")
        val resumedWorker = FakePreflightOcrWorker(resumedStore)

        val resumed = coordinator(resumedStore, resumedWorker, pages)
            .runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        // Only the remainder was OCR'd: the checkpointed page was skipped by
        // content identity (ST-01.4/ST-06), never re-decoded. The analysis
        // phase then pauses at the no-transport gate again (no chunks were
        // persisted — the slice-A shell never wires a transport).
        resumed.status shouldBe BatchPass1Status.PAUSED
        resumed.reason shouldBe ChapterProfileBatchCoordinator.ANALYSIS_NO_TRANSPORT_REASON
        resumedWorker.ocrPages shouldContainExactly listOf("p2", "p3")
        resumedWorker.releasedHandoffs shouldContainExactly listOf("p2", "p3")
        resumedStore.pageLeaseOwner("p1").shouldBeNull()

        val manifest = artifactStore().readManifest().shouldNotBeNull()
        manifest.ocrCheckpoints.keys shouldBe setOf("p1", "p2", "p3")
        manifest.analysisChunks shouldBe emptyList()
        val record = runRecord(resumedStore).shouldNotBeNull()
        record.state shouldBe ChapterRunState.ANALYSIS_CHUNKS
        // Same frozen configuration: the interrupted run is CONTINUED (same
        // run id), not silently restarted under a new snapshot (ST-03.1).
        record.runId shouldBe interruptedRecord.runId
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_DONE] shouldBe 3
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_REUSED] shouldBe 1
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_STOP] shouldBe 1
        record.ocrCorpusFingerprint.shouldNotBeNull()
    }

    @Test
    fun `changed source identity re-ocres a stale checkpointed page`() = runTest {
        val store = lazyStore()
        store.preRegisterPages(listOf("p1", "p2"))
        val firstWorker = FakePreflightOcrWorker(store)
        val pages = orderedPages("p1", "p2")
        coordinator(store, firstWorker, pages).runPass1(pages, TranslatorComputeClass.REMOTE_IO)
        val firstCorpus = runRecord(store).shouldNotBeNull().ocrCorpusFingerprint.shouldNotBeNull()

        // The page file was replaced under the same key: the current source
        // digest no longer matches the durable checkpoint identity, so the
        // page MUST re-run instead of reusing stale OCR (ST-06 skip rule).
        val resumedStore = ChapterTranslationStore.openArtifact(root(), "Chapter 1.json")
        val changedSources = sourcePairs(pages).map { (pageKey, _) ->
            pageKey to hex64("replaced-source-$pageKey")
        }
        val resumedWorker = FakePreflightOcrWorker(resumedStore).apply {
            // The replaced page files hash differently at OCR time, exactly as
            // the real lane would stamp the live source fingerprint.
            sourceShaFor = { pageKey -> hex64("replaced-source-$pageKey") }
        }

        val outcome = coordinator(resumedStore, resumedWorker, pages, changedSources)
            .runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        outcome.status shouldBe BatchPass1Status.PAUSED
        resumedWorker.ocrPages shouldContainExactly listOf("p1", "p2")
        val record = runRecord(resumedStore).shouldNotBeNull()
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_REUSED] shouldBe 0
        record.ocrCorpusFingerprint.shouldNotBeNull() shouldNotBe firstCorpus
    }

    @Test
    fun `empty page set is a no-op without a run record`() = runTest {
        val store = lazyStore()
        store.preRegisterPages(listOf("p1"))
        val worker = FakePreflightOcrWorker(store)

        val outcome = coordinator(store, worker, emptyList())
            .runPass1(emptyList(), TranslatorComputeClass.REMOTE_IO)

        outcome.status shouldBe BatchPass1Status.COMPLETED
        worker.ocrPages shouldContainExactly emptyList()
        artifactStore().readManifest().shouldNotBeNull().activeRun.shouldBeNull()
    }
}
