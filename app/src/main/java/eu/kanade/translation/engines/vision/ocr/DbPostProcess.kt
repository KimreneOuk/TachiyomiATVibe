package eu.kanade.translation.engines.vision.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * a single text line detected by the PP-OCRv6 small **det** model
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
 * pure implementation of PaddleOCR's `DBPostProcess`, the
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
 *  5. Output the raw axis-aligned bbox `[x1,y1,x2,y2]` (inclusive max) — no
 *     expansion.
 *
 * The reference DB postprocess computes a rotated minimum-area rectangle per
 * component (`cv2.minAreaRect`). We use the axis-aligned bbox instead: manga
 * text lines are axis-aligned (horizontal rows or vertical columns), rec
 * resamples to a fixed 48 px height regardless, and avoiding the oriented-rect
 * math keeps this pure + simple. The raw axis-aligned bbox is sufficient;
 * the rec head already has padding from its own resize to 48 px height.
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
    }

    /**
     * Minimum component area (in map pixels) to keep. The reference caps via
     * `max_candidates` and a min-area floor; tiny specks are noise. Sized in map
     * space so it is resolution-independent relative to the 736-px map.
     */
    private const val MAX_COMPONENT_AREA_FRAC = 0.5f
    private const val MIN_AREA_PX = 16

    // line-merge thresholds for [mergeLineFragments]. Tuned in the
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
        probabilityMap: FloatArray,
        width: Int,
        height: Int,
        threshold: Float = Defaults.THRESH,
        boxThreshold: Float = Defaults.BOX_THRESH,
        maxCandidates: Int = Defaults.MAX_CANDIDATES,
    ): List<TextLine> {
        if (width <= 0 || height <= 0 || probabilityMap.size < width * height) return emptyList()

        val binary = BooleanArray(width * height)
        for (i in probabilityMap.indices) {
            binary[i] = probabilityMap[i] > threshold
        }

        val labels = IntArray(width * height)
        val components = ArrayList<Component>(maxCandidates.coerceAtMost(64))
        var nextLabel = 1
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                if (!binary[idx] || labels[idx] != 0) continue
                val component = floodLabel(
                    binary = binary,
                    labels = labels,
                    probabilityMap = probabilityMap,
                    width = width,
                    height = height,
                    startX = x,
                    startY = y,
                    label = nextLabel,
                )
                if (component.pixelCount >= MIN_AREA_PX) {
                    components.add(component)
                }
                nextLabel++
                if (components.size >= maxCandidates) {
                    // PaddleOCR's max_candidates cap; stop early so pathological
                    // pages don't stall the OCR loop.
                    return finalize(components, boxThreshold, width, height)
                }
            }
        }
        return finalize(components, boxThreshold, width, height)
    }

    private fun finalize(
        components: List<Component>,
        boxThreshold: Float,
        width: Int,
        height: Int,
    ): List<TextLine> {
        val out = ArrayList<TextLine>(components.size)
        for (component in components) {
            // DB's region score is the mean probability over the component's area.
            val meanScore = if (component.pixelCount > 0) component.probSum / component.pixelCount else 0f
            if (meanScore < boxThreshold) continue
            val area = (component.maxX - component.minX + 1).toLong() * (component.maxY - component.minY + 1).toLong()
            if (area > (MAX_COMPONENT_AREA_FRAC * width * height).toLong()) continue
            val rawBox = intArrayOf(component.minX, component.minY, component.maxX, component.maxY)
            out.add(TextLine(bbox = rawBox, meanScore = meanScore))
        }
        // Merge fragments the axis-aligned CC step splits apart: normal inter-
        // character spacing becomes a component boundary, so a horizontal CJK line
        // comes back one-box-per-character and the rec head sees single glyphs.
        val merged = mergeLineFragments(out).toMutableList()
        for (i in merged.indices) {
            val b = merged[i].bbox
            b[0] = b[0].coerceIn(0, width - 1)
            b[1] = b[1].coerceIn(0, height - 1)
            b[2] = b[2].coerceIn(0, width - 1)
            b[3] = b[3].coerceIn(0, height - 1)
        }
        // Deterministic top-to-bottom, left-to-right order so the caller can
        // apply its reading-order policy (e.g. manga right-to-left) predictably.
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
            val (centerCross, crossSize, gapSize) = when (axis) {
                Axis.HORIZONTAL -> Triple((b[1] + b[3]) / 2f, b[3] - b[1], b[3] - b[1])
                // VERTICAL: gap is vertical spacing between stacked glyphs,
                // proportional to glyph height not column width.
                Axis.VERTICAL -> Triple((b[0] + b[2]) / 2f, b[2] - b[0], b[3] - b[1])
            }
            var placed = false
            for (m in merged) {
                val (mCenterCross, mCrossSize, mGapSize) = when (axis) {
                    Axis.HORIZONTAL -> Triple((m.y1 + m.y2) / 2f, m.y2 - m.y1, m.y2 - m.y1)
                    Axis.VERTICAL -> Triple((m.x1 + m.x2) / 2f, m.x2 - m.x1, m.y2 - m.y1)
                }
                val sameLine = abs(centerCross - mCenterCross) <= sameLineFrac * min(crossSize, mCrossSize)
                if (!sameLine) continue
                val gap = when (axis) {
                    Axis.HORIZONTAL -> max(0, max(b[0], m.x1) - min(b[2], m.x2))
                    Axis.VERTICAL -> max(0, max(b[1], m.y1) - min(b[3], m.y2))
                }
                if (gap <= mergeGapFactor * max(gapSize, mGapSize)) {
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
        probabilityMap: FloatArray,
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
        var stackPointer = 0
        stack[stackPointer++] = startY * width + startX
        labels[startY * width + startX] = label
        while (stackPointer > 0) {
            val idx = stack[--stackPointer]
            val x = idx % width
            val y = idx / width
            count++
            probSum += probabilityMap[idx]
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
                        stack[stackPointer++] = nIdx
                    }
                }
            }
        }
        return Component(minX, minY, maxX, maxY, count, probSum)
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
    fun backProject(bbox: IntArray, scaleX: Float, scaleY: Float, cropWidth: Int, cropHeight: Int): IntArray {
        val x1 = (bbox[0] * scaleX).toInt().coerceIn(0, cropWidth - 1)
        val y1 = (bbox[1] * scaleY).toInt().coerceIn(0, cropHeight - 1)
        val x2 = (bbox[2] * scaleX).toInt().coerceIn(0, cropWidth - 1)
        val y2 = (bbox[3] * scaleY).toInt().coerceIn(0, cropHeight - 1)
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
