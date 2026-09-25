package eu.kanade.translation.segmentation

import kotlinx.serialization.Serializable

/** Compact row-major run-length persistence for one page-space bubble mask. */
@Serializable
data class BubbleMaskRle(
    val width: Int,
    val height: Int,
    val bounds: List<Int>,
    val runs: List<Int>,
    val score: Float,
) {
    init {
        require(width > 0 && height > 0 && bounds.size == 4 && runs.size % 2 == 0) { "Invalid bubble RLE" }
        require(
            bounds[0] in 0..width &&
                bounds[1] in 0..height &&
                bounds[2] in bounds[0]..width &&
                bounds[3] in bounds[1]..height,
        ) { "Bubble RLE bounds outside mask" }
        var previousEnd = 0
        for (i in runs.indices step 2) {
            val start = runs[i]
            val length = runs[i + 1]
            require(start >= previousEnd && length > 0 && start <= pixelCount - length) {
                "Bubble RLE run outside mask"
            }
            previousEnd = start + length
        }
    }

    /** Materializes this mask only when a dense raster is required by a pixel operation. */
    fun decode(): ByteArray = ByteArray(pixelCount).also(::rasterizeOnto)

    /**
     * Rasterizes the mask into an already allocated page-sized [target]. This avoids
     * one full-page temporary per persisted instance when callers need a union.
     */
    fun rasterizeOnto(target: ByteArray) {
        require(target.size == pixelCount) { "Bubble RLE target has wrong dimensions" }
        for (i in runs.indices step 2) {
            java.util.Arrays.fill(target, runs[i], runs[i] + runs[i + 1], 1)
        }
    }

    /** Number of interior pixels inside the half-open page-space rectangle. */
    fun overlapPixels(x1: Int, y1: Int, x2: Int, y2: Int): Int {
        val left = x1.coerceIn(0, width)
        val top = y1.coerceIn(0, height)
        val right = x2.coerceIn(left, width)
        val bottom = y2.coerceIn(top, height)
        if (right == left || bottom == top) return 0
        var overlap = 0
        for (row in top until bottom) {
            val rowStart = row * width
            val rowEnd = rowStart + width
            for (i in runs.indices step 2) {
                val start = runs[i]
                if (start >= rowEnd) break
                val end = start + runs[i + 1]
                if (end >
                    rowStart
                ) {
                    overlap +=
                        (minOf(end, rowEnd, rowStart + right) - maxOf(start, rowStart + left)).coerceAtLeast(0)
                }
            }
        }
        return overlap
    }

    private val pixelCount: Int
        get() = Math.multiplyExact(width, height)

    companion object {
        fun encode(mask: BubbleSegmentationDecoder.Mask): BubbleMaskRle {
            val runs = ArrayList<Int>()
            var index = 0
            while (index < mask.pixels.size) {
                if (mask.pixels[index] == 0.toByte()) {
                    index++
                    continue
                }
                val start = index
                while (index < mask.pixels.size && mask.pixels[index] != 0.toByte()) index++
                runs += start
                runs += index - start
            }
            return BubbleMaskRle(mask.width, mask.height, mask.bounds.toList(), runs, mask.score)
        }
    }
}
