package eu.kanade.translation.batch

import eu.kanade.translation.translator.TranslatorComputeClass
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap

class ChunkBatchCoordinatorTest {

    @Test
    fun `partitions pages into chunks using DynamicPageChunker and executes all chunks`() = runTest {
        val events = RecordingListener()
        val processedOcrPages = ConcurrentHashMap.newKeySet<String>()
        val renderedPages = ConcurrentHashMap.newKeySet<String>()

        val nativeWorker = object : NativeLaneWorker {
            override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef {
                processedOcrPages += pageKey
                return OcrReadyPageRef(pageKey, pageIndex, 1L, emptyList())
            }

            override suspend fun runInpaintStage(pageKey: String) {}
        }

        val translatorWorker = object : TranslatorLaneWorker {
            override suspend fun translate(ref: OcrReadyPageRef) {}
        }

        val renderJoin = object : RenderJoinWorker {
            override fun onNativeBranchDone(pageKey: String) {}
            override fun onTranslationBranchDone(pageKey: String) {}
            override suspend fun awaitAndRender(pageKey: String) {
                renderedPages += pageKey
            }
        }

        val coordinator = ChunkBatchCoordinator(nativeWorker, translatorWorker, renderJoin, listener = events)
        val pages = (1..12).map { "page_$it.jpg" to (it - 1) }

        // DynamicPageChunker for 12 pages creates 3 chunks of size 5, 5, 2
        val outcome = coordinator.runChunkedBatch(pages, TranslatorComputeClass.REMOTE_IO)

        outcome.totalChunks shouldBe 3
        outcome.completedChunks shouldBe 3
        outcome.processedPages.size shouldBe 12
        processedOcrPages.size shouldBe 12
        renderedPages.size shouldBe 12
    }

    @Test
    fun `density clamp partitions chunks according to bubble threshold`() = runTest {
        val processedChunks = mutableListOf<List<String>>()

        val nativeWorker = object : NativeLaneWorker {
            override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef {
                return OcrReadyPageRef(pageKey, pageIndex, 1L, emptyList())
            }

            override suspend fun runInpaintStage(pageKey: String) {}
        }

        val chunkTranslator = object : ChunkTranslatorLaneWorker {
            override suspend fun translateChunk(
                chunk: PageChunk,
                refs: List<OcrReadyPageRef>,
                rollingContext: RollingContextPacket,
            ): RollingContextPacket? {
                synchronized(processedChunks) {
                    processedChunks.add(chunk.pageKeys)
                }
                return null
            }
        }

        val renderJoin = object : RenderJoinWorker {
            override fun onNativeBranchDone(pageKey: String) {}
            override fun onTranslationBranchDone(pageKey: String) {}
            override suspend fun awaitAndRender(pageKey: String) {}
        }

        val coordinator = ChunkBatchCoordinator(nativeWorker, chunkTranslator, renderJoin)
        val pages = (1..6).map { "p$it" to (it - 1) }
        val bubbleCounts = mapOf(
            "p1" to 20,
            "p2" to 20, // 40 >= 35 -> chunk 1: [p1, p2]
            "p3" to 5,
            "p4" to 5,
            "p5" to 5,
            "p6" to 5,
        )

        val outcome = coordinator.runChunkedBatch(pages, bubbleCounts = bubbleCounts)

        outcome.totalChunks shouldBe 2
        processedChunks.size shouldBe 2
        processedChunks[0] shouldBe listOf("p1", "p2")
        processedChunks[1] shouldBe listOf("p3", "p4", "p5", "p6")
    }

    @Test
    fun `chunk execution progresses strictly through Phase 1 to Phase 4 in order per chunk`() = runTest {
        val events = RecordingListener()
        val nativeWorker = object : NativeLaneWorker {
            override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef {
                events.add("ocr:$pageKey")
                return OcrReadyPageRef(pageKey, pageIndex, 1L, emptyList())
            }

            override suspend fun runInpaintStage(pageKey: String) {
                events.add("inpaint:$pageKey")
            }
        }

        val translatorWorker = object : TranslatorLaneWorker {
            override suspend fun translate(ref: OcrReadyPageRef) {
                events.add("translate:${ref.pageKey}")
            }
        }

        val renderJoin = object : RenderJoinWorker {
            override fun onNativeBranchDone(pageKey: String) {}
            override fun onTranslationBranchDone(pageKey: String) {}
            override suspend fun awaitAndRender(pageKey: String) {
                events.add("render:$pageKey")
            }
        }

        val coordinator = ChunkBatchCoordinator(nativeWorker, translatorWorker, renderJoin, listener = events)
        // 2 pages in 1 chunk
        val pages = listOf("p0" to 0, "p1" to 1)
        coordinator.runChunkedBatch(pages, TranslatorComputeClass.REMOTE_IO)

        val log = events.log

        // Phase 1 (OCR) for all pages before Phase 2 (Inpaint / Translate)
        val ocrP0 = log.indexOf("ocr:p0")
        val ocrP1 = log.indexOf("ocr:p1")
        val inpaintP0 = log.indexOf("inpaint:p0")
        val inpaintP1 = log.indexOf("inpaint:p1")
        val transP0 = log.indexOf("translate:p0")
        val transP1 = log.indexOf("translate:p1")
        val renderP0 = log.indexOf("render:p0")
        val renderP1 = log.indexOf("render:p1")

        (ocrP0 < inpaintP0) shouldBe true
        (ocrP1 < inpaintP0) shouldBe true
        (ocrP0 < transP0) shouldBe true
        (ocrP1 < transP1) shouldBe true

        // Phase 2 (Inpaint + Translate) before Phase 3 (Render)
        (inpaintP0 < renderP0) shouldBe true
        (inpaintP1 < renderP1) shouldBe true
        (transP0 < renderP0) shouldBe true
        (transP1 < renderP1) shouldBe true
    }

