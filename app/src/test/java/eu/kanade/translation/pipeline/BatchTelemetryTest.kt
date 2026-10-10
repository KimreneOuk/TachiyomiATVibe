package eu.kanade.translation.pipeline

import com.hippo.unifile.FakeUniFile
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.diagnostics.TelemetryTrace
import eu.kanade.translation.engines.translator.GeminiInputAccountingContract
import eu.kanade.translation.engines.translator.OpenRouterInputAccountingContract
import eu.kanade.translation.engines.translator.ProviderHttpResult
import eu.kanade.translation.engines.translator.ProviderRequestGovernor
import eu.kanade.translation.engines.translator.TranslatorComputeClass
import eu.kanade.translation.engines.translator.contextual.ContextualRequestProtocol
import eu.kanade.translation.engines.translator.contextual.TranslationContextChunk
import eu.kanade.translation.engines.translator.providers.GeminiTranslator
import eu.kanade.translation.engines.translator.providers.OpenRouterTranslator
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.LeaseAcquisition
import eu.kanade.translation.persistence.chapter.OcrStagePatch
import eu.kanade.translation.persistence.chapter.PageWriteOrigin
import eu.kanade.translation.persistence.chapter.StagePatchResult
import eu.kanade.translation.persistence.chapter.ocrBlockFingerprints
import eu.kanade.translation.pipeline.batch.BatchChapterTranslator
import eu.kanade.translation.pipeline.batch.BatchPass1Status
import eu.kanade.translation.pipeline.batch.ChapterProfileBatchCoordinator
import eu.kanade.translation.pipeline.batch.NativeLaneRunner
import eu.kanade.translation.pipeline.batch.NativeLaneWorker
import eu.kanade.translation.pipeline.batch.OcrReadyPageRef
import eu.kanade.translation.pipeline.batch.PageKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.GeminiThinkingMode
import tachiyomi.domain.translation.TranslationPreferences
import java.io.File

class BatchTelemetryTest {

