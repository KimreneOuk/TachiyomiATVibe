package eu.kanade.translation.inpainting

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * TachiyomiAT: legacy free-text inpainting on Android.
 *
 * Bitmap-level port of the validated Python "legacy" free-text path
 * (`tools/inpaint-debug-viewer/server.py::inpaint_free_text`). For one free-text
 * box it:
 *  1. crops the page to the box + ring + feather margin (NOT the full page —
 *     memory on the 6 GB target);
 *  2. builds a stroke (ink) mask by gray-thresholding inside the box;
 *  3. dilates it by the feather radius so the erase region covers ink + halo +
 *     feather margin — this makes the feather ring land on pure background, so
 *     the blend does NOT bleed original text back in (the reason the bare stroke
 *     path must use feather = 0);
 *  4. samples the LOCAL surrounding color ([PushPullGradient.localRingMedian]);
 *  5. reconstructs the hole with the push-pull gradient ([PushPullGradient.pushPullFill]);
 *  6. blends back with the distance-field feather ([BubbleMaskBuilder.featherAlphaField]).
 *
 * Mutates [image] in place (it is the caller's mutable result bitmap) and
 * returns it. Stateless; per-call transient buffers sized to the small crop.
 */
object LegacyFreeTextInpainter {

    private const val RING = 8                  // local annulus half-width (px)
    private const val STROKE_GRAY_THRESH = 160  // ink capture (matches prototype)
    private const val STROKE_BOX_PAD = 2        // px added around the box when thresholding
    private const val STROKE_DILATE = 5         // erase-region dilation radius (ink + halo + margin)
    private const val FEATHER_RAMP = 3          // bleed-free feather ramp width
    private const val FREE_MIN_SIDE = 18        // tiny free-text short-side floor (free-only)
    private const val FREE_LONG_FLOOR = 22      // tiny free-text long-side floor (free-only)

    /**
     * Inpaint one free-text [box] (`[x1, y1, x2, y2]`, page coords) into [image].
     * Returns [image] (mutated). Free-only tiny-box expansion is applied.
     */
    fun inpaint(image: Bitmap, box: IntArray): Bitmap {
        val w = image.width
        val h = image.height
        if (box.size < 4) return image

        // 1. Optional tiny free-text expansion (symmetric, clipped to the page).
        val (bx1, by1, bx2, by2) = expandTinyFree(box, w, h)
        if (bx2 - bx1 <= 0 || by2 - by1 <= 0) return image

        // 2. Crop to box + ring + feather margin.
        val margin = RING + FEATHER_RAMP + STROKE_BOX_PAD + 2
        val cx1 = max(0, bx1 - margin)
        val cy1 = max(0, by1 - margin)
        val cx2 = min(w, bx2 + margin)
        val cy2 = min(h, by2 + margin)
        val cw = cx2 - cx1
        val ch = cy2 - cy1
        if (cw <= 0 || ch <= 0) return image

        val orig = IntArray(cw * ch)
        image.getPixels(orig, 0, cw, cx1, cy1, cw, ch)
        val work = orig.copyOf()

        // 3. Stroke mask (ink) inside the localized box, dilated to form the
        //    bleed-free erase region.
        val stroke = buildStrokeMask(work, cw, ch, bx1 - cx1, by1 - cy1, bx2 - cx1, by2 - cy1)
        if (!stroke.any { it != 0.toByte() }) return image
        val erase = BubbleMaskBuilder.dilateMaskDisk(stroke, cw, ch, STROKE_DILATE)

        // 4 + 5. Local ring color + push-pull gradient fill (mutates `work`).
        val bg = PushPullGradient.localRingMedian(orig, cw, ch, erase, RING)
        PushPullGradient.pushPullFill(work, cw, ch, erase, bg)

        // 6. Distance-field feather blend of original ↔ filled, written into `work`.
        val alpha = BubbleMaskBuilder.featherAlphaField(erase, cw, ch, FEATHER_RAMP)
        for (i in work.indices) {
            val a = alpha[i]
            if (a > 0.0f) work[i] = blend(orig[i], work[i], a)
        }

        image.setPixels(work, 0, cw, cx1, cy1, cw, ch)
        return image
    }

    /** Gray-threshold ink mask inside the localized box `[lx1,ly1,lx2,ly2]` (+ pad). */
    private fun buildStrokeMask(
        pixels: IntArray,
        cw: Int,
        ch: Int,
        lx1: Int,
        ly1: Int,
        lx2: Int,
        ly2: Int,
    ): ByteArray {
        val mask = ByteArray(cw * ch)
        val x1 = max(0, lx1 - STROKE_BOX_PAD)
        val y1 = max(0, ly1 - STROKE_BOX_PAD)
        val x2 = min(cw, lx2 + STROKE_BOX_PAD)
        val y2 = min(ch, ly2 + STROKE_BOX_PAD)
        for (y in y1 until y2) {
            val row = y * cw
            for (x in x1 until x2) {
                val p = pixels[row + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val gray = (r * 299 + g * 587 + b * 114) / 1000
                if (gray < STROKE_GRAY_THRESH) mask[row + x] = 1
            }
        }
        return mask
    }

    /** Free-only tiny-box expansion: grow to the size floor, symmetric, page-clipped. */
    private fun expandTinyFree(box: IntArray, w: Int, h: Int): IntArray {
        var x1 = box[0]; var y1 = box[1]; var x2 = box[2]; var y2 = box[3]
        val bw = x2 - x1; val bh = y2 - y1
        val shortSide = min(bw, bh); val longSide = max(bw, bh)
        if (shortSide >= FREE_MIN_SIDE && longSide >= FREE_LONG_FLOOR) return intArrayOf(x1, y1, x2, y2)
        if (shortSide < FREE_MIN_SIDE) {
            val grow = FREE_MIN_SIDE - shortSide
            val half = grow / 2
            val other = grow - half
            if (bw <= bh) { x1 = max(0, x1 - half); x2 = min(w, x2 + other) } else { y1 = max(0, y1 - half); y2 = min(h, y2 + other) }
        }
        val newShort = min(x2 - x1, y2 - y1)
        val newLong = max(x2 - x1, y2 - y1)
        if (newLong < FREE_LONG_FLOOR) {
            val bw2 = x2 - x1; val bh2 = y2 - y1
            val grow = FREE_LONG_FLOOR - newLong
            val half = grow / 2
            val other = grow - half
            if (bw2 <= bh2) { y1 = max(0, y1 - half); y2 = min(h, y2 + other) } else { x1 = max(0, x1 - half); x2 = min(w, x2 + other) }
        }
        return intArrayOf(x1, y1, x2, y2)
    }

    private fun blend(orig: Int, filled: Int, a: Float): Int {
        val inv = 1.0f - a
        val r = ((orig shr 16 and 0xFF) * inv + (filled shr 16 and 0xFF) * a).toInt().coerceIn(0, 255)
        val g = ((orig shr 8 and 0xFF) * inv + (filled shr 8 and 0xFF) * a).toInt().coerceIn(0, 255)
        val b = ((orig and 0xFF) * inv + (filled and 0xFF) * a).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
