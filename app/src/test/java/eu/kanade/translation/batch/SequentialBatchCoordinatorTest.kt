package eu.kanade.translation.batch

import eu.kanade.translation.translator.TranslatorComputeClass
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 5 production-path coverage for the consolidated sequential coordinator:
 * exact per-page stage invocation counts, bounded native lookahead, sequential
 * translation commits in natural order, reader-vs-batch ownership skips,
 * cancellation release, and chapter-length-independent in-flight bounds.
 */
class SequentialBatchCoordinatorTest {

    @Test
    fun `every page invokes ocr and inpaint exactly once and renders in natural order`() = runTest {
        val events = RecordingListener()
        val ocrCounts = ConcurrentHashMap<String, Int>()
        val inpaintCounts = ConcurrentHashMap<String, Int>()
        val native = object : NativeLaneWorker {
            override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef {
                ocrCounts.merge(pageKey, 1, Int::plus)
                return OcrReadyPageRef(pageKey, pageIndex, 0L, emptyList())
            }

            override suspend fun runInpaintStage(pageKey: String) {
                inpaintCounts.merge(pageKey, 1, Int::plus)
            }
        }
        val translator = object : TranslatorLaneWorker {
            override suspend fun translate(ref: OcrReadyPageRef) {
                yield()
            }
        }
        val render = RecordingRenderJoin()

        val coordinator = SequentialBatchCoordinator(native, translator, render, events)
        val pages = (0 until 6).map { "p$it" to it }
        val outcome = coordinator.runPass1(pages, TranslatorComputeClass.REMOTE_IO)

        pages.forEach { (pageKey, _) ->
            ocrCounts[pageKey] shouldBe 1
            inpaintCounts[pageKey] shouldBe 1
        }
        render.rendered shouldContainExactly pages.map { it.first }
        events.filter("translationRequested:") shouldContainExactly pages.map { "translationRequested:p${it.second}" }
        outcome.needsTranslation.toSet() shouldBe pages.map { it.first }.toSet()
        events.barrierReleased.get() shouldBe true
    }

    @Test
    fun `native lookahead never exceeds three pages beyond the translation frontier`() = runTest {
        val events = RecordingListener()
        val native = ImmediateNativeWorker()
        val translator = GatedTranslatorWorker()
        val render = RecordingRenderJoin()

        val coordinator = SequentialBatchCoordinator(native, translator, render, events)
        val pages = (0 until 8).map { "p$it" to it }

        val pass1 = async { coordinator.runPass1(pages, TranslatorComputeClass.REMOTE_IO) }
        advanceUntilIdle()

        // The translator holds p0 unsettled, so the native lane may start at
        // most p0..p3 (current page plus three lookahead pages).
        events.count("ocrStarted:") shouldBe SequentialBatchCoordinator.MAX_NATIVE_LOOKAHEAD_PAGES + 1
        events.contains("ocrStarted:p4") shouldBe false
        translator.awaitTranslationStarted("p0")
        events.count("inpaintFinished:") shouldBe 4

        // Settling p0 lets the native lane advance exactly one more page.
        translator.allowTranslationToFinish("p0")
        advanceUntilIdle()
        events.contains("ocrStarted:p4") shouldBe true
        events.contains("ocrStarted:p5") shouldBe false

        (1 until 8).forEach { translator.allowTranslationToFinish("p$it") }
        pass1.await()
        events.count("ocrStarted:") shouldBe 8
        render.rendered.size shouldBe 8
    }

    @Test
    fun `translation commits sequentially in natural order while native runs ahead`() = runTest {
        val events = RecordingListener()
        val native = ImmediateNativeWorker()
        val requested = mutableListOf<String>()
        val translator = object : TranslatorLaneWorker {
            override suspend fun translate(ref: OcrReadyPageRef) {
                synchronized(requested) { requested.add(ref.pageKey) }
                // Slow the translator down so native lookahead builds up first.
                repeat(3) { yield() }
            }
        }
        val render = RecordingRenderJoin()

        val coordinator = SequentialBatchCoordinator(native, translator, render, events)
        coordinator.runPass1(
            (0 until 5).map { "p$it" to it },
            TranslatorComputeClass.REMOTE_IO,
        )

        synchronized(requested) { requested.toList() } shouldContainExactly listOf("p0", "p1", "p2", "p3", "p4")
        events.filter("translationFinished:") shouldContainExactly (0 until 5).map { "translationFinished:p$it" }
    }

