package eu.kanade.translation.webtoon

import android.graphics.Bitmap
import eu.kanade.translation.model.Detection
import eu.kanade.translation.recognition.BoxGeometry
import eu.kanade.translation.segmentation.BubbleMaskRle
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Aspect-preserving sliding-window detection runner for tall webtoon images.
 *
 * Slices images with aspect ratio >= 2.0 into standard 1:1.4 aspect windows with overlap,
 * avoiding the extreme aspect ratio distortion caused by forcing tall images into a fixed
 * 640x640 model input. Detections from overlapping windows are mapped to global coordinates
 * and deduplicated via IoU merging.
 */
object WebtoonSlidingDetector {

    const val TALL_ASPECT_RATIO_THRESHOLD = 2.0f
    const val DEFAULT_OVERLAP_PX = 250
    const val MANGA_ASPECT_RATIO = 1.4f

    data class WindowSlice(
        val index: Int,
        val top: Int,
        val bottom: Int,
        val height: Int,
    )

    fun isTallImage(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        return (height.toFloat() / width.toFloat()) >= TALL_ASPECT_RATIO_THRESHOLD
    }

    /**
     * Calculates the window slice boundaries for a tall image.
     */
    fun calculateWindows(
        width: Int,
        height: Int,
        targetWindowHeight: Int = (width * MANGA_ASPECT_RATIO).roundToInt(),
        overlapPx: Int = min(DEFAULT_OVERLAP_PX, targetWindowHeight / 4),
    ): List<WindowSlice> {
        if (width <= 0 || height <= 0) return emptyList()
        if (!isTallImage(width, height)) {
            return listOf(WindowSlice(0, 0, height, height))
        }

        val windowH = max(100, min(height, targetWindowHeight))
        val overlap = max(0, min(overlapPx, windowH / 2))
        val step = max(1, windowH - overlap)

        val count = ceil((height - overlap).toDouble() / step.toDouble()).toInt().coerceAtLeast(1)
        val slices = ArrayList<WindowSlice>(count)

        for (i in 0 until count) {
            val top = i * step
            if (top >= height) break
            val bottom = min(height, top + windowH)
            val h = bottom - top
            slices.add(WindowSlice(i, top, bottom, h))
            if (bottom >= height) break
        }

        return slices
    }

    /**
     * Projects a detection from a local window coordinate space to global image space.
     */
    fun projectDetection(detection: Detection, windowTop: Int): Detection {
        if (windowTop == 0) return detection
        val b = detection.bbox
        val projectedBbox = intArrayOf(
            b[0],
            b[1] + windowTop,
            b[2],
            b[3] + windowTop,
        )
        return detection.copy(bbox = projectedBbox)
    }

    /**
     * Deduplicates and merges overlapping detections from sliding windows.
     * When two detections of the same label significantly overlap, they are merged
     * into a single union bounding box to ensure speech bubbles lying on window seams
     * remain unified.
     */
    fun mergeDetections(
        detections: List<Detection>,
        iouThreshold: Float = 0.40f,
    ): List<Detection> {
        if (detections.size <= 1) return detections

        val sorted = detections.sortedByDescending { it.score }
        val merged = ArrayList<Detection>()
        val consumed = BooleanArray(sorted.size)

        for (i in sorted.indices) {
            if (consumed[i]) continue
            var current = sorted[i]
            consumed[i] = true

            for (j in i + 1 until sorted.size) {
                if (consumed[j]) continue
                val candidate = sorted[j]

                // Only merge detections of the same category (e.g. bubble with bubble)
                if (current.label != candidate.label) continue

                val iou = BoxGeometry.iou(current.bbox, candidate.bbox)
                val interArea = BoxGeometry.intersectionArea(current.bbox, candidate.bbox)
                val minArea = min(BoxGeometry.bboxArea(current.bbox), BoxGeometry.bboxArea(candidate.bbox))
                val containment = if (minArea > 0) interArea.toFloat() / minArea.toFloat() else 0f

                // Check for sliding window seam collinearity: two boxes sharing >60% horizontal span
                // that meet or overlap along a vertical seam
                val xOverlap = max(0, min(current.bbox[2], candidate.bbox[2]) - max(current.bbox[0], candidate.bbox[0]))
                val minWidth = min(current.bbox[2] - current.bbox[0], candidate.bbox[2] - candidate.bbox[0])
                val xOverlapRatio = if (minWidth > 0) xOverlap.toFloat() / minWidth.toFloat() else 0f
                val yOverlap = max(0, min(current.bbox[3], candidate.bbox[3]) - max(current.bbox[1], candidate.bbox[1]))
                val verticalGap = max(0, max(current.bbox[1], candidate.bbox[1]) - min(current.bbox[3], candidate.bbox[3]))
                val isSeamCollinear = xOverlapRatio >= 0.60f && (yOverlap > 0 || verticalGap <= 20)

                if (iou >= iouThreshold || containment >= 0.70f || isSeamCollinear) {
                    consumed[j] = true
                    // Merge into union bounding box
                    val unionBox = intArrayOf(
                        min(current.bbox[0], candidate.bbox[0]),
                        min(current.bbox[1], candidate.bbox[1]),
                        max(current.bbox[2], candidate.bbox[2]),
                        max(current.bbox[3], candidate.bbox[3]),
                    )
                    current = current.copy(
                        bbox = unionBox,
                        score = max(current.score, candidate.score),
                    )
                }
            }
            merged.add(current)
        }

        return merged
    }

