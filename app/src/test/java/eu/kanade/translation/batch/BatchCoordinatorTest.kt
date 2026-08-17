package eu.kanade.translation.batch

import eu.kanade.translation.translator.TranslatorComputeClass
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap

class BatchCoordinatorTest {

    @Test
    fun `remote translation overlaps inpaint and follows OCR`() = runTest {
        val events = RecordingListener()
        val native = GatedNativeWorker()
        val translator = RecordingTranslatorWorker()
        val render = NoopRenderJoin()
        val coord = BatchCoordinator(native, translator, render, events)

        val pages = listOf("p0" to 0)
        val done = async { coord.runPass1(pages, TranslatorComputeClass.REMOTE_IO) }

        // Wait until OCR is published
        native.ocrPublished("p0").await()
        native.allowOcrToFinish("p0")

        // Inpaint starts immediately after this page's OCR, before any chapter barrier.
        events.awaitEvent("inpaintStarted:p0")

        // Let translator start while inpaint is still gated.
        translator.allowTranslationToStart("p0")
        native.allowInpaintToFinish("p0")
        done.await()

        val log = events.log
        val ocrFinishIdx = log.indexOf("ocrFinished:p0")
        val translateIdx = log.indexOf("translationRequested:p0")
        val inpaintStartIdx = log.indexOf("inpaintStarted:p0")

        (ocrFinishIdx < translateIdx) shouldBe true
        (ocrFinishIdx < inpaintStartIdx) shouldBe true
    }

    @Test
    fun `each page inpaints before the next page OCR starts`() = runTest {
        val events = RecordingListener()
        val native = GatedNativeWorker()
        val translator = RecordingTranslatorWorker()
        val render = NoopRenderJoin()
        val coord = BatchCoordinator(native, translator, render, events)

        val pages = listOf("p0" to 0, "p1" to 1)
        val done = async { coord.runPass1(pages, TranslatorComputeClass.REMOTE_IO) }

        native.ocrPublished("p0").await()
        native.allowOcrToFinish("p0")
        events.awaitEvent("inpaintStarted:p0")
        events.log.contains("ocrStarted:p1") shouldBe false
        native.allowInpaintToFinish("p0")

        native.ocrPublished("p1").await()
        native.allowOcrToFinish("p1")
        events.awaitEvent("inpaintStarted:p1")
        native.allowInpaintToFinish("p1")

        translator.allowTranslationToStart("p0")
        translator.allowTranslationToStart("p1")

        done.await()

        val log = events.log
        val ocr0 = log.indexOf("ocrStarted:p0")
        val ocr1 = log.indexOf("ocrStarted:p1")
        val inpaint0 = log.indexOf("inpaintStarted:p0")
        val inpaint1 = log.indexOf("inpaintStarted:p1")

        val inpaint0Finish = log.indexOf("inpaintFinished:p0")
        (ocr0 < inpaint0) shouldBe true
        (inpaint0Finish < ocr1) shouldBe true
        (ocr1 < inpaint1) shouldBe true
    }

    @Test
    fun `ML Kit translates inline between OCR and inpaint`() = runTest {
        val events = RecordingListener()
        val native = GatedNativeWorker()
        val translator = RecordingTranslatorWorker()
        val render = NoopRenderJoin()
        val coord = BatchCoordinator(native, translator, render, events)

        val pages = listOf("p0" to 0)
        val done = async { coord.runPass1(pages, TranslatorComputeClass.LOCAL_COMPUTE) }

        native.ocrPublished("p0").await()
        native.allowOcrToFinish("p0")

        events.awaitEvent("translationRequested:p0")
        translator.allowTranslationToStart("p0")
        events.awaitEvent("inpaintStarted:p0")
        native.allowInpaintToFinish("p0")

        done.await()

        val log = events.log
        val ocrFinish = log.indexOf("ocrFinished:p0")
        val translateStart = log.indexOf("translationRequested:p0")
        val inpaintStart = log.indexOf("inpaintStarted:p0")

        (ocrFinish < translateStart) shouldBe true
        (translateStart < inpaintStart) shouldBe true
    }

