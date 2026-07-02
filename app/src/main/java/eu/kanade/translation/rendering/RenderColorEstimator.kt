package eu.kanade.translation.rendering

import android.graphics.Bitmap
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.inpainting.BoundaryAwarePipeline
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * TachiyomiAT: shared text-color estimator.
 *
 * Background
 * ----------
 * Color is sampled against the ORIGINAL decoded bitmap at recognition time
 * (first pass), then re-derived against the CLEANED bitmap post-inpaint via
 * [recomputeFor] (the authoritative pass). The inpainter may replace the box
 * background with a different color (e.g. filling a grayscale panel with
 * mid-gray, or — in AOT quality mode — reconstructing heterogeneous artwork),
 * so the color chosen against the original no longer matches what the user
 * actually sees behind the rendered text. [recomputeFor] re-samples against
 * the cleaned bitmap right before render() so the decision reflects reality.
 *
 * Behavior (adaptive contrast fill)
 * ---------------------------------
 * A seeded 2-means on a padded crop splits the sample into a dominant
 * background cluster and a foreground (ink) cluster. The text FILL defaults to
 * the detected ink color; if its WCAG contrast against the background falls
 * below [CONTRAST_AA] it is forced to pure black/white so the text is always
 * legible regardless of what inpainting produced. This is the fix for "dark
 * background swallows the text": the old gray-snap heuristic forced gray ink
 * to black unconditionally (black-on-dark) and could not reach saturated-dark
 * ink; a WCAG check catches every low-contrast case.
 *
 * NOTE: this object owns only the text FILL color. The stroke color is owned
 * by [PageTextRenderer] (luma-inverse of the text) and the stroke width by
 * [TextLayoutPlanner] (a fraction of font size). The Triple returned from
 * [estimate] still carries stroke/width for caller compatibility, but those
 * fields are vestigial and overridden downstream.
 */
object RenderColorEstimator {

    // WCAG 2.1 contrast ratio a text fill must meet against its background to be
    // kept as-is; below this the fill is forced to pure black/white. 4.5 is the
    // AA threshold for normal text; conservative for the "readable without
    // zoom" requirement. The one tuning knob — see Phase 2 measurement.
    private const val CONTRAST_AA = 4.5f

    // Rec.601 background-luma threshold (0..255) used to pick black-vs-white
    // when the ink color is forced. Below this the background is "dark" → white.
    private const val DARK_BG_LUMA = 85f

    /**
     * Pure, Bitmap-free text-color decision, extracted from [estimate] so the
     * policy is unit-testable without an `android.graphics.Bitmap`.
     *
     * Takes the dominant background and foreground (ink) cluster colors (RGB in
     * 0..255). The fill defaults to the detected ink color; if its WCAG contrast
     * against the background is below [CONTRAST_AA] it is forced to pure black
     * or white (whichever contrasts more with the background) so the text is
     * always legible.
     *
     * Returns the text ARGB. Stroke color and width are NOT decided here — they
     * are owned by [PageTextRenderer] and [TextLayoutPlanner] respectively.
     */
    internal fun colorPolicy(bgColor: FloatArray, fgColor: FloatArray): Long {
        val textArgb = packArgb(fgColor)
        val ratio = contrastRatio(bgColor, fgColor)
        if (ratio >= CONTRAST_AA) return textArgb

        // Ink is too close to the background to read. Force the high-contrast
        // pole. bgLuma picks the pole that contrasts MORE with the background:
        // a dark background gets white text, a light background gets black text.
        val bgLuma = 0.299f * bgColor[0] + 0.587f * bgColor[1] + 0.114f * bgColor[2]
        val forced = if (bgLuma < DARK_BG_LUMA) 0xFFFFFFFFL else 0xFF000000L
        // Observability: log every legibility override so the AA threshold can be
        // tuned from real data. ratio<AA is exactly the "text was unreadable" case.
        logcat(LogPriority.DEBUG) {
            "[color] contrast override: ratio=%.2f < %.1f; ink=rgb(%d,%d,%d) bg=rgb(%d,%d,%d) -> %s".format(
                ratio, CONTRAST_AA,
                fgColor[0].toInt(), fgColor[1].toInt(), fgColor[2].toInt(),
                bgColor[0].toInt(), bgColor[1].toInt(), bgColor[2].toInt(),
                if (forced == 0xFFFFFFFFL) "white" else "black",
            )
        }
        return forced
    }

