package eu.kanade.translation.engines.vision.ocr

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PaddleOcrV6DetEngineTest {

    @Test
    fun `calculateDetDimensions scales normal 16-9 page so longest side is 960 and multiples of 32`() {
        val (rw, rh) = PaddleOcrV6DetEngine.calculateDetDimensions(1920, 1080)
        // 1920x1080: aspect ratio = 1.777 <= 2.0 -> scale = 960 / 1920 = 0.5
        // rw = round(1920 * 0.5 / 32) * 32 = 960
        // rh = round(1080 * 0.5 / 32) * 32 = round(16.875) * 32 = 17 * 32 = 544
        rw shouldBe 960
        rh shouldBe 544
        (rw % 32) shouldBe 0
        (rh % 32) shouldBe 0
    }

    @Test
    fun `calculateDetDimensions scales portrait page so height is 960 and multiples of 32`() {
        val (rw, rh) = PaddleOcrV6DetEngine.calculateDetDimensions(800, 1200)
        // 800x1200: aspect ratio = 1.5 <= 2.0 -> scale = 960 / 1200 = 0.8
        // rw = round(800 * 0.8 / 32) * 32 = round(20.0) * 32 = 640
        // rh = round(1200 * 0.8 / 32) * 32 = round(30.0) * 32 = 960
        rw shouldBe 640
        rh shouldBe 960
        (rw % 32) shouldBe 0
        (rh % 32) shouldBe 0
    }

    @Test
    fun `calculateDetDimensions scales typical manga pages 1000x1600 and 1200x1920`() {
        val (rw1, rh1) = PaddleOcrV6DetEngine.calculateDetDimensions(1000, 1600)
        rw1 shouldBe 608
        rh1 shouldBe 960
        (rw1 % 32) shouldBe 0
        (rh1 % 32) shouldBe 0

        val (rw2, rh2) = PaddleOcrV6DetEngine.calculateDetDimensions(1200, 1920)
        rw2 shouldBe 608
        rh2 shouldBe 960
        (rw2 % 32) shouldBe 0
        (rh2 % 32) shouldBe 0
    }

    @Test
    fun `backProject maps map-space coordinates accurately back to full-page pixel coords`() {
        val bitmapW = 1000
        val bitmapH = 1600
        val (rw, rh) = PaddleOcrV6DetEngine.calculateDetDimensions(bitmapW, bitmapH)
        val scaleX = bitmapW.toFloat() / rw
        val scaleY = bitmapH.toFloat() / rh

        // A line in map space at [60, 96, 304, 480]
        val mapBbox = intArrayOf(60, 96, 304, 480)
        val pageBbox = DbPostProcess.backProject(mapBbox, scaleX, scaleY, bitmapW, bitmapH)

        pageBbox[0] shouldBe 98
        pageBbox[1] shouldBe 160
        pageBbox[2] shouldBe 500
        pageBbox[3] shouldBe 800

        // Corner line at map boundaries is clamped without off-by-one errors
        val edgeMapBbox = intArrayOf(0, 0, rw, rh)
        val edgePageBbox = DbPostProcess.backProject(edgeMapBbox, scaleX, scaleY, bitmapW, bitmapH)
        edgePageBbox[0] shouldBe 0
        edgePageBbox[1] shouldBe 0
        edgePageBbox[2] shouldBe (bitmapW - 1)
        edgePageBbox[3] shouldBe (bitmapH - 1)
    }

    @Test
    fun `calculateDetDimensions applies 3-4 page pixel budget for long webtoon strips`() {
        // Long vertical strip: aspect ratio > 2.0 (e.g. 1000 x 5000)
        // ratio = min(1.0, sqrt(0.75 * 960 * 960 / (1000 * 5000)))
        // 0.75 * 960 * 960 = 691200
        // 691200 / 5000000 = 0.13824 -> sqrt(0.13824) = 0.371806
        // rw = round(1000 * 0.371806 / 32) * 32 = round(11.6189) * 32 = 12 * 32 = 384
        // rh = round(5000 * 0.371806 / 32) * 32 = round(58.094) * 32 = 58 * 32 = 1856
        val (rw, rh) = PaddleOcrV6DetEngine.calculateDetDimensions(1000, 5000)
        rw shouldBe 384
        rh shouldBe 1856
        (rw % 32) shouldBe 0
        (rh % 32) shouldBe 0
    }

    @Test
    fun `calculateDetDimensions does not upscale small bubble crops`() {
        val (rw, rh) = PaddleOcrV6DetEngine.calculateDetDimensions(150, 100)
        rw shouldBe 160
        rh shouldBe 96
        (rw % 32) shouldBe 0
        (rh % 32) shouldBe 0
    }
}
