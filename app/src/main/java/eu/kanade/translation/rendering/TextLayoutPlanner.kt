package eu.kanade.translation.rendering

import eu.kanade.translation.model.TranslationBlock
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * TachiyomiAT: pure text-measurement abstraction.
 *
 * The layout math in [TextLayoutPlanner] needs to ask "how wide is this string at
 * this font size?" and "what is the line height?". Those answers come from a real
 * [android.graphics.Paint] bound to the render typeface — which is Android-bound
 * and therefore NOT unit-testable (the project deliberately avoids Robolectric;
 * see `docs/TRANSLATION_MODULE.md` test-coverage note). Injecting the measurement
 * behind this interface keeps [TextLayoutPlanner] a ★ pure, JVM-testable object:
 * tests pass a deterministic fake measurer, the renderer passes [PaintTextMeasurer].
 *
 * This is the same split already used in the rendering package
 * ([RenderColorEstimator.colorPolicy] pure vs [RenderColorEstimator.estimate]
 * Bitmap-bound): keep the *decision* pure, push only the *I/O* to the Android side.
 */
interface TextMeasurer {
    /** Visual width of [text] rendered at [fontSizePx], in the planner's pixel units. */
    fun measureTextWidth(text: String, fontSizePx: Float): Float

    /**
     * Full line height (`fontMetrics.descent - fontMetrics.ascent`) at [fontSizePx].
     * The value used for line stacking, matching [PageTextRenderer]'s legacy
     * `fm.descent - fm.ascent`.
     */
    fun lineHeight(fontSizePx: Float): Float
}

/**
 * TachiyomiAT: a pure axis-aligned float rectangle.
 *
 * Deliberately NOT `android.graphics.RectF` — the planner must stay JVM-testable
 * (no `android.graphics` dependency). The renderer converts this to a `RectF` when
 * it actually needs to clip a [android.graphics.Canvas].
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
 *  - [CENTER]: text centred on [BlockLayout.originX] (the box centre) — the
 *    default for boxes that did not need to grow.
 *  - [LEFT]: text left-aligned at [BlockLayout.originX] (a fixed left edge) —
 *    used when a box grew RIGHT into free space (the original left edge is the
 *    anchor) and by the clip safety-net (originX = clip left edge).
 *  - [RIGHT]: text right-aligned at [BlockLayout.originX] (a fixed right edge) —
 *    used when a box grew LEFT into free space (the original right edge is the
 *    anchor), so parentless edge SFX do not slide toward the page centre.
 */
enum class TextAlign { CENTER, LEFT, RIGHT }