    @Test
    fun `sequential chunks enforce previous chunk render finishes before next chunk OCR starts`() = runTest {
        val events = RecordingListener()
        val nativeWorker = object : NativeLaneWorker {
            override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef {
                events.add("ocr:$pageKey")
                return OcrReadyPageRef(pageKey, pageIndex, 1L, emptyList())
            }

            override suspend fun runInpaintStage(pageKey: String) {
                events.add("inpaint:$pageKey")
            }
        }

        val translatorWorker = object : TranslatorLaneWorker {
            override suspend fun translate(ref: OcrReadyPageRef) {
                events.add("translate:${ref.pageKey}")
            }
        }

        val renderJoin = object : RenderJoinWorker {
            override fun onNativeBranchDone(pageKey: String) {}
            override fun onTranslationBranchDone(pageKey: String) {}
            override suspend fun awaitAndRender(pageKey: String) {
                events.add("render:$pageKey")
            }
        }

        val coordinator = ChunkBatchCoordinator(nativeWorker, translatorWorker, renderJoin, listener = events)
        val pages = listOf("p0" to 0, "p1" to 1)
        // Force 2 chunks using bubble density (threshold 35)
        val bubbleCounts = mapOf("p0" to 36, "p1" to 36)
        coordinator.runChunkedBatch(pages, TranslatorComputeClass.REMOTE_IO, bubbleCounts)

        val log = events.log
        val renderP0 = log.indexOf("render:p0")
        val ocrP1 = log.indexOf("ocr:p1")

        // Chunk 0 render must finish before Chunk 1 OCR begins
        (renderP0 < ocrP1) shouldBe true
    }

    @Test
    fun `Phase 2 concurrently executes native inpainting and translation lane`() = runTest {
        val events = RecordingListener()
        val native = GatedNativeWorker()
        val translator = GatedTranslatorWorker()
        val renderJoin = RecordingRenderJoin()

        val coordinator = ChunkBatchCoordinator(native, translator, renderJoin, listener = events)
        val pages = listOf("p0" to 0)

        val batchJob = async { coordinator.runChunkedBatch(pages, TranslatorComputeClass.REMOTE_IO) }

        // Wait for OCR
        native.allowOcrToFinish("p0")

        // Wait for both inpaint and translation to start concurrently
        native.awaitInpaintStarted("p0")
        translator.awaitTranslationStarted("p0")

        // While both are in-flight, verify render has NOT started
        renderJoin.renderedPages.isEmpty() shouldBe true

        // Complete inpainting first
        native.allowInpaintToFinish("p0")
        renderJoin.renderedPages.isEmpty() shouldBe true

        // Complete translation
        translator.allowTranslationToFinish("p0")

        batchJob.await()
        renderJoin.renderedPages shouldContainExactly setOf("p0")
    }

