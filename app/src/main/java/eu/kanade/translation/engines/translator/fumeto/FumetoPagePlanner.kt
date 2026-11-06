package eu.kanade.translation.engines.translator.fumeto

/**
 * Page-mode window planner, ported 1:1 from the fumetotranslate reference
 * `run.py:windows`. A page whose lines fit the encoder window is encoded once;
 * longer pages are cut into windows of consecutive lines, widened with their
 * neighbours so every line decodes against some context.
 *
 * @param lo window lines [lo, hi); targets are the decoded lines [targetLo, targetHi).
 */
internal data class FumetoWindow(val lo: Int, val hi: Int, val targetLo: Int, val targetHi: Int)

internal object FumetoPagePlanner {

    /**
     * Each line costs its token count + 1 (the separator). A page fitting
     * `budget + 1` total is one window; otherwise targets claim at most half
     * the budget and the window grows around them while it fits.
     */
    fun windows(lineTokenCounts: IntArray, budget: Int): List<FumetoWindow> {
        val n = lineTokenCounts.size
        val cost = IntArray(n) { lineTokenCounts[it] + 1 }
        if (cost.sum() + 1 <= budget) return listOf(FumetoWindow(0, n, 0, n))

        val out = ArrayList<FumetoWindow>()
        var i = 0
        while (i < n) {
            var j = i
            var c = 0
            while (j < n && (j == i || c + cost[j] <= budget / 2)) {
                c += cost[j]
                j++
            }
            var lo = i
            var hi = j
            var total = c + 1
            var grew = true
            while (grew) {
                grew = false
                if (hi < n && total + cost[hi] <= budget) {
                    total += cost[hi]
                    hi++
                    grew = true
                }
                if (lo > 0 && total + cost[lo - 1] <= budget) {
                    total += cost[lo - 1]
                    lo--
                    grew = true
                }
            }
            out.add(FumetoWindow(lo, hi, i, j))
            i = j
        }
        return out
    }
}