/**
 * TachiyomiAT: the resolved placement for a single translated block, produced by
 * [TextLayoutPlanner.plan]. The renderer ([PageTextRenderer]) only DRAWS these —
 * it performs no further layout. See the "neighbor-aware layout" contract in
 * `docs/TRANSLATION_MODULE.md`.
 *
 * @property originX horizontal draw anchor. Its meaning depends on [drawAlign]:
 *   CENTER ⇒ box centre; LEFT ⇒ the (clip or growth) left edge; RIGHT ⇒ the
 *   growth right edge.
 * @property originY vertical center the renderer stacks lines around.
 * @property drawAlign how the text is anchored horizontally at [originX].
 * @property clipRect non-null ONLY when, after growing into free space and
 *   shrinking, the block still cannot avoid a neighbour. The renderer clips its
 *   canvas to this rect so rendered text extents can never overlap — the safety
 *   net that makes the non-overlap guarantee structural rather than best-effort.
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
)

/**
 * TachiyomiAT: pure, neighbour-aware text-layout solver for the render stage.
 *
 * **Why this exists.** [PageTextRenderer] used to lay each block out independently,
 * centred on its own rect, with zero knowledge of the other blocks on the page.
 * That produced three reported defects, all the same root cause — no global view:
 *  1. **Collision:** two adjacent bubbles' rendered text extents overlap (e.g. one
 *     bottom-left, one bottom-right). Pre-render dedupe only collapses
 *     *near-duplicate same-region* boxes; two genuinely-distinct but close boxes
 *     both survive and overlap at draw time. Text that did not fit was drawn
 *     anyway, overflowing straight into the neighbour.
 *  2. **Forced horizontal-long:** a tall parentless box was widened up to 3.5× and
 *     *re-centred* symmetrically, ignoring the translated text length and whether
 *     the wider footprint reached a neighbour.
 *  3. **Too small:** the font-fit binary search bottomed out at ~8 px with no
 *     legibility floor and no grow/clip fallback, producing illegible text.
 *
 * This object fixes all three by planning the WHOLE page at once:
 *  - It reuses the existing, tuned per-block math ([computeRects],
 *    [binarySearchFontSize], [cjkWrap]) unchanged as the initial fit.
 *  - A free-space-aware pass then re-anchors reshaped (tall) boxes AWAY from their
 *    nearest neighbour and grows overflowing/undersized boxes into whichever side
 *    has the most free space (toward the page edge), so overflow never points at a
 *    neighbour.
 *  - A legibility floor ([minLegibleFont]) prevents the collapse to ~8 px by
 *    preferring growth into free space over shrinking past readability.
 *  - A clip safety-net guarantees the structural invariant: after planning, no two
 *    rendered extents overlap. Anything that genuinely cannot be placed is clipped
 *    (the only path that can lose content) — it is never silently drawn over a
 *    neighbour.
 *
 * Pure & side-effect-free: no `Bitmap`/`Canvas`/ONNX dependency. All font-dependent
 * measurement goes through the injected [TextMeasurer], so the solver is unit-testable
 * with a deterministic fake (★ — see `TextLayoutPlannerTest`). The renderer is now
 * only responsible for drawing a [BlockLayout] list onto a canvas.
 *
 * **Ordering.** Blocks are placed highest-[TranslationBlock.score] first: the most
 * confident box takes its preferred placement and lower-confidence boxes treat it
 * as a fixed obstacle. This mirrors the dedupe stage's "keep the higher-score box"
 * rule and makes the single placement pass deterministic — no relaxation loop, no
 * oscillation. Ties keep reading order (stable sort).
 */
object TextLayoutPlanner {

    // ---- Reshape (tall parentless box → wider) — mirrors the legacy tuned math. ----
    /** A parentless box taller than this × its width is reshaped to a wider rect. */
    private const val RESHAPE_TALL_RATIO = 2.0f
    private const val RESHAPE_MIN_WIDTH_FACTOR = 1.5f
    private const val RESHAPE_MAX_WIDTH_FACTOR = 3.5f

    // ---- Vertical layout geometry — identical to the legacy renderer constants. ----
    private const val VERTICAL_CHAR_STEP = 1.05f
    private const val VERTICAL_COL_STEP = 1.25f

    // ---- Font-fit bounds — identical to the legacy renderer (preserved on purpose). ----
    private const val FIT_MAX_FONT_PX = 72f
    private const val FIT_MIN_FONT_PX = 8f
    private const val FIT_START_WIDTH_FACTOR = 1.5f

    // ---- Outline width — one source of truth (see [computeStrokeWidth]). ----
    // Fraction of the fitted font size; floor keeps it visible at small sizes.
    private const val STROKE_WIDTH_FRACTION = 0.12f
    private const val MIN_STROKE_PX = 2f

    /**
     * Legibility floor. The legacy fit could collapse to [FIT_MIN_FONT_PX] (~8 px),
     * which is unreadable. The floor is the larger of an absolute minimum and a
     * fraction of the smaller page dimension, so it scales with page resolution
     * (a 1500 px page ⇒ ~21 px floor) while never going below the absolute value.
     * Normal text fits at 36–72 px, far above this, so the floor is invisible for
     * the common case and only rescues the genuinely-oversized-text bubbles.
     */
    private const val LEGIBLE_FONT_FRACTION = 0.014f
    private const val LEGIBLE_FONT_ABS_PX = 14f

    /** Min visible gap kept between two placed extents, in planner px. */
    private const val MIN_GAP_PX = 2f

