package eu.kanade.translation.inpainting

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure helpers for the boundary-aware tiered inpainting pipeline.
 *
 * All functions are pure (IntArray/ByteArray in, out, no Android/ONNX) so they
 * are JVM-unit-testable in isolation. Every caller threads their own working
 * buffers; this object holds no instance state.
 */
object BoundaryAwarePipeline {

    enum class Tier { FLAT, TEXTURED, COLOR }

    data class RegionStats(
        val medianColor: Int,
        val grayMean: Float,
        val grayStd: Float,
        val nearWhiteRatio: Float,
        val darkPixelRatio: Float,
        val edgeDensity: Float,
        val saturationMean: Float,
        /**
         * Number of mask pixels actually sampled to derive these stats. `0`
         * marks a degenerate result (empty mask/seed) where every other field
         * is a synthetic default — notably [medianColor] defaults to pure white.
         * Callers MUST NOT trust a degenerate result's median for a flat fill,
         * since painting with it produces a solid white block. See
         * `SmartBubbleTextCleaner.fillContained`.
         */
        val sampleCount: Int,
    )

    data class ContainmentResult(
        val mask: ByteArray,
        val interiorMedian: Int,
        val isFallback: Boolean,
        val coverage: Float,
    )

    /** RGB Euclidean distance for flood inclusion. 35 captures minor shading
     *  (screentone base, gradient) without crossing a dark bubble outline. */
    private const val CONTAINMENT_DELTA = 35f

    /** Max flood radius beyond the seed region bounding box (px). */
    private const val FLOOD_MARGIN = 60

    /** If flooded-area / erase-area < this ratio, fall back to padded box. */
    private const val MIN_FLOOD_FRACTION = 0.80f

    /** Erosion fraction for parent-bubble seed: erode = max(2, minDim / 10). */
    private const val BUBBLE_ERODE_FRACTION = 10

