package eu.kanade.translation.engines.inpainting

import android.graphics.Bitmap
import eu.kanade.translation.engines.inpainting.aot.NeuralInpaintingCoordinator
import eu.kanade.translation.engines.inpainting.litert.LiteRTMangaInpaintingEngine
import eu.kanade.translation.model.Detection
import eu.kanade.translation.model.InpaintMaskBox
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
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
        val inpainter = mockk<NeuralInpaintingCoordinator>()
        every { inpainter.isInitialized() } returns false
        every { inpainter.freeTextNeuralFill = any() } just runs
        every { inpainter.bubbleNeuralFill = any() } just runs
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
        val inpainter = mockk<NeuralInpaintingCoordinator>()
        every { inpainter.isInitialized() } returns false
        every { inpainter.freeTextNeuralFill = any() } just runs
        every { inpainter.bubbleNeuralFill = any() } just runs
        every { inpainter.inpaintRegions(any(), any(), any(), any(), any()) } throws
            IllegalStateException("cleanup failed")
        val page = PageTranslation()
        page.inpaintMaskBoxes = listOf(InpaintMaskBox(10, 20, 40, 60, 2))

        PageInpaintingEngine(InpaintingMode.FAST, inpainter, notifyInpaintFailed = {})
            .inpaint(bitmap(), page) shouldBe null

        page.inpaintStatus shouldBe StageStatus.FAILED
        page.errorMessage shouldBe "cleanup failed"
    }

    @Test
    fun `BALANCE with no neural session fails the page unless fallback pref is enabled`() {
        val inpainter = mockk<NeuralInpaintingCoordinator>()
        every { inpainter.isInitialized() } returns false
        every { inpainter.freeTextNeuralFill = any() } just runs
        every { inpainter.bubbleNeuralFill = any() } just runs
        val page = PageTranslation()
        page.inpaintMaskBoxes = listOf(InpaintMaskBox(10, 20, 40, 60, 2))

        PageInpaintingEngine(InpaintingMode.BALANCE, inpainter, qualityFallbackPref = { false }, notifyInpaintFailed = {})
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
        val inpainter = mockk<NeuralInpaintingCoordinator>()
        every { inpainter.isInitialized() } returns false
        every { inpainter.freeTextNeuralFill = any() } just runs
        every { inpainter.bubbleNeuralFill = any() } just runs
        every { inpainter.lastRunDegraded } returns false
        every { inpainter.inpaintRegions(any(), any(), any(), any(), any()) } returns mockk()
        val page1 = PageTranslation().apply { inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 2)) }
        val page2 = PageTranslation().apply { inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 2)) }
        var pref = false
        val engine = PageInpaintingEngine(InpaintingMode.QUALITY, inpainter, qualityFallbackPref = { pref }, notifyInpaintFailed = {})

        engine.inpaint(bitmap(), page1)
        page1.inpaintStatus shouldBe StageStatus.FAILED
        pref = true
        engine.inpaint(bitmap(), page2)
        page2.inpaintStatus shouldBe StageStatus.READY
    }

    @Test
    fun `engine passes a dispatch resolved from mode and session reality`() {
        val inpainter = mockk<NeuralInpaintingCoordinator>()
        every { inpainter.isInitialized() } returns true
        every { inpainter.freeTextNeuralFill = any() } just runs
        every { inpainter.bubbleNeuralFill = any() } just runs
        every { inpainter.lastRunDegraded } returns false
        every { inpainter.inpaintRegions(any(), any(), any(), any(), any()) } returns mockk()
        val litert = mockk<LiteRTMangaInpaintingEngine>()
        every { litert.isAvailable() } returns true
        val page = PageTranslation().apply { inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 0)) }

        PageInpaintingEngine(
            InpaintingMode.BALANCE,
            inpainter,
            litertInpainter = litert,
            neuralModel = tachiyomi.domain.translation.NeuralInpaintModel.AOT_GAN,
        ).inpaint(bitmap(), page)

        verify {
            inpainter.inpaintRegions(
                any(),
                any(),
                any(),
                RegionDispatch(RegionBackend.SMOOTH_DIFFUSION, RegionBackend.NEURAL),
                any(),
            )
        }
    }

    @Test
    fun `QUALITY mode with LiteRT routes through inpainter with both neural delegates`() {
        val inpainter = mockk<NeuralInpaintingCoordinator>()
        val litert = mockk<LiteRTMangaInpaintingEngine>()
        val cleaned = mockk<Bitmap>()
        every { inpainter.lastRunDegraded } returns false
        every { inpainter.freeTextNeuralFill = any() } just runs
        every { inpainter.bubbleNeuralFill = any() } just runs
        every { inpainter.inpaintRegions(any(), any(), any(), any(), any()) } returns cleaned
        every { litert.isAvailable() } returns true
        val page = PageTranslation().apply { inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 2)) }

        PageInpaintingEngine(
            InpaintingMode.QUALITY,
            inpainter,
            litertInpainter = litert,
        ).inpaint(bitmap(), page) shouldBe cleaned

        verify(exactly = 0) { litert.inpaintRegions(any(), any(), any(), any()) }
        verify(exactly = 1) { inpainter.inpaintRegions(any(), any(), any(), any(), any()) }
        verify {
            inpainter.inpaintRegions(
                any(),
                any(),
                any(),
                RegionDispatch(RegionBackend.NEURAL, RegionBackend.NEURAL),
                any(),
            )
        }
        verify { inpainter.freeTextNeuralFill = any<((Bitmap, List<IntArray>) -> Bitmap?)>() }
        verify {
            inpainter.bubbleNeuralFill =
                any<((Bitmap, ByteArray, IntArray, Int) -> IntArray?)>()
        }
    }

    @Test
    fun `BALANCE LiteRT keeps bubbles classical and hands free text to the delegate`() {
        val inpainter = mockk<NeuralInpaintingCoordinator>()
        val litert = mockk<LiteRTMangaInpaintingEngine>()
        every { inpainter.isInitialized() } returns false
        every { inpainter.freeTextNeuralFill = any() } just runs
        every { inpainter.bubbleNeuralFill = any() } just runs
        every { inpainter.lastRunDegraded } returns false
        every { inpainter.inpaintRegions(any(), any(), any(), any(), any()) } returns mockk()
        every { litert.isAvailable() } returns true
        val page = PageTranslation().apply { inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 2)) }

        PageInpaintingEngine(
            InpaintingMode.BALANCE,
            inpainter,
            litertInpainter = litert,
        ).inpaint(bitmap(), page)

        verify(exactly = 0) { litert.inpaintRegions(any(), any(), any(), any()) }
        verify {
            inpainter.inpaintRegions(
                any(),
                any(),
                any(),
                RegionDispatch(RegionBackend.SMOOTH_DIFFUSION, RegionBackend.NEURAL),
                any(),
            )
        }
        verify {
            inpainter.freeTextNeuralFill =
                any<((Bitmap, List<IntArray>) -> Bitmap?)>()
        }
    }

    @Test
    fun `LiteRT model with unavailable interpreter falls back to the classical dispatch`() {
        val inpainter = mockk<NeuralInpaintingCoordinator>()
        val litert = mockk<LiteRTMangaInpaintingEngine>()
        every { inpainter.isInitialized() } returns false
        every { inpainter.freeTextNeuralFill = any() } just runs
        every { inpainter.bubbleNeuralFill = any() } just runs
        every { inpainter.lastRunDegraded } returns false
        every { inpainter.inpaintRegions(any(), any(), any(), any(), any()) } returns mockk()
        every { litert.isAvailable() } returns false
        val page = PageTranslation().apply { inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 2)) }

        PageInpaintingEngine(
            InpaintingMode.QUALITY,
            inpainter,
            litertInpainter = litert,
            qualityFallbackPref = { true },
        ).inpaint(bitmap(), page)

        verify {
            inpainter.inpaintRegions(
                any(),
                any(),
                any(),
                RegionDispatch(RegionBackend.SMOOTH_DIFFUSION, RegionBackend.OPENCV_TELEA),
                any(),
            )
        }
        verify { inpainter.freeTextNeuralFill = null }
        verify { inpainter.bubbleNeuralFill = null }
    }

    @Test
    fun `FAST mode never engages the LiteRT delegate`() {
        val inpainter = mockk<NeuralInpaintingCoordinator>()
        val litert = mockk<LiteRTMangaInpaintingEngine>()
        every { inpainter.isInitialized() } returns false
        every { inpainter.freeTextNeuralFill = any() } just runs
        every { inpainter.bubbleNeuralFill = any() } just runs
        every { inpainter.lastRunDegraded } returns false
        every { inpainter.inpaintRegions(any(), any(), any(), any(), any()) } returns mockk()
        every { litert.isAvailable() } returns true
        val page = PageTranslation().apply { inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 2)) }

        PageInpaintingEngine(
            InpaintingMode.FAST,
            inpainter,
            litertInpainter = litert,
        ).inpaint(bitmap(), page)

        verify {
            inpainter.inpaintRegions(
                any(),
                any(),
                any(),
                RegionDispatch(RegionBackend.SMOOTH_DIFFUSION, RegionBackend.OPENCV_TELEA),
                any(),
            )
        }
        verify { inpainter.freeTextNeuralFill = null }
        verify { inpainter.bubbleNeuralFill = null }
    }

    @Test
    fun `successful inpainting stamps selected mode and records degraded fallback`() {
        val inpainter = mockk<NeuralInpaintingCoordinator>()
        val cleaned = mockk<Bitmap>()
        every { inpainter.isInitialized() } returns true
        every { inpainter.freeTextNeuralFill = any() } just runs
        every { inpainter.bubbleNeuralFill = any() } just runs
        every { inpainter.lastRunDegraded } returns false
        every { inpainter.inpaintRegions(any(), any(), any(), any(), any()) } returns cleaned
        val litert = mockk<LiteRTMangaInpaintingEngine>()
        every { litert.isAvailable() } returns true
        val page = PageTranslation().apply { inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 2)) }

        PageInpaintingEngine(
            InpaintingMode.BALANCE,
            inpainter,
            litertInpainter = litert,
            neuralModel = tachiyomi.domain.translation.NeuralInpaintModel.AOT_GAN,
        ).inpaint(bitmap(), page) shouldBe cleaned
        // Optimized AOT carries the new AOT_LITERT token (LiteRT GPU runtime).
        page.inpaintingModeUsed shouldBe "BALANCE:AOT_LITERT"

        every { inpainter.lastRunDegraded } returns true
        val degradedPage = PageTranslation().apply { inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 2)) }
        PageInpaintingEngine(
            InpaintingMode.BALANCE,
            inpainter,
            litertInpainter = litert,
            neuralModel = tachiyomi.domain.translation.NeuralInpaintModel.AOT_GAN,
        ).inpaint(bitmap(), degradedPage) shouldBe cleaned
        degradedPage.inpaintingModeUsed shouldBe "BALANCE:AOT_LITERT_DEGRADED"

        every { inpainter.lastRunDegraded } returns false
        val litertPage = PageTranslation().apply { inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 2)) }
        PageInpaintingEngine(
            InpaintingMode.BALANCE,
            inpainter,
            litertInpainter = litert,
            neuralModel = tachiyomi.domain.translation.NeuralInpaintModel.LAMA_LITERT_GPU,
        ).inpaint(bitmap(), litertPage) shouldBe cleaned
        litertPage.inpaintingModeUsed shouldBe "BALANCE:LAMA_LITERT"
    }

    @Test
    fun `cancellation is rethrown and is not recorded as an inpainting failure`() {
        val inpainter = mockk<NeuralInpaintingCoordinator>()
        every { inpainter.isInitialized() } returns false
        every { inpainter.freeTextNeuralFill = any() } just runs
        every { inpainter.bubbleNeuralFill = any() } just runs
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

    @Test
    fun `a failed page announces to the user while success and cancellation stay silent`() {
        val announced = mutableListOf<String>()

        // Neural-less QUALITY needs an explicit fallback pref or the engine
        // throws before reaching the backend under test.
        val failing = mockk<NeuralInpaintingCoordinator>()
        every { failing.isInitialized() } returns false
        every { failing.freeTextNeuralFill = any() } just runs
        every { failing.bubbleNeuralFill = any() } just runs
        every { failing.inpaintRegions(any(), any(), any(), any(), any()) } throws IllegalStateException("cleanup failed")
        val failedPage = PageTranslation().apply { inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 2)) }
        PageInpaintingEngine(InpaintingMode.QUALITY, failing, qualityFallbackPref = { true }, notifyInpaintFailed = announced::add)
            .inpaint(bitmap(), failedPage)

        val succeeding = mockk<NeuralInpaintingCoordinator>()
        every { succeeding.isInitialized() } returns false
        every { succeeding.freeTextNeuralFill = any() } just runs
        every { succeeding.bubbleNeuralFill = any() } just runs
        every { succeeding.lastRunDegraded } returns false
        every { succeeding.inpaintRegions(any(), any(), any(), any(), any()) } returns mockk()
        val readyPage = PageTranslation().apply { inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 2)) }
        PageInpaintingEngine(InpaintingMode.QUALITY, succeeding, qualityFallbackPref = { true }, notifyInpaintFailed = announced::add)
            .inpaint(bitmap(), readyPage)

        val cancelling = mockk<NeuralInpaintingCoordinator>()
        every { cancelling.isInitialized() } returns false
        every { cancelling.freeTextNeuralFill = any() } just runs
        every { cancelling.bubbleNeuralFill = any() } just runs
        every { cancelling.inpaintRegions(any(), any(), any(), any(), any()) } throws CancellationException("cancelled")
        val cancelledPage = PageTranslation().apply { inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 2)) }
        try {
            PageInpaintingEngine(InpaintingMode.QUALITY, cancelling, qualityFallbackPref = { true }, notifyInpaintFailed = announced::add)
                .inpaint(bitmap(), cancelledPage)
        } catch (_: CancellationException) {
        }

        announced shouldBe listOf("QUALITY")
    }

    private fun assertMaskProcessed(page: PageTranslation) {
        val source = bitmap()
        val cleaned = mockk<Bitmap>()
        val inpainter = mockk<NeuralInpaintingCoordinator>()
        every { inpainter.isInitialized() } returns false
        every { inpainter.freeTextNeuralFill = any() } just runs
        every { inpainter.bubbleNeuralFill = any() } just runs
        every { inpainter.lastRunDegraded } returns false
        every { inpainter.inpaintRegions(any(), any(), any(), any(), any()) } returns cleaned

        PageInpaintingEngine(InpaintingMode.FAST, inpainter).inpaint(source, page) shouldBe cleaned

        page.inpaintStatus shouldBe StageStatus.READY
        verify(exactly = 1) {
            inpainter.inpaintRegions(
                source,
                match { it.map(IntArray::toList) == listOf(listOf(10, 20, 40, 60)) },
                listOf(2),
                RegionDispatch(RegionBackend.SMOOTH_DIFFUSION, RegionBackend.OPENCV_TELEA),
                emptyList(),
            )
        }
    }

    private fun bitmap(): Bitmap = mockk<Bitmap> {
        every { width } returns 100
        every { height } returns 100
    }
}
