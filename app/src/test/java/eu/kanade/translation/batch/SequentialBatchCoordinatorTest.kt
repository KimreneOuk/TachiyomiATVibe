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
import java.io.ByteArrayInputStream
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
    fun `same visit hands one source decode from ocr to inpaint`() = runTest {
        val sourceOpens = AtomicInteger(0)
        val released = AtomicInteger(0)
        val sourceStreamFactory = { pageKey: String ->
            sourceOpens.incrementAndGet()
            ByteArrayInputStream("source:$pageKey".toByteArray())
        }
        val native = object : NativeLaneWorker {
            override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef {
                val decoded = sourceStreamFactory(pageKey).readBytes()
                return OcrReadyPageRef(pageKey, pageIndex, 0L, emptyList(), nativeHandoff = decoded)
            }

            override suspend fun runInpaintStage(pageKey: String) {}

            override suspend fun runInpaintStage(pageKey: String, nativeHandoff: Any?) {
                nativeHandoff shouldBe "source:$pageKey".toByteArray()
            }

            override fun releaseNativeHandoff(ref: OcrReadyPageRef) {
                released.incrementAndGet()
            }
        }
        val coordinator = SequentialBatchCoordinator(
            native,
            object : TranslatorLaneWorker {
                override suspend fun translate(ref: OcrReadyPageRef) {}
            },
            RecordingRenderJoin(),
        )

        coordinator.runPass1(listOf("p0" to 0), TranslatorComputeClass.REMOTE_IO)

        sourceOpens.get() shouldBe 1
        released.get() shouldBe 1
    }

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
    fun `adaptive probe is the only native lookahead and waits for prior chunk terminal`() = runTest {
        val events = RecordingListener()
        val native = ImmediateNativeWorker()
        val translator = ProbeChunkTranslator(probePage = "p2", gateFirstChunk = true)
        val render = RecordingRenderJoin()
        val pages = (0 until 4).map { "p$it" to it }
        val coordinator = SequentialBatchCoordinator(native, translator, render, events)

        val pass1 = async { coordinator.runPass1(pages, TranslatorComputeClass.REMOTE_IO) }
        translator.awaitFirstChunkStarted()
        advanceUntilIdle()

        events.count("ocrStarted:") shouldBe 3
        events.contains("ocrStarted:p3") shouldBe false
        events.contains("inpaintStarted:p2") shouldBe false
        translator.allowFirstChunkToFinish()
        advanceUntilIdle()
        pass1.await()

        events.contains("ocrStarted:p3") shouldBe true
        render.rendered shouldBe pages.map { it.first }
        translator.admitted shouldContainExactly listOf("p0", "p1", "p2", "p3")
        translator.admissionCalls.count { it == "p2" } shouldBe 1
    }

    @Test
    fun `adaptive AI completion overlaps inpaint only after full chunk OCR`() = runTest {
        val events = RecordingListener()
        val native = GatedNativeWorker()
        val translator = ProbeChunkTranslator(probePage = "never", gateFirstChunk = true)
        val render = RecordingRenderJoin()
        val coordinator = SequentialBatchCoordinator(
            native,
            translator,
            render,
            events,
        )

        val pass1 = async {
            coordinator.runPass1(
                listOf("p0" to 0, "p1" to 1),
                TranslatorComputeClass.REMOTE_IO,
            )
        }
        native.allowOcrToFinish("p0")
        native.allowOcrToFinish("p1")
        translator.awaitFirstChunkStarted()
        native.awaitInpaintStarted("p0")

        events.filter("ocrFinished:") shouldContainExactly listOf("ocrFinished:p0", "ocrFinished:p1")
        events.contains("inpaintStarted:p0") shouldBe true
        translator.allowFirstChunkToFinish()
        native.allowInpaintToFinish("p0")
        native.allowInpaintToFinish("p1")
        pass1.await()
        render.rendered shouldContainExactly listOf("p0", "p1")
    }

    @Test
    fun `unexpected translation failure stops before admitting the next OCR`() = runTest {
        val events = RecordingListener()
        val native = ImmediateNativeWorker()
        val translator = ProbeChunkTranslator(
            probePage = "p1",
            gateFirstChunk = false,
            failFirstChunk = true,
        )
        val render = RecordingRenderJoin()
        val coordinator = SequentialBatchCoordinator(native, translator, render, events)

        val outcome = coordinator.runPass1(
            listOf("p0" to 0, "p1" to 1, "p2" to 2),
            TranslatorComputeClass.REMOTE_IO,
        )

        outcome.status shouldBe BatchPass1Status.FAILED
        outcome.anchorPageKey shouldBe "p0"
        outcome.terminalPageKeys shouldBe setOf("p0")
        outcome.unexpectedStage shouldBe BatchDiagnosticStage.TRANSLATION
        events.contains("ocrStarted:p2") shouldBe false
        render.rendered shouldBe emptyList()
    }

    @Test
    fun `typed pause settles unresolved chunk and stops future OCR admission`() = runTest {
        val events = RecordingListener()
        val native = ImmediateNativeWorker()
        val rendered = mutableListOf<String>()
        val settled = mutableListOf<String>()
        val render = object : RenderJoinWorker {
            override fun onNativeBranchDone(pageKey: String) {}
            override fun onTranslationBranchDone(pageKey: String) {}

            override suspend fun awaitAndRender(pageKey: String) {
                rendered += pageKey
            }

            override suspend fun awaitAndSettle(pageKey: String) {
                settled += pageKey
            }
        }
        val translator = object : TranslatorLaneWorker {
            override suspend fun translate(ref: OcrReadyPageRef) {
                if (ref.pageKey == "p1") {
                    error("typed path should use translateOutcome")
                }
            }

            override suspend fun translateOutcome(ref: OcrReadyPageRef): ChunkCompletionOutcome =
                if (ref.pageKey == "p1") {
                    ChunkCompletionOutcome.Paused(
                        anchorPageKey = "p1",
                        completedPageKeys = setOf("p0"),
                        retryablePageKeys = setOf("p1"),
                        reason = "provider quota exhausted",
                    )
                } else {
                    ChunkCompletionOutcome.Completed(setOf(ref.pageKey))
                }
        }
        val coordinator = SequentialBatchCoordinator(native, translator, render, events)

        val outcome = coordinator.runPass1(
            (0 until 8).map { "p$it" to it },
            TranslatorComputeClass.REMOTE_IO,
        )

        outcome.status shouldBe BatchPass1Status.PAUSED
        outcome.anchorPageKey shouldBe "p1"
        outcome.completedPageKeys shouldBe setOf("p0")
        outcome.retryablePageKeys shouldBe setOf("p1")
        events.contains("ocrStarted:p7") shouldBe false
        rendered shouldBe listOf("p0")
        settled shouldContainExactly (1 until 7).map { "p$it" }
    }

    @Test
    fun `typed pause propagates through the local translation lane`() = runTest {
        val ocrStarted = mutableListOf<String>()
        val native = object : NativeLaneWorker {
            override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef {
                ocrStarted += pageKey
                return OcrReadyPageRef(pageKey, pageIndex, 0L, emptyList())
            }

            override suspend fun runInpaintStage(pageKey: String) = Unit
        }
        val translator = object : TranslatorLaneWorker {
            override suspend fun translate(ref: OcrReadyPageRef) = error("typed path should use translateOutcome")

            override suspend fun translateOutcome(ref: OcrReadyPageRef): ChunkCompletionOutcome =
                if (ref.pageKey == "p0") {
                    ChunkCompletionOutcome.Paused(
                        anchorPageKey = "p0",
                        retryablePageKeys = setOf("p0"),
                        reason = "provider quota exhausted",
                    )
                } else {
                    ChunkCompletionOutcome.Completed(setOf(ref.pageKey))
                }
        }

        val outcome = SequentialBatchCoordinator(
            native,
            translator,
            RecordingRenderJoin(),
        ).runPass1(
            listOf("p0" to 0, "p1" to 1),
            TranslatorComputeClass.LOCAL_COMPUTE,
        )

        outcome.status shouldBe BatchPass1Status.PAUSED
        outcome.anchorPageKey shouldBe "p0"
        ocrStarted shouldBe listOf("p0")
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
    fun `unexpected stage failure stops the pass without completing later pages`() = runTest {
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

        outcome.status shouldBe BatchPass1Status.FAILED
        outcome.anchorPageKey shouldBe "p0"
        outcome.terminalPageKeys shouldBe setOf("p0")
        outcome.unexpectedStage shouldBe BatchDiagnosticStage.OCR
        outcome.completedPageKeys shouldBe emptySet()
        render.rendered shouldBe emptyList()
        events.barrierReleased.get() shouldBe false
    }

    @Test
    fun `unexpected inpaint failure is not reported as completed`() = runTest {
        val events = RecordingListener()
        val native = object : NativeLaneWorker {
            override suspend fun runOcrStage(pageKey: String, pageIndex: Int) =
                OcrReadyPageRef(pageKey, pageIndex, 0L, emptyList())

            override suspend fun runInpaintStage(pageKey: String) {
                if (pageKey == "p0") throw RuntimeException("inpaint stage failure")
            }
        }
        val translator = object : TranslatorLaneWorker {
            override suspend fun translate(ref: OcrReadyPageRef) = Unit
        }
        val render = RecordingRenderJoin()

        val outcome = SequentialBatchCoordinator(native, translator, render, events).runPass1(
            listOf("p0" to 0, "p1" to 1),
            TranslatorComputeClass.REMOTE_IO,
        )

        outcome.status shouldBe BatchPass1Status.FAILED
        outcome.anchorPageKey shouldBe "p0"
        outcome.terminalPageKeys shouldBe setOf("p0")
        outcome.unexpectedStage shouldBe BatchDiagnosticStage.INPAINT
        outcome.completedPageKeys shouldBe emptySet()
    }

    @Test
    fun `unexpected translation failure is not reported as completed`() = runTest {
        val events = RecordingListener()
        val native = object : NativeLaneWorker {
            override suspend fun runOcrStage(pageKey: String, pageIndex: Int) =
                OcrReadyPageRef(pageKey, pageIndex, 0L, emptyList())

            override suspend fun runInpaintStage(pageKey: String) = Unit
        }
        val translator = object : TranslatorLaneWorker {
            override suspend fun translate(ref: OcrReadyPageRef) {
                if (ref.pageKey == "p0") throw RuntimeException("translation stage failure")
            }
        }
        val render = RecordingRenderJoin()

        val outcome = SequentialBatchCoordinator(native, translator, render, events).runPass1(
            listOf("p0" to 0, "p1" to 1),
            TranslatorComputeClass.REMOTE_IO,
        )

        outcome.status shouldBe BatchPass1Status.FAILED
        outcome.anchorPageKey shouldBe "p0"
        outcome.terminalPageKeys shouldBe setOf("p0")
        outcome.unexpectedStage shouldBe BatchDiagnosticStage.TRANSLATION
        outcome.completedPageKeys shouldBe emptySet()
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

    private class ProbeChunkTranslator(
        private val probePage: String,
        private val gateFirstChunk: Boolean,
        private val failFirstChunk: Boolean = false,
    ) : TranslatorLaneWorker {
        override val usesChunkAdmission: Boolean = true
        val admitted = mutableListOf<String>()
        val admissionCalls = mutableListOf<String>()
        private val firstChunkStarted = CompletableDeferred<Unit>()
        private val firstChunkFinish = CompletableDeferred<Unit>()
        private var completionCount = 0

        override suspend fun admit(ref: OcrReadyPageRef): ChunkAdmission {
            synchronized(admitted) {
                admissionCalls += ref.pageKey
                if (ref.pageKey !in admitted) admitted += ref.pageKey
            }
            return if (ref.pageKey == probePage) ChunkAdmission.PROBE else ChunkAdmission.ACCEPT
        }

        override suspend fun translate(ref: OcrReadyPageRef) {
            error("adaptive test translator must complete buffered chunks")
        }

        override suspend fun completeChunk(finalChunk: Boolean) {
            completionCount++
            if (completionCount != 1) return
            firstChunkStarted.complete(Unit)
            if (failFirstChunk) error("provider 429")
            if (gateFirstChunk) firstChunkFinish.await()
        }

        suspend fun awaitFirstChunkStarted() = firstChunkStarted.await()

        fun allowFirstChunkToFinish() {
            firstChunkFinish.complete(Unit)
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
