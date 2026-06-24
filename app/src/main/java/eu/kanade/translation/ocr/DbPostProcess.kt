package eu.kanade.translation.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * TachiyomiAT: a single text line detected by the PP-OCRv6 small **det** model
 * (DB — Differentiable Binarization). Coordinates are in the SAME space as the
 * bitmap passed to [PaddleOcrV6DetEngine.detectLines] (crop pixel coords), i.e.
 * already back-projected from the model's resized input space.
 *
 * @property bbox axis-aligned bounding box `[x1, y1, x2, y2]` (top-left /
 * bottom-right) in crop pixel coords. This is what the rec path crops + rotates.
 * @property meanScore the DB region score (mean probability inside the contour);
 *   useful for diagnostics and for ranking overlapping lines.
 */
data class TextLine(
    val bbox: IntArray,
    val meanScore: Float,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TextLine) return false
        return bbox.contentEquals(other.bbox) && meanScore == other.meanScore
    }

    override fun hashCode(): Int = 31 * bbox.contentHashCode() + meanScore.hashCode()
}

/**
 * TachiyomiAT: pure implementation of PaddleOCR's `DBPostProcess`, the
 * postprocessor for the PP-OCRv6 small **det** ONNX model.
 *
 * The det model emits a probability (saliency) map over the resized input; this
 * object turns that map into a list of [TextLine]s. Every step is pure
 * (no Android, no ONNX) so it is unit-testable in isolation — important given
 * the documented history of "fixes" to the OCR pipeline that passed tests in
 * isolation but broke on-device (see `docs/ocr-engine-notes.md`).
 *
 * Pipeline (mirrors PaddleOCR `DBPostProcess`):
 *  1. Threshold the prob map at [thresh] → binary mask.
 *  2. Connected-components labeling (8-connectivity) on the binary mask.
 *  3. For each component: compute the axis-aligned bbox + mean prob (score).
 *  4. Drop components below [boxThresh] mean score, below [minArea] pixels, or
 *     exceeding [maxCandidates].
 *  5. Unclip: expand each bbox outward by [unclipRatio] about its centroid,
 *     clamped to the map bounds — rec needs a little slack around the glyphs.
 *
 * The reference DB postprocess computes a rotated minimum-area rectangle per
 * component (`cv2.minAreaRect`). We use the axis-aligned bbox instead: manga
 * text lines are axis-aligned (horizontal rows or vertical columns), rec
 * resamples to a fixed 48 px height regardless, and avoiding the oriented-rect
 * math keeps this pure + simple. If on-device A/B shows axis-aligned clipping
 * of glyph edges, [unclipRatio] can be tuned (it already adds slack).
 *
 * All inputs/outputs are in **map space** (the prob map's pixel dimensions). The
 * caller ([PaddleOcrV6DetEngine]) back-projects the resulting bboxes to crop
 * pixel coords using the resize scale factors.
 */
object DbPostProcess {

    /** Default thresholds — match `inference.yml` of `PP-OCRv6_small_det_onnx`. */
    object Defaults {
        const val THRESH = 0.2f
        const val BOX_THRESH = 0.45f
        const val MAX_CANDIDATES = 3000
        const val UNCLIP_RATIO: Double = 1.4
    }

    /**
     * Minimum component area (in map pixels) to keep. The reference caps via
     * `max_candidates` and a min-area floor; tiny specks are noise. Sized in map
     * space so it is resolution-independent relative to the 736-px map.
     */
    private const val MIN_AREA_PX = 16

    // TachiyomiAT: line-merge thresholds for [mergeLineFragments]. Tuned in the
    // Python prototype to recover horizontal CJK lines (所因誤) the axis-aligned
    // connected-components step over-segments, without merging genuinely separate
    // lines. See det_merge_proto.py + DbPostProcessTest.
    private const val SAME_LINE_FRAC = 0.6f
    private const val MERGE_GAP_FACTOR = 1.0f
    private const val MERGE_MAX_PASSES = 3

