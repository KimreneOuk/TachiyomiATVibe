package eu.kanade.translation.engines.inpainting.bubble

import eu.kanade.translation.engines.inpainting.InpaintPixelOps
import eu.kanade.translation.engines.inpainting.aot.NeuralInpaintingCoordinator
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BubbleMedianDiffusionFillTest {

    // Fill policy under test: masked components collapse to one flat fill.
    // Most fixtures assert only "flat + unchanged ring" (color-agnostic); the
    // glyph fixtures additionally pin WHICH color — the near-white paper
    // estimated from the unmasked ring when the interior is ink-dominated, so
    // a black glyph can never regress into a black blob.

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

        BubbleMedianDiffusionFill.reportBubbleFill(pixels, mask, width, height, smoothPasses = 0)

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

        BubbleMedianDiffusionFill.reportBubbleFill(pixels, mask, width, height, smoothPasses = 0)

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

        BubbleMedianDiffusionFill.reportBubbleFill(pixels, mask, width, height, smoothPasses = 0)

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
        BubbleMedianDiffusionFill.fillAndBlend(viaHelper, mask, width, height, smoothPasses = 2, featherRampPx = 3)

        // Legacy pipeline: fill a working copy, then blend against the untouched
        // original values — what the old second full-page read produced.
        val legacy = pixels.copyOf()
        BubbleMedianDiffusionFill.reportBubbleFill(legacy, mask, width, height, smoothPasses = 2)
        val alpha = BubbleMaskBuilder.featherAlphaFieldInward(mask, width, height, 3)
        for (i in legacy.indices) {
            val a = alpha[i]
            if (a > 0.0f) legacy[i] = InpaintPixelOps.blendPixel(pixels[i], legacy[i], a)
        }

        viaHelper shouldBe legacy
        // Outside the mask nothing may change at all (inward feather leaves
        // mask-edge pixels unblended, but reportBubbleFill wrote those — the
        // contract boundary is the MASK, not the alpha field).
        for (i in pixels.indices) {
            if (mask[i] == 0.toByte()) viaHelper[i] shouldBe pixels[i]
        }
    }

    @Test
    fun `fillAndBlend never writes outside the mask`() {
        val width = 16
        val height = 16
        val pixels = IntArray(width * height) { i ->
            argb((i * 11) % 256, (i * 5) % 256, (i * 3) % 256)
        }
        val mask = ByteArray(width * height)
        // Small plus-shaped erase region well inside the canvas.
        for (y in 5 until 11) {
            for (x in 5 until 11) mask[index(x, y, width)] = 1
        }
        for (x in 3 until 13) mask[index(x, 7, width)] = 1
        val original = pixels.copyOf()

        BubbleMedianDiffusionFill.fillAndBlend(pixels, mask, width, height, smoothPasses = 4, featherRampPx = 12)

        // Segmentation-only contract: the feather must live entirely INSIDE
        // the mask; every unmasked pixel stays pixel-identical.
        for (i in pixels.indices) {
            if (mask[i] == 0.toByte()) pixels[i] shouldBe original[i]
        }
    }

    @Test
    fun `two disconnected glyphs on different paper each take their own local ring color`() {
        val width = 32
        val height = 16
        val whitePaper = argb(252, 252, 252)
        val creamPaper = argb(230, 220, 180)
        val ink = argb(0, 0, 0)
        val pixels = IntArray(width * height)
        val mask = ByteArray(width * height)
        for (i in pixels.indices) pixels[i] = whitePaper
        // Right half sits on cream paper (a different bubble / screentone).
        for (y in 0 until height) {
            for (x in 16 until width) pixels[index(x, y, width)] = creamPaper
        }
        // Two 8x8 solid-black glyphs, one on each paper. 8x8 is deep enough
        // for distance >= 2 interior samples, so the ink-dominated ring
        // fallback engages for both.
        for (y in 4 until 12) {
            for (x in 2 until 10) {
                val idx = index(x, y, width)
                pixels[idx] = ink
                mask[idx] = 1
            }
            for (x in 22 until 30) {
                val idx = index(x, y, width)
                pixels[idx] = ink
                mask[idx] = 1
            }
        }

        BubbleMedianDiffusionFill.reportBubbleFill(pixels, mask, width, height, smoothPasses = 0)

        // Left glyph: local ring is white paper, snapped to pure white.
        val leftFill = pixels[index(5, 7, width)]
        ((leftFill shr 16) and 0xFF) shouldBe 255
        ((leftFill shr 8) and 0xFF) shouldBe 255
        (leftFill and 0xFF) shouldBe 255
        // Right glyph: local ring is cream (chroma >= 30, passes through
        // unsnapped) — proving the page-global cache is gone.
        val rightFill = pixels[index(25, 7, width)]
        ((rightFill shr 16) and 0xFF) shouldBe 230
        ((rightFill shr 8) and 0xFF) shouldBe 220
        (rightFill and 0xFF) shouldBe 180
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
        val inpainter = NeuralInpaintingCoordinator()
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
        val inpainter = NeuralInpaintingCoordinator()
        val bounds = listOf(listOf(8, 8, 12, 12))
        // Radius 5 would completely wipe a 4x4 component
        val eroded = inpainter.erodeBinaryMask(mask, width, height, bounds, radius = 5)

        // Must not be empty due to automatic fallback to milder radius/original
        eroded.any { it != 0.toByte() } shouldBe true
    }

    @Test
    fun `glyph-only mask estimates paper from the unmasked ring instead of snapping to black`() {
        // The deterministic failure fixture from the mask-semantics audit:
        // an 8x8 white paper canvas with a 6x6 pure-black glyph fully masked.
        // Every interior sample at distance >= 2 is black ink, so the old
        // interior-only median collapsed to a solid BLACK blob. The fill must
        // now take the paper color from the unmasked surrounding ring.
        val width = 8
        val height = 8
        val paper = argb(250, 250, 250)
        val ink = argb(0, 0, 0)
        val pixels = IntArray(width * height) { paper }
        val mask = ByteArray(width * height)
        for (y in 1 until 7) {
            for (x in 1 until 7) {
                val idx = index(x, y, width)
                pixels[idx] = ink
                mask[idx] = 1
            }
        }
        val original = pixels.copyOf()

        BubbleMedianDiffusionFill.reportBubbleFill(pixels, mask, width, height, smoothPasses = 0)

        // The masked glyph must NOT have become a black blob: the flat fill is
        // the near-white paper estimated from the ring outside the mask.
        val fill = pixels[index(3, 3, width)]
        (fill and 0xFF) shouldBe 255
        ((fill shr 16) and 0xFF) shouldBe 255
        ((fill shr 8) and 0xFF) shouldBe 255
        for (i in pixels.indices) {
            if (mask[i] == 0.toByte()) pixels[i] shouldBe original[i]
        }
    }

    @Test
    fun `paper-dominated bubble interior still fills from interior sampling`() {
        // Whole-balloon mask: mostly white paper with scattered dark glyphs.
        // Interior sampling dominates (paper > 80%) and must still produce a
        // flat near-white fill for the whole component.
        val width = 32
        val height = 32
        val paper = argb(252, 252, 252)
        val ink = argb(10, 10, 10)
        val pixels = IntArray(width * height) { paper }
        val mask = ByteArray(width * height)
        for (y in 4 until 28) {
            for (x in 4 until 28) {
                val idx = index(x, y, width)
                mask[idx] = 1
                // ~15% ink coverage inside the mask: paper still dominates.
                if ((x * 7 + y * 13) % 7 == 0) pixels[idx] = ink
            }
        }
        val original = pixels.copyOf()

        BubbleMedianDiffusionFill.reportBubbleFill(pixels, mask, width, height, smoothPasses = 0)

        val fill = pixels[index(16, 16, width)]
        (fill and 0xFF) shouldBe 255
        ((fill shr 16) and 0xFF) shouldBe 255
        ((fill shr 8) and 0xFF) shouldBe 255
        for (i in pixels.indices) {
            if (mask[i] == 0.toByte()) pixels[i] shouldBe original[i]
        }
    }

    private fun index(x: Int, y: Int, width: Int): Int = y * width + x

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
