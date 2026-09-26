package eu.kanade.translation.engines.rendering

import eu.kanade.translation.model.TranslationBlock
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private typealias FinalResolution = TextLayoutPlanner.FinalResolution
private typealias FreeRectCandidate = TextLayoutPlanner.FreeRectCandidate
private typealias RescueBudget = TextLayoutPlanner.RescueBudget
private typealias GrownBox = TextLayoutPlanner.GrownBox
private typealias FreeTextWideningPlan = TextLayoutPlanner.FreeTextWideningPlan
private typealias SharedCellPlan = TextLayoutPlanner.SharedCellPlan

/**
 * Pure collision-relaxation and free-space planning algorithms extracted from
 * [TextLayoutPlanner]. The planner remains the owner of orchestration and
 * delegates these side-effect-free spans through the same private seams.
 */
internal object CollisionRelaxation {

    private const val FIT_MIN_FONT_PX = TextLayoutPlanner.FIT_MIN_FONT_PX
    private const val VERTICAL_CHAR_STEP = TextLayoutPlanner.VERTICAL_CHAR_STEP
    private const val VERTICAL_COL_STEP = TextLayoutPlanner.VERTICAL_COL_STEP

    private fun maskedShiftCap(block: TranslationBlock, pageWidth: Float, pageHeight: Float): Float =
        TextLayoutPlanner.maskedShiftCap(block, pageWidth, pageHeight)

    private fun relocationWithinCap(
        entryX: Float,
        entryY: Float,
        candidateX: Float,
        candidateY: Float,
        cap: Float,
    ): Boolean = TextLayoutPlanner.relocationWithinCap(entryX, entryY, candidateX, candidateY, cap)

    private fun conservativeOccupancyOf(
        layout: BlockLayout,
        measurer: TextMeasurer,
        scale: Float,
        gapHalf: Float,
    ): FloatRect = TextLayoutPlanner.conservativeOccupancyOf(layout, measurer, scale, gapHalf)

    private fun inkRectOf(layout: BlockLayout, measurer: TextMeasurer): FloatRect =
        TextLayoutPlanner.inkRectOf(layout, measurer)

    private fun containedIn(inner: FloatRect, outer: FloatRect): Boolean =
        TextLayoutPlanner.containedIn(inner, outer)

    private fun paintEnvelopeContainedInComponent(
        layout: BlockLayout,
        cellPlan: SharedCellPlan?,
        measurer: TextMeasurer,
        scale: Float,
    ): Boolean = TextLayoutPlanner.paintEnvelopeContainedInComponent(layout, cellPlan, measurer, scale)

    private fun adaptiveBlockLayout(
        block: TranslationBlock,
        text: String,
        slab: FloatRect,
        adaptive: AdaptiveResult,
        scale: Float,
    ): BlockLayout = TextLayoutPlanner.adaptiveBlockLayout(block, text, slab, adaptive, scale)

    private fun placeBlock(
        block: TranslationBlock,
        text: String,
        isVertical: Boolean,
        rect: RectResult,
        obstacles: List<FloatRect>,
        pageWidth: Float,
        pageHeight: Float,
        minLegible: Float,
        scale: Float,
        measurer: TextMeasurer,
        regionOverride: FloatRect?,
        allowGrowth: Boolean = true,
    ): BlockLayout = TextLayoutPlanner.placeBlock(
        block,
        text,
        isVertical,
        rect,
        obstacles,
        pageWidth,
        pageHeight,
        minLegible,
        scale,
        measurer,
        regionOverride,
        allowGrowth,
    )

    private fun computeStrokeWidth(fontSizePx: Float, scale: Float): Float =
        TextLayoutPlanner.computeStrokeWidth(fontSizePx, scale)

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

    private fun computeRects(
        block: TranslationBlock,
        sampleSize: Int,
        regionOverride: FloatRect? = null,
    ): RectResult = TextLayoutPlanner.computeRects(block, sampleSize, regionOverride)