    /**
     * Run the full DB postprocess on a flat prob map.
     *
     * @param probMap flat `[H * W]` array of probabilities in `[0,1]`, row-major.
     * @param width map width W.
     * @param height map height H.
     */
    fun detectLines(
        probMap: FloatArray,
        width: Int,
        height: Int,
        thresh: Float = Defaults.THRESH,
        boxThresh: Float = Defaults.BOX_THRESH,
        maxCandidates: Int = Defaults.MAX_CANDIDATES,
        unclipRatio: Double = Defaults.UNCLIP_RATIO,
    ): List<TextLine> {
        if (width <= 0 || height <= 0 || probMap.size < width * height) return emptyList()

        // 1. Binarize the prob map.
        val binary = BooleanArray(width * height)
        for (i in probMap.indices) {
            binary[i] = probMap[i] > thresh
        }

        // 2-4. Connected components with on-the-fly bbox + score accumulation.
        val labels = IntArray(width * height)
        val components = ArrayList<Component>(maxCandidates.coerceAtMost(64))
        var nextLabel = 1
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                if (!binary[idx] || labels[idx] != 0) continue
                val comp = floodLabel(
                    binary = binary,
                    labels = labels,
                    probMap = probMap,
                    width = width,
                    height = height,
                    startX = x,
                    startY = y,
                    label = nextLabel,
                )
                if (comp.pixelCount >= MIN_AREA_PX) {
                    components.add(comp)
                }
                nextLabel++
                if (components.size >= maxCandidates) {
                    // Bound work: PaddleOCR's `max_candidates` caps the number of
                    // returned boxes; stop scanning once we have that many viable
                    // components to avoid pathological pages stalling the OCR loop.
                    return finalize(components, boxThresh, unclipRatio, width, height)
                }
            }
        }
        return finalize(components, boxThresh, unclipRatio, width, height)
    }

    private fun finalize(
        components: List<Component>,
        boxThresh: Float,
        unclipRatio: Double,
        width: Int,
        height: Int,
    ): List<TextLine> {
        val out = ArrayList<TextLine>(components.size)
        for (c in components) {
            // DB's region score is the mean probability over the component's area.
            val meanScore = if (c.pixelCount > 0) c.probSum / c.pixelCount else 0f
            if (meanScore < boxThresh) continue
            val unclipped = unclip(c, unclipRatio, width, height)
            out.add(TextLine(bbox = unclipped, meanScore = meanScore))
        }
        // Merge same-line / same-column fragments that the axis-aligned
        // connected-components step splits apart (normal inter-character spacing
        // becomes a component boundary, so a horizontal line of CJK glyphs comes
        // back as one box per character). Without this, the rec head sees one
        // glyph at a time and the assembled text is fragmented. Validated in the
        // Python prototype: a Chinese bubble reading 所因誤 came back as 5
        // fragments before merge and 2 clean lines after.
        val merged = mergeLineFragments(out).toMutableList()
        // Clamp to image bounds
        for (i in merged.indices) {
            val b = merged[i].bbox
            b[0] = b[0].coerceIn(0, width - 1)
            b[1] = b[1].coerceIn(0, height - 1)
            b[2] = b[2].coerceIn(0, width - 1)
            b[3] = b[3].coerceIn(0, height - 1)
        }
        // Stable, deterministic order (top-to-bottom, left-to-right) so the caller
        // can apply its own reading-order policy (e.g. manga right-to-left) on a
        // predictable input.
        merged.sortWith(compareBy<TextLine>({ it.bbox[1] }, { it.bbox[0] }))
        return merged
    }

    /**
     * Reconstruct text lines/columns from per-character fragments emitted by the
     * connected-components step.
     *
     * Two boxes on the same row merge when their vertical centers are within
     * [SAME_LINE_FRAC] * min(height) AND the horizontal gap between them is within
     * [MERGE_GAP_FACTOR] * max(height). The vertical (column) case is symmetric
     * (swap x/y). Iterated to a fixed point so cascading merges complete.
     *
     * Pure + deterministic. Mirrors the validated Python `merge_lines` exactly.
     */
    fun mergeLineFragments(
        lines: List<TextLine>,
        sameLineFrac: Float = SAME_LINE_FRAC,
        mergeGapFactor: Float = MERGE_GAP_FACTOR,
    ): List<TextLine> {
        if (lines.size < 2) return lines
        var current = lines
        for (pass in 0 until MERGE_MAX_PASSES) {
            // Classify each fragment by orientation: wide (w >= h) merges along
            // the horizontal axis (same row), tall (h > w) merges along the
            // vertical axis (same column). Mirrors the validated Python prototype.
            val horiz = current.filter { (it.bbox[2] - it.bbox[0]) >= (it.bbox[3] - it.bbox[1]) }
            val vert = current.filter { (it.bbox[2] - it.bbox[0]) < (it.bbox[3] - it.bbox[1]) }
            val mergedH = mergeAlongAxis(horiz, axis = Axis.HORIZONTAL, sameLineFrac, mergeGapFactor)
            val mergedV = mergeAlongAxis(vert, axis = Axis.VERTICAL, sameLineFrac, mergeGapFactor)
            val next = mergedH + mergedV
            if (next.size == current.size) break
            current = next
        }
        return current
    }

    private enum class Axis { HORIZONTAL, VERTICAL }

    private fun mergeAlongAxis(
        group: List<TextLine>,
        axis: Axis,
        sameLineFrac: Float,
        mergeGapFactor: Float,
    ): List<TextLine> {
        if (group.isEmpty()) return group
        // Sort by the cross-axis center then the along-axis start so same-line
        // candidates are adjacent in iteration order.
        val sorted = when (axis) {
            Axis.HORIZONTAL -> group.sortedWith(
                compareBy({ (it.bbox[1] + it.bbox[3]) / 2 }, { it.bbox[0] }),
            )
            Axis.VERTICAL -> group.sortedWith(
                compareBy({ (it.bbox[0] + it.bbox[2]) / 2 }, { it.bbox[1] }),
            )
        }
        val merged = ArrayList<MutableTextLine>()
        for (t in sorted) {
            val b = t.bbox
            val (centerCross, sizeAlong, crossHalf) = when (axis) {
                Axis.HORIZONTAL -> Triple((b[1] + b[3]) / 2f, b[3] - b[1], b[3] - b[1])
                Axis.VERTICAL -> Triple((b[0] + b[2]) / 2f, b[2] - b[0], b[2] - b[0])
            }
            var placed = false
            for (m in merged) {
                val (mCenterCross, mSizeAlong) = when (axis) {
                    Axis.HORIZONTAL -> Pair((m.y1 + m.y2) / 2f, m.y2 - m.y1)
                    Axis.VERTICAL -> Pair((m.x1 + m.x2) / 2f, m.x2 - m.x1)
                }
                val sameLine = abs(centerCross - mCenterCross) <= sameLineFrac * min(sizeAlong, mSizeAlong)
                if (!sameLine) continue
                // Along-axis gap between the two boxes.
                val gap = when (axis) {
                    Axis.HORIZONTAL -> max(0, max(b[0], m.x1) - min(b[2], m.x2))
                    Axis.VERTICAL -> max(0, max(b[1], m.y1) - min(b[3], m.y2))
                }
                if (gap <= mergeGapFactor * max(sizeAlong, mSizeAlong)) {
                    m.x1 = min(m.x1, b[0])
                    m.y1 = min(m.y1, b[1])
                    m.x2 = max(m.x2, b[2])
                    m.y2 = max(m.y2, b[3])
                    m.score = max(m.score, t.meanScore)
                    placed = true
                    break
                }
            }
            if (!placed) {
                merged.add(MutableTextLine(b[0], b[1], b[2], b[3], t.meanScore))
            }
        }
        return merged.map { ml ->
            TextLine(bbox = intArrayOf(ml.x1, ml.y1, ml.x2, ml.y2), meanScore = ml.score)
        }
    }

    private class MutableTextLine(var x1: Int, var y1: Int, var x2: Int, var y2: Int, var score: Float)

    /**
     * 8-connectivity flood fill that records the component's pixel bbox, pixel
     * count, and summed probability. Iterative (stack-based) to avoid stack
     * overflow on large components. Marks visited cells via [labels].
     */
    private fun floodLabel(
        binary: BooleanArray,
        labels: IntArray,
        probMap: FloatArray,
        width: Int,
        height: Int,
        startX: Int,
        startY: Int,
        label: Int,
    ): Component {
        var minX = startX
        var maxX = startX
        var minY = startY
        var maxY = startY
        var count = 0
        var probSum = 0f
        val stack = IntArray(width * height)
        var sp = 0
        stack[sp++] = startY * width + startX
        labels[startY * width + startX] = label
        while (sp > 0) {
            val idx = stack[--sp]
            val x = idx % width
            val y = idx / width
            count++
            probSum += probMap[idx]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            // 8 neighbors
            val x0 = max(0, x - 1)
            val x1 = min(width - 1, x + 1)
            val y0 = max(0, y - 1)
            val y1 = min(height - 1, y + 1)
            for (ny in y0..y1) {
                for (nx in x0..x1) {
                    val nIdx = ny * width + nx
                    if (binary[nIdx] && labels[nIdx] == 0) {
                        labels[nIdx] = label
                        stack[sp++] = nIdx
                    }
                }
            }
        }
        return Component(minX, minY, maxX, maxY, count, probSum)
    }

    /**
     * Expand the axis-aligned bbox outward about its centroid by [ratio],
     * clamped to `[0, width] x [0, height]`. Equivalent to PaddleOCR's
     * `unclip` for axis-aligned boxes (the offset length scales with the box
     * half-extent, matching the polygon-offset intent of the reference).
     *
     * [ratio] is a Double (not Float) deliberately: passing `1.4f` through
     * `halfW * ratio` promotes the Float to Double as 1.399999976158142,
     * which truncates asymmetrically via `.toInt()` and shrinks the box by
     * ~1 px on the far edge — a real precision bug caught by the unit test
     * for the unclip expectation. The default [Defaults.UNCLIP_RATIO] is a
     * Double literal to match.
     */
    private fun unclip(c: Component, ratio: Double, width: Int, height: Int): IntArray {
        val w = (c.maxX - c.minX).toDouble()
        val h = (c.maxY - c.minY).toDouble()
        val distance = if (w + h <= 0.0) 0.0 else (w * h * ratio) / (2.0 * (w + h))
        val x1 = kotlin.math.floor(c.minX - distance).toInt()
        val y1 = kotlin.math.floor(c.minY - distance).toInt()
        val x2 = kotlin.math.ceil(c.maxX + distance).toInt()
        val y2 = kotlin.math.ceil(c.maxY + distance).toInt()
        return intArrayOf(x1, y1, x2, y2)
    }

    private data class Component(
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
        val pixelCount: Int,
        val probSum: Float,
    )

    /**
     * Convenience: back-project a single map-space bbox to crop-space using the
     * per-axis scale factors from the det model's resize step. Exposed so the
     * engine can keep the back-projection alongside the pure postprocess.
     */
    fun backProject(bbox: IntArray, scaleX: Float, scaleY: Float, cropW: Int, cropH: Int): IntArray {
        val x1 = (bbox[0] * scaleX).toInt().coerceIn(0, cropW - 1)
        val y1 = (bbox[1] * scaleY).toInt().coerceIn(0, cropH - 1)
        val x2 = (bbox[2] * scaleX).toInt().coerceIn(0, cropW - 1)
        val y2 = (bbox[3] * scaleY).toInt().coerceIn(0, cropH - 1)
        return intArrayOf(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
    }

    /** L2 distance between two bbox centers, used by overlap/dedupe callers. */
    fun centerDistance(a: IntArray, b: IntArray): Float {
        val acx = (a[0] + a[2]) / 2f
        val acy = (a[1] + a[3]) / 2f
        val bcx = (b[0] + b[2]) / 2f
        val bcy = (b[1] + b[3]) / 2f
        val dx = acx - bcx
        val dy = acy - bcy
        return sqrt(dx * dx + dy * dy)
    }
}
