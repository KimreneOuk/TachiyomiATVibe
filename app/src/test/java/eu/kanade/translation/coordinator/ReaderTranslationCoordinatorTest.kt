package eu.kanade.translation.coordinator

import eu.kanade.translation.engines.translator.TextTranslator
import eu.kanade.translation.engines.vision.ocr.PageRecognitionEngine
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.processing.TranslationProcessor
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class ReaderTranslationCoordinatorTest {

    private val processor: TranslationProcessor = mockk(relaxed = true)
    private val recognitionEngine: PageRecognitionEngine = mockk(relaxed = true)
    private val translator: TextTranslator = mockk(relaxed = true)
    private var isBatchActive = false

    private var testScope: CoroutineScope? = null
    private var coordinator: ReaderTranslationCoordinator? = null

    private fun createCoordinator(autoStart: Boolean = false): ReaderTranslationCoordinator {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        testScope = scope
        return ReaderTranslationCoordinator(
            processor = processor,
            recognitionEngineProvider = { recognitionEngine },
            translatorProvider = { translator },
            isBatchActiveForChapter = { isBatchActive },
            scope = scope,
            autoStart = autoStart,
        ).also { coordinator = it }
    }

    @AfterEach
    fun tearDown() {
        coordinator?.close()
        testScope?.cancel()
    }

    @Test
    fun `enqueuePage enqueues manual request and preempts auto`() = runTest {
        val coord = createCoordinator()
        coord.setAutoEnabled("ch1", true)
        val store: ChapterTranslationStore = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(emptyMap())
        }

        val autoReq = ReaderTranslationCoordinator.PageRequest(
            chapterKey = "ch1",
            pageKey = "p1.jpg",
            isManual = false,
            streamFn = { ByteArrayInputStream(ByteArray(0)) },
            store = store,
        )
        val manualReq = ReaderTranslationCoordinator.PageRequest(
            chapterKey = "ch1",
            pageKey = "p2.jpg",
            isManual = true,
            streamFn = { ByteArrayInputStream(ByteArray(0)) },
            store = store,
        )

        coord.enqueuePage(autoReq) shouldBe true
        coord.enqueuePage(manualReq) shouldBe true
        coord.pendingCount.value shouldBe 2
    }

    @Test
    fun `enqueuePage rejects duplicate in-flight page`() = runTest {
        val coord = createCoordinator()
        val store: ChapterTranslationStore = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(emptyMap())
        }

        val req = ReaderTranslationCoordinator.PageRequest(
            chapterKey = "ch1",
            pageKey = "p1.jpg",
            isManual = true,
            streamFn = { ByteArrayInputStream(ByteArray(0)) },
            store = store,
        )

        coord.enqueuePage(req) shouldBe true
        coord.enqueuePage(req) shouldBe true
    }

    @Test
    fun `updateViewportWindow trims distant auto pages`() = runTest {
        val coord = createCoordinator()
        coord.setAutoEnabled("ch1", true)
        val store: ChapterTranslationStore = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(emptyMap())
        }

        val req1 = ReaderTranslationCoordinator.PageRequest(
            chapterKey = "ch1",
            pageKey = "p1.jpg",
            isManual = false,
            streamFn = { ByteArrayInputStream(ByteArray(0)) },
            store = store,
        )
        val req2 = ReaderTranslationCoordinator.PageRequest(
            chapterKey = "ch1",
            pageKey = "p2.jpg",
            isManual = false,
            streamFn = { ByteArrayInputStream(ByteArray(0)) },
            store = store,
        )

        coord.enqueuePage(req1)
        coord.enqueuePage(req2)
        coord.pendingCount.value shouldBe 2

        coord.updateViewportWindow("ch1", setOf("p1.jpg"))
        coord.pendingCount.value shouldBe 1
    }

    @Test
    fun `coordinator yields when batch is active`() = runTest {
        isBatchActive = true
        val coord = createCoordinator(autoStart = true)
        val store: ChapterTranslationStore = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(emptyMap())
        }

        val req = ReaderTranslationCoordinator.PageRequest(
            chapterKey = "ch1",
            pageKey = "p1.jpg",
            isManual = true,
            streamFn = { ByteArrayInputStream(ByteArray(0)) },
            store = store,
        )

        coord.enqueuePage(req)

        coVerify(exactly = 0) { processor.processSinglePage(any(), any(), any(), any(), any(), any()) }
    }
}
