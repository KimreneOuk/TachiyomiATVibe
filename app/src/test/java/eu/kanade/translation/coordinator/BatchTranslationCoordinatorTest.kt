package eu.kanade.translation.coordinator

import android.graphics.Bitmap
import eu.kanade.translation.engines.translator.TextTranslator
import eu.kanade.translation.engines.vision.ocr.PageRecognitionEngine
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PublishedPageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.toPublishedPage
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.pipeline.DecodedPage
import eu.kanade.translation.processing.TranslationProcessor
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class BatchTranslationCoordinatorTest {

    private val processor: TranslationProcessor = mockk(relaxed = true)
    private val recognitionEngine: PageRecognitionEngine = mockk(relaxed = true)
    private val translator: TextTranslator = mockk(relaxed = true)

    private val coordinator = BatchTranslationCoordinator(
        processor = processor,
        recognitionEngineProvider = { recognitionEngine },
        translatorProvider = { translator },
    )

    @AfterEach
    fun tearDown() {
        coordinator.close()
    }

    private fun createBlock(text: String) = TranslationBlock(
        text = text,
        translation = "",
        width = 100f,
        height = 50f,
        x = 10f,
        y = 10f,
        symHeight = 50f,
        symWidth = 100f,
        angle = 0f,
    )

    @Test
    fun `translateChapter skips translation-ready page without display readiness`() = runTest {
        val completedPage = PageTranslation(
            sourceFileName = "p1.jpg",
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.READY,
            inpaintStatus = StageStatus.FAILED,
            blocks = mutableListOf(createBlock("Done").apply { translation = "Translated" }),
        )

        val stateMap = mapOf("p1.jpg" to completedPage.toPublishedPage())
        val store: ChapterTranslationStore = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(stateMap)
        }

        val progress = coordinator.translateChapter(
            chapterKey = "ch1",
            orderedPageKeys = listOf("p1.jpg"),
            streamProvider = { ByteArrayInputStream(ByteArray(0)) },
            store = store,
        )

        progress.isFinished shouldBe true
        progress.completedPages shouldBe 1
        progress.totalPages shouldBe 1
        // Since p1.jpg was already complete, analyze is never called!
        coVerify(exactly = 0) { processor.analyze(any(), any(), any()) }
    }

    @Test
    fun `translateChapter runs OCR barrier and translates uncompleted pages`() = runTest {
        val store: ChapterTranslationStore = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(emptyMap())
        }

        val mockBitmap: Bitmap = mockk(relaxed = true)
        val decoded = DecodedPage(
            bitmap = mockBitmap,
            sampleSize = 1,
            originalWidth = 800,
            originalHeight = 1200,
            decodeDecision = mockk(relaxed = true),
            sourceBytesSize = 1000L,
            sourceFingerprint = "sha123",
        )

        coEvery { processor.decodeImage("p1.jpg", any(), any()) } returns decoded

        val pageWithBlock = PageTranslation(
            sourceFileName = "p1.jpg",
            blocks = mutableListOf(createBlock("Hello")),
            ocrStatus = StageStatus.READY,
        )
        coEvery { processor.analyze("p1.jpg", mockBitmap, recognitionEngine) } returns pageWithBlock

        val progress = coordinator.translateChapter(
            chapterKey = "ch1",
            orderedPageKeys = listOf("p1.jpg"),
            streamProvider = { ByteArrayInputStream(ByteArray(0)) },
            store = store,
            inpaintingEnabled = false,
        )

        progress.isFinished shouldBe true
        progress.completedPages shouldBe 1
        progress.totalPages shouldBe 1
        coVerify(exactly = 1) { processor.analyze("p1.jpg", mockBitmap, recognitionEngine) }
        coVerify(exactly = 1) { processor.translateBatch(any(), translator) }
    }
}
