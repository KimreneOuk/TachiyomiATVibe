package eu.kanade.translation.rendering

import eu.kanade.translation.segmentation.MaskGeometry
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * One accepted adaptive line: prewrapped text plus its integer placement and
 * layout width/height, with the line's conservative (un-clipped) planning
 * occupancy.
 */
internal data class AdaptiveLine(
    val text: String,
    val leftPx: Int,
    val topPx: Int,
    val layoutWidthPx: Int,
    val layoutHeightPx: Int,
    val conservativeOccupancy: FloatRect,
)

/**
 * Successful adaptive band fit for one block. [anchorX]/[anchorY] are the
 * winning alignment anchor (BlockLayout origin); [usedTrialText] is the text
 * the lines were wrapped from (== the input text when the ALL-CAPS trial was
 * rejected or ineligible); [stats] are exact deterministic counters.
 */
internal data class AdaptiveResult(
    val lines: List<AdaptiveLine>,
    val fontPx: Float,
    val usedTrialText: String,
    val anchorX: Float,
    val anchorY: Float,
    val stats: AdaptiveBandPlanner.Stats,
)

/**
 * TachiyomiAT T912 slice 5: pure adaptive band fitter for horizontal text in a
 * span-mode shared cell (architecture revision 2, "Slice 5: adaptive bands and
 * the Android shaping contract").
 *
 * Deterministic and bounded: at most [TextLayoutTuning.MAX_FONT_BINARY_STEPS]
 * font binary-search steps, [TextLayoutTuning.MAX_BAND_ALIGNMENTS] vertical
 * alignments (OCR/block center, cell content center, half-line toward the
 * larger free side), and [TextLayoutTuning.MAX_BAND_FIXED_POINT_PASSES]
 * centering fixed-point passes per alignment. A line band is valid only when
 * one continuous x-interval survives intersection across EVERY covered
 * component row (interval lists coalesced pairwise; a list that would exceed
 * [TextLayoutTuning.MAX_BAND_INTERVALS] intervals is treated as no valid
 * band). A line is accepted only when
 * `layoutWidthPx = max(1, ceil(advance + 2*SHAPING_GUARD))` fits its hard band
 * and every pixel column of its rect is span-owned on every covered row.
 * Null means "fall back to the stroke-inset rectangular cell layout".
 *
 * Pure JVM: integer span math + injected measurement only — no `android`
 * imports, no dense page arrays.
 */
internal object AdaptiveBandPlanner {

    /** Exact deterministic counters for tests. */
    data class Stats(
        val fontSteps: Int,
        val alignmentsTried: Int,
        val fixedPointPasses: Int,
    )

    /** Half-open integer x-interval of a band. */
    private data class BandInterval(val start: Int, val endExclusive: Int) {
        val width: Int get() = endExclusive - start
    }

    /** A successful (font, alignment) attempt; internal bookkeeping only. */
    private class Attempt(
        val lines: List<AdaptiveLine>,
        val fontPx: Float,
        val alignmentsTried: Int,
        val fixedPointPasses: Int,
        val anchorX: Float,
        val anchorY: Float,
    )

    /** Per-row coalesced x-intervals of the cell spans, keyed by row. */
    private class RowIndex(val rows: Map<Int, List<BandInterval>>, val minRow: Int, val maxRow: Int)

