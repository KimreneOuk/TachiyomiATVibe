package eu.kanade.translation.inpainting

import android.graphics.Bitmap
import android.graphics.Color
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class SmartBubbleTextCleaner(
    private val contextPad: Int = 10,
    private val textMaskPad: Int = 2,
    private val colorDistanceThreshold: Float = 45.0f,
    // 2→3 iterations: faint-text detection captures thinner strokes whose
    // anti-aliased edges show through the fill; the extra dilation pass grows
    // the mask to cover them before feathering.
    private val dilationIterations: Int = 3,
    private val featherRadius: Int = 6,
) {
    private var workingBuffer1: IntArray? = null
    private var workingBuffer2: IntArray? = null
    private var currentBufferSize = 0

    private fun getWorkingBuffers(size: Int): Pair<IntArray, IntArray> {
        if (size > MAX_CACHED_PIXELS) {
            return Pair(IntArray(size), IntArray(size))
        }
        return if (size == currentBufferSize &&
            workingBuffer1 != null &&
            workingBuffer2 != null
        ) {
            Pair(workingBuffer1!!, workingBuffer2!!)
        } else {
            workingBuffer1 = IntArray(size)
            workingBuffer2 = IntArray(size)
            currentBufferSize = size
            Pair(workingBuffer1!!, workingBuffer2!!)
        }
    }

    fun cleanRegions(
        image: Bitmap,
        boxes: List<IntArray>,
    ): Bitmap {
        for (box in boxes) {
            val x1 = box[0].coerceIn(0, image.width)
            val y1 = box[1].coerceIn(0, image.height)
            val x2 = box[2].coerceIn(x1, image.width)
            val y2 = box[3].coerceIn(y1, image.height)
            if (x2 <= x1 || y2 <= y1) continue
            cleanSingleRegion(image, x1, y1, x2, y2)
        }
        return image
    }

    /**
     * TachiyomiAT: FAST free-text erase driven by SOLID boxes (the prototype
     * `mask_mode = paddle_boxes` behavior for the non-neural path).
     *
     * Boxes are PaddleOCR-v6 line boxes back-projected to page coords by
     * [AOTInpaintion.refineFreeTextBoxes] (or a detector-v4 fallback box). Each
     * box is padded by [maskPad] and filled SOLID — no [generateTextMask], no
     * faint-recovery. The prior bug came from shrinking/over-processing masks
     * after detection; this path is deliberately direct: solid padded box → fill.
     *
     * The fill reuses the cleaner's background-estimate + feathered-blend
     * machinery so behavior is consistent with [cleanSingleRegion]; only the
     * erase-mask source differs. Used by the FAST route in
     * [AOTInpaintion.inpaintRegions] when Paddle-refined boxes are available;
     * [cleanRegions] (pixel heuristics) remains the conservative fallback when
     * `paddleDet` is null.
     */
    fun fillSolidBoxes(
        image: Bitmap,
        boxes: List<IntArray>,
        maskPad: Int,
    ): Bitmap {
        for (box in boxes) {
            val x1 = box[0].coerceIn(0, image.width)
            val y1 = box[1].coerceIn(0, image.height)
            val x2 = box[2].coerceIn(x1, image.width)
            val y2 = box[3].coerceIn(y1, image.height)
            if (x2 <= x1 || y2 <= y1) continue
            fillSolidRegion(image, x1, y1, x2, y2, maskPad)
        }
        return image
    }

    /**
     * TachiyomiAT: body of [fillSolidBoxes] for one padded SOLID box. The erase
     * mask is the solid padded box; the fill is the **Telea Fast Marching Method**
     * ([FastMarchingMethod.inpaintTelea]) — same as the prototype's
     * `cv2.inpaint(INPAINT_TELEA)`.
     *
     * Telea reconstructs each hole pixel from its known neighbours in fast-
     * marching arrival order, so surrounding gradient/texture flows in rather
     * than a flat block. A feathered blend softens the hole/artwork boundary.
     */
    private fun fillSolidRegion(
        image: Bitmap,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        maskPad: Int,
    ) {
        val w = image.width
        val h = image.height

        val pad = contextPad
        val cx1 = max(0, x1 - pad)
        val cy1 = max(0, y1 - pad)
        val cx2 = min(w, x2 + pad)
        val cy2 = min(h, y2 + pad)

        val contextW = cx2 - cx1
        val contextH = cy2 - cy1
        val contextSize = contextW * contextH

        val (contextPixels, resultPixels) = getWorkingBuffers(contextSize)
        image.getPixels(contextPixels, 0, contextW, cx1, cy1, contextW, contextH)
        contextPixels.copyInto(resultPixels, endIndex = contextSize)

        val localX1 = x1 - cx1
        val localY1 = y1 - cy1
        val localX2 = x2 - cx1
        val localY2 = y2 - cy1

        // Erase mask: the SOLID padded box (no pixel-heuristic detection).
        val fx1 = max(0, localX1 - maskPad)
        val fy1 = max(0, localY1 - maskPad)
        val fx2 = min(contextW, localX2 + maskPad)
        val fy2 = min(contextH, localY2 + maskPad)
        val solidMask = ByteArray(contextW * contextH)
        if (fx2 > fx1 && fy2 > fy1) {
            for (y in fy1 until fy2) {
                val row = y * contextW
                for (x in fx1 until fx2) {
                    solidMask[row + x] = 1
                }
            }
        }

        val minRegionDim = (localX2 - localX1).coerceAtLeast(1)
            .coerceAtMost((localY2 - localY1).coerceAtLeast(1))
        val (scaledFeather, _) = BubbleCleanerMath.scaledMorphology(minRegionDim, featherRadius, dilationIterations)

        // Telea reconstructs in place; copy so contextPixels stays original for the blend.
        // radius=3 matches the prototype (cv2.inpaint flag 3) and cleanBubbleGroupFmm.
        val reconstructed = contextPixels.copyOf()
        FastMarchingMethod.inpaintTelea(reconstructed, solidMask, contextW, contextH, radius = 3)

        // Feather-blend so the hole boundary is a soft ramp; alpha covers mask core + ring.
        val alpha = BubbleMaskBuilder.featherAlpha(solidMask, contextW, contextH, scaledFeather)
        val filled = applyFeatheredFill(contextPixels, reconstructed, alpha)
        System.arraycopy(filled, 0, resultPixels, 0, contextW * contextH)

        image.setPixels(resultPixels, 0, contextW, cx1, cy1, contextW, contextH)
        logcat(LogPriority.INFO) {
            "[bubble_cleaner] telea solid box " +
                "mask=${BubbleMaskBuilder.maskCoverage(solidMask).format1()}% roi=${contextW}x$contextH"
        }
    }

    private fun cleanSingleRegion(
        image: Bitmap,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
    ) {
        val w = image.width
        val h = image.height

        val pad = contextPad
        val cx1 = max(0, x1 - pad)
        val cy1 = max(0, y1 - pad)
        val cx2 = min(w, x2 + pad)
        val cy2 = min(h, y2 + pad)

        val contextW = cx2 - cx1
        val contextH = cy2 - cy1
        val contextSize = contextW * contextH

        val (contextPixels, resultPixels) = getWorkingBuffers(contextSize)
        image.getPixels(contextPixels, 0, contextW, cx1, cy1, contextW, contextH)
        contextPixels.copyInto(resultPixels, endIndex = contextSize)

        val localX1 = x1 - cx1
        val localY1 = y1 - cy1
        val localX2 = x2 - cx1
        val localY2 = y2 - cy1

        // TachiyomiAT: scale text-mask pad by region size (see cleanBubbleGroup).
        val minRegionDim = min(localX2 - localX1, localY2 - localY1).coerceAtLeast(1)
        val mp = BubbleCleanerMath.scaledTextMaskPad(minRegionDim, textMaskPad)
        val ex1 = max(0, localX1 - mp)
        val ey1 = max(0, localY1 - mp)
        val ex2 = min(contextW, localX2 + mp)
        val ey2 = min(contextH, localY2 + mp)

        val ringMp = textMaskPad + 6
        val ringEx1 = max(0, localX1 - ringMp)
        val ringEy1 = max(0, localY1 - ringMp)
        val ringEx2 = min(contextW, localX2 + ringMp)
        val ringEy2 = min(contextH, localY2 + ringMp)

        val ringMask = ByteArray(contextW * contextH) { 1.toByte() }
        val border = 2
        for (bx in 0 until border) {
            for (by in 0 until contextH) {
                ringMask[by * contextW + bx] = 0
                ringMask[by * contextW + (contextW - 1 - bx)] = 0
            }
        }
        for (by in 0 until border) {
            for (bx in 0 until contextW) {
                ringMask[by * contextW + bx] = 0
                ringMask[(contextH - 1 - by) * contextW + bx] = 0
            }
        }
        for (ry in ringEy1 until ringEy2) {
            for (rx in ringEx1 until ringEx2) {
                ringMask[ry * contextW + rx] = 0
            }
        }

        val bgStats = sampleBackgroundStats(contextPixels, ringMask, contextW, contextH)
        val bgType = BubbleCleanerMath.classifyBackground(bgStats)

        if (ex2 <= ex1 || ey2 <= ey1) return

        val boxMask = generateTextMask(
            contextPixels, bgStats, bgType,
            ex1, ey1, ex2, ey2, contextW, contextH,
        )

        var combinedMask = ByteArray(contextW * contextH)
        val zoneW = ex2 - ex1
        val zoneH = ey2 - ey1
        var hasAny = false
        for (zy in 0 until zoneH) {
            for (zx in 0 until zoneW) {
                if (boxMask[zy * zoneW + zx] != 0.toByte()) {
                    combinedMask[(ey1 + zy) * contextW + (ex1 + zx)] = 1
                    hasAny = true
                }
            }
        }
        if (!hasAny) {
            // Faint-text recovery (cf. cleanBubbleGroup): lowered-threshold pass
            // keyed off the ring median before the whole-box "gray rectangle" fill.
            val singleBox = intArrayOf(ex1, ey1, ex2, ey2)
            val recovered = recoverFaintTextMask(
                contextPixels,
                bgStats,
                listOf(singleBox),
                contextW,
                contextH,
            )
            if (recovered.any { it != 0.toByte() }) {
                for (i in recovered.indices) {
                    if (recovered[i] != 0.toByte()) combinedMask[i] = 1
                }
            } else {
                val tightFallback = tightDifferenceMask(
                    contextPixels,
                    bgStats,
                    listOf(singleBox),
                    contextW,
                    contextH,
                )
                for (i in tightFallback.indices) {
                    if (tightFallback[i] != 0.toByte()) combinedMask[i] = 1
                }
            }
        }
        combinedMask = BubbleCleanerMath.unionMasks(
            combinedMask,
            buildLocalContrastTextMask(
                pixels = contextPixels,
                boxes = listOf(intArrayOf(ex1, ey1, ex2, ey2)),
                width = contextW,
                height = contextH,
            ),
        )

        var finalMask = combinedMask
        // TachiyomiAT: scale morphology by region size for proportional feather/dilation.
        val (scaledFeather, scaledDilation) = BubbleCleanerMath.scaledMorphology(
            minRegionDim,
            featherRadius,
            dilationIterations,
        )
        finalMask = BubbleMaskBuilder.dilateMaskDisk(finalMask, contextW, contextH, scaledDilation)
        if (BubbleMaskBuilder.maskCoverage(finalMask) < MIN_OCR_TEXT_MASK_COVERAGE) {
            val aggressiveContrast = buildLocalContrastTextMask(
                pixels = contextPixels,
                boxes = listOf(intArrayOf(ex1, ey1, ex2, ey2)),
                width = contextW,
                height = contextH,
                aggressive = false,
            )
            finalMask = BubbleCleanerMath.unionMasks(finalMask, aggressiveContrast)
            if (BubbleMaskBuilder.maskCoverage(finalMask) < MIN_OCR_TEXT_MASK_COVERAGE) {
                logcat(LogPriority.INFO) {
                    "[bubble_cleaner] low text-mask coverage; leaving OCR region unfilled"
                }
            }
        }
        val alpha = BubbleMaskBuilder.featherAlpha(finalMask, contextW, contextH, scaledFeather)

        // TachiyomiAT: fill region = mask core + feather ring (alpha > 0), so
        // buildLocalBackground interpolates the ring and the blend is a soft ramp.
        val fillMask = ByteArray(contextW * contextH)
        for (i in alpha.indices) {
            if (alpha[i] > 0f) fillMask[i] = 1
        }

        // TachiyomiAT: local per-pixel background — gray-rectangle fix (cf. cleanBubbleGroup).
        val localBg = buildLocalBackground(
            contextPixels,
            fillMask,
            contextW,
            contextH,
            bgStats.medianColor,
            preferFlatFill = BubbleCleanerMath.shouldUseSolidFlatFill(bgType, bgStats),
        )

        val filled = applyFeatheredFill(contextPixels, localBg, alpha)
        System.arraycopy(filled, 0, resultPixels, 0, contextW * contextH)

        image.setPixels(resultPixels, 0, contextW, cx1, cy1, contextW, contextH)
        logcat(LogPriority.INFO) {
            "[bubble_cleaner] region bg=$bgType std=${bgStats.grayStd.format1()} " +
                "mask=${BubbleMaskBuilder.maskCoverage(finalMask).format1()}% roi=${contextW}x$contextH"
        }
    }

    /**
     * TachiyomiAT: recovery pass when [generateTextMask] finds nothing. Re-scans
     * each box with a much lower threshold keyed off the ring-sampled background
     * median (reliable: sampled from the border ring, not the text-filled box),
     * so faint strokes the main pass missed still register.
     * removeEdgeTouchingComponents bounds the result to genuine text.
     *
     * Returns a full-context-sized mask; caller ANDs it into [combinedMask].
     */
    private fun recoverFaintTextMask(
        pixels: IntArray,
        stats: BackgroundStats,
        boxes: List<IntArray>,
        contextW: Int,
        contextH: Int,
    ): ByteArray {
        val combined = ByteArray(contextW * contextH)
        if (boxes.isEmpty()) return combined
        val ringR = (stats.medianColor shr 16 and 0xFF).toFloat()
        val ringG = (stats.medianColor shr 8 and 0xFF).toFloat()
        val ringB = (stats.medianColor and 0xFF).toFloat()
        // Floor threshold (45 → 12) so faint strokes still register; bounded by
        // removeEdgeTouchingComponents + downstream mask intersections.
        val floorThresh = 12.0f
        for (box in boxes) {
            val bx1 = box[0].coerceIn(0, contextW)
            val by1 = box[1].coerceIn(0, contextH)
            val bx2 = box[2].coerceIn(bx1, contextW)
            val by2 = box[3].coerceIn(by1, contextH)
            if (bx2 <= bx1 || by2 <= by1) continue
            val zoneW = bx2 - bx1
            val zoneH = by2 - by1
            val zoneMask = ByteArray(zoneW * zoneH)
            var zoneHasAny = false
            for (zy in 0 until zoneH) {
                for (zx in 0 until zoneW) {
                    val px = pixels[(by1 + zy) * contextW + (bx1 + zx)]
                    val r = (px shr 16 and 0xFF).toFloat()
                    val g = (px shr 8 and 0xFF).toFloat()
                    val b = (px and 0xFF).toFloat()
                    val dr = r - ringR
                    val dg = g - ringG
                    val db = b - ringB
                    if (sqrt(dr * dr + dg * dg + db * db) > floorThresh) {
                        zoneMask[zy * zoneW + zx] = 1
                        zoneHasAny = true
                    }
                }
            }
            if (!zoneHasAny) continue
            // Drop edge-touching components (likely anti-aliasing bleed, not text).
            val filtered = BubbleMaskBuilder.removeEdgeTouchingComponents(zoneMask, zoneW, zoneH)
            for (zy in 0 until zoneH) {
                for (zx in 0 until zoneW) {
                    if (filtered[zy * zoneW + zx] != 0.toByte()) {
                        combined[(by1 + zy) * contextW + (bx1 + zx)] = 1
                    }
                }
            }
        }
        return combined
    }

    /**
     * TachiyomiAT: last-resort fallback when even [recoverFaintTextMask] finds
     * nothing. Marks ONLY pixels differing from the ring median by > epsilon,
     * rather than the whole box (avoids the "gray rectangle" / "too much space"
     * artifact). Preserves inter-stroke background; no pixels set on uniform
     * images.
     *
     * Per-pixel (not bounding-rectangle): the old impl filled the whole
     * enclosing rect and erased inter-stroke gaps. The downstream dilation
     * ([BubbleMaskBuilder.dilateMask]) covers each stroke's anti-aliased fringe
     * without erasing those gaps, so per-pixel marking is correct.
     */
    internal fun tightDifferenceMask(
        pixels: IntArray,
        stats: BackgroundStats,
        boxes: List<IntArray>,
        contextW: Int,
        contextH: Int,
    ): ByteArray {
        val combined = ByteArray(contextW * contextH)
        if (boxes.isEmpty()) return combined
        val ringR = (stats.medianColor shr 16 and 0xFF)
        val ringG = (stats.medianColor shr 8 and 0xFF)
        val ringB = (stats.medianColor and 0xFF)
        // Permissive epsilon: marks any non-background pixel as a text candidate.
        val epsilon = 8
        for (box in boxes) {
            val bx1 = box[0].coerceIn(0, contextW)
            val by1 = box[1].coerceIn(0, contextH)
            val bx2 = box[2].coerceIn(bx1, contextW)
            val by2 = box[3].coerceIn(by1, contextH)
            if (bx2 <= bx1 || by2 <= by1) continue
            for (y in by1 until by2) {
                for (x in bx1 until bx2) {
                    val px = pixels[y * contextW + x]
                    val r = px shr 16 and 0xFF
                    val g = px shr 8 and 0xFF
                    val b = px and 0xFF
                    if (abs(r - ringR) + abs(g - ringG) + abs(b - ringB) > epsilon) {
                        combined[y * contextW + x] = 1
                    }
                }
            }
        }
        return combined
    }

    private fun sampleBackgroundStats(
        pixels: IntArray,
        ringMask: ByteArray,
        width: Int,
        height: Int,
    ): BackgroundStats {
        val rList = mutableListOf<Int>()
        val gList = mutableListOf<Int>()
        val bList = mutableListOf<Int>()
        var sumGray = 0L
        var sumSqGray = 0L
        var nearWhiteCount = 0
        var darkCount = 0
        var edgeCount = 0

        for (y in 0 until height) {
            for (x in 0 until width) {
                if (ringMask[y * width + x] == 0.toByte()) continue
                val px = pixels[y * width + x]
                val r = px shr 16 and 0xFF
                val g = px shr 8 and 0xFF
                val b = px and 0xFF
                val gray = (r * 299 + g * 587 + b * 114) / 1000
                rList.add(r)
                gList.add(g)
                bList.add(b)
                sumGray += gray
                sumSqGray += gray.toLong() * gray
                if (gray > 230) nearWhiteCount++
                if (gray < 60) darkCount++
                if (x > 0 && y > 0) {
                    val left = pixels[y * width + (x - 1)]
                    val up = pixels[(y - 1) * width + x]
                    val leftGray =
                        (((left shr 16 and 0xFF) * 299) + ((left shr 8 and 0xFF) * 587) + ((left and 0xFF) * 114)) /
                            1000
                    val upGray =
                        (((up shr 16 and 0xFF) * 299) + ((up shr 8 and 0xFF) * 587) + ((up and 0xFF) * 114)) / 1000
                    if (abs(gray - leftGray) + abs(gray - upGray) > 80) edgeCount++
                }
            }
        }

        val count = rList.size
        if (count == 0) {
            return BackgroundStats(
                medianColor = Color.WHITE,
                grayMean = 250.0f,
                grayStd = 5.0f,
                nearWhiteRatio = 1.0f,
                darkPixelRatio = 0.0f,
                edgeDensity = 0.0f,
                sampleCount = 0,
            )
        }

        rList.sort()
        gList.sort()
        bList.sort()
        val medianR = rList[count / 2]
        val medianG = gList[count / 2]
        val medianB = bList[count / 2]
        val grayMean = sumGray.toFloat() / count
        val grayVar = sumSqGray.toFloat() / count - grayMean * grayMean
        val grayStd = sqrt(max(0f, grayVar))

        return BackgroundStats(
            medianColor = (0xFF shl 24) or (medianR shl 16) or (medianG shl 8) or medianB,
            grayMean = grayMean,
            grayStd = grayStd,
            nearWhiteRatio = nearWhiteCount.toFloat() / count,
            darkPixelRatio = darkCount.toFloat() / count,
            edgeDensity = edgeCount.toFloat() / count,
            sampleCount = count,
        )
    }

    private fun generateTextMask(
        pixels: IntArray,
        stats: BackgroundStats,
        bgType: String,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        contextW: Int,
        contextH: Int,
    ): ByteArray {
        val zoneW = x2 - x1
        val zoneH = y2 - y1
        if (zoneW <= 0 || zoneH <= 0) return ByteArray(0)

        val mask = ByteArray(zoneW * zoneH)
        val bgCluster = findBackgroundCluster(pixels, x1, y1, zoneW, zoneH, contextW)

        // TachiyomiAT: the in-box bg centroid (bgCluster) drifts toward the text
        // color when the box is mostly text, so faint strokes fall under threshold.
        // The ring-sampled median (border ring = real background) is more reliable.
        // Mark as text if a pixel differs from EITHER reference beyond threshold;
        // the OR catches faint strokes while removeEdgeTouchingComponents + the
        // downstream mask intersection bound the result to the text region.
        val ringR = (stats.medianColor shr 16 and 0xFF).toFloat()
        val ringG = (stats.medianColor shr 8 and 0xFF).toFloat()
        val ringB = (stats.medianColor and 0xFF).toFloat()

        var threshVal = colorDistanceThreshold
        if (bgCluster.foregroundDistance < threshVal * 1.5f) {
            // Low-contrast text: lower threshold so faint strokes clear the bar;
            // extra dilation covers their anti-aliased edges.
            threshVal = max(12.0f, bgCluster.foregroundDistance * 0.4f)
        }

        for (zy in 0 until zoneH) {
            for (zx in 0 until zoneW) {
                val px = pixels[(y1 + zy) * contextW + (x1 + zx)]
                val r = (px shr 16 and 0xFF).toFloat()
                val g = (px shr 8 and 0xFF).toFloat()
                val b = (px and 0xFF).toFloat()
                val dr = r - bgCluster.r
                val dg = g - bgCluster.g
                val db = b - bgCluster.b
                val distFromCluster = sqrt(dr * dr + dg * dg + db * db)
                val drRing = r - ringR
                val dgRing = g - ringG
                val dbRing = b - ringB
                val distFromRing = sqrt(drRing * drRing + dgRing * dgRing + dbRing * dbRing)
                if (distFromCluster > threshVal || distFromRing > threshVal) {
                    mask[zy * zoneW + zx] = 1
                }
            }
        }
        return BubbleMaskBuilder.removeEdgeTouchingComponents(mask, zoneW, zoneH)
    }

    private fun findBackgroundCluster(
        pixels: IntArray,
        x1: Int,
        y1: Int,
        zoneW: Int,
        zoneH: Int,
        contextW: Int,
    ): ColorCluster {
        val total = zoneW * zoneH
        val step = max(1, total / 1000)
        val samples = ArrayList<Int>(min(total, 1000))
        for (i in 0 until total step step) {
            val x = i % zoneW
            val y = i / zoneW
            samples.add(pixels[(y1 + y) * contextW + (x1 + x)])
        }

        var c0r = 0f
        var c0g = 0f
        var c0b = 0f
        var c1r = 255f
        var c1g = 255f
        var c1b = 255f
        var count0 = 0
        var count1 = 0

        repeat(5) {
            var s0r = 0f
            var s0g = 0f
            var s0b = 0f
            var s1r = 0f
            var s1g = 0f
            var s1b = 0f
            count0 = 0
            count1 = 0
            for (px in samples) {
                val r = (px shr 16 and 0xFF).toFloat()
                val g = (px shr 8 and 0xFF).toFloat()
                val b = (px and 0xFF).toFloat()
                val d0 = sq(r - c0r) + sq(g - c0g) + sq(b - c0b)
                val d1 = sq(r - c1r) + sq(g - c1g) + sq(b - c1b)
                if (d0 < d1) {
                    s0r += r
                    s0g += g
                    s0b += b
                    count0++
                } else {
                    s1r += r
                    s1g += g
                    s1b += b
                    count1++
                }
            }
            if (count0 > 0) {
                c0r = s0r / count0
                c0g = s0g / count0
                c0b = s0b / count0
            }
            if (count1 > 0) {
                c1r = s1r / count1
                c1g = s1g / count1
                c1b = s1b / count1
            }
        }

        val bgIsZero = count0 >= count1
        val bgR = if (bgIsZero) c0r else c1r
        val bgG = if (bgIsZero) c0g else c1g
        val bgB = if (bgIsZero) c0b else c1b
        val fgR = if (bgIsZero) c1r else c0r
        val fgG = if (bgIsZero) c1g else c0g
        val fgB = if (bgIsZero) c1b else c0b
        val fgDistance = sqrt(sq(fgR - bgR) + sq(fgG - bgG) + sq(fgB - bgB))
        return ColorCluster(bgR, bgG, bgB, fgDistance)
    }

    private fun sq(value: Float): Float = value * value

    private fun Float.format1(): String = "%.1f".format(this)

    /**
     * TachiyomiAT: per-pixel LOCAL background color, replacing the single flat
     * ring-median fill that produced a "gray rectangle over the whole bounding
     * box" on grayscale/tinted backgrounds.
     *
     * For each masked pixel, averages surrounding non-masked background pixels
     * in a box kernel with a Gaussian falloff (poor-man's inpaint: background
     * is interpolated across the text hole). Background pixels keep their own
     * color. [medianColor] is the fallback when no background falls in the
     * kernel (degenerate all-masked case).
     *
     * Cost is O(n·ksize²), the same class as [buildFeatherAlpha], so no perf
     * regression. `internal` so [SmartBubbleTextCleanerTest] can exercise it
     * without an Android Bitmap.
     */
    internal fun buildLocalBackground(
        pixels: IntArray,
        mask: ByteArray,
        width: Int,
        height: Int,
        medianColor: Int,
        preferFlatFill: Boolean = false,
        /**
         * TachiyomiAT: optional constraint on eligible background source pixels.
         * When non-null, only pixels NOT in [mask] AND in [bgSourceMask] are used.
         *
         * Fixes the color-bleed artifact where, on a tinted page, the background
         * scan reached past the bubble boundary into surrounding artwork and
         * pulled its color into the bubble fill. Passing the eroded bubble
         * interior keeps the reference inside the bubble. Null preserves the
         * whole-context behavior (free-text path with no parent bubble).
         */
        bgSourceMask: ByteArray? = null,
    ): IntArray {
        val n = width * height
        val bg = IntArray(n)
        val medianR = (medianColor shr 16 and 0xFF)
        val medianG = (medianColor shr 8 and 0xFF)
        val medianB = (medianColor and 0xFF)

        if (preferFlatFill) {
            val fill = (0xFF shl 24) or (medianR shl 16) or (medianG shl 8) or medianB
            for (i in 0 until n) {
                bg[i] = if (mask[i] == 0.toByte()) pixels[i] else fill
            }
            return bg
        }

        val r = max(2, featherRadius)
        val kernelGaussian = FloatArray(r * 2 + 1) { dy ->
            val fy = dy - r
            // sigma ~ r/2 for gentle falloff over the kernel half-width.
            kotlin.math.exp(-(fy * fy).toFloat() / (2.0f * (r / 2.0f) * (r / 2.0f)))
        }
        val directionalBg = buildDirectionalBackground(pixels, mask, width, height, bgSourceMask)

        // TachiyomiAT: neighbor is a background reference only when outside [mask]
        // AND (if supplied) inside the allowed source region.
        fun isBgSource(ni: Int): Boolean {
            if (mask[ni] != 0.toByte()) return false
            return bgSourceMask == null || bgSourceMask[ni] != 0.toByte()
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                if (mask[idx] == 0.toByte()) {
                    bg[idx] = pixels[idx]
                    continue
                }
                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var weight = 0f
                for (ky in -r..r) {
                    val ny = y + ky
                    if (ny !in 0 until height) continue
                    val wy = kernelGaussian[ky + r]
                    val rowOff = ny * width
                    for (kx in -r..r) {
                        val nx = x + kx
                        if (nx !in 0 until width) continue
                        val ni = rowOff + nx
                        if (!isBgSource(ni)) continue
                        val px = pixels[ni]
                        val w = wy * kernelGaussian[kx + r]
                        sumR += (px shr 16 and 0xFF) * w
                        sumG += (px shr 8 and 0xFF) * w
                        sumB += (px and 0xFF) * w
                        weight += w
                    }
                }
                bg[idx] = if (weight > 1e-3f) {
                    val rr = (sumR / weight).roundToInt().coerceIn(0, 255)
                    val gg = (sumG / weight).roundToInt().coerceIn(0, 255)
                    val bb = (sumB / weight).roundToInt().coerceIn(0, 255)
                    (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                } else if (directionalBg[idx] != 0) {
                    directionalBg[idx]
                } else {
                    // Degenerate (all neighbors masked): fall back to ring median.
                    (0xFF shl 24) or (medianR shl 16) or (medianG shl 8) or medianB
                }
            }
        }
        return bg
    }

    internal fun buildLocalContrastTextMask(
        pixels: IntArray,
        boxes: List<IntArray>,
        width: Int,
        height: Int,
        aggressive: Boolean = false,
    ): ByteArray {
        val n = min(pixels.size, width * height)
        if (n <= 0 || boxes.isEmpty()) return ByteArray(width * height)

        val gray = IntArray(width * height)
        for (i in 0 until n) {
            val px = pixels[i]
            gray[i] = ((px shr 16 and 0xFF) * 299 + (px shr 8 and 0xFF) * 587 + (px and 0xFF) * 114) / 1000
        }
        val integralW = width + 1
        val integral = LongArray((width + 1) * (height + 1))
        val integralSq = LongArray((width + 1) * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0L
            var rowSqSum = 0L
            for (x in 0 until width) {
                val g = gray[y * width + x]
                rowSum += g
                rowSqSum += g.toLong() * g.toLong()
                val dst = (y + 1) * integralW + (x + 1)
                integral[dst] = integral[dst - integralW] + rowSum
                integralSq[dst] = integralSq[dst - integralW] + rowSqSum
            }
        }

        val combined = ByteArray(width * height)
        for (box in boxes) {
            val bx1 = box[0].coerceIn(0, width)
            val by1 = box[1].coerceIn(0, height)
            val bx2 = box[2].coerceIn(bx1, width)
            val by2 = box[3].coerceIn(by1, height)
            if (bx2 <= bx1 || by2 <= by1) continue

            val zoneW = bx2 - bx1
            val zoneH = by2 - by1
            val radius = (min(zoneW, zoneH) / 7).coerceIn(4, if (aggressive) 14 else 10)
            val dominantLightBackground = BubbleCleanerMath.isDominantLightBackground(gray, bx1, by1, bx2, by2, width)
            val zoneMask = ByteArray(zoneW * zoneH)
            val strongTextMask = ByteArray(zoneW * zoneH)
            for (y in by1 until by2) {
                val sy1 = max(0, y - radius)
                val sy2 = min(height, y + radius + 1)
                for (x in bx1 until bx2) {
                    val sx1 = max(0, x - radius)
                    val sx2 = min(width, x + radius + 1)
                    val count = (sx2 - sx1) * (sy2 - sy1)
                    if (count <= 0) continue
                    val sum = BubbleCleanerMath.rectSum(integral, integralW, sx1, sy1, sx2, sy2)
                    val sumSq = BubbleCleanerMath.rectSum(integralSq, integralW, sx1, sy1, sx2, sy2)
                    val mean = sum.toFloat() / count.toFloat()
                    val variance = sumSq.toFloat() / count.toFloat() - mean * mean
                    val std = sqrt(max(0f, variance))
                    val value = gray[y * width + x].toFloat()
                    val delta = abs(value - mean)
                    val threshold = if (aggressive) {
                        max(10f, min(34f, std * 0.70f + 7f))
                    } else {
                        max(14f, min(42f, std * 0.85f + 10f))
                    }
                    val darkStroke = value < mean - threshold || (value < 82f && mean > 110f)
                    val lightStroke =
                        !dominantLightBackground && value > mean + threshold && value > 172f && mean < 205f
                    if (darkStroke || lightStroke) {
                        val zi = (y - by1) * zoneW + (x - bx1)
                        zoneMask[zi] = 1
                        if (value < 92f || value > 232f) {
                            strongTextMask[zi] = 1
                        }
                    }
                }
            }

            val filtered = filterTextLikeComponents(
                suppressLongThinBands(zoneMask, strongTextMask, zoneW, zoneH),
                zoneW,
                zoneH,
                aggressive,
            )
            for (zy in 0 until zoneH) {
                for (zx in 0 until zoneW) {
                    if (filtered[zy * zoneW + zx] != 0.toByte()) {
                        combined[(by1 + zy) * width + (bx1 + zx)] = 1
                    }
                }
            }
        }
        return combined
    }

    private fun filterTextLikeComponents(
        mask: ByteArray,
        width: Int,
        height: Int,
        aggressive: Boolean,
    ): ByteArray {
        val result = ByteArray(mask.size)
        val visited = BooleanArray(mask.size)
        val queue = ArrayDeque<Int>()
        val minArea = if (aggressive) 2 else max(3, (width * height * 0.00008f).roundToInt())
        val maxArea = max(16, (width * height * 0.60f).roundToInt())

        for (start in mask.indices) {
            if (mask[start] == 0.toByte() || visited[start]) continue
            visited[start] = true
            queue.add(start)
            val component = mutableListOf<Int>()
            var minX = width
            var minY = height
            var maxX = 0
            var maxY = 0
            var touchesEdge = false
            while (queue.isNotEmpty()) {
                val idx = queue.removeFirst()
                component.add(idx)
                val x = idx % width
                val y = idx / width
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
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

            val area = component.size
            val componentW = maxX - minX + 1
            val componentH = maxY - minY + 1
            val likelyTextureDot = area < minArea && componentW <= 3 && componentH <= 3
            val likelyPanelOrHatch = touchesEdge && (componentW > width * 0.65f || componentH > height * 0.65f)
            val likelyBandArtifact =
                (componentH <= 3 && componentW > width * 0.55f) ||
                    (componentW <= 3 && componentH > height * 0.55f)
            val tooLarge = area > maxArea || (componentW > width * 0.95f && componentH > height * 0.75f)
            if (!likelyTextureDot && !likelyPanelOrHatch && !likelyBandArtifact && !tooLarge) {
                for (idx in component) result[idx] = 1
            }
        }
        return result
    }

    private fun suppressLongThinBands(
        mask: ByteArray,
        strongTextMask: ByteArray,
        width: Int,
        height: Int,
    ): ByteArray {
        val result = mask.copyOf()
        val rowThreshold = (width * 0.55f).roundToInt().coerceAtLeast(12)
        val colThreshold = (height * 0.55f).roundToInt().coerceAtLeast(12)

        for (y in 0 until height) {
            val row = y * width
            var count = 0
            for (x in 0 until width) {
                if (mask[row + x] != 0.toByte()) count++
            }
            if (count < rowThreshold) continue
            for (x in 0 until width) {
                if (strongTextMask[row + x] == 0.toByte()) result[row + x] = 0
            }
        }

        for (x in 0 until width) {
            var count = 0
            for (y in 0 until height) {
                if (mask[y * width + x] != 0.toByte()) count++
            }
            if (count < colThreshold) continue
            for (y in 0 until height) {
                val idx = y * width + x
                if (strongTextMask[idx] == 0.toByte()) result[idx] = 0
            }
        }

        return result
    }

    private fun buildDirectionalBackground(
        pixels: IntArray,
        mask: ByteArray,
        width: Int,
        height: Int,
        bgSourceMask: ByteArray? = null,
    ): IntArray {
        val n = width * height
        val leftColor = IntArray(n)
        val leftDist = IntArray(n) { Int.MAX_VALUE }
        val rightColor = IntArray(n)
        val rightDist = IntArray(n) { Int.MAX_VALUE }
        val topColor = IntArray(n)
        val topDist = IntArray(n) { Int.MAX_VALUE }
        val bottomColor = IntArray(n)
        val bottomDist = IntArray(n) { Int.MAX_VALUE }

        // TachiyomiAT: anchor only when outside [mask] AND (if supplied) inside
        // [bgSourceMask]; this stops the scan reaching past the bubble boundary
        // into surrounding artwork (the color-bleed source).
        fun isAnchor(idx: Int): Boolean {
            if (mask[idx] != 0.toByte()) return false
            return bgSourceMask == null || bgSourceMask[idx] != 0.toByte()
        }

        for (y in 0 until height) {
            var lastColor = 0
            var lastX = -1
            val row = y * width
            for (x in 0 until width) {
                val idx = row + x
                if (isAnchor(idx)) {
                    lastColor = pixels[idx]
                    lastX = x
                } else if (lastX >= 0) {
                    leftColor[idx] = lastColor
                    leftDist[idx] = x - lastX
                }
            }
            lastColor = 0
            lastX = -1
            for (x in width - 1 downTo 0) {
                val idx = row + x
                if (isAnchor(idx)) {
                    lastColor = pixels[idx]
                    lastX = x
                } else if (lastX >= 0) {
                    rightColor[idx] = lastColor
                    rightDist[idx] = lastX - x
                }
            }
        }

        for (x in 0 until width) {
            var lastColor = 0
            var lastY = -1
            for (y in 0 until height) {
                val idx = y * width + x
                if (isAnchor(idx)) {
                    lastColor = pixels[idx]
                    lastY = y
                } else if (lastY >= 0) {
                    topColor[idx] = lastColor
                    topDist[idx] = y - lastY
                }
            }
            lastColor = 0
            lastY = -1
            for (y in height - 1 downTo 0) {
                val idx = y * width + x
                if (isAnchor(idx)) {
                    lastColor = pixels[idx]
                    lastY = y
                } else if (lastY >= 0) {
                    bottomColor[idx] = lastColor
                    bottomDist[idx] = lastY - y
                }
            }
        }

        val out = IntArray(n)
        for (i in 0 until n) {
            if (mask[i] == 0.toByte()) {
                out[i] = pixels[i]
                continue
            }
            var sumR = 0f
            var sumG = 0f
            var sumB = 0f
            var weight = 0f

            fun add(color: Int, dist: Int) {
                if (dist == Int.MAX_VALUE || color == 0) return
                val w = 1.0f / max(1, dist).toFloat()
                sumR += (color shr 16 and 0xFF) * w
                sumG += (color shr 8 and 0xFF) * w
                sumB += (color and 0xFF) * w
                weight += w
            }

            add(leftColor[i], leftDist[i])
            add(rightColor[i], rightDist[i])
            add(topColor[i], topDist[i])
            add(bottomColor[i], bottomDist[i])

            if (weight > 1e-3f) {
                val r = (sumR / weight).roundToInt().coerceIn(0, 255)
                val g = (sumG / weight).roundToInt().coerceIn(0, 255)
                val b = (sumB / weight).roundToInt().coerceIn(0, 255)
                out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    internal data class BackgroundStats(
        val medianColor: Int,
        val grayMean: Float,
        val grayStd: Float,
        val nearWhiteRatio: Float,
        val darkPixelRatio: Float,
        val edgeDensity: Float,
        /**
         * `0` marks a degenerate empty-ring result (median defaults to
         * `Color.WHITE`); callers must not trust its [medianColor] for a flat fill.
         */
        val sampleCount: Int,
    )

    /**
     * TachiyomiAT: pure feathered-fill, the shared body of cleanBubbleGroup /
     * cleanSingleRegion, extracted so the feathering fix is unit-testable
     * without an Android Bitmap.
     *
     * Blends each pixel where [alpha] > 0 between its original color and the
     * [background] estimate, weighted by alpha: 0 = untouched, 1 = replaced
     * (mask core), in between = soft ramp (feather ring), killing the hard edge.
     *
     * Returns a new IntArray; does not mutate [pixels]. The earlier inline loops
     * gated on `mask != 0`, which skipped the entire feather ring — see the
     * activation fix in cleanBubbleGroup / cleanSingleRegion.
     */
    internal fun applyFeatheredFill(
        pixels: IntArray,
        background: IntArray,
        alpha: FloatArray,
    ): IntArray {
        val n = minOf(pixels.size, background.size, alpha.size)
        val out = IntArray(pixels.size)
        System.arraycopy(pixels, 0, out, 0, pixels.size)
        for (i in 0 until n) {
            val a = alpha[i]
            if (a <= 0.0f) continue
            val inv = 1.0f - a
            val px = pixels[i]
            val bgpx = background[i]
            val r = ((px shr 16 and 0xFF) * inv + (bgpx shr 16 and 0xFF) * a)
                .roundToInt().coerceIn(0, 255)
            val g = ((px shr 8 and 0xFF) * inv + (bgpx shr 8 and 0xFF) * a)
                .roundToInt().coerceIn(0, 255)
            val b = ((px and 0xFF) * inv + (bgpx and 0xFF) * a)
                .roundToInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    private data class ColorCluster(
        val r: Float,
        val g: Float,
        val b: Float,
        val foregroundDistance: Float,
    )

    private companion object {
        private const val MIN_OCR_TEXT_MASK_COVERAGE = 3.0f
        private const val MAX_CACHED_PIXELS = 1_000_000

        /** Fabricated default [BoundaryAwarePipeline] emits for an empty
         *  containment seed; used to detect an untrustworthy interior median
         *  so it is never painted. */
        private const val WHITE_ARGB = 0xFFFFFFFF.toInt()
    }

    fun clearWorkingBuffers() {
        workingBuffer1 = null
        workingBuffer2 = null
        currentBufferSize = 0
    }
}