    private fun packArgb(rgb: FloatArray): Long {
        val r = rgb[0].toInt().coerceIn(0, 255)
        val g = rgb[1].toInt().coerceIn(0, 255)
        val b = rgb[2].toInt().coerceIn(0, 255)
        return (0xFFL shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
    }

    /**
     * WCAG 2.1 contrast ratio (1.0..21.0) between two colors. Uses the proper
     * sRGB-relative luminance: linearize each channel via the sRGB gamma, then
     * weighted sum, then `(Lmax+0.05)/(Lmin+0.05)`. Inputs are sRGB in 0..255.
     */
    private fun contrastRatio(a: FloatArray, b: FloatArray): Float {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val (hi, lo) = if (la >= lb) la to lb else lb to la
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun relativeLuminance(rgb: FloatArray): Float {
        var lr = 0f
        var lg = 0f
        var lb = 0f
        for (i in 0..2) {
            val c = rgb[i] / 255f
            val linear = if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055) / 1.055), 2.4).toFloat()
            when (i) {
                0 -> lr = linear
                1 -> lg = linear
                2 -> lb = linear
            }
        }
        return 0.2126f * lr + 0.7152f * lg + 0.0722f * lb
    }

    /**
     * Estimate (textColor, strokeColor, strokeWidth) for a single bounding box
     * on [bitmap]. [bitmap] may be the original decoded page (recognition-time
     * first pass) or the cleaned/inpainted page (post-inpaint recompute).
     *
     * [parentBbox] is the enclosing speech-bubble box if this text lives inside
     * a bubble, else null. When set, the sample is restricted to the eroded
     * bubble interior (so artwork outside the bubble does not contaminate the
     * background estimate) and the pad is widened so a tiny box still captures
     * enough of that interior. When null (SFX / free-floating text) the pad is
     * kept tight to the box so the 2-means background reflects the LOCAL region
     * rather than reaching into adjacent artwork.
     *
     * Only the text color is genuinely decided here; the returned stroke color
     * and width are vestigial (synthesized for caller compatibility) and are
     * overridden by [PageTextRenderer] / [TextLayoutPlanner].
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
        // F4: unparented text (SFX / free text) has no bubble interior to lean
        // on, so a wide pad reaches into adjacent artwork and contaminates the
        // background cluster → wrong polarity → illegible text. Keep the
        // unparented pad tight (1/8 of the shorter side) so the sample stays in
        // the local region. Parented text widens as before to capture the
        // (masked) bubble interior.
        val basePad = if (parentBbox != null) max(12, min(boxW, boxH) / 2) else max(2, min(boxW, boxH) / 8)
        val pad = if (parentBbox != null) max(basePad, 16) else basePad
        val left = (x1 - pad).coerceIn(0, bitmap.width)
        val top = (y1 - pad).coerceIn(0, bitmap.height)
        val right = (x2 + pad).coerceIn(left, bitmap.width)
        val bottom = (y2 + pad).coerceIn(top, bitmap.height)
        val cropW = right - left
        val cropH = bottom - top
        if (cropW <= 0 || cropH <= 0) return Triple(0xFF000000L, 0xFFFFFFFFL, 0f)

        val pixels = IntArray(cropW * cropH)
        bitmap.getPixels(pixels, 0, cropW, left, top, cropW, cropH)

        val textArgb = decideTextFill(pixels, cropW, cropH, left, top, parentBbox)
        // Stroke color mirrors the renderer's luma-inverse invariant so the
        // persisted value is correct if ever read directly; width is vestigial
        // (TextLayoutPlanner derives it from font size).
        val bgLuma = sampleBackgroundLuma(pixels, cropW, cropH, left, top, parentBbox)
        val strokeArgb = if (bgLuma < DARK_BG_LUMA) 0xFF000000L else 0xFFFFFFFFL
        return Triple(textArgb, strokeArgb, 0f)
    }

    /**
     * The pure post-sampling decision: 2-means bg/fg split of [pixels] (optionally
     * masked to the eroded parent-bubble interior) → [colorPolicy] adaptive fill.
     * Extracted from [estimate] so the full buggy code path (the part that
     * actually decides the text color, including the contrast override) is
     * unit-testable without an `android.graphics.Bitmap`. [pixels] is the
     * crop's ARGB data; [cropLeft]/[cropTop] are its offset in page coordinates
     * so the eroded bubble interior mask can be built.
     */
    internal fun decideTextFill(
        pixels: IntArray,
        cropW: Int,
        cropH: Int,
        cropLeft: Int,
        cropTop: Int,
        parentBbox: IntArray? = null,
    ): Long {
        val sampleMask = bubbleInteriorMask(parentBbox, cropW, cropH, cropLeft, cropTop)
        val step = max(1, pixels.size / 1200)
        val (bgColor, fgColor) = extractClusters(pixels, step, sampleMask)
        return colorPolicy(bgColor, fgColor)
    }

    /**
     * Rec.601 luma (0..255) of the dominant background cluster. Used to derive
     * the (vestigial) persisted stroke color. Pure: takes the sampled pixels.
     */
    internal fun sampleBackgroundLuma(
        pixels: IntArray,
        cropW: Int,
        cropH: Int,
        cropLeft: Int,
        cropTop: Int,
        parentBbox: IntArray? = null,
    ): Float {
        val sampleMask = bubbleInteriorMask(parentBbox, cropW, cropH, cropLeft, cropTop)
        val step = max(1, pixels.size / 1200)
        val (bgColor, _) = extractClusters(pixels, step, sampleMask)
        return 0.299f * bgColor[0] + 0.587f * bgColor[1] + 0.114f * bgColor[2]
    }

    private fun bubbleInteriorMask(
        parentBbox: IntArray?,
        cropW: Int,
        cropH: Int,
        cropLeft: Int,
        cropTop: Int,
    ): ByteArray? {
        if (parentBbox == null) return null
        // Containment-aware 2-means: restrict the sample to the eroded bubble
        // interior so the background estimate comes from inside the bubble, not
        // from artwork outside it that the padded crop may have reached.
        val bw = parentBbox[2] - parentBbox[0]
        val bh = parentBbox[3] - parentBbox[1]
        val erode = max(2, min(bw, bh) / 10)
        val sx1 = max(0, parentBbox[0] + erode - cropLeft)
        val sy1 = max(0, parentBbox[1] + erode - cropTop)
        val sx2 = min(cropW, parentBbox[2] - erode - cropLeft)
        val sy2 = min(cropH, parentBbox[3] - erode - cropTop)
        if (sx2 <= sx1 || sy2 <= sy1) return null
        return ByteArray(cropW * cropH).also { mask ->
            for (y in sy1 until sy2) {
                for (x in sx1 until sx2) {
                    mask[y * cropW + x] = 1
                }
            }
        }
    }

    /**
     * 2-means (seeded black vs white, 5 iterations) to find the dominant
     * background and foreground clusters of [pixels] sampled every [step]. When [sampleMask]
     * is non-null, only pixels where `sampleMask[i] != 0` are considered.
     * Returns Pair(backgroundColor, foregroundColor).
     */
    private fun extractClusters(
        pixels: IntArray,
        step: Int,
        sampleMask: ByteArray? = null,
    ): Pair<FloatArray, FloatArray> {
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
        return if (count0 >= count1) Pair(center0, center1) else Pair(center1, center0)
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
            val (text, stroke, _) = estimate(
                cleanedBitmap,
                block.x.toInt(),
                block.y.toInt(),
                (block.x + block.width).toInt(),
                (block.y + block.height).toInt(),
                parent,
            )
            block.textColor = text
            block.strokeColor = stroke
            // Stroke width is owned by TextLayoutPlanner (font-derived); do not
            // write the estimator's vestigial width onto the block.
        }
    }
}
