package eu.kanade.translation.engines.inpainting.bubble

import android.graphics.Bitmap
import eu.kanade.translation.engines.inpainting.aot.AotPixelOps
import eu.kanade.translation.engines.inpainting.opencv.OpenCvInpaintEngine
import kotlin.math.max
import kotlin.math.min

/**
 * OpenCV NS fill for bubble regions. It consumes the same merged mask as the
 * legacy median fill and runs per component-cluster crop to keep native memory
 * bounded.
 */
object BubbleOpenCvInpainter {

    const val CLUSTER_GAP_PX = 64
    const val CROP_CONTEXT_PX = 24
    const val SMALL_CLUSTER_MAX_DIM = 256
    const val FEATHER_RAMP_PX = 12

    /**
     * Finds 4-connected mask components and merges their half-open bounds when
     * both edge gaps are at most [gap]. The returned bounds are ordered top to
     * bottom, then left to right.
     */
    fun componentClusters(
        mask: ByteArray,
        width: Int,
        height: Int,
        gap: Int = CLUSTER_GAP_PX,
    ): List<IntArray> {
        require(width > 0 && height > 0) { "mask dimensions must be positive: ${width}x$height" }
        require(width.toLong() * height == mask.size.toLong()) {
            "mask length=${mask.size} does not match ${width}x$height"
        }
        require(gap >= 0) { "gap must be non-negative: $gap" }

        val visited = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        val boxes = ArrayList<IntArray>()
        for (start in mask.indices) {
            if (mask[start] == 0.toByte() || visited[start]) continue

            var minX = width
            var minY = height
            var maxX = -1
            var maxY = -1
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)

                if (x > 0) enqueueIfMasked(index - 1, mask, visited, queue, tail).also { tail = it }
                if (x + 1 < width) enqueueIfMasked(index + 1, mask, visited, queue, tail).also { tail = it }
                if (y > 0) enqueueIfMasked(index - width, mask, visited, queue, tail).also { tail = it }
                if (y + 1 < height) enqueueIfMasked(index + width, mask, visited, queue, tail).also { tail = it }
            }
            boxes.add(intArrayOf(minX, minY, maxX + 1, maxY + 1))
        }

        var merged = boxes
        while (true) {
            val combined = ArrayList<IntArray>(merged.size)
            var didMerge = false
            for (box in merged) {
                val join = combined.firstOrNull { other ->
                    edgeGap(box[0], box[2], other[0], other[2]) <= gap &&
                        edgeGap(box[1], box[3], other[1], other[3]) <= gap
                }
                if (join == null) {
                    combined.add(box)
                } else {
                    join[0] = min(join[0], box[0])
                    join[1] = min(join[1], box[1])
                    join[2] = max(join[2], box[2])
                    join[3] = max(join[3], box[3])
                    didMerge = true
                }
            }
            merged = combined
            if (!didMerge) {
                return merged.sortedWith(compareBy<IntArray> { it[1] }.thenBy { it[0] })
            }
        }
    }

    /**
     * Fills each cluster crop with OpenCV NS and feathers it back into [image].
     * Returns the least capable backend used, so emergency push-pull remains
     * visible to the caller for degraded-run stamping.
     */
    fun inpaintClusters(
        image: Bitmap,
        mask: ByteArray,
        clusters: List<IntArray>,
        width: Int,
        height: Int,
    ): OpenCvInpaintEngine.Backend {
        require(image.width == width && image.height == height) {
            "bitmap dimensions ${image.width}x${image.height} do not match ${width}x$height"
        }
        require(width > 0 && height > 0 && width.toLong() * height == mask.size.toLong()) {
            "mask length=${mask.size} does not match ${width}x$height"
        }

        var worst = OpenCvInpaintEngine.Backend.OPENCV_NS
        for (cluster in clusters) {
            if (cluster.size < 4) continue
            val x1 = (cluster[0] - CROP_CONTEXT_PX).coerceIn(0, width)
            val y1 = (cluster[1] - CROP_CONTEXT_PX).coerceIn(0, height)
            val x2 = (cluster[2] + CROP_CONTEXT_PX).coerceIn(0, width)
            val y2 = (cluster[3] + CROP_CONTEXT_PX).coerceIn(0, height)
            val cropW = x2 - x1
            val cropH = y2 - y1
            if (cropW <= 0 || cropH <= 0) continue

            val localMask = ByteArray(cropW * cropH)
            var anyMasked = false
            for (y in 0 until cropH) {
                val sourceOffset = (y1 + y) * width + x1
                val destinationOffset = y * cropW
                for (x in 0 until cropW) {
                    val value = mask[sourceOffset + x]
                    localMask[destinationOffset + x] = value
                    if (value != 0.toByte()) anyMasked = true
                }
            }
            if (!anyMasked) continue

            val original = IntArray(cropW * cropH)
            image.getPixels(original, 0, cropW, x1, y1, cropW, cropH)
            val inpainted = original.copyOf()
            val maxDimension = max(cropW, cropH)
            val backend = OpenCvInpaintEngine.inpaintPixelsWithBackend(
                pixels = inpainted,
                mask = localMask,
                width = cropW,
                height = cropH,
                radius = if (maxDimension <= SMALL_CLUSTER_MAX_DIM) 3.0 else 5.0,
                method = OpenCvInpaintEngine.INPAINT_NS,
            )
            if (backend == OpenCvInpaintEngine.Backend.PUSH_PULL_EMERGENCY) {
                worst = backend
            }
            val alpha = BubbleMaskBuilder.featherAlphaField(localMask, cropW, cropH, FEATHER_RAMP_PX)
            AotPixelOps.compositeInto(original, inpainted, alpha, inpainted)
            image.setPixels(inpainted, 0, cropW, x1, y1, cropW, cropH)
        }
        return worst
    }

    private fun edgeGap(a1: Int, a2: Int, b1: Int, b2: Int): Int = max(a1 - b2, b1 - a2)

    private fun enqueueIfMasked(
        index: Int,
        mask: ByteArray,
        visited: BooleanArray,
        queue: IntArray,
        tail: Int,
    ): Int {
        if (mask[index] == 0.toByte() || visited[index]) return tail
        visited[index] = true
        queue[tail] = index
        return tail + 1
    }
}