    /**
     * Resolve a render plan for every block on the page. Blocks whose chosen text
     * (translation, or source when [renderSourceText]) is blank are dropped — the
     * renderer would skip them anyway, and excluding them here keeps them out of
     * the obstacle set so they do not needlessly constrain neighbours.
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

        // Stable order: highest score first (most confident box places first and
        // becomes a fixed obstacle for lower-score boxes); ties keep reading order.
        val ordered = blocks.withIndex().sortedWith(
            compareByDescending<IndexedValue<TranslationBlock>> { it.value.score }
                .thenBy { it.index },
        )

        val placed = ArrayList<BlockLayout>(blocks.size)
        for (indexed in ordered) {
            val block = indexed.value
            val text = chosenText(block, renderSourceText)
            if (text.isBlank()) continue

            val rect = computeRects(block, sampleSize)
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
            )
            placed.add(resolved)
        }
        return placed
    }

    /** Legibility floor for a page of [pageWidth]×[pageHeight] at decode [scale]. */
    internal fun minLegibleFont(pageWidth: Float, pageHeight: Float, scale: Float): Float =
        max(LEGIBLE_FONT_ABS_PX * scale, min(pageWidth, pageHeight) * LEGIBLE_FONT_FRACTION)

    private fun chosenText(block: TranslationBlock, renderSourceText: Boolean): String =
        if (renderSourceText) block.translation.ifBlank { block.text } else block.translation

