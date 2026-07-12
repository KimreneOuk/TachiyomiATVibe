package eu.kanade.translation.inpainting

import kotlin.math.max

/**
 * Pure mask-construction and morphology helpers used by [SmartBubbleTextCleaner]
 * to build the regions where original text is erased before inpainting.
 *
 * Extracted from the cleaner so these byte-array algorithms are unit-testable
 * in isolation (the cleaner's API operates on `android.graphics.Bitmap`, which
 * plain JVM tests can't load). Every function is pure; callers thread in the
 * tuning constants the cleaner used to read as fields.
 */
object BubbleMaskBuilder {

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
     * TachiyomiAT: solid rectangular erase mask over the union of [boxes], each
     * padded by [pad] px and dilated by a disk SE of [dilateRadius]. Each box
     * becomes a solid rectangle (not sparse text pixels) then is padded/dilated
     * so anti-aliased stroke edges fall inside the hole. Port of the validated
     * `paddle_boxes` path. Pure (no Android/ONNX).
     *
     * @param boxes `[x1, y1, x2, y2]` in canvas coords.
     * @param pad px added per side before filling, clamped to the canvas.
     * @param dilateRadius disk-dilation radius after filling (0 = none).
     */
    fun buildRectMask(
        boxes: List<IntArray>,
        width: Int,
        height: Int,
        pad: Int,
        dilateRadius: Int = 2,
    ): ByteArray {
        if (width <= 0 || height <= 0 || boxes.isEmpty()) return ByteArray(width * height)
        val mask = ByteArray(width * height)
        for (box in boxes) {
            if (box.size < 4) continue
            val x1 = (box[0] - pad).coerceIn(0, width)
            val y1 = (box[1] - pad).coerceIn(0, height)
            val x2 = (box[2] + pad).coerceIn(0, width)
            val y2 = (box[3] + pad).coerceIn(0, height)
            if (x2 <= x1 || y2 <= y1) continue
            for (y in y1 until y2) {
                val row = y * width
                for (x in x1 until x2) {
                    mask[row + x] = 1
                }
            }
        }
        return if (dilateRadius > 0) {
            dilateMaskDisk(mask, width, height, dilateRadius)
        } else {
            mask
        }
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
     * TachiyomiAT: disk (circular) structuring-element dilation.
     *
     * Grows set pixels isotropically (a disk of radius [radius]) so rectangle
     * corners become genuinely rounded rather than chamfered — directly fixing
     * the "corners too sharp" artifact, since the erase mask's corners are the
     * corners the user sees. A disk does NOT bridge thin gaps more than a
     * 4-neighbourhood grower at the same radius. Single-pass: [radius] IS the growth.
     */
    fun dilateMaskDisk(
        mask: ByteArray,
        width: Int,
        height: Int,
        radius: Int,
    ): ByteArray {
        if (radius <= 0) return mask.copyOf()
        val result = mask.copyOf()
        val kernel = mutableListOf<Pair<Int, Int>>()
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                if (dx * dx + dy * dy <= radius * radius) {
                    kernel += dx to dy
                }
            }
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (mask[y * width + x] != 0.toByte()) {
                    for ((dx, dy) in kernel) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx in 0 until width && ny in 0 until height) {
                            result[ny * width + nx] = 1
                        }
                    }
                }
            }
        }
        return result
    }

    /**
     * TachiyomiAT: target the neural (AOT) crop so the text box is ~1/3 of the
     * ≤512 inference tensor (resolution floor) with ~2× its area as real page
     * context. A fixed *fraction* (vs the earlier margin heuristic) keeps both
     * bounded regardless of text size. The 512 ceiling bounds memory: it never
     * charges `canRunNeuralInpaint` more than the tensor cap → no new
     * heap-pressure downgrades on the 6GB target.
     *
     * @param boxLongSide the longer side of the text box union (px)
     * @return the target longer side of the context crop (px)
     */
    fun computeNeuralCrop(boxLongSide: Int): Int {
        if (boxLongSide <= 0) return NEURAL_CROP_MIN
        val target = boxLongSide * NEURAL_BOX_FRACTION_DENOM / NEURAL_BOX_FRACTION_NUM
        return target.coerceIn(NEURAL_CROP_MIN, NEURAL_CROP_MAX)
    }

    /**
     * TachiyomiAT: two-pass chamfer distance transform of [mask]. Returns, per
     * pixel, the (approx Euclidean) distance to the nearest non-zero mask pixel
     * (0 inside, growing outward). The (3,4) chamfer weights are accurate to
     * ~8% of true Euclidean at O(n) cost. Replaces the O(n·featherRadius²)
     * box-blur feather with a true distance field so the blend is a smooth
     * monotonic ramp rather than a thin cliff; consumed by both neural and
     * classical feather paths. Pure (no Android/ONNX).
     */
    fun distanceToMask(
        mask: ByteArray,
        width: Int,
        height: Int,
    ): FloatArray {
        val n = minOf(mask.size, width * height)
        // Sentinel must be large but leave headroom so INF + weight cannot
        // overflow Int (adding to a still-unreached neighbour). 1e9 leaves
        // ~1.3e9 of headroom, far more than any real chamfer sum.
        val INF = 1_000_000_000
        val dist = IntArray(n) { INF }
        val H = 3 // horizontal/vertical step weight (chamfer 3,4)
        val D = 4 // diagonal step weight

        // Forward pass (top-left → bottom-right). Only relax from neighbours
        // already reached (< INF); unreached ones cannot contribute yet.
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val i = row + x
                if (mask[i] != 0.toByte()) { dist[i] = 0; continue }
                var best = dist[i]
                if (x > 0) {
                    val v = dist[i - 1]; if (v < best - H) best = v + H
                }
                if (y > 0) {
                    val v = dist[i - width]; if (v < best - H) best = v + H
                }
                if (x > 0 && y > 0) {
                    val v = dist[i - width - 1]; if (v < best - D) best = v + D
                }
                if (x < width - 1 && y > 0) {
                    val v = dist[i - width + 1]; if (v < best - D) best = v + D
                }
                dist[i] = best
            }
        }
        // Backward pass (bottom-right to top-left).
        for (y in height - 1 downTo 0) {
            val row = y * width
            for (x in width - 1 downTo 0) {
                val i = row + x
                var best = dist[i]
                if (x < width - 1) {
                    val v = dist[i + 1]; if (v < best - H) best = v + H
                }
                if (y < height - 1) {
                    val v = dist[i + width]; if (v < best - H) best = v + H
                }
                if (x < width - 1 && y < height - 1) {
                    val v = dist[i + width + 1]; if (v < best - D) best = v + D
                }
                if (x > 0 && y < height - 1) {
                    val v = dist[i + width - 1]; if (v < best - D) best = v + D
                }
                dist[i] = best
            }
        }

        val out = FloatArray(n)
        val scale = 1f / H.toFloat() // chamfer units → px
        for (i in 0 until n) {
            out[i] = if (dist[i] >= INF) Float.POSITIVE_INFINITY else dist[i] * scale
        }
        return out
    }

    /**
     * TachiyomiAT: distance-field feathered alpha (0..1) for [mask]. In-mask
     * pixels are fully opaque (1.0); outside, alpha ramps down linearly from
     * 1.0 at the edge to 0.0 at [rampWidth] px. The single soft-edge routine
     * consumed by both the neural and classical inpaint paths.
     */
    fun featherAlphaField(
        mask: ByteArray,
        width: Int,
        height: Int,
        rampWidth: Int,
    ): FloatArray {
        val alpha = FloatArray(width * height)
        val hasAny = mask.any { it != 0.toByte() }
        if (!hasAny) return alpha
        val dist = distanceToMask(mask, width, height)
        // A ramp of at least 2 keeps the floor that callers relied on.
        val ramp = max(2, rampWidth).toFloat()
        for (i in alpha.indices) {
            alpha[i] = if (mask[i] != 0.toByte()) {
                1.0f
            } else {
                (1.0f - dist[i] / ramp).coerceIn(0.0f, 1.0f)
            }
        }
        return alpha
    }

    /**
     * Build a feathered alpha map (0..1) for [mask]: core pixels fully opaque,
     * pixels outside ramping down over [featherRadius] px.
     *
     * TachiyomiAT: delegates to the distance-field [featherAlphaField] so the
     * classical paths share the identical smooth ramp as the neural path (the
     * old box-blurred average produced a thin cliff that exposed the erase rect).
     */
    fun featherAlpha(
        mask: ByteArray,
        width: Int,
        height: Int,
        featherRadius: Int,
    ): FloatArray = featherAlphaField(mask, width, height, featherRadius)

    /**
     * TachiyomiAT: DYNAMIC pill-shaped erase mask for bubble text boxes.
     *
     * Each box is padded by [pad] px, filled solid, then dilated with a disk SE
     * so corners are rounded into a capsule (matching speech-bubble interiors).
     * "Dynamic" = the dilation radius adapts to the box dimensions (small bubble
     * → tighter pill, large → wider), keeping the rounded corner proportional.
     * Used by the AOT report path over the full page canvas. Pure (no Android/ONNX).
     *
     * @param boxes `[x1, y1, x2, y2]` in canvas coords (page-space).
     * @param width canvas width (px).
     * @param height canvas height (px).
     * @param pad px added per side before filling, clamped to the canvas.
     */
    fun buildDynamicPillMask(
        boxes: List<IntArray>,
        width: Int,
        height: Int,
        pad: Int,
    ): ByteArray {
        if (width <= 0 || height <= 0 || boxes.isEmpty()) return ByteArray(width * height)
        // Start from the padded solid-rect union mask, then dilate each box
        // with a disk radius proportional to its shorter side so rounding scales
        // with the bubble. Clamp the radius so tiny boxes don't get an outsized
        // pill and large boxes don't smear across the page.
        val combined = buildRectMask(boxes, width, height, pad, dilateRadius = 0)
        for (box in boxes) {
            if (box.size < 4) continue
            val bw = (box[2] - box[0]).coerceAtLeast(0)
            val bh = (box[3] - box[1]).coerceAtLeast(0)
            if (bw == 0 || bh == 0) continue
            val shortSide = minOf(bw, bh)
            // Disk radius ≈ 12% of the short side, clamped to [2, 16] — rounds
            // corners into a capsule without bridging adjacent bubbles.
            val radius = (shortSide / 8).coerceIn(2, 16)
            // OR each box's dilated region into the running mask to preserve union semantics.
            val singleBoxMask = buildRectMask(listOf(box), width, height, pad, dilateRadius = 0)
            val dilated = dilateMaskDisk(singleBoxMask, width, height, radius)
            for (i in combined.indices) {
                if (dilated[i] != 0.toByte()) combined[i] = 1
            }
        }
        return combined
    }

    /**
     * TachiyomiAT: FIXED pill-shaped erase mask for free-text line boxes.
     *
     * Unlike [buildDynamicPillMask], uses one fixed dilation radius for every
     * box — appropriate for PaddleOCR text-line boxes, uniformly narrow strips
     * where a proportional radius would over-round short lines. Used by the AOT
     * report fast-path and 512-context neural crop on the crop-local canvas.
     * Pure (no Android/ONNX).
     *
     * @param boxes `[x1, y1, x2, y2]` in canvas coords (crop-local).
     * @param width canvas width (px).
     * @param height canvas height (px).
     * @param pad px added per side before filling.
     * @param dilateRadius fixed disk-dilation radius after filling (0 = none).
     */
    fun buildFixedPillMask(
        boxes: List<IntArray>,
        width: Int,
        height: Int,
        pad: Int,
        dilateRadius: Int,
    ): ByteArray {
        if (width <= 0 || height <= 0 || boxes.isEmpty()) return ByteArray(width * height)
        return buildRectMask(boxes, width, height, pad, dilateRadius = dilateRadius)
    }

    /** Text box targets ~1/3 of the inference tensor (numerator/denominator). */
    private const val NEURAL_BOX_FRACTION_NUM = 1
    private const val NEURAL_BOX_FRACTION_DENOM = 3

    /** Crop long-side clamp (px). Floor keeps context on tiny boxes; ceiling is
     *  the tensor cap, bounding heap (no new 6GB downgrades). */
    private const val NEURAL_CROP_MIN = 384
    private const val NEURAL_CROP_MAX = 512
}
