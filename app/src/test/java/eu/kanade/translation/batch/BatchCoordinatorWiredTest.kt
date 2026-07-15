package eu.kanade.translation.batch

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.translator.TranslatorComputeClass
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * TachiyomiAT: integration-flavored tests proving the [BatchCoordinator] schedule
 * — the schedule now wired into [eu.kanade.translation.TranslationPipeline.translateBatch]
 * via the NativeLaneWorker / TranslatorLaneWorker / RenderJoinWorker adapters —
 * enforces the Checkpoint 2 §1/§2/§5 invariants for the *wired* translator-worker
 * pattern (a per-item worker that may defer completion, plus a tryRender-style join).
 *
 * These complement [BatchCoordinatorTest] by exercising the same schedule through a
 * translator worker that mimics the pipeline's chunked-AI lane (accept-and-maybe-
 * defer) and a render join that mirrors tryRender (idempotent), so the wired adapter
 * pattern is proven not to deadlock and to preserve serialization/overlap.
 */
class BatchCoordinatorWiredTest {

    /**
     * (a) + (e) For a REMOTE_IO translator whose lane defers completion (mimicking the
     * AI chunk planner that only completes a page when its chunk flushes), the
     * translation request still starts after OCR and before same-page inpaint
     * completes, and Pass 2 (runPass2) only starts after the Pass-1 barrier.
     */
    @Test
    fun `deferred translator lane still overlaps inpaint and gates pass2 on barrier`() = runTest {
        val events = RecordingListener()
        val native = GatedNativeWorker()
        val translator = DeferringTranslatorWorker()
        val render = IdempotentRenderJoin()
        val coord = BatchCoordinator(native, translator, render, events)

        val pages = listOf("p0" to 0, "p1" to 1)
        val pass1 = async { coord.runPass1(pages, TranslatorComputeClass.REMOTE_IO) }

        // Wait for p0 OCR to publish, then let the translator lane observe it BEFORE
        // releasing inpaint — this is what makes the translation-before-inpaint overlap
        // observable under virtual time (mirrors BatchCoordinatorTest's gating).
        native.ocrPublished("p0").await()
        // Yield so the translator lane picks up p0 and fires translationRequested.
        repeat(3) { yield() }
        native.allowInpaintToFinish("p0")
        native.allowInpaintToFinish("p1")
        pass1.await()

        // The translator lane accepted every page (overlap with inpaint was available);
        // render fired for every page through the join.
        render.rendered shouldContainExactly setOf("p0", "p1")
        translator.accepted shouldContainExactly setOf("p0", "p1")

        val log = events.log
        val ocrIdx = log.indexOf("ocrPublished:p0")
        val translateIdx = log.indexOf("translationRequested:p0")
        val inpaintFinishIdx = log.indexOf("inpaintFinished:p0")
        (ocrIdx shouldNotBe -1)
        translateIdx shouldNotBe -1
        inpaintFinishIdx shouldNotBe -1
        // (a) translation request follows OCR and precedes inpaint completion.
        (translateIdx > ocrIdx) shouldBe true
        (translateIdx < inpaintFinishIdx) shouldBe true

        // (e) Pass 2 only starts after the Pass-1 barrier.
        val pass2 = async { coord.runPass2(listOf("p0" to PageTranslation())) { } }
        pass2.await()
        val barrierIdx = events.log.indexOf("pass1BarrierReleased")
        val pass2Idx = events.log.indexOf("pass2Started")
        (barrierIdx shouldNotBe -1)
        pass2Idx shouldNotBe -1
        (pass2Idx > barrierIdx) shouldBe true
    }