    private fun graphemeClusters(text: String): List<String> =
        TextLayoutPlanner.graphemeClusters(text)

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

    private fun hardCellsDisjoint(a: FloatRect?, b: FloatRect?): Boolean =
        a != null && b != null && !a.overlaps(b)

    /** Occupancy collision of one candidate against the accepted set (rule 2). */
    private fun footprintCollides(
        occupancy: FloatRect,
        hardCell: FloatRect?,
        placed: List<PlacedFootprint>,
    ): Boolean {
        for (footprint in placed) {
            if (hardCellsDisjoint(hardCell, footprint.cellRect)) continue
            if (occupancy.overlaps(footprint.occupancy)) return true
        }
        return false
    }

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

    /** Directional shift index: 0 left, 1 right, 2 up, 3 down (fixed candidate order). */
    private val SHIFT_LEFT = 0
    private val SHIFT_RIGHT = 1
    private val SHIFT_UP = 2
    private val SHIFT_DOWN = 3

    /**
     * Smallest displacement in [direction] whose translated [occupancy] no longer
     * overlaps ANY applicable accepted occupancy, computed from the inflated
     * occupancies with a bounded fixed-point pass (each pass may clear further
     * footprints revealed by the move; validation re-checks the full set, so a
     * non-converged value can only fail a candidate, never over-accept).
     */
    private fun minShiftDisplacement(
        direction: Int,
        occupancy: FloatRect,
        hardCell: FloatRect?,
        placed: List<PlacedFootprint>,
    ): Float {
        var d = 0f
        var pass = 0
        val maxPasses = placed.size + 1
        while (pass < maxPasses) {
            pass++
            val moved = translateRect(
                occupancy,
                when (direction) {
                    SHIFT_LEFT -> -d
                    SHIFT_RIGHT -> d
                    else -> 0f
                },
                when (direction) {
                    SHIFT_UP -> -d
                    SHIFT_DOWN -> d
                    else -> 0f
                },
            )
            var need = d
            for (footprint in placed) {
                if (hardCellsDisjoint(hardCell, footprint.cellRect)) continue
                val other = footprint.occupancy
                if (!moved.overlaps(other)) continue
                // Additional displacement that separates `moved` from `other` on
                // this direction's axis (moving fully past the near edge).
                val clear = when (direction) {
                    SHIFT_LEFT -> moved.right - other.left
                    SHIFT_RIGHT -> other.right - moved.left
                    SHIFT_UP -> moved.bottom - other.top
                    else -> other.bottom - moved.top
                }
                if (clear > 0f) need = max(need, d + clear)
            }
            if (need <= d + 0.001f) return d
            d = need
        }
        return d
    }

