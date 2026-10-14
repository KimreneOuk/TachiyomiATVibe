package eu.kanade.translation.engines.rendering

import eu.kanade.translation.model.TranslationBlockView
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private typealias GrownBox = TextLayoutPlanner.GrownBox

/**
 * Pure collision-relaxation and free-space planning used by
 * [TextLayoutPlanner]. The planner owns orchestration and delegates these
 * side-effect-free operations through private seams.
 */
internal object CollisionRelaxation {

    private const val VERTICAL_CHAR_STEP = TextLayoutPlanner.VERTICAL_CHAR_STEP
    private const val VERTICAL_COL_STEP = TextLayoutPlanner.VERTICAL_COL_STEP

    private fun cjkWrap(text: String, font: Float, maxWidth: Float, measurer: TextMeasurer): List<String> =
        TextLayoutPlanner.cjkWrap(text, font, maxWidth, measurer)

    private fun binarySearchFontSize(
        text: String,
        safeW: Float,
        safeH: Float,
        baseW: Float,
        isVertical: Boolean,
        scale: Float,
        measurer: TextMeasurer,
    ): Float = TextLayoutPlanner.binarySearchFontSize(text, safeW, safeH, baseW, isVertical, scale, measurer)

    private fun fitsAt(
        text: String,
        font: Float,
        isVertical: Boolean,
        safeW: Float,
        safeH: Float,
        measurer: TextMeasurer,
    ): Boolean = TextLayoutPlanner.fitsAt(text, font, isVertical, safeW, safeH, measurer)

    private fun columnsFor(text: String, safeH: Float, charStep: Float): Int =
        TextLayoutPlanner.columnsFor(text, safeH, charStep)

    private fun strippedLength(text: String): Int =
        TextLayoutPlanner.strippedLength(text)

    private fun minWidthForLines(
        text: String,
        font: Float,
        maxLines: Int,
        measurer: TextMeasurer,
    ): Float = TextLayoutPlanner.minWidthForLines(text, font, maxLines, measurer)

    private fun translateRect(rect: FloatRect, dx: Float, dy: Float): FloatRect =
        FloatRect(rect.left + dx, rect.top + dy, rect.right + dx, rect.bottom + dy)

    /**
     * Move a layout uniformly by ([dx], [dy]): the origin, the legacy clip rect,
     * and every positioned line (integer floor placement, per the planner's
     * rounding contract) shift together.
     */
    private fun translateLayout(layout: BlockLayout, dx: Float, dy: Float): BlockLayout =
        layout.copy(
            originX = layout.originX + dx,
            originY = layout.originY + dy,
            clipRect = layout.clipRect?.let { translateRect(it, dx, dy) },
            positionedLines = layout.positionedLines?.map { line ->
                line.copy(
                    leftPx = floor(line.leftPx + dx).toInt(),
                    topPx = floor(line.topPx + dy).toInt(),
                    conservativeOccupancy = translateRect(line.conservativeOccupancy, dx, dy),
                )
            },
            conservativeOccupancy = layout.conservativeOccupancy.map { translateRect(it, dx, dy) },
        )

    internal fun resolveMinimalDisplacementX(
        preferredX: Float,
        width: Float,
        pageWidth: Float,
        obstacles: List<FloatRect>,
        origCenterX: Float,
    ): Float {
        val minXAllowed = origCenterX - width
        val maxXAllowed = origCenterX
        val maxX = (pageWidth - width).coerceAtLeast(0f)
        var x = preferredX.coerceIn(0f, maxX).coerceIn(minXAllowed, maxXAllowed)
        // Bounded passes: each obstacle is shifted past at most once; obstacles do not move.
        var guard = 0
        while (guard < obstacles.size + 1) {
            val candidate = FloatRect(x, 0f, x + width, Float.MAX_VALUE)
            val colliding = obstacles.firstOrNull { candidate.overlaps(it) }
            if (colliding == null) return x
            val centerX = x + width / 2f
            val obsCenterX = (colliding.left + colliding.right) / 2f
            // Push toward the side of the obstacle nearer the preferred centre.
            val nextX = if (centerX < obsCenterX) {
                colliding.left - width
            } else {
                colliding.right
            }.coerceIn(0f, maxX).coerceIn(minXAllowed, maxXAllowed)

            if (nextX == x) return x
            x = nextX
            guard++
        }
        return x
    }

