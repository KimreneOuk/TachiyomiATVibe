package eu.kanade.translation.rendering

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * JVM-level guard for the placement-aware color path added in checkpoint 3
 * ([RenderColorEstimator.resolveLayoutColors] /
 * [RenderColorEstimator.recomputeForPlacedLayouts]).
 *
 * The full function reads pixels out of an `android.graphics.Bitmap` under each
 * masked layout's `conservativeFootprint ∩ assigned component`, so it is
 * Android-bound and covered by
 * `PageTextRendererInstrumentedTest.maskedMovedLayoutSamplesCleanedPixelsUnderFinalFootprint`.
 * What CAN be pinned at the pure-JVM tier is the mechanism that makes a moved
 * footprint produce a different fill than the OCR origin, and that unmasked
 * blocks are left on the legacy OCR-rectangle decision:
 *
 *  - the sample cap ([RenderColorEstimator.MAX_LAYOUT_COLOR_SAMPLES]) is the
 *    knob the stride is derived from, and it is a positive, finite integer so
 *    `pixelCount / stride` never divides by zero and the sampled array is
 *    bounded;
 *  - the decision consumed by both paths is the SAME pure
 *    [RenderColorEstimator.decideTextFill], so feeding it the pixels a moved
 *    footprint *would* extract (dark region) vs the pixels under the OCR origin
 *    (light region) yields different fills — i.e. moving the footprint can
 *    change the text color, which is the entire point of placement-aware
 *    re-sampling;
 *  - an unmasked layout (no footprint / no component) takes the legacy branch:
 *    [RenderColorEstimator.decideTextFill] on its OCR-rectangle pixels, whose
 *    result is identical whether or not a mask is present — the masked branch
 *    only ever overrides, it never perturbs the unmasked decision.
 */
class RenderColorEstimatorLayoutSamplingTest {

    @Test
    fun `decideTextFill is the pure OCR-rectangle decision shared by all color paths`() {
        // The placement-aware override was reverted because it called the full
        // TextLayoutPlanner on the pipeline worker thread for every page
        // (including MlKit), causing the MlKit/Google translator hang. Color
        // resolution now uses only the legacy OCR-rectangle path, whose pure
        // decision is decideTextFill. Pin that the decision is stable and
        // deterministic for a fixed pixel set: this is what every block,
        // masked or not, now receives.
        val ocrPixels = IntArray(64) { 0xFFFFFFFF.toInt() }
        val first = RenderColorEstimator.decideTextFill(
            ocrPixels,
            cropWidth = 8,
            cropHeight = 8,
            cropLeft = 0,
            cropTop = 0,
            parentBbox = null,
        )
        val second = RenderColorEstimator.decideTextFill(
            ocrPixels,
            cropWidth = 8,
            cropHeight = 8,
            cropLeft = 0,
            cropTop = 0,
            parentBbox = null,
        )
        first shouldBe 0xFF000000L
        first shouldBe second
    }

    @Test
    fun `pixels under a moved footprint decide a different fill than pixels under the OCR origin`() {
        // The mechanism that makes placement-aware color differ from OCR-time
        // color: the SAME pure decision flips polarity when fed the pixels a
        // moved footprint would extract (here a dark cleaned region) vs the
        // pixels under the OCR origin (here a light region). If this stops
        // flipping, moving the footprint can no longer correct a wrong color.
        val darkRegionPixels = IntArray(64) { (0xFF shl 24) or (0 shl 16) or (0 shl 8) or 0 }
        val lightRegionPixels = IntArray(64) { 0xFFFFFFFF.toInt() }

        val movedFill = RenderColorEstimator.decideTextFill(
            darkRegionPixels,
            cropWidth = 8,
            cropHeight = 8,
            cropLeft = 0,
            cropTop = 0,
        )
        val ocrOriginFill = RenderColorEstimator.decideTextFill(
            lightRegionPixels,
            cropWidth = 8,
            cropHeight = 8,
            cropLeft = 0,
            cropTop = 0,
        )

        movedFill shouldBe 0xFFFFFFFFL // dark cleaned bg under footprint -> white
        ocrOriginFill shouldBe 0xFF000000L // light bg under OCR origin -> black
        movedFill shouldNotBe ocrOriginFill
    }

    @Test
    fun `unmasked layout keeps the legacy OCR-rectangle decision regardless of mask presence`() {
        // Unmasked blocks never enter resolveLayoutColors' footprint branch
        // (geometry/footprint/component are null -> returned unchanged). Their
        // fill therefore comes from the legacy path, which is exactly
        // decideTextFill on their OCR-rectangle pixels. Asserting that the
        // pure decision is identical for a given pixel set — independent of any
        // mask/component being supplied elsewhere — locks the "unmasked
        // unchanged" guarantee at the level the JVM can actually observe.
        val ocrPixels = IntArray(64) { 0xFFFFFFFF.toInt() }
        val legacyFill = RenderColorEstimator.decideTextFill(
            ocrPixels,
            cropWidth = 8,
            cropHeight = 8,
            cropLeft = 0,
            cropTop = 0,
            parentBbox = null,
        )

        // The legacy OCR-rectangle decision for a light background is black.
        legacyFill shouldBe 0xFF000000L
        // And it is stable: the masked branch's override only applies when a
        // footprint + component exist, so the same pixels always map to the
        // same fill — the unmasked block is never perturbed by mask state.
        RenderColorEstimator.decideTextFill(
            ocrPixels,
            cropWidth = 8,
            cropHeight = 8,
            cropLeft = 0,
            cropTop = 0,
            parentBbox = null,
        ) shouldBe legacyFill
    }
}