    /**
     * The bounded final post-anchor resolution for one colliding block (it is by
     * construction the lower-priority one): at most
     * [TextLayoutTuning.MAX_FINAL_PLACEMENT_ATTEMPTS] evaluated candidates, in
     * the deterministic order — (1) the selected final geometry re-validated
     * as-is; (2) next smaller font (adaptive: band refit with a one-step reduced
     * font cap; legacy: same box, font one step smaller); (3) the original/cell
     * baseline geometry (legacy: pre-growth base rect via [placeBlock]
     * `allowGrowth = false`; adaptive: the stroke-inset rectangular cell
     * fallback); (4-7) minimum legal left/right/up/down shift; (8) one hard
     * disjoint clip/refit candidate into the best of the four axis-aligned free
     * rectangles around the current occupancy. Duplicates after clamping are
     * skipped without counting; the FIRST validating candidate wins.
     *
     *  repair (R2): the ladder NEVER drops the block. When every candidate
     * fails validation, a clipped draw is accepted (visibility override —
     * placement safety may never remove text from the page). Unmasked layouts
     * retain the original candidate-8 free-rect fallback.
     *
     *  contained-fit rescue (Director-validated model): on the masked
     * branch, candidate 1 — a known duplicate collision failure whenever the
     * resolver was entered (slice-7 review NOTE 3) — is replaced by the
     * OCR-box-first tiered [containedReflowRescue]. If that rescue is
     * collision-free it wins immediately; otherwise it is retained and, when
     * every capped candidate also fails, returned as the terminal result WITH
     * overlap (contained-and-legible beats exile or clipping). Only when no
     * contained fit exists at the render floor does the tail fall back to the
     * unshifted entry geometry with [BlockLayout.maskUsable] cleared: the
     * metadata wiring then skips the exact component clip, so the text stays
     * fully visible instead of being sheared by a mask that cannot host it.
     * The fallback is not an extra evaluated candidate — the cap stays at
     * [TextLayoutTuning.MAX_FINAL_PLACEMENT_ATTEMPTS].
     */
    internal fun resolvePostAnchorPlacement(
        layout: BlockLayout,
        occupancy: FloatRect,
        hardCell: FloatRect?,
        block: TranslationBlock,
        text: String,
        isVertical: Boolean,
        rect: RectResult,
        regionOverride: FloatRect?,
        obstacles: List<FloatRect>,
        adaptive: AdaptiveResult?,
        cellPlan: SharedCellPlan?,
        placed: List<PlacedFootprint>,
        positionedLinesUsed: Int,
        staticLayoutsUsed: Int,
        collisionGap: Int,
        gapHalf: Float,
        pageWidth: Float,
        pageHeight: Float,
        minLegible: Float,
        scale: Float,
        sampleSize: Int,
        measurer: TextMeasurer,
        rescueBudget: RescueBudget,
        precomputedRescue: BlockLayout?,
    ): FinalResolution {
        // Shift and free-rectangle constraint bounds: page ∩ hard cell slab when
        // one exists (component/cell containment is structural), else the page.
        val bounds = if (hardCell != null) {
            FloatRect(
                max(0f, hardCell.left),
                max(0f, hardCell.top),
                min(pageWidth, hardCell.right),
                min(pageHeight, hardCell.bottom),
            )
        } else {
            FloatRect(0f, 0f, pageWidth, pageHeight)
        }
        val ink = inkRectOf(layout, measurer)
        val maskedShiftCap = if (block.segmentationMask != null) {
            maskedShiftCap(block, pageWidth, pageHeight)
        } else {
            Float.POSITIVE_INFINITY
        }
        //  contained-fit rescue: precomputed by the placement loop for
        // EVERY masked block (containment-first — the rescue is attempted
        // before any shift machinery, and its cached result is reused here so
        // the band-fit budget is never double-spent). Not a counted candidate
        // — it replaces masked candidate 1 and serves as the terminal
        // contained-with-overlap result. Null means the rescue was attempted
        // and found nothing usable (no ceiling / no contained fit / budget
        // exhausted) — the ladder then runs and the fail-open tail applies.
        val containedRescue = precomputedRescue
        val originalLines = layout.positionedLines?.size ?: 0
        val fitMinFont = FIT_MIN_FONT_PX * scale

        fun pageBudgetAllows(candidate: BlockLayout): Boolean {
            val k = candidate.positionedLines?.size ?: 0
            return positionedLinesUsed - originalLines + k <=
                TextLayoutTuning.MAX_POSITIONED_LINES_PER_PAGE &&
                staticLayoutsUsed - 2 * originalLines + 2 * k <=
                TextLayoutTuning.MAX_STATIC_LAYOUTS_PER_PAGE
        }

        fun validates(candidate: BlockLayout): Boolean {
            val candidateInk = inkRectOf(candidate, measurer)
            // Containment: box inside the page, and inside the hard cell when the
            // block carries one (hard-clip containment kept).
            if (candidateInk.left < 0f ||
                candidateInk.top < 0f ||
                candidateInk.right > pageWidth ||
                candidateInk.bottom > pageHeight
            ) {
                return false
            }
            if (hardCell != null && !containedIn(candidateInk, hardCell)) return false
            if (!paintEnvelopeContainedInComponent(candidate, cellPlan, measurer, scale)) return false
            if (!pageBudgetAllows(candidate)) return false
            val candidateOccupancy = conservativeOccupancyOf(candidate, measurer, scale, gapHalf)
            return !footprintCollides(candidateOccupancy, hardCell, placed)
        }

        var attempts = 0
        val evaluated = ArrayList<BlockLayout>()

        fun tryCandidate(candidate: BlockLayout?): BlockLayout? {
            if (candidate == null) return null
            // Duplicates after clamping are skipped and do NOT create
            // replacements; only evaluated candidates advance the counter.
            if (evaluated.any { it == candidate }) return null
            evaluated.add(candidate)
            attempts++
            if (validates(candidate)) return candidate
            return null
        }

        // Candidate 1: the selected final geometry re-validated as-is (unmasked).
        // Masked: the entry layout is the known collision failure whenever the
        // resolver was entered (slice-7 review NOTE 3) — the tiered contained
        // reflow rescue takes this candidate slot instead. When the rescue
        // itself still collides, the ladder continues with it retained for the
        // tail (contained overlap beats exile/clipping).
        if (block.segmentationMask != null && containedRescue != null) {
            tryCandidate(containedRescue)?.let { return FinalResolution(it, attempts) }
        } else {
            tryCandidate(layout)?.let { return FinalResolution(it, attempts) }
        }

        // Candidate 2: next smaller font, one step.
        val smallerFont: BlockLayout? = if (layout.positionedLines != null) {
            val slab = cellPlan?.slab
            val adaptiveFit = adaptive
            if (slab != null && adaptiveFit != null && adaptiveFit.fontPx - 1f >= fitMinFont) {
                AdaptiveBandPlanner.fitAdaptiveBands(
                    text = text,
                    cellSpans = cellPlan.spans,
                    slab = slab,
                    scale = scale,
                    collisionGapPx = collisionGap,
                    measurer = measurer,
                    minFontPx = fitMinFont,
                    maxFontPx = adaptiveFit.fontPx - 1f,
                    blockCenterX = block.x + block.width / 2f,
                    blockCenterY = block.y + block.height / 2f,
                )?.let { adaptiveBlockLayout(block, text, slab, it, scale) }
            } else {
                null
            }
        } else {
            val smaller = layout.fontSizePx - 1f
            if (smaller >= fitMinFont) {
                layout.copy(
                    fontSizePx = smaller,
                    strokeWidth = computeStrokeWidth(smaller, scale),
                    lines = if (isVertical) emptyList() else cjkWrap(text, smaller, layout.safeW, measurer),
                )
            } else {
                null
            }
        }
        tryCandidate(smallerFont)?.let { return FinalResolution(it, attempts) }

        // Candidate 3: the original/cell baseline geometry.
        val baseline: BlockLayout? = if (layout.positionedLines != null) {
            // Adaptive: the conservative stroke-inset rectangular cell layout —
            // the exact slice-5 fallback call.
            placeBlock(
                block = block,
                text = text,
                isVertical = isVertical,
                rect = rect,
                obstacles = obstacles,
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                minLegible = minLegible,
                scale = scale,
                measurer = measurer,
                regionOverride = regionOverride,
            )
        } else {
            // Legacy: the pre-growth base rect through the same fit/anchor/clip path.
            placeBlock(
                block = block,
                text = text,
                isVertical = isVertical,
                rect = rect,
                obstacles = obstacles,
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                minLegible = minLegible,
                scale = scale,
                measurer = measurer,
                regionOverride = regionOverride,
                allowGrowth = false,
            )
        }
        tryCandidate(baseline)?.let { return FinalResolution(it, attempts) }

        // Candidates 4-7: minimum legal left/right/up/down shift of the selected
        // geometry, displacements computed from the inflated occupancies and
        // clamped so the moved box stays within page/component/cell bounds.
        for (direction in intArrayOf(SHIFT_LEFT, SHIFT_RIGHT, SHIFT_UP, SHIFT_DOWN)) {
            val maxDisplacement = when (direction) {
                SHIFT_LEFT -> ink.left - bounds.left
                SHIFT_RIGHT -> bounds.right - ink.right
                SHIFT_UP -> ink.top - bounds.top
                else -> bounds.bottom - ink.bottom
            }
            if (maxDisplacement <= 0f) continue // blocked by the bound: no legal move
            val requiredUnclamped = minShiftDisplacement(direction, occupancy, hardCell, placed)
            val required = if (block.segmentationMask != null) {
                // A partial move is already known not to clear the obstacle.
                if (requiredUnclamped > maxDisplacement + 0.001f ||
                    requiredUnclamped > maskedShiftCap + 0.001f
                ) {
                    continue
                }
                requiredUnclamped
            } else {
                // Compatibility: preserve the exact legacy unmasked decision.
                requiredUnclamped.coerceAtMost(maxDisplacement)
            }
            if (required <= 0f) continue // clamps to a no-op: duplicate of candidate 1
            val shifted = when (direction) {
                SHIFT_LEFT -> translateLayout(layout, -required, 0f)
                SHIFT_RIGHT -> translateLayout(layout, required, 0f)
                SHIFT_UP -> translateLayout(layout, 0f, -required)
                else -> translateLayout(layout, 0f, required)
            }
            tryCandidate(shifted)?.let { return FinalResolution(it, attempts) }
        }

        // Candidate 8: one hard disjoint clip/refit candidate. The four
        // axis-aligned free rectangles around the current occupancy within
        // [bounds], each cut back past applicable accepted occupancies (keeping
        // the part adjacent to this block; exempt hard-cell pairs do not cut —
        // their paint is structurally separated). Choose maximum area, then
        // shortest displacement, then fixed order left/right/up/down.
        val freeRects = ArrayList<FreeRectCandidate>(4)
        for (direction in intArrayOf(SHIFT_LEFT, SHIFT_RIGHT, SHIFT_UP, SHIFT_DOWN)) {
            val candidate = when (direction) {
                SHIFT_LEFT -> {
                    // Strip [bounds.left, occupancy.left] x occupancy y-range:
                    // cut back past every applicable occupancy that REACHES into
                    // it (overlaps its y-range, protrudes left of this block,
                    // and pokes past the current cut); one that straddles this
                    // block's own left edge degenerates the strip.
                    var x1 = bounds.left
                    for (footprint in placed) {
                        if (hardCellsDisjoint(hardCell, footprint.cellRect)) continue
                        val other = footprint.occupancy
                        if (other.top < occupancy.bottom &&
                            occupancy.top < other.bottom &&
                            other.left < occupancy.left &&
                            other.right > x1
                        ) {
                            x1 = other.right
                        }
                    }
                    if (x1 < occupancy.left - 0.001f && occupancy.bottom > occupancy.top) {
                        FloatRect(x1, occupancy.top, occupancy.left, occupancy.bottom)
                    } else {
                        null
                    }
                }
                SHIFT_RIGHT -> {
                    var x2 = bounds.right
                    for (footprint in placed) {
                        if (hardCellsDisjoint(hardCell, footprint.cellRect)) continue
                        val other = footprint.occupancy
                        if (other.top < occupancy.bottom &&
                            occupancy.top < other.bottom &&
                            other.right > occupancy.right &&
                            other.left < x2
                        ) {
                            x2 = other.left
                        }
                    }
                    if (x2 > occupancy.right + 0.001f && occupancy.bottom > occupancy.top) {
                        FloatRect(occupancy.right, occupancy.top, x2, occupancy.bottom)
                    } else {
                        null
                    }
                }
                SHIFT_UP -> {
                    var y1 = bounds.top
                    for (footprint in placed) {
                        if (hardCellsDisjoint(hardCell, footprint.cellRect)) continue
                        val other = footprint.occupancy
                        if (other.left < occupancy.right &&
                            occupancy.left < other.right &&
                            other.top < occupancy.top &&
                            other.bottom > y1
                        ) {
                            y1 = other.bottom
                        }
                    }
                    if (y1 < occupancy.top - 0.001f && occupancy.right > occupancy.left) {
                        FloatRect(occupancy.left, y1, occupancy.right, occupancy.top)
                    } else {
                        null
                    }
                }
                else -> {
                    var y2 = bounds.bottom
                    for (footprint in placed) {
                        if (hardCellsDisjoint(hardCell, footprint.cellRect)) continue
                        val other = footprint.occupancy
                        if (other.left < occupancy.right &&
                            occupancy.left < other.right &&
                            other.bottom > occupancy.bottom &&
                            other.top < y2
                        ) {
                            y2 = other.top
                        }
                    }
                    if (y2 > occupancy.bottom + 0.001f && occupancy.right > occupancy.left) {
                        FloatRect(occupancy.left, occupancy.bottom, occupancy.right, y2)
                    } else {
                        null
                    }
                }
            }
            if (candidate != null && candidate.width() > 0f && candidate.height() > 0f) {
                freeRects.add(FreeRectCandidate(candidate, direction))
            }
        }
        var clipCandidate: BlockLayout? = null
        if (freeRects.isNotEmpty()) {
            // Maximum area, then shortest displacement (into the adjacent free
            // rectangle), then fixed order left/right/up/down.
            val chosen = freeRects.sortedWith(
                compareByDescending<FreeRectCandidate> { it.rect.width() * it.rect.height() }
                    .thenBy { freeRectDisplacement(it.rect, it.direction, occupancy) }
                    .thenBy { it.direction },
            ).first()
            clipCandidate = fitIntoFreeRect(
                block = block,
                text = text,
                isVertical = isVertical,
                freeRect = chosen.rect,
                scale = scale,
                gapHalf = gapHalf,
                measurer = measurer,
            )
            val refitWithinCap = block.segmentationMask == null ||
                relocationWithinCap(
                    layout.originX,
                    layout.originY,
                    clipCandidate.originX,
                    clipCandidate.originY,
                    maskedShiftCap,
                )
            if (refitWithinCap) {
                tryCandidate(clipCandidate)?.let { return FinalResolution(it, attempts) }
            }
        }

        //  repair (R2): ladder exhausted → accept a CLIPPED DRAW, never
        // `NonDraw(NO_DISJOINT_POST_ANCHOR_PLACEMENT)` (visibility override:
        // placement safety may never remove text from the page).
        // Unmasked layouts preserve the prior candidate-8 free-rect fallback.
        //
        //  contained-fit tail, in priority order:
        //  1. A computed contained rescue exists → return it even though its
        //     conservative occupancy overlaps another block: fully contained,
        //     complete text in its own bubble beats exile or shrinking, and
        //     the never-drop contract already permits overlapping text. No new
        //     clipRect: the rescue is exact-contained, so the component/cell
        //     painter clips can only remove an AA fringe.
        //  2. No contained fit at the floor → MASK-USABLE=false: fail open to
        //     the unshifted resolver-entry geometry. Metadata wiring skips the
        //     exact component clip (visibility wins over a mask that cannot
        //     host the text) and the own-ink clip keeps the page bound only.
        // The fallback is NOT an extra evaluated candidate: the cap stays at
        // [TextLayoutTuning.MAX_FINAL_PLACEMENT_ATTEMPTS].
        return FinalResolution(
            if (block.segmentationMask == null) {
                // Compatibility: retain the pre-repair visibility override for
                // ordinary/unmasked layouts.
                clipCandidate ?: layout.copy(clipRect = inkRectOf(layout, measurer))
            } else if (containedRescue != null) {
                containedRescue
            } else {
                layout.copy(
                    clipRect = inkRectOf(layout, measurer),
                    maskUsable = false,
                )
            },
            attempts,
        )
    }

