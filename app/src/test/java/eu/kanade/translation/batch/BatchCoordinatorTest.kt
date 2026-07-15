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
 * Guards [BatchCoordinator] — the extracted, testable batch schedule
 * (Checkpoint 2 §1, §2, §5). Uses fakes + a recording listener so the lane
 * serialization, overlap, channel backpressure, render join, and Pass-1 barrier
 * can be asserted deterministically without OCR/inpaint/network.
 */
class BatchCoordinatorTest {

    @Test
    fun `remote translation starts after OCR and before same-page inpaint completes`() = runTest {
        val events = RecordingListener()
        val native = GatedNativeWorker()
        val translator = RecordingTranslatorWorker()
        val render = NoopRenderJoin()
        val coord = BatchCoordinator(native, translator, render, events)

        // Drive a single page. Control points:
        //  - native.runOcr publishes OCR (the work item)
        //  - coordinator offers to channel -> translator.translate
        //  - native.runInpaint runs AFTER the offer
        val pages = listOf("p0" to 0)
        val done = async { coord.runPass1(pages, TranslatorComputeClass.REMOTE_IO) }

        // Wait until OCR is published, then assert translation starts before
        // inpaint finishes. The native worker holds inpaint open until released.
        native.ocrPublished("p0").await()
        // Translation request should be observable before inpaint completes.
        native.allowInpaintToFinish("p0")
        done.await()

        val log = events.log
        val ocrIdx = log.indexOf("ocrPublished:p0")
        val translateIdx = log.indexOf("translationRequested:p0")
        val inpaintFinishIdx = log.indexOf("inpaintFinished:p0")
        (ocrIdx shouldNotBe -1)
        translateIdx shouldNotBe -1
        inpaintFinishIdx shouldNotBe -1
        // OCR is published before the translation request is offered.
        (translateIdx > ocrIdx) shouldBe true
        // Translation starts before the same page's inpaint finishes (overlap).
        (translateIdx < inpaintFinishIdx) shouldBe true
    }

    @Test
    fun `native OCR and inpaint never overlap across pages`() = runTest {
        val events = RecordingListener()
        val overlap = AtomicInteger(0)
        val maxOverlap = AtomicInteger(0)
        val native = object : GatedNativeWorker() {
            override suspend fun runInpaint(handle: OcrResultHandle) {
                val cur = overlap.incrementAndGet()
                maxOverlap.updateAndGet { maxOf(it, cur) }
                super.runInpaint(handle)
                overlap.decrementAndGet()
            }
        }
        val translator = NoopTranslatorWorker()
        val render = NoopRenderJoin()
        val coord = BatchCoordinator(native, translator, render, events)

        val pages = listOf("p0" to 0, "p1" to 1, "p2" to 2)
        val done = async {
            coord.runPass1(pages, TranslatorComputeClass.REMOTE_IO)
        }
        // Release all pages so they flow through; concurrency is measured.
        pages.forEach { (_, _) -> }
        native.releaseAll(3)
        done.await()

        // Native lane is serialized: inpaint overlap must never exceed 1.
        maxOverlap.get() shouldBe 1
    }

    @Test
    fun `ML Kit translation does not overlap native inpaint`() = runTest {
        val events = RecordingListener()
        val nativeInflight = AtomicInteger(0)
        val maxNative = AtomicInteger(0)
        val translateInflight = AtomicInteger(0)
        val maxTranslate = AtomicInteger(0)
        val overlap = AtomicInteger(0)
        val maxOverlap = AtomicInteger(0)

        val native = object : NativeLaneWorker {
            override suspend fun runOcr(pageKey: String, pageIndex: Int): OcrResultHandle? {
                val h = SimpleHandle(TranslationWorkItem(pageKey, pageIndex, PageTranslation(), true))
                return h
            }
            override suspend fun runInpaint(handle: OcrResultHandle) {
                val cur = nativeInflight.incrementAndGet()
                maxNative.updateAndGet { maxOf(it, cur) }
                // Detect overlap with translation.
                if (translateInflight.get() > 0) {
                    val o = overlap.incrementAndGet()
                    maxOverlap.updateAndGet { maxOf(it, o) }
                }
                yield()
                nativeInflight.decrementAndGet()
            }
            override fun releaseNativeResources(pageKey: String) {}
        }
        val translator = object : TranslatorLaneWorker {
            override suspend fun translate(item: TranslationWorkItem) {
                val cur = translateInflight.incrementAndGet()
                maxTranslate.updateAndGet { maxOf(it, cur) }
                if (nativeInflight.get() > 0) {
                    val o = overlap.incrementAndGet()
                    maxOverlap.updateAndGet { maxOf(it, o) }
                }
                yield()
                translateInflight.decrementAndGet()
            }
        }
        val coord = BatchCoordinator(native, translator, NoopRenderJoin(), events)

        val pages = listOf("p0" to 0, "p1" to 1, "p2" to 2)
        coord.runPass1(pages, TranslatorComputeClass.LOCAL_COMPUTE)

        // LOCAL_COMPUTE serializes translation with native: no overlap.
        maxOverlap.get() shouldBe 0
    }