    /** Free horizontal space to the LEFT of [origLeft], bounded by the page left edge and the nearest obstacle. */
    private fun freeSpaceLeft(
        centerX: Float,
        origLeft: Float,
        obstacles: List<FloatRect>,
        pageWidth: Float,
    ): Float {
        var bound = 0f
        for (obs in obstacles) {
            if (obs.right <= centerX && obs.right > bound) bound = obs.right
        }
        return (origLeft - bound).coerceAtLeast(0f)
    }

    /** Free horizontal space to the RIGHT of [origRight], bounded by the page width. */
    private fun freeSpaceRight(
        centerX: Float,
        origRight: Float,
        obstacles: List<FloatRect>,
        pageWidth: Float,
    ): Float {
        var bound = pageWidth
        for (obs in obstacles) {
            if (obs.left >= centerX && obs.left < bound) bound = obs.left
        }
        return (bound - origRight).coerceAtLeast(0f)
    }

    /** Free vertical space ABOVE [origTop], bounded by the page top (0) and the nearest obstacle. */
    private fun freeSpaceVerticalUp(
        centerY: Float,
        origTop: Float,
        obstacles: List<FloatRect>,
    ): Float {
        var bound = 0f
        for (obs in obstacles) {
            if (obs.bottom <= centerY && obs.bottom > bound) bound = obs.bottom
        }
        return (origTop - bound).coerceAtLeast(0f)
    }

    /** Free vertical space BELOW [origBottom], bounded by [pageHeight] and the nearest obstacle. */
    private fun freeSpaceVerticalDown(
        centerY: Float,
        origBottom: Float,
        obstacles: List<FloatRect>,
        pageHeight: Float,
    ): Float {
        var bound = pageHeight
        for (obs in obstacles) {
            if (obs.top >= centerY && obs.top < bound) bound = obs.top
        }
        return (bound - origBottom).coerceAtLeast(0f)
    }

