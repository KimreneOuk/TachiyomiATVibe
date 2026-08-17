package eu.kanade.translation.rendering

import eu.kanade.translation.model.TranslationBlock
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * TachiyomiAT: pure text-measurement abstraction.
 *
 * Layout math needs font metrics that only a real [android.graphics.Paint] can
 * provide, which is Android-bound and not unit-testable (the project avoids
 * Robolectric). Injecting measurement behind this interface keeps
 * [TextLayoutPlanner] JVM-testable: tests pass a deterministic fake, the renderer
 * passes [PaintTextMeasurer]. Same pure-decision / Android-I/O split as
 * [RenderColorEstimator].
 */
interface TextMeasurer {
    /** Visual width of [text] rendered at [fontSizePx], in the planner's pixel units. */
    fun measureTextWidth(text: String, fontSizePx: Float): Float

    /**
     * Full line height (`fontMetrics.descent - fontMetrics.ascent`) at [fontSizePx],
     * matching the renderer's legacy stacking height.
     */
    fun lineHeight(fontSizePx: Float): Float

    fun antiAliasInset(fontSizePx: Float): Float = 0f
}

/**
 * TachiyomiAT: pure axis-aligned float rectangle.
 *
 * Deliberately NOT `android.graphics.RectF` — keeps the planner JVM-testable with
 * no `android.graphics` dependency. The renderer converts to `RectF` for clipping.
 */
data class FloatRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun width(): Float = right - left
    fun height(): Float = bottom - top

    /** Positive-area intersection test. Touching edges (zero area) is NOT an overlap. */
    fun overlaps(other: FloatRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun intersection(other: FloatRect): FloatRect = FloatRect(
        max(left, other.left),
        max(top, other.top),
        min(right, other.right),
        min(bottom, other.bottom),
    )
}

/**
 * TachiyomiAT: horizontal text anchoring for a [BlockLayout].
 *  - [CENTER]: centred on [BlockLayout.originX] — default for non-grown boxes.
 *  - [LEFT]: left-aligned at [BlockLayout.originX] — used when a box grew RIGHT
 *    into free space (original left edge stays the anchor) and by the clip net.
 *  - [RIGHT]: right-aligned at [BlockLayout.originX] — used when a box grew LEFT,
 *    so parentless edge SFX do not slide toward the page centre.
 */
enum class TextAlign { CENTER, LEFT, RIGHT }

/**
 * TachiyomiAT: the resolved placement for a single translated block, produced by
 * [TextLayoutPlanner.plan]. The renderer only DRAWS these — it does no further layout.
 *
 * @property originX horizontal draw anchor whose meaning depends on [drawAlign]:
 *   CENTER ⇒ box centre; LEFT ⇒ (clip or growth) left edge; RIGHT ⇒ growth right edge.
 * @property originY vertical center the renderer stacks lines around.
 * @property drawAlign how the text is anchored horizontally at [originX].
 * @property clipRect non-null ONLY when, after growing + shrinking, the block still
 *   cannot avoid a neighbour. The renderer clips to this rect so extents can never
 *   overlap — the safety net making non-overlap structural, not best-effort.
 */
data class BlockLayout(
    val block: TranslationBlock,
    val text: String,
    val isVertical: Boolean,
    val originX: Float,
    val originY: Float,
    val safeW: Float,
    val safeH: Float,
    val fontSizePx: Float,
    val strokeWidth: Float,
    val drawAlign: TextAlign,
    val clipRect: FloatRect?,
    val lines: List<String>,
    val maskGeometry: eu.kanade.translation.segmentation.MaskGeometry? = null,
    val maskKey: String? = null,
    val maskComponentId: Int? = null,
)

/**
 * TachiyomiAT: pure, neighbour-aware text-layout solver for the render stage.
 *
 * **Why this exists.** [PageTextRenderer] used to lay each block out independently
 * with no global view, causing three defects: (1) collision — close boxes' extents
 * overlapped; (2) forced horizontal-long — a tall parentless box was widened up to
 * 3.5× and symmetrically re-centred, ignoring text length and neighbours; (3) too
 * small — the font-fit bottomed at ~8 px with no legibility floor.
 *
 * This object plans the WHOLE page at once: it reuses the tuned per-block math
 * ([computeRects], [binarySearchFontSize], [cjkWrap]) as the initial fit, then a
 * free-space-aware pass re-anchors reshaped boxes away from their nearest neighbour
 * and grows overflowing/undersized boxes toward the page edge so overflow never
 * points at a neighbour. A legibility floor ([minLegibleFont]) prevents the ~8 px
 * collapse, and a clip safety-net guarantees the structural invariant that no two
 * rendered extents overlap.
 *
 * Pure & side-effect-free (no `Bitmap`/`Canvas`/ONNX); all font-dependent
 * measurement goes through the injected [TextMeasurer], so it is unit-testable
 * with a deterministic fake.
 *
 * **Ordering.** Blocks are placed highest-[TranslationBlock.score] first: the most
 * confident box takes its preferred placement and lower-confidence boxes treat it
 * as a fixed obstacle. Ties keep reading order (stable sort).
 */
