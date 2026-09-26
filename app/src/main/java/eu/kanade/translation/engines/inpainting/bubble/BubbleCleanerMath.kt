package eu.kanade.translation.engines.inpainting.bubble
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure pixel/math helpers extracted from [SmartBubbleTextCleaner].
 *
 * Operates only on [IntArray]/[ByteArray]/primitives, mirroring the project's
 * established pattern (BoxGeometry, BubbleMaskBuilder, AotOutputGuard). The
 * cleaner keeps its Android-Bitmap shell and working-buffer cache; these are
 * the deterministic JVM-testable utilities behind it.
 */
internal object BubbleCleanerMath {

    /**
     * Scales feather and dilation radii by region size: `featherRadius` /
     * `dilationIterations` are caps, floored at 2/1, scaled by `minDim / 12`
     * and `minDim / 20` respectively so small bubbles are not over-feathered.
     */
    internal fun scaledMorphology(
        minDim: Int,
        featherRadius: Int,
        dilationIterations: Int,
    ): Pair<Int, Int> {
        val feather = featherRadius.coerceAtMost(max(2, minDim / 12)).coerceAtLeast(2)
        val dilation = dilationIterations.coerceAtMost(max(1, minDim / 20)).coerceAtLeast(1)
        return feather to dilation
    }

    /**
     * Scales the text-mask pad (mp) by region size: `max(textMaskPad, min(8,
     * minDim / 8))` so a small bubble whose text fills it does not over-erase
     * past its boundary.
     */
    internal fun scaledTextMaskPad(minDim: Int, textMaskPad: Int): Int =
        max(textMaskPad, min(8, minDim / 8))

    /**
     * Percentile value (0..255) of a 256-bin gray histogram. `percentile` in
     * [0,1]. Walks bins until cumulative count reaches the percentile target.
     */
    internal fun percentileGray(hist: IntArray, count: Int, percentile: Float): Int {
        val target = (count * percentile).roundToInt().coerceIn(1, count)
        var seen = 0
        for (value in hist.indices) {
            seen += hist[value]
            if (seen >= target) return value
        }
        return hist.lastIndex
    }

    /** Summed-area table rectangle sum: `I[d] - I[b] - I[c] + I[a]`. */
    internal fun rectSum(
        integral: LongArray,
        stride: Int,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
    ): Long {
        val a = y1 * stride + x1
        val b = y1 * stride + x2
        val c = y2 * stride + x1
        val d = y2 * stride + x2
        return integral[d] - integral[b] - integral[c] + integral[a]
    }

    /** Per-element OR of two byte masks into a fresh 0/1 [ByteArray]. */
    internal fun unionMasks(a: ByteArray, b: ByteArray): ByteArray {
        val result = ByteArray(min(a.size, b.size))
        for (i in result.indices) {
            if (a[i] != 0.toByte() || b[i] != 0.toByte()) result[i] = 1
        }
        return result
    }

    /**
     * True when the rectangle is dominated by near-white background: median
     * gray > 220 and 75th percentile > 238 (sampled via [percentileGray]).
     */
    internal fun isDominantLightBackground(
        gray: IntArray,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        width: Int,
    ): Boolean {
        val hist = IntArray(256)
        var count = 0
        for (y in y1 until y2) {
            for (x in x1 until x2) {
                hist[gray[y * width + x].coerceIn(0, 255)]++
                count++
            }
        }
        if (count <= 0) return false
        val median = percentileGray(hist, count, 0.50f)
        val p75 = percentileGray(hist, count, 0.75f)
        return median > 220 && p75 > 238
    }

    /**
     * Background tier classifier. Returns one of: `dark_flat`,
     * `dark_lightly_varying`, `dark_textured`, `flat_white`, `flat_colored`,
     * `lightly_varying`, `textured`. Thresholds are pinned by tests.
     */
    internal fun classifyBackground(stats: SmartBubbleTextCleaner.BackgroundStats): String {
        if (stats.darkPixelRatio > 0.5f || stats.grayMean < 80f) {
            if (stats.grayStd < 18f && stats.edgeDensity < 0.06f) return "dark_flat"
            if (stats.grayStd < 35f && stats.edgeDensity < 0.12f) return "dark_lightly_varying"
            return "dark_textured"
        }
        if (stats.nearWhiteRatio > 0.7f && stats.grayStd < 20f) return "flat_white"
        if (stats.grayStd < 15f && stats.nearWhiteRatio <= 0.7f) return "flat_colored"
        if (stats.grayStd < 35f && stats.edgeDensity < 0.08f) return "lightly_varying"
        return "textured"
    }

    /**
     * Solid flat-fill gate: true for `flat_white`, or for `flat_colored`/
     * `dark_flat` with very low variance (`grayStd < 10`, `edgeDensity < 0.04`).
     */
    internal fun shouldUseSolidFlatFill(
        bgType: String,
        stats: SmartBubbleTextCleaner.BackgroundStats,
    ): Boolean =
        bgType == "flat_white" ||
            (
                (bgType == "flat_colored" || bgType == "dark_flat") &&
                    stats.grayStd < 10f &&
                    stats.edgeDensity < 0.04f
                )
}
