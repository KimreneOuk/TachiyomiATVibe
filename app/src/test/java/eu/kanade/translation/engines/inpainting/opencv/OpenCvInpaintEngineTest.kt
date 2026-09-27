package eu.kanade.translation.engines.inpainting.opencv

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OpenCvInpaintEngineTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun redOf(p: Int): Int = (p shr 16) and 0xFF

    @Test
    fun `inpaint reports the classical backend actually used and preserves unmasked pixels`() {
        val w = 8
        val h = 8
        val pixels = IntArray(w * h) { argb((it % w) * 10, (it % w) * 10, (it % w) * 10) }
        val mask = ByteArray(w * h)
        for (y in 0 until h) for (x in 3..5) mask[y * w + x] = 1
        val before = pixels.copyOf()

        val backend = OpenCvInpaintEngine.inpaintPixelsWithBackend(pixels, mask, w, h)

        backend.routeLabel shouldBe if (OpenCvInpaintEngine.isAvailable) "telea" else "push_pull_emergency"
        for (i in pixels.indices) {
            if (mask[i] == 0.toByte()) pixels[i] shouldBe before[i]
        }
        val midVal = redOf(pixels[4 * w + 4])
        (midVal in 20..60) shouldBe true
    }

    @Test
    fun `no holes reports unchanged and leaves input intact`() {
        val w = 8
        val h = 8
        val pixels = IntArray(w * h) { argb(it * 2, it * 2, it * 2) }
        val before = pixels.copyOf()

        OpenCvInpaintEngine.inpaintPixelsWithBackend(pixels, ByteArray(w * h), w, h) shouldBe
            OpenCvInpaintEngine.Backend.UNCHANGED
        pixels.toList() shouldBe before.toList()
    }

    @Test
    fun `degenerate dimensions return unchanged without touching buffers`() {
        val pixels = IntArray(10) { argb(100, 100, 100) }
        val mask = ByteArray(10) { 1 }
        val before = pixels.copyOf()

        OpenCvInpaintEngine.inpaintPixelsWithBackend(pixels, mask, 0, 10) shouldBe
            OpenCvInpaintEngine.Backend.UNCHANGED
        OpenCvInpaintEngine.inpaintPixelsWithBackend(pixels, mask, 10, 0) shouldBe
            OpenCvInpaintEngine.Backend.UNCHANGED
        OpenCvInpaintEngine.inpaintPixelsWithBackend(pixels, mask, -1, -1) shouldBe
            OpenCvInpaintEngine.Backend.UNCHANGED
        pixels.toList() shouldBe before.toList()
    }
}
