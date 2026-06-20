package eu.kanade.translation.rendering

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Guards the pure text/stroke color policy of [RenderColorEstimator], the
 * helper that picks the color rendered text is drawn in over a cleaned bubble.
 *
 * The legacy bug it guards: both the ROI and ML Kit estimators had a
 * copy-pasted `INVERTED_TEXT_COLORS` constant that returned the SAME dark-gray
 * text color as the non-inverted path, so dark-inpainted bubbles got dark-gray
 * text on top → illegible. The fix re-derives colors post-inpaint and snaps
 * low-saturation mid-gray text to pure black.
 *
 * These tests exercise the Bitmap-free decision ([colorPolicy] / [snapGray])
 * directly; the 2-means sampling against an `android.graphics.Bitmap` is left
 * to instrumented tests.
 */
class RenderColorEstimatorTest {

    private val whiteText = 0xFFFFFFFFL
    private val blackStroke = 0xFF000000L
    private val blackText = 0xFF000000L
    private val whiteStroke = 0xFFFFFFFFL

    @Test
    fun `dark background yields white text with a black stroke`() {
        // Luma 20 is well below the dark-background threshold (85).
        val (text, stroke, _) = RenderColorEstimator.colorPolicy(bgLuma = 20f)

        text shouldBe whiteText
        stroke shouldBe blackStroke
    }

    @Test
    fun `light background yields black text with a white stroke`() {
        // Luma 200 is well above the dark-background threshold.
        val (text, stroke, _) = RenderColorEstimator.colorPolicy(bgLuma = 200f)

        text shouldBe blackText
        stroke shouldBe whiteStroke
    }

    @Test
    fun `snapGray leaves saturated colors untouched`() {
        // A saturated color (e.g. red) is NOT gray and must be preserved even if
        // its luma is mid-range. Construct a red text triple and confirm no snap.
        val redText = Triple(0xFFFF0000L, whiteStroke, 3.0f)

        val snapped = RenderColorEstimator.snapGray(redText)

        snapped.first shouldBe 0xFFFF0000L
    }

    @Test
    fun `snapGray turns a low-saturation mid-gray text color to pure black`() {
        // The legacy illegible case: mid-gray (0x808080) text on a mid-gray
        // inpaint. Luma ~128 sits in the gray band and saturation is 0 → snap.
        val grayText = Triple(0xFF808080L, whiteStroke, 3.0f)

        val snapped = RenderColorEstimator.snapGray(grayText)

        snapped.first shouldBe blackText
        // Stroke is preserved.
        snapped.second shouldBe whiteStroke
    }

    @Test
    fun `snapGray keeps the stroke and width of a saturated triple`() {
        val colored = Triple(0xFF0000FFL, 0xFFFFFFFFL, 4.5f)

        val snapped = RenderColorEstimator.snapGray(colored)

        snapped.second shouldBe 0xFFFFFFFFL
        snapped.third shouldBe 4.5f
    }
}
