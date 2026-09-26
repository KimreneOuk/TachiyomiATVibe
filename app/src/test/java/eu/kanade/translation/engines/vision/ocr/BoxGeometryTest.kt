package eu.kanade.translation.engines.vision.ocr

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Guards the shared axis-aligned-bounding-box geometry used by the detection
 * and OCR stages to dedupe overlapping text regions. Previously the IoU /
 * intersection / area primitives and the geometric-duplicate predicate were
 * copy-pasted in BOTH OnnxPageTextDetector and RoiPageRecognitionEngine with
 * only threshold constants differing — a fix to one never reached the other.
 */
class BoxGeometryTest {

    private fun box(x1: Int, y1: Int, x2: Int, y2: Int) = intArrayOf(x1, y1, x2, y2)

    @Test
    fun `bboxArea of a normal box is width times height`() {
        BoxGeometry.bboxArea(box(0, 0, 10, 4)) shouldBe 40
    }

    @Test
    fun `bboxArea is clamped to zero for inverted or degenerate boxes`() {
        // Inverted (x2 < x1) and zero-size boxes must never go negative.
        BoxGeometry.bboxArea(box(10, 10, 0, 0)) shouldBe 0
        BoxGeometry.bboxArea(box(5, 5, 5, 5)) shouldBe 0
    }

    @Test
    fun `intersectionArea is the overlap region`() {
        BoxGeometry.intersectionArea(box(0, 0, 10, 10), box(5, 5, 15, 15)) shouldBe 25
    }

    @Test
    fun `intersectionArea is zero for disjoint boxes`() {
        BoxGeometry.intersectionArea(box(0, 0, 5, 5), box(10, 10, 15, 15)) shouldBe 0
    }

    @Test
    fun `iou is one for identical boxes`() {
        BoxGeometry.iou(box(0, 0, 10, 10), box(0, 0, 10, 10)) shouldBe 1.0f
    }

    @Test
    fun `iou is zero for disjoint boxes`() {
        BoxGeometry.iou(box(0, 0, 5, 5), box(10, 10, 15, 15)) shouldBe 0.0f
    }

    @Test
    fun `iou is a fraction for partial overlap`() {
        // 5x5 overlap (25) over union 100+100-25 = 175 → 1/7.
        val iou = BoxGeometry.iou(box(0, 0, 10, 10), box(5, 5, 15, 15))
        iou shouldBe (25f / 175f)
    }

    @Test
    fun `iou is zero for degenerate boxes`() {
        BoxGeometry.iou(box(0, 0, 0, 0), box(0, 0, 10, 10)) shouldBe 0.0f
    }

    @Test
    fun `isGeometricDuplicate flags high-IoU pairs`() {
        val tight = BoxGeometry.DedupThresholds(iou = 0.5f, containment = 1f, center = 0f, size = 0f)

        // Nearly identical boxes → IoU ~0.68 > 0.5 → duplicate.
        BoxGeometry.isGeometricDuplicate(box(0, 0, 10, 10), box(1, 1, 11, 11), tight) shouldBe true
    }

    @Test
    fun `isGeometricDuplicate flags containment of the smaller box`() {
        // IoU is low, but the small box is fully inside the large one →
        // containment (inter/minArea) == 1.0 > threshold.
        val containmentOnly = BoxGeometry.DedupThresholds(iou = 0.99f, containment = 0.5f, center = 0f, size = 0f)

        BoxGeometry.isGeometricDuplicate(box(0, 0, 100, 100), box(40, 40, 60, 60), containmentOnly) shouldBe true
    }

    @Test
    fun `isGeometricDuplicate flags close centers with near-equal size`() {
        // Barely overlapping but same size and nearly same center.
        val centerSizeOnly = BoxGeometry.DedupThresholds(iou = 0.99f, containment = 0.99f, center = 0.2f, size = 0.2f)

        BoxGeometry.isGeometricDuplicate(box(0, 0, 100, 100), box(2, 2, 102, 102), centerSizeOnly) shouldBe true
    }

    @Test
    fun `isGeometricDuplicate leaves clearly distinct boxes alone`() {
        val normal = BoxGeometry.DedupThresholds(iou = 0.5f, containment = 0.5f, center = 0.1f, size = 0.1f)

        BoxGeometry.isGeometricDuplicate(box(0, 0, 10, 10), box(100, 100, 120, 120), normal) shouldBe false
    }
}