    /**
     * (d) When the bounded channel is full, the native lane must NOT suspend at the
     * `trySend` point while still holding the bitmap + native permit. Instead it logs
     * the backpressure decision ([channelSendSuspendedBeforeBitmapRelease]), finishes
     * same-page inpaint, releases native resources, and only then performs the
     * suspending [Channel.send]. The decision event therefore precedes
     * `inpaintFinished` for that page (the coordinator defers the send rather than
     * suspending in-place), `releaseNativeResources` runs for the backpressured page,
     * and the batch still completes once the channel drains.
     *
     * To force backpressure under virtual time, the native inpaint gates are released
     * FIRST (so the serialized native lane advances p0 -> p1) while the translator
     * lane stays stalled (so p0 remains unconsumed in the capacity-1 channel when p1
     * offers). Only after the backpressure decision is observed is the translator lane
     * released, letting the deferred sends drain.
     */
    @Test
    fun `full bounded channel releases native resources before suspending send`() = runTest {
        val events = RecordingListener()
        val releasedBeforeSend = ConcurrentHashMap.newKeySet<String>()
        val native = object : StallingNativeWorker() {
            override fun releaseNativeResources(pageKey: String) {
                // Record that release happened; the coordinator must call this on the
                // backpressure path before the suspending send.
                releasedBeforeSend += pageKey
            }
        }
        // The translator lane stalls so items accumulate in the bounded channel and
        // force the backpressure path for the last page.
        val translator = StallingTranslatorWorker()
        val coord = BatchCoordinator(native, translator, IdempotentRenderJoin(), events, channelCapacity = 1)

        val pages = listOf("p0" to 0, "p1" to 1, "p2" to 2)
        val done = async { coord.runPass1(pages, TranslatorComputeClass.REMOTE_IO) }
        // Let p0 flow: OCR -> trySend(p0) fills the channel -> doInpaint(p0) stalls on
        // its gate, and the translator lane receives p0 then stalls on its gate.
        native.ocrPublished("p0").await()
        repeat(5) { yield() }

        // Release native inpaint gates ONLY. p0 finishes inpaint and releases the
        // native permit; p1 then acquires the permit, runs OCR, and trySend(p1) finds
        // the channel still full (p0 unconsumed, translator stalled) -> backpressure.
        native.releaseAll(3)
        repeat(10) { yield() }

        val backpressure = events.log.filter { it.startsWith("channelSendSuspendedBeforeBitmapRelease") }
        // The backpressure decision fired for p1 while the translator lane was stalled.
        (backpressure.isNotEmpty()) shouldBe true

        // Now release the translator lane so the deferred sends drain and pass1 completes.
        translator.releaseAll(3)
        done.await()

        backpressure.forEach { ev ->
            val page = ev.substringAfter(":")
            val bpIdx = events.log.indexOf(ev)
            val inpaintIdx = events.log.indexOf("inpaintFinished:$page")
            (inpaintIdx shouldNotBe -1)
            // The backpressure DECISION precedes inpaintFinished: the coordinator
            // detected the full channel and chose to finish inpaint + release rather
            // than suspending in-place at trySend while holding the bitmap + permit.
            (bpIdx < inpaintIdx) shouldBe true
            // releaseNativeResources ran for the backpressured page before the send.
            (page in releasedBeforeSend) shouldBe true
        }
    }

    /**
     * (b) Native OCR + inpaint never overlap across pages regardless of translator
     * lane behavior: the serialized native lane permit caps concurrent inpaint at 1.
     */
    @Test
    fun `native inpaint overlap never exceeds one across pages`() = runTest {
        val events = RecordingListener()
        val inflight = AtomicInteger(0)
        val maxOverlap = AtomicInteger(0)
        val native = object : GatedNativeWorker() {
            override suspend fun runInpaint(handle: OcrResultHandle) {
                val cur = inflight.incrementAndGet()
                maxOverlap.updateAndGet { maxOf(it, cur) }
                super.runInpaint(handle)
                inflight.decrementAndGet()
            }
        }
        val coord = BatchCoordinator(native, DeferringTranslatorWorker(), IdempotentRenderJoin(), events)
        val pages = listOf("p0" to 0, "p1" to 1, "p2" to 2, "p3" to 3)
        val done = async { coord.runPass1(pages, TranslatorComputeClass.REMOTE_IO) }
        native.releaseAll(4)
        done.await()

        maxOverlap.get() shouldBe 1
    }

    /**
     * (c) For LOCAL_COMPUTE (ML Kit), translation runs inline on the native lane, so it
     * never overlaps native inpaint. The translator lane is NOT used for LOCAL_COMPUTE.
     */
    @Test
    fun `local compute translation never overlaps native inpaint`() = runTest {
        val events = RecordingListener()
        val nativeInflight = AtomicInteger(0)
        val translateInflight = AtomicInteger(0)
        val maxOverlap = AtomicInteger(0)
        val native = object : NativeLaneWorker {
            override suspend fun runOcr(pageKey: String, pageIndex: Int): OcrResultHandle? {
                return SimpleHandle(TranslationWorkItem(pageKey, pageIndex, PageTranslation(), false))
            }
            override suspend fun runInpaint(handle: OcrResultHandle) {
                val cur = nativeInflight.incrementAndGet()
                if (translateInflight.get() > 0) maxOverlap.incrementAndGet()
                yield()
                nativeInflight.decrementAndGet()
            }
            override fun releaseNativeResources(pageKey: String) {}
        }
        val translator = object : TranslatorLaneWorker {
            override suspend fun translate(item: TranslationWorkItem) {
                val cur = translateInflight.incrementAndGet()
                if (nativeInflight.get() > 0) maxOverlap.incrementAndGet()
                yield()
                translateInflight.decrementAndGet()
            }
        }
        val coord = BatchCoordinator(native, translator, IdempotentRenderJoin(), events)
        coord.runPass1(listOf("p0" to 0, "p1" to 1), TranslatorComputeClass.LOCAL_COMPUTE)

        // LOCAL_COMPUTE serializes translation with native: zero observed overlap.
        maxOverlap.get() shouldBe 0
    }

    // ---- helpers ----

