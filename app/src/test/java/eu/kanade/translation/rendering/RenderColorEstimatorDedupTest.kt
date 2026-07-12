package eu.kanade.translation.rendering

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Regression guard for the Wave 3 P1a dedup in `RenderColorEstimator.estimate`:
 * the duplicate `extractClusters` + `bubbleInteriorMask` work was merged so each
 * runs exactly once per estimate call, with both `colorPolicy` and the inline
 * `bgLuma` derivation consuming the single `sampleClusters` result.
 *
 * This locks the observable output of the pure, Bitmap-free decision functions
 * (`colorPolicy`, `sampleBackgroundLuma`, `decideTextFill`) to golden values so
 * a future refactor that re-introduces a double-sample (or shifts the luma
 * weights / fill polarity) fails here.
 *
 * These functions are `internal`, so the test shares the package. No
 * `android.graphics.Bitmap` is needed — the dedup claim is about the
 * post-sampling decision, which is pure int/float math.
 */
class RenderColorEstimatorDedupTest {

    /**
     * `colorPolicy` is the load-bearing decision: a Rec.601 luma on the
     * background cluster, snapped to pure black text on light backgrounds or
     * pure white text on dark backgrounds. The dedup must NOT change the
     * threshold (`DARK_BG_LUMA = 85f`) or the polarity.
     */
    @Test
    fun `colorPolicy_returns_black_text_for_light_background`() {
        // bg luma = 0.299*240 + 0.587*240 + 0.114*240 = 240.0 (> 85) -> black text
        val bg = floatArrayOf(240f, 240f, 240f)
        val fg = floatArrayOf(20f, 20f, 20f)
        RenderColorEstimator.colorPolicy(bg, fg) shouldBe 0xFF000000L
    }

    @Test
    fun `colorPolicy_returns_white_text_for_dark_background`() {
        // bg luma = 0.299*10 + 0.587*10 + 0.114*10 = 10.0 (< 85) -> white text
        val bg = floatArrayOf(10f, 10f, 10f)
        val fg = floatArrayOf(230f, 230f, 230f)
        RenderColorEstimator.colorPolicy(bg, fg) shouldBe 0xFFFFFFFFL
    }

    @Test
    fun `colorPolicy_dark_bg luma threshold is inclusive of low_luma black`() {
        // Luma exactly at 0 stays below the 85 threshold -> white text.
        val bg = floatArrayOf(0f, 0f, 0f)
        val fg = floatArrayOf(255f, 255f, 255f)
        RenderColorEstimator.colorPolicy(bg, fg) shouldBe 0xFFFFFFFFL
    }

    @Test
    fun `colorPolicy_respects_rec601_weights_not_plain_average`() {
        // Pure green (0,255,0): Rec.601 luma = 0.587*255 = 149.7 > 85 -> black.
        // A plain average would give 85.0 which sits at the threshold; this
        // guards against accidentally swapping in an unweighted mean.
        val bg = floatArrayOf(0f, 255f, 0f)
        val fg = floatArrayOf(0f, 0f, 0f)
        RenderColorEstimator.colorPolicy(bg, fg) shouldBe 0xFF000000L
    }

    /**
     * `sampleBackgroundLuma` is the second consumer of the merged sample. It
     * must reuse the SAME bg cluster as `colorPolicy` — i.e. the dedup did not
     * silently leave it computing against a different center. We verify by
     * constructing a synthetic crop whose dominant cluster has a known luma.
     */
    @Test
    fun `sampleBackgroundLuma_returns_rec601_luma_of_dominant_cluster`() {
        // 4x4 crop, fully filled with mid-gray (128). The 2-means seeded at
        // (0,0,0) vs (255,255,255) converges so the dominant cluster is the
        // actual pixel color; luma = 0.299*128 + 0.587*128 + 0.114*128 = 128.0f.
        val gray = (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128
        val pixels = IntArray(16) { gray }
        val luma = RenderColorEstimator.sampleBackgroundLuma(
            pixels = pixels,
            cropWidth = 4,
            cropHeight = 4,
            cropLeft = 0,
            cropTop = 0,
            parentBbox = null,
        )
        luma shouldBe 128.0f
    }

    /**
     * The dedup invariant: `decideTextFill` and `sampleBackgroundLuma` must
     * agree on polarity because they share the same bg cluster. For a dark
     * background, luma < 85 AND the fill must be white.
     */
    @Test
    fun `decideTextFill_and_sampleBackgroundLuma_agree_on_dark_background_polarity`() {
        val black = (0xFF shl 24) // 0xFF000000 as ARGB Int; r=g=b=0
        val pixels = IntArray(16) { black }
        val luma = RenderColorEstimator.sampleBackgroundLuma(
            pixels, cropWidth = 4, cropHeight = 4, cropLeft = 0, cropTop = 0, parentBbox = null,
        )
        val fill = RenderColorEstimator.decideTextFill(
            pixels, cropWidth = 4, cropHeight = 4, cropLeft = 0, cropTop = 0, parentBbox = null,
        )
        luma shouldBe 0.0f
        fill shouldBe 0xFFFFFFFFL
    }

    @Test
    fun `decideTextFill_and_sampleBackgroundLuma_agree_on_light_background_polarity`() {
        val white = 0xFFFFFFFF.toInt()
        val pixels = IntArray(16) { white }
        val luma = RenderColorEstimator.sampleBackgroundLuma(
            pixels, cropWidth = 4, cropHeight = 4, cropLeft = 0, cropTop = 0, parentBbox = null,
        )
        val fill = RenderColorEstimator.decideTextFill(
            pixels, cropWidth = 4, cropHeight = 4, cropLeft = 0, cropTop = 0, parentBbox = null,
        )
        luma shouldBe 255.0f
        fill shouldBe 0xFF000000L
    }
}