    @Test
    fun `local compute translation runs inline and never overlaps native work`() = runTest {
        val events = RecordingListener()
        val nativeInflight = AtomicInteger(0)
        val translateInflight = AtomicInteger(0)
        val overlaps = AtomicInteger(0)
        val native = object : NativeLaneWorker {
            override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef {
                nativeInflight.incrementAndGet()
                if (translateInflight.get() > 0) overlaps.incrementAndGet()
                yield()
                nativeInflight.decrementAndGet()
                return OcrReadyPageRef(pageKey, pageIndex, 0L, emptyList())
            }

            override suspend fun runInpaintStage(pageKey: String) {
                nativeInflight.incrementAndGet()
                if (translateInflight.get() > 0) overlaps.incrementAndGet()
                yield()
                nativeInflight.decrementAndGet()
            }
        }
        val translator = object : TranslatorLaneWorker {
            override suspend fun translate(ref: OcrReadyPageRef) {
                translateInflight.incrementAndGet()
                if (nativeInflight.get() > 0) overlaps.incrementAndGet()
                yield()
                translateInflight.decrementAndGet()
            }
        }
        val render = RecordingRenderJoin()

        val coordinator = SequentialBatchCoordinator(native, translator, render, events)
        coordinator.runPass1(
            (0 until 3).map { "p$it" to it },
            TranslatorComputeClass.LOCAL_COMPUTE,
        )

        overlaps.get() shouldBe 0
        val log = events.log
        (log.indexOf("ocrStarted:p0") < log.indexOf("translationRequested:p0")) shouldBe true
        (log.indexOf("translationFinished:p0") < log.indexOf("inpaintStarted:p0")) shouldBe true
        (log.indexOf("inpaintFinished:p0") < log.indexOf("ocrStarted:p1")) shouldBe true
        render.rendered.size shouldBe 3
    }

    @Test
    fun `remote translation overlaps same-page inpaint and render waits for both branches`() = runTest {
        val events = RecordingListener()
        val native = GatedNativeWorker()
        val translator = GatedTranslatorWorker()
        val render = RecordingRenderJoin()

        val coordinator = SequentialBatchCoordinator(native, translator, render, events)
        val pass1 = async { coordinator.runPass1(listOf("p0" to 0), TranslatorComputeClass.REMOTE_IO) }

        native.allowOcrToFinish("p0")
        native.awaitInpaintStarted("p0")
        translator.awaitTranslationStarted("p0")

        render.rendered.isEmpty() shouldBe true

        native.allowInpaintToFinish("p0")
        advanceUntilIdle()
        render.rendered.isEmpty() shouldBe true

        translator.allowTranslationToFinish("p0")
        pass1.await()
        render.rendered shouldBe listOf("p0")
    }

    @Test
    fun `page without an OCR reference skips inpaint and translation but settles render gates`() = runTest {
        val events = RecordingListener()
        val inpaintPages = ConcurrentHashMap.newKeySet<String>()
        val native = object : NativeLaneWorker {
            override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? {
                // p1 models a reader-owned/reused page: the batch gets no work item.
                if (pageKey == "p1") return null
                return OcrReadyPageRef(pageKey, pageIndex, 0L, emptyList())
            }

            override suspend fun runInpaintStage(pageKey: String) {
                inpaintPages += pageKey
            }
        }
        val translated = ConcurrentHashMap.newKeySet<String>()
        val translator = object : TranslatorLaneWorker {
            override suspend fun translate(ref: OcrReadyPageRef) {
                translated += ref.pageKey
            }
        }
        val render = RecordingRenderJoin()

        val coordinator = SequentialBatchCoordinator(native, translator, render, events)
        val outcome = coordinator.runPass1(
            listOf("p0" to 0, "p1" to 1, "p2" to 2),
            TranslatorComputeClass.REMOTE_IO,
        )

        inpaintPages.toSet() shouldBe setOf("p0", "p2")
        translated.toSet() shouldBe setOf("p0", "p2")
        outcome.needsTranslation.toSet() shouldBe setOf("p0", "p2")
        // The render join still observes every page so no per-page signal leaks.
        render.rendered shouldBe listOf("p0", "p1", "p2")
        events.barrierReleased.get() shouldBe true
    }

