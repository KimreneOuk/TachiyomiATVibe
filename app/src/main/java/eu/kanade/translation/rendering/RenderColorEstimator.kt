package eu.kanade.translation.rendering

import android.graphics.Bitmap
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.inpainting.BoundaryAwarePipeline
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * TachiyomiAT: shared text-color / stroke-color / stroke-width estimator.
 *
 * Background
 * ----------
 * Previously two near-duplicate copies of this logic existed:
 *   - RoiPageRecognitionEngine.computeRenderColors  (ONNX path)
 *   - MlKitFullPageRecognitionEngine.computeContrastColors  (ML Kit path)
 * and they had diverged in a critical way: the ROI path's INVERTED_TEXT_COLORS
 * constant was a copy-paste of PYTHON_DEFAULT_TEXT_COLORS (both returned the
 * SAME dark-gray text color 0xFF1A1A1A), so dark-inpainted bubbles got dark-gray
 * text on top → illegible. The ML Kit copy had the correct inverted value but
 * was only reachable in fallback mode (where inpaint always returns FAILED).
 *
 * Worse, color was sampled against the ORIGINAL decoded bitmap at recognition
 * time, BEFORE inpainting. The inpainter may replace the box background with a
 * different median color (e.g. filling a grayscale panel with mid-gray), so the
 * text color chosen against the original background no longer matches what the
 * user actually sees behind the rendered text. The fix is to re-derive colors
 * against the CLEANED bitmap post-inpaint — this object exposes [recomputeFor]
 * for exactly that, called from ChapterTranslator right before render().
 *
 * Behavior
 * --------
 * - 2-means cluster on a padded crop around the box to find the dominant
 *   background cluster, then luma-based contrast selection:
 *     dark background  → white text (0xFFFFFFFF) + black stroke (0xFF000000)
 *     light background → black text (0xFF000000) + white stroke (0xFFFFFFFF)
 * - Gray snap: any chosen text color that lands in the low-saturation mid-luma
 *   "gray band" snaps to pure black (0xFF000000). Mid-gray text (e.g. the old
 *   0xFF1A1A1A) on a mid-gray inpaint is the worst legibility case; snapping to
 *   black removes the ambiguity. Saturated colors (e.g. blue on a colored
 *   bubble) are preserved as-is.
 */
object RenderColorEstimator {

    // (textColor, strokeColor, strokeWidth)
    private val DARK_BG_COLORS = Triple(0xFFFFFFFF, 0xFF000000, 4.5f) // white text on dark
    private val LIGHT_BG_COLORS = Triple(0xFF000000, 0xFFFFFFFF, 3.0f) // black text on light

    // Luma thresholds for background classification (Rec.601 weights).
    private const val DARK_BG_LUMA = 85f

    /**
     * Pure, Bitmap-free color decision: given the dominant background luma
     * (Rec.601, 0..255), pick (textColor, strokeColor, strokeWidth) and apply
     * the gray-snap. Extracted from [estimate] so the policy is unit-testable
     * without an `android.graphics.Bitmap`.
     *
     *  - dark background  → white text + black stroke (wide)
     *  - light background → black text + white stroke (narrower)
     *  - then snap any low-saturation mid-gray text color to pure black.
     */
    internal fun colorPolicy(bgLuma: Float): Triple<Long, Long, Float> {
        val base = if (bgLuma < DARK_BG_LUMA) DARK_BG_COLORS else LIGHT_BG_COLORS
        return snapGray(base)
    }

