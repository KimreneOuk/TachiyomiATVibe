package eu.kanade.translation.inpainting

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure mask-construction and morphology helpers used by [SmartBubbleTextCleaner]
 * to build the regions where original text is allowed/erased before inpainting.
 *
 * Extracted out of the cleaner so these byte-array algorithms are testable in
 * isolation (the cleaner's public API operates on `android.graphics.Bitmap`,
 * which plain JVM tests can't load). Every function here is pure — it works
 * only on its parameters, no instance state — so callers must thread in the
 * tuning constants the cleaner used to read as fields.
 */
object BubbleMaskBuilder {

    /**
     * Build a filled mask over the union of [boxes], each drawn as a rounded
     * rectangle (corner radius derived from the box's shorter side). Pixels
     * inside any rounded rect are set to 1; the rest stay 0.
     */
    fun roundedAllowedMask(
        boxes: List<IntArray>,
        width: Int,
        height: Int,
    ): ByteArray {
        val mask = ByteArray(width * height)
        for (box in boxes) {
            val x1 = box[0].coerceIn(0, width)
            val y1 = box[1].coerceIn(0, height)
            val x2 = box[2].coerceIn(x1, width)
            val y2 = box[3].coerceIn(y1, height)
            val bw = x2 - x1
            val bh = y2 - y1
            if (bw <= 0 || bh <= 0) continue
            val radius = (min(bw, bh) * 0.25f).roundToInt().coerceIn(8, 20).coerceAtMost(min(bw, bh) / 2)
            for (y in y1 until y2) {
                for (x in x1 until x2) {
                    if (insideRoundedRect(x - x1, y - y1, bw, bh, radius)) {
                        mask[y * width + x] = 1
                    }
                }
            }
        }
        return mask
    }

    /**
     * Build a filled rectangular mask for the interior of [bubble], eroded
     * inward by [erodePx] on every side and clamped to the canvas bounds.
     */
    fun bubbleInteriorMask(
        bubble: IntArray,
        width: Int,
        height: Int,
        erodePx: Int,
    ): ByteArray {
        val x1 = (bubble[0] + erodePx).coerceIn(0, width)
        val y1 = (bubble[1] + erodePx).coerceIn(0, height)
        val x2 = (bubble[2] - erodePx).coerceIn(x1, width)
        val y2 = (bubble[3] - erodePx).coerceIn(y1, height)
        val mask = ByteArray(width * height)
        for (y in y1 until y2) {
            for (x in x1 until x2) {
                mask[y * width + x] = 1
            }
        }
        return mask
    }

    /**
     * Pixel-in-rounded-rect test. Outside the corner zones every interior pixel
     * counts; inside a corner zone the pixel must lie within `radius` of the
     * corner center.
     */
    fun insideRoundedRect(x: Int, y: Int, width: Int, height: Int, radius: Int): Boolean {
        if (radius <= 0) return true
        val left = x < radius
        val right = x >= width - radius
        val top = y < radius
        val bottom = y >= height - radius
        if (!(left || right) || !(top || bottom)) return true
        val cx = if (left) radius else width - radius - 1
        val cy = if (top) radius else height - radius - 1
        val dx = x - cx
        val dy = y - cy
        return dx * dx + dy * dy <= radius * radius
    }

    /** Logical AND of two equal-length masks. Either-zero stays zero. */
    fun andMasks(a: ByteArray, b: ByteArray): ByteArray {
        val result = ByteArray(a.size)
        for (i in a.indices) {
            if (a[i] != 0.toByte() && b[i] != 0.toByte()) result[i] = 1
        }
        return result
    }

    /** Percentage (0..100) of [mask] bytes that are non-zero. */
    fun maskCoverage(mask: ByteArray): Float {
        if (mask.isEmpty()) return 0f
        return mask.count { it != 0.toByte() } * 100f / mask.size
    }

    /**
     * Drop connected components of set pixels that touch the canvas border
     * (within a 2px margin), keeping only interior blobs. Used to reject
     * detector masks that bleed out of the page edge.
     */
    fun removeEdgeTouchingComponents(
        mask: ByteArray,
        width: Int,
        height: Int,
    ): ByteArray {
        val filtered = ByteArray(mask.size)
        val visited = BooleanArray(mask.size)
        val queue = ArrayDeque<Int>()
        for (start in mask.indices) {
            if (mask[start] == 0.toByte() || visited[start]) continue
            visited[start] = true
            queue.add(start)
            val component = mutableListOf<Int>()
            var touchesEdge = false
            while (queue.isNotEmpty()) {
                val idx = queue.removeFirst()
                component.add(idx)
                val x = idx % width
                val y = idx / width
                if (x <= 1 || y <= 1 || x >= width - 2 || y >= height - 2) touchesEdge = true
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 0 until width || ny !in 0 until height) continue
                        val ni = ny * width + nx
                        if (mask[ni] != 0.toByte() && !visited[ni]) {
                            visited[ni] = true
                            queue.add(ni)
                        }
                    }
                }
            }
            if (!touchesEdge) {
                for (idx in component) filtered[idx] = 1
            }
        }
        return filtered
    }

    /**
     * Dilate the set pixels of [mask] by one pixel (4-neighbourhood), repeated
     * [iterations] times.
     *
     * NOTE: behavior is preserved byte-for-byte from the cleaner's original
     * private copy, which reads the *original* [mask] inside every iteration
     * rather than the running result — so beyond the first iteration the
     * dilation is effectively re-applied to the source each pass. Callers that
     * want a true multi-pass dilation should pass iterations = 1.
     */
    fun dilateMask(
        mask: ByteArray,
        width: Int,
        height: Int,
        iterations: Int,
    ): ByteArray {
        val result = mask.copyOf()
        for (iter in 0 until iterations) {
            val temp = result.copyOf()
            for (y in 1 until height - 1) {
                for (x in 1 until width - 1) {
                    if (mask[y * width + x] != 0.toByte()) {
                        temp[y * width + x] = 1
                        temp[(y - 1) * width + x] = 1
                        temp[(y + 1) * width + x] = 1
                        temp[y * width + (x - 1)] = 1
                        temp[y * width + (x + 1)] = 1
                    }
                }
            }
            for (i in result.indices) result[i] = temp[i]
        }
        return result
    }

    /**
     * Build a feathered alpha map (0..1) for [mask]: core pixels are fully
     * opaque (1.0); pixels just outside are a box-blurred average over a
     * [featherRadius] kernel. Used to blend the inpaint smoothly with the
     * surrounding artwork.
     */
    fun featherAlpha(
        mask: ByteArray,
        width: Int,
        height: Int,
        featherRadius: Int,
    ): FloatArray {
        val alpha = FloatArray(width * height)
        val coreBool = BooleanArray(width * height)
        for (i in mask.indices) coreBool[i] = mask[i] != 0.toByte()

        val hasAny = coreBool.any { it }
        if (!hasAny) return alpha

        val fr = max(2, featherRadius)

        val blurred = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0f
                var count = 0
                for (ky in -fr..fr) {
                    for (kx in -fr..fr) {
                        val ny = y + ky
                        val nx = x + kx
                        if (ny in 0 until height && nx in 0 until width) {
                            sum += if (coreBool[ny * width + nx]) 255f else 0f
                            count++
                        }
                    }
                }
                blurred[y * width + x] = if (count > 0) sum / count / 255f else 0f
            }
        }

        for (i in alpha.indices) {
            alpha[i] = if (coreBool[i]) 1.0f else blurred[i]
        }
        return alpha
    }
}
