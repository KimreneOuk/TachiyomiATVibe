package eu.kanade.translation.processing

import android.content.Context
import android.graphics.Bitmap
import eu.kanade.translation.engines.translator.TextTranslator
import eu.kanade.translation.engines.vision.ocr.PageRecognitionEngine
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TranslationProcessorTest {

    private val context: Context = mockk(relaxed = true)
    private val processor = TranslationProcessor(context)

    private fun createBlock(text: String, translation: String = "") = TranslationBlock(
        text = text,
        translation = translation,
        width = 100f,
        height = 50f,
        x = 10f,
        y = 10f,
        symHeight = 50f,
        symWidth = 100f,
        angle = 0f,
    )

    @Test
    fun `analyze marks textless page as complete across all stages`() = runTest {
        val bitmap: Bitmap = mockk(relaxed = true) {
            every { width } returns 800
            every { height } returns 1200
        }
        val emptyPage = PageTranslation(blocks = mutableListOf())
        val engine: PageRecognitionEngine = mockk {
            coEvery { analyze(bitmap) } returns emptyPage
        }

        val result = processor.analyze("page_001.jpg", bitmap, engine)

        result.ocrStatus shouldBe StageStatus.READY
        result.translationStatus shouldBe StageStatus.READY
        result.inpaintStatus shouldBe StageStatus.READY
        result.renderStatus shouldBe StageStatus.READY
        result.sourceFileName shouldBe "page_001.jpg"
    }

    @Test
    fun `analyze marks page with blocks pending for translation and inpaint`() = runTest {
        val bitmap: Bitmap = mockk(relaxed = true) {
            every { width } returns 800
            every { height } returns 1200
        }
        val block = createBlock("こんにちは")
        val page = PageTranslation(blocks = mutableListOf(block))
        val engine: PageRecognitionEngine = mockk {
            coEvery { analyze(bitmap) } returns page
        }

        val result = processor.analyze("page_001.jpg", bitmap, engine)

        result.ocrStatus shouldBe StageStatus.READY
        result.translationStatus shouldBe StageStatus.PENDING
        result.inpaintStatus shouldBe StageStatus.PENDING
        result.blocks.size shouldBe 1
    }

    @Test
    fun `translatePage translates block and marks ready`() = runTest {
        val block = createBlock("こんにちは")
        val page = PageTranslation(
            sourceFileName = "page_001.jpg",
            blocks = mutableListOf(block),
            ocrStatus = StageStatus.READY,
        )
        val translator: TextTranslator = mockk {
            coEvery { translatePage("page_001.jpg", page) } answers {
                block.translation = "Hello"
            }
        }

        processor.translatePage("page_001.jpg", page, translator)

        page.translationStatus shouldBe StageStatus.READY
        block.translation shouldBe "Hello"
        coVerify(exactly = 1) { translator.translatePage("page_001.jpg", page) }
    }

    @Test
    fun `translateBatch translates batch of pages`() = runTest {
        val page1 = PageTranslation(
            sourceFileName = "page_001.jpg",
            blocks = mutableListOf(createBlock("A")),
        )
        val page2 = PageTranslation(
            sourceFileName = "page_002.jpg",
            blocks = mutableListOf(createBlock("B")),
        )
        val map = linkedMapOf("page_001.jpg" to page1, "page_002.jpg" to page2)
        val translator: TextTranslator = mockk {
            coEvery { translate(any()) } answers {
                page1.blocks[0].translation = "A_trans"
                page2.blocks[0].translation = "B_trans"
            }
        }

        processor.translateBatch(map, translator)

        page1.translationStatus shouldBe StageStatus.READY
        page2.translationStatus shouldBe StageStatus.READY
        page1.blocks[0].translation shouldBe "A_trans"
        page2.blocks[0].translation shouldBe "B_trans"
    }

    @Test
    fun `inpaint updates page cleaned bitmap and ready status`() = runTest {
        val bitmap: Bitmap = mockk(relaxed = true)
        val cleanedBitmap: Bitmap = mockk(relaxed = true)
        val page = PageTranslation(
            sourceFileName = "page_001.jpg",
            blocks = mutableListOf(createBlock("A")),
        )
        val engine: PageRecognitionEngine = mockk {
            coEvery { inpaint(bitmap, page) } returns cleanedBitmap
        }

        val result = processor.inpaint("page_001.jpg", bitmap, page, engine)

        result shouldBe cleanedBitmap
        page.cleanedBitmap shouldBe cleanedBitmap
        page.inpaintStatus shouldBe StageStatus.READY
    }

    @Test
    fun `inpaint fallback on null output marks ready and original fallback`() = runTest {
        val bitmap: Bitmap = mockk(relaxed = true)
        val page = PageTranslation(
            sourceFileName = "page_001.jpg",
            blocks = mutableListOf(createBlock("A")),
        )
        val engine: PageRecognitionEngine = mockk {
            coEvery { inpaint(bitmap, page) } returns null
        }

        val result = processor.inpaint("page_001.jpg", bitmap, page, engine)

        result shouldBe null
        page.cleanedBitmap shouldBe null
        page.inpaintStatus shouldBe StageStatus.READY
        page.originalImageFallback shouldBe true
    }

    @Test
    fun `inpaint on OOM reclaims native memory and sets fallback`() = runTest {
        val bitmap: Bitmap = mockk(relaxed = true)
        val page = PageTranslation(
            sourceFileName = "page_001.jpg",
            blocks = mutableListOf(createBlock("A")),
        )
        val engine: PageRecognitionEngine = mockk(relaxed = true) {
            coEvery { inpaint(bitmap, page) } throws OutOfMemoryError("Fake native OOM")
        }

        val result = processor.inpaint("page_001.jpg", bitmap, page, engine)

        result shouldBe null
        page.inpaintStatus shouldBe StageStatus.FAILED
        page.originalImageFallback shouldBe true
        verify(exactly = 1) { engine.reclaimPooledMemory() }
        verify(exactly = 1) { engine.forceReleaseNativeBuffers() }
    }
}