    /**
     * Grow the box into free space when the fit is undersized (below the legibility
     * floor) or the text overflows. Growth is only into the larger-free side,
     * clamped so it never crosses an obstacle or the page edge — overflow is
     * redirected into open space, never at a neighbour. Returns the possibly
     * unchanged box + a refit font.
     */
    internal fun growIntoFreeSpaceIfNeeded(
        text: String,
        isVertical: Boolean,
        baseX: Float,
        baseY: Float,
        baseW: Float,
        baseH: Float,
        safePad: Float,
        obstacles: List<FloatRect>,
        pageWidth: Float,
        pageHeight: Float,
        minLegible: Float,
        currentFont: Float,
        measurer: TextMeasurer,
        regionConstraint: FloatRect? = null,
    ): GrownBox {
        var bx = baseX
        var by = baseY
        var bw = baseW
        var bh = baseH
        var sw = max(1f, bw - safePad * 2f)
        var sh = max(1f, bh - safePad * 2f)
        var font = currentFont
        var grewRight = false
        var grewLeft = false

        val targetFont = max(minLegible, currentFont)
        val needsGrowth = font < minLegible || overflows(text, font, isVertical, sw, sh, measurer)
        if (!needsGrowth) {
            return GrownBox(bx, by, bw, bh, sw, sh, font, grewRight, grewLeft)
        }

        if (!isVertical) {
            val isHorizontalAspect = bw >= bh
            if (isHorizontalAspect) {
                // (a) For horizontal/wide bubbles (e.g. Webtoons / LTR dialogue): grow WIDTH first
                // into the larger free side to wrap into wider proportional lines.
                val lineH = measurer.lineHeight(targetFont)
                val maxLines = max(1, (sh / lineH).toInt())
                val safeLeft = bx + safePad
                val safeRight = bx + bw - safePad
                val centerX = bx + bw / 2f
                var freeLeft = freeSpaceLeft(centerX, safeLeft, obstacles, pageWidth)
                var freeRight = freeSpaceRight(centerX, safeRight, obstacles, pageWidth)
                if (regionConstraint != null) {
                    freeLeft = min(freeLeft, (safeLeft - regionConstraint.left).coerceAtLeast(0f))
                    freeRight = min(freeRight, (regionConstraint.right - safeRight).coerceAtLeast(0f))
                }

                val neededW = minWidthForLines(text, targetFont, maxLines, measurer)
                val widthGrowthNeeded = (neededW - sw).coerceAtLeast(0f)
                if (widthGrowthNeeded > 0f) {
                    if (freeRight >= freeLeft) {
                        bw += min(freeRight, widthGrowthNeeded)
                        grewRight = true
                    } else {
                        val g = min(freeLeft, widthGrowthNeeded)
                        bx -= g
                        bw += g
                        grewLeft = true
                    }
                    sw = max(1f, bw - safePad * 2f)
                }

                // (b) Grow HEIGHT only for residual line wrapping within headroom
                val centerY = by + bh / 2f
                val safeTop = by + safePad
                val safeBottom = by + bh - safePad
                var freeUp = freeSpaceVerticalUp(centerY, safeTop, obstacles)
                var freeDown = freeSpaceVerticalDown(centerY, safeBottom, obstacles, pageHeight)
                if (regionConstraint != null) {
                    freeUp = min(freeUp, (safeTop - regionConstraint.top).coerceAtLeast(0f))
                    freeDown = min(freeDown, (regionConstraint.bottom - safeBottom).coerceAtLeast(0f))
                }

                val baseLines = max(1, (sh / lineH).toInt())
                val maxLinesByHeight = max(baseLines, ((sh + max(freeUp, freeDown)) / lineH).toInt())
                val fitLines = (baseLines..maxLinesByHeight).firstOrNull { n ->
                    minWidthForLines(text, targetFont, n, measurer) <= sw
                }
                val targetLines = fitLines ?: maxLinesByHeight
                val heightGrowthNeeded = (targetLines * lineH - sh).coerceAtLeast(0f)
                if (heightGrowthNeeded > 0f) {
                    if (freeDown >= freeUp) {
                        bh += min(freeDown, heightGrowthNeeded)
                    } else {
                        val g = min(freeUp, heightGrowthNeeded)
                        by -= g
                        bh += g
                    }
                    sh = max(1f, bh - safePad * 2f)
                }
            } else {
                // (a) Tall/vertical boxes (Japanese vertical source text converted to horizontal English):
                // Grow HEIGHT first (taller ⇒ more wrap lines ⇒ narrower), then WIDTH
                val lineH = measurer.lineHeight(targetFont)
                val centerY = by + bh / 2f
                val safeTop = by + safePad
                val safeBottom = by + bh - safePad
                var freeUp = freeSpaceVerticalUp(centerY, safeTop, obstacles)
                var freeDown = freeSpaceVerticalDown(centerY, safeBottom, obstacles, pageHeight)
                if (regionConstraint != null) {
                    freeUp = min(freeUp, (safeTop - regionConstraint.top).coerceAtLeast(0f))
                    freeDown = min(freeDown, (regionConstraint.bottom - safeBottom).coerceAtLeast(0f))
                }

                val baseLines = max(1, (sh / lineH).toInt())
                val maxLinesByHeight = max(baseLines, ((sh + max(freeUp, freeDown)) / lineH).toInt())
                val swBeforeGrowth = sw
                val fitLines = (baseLines..maxLinesByHeight).firstOrNull { n ->
                    minWidthForLines(text, targetFont, n, measurer) <= swBeforeGrowth
                }
                val targetLines = fitLines ?: maxLinesByHeight
                val heightGrowthNeeded = (targetLines * lineH - sh).coerceAtLeast(0f)
                if (heightGrowthNeeded > 0f) {
                    if (freeDown >= freeUp) {
                        bh += min(freeDown, heightGrowthNeeded)
                    } else {
                        val g = min(freeUp, heightGrowthNeeded)
                        by -= g
                        bh += g
                    }
                    sh = max(1f, bh - safePad * 2f)
                }

                // (b) WIDTH for residual overflow
                val maxLines = max(1, (sh / lineH).toInt())
                val safeLeft = bx + safePad
                val safeRight = bx + bw - safePad
                val centerX = bx + bw / 2f
                var freeLeft = freeSpaceLeft(centerX, safeLeft, obstacles, pageWidth)
                var freeRight = freeSpaceRight(centerX, safeRight, obstacles, pageWidth)
                if (regionConstraint != null) {
                    freeLeft = min(freeLeft, (safeLeft - regionConstraint.left).coerceAtLeast(0f))
                    freeRight = min(freeRight, (regionConstraint.right - safeRight).coerceAtLeast(0f))
                }

                val neededW = minWidthForLines(text, targetFont, maxLines, measurer)
                val widthGrowthNeeded = (neededW - sw).coerceAtLeast(0f)
                if (widthGrowthNeeded > 0f) {
                    if (freeRight >= freeLeft) {
                        bw += min(freeRight, widthGrowthNeeded)
                        grewRight = true
                    } else {
                        val g = min(freeLeft, widthGrowthNeeded)
                        bx -= g
                        bw += g
                        grewLeft = true
                    }
                    sw = max(1f, bw - safePad * 2f)
                }
            }
        } else {
            // Vertical text layout
            val safeTop = by + safePad
            val safeBottom = by + bh - safePad
            val centerY = by + bh / 2f
            var freeUp = freeSpaceVerticalUp(centerY, safeTop, obstacles)
            var freeDown = freeSpaceVerticalDown(centerY, safeBottom, obstacles, pageHeight)
            if (regionConstraint != null) {
                freeUp = min(freeUp, (safeTop - regionConstraint.top).coerceAtLeast(0f))
                freeDown = min(freeDown, (regionConstraint.bottom - safeBottom).coerceAtLeast(0f))
            }

            val charStep = targetFont * VERTICAL_CHAR_STEP
            val colStep = targetFont * VERTICAL_COL_STEP
            val colsAtCurrent = columnsFor(text, sh, charStep)
            val colWidthNow = colsAtCurrent * colStep
            val growthNeeded = if (colWidthNow > sw) {
                // Height needed to drop one column.
                val chars = strippedLength(text)
                val colsTarget = (colsAtCurrent - 1).coerceAtLeast(1)
                val charsPerCol = ceil(chars.toFloat() / colsTarget).toInt().coerceAtLeast(1)
                (charsPerCol * charStep - sh).coerceAtLeast(0f)
            } else {
                0f
            }
            if (growthNeeded > 0f) {
                if (freeDown >= freeUp) {
                    bh += min(freeDown, growthNeeded)
                } else {
                    val g = min(freeUp, growthNeeded)
                    by -= g
                    bh += g
                }
                sh = max(1f, bh - safePad * 2f)
            }
        }

        font = binarySearchFontSize(text, sw, sh, bw, isVertical, scale = 1f, measurer = measurer)
        // Honour the floor: prefer the legible size over the fit result when the
        // grown box can finally accommodate it.
        if (font < minLegible && fitsAt(text, targetFont, isVertical, sw, sh, measurer)) {
            font = targetFont
        }
        return GrownBox(bx, by, bw, bh, sw, sh, font, grewRight, grewLeft)
    }

    /** True when the rendered text at [font] would exceed the safe rect. */
    internal fun overflows(
        text: String,
        font: Float,
        isVertical: Boolean,
        safeW: Float,
        safeH: Float,
        measurer: TextMeasurer,
    ): Boolean {
        if (isVertical) {
            val charStep = font * VERTICAL_CHAR_STEP
            val colStep = font * VERTICAL_COL_STEP
            val cols = columnsFor(text, safeH, charStep)
            return cols * colStep > safeW + 0.5f
        }
        val lines = cjkWrap(text, font, safeW, measurer)
        val lineH = measurer.lineHeight(font)
        if (lines.size * lineH > safeH + 0.5f) return true
        return lines.any { measurer.measureTextWidth(it, font) > safeW + 0.5f }
    }
}
