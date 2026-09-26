package eu.kanade.translation.engines.inpainting.opencv

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OpenCvInpaintEngineTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun redOf(p: Int): Int = (p shr 16) and 0xFF

    @Test
    fun `isAvailable does not throw and returns boolean safely on host JVM`() {
        // Must never throw UnsatisfiedLinkError or crash
        val available = OpenCvInpaintEngine.isAvailable
        (available || !available) shouldBe true
    }

    @Test
    fun `inpaintPixels returns input unchanged when mask has no holes`() {
        val w = 8
        val h = 8
        val pixels = IntArray(w * h) { argb(it * 2, it * 2, it * 2) }
        val mask = ByteArray(w * h)
        val before = pixels.copyOf()

        OpenCvInpaintEngine.inpaintPixels(pixels, mask, w, h)
        pixels.toList() shouldBe before.toList()
    }

    @Test
    fun `inpaintPixels handles degenerate zero or negative dimensions safely`() {
        val pixels = IntArray(10) { argb(100, 100, 100) }
        val mask = ByteArray(10) { 1 }

        OpenCvInpaintEngine.inpaintPixels(pixels, mask, 0, 10)
        OpenCvInpaintEngine.inpaintPixels(pixels, mask, 10, 0)
        OpenCvInpaintEngine.inpaintPixels(pixels, mask, -1, -1)
        OpenCvInpaintEngine.inpaintPixels(IntArray(0), ByteArray(0), 0, 0)
    }

    @Test
    fun `inpaintTelea continues gradient across masked region and preserves unmasked pixels`() {
        val w = 8
        val h = 8
        val pixels = IntArray(w * h) { argb((it % w) * 10, (it % w) * 10, (it % w) * 10) }
        val mask = ByteArray(w * h)
        for (y in 0 until h) for (x in 3..5) mask[y * w + x] = 1

        val before = pixels.copyOf()
        OpenCvInpaintEngine.inpaintTelea(pixels, mask, w, h)

        // Non-masked pixels must remain completely identical
        for (i in pixels.indices) {
            if (mask[i] == 0.toByte()) {
                pixels[i] shouldBe before[i]
            }
        }

        // Mid-hole column 4 should be filled within the gradient bounds
        val midY = 4
        val midVal = redOf(pixels[midY * w + 4])
        (midVal in 20..60) shouldBe true
    }
}