    @Test
    fun `per-page failures are isolated and the rest of the batch completes`() = runTest {
        val events = RecordingListener()
        val native = object : NativeLaneWorker {
            override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? {
                if (pageKey == "p0") throw RuntimeException("ocr stage failure")
                return OcrReadyPageRef(pageKey, pageIndex, 0L, emptyList())
            }

            override suspend fun runInpaintStage(pageKey: String) {
                if (pageKey == "p1") throw RuntimeException("inpaint stage failure")
            }
        }
        val translator = object : TranslatorLaneWorker {
            override suspend fun translate(ref: OcrReadyPageRef) {
                if (ref.pageKey == "p2") throw RuntimeException("translation failure")
            }
        }
        val render = RecordingRenderJoin()

        val coordinator = SequentialBatchCoordinator(native, translator, render, events)
        val outcome = coordinator.runPass1(
            listOf("p0" to 0, "p1" to 1, "p2" to 2, "p3" to 3),
            TranslatorComputeClass.REMOTE_IO,
        )

        render.rendered shouldBe listOf("p0", "p1", "p2", "p3")
        outcome.needsTranslation.toSet() shouldBe setOf("p1", "p2", "p3")
        events.barrierReleased.get() shouldBe true
    }

    @Test
    fun `cancellation stops the pass promptly and starts no further stages`() = runTest {
        val events = RecordingListener()
        val native = GatedNativeWorker()
        val translator = object : TranslatorLaneWorker {
            override suspend fun translate(ref: OcrReadyPageRef) {}
        }
        val render = RecordingRenderJoin()

        val coordinator = SequentialBatchCoordinator(native, translator, render, events)
        val pass1 = async {
            coordinator.runPass1(
                listOf("p0" to 0, "p1" to 1, "p2" to 2),
                TranslatorComputeClass.REMOTE_IO,
            )
        }

        native.awaitOcrStarted("p0")
        pass1.cancelAndJoin()

        events.count("inpaintStarted:") shouldBe 0
        events.count("translationRequested:") shouldBe 0
        events.barrierReleased.get() shouldBe false
        render.rendered.isEmpty() shouldBe true
    }

    @Test
    fun `long chapter keeps in-flight pages bounded and a second run behaves identically`() = runTest {
        val pageCount = 120
        val pages = (0 until pageCount).map { "p$it" to it }
        val native = ImmediateNativeWorker()

        suspend fun runOnce(): Triple<RecordingListener, RecordingRenderJoin, BatchPass1Outcome> {
            val events = RecordingListener()
            val translator = object : TranslatorLaneWorker {
                override suspend fun translate(ref: OcrReadyPageRef) {
                    yield()
                }
            }
            val render = RecordingRenderJoin()
            val outcome = SequentialBatchCoordinator(native, translator, render, events)
                .runPass1(pages, TranslatorComputeClass.REMOTE_IO)
            return Triple(events, render, outcome)
        }

        val (events, render, outcome) = runOnce()

        (events.maxUnsettledPages.get() <= SequentialBatchCoordinator.MAX_NATIVE_LOOKAHEAD_PAGES + 1) shouldBe true
        render.rendered.size shouldBe pageCount
        outcome.needsTranslation.size shouldBe pageCount

        val (secondEvents, secondRender, secondOutcome) = runOnce()
        secondEvents.count("ocrStarted:") shouldBe pageCount
        secondEvents.count("inpaintStarted:") shouldBe pageCount
        secondRender.rendered.size shouldBe pageCount
        secondOutcome.needsTranslation.size shouldBe pageCount
    }

    private open class RecordingListener : BatchScheduleListener() {
        private val _log = mutableListOf<String>()
        val log: List<String> get() = synchronized(_log) { _log.toList() }
        private val waiters = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        val barrierReleased = AtomicBoolean(false)
        private val ocrStartedCount = AtomicInteger(0)
        private val translationFinishedCount = AtomicInteger(0)
        val maxUnsettledPages = AtomicInteger(0)

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

        fun contains(event: String): Boolean = synchronized(_log) { _log.contains(event) }

        fun count(prefix: String): Int = synchronized(_log) { _log.count { it.startsWith(prefix) } }