    private val capturedLines = mutableListOf<String>()

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        capturedLines.clear()
        TelemetryTrace.setTestSink { capturedLines.add(it) }
        TelemetryTrace.enabled = true
    }

    @AfterEach
    fun tearDown() {
        TelemetryTrace.setTestSink(null)
    }

    // -------------------------------------------------------------------------
    // 1. Per-page batch progress telemetry (page_step) without 5-page suppression
    // -------------------------------------------------------------------------

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
        inpaintMaskBoxes = listOf(eu.kanade.translation.model.InpaintMaskBox(0, 0, 10, 10, 1)),
    )

    private fun root(): UniFile = FakeUniFile(parent = null, backing = tempDir)

    private fun lazyStore(): ChapterTranslationStore = ChapterTranslationStore.lazy(
        artifactParentResolver = { root().createFile("Chapter 1.json")!! },
        artifactParent = root(),
        artifactFileName = "Chapter 1.json",
    )

    private fun orderedPages(vararg pageKeys: String): List<PageKey> =
        pageKeys.mapIndexed { index, pageKey -> pageKey to index }

    private fun sourcePairs(pages: List<PageKey>): List<Pair<String, String>> =
        pages.map { (pageKey, _) -> pageKey to hex64("source-$pageKey") }

    private inner class FakeWorker(private val store: ChapterTranslationStore) : NativeLaneWorker {
        var skipPage: String? = null

        override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? {
            if (pageKey == skipPage) return null
            val lease = store.tryAcquirePageStageLease(pageKey, PageStage.Ocr, PageWriteOrigin.BATCH)
                .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
            val before = store.snapshot(pageKey)
            val currentSha = hex64("source-$pageKey")
            val resultPage = ocrPage(pageKey, "text-$pageKey").apply {
                sourceFingerprint = currentSha
                detectionFingerprint = hex64("detection-$currentSha")
                ocrFingerprint = hex64("ocr-$currentSha")
            }
            store.mergeOcr(
                OcrStagePatch(
                    pageKey = pageKey,
                    generation = before.generation,
                    expectedPageVersion = before.pageVersion,
                    expectedPriorOcrFingerprints = before.page?.ocrBlockFingerprints().orEmpty(),
                    ocrResult = resultPage,
                    expectedLeaseToken = lease.token,
                ),
                description = "batch telemetry test ocr",
            ).shouldBeInstanceOf<StagePatchResult.Accepted>()
            val after = store.snapshot(pageKey)
            return OcrReadyPageRef(
                pageKey = pageKey,
                pageIndex = pageIndex,
                generation = after.generation,
                blockFingerprints = emptyList(),
                leaseToken = lease.token,
                candidateGenerationId = after.candidateGenerationId,
                artifactPageVersion = after.artifactPageVersion,
                dependencyFingerprint = after.dependencyFingerprint,
            )
        }

        override suspend fun runInpaintStage(pageKey: String) {}

        override fun releaseNativeHandoff(ref: OcrReadyPageRef) {}
    }

    @Test
    fun `preflight emits page_step on every page without 5-page diagnostic suppression`() = runTest {
        val store = lazyStore()
        store.preRegisterPages(listOf("p1", "p2", "p3"))
        val worker = FakeWorker(store)
        val pages = orderedPages("p1", "p2", "p3")

        val coordinator = ChapterProfileBatchCoordinator(
            store = store,
            nativeWorker = worker,
            frozenConfig = ChapterProfileBatchCoordinator.frozenRunConfig(
                sourceLang = "ja",
                targetLang = "en",
                ocrEngine = "FakeOcrEngine",
                inpaintMode = "OFF",
                providerKey = "fake:provider",
            ),
            orderedSourcePairs = sourcePairs(pages),
            releaseBatchLease = { pageKey -> store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
        )

        val outcome = coordinator.runPass1(pages, TranslatorComputeClass.REMOTE_IO)
        outcome.status shouldBe BatchPass1Status.PAUSED

        val pageStepEvents = capturedLines.filter { it.contains("domain=batch") && it.contains("event=page_step") }
        // Exactly 3 events: one for every page (0, 1, 2) without suppression on page 1
        pageStepEvents.size shouldBe 3

        val step0 = pageStepEvents[0]
        step0 shouldContain "domain=batch"
        step0 shouldContain "event=page_step"
        step0 shouldContain "pageIndex=0"
        step0 shouldContain "pageKey=p1"
        step0 shouldContain "stage=OCR_DONE"
        step0 shouldContain "progressPercent=33.3"
        step0 shouldContain "stageMs="

        val step1 = pageStepEvents[1]
        step1 shouldContain "domain=batch"
        step1 shouldContain "event=page_step"
        step1 shouldContain "pageIndex=1"
        step1 shouldContain "pageKey=p2"
        step1 shouldContain "stage=OCR_DONE"
        step1 shouldContain "progressPercent=66.7"
        step1 shouldContain "stageMs="

        val step2 = pageStepEvents[2]
        step2 shouldContain "domain=batch"
        step2 shouldContain "event=page_step"
        step2 shouldContain "pageIndex=2"
        step2 shouldContain "pageKey=p3"
        step2 shouldContain "stage=OCR_DONE"
        step2 shouldContain "progressPercent=100.0"
        step2 shouldContain "stageMs="
    }

    @Test
    fun `preflight emits CHECKPOINT_REUSED and OCR_SKIPPED page_step events`() = runTest {
        val store = lazyStore()
        store.preRegisterPages(listOf("p1", "p2"))
        val worker = FakeWorker(store)
        val pages = orderedPages("p1", "p2")

        val coordinator = ChapterProfileBatchCoordinator(
            store = store,
            nativeWorker = worker,
            frozenConfig = ChapterProfileBatchCoordinator.frozenRunConfig(
                sourceLang = "ja",
                targetLang = "en",
                ocrEngine = "FakeOcrEngine",
                inpaintMode = "OFF",
                providerKey = "fake:provider",
            ),
            orderedSourcePairs = sourcePairs(pages),
            releaseBatchLease = { pageKey -> store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
        )

        // Pass 1: Fresh OCR
        coordinator.runPass1(pages, TranslatorComputeClass.REMOTE_IO)
        capturedLines.clear()

        // Pass 2: Reused checkpoints
        coordinator.runPass1(pages, TranslatorComputeClass.REMOTE_IO)
        val reusedEvents = capturedLines.filter { it.contains("domain=batch") && it.contains("event=page_step") }
        reusedEvents.size shouldBe 2
        reusedEvents[0] shouldContain "stage=CHECKPOINT_REUSED"
        reusedEvents[0] shouldContain "pageIndex=0"
        reusedEvents[1] shouldContain "stage=CHECKPOINT_REUSED"
        reusedEvents[1] shouldContain "pageIndex=1"

        // Pass with a skipped page
        capturedLines.clear()
        val store2 = lazyStore()
        store2.preRegisterPages(listOf("skip1"))
        val skipPages = orderedPages("skip1")
        val skipWorker = FakeWorker(store2).apply { skipPage = "skip1" }
        val skipCoordinator = ChapterProfileBatchCoordinator(
            store = store2,
            nativeWorker = skipWorker,
            frozenConfig = ChapterProfileBatchCoordinator.frozenRunConfig(
                sourceLang = "ja",
                targetLang = "en",
                ocrEngine = "FakeOcrEngine",
                inpaintMode = "OFF",
                providerKey = "fake:provider",
            ),
            orderedSourcePairs = sourcePairs(skipPages),
            releaseBatchLease = { pageKey -> store2.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
        )
        skipCoordinator.runPass1(skipPages, TranslatorComputeClass.REMOTE_IO)
        val skippedEvents = capturedLines.filter { it.contains("domain=batch") && it.contains("event=page_step") }
        skippedEvents.size shouldBe 1
        skippedEvents[0] shouldContain "stage=OCR_SKIPPED"
        skippedEvents[0] shouldContain "pageIndex=0"
    }

    // -------------------------------------------------------------------------
    // 2. AI Envelope Transit breakdown (ai_envelope_transit)
    // -------------------------------------------------------------------------

    private fun testChunk(): TranslationContextChunk {
        val page = PageTranslation(
            sourceFileName = "page_1.jpg",
            blocks = mutableListOf(block("Hello")),
        )
        return TranslationContextChunk(
            pages = linkedMapOf("page_1.jpg" to page),
            blockCount = 1,
            rollingContext = "",
            estimatedPromptTokens = 10,
            maxOutputTokens = 100,
            protocol = ContextualRequestProtocol.BATCH_V1,
            pageIndexes = mapOf("page_1.jpg" to 0),
        )
    }

    @Test
    fun `OpenAiCompatibleTranslator emits ai_envelope_transit decomposition`() = runTest {
        val openAiResponseBody = "data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"translations\\\":[{\\\"id\\\":\\\"b1\\\",\\\"translation\\\":\\\"Bonjour\\\"}]}\"}}]}\n\ndata: [DONE]\n\n"
        val rawProviderResponseClass = Class.forName("eu.kanade.translation.engines.translator.providers.OpenAiCompatibleTranslator\$RawProviderResponse")
        val constructor = rawProviderResponseClass.getDeclaredConstructor(
            Int::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
        )
        constructor.isAccessible = true
        val fakeResponse = constructor.newInstance(200, null, openAiResponseBody)

        val governor = mockk<ProviderRequestGovernor>()
        coEvery { governor.executeWithUsage<Any>(any(), any()) } returns ProviderHttpResult(fakeResponse)

        val translator = OpenRouterTranslator(
            fromLang = TextRecognizerLanguage.JAPANESE,
            toLang = TextTranslatorLanguage.ENGLISH,
            apiKey = "test-key",
            modelName = "anthropic/claude-3.5-sonnet",
            maxOutputToken = 100,
            temperature = 0.2f,
            requestGovernor = governor,
            customAccountingContract = OpenRouterInputAccountingContract("anthropic/claude-3.5-sonnet"),
        )

        translator.translateContextualStructured(testChunk())

        val transitEvents = capturedLines.filter {
            it.contains("domain=batch") && it.contains("event=ai_envelope_transit")
        }
        transitEvents.size shouldBe 1
        val event = transitEvents.first()
        event shouldContain "domain=batch"
        event shouldContain "event=ai_envelope_transit"
        event shouldContain "envelopeId="
        event shouldContain "pageCount=1"
        event shouldContain "serializeMs="
        event shouldContain "httpTransitMs="
        event shouldContain "deserializeMs="
        event shouldContain "totalRpcMs="
    }

    @Test
    fun `GeminiTranslator emits ai_envelope_transit decomposition`() = runTest {
        val geminiResponseBody = """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {
                    "text": "{\"translations\":[{\"id\":\"b1\",\"translation\":\"Bonjour\"}]}"
                  }
                ]
              }
            }
          ]
        }
        """.trimIndent()

        val rawGeminiResponseClass = Class.forName("eu.kanade.translation.engines.translator.providers.GeminiTranslator\$RawGeminiResponse")
        val constructor = rawGeminiResponseClass.getDeclaredConstructor(
            Int::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
        )
        constructor.isAccessible = true
        val fakeResponse = constructor.newInstance(200, null, geminiResponseBody)

        val governor = mockk<ProviderRequestGovernor>()
        coEvery { governor.executeWithUsage<Any>(any(), any()) } returns ProviderHttpResult(fakeResponse)

        val translator = GeminiTranslator(
            fromLang = TextRecognizerLanguage.JAPANESE,
            toLang = TextTranslatorLanguage.ENGLISH,
            apiKey = "test-gemini-key",
            modelName = "gemini-1.5-flash",
            maxOutputToken = 100,
            temp = 0.2f,
            thinkingMode = GeminiThinkingMode.DISABLED,
            requestGovernor = governor,
            customAccountingContract = GeminiInputAccountingContract("gemini-1.5-flash"),
        )

        translator.translateContextualStructured(testChunk())

        val transitEvents = capturedLines.filter {
            it.contains("domain=batch") && it.contains("event=ai_envelope_transit")
        }
        transitEvents.size shouldBe 1
        val event = transitEvents.first()
        event shouldContain "domain=batch"
        event shouldContain "event=ai_envelope_transit"
        event shouldContain "envelopeId="
        event shouldContain "pageCount=1"
        event shouldContain "serializeMs="
        event shouldContain "httpTransitMs="
        event shouldContain "deserializeMs="
        event shouldContain "totalRpcMs="
    }

    // -------------------------------------------------------------------------
    // 3. Native Lane Lease Contention telemetry (lease_contention)
    // -------------------------------------------------------------------------

    private fun batchTranslator(nativeLane: NativeLaneRunner): BatchChapterTranslator {
        val prefs = mockk<TranslationPreferences>(relaxed = true) {
            every { translateFromLanguage() } returns mockk { every { get() } returns "ENGLISH" }
            every { translateToLanguage() } returns mockk { every { get() } returns "ENGLISH" }
        }
        return BatchChapterTranslator(
            provider = mockk(relaxed = true),
            translationPreferences = prefs,
            nativeLane = nativeLane,
            engineRebuildMutex = Mutex(),
            ensureEnginesBuiltFor = { _, _ -> },
            recognitionEngineFn = { error("not expected") },
            textTranslatorFn = { error("not expected") },
            computeSourceFingerprintFn = { error("not expected") },
            batchExpectedFingerprintsFn = { _, _ -> error("not expected") },
            inpaintingModeFromPref = { error("not expected") },
            inpaintingStampDecision = { error("not expected") },
            releaseBatchPageLease = { _, _ -> error("not expected") },
            persistPageWithOomRecovery = { _, _, _, _ -> error("not expected") },
            loadPersistedCleanedBitmap = { _, _, _, _ -> error("not expected") },
            deleteRetiredCleanedFile = { _, _, _, _, _ -> error("not expected") },
            markPageTimedOut = { _, _, _, _ -> error("not expected") },
            analyzePage = { _, _, _, _, _ -> error("not expected") },
            decodePageBitmapForTranslation = { _, _ -> error("not expected") },
            preflightInpaintGate = { _, _ -> error("not expected") },
            inpaintPage = { _, _, _, _, _ -> error("not expected") },
            retryInpaintDownscaled = { _, _, _, _, _, _, _ -> error("not expected") },
            persistCleanedBitmap = { _, _, _, _, _, _, _, _, _, _ -> error("not expected") },
            updatePageFromCurrentSnapshotFn = { _, _, _, _ -> error("not expected") },
            onBatchClosedFn = { null },
        )
    }

    @Test
    fun `BatchChapterTranslator emits lease_contention with acquired=true when lane acquired`() = runTest {
        val store = ChapterTranslationStore()
        val chapter = mockk<Chapter> {
            every { id } returns 42L
            every { name } returns "Chapter 42"
        }
        val translator = batchTranslator(
            nativeLane = object : NativeLaneRunner {
                override suspend fun <T> run(
                    timeoutMs: Long,
                    chapterId: Long?,
                    chapterName: String,
                    pageKey: String,
                    onTimeout: suspend () -> Unit,
                    block: suspend () -> T,
                ): T? {
                    delay(10)
                    return block()
                }
            },
        )

        runCatching {
            translator.translateBatch(
                manga = mockk<Manga>(relaxed = true),
                chapter = chapter,
                source = mockk<HttpSource>(relaxed = true),
                store = store,
                orderedStreams = listOf("p1" to { "".byteInputStream() }),
            )
        }

        val contentionEvents = capturedLines.filter {
            it.contains("domain=batch") && it.contains("event=lease_contention")
        }
        contentionEvents.size shouldBe 1
        val event = contentionEvents.first()
        event shouldContain "domain=batch"
        event shouldContain "event=lease_contention"
        event shouldContain "chapterId=42"
        event shouldContain "waitMs="
        event shouldContain "acquired=true"
    }

    @Test
    fun `BatchChapterTranslator emits lease_contention with acquired=false on timeout`() = runTest {
        val store = ChapterTranslationStore()
        val chapter = mockk<Chapter> {
            every { id } returns 99L
            every { name } returns "Chapter 99"
        }
        val translator = batchTranslator(
            nativeLane = object : NativeLaneRunner {
                override suspend fun <T> run(
                    timeoutMs: Long,
                    chapterId: Long?,
                    chapterName: String,
                    pageKey: String,
                    onTimeout: suspend () -> Unit,
                    block: suspend () -> T,
                ): T? {
                    delay(5)
                    onTimeout()
                    return null
                }
            },
        )

        translator.translateBatch(
            manga = mockk<Manga>(relaxed = true),
            chapter = chapter,
            source = mockk<HttpSource>(relaxed = true),
            store = store,
            orderedStreams = listOf("p1" to { "".byteInputStream() }),
        )

        val contentionEvents = capturedLines.filter {
            it.contains("domain=batch") && it.contains("event=lease_contention")
        }
        contentionEvents.size shouldBe 1
        val event = contentionEvents.first()
        event shouldContain "domain=batch"
        event shouldContain "event=lease_contention"
        event shouldContain "chapterId=99"
        event shouldContain "waitMs="
        event shouldContain "acquired=false"
    }
}
