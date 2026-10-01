package eu.kanade.translation.engines.inpainting

import android.graphics.Bitmap
import eu.kanade.translation.engines.inpainting.aot.AOTInpainting
import eu.kanade.translation.model.Detection
import eu.kanade.translation.model.InpaintMaskBox
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class PageInpaintingEngineTest {

    @Test
    fun `saved erase mask is processed after OCR blocks disappear`() {
        val page = PageTranslation()
        page.inpaintMaskBoxes = listOf(InpaintMaskBox(10, 20, 40, 60, 2))
        assertMaskProcessed(page)
    }

    @Test
    fun `live detector region is processed without OCR blocks`() {
        val page = PageTranslation()
        page.allTextDetections = listOf(
            Detection(intArrayOf(10, 20, 40, 60), label = 2, score = 0.9f, className = "text"),
        )
        assertMaskProcessed(page)
    }

    @Test
    fun `textless page with no erase regions completes without invoking backend`() {
        val inpainter = mockk<AOTInpainting>()
        val page = PageTranslation()
        page.errorMessage = "previous failure"

        PageInpaintingEngine(InpaintingMode.QUALITY, inpainter).inpaint(mockk(), page) shouldBe null

        page.inpaintStatus shouldBe StageStatus.READY
        page.errorMessage shouldBe null
        verify { inpainter wasNot Called }
    }

    @Test
    fun `backend failure on a textless masked page is not reported as success`() {
        val inpainter = mockk<AOTInpainting>()
        every { inpainter.isInitialized() } returns false
        every { inpainter.inpaintRegions(any(), any(), any(), any(), any()) } throws
            IllegalStateException("cleanup failed")
        val page = PageTranslation()
        page.inpaintMaskBoxes = listOf(InpaintMaskBox(10, 20, 40, 60, 2))

        PageInpaintingEngine(InpaintingMode.FAST, inpainter).inpaint(bitmap(), page) shouldBe null

        page.inpaintStatus shouldBe StageStatus.FAILED
        page.errorMessage shouldBe "cleanup failed"
    }

    private fun assertMaskProcessed(page: PageTranslation) {
        val source = bitmap()
        val cleaned = mockk<Bitmap>()
        val inpainter = mockk<AOTInpainting>()
        every { inpainter.isInitialized() } returns false
        every { inpainter.inpaintRegions(any(), any(), any(), any(), any()) } returns cleaned

        PageInpaintingEngine(InpaintingMode.FAST, inpainter).inpaint(source, page) shouldBe cleaned

        page.inpaintStatus shouldBe StageStatus.READY
        verify(exactly = 1) {
            inpainter.inpaintRegions(
                source,
                match { it.map(IntArray::toList) == listOf(listOf(10, 20, 40, 60)) },
                listOf(2),
                InpaintingMode.FAST,
                emptyList(),
            )
        }
    }

    private fun bitmap(): Bitmap = mockk<Bitmap> {
        every { width } returns 100
        every { height } returns 100
    }
}