    @Test
    fun `Phase 4 propagates updated rolling context across sequential chunks`() = runTest {
        val receivedContexts = mutableListOf<RollingContextPacket>()

        val nativeWorker = object : NativeLaneWorker {
            override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef {
                return OcrReadyPageRef(pageKey, pageIndex, 1L, emptyList())
            }

            override suspend fun runInpaintStage(pageKey: String) {}
        }

        val chunkTranslator = object : ChunkTranslatorLaneWorker {
            override suspend fun translateChunk(
                chunk: PageChunk,
                refs: List<OcrReadyPageRef>,
                rollingContext: RollingContextPacket,
            ): RollingContextPacket {
                receivedContexts.add(rollingContext)
                return when (chunk.index) {
                    0 -> RollingContextPacket(
                        glossary = mapOf("Hunter" to "Ranker"),
                        microSummary = "Jinwoo enters dungeon.",
                    )
                    1 -> RollingContextPacket(
                        glossary = mapOf("Shadow" to "Extraction"),
                        microSummary = "Jinwoo extracts shadows.",
                    )
                    else -> RollingContextPacket()
                }
            }
        }

        val renderJoin = object : RenderJoinWorker {
            override fun onNativeBranchDone(pageKey: String) {}
            override fun onTranslationBranchDone(pageKey: String) {}
            override suspend fun awaitAndRender(pageKey: String) {}
        }

        val rollingContextManager = RollingContextManager()
        val coordinator = ChunkBatchCoordinator(
            nativeWorker = nativeWorker,
            translatorWorker = chunkTranslator,
            renderJoin = renderJoin,
            rollingContextManager = rollingContextManager,
        )

        val pages = listOf("p0" to 0, "p1" to 1)
        val bubbleCounts = mapOf("p0" to 36, "p1" to 36) // 2 chunks

        coordinator.runChunkedBatch(pages, TranslatorComputeClass.REMOTE_IO, bubbleCounts)

        receivedContexts.size shouldBe 2
        // Chunk 0 received empty initial context
        receivedContexts[0].glossary shouldBe emptyMap()
        receivedContexts[0].microSummary shouldBe ""

        // Chunk 1 received context updated by Chunk 0
        receivedContexts[1].glossary shouldBe mapOf("Hunter" to "Ranker")
        receivedContexts[1].microSummary shouldBe "Jinwoo enters dungeon."

        // Final manager state accumulated both chunks
        val finalContext = rollingContextManager.getRollingContext()
        finalContext.glossary shouldBe mapOf("Hunter" to "Ranker", "Shadow" to "Extraction")
        finalContext.microSummary shouldBe "Jinwoo extracts shadows."
    }

    @Test
    fun `cancellation during Phase 1 cancels batch cleanly`() = runTest {
        val native = GatedNativeWorker()
        val translator = object : TranslatorLaneWorker {
            override suspend fun translate(ref: OcrReadyPageRef) {}
        }
        val renderJoin = RecordingRenderJoin()

        val coordinator = ChunkBatchCoordinator(native, translator, renderJoin)
        val pages = listOf("p0" to 0, "p1" to 1)

        val job = async { coordinator.runChunkedBatch(pages, TranslatorComputeClass.REMOTE_IO) }

        native.awaitOcrStarted("p0")
        job.cancelAndJoin()

        job.isCancelled shouldBe true
        renderJoin.renderedPages.isEmpty() shouldBe true
    }

    @Test
    fun `cancellation during Phase 2 cancels chunk execution promptly`() = runTest {
        val native = GatedNativeWorker()
        val translator = GatedTranslatorWorker()
        val renderJoin = RecordingRenderJoin()

        val coordinator = ChunkBatchCoordinator(native, translator, renderJoin)
        val pages = listOf("p0" to 0)

        val job = async { coordinator.runChunkedBatch(pages, TranslatorComputeClass.REMOTE_IO) }

        native.allowOcrToFinish("p0")
        native.awaitInpaintStarted("p0")
        translator.awaitTranslationStarted("p0")

        job.cancelAndJoin()

        job.isCancelled shouldBe true
        renderJoin.renderedPages.isEmpty() shouldBe true
    }

    @Test
    fun `partial error resilience continues execution when single page OCR fails`() = runTest {
        val rendered = ConcurrentHashMap.newKeySet<String>()
        val nativeWorker = object : NativeLaneWorker {
            override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? {
                if (pageKey == "p0") throw RuntimeException("OCR native decode failed")
                return OcrReadyPageRef(pageKey, pageIndex, 1L, emptyList())
            }

            override suspend fun runInpaintStage(pageKey: String) {}
        }

        val translatorWorker = object : TranslatorLaneWorker {
            override suspend fun translate(ref: OcrReadyPageRef) {}
        }

        val renderJoin = object : RenderJoinWorker {
            override fun onNativeBranchDone(pageKey: String) {}
            override fun onTranslationBranchDone(pageKey: String) {}
            override suspend fun awaitAndRender(pageKey: String) {
                rendered += pageKey
            }
        }

        val coordinator = ChunkBatchCoordinator(nativeWorker, translatorWorker, renderJoin)
        val pages = listOf("p0" to 0, "p1" to 1)

        val outcome = coordinator.runChunkedBatch(pages, TranslatorComputeClass.REMOTE_IO)

        outcome.completedChunks shouldBe 1
        outcome.needsTranslation shouldBe listOf("p1")
        rendered shouldContainExactly setOf("p0", "p1")
    }

