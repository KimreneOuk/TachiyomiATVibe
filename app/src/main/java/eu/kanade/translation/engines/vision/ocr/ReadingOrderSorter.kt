package eu.kanade.translation.engines.vision.ocr

/**
 * Sorts detected panels into reading order using XY-cut.
 *
 * Each panel is a `FloatArray` of `[x1, y1, x2, y2]`. The sort recursively
 * splits the panel set along the widest empty gutter (horizontal cut first,
 * then vertical), falling back to column-by-centre when panels mutually
 * overlap (no gutter >= 1px).
 *
 * `rtl` is an explicit parameter: `true` for right-to-left manga (right
 * column first), `false` for LTR comic/webtoon. The caller resolves the
 * preference once and passes it in, keeping this object pure/JVM-testable.
 */
internal object ReadingOrderSorter {

    internal fun readingOrderPanels(panels: List<FloatArray>, rtl: Boolean): List<FloatArray> {
        if (panels.size <= 1) return panels
        val indices = xyCutOrder(panels, panels.indices.toList(), rtl)
        return indices.map { panels[it] }
    }

    internal fun xyCutOrder(panels: List<FloatArray>, idxList: List<Int>, rtl: Boolean): List<Int> {
        if (idxList.size <= 1) return idxList
        val hCut = widestGutterSplit(panels, idxList, axis = 'y')
        if (hCut != null) {
            val (top, bottom) = hCut
            return xyCutOrder(panels, top, rtl) + xyCutOrder(panels, bottom, rtl)
        }
        val vCut = widestGutterSplit(panels, idxList, axis = 'x')
        if (vCut != null) {
            val (left, right) = vCut
            return if (rtl) {
                xyCutOrder(panels, right, rtl) + xyCutOrder(panels, left, rtl)
            } else {
                xyCutOrder(panels, left, rtl) + xyCutOrder(panels, right, rtl)
            }
        }
        return columnFallbackOrder(panels, idxList, rtl)
    }

    internal fun widestGutterSplit(
        panels: List<FloatArray>,
        idxList: List<Int>,
        axis: Char,
    ): Pair<List<Int>, List<Int>>? {
        val events = ArrayList<FloatArray>(idxList.size * 2)
        for (i in idxList) {
            val p = panels[i]
            val lo = if (axis == 'y') p[1] else p[0]
            val hi = if (axis == 'y') p[3] else p[2]
            events.add(floatArrayOf(lo, 1f))
            events.add(floatArrayOf(hi, -1f))
        }
        events.sortBy { it[0] * 2f - it[1] }
        var bestStart = Float.NaN
        var bestWidth = 1f
        var count = 0
        var prev = Float.NaN
        var bestFound = false
        for (e in events) {
            if (count == 0 && !prev.isNaN()) {
                val w = e[0] - prev
                if (w >= 1f && w > bestWidth) {
                    bestWidth = w
                    bestStart = prev
                    bestFound = true
                }
            }
            count += e[1].toInt()
            prev = e[0]
        }
        if (!bestFound) return null
        val cut = bestStart + bestWidth / 2f
        val a = ArrayList<Int>()
        val b = ArrayList<Int>()
        for (i in idxList) {
            val p = panels[i]
            val c = if (axis == 'y') (p[1] + p[3]) / 2f else (p[0] + p[2]) / 2f
            if (c <= cut) a.add(i) else b.add(i)
        }
        if (a.isEmpty() || b.isEmpty()) return null
        return a to b
    }

    internal fun columnFallbackOrder(
        panels: List<FloatArray>,
        idxList: List<Int>,
        rtl: Boolean,
    ): List<Int> {
        val byCx = if (rtl) {
            compareByDescending<Int> { (panels[it][0] + panels[it][2]) / 2f }
        } else {
            compareBy<Int> { (panels[it][0] + panels[it][2]) / 2f }
        }
        return idxList.sortedWith(byCx.thenBy { panels[it][1] })
    }
}