    /**
     * Compute the true-flat-interior containment mask via BFS flood-fill.
     *
     * Parented text seeds from the eroded detector-v4 bubble interior; free
     * text seeds from the border ring around the erase boxes. The flood expands
     * through pixels whose color is close to the seed-area median, capped by
     * [floodMargin], and stops at the first strong edge (bubble outline, panel
     * border, artwork boundary). When the flood covers too little area (text on
     * textured/edge content) the result falls back to a padded-union of
     * [eraseBoxes] so the caller never over-constrains.
     *
     * @param pixels context-crop ARGB pixels (contextW × contextH).
     * @param parentBubble detector-v4 bubble box in context-local coords, or
     *   null for free text. [x1, y1, x2, y2].
     * @param eraseBoxes Paddle-v6 line boxes (the holes) in context-local coords.
     * @return [ContainmentResult] (mask 1 = interior, median, fallback, coverage).
     */
    fun computeContainment(
        pixels: IntArray,
        contextW: Int,
        contextH: Int,
        parentBubble: IntArray?,
        eraseBoxes: List<IntArray>,
    ): ContainmentResult {
        val n = contextW * contextH
        val containment = ByteArray(n)

        val (seedMask, interiorMedian) = buildSeedMask(
            pixels, contextW, contextH, parentBubble, eraseBoxes,
        )

        if (seedMask.size != n) {
            return ContainmentResult(
                mask = paddedUnionMask(contextW, contextH, eraseBoxes, 8),
                interiorMedian = interiorMedian,
                isFallback = true,
                coverage = 0f,
            )
        }

        val seedCount = seedMask.count { it != 0.toByte() }
        if (seedCount == 0) {
            return ContainmentResult(
                mask = paddedUnionMask(contextW, contextH, eraseBoxes, 8),
                interiorMedian = interiorMedian,
                isFallback = true,
                coverage = 0f,
            )
        }

        val seedR = (interiorMedian shr 16 and 0xFF).toFloat()
        val seedG = (interiorMedian shr 8 and 0xFF).toFloat()
        val seedB = (interiorMedian and 0xFF).toFloat()

        var minSx = contextW
        var minSy = contextH
        var maxSx = 0
        var maxSy = 0
        for (i in 0 until n) {
            if (seedMask[i] == 0.toByte()) continue
            val x = i % contextW
            val y = i / contextW
            if (x < minSx) minSx = x
            if (y < minSy) minSy = y
            if (x > maxSx) maxSx = x
            if (y > maxSy) maxSy = y
        }
        val fx1 = max(0, minSx - floodMargin)
        val fy1 = max(0, minSy - floodMargin)
        val fx2 = min(contextW, maxSx + floodMargin + 1)
        val fy2 = min(contextH, maxSy + floodMargin + 1)

        val queue = IntArray(n)
        var head = 0
        var tail = 0
        val visited = BooleanArray(n)
        val dx4 = intArrayOf(-1, 1, 0, 0)
        val dy4 = intArrayOf(0, 0, -1, 1)

        for (i in 0 until n) {
            if (seedMask[i] != 0.toByte()) {
                containment[i] = 1
                visited[i] = true
                queue[tail++] = i
            }
        }

        while (head < tail) {
            val idx = queue[head++]
            val cx = idx % contextW
            val cy = idx / contextW
            for (d in 0..3) {
                val nx = cx + dx4[d]
                val ny = cy + dy4[d]
                if (nx < fx1 || nx >= fx2 || ny < fy1 || ny >= fy2) continue
                if (ny < 0 || ny >= contextH || nx < 0 || nx >= contextW) continue
                val ni = ny * contextW + nx
                if (visited[ni]) continue
                val px = pixels[ni]
                val pr = (px shr 16 and 0xFF).toFloat()
                val pg = (px shr 8 and 0xFF).toFloat()
                val pb = (px and 0xFF).toFloat()
                val dr = pr - seedR
                val dg = pg - seedG
                val db = pb - seedB
                if (sqrt(dr * dr + dg * dg + db * db) <= colorDelta) {
                    visited[ni] = true
                    containment[ni] = 1
                    queue[tail++] = ni
                }
            }
        }

        // Coverage check: if the flooded area is too small relative to the
        // erase boxes, fall back to padded union (textured/edge content).
        val floodArea = containment.count { it != 0.toByte() }
        val eraseArea = eraseBoxes.sumOf { (it[2] - it[0]) * (it[3] - it[1]) }
        val isFallback = floodArea < eraseArea * minFloodFraction

        if (isFallback) {
            return ContainmentResult(
                mask = paddedUnionMask(contextW, contextH, eraseBoxes, 8),
                interiorMedian = interiorMedian,
                isFallback = true,
                coverage = floodArea * 100f / n,
            )
        }

        val coverage = floodArea * 100f / n
        return ContainmentResult(containment, interiorMedian, false, coverage)
    }

    /**
     * Paint every pixel OUTSIDE [containment] to [interiorMedian] in place.
     * This ensures downstream solvers (Telea, AOT) only see interior-coloured
     * neighbours and cannot pull dark artwork across the bubble boundary.
     */
    fun paintExterior(
        pixels: IntArray,
        containment: ByteArray,
        interiorMedian: Int,
        width: Int,
        height: Int,
    ) {
        val n = minOf(pixels.size, containment.size, width * height)
        for (i in 0 until n) {
            if (containment[i] == 0.toByte()) {
                pixels[i] = interiorMedian
            }
        }
    }

    /**
     * Classify a region into [Tier.FLAT], [Tier.TEXTURED], or [Tier.COLOR]
     * based on [stats] collected from the containment interior.
     *
     * The screentone guard: a region with mid-band gray std and low saturation
     * (screentone dot pattern) forces TEXTURED so it is never flat-grayed.
     */
    fun classifyTier(stats: RegionStats): Tier {
        val flatWhite = stats.nearWhiteRatio > 0.7f && stats.grayStd < 20f
        val flatColored = stats.grayStd < 15f && stats.nearWhiteRatio <= 0.7f
        val darkFlat = stats.darkPixelRatio > 0.5f && stats.grayStd < 18f

        if (flatWhite || flatColored || darkFlat) {
            return Tier.FLAT
        }

        // Screentone guard: screentone has moderate gray std (dot contrast)
        // but low colour saturation. Route to TEXTURED so it is reconstructed
        // by Telea rather than flat-filled.
        val screentonePattern =
            stats.grayStd in 14f..50f &&
                stats.saturationMean < 20f &&
                stats.edgeDensity < 0.20f

        val saturatedColor = stats.saturationMean > 35f

        return when {
            saturatedColor -> Tier.COLOR
            screentonePattern -> Tier.TEXTURED
            stats.grayStd < 22f && stats.edgeDensity < 0.08f -> Tier.FLAT
            stats.grayStd < 45f -> Tier.TEXTURED
            stats.edgeDensity < 0.12f -> Tier.FLAT
            else -> Tier.TEXTURED
        }
    }