object TextLayoutPlanner {

    // Reshape (tall parentless box → wider) — mirrors the legacy tuned math.
    /** A parentless box taller than this × its width is reshaped to a wider rect. */
    private const val RESHAPE_TALL_RATIO = 2.0f
    private const val RESHAPE_MIN_WIDTH_FACTOR = 1.5f
    private const val RESHAPE_MAX_WIDTH_FACTOR = 3.5f

    private const val VERTICAL_CHAR_STEP = 1.05f
    private const val VERTICAL_COL_STEP = 1.25f

    private const val FIT_MAX_FONT_PX = 72f
    private const val FIT_MIN_FONT_PX = 8f
    private const val FIT_START_WIDTH_FACTOR = 1.5f

    // Outline width — single source of truth (see [computeStrokeWidth]).
    private const val STROKE_WIDTH_FRACTION = 0.12f
    private const val MIN_STROKE_PX = 2f

    /**
     * Legibility floor. The legacy fit could collapse to ~8 px (unreadable). The
     * floor is the larger of an absolute minimum and a fraction of the smaller
     * page dimension, so it scales with resolution. Invisible for normal text
     * (fits at 36–72 px); only rescues genuinely-oversized-text bubbles.
     */
    private const val LEGIBLE_FONT_FRACTION = 0.014f
    private const val LEGIBLE_FONT_ABS_PX = 14f

    /** Min visible gap kept between two placed extents, in planner px. */
    private const val MIN_GAP_PX = 2f

    /**
     * Resolve a render plan for every block on the page. Blocks whose chosen text
     * is blank are dropped here so they do not needlessly constrain neighbours as
     * obstacles (the renderer would skip them anyway).
     */
    fun plan(
        blocks: List<TranslationBlock>,
        pageWidth: Float,
        pageHeight: Float,
        sampleSize: Int,
        renderSourceText: Boolean,
        measurer: TextMeasurer,
    ): List<BlockLayout> {
        if (blocks.isEmpty()) return emptyList()
        val scale = 1f / sampleSize
        val minLegible = minLegibleFont(pageWidth, pageHeight, scale)

        // Highest score first: most confident box places first and becomes a fixed
        // obstacle for lower-score boxes; ties keep reading order for determinism.
        val ordered = blocks.withIndex().sortedWith(
            compareByDescending<IndexedValue<TranslationBlock>> { it.value.score }
                .thenBy { it.index },
        )

        val maskRegions = buildMaskRegions(blocks)
        val placed = ArrayList<BlockLayout>(blocks.size)
        for (indexed in ordered) {
            val block = indexed.value
            val text = chosenText(block, renderSourceText)
            if (text.isBlank()) continue

            val maskRegion = maskRegions[indexed.index]
            val rect = computeRects(block, sampleSize, maskRegion)
            if (rect.safeW < 1f || rect.safeH < 1f) continue

            val isVertical = block.direction == "TTB" && shouldRenderVertical(text)
            val placedObstacles = placed.map { extentOf(it, measurer) }

            val resolved = placeBlock(
                block = block,
                text = text,
                isVertical = isVertical,
                rect = rect,
                obstacles = placedObstacles,
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                minLegible = minLegible,
                scale = scale,
                measurer = measurer,
                regionOverride = maskRegion,
            )
            placed.add(resolved)
        }
        return equalizeSharedMaskFonts(placed, measurer, scale)
    }

    /** Legibility floor for a page of [pageWidth]×[pageHeight] at decode [scale]. */
    internal fun minLegibleFont(pageWidth: Float, pageHeight: Float, scale: Float): Float =
        max(LEGIBLE_FONT_ABS_PX * scale, min(pageWidth, pageHeight) * LEGIBLE_FONT_FRACTION)

    private fun chosenText(block: TranslationBlock, renderSourceText: Boolean): String =
        if (renderSourceText) block.translation.ifBlank { block.text } else block.translation

