package eu.kanade.translation.engines.inpainting.aot

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal object AotBoxGeometry {

    internal fun localizeBox(box: IntArray, originX: Int, originY: Int, width: Int, height: Int): IntArray? {
        val x1 = (box[0] - originX).coerceIn(0, width)
        val y1 = (box[1] - originY).coerceIn(0, height)
        val x2 = (box[2] - originX).coerceIn(0, width)
        val y2 = (box[3] - originY).coerceIn(0, height)
        return if (x2 > x1 && y2 > y1) intArrayOf(x1, y1, x2, y2) else null
    }

    internal fun paddedUnionBounds(boxes: List<IntArray>, width: Int, height: Int, pad: Int): IntArray? {
        if (boxes.isEmpty()) return null
        val x1 = max(0, boxes.minOf { it[0] } - pad)
        val y1 = max(0, boxes.minOf { it[1] } - pad)
        val x2 = min(width, boxes.maxOf { it[2] } + pad)
        val y2 = min(height, boxes.maxOf { it[3] } + pad)
        return if (x2 > x1 && y2 > y1) intArrayOf(x1, y1, x2, y2) else null
    }

    internal fun centeredReportCrop(boxes: List<IntArray>, width: Int, height: Int, contextSize: Int): IntArray? {
        if (boxes.isEmpty()) return null
        val ux1 = boxes.minOf { it[0] }
        val uy1 = boxes.minOf { it[1] }
        val ux2 = boxes.maxOf { it[2] }
        val uy2 = boxes.maxOf { it[3] }
        val side = min(contextSize, min(width, height))
        val cx = ((ux1 + ux2) / 2.0).roundToInt()
        val cy = ((uy1 + uy2) / 2.0).roundToInt()
        val x1 = (cx - side / 2).coerceIn(0, width - side)
        val y1 = (cy - side / 2).coerceIn(0, height - side)
        return intArrayOf(x1, y1, x1 + side, y1 + side)
    }

    internal fun findParentBubble(textBox: IntArray, bubbleBoxes: List<IntArray>): IntArray? {
        val cx = (textBox[0] + textBox[2]) / 2.0
        val cy = (textBox[1] + textBox[3]) / 2.0
        var best: IntArray? = null
        var bestArea = Float.MAX_VALUE
        for (bubble in bubbleBoxes) {
            if (cx >= bubble[0] && cx <= bubble[2] && cy >= bubble[1] && cy <= bubble[3]) {
                val area = (bubble[2] - bubble[0]) * (bubble[3] - bubble[1]).toFloat()
                if (area < bestArea) {
                    best = bubble
                    bestArea = area
                }
            }
        }
        return best
    }

    internal fun overlapsAnyBubble(
        textBox: IntArray,
        bubbleBoxes: List<IntArray>,
        minOverlapFraction: Float = 0.12f,
    ): Boolean {
        if (bubbleBoxes.isEmpty() || textBox.size < 4) return false
        val textArea = (
            (textBox[2] - textBox[0]).coerceAtLeast(1) *
                (textBox[3] - textBox[1]).coerceAtLeast(1)
            ).toFloat()
        for (bubble in bubbleBoxes) {
            val ix1 = max(textBox[0], bubble[0])
            val iy1 = max(textBox[1], bubble[1])
            val ix2 = min(textBox[2], bubble[2])
            val iy2 = min(textBox[3], bubble[3])
            if (ix2 <= ix1 || iy2 <= iy1) continue
            val overlap = (ix2 - ix1) * (iy2 - iy1)
            if (overlap / textArea >= minOverlapFraction) return true
        }
        return false
    }

    /**
     * Clusters nearby free-text box groups that can fit together inside a single
     * [maxContextSize] × [maxContextSize] window.
     *
     * Groups whose combined bounding union fits within [maxContextSize] along both dimensions
     * are merged into a single cluster. This drastically cuts the number of required neural
     * inpainting passes while preserving the exact mask boundaries of each individual text box.
     */
    internal fun clusterFreeTextGroups(
        groups: List<List<IntArray>>,
        maxContextSize: Int = 512,
    ): List<List<IntArray>> {
        val validGroups = groups.filter { it.isNotEmpty() }
        if (validGroups.size <= 1) return validGroups

        class Cluster(
            val boxes: MutableList<IntArray>,
            var minX: Int,
            var minY: Int,
            var maxX: Int,
            var maxY: Int,
        ) {
            fun canMergeWith(other: Cluster, maxSize: Int): Boolean {
                val newMinX = min(minX, other.minX)
                val newMinY = min(minY, other.minY)
                val newMaxX = max(maxX, other.maxX)
                val newMaxY = max(maxY, other.maxY)
                return (newMaxX - newMinX) <= maxSize && (newMaxY - newMinY) <= maxSize
            }

            fun merge(other: Cluster) {
                boxes.addAll(other.boxes)
                minX = min(minX, other.minX)
                minY = min(minY, other.minY)
                maxX = max(maxX, other.maxX)
                maxY = max(maxY, other.maxY)
            }

            fun unionAreaWith(other: Cluster): Long {
                val w = (max(maxX, other.maxX) - min(minX, other.minX)).toLong()
                val h = (max(maxY, other.maxY) - min(minY, other.minY)).toLong()
                return w * h
            }
        }

        val clusters = validGroups.map { group ->
            val minX = group.minOf { it[0] }
            val minY = group.minOf { it[1] }
            val maxX = group.maxOf { it[2] }
            val maxY = group.maxOf { it[3] }
            Cluster(group.toMutableList(), minX, minY, maxX, maxY)
        }.toMutableList()

        while (true) {
            var bestI = -1
            var bestJ = -1
            var minUnionArea = Long.MAX_VALUE

            for (i in 0 until clusters.size) {
                for (j in i + 1 until clusters.size) {
                    if (clusters[i].canMergeWith(clusters[j], maxContextSize)) {
                        val area = clusters[i].unionAreaWith(clusters[j])
                        if (area < minUnionArea) {
                            minUnionArea = area
                            bestI = i
                            bestJ = j
                        }
                    }
                }
            }

            if (bestI != -1 && bestJ != -1) {
                clusters[bestI].merge(clusters[bestJ])
                clusters.removeAt(bestJ)
            } else {
                break
            }
        }

        return clusters.map { it.boxes }
    }
}