    /**
     * Collect [RegionStats] over the non-zero pixels of [mask]. When [mask]
     * is the containment mask, this gives interior-only stats.
     *
     * Pure and allocation-light: samples at most 2000 pixels (step) and uses
     * Integer sorting for median, so it is safe on any crop size.
     */
    fun collectStats(pixels: IntArray, mask: ByteArray, width: Int, height: Int): RegionStats {
        val n = minOf(pixels.size, mask.size, width * height)
        val rList = mutableListOf<Int>()
        val gList = mutableListOf<Int>()
        val bList = mutableListOf<Int>()
        var sumGray = 0L
        var sumSqGray = 0L
        var sumSat = 0L
        var nearWhiteCount = 0
        var darkCount = 0
        var edgeCount = 0
        val step = max(1, n / 2000)

        var i = 0
        while (i < n) {
            if (mask[i] != 0.toByte()) {
                val px = pixels[i]
                val r = px shr 16 and 0xFF
                val g = px shr 8 and 0xFF
                val b = px and 0xFF
                val gray = (r * 299 + g * 587 + b * 114) / 1000
                val maxC = max(max(r, g), b)
                val minC = min(min(r, g), b)
                val sat = maxC - minC
                rList.add(r)
                gList.add(g)
                bList.add(b)
                sumGray += gray
                sumSqGray += gray.toLong() * gray
                sumSat += sat.toLong()
                if (gray > 230) nearWhiteCount++
                if (gray < 60) darkCount++
                val x = i % width
                val y = i / width
                if (x > 0 && y > 0) {
                    val leftGray =
                        (((pixels[(y * width) + (x - 1)] shr 16 and 0xFF) * 299) +
                            ((pixels[(y * width) + (x - 1)] shr 8 and 0xFF) * 587) +
                            ((pixels[(y * width) + (x - 1)] and 0xFF) * 114)) / 1000
                    val upGray =
                        (((pixels[((y - 1) * width) + x] shr 16 and 0xFF) * 299) +
                            ((pixels[((y - 1) * width) + x] shr 8 and 0xFF) * 587) +
                            ((pixels[((y - 1) * width) + x] and 0xFF) * 114)) / 1000
                    if (abs(gray - leftGray) + abs(gray - upGray) > 80) edgeCount++
                }
            }
            i += step
        }

        val count = rList.size
        if (count == 0) {
            return RegionStats(
                medianColor = 0xFFFFFFFF.toInt(),
                grayMean = 250f,
                grayStd = 5f,
                nearWhiteRatio = 1f,
                darkPixelRatio = 0f,
                edgeDensity = 0f,
                saturationMean = 0f,
                sampleCount = 0,
            )
        }

        rList.sort()
        gList.sort()
        bList.sort()
        val mid = count / 2
        val median = (0xFF shl 24) or (rList[mid] shl 16) or (gList[mid] shl 8) or bList[mid]
        val grayMean = sumGray.toFloat() / count
        val grayVar = (sumSqGray.toFloat() / count) - grayMean * grayMean
        val grayStd = sqrt(max(0f, grayVar))
        val satMean = sumSat.toFloat() / count

        return RegionStats(
            medianColor = median,
            grayMean = grayMean,
            grayStd = grayStd,
            nearWhiteRatio = nearWhiteCount.toFloat() / count,
            darkPixelRatio = darkCount.toFloat() / count,
            edgeDensity = edgeCount.toFloat() / count,
            saturationMean = satMean,
            sampleCount = count,
        )
    }

    private val colorDelta = CONTAINMENT_DELTA
    private val floodMargin = FLOOD_MARGIN
    private val minFloodFraction = MIN_FLOOD_FRACTION

