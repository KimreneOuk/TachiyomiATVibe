package eu.kanade.translation.detection

/**
 * TachiyomiAT: panel-aware translation context.
 *
 * Pure geometry: assigns a text/bubble box to the panel that contains the
 * largest fraction of its area (max-containment), then categorises the
 * assignment so downstream translation code knows how much to trust it.
 *
 * Conservative policy — no fallback, no nearest-panel guessing:
 *  - [OWNED]       containment >= [OWNED_THRESHOLD] -> confident panel ownership
 *  - [SPANNING] [SPAN_LO] <= containment < [OWNED_THRESHOLD] -> crosses a gutter
 *  - [FREE_FLOATING] containment < [SPAN_LO] AND >= 1 panel exists on the page
 *  - [ORPHAN]      text exists but the page has 0 panels (broken/full-bleed page)
 *  - [INVALID]     degenerate input geometry (zero/negative area, NaN)
 *
 * FREE_FLOATING and ORPHAN text are surfaced to the translator as page-level
 * context, never silently attached to the nearest panel (that would poison
 * speaker/pronoun inference — the exact failure mode this feature fixes).
 *
 * Every degenerate / uncertain case is logged by the caller
 * ([RoiPageRecognitionEngine]); nothing is silently dropped.
 */
object PanelAssignment {

    /** containment >= this -> confident ownership (matches tools/ eval: 0.80). */
    const val OWNED_THRESHOLD = 0.80f

    /** [SPAN_LO, OWNED_THRESHOLD) -> crosses a panel gutter (matches tools/ eval: 0.10). */
    const val SPAN_LO = 0.10f

    fun interface Category {
        fun asString(): String
        companion object {
            val OWNED = Category { "owned" }
            val SPANNING = Category { "spanning" }
            val FREE_FLOATING = Category { "free_floating" }
            val ORPHAN = Category { "orphan" }
            val INVALID = Category { "invalid" }
            val NONE = Category { "none" }
        }
    }

    /** Result of assigning one text/bubble box to the page's panels. */
    data class Result(
        /** Reading-order panel index, or null when not confidently owned. */
        val panelIndex: Int?,
        /** Original-detector-order panel index of the best candidate (advisory). */
        val bestPanelIdxRaw: Int?,
        /** Containment fraction of the best candidate in [0,1]. */
        val bestContainment: Float,
        val category: Category,
    )

    /** No panels exist on the page -> every text box is orphan (or invalid). */
    fun noPanels(boxValid: Boolean): Result = Result(
        panelIndex = null,
        bestPanelIdxRaw = null,
        bestContainment = 0f,
        category = if (boxValid) Category.ORPHAN else Category.INVALID,
    )

    /**
     * Assign textX1..textY2 (original-image coordinates) to the panel in
     * [panels] (reading-order indices) with max containment. Panels are
     * [x1, y1, x2, y2] in the same coordinate space.
     *
     * Returns [Result] with the appropriate category. Degenerate text or panel
     * geometry short-circuits to INVALID — callers MUST log it; nothing is
     * silently coerced into a confident assignment.
     */
    fun assign(
        textX1: Float,
        textY1: Float,
        textX2: Float,
        textY2: Float,
        panels: List<FloatArray>,
    ): Result {
        if (!isValidBox(textX1, textY1, textX2, textY2)) {
            return Result(null, null, 0f, Category.INVALID)
        }
        if (panels.isEmpty()) return noPanels(boxValid = true)

        val textArea = area(textX1, textY1, textX2, textY2)
        var bestIdx = -1
        var bestCont = 0f
        for (i in panels.indices) {
            val p = panels[i]
            if (!isValidBox(p[0], p[1], p[2], p[3])) continue
            val inter = intersectionArea(
                textX1,
                textY1,
                textX2,
                textY2,
                p[0],
                p[1],
                p[2],
                p[3],
            )
            if (inter <= 0f) continue
            val cont = if (textArea > 0f) inter / textArea else 0f
            if (cont > bestCont) {
                bestCont = cont
                bestIdx = i
            }
        }
        if (bestIdx < 0) {
            // Text outside every panel; panels exist -> free-floating.
            return Result(null, null, 0f, Category.FREE_FLOATING)
        }
        val cat = when {
            bestCont >= OWNED_THRESHOLD -> Category.OWNED
            bestCont >= SPAN_LO -> Category.SPANNING
            else -> Category.FREE_FLOATING
        }
        return Result(
            panelIndex = if (cat === Category.OWNED) bestIdx else null,
            bestPanelIdxRaw = bestIdx,
            bestContainment = bestCont,
            category = cat,
        )
    }

    fun isValidBox(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        if (x1.isNaN() || y1.isNaN() || x2.isNaN() || y2.isNaN()) return false
        if (x2 - x1 <= 0f) return false
        if (y2 - y1 <= 0f) return false
        return true
    }

    fun area(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val w = x2 - x1
        val h = y2 - y1
        return if (w > 0f && h > 0f) w * h else 0f
    }

    fun intersectionArea(
        ax1: Float,
        ay1: Float,
        ax2: Float,
        ay2: Float,
        bx1: Float,
        by1: Float,
        bx2: Float,
        by2: Float,
    ): Float {
        val ix1 = maxOf(ax1, bx1)
        val iy1 = maxOf(ay1, by1)
        val ix2 = minOf(ax2, bx2)
        val iy2 = minOf(ay2, by2)
        val w = ix2 - ix1
        val h = iy2 - iy1
        return if (w > 0f && h > 0f) w * h else 0f
    }
}