    @Test
    fun `partial error in translation or inpainting allows other pages to finish`() = runTest {
        val rendered = ConcurrentHashMap.newKeySet<String>()
        val nativeWorker = object : NativeLaneWorker {
            override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef {
                return OcrReadyPageRef(pageKey, pageIndex, 1L, emptyList())
            }

            override suspend fun runInpaintStage(pageKey: String) {
                if (pageKey == "p0") throw RuntimeException("Inpainting model failed")
            }
        }

        val translatorWorker = object : TranslatorLaneWorker {
            override suspend fun translate(ref: OcrReadyPageRef) {
                if (ref.pageKey == "p1") throw RuntimeException("Translation network error")
            }
        }

        val renderJoin = object : RenderJoinWorker {
            override fun onNativeBranchDone(pageKey: String) {}
            override fun onTranslationBranchDone(pageKey: String) {}
            override suspend fun awaitAndRender(pageKey: String) {
                rendered += pageKey
            }
        }

        val coordinator = ChunkBatchCoordinator(nativeWorker, translatorWorker, renderJoin)
        val pages = listOf("p0" to 0, "p1" to 1, "p2" to 2)

        val outcome = coordinator.runChunkedBatch(pages, TranslatorComputeClass.REMOTE_IO)

        outcome.completedChunks shouldBe 1
        rendered shouldContainExactly setOf("p0", "p1", "p2")
    }

    private class RecordingListener : BatchScheduleListener() {
        private val _log = mutableListOf<String>()
        val log: List<String> get() = synchronized(_log) { _log.toList() }

        fun add(entry: String) {
            synchronized(_log) {
                _log.add(entry)
            }
        }

        override fun ocrStarted(pageKey: String) = add("ocrStarted:$pageKey")
        override fun ocrFinished(pageKey: String) = add("ocrFinished:$pageKey")
        override fun inpaintStarted(pageKey: String) = add("inpaintStarted:$pageKey")
        override fun inpaintFinished(pageKey: String) = add("inpaintFinished:$pageKey")
        override fun translationRequested(pageKey: String) = add("translationRequested:$pageKey")
        override fun translationFinished(pageKey: String) = add("translationFinished:$pageKey")
        override fun renderStarted(pageKey: String) = add("renderStarted:$pageKey")
        override fun renderFinished(pageKey: String) = add("renderFinished:$pageKey")
    }

    private class GatedNativeWorker : NativeLaneWorker {
        private val ocrStartGates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val ocrFinishGates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val inpaintStartGates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val inpaintFinishGates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef {
            ocrStartGates.getOrPut(pageKey) { CompletableDeferred() }.complete(Unit)
            ocrFinishGates.getOrPut(pageKey) { CompletableDeferred() }.await()
            return OcrReadyPageRef(pageKey, pageIndex, 1L, emptyList())
        }

        override suspend fun runInpaintStage(pageKey: String) {
            inpaintStartGates.getOrPut(pageKey) { CompletableDeferred() }.complete(Unit)
            inpaintFinishGates.getOrPut(pageKey) { CompletableDeferred() }.await()
        }

        suspend fun awaitOcrStarted(pageKey: String) =
            ocrStartGates.getOrPut(pageKey) { CompletableDeferred() }.await()

        fun allowOcrToFinish(pageKey: String) =
            ocrFinishGates.getOrPut(pageKey) { CompletableDeferred() }.complete(Unit)

        suspend fun awaitInpaintStarted(pageKey: String) =
            inpaintStartGates.getOrPut(pageKey) { CompletableDeferred() }.await()

        fun allowInpaintToFinish(pageKey: String) =
            inpaintFinishGates.getOrPut(pageKey) { CompletableDeferred() }.complete(Unit)
    }

    private class GatedTranslatorWorker : TranslatorLaneWorker {
        private val transStartGates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val transFinishGates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        override suspend fun translate(ref: OcrReadyPageRef) {
            transStartGates.getOrPut(ref.pageKey) { CompletableDeferred() }.complete(Unit)
            transFinishGates.getOrPut(ref.pageKey) { CompletableDeferred() }.await()
        }

        suspend fun awaitTranslationStarted(pageKey: String) =
            transStartGates.getOrPut(pageKey) { CompletableDeferred() }.await()

        fun allowTranslationToFinish(pageKey: String) =
            transFinishGates.getOrPut(pageKey) { CompletableDeferred() }.complete(Unit)
    }

    private class RecordingRenderJoin : RenderJoinWorker {
        val renderedPages = ConcurrentHashMap.newKeySet<String>()

        override fun onNativeBranchDone(pageKey: String) {}
        override fun onTranslationBranchDone(pageKey: String) {}
        override suspend fun awaitAndRender(pageKey: String) {
            renderedPages += pageKey
        }
    }
}