    /** Displacement needed to move the occupancy fully into an adjacent free rect. */
    private fun freeRectDisplacement(rect: FloatRect, direction: Int, occupancy: FloatRect): Float =
        when (direction) {
            SHIFT_LEFT -> occupancy.right - rect.right
            SHIFT_RIGHT -> rect.left - occupancy.left
            SHIFT_UP -> occupancy.bottom - rect.bottom
            else -> rect.top - occupancy.bottom
        }

    /**
     * The hard disjoint clip/refit layout: fit [text] ONLY inside [freeRect]
     * (bounded binary font search over the box minus the conservative inflation,
     * two fixed-point passes so the estimated inflation matches the fitted font),
     * centered in the rectangle with `clipRect = freeRect` — the rectangular
     * hard clip that makes the separation structural for unmasked layouts.
     */
    private fun fitIntoFreeRect(
        block: TranslationBlock,
        text: String,
        isVertical: Boolean,
        freeRect: FloatRect,
        scale: Float,
        gapHalf: Float,
        measurer: TextMeasurer,
    ): BlockLayout {
        fun inflationAt(font: Float): Float =
            computeStrokeWidth(font, scale) + TextLayoutTuning.aaGuard(scale) + gapHalf

        var inflate = inflationAt(FIT_MIN_FONT_PX * scale)
        var font = FIT_MIN_FONT_PX * scale
        var pass = 0
        while (pass < 2) {
            pass++
            val boxW = (freeRect.width() - 2f * inflate).coerceAtLeast(1f)
            val boxH = (freeRect.height() - 2f * inflate).coerceAtLeast(1f)
            font = binarySearchFontSize(text, boxW, boxH, boxW, isVertical, scale, measurer)
            val next = inflationAt(font)
            if (abs(next - inflate) <= 0.001f) break
            inflate = next
        }
        val boxW = (freeRect.width() - 2f * inflate).coerceAtLeast(1f)
        val boxH = (freeRect.height() - 2f * inflate).coerceAtLeast(1f)
        return BlockLayout(
            block = block,
            text = text,
            isVertical = isVertical,
            originX = freeRect.left + freeRect.width() / 2f,
            originY = freeRect.top + freeRect.height() / 2f,
            safeW = boxW,
            safeH = boxH,
            fontSizePx = font,
            strokeWidth = computeStrokeWidth(font, scale),
            drawAlign = TextAlign.CENTER,
            clipRect = freeRect,
            lines = if (isVertical) emptyList() else cjkWrap(text, font, boxW, measurer),
        )
    }

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

