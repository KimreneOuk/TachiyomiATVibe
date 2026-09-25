package eu.kanade.translation.inpainting.aot
import eu.kanade.translation.inpainting.bubble.BubbleMaskBuilder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AotReportBubbleFillTest {

    //  policy: inpainting tests assert only that the fill runs and produces
    // a flat cleaned fill inside the masked components — never WHICH color, and
    // never pixel-exact geometric invariants (those are not acceptance criteria).

    @Test
    fun `reportBubbleFill runs and collapses a small component interior to one flat fill`() {
        val width = 12
        val height = 12
        val ring = argb(80, 90, 100)
        val pixels = IntArray(width * height) { ring }
        val mask = ByteArray(width * height)
        // A plausible 4x4 erase region whose pixels all differ (like real text
        // strokes). Every masked pixel must end up the SAME flat fill color —
        // without asserting which color the filler chose.
        val component = ArrayList<Int>()
        for (y in 4 until 8) {
            for (x in 4 until 8) {
                val idx = index(x, y, width)
                pixels[idx] = argb((x * 23) % 256, (y * 17) % 256, (x * y) % 256)
                mask[idx] = 1
                component.add(idx)
            }
        }
        val original = pixels.copyOf()

        AotReportBubbleFill.reportBubbleFill(pixels, mask, width, height, smoothPasses = 0)

        val fill = pixels[component.first()]
        for (idx in component) {
            pixels[idx] shouldBe fill
        }
        for (i in pixels.indices) {
            if (mask[i] == 0.toByte()) pixels[i] shouldBe original[i]
        }
    }

    @Test
    fun `reportBubbleFill runs on a diagonal pair and fills it flat without touching the ring`() {
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
        val original = pixels.copyOf()

        AotReportBubbleFill.reportBubbleFill(pixels, mask, width, height, smoothPasses = 0)

        // The diagonally connected pair is one component; both pixels must end
        // up on the same flat fill (any color), and unmasked pixels stay put.
        pixels[first] shouldBe pixels[second]
        for (i in pixels.indices) {
            if (mask[i] == 0.toByte()) pixels[i] shouldBe original[i]
        }
    }

    @Test
    fun `reportBubbleFill fills tiny disconnected components flat and leaves unmasked pixels unchanged`() {
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
        // Two stroke pairs, each internally connected but separated from the
        // other: each pair is its own component and must collapse to one flat
        // fill (any color), while unmasked pixels stay put.
        val leftPair = listOf(index(1, 1, width), index(2, 1, width))
        val rightPair = listOf(index(5, 1, width), index(6, 1, width))
        pixels[leftPair[0]] = argb(1, 2, 3)
        pixels[leftPair[1]] = argb(4, 5, 6)
        pixels[rightPair[0]] = argb(7, 8, 9)
        pixels[rightPair[1]] = argb(10, 11, 12)
        for (idx in leftPair + rightPair) mask[idx] = 1
        val original = pixels.copyOf()

        AotReportBubbleFill.reportBubbleFill(pixels, mask, width, height, smoothPasses = 0)

        pixels[leftPair[0]] shouldBe pixels[leftPair[1]]
        pixels[rightPair[0]] shouldBe pixels[rightPair[1]]
        for (i in pixels.indices) {
            if (mask[i] == 0.toByte()) pixels[i] shouldBe original[i]
        }
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

    @Test
    fun `erodeBinaryMask erodes borders by radius protecting stroke edges`() {
        val width = 30
        val height = 30
        val mask = ByteArray(width * height)
        // Solid rectangle from (5,5) to (25,25)
        for (y in 5 until 25) {
            for (x in 5 until 25) {
                mask[y * width + x] = 1
            }
        }
        val inpainter = AOTInpainting()
        val bounds = listOf(listOf(5, 5, 25, 25))
        val eroded = inpainter.erodeBinaryMask(mask, width, height, bounds, radius = 3)

        // Outer border (5, 6, 7) must be eroded (value 0) to protect stroke borders
        for (y in 5..7) {
            for (x in 5 until 25) {
                eroded[y * width + x] shouldBe 0.toByte()
            }
        }
        // Interior (e.g. 10 to 20) must still be 1
        for (y in 10..20) {
            for (x in 10..20) {
                eroded[y * width + x] shouldBe 1.toByte()
            }
        }
    }

    @Test
    fun `erodeBinaryMask falls back to milder radius if small component would vanish`() {
        val width = 20
        val height = 20
        val mask = ByteArray(width * height)
        // Small 4x4 bubble at (8,8) to (12,12)
        for (y in 8 until 12) {
            for (x in 8 until 12) {
                mask[y * width + x] = 1
            }
        }
        val inpainter = AOTInpainting()
        val bounds = listOf(listOf(8, 8, 12, 12))
        // Radius 5 would completely wipe a 4x4 component
        val eroded = inpainter.erodeBinaryMask(mask, width, height, bounds, radius = 5)

        // Must not be empty due to automatic fallback to milder radius/original
        eroded.any { it != 0.toByte() } shouldBe true
    }

    private fun index(x: Int, y: Int, width: Int): Int = y * width + x

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
