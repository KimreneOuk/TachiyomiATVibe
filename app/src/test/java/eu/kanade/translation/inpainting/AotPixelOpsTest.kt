package eu.kanade.translation.inpainting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AotPixelOpsTest {

    @Test
    fun `blendPixel alpha zero returns original RGB with opaque alpha`() {
        val original = (0x12 shl 24) or (10 shl 16) or (20 shl 8) or 30
        val inpainted = (0x34 shl 24) or (200 shl 16) or (210 shl 8) or 220

        AotPixelOps.blendPixel(original, inpainted, 0.0f) shouldBe argb(10, 20, 30)
    }

    @Test
    fun `blendPixel alpha one returns inpainted RGB with opaque alpha`() {
        val original = (0x12 shl 24) or (10 shl 16) or (20 shl 8) or 30
        val inpainted = (0x34 shl 24) or (200 shl 16) or (210 shl 8) or 220

        AotPixelOps.blendPixel(original, inpainted, 1.0f) shouldBe argb(200, 210, 220)
    }

    @Test
    fun `blendPixel alpha half rounds non-even midpoint`() {
        val original = argb(10, 20, 30)
        val inpainted = argb(13, 23, 33)

        AotPixelOps.blendPixel(original, inpainted, 0.5f) shouldBe argb(12, 22, 32)
    }

    @Test
    fun `blendPixel output alpha is always opaque`() {
        val original = (0x01 shl 24) or (10 shl 16) or (20 shl 8) or 30
        val inpainted = (0x7F shl 24) or (200 shl 16) or (210 shl 8) or 220

        (AotPixelOps.blendPixel(original, inpainted, 0.25f) ushr 24) shouldBe 0xFF
    }

    @Test
    fun `maskValue returns source alpha when alpha is non-zero and above blue`() {
        val pixel = (0x80 shl 24) or (10 shl 16) or (20 shl 8) or 30

        AotPixelOps.maskValue(pixel) shouldBe 0x80
    }

    @Test
    fun `maskValue returns blue channel when alpha is zero`() {
        val pixel = (0x00 shl 24) or (77 shl 16) or (77 shl 8) or 77

        AotPixelOps.maskValue(pixel) shouldBe 77
    }

    @Test
    fun `maskValue preserves current max of blue channel and alpha behavior`() {
        val pixel = (0x10 shl 24) or (100 shl 16) or (150 shl 8) or 200

        AotPixelOps.maskValue(pixel) shouldBe 200
    }

    @Test
    fun `histogramMedian odd count returns middle value`() {
        val hist = IntArray(256)
        hist[10] = 1
        hist[20] = 1
        hist[30] = 1

        AotPixelOps.histogramMedian(hist, 3) shouldBe 20
    }

    @Test
    fun `histogramMedian even count returns first value after half`() {
        val hist = IntArray(256)
        hist[5] = 2
        hist[7] = 2

        AotPixelOps.histogramMedian(hist, 4) shouldBe 7
    }

    @Test
    fun `histogramMedian empty count returns 255`() {
        AotPixelOps.histogramMedian(IntArray(256), 0) shouldBe 255
    }

    @Test
    fun `avg4 rounds channel average with plus two before shift`() {
        val a = argb(1, 10, 100)
        val b = argb(2, 20, 101)
        val c = argb(3, 30, 102)
        val d = argb(5, 41, 104)

        AotPixelOps.avg4(a, b, c, d) shouldBe argb(3, 25, 102)
    }

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
