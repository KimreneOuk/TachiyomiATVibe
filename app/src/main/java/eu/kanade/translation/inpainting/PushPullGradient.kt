package eu.kanade.translation.inpainting

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * TachiyomiAT: push-pull gradient inpainting for free text.
 *
 * Pure-JVM port of the validated free-text path from the Python prototype:
 *  - [localRingMedian] — median of the annulus around the text (the LOCAL
 *    surrounding color), replacing the old global page median that kept free
 *    text "stuck on white" (pages are mostly white, so the global median was
 *    always white).
 *  - [pushPullFill] — erase ink with the local color, push-pull gradient
 *    (box downscale → bilinear upscale) into the hole, then boundary diffusion
 *    inside the mask.
 *
 * Operates only on `IntArray`/`ByteArray` (no `android.graphics.Bitmap`) so it
 * is unit-testable in plain JVM. The bitmap-level caller crops the page first
 * (memory).
 */
object PushPullGradient {

    /** Half-width (px) of the local annulus sampled for the background color. */
    const val DEFAULT_RING = 8

    /**
     * Push-pull downsample divisor (small dim = w / 20). Floor is max(2, …),
     * affecting only sub-80px crops.
     */
    private const val DOWN_SAMPLE_DIV = 20

    /** Boundary-diffusion passes inside the mask (matches the prototype). */
    const val DIFFUSION_PASSES = 15