    @Test
    fun `full bounded channel does not suspend while bitmap and native permit retained`() = runTest {
        val events = RecordingListener()
        // Channel capacity 1 so a second page fills it immediately and forces the
        // backpressure path. The native worker stalls inpaint so we can observe
        // that the send only happens AFTER inpaint + release.
        val native = StallingNativeWorker()
        val translator = StallingTranslatorWorker()
        val render = NoopRenderJoin()
        val coord = BatchCoordinator(native, translator, render, events, channelCapacity = 1)

        val pages = listOf("p0" to 0, "p1" to 1, "p2" to 2)
        val done = async { coord.runPass1(pages, TranslatorComputeClass.REMOTE_IO) }

        // Let p0 + p1 flow to fill the channel (capacity 1). p2's offer must
        // block; before blocking it should have released native resources.
        // Advance scheduling.
        repeat(10) { yield() }
        // The backpressure event must precede any suspending send for p2; and
        // inpaintFinished must come before the channel send resumes.
        native.releaseAll(3)
        translator.releaseAll(3)
        done.await()

        // When backpressure fired, inpaintFinished preceded it for that page
        // (resources released before suspending send). The core invariant: a
        // full channel never suspends while a bitmap + native permit is retained.
        val backpressure = events.log.filter { it.startsWith("channelSendSuspendedBeforeBitmapRelease") }
        backpressure.forEach { ev ->
            val page = ev.substringAfter(":")
            val inpaintIdx = events.log.indexOf("inpaintFinished:$page")
            val bpIdx = events.log.indexOf(ev)
            (inpaintIdx >= 0) shouldBe true
            (bpIdx > inpaintIdx) shouldBe true
        }
    }

    @Test
    fun `render waits for both translation and native branches`() = runTest {
        val events = RecordingListener()
        val nativeBranchDone = ConcurrentHashMap<String, Boolean>()
        val translationBranchDone = ConcurrentHashMap<String, Boolean>()
        val native = object : GatedNativeWorker() {}
        val translator = RecordingTranslatorWorker()
        val render = object : NoopRenderJoin() {
            override fun onNativeBranchDone(pageKey: String) {
                nativeBranchDone[pageKey] = true
            }
            override fun onTranslationBranchDone(pageKey: String) {
                translationBranchDone[pageKey] = true
            }
            override suspend fun awaitAndRender(pageKey: String) {
                // Render must see BOTH branches marked done before it runs.
                (nativeBranchDone[pageKey] == true) shouldBe true
                (translationBranchDone[pageKey] == true) shouldBe true
                super.awaitAndRender(pageKey)
            }
        }
        val coord = BatchCoordinator(native, translator, render, events)

        val pages = listOf("p0" to 0)
        val done = async { coord.runPass1(pages, TranslatorComputeClass.REMOTE_IO) }
        native.releaseAll(1)
        done.await()

        val log = events.log
        val renderIdx = log.indexOf("renderStarted:p0")
        val translationIdx = log.indexOf("translationFinished:p0")
        val inpaintIdx = log.indexOf("inpaintFinished:p0")
        (translationIdx shouldNotBe -1)
        (renderIdx shouldNotBe -1)
        (renderIdx > translationIdx) shouldBe true
        (renderIdx > inpaintIdx) shouldBe true
    }