    /**
     * Build the seed mask (pixels that initiate the BFS) and compute the
     * seed-area median color.
     *
     * For parented bubbles: erode the bubble rect → interior set → median.
     * For free text: border ring around the union of [eraseBoxes] → median.
     */
    private fun buildSeedMask(
        pixels: IntArray,
        w: Int,
        h: Int,
        parentBubble: IntArray?,
        eraseBoxes: List<IntArray>,
    ): Pair<ByteArray, Int> {
        if (parentBubble != null) {
            return buildParentBubbleSeed(pixels, w, h, parentBubble)
        }
        return buildFreeTextSeed(pixels, w, h, eraseBoxes)
    }

    private fun buildParentBubbleSeed(
        pixels: IntArray,
        w: Int,
        h: Int,
        bubble: IntArray,
    ): Pair<ByteArray, Int> {
        val bx1 = bubble[0].coerceIn(0, w)
        val by1 = bubble[1].coerceIn(0, h)
        val bx2 = bubble[2].coerceIn(bx1, w)
        val by2 = bubble[3].coerceIn(by1, h)
        val bw = bx2 - bx1
        val bh = by2 - by1
        val erode = max(2, min(bw, bh) / 10)
        val sx1 = (bx1 + erode).coerceIn(0, w)
        val sy1 = (by1 + erode).coerceIn(0, h)
        val sx2 = (bx2 - erode).coerceIn(sx1, w)
        val sy2 = (by2 - erode).coerceIn(sy1, h)
        return sampleRectMedian(pixels, w, h, sx1, sy1, sx2, sy2)
    }

    private fun buildFreeTextSeed(
        pixels: IntArray,
        w: Int,
        h: Int,
        eraseBoxes: List<IntArray>,
    ): Pair<ByteArray, Int> {
        if (eraseBoxes.isEmpty()) return Pair(ByteArray(w * h), 0xFFFFFFFF.toInt())

        val ringWidth = 12
        val ringEx1 = eraseBoxes.minOf { max(0, it[0] - ringWidth) }.coerceIn(0, w)
        val ringEy1 = eraseBoxes.minOf { max(0, it[1] - ringWidth) }.coerceIn(0, h)
        val ringEx2 = eraseBoxes.maxOf { min(w, it[2] + ringWidth) }.coerceIn(0, w)
        val ringEy2 = eraseBoxes.maxOf { min(h, it[3] + ringWidth) }.coerceIn(0, h)

        val boxInnerPad = 2
        val innerEx1 = eraseBoxes.minOf { max(0, it[0] - boxInnerPad) }.coerceIn(0, w)
        val innerEy1 = eraseBoxes.minOf { max(0, it[1] - boxInnerPad) }.coerceIn(0, h)
        val innerEx2 = eraseBoxes.maxOf { min(w, it[2] + boxInnerPad) }.coerceIn(0, w)
        val innerEy2 = eraseBoxes.maxOf { min(h, it[3] + boxInnerPad) }.coerceIn(0, h)

        val seedMask = ByteArray(w * h)
        for (y in ringEy1 until ringEy2) {
            for (x in ringEx1 until ringEx2) {
                if (x in innerEx1 until innerEx2 && y in innerEy1 until innerEy2) continue
                seedMask[y * w + x] = 1
            }
        }

        val median = computeRingMedian(pixels, seedMask, w, h)
        if (median == 0xFFFFFFFF.toInt()) return Pair(seedMask, median)

        // Filter seed to pixels close to the ring median (remove text strokes
        // that fall in the ring).
        val seedR = (median shr 16 and 0xFF).toFloat()
        val seedG = (median shr 8 and 0xFF).toFloat()
        val seedB = (median and 0xFF).toFloat()
        val filtered = ByteArray(w * h)
        var seedCount = 0
        for (i in 0 until w * h) {
            if (seedMask[i] == 0.toByte()) continue
            val px = pixels[i]
            val r = (px shr 16 and 0xFF).toFloat()
            val g = (px shr 8 and 0xFF).toFloat()
            val b = (px and 0xFF).toFloat()
            val dr = r - seedR
            val dg = g - seedG
            val db = b - seedB
            if (sqrt(dr * dr + dg * dg + db * db) <= colorDelta * 1.5f) {
                filtered[i] = 1
                seedCount++
            }
        }

        return if (seedCount > 0) {
            Pair(filtered, median)
        } else {
            Pair(seedMask, median)
        }
    }

