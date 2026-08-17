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
 * B1 regression coverage for cancellation in the per-page OCR/inpaint loop.
 */
class BatchCoordinatorCancellationTest {

    @Test
    fun `cancel before any page completes OCR launches zero inpaint jobs`() = runTest {
        val events = RecordingListener()
        val native = GatedNativeWorker()
        val translator = ImmediateTranslatorWorker()
        val render = NoopRenderJoin()
        val coord = BatchCoordinator(native, translator, render, events)

        val pages = listOf("p0" to 0, "p1" to 1, "p2" to 2)
        val pass1 = async { coord.runPass1(pages, TranslatorComputeClass.REMOTE_IO) }

        // Let p0 enter OCR, but cancel before OCR can complete and reach inpaint.
        native.ocrPublished("p0").await()
        pass1.cancelAndJoin()

        events.inpaintStartedCount.get() shouldBe 0
        events.barrierReleased.get() shouldBe false
    }

    @Test
    fun `cancel during page two leaves page one fully complete`() = runTest {
        val events = RecordingListener()
        val native = GatedNativeWorker()
        val translator = ImmediateTranslatorWorker()
        val render = NoopRenderJoin()
        val coord = BatchCoordinator(native, translator, render, events)

        val pages = listOf("p0" to 0, "p1" to 1, "p2" to 2)
        val pass1 = async { coord.runPass1(pages, TranslatorComputeClass.REMOTE_IO) }

        // Complete page one through inpaint, then cancel while page two is in OCR.
        native.ocrPublished("p0").await()
        native.allowOcrToFinish("p0")
        events.awaitEvent("inpaintStarted:p0")
        native.allowInpaintToFinish("p0")
        native.ocrPublished("p1").await()
        pass1.cancelAndJoin()

        events.inpaintStartedCount.get() shouldBe 1
        events.log.contains("inpaintStarted:p1") shouldBe false
        events.log.contains("inpaintStarted:p2") shouldBe false
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

        fun allowInpaintToFinish(pageKey: String) {
            inpaintFinishGates.getOrPut(pageKey) { CompletableDeferred() }.complete(Unit)
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
