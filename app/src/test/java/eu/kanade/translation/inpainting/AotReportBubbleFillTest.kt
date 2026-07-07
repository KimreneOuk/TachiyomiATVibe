package eu.kanade.translation.inpainting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AotReportBubbleFillTest {

    @Test
    fun `medianRingColor returns uniform ring color`() {
        val ring = argb(11, 22, 33)
        val pixels = IntArray(3 * 3) { ring }
        pixels[index(1, 1, 3)] = argb(200, 210, 220)

        AotReportBubbleFill.medianRingColor(
            pixels = pixels,
            ox1 = 0,
            oy1 = 0,
            ox2 = 3,
            oy2 = 3,
            ix1 = 1,
            iy1 = 1,
            ix2 = 2,
            iy2 = 2,
            width = 3,
        ) shouldBe ring
    }

    @Test
    fun `medianRingColor returns opaque white when ring count is zero`() {
        val pixels = intArrayOf(argb(10, 20, 30))

        AotReportBubbleFill.medianRingColor(
            pixels = pixels,
            ox1 = 0,
            oy1 = 0,
            ox2 = 1,
            oy2 = 1,
            ix1 = 0,
            iy1 = 0,
            ix2 = 1,
            iy2 = 1,
            width = 1,
        ) shouldBe 0xFFFFFFFF.toInt()
    }

    @Test
    fun `reportBubbleFill with no smoothing fills single component with ring median and leaves unmasked pixels unchanged`() {
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

        pixels[center] shouldBe ring
        for (i in pixels.indices) {
            if (i != center) pixels[i] shouldBe original[i]
        }
    }

    @Test
    fun `reportBubbleFill treats diagonal mask pixels as one connected component`() {
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

        pixels[first] shouldBe ring
        pixels[second] shouldBe ring
        pixels[index(1, 2, width)] shouldBe skippedInner
        pixels[index(2, 1, width)] shouldBe skippedInner
    }

    @Test
    fun `reportBubbleFill fills disconnected components from independent local ring colors`() {
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

        pixels[leftComponent] shouldBe leftRing
        pixels[rightComponent] shouldBe rightRing
    }

    @Test
    fun `smoothMaskedComponent changes only masked component pixels and leaves unmasked pixels unchanged`() {
        val width = 3
        val height = 3
        val pixels = IntArray(width * height) { i -> gray(10 + i * 10) }
        val original = pixels.copyOf()
        val mask = ByteArray(width * height)
        val center = index(1, 1, width)
        val unmaskedComponentPixel = index(2, 1, width)
        mask[center] = 1
        val expectedCenter = AotPixelOps.avg4(
            original[index(1, 0, width)],
            original[index(1, 2, width)],
            original[index(0, 1, width)],
            original[index(2, 1, width)],
        )

        AotReportBubbleFill.smoothMaskedComponent(
            pixels = pixels,
            mask = mask,
            component = listOf(center, unmaskedComponentPixel),
            width = width,
            height = height,
            passes = 1,
        )

        pixels[center] shouldBe expectedCenter
        pixels[unmaskedComponentPixel] shouldBe original[unmaskedComponentPixel]
        for (i in pixels.indices) {
            if (i != center && i != unmaskedComponentPixel) pixels[i] shouldBe original[i]
        }
    }

    @Test
    fun `smoothMaskedComponent pins edge clamping and current neighbor behavior on tiny fixture`() {
        val width = 3
        val height = 3
        val pixels = intArrayOf(
            gray(10), gray(20), gray(30),
            gray(40), gray(50), gray(60),
            gray(70), gray(80), gray(90),
        )
        val original = pixels.copyOf()
        val mask = ByteArray(width * height)
        val topLeft = index(0, 0, width)
        val topMiddle = index(1, 0, width)
        mask[topLeft] = 1
        mask[topMiddle] = 1

        AotReportBubbleFill.smoothMaskedComponent(
            pixels = pixels,
            mask = mask,
            component = listOf(topLeft, topMiddle),
            width = width,
            height = height,
            passes = 1,
        )

        pixels[topLeft] shouldBe gray(20)
        pixels[topMiddle] shouldBe gray(28)
        for (i in pixels.indices) {
            if (i != topLeft && i != topMiddle) pixels[i] shouldBe original[i]
        }
    }

    private fun index(x: Int, y: Int, width: Int): Int = y * width + x

    private fun gray(v: Int): Int = argb(v, v, v)

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
