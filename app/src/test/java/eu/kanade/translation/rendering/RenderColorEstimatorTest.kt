package eu.kanade.translation.rendering

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Guards the adaptive text-fill policy of [RenderColorEstimator].
 *
 * The fill defaults to the detected ink color and is forced to pure black or
 * white when its WCAG contrast against the background drops below the AA
 * threshold. This is the fix for "dark background swallows the text": the old
 * gray-snap heuristic forced gray ink to black unconditionally (black-on-dark)
 * and could not reach saturated-dark ink; the WCAG check catches every
 * low-contrast case.
 *
 * These tests exercise the Bitmap-free decision ([colorPolicy]) directly; the
 * 2-means sampling against an `android.graphics.Bitmap` is covered by
 * [RenderColorEstimatorSamplingTest] (Robolectric).
 */
class RenderColorEstimatorTest {

    private val white = 0xFFFFFFFFL
    private val black = 0xFF000000L

    private fun rgb(r: Int, g: Int, b: Int) = floatArrayOf(r.toFloat(), g.toFloat(), b.toFloat())

    @Test
    fun `dark ink on dark background is forced to white`() {
        // Symptom A regression: a dark foreground cluster (near-black ink) on a
        // dark background has contrast well below AA → fill must become white.
        val text = RenderColorEstimator.colorPolicy(
            bgColor = rgb(20, 20, 20),   // dark background
            fgColor = rgb(30, 30, 30),   // dark ink cluster
        )
        text shouldBe white
    }

    @Test
    fun `bright ink on dark background is preserved`() {
        // High-contrast ink (white/near-white on dark) already clears AA → keep
        // the detected ink color rather than overriding.
        val text = RenderColorEstimator.colorPolicy(
            bgColor = rgb(20, 20, 20),
            fgColor = rgb(240, 240, 240),
        )
        text shouldBe 0xFFF0F0F0L
    }

    @Test
    fun `dark ink on light background is preserved`() {
        val text = RenderColorEstimator.colorPolicy(
            bgColor = rgb(230, 230, 230), // light background
            fgColor = rgb(20, 20, 20),    // dark ink
        )
        text shouldBe 0xFF141414L
    }

    @Test
    fun `light ink on light background is forced to black`() {
        // Symptom A (inverse): a light foreground cluster on a light background
        // is low contrast → fill must become black.
        val text = RenderColorEstimator.colorPolicy(
            bgColor = rgb(230, 230, 230),
            fgColor = rgb(220, 220, 220),
        )
        text shouldBe black
    }

    @Test
    fun `saturated low-contrast ink is also forced to B-W`() {
        // Closes the gap the old gray-snap could not reach: a SATURATED but dark
        // ink (saturation above the old 25 threshold) on a dark background. The
        // WCAG check must still force white, since the cluster barely contrasts.
        val text = RenderColorEstimator.colorPolicy(
            bgColor = rgb(15, 15, 15),
            fgColor = rgb(40, 0, 0), // saturated dark red, contrast < AA
        )
        text shouldBe white
    }

    @Test
    fun `high-contrast saturated ink is preserved`() {
        // A vivid color that contrasts well with the background is kept — ink
        // fidelity is only sacrificed for legibility, never preemptively.
        val text = RenderColorEstimator.colorPolicy(
            bgColor = rgb(240, 240, 240), // near-white background
            fgColor = rgb(220, 0, 0),     // saturated red, high contrast
        )
        text shouldBe 0xFFDC0000L
    }

    @Test
    fun `mid-gray ink on mid-gray background is forced to black`() {
        // The legacy illegible case: gray-on-gray. Contrast is ~1.0, far below
        // AA. The lighter background ⇒ black fill.
        val text = RenderColorEstimator.colorPolicy(
            bgColor = rgb(128, 128, 128),
            fgColor = rgb(140, 140, 140),
        )
        text shouldBe black
    }
}
