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

    private fun index(x: Int, y: Int, width: Int): Int = y * width + x

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
