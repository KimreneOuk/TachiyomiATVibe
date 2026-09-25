package eu.kanade.translation.inpainting.bubble

import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Guards the pure boundary-aware pipeline helpers extracted into
 * [BoundaryAwarePipeline]. Pure byte-array / IntArray algorithms that are easy
 * to get wrong (flood bounds, tier classification thresholds, exterior paintout
 * semantics), so each behaviour is pinned here.
 */
class BoundaryAwarePipelineTest {

    // ---- computeContainment with parentBubble ----

    @Test
    fun `containment flood stops at a dark edge inside a white bubble`() {
        val w = 30
        val h = 30
        val pixels = IntArray(w * h) { 0xFFFFFFFF.toInt() } // all white

        // Draw a dark horizontal bar across the bottom half (bubble outline).
        for (y in 20 until 30) {
            for (x in 0 until 30) {
                pixels[y * w + x] = 0xFF000000.toInt() // black
            }
        }

        // Bubble box: full 30x30. The flood should stop at y=20 (the dark bar).
        val bubble = intArrayOf(0, 0, 30, 30)
        val eraseBox = intArrayOf(5, 5, 15, 15)

        val result = BoundaryAwarePipeline.computeContainment(pixels, w, h, bubble, listOf(eraseBox))

        // The containment should include the white area but stop before the dark bar.
        result.isFallback shouldBe false
        result.mask[21 * w + 5] shouldBe 0.toByte() // below the bar
        result.mask[19 * w + 5] shouldBe 1.toByte() // above the bar
        result.interiorMedian shouldBe 0xFFFFFFFF.toInt()
    }

    @Test
    fun `containment with no dark edge fills the full bubble interior`() {
        val w = 20
        val h = 20
        val pixels = IntArray(w * h) { 0xFFFFFFFF.toInt() } // all white
        val bubble = intArrayOf(0, 0, 20, 20)
        val eraseBox = intArrayOf(5, 5, 15, 15)

        val result = BoundaryAwarePipeline.computeContainment(pixels, w, h, bubble, listOf(eraseBox))

        result.isFallback shouldBe false
        // Most of the white area should be flooded.
        val flooded = result.mask.count { it != 0.toByte() }
        flooded shouldBeGreaterThan (w * h * 0.6f).toInt()
    }

    @Test
    fun `containment falls back to padded box when flood is too small (textured content)`() {
        val w = 20
        val h = 20
        // Every pixel is a different random colour — no connected flat region.
        val pixels = IntArray(w * h) { idx ->
            val r = (idx * 37) % 256
            val g = (idx * 53) % 256
            val b = (idx * 71) % 256
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val eraseBox = intArrayOf(5, 5, 15, 15)

        val result = BoundaryAwarePipeline.computeContainment(pixels, w, h, null, listOf(eraseBox))

        result.isFallback shouldBe true
    }

    @Test
    fun `containment on free text seeds from the box ring`() {
        val w = 30
        val h = 30
        val pixels = IntArray(w * h) { 0xFFEEEEEE.toInt() } // light gray background

        // A dark text stroke in the middle.
        for (y in 10 until 14) {
            for (x in 10 until 20) {
                pixels[y * w + x] = 0xFF111111.toInt()
            }
        }

        val eraseBox = intArrayOf(10, 10, 20, 14)

        val result = BoundaryAwarePipeline.computeContainment(pixels, w, h, null, listOf(eraseBox))

        result.isFallback shouldBe false
        result.interiorMedian shouldBe 0xFFEEEEEE.toInt()
    }

    // ---- paintExterior ----

    @Test
    fun `paintExterior only paints pixels outside containment`() {
        val pixels = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt())
        val containment = byteArrayOf(1, 0, 1, 0)
        val interior = 0xFF888888.toInt()

        BoundaryAwarePipeline.paintExterior(pixels, containment, interior, 4, 1)

        pixels[0] shouldBe 0xFFFF0000.toInt() // inside — unchanged
        pixels[1] shouldBe interior // outside — painted
        pixels[2] shouldBe 0xFF0000FF.toInt() // inside — unchanged
        pixels[3] shouldBe interior // outside — painted
    }

    @Test
    fun `paintExterior with full containment leaves all pixels unchanged`() {
        val pixels = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt())
        val containment = byteArrayOf(1, 1)
        val interior = 0xFF888888.toInt()

        val before = pixels.copyOf()
        BoundaryAwarePipeline.paintExterior(pixels, containment, interior, 2, 1)