    /**
     * Fit [text] into per-line bands derived from [cellSpans] (row-major,
     * slab-intersected cell spans) within the hard [slab]. [minFontPx]/
     * [maxFontPx] are the page font bounds (`FIT_MIN_FONT_PX*scale ..
     * FIT_MAX_FONT_PX*scale`); [blockCenterX]/[blockCenterY] anchor the OCR
     * center alignment and the center-nearest interval selection;
     * [collisionGapPx] is the page COLLISION_GAP used for conservative
     * occupancy inflation. Returns null when every bounded candidate fails.
     */
    fun fitAdaptiveBands(
        text: String,
        cellSpans: List<MaskGeometry.RowSpan>,
        slab: FloatRect,
        scale: Float,
        collisionGapPx: Int,
        measurer: TextMeasurer,
        minFontPx: Float,
        maxFontPx: Float,
        blockCenterX: Float,
        blockCenterY: Float,
    ): AdaptiveResult? {
        if (text.isBlank() || cellSpans.isEmpty()) return null
        if (slab.width() < 1f || slab.height() < 1f) return null
        val rowIndex = buildRowIndex(cellSpans) ?: return null

        // 9. ALL-CAPS trial ONCE per block, BEFORE band fitting, against a
        // conservative rectangular fit of the slab. The accepted trial text is
        // what gets wrapped; the persisted translation is never mutated.
        val trialText = TextLineBreaker.applyBestHyphenTrial(
            text = text,
            minFontPx = minFontPx,
            baselineFontFitter = { candidate ->
                rectFitFont(candidate, slab, scale, measurer, minFontPx, maxFontPx)
            },
        ) ?: text

        var lo = ceil(minFontPx).toInt().coerceAtLeast(1)
        var hi = floor(maxFontPx).toInt()
        if (hi < lo) return null
        var best: Attempt? = null
        var fontSteps = 0
        // 1. Font candidates: bounded binary search, largest fit first.
        while (lo <= hi && fontSteps < TextLayoutTuning.MAX_FONT_BINARY_STEPS) {
            val mid = (lo + hi) / 2
            fontSteps++
            val attempt = tryFont(trialText, mid.toFloat(), rowIndex, slab, scale, collisionGapPx, measurer, blockCenterX, blockCenterY)
            if (attempt != null) {
                best = attempt
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        best ?: return null
        return AdaptiveResult(
            lines = best.lines,
            fontPx = best.fontPx,
            usedTrialText = trialText,
            anchorX = best.anchorX,
            anchorY = best.anchorY,
            stats = Stats(fontSteps, best.alignmentsTried, best.fixedPointPasses),
        )
    }

    /**
     * Try one font: up to three vertical alignments, each with a centering
     * fixed point, then exact per-line validation. Null when the font fails
     * (any alignment failing the >24-line cap fails the whole font).
     */
    private fun tryFont(
        text: String,
        font: Float,
        rowIndex: RowIndex,
        slab: FloatRect,
        scale: Float,
        collisionGapPx: Int,
        measurer: TextMeasurer,
        blockCenterX: Float,
        blockCenterY: Float,
    ): Attempt? {
        val lineH = measurer.lineHeight(font)
        if (lineH <= 0f) return null
        val inset = TextLayoutTuning.strokeInsetPx(font, scale)
        val contentCenterY = contentCenterY(rowIndex)

        for (alignment in 0 until TextLayoutTuning.MAX_BAND_ALIGNMENTS) {
            // 5. Alignment anchors: (a) OCR/block center, (b) cell content
            // center, (c) half-line toward the larger free side within the
            // slab (measured from the block center anchor).
            val anchorY = when (alignment) {
                0 -> blockCenterY
                1 -> contentCenterY
                else -> {
                    val freeAbove = blockCenterY - slab.top
                    val freeBelow = slab.bottom - blockCenterY
                    if (freeBelow >= freeAbove) blockCenterY + lineH / 2f else blockCenterY - lineH / 2f
                }
            }
            val fixed = centeringFixedPoint(text, font, lineH, inset, anchorY, blockCenterX, rowIndex, slab, measurer)
                ?: continue
            val lines = validateLines(fixed, font, lineH, inset, collisionGapPx, scale, rowIndex, measurer)
                ?: continue
            return Attempt(
                lines = lines,
                fontPx = font,
                alignmentsTried = alignment + 1,
                fixedPointPasses = fixed.passes,
                anchorX = blockCenterX,
                anchorY = anchorY,
            )
        }
        return null
    }

    private class FixedPoint(
        val texts: List<String>,
        val selected: List<BandInterval>,
        val coveredRows: List<IntRange>,
        val firstTop: Float,
        val passes: Int,
    )

    /**
     * 6. Centering fixed point (max [TextLayoutTuning.MAX_BAND_FIXED_POINT_PASSES]
     * passes): wrap at the current usable width W (min band width minus twice
     * the stroke inset), place the lines at the alignment anchor, recompute
     * per-line bands from the new y positions, take the new min band width;
     * stop when stable or when the pass budget is exhausted. The final pass's
     * wrap/bands/positions are what validation judges.
     */
    private fun centeringFixedPoint(
        text: String,
        font: Float,
        lineH: Float,
        inset: Float,
        anchorY: Float,
        centerX: Float,
        rowIndex: RowIndex,
        slab: FloatRect,
        measurer: TextMeasurer,
    ): FixedPoint? {
        var width = slab.width() - 2f * inset
        if (width < 1f) return null
        var passes = 0
        var outcome: FixedPoint? = null
        var stable = false
        while (passes < TextLayoutTuning.MAX_BAND_FIXED_POINT_PASSES && !stable) {
            passes++
            val texts = TextLineBreaker.prewrap(text, font, width, measurer)
            // 8. More than the per-block line cap fails the whole font.
            if (texts.size > TextLayoutTuning.MAX_POSITIONED_LINES_PER_BLOCK) return null
            val totalH = texts.size * lineH
            val firstTop = anchorY - totalH / 2f
            // Local containment gate (documented in the implementation report):
            // the whole stack must lie inside the hard slab, or the cell/slab
            // clip would erase ink and the font is simply too large.
            if (firstTop < slab.top - SLAB_EPSILON || firstTop + totalH > slab.bottom + SLAB_EPSILON) {
                return null
            }
            val selected = ArrayList<BandInterval>(texts.size)
            val coveredRows = ArrayList<IntRange>(texts.size)
            var minBandWidth = Int.MAX_VALUE
            for (i in texts.indices) {
                val yTop = firstTop + i * lineH
                val (intervals, rows) = bandForLine(yTop, lineH, rowIndex) ?: return null
                // 4. The surviving interval nearest the block/cell center wins.
                val chosen = selectNearestCenter(intervals, centerX) ?: return null
                selected += chosen
                coveredRows += rows
                if (chosen.width < minBandWidth) minBandWidth = chosen.width
            }
            val newWidth = minBandWidth - 2f * inset
            stable = newWidth == width
            outcome = FixedPoint(texts, selected, coveredRows, firstTop, passes)
            width = newWidth
        }
        return outcome
    }

    /**
     * 7. Accept each line only when `max(1, ceil(advance + 2*SHAPING_GUARD))`
     * fits its hard band, then place it centered in the band with integer
     * floor semantics and verify EXACT span containment of every pixel column
     * on every covered row.
     */
    private fun validateLines(
        fixed: FixedPoint,
        font: Float,
        lineH: Float,
        inset: Float,
        collisionGapPx: Int,
        scale: Float,
        rowIndex: RowIndex,
        measurer: TextMeasurer,
    ): List<AdaptiveLine>? {
        val guard = TextLayoutTuning.shapingGuardPx(font, scale)
        val inflation = TextLayoutPlanner.computeStrokeWidth(font, scale) +
            TextLayoutTuning.aaGuard(scale) + collisionGapPx / 2f
        val lines = ArrayList<AdaptiveLine>(fixed.texts.size)
        for (i in fixed.texts.indices) {
            val lineText = fixed.texts[i]
            val advance = measurer.measureTextWidth(lineText, font)
            val layoutWidthPx = max(1, ceil(advance + 2f * guard).toInt())
            val layoutHeightPx = max(1, ceil(lineH).toInt())
            val band = fixed.selected[i]
            if (layoutWidthPx > band.width) return null
            val top = fixed.firstTop + i * lineH
            val leftPx = floor(band.start + (band.width - layoutWidthPx) / 2f).toInt()
            val topPx = floor(top).toInt()
            val right = leftPx + layoutWidthPx
            // Exact span containment on every covered row.
            for (row in fixed.coveredRows[i]) {
                if (!spansCoverRow(rowIndex.rows[row], leftPx, right)) return null
            }
            val conservativeOccupancy = FloatRect(
                leftPx - inflation,
                topPx - inflation,
                leftPx + advance + inflation,
                topPx + lineH + inflation,
            )
            lines += AdaptiveLine(lineText, leftPx, topPx, layoutWidthPx, layoutHeightPx, conservativeOccupancy)
        }
        return lines
    }

    /**
     * 2/3. Band rows for a line occupying `[yTop, yTop + lineH)`: integer rows
     * `floor(yTop)..ceil(yTop+lineH)-1` clamped to the covered span rows. The
     * surviving intervals are the pairwise coalesced intersection across EVERY
     * covered row; a fully-covered-row miss or an interval-list explosion
     * beyond [TextLayoutTuning.MAX_BAND_INTERVALS] means no valid band.
     */
    private fun bandForLine(yTop: Float, lineH: Float, rowIndex: RowIndex): Pair<List<BandInterval>, IntRange>? {
        val rowStart = max(floor(yTop).toInt(), rowIndex.minRow)
        val rowEnd = min(ceil(yTop + lineH).toInt() - 1, rowIndex.maxRow)
        if (rowStart > rowEnd) return null
        var acc: List<BandInterval>? = null
        for (row in rowStart..rowEnd) {
            val rowIntervals = rowIndex.rows[row] ?: return null
            acc = if (acc == null) rowIntervals else intersectIntervals(acc, rowIntervals) ?: return null
        }
        val result = acc ?: return null
        if (result.isEmpty()) return null
        return result to (rowStart..rowEnd)
    }

    /** Two-pointer intersection of two disjoint sorted interval lists; null past the guard. */
    private fun intersectIntervals(a: List<BandInterval>, b: List<BandInterval>): List<BandInterval>? {
        val result = ArrayList<BandInterval>(minOf(a.size, b.size))
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            val start = maxOf(a[i].start, b[j].start)
            val end = minOf(a[i].endExclusive, b[j].endExclusive)
            if (start < end) {
                result += BandInterval(start, end)
                if (result.size > TextLayoutTuning.MAX_BAND_INTERVALS) return null
            }
            if (a[i].endExclusive <= b[j].endExclusive) i++ else j++
        }
        return result
    }

    /**
     * 4. Among surviving intervals select the one nearest the block/cell
     * center; ties prefer the wider interval, then the leftmost.
     */
    private fun selectNearestCenter(intervals: List<BandInterval>, centerX: Float): BandInterval? {
        if (intervals.isEmpty()) return null
        var best = intervals.first()
        var bestDistance = abs(intervalCenter(best) - centerX)
        for (i in 1 until intervals.size) {
            val candidate = intervals[i]
            val distance = abs(intervalCenter(candidate) - centerX)
            val wider = candidate.width > best.width
            if (distance < bestDistance ||
                (distance == bestDistance && wider) ||
                (distance == bestDistance && candidate.width == best.width && candidate.start < best.start)
            ) {
                best = candidate
                bestDistance = distance
            }
        }
        return best
    }

    private fun intervalCenter(interval: BandInterval): Float = (interval.start + interval.endExclusive) / 2f

    /** Y center of the cell span content bounding box (alignment (b)). */
    private fun contentCenterY(rowIndex: RowIndex): Float = (rowIndex.minRow + rowIndex.maxRow + 1) / 2f

    /** True when every column in `[left, right)` is span-owned on the row. */
    private fun spansCoverRow(rowIntervals: List<BandInterval>?, left: Int, right: Int): Boolean {
        if (rowIntervals == null || left >= right) return false
        var cursor = left
        for (interval in rowIntervals) {
            if (interval.start > cursor) return false
            if (interval.endExclusive > cursor) {
                cursor = interval.endExclusive
                if (cursor >= right) return true
            }
        }
        return cursor >= right
    }

    /** Coalesced per-row x-intervals of the cell spans; null when empty. */
    private fun buildRowIndex(cellSpans: List<MaskGeometry.RowSpan>): RowIndex? {
        if (cellSpans.isEmpty()) return null
        val byRow = LinkedHashMap<Int, MutableList<BandInterval>>()
        var minRow = Int.MAX_VALUE
        var maxRow = Int.MIN_VALUE
        for (span in cellSpans) {
            if (span.endExclusive <= span.start) continue
            byRow.getOrPut(span.y) { mutableListOf() }.add(BandInterval(span.start, span.endExclusive))
            if (span.y < minRow) minRow = span.y
            if (span.y + 1 > maxRow) maxRow = span.y + 1
        }
        if (byRow.isEmpty()) return null
        return RowIndex(byRow, minRow, maxRow - 1)
    }

    /**
     * 9. Trial fitter: the largest font (bounded binary search over the same
     * integer candidate range) at which [text] fits the slab-conservative
     * rectangle — the slab shrunk by the stroke inset on every side — wrapping
     * with the same [TextLineBreaker.prewrap] used for band fitting. Floors at
     * [minFontPx] when nothing fits, which is exactly the "baseline could not
     * fit without overflow at min font" signal of the breaker contract.
     */
    private fun rectFitFont(
        text: String,
        slab: FloatRect,
        scale: Float,
        measurer: TextMeasurer,
        minFontPx: Float,
        maxFontPx: Float,
    ): Float {
        var lo = ceil(minFontPx).toInt().coerceAtLeast(1)
        var hi = floor(maxFontPx).toInt()
        if (hi < lo) return minFontPx
        var best = lo.toFloat()
        var steps = 0
        while (lo <= hi && steps < TextLayoutTuning.MAX_FONT_BINARY_STEPS) {
            val mid = (lo + hi) / 2
            steps++
            val midF = mid.toFloat()
            val inset = TextLayoutTuning.strokeInsetPx(midF, scale)
            val usableW = slab.width() - 2f * inset
            val usableH = slab.height() - 2f * inset
            val fits = usableW >= 1f && usableH >= 1f && run {
                val wrapped = TextLineBreaker.prewrap(text, midF, usableW, measurer)
                val lineH = measurer.lineHeight(midF)
                wrapped.size * lineH <= usableH &&
                    wrapped.all { measurer.measureTextWidth(it, midF) <= usableW }
            }
            if (fits) {
                best = midF
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return best
    }

    /** Float slack for the vertical slab containment gate (documented local choice). */
    private const val SLAB_EPSILON = 0.001f
}
