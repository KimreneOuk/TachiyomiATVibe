package eu.kanade.translation.engines.rendering

import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pins [TextLayoutPlanner.computeStrokeWidth] — the single source of truth for
 * outline width after the RC2 fix.
 *
 * RC2 (the "no outline" symptom): the old implementation scaled the estimator's
 * 4.5/3.0 px by `fontSizePx / startSizeEstimate` (~0.05), floored at `1.0*scale`,
 * so fitted text got a sub-pixel outline that vanished under anti-aliasing. The
 * new formula is a pure function of font size: `max(MIN_STROKE_PX*scale, fontSizePx*0.12)`.
 */
class TextLayoutPlannerStrokeTest {

    @Test
    fun `width scales with font size`() {
        // Larger text ⇒ proportionally larger outline (0.12 of font size). Both
        // sizes sit above the 2px floor (boundary ≈ fontSize 16.7) so the 0.12×
        // term wins and width tracks font size linearly.
        val small = TextLayoutPlanner.computeStrokeWidth(fontSizePx = 24f, scale = 1f)
        val large = TextLayoutPlanner.computeStrokeWidth(fontSizePx = 64f, scale = 1f)
        large shouldBe (64f * 0.12f)
        small shouldBe (24f * 0.12f)
        large shouldBeGreaterThan small
    }

    @Test
    fun `width never collapses below the visible floor`() {
        // The RC2 regression: even at a tiny font the outline must stay visible.
        // At fontSize below ~16.67 the 0.12× term is < 2, so the 2px floor wins.
        val tiny = TextLayoutPlanner.computeStrokeWidth(fontSizePx = 4f, scale = 1f)
        tiny shouldBe 2f
        val mid = TextLayoutPlanner.computeStrokeWidth(fontSizePx = 10f, scale = 1f)
        mid shouldBe 2f
    }

    @Test
    fun `floor scales with resolution`() {
        // On a 2× downsampled page (scale=0.5) the floor is 1px so the outline
        // does not dominate small glyphs; at full res (scale=1) it is 2px.
        TextLayoutPlanner.computeStrokeWidth(fontSizePx = 4f, scale = 0.5f) shouldBe 1f
        TextLayoutPlanner.computeStrokeWidth(fontSizePx = 4f, scale = 1f) shouldBe 2f
        TextLayoutPlanner.computeStrokeWidth(fontSizePx = 4f, scale = 2f) shouldBe 4f
    }

    @Test
    fun `width is independent of block stroke width`() {
        // After the fix, computeStrokeWidth takes no block input — the estimator's
        // 4.5/3.0 and any persisted strokeWidth can no longer collapse the outline.
        // Two calls with identical font/scale always agree.
        val a = TextLayoutPlanner.computeStrokeWidth(fontSizePx = 30f, scale = 1f)
        val b = TextLayoutPlanner.computeStrokeWidth(fontSizePx = 30f, scale = 1f)
        a shouldBe b
        a shouldBe (30f * 0.12f) // 3.6px, visibly thicker than the old ~1px
    }
}
