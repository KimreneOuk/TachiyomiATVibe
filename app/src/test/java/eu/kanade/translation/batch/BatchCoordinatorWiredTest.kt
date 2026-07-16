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

class BatchCoordinatorWiredTest {

    @Test
    fun `deferred translator lane still overlaps inpaint and gates pass2 on barrier`() = runTest {
        val events = RecordingListener()
        val native = GatedNativeWorker()
        val translator = DeferringTranslatorWorker()
        val render = IdempotentRenderJoin()
        val coord = BatchCoordinator(native, translator, render, events)

        val pages = listOf("p0" to 0, "p1" to 1)
        val pass1 = async { coord.runPass1(pages, TranslatorComputeClass.REMOTE_IO) }

        native.ocrPublished("p0").await()
        native.allowOcrToFinish("p0")
        native.ocrPublished("p1").await()
        native.allowOcrToFinish("p1")
        
        events.awaitEvent("allOcrBarrierReleased")
        
        // Yield so translator lane picks up items
        repeat(3) { yield() }
        
        // DeferringTranslatorWorker accepts immediately
        
        native.allowInpaintToFinish("p0")
        native.allowInpaintToFinish("p1")
        pass1.await()

        render.rendered shouldContainExactly setOf("p0", "p1")
        translator.accepted shouldContainExactly setOf("p0", "p1")

        val log = events.log
        val barrierIdx = log.indexOf("allOcrBarrierReleased")
        val translateIdx = log.indexOf("translationRequested:p0")
        val inpaintFinishIdx = log.indexOf("inpaintFinished:p0")
        
        (barrierIdx shouldNotBe -1)
        (translateIdx shouldNotBe -1)
        (inpaintFinishIdx shouldNotBe -1)
        (translateIdx > barrierIdx) shouldBe true
        (translateIdx < inpaintFinishIdx) shouldBe true

        val pass2 = async { coord.runPass2(listOf("p0" to PageTranslation())) { } }
        pass2.await()
        val pass2Idx = events.log.indexOf("pass2Started")
        (pass2Idx > barrierIdx) shouldBe true
    }

    @Test
    fun `native inpaint overlap never exceeds one across pages`() = runTest {
        val events = RecordingListener()
        val inflight = AtomicInteger(0)
        val maxOverlap = AtomicInteger(0)
        val native = object : GatedNativeWorker() {
            override suspend fun runInpaintStage(pageKey: String) {
                val cur = inflight.incrementAndGet()
                maxOverlap.updateAndGet { maxOf(it, cur) }
                super.runInpaintStage(pageKey)
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

    @Test
    fun `local compute translation never overlaps native inpaint`() = runTest {
        val events = RecordingListener()
        val nativeInflight = AtomicInteger(0)
        val translateInflight = AtomicInteger(0)
        val maxOverlap = AtomicInteger(0)
        val native = object : NativeLaneWorker {
            override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? {
                return OcrReadyPageRef(pageKey, pageIndex, 0L, emptyList())
            }
            override suspend fun runInpaintStage(pageKey: String) {
                val cur = nativeInflight.incrementAndGet()
                if (translateInflight.get() > 0) maxOverlap.incrementAndGet()
                yield()
                nativeInflight.decrementAndGet()
            }
        }
        val translator = object : TranslatorLaneWorker {
            override suspend fun translate(ref: OcrReadyPageRef) {
                val cur = translateInflight.incrementAndGet()
                if (nativeInflight.get() > 0) maxOverlap.incrementAndGet()
                yield()
                translateInflight.decrementAndGet()
            }
        }
        val coord = BatchCoordinator(native, translator, IdempotentRenderJoin(), events)
        coord.runPass1(listOf("p0" to 0, "p1" to 1), TranslatorComputeClass.LOCAL_COMPUTE)

        maxOverlap.get() shouldBe 0
    }

    private open class RecordingListener : BatchScheduleListener() {
        private val _log = mutableListOf<String>()
        val log: List<String> get() = _log.toList()
        val waiters = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        @Synchronized
        private fun add(entry: String) {
            _log.add(entry)
            waiters[entry]?.complete(Unit)
        }

        suspend fun awaitEvent(event: String) {
            val deferred = synchronized(this) {
                if (_log.contains(event)) return
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
        override fun allOcrBarrierReleased() = add("allOcrBarrierReleased")
        override fun pass1BarrierReleased() = add("pass1BarrierReleased")
        override fun pass2Started() = add("pass2Started")
    }

    private open class GatedNativeWorker : NativeLaneWorker {
        private val inpaintGates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val ocrSignals = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val ocrFinishGates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        
        override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? {
            ocrSignals.getOrPut(pageKey) { CompletableDeferred() }.complete(Unit)
            ocrFinishGates.getOrPut(pageKey) { CompletableDeferred() }.await()
            return OcrReadyPageRef(pageKey, pageIndex, 0L, emptyList())
        }
        override suspend fun runInpaintStage(pageKey: String) {
            inpaintGates.getOrPut(pageKey) { CompletableDeferred() }.await()
        }
        fun ocrPublished(pageKey: String): Deferred<Unit> =
            ocrSignals.getOrPut(pageKey) { CompletableDeferred() }
        fun allowOcrToFinish(pageKey: String) {
            ocrFinishGates.getOrPut(pageKey) { CompletableDeferred() }.complete(Unit)
        }
        fun allowInpaintToFinish(pageKey: String) {
            inpaintGates.getOrPut(pageKey) { CompletableDeferred() }.complete(Unit)
        }
        fun releaseAll(count: Int) {
            repeat(count) { idx ->
                ocrFinishGates.getOrPut("p$idx") { CompletableDeferred() }.complete(Unit)
                inpaintGates.getOrPut("p$idx") { CompletableDeferred() }.complete(Unit)
            }
        }
    }

    private class DeferringTranslatorWorker : TranslatorLaneWorker {
        val accepted = ConcurrentHashMap.newKeySet<String>()
        override suspend fun translate(ref: OcrReadyPageRef) {
            accepted += ref.pageKey
        }
    }

    private class IdempotentRenderJoin : RenderJoinWorker {
        val rendered = ConcurrentHashMap.newKeySet<String>()
        override fun onNativeBranchDone(pageKey: String) {}
        override fun onTranslationBranchDone(pageKey: String) {}
        override suspend fun awaitAndRender(pageKey: String) {
            rendered += pageKey
        }
    }
}