    /**
     * Estimate (textColor, strokeColor, strokeWidth) for a single bounding box
     * on [bitmap]. [bitmap] may be the original decoded page (recognition-time
     * first pass) or the cleaned/inpainted page (post-inpaint recompute).
     *
     * [parentBbox] is the enclosing speech-bubble box if this text lives inside
     * a bubble, else null. It is no longer used to SHORT-CIRCUIT the contrast
     * check (the old behavior always returned dark-gray text for bubble text,
     * which is exactly wrong for dark-filled bubbles) — it is kept on the
     * signature for caller compatibility and may be used to widen the sampling
     * pad when the text box is tiny.
     */
    fun estimate(
        bitmap: Bitmap,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        parentBbox: IntArray? = null,
    ): Triple<Long, Long, Float> {
        val boxW = max(1, x2 - x1)
        val boxH = max(1, y2 - y1)
        val basePad = max(12, min(boxW, boxH) / 2)
        val pad = if (parentBbox != null) max(basePad, 16) else basePad
        val left = (x1 - pad).coerceIn(0, bitmap.width)
        val top = (y1 - pad).coerceIn(0, bitmap.height)
        val right = (x2 + pad).coerceIn(left, bitmap.width)
        val bottom = (y2 + pad).coerceIn(top, bitmap.height)
        val cropW = right - left
        val cropH = bottom - top
        if (cropW <= 0 || cropH <= 0) return LIGHT_BG_COLORS

        val pixels = IntArray(cropW * cropH)
        bitmap.getPixels(pixels, 0, cropW, left, top, cropW, cropH)

        // TachiyomiAT: containment-aware 2-means. When a parent bubble is known,
        // build the eroded bubble interior mask and sample only those pixels so
        // the background estimate comes from inside the bubble — not from artwork
        // outside the bubble that the sampling pad may have reached.
        val sampleMask: ByteArray? = if (parentBbox != null) {
            val bw = parentBbox[2] - parentBbox[0]
            val bh = parentBbox[3] - parentBbox[1]
            val erode = max(2, min(bw, bh) / 10)
            val sx1 = max(0, parentBbox[0] + erode - left)
            val sy1 = max(0, parentBbox[1] + erode - top)
            val sx2 = min(cropW, parentBbox[2] - erode - left)
            val sy2 = min(cropH, parentBbox[3] - erode - top)
            if (sx2 > sx1 && sy2 > sy1) {
                ByteArray(cropW * cropH).also { mask ->
                    for (y in sy1 until sy2) {
                        for (x in sx1 until sx2) {
                            mask[y * cropW + x] = 1
                        }
                    }
                }
            } else null
        } else null

        val step = max(1, pixels.size / 1200)
        val bgColor = dominantBackgroundCluster(pixels, step, sampleMask)
        val brightness = 0.299f * bgColor[0] + 0.587f * bgColor[1] + 0.114f * bgColor[2]
        return colorPolicy(brightness)
    }

    /**
     * 2-means (seeded black vs white, 5 iterations) to find the dominant
     * background cluster of [pixels] sampled every [step]. When [sampleMask]
     * is non-null, only pixels where `sampleMask[i] != 0` are considered.
     */
    private fun dominantBackgroundCluster(
        pixels: IntArray,
        step: Int,
        sampleMask: ByteArray? = null,
    ): FloatArray {
        var center0 = floatArrayOf(0f, 0f, 0f)
        var center1 = floatArrayOf(255f, 255f, 255f)
        var count0 = 0
        var count1 = 0

        repeat(5) {
            var sum0 = floatArrayOf(0f, 0f, 0f)
            var sum1 = floatArrayOf(0f, 0f, 0f)
            count0 = 0
            count1 = 0
            for (i in pixels.indices step step) {
                if (sampleMask != null && sampleMask[i] == 0.toByte()) continue
                val pixel = pixels[i]
                val r = (pixel shr 16 and 0xFF).toFloat()
                val g = (pixel shr 8 and 0xFF).toFloat()
                val b = (pixel and 0xFF).toFloat()
                val d0 = (r - center0[0]).pow(2) + (g - center0[1]).pow(2) + (b - center0[2]).pow(2)
                val d1 = (r - center1[0]).pow(2) + (g - center1[1]).pow(2) + (b - center1[2]).pow(2)
                if (d0 < d1) {
                    sum0[0] += r
                    sum0[1] += g
                    sum0[2] += b
                    count0++
                } else {
                    sum1[0] += r
                    sum1[1] += g
                    sum1[2] += b
                    count1++
                }
            }
            if (count0 > 0) {
                center0[0] = sum0[0] / count0
                center0[1] = sum0[1] / count0
                center0[2] = sum0[2] / count0
            }
            if (count1 > 0) {
                center1[0] = sum1[0] / count1
                center1[1] = sum1[1] / count1
                center1[2] = sum1[2] / count1
            }
        }
        return if (count0 >= count1) center0 else center1
    }