    @Test
    fun `Pass 2 starts only after the Pass-1 barrier`() = runTest {
        val events = RecordingListener()
        val native = GatedNativeWorker()
        val translator = RecordingTranslatorWorker()
        val coord = BatchCoordinator(native, translator, NoopRenderJoin(), events)

        val pages = listOf("p0" to 0, "p1" to 1)
        val pass1 = async { coord.runPass1(pages, TranslatorComputeClass.REMOTE_IO) }
        native.releaseAll(2)
        pass1.await()

        val pass2 = async {
            coord.runPass2(listOf("p0" to PageTranslation())) { /* no-op */ }
        }
        pass2.await()

        val barrierIdx = events.log.indexOf("pass1BarrierReleased")
        val pass2Idx = events.log.indexOf("pass2Started")
        (barrierIdx shouldNotBe -1)
        (pass2Idx shouldNotBe -1)
        (pass2Idx > barrierIdx) shouldBe true
    }

    @Test
    fun `final completion waits for all branches`() = runTest {
        val events = RecordingListener()
        val native = GatedNativeWorker()
        val translator = RecordingTranslatorWorker()
        val render = TrackingRenderJoin()
        val coord = BatchCoordinator(native, translator, render, events)

        val pages = listOf("p0" to 0, "p1" to 1, "p2" to 2)
        val done = async { coord.runPass1(pages, TranslatorComputeClass.REMOTE_IO) }
        native.releaseAll(3)
        done.await() // returns only after render join for every page completed

        render.rendered shouldContainExactly setOf("p0", "p1", "p2")
    }

    // ---- helpers ----
}

/** Records a flat timestamped event log for ordering assertions. */
private open class RecordingListener : BatchScheduleListener() {
    private val _log = mutableListOf<String>()
    val log: List<String> get() = _log.toList()

    private fun add(entry: String) {
        synchronized(_log) { _log += entry }
    }
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

/** Simple handle wrapping a work item. */
private class SimpleHandle(override val item: TranslationWorkItem) : OcrResultHandle

/**
 * Native worker whose OCR publishes immediately and whose inpaint blocks until
 * [release]d, so tests can observe the translation-before-inpaint overlap.
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
        val pageKey = handle.item.pageKey
        inpaintGates.getOrPut(pageKey) { CompletableDeferred() }.await()
    }

    override fun releaseNativeResources(pageKey: String) {}

    fun ocrPublished(pageKey: String): Deferred<Unit> =
        ocrSignals.getOrPut(pageKey) { CompletableDeferred() }

    fun allowInpaintToFinish(pageKey: String) {
        inpaintGates.getOrPut(pageKey) { CompletableDeferred() }.complete(Unit)
    }

    fun releaseAll(count: Int) {
        repeat(count) { idx ->
            val pageKey = "p$idx"
            inpaintGates.getOrPut(pageKey) { CompletableDeferred() }.complete(Unit)
        }
    }
}

private class RecordingTranslatorWorker : TranslatorLaneWorker {
    override suspend fun translate(item: TranslationWorkItem) {
        // Fast path: no-op so the schedule is the only thing under test.
    }
}

private class NoopTranslatorWorker : TranslatorLaneWorker {
    override suspend fun translate(item: TranslationWorkItem) {}
}

private open class NoopRenderJoin : RenderJoinWorker {
    override fun onNativeBranchDone(pageKey: String) {}
    override fun onTranslationBranchDone(pageKey: String) {}
    override suspend fun awaitAndRender(pageKey: String) {}
}

private class TrackingRenderJoin : RenderJoinWorker {
    private val _rendered = mutableSetOf<String>()
    val rendered: Set<String> get() = _rendered.toSet()
    override fun onNativeBranchDone(pageKey: String) {}
    override fun onTranslationBranchDone(pageKey: String) {}
    override suspend fun awaitAndRender(pageKey: String) {
        _rendered += pageKey
    }
}

/** Stalls both inpaint and translation so backpressure is observable. */
private class StallingNativeWorker : NativeLaneWorker {
    private val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    override suspend fun runOcr(pageKey: String, pageIndex: Int): OcrResultHandle? {
        return SimpleHandle(TranslationWorkItem(pageKey, pageIndex, PageTranslation(), false))
    }
    override suspend fun runInpaint(handle: OcrResultHandle) {
        gates.getOrPut(handle.item.pageKey) { CompletableDeferred() }.await()
    }
    override fun releaseNativeResources(pageKey: String) {}
    fun releaseAll(count: Int) {
        repeat(count) { idx ->
            gates.getOrPut("p$idx") { CompletableDeferred() }.complete(Unit)
        }
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
