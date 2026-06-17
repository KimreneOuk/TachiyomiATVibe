package eu.kanade.translation.inpainting

import android.graphics.Bitmap
import android.graphics.Color
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class SmartBubbleTextCleaner(
    private val contextPad: Int = 10,
    private val textMaskPad: Int = 2,
    private val textThreshold: Int = 25,
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
        return if (size == currentBufferSize &&
                   workingBuffer1 != null &&
                   workingBuffer2 != null) {
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
            val mp = textMaskPad
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

        val combinedMask = ByteArray(contextW * contextH)
        for (box in localTextBoxes) {
            val mp = textMaskPad
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
                contextPixels, bgStats, localTextBoxes, contextW, contextH,
            )
            if (recovered.any { it != 0.toByte() }) {
                for (i in recovered.indices) {
                    if (recovered[i] != 0.toByte()) combinedMask[i] = 1
                }
            } else {
                val tightFallback = tightDifferenceMask(
                    contextPixels, bgStats, localTextBoxes, contextW, contextH,
                )
                for (i in tightFallback.indices) {
                    if (tightFallback[i] != 0.toByte()) combinedMask[i] = 1
                }
            }
        }

        val roundedAllowed = buildRoundedAllowedMask(localTextBoxes, contextW, contextH)
        val bubbleInterior = buildBubbleInteriorMask(
            intArrayOf(bx1 - cx1, by1 - cy1, bx2 - cx1, by2 - cy1),
            contextW,
            contextH,
            bubbleErode + 2,
        )
        var finalMask = andMasks(combinedMask, roundedAllowed)
        finalMask = andMasks(finalMask, bubbleInterior)
        finalMask = dilateMask(finalMask, contextW, contextH)
        val alpha = buildFeatherAlpha(finalMask, contextW, contextH)

        val medianR = (bgStats.medianColor shr 16 and 0xFF).toInt()
        val medianG = (bgStats.medianColor shr 8 and 0xFF).toInt()
        val medianB = (bgStats.medianColor and 0xFF).toInt()

        for (y in 0 until contextH) {
            for (x in 0 until contextW) {
                val idx = y * contextW + x
                if (finalMask[idx] == 0.toByte()) continue
                val a = alpha[idx]
                val px = contextPixels[idx]
                val pr = (px shr 16 and 0xFF)
                val pg = (px shr 8 and 0xFF)
                val pb = (px and 0xFF)
                val r = (pr * (1.0f - a) + medianR * a).toInt().coerceIn(0, 255)
                val g = (pg * (1.0f - a) + medianG * a).toInt().coerceIn(0, 255)
                val b = (pb * (1.0f - a) + medianB * a).toInt().coerceIn(0, 255)
                resultPixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        image.setPixels(resultPixels, 0, contextW, cx1, cy1, contextW, contextH)
        logcat(LogPriority.INFO) {
            "[bubble_cleaner] group boxes=${textBoxes.size} bg=$bgType std=${bgStats.grayStd.format1()} " +
                "mask=${maskCoverage(finalMask).format1()}% roi=${contextW}x${contextH}"
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
        x1: Int, y1: Int, x2: Int, y2: Int,
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

        val mp = textMaskPad
        val ex1 = max(0, localX1 - mp)
        val ey1 = max(0, localY1 - mp)
        val ex2 = min(contextW, localX2 + mp)
        val ey2 = min(contextH, localY2 + mp)

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
        for (ry in ey1 until ey2) {
            for (rx in ex1 until ex2) {
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

        val combinedMask = ByteArray(contextW * contextH)
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
                contextPixels, bgStats, listOf(singleBox), contextW, contextH,
            )
            if (recovered.any { it != 0.toByte() }) {
                for (i in recovered.indices) {
                    if (recovered[i] != 0.toByte()) combinedMask[i] = 1
                }
            } else {
                val tightFallback = tightDifferenceMask(
                    contextPixels, bgStats, listOf(singleBox), contextW, contextH,
                )
                for (i in tightFallback.indices) {
                    if (tightFallback[i] != 0.toByte()) combinedMask[i] = 1
                }
            }
        }

        val roundedAllowed = buildRoundedAllowedMask(listOf(intArrayOf(ex1, ey1, ex2, ey2)), contextW, contextH)
        var finalMask = andMasks(combinedMask, roundedAllowed)
        finalMask = dilateMask(finalMask, contextW, contextH)
        val alpha = buildFeatherAlpha(finalMask, contextW, contextH)

        val medianR = (bgStats.medianColor shr 16 and 0xFF).toInt()
        val medianG = (bgStats.medianColor shr 8 and 0xFF).toInt()
        val medianB = (bgStats.medianColor and 0xFF).toInt()

        for (yy in 0 until contextH) {
            for (xx in 0 until contextW) {
                val idx = yy * contextW + xx
                if (finalMask[idx] == 0.toByte()) continue
                val a = alpha[idx]
                val px = contextPixels[idx]
                val pr = (px shr 16 and 0xFF)
                val pg = (px shr 8 and 0xFF)
                val pb = (px and 0xFF)
                val r = (pr * (1.0f - a) + medianR * a).toInt().coerceIn(0, 255)
                val g = (pg * (1.0f - a) + medianG * a).toInt().coerceIn(0, 255)
                val b = (pb * (1.0f - a) + medianB * a).toInt().coerceIn(0, 255)
                resultPixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        image.setPixels(resultPixels, 0, contextW, cx1, cy1, contextW, contextH)
        logcat(LogPriority.INFO) {
            "[bubble_cleaner] region bg=$bgType std=${bgStats.grayStd.format1()} " +
                "mask=${maskCoverage(finalMask).format1()}% roi=${contextW}x${contextH}"
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
            val filtered = removeEdgeTouchingComponents(zoneMask, zoneW, zoneH)
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
     * everything — the "gray rectangle" symptom), fill only the TIGHT bounding
     * box of any pixel that differs from the ring median by more than a small
     * epsilon. This catches anti-aliased text edges that the distance threshold
     * missed, and shrinks the filled region to where text actually is. If the
     * image is genuinely uniform (no text, no edges), the tight box collapses
     * and nothing is filled — preferable to a spurious full-box rectangle.
     */
    private fun tightDifferenceMask(
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
        // the goal is to bound the filled region to "where the pixels aren't
        // pure background" rather than the full OCR box.
        val epsilon = 8
        for (box in boxes) {
            val bx1 = box[0].coerceIn(0, contextW)
            val by1 = box[1].coerceIn(0, contextH)
            val bx2 = box[2].coerceIn(bx1, contextW)
            val by2 = box[3].coerceIn(by1, contextH)
            if (bx2 <= bx1 || by2 <= by1) continue
            // First pass: find the tight bounds of differing pixels.
            var minX = bx2
            var minY = by2
            var maxX = bx1
            var maxY = by1
            for (y in by1 until by2) {
                for (x in bx1 until bx2) {
                    val px = pixels[y * contextW + x]
                    val r = px shr 16 and 0xFF
                    val g = px shr 8 and 0xFF
                    val b = px and 0xFF
                    if (abs(r - ringR) + abs(g - ringG) + abs(b - ringB) > epsilon) {
                        if (x < minX) minX = x
                        if (y < minY) minY = y
                        if (x > maxX) maxX = x
                        if (y > maxY) maxY = y
                    }
                }
            }
            if (maxX < minX || maxY < minY) continue // truly uniform — fill nothing
            // Pad the tight bounds slightly so anti-aliased edges are covered,
            // but clamp to the original box (never grow beyond it).
            val pad = 2
            val fx1 = max(bx1, minX - pad)
            val fy1 = max(by1, minY - pad)
            val fx2 = min(bx2, maxX + pad + 1)
            val fy2 = min(by2, maxY + pad + 1)
            for (y in fy1 until fy2) {
                for (x in fx1 until fx2) {
                    combined[y * contextW + x] = 1
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
                    val leftGray = (((left shr 16 and 0xFF) * 299) + ((left shr 8 and 0xFF) * 587) + ((left and 0xFF) * 114)) / 1000
                    val upGray = (((up shr 16 and 0xFF) * 299) + ((up shr 8 and 0xFF) * 587) + ((up and 0xFF) * 114)) / 1000
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
        x1: Int, y1: Int, x2: Int, y2: Int,
        contextW: Int, contextH: Int,
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
        return removeEdgeTouchingComponents(mask, zoneW, zoneH)
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

    private fun removeEdgeTouchingComponents(
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

    private fun buildRoundedAllowedMask(
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

    private fun buildBubbleInteriorMask(
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

    private fun insideRoundedRect(x: Int, y: Int, width: Int, height: Int, radius: Int): Boolean {
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

    private fun andMasks(a: ByteArray, b: ByteArray): ByteArray {
        val result = ByteArray(a.size)
        for (i in a.indices) {
            if (a[i] != 0.toByte() && b[i] != 0.toByte()) result[i] = 1
        }
        return result
    }

    private fun maskCoverage(mask: ByteArray): Float {
        if (mask.isEmpty()) return 0f
        return mask.count { it != 0.toByte() } * 100f / mask.size
    }

    private fun sq(value: Float): Float = value * value

    private fun Float.format1(): String = "%.1f".format(this)

    private fun dilateMask(
        mask: ByteArray,
        width: Int,
        height: Int,
    ): ByteArray {
        val result = mask.copyOf()
        for (iter in 0 until dilationIterations) {
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

    private fun buildFeatherAlpha(
        mask: ByteArray,
        width: Int,
        height: Int,
    ): FloatArray {
        val alpha = FloatArray(width * height)
        val coreBool = BooleanArray(width * height)
        for (i in mask.indices) coreBool[i] = mask[i] != 0.toByte()

        val hasAny = coreBool.any { it }
        if (!hasAny) return alpha

        val fr = max(2, featherRadius)
        val ksize = fr * 2 + 1

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

    private data class BackgroundStats(
        val medianColor: Int,
        val grayMean: Float,
        val grayStd: Float,
        val nearWhiteRatio: Float,
        val darkPixelRatio: Float,
        val edgeDensity: Float,
    )

    private data class ColorCluster(
        val r: Float,
        val g: Float,
        val b: Float,
        val foregroundDistance: Float,
    )

    fun clearWorkingBuffers() {
        workingBuffer1 = null
        workingBuffer2 = null
        currentBufferSize = 0
    }
}