    /**
     * Median ARGB of the local annulus around the mask bbox — the free-text
     * surroundings. Falls back to the global page median when the annulus is
     * empty (degenerate/edge case); never returns pure white by default.
     *
     * Port of `_local_ring_median`.
     *
     * @param pixels packed ARGB page (or crop) pixels, row-major
     * @param mask   1 = text (excluded from sampling), 0 = background
     */
    fun localRingMedian(
        pixels: IntArray,
        width: Int,
        height: Int,
        mask: ByteArray,
        ring: Int = DEFAULT_RING,
    ): Int {
        var y1 = height
        var y2 = -1
        var x1 = width
        var x2 = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (mask[row + x] != 0.toByte()) {
                    if (x < x1) x1 = x
                    if (x > x2) x2 = x
                    if (y < y1) y1 = y
                    if (y > y2) y2 = y
                }
            }
        }
        if (y2 < 0) {
            // No mask at all → global median of the whole buffer.
            return medianArgb(pixels, 0, pixels.size)
        }
        val ry1 = max(0, y1 - ring)
        val ry2 = min(height, y2 + ring + 1)
        val rx1 = max(0, x1 - ring)
        val rx2 = min(width, x2 + ring + 1)

        val histR = IntArray(256)
        val histG = IntArray(256)
        val histB = IntArray(256)
        var count = 0
        // Annulus = the ring rectangle minus the mask bbox interior, so we sample
        // only the surrounding background (the text region is excluded).
        val inY1 = y1 - ry1
        val inX1 = x1 - rx1
        val inY2 = inY1 + (y2 - y1 + 1)
        val inX2 = inX1 + (x2 - x1 + 1)
        for (y in ry1 until ry2) {
            val ly = y - ry1
            val row = y * width
            for (x in rx1 until rx2) {
                val lx = x - rx1
                if (ly in inY1 until inY2 && lx in inX1 until inX2) continue
                val p = pixels[row + x]
                histR[(p shr 16) and 0xFF]++
                histG[(p shr 8) and 0xFF]++
                histB[p and 0xFF]++
                count++
            }
        }
        if (count == 0) return medianArgb(pixels, 0, pixels.size)
        val mr = histogramMedian(histR, count)
        val mg = histogramMedian(histG, count)
        val mb = histogramMedian(histB, count)
        return (0xFF shl 24) or (mr shl 16) or (mg shl 8) or mb
    }

    /**
     * Push-pull gradient fill: erase the masked ink with [bgArgb], build a
     * smooth background gradient (box downscale → bilinear upscale), paint it
     * into the hole, then diffuse the hole boundary to its neighbours.
     *
     * Mutates [pixels] in place; only masked pixels change. Port of
     * `pil_inpaint_stroke` (the core reconstruction, minus the now-external
     * ring color which the caller passes as [bgArgb]).
     *
     * Operates on a CROP sized [width]×[height] — callers must NOT pass the full
     * page (6 GB target). [tmp] is an optional reusable buffer of size
     * width*height to avoid per-call allocation on the hot path.
     */
    fun pushPullFill(
        pixels: IntArray,
        width: Int,
        height: Int,
        mask: ByteArray,
        bgArgb: Int,
        tmp: IntArray? = null,
    ) {
        val n = width * height
        if (n == 0) return

        // a. Erase the ink with the local background color before downscaling so
        //    the text does not pollute the gradient.
        for (i in 0 until n) {
            if (mask[i] != 0.toByte()) pixels[i] = bgArgb
        }

        // b. Box-average downscale (≈ PIL BILINEAR shrink) → tiny gradient source.
        val smallW = max(2, width / DOWN_SAMPLE_DIV)
        val smallH = max(2, height / DOWN_SAMPLE_DIV)
        val small = downsampleBoxAvg(pixels, width, height, smallW, smallH)

        // c. Bilinear upscale back to full size → smooth gradient map.
        val gradient = upsampleBilinear(small, smallW, smallH, width, height)

        // d. Paint the gradient into the hole only.
        for (i in 0 until n) {
            if (mask[i] != 0.toByte()) pixels[i] = gradient[i]
        }

        // e. Boundary diffusion inside the mask (edge-clamped 4-neighbour average).
        //    This stitches the hole's edge to the real surrounding pixels.
        val scratch = tmp ?: IntArray(n)
        for (pass in 0 until DIFFUSION_PASSES) {
            for (y in 0 until height) {
                val row = y * width
                val rowUp = if (y > 0) row - width else row
                val rowDown = if (y < height - 1) row + width else row
                for (x in 0 until width) {
                    val i = row + x
                    if (mask[i] == 0.toByte()) continue
                    val left = if (x > 0) i - 1 else i
                    val right = if (x < width - 1) i + 1 else i
                    scratch[i] = avg4(pixels[rowUp + x], pixels[rowDown + x], pixels[left], pixels[right])
                }
            }
            for (i in 0 until n) {
                if (mask[i] != 0.toByte()) pixels[i] = scratch[i]
            }
        }
    }

    private fun histogramMedian(hist: IntArray, count: Int): Int {
        val half = count / 2
        var acc = 0
        for (v in 0..255) {
            acc += hist[v]
            if (acc > half) return v
        }
        return 255
    }

    private fun medianArgb(pixels: IntArray, from: Int, to: Int): Int {
        val histR = IntArray(256)
        val histG = IntArray(256)
        val histB = IntArray(256)
        var count = 0
        for (i in from until to) {
            val p = pixels[i]
            histR[(p shr 16) and 0xFF]++
            histG[(p shr 8) and 0xFF]++
            histB[p and 0xFF]++
            count++
        }
        if (count == 0) return 0xFFFFFFFF.toInt()
        val mr = histogramMedian(histR, count)
        val mg = histogramMedian(histG, count)
        val mb = histogramMedian(histB, count)
        return (0xFF shl 24) or (mr shl 16) or (mg shl 8) or mb
    }

    /** Area-average downscale of an ARGB buffer to [smallW]×[smallH]. */
    private fun downsampleBoxAvg(
        src: IntArray,
        width: Int,
        height: Int,
        smallW: Int,
        smallH: Int,
    ): IntArray {
        val sumR = IntArray(smallW * smallH)
        val sumG = IntArray(smallW * smallH)
        val sumB = IntArray(smallW * smallH)
        val cnt = IntArray(smallW * smallH)
        for (y in 0 until height) {
            val sy = (y * smallH) / height
            val row = y * width
            val srow = sy * smallW
            for (x in 0 until width) {
                val sx = (x * smallW) / width
                val si = srow + sx
                val p = src[row + x]
                sumR[si] += (p shr 16) and 0xFF
                sumG[si] += (p shr 8) and 0xFF
                sumB[si] += p and 0xFF
                cnt[si]++
            }
        }
        val out = IntArray(smallW * smallH)
        for (i in out.indices) {
            val c = if (cnt[i] > 0) cnt[i] else 1
            val r = (sumR[i] + c / 2) / c
            val g = (sumG[i] + c / 2) / c
            val b = (sumB[i] + c / 2) / c
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    /** Bilinear upsample of an ARGB buffer from [smallW]×[smallH] to [width]×[height]. */
    private fun upsampleBilinear(
        small: IntArray,
        smallW: Int,
        smallH: Int,
        width: Int,
        height: Int,
    ): IntArray {
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val fy = (y + 0.5f) * smallH / height - 0.5f
            var y0 = floor(fy).toInt()
            val ty = (fy - y0).coerceIn(0f, 1f)
            if (y0 < 0) y0 = 0
            val y1 = (y0 + 1).coerceAtMost(smallH - 1)
            y0 = y0.coerceAtMost(smallH - 1)
            for (x in 0 until width) {
                val fx = (x + 0.5f) * smallW / width - 0.5f
                var x0 = floor(fx).toInt()
                val tx = (fx - x0).coerceIn(0f, 1f)
                if (x0 < 0) x0 = 0
                val x1 = (x0 + 1).coerceAtMost(smallW - 1)
                x0 = x0.coerceAtMost(smallW - 1)
                val c00 = small[y0 * smallW + x0]
                val c10 = small[y0 * smallW + x1]
                val c01 = small[y1 * smallW + x0]
                val c11 = small[y1 * smallW + x1]
                out[y * width + x] = bilerp(c00, c10, c01, c11, tx, ty)
            }
        }
        return out
    }

    private fun bilerp(c00: Int, c10: Int, c01: Int, c11: Int, tx: Float, ty: Float): Int {
        val r0 = lerp((c00 shr 16) and 0xFF, (c10 shr 16) and 0xFF, tx)
        val r1 = lerp((c01 shr 16) and 0xFF, (c11 shr 16) and 0xFF, tx)
        val g0 = lerp((c00 shr 8) and 0xFF, (c10 shr 8) and 0xFF, tx)
        val g1 = lerp((c01 shr 8) and 0xFF, (c11 shr 8) and 0xFF, tx)
        val b0 = lerp(c00 and 0xFF, c10 and 0xFF, tx)
        val b1 = lerp(c01 and 0xFF, c11 and 0xFF, tx)
        val r = lerp(r0, r1, ty)
        val g = lerp(g0, g1, ty)
        val b = lerp(b0, b1, ty)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun lerp(a: Int, b: Int, t: Float): Int =
        (a + (b - a) * t).toInt().coerceIn(0, 255)

    private fun avg4(a: Int, b: Int, c: Int, d: Int): Int {
        val r = ((a shr 16 and 0xFF) + (b shr 16 and 0xFF) + (c shr 16 and 0xFF) + (d shr 16 and 0xFF) + 2) shr 2
        val g = ((a shr 8 and 0xFF) + (b shr 8 and 0xFF) + (c shr 8 and 0xFF) + (d shr 8 and 0xFF) + 2) shr 2
        val bb = ((a and 0xFF) + (b and 0xFF) + (c and 0xFF) + (d and 0xFF) + 2) shr 2
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bb
    }
}
