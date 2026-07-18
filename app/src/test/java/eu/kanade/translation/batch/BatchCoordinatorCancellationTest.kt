package eu.kanade.translation.batch

import eu.kanade.translation.translator.TranslatorComputeClass
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * B1 regression coverage: cancelling a batch mid-OCR (before the all-OCR
 * barrier opens) must not allow any inpaint job to start. The barrier lives in
 * `BatchCoordinator.runPass1` after `ocrJobs.awaitAll()`; if cancellation lands
 * before that point the inpaint loop is never reached.
 *
 * Also verifies the inverse: once the barrier has opened and inpaint has begun,
 * cancelling the coordinator stops launching further inpaints but lets the
 * currently running one observe its cancellation.
 */
class BatchCoordinatorCancellationTest {

    @Test
    fun `cancel before all-OCR barrier launches zero inpaint jobs`() = runTest {
        val events = RecordingListener()
        val native = GatedNativeWorker()
        val translator = ImmediateTranslatorWorker()
        val render = NoopRenderJoin()
        val coord = BatchCoordinator(native, translator, render, events)

        val pages = listOf("p0" to 0, "p1" to 1, "p2" to 2)
        val pass1 = async { coord.runPass1(pages, TranslatorComputeClass.REMOTE_IO) }

        // Let only p0 OCR publish; p1 and p2 are still gated, so awaitAll() cannot
        // complete and the barrier cannot open.
        native.ocrPublished("p0").await()
        native.allowOcrToFinish("p0")
        // Yield so the OCR job for p0 lands; p1/p2 are blocked in runOcrStage.
        repeat(3) { yield() }

        pass1.cancelAndJoin()

        events.inpaintStartedCount.get() shouldBe 0
        events.barrierReleased.get() shouldBe false
    }

    @Test
    fun `cancel after barrier stops launching further inpaint jobs`() = runTest {
        val events = RecordingListener()
        val native = GatedNativeWorker()
        val translator = ImmediateTranslatorWorker()
        val render = NoopRenderJoin()
        val coord = BatchCoordinator(native, translator, render, events)

        val pages = listOf("p0" to 0, "p1" to 1, "p2" to 2)
        val pass1 = async { coord.runPass1(pages, TranslatorComputeClass.REMOTE_IO) }

        // Open the barrier by releasing every OCR gate.
        native.releaseOcr(3)
        events.awaitEvent("allOcrBarrierReleased")

        // Let p0 inpaint start, then cancel before p1/p2 inpaint begin.
        events.awaitEvent("inpaintStarted:p0")
        pass1.cancelAndJoin()

        // p0 launched; p1 and p2 may or may not have started depending on
        // scheduling, but the count must be strictly less than the page count
        // (i.e. cancellation prevented the full sweep).
        val started = events.inpaintStartedCount.get()
        (started < pages.size) shouldBe true
    }

    private class RecordingListener : BatchScheduleListener() {
        val log = mutableListOf<String>()
        val waiters = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        val inpaintStartedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val barrierReleased = AtomicBoolean(false)

        @Synchronized
        private fun add(entry: String) {
            log.add(entry)
            waiters[entry]?.complete(Unit)
        }

        suspend fun awaitEvent(event: String) {
            val deferred = synchronized(this) {
                if (log.contains(event)) return
                waiters.getOrPut(event) { CompletableDeferred() }
            }
            deferred.await()
        }

        override fun inpaintStarted(pageKey: String) {
            inpaintStartedCount.incrementAndGet()
            add("inpaintStarted:$pageKey")
        }
        override fun allOcrBarrierReleased() {
            barrierReleased.set(true)
            add("allOcrBarrierReleased")
        }
    }

    private open class GatedNativeWorker : NativeLaneWorker {
        private val ocrPublishedSignals = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val ocrFinishGates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val inpaintFinishGates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? {
            ocrPublishedSignals.getOrPut(pageKey) { CompletableDeferred() }.complete(Unit)
            ocrFinishGates.getOrPut(pageKey) { CompletableDeferred() }.await()
            return OcrReadyPageRef(pageKey, pageIndex, 0L, emptyList())
        }

        override suspend fun runInpaintStage(pageKey: String) {
            inpaintFinishGates.getOrPut(pageKey) { CompletableDeferred() }.await()
        }

        fun ocrPublished(pageKey: String) =
            ocrPublishedSignals.getOrPut(pageKey) { CompletableDeferred() }

        fun allowOcrToFinish(pageKey: String) {
            ocrFinishGates.getOrPut(pageKey) { CompletableDeferred() }.complete(Unit)
        }

        fun releaseOcr(count: Int) {
            repeat(count) { idx ->
                val key = "p$idx"
                // Ensure the publish signal exists (it would be created inside runOcrStage),
                // then release the OCR finish gate so runOcrStage returns.
                ocrPublishedSignals.getOrPut(key) { CompletableDeferred() }.complete(Unit)
                ocrFinishGates.getOrPut(key) { CompletableDeferred() }.complete(Unit)
            }
        }
    }

    private class ImmediateTranslatorWorker : TranslatorLaneWorker {
        override suspend fun translate(ref: OcrReadyPageRef) {
            // Yield once so the translator lane is observably scheduled but does
            // not block the inpaint loop on a per-page gate.
            yield()
        }
    }

    private class NoopRenderJoin : RenderJoinWorker {
        override fun onNativeBranchDone(pageKey: String) {}
        override fun onTranslationBranchDone(pageKey: String) {}
        override suspend fun awaitAndRender(pageKey: String) {}
    }
}