    /**
     * Sample the median of pixels inside [sx1, sy1, sx2, sy2] and return
     * (seedMask, median). The seed mask is a ByteArray sized w×h with 1 at
     * every pixel inside the rect whose colour is close to the median.
     */
    private fun sampleRectMedian(
        pixels: IntArray,
        w: Int,
        h: Int,
        sx1: Int,
        sy1: Int,
        sx2: Int,
        sy2: Int,
    ): Pair<ByteArray, Int> {
        val n = w * h
        if (sx2 <= sx1 || sy2 <= sy1) return Pair(ByteArray(n), 0xFFFFFFFF.toInt())

        val rList = mutableListOf<Int>()
        val gList = mutableListOf<Int>()
        val bList = mutableListOf<Int>()
        val step = max(1, ((sx2 - sx1) * (sy2 - sy1)) / 500)

        var idx = 0
        for (y in sy1 until sy2) {
            for (x in sx1 until sx2) {
                if (idx % step == 0) {
                    val px = pixels[y * w + x]
                    rList.add(px shr 16 and 0xFF)
                    gList.add(px shr 8 and 0xFF)
                    bList.add(px and 0xFF)
                }
                idx++
            }
        }

        if (rList.isEmpty()) return Pair(ByteArray(n), 0xFFFFFFFF.toInt())
        rList.sort()
        gList.sort()
        bList.sort()
        val mid = rList.size / 2
        val median = (0xFF shl 24) or (rList[mid] shl 16) or (gList[mid] shl 8) or bList[mid]

        val seedMask = ByteArray(n)
        val mR = median shr 16 and 0xFF
        val mG = median shr 8 and 0xFF
        val mB = median and 0xFF
        for (y in sy1 until sy2) {
            for (x in sx1 until sx2) {
                val px = pixels[y * w + x]
                val dr = (px shr 16 and 0xFF) - mR
                val dg = (px shr 8 and 0xFF) - mG
                val db = (px and 0xFF) - mB
                if (dr * dr + dg * dg + db * db <= (colorDelta * colorDelta).toInt()) {
                    seedMask[y * w + x] = 1
                }
            }
        }

        return Pair(seedMask, median)
    }

    /**
     * Build a padded-union mask from [boxes] with [pad] pixels added to every
     * side. Used as the fallback when containment flood fails.
     */
    private fun paddedUnionMask(
        w: Int,
        h: Int,
        boxes: List<IntArray>,
        pad: Int,
    ): ByteArray {
        val mask = ByteArray(w * h)
        for (box in boxes) {
            val x1 = (box[0] - pad).coerceIn(0, w)
            val y1 = (box[1] - pad).coerceIn(0, h)
            val x2 = (box[2] + pad).coerceIn(0, w)
            val y2 = (box[3] + pad).coerceIn(0, h)
            if (x2 <= x1 || y2 <= y1) continue
            for (y in y1 until y2) {
                val row = y * w
                for (x in x1 until x2) {
                    mask[row + x] = 1
                }
            }
        }
        return mask
    }

    /**
     * Compute the median ARGB color of the non-zero pixels in [mask].
     */
    private fun computeRingMedian(
        pixels: IntArray,
        mask: ByteArray,
        w: Int,
        h: Int,
    ): Int {
        val rList = mutableListOf<Int>()
        val gList = mutableListOf<Int>()
        val bList = mutableListOf<Int>()
        for (i in 0 until w * h) {
            if (mask[i] == 0.toByte()) continue
            val px = pixels[i]
            rList.add(px shr 16 and 0xFF)
            gList.add(px shr 8 and 0xFF)
            bList.add(px and 0xFF)
        }
        if (rList.isEmpty()) return 0xFFFFFFFF.toInt()
        rList.sort()
        gList.sort()
        bList.sort()
        val mid = rList.size / 2
        return (0xFF shl 24) or (rList[mid] shl 16) or (gList[mid] shl 8) or bList[mid]
    }

}
