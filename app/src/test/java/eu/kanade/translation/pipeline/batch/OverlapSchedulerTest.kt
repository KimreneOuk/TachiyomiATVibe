package eu.kanade.translation.pipeline.batch

import com.hippo.unifile.FakeUniFile
import com.hippo.unifile.UniFile
import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.LeaseAcquisition
import eu.kanade.translation.OcrStagePatch
import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.StagePatchResult
import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.CleanedImageProbe
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactStore
import eu.kanade.translation.artifact.ProbedImage
import eu.kanade.translation.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.model.InpaintMaskBox
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.toPageDisplayProjection
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
     * T934 track I: seeds a page whose OCR is FINAL (READY, non-empty blocks)
     * while its translation is still PENDING — the decoupled gate's new
     * candidate shape. Inpaint's data dependency is detection/masks only
     * (StageFingerprints.inpaint has no translation input), so this page must
     * be schedulable without waiting for its own translation.
     */
    private suspend fun seedOcrOnlyPage(store: ChapterTranslationStore, pageKey: String) {
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
            description = "t934 overlap test ocr",
        ).shouldBeInstanceOf<StagePatchResult.Accepted>()
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

    // ------------------------------------------------------------------
    // T925 coexistence: contention with a live concurrent writer (the
    // reader's translate-on-view lane) must YIELD the page, never retry it
    // forever — the retry livelocked the whole ordered drain on device.
    // ------------------------------------------------------------------

    /**
     * Lane whose guarded publication loses a precondition race for the pages
     * in [contended] (every attempt) and commits the rest through the SAME
     * guarded merge the real lane performs.
     */
    private inner class ContendedInpaintLane(
        private val store: ChapterTranslationStore,
        private val identities: ConcurrentHashMap<String, BatchWriteIdentity>,
        private val contended: Set<String>,
    ) : NativeLaneWorker {
        val attempted = mutableListOf<String>()

        override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? = null

        override suspend fun runInpaintStage(pageKey: String) = runInpaintStage(pageKey, null)

        override suspend fun runInpaintStage(pageKey: String, nativeHandoff: Any?) {
            attempted += pageKey
            if (pageKey in contended) {
                throw BatchContentionRejectedException(
                    yieldPageKey = pageKey,
                    stage = BatchDiagnosticStage.INPAINT,
                )
            }
            val identity = identities[pageKey]
                ?: error("overlap scheduler must register the write identity for $pageKey")
            store.updatePageGuarded(
                pageKey = pageKey,
                expected = ChapterTranslationStore.PatchPrecondition(
                    generation = identity.generation,
                    pageVersion = identity.pageVersion,
                    leaseToken = identity.leaseToken,
                    candidateGenerationId = identity.candidateGenerationId,
                    dependencyFingerprint = identity.dependencyFingerprint,
                    artifactPageVersion = identity.artifactPageVersion,
                ),
                description = "t925 contention test inpaint",
            ) { page ->
                page!!.apply { inpaintStatus = StageStatus.READY }
            }.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
        }
    }

    /** Lane whose publication fails with a plain error every time. */
    private inner class FailingInpaintLane : NativeLaneWorker {
        val attempted = mutableListOf<String>()

        override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? = null

        override suspend fun runInpaintStage(pageKey: String) = runInpaintStage(pageKey, null)

        override suspend fun runInpaintStage(pageKey: String, nativeHandoff: Any?) {
            attempted += pageKey
            error("inpaint merge rejected for $pageKey")
        }
    }

    @Test
    fun `serial drain yields a contended page and terminates instead of livelocking`() = runTest {
        val store = lazyStore()
        val pageKeys = listOf("p1", "p2")
        store.preRegisterPages(pageKeys)
        pageKeys.forEach { seedTranslatedPage(store, it) }
        val identities = ConcurrentHashMap<String, BatchWriteIdentity>()
        val lane = ContendedInpaintLane(store, identities, contended = setOf("p1"))
        val scheduler = scheduler(store, lane, pageKeys, identities)

        scheduler.drainSerial()

        // p1 attempted EXACTLY once (yielded + deferred for the pass) — the
        // drain moved on and terminated; p2 still committed through the lane.
        lane.attempted.count { it == "p1" } shouldBe 1
        lane.attempted.count { it == "p2" } shouldBe 1
        store.snapshot("p2").page?.inpaintStatus shouldBe StageStatus.READY
        // p1 stays pending: the owner's outcome (or a later run) reconciles it.
        (store.snapshot("p1").page?.inpaintStatus != StageStatus.READY) shouldBe true
        scheduler.counters.snapshot()["overlapInpaintFailures"] shouldBe 1L
    }

    @Test
    fun `serial drain defers a persistently failing page instead of retrying forever`() = runTest {
        val store = lazyStore()
        val pageKeys = listOf("p1", "p2")
        store.preRegisterPages(pageKeys)
        pageKeys.forEach { seedTranslatedPage(store, it) }
        val identities = ConcurrentHashMap<String, BatchWriteIdentity>()
        val lane = FailingInpaintLane()
        val scheduler = scheduler(store, lane, pageKeys, identities)

        scheduler.drainSerial()

        lane.attempted shouldBe pageKeys
        scheduler.counters.snapshot()["overlapInpaintFailures"] shouldBe 2L
    }

    @Test
    fun `overlap loop yields a contended page and commits the rest in the same window`() = runTest {
        val store = lazyStore()
        val pageKeys = listOf("p1", "p2")
        store.preRegisterPages(pageKeys)
        pageKeys.forEach { seedTranslatedPage(store, it) }
        val identities = ConcurrentHashMap<String, BatchWriteIdentity>()
        val lane = ContendedInpaintLane(store, identities, contended = setOf("p1"))
        val scheduler = scheduler(store, lane, pageKeys, identities)

        val loopJob = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { scheduler.runOverlapLoop() }
        scheduler.onRemoteWindowOpened()
        testScheduler.advanceUntilIdle()
        scheduler.onRemoteWindowClosed()
        scheduler.stopOverlap()
        loopJob.cancel()

        // The window served both pages exactly once; the contended one was
        // yielded and NOT re-tried within the window loop.
        lane.attempted shouldBe pageKeys
        store.snapshot("p2").page?.inpaintStatus shouldBe StageStatus.READY
        (store.snapshot("p1").page?.inpaintStatus != StageStatus.READY) shouldBe true
        // Teardown discipline still holds for the yielded page.
        store.snapshot("p1").leaseToken shouldBe null
    }

    // ------------------------------------------------------------------
    // T934 track I — inpaint decoupling: the inpaint artifact fingerprint
    // (StageFingerprints.inpaint) has NO translation input, so candidate
    // admission gates on OCR being FINAL instead of the page's translation
    // status. What must NOT regress: no OCR/detector work ever rides this
    // scheduler (no inpaint during OCR preflight), the one-native-job
    // bitmap envelope, and the displayReady promotion gate (translation
    // terminal + cleaned image).
    // ------------------------------------------------------------------

    @Test
    fun `ocr-final translation-pending page is inpainted in the window without waiting for translation`() = runTest {
        val store = lazyStore()
        val pageKeys = listOf("p1", "p2")
        store.preRegisterPages(pageKeys)
        pageKeys.forEach { seedOcrOnlyPage(store, it) }
        val identities = ConcurrentHashMap<String, BatchWriteIdentity>()
        val lane = FakeInpaintLane(store, identities)
        val scheduler = scheduler(store, lane, pageKeys, identities)

        val loopJob = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { scheduler.runOverlapLoop() }
        scheduler.onRemoteWindowOpened()
        testScheduler.advanceUntilIdle()
        scheduler.onRemoteWindowClosed()
        scheduler.stopOverlap()
        loopJob.cancel()

        // Decoupled gate: BOTH pages were inpainted even though neither
        // translation has run.
        lane.inpainted shouldBe pageKeys
        scheduler.counters.snapshot()["overlapInpaintsExecuted"] shouldBe 2L
        // The never-rule survives the decoupling: ZERO detector/OCR work.
        lane.ocrEntries shouldBe emptyList()
        lane.concurrent.observedMax() shouldBe 1
        // Inpaint committed while translation stayed untouched (PENDING).
        pageKeys.forEach { key ->
            val page = store.snapshot(key).page!!
            page.ocrStatus shouldBe StageStatus.READY
            page.inpaintStatus shouldBe StageStatus.READY
            page.translationStatus shouldBe StageStatus.PENDING
            // TX-06 teardown discipline unchanged.
            store.snapshot(key).leaseToken shouldBe null
        }
    }

    @Test
    fun `non-final ocr pages are never inpaint candidates - no inpaint before ocr final`() = runTest {
        val store = lazyStore()
        val pageKeys = listOf("p1", "p2")
        store.preRegisterPages(pageKeys)
        // p1: OCR still PENDING (pre-registration only).
        // p2: OCR merged (READY), then flipped to RUNNING (mid-preflight shape).
        seedOcrOnlyPage(store, "p2")
        val lease = store.tryAcquirePageStageLease("p2", PageStage.Ocr, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
        val mid = store.snapshot("p2")
        store.updatePageGuarded(
            pageKey = "p2",
            expected = ChapterTranslationStore.PatchPrecondition(
                generation = mid.generation,
                pageVersion = mid.pageVersion,
                leaseToken = lease.token,
            ),
            description = "t934 ocr running seed",
        ) { page ->
            page!!.apply { ocrStatus = StageStatus.RUNNING }
        }.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
        store.releasePageStageLease("p2", PageWriteOrigin.BATCH)

        val identities = ConcurrentHashMap<String, BatchWriteIdentity>()
        val lane = FakeInpaintLane(store, identities)
        val scheduler = scheduler(store, lane, pageKeys, identities)

        val loopJob = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { scheduler.runOverlapLoop() }
        scheduler.onRemoteWindowOpened()
        testScheduler.advanceUntilIdle()
        scheduler.onRemoteWindowClosed()
        // The serial drain must not pick them up either.
        scheduler.drainSerial()
        scheduler.stopOverlap()
        loopJob.cancel()

        // Neither arm ever touched the lane: a non-final OCR page has no
        // durable mask, so the relaxed gate still requires OCR READY.
        lane.inpainted shouldBe emptyList()
        lane.ocrEntries shouldBe emptyList()
        scheduler.counters.snapshot()["overlapInpaintsExecuted"] shouldBe 0L
        scheduler.counters.snapshot()["serialInpaintsExecuted"] shouldBe 0L
        store.snapshot("p1").page?.ocrStatus shouldBe StageStatus.PENDING
        store.snapshot("p2").page?.ocrStatus shouldBe StageStatus.RUNNING
    }

    @Test
    fun `display promotion still requires translation terminal and cleaned image even when inpaint commits first`() = runTest {
        // T934 round 2 fixture fix: the committed-display promotion
        // (ChapterArtifactStore.promoteLiveCandidate → displayBaseIsValid)
        // validates the cleaned image FOR REAL — the companion file must exist
        // on disk and probe as a decodable image matching the page's source
        // dimensions. Production is correct; the fixture was missing both. The
        // JVM has no BitmapFactory, so install the header-probe seam
        // (ChapterTranslationStorePersistenceTest / D7 fixture recipe) and
        // persist the cleaned companion file the layout expects.
        val productionProbe = ChapterTranslationStore.artifactImageProbe
        ChapterTranslationStore.artifactImageProbe = CleanedImageProbe { ProbedImage(100, 160) }
        try {
            val store = lazyStore()
            val pageKeys = listOf("p1")
            store.preRegisterPages(pageKeys)
            pageKeys.forEach { seedOcrOnlyPage(store, it) }
            val identities = ConcurrentHashMap<String, BatchWriteIdentity>()
            val lane = FakeInpaintLane(store, identities)
            val scheduler = scheduler(store, lane, pageKeys, identities)

            val loopJob = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { scheduler.runOverlapLoop() }
            scheduler.onRemoteWindowOpened()
            testScheduler.advanceUntilIdle()
            scheduler.onRemoteWindowClosed()
            scheduler.stopOverlap()
            loopJob.cancel()

            // Inpaint committed FIRST (decoupled gate), but the page must NOT be
            // display-ready: the promotion gate still requires the translation to
            // be terminal AND a cleaned image to exist.
            val afterInpaint = store.snapshot("p1").page!!
            afterInpaint.inpaintStatus shouldBe StageStatus.READY
            afterInpaint.translationStatus shouldBe StageStatus.PENDING
            afterInpaint.cleanedImageName shouldBe null
            afterInpaint.toPageDisplayProjection().displayReady shouldBe false
            afterInpaint.hasRenderedResult shouldBe false
            // The committed-display promotion (ChapterTranslationStore
            // promoteDisplayIfReadyLocked) must not have fired either.
            val unresolved = store.resolveDisplayPage("p1")!!
            unresolved.toPageDisplayProjection().displayReady shouldBe false

            // Now drive the page to the full display shape through the SAME
            // guarded store idiom: translation terminal + render READY + a cleaned
            // image name + non-blank block translation. The cleaned companion
            // file exists on disk first (ChapterArtifactLayout
            // .legacyCompanionImageFile: "Chapter 1_images/<name>") so the
            // promotion's real file validation passes.
            File(mangaDir, "Chapter 1_images").mkdirs()
            File(mangaDir, "Chapter 1_images/cleaned-p1.jpg").writeBytes(byteArrayOf(1))
            val tLease = store.tryAcquirePageStageLease("p1", PageStage.Translation, PageWriteOrigin.BATCH)
                .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
            val mid = store.snapshot("p1")
            store.updatePageGuarded(
                pageKey = "p1",
                expected = ChapterTranslationStore.PatchPrecondition(
                    generation = mid.generation,
                    pageVersion = mid.pageVersion,
                    leaseToken = tLease.token,
                ),
                description = "t934 display promotion test translate+render",
            ) { page ->
                page!!.apply {
                    translationStatus = StageStatus.READY
                    renderStatus = StageStatus.READY
                    cleanedImageName = "cleaned-p1.jpg"
                    blocks.forEach { block -> block.translation = "translated-${block.blockId}" }
                }
            }.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
            store.releasePageStageLease("p1", PageWriteOrigin.BATCH)

            // The promotion gate is untouched: with translation terminal + cleaned
            // image the page IS display-ready (live and committed views agree).
            val live = store.snapshot("p1").page!!
            live.toPageDisplayProjection().displayReady shouldBe true
            live.hasRenderedResult shouldBe true
            val promoted = store.resolveDisplayPage("p1")!!
            promoted.toPageDisplayProjection().displayReady shouldBe true
            promoted.translationStatus shouldBe StageStatus.READY
            promoted.cleanedImageName shouldBe "cleaned-p1.jpg"
        } finally {
            ChapterTranslationStore.artifactImageProbe = productionProbe
        }
    }

    /**
     * Commits a READY translation (plus optional cleaned image name and
     * non-blank block translations) through the SAME guarded lease idiom the
     * translation lane's final commit uses.
     */
    private suspend fun commitTranslation(
        store: ChapterTranslationStore,
        pageKey: String,
        cleanedName: String?,
    ) {
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
            description = "t934 render sweep test translation ready",
        ) { page ->
            page!!.apply {
                translationStatus = StageStatus.READY
                if (cleanedName != null) {
                    cleanedImageName = cleanedName
                }
                blocks.forEach { block -> block.translation = "translated-${block.blockId}" }
            }
        }.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
        store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH)
    }

    @Test
    fun `serial drain stamps order-inverted display-complete pages render-terminal`() = runTest {
        // T934 round 3: the decoupled window inpaints p1/p2 while BOTH
        // translations are still PENDING (the order inversion the relaxed
        // candidacy creates). The inpaint lane's own render stamp is gated
        // on the translation being already terminal, so no stamp lands
        // here. The drain's adoption sweep must stamp exactly the page that
        // later carries the full display evidence (p1: cleaned image +
        // translated block), and skip the one missing it (p2: no cleaned
        // image) — the drain-side twin of the coordinator's E2 stamp.
        val productionProbe = ChapterTranslationStore.artifactImageProbe
        ChapterTranslationStore.artifactImageProbe = CleanedImageProbe { ProbedImage(100, 160) }
        try {
            val store = lazyStore()
            val pageKeys = listOf("p1", "p2")
            store.preRegisterPages(pageKeys)
            pageKeys.forEach { seedOcrOnlyPage(store, it) }
            val identities = ConcurrentHashMap<String, BatchWriteIdentity>()
            val lane = FakeInpaintLane(store, identities)
            val scheduler = scheduler(store, lane, pageKeys, identities)

            // The window inpaints both pages while their translations are
            // PENDING — the order inversion.
            val loopJob = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { scheduler.runOverlapLoop() }
            scheduler.onRemoteWindowOpened()
            testScheduler.advanceUntilIdle()
            scheduler.onRemoteWindowClosed()
            scheduler.stopOverlap()
            loopJob.cancel()

            lane.inpainted shouldBe pageKeys
            pageKeys.forEach { key ->
                val page = store.snapshot(key).page!!
                page.inpaintStatus shouldBe StageStatus.READY
                page.translationStatus shouldBe StageStatus.PENDING
                page.renderStatus shouldBe StageStatus.PENDING
            }

            // The translations commit later (next window of the pass, or a
            // restart). p1 gets the full display shape; p2 has no cleaned
            // image persisted.
            File(mangaDir, "Chapter 1_images").mkdirs()
            File(mangaDir, "Chapter 1_images/cleaned-p1.jpg").writeBytes(byteArrayOf(1))
            commitTranslation(store, "p1", cleanedName = "cleaned-p1.jpg")
            commitTranslation(store, "p2", cleanedName = null)

            // The drain finds no inpaint work (both artifacts durable) but
            // settles the inverted page's render terminality.
            scheduler.drainSerial()

            val stamped = store.snapshot("p1").page!!
            stamped.renderStatus shouldBe StageStatus.READY
            stamped.translationStatus shouldBe StageStatus.READY
            stamped.inpaintStatus shouldBe StageStatus.READY
            // The stamp fires the committed-display promotion (the reader gate).
            store.resolveDisplayPage("p1")!!.toPageDisplayProjection().displayReady shouldBe true
            // Missing display evidence is NOT stamped.
            store.snapshot("p2").page!!.renderStatus shouldBe StageStatus.PENDING
            // TX-06 teardown discipline: the sweep's Render lease is released.
            store.snapshot("p1").leaseToken shouldBe null
            store.snapshot("p2").leaseToken shouldBe null
        } finally {
            ChapterTranslationStore.artifactImageProbe = productionProbe
        }
    }
}