    /** Records a flat timestamped event log (mirrors BatchCoordinatorTest's helper). */
    private open class RecordingListener : BatchScheduleListener() {
        private val _log = mutableListOf<String>()
        val log: List<String> get() = _log.toList()
        private fun add(entry: String) = synchronized(_log) { _log += entry }
        override fun nativeLaneEntered(pageKey: String) = add("nativeLaneEntered:$pageKey")
        override fun nativeLaneLeft(pageKey: String) = add("nativeLaneLeft:$pageKey")
        override fun ocrPublished(pageKey: String) = add("ocrPublished:$pageKey")
        override fun inpaintStarted(pageKey: String) = add("inpaintStarted:$pageKey")
        override fun inpaintFinished(pageKey: String) = add("inpaintFinished:$pageKey")
        override fun translationRequested(pageKey: String) = add("translationRequested:$pageKey")
        override fun translationFinished(pageKey: String) = add("translationFinished:$pageKey")
        override fun renderStarted(pageKey: String) = add("renderStarted:$pageKey")
        override fun renderFinished(pageKey: String) = add("renderFinished:$pageKey")
        override fun pass1BarrierReleased() = add("pass1BarrierReleased")
        override fun pass2Started() = add("pass2Started")
        override fun channelSendSuspendedBeforeBitmapRelease(pageKey: String) =
            add("channelSendSuspendedBeforeBitmapRelease:$pageKey")
    }

    private class SimpleHandle(override val item: TranslationWorkItem) : OcrResultHandle

    /**
     * Native worker whose OCR publishes immediately and whose inpaint blocks until
     * released, so the translation-before-inpaint overlap is observable. Mirrors the
     * [BatchCoordinatorTest] helper: per-page OCR signal + per-page inpaint gate so a
     * test can observe `ocrPublished` then yield to let the translator lane fire its
     * request BEFORE releasing inpaint (the overlap window).
     */
    private open class GatedNativeWorker : NativeLaneWorker {
        private val inpaintGates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val ocrSignals = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        override suspend fun runOcr(pageKey: String, pageIndex: Int): OcrResultHandle? {
            val item = TranslationWorkItem(pageKey, pageIndex, PageTranslation(), skipInpaint = false)
            ocrSignals.getOrPut(pageKey) { CompletableDeferred() }.complete(Unit)
            return SimpleHandle(item)
        }
        override suspend fun runInpaint(handle: OcrResultHandle) {
            inpaintGates.getOrPut(handle.item.pageKey) { CompletableDeferred() }.await()
        }
        override fun releaseNativeResources(pageKey: String) {}
        fun ocrPublished(pageKey: String): Deferred<Unit> =
            ocrSignals.getOrPut(pageKey) { CompletableDeferred() }
        fun allowInpaintToFinish(pageKey: String) {
            inpaintGates.getOrPut(pageKey) { CompletableDeferred() }.complete(Unit)
        }
        fun releaseAll(count: Int) {
            repeat(count) { idx ->
                inpaintGates.getOrPut("p$idx") { CompletableDeferred() }.complete(Unit)
            }
        }
    }

    /** Native worker that stalls inpaint (for backpressure tests). */
    private open class StallingNativeWorker : NativeLaneWorker {
        private val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val ocrSignals = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        override suspend fun runOcr(pageKey: String, pageIndex: Int): OcrResultHandle? {
            ocrSignals.getOrPut(pageKey) { CompletableDeferred() }.complete(Unit)
            return SimpleHandle(TranslationWorkItem(pageKey, pageIndex, PageTranslation(), false))
        }
        override suspend fun runInpaint(handle: OcrResultHandle) {
            gates.getOrPut(handle.item.pageKey) { CompletableDeferred() }.await()
        }
        override fun releaseNativeResources(pageKey: String) {}
        fun ocrPublished(pageKey: String): Deferred<Unit> =
            ocrSignals.getOrPut(pageKey) { CompletableDeferred() }
        fun releaseAll(count: Int) {
            repeat(count) { idx ->
                gates.getOrPut("p$idx") { CompletableDeferred() }.complete(Unit)
            }
        }
    }

    /** Translator worker that mimics the pipeline's AI lane: accept-and-maybe-defer. */
    private class DeferringTranslatorWorker : TranslatorLaneWorker {
        val accepted = ConcurrentHashMap.newKeySet<String>()
        override suspend fun translate(item: TranslationWorkItem) {
            accepted += item.pageKey
            // Accept without blocking on terminal completion (mirrors the planner path
            // where a page completes when its chunk flushes, possibly via a later page).
        }
    }

    private class StallingTranslatorWorker : TranslatorLaneWorker {
        private val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        override suspend fun translate(item: TranslationWorkItem) {
            gates.getOrPut(item.pageKey) { CompletableDeferred() }.await()
        }
        fun releaseAll(count: Int) {
            repeat(count) { idx ->
                gates.getOrPut("p$idx") { CompletableDeferred() }.complete(Unit)
            }
        }
    }

    /** Render join mirroring tryRender: idempotent, records each rendered page once. */
    private class IdempotentRenderJoin : RenderJoinWorker {
        val rendered = ConcurrentHashMap.newKeySet<String>()
        override fun onNativeBranchDone(pageKey: String) {}
        override fun onTranslationBranchDone(pageKey: String) {}
        override suspend fun awaitAndRender(pageKey: String) {
            rendered += pageKey
        }
    }
}