        pixels shouldBe before
    }

    // ---- classifyTier ----

    @Test
    fun `classifyTier flat white returns FLAT`() {
        val stats = BoundaryAwarePipeline.RegionStats(
            medianColor = 0xFFFFFFFF.toInt(),
            grayMean = 250f,
            grayStd = 5f,
            nearWhiteRatio = 0.9f,
            darkPixelRatio = 0f,
            edgeDensity = 0.01f,
            saturationMean = 2f,
            sampleCount = 100,
        )
        BoundaryAwarePipeline.classifyTier(stats) shouldBe BoundaryAwarePipeline.Tier.FLAT
    }

    @Test
    fun `classifyTier dark flat returns FLAT`() {
        val stats = BoundaryAwarePipeline.RegionStats(
            medianColor = 0xFF111111.toInt(),
            grayMean = 30f,
            grayStd = 10f,
            nearWhiteRatio = 0f,
            darkPixelRatio = 0.7f,
            edgeDensity = 0.02f,
            saturationMean = 3f,
            sampleCount = 100,
        )
        BoundaryAwarePipeline.classifyTier(stats) shouldBe BoundaryAwarePipeline.Tier.FLAT
    }

    @Test
    fun `classifyTier saturated returns COLOR`() {
        val stats = BoundaryAwarePipeline.RegionStats(
            medianColor = 0xFFFF4444.toInt(),
            grayMean = 120f,
            grayStd = 30f,
            nearWhiteRatio = 0.1f,
            darkPixelRatio = 0f,
            edgeDensity = 0.05f,
            saturationMean = 50f,
            sampleCount = 100,
        )
        BoundaryAwarePipeline.classifyTier(stats) shouldBe BoundaryAwarePipeline.Tier.COLOR
    }

    @Test
    fun `classifyTier screentone pattern returns TEXTURED`() {
        // Screentone: moderate gray std, low saturation, low edge density.
        val stats = BoundaryAwarePipeline.RegionStats(
            medianColor = 0xFF808080.toInt(),
            grayMean = 128f,
            grayStd = 30f,
            nearWhiteRatio = 0.2f,
            darkPixelRatio = 0.2f,
            edgeDensity = 0.1f,
            saturationMean = 8f,
            sampleCount = 100,
        )
        BoundaryAwarePipeline.classifyTier(stats) shouldBe BoundaryAwarePipeline.Tier.TEXTURED
    }

    @Test
    fun `classifyTier light textured with moderate std returns TEXTURED`() {
        val stats = BoundaryAwarePipeline.RegionStats(
            medianColor = 0xFFB0B0B0.toInt(),
            grayMean = 180f,
            grayStd = 30f,
            nearWhiteRatio = 0.3f,
            darkPixelRatio = 0f,
            edgeDensity = 0.1f,
            saturationMean = 10f,
            sampleCount = 100,
        )
        BoundaryAwarePipeline.classifyTier(stats) shouldBe BoundaryAwarePipeline.Tier.TEXTURED
    }

    // ---- collectStats ----

    @Test
    fun `collectStats sums stats over the mask interior`() {
        val w = 4
        val h = 4
        val pixels = intArrayOf(
            0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF111111.toInt(), 0xFF111111.toInt(),
            0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF111111.toInt(), 0xFF111111.toInt(),
            0xFF111111.toInt(), 0xFF111111.toInt(), 0xFFEEEEEE.toInt(), 0xFFEEEEEE.toInt(),
            0xFF111111.toInt(), 0xFF111111.toInt(), 0xFFEEEEEE.toInt(), 0xFFEEEEEE.toInt(),
        )
        // Mask: only the top-left 2x2 white region.
        val mask = byteArrayOf(
            1, 1, 0, 0,
            1, 1, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0,
        )

        val stats = BoundaryAwarePipeline.collectStats(pixels, mask, w, h)

        stats.nearWhiteRatio shouldBe 1.0f
        stats.darkPixelRatio shouldBe 0f
        stats.grayStd shouldBeLessThan 5f
        stats.sampleCount shouldBeGreaterThan 0
    }

    @Test
    fun `collectStats on an empty mask is degenerate - synthetic white median, zero samples`() {
        // This is the contract the flat-fill guard relies on: an empty mask
        // yields a fabricated white median AND sampleCount == 0, so callers can
        // detect an untrustworthy median and avoid painting a white block.
        val w = 4
        val h = 4
        val pixels = IntArray(w * h) { 0xFF000000.toInt() }
        val mask = ByteArray(w * h) // all zero

        val stats = BoundaryAwarePipeline.collectStats(pixels, mask, w, h)

        stats.sampleCount shouldBe 0
        stats.medianColor shouldBe 0xFFFFFFFF.toInt()
    }

    // ---- helper helper ----

    private fun gray(v: Int): Int = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
}
