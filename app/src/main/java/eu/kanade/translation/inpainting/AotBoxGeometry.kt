package eu.kanade.translation.inpainting

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
        val textArea = ((textBox[2] - textBox[0]).coerceAtLeast(1) *
            (textBox[3] - textBox[1]).coerceAtLeast(1)).toFloat()
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
}