    internal fun boundedFreeTextWideningPlan(
        block: TranslationBlock,
        text: String,
        isVertical: Boolean,
        sampleSize: Int,
        obstacles: List<FloatRect>,
        pageWidth: Float,
        pageHeight: Float,
        minLegible: Float,
        scale: Float,
        measurer: TextMeasurer,
    ): FreeTextWideningPlan? {
        if (block.segmentationMask != null) return null // path precondition (see doc)
        if (block.label != 2) return null
        if (block.parentWidth > 0f && block.parentHeight > 0f) return null
        if (isVertical) return null
        val graphemes = graphemeClusters(text).count { !it.isBlank() }
        if (graphemes < TextLayoutTuning.FREE_TEXT_MIN_GRAPHEMES) return null
        if (block.width <= 0f || block.height / block.width < TextLayoutTuning.FREE_TEXT_TALL_RATIO) return null

        // Baseline: the UNRESHAPED original OCR box. computeRects with the OCR
        // rect as the region override yields exactly the parentless padding and
        // safe dims the legacy path computes — minus the tall-box reshape.
        val baseline = computeRects(
            block,
            sampleSize,
            FloatRect(block.x, block.y, block.x + block.width, block.y + block.height),
        )
        val baselineFont = binarySearchFontSize(
            text,
            baseline.safeW,
            baseline.safeH,
            baseline.baseW,
            false,
            scale,
            measurer,
        )
        val baselineOverflows = overflows(text, baselineFont, false, baseline.safeW, baseline.safeH, measurer)
        val hasFitPressure = baselineFont < minLegible ||
            overflows(text, minLegible, false, baseline.safeW, baseline.safeH, measurer)
        if (!hasFitPressure) return null

        val centerX = block.x + block.width / 2f
        val bandTop = block.y
        val bandBottom = block.y + block.height
        val pageShortSide = min(pageWidth, pageHeight)
        val pageClampedWidth = 2f * min(centerX, pageWidth - centerX)
        val collisionFreeWidth = collisionFreeWidthForBand(centerX, bandTop, bandBottom, pageWidth, obstacles)

        var previousWidth = Float.NaN
        for (factor in listOf(TextLayoutTuning.FREE_TEXT_WIDEN_FACTOR_1, TextLayoutTuning.FREE_TEXT_WIDEN_FACTOR_2)) {
            val width = minOf(
                block.width * factor,
                block.width + TextLayoutTuning.FREE_TEXT_MAX_PAGE_ADD_FRACTION * pageShortSide,
                pageClampedWidth,
                collisionFreeWidth,
            )
            // Not a widening trial, or clamped to the same width as a previous
            // candidate: deduplicate with the documented 0.01 px epsilon.
            if (width - block.width <= TextLayoutTuning.FREE_TEXT_WIDTH_EPSILON) continue
            if (!previousWidth.isNaN() && abs(width - previousWidth) <= TextLayoutTuning.FREE_TEXT_WIDTH_EPSILON) {
                continue
            }
            previousWidth = width
            val region = FloatRect(
                centerX - width / 2f,
                block.y,
                centerX + width / 2f,
                block.y + block.height,
            )
            val candidate = computeRects(block, sampleSize, region)
            val font = binarySearchFontSize(
                text,
                candidate.safeW,
                candidate.safeH,
                candidate.baseW,
                false,
                scale,
                measurer,
            )
            val qualifies = font >= TextLayoutTuning.FREE_TEXT_MIN_FONT_GAIN * baselineFont ||
                (baselineOverflows && !overflows(text, font, false, candidate.safeW, candidate.safeH, measurer))
            if (qualifies) return FreeTextWideningPlan(candidate, region)
        }
        // No candidate qualified: keep the unreshaped OCR baseline with no
        // region override (today's exact label-2 legacy behavior).
        return FreeTextWideningPlan(baseline, null)
    }

    /**
     *  slice 6: widest symmetric box centered at [centerX] that stays on
     * the page and does not overlap any already-placed obstacle extent whose
     * vertical extent overlaps the candidate's band `[bandTop, bandBottom)`.
     * Extends the [freeSpaceLeft]/[freeSpaceRight] bound scan with that band
     * filter; deterministic in [obstacles] order.
     */
    private fun collisionFreeWidthForBand(
        centerX: Float,
        bandTop: Float,
        bandBottom: Float,
        pageWidth: Float,
        obstacles: List<FloatRect>,
    ): Float {
        var leftBound = 0f
        var rightBound = pageWidth
        for (obs in obstacles) {
            if (!(obs.top < bandBottom && bandTop < obs.bottom)) continue
            if (obs.left < centerX && obs.right > centerX) return 0f // straddles the center
            if (obs.right <= centerX && obs.right > leftBound) leftBound = obs.right
            if (obs.left >= centerX && obs.left < rightBound) rightBound = obs.left
        }
        return 2f * min(
            (centerX - leftBound).coerceAtLeast(0f),
            (rightBound - centerX).coerceAtLeast(0f),
        )
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
