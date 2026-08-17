package eu.kanade.translation.inpainting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AotReportBubbleFillTest {

    @Test
    fun `reportBubbleFill leaves a component without an inset interior unchanged`() {
        val width = 5
        val height = 5
        val ring = argb(80, 90, 100)
        val pixels = IntArray(width * height) { ring }
        val mask = ByteArray(width * height)
        val center = index(2, 2, width)
        pixels[center] = argb(1, 2, 3)
        mask[center] = 1
        val original = pixels.copyOf()

        AotReportBubbleFill.reportBubbleFill(pixels, mask, width, height, smoothPasses = 0)

        // The production filler intentionally protects a five-pixel boundary
        // inset; a one-pixel component has no eligible interior.
        pixels[center] shouldBe original[center]
        for (i in pixels.indices) {
            if (i != center) pixels[i] shouldBe original[i]
        }
    }

    @Test
    fun `reportBubbleFill preserves diagonal components without an inset interior`() {
        val width = 4
        val height = 4
        val ring = argb(30, 40, 50)
        val skippedInner = argb(200, 210, 220)
        val pixels = IntArray(width * height) { ring }
        val mask = ByteArray(width * height)
        pixels[index(1, 2, width)] = skippedInner
        pixels[index(2, 1, width)] = skippedInner
        val first = index(1, 1, width)
        val second = index(2, 2, width)
        pixels[first] = argb(1, 2, 3)
        pixels[second] = argb(4, 5, 6)
        mask[first] = 1
        mask[second] = 1

        AotReportBubbleFill.reportBubbleFill(pixels, mask, width, height, smoothPasses = 0)

        pixels[first] shouldBe argb(1, 2, 3)
        pixels[second] shouldBe argb(4, 5, 6)
        pixels[index(1, 2, width)] shouldBe skippedInner
        pixels[index(2, 1, width)] shouldBe skippedInner
    }

    @Test
    fun `reportBubbleFill preserves tiny disconnected components without an inset interior`() {
        val width = 7
        val height = 3
        val leftRing = argb(255, 0, 0)
        val rightRing = argb(0, 0, 255)
        val pixels = IntArray(width * height) { rightRing }
        val mask = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0..3) {
                pixels[index(x, y, width)] = leftRing
            }
        }
        val leftComponent = index(1, 1, width)
        val rightComponent = index(5, 1, width)
        pixels[leftComponent] = argb(1, 2, 3)
        pixels[rightComponent] = argb(4, 5, 6)
        mask[leftComponent] = 1
        mask[rightComponent] = 1

        AotReportBubbleFill.reportBubbleFill(pixels, mask, width, height, smoothPasses = 0)

        pixels[leftComponent] shouldBe argb(1, 2, 3)
        pixels[rightComponent] shouldBe argb(4, 5, 6)
    }

    @Test
    fun `fillAndBlend matches the legacy fill then blend sequence`() {
        val width = 12
        val height = 12
        val pixels = IntArray(width * height) { i ->
            argb((i * 7) % 256, (i * 13) % 256, (i * 29) % 256)
        }
        val mask = ByteArray(width * height)
        for (y in 3 until 9) {
            for (x in 3 until 9) mask[index(x, y, width)] = 1
        }

        val viaHelper = pixels.copyOf()
        AotReportBubbleFill.fillAndBlend(viaHelper, mask, width, height, smoothPasses = 2, featherRampPx = 3)

        // Legacy pipeline: fill a working copy, then blend against the untouched
        // original values — what the old second full-page read produced.
        val legacy = pixels.copyOf()
        AotReportBubbleFill.reportBubbleFill(legacy, mask, width, height, smoothPasses = 2)
        val alpha = BubbleMaskBuilder.featherAlphaField(mask, width, height, 3)
        for (i in legacy.indices) {
            val a = alpha[i]
            if (a > 0.0f) legacy[i] = AotPixelOps.blendPixel(pixels[i], legacy[i], a)
        }

        viaHelper shouldBe legacy
        for (i in pixels.indices) {
            if (alpha[i] <= 0.0f) viaHelper[i] shouldBe pixels[i]
        }
    }

    private fun index(x: Int, y: Int, width: Int): Int = y * width + x

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
