package eu.kanade.translation.rendering

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Exercises the post-sampling decision ([RenderColorEstimator.decideTextFill])
 * end-to-end with synthetic pixel arrays — the code path where the symptom-A
 * fill-color bug actually lives (2-means → colorPolicy).
 *
 * Pure JVM: constructs ARGB `IntArray`s directly instead of an
 * `android.graphics.Bitmap`, so no Robolectric is needed. This mirrors the
 * codebase's deliberate "keep the policy Bitmap-free and `internal`" testing
 * pattern (see the header on [RenderColorEstimatorTest]).
 */
class RenderColorEstimatorSamplingTest {

    private val white = 0xFFFFFFFFL
    private val black = 0xFF000000L

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** A crop that is mostly [bg], with a centered [w]×[w] ink block of [ink]. */
    private fun cropWithInk(size: Int, bg: Int, ink: Int, w: Int = size / 3): IntArray {
        val px = IntArray(size * size) { bg }
        val o = (size - w) / 2
        for (y in o until o + w) for (x in o until o + w) px[y * size + x] = ink
        return px
    }

    @Test
    fun `dark ink on dark background yields white text`() {
        // Symptom A: a dark bubble where even the ink cluster is dark. The 2-means
        // fg cluster is dark, contrast < AA → decideTextFill must force white.
        val pixels = cropWithInk(size = 60, bg = argb(20, 20, 20), ink = argb(35, 35, 35))
        val text = RenderColorEstimator.decideTextFill(pixels, 60, 60, cropLeft = 0, cropTop = 0)
        text shouldBe white
    }

    @Test
    fun `light ink on light background yields black text`() {
        val pixels = cropWithInk(size = 60, bg = argb(235, 235, 235), ink = argb(220, 220, 220))
        val text = RenderColorEstimator.decideTextFill(pixels, 60, 60, cropLeft = 0, cropTop = 0)
        text shouldBe black
    }

    @Test
    fun `dark ink on light background is preserved`() {
        // High-contrast ink clears AA → the detected dark cluster color is kept.
        val pixels = cropWithInk(size = 60, bg = argb(240, 240, 240), ink = argb(20, 20, 20))
        val text = RenderColorEstimator.decideTextFill(pixels, 60, 60, cropLeft = 0, cropTop = 0)
        val b = (text and 0xFF).toInt()
        b shouldBeLessThan 80
    }

    @Test
    fun `saturated low-contrast ink is also forced to B-W`() {
        // The gap the old gray-snap could not reach: a SATURATED dark ink on a
        // dark background. The WCAG check must still force white.
        val pixels = cropWithInk(size = 60, bg = argb(15, 15, 15), ink = argb(40, 0, 0))
        val text = RenderColorEstimator.decideTextFill(pixels, 60, 60, cropLeft = 0, cropTop = 0)
        text shouldBe white
    }

    @Test
    fun `parented bubble mask ignores bright art outside the bubble`() {
        // Existing-behavior guard: a parented bubble whose interior is dark but
        // is surrounded (in the crop) by bright artwork. The eroded interior
        // mask must keep the estimate from the interior (dark), so the dark ink
        // on the dark interior → forced white. Without the mask, the bright
        // surroundings would dominate the bg cluster → black text on dark ink.
        val size = 60
        val bright = argb(245, 245, 245)
        val dark = argb(20, 20, 20)
        val pixels = IntArray(size * size) { bright }
        // Bubble interior occupying the centre 40×40 of the crop.
        val o = 10
        for (y in o until o + 40) for (x in o until o + 40) pixels[y * size + x] = dark
        // Dark ink inside the interior.
        val io = 25
        for (y in io until io + 10) for (x in io until io + 10) pixels[y * size + x] = argb(35, 35, 35)
        // crop covers 0..60; parentBbox (in crop coords) is the interior [10,10,50,50].
        val parent = intArrayOf(10, 10, 50, 50)

        val text = RenderColorEstimator.decideTextFill(pixels, size, size, cropLeft = 0, cropTop = 0, parent)
        text shouldBe white
    }

    @Suppress("unused")
    private infix fun Int.shouldBeLessThan(threshold: Int) {
        check(this < threshold) { "$this should be < $threshold" }
    }
}