    /**
     * Re-derive [blocks]' textColor/strokeColor/strokeWidth against [cleanedBitmap]
     * (the post-inpaint bitmap the renderer will draw on). Mutates each block
     * in place. Skip blocks whose translation text is blank (the renderer skips
     * them anyway). Safe to call with a null bitmap (no-op).
     *
     * This is the call that makes "dark inpaint → light text, light inpaint →
     * dark text" actually hold, because the color is now sampled from the SAME
     * background the user will see behind the rendered text — not the original.
     */
    fun recomputeFor(cleanedBitmap: Bitmap?, blocks: List<TranslationBlock>) {
        if (cleanedBitmap == null) return
        for (block in blocks) {
            // Only blocks that will actually render need a recompute.
            if (block.translation.isBlank() && block.text.isBlank()) continue
            val parent = if (block.parentWidth > 0f && block.parentHeight > 0f) {
                intArrayOf(
                    block.parentX.toInt(),
                    block.parentY.toInt(),
                    (block.parentX + block.parentWidth).toInt(),
                    (block.parentY + block.parentHeight).toInt(),
                )
            } else {
                null
            }
            val (text, stroke, width) = estimate(
                cleanedBitmap,
                block.x.toInt(),
                block.y.toInt(),
                (block.x + block.width).toInt(),
                (block.y + block.height).toInt(),
                parent,
            )
            block.textColor = text
            block.strokeColor = stroke
            // Keep the estimator's stroke width if the block had none, else
            // preserve any explicit per-block override.
            if (block.strokeWidth <= 0f) block.strokeWidth = width
        }
    }

    /**
     * Snap a (text, stroke, width) triple's TEXT color to pure black when it
     * falls in the low-saturation gray band. The intent: mid-gray text (e.g.
     * the legacy 0xFF1A1A1A) on a mid-gray inpaint is illegible, and there is
     * no good reason to render gray text — snap to black. Saturated colors
     * (e.g. a deliberately colored translation) are preserved.
     *
     * Gray-snap band: a chosen text color with luma in
     * [GRAY_MIN_LUMA, GRAY_MAX_LUMA] AND channel spread ≤ GRAY_MAX_SAT snaps
     * to pure black. `internal` so the policy is unit-testable.
     */
    internal fun snapGray(triple: Triple<Long, Long, Float>): Triple<Long, Long, Float> {
        val textArgb = triple.first
        val a = (textArgb shr 24 and 0xFF).toInt()
        val r = (textArgb shr 16 and 0xFF).toInt()
        val g = (textArgb shr 8 and 0xFF).toInt()
        val b = (textArgb and 0xFF).toInt()
        val luma = 0.299f * r + 0.587f * g + 0.114f * b
        val maxC = max(max(r, g), b)
        val minC = min(min(r, g), b)
        val sat = (maxC - minC).toFloat()
        if (luma in GRAY_MIN_LUMA..GRAY_MAX_LUMA && sat <= GRAY_MAX_SAT) {
            // Snap text to pure black, keep stroke unchanged.
            val blackText = (a.toLong() shl 24) or 0x000000L
            return Triple(blackText, triple.second, triple.third)
        }
        return triple
    }

    // Gray-snap band. A chosen text color in [GRAY_MIN_LUMA, GRAY_MAX_LUMA] AND
    // with low saturation is snapped to pure black — mid-gray text on a
    // mid-gray inpaint is illegible and there is no reason to keep it gray.
    private const val GRAY_MIN_LUMA = 40f
    private const val GRAY_MAX_LUMA = 200f
    private const val GRAY_MAX_SAT = 25f // max channel spread for "gray"
}