    /**
     * Place one block, treating [obstacles] (already-finalised higher-score extents)
     * as fixed. Steps, in priority order:
     *  1. If the box was reshaped tall (Defect 2), re-anchor the wider footprint
     *     AWAY from the nearest obstacle / toward the larger free side instead of
     *     the legacy symmetric re-centre.
     *  2. Fit the font; if it lands below the legibility floor or the text
     *     overflows the box (Defects 1 & 3), grow the box into the larger free
     *     side (never toward an obstacle) and re-fit, so overflow points into open
     *     space, not at a neighbour.
     *  3. If a residual overlap with an obstacle remains, clip this block to the
     *     half-space on its own side of the obstacle — the structural guarantee
     *     that rendered extents never overlap.
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
    ): BlockLayout {
        var baseX = rect.baseX
        var baseY = rect.baseY
        var baseW = rect.baseW
        var baseH = rect.baseH
        val safePad = max(0f, (baseW - rect.safeW) / 2f)

        val hasParent = block.parentWidth > 0f && block.parentHeight > 0f
        val anchorToOcrCenter = block.label == 2 || (block.direction == "TTB" && !isVertical)
        val region = if (hasParent) {
            FloatRect(block.parentX, block.parentY, block.parentX + block.parentWidth, block.parentY + block.parentHeight)
        } else if (rect.reshaped) {
            FloatRect(0f, 0f, pageWidth, pageHeight)
        } else {
            FloatRect(block.x, block.y, block.x + block.width, block.y + block.height)
        }

        // (1) Re-anchor a reshaped tall box by MINIMAL DISPLACEMENT subject to
        // staying on-page and not overlapping an obstacle. Start from the legacy
        // symmetric placement (box centred on the original bubble centre); if that
        // candidate collides with an obstacle or the page edge, shift it the
        // smallest distance that resolves the collision — toward whichever side has
        // room. This keeps isolated boxes exactly where the legacy renderer put
        // them (no regression) and only nudges when a real collision would occur,
        // instead of always anchoring on an edge whenever free space is imbalanced.
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

        // (2) Fit, then grow into free space if undersized or overflowing.
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

        val strokeWidth = computeStrokeWidth(fontSize, scale)
        var originY = baseY + baseH / 2f
        // Re-anchor at the ORIGINAL edge of a box that grew horizontally into free
        // space, so parentless edge text (e.g. SFX) does not drift toward the page
        // centre: grew-right ⇒ pin the (unchanged) left edge with LEFT align;
        // grew-left ⇒ pin the (unchanged) right edge with RIGHT align. A genuinely
        // required clip (below) overrides this to LEFT at its own left edge.
        var originX: Float
        var drawAlign: TextAlign
        val centeredOriginX = baseX + baseW / 2f
        val edgeAnchorWouldAvoidOverlap = !isVertical && centeredExtentOverlapsObstacle(
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

        // (3) Clip safety-net: if the final extent still overlaps an obstacle,
        // clip to this block's own side of the obstacle boundary. Guarantees the
        // no-overlap invariant. Reached only when free space was exhausted.
        val safeLeft = baseX + safePad
        val safeTop = baseY + safePad
        val safeRight = baseX + baseW - safePad
        val safeBottom = baseY + baseH - safePad
        var clip = FloatRect(safeLeft, safeTop, safeRight, safeBottom)
        var neededClip = false
        for (obs in obstacles) {
            if (!clip.overlaps(obs)) continue
            val inter = clip.intersection(obs)
            // Keep the half of `clip` that lies on this block's centre side of the
            // obstacle, so the clip removes exactly the overlapping sliver.
            val centerX = baseX + baseW / 2f
            clip = if (centerX < (obs.left + obs.right) / 2f) {
                // Block is to the LEFT of the obstacle → drop the right sliver.
                FloatRect(clip.left, clip.top, min(clip.right, inter.left), clip.bottom)
            } else {
                FloatRect(max(clip.left, inter.right), clip.top, clip.right, clip.bottom)
            }
            neededClip = true
        }
        if (neededClip && clip.width() > MIN_GAP_PX && clip.height() > MIN_GAP_PX) {
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

            // Re-wrap into the (narrower) clip width and re-fit the font so the
            // text fills the clipped region rather than overflowing it.
            val fitFont = binarySearchFontSize(
                text, safeW, safeH, safeW, isVertical, scale, measurer,
            )
            // Allow the font to shrink to fit the clipped bounds instead of enforcing the legibility floor,
            // preventing the text from being cut off.
            fontSize = fitFont
        }

        // (C) Containment clip: if rendered text overflows region R, clip to R.
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
                    text, safeW, safeH, safeW, isVertical, scale, measurer,
                )
                fontSize = fitFont
            }
        }

        // Free text / vertical-source Latin translations must remain visually
        // attached to the OCR region. The solver may still grow/clip the safe
        // box for legibility, but the actual draw anchor stays at the original
        // OCR centre instead of drifting to a parent rect or reshaped footprint.
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
        )
    }

    /**
     * Place a box of [width] horizontally by minimal displacement.
     *
     * The ideal left edge is [preferredX]; if a box placed there overlaps any
     * [obstacles] or leaves the page, it is shifted the smallest distance that
     * resolves ALL collisions. On-page bounds are clamped first, then obstacle
     * collisions are resolved by pushing out of the overlapping obstacle toward
     * whichever side keeps the box nearer its preferred centre. When shifting
     * would push it off-page or into another obstacle, the move is clamped and the
     * residual overlap is left for the clip safety-net (step 3 of [placeBlock]).
     *
     * This is what makes the reshaped-box placement (Defect 2) correct: an isolated
     * box is placed exactly at its preferred (centred) position — the legacy
     * behaviour — and only moves when a real collision forces it, rather than
     * always anchoring on an edge whenever left/right free space differs.
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
        // Resolve collisions one at a time, up to a bounded number of passes
        // (each obstacle is shifted past at most once; obstacles do not move).
        var guard = 0
        while (guard < obstacles.size + 1) {
            val candidate = FloatRect(x, 0f, x + width, Float.MAX_VALUE)
            val colliding = obstacles.firstOrNull { candidate.overlaps(it) }
            if (colliding == null) return x
            val centerX = x + width / 2f
            val obsCenterX = (colliding.left + colliding.right) / 2f
            // Push to the side of the obstacle nearer the preferred centre.
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

    /**
     * Free horizontal space to the LEFT of [origLeft], bounded by the page left
     * edge (0) and the nearest obstacle whose right edge is left of [centerX].
     */
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
     * Grow the box into free space when the current fit is undersized (below the
     * legibility floor) or the text overflows the safe rect. Growth is only ever
     * into the larger-free side, clamped so it never crosses an obstacle or the
     * page edge — i.e. overflow is redirected into open space, never at a neighbour.
     * Returns the (possibly unchanged) box + a refit font.
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
            // Horizontal. Grow HEIGHT first (taller ⇒ more wrap lines ⇒ narrower),
            // then WIDTH only for whatever still won't fit — each into its larger
            // free side so overflow is redirected into open space, never at a
            // neighbour, and bounded by the available free space so a box never
            // crosses an obstacle (contract #16f).
            val lineH = measurer.lineHeight(targetFont)
            val centerY = by + bh / 2f
            val safeTop = by + safePad
            val safeBottom = by + bh - safePad
            val freeUp = freeSpaceVerticalUp(centerY, safeTop, obstacles)
            val freeDown = freeSpaceVerticalDown(centerY, safeBottom, obstacles, pageHeight)