    /**
     * Executes detection across sliding windows if tall, otherwise delegates directly to [detectFn].
     */
    fun detectSliding(
        bitmap: Bitmap,
        detectFn: (Bitmap) -> List<Detection>,
    ): List<Detection> {
        if (!isTallImage(bitmap.width, bitmap.height)) {
            return detectFn(bitmap)
        }

        val windows = calculateWindows(bitmap.width, bitmap.height)
        val allDetections = ArrayList<Detection>()

        for (window in windows) {
            val isFullImage = window.top == 0 && window.height == bitmap.height
            val crop = if (isFullImage) {
                bitmap
            } else {
                Bitmap.createBitmap(bitmap, 0, window.top, bitmap.width, window.height)
            }

            try {
                val windowDetections = detectFn(crop)
                for (detection in windowDetections) {
                    allDetections.add(projectDetection(detection, window.top))
                }
            } finally {
                if (crop !== bitmap) {
                    crop.recycle()
                }
            }
        }

        return mergeDetections(allDetections)
    }

    /**
     * Executes bubble segmentation across sliding windows for tall images.
     * Reconstructs global [BubbleMaskRle] instances from local window segmentations.
     */
    fun segmentSliding(
        bitmap: Bitmap,
        segmentFn: (Bitmap) -> List<BubbleMaskRle>,
    ): List<BubbleMaskRle> {
        if (!isTallImage(bitmap.width, bitmap.height)) {
            return segmentFn(bitmap)
        }

        val windows = calculateWindows(bitmap.width, bitmap.height)
        val allMasks = ArrayList<BubbleMaskRle>()

        for (window in windows) {
            val isFullImage = window.top == 0 && window.height == bitmap.height
            val crop = if (isFullImage) {
                bitmap
            } else {
                Bitmap.createBitmap(bitmap, 0, window.top, bitmap.width, window.height)
            }

            try {
                val windowMasks = segmentFn(crop)
                for (mask in windowMasks) {
                    allMasks.add(projectRle(mask, window.top, bitmap.width, bitmap.height))
                }
            } finally {
                if (crop !== bitmap) {
                    crop.recycle()
                }
            }
        }

        return allMasks
    }

    /**
     * Projects a window-local [BubbleMaskRle] into page coordinates. Runs are
     * page-flat (start,length) pairs over width*height, so a vertical window
     * offset shifts each run start by windowTop * pageWidth and moves the
     * bounds' y by the same offset; lengths and x stay untouched.
     */
    fun projectRle(
        mask: BubbleMaskRle,
        windowTop: Int,
        pageWidth: Int,
        pageHeight: Int,
    ): BubbleMaskRle {
        if (windowTop == 0 && mask.height == pageHeight) {
            return mask
        }
        val offset = windowTop * pageWidth
        return mask.copy(
            width = pageWidth,
            height = pageHeight,
            bounds = listOf(
                mask.bounds[0],
                mask.bounds[1] + windowTop,
                mask.bounds[2],
                min(pageHeight, mask.bounds[3] + windowTop),
            ),
            runs = mask.runs.mapIndexed { index, value -> if (index % 2 == 0) value + offset else value },
        )
    }
}
