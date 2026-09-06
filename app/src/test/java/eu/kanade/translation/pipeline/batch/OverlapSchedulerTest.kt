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
import eu.kanade.translation.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.model.InpaintMaskBox
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocrBlockFingerprints
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * T924 Stage 7 gate 6.x basis (implementation-sequence §S7): the
 * [OverlapScheduler] — serial local inpaint inside the single in-flight
 * remote request window, EXISTING native inpaint lane + guarded identity
 * path, native admission unchanged (never preempts a MANUAL owner), strictly
 * one inpaint at a time, zero detector/OCR overlap, teardown safety, and the
 * gate-6.5 keep-or-revert counters.
 */
class OverlapSchedulerTest {

    @TempDir
    lateinit var mangaDir: File

    // ------------------------------------------------------------------
    // Scaffolding (dispatch-test idioms).
    // ------------------------------------------------------------------

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
        inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 1)),
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

    /** Seeds a page with OCR READY + translation READY, inpaint still pending. */
    private suspend fun seedTranslatedPage(store: ChapterTranslationStore, pageKey: String) {
        store.preRegisterPages(listOf(pageKey))
        val lease = store.tryAcquirePageStageLease(pageKey, PageStage.Ocr, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
        val before = store.snapshot(pageKey)
        store.mergeOcr(
            OcrStagePatch(
                pageKey = pageKey,
                generation = before.generation,
                expectedPageVersion = before.pageVersion,
                expectedPriorOcrFingerprints = before.page?.ocrBlockFingerprints().orEmpty(),
                ocrResult = ocrPage(pageKey, "source-$pageKey"),
                expectedLeaseToken = lease.token,
            ),
            description = "t924 overlap test ocr",
        ).shouldBeInstanceOf<StagePatchResult.Accepted>()
        store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH)

        val tLease = store.tryAcquirePageStageLease(pageKey, PageStage.Translation, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
        val mid = store.snapshot(pageKey)
        store.updatePageGuarded(
            pageKey = pageKey,
            expected = ChapterTranslationStore.PatchPrecondition(
                generation = mid.generation,
                pageVersion = mid.pageVersion,
                leaseToken = tLease.token,
            ),
            description = "t924 overlap test translation ready",
        ) { page ->
            page!!.apply { translationStatus = StageStatus.READY }
        }.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
        store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH)
    }

    /**
     * The EXISTING native lane stand-in: performs the durable inpaint merge
     * through the SAME guarded identity path the real lane's
     * `guardedBatchUpdate` uses (identity from the shared map, refreshed from
     * the accepted snapshot), and counts every detector/OCR entry (must stay
     * zero — inpaint only overlaps the remote wait).
     */
    private inner class FakeInpaintLane(
        private val store: ChapterTranslationStore,
        private val identities: ConcurrentHashMap<String, BatchWriteIdentity>,
    ) : NativeLaneWorker {
        val inpainted = mutableListOf<String>()
        val ocrEntries = mutableListOf<String>()
        val concurrent = AtomicIntegerMax()

        override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? {
            ocrEntries += pageKey
            return null
        }

        override suspend fun runInpaintStage(pageKey: String) {
            runInpaintStage(pageKey, null)
        }

        override suspend fun runInpaintStage(pageKey: String, nativeHandoff: Any?) {
            inpainted += pageKey
            concurrent.enter()
            try {
                val identity = identities[pageKey]
                    ?: error("overlap scheduler must register the write identity for $pageKey")
                val result = store.updatePageGuarded(
                    pageKey = pageKey,
                    expected = ChapterTranslationStore.PatchPrecondition(
                        generation = identity.generation,
                        pageVersion = identity.pageVersion,
                        leaseToken = identity.leaseToken,
                        candidateGenerationId = identity.candidateGenerationId,
                        dependencyFingerprint = identity.dependencyFingerprint,
                        artifactPageVersion = identity.artifactPageVersion,
                    ),
                    description = "t924 overlap test inpaint",
                ) { page ->
                    page!!.apply { inpaintStatus = StageStatus.READY }
                }
                result.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
            } finally {
                concurrent.exit()
            }
        }
    }

    /** Max observed concurrency probe (strictly-one-inpaint assertion). */
    private class AtomicIntegerMax {
        private val value = java.util.concurrent.atomic.AtomicInteger(0)
        private val max = java.util.concurrent.atomic.AtomicInteger(0)
        fun enter() {
            max.accumulateAndGet(value.incrementAndGet()) { a, b -> maxOf(a, b) }
        }

        fun exit() {
            value.decrementAndGet()
        }

        fun observedMax(): Int = max.get()
    }

    private fun scheduler(
        store: ChapterTranslationStore,
        worker: NativeLaneWorker,
        pageKeys: List<String>,
        identities: ConcurrentHashMap<String, BatchWriteIdentity>,
    ): OverlapScheduler = OverlapScheduler(
        store = store,
        nativeWorker = worker,
        orderedPageKeys = pageKeys,
        batchWriteIdentities = identities,
        releaseBatchLease = { pageKey -> store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
    )

    // ------------------------------------------------------------------
    // Tests.
    // ------------------------------------------------------------------

    @Test
    fun `inpaint runs inside the remote window through the existing lane with zero ocr overlap`() = runTest {
        val store = lazyStore()
        val pageKeys = listOf("p1", "p2", "p3")
        store.preRegisterPages(pageKeys)
        pageKeys.forEach { seedTranslatedPage(store, it) }
        val identities = ConcurrentHashMap<String, BatchWriteIdentity>()
        val lane = FakeInpaintLane(store, identities)
        val scheduler = scheduler(store, lane, pageKeys, identities)

        val loopJob = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { scheduler.runOverlapLoop() }
        scheduler.onRemoteWindowOpened()
        testScheduler.advanceUntilIdle()
        scheduler.onRemoteWindowClosed()
        scheduler.stopOverlap()
        loopJob.cancel()

        // Every committed page was inpainted exactly once, through the
        // EXISTING lane, strictly one at a time, with ZERO detector/OCR work.
        lane.inpainted shouldBe pageKeys
        lane.ocrEntries shouldBe emptyList()
        lane.concurrent.observedMax() shouldBe 1

        // Gate-6.5 counters: overlap arm did the work.
        scheduler.counters.snapshot()["overlapInpaintsExecuted"] shouldBe 3L
        scheduler.counters.snapshot()["overlapWindowsCount"] shouldBe 1L
        scheduler.counters.snapshot()["serialInpaintsExecuted"] shouldBe 0L

        // Teardown discipline: identities deregistered, BATCH leases released.
        identities.isEmpty() shouldBe true
        pageKeys.forEach { key ->
            store.snapshot(key).leaseToken shouldBe null
        }
    }

    @Test
    fun `manual-owned lease is never preempted - page skipped and window falls back`() = runTest {
        val store = lazyStore()
        val pageKeys = listOf("p1", "p2")
        store.preRegisterPages(pageKeys)
        pageKeys.forEach { seedTranslatedPage(store, it) }
        // A MANUAL owner owns p1's inpaint stage (native admission unchanged).
        store.tryAcquirePageStageLease("p1", PageStage.Inpaint, PageWriteOrigin.MANUAL)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>()
        val identities = ConcurrentHashMap<String, BatchWriteIdentity>()
        val lane = FakeInpaintLane(store, identities)
        val scheduler = scheduler(store, lane, pageKeys, identities)

        val loopJob = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { scheduler.runOverlapLoop() }
        scheduler.onRemoteWindowOpened()
        testScheduler.advanceUntilIdle()
        scheduler.onRemoteWindowClosed()
        scheduler.stopOverlap()
        loopJob.cancel()

        // p1 skipped (MANUAL wins), p2 inpainted through the lane.
        lane.inpainted shouldBe listOf("p2")
        scheduler.counters.snapshot()["overlapInpaintsExecuted"] shouldBe 1L
        // The window could not serve its full work — gate-6.5 fallback marker.
        scheduler.counters.snapshot()["serialFallbacks"] shouldBe 1L
        // The MANUAL lease is untouched.
        store.snapshot("p1").leaseToken.shouldNotBeNull()
    }

    @Test
    fun `serial drain is the keep-serial arm - same lane, one at a time, zero windows`() = runTest {
        val store = lazyStore()
        val pageKeys = listOf("p1", "p2")
        store.preRegisterPages(pageKeys)
        pageKeys.forEach { seedTranslatedPage(store, it) }
        val identities = ConcurrentHashMap<String, BatchWriteIdentity>()
        val lane = FakeInpaintLane(store, identities)
        val scheduler = scheduler(store, lane, pageKeys, identities)

        scheduler.drainSerial()

        lane.inpainted shouldBe pageKeys
        lane.concurrent.observedMax() shouldBe 1
        scheduler.counters.snapshot()["serialInpaintsExecuted"] shouldBe 2L
        scheduler.counters.snapshot()["overlapInpaintsExecuted"] shouldBe 0L
        scheduler.counters.snapshot()["overlapWindowsCount"] shouldBe 0L
    }

    @Test
    fun `inpaint commit hook fires per committed page (stage-7 publication seam)`() = runTest {
        val store = lazyStore()
        val pageKeys = listOf("p1", "p2")
        store.preRegisterPages(pageKeys)
        pageKeys.forEach { seedTranslatedPage(store, it) }
        val identities = ConcurrentHashMap<String, BatchWriteIdentity>()
        val lane = FakeInpaintLane(store, identities)
        val scheduler = scheduler(store, lane, pageKeys, identities)
        val committedHooks = mutableListOf<String>()
        scheduler.onInpaintCommitted = { pageKey ->
            committedHooks += pageKey
        }

        val loopJob = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { scheduler.runOverlapLoop() }
        scheduler.onRemoteWindowOpened()
        testScheduler.advanceUntilIdle()
        scheduler.onRemoteWindowClosed()
        scheduler.stopOverlap()
        loopJob.cancel()

        committedHooks.size shouldBe 2
        committedHooks.toSet() shouldBe pageKeys.toSet()
    }
}