            // (a) HEIGHT — always reserve at least one line at the floor font (a box
            // too short for even one line could never reach the legibility floor no
            // matter how wide it grew, collapsing the fit to ~8 px — Defect 3). Then
            // grow further so the text wraps into MORE lines (taller, narrower
            // footprint) instead of ballooning width: pick the smallest line count
            // within the vertical headroom whose wrapped width already fits the
            // current safe width; if none fits, use all available headroom and let
            // the width step below handle the residual.
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

            // (b) WIDTH — re-wrap into the lines that now fit and grow only for any
            // residual overflow, into the larger free side (record which side so
            // placeBlock can re-anchor at the original edge).
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
            // Vertical: growing HEIGHT lets more glyphs fit per column ⇒ fewer
            // columns ⇒ narrower footprint, relieving horizontal neighbour pressure.
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
        // Honour the floor: prefer the target (legible) size over the fit result
        // when the grown box can finally accommodate it.
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
        // TachiyomiAT: which horizontal side the box grew into (false when it did
        // not grow horizontally). Drives the re-anchor in placeBlock so a box that
        // grew into free space stays pinned at its ORIGINAL edge instead of
        // re-centering on the (now wider) box and drifting toward page centre.
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
                ch == '\n' || ch.isWhitespace() -> { i++ }
                isCJK(ch) -> { tokens.add(ch.toString()); i++ }
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
                TextAlign.CENTER -> FloatRect(cx - maxLineW / 2f, cy - totalH / 2f, cx + maxLineW / 2f, cy + totalH / 2f)
            }
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

    // ---- Pure per-block math (ported unchanged from the legacy renderer). ----

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
     * Compute the base + safe rect for a block, mirroring the legacy renderer:
     * parented blocks use the parent bubble rect; parentless TALL boxes are
     * reshaped wider (area-preserving, clamped width). The reshape keeps the
     * legacy symmetric placement here; placement/anchoring AWAY from neighbours is
     * the planner's job ([placeBlock] step 1). [reshaped]/[origLeft]/[origRight]
     * carry the original bubble edges so the planner can re-anchor against them.
     */
    internal fun computeRects(block: TranslationBlock, sampleSize: Int = 1): RectResult {
        val scale = 1f / sampleSize
        val hasParent = block.parentWidth > 0f && block.parentHeight > 0f
        val textPad = if (hasParent) {
            max(12f * scale, 0.15f * min(block.parentWidth, block.parentHeight))
        } else {
            max(4f * scale, 0.03f * min(block.width, block.height))
        }
        var baseX = if (hasParent) block.parentX else block.x
        var baseY = if (hasParent) block.parentY else block.y
        var baseW = if (hasParent) block.parentWidth else block.width
        var baseH = if (hasParent) block.parentHeight else block.height
        val origLeft = baseX
        val origRight = baseX + baseW
        var reshaped = false
        if (!hasParent && baseH > 0f && baseW > 0f && baseH / baseW > RESHAPE_TALL_RATIO) {
            val area = baseW * baseH
            var newH = sqrt(area.toDouble()).toFloat()
            var newW = newH
            newW = newW.coerceIn(baseW * RESHAPE_MIN_WIDTH_FACTOR, baseW * RESHAPE_MAX_WIDTH_FACTOR)
            newH = area / newW
            // Symmetric re-centre on the original bubble centre (legacy behaviour);
            // [placeBlock] re-anchors away from a neighbour when one is present.
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
     * Greedy line wrap. Faithful port of the legacy renderer: newline forces a
     * break; each CJK glyph is its own token (breaks anywhere); a maximal Latin
     * run is ONE atomic token (Latin words are never hyphenated). Width queries go
     * through [measurer] so this stays pure-JVM-testable.
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
     * rendered text fits the safe rect, by binary search. Faithful to the legacy
     * fit except that width/line queries go through [measurer]. Returns the floor
     * when nothing fits — the planner's growth + clip stages handle that case
     * rather than letting the caller draw an overflow.
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
        // One source of truth for outline width: a fixed fraction of the fitted
        // font size, floored so it never collapses below a visible pixel. This
        // matches PageTextRenderer's own fallback and replaces three divergent
        // formulas (the old estimator 4.5/3.0 scaled by a font-fit ratio that
        // shrank it to ~1px, the renderer's 0.12× floor 2, and the planner's
        // 0.07× floor 1.5). Block.strokeWidth is no longer an input.
        return max(MIN_STROKE_PX * scale, fontSizePx * STROKE_WIDTH_FRACTION)
    }
}
