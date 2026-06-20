package eu.kanade.translation.recognition

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure axis-aligned-bounding-box geometry used by the detection + OCR stages to
 * dedupe overlapping text detections.
 *
 * Previously the same primitives (`iou`, `intersectionArea`, `bboxArea`) and a
 * near-identical geometric-duplicate predicate existed in BOTH
 * [eu.kanade.translation.detection.OnnxPageTextDetector] and
 * [eu.kanade.translation.recognition.RoiPageRecognitionEngine], with only the
 * threshold constants differing. A fix to one copy never reached the other.
 * Centralizing the math gives a single tested source of truth; the per-stage
 * threshold sets stay on their owners as [DedupThresholds].
 *
 * Boxes are `intArrayOf(x1, y1, x2, y2)` (top-left / bottom-right), matching the
 * on-device detector's output shape.
 */
object BoxGeometry {

    /** Area of a box, clamped at 0 for degenerate/inverted boxes. */
    fun bboxArea(box: IntArray): Int =
        max(0, box[2] - box[0]) * max(0, box[3] - box[1])

    /** Overlapping area of two boxes, 0 when they don't intersect. */
    fun intersectionArea(a: IntArray, b: IntArray): Int {
        val ix1 = max(a[0], b[0])
        val iy1 = max(a[1], b[1])
        val ix2 = min(a[2], b[2])
        val iy2 = min(a[3], b[3])
        if (ix2 <= ix1 || iy2 <= iy1) return 0
        return (ix2 - ix1) * (iy2 - iy1)
    }

    /** Intersection-over-union of two boxes in [0, 1]. */
    fun iou(a: IntArray, b: IntArray): Float {
        val inter = intersectionArea(a, b)
        if (inter <= 0) return 0.0f
        val aArea = bboxArea(a)
        val bArea = bboxArea(b)
        val union = aArea + bArea - inter
        return if (union > 0) inter.toFloat() / union.toFloat() else 0.0f
    }

    /**
     * Geometric duplicate predicate shared by both dedup stages. Returns true
     * when [a] and [b] are "the same" text region under any of three signals:
     *  1. high IoU,
     *  2. high containment of the smaller box inside the larger, or
     *  3. close centers AND near-equal size (catches near-identical boxes that
     *     barely overlap, e.g. the same bubble detected at two slightly offset
     *     crops).
     *
     * The decision is driven entirely by [thresholds], so each caller keeps its
     * own tuned constants while sharing the algorithm.
     */
    fun isGeometricDuplicate(a: IntArray, b: IntArray, thresholds: DedupThresholds): Boolean {
        if (iou(a, b) > thresholds.iou) return true
        val minArea = min(bboxArea(a), bboxArea(b))
        if (minArea > 0 && intersectionArea(a, b).toFloat() / minArea.toFloat() > thresholds.containment) {
            return true
        }

        val aw = max(1, a[2] - a[0])
        val ah = max(1, a[3] - a[1])
        val bw = max(1, b[2] - b[0])
        val bh = max(1, b[3] - b[1])
        val centerDx = abs((a[0] + a[2]) - (b[0] + b[2])) / 2f
        val centerDy = abs((a[1] + a[3]) - (b[1] + b[3])) / 2f
        return centerDx <= thresholds.center * min(aw, bw) &&
            centerDy <= thresholds.center * min(ah, bh) &&
            abs(aw - bw).toFloat() <= thresholds.size * max(aw, bw) &&
            abs(ah - bh).toFloat() <= thresholds.size * max(ah, bh)
    }

    /**
     * Tunable thresholds for [isGeometricDuplicate]. Each dedup stage
     * instantiates its own tuned set.
     */
    data class DedupThresholds(
        val iou: Float,
        val containment: Float,
        val center: Float,
        val size: Float,
    )
}
