package eu.kanade.translation.inpainting

import kotlin.math.max

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
     * TachiyomiAT: build a SOLID rectangular erase mask over the union of
     * [boxes], each padded outward by [pad] px and dilated by a disk SE of
     * [dilateRadius].
     *
     * Port of `build_rect_mask` in `tools/inpaint-debug-viewer/server.py`
     * (the `mask_mode = paddle_boxes` path): every PaddleOCR-v6 line box is
     * turned into a solid white rectangle — NOT sparse text pixels and NOT the
     * loose detector-v4 rectangle — then padded and dilated so anti-aliased
     * stroke edges fall inside the hole. This is the proven, boring-and-direct
     * erase target: Paddle box → pad → mask.
     *
     * Pure (no Android, no ONNX) so it is unit-tested in isolation. Returns a
     * `width * height` ByteArray where 1 = erase.
     *
     * @param boxes `[x1, y1, x2, y2]` boxes in canvas coords (e.g. page-space or
     *   crop-local — same space as [width]/[height]).
     * @param pad px added to every side of each box before filling, clamped to
     *   the canvas. Prototype default is 8.
     * @param dilateRadius disk-dilation radius applied after filling (0 = no
     *   dilation). The prototype uses `cv2.MORPH_ELLIPSE (5,5)` ≈ disk radius 2.
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
     * This grows set pixels isotropically — a disk of radius [radius] — so
     * rectangle corners become genuinely rounded rather than chamfered. This
     * directly addresses the reported "corners too sharp" inpainting artifact:
     * the erase mask's corners are the corners the user sees on the cleaned
     * bubble, and a disk SE rounds them.
     *
     * The disk does NOT bridge thin gaps more than a 4-neighbourhood grower
     * would at the same radius (a disk of radius N has the same diagonal reach
     * as a diamond of radius N).
     *
     * Implementation: precompute the disk kernel offsets once (dx,dy pairs
     * where dx²+dy² ≤ radius²), then for each set source pixel OR the kernel
     * into the output. Single-pass, no iteration loop — [radius] IS the growth.
     */
    fun dilateMaskDisk(
        mask: ByteArray,
        width: Int,
        height: Int,
        radius: Int,
    ): ByteArray {
        if (radius <= 0) return mask.copyOf()
        val result = mask.copyOf()
        // Precompute disk kernel offsets (relative coords where dx²+dy² ≤ r²).
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
     * TachiyomiAT: target the neural (AOT) crop so the text box occupies
     * roughly one third of the model's ≤512 inference tensor, with the other
     * two thirds filled by real surrounding-page context. This is a goal-driven
     * rule that simultaneously guarantees:
     *  - a resolution floor (the box is never sub-128 in the tensor view, since
     *    ~1/3 of the [384,512] clamp is ≥128), and
     *  - generous context (the model sees ~2× the box's own area of page).
     *
     * The earlier heuristic was `boxLongSide × 2.5` clamped to [64,256] — a
     * margin, which made the box's *fraction* of the tensor vary with box size.
     * Sizing to a fixed *fraction* keeps both resolution and context bounded
     * regardless of how big the text is.
     *
     * The clamp bounds memory: a crop long side of 512 is today's worst case
     * (the inference tensor is capped there anyway), so this never charges
     * `canRunNeuralInpaint` a higher peak than the previous design → no new
     * heap-pressure downgrades on the 6GB target.
     *
     * Pure (no Android, no ONNX) so it is unit-tested in isolation.
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
     * TachiyomiAT: two-pass chamfer distance transform of [mask]. Returns, for
     * every pixel, the (approximate Euclidean) distance to the nearest non-zero
     * mask pixel. Distance is 0 inside the mask and grows outward. Approximated
     * with the (3,4) chamfer weights — accurate to within ~8% of true Euclidean
     * distance, at O(n) cost with one transient `IntArray`.
     *
     * This replaces the O(n · featherRadius²) box-blur feather with a true
     * distance field, so the blend transition is a smooth monotonic ramp away
     * from the mask edge rather than a thin 2–6px cliff. Both the neural
     * ([AOTInpainting.featherBlend]) and classical feather paths consume it,
     * so bubbles and free text get an identical soft boundary.
     *
     * Pure (no Android, no ONNX) so it is unit-tested in isolation.
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

        // Forward pass (top-left to bottom-right). Only relax from neighbours
        // that have already been reached (< INF); unreached neighbours cannot
        // contribute a distance yet.
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
        val scale = 1f / H.toFloat() // normalize chamfer units → px (3-weight basis)
        for (i in 0 until n) {
            out[i] = if (dist[i] >= INF) Float.POSITIVE_INFINITY else dist[i] * scale
        }
        return out
    }

    /**
     * TachiyomiAT: distance-field feathered alpha (0..1) for [mask]. Core
     * (in-mask) pixels are fully opaque (1.0); outside, alpha ramps down
     * linearly from 1.0 at the edge to 0.0 at [rampWidth] px from it. This is
     * the smooth replacement for the box-blur [featherAlpha] and is the single
     * soft-edge routine consumed by both the neural and classical inpaint paths.
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
     * Build a feathered alpha map (0..1) for [mask]: core pixels are fully
     * opaque (1.0); pixels outside ramp down over [featherRadius] px. Used to
     * blend the inpaint smoothly with the surrounding artwork.
     *
     * TachiyomiAT: this now delegates to the distance-field [featherAlphaField]
     * so the classical inpaint paths (bubbles, unparented text, solid boxes)
     * share the identical smooth monotonic ramp as the neural path. The earlier
     * box-blurred average produced a thin 2–6px cliff that exposed the erase
     * rectangle; the distance field removes that artefact uniformly.
     */
    fun featherAlpha(
        mask: ByteArray,
        width: Int,
        height: Int,
        featherRadius: Int,
    ): FloatArray = featherAlphaField(mask, width, height, featherRadius)

    // --- neural crop sizing constants (internal; no settings surface) ---

    /** Text box targets ~1/3 of the inference tensor (numerator/denominator). */
    private const val NEURAL_BOX_FRACTION_NUM = 1
    private const val NEURAL_BOX_FRACTION_DENOM = 3

    /** Crop long-side clamp [px]. Floor keeps context on tiny boxes; ceiling is
     *  today's worst case (tensor cap), bounding heap (no new downgrades). */
    private const val NEURAL_CROP_MIN = 384
    private const val NEURAL_CROP_MAX = 512
}