    /**
     * Place one block, treating already-finalised higher-score [obstacles] as fixed.
     * Steps: (1) re-anchor a reshaped tall box away from the nearest obstacle /
     * toward the larger free side (Defect 2); (2) fit font, then if undersized or
     * overflowing (Defects 1 & 3) grow the box into the larger free side (never
     * toward an obstacle) and re-fit; (3) if a residual overlap remains, clip this
     * block to its own side of the obstacle boundary (the no-overlap guarantee).
     */
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
    ): BlockLayout {
        var baseX = rect.baseX
        var baseY = rect.baseY
        var baseW = rect.baseW
        var baseH = rect.baseH
        val safePad = max(0f, (baseW - rect.safeW) / 2f)

        val hasParent = regionOverride == null && block.parentWidth > 0f && block.parentHeight > 0f
        val anchorToOcrCenter = regionOverride != null ||
            hasParent ||
            block.label == 2 ||
            (block.direction == "TTB" && !isVertical)
        val region = if (regionOverride != null) {
            regionOverride
        } else if (hasParent) {
            FloatRect(
                block.parentX,
                block.parentY,
                block.parentX + block.parentWidth,
                block.parentY + block.parentHeight,
            )
        } else if (rect.reshaped) {
            FloatRect(0f, 0f, pageWidth, pageHeight)
        } else {
            FloatRect(block.x, block.y, block.x + block.width, block.y + block.height)
        }

        // (1) Re-anchor a reshaped tall box by MINIMAL DISPLACEMENT: start from the
        // legacy symmetric placement; if it collides with an obstacle or page edge,
        // shift the smallest distance that resolves it. Isolated boxes keep the
        // legacy position (no regression); only nudges on a real collision.
        if (rect.reshaped) {
            val origCenterX = rect.origLeft + (rect.origRight - rect.origLeft) / 2f
            baseX = resolveMinimalDisplacementX(
                preferredX = origCenterX - baseW / 2f,
                width = baseW,
                pageWidth = pageWidth,
                obstacles = obstacles,
                origCenterX = origCenterX,
            )
        }

        var safeW = max(1f, baseW - safePad * 2f)
        var safeH = max(1f, baseH - safePad * 2f)
        var fontSize = binarySearchFontSize(text, safeW, safeH, baseW, isVertical, scale, measurer)

        val grown = growIntoFreeSpaceIfNeeded(
            text = text,
            isVertical = isVertical,
            baseX = baseX,
            baseY = baseY,
            baseW = baseW,
            baseH = baseH,
            safePad = safePad,
            obstacles = obstacles,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            minLegible = minLegible,
            currentFont = fontSize,
            measurer = measurer,
        )
        baseW = min(grown.baseW, region.width())
        baseH = min(grown.baseH, region.height())
        baseX = grown.baseX.coerceIn(region.left, region.right - baseW)
        baseY = grown.baseY.coerceIn(region.top, region.bottom - baseH)
        safeW = max(1f, baseW - safePad * 2f)
        safeH = max(1f, baseH - safePad * 2f)
        fontSize = binarySearchFontSize(text, safeW, safeH, baseW, isVertical, scale, measurer)

        var strokeWidth = computeStrokeWidth(fontSize, scale)
        var originY = baseY + baseH / 2f
        // Pin the original edge of a box that grew horizontally so parentless edge
        // text (e.g. SFX) does not drift toward the page centre: grew-right ⇒ LEFT
        // at the left edge; grew-left ⇒ RIGHT at the right edge. A required clip
        // (below) overrides this to LEFT at its own left edge.
        var originX: Float
        var drawAlign: TextAlign
        val centeredOriginX = baseX + baseW / 2f
        val edgeAnchorWouldAvoidOverlap = !isVertical &&
            centeredExtentOverlapsObstacle(
                text = text,
                fontSize = fontSize,
                safeW = safeW,
                originX = centeredOriginX,
                originY = originY,
                obstacles = obstacles,
                measurer = measurer,
            )
        when {
            grown.grewRight && edgeAnchorWouldAvoidOverlap -> {
                originX = baseX
                drawAlign = TextAlign.LEFT
            }
            grown.grewLeft && edgeAnchorWouldAvoidOverlap -> {
                originX = baseX + baseW
                drawAlign = TextAlign.RIGHT
            }
            else -> {
                originX = centeredOriginX
                drawAlign = TextAlign.CENTER
            }
        }
        var clipRect: FloatRect? = null

        // (3) Clip safety-net: if the final extent still overlaps an obstacle, clip
        // to this block's own side of the obstacle. Reached only when free space is
        // exhausted; guarantees the no-overlap invariant.
        val safeLeft = baseX + safePad
        val safeTop = baseY + safePad
        val safeRight = baseX + baseW - safePad
        val safeBottom = baseY + baseH - safePad
        var clip = FloatRect(safeLeft, safeTop, safeRight, safeBottom)
        var neededClip = false
        for (obs in obstacles) {
            if (!clip.overlaps(obs)) continue
            val inter = clip.intersection(obs)
            // Keep the half of `clip` on this block's centre side, removing exactly
            // the overlapping sliver.
            val centerX = baseX + baseW / 2f
            clip = if (centerX < (obs.left + obs.right) / 2f) {
                // Block is LEFT of the obstacle → drop the right sliver.
                FloatRect(clip.left, clip.top, min(clip.right, inter.left), clip.bottom)
            } else {
                FloatRect(max(clip.left, inter.right), clip.top, clip.right, clip.bottom)
            }
            neededClip = true
        }
        if (regionOverride == null && neededClip && clip.width() > MIN_GAP_PX && clip.height() > MIN_GAP_PX) {
            clipRect = clip
            if (isVertical) {
                originX = clip.left + clip.width() / 2f
            } else {
                originX = clip.left
                drawAlign = TextAlign.LEFT
            }
            originY = clip.top + clip.height() / 2f
            safeW = clip.width()
            safeH = clip.height()

            val fitFont = binarySearchFontSize(
                text,
                safeW,
                safeH,
                safeW,
                isVertical,
                scale,
                measurer,
            )
            // Skip the legibility floor so text fits the clipped bounds instead of
            // being cut off.
            fontSize = fitFont
        }

        // (C) Containment clip: fall back to clipping at the region boundary when
        // text still overflows it.
        if (overflows(text, fontSize, isVertical, region.width(), region.height(), measurer)) {
            val r = if (clipRect != null) clipRect.intersection(region) else region
            if (r.width() > MIN_GAP_PX && r.height() > MIN_GAP_PX) {
                clipRect = r
                if (isVertical) {
                    originX = r.left + r.width() / 2f
                } else {
                    originX = r.left
                    drawAlign = TextAlign.LEFT
                }
                originY = r.top + r.height() / 2f
                safeW = r.width()
                safeH = r.height()

                val fitFont = binarySearchFontSize(
                    text,
                    safeW,
                    safeH,
                    safeW,
                    isVertical,
                    scale,
                    measurer,
                )
                fontSize = fitFont
            }
        }

        if (regionOverride != null) {
            val centerX = block.x + block.width / 2f
            val centerY = block.y + block.height / 2f
            originX = centerX
            originY = centerY
            drawAlign = TextAlign.CENTER

            // The OCR centre may be off-centre inside a fused mask slice. Fit to
            // the smaller side so centred text cannot be clipped on either edge.
            var maskClip = FloatRect(
                region.left + strokeWidth / 2f,
                region.top + strokeWidth / 2f,
                region.right - strokeWidth / 2f,
                region.bottom - strokeWidth / 2f,
            )
            repeat(2) {
                safeW = max(1f, 2f * min(centerX - maskClip.left, maskClip.right - centerX))
                safeH = max(1f, 2f * min(centerY - maskClip.top, maskClip.bottom - centerY))
                fontSize = binarySearchFontSize(
                    text,
                    safeW,
                    safeH,
                    safeW,
                    isVertical,
                    scale,
                    measurer,
                )
                strokeWidth = computeStrokeWidth(fontSize, scale)
                maskClip = FloatRect(
                    region.left + strokeWidth / 2f,
                    region.top + strokeWidth / 2f,
                    region.right - strokeWidth / 2f,
                    region.bottom - strokeWidth / 2f,
                )
            }
            clipRect = maskClip
        }

        // Free text / parented translations remain visually attached to the OCR
        // region even when the fitted container is larger than that source box.
        if (anchorToOcrCenter) {
            originX = block.x + block.width / 2f
            originY = block.y + block.height / 2f
            drawAlign = TextAlign.CENTER
        }

        return BlockLayout(
            block = block,
            text = text,
            isVertical = isVertical,
            originX = originX,
            originY = originY,
            safeW = safeW,
            safeH = safeH,
            fontSizePx = fontSize,
            strokeWidth = strokeWidth,
            drawAlign = drawAlign,
            clipRect = clipRect,
            lines = if (isVertical) emptyList() else cjkWrap(text, fontSize, safeW, measurer),
        )
    }

    /**
     * Place a box of [width] horizontally by minimal displacement from [preferredX].
     * On-page bounds are clamped first; an overlapping obstacle is resolved by
     * pushing the box to the side of the obstacle nearer its preferred centre. When
     * a shift would go off-page or into another obstacle, the move is clamped and
     * the residual overlap is left for the clip safety-net (placeBlock step 3).
     *
     * This is the Defect 2 fix: an isolated box keeps its preferred (centred)
     * position — no regression — and only moves when a real collision forces it.
     */
    private fun resolveMinimalDisplacementX(
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

    /**
     * Grow the box into free space when the fit is undersized (below the legibility
     * floor) or the text overflows. Growth is only into the larger-free side,
     * clamped so it never crosses an obstacle or the page edge — overflow is
     * redirected into open space, never at a neighbour. Returns the possibly
     * unchanged box + a refit font.
     */
    private fun growIntoFreeSpaceIfNeeded(
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
            // Grow HEIGHT first (taller ⇒ more wrap lines ⇒ narrower), then WIDTH
            // for any residual, each into its larger free side so a box never
            // crosses an obstacle.
            val lineH = measurer.lineHeight(targetFont)
            val centerY = by + bh / 2f
            val safeTop = by + safePad
            val safeBottom = by + bh - safePad
            val freeUp = freeSpaceVerticalUp(centerY, safeTop, obstacles)
            val freeDown = freeSpaceVerticalDown(centerY, safeBottom, obstacles, pageHeight)

            // (a) HEIGHT — reserve at least one line at the floor font (a box too
            // short for one line could never reach the legibility floor no matter
            // how wide, collapsing the fit to ~8 px — Defect 3). Then grow so text
            // wraps into more lines (taller, narrower) instead of widening: pick
            // the smallest line count within headroom whose wrapped width fits; if
            // none fits, use all headroom and let the WIDTH step handle the residual.
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

            // (b) WIDTH — grow only for residual overflow into the larger free
            // side; record which side so placeBlock can re-anchor at the original edge.
            val maxLines = max(1, (sh / lineH).toInt())
            val safeLeft = bx + safePad
            val safeRight = bx + bw - safePad
            val centerX = bx + bw / 2f
            val freeLeft = freeSpaceLeft(centerX, safeLeft, obstacles, pageWidth)
            val freeRight = freeSpaceRight(centerX, safeRight, obstacles, pageWidth)
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
        } else {
            // Vertical: growing HEIGHT fits more glyphs per column ⇒ fewer columns
            // ⇒ narrower footprint, relieving horizontal neighbour pressure.
            val safeTop = by + safePad
            val safeBottom = by + bh - safePad
            val centerY = by + bh / 2f
            val freeUp = freeSpaceVerticalUp(centerY, safeTop, obstacles)
            val freeDown = freeSpaceVerticalDown(centerY, safeBottom, obstacles, pageHeight)

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

    private data class GrownBox(
        val baseX: Float,
        val baseY: Float,
        val baseW: Float,
        val baseH: Float,
        val safeW: Float,
        val safeH: Float,
        val fontSize: Float,
        // Which horizontal side the box grew into (false when it did not grow
        // horizontally). Drives the placeBlock re-anchor so a grown box stays
        // pinned at its original edge instead of drifting toward page centre.
        val grewRight: Boolean,
        val grewLeft: Boolean,
    )

    private fun freeSpaceVerticalUp(centerY: Float, origTop: Float, obstacles: List<FloatRect>): Float {
        var bound = 0f
        for (obs in obstacles) {
            if (obs.bottom <= centerY && obs.bottom > bound) bound = obs.bottom
        }
        return (origTop - bound).coerceAtLeast(0f)
    }

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

    /** True when the rendered text at [font] would exceed the safe rect. */
    private fun overflows(
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

    /** True when the text fits the safe rect at [font] exactly (no overflow). */
    private fun fitsAt(
        text: String,
        font: Float,
        isVertical: Boolean,
        safeW: Float,
        safeH: Float,
        measurer: TextMeasurer,
    ): Boolean = !overflows(text, font, isVertical, safeW, safeH, measurer)

    /** Number of vertical columns the stripped text occupies at [charStep] in [safeH]. */
    private fun columnsFor(text: String, safeH: Float, charStep: Float): Int {
        val chars = strippedLength(text)
        if (chars == 0) return 1
        val maxChars = max(1, (safeH / charStep).toInt())
        return ceil(chars.toFloat() / maxChars).toInt().coerceAtLeast(1)
    }

    private fun strippedLength(text: String): Int =
        text.count { it != '\r' && it != '\n' && it != ' ' }

    /**
     * Smallest wrap width at [font] that fits [text] into at most [maxLines] lines,
     * by binary search over `[widestAtomicToken, singleLineWidth]`. CJK glyphs break
     * anywhere so the floor is small; Latin words are atomic so the floor is the
     * widest single word. Returns the single-line width when [maxLines] == 1.
     */
    private fun minWidthForLines(
        text: String,
        font: Float,
        maxLines: Int,
        measurer: TextMeasurer,
    ): Float {
        val tokens = tokenize(text)
        val fullW = measurer.measureTextWidth(text, font).coerceAtLeast(1f)
        if (maxLines <= 1) return fullW
        val minWordW = tokens.maxOfOrNull { measurer.measureTextWidth(it, font) }?.coerceAtLeast(1f) ?: fullW
        if (cjkWrap(text, font, minWordW, measurer).size <= maxLines) return minWordW
        var lo = minWordW
        var hi = fullW
        var ans = fullW
        var guard = 0
        while (lo <= hi && guard < 24) {
            val mid = (lo + hi) / 2f
            if (cjkWrap(text, font, mid, measurer).size <= maxLines) {
                ans = mid
                hi = mid
            } else {
                lo = mid
            }
            if (hi - lo < 1f) break
            guard++
        }
        return ans
    }

    private fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '\n' || ch.isWhitespace() -> {
                    i++
                }
                isCJK(ch) -> {
                    tokens.add(ch.toString())
                    i++
                }
                else -> {
                    val start = i
                    while (i < text.length && !isCJK(text[i]) && !text[i].isWhitespace() && text[i] != '\n') i++
                    tokens.add(text.substring(start, i))
                }
            }
        }
        return tokens
    }

    /**
     * The rendered pixel extent of a finalized [layout], centered on its origin
     * (or within its clip rect when clipped). Used as the obstacle footprint for
     * subsequently-placed blocks and for the final overlap check.
     */
    private fun extentOf(layout: BlockLayout, measurer: TextMeasurer): FloatRect {
        val cx = layout.originX
        val cy = layout.originY
        return if (layout.isVertical) {
            val charStep = layout.fontSizePx * VERTICAL_CHAR_STEP
            val colStep = layout.fontSizePx * VERTICAL_COL_STEP
            val cols = columnsFor(layout.text, layout.safeH, charStep)
            val totalW = cols * colStep
            val colH = min(layout.safeH, strippedLength(layout.text) * charStep)
            FloatRect(cx - totalW / 2f, cy - colH / 2f, cx + totalW / 2f, cy + colH / 2f)
        } else {
            val lines = cjkWrap(layout.text, layout.fontSizePx, layout.safeW, measurer)
            val lineH = measurer.lineHeight(layout.fontSizePx)
            val totalH = lines.size * lineH
            val maxLineW = (lines.maxOfOrNull { measurer.measureTextWidth(it, layout.fontSizePx) } ?: 0f)
                .coerceAtLeast(0f)
            when (layout.drawAlign) {
                TextAlign.LEFT -> FloatRect(cx, cy - totalH / 2f, cx + maxLineW, cy + totalH / 2f)
                TextAlign.RIGHT -> FloatRect(cx - maxLineW, cy - totalH / 2f, cx, cy + totalH / 2f)
                TextAlign.CENTER -> FloatRect(
                    cx - maxLineW / 2f,
                    cy - totalH / 2f,
                    cx + maxLineW / 2f,
                    cy + totalH / 2f,
                )
            }
        }
    }

    /**
     * Build strict layout regions from persisted segmentation masks. A fused mask
     * may contain multiple OCR children; partition its bounds at the midpoints of
     * the child centers so one child cannot consume the other's space.
     */
    private fun buildMaskRegions(blocks: List<TranslationBlock>): Map<Int, FloatRect> {
        val regions = HashMap<Int, FloatRect>()
        val groups = blocks.withIndex()
            .filter { it.value.segmentationMask != null }
            .groupBy { it.value.segmentationMask!! }

        for ((mask, indexedBlocks) in groups) {
            val bounds = mask.bounds
            val maskRect = FloatRect(
                bounds[0].toFloat(),
                bounds[1].toFloat(),
                bounds[2].toFloat(),
                bounds[3].toFloat(),
            )
            if (indexedBlocks.size == 1) {
                regions[indexedBlocks.single().index] = maskRect
                continue
            }

            val centers = indexedBlocks.associate { item ->
                item.index to Pair(
                    item.value.x + item.value.width / 2f,
                    item.value.y + item.value.height / 2f,
                )
            }
            val spanX = centers.values.maxOf { it.first } - centers.values.minOf { it.first }
            val spanY = centers.values.maxOf { it.second } - centers.values.minOf { it.second }
            val splitX = spanX >= spanY
            val ordered = indexedBlocks.sortedBy { centers[it.index]!!.let { c -> if (splitX) c.first else c.second } }
            val cuts = ordered.zipWithNext().map { (left, right) ->
                val a = centers[left.index]!!
                val b = centers[right.index]!!
                if (splitX) (a.first + b.first) / 2f else (a.second + b.second) / 2f
            }

            ordered.forEachIndexed { position, item ->
                val region = if (splitX) {
                    FloatRect(
                        if (position == 0) maskRect.left else cuts[position - 1],
                        maskRect.top,
                        if (position == ordered.lastIndex) maskRect.right else cuts[position],
                        maskRect.bottom,
                    )
                } else {
                    FloatRect(
                        maskRect.left,
                        if (position == 0) maskRect.top else cuts[position - 1],
                        maskRect.right,
                        if (position == ordered.lastIndex) maskRect.bottom else cuts[position],
                    )
                }
                regions[item.index] = region
            }
        }
        return regions
    }

    /** Give children of one fused mask the same safe fitted font size. */
    private fun equalizeSharedMaskFonts(
        layouts: List<BlockLayout>,
        measurer: TextMeasurer,
        scale: Float,
    ): List<BlockLayout> {
        val groups = layouts.filter { it.block.segmentationMask != null }
            .groupBy { it.block.segmentationMask!! }
        if (groups.values.none { it.size > 1 }) return layouts
        return layouts.map { layout ->
            val group = groups[layout.block.segmentationMask]
            if (group == null || group.size < 2) return@map layout
            val commonFont = group.minOf { it.fontSizePx }
            layout.copy(
                fontSizePx = commonFont,
                strokeWidth = computeStrokeWidth(commonFont, scale),
                lines = if (layout.isVertical) {
                    emptyList()
                } else {
                    cjkWrap(
                        layout.text,
                        commonFont,
                        layout.safeW,
                        measurer,
                    )
                },
            )
        }
    }

    private fun centeredExtentOverlapsObstacle(
        text: String,
        fontSize: Float,
        safeW: Float,
        originX: Float,
        originY: Float,
        obstacles: List<FloatRect>,
        measurer: TextMeasurer,
    ): Boolean {
        if (obstacles.isEmpty()) return false
        val lines = cjkWrap(text, fontSize, safeW, measurer)
        val lineH = measurer.lineHeight(fontSize)
        val totalH = lines.size * lineH
        val maxLineW = (lines.maxOfOrNull { measurer.measureTextWidth(it, fontSize) } ?: 0f)
            .coerceAtLeast(0f)
        val centered = FloatRect(
            originX - maxLineW / 2f,
            originY - totalH / 2f,
            originX + maxLineW / 2f,
            originY + totalH / 2f,
        )
        return obstacles.any { obs ->
            val inter = centered.intersection(obs)
            inter.width() > MIN_GAP_PX && inter.height() > MIN_GAP_PX
        }
    }

    // Pure per-block math ported unchanged from the legacy renderer.

    internal fun isCJK(ch: Char): Boolean {
        val cp = ch.code
        return (cp in 0x4E00..0x9FFF) ||
            (cp in 0x3400..0x4DBF) ||
            (cp in 0x20000..0x2A6DF) ||
            (cp in 0x2A700..0x2B73F) ||
            (cp in 0x2B740..0x2B81F) ||
            (cp in 0xF900..0xFAFF) ||
            (cp in 0x2F800..0x2FA1F) ||
            (cp in 0x3000..0x303F) ||
            (cp in 0x3040..0x309F) ||
            (cp in 0x30A0..0x30FF) ||
            (cp in 0x31F0..0x31FF) ||
            (cp in 0xAC00..0xD7AF) ||
            (cp in 0xFF00..0xFFEF) ||
            (cp in 0xFE30..0xFE4F)
    }

    /** Fraction of non-whitespace chars that are CJK; 0 for blank text. */
    internal fun cjkRatio(text: String): Float {
        val total = text.count { !it.isWhitespace() }
        if (total == 0) return 0f
        val cjk = text.count { !it.isWhitespace() && isCJK(it) }
        return cjk.toFloat() / total.toFloat()
    }

    /** Vertical layout only when CJK chars are the MAJORITY (>50%) of the text. */
    internal fun shouldRenderVertical(text: String): Boolean = cjkRatio(text) > 0.5f

    internal data class RectResult(
        val baseX: Float,
        val baseY: Float,
        val baseW: Float,
        val baseH: Float,
        val safeW: Float,
        val safeH: Float,
        val reshaped: Boolean,
        val origLeft: Float,
        val origRight: Float,
    )

    /**
     * Compute base + safe rect for a block, mirroring the legacy renderer: parented
     * blocks use the parent bubble rect; parentless TALL boxes are reshaped wider
     * (area-preserving, clamped width). The reshape keeps the legacy symmetric
     * placement here; neighbour-aware re-anchoring is the planner's job (placeBlock
     * step 1). [reshaped]/[origLeft]/[origRight] carry the original edges so the
     * planner can re-anchor against them.
     */
    internal fun computeRects(
        block: TranslationBlock,
        sampleSize: Int = 1,
        regionOverride: FloatRect? = null,
    ): RectResult {
        val scale = 1f / sampleSize
        val hasParent = regionOverride == null && block.parentWidth > 0f && block.parentHeight > 0f
        val textPad = if (hasParent) {
            max(12f * scale, 0.15f * min(block.parentWidth, block.parentHeight))
        } else {
            max(4f * scale, 0.03f * min(block.width, block.height))
        }
        var baseX = when {
            regionOverride != null -> regionOverride.left
            hasParent -> block.parentX
            else -> block.x
        }
        var baseY = when {
            regionOverride != null -> regionOverride.top
            hasParent -> block.parentY
            else -> block.y
        }
        var baseW = when {
            regionOverride != null -> regionOverride.width()
            hasParent -> block.parentWidth
            else -> block.width
        }
        var baseH = when {
            regionOverride != null -> regionOverride.height()
            hasParent -> block.parentHeight
            else -> block.height
        }
        val origLeft = baseX
        val origRight = baseX + baseW
        var reshaped = false
        if (regionOverride == null && !hasParent && baseH > 0f && baseW > 0f && baseH / baseW > RESHAPE_TALL_RATIO) {
            val area = baseW * baseH
            var newH = sqrt(area.toDouble()).toFloat()
            var newW = newH
            newW = newW.coerceIn(baseW * RESHAPE_MIN_WIDTH_FACTOR, baseW * RESHAPE_MAX_WIDTH_FACTOR)
            newH = area / newW
            // Symmetric re-centre on the original bubble centre (legacy behaviour);
            // placeBlock re-anchors away from a neighbour when one is present.
            baseX = (origLeft + origRight) / 2f - newW / 2f
            baseY += (baseH - newH) / 2f
            baseW = newW
            baseH = newH
            reshaped = true
        }
        val safePad = min(textPad, min(baseW, baseH) / 3f)
        val safeW = max(1f, baseW - safePad * 2f)
        val safeH = max(1f, baseH - safePad * 2f)
        return RectResult(baseX, baseY, baseW, baseH, safeW, safeH, reshaped, origLeft, origRight)
    }

    /**
     * Greedy line wrap (legacy port): newline forces a break; each CJK glyph is its
     * own token (breaks anywhere); a maximal Latin run is ONE atomic token (Latin
     * words are never hyphenated). Width queries go through [measurer] so this stays
     * pure-JVM-testable.
     */
    internal fun cjkWrap(text: String, fontSizePx: Float, maxWidthPx: Float, measurer: TextMeasurer): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\n') {
                tokens.add("\n")
                i++
            } else if (ch.isWhitespace()) {
                tokens.add(" ")
                i++
            } else if (isCJK(ch)) {
                tokens.add(ch.toString())
                i++
            } else {
                val start = i
                while (i < text.length && !isCJK(text[i]) && !text[i].isWhitespace() && text[i] != '\n') {
                    i++
                }
                tokens.add(text.substring(start, i))
            }
        }
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (token in tokens) {
            if (token == "\n") {
                lines.add(current.toString())
                current = StringBuilder()
                continue
            }
            val candidate = current.toString() + token
            val width = measurer.measureTextWidth(candidate, fontSizePx)
            if (width > maxWidthPx && current.isNotEmpty()) {
                lines.add(current.toString().trimEnd())
                current = StringBuilder(if (token == " ") "" else token)
            } else {
                current.append(token)
            }
        }
        if (current.isNotEmpty()) {
            lines.add(current.toString().trimEnd())
        }
        return if (lines.isEmpty()) listOf(text) else lines
    }

    /**
     * Largest font size in `[FIT_MIN_FONT_PX*scale, FIT_MAX_FONT_PX*scale]` whose
     * rendered text fits the safe rect, by binary search. Returns the floor when
     * nothing fits — the planner's growth + clip stages handle that case rather
     * than letting the caller draw an overflow.
     */
    internal fun binarySearchFontSize(
        text: String,
        safeW: Float,
        safeH: Float,
        containerW: Float,
        isVertical: Boolean,
        scale: Float,
        measurer: TextMeasurer,
    ): Float {
        val startSize = max(containerW * FIT_START_WIDTH_FACTOR, FIT_MIN_FONT_PX * scale * 4.5f).toInt()
        var high = min(max(startSize, (FIT_MIN_FONT_PX * scale * 4.5f).toInt()), (FIT_MAX_FONT_PX * scale).toInt())
        var low = max(2, (FIT_MIN_FONT_PX * scale).toInt())
        var best = low.toFloat()
        while (low <= high) {
            val mid = (low + high) / 2
            val midF = mid.toFloat()
            if (isVertical) {
                val charStep = midF * VERTICAL_CHAR_STEP
                val colStep = midF * VERTICAL_COL_STEP
                val chars = strippedLength(text)
                val maxChars = max(1, (safeH / charStep).toInt())
                val numCols = ceil(chars.toFloat() / maxChars).toInt().coerceAtLeast(1)
                val totalW = numCols * colStep
                val maxColH = maxChars * charStep
                if (totalW <= safeW && maxColH <= safeH) {
                    best = midF
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            } else {
                val wrapped = cjkWrap(text, midF, safeW, measurer)
                val lineH = measurer.lineHeight(midF)
                val totalHeight = wrapped.size * lineH
                val maxLineWidth = wrapped.maxOfOrNull { measurer.measureTextWidth(it, midF) } ?: 0f
                if (totalHeight <= safeH && maxLineWidth <= safeW) {
                    best = midF
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
        }
        return best
    }

    internal fun computeStrokeWidth(fontSizePx: Float, scale: Float): Float {
        // Single source of truth for outline width: a fixed fraction of the fitted
        // font size, floored so it never collapses below a visible pixel. Replaces
        // three divergent legacy formulas; Block.strokeWidth is no longer an input.
        return max(MIN_STROKE_PX * scale, fontSizePx * STROKE_WIDTH_FRACTION)
    }

    fun graphemeClusters(text: String): List<String> {
        val iterator = java.text.BreakIterator.getCharacterInstance()
        iterator.setText(text)
        val clusters = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != java.text.BreakIterator.DONE) {
            clusters.add(text.substring(start, end))
            start = end
            end = iterator.next()
        }
        return clusters
    }

    fun verticalGlyph(cluster: String): String {
        return when (cluster) {
            "（" -> "︵"
            "）" -> "︶"
            "[" -> "︵"
            "]" -> "︶"
            "{" -> "︵"
            "}" -> "︶"
            "【" -> "︻"
            "】" -> "︼"
            "《" -> "︽"
            "》" -> "︾"
            "「" -> "﹁"
            "」" -> "﹂"
            "『" -> "﹃"
            "』" -> "﹄"
            "ー" -> "丨"
            "-" -> "丨"
            "…" -> "︙"
            "‥" -> "︰"
            "、" -> "︑"
            "。" -> "︒"
            "," -> "︑"
            "." -> "︒"
            "?" -> "？"
            "!" -> "！"
            else -> cluster
        }
    }

    fun verticalOrientation(cluster: String): VerticalOrientation {
        return if (cluster.length == 1 && cluster[0].isLetterOrDigit() && cluster[0].code in 0x0020..0x007E) {
            VerticalOrientation.ROTATED
        } else {
            VerticalOrientation.UPRIGHT
        }
    }
}

enum class VerticalOrientation { UPRIGHT, ROTATED }