    @Test
    fun `later OCR waits for the earlier page inpaint`() = runTest {
        val events = RecordingListener()
        val native = GatedNativeWorker()
        val translator = RecordingTranslatorWorker()
        val render = NoopRenderJoin()
        val coord = BatchCoordinator(native, translator, render, events)

        val pages = listOf("p0" to 0, "p1" to 1)
        val done = async { coord.runPass1(pages, TranslatorComputeClass.REMOTE_IO) }

        // The next page waits until the current page's inpaint has finished.
        native.ocrPublished("p0").await()
        native.allowOcrToFinish("p0")
        events.awaitEvent("inpaintStarted:p0")
        events.log.contains("ocrStarted:p1") shouldBe false
        native.allowInpaintToFinish("p0")

        native.ocrPublished("p1").await()
        native.allowOcrToFinish("p1")
        events.awaitEvent("inpaintStarted:p1")

        // Unstall translator
        translator.allowTranslationToStart("p0")
        translator.allowTranslationToStart("p1")
        native.allowInpaintToFinish("p1")
        done.await()

        val inpaint0Finish = events.log.indexOf("inpaintFinished:p0")
        val ocr1 = events.log.indexOf("ocrStarted:p1")
        (inpaint0Finish < ocr1) shouldBe true
    }

    class RecordingListener : BatchScheduleListener() {
        val log = mutableListOf<String>()
        val waiters = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

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

        override fun ocrStarted(pageKey: String) = add("ocrStarted:$pageKey")
        override fun ocrFinished(pageKey: String) = add("ocrFinished:$pageKey")
        override fun ocrPublished(pageKey: String) = add("ocrPublished:$pageKey")
        override fun inpaintStarted(pageKey: String) = add("inpaintStarted:$pageKey")
        override fun inpaintFinished(pageKey: String) = add("inpaintFinished:$pageKey")
        override fun translationRequested(pageKey: String) = add("translationRequested:$pageKey")
        override fun translationFinished(pageKey: String) = add("translationFinished:$pageKey")
        override fun renderStarted(pageKey: String) = add("renderStarted:$pageKey")
        override fun renderFinished(pageKey: String) = add("renderFinished:$pageKey")
        override fun pass1BarrierReleased() = add("pass1BarrierReleased")
        override fun pass2Started() = add("pass2Started")
    }

    class GatedNativeWorker : NativeLaneWorker {
        private val published = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val ocrFinishGates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val inpaintFinishGates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? {
            published.getOrPut(pageKey) { CompletableDeferred() }.complete(Unit)
            ocrFinishGates.getOrPut(pageKey) { CompletableDeferred() }.await()
            return OcrReadyPageRef(pageKey, pageIndex, 0L, emptyList())
        }

        override suspend fun runInpaintStage(pageKey: String) {
            inpaintFinishGates.getOrPut(pageKey) { CompletableDeferred() }.await()
        }

        fun ocrPublished(pageKey: String): Deferred<Unit> =
            published.getOrPut(pageKey) { CompletableDeferred() }

        fun allowOcrToFinish(pageKey: String) {
            ocrFinishGates.getOrPut(pageKey) { CompletableDeferred() }.complete(Unit)
        }

        fun allowInpaintToFinish(pageKey: String) {
            inpaintFinishGates.getOrPut(pageKey) { CompletableDeferred() }.complete(Unit)
        }
    }

    class RecordingTranslatorWorker : TranslatorLaneWorker {
        private val startGates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        override suspend fun translate(ref: OcrReadyPageRef) {
            startGates.getOrPut(ref.pageKey) { CompletableDeferred() }.await()
        }

        fun allowTranslationToStart(pageKey: String) {
            startGates.getOrPut(pageKey) { CompletableDeferred() }.complete(Unit)
        }
    }

    class NoopRenderJoin : RenderJoinWorker {
        override fun onNativeBranchDone(pageKey: String) {}
        override fun onTranslationBranchDone(pageKey: String) {}
        override suspend fun awaitAndRender(pageKey: String) {}
    }
}
