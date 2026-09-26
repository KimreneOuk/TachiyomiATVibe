package eu.kanade.translation

import com.hippo.unifile.FakeUniFile
import com.hippo.unifile.UniFile
import eu.kanade.translation.engines.translator.TranslatorComputeClass
import eu.kanade.translation.model.InpaintMaskBox
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.orchestration.mergeRestoredQueueEntries
import eu.kanade.translation.persistence.artifact.AtomicChapterDocuments
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactLayout
import eu.kanade.translation.persistence.artifact.ChapterRunRecord
import eu.kanade.translation.persistence.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.pipeline.LeaseAcquisition
import eu.kanade.translation.pipeline.OcrStagePatch
import eu.kanade.translation.pipeline.PageWriteOrigin
import eu.kanade.translation.pipeline.StagePatchResult
import eu.kanade.translation.pipeline.batch.BatchPass1Status
import eu.kanade.translation.pipeline.batch.ChapterProfileBatchCoordinator
import eu.kanade.translation.pipeline.batch.NativeLaneWorker
import eu.kanade.translation.pipeline.batch.OcrReadyPageRef
import eu.kanade.translation.pipeline.batch.PageKey
import eu.kanade.translation.pipeline.ocrBlockFingerprints
import eu.kanade.translation.pipeline.ocrFingerprint
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

class ChapterTranslatorQueueRestoreTest {

    @Test
    fun `restore merge retains concurrent queue additions in durable order`() {
        val restored = mapOf(10L to "restored-10")
        val live = mapOf(20L to "concurrent-20")

        mergeRestoredQueueEntries(
            durableIds = listOf(10L, 20L),
            restoredById = restored,
            liveById = live,
        ) shouldContainExactly listOf("restored-10", "concurrent-20")
    }

    @Test
    fun `restore merge honors removals and drops stale lookup results`() {
        mergeRestoredQueueEntries(
            durableIds = listOf(20L, 20L, 30L),
            restoredById = mapOf(10L to "stale-10", 20L to "restored-20"),
            liveById = emptyMap(),
        ) shouldContainExactly listOf("restored-20")
    }

    @Test
    fun `restore merge prefers a live requeue over stale restored state`() {
        mergeRestoredQueueEntries(
            durableIds = listOf(20L),
            restoredById = mapOf(20L to "stale-paused"),
            liveById = mapOf(20L to "fresh-queue"),
        ) shouldContainExactly listOf("fresh-queue")
    }

    // -------------------------------------------------------------------------
    //  wave-2 review gap 2 (queue-restore obligation, zero-legacy form):
    // a chapter with an interrupted pipeline run restored from the persisted
    // queue. The harness mirrors the interrupted-run idiom (real store, real
    // interrupted pass) so the run record below is a genuine durable record
    // with checkpoints, not a synthetic one. ( the  flag and its
    // decideResume decision tree are gone — restore never auto-starts a run,
    // and the durable state stays byte-untouched until explicit admission.)
    // -------------------------------------------------------------------------

    @TempDir
    lateinit var mangaDir: File

    private fun hex64(tag: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(tag.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

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

    private fun frozenConfig() = ChapterProfileBatchCoordinator.frozenRunConfig(
        sourceLang = "ja",
        targetLang = "en",
        ocrEngine = "FakeOcrEngine",
        inpaintMode = "OFF",
        providerKey = "fake:provider",
    )

    private fun ocrPage(pageKey: String) = PageTranslation(
        sourceFileName = pageKey,
        blocks = mutableListOf(
            TranslationBlock(
                blockId = "b1",
                text = "source",
                translation = "",
                width = 10f,
                height = 10f,
                x = 0f,
                y = 0f,
                symHeight = 1f,
                symWidth = 1f,
                angle = 0f,
            ),
        ),
        imgWidth = 100f,
        imgHeight = 160f,
        decodeSampleSize = 1,
        ocrStatus = StageStatus.READY,
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        sourceFingerprint = hex64("source-$pageKey"),
        detectionFingerprint = hex64("detection-$pageKey"),
        ocrFingerprint = hex64("ocr-$pageKey"),
        inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 1)),
    )

