package eu.kanade.translation.engines.coloring

import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.abs

class LineArtMergerTest {

    @Test
    fun `rgbToLab matches CIELAB reference values`() {
        probes.forEach { (name, rgb) ->
            val lab = LineArtMerger.rgbToLab(rgb)
            val expected = probeLab.getValue(name)
            for (c in 0 until 3) {
                abs(lab[c] - expected[c]) shouldBeLessThan LAB_TOLERANCE
            }
        }
    }

    @Test
    fun `labToRgb inverts rgbToLab for in-gamut colors`() {
        probes.forEach { (_, rgb) ->
            val roundTrip = LineArtMerger.labToRgb(LineArtMerger.rgbToLab(rgb))
            for (c in 0 until 3) {
                abs(roundTrip[c] - rgb[c]) shouldBeLessThan ROUND_TRIP_TOLERANCE
            }
        }
    }

    @Test
    fun `merge leaves flat pages unchanged`() {
        val width = 8
        val height = 8
        val colored = flat(width, height, probes.getValue("skin"))
        val bw = flat(width, height, floatArrayOf(1f, 1f, 1f))
        val merged = LineArtMerger.merge(colored, bw, width, height, sigma = 3f, ink = 1f)
        for (i in colored.indices) {
            abs(merged[i] - colored[i]) shouldBeLessThan ROUND_TRIP_TOLERANCE
        }
    }

    @Test
    fun `merge re-imposes ink lightness only at edges and keeps hue`() {
        val width = 32
        val height = 8
        val colored = flat(width, height, probes.getValue("skin"))
        val bw = flat(width, height, floatArrayOf(1f, 1f, 1f))
        // Black stripe spanning columns 12..19 re-imposes its edges.
        for (y in 0 until height) {
            for (x in 12..19) {
                bw[3 * (y * width + x)] = 0f
                bw[3 * (y * width + x) + 1] = 0f
                bw[3 * (y * width + x) + 2] = 0f
            }
        }
        val merged = LineArtMerger.merge(colored, bw, width, height, sigma = 3f, ink = 1f)

        val coloredLab = LineArtMerger.rgbToLab(colored)
        val mergedLab = LineArtMerger.rgbToLab(merged)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val withinBlurReach = x in 3..28
                if (!withinBlurReach) {
                    // Beyond the blur reach (radius 9) the detail is zero.
                    for (c in 0 until 3) {
                        abs(merged[3 * i + c] - colored[3 * i + c]) shouldBeLessThan ROUND_TRIP_TOLERANCE
                    }
                }
                if (x in 12..19) {
                    // Stripe pixels darken without touching hue.
                    abs(mergedLab[3 * i + 1] - coloredLab[3 * i + 1]) shouldBeLessThan HUE_TOLERANCE
                    abs(mergedLab[3 * i + 2] - coloredLab[3 * i + 2]) shouldBeLessThan HUE_TOLERANCE
                }
            }
        }
        // At least one stripe pixel must be visibly darker than the source colour.
        val stripeCenter = LineArtMerger.rgbToLab(
            merged.sliceArray(3 * (4 * width + 15)..3 * (4 * width + 15) + 2),
        )
        (stripeCenter[0] + 5f) shouldBeLessThan coloredLab[3 * (4 * width + 15)]
    }

    @Test
    fun `merge stays finite and in range under extreme ink`() {
        val width = 8
        val height = 8
        val colored = flat(width, height, probes.getValue("bug_pink"))
        val bw = flat(width, height, floatArrayOf(0f, 0f, 0f))
        val merged = LineArtMerger.merge(colored, bw, width, height, sigma = 3f, ink = 10f)
        for (value in merged) {
            (value.isFinite()) shouldBe true
            (value in 0f..1f) shouldBe true
        }
    }

    private fun flat(width: Int, height: Int, rgb: FloatArray): FloatArray {
        val out = FloatArray(width * height * 3)
        for (i in 0 until width * height) {
            out[3 * i] = rgb[0]
            out[3 * i + 1] = rgb[1]
            out[3 * i + 2] = rgb[2]
        }
        return out
    }

    private companion object {
        const val LAB_TOLERANCE = 0.01f
        const val ROUND_TRIP_TOLERANCE = 0.005f
        const val HUE_TOLERANCE = 0.05f

        // Probe RGBs and their CIELAB (D65) values generated from scikit-image.
        val probes = mapOf(
            "black" to floatArrayOf(0.0000f, 0.0000f, 0.0000f),
            "white" to floatArrayOf(1.0000f, 1.0000f, 1.0000f),
            "gray50" to floatArrayOf(0.5000f, 0.5000f, 0.5000f),
            "gray10" to floatArrayOf(0.1000f, 0.1000f, 0.1000f),
            "red" to floatArrayOf(1.0000f, 0.0000f, 0.0000f),
            "green" to floatArrayOf(0.0000f, 1.0000f, 0.0000f),
            "blue" to floatArrayOf(0.0000f, 0.0000f, 1.0000f),
            "yellow" to floatArrayOf(1.0000f, 1.0000f, 0.0000f),
            "cyan" to floatArrayOf(0.0000f, 1.0000f, 1.0000f),
            "magenta" to floatArrayOf(1.0000f, 0.0000f, 1.0000f),
            "bug_pink" to floatArrayOf(0.9960f, 0.3410f, 0.4270f),
            "skin" to floatArrayOf(0.9600f, 0.7600f, 0.6800f),
            "rust" to floatArrayOf(0.8500f, 0.4400f, 0.3200f),
            "navy" to floatArrayOf(0.0500f, 0.0500f, 0.2000f),
            "dark_red" to floatArrayOf(0.3000f, 0.0000f, 0.0000f),
            "forest" to floatArrayOf(0.1000f, 0.3500f, 0.2000f),
            "lavender" to floatArrayOf(0.7000f, 0.6500f, 0.8500f),
            "teal" to floatArrayOf(0.2000f, 0.6000f, 0.6000f),
        )

        val probeLab = mapOf(
            "black" to floatArrayOf(0.0000f, 0.0000f, 0.0000f),
            "white" to floatArrayOf(100.0000f, -0.0025f, 0.0047f),
            "gray50" to floatArrayOf(53.3890f, -0.0015f, 0.0028f),
            "gray10" to floatArrayOf(9.0104f, -0.0005f, 0.0010f),
            "red" to floatArrayOf(53.2406f, 80.0923f, 67.2028f),
            "green" to floatArrayOf(87.7351f, -86.1830f, 83.1797f),
            "blue" to floatArrayOf(32.2957f, 79.1856f, -107.8573f),
            "yellow" to floatArrayOf(97.1395f, -21.5547f, 94.4781f),
            "cyan" to floatArrayOf(91.1133f, -48.0906f, -14.1263f),
            "magenta" to floatArrayOf(60.3235f, 98.2331f, -60.8210f),
            "bug_pink" to floatArrayOf(60.7675f, 64.5726f, 23.5153f),
            "skin" to floatArrayOf(82.3286f, 15.3622f, 17.2219f),
            "rust" to floatArrayOf(58.9309f, 38.3421f, 35.4417f),
            "navy" to floatArrayOf(5.4568f, 13.3084f, -24.7271f),
            "dark_red" to floatArrayOf(12.9695f, 33.5098f, 20.3359f),
            "forest" to floatArrayOf(33.2175f, -29.8839f, 16.4199f),
            "lavender" to floatArrayOf(70.6651f, 14.8499f, -24.1386f),
            "teal" to floatArrayOf(57.8316f, -28.4560f, -8.5633f),
        )
    }
}
