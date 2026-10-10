package eu.kanade.translation.engines.inpainting

import android.graphics.Bitmap
import eu.kanade.translation.engines.inpainting.aot.AOTInpainting
import eu.kanade.translation.model.Detection
import eu.kanade.translation.model.InpaintMaskBox
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
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
        every { inpainter.isInitialized() } returns false
        every { inpainter.lastRunDegraded } returns false
        val page = PageTranslation()
        page.errorMessage = "previous failure"

        PageInpaintingEngine(InpaintingMode.QUALITY, inpainter).inpaint(mockk(), page) shouldBe null

        page.inpaintStatus shouldBe StageStatus.READY
        page.errorMessage shouldBe null
        verify(exactly = 0) { inpainter.inpaintRegions(any(), any(), any(), any(), any()) }
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

    @Test
    fun `BALANCE with no neural session fails the page unless fallback pref is enabled`() {
        val inpainter = mockk<AOTInpainting>()
        every { inpainter.isInitialized() } returns false
        val page = PageTranslation()
        page.inpaintMaskBoxes = listOf(InpaintMaskBox(10, 20, 40, 60, 2))

        PageInpaintingEngine(InpaintingMode.BALANCE, inpainter, qualityFallbackPref = { false })
            .inpaint(bitmap(), page) shouldBe null

        page.inpaintStatus shouldBe StageStatus.FAILED
        page.errorMessage?.contains("unavailable") shouldBe true

        val cleaned = mockk<Bitmap>()
        every { inpainter.lastRunDegraded } returns false
        every { inpainter.inpaintRegions(any(), any(), any(), any(), any()) } returns cleaned
        val page2 = PageTranslation()
        page2.inpaintMaskBoxes = listOf(InpaintMaskBox(10, 20, 40, 60, 2))
        PageInpaintingEngine(InpaintingMode.BALANCE, inpainter, qualityFallbackPref = { true })
            .inpaint(bitmap(), page2) shouldBe cleaned
        page2.inpaintStatus shouldBe StageStatus.READY
    }

    @Test
    fun `fallback pref is read live per inpaint call`() {
        val inpainter = mockk<AOTInpainting>()
        every { inpainter.isInitialized() } returns false
        every { inpainter.lastRunDegraded } returns false
        every { inpainter.inpaintRegions(any(), any(), any(), any(), any()) } returns mockk()
        val page1 = PageTranslation().apply { inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 2)) }
        val page2 = PageTranslation().apply { inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 2)) }
        var pref = false
        val engine = PageInpaintingEngine(InpaintingMode.QUALITY, inpainter, qualityFallbackPref = { pref })

        engine.inpaint(bitmap(), page1)
        page1.inpaintStatus shouldBe StageStatus.FAILED
        pref = true
        engine.inpaint(bitmap(), page2)
        page2.inpaintStatus shouldBe StageStatus.READY
    }

    @Test
    fun `engine passes a dispatch resolved from mode and session reality`() {
        val inpainter = mockk<AOTInpainting>()
        every { inpainter.isInitialized() } returns true
        every { inpainter.lastRunDegraded } returns false
        every { inpainter.inpaintRegions(any(), any(), any(), any(), any()) } returns mockk()
        val page = PageTranslation().apply { inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 0)) }

        PageInpaintingEngine(InpaintingMode.BALANCE, inpainter).inpaint(bitmap(), page)

        verify {
            inpainter.inpaintRegions(
                any(),
                any(),
                any(),
                RegionDispatch(RegionBackend.OPENCV_NS, RegionBackend.AOT_NEURAL),
                any(),
            )
        }
    }

    @Test
    fun `successful inpainting stamps selected mode and records degraded fallback`() {
        val inpainter = mockk<AOTInpainting>()
        val cleaned = mockk<Bitmap>()
        every { inpainter.isInitialized() } returns true
        every { inpainter.lastRunDegraded } returns false
        every { inpainter.inpaintRegions(any(), any(), any(), any(), any()) } returns cleaned
        val page = PageTranslation().apply { inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 2)) }

        PageInpaintingEngine(InpaintingMode.BALANCE, inpainter).inpaint(bitmap(), page) shouldBe cleaned
        page.inpaintingModeUsed shouldBe "BALANCE:AOT"

        every { inpainter.lastRunDegraded } returns true
        val degradedPage = PageTranslation().apply { inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 2)) }
        PageInpaintingEngine(InpaintingMode.BALANCE, inpainter).inpaint(bitmap(), degradedPage) shouldBe cleaned
        degradedPage.inpaintingModeUsed shouldBe "BALANCE:AOT_DEGRADED"

        every { inpainter.lastRunDegraded } returns false
        val lamaPage = PageTranslation().apply { inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 2)) }
        PageInpaintingEngine(
            InpaintingMode.BALANCE,
            inpainter,
            neuralModel = tachiyomi.domain.translation.NeuralInpaintModel.LAMA_MANGA,
        ).inpaint(bitmap(), lamaPage) shouldBe cleaned
        lamaPage.inpaintingModeUsed shouldBe "BALANCE:LAMA"
    }

    @Test
    fun `cancellation is rethrown and is not recorded as an inpainting failure`() {
        val inpainter = mockk<AOTInpainting>()
        every { inpainter.isInitialized() } returns false
        every { inpainter.inpaintRegions(any(), any(), any(), any(), any()) } throws CancellationException("cancelled")
        val page = PageTranslation().apply { inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 2)) }

        val message = try {
            PageInpaintingEngine(InpaintingMode.FAST, inpainter).inpaint(bitmap(), page)
            null
        } catch (e: CancellationException) {
            e.message
        }

        message shouldBe "cancelled"
        page.inpaintStatus shouldBe StageStatus.RUNNING
    }

    private fun assertMaskProcessed(page: PageTranslation) {
        val source = bitmap()
        val cleaned = mockk<Bitmap>()
        val inpainter = mockk<AOTInpainting>()
        every { inpainter.isInitialized() } returns false
        every { inpainter.lastRunDegraded } returns false
        every { inpainter.inpaintRegions(any(), any(), any(), any(), any()) } returns cleaned

        PageInpaintingEngine(InpaintingMode.FAST, inpainter).inpaint(source, page) shouldBe cleaned

        page.inpaintStatus shouldBe StageStatus.READY
        verify(exactly = 1) {
            inpainter.inpaintRegions(
                source,
                match { it.map(IntArray::toList) == listOf(listOf(10, 20, 40, 60)) },
                listOf(2),
                InpaintingMode.FAST.resolveDispatch(neuralAvailable = false),
                emptyList(),
            )
        }
    }

    private fun bitmap(): Bitmap = mockk<Bitmap> {
        every { width } returns 100
        every { height } returns 100
    }
}