        fun filter(prefix: String): List<String> = synchronized(_log) { _log.filter { it.startsWith(prefix) } }

        override fun ocrStarted(pageKey: String) {
            ocrStartedCount.incrementAndGet()
            recordUnsettled()
            add("ocrStarted:$pageKey")
        }

        override fun ocrFinished(pageKey: String) = add("ocrFinished:$pageKey")

        override fun ocrPublished(pageKey: String) = add("ocrPublished:$pageKey")

        override fun inpaintStarted(pageKey: String) = add("inpaintStarted:$pageKey")

        override fun inpaintFinished(pageKey: String) = add("inpaintFinished:$pageKey")

        override fun translationRequested(pageKey: String) = add("translationRequested:$pageKey")

        override fun translationFinished(pageKey: String) {
            translationFinishedCount.incrementAndGet()
            recordUnsettled()
            add("translationFinished:$pageKey")
        }

        override fun renderStarted(pageKey: String) = add("renderStarted:$pageKey")

        override fun renderFinished(pageKey: String) = add("renderFinished:$pageKey")

        override fun pass1BarrierReleased() {
            barrierReleased.set(true)
            add("pass1BarrierReleased")
        }

        private fun recordUnsettled() {
            val unsettled = ocrStartedCount.get() - translationFinishedCount.get()
            maxUnsettledPages.updateAndGet { maxOf(it, unsettled) }
        }
    }

    private class ImmediateNativeWorker : NativeLaneWorker {
        override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef {
            yield()
            return OcrReadyPageRef(pageKey, pageIndex, 0L, emptyList())
        }

        override suspend fun runInpaintStage(pageKey: String) {
            yield()
        }
    }

    private class GatedNativeWorker : NativeLaneWorker {
        private val ocrStartSignals = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val ocrFinishGates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val inpaintStartSignals = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val inpaintFinishGates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef {
            ocrStartSignals.getOrPut(pageKey) { CompletableDeferred() }.complete(Unit)
            ocrFinishGates.getOrPut(pageKey) { CompletableDeferred() }.await()
            return OcrReadyPageRef(pageKey, pageIndex, 0L, emptyList())
        }

        override suspend fun runInpaintStage(pageKey: String) {
            inpaintStartSignals.getOrPut(pageKey) { CompletableDeferred() }.complete(Unit)
            inpaintFinishGates.getOrPut(pageKey) { CompletableDeferred() }.await()
        }

        suspend fun awaitOcrStarted(pageKey: String) =
            ocrStartSignals.getOrPut(pageKey) { CompletableDeferred() }.await()

        fun allowOcrToFinish(pageKey: String) {
            ocrFinishGates.getOrPut(pageKey) { CompletableDeferred() }.complete(Unit)
        }

        suspend fun awaitInpaintStarted(pageKey: String) =
            inpaintStartSignals.getOrPut(pageKey) { CompletableDeferred() }.await()

        fun allowInpaintToFinish(pageKey: String) {
            inpaintFinishGates.getOrPut(pageKey) { CompletableDeferred() }.complete(Unit)
        }
    }

    private class GatedTranslatorWorker : TranslatorLaneWorker {
        private val startSignals = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val finishGates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        override suspend fun translate(ref: OcrReadyPageRef) {
            startSignals.getOrPut(ref.pageKey) { CompletableDeferred() }.complete(Unit)
            finishGates.getOrPut(ref.pageKey) { CompletableDeferred() }.await()
        }

        suspend fun awaitTranslationStarted(pageKey: String) =
            startSignals.getOrPut(pageKey) { CompletableDeferred() }.await()

        fun allowTranslationToFinish(pageKey: String) {
            finishGates.getOrPut(pageKey) { CompletableDeferred() }.complete(Unit)
        }
    }

    private class RecordingRenderJoin : RenderJoinWorker {
        private val _rendered = mutableListOf<String>()
        val rendered: List<String> get() = synchronized(_rendered) { _rendered.toList() }

        override fun onNativeBranchDone(pageKey: String) {}

        override fun onTranslationBranchDone(pageKey: String) {}

        override suspend fun awaitAndRender(pageKey: String) {
            yield()
            synchronized(_rendered) { _rendered.add(pageKey) }
        }
    }
}
