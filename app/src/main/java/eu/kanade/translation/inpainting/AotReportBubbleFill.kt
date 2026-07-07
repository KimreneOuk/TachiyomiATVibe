package eu.kanade.translation.inpainting

import kotlin.math.max
import kotlin.math.min

internal object AotReportBubbleFill {

    internal fun reportBubbleFill(
        pixels: IntArray,
        mask: ByteArray,
        width: Int,
        height: Int,
        smoothPasses: Int,
    ) {
        val visited = BooleanArray(mask.size)
        val queue = ArrayDeque<Int>()
        for (start in mask.indices) {
            if (mask[start] == 0.toByte() || visited[start]) continue
            visited[start] = true
            queue.clear()
            queue.add(start)
            val component = ArrayList<Int>()
            var minX = width
            var minY = height
            var maxX = -1
            var maxY = -1
            while (queue.isNotEmpty()) {
                val idx = queue.removeFirst()
                component.add(idx)
                val x = idx % width
                val y = idx / width
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 0 until width || ny !in 0 until height) continue
                        val ni = ny * width + nx
                        if (mask[ni] != 0.toByte() && !visited[ni]) {
                            visited[ni] = true
                            queue.add(ni)
                        }
                    }
                }
            }
            if (component.isEmpty()) continue
            val ox1 = max(0, minX - 2)
            val oy1 = max(0, minY - 2)
            val ox2 = min(width, maxX + 3)
            val oy2 = min(height, maxY + 3)
            val median = medianRingColor(pixels, ox1, oy1, ox2, oy2, minX, minY, maxX + 1, maxY + 1, width)
            for (idx in component) pixels[idx] = median
            smoothMaskedComponent(pixels, mask, component, width, height, smoothPasses)
        }
    }

    internal fun medianRingColor(
        pixels: IntArray,
        ox1: Int,
        oy1: Int,
        ox2: Int,
        oy2: Int,
        ix1: Int,
        iy1: Int,
        ix2: Int,
        iy2: Int,
        width: Int,
    ): Int {
        val histR = IntArray(256)
        val histG = IntArray(256)
        val histB = IntArray(256)
        var count = 0
        for (y in oy1 until oy2) {
            val row = y * width
            for (x in ox1 until ox2) {
                if (x in ix1 until ix2 && y in iy1 until iy2) continue
                val p = pixels[row + x]
                histR[(p shr 16) and 0xFF]++
                histG[(p shr 8) and 0xFF]++
                histB[p and 0xFF]++
                count++
            }
        }
        if (count == 0) return 0xFFFFFFFF.toInt()
        val r = AotPixelOps.histogramMedian(histR, count)
        val g = AotPixelOps.histogramMedian(histG, count)
        val b = AotPixelOps.histogramMedian(histB, count)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    internal fun smoothMaskedComponent(
        pixels: IntArray,
        mask: ByteArray,
        component: List<Int>,
        width: Int,
        height: Int,
        passes: Int,
    ) {
        val scratch = IntArray(component.size)
        for (pass in 0 until passes) {
            for (i in component.indices) {
                val idx = component[i]
                val x = idx % width
                val y = idx / width
                val up = if (y > 0) idx - width else idx
                val down = if (y < height - 1) idx + width else idx
                val left = if (x > 0) idx - 1 else idx
                val right = if (x < width - 1) idx + 1 else idx
                scratch[i] = AotPixelOps.avg4(pixels[up], pixels[down], pixels[left], pixels[right])
            }
            for (i in component.indices) {
                val idx = component[i]
                if (mask[idx] != 0.toByte()) pixels[idx] = scratch[i]
            }
        }
    }
}