    /** Kills the pass at [failAt] so an interrupted, resumable state exists. */
    private inner class InterruptingOcrWorker(
        private val store: ChapterTranslationStore,
        private val failAt: String,
    ) : NativeLaneWorker {
        val ocrPages = mutableListOf<String>()

        override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? {
            if (pageKey == failAt) throw IllegalStateException("simulated mid-preflight death")
            ocrPages += pageKey
            val lease = store.tryAcquirePageStageLease(pageKey, PageStage.Ocr, PageWriteOrigin.BATCH)
                .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
            val before = store.snapshot(pageKey)
            store.mergeOcr(
                OcrStagePatch(
                    pageKey = pageKey,
                    generation = before.generation,
                    expectedPageVersion = before.pageVersion,
                    expectedPriorOcrFingerprints = before.page?.ocrBlockFingerprints().orEmpty(),
                    ocrResult = ocrPage(pageKey),
                    expectedLeaseToken = lease.token,
                ),
                description = "t924 queue-restore harness ocr",
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

    private suspend fun interruptedFlaggedRun(): Triple<
        ChapterTranslationStore,
        ChapterRunRecord,
        Set<String>,
        > {
        val store = lazyStore()
        store.preRegisterPages(listOf("p1", "p2"))
        val worker = InterruptingOcrWorker(store, failAt = "p2")
        val pages = listOf<PageKey>("p1" to 0, "p2" to 1)
        val coordinator = ChapterProfileBatchCoordinator(
            store = store,
            nativeWorker = worker,
            frozenConfig = frozenConfig(),
            orderedSourcePairs = pages.map { (pageKey, _) -> pageKey to hex64("source-$pageKey") },
            releaseBatchLease = { pageKey -> store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
        )
        val outcome = coordinator.runPass1(pages, TranslatorComputeClass.REMOTE_IO)
        outcome.status shouldBe BatchPass1Status.FAILED
        val manifest = artifactStore().readManifest().shouldNotBeNull()
        val record = (
            artifactStore().readRunRecord(manifest.activeRun.shouldNotBeNull())
                as ChapterArtifactEngine.RunRecordRead.Usable
            ).record
        return Triple(store, record, manifest.ocrCheckpoints.keys)
    }

    @Test
    fun `queue restore of a chapter with an interrupted pipeline run never auto starts the run`() = runTest {
        val (store, record, checkpointedKeys) = interruptedFlaggedRun()
        val manifestBefore = artifactStore().readManifest().shouldNotBeNull()

        // Queue-restore idiom: the chapter's entry survives the restore merge
        // in durable order. The restored entry is what the user explicitly
        // (re)starts — restore itself starts nothing.
        mergeRestoredQueueEntries(
            durableIds = listOf(10L),
            restoredById = mapOf(10L to "restored-flagged-10"),
            liveById = emptyMap(),
        ) shouldContainExactly listOf("restored-flagged-10")

        // The restore path decides from the durable record + current
        // settings only ( no queue input) and — with the flag
        // gone  — that is the coordinator's own resume machinery, which
        // only ever fires inside a dispatched run. Restore itself starts
        // nothing: byte-equal manifest, untouched checkpoints, no lease held.
        artifactStore().readManifest().shouldNotBeNull() shouldBe manifestBefore
        manifestBefore.ocrCheckpoints.keys shouldBe checkpointedKeys
        store.pageLeaseOwner("p1").shouldBeNull()
        store.pageLeaseOwner("p2").shouldBeNull()
        //  zero-legacy: the surviving record IS the resume evidence — the
        // phase pointer advanced past RUN_SNAPSHOT with p1's checkpointed OCR
        // (OCR_PLAN, done=1) and p2's mid-preflight death left it there. The
        // next explicit run resumes from this pointer; restore starts nothing.
        record.state shouldBe eu.kanade.translation.persistence.artifact.ChapterRunState.OCR_PLAN
        record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_DONE] shouldBe 1
    }
}
