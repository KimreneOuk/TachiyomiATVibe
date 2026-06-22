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
    // TachiyomiAT: 2 → 3 iterations. With faint-text detection now also keying off
    // the ring-sampled background median (see generateTextMask), the text mask
    // captures thinner/fainter strokes. Those strokes have thin anti-aliased edges
    // that, after feathering, can leave a hairline of the original showing through
    // the fill. One extra dilation pass grows the mask outward by a couple of px so
    // the fill fully covers those edges before feathering softens the boundary.
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

    fun cleanBubbleGroup(
        image: Bitmap,
        bubbleBbox: IntArray,
        textBoxes: List<IntArray>,
    ): Bitmap {
        val w = image.width
        val h = image.height

        val bx1 = bubbleBbox[0].coerceIn(0, w)
        val by1 = bubbleBbox[1].coerceIn(0, h)
        val bx2 = bubbleBbox[2].coerceIn(bx1, w)
        val by2 = bubbleBbox[3].coerceIn(by1, h)
        if (bx2 <= bx1 || by2 <= by1) return image

        val pad = contextPad
        val cx1 = max(0, bx1 - pad)
        val cy1 = max(0, by1 - pad)
        val cx2 = min(w, bx2 + pad)
        val cy2 = min(h, by2 + pad)

        val contextW = cx2 - cx1
        val contextH = cy2 - cy1
        val contextSize = contextW * contextH

        val (contextPixels, resultPixels) = getWorkingBuffers(contextSize)
        image.getPixels(contextPixels, 0, contextW, cx1, cy1, contextW, contextH)
        contextPixels.copyInto(resultPixels, endIndex = contextSize)

        val localTextBoxes = textBoxes.map { box ->
            val lx1 = max(0, box[0] - cx1)
            val ly1 = max(0, box[1] - cy1)
            val lx2 = min(contextW, box[2] - cx1)
            val ly2 = min(contextH, box[3] - cy1)
            intArrayOf(lx1, ly1, lx2, ly2)
        }
        val eraseBoxes = expandBoxes(localTextBoxes, textMaskPad, contextW, contextH)

        val ringMask = ByteArray(contextW * contextH) { 1.toByte() }
        val border = 2
        for (x in 0 until border) {
            for (y in 0 until contextH) {
                ringMask[y * contextW + x] = 0
                ringMask[y * contextW + (contextW - 1 - x)] = 0
            }
        }
        for (y in 0 until border) {
            for (x in 0 until contextW) {
                ringMask[y * contextW + x] = 0
                ringMask[(contextH - 1 - y) * contextW + x] = 0
            }
        }
        for (box in localTextBoxes) {
            val mp = textMaskPad + 6
            val ex1 = max(0, box[0] - mp)
            val ey1 = max(0, box[1] - mp)
            val ex2 = min(contextW, box[2] + mp)
            val ey2 = min(contextH, box[3] + mp)
            for (y in ey1 until ey2) {
                for (x in ex1 until ex2) {
                    ringMask[y * contextW + x] = 0
                }
            }
        }

        val bubbleW = bx2 - bx1
        val bubbleH = by2 - by1
        val bubbleErode = min(4, max(2, min(bubbleW, bubbleH) / 10))
        val intX1 = max(0, bx1 - cx1 + bubbleErode)
        val intY1 = max(0, by1 - cy1 + bubbleErode)
        val intX2 = min(contextW, bx2 - cx1 - bubbleErode)
        val intY2 = min(contextH, by2 - cy1 - bubbleErode)
        for (y in 0 until contextH) {
            for (x in 0 until contextW) {
                if (x < intX1 || x >= intX2 || y < intY1 || y >= intY2) {
                    ringMask[y * contextW + x] = 0
                }
            }
        }

        val bgStats = sampleBackgroundStats(contextPixels, ringMask, contextW, contextH)
        val bgType = classifyBackground(bgStats)

        var combinedMask = ByteArray(contextW * contextH)
        for (box in localTextBoxes) {
            val mp = max(textMaskPad, 8)
            val ex1 = max(0, box[0] - mp)
            val ey1 = max(0, box[1] - mp)
            val ex2 = min(contextW, box[2] + mp)
            val ey2 = min(contextH, box[3] + mp)
            if (ex2 <= ex1 || ey2 <= ey1) continue

            val boxMask = generateTextMask(
                contextPixels, bgStats, bgType,
                ex1, ey1, ex2, ey2, contextW, contextH,
            )
            for (y in ey1 until ey2) {
                for (x in ex1 until ex2) {
                    if (boxMask[(y - ey1) * (ex2 - ex1) + (x - ex1)] != 0.toByte()) {
                        combinedMask[y * contextW + x] = 1
                    }
                }
            }
        }

        val hasAnyMask = combinedMask.any { it != 0.toByte() }
        if (!hasAnyMask && localTextBoxes.isNotEmpty()) {
            // TachiyomiAT: generateTextMask found NOTHING to clean — faint/low-
            // contrast text whose strokes all fell under threshold, or anti-aliased
            // edges too thin to register. Previously this filled the ENTIRE box
            // with the ring median, which on a grayscale panel or colored bubble
            // produced the reported "gray rectangle over the whole bounding box."
            // Now: try a RECOVERY pass first — a much lower threshold keyed off
            // the ring-sampled background median alone (the most reliable bg
            // reference), to catch the faint strokes the main pass missed. Only
            // if that ALSO finds nothing do we fall back to the tight box of any
            // pixel differing from the median (catches anti-aliased edges), and
            // only as a last resort fill the box. Most of the time the recovery
            // pass succeeds and the box is never blindly filled.
            val recovered = recoverFaintTextMask(
                contextPixels,
                bgStats,
                localTextBoxes,
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
                    localTextBoxes,
                    contextW,
                    contextH,
                )
                for (i in tightFallback.indices) {
                    if (tightFallback[i] != 0.toByte()) combinedMask[i] = 1
                }
            }
        }
        combinedMask = unionMasks(
            combinedMask,
            buildLocalContrastTextMask(
                pixels = contextPixels,
                boxes = eraseBoxes,
                width = contextW,
                height = contextH,
            ),
        )

        var finalMask = combinedMask
        // TachiyomiAT: disk SE (radius 1) rounds mask corners instead of the
        // 45° chamfer a 4-neighbourhood diamond produces. See dilateMaskDisk.
        finalMask = BubbleMaskBuilder.dilateMaskDisk(finalMask, contextW, contextH, 1)
        if (BubbleMaskBuilder.maskCoverage(finalMask) < MIN_OCR_TEXT_MASK_COVERAGE && eraseBoxes.isNotEmpty()) {
            val aggressiveContrast = buildLocalContrastTextMask(
                pixels = contextPixels,
                boxes = eraseBoxes,
                width = contextW,
                height = contextH,
                aggressive = false,
            )
            finalMask = unionMasks(finalMask, aggressiveContrast)
            if (BubbleMaskBuilder.maskCoverage(finalMask) < MIN_OCR_TEXT_MASK_COVERAGE) {
                logcat(LogPriority.INFO) {
                    "[bubble_cleaner] low text-mask coverage; leaving OCR boxes unfilled groupBoxes=${textBoxes.size}"
                }
            }
        }
        val alpha = BubbleMaskBuilder.featherAlpha(finalMask, contextW, contextH, featherRadius)

        // TachiyomiAT: activate feathering. The fill region is the mask CORE
        // plus the feather RING (every pixel where alpha > 0). The earlier code
        // gated the fill on `finalMask[idx] != 0`, which skipped the entire
        // feather ring — alpha was computed and then discarded, and inside the
        // mask alpha was always 1.0, so the fill had a hard 1px edge at the mask
        // boundary (the reported "box border" artifact on speech bubbles).
        // Build an expanded fill mask so buildLocalBackground also interpolates
        // a background estimate for the ring pixels (otherwise the ring blend
        // would use the original pixel as its "background" and be a no-op).
        val fillMask = ByteArray(contextW * contextH)
        for (i in alpha.indices) {
            if (alpha[i] > 0f) fillMask[i] = 1
        }

        // TachiyomiAT: constrain background sampling to the bubble INTERIOR so
        // the fill matches the bubble's own background and does not bleed the
        // surrounding artwork color (e.g. blue sky) into the cleaned bubble —
        // the reported color-bleed artifact. intX1..intY2 are the eroded bubble
        // interior bounds (computed above for the ring mask). When the interior
        // is degenerate (too eroded to sample), bgSourceMask stays null and
        // buildLocalBackground falls back to the whole-context behavior.
        val bgSourceMask = if (intX2 > intX1 && intY2 > intY1) {
            ByteArray(contextW * contextH).also { bsm ->
                for (y in intY1 until intY2) {
                    for (x in intX1 until intX2) {
                        bsm[y * contextW + x] = 1
                    }
                }
            }
        } else {
            null
        }

        // TachiyomiAT: local per-pixel background instead of one flat median —
        // see buildLocalBackground. This is the fix for the gray-rectangle
        // symptom: on grayscale/tinted backgrounds the ring median was gray
        // (~128) and got painted over the whole masked region.
        val localBg = buildLocalBackground(
            contextPixels,
            fillMask,
            contextW,
            contextH,
            bgStats.medianColor,
            preferFlatFill = shouldUseSolidFlatFill(bgType, bgStats),
            bgSourceMask = bgSourceMask,
        )

        val filled = applyFeatheredFill(contextPixels, localBg, alpha)
        System.arraycopy(filled, 0, resultPixels, 0, contextW * contextH)

        image.setPixels(resultPixels, 0, contextW, cx1, cy1, contextW, contextH)
        logcat(LogPriority.INFO) {
            "[bubble_cleaner] group boxes=${textBoxes.size} bg=$bgType std=${bgStats.grayStd.format1()} " +
                "mask=${BubbleMaskBuilder.maskCoverage(finalMask).format1()}% roi=${contextW}x$contextH"
        }
        return image
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

        val mp = max(textMaskPad, 8)
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
        val bgType = classifyBackground(bgStats)

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
            // TachiyomiAT: same faint-text recovery as cleanBubbleGroup's
            // fallback. generateTextMask found nothing here either; try a
            // lowered-threshold pass keyed off the ring median before resorting
            // to the whole-box fill (which paints the median color over the
            // entire box — the "gray rectangle" symptom on grayscale panels).
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
        combinedMask = unionMasks(
            combinedMask,
            buildLocalContrastTextMask(
                pixels = contextPixels,
                boxes = listOf(intArrayOf(ex1, ey1, ex2, ey2)),
                width = contextW,
                height = contextH,
            ),
        )

        var finalMask = combinedMask
        // TachiyomiAT: disk SE rounds mask corners (vs the diamond's chamfer).
        finalMask = BubbleMaskBuilder.dilateMaskDisk(finalMask, contextW, contextH, dilationIterations)
        if (BubbleMaskBuilder.maskCoverage(finalMask) < MIN_OCR_TEXT_MASK_COVERAGE) {
            val aggressiveContrast = buildLocalContrastTextMask(
                pixels = contextPixels,
                boxes = listOf(intArrayOf(ex1, ey1, ex2, ey2)),
                width = contextW,
                height = contextH,
                aggressive = false,
            )
            finalMask = unionMasks(finalMask, aggressiveContrast)
            if (BubbleMaskBuilder.maskCoverage(finalMask) < MIN_OCR_TEXT_MASK_COVERAGE) {
                logcat(LogPriority.INFO) {
                    "[bubble_cleaner] low text-mask coverage; leaving OCR region unfilled"
                }
            }
        }
        val alpha = BubbleMaskBuilder.featherAlpha(finalMask, contextW, contextH, featherRadius)

        // TachiyomiAT: activate feathering — same fix as cleanBubbleGroup. The
        // fill region is the mask CORE plus the feather RING (alpha > 0), and
        // buildLocalBackground interpolates a background for the ring too, so
        // the blend produces a soft ramp instead of a hard 1px edge.
        val fillMask = ByteArray(contextW * contextH)
        for (i in alpha.indices) {
            if (alpha[i] > 0f) fillMask[i] = 1
        }

        // TachiyomiAT: local per-pixel background — same gray-rectangle fix as
        // cleanBubbleGroup (see buildLocalBackground).
        val localBg = buildLocalBackground(
            contextPixels,
            fillMask,
            contextW,
            contextH,
            bgStats.medianColor,
            preferFlatFill = shouldUseSolidFlatFill(bgType, bgStats),
        )

        val filled = applyFeatheredFill(contextPixels, localBg, alpha)
        System.arraycopy(filled, 0, resultPixels, 0, contextW * contextH)

        image.setPixels(resultPixels, 0, contextW, cx1, cy1, contextW, contextH)
        logcat(LogPriority.INFO) {
            "[bubble_cleaner] region bg=$bgType std=${bgStats.grayStd.format1()} " +
                "mask=${BubbleMaskBuilder.maskCoverage(finalMask).format1()}% roi=${contextW}x$contextH"
        }
    }

    fun isFlatBackgroundRegion(
        image: Bitmap,
        box: IntArray,
    ): Boolean {
        val x1 = box[0].coerceIn(0, image.width)
        val y1 = box[1].coerceIn(0, image.height)
        val x2 = box[2].coerceIn(x1, image.width)
        val y2 = box[3].coerceIn(y1, image.height)
        if (x2 <= x1 || y2 <= y1) return true

        val pad = contextPad
        val cx1 = max(0, x1 - pad)
        val cy1 = max(0, y1 - pad)
        val cx2 = min(image.width, x2 + pad)
        val cy2 = min(image.height, y2 + pad)
        val contextW = cx2 - cx1
        val contextH = cy2 - cy1
        val pixels = IntArray(contextW * contextH)
        image.getPixels(pixels, 0, contextW, cx1, cy1, contextW, contextH)

        val ringMask = ByteArray(contextW * contextH) { 1.toByte() }
        val localX1 = x1 - cx1
        val localY1 = y1 - cy1
        val localX2 = x2 - cx1
        val localY2 = y2 - cy1
        for (y in max(0, localY1 - textMaskPad) until min(contextH, localY2 + textMaskPad)) {
            for (x in max(0, localX1 - textMaskPad) until min(contextW, localX2 + textMaskPad)) {
                ringMask[y * contextW + x] = 0
            }
        }
        val stats = sampleBackgroundStats(pixels, ringMask, contextW, contextH)
        val bgType = classifyBackground(stats)
        return bgType == "flat_white" || bgType == "flat_colored" || bgType == "lightly_varying"
    }

    /**
     * TachiyomiAT: build a **tight text-region** mask (1 byte per pixel,
     * contextW × contextH) over the supplied [boxes], for the AOT/neural
     * inpainter to erase. The mask is a SOLID rectangle tightly fitted to
     * where text actually is — not the detector's loose bounding box, and not
     * individual strokes.
     *
     * This is the less-destructive masking path for QUALITY mode, matching
     * production manga-translation practice for AOT-GAN inpainters: feed a
     * solid hole tight to the text so the model reconstructs one clean region
     * per text block, rather than redrawing the whole detector box (the old
     * destructive behavior) or filling many sparse stroke pixels (noisy).
     *
     * The detector chain runs only to find WHERE text is (the tight bounds of
     * detected pixels), then the final mask is solid within those bounds:
     *  1. sampleBackgroundStats from a border ring around the box,
     *  2. generateTextMask (in-box 2-cluster centroid + ring median),
     *  3. recoverFaintTextMask (lowered threshold for faint strokes),
     *  4. tightDifferenceMask (last-resort per-pixel differ from median),
     *  5. buildLocalContrastTextMask (local-contrast integral-image detector).
     * Steps 2–5 are UNIONed to find the detected-pixel set; the tight bbox of
     * that set (clamped to the original box) is then filled SOLID.
     *
     * **Coverage safety net (per box):** if no pixels are detected (a genuinely
     * uniform box), that box falls back to a SOLID fill of the FULL original
     * box. This guarantees the tight-region path never regresses the "complete
     * erasure" of the old whole-box approach when detection genuinely fails.
     *
     * Memory: each detector allocates O(box-area) working buffers; the
     * local-contrast detector builds two integral LongArrays sized to the whole
     * context. For the AOT model's 512×512 input that is ~2 MB per LongArray —
     * acceptable for a single per-crop allocation (no cross-call retention).
     *
     * @param contextW x-extent of [pixels] / returned mask
     * @param contextH y-extent of [pixels] / returned mask
     * @param boxes erasure boxes in context-local coordinates
     * @param tightPad extra px padded around the detected tight bounds before
     *   filling solid, so anti-aliased stroke edges fall inside the hole
     * @param dilateIterations dilation passes applied to the final solid mask
     * @return a contextW × contextH mask; 1 = erase (solid tight region or fallback)
     */
    fun buildTightTextRegionMask(
        pixels: IntArray,
        contextW: Int,
        contextH: Int,
        boxes: List<IntArray>,
        tightPad: Int = 3,
        dilateIterations: Int = dilationIterations,
    ): ByteArray {
        val combined = ByteArray(contextW * contextH)
        if (boxes.isEmpty()) return combined
        val perBoxHasText = BooleanArray(boxes.size)
        for ((boxIdx, box) in boxes.withIndex()) {
            val bx1 = box[0].coerceIn(0, contextW)
            val by1 = box[1].coerceIn(0, contextH)
            val bx2 = box[2].coerceIn(bx1, contextW)
            val by2 = box[3].coerceIn(by1, contextH)
            if (bx2 <= bx1 || by2 <= by1) continue

            // Border ring around the box for background stats (mirrors the
            // cleanBubbleGroup ring construction, scaled to the box).
            val ringMp = textMaskPad + 6
            val ringEx1 = max(0, bx1 - ringMp)
            val ringEy1 = max(0, by1 - ringMp)
            val ringEx2 = min(contextW, bx2 + ringMp)
            val ringEy2 = min(contextH, by2 + ringMp)
            val ringMask = ByteArray(contextW * contextH) { 1 }
            val border = 2
            for (x in 0 until border) {
                for (y in 0 until contextH) {
                    ringMask[y * contextW + x] = 0
                    ringMask[y * contextW + (contextW - 1 - x)] = 0
                }
            }
            for (y in 0 until border) {
                for (x in 0 until contextW) {
                    ringMask[y * contextW + x] = 0
                    ringMask[(contextH - 1 - y) * contextW + x] = 0
                }
            }
            for (ry in ringEy1 until ringEy2) {
                for (rx in ringEx1 until ringEx2) {
                    ringMask[ry * contextW + rx] = 0
                }
            }
            val stats = sampleBackgroundStats(pixels, ringMask, contextW, contextH)
            val bgType = classifyBackground(stats)

            val mp = max(textMaskPad, 8)
            val ex1 = max(0, bx1 - mp)
            val ey1 = max(0, by1 - mp)
            val ex2 = min(contextW, bx2 + mp)
            val ey2 = min(contextH, by2 + mp)
            if (ex2 <= ex1 || ey2 <= ey1) continue

            // Union all detector outputs to find the detected-pixel set, and
            // track the tight bounding box of that set within the original box.
            var minX = bx2
            var minY = by2
            var maxX = bx1
            var maxY = by1
            fun considerDetected(x: Int, y: Int) {
                if (x < bx1 || x >= bx2 || y < by1 || y >= by2) return
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }

            // 1. in-box centroid + ring-median detector (zone-sized mask).
            val zoneMask = generateTextMask(pixels, stats, bgType, ex1, ey1, ex2, ey2, contextW, contextH)
            val zoneW = ex2 - ex1
            for (zy in 0 until (ey2 - ey1)) {
                for (zx in 0 until zoneW) {
                    if (zoneMask[zy * zoneW + zx] != 0.toByte()) {
                        considerDetected(ex1 + zx, ey1 + zy)
                    }
                }
            }
            // 2–3. faint recovery + last-resort per-pixel difference. Both
            // return full-context-sized masks.
            val recovered = recoverFaintTextMask(pixels, stats, listOf(intArrayOf(ex1, ey1, ex2, ey2)), contextW, contextH)
            val tight = tightDifferenceMask(pixels, stats, listOf(intArrayOf(ex1, ey1, ex2, ey2)), contextW, contextH)
            for (i in recovered.indices) {
                if (recovered[i] != 0.toByte()) considerDetected(i % contextW, i / contextW)
            }
            for (i in tight.indices) {
                if (tight[i] != 0.toByte()) considerDetected(i % contextW, i / contextW)
            }
            // 4. local-contrast integral-image detector.
            val contrast = buildLocalContrastTextMask(
                pixels = pixels,
                boxes = listOf(intArrayOf(ex1, ey1, ex2, ey2)),
                width = contextW,
                height = contextH,
            )
            for (i in contrast.indices) {
                if (contrast[i] != 0.toByte()) considerDetected(i % contextW, i / contextW)
            }

            val hasText = maxX >= minX && maxY >= minY
            perBoxHasText[boxIdx] = hasText
            if (hasText) {
                // Fill the tight bounding box of detected text SOLID, padded
                // slightly inward to cover anti-aliased edges, clamped to the
                // original box (never grow beyond it).
                val fx1 = max(bx1, minX - tightPad)
                val fy1 = max(by1, minY - tightPad)
                val fx2 = min(bx2, maxX + tightPad + 1)
                val fy2 = min(by2, maxY + tightPad + 1)
                for (y in fy1 until fy2) {
                    for (x in fx1 until fx2) {
                        combined[y * contextW + x] = 1
                    }
                }
            }
        }

        // Per-box solid fallback: any box whose detectors found nothing gets a
        // solid fill of the FULL original box, so a uniform/genuinely-textless
        // box is still fully erased (no coverage regression vs the old mask).
        for ((boxIdx, box) in boxes.withIndex()) {
            if (perBoxHasText[boxIdx]) continue
            val bx1 = box[0].coerceIn(0, contextW)
            val by1 = box[1].coerceIn(0, contextH)
            val bx2 = box[2].coerceIn(bx1, contextW)
            val by2 = box[3].coerceIn(by1, contextH)
            if (bx2 <= bx1 || by2 <= by1) continue
            for (y in by1 until by2) {
                for (x in bx1 until bx2) {
                    combined[y * contextW + x] = 1
                }
            }
        }

        // TachiyomiAT: disk SE rounds the tight text region's corners so the
        // neural inpaint mask doesn't produce sharp rectangular borders.
        return BubbleMaskBuilder.dilateMaskDisk(combined, contextW, contextH, dilateIterations)
    }

    /**
     * TachiyomiAT: recovery pass for the whole-box fallback. When
     * generateTextMask returns an empty mask (all strokes fell under the normal
     * threshold), re-scan each box with a MUCH lower threshold keyed off the
     * ring-sampled background median (the most reliable background reference —
     * it is sampled from the border ring AROUND the box, not the box interior).
     * The lowered threshold catches the faint strokes the main pass missed, and
     * removeEdgeTouchingComponents keeps the mask bounded to genuine text
     * regions rather than bleeding to the box edges.
     *
     * Returns a full-context-sized mask (contextW * contextH) — caller ANDs it
     * into [combinedMask]. Empty if the recovery found nothing.
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
        // Floor threshold: lower than the main pass (45 → 12) so very faint
        // strokes still register. The main pass already tried 45 (and a lowered
        // value when clusters were close); this pass goes to the absolute floor
        // and relies on removeEdgeTouchingComponents + downstream mask
        // intersections to bound the region.
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
            // Drop components that touch the zone edge (likely anti-aliasing
            // bleed, not real text) — same hygiene as generateTextMask.
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
     * nothing. Instead of filling the ENTIRE box (which paints the median over
     * everything — the "gray rectangle" / "too much space" symptom), set the
     * mask ONLY on the actual pixels that differ from the ring median by more
     * than a small epsilon. This catches anti-aliased text edges that the
     * distance threshold missed, and bounds the filled region to where text
     * actually is — preserving the background between strokes. If the image is
     * genuinely uniform (no text, no edges), no pixels are set — preferable to
     * a spurious full-box rectangle.
     *
     * Per-pixel (not bounding-rectangle): the earlier implementation found the
     * tight bounds of differing pixels and then filled the WHOLE enclosing
     * rectangle, erasing the gaps between strokes. That produced the reported
     * "too much space" destruction. The downstream dilation
     * ([BubbleMaskBuilder.dilateMask], now a true multi-pass grower) covers the
     * anti-aliased fringe around each stroke pixel without erasing the inter-
     * stroke background, so per-pixel marking is both safe and correct.
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
        // Small epsilon: any pixel that differs from the background median at
        // all is a candidate text/edge pixel. This is deliberately permissive —
        // the goal is to mark "where the pixels aren't pure background" rather
        // than the full OCR box.
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
        )
    }

    private fun classifyBackground(stats: BackgroundStats): String {
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

        // TachiyomiAT: the in-box 2-cluster centroid (bgCluster) is a *noisy*
        // background estimate because the box ITSELF is mostly text, so the
        // "background" centroid drifts toward the text color. Faint strokes — whose
        // color sits between true background and text — then fall UNDER the
        // detection threshold and never get masked, leaving the original text
        // faintly visible through the fill. The ring-sampled median
        // (stats.medianColor) is a far more reliable background reference because
        // it is sampled from the border ring AROUND the box, which is real
        // background. Use BOTH references: a pixel is "text" if it differs from the
        // in-box centroid OR from the ring median beyond the threshold. The OR
        // catches faint strokes that the in-box centroid alone misses, without
        // over-erasing, because removeEdgeTouchingComponents + the rounded-rect /
        // bubble-interior mask intersection downstream still bound the mask to the
        // text region.
        val ringR = (stats.medianColor shr 16 and 0xFF).toFloat()
        val ringG = (stats.medianColor shr 8 and 0xFF).toFloat()
        val ringB = (stats.medianColor and 0xFF).toFloat()

        var threshVal = colorDistanceThreshold
        if (bgCluster.foregroundDistance < threshVal * 1.5f) {
            // Clusters are close (low-contrast text): lower the floor so faint
            // strokes still clear the bar. Floor was 15; tighten to 12 so very
            // faint anti-aliasing edges are captured, then cover their thin edges
            // with the extra dilation pass below.
            threshVal = max(12.0f, bgCluster.foregroundDistance * 0.4f)
        }

        for (zy in 0 until zoneH) {
            for (zx in 0 until zoneW) {
                val px = pixels[(y1 + zy) * contextW + (x1 + zx)]
                val r = (px shr 16 and 0xFF).toFloat()
                val g = (px shr 8 and 0xFF).toFloat()
                val b = (px and 0xFF).toFloat()
                // Distance from the in-box background centroid.
                val dr = r - bgCluster.r
                val dg = g - bgCluster.g
                val db = b - bgCluster.b
                val distFromCluster = sqrt(dr * dr + dg * dg + db * db)
                // Distance from the ring-sampled true background median.
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
     * ring-median fill that produced the reported "gray rectangle over the
     * whole bounding box" on grayscale/tinted backgrounds.
     *
     * The old fill painted every masked pixel with [BackgroundStats.medianColor]
     * — one global color sampled from the border ring. On a grayscale manga
     * panel or a tinted bubble, that median IS gray (~128), so the cleaned text
     * region became a uniform gray patch regardless of the local artwork.
     *
     * This builds a background estimate that varies per pixel: for each pixel,
     * it averages the colors of the SURROUNDING non-masked (background) pixels
     * within a box kernel, weighted by a Gaussian falloff so nearer background
     * pixels count more. Background pixels keep their own color; masked pixels
     * get the local-average background — i.e. the background is interpolated
     * across the text hole (a poor-man's inpaint). On a gradient or textured
     * background this matches the local artwork instead of one flat gray.
     *
     * [medianColor] is still used as a fallback: if no background pixels fall in
     * the kernel (e.g. the mask fills the whole context — every neighbor is also
     * masked), the pixel uses the ring median. This preserves the previous
     * behavior in the degenerate all-masked case while fixing the common case.
     *
     * Cost is O(n·ksize²), the same complexity class as [buildFeatherAlpha]
     * (which already does a per-pixel radius loop), so this is not a perf
     * regression relative to the existing feather step.
     *
     * `internal` so [SmartBubbleTextCleanerTest] can exercise the pure
     * pixel→color logic without an Android Bitmap (the public clean* API
     * operates on android.graphics.Bitmap, which plain JVM tests can't load).
     */
    internal fun buildLocalBackground(
        pixels: IntArray,
        mask: ByteArray,
        width: Int,
        height: Int,
        medianColor: Int,
        preferFlatFill: Boolean = false,
        /**
         * TachiyomiAT: optional constraint on which pixels are eligible to be
         * sampled as background. When non-null, a pixel is only used as a
         * background reference if it is NOT in [mask] AND IS in [bgSourceMask].
         *
         * This is the fix for the color-bleed artifact: on a tinted page the
         * bubble interior is one color (e.g. white) and the surrounding artwork
         * is another (e.g. blue). Without a constraint, the directional/Gaussian
         * background scan reaches past the bubble boundary into the artwork and
         * pulls that color INTO the bubble fill, producing the reported blue
         * patches inside cleaned speech bubbles. Passing the eroded bubble
         * interior here keeps the background reference inside the bubble, so the
         * fill matches the bubble's own background. Null (the free-text path
         * with no parent bubble) preserves the original whole-context behavior.
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
            // sigma ~ r/2 gives a gentle falloff over the kernel half-width.
            kotlin.math.exp(-(fy * fy).toFloat() / (2.0f * (r / 2.0f) * (r / 2.0f)))
        }
        val directionalBg = buildDirectionalBackground(pixels, mask, width, height, bgSourceMask)

        // TachiyomiAT: a neighbor counts as a background reference only when it
        // is outside [mask] AND (when a bgSourceMask is supplied) inside the
        // allowed source region. Precompute this as an eligibility test.
        fun isBgSource(ni: Int): Boolean {
            if (mask[ni] != 0.toByte()) return false
            return bgSourceMask == null || bgSourceMask[ni] != 0.toByte()
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                // Background pixels keep their own color — no estimation needed.
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
                    // Degenerate: every neighbor is masked too. Fall back to the
                    // ring median so the pixel isn't left as the original text.
                    (0xFF shl 24) or (medianR shl 16) or (medianG shl 8) or medianB
                }
            }
        }
        return bg
    }

    private fun expandBoxes(
        boxes: List<IntArray>,
        pad: Int,
        width: Int,
        height: Int,
    ): List<IntArray> = boxes.mapNotNull { box ->
        val x1 = max(0, box[0] - pad)
        val y1 = max(0, box[1] - pad)
        val x2 = min(width, box[2] + pad)
        val y2 = min(height, box[3] + pad)
        if (x2 <= x1 || y2 <= y1) null else intArrayOf(x1, y1, x2, y2)
    }

    private fun unionMasks(a: ByteArray, b: ByteArray): ByteArray {
        val result = ByteArray(min(a.size, b.size))
        for (i in result.indices) {
            if (a[i] != 0.toByte() || b[i] != 0.toByte()) result[i] = 1
        }
        return result
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
            val dominantLightBackground = isDominantLightBackground(gray, bx1, by1, bx2, by2, width)
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
                    val sum = rectSum(integral, integralW, sx1, sy1, sx2, sy2)
                    val sumSq = rectSum(integralSq, integralW, sx1, sy1, sx2, sy2)
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
                    val lightStroke = !dominantLightBackground && value > mean + threshold && value > 172f && mean < 205f
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

    private fun isDominantLightBackground(
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

    private fun percentileGray(hist: IntArray, count: Int, percentile: Float): Int {
        val target = (count * percentile).roundToInt().coerceIn(1, count)
        var seen = 0
        for (value in hist.indices) {
            seen += hist[value]
            if (seen >= target) return value
        }
        return hist.lastIndex
    }

    private fun rectSum(
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

    private fun shouldUseSolidFlatFill(bgType: String, stats: BackgroundStats): Boolean =
        bgType == "flat_white" ||
            ((bgType == "flat_colored" || bgType == "dark_flat") && stats.grayStd < 10f && stats.edgeDensity < 0.04f)

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

        // TachiyomiAT: a pixel is a valid directional background anchor only
        // when it is outside [mask] AND (when supplied) inside [bgSourceMask].
        // Constraining the anchor stops the scan from reaching past the bubble
        // boundary into the surrounding artwork (the color-bleed source).
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
    )

    /**
     * TachiyomiAT: pure feathered-fill — the shared body of the
     * cleanBubbleGroup / cleanSingleRegion fill loops, extracted so the
     * feathering fix is unit-testable without an Android Bitmap.
     *
     * Blends each pixel where [alpha] > 0 between its original color and the
     * [background] estimate, weighted by alpha:
     *  - alpha == 0  → untouched (returned as the original pixel)
     *  - alpha == 1  → fully replaced by background (mask core)
     *  - 0 < alpha < 1 → soft ramp (feather ring), killing the hard 1px edge
     *
     * Returns a new IntArray; does not mutate [pixels]. The earlier inline
     * loops gated on `mask != 0`, which skipped the entire feather ring and
     * discarded the alpha map — see the activation fix in cleanBubbleGroup /
     * cleanSingleRegion.
     */
    internal fun applyFeatheredFill(
        pixels: IntArray,
        background: IntArray,
        alpha: FloatArray,
    ): IntArray {
        val n = minOf(pixels.size, background.size, alpha.size)
        val out = IntArray(pixels.size)
        // Copy first so untouched (alpha==0) pixels keep their original value.
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
    }

    fun clearWorkingBuffers() {
        workingBuffer1 = null
        workingBuffer2 = null
        currentBufferSize = 0
    }
}
