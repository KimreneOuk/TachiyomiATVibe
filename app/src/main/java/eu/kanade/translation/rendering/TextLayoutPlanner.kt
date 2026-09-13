package eu.kanade.translation.rendering

import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.segmentation.BubbleMaskRle
import eu.kanade.translation.segmentation.MaskConversionBudgets
import eu.kanade.translation.segmentation.MaskGeometry
import eu.kanade.translation.segmentation.OrderedMaskResult
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
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
 * TachiyomiAT T912 slice 5: one pre-positioned, prewrapped horizontal line.
 * `leftPx`/`topPx` are integer `floor()` placement results;
 * `layoutWidthPx = max(1, ceil(advance + 2*SHAPING_GUARD))` is the EXACT width
 * the renderer must shape the line at (one `StaticLayout` line, left-aligned
 * inside that width); [conservativeOccupancy] is the un-clipped planning
 * envelope (advance/line-height rect inflated by stroke, AA guard, and half
 * collision gap).
 */
data class PositionedLine(
    val text: String,
    val leftPx: Int,
    val topPx: Int,
    val layoutWidthPx: Int,
    val layoutHeightPx: Int,
    val conservativeOccupancy: FloatRect,
)

/**
 * TachiyomiAT T912 slice 5: the structural render boundary of a layout —
 * component path (via the `(planGeometryId, componentId)` pair) then the
 * cell/safety rectangle. Only these clips justify pixel-containment claims;
 * planning occupancies stay conservative and un-clipped.
 */
data class HardClip(
    val planGeometryId: Int?,
    val componentId: Int?,
    val cellRect: FloatRect?,
)

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
    val maskGeometry: MaskGeometry? = null,
    /**
     * TachiyomiAT: compact per-page group id of this block's segmentation mask
     * (see [SharedMaskSession]). Null when the block has no mask or its mask is
     * groupless. Valid ONLY within the page plan it was produced for.
     */
    val planGeometryId: Int? = null,
    val maskComponentId: Int? = null,
    /**
     * TachiyomiAT: this block's mask region rect (the same region used for
     * placement). The renderer intersects it with the component path so shared
     * components clip structurally instead of only by planning.
     */
    val cellRect: FloatRect? = null,
    /**
     * TachiyomiAT slice 5: pre-positioned adaptive lines. Null selects the
     * EXACT legacy renderer (stacked lines / vertical columns); non-null
     * selects the positioned-line shaping path (one StaticLayout line per
     * entry at its integer placement).
     */
    val positionedLines: List<PositionedLine>? = null,
    /**
     * TachiyomiAT slice 5: per-line conservative planning envelopes (un-clipped,
     * stroke/AA/half-gap inflated). Empty unless [positionedLines] != null.
     */
    val conservativeOccupancy: List<FloatRect> = emptyList(),
    /**
     * TachiyomiAT slice 5: structural render boundary mirrored from
     * [maskGeometry]/[planGeometryId]/[maskComponentId]/[cellRect]; the
     * renderer composes component path then cell rectangle from it.
     */
    val hardClip: HardClip = HardClip(null, null, null),
    /**
     * T912 contained-fit: false ONLY for the mask-unusable fallback — no
     * fully contained fit exists at the render floor, so the planner fails
     * open to the unshifted OCR-region draw and metadata wiring must not
     * attach the exact component clip (visibility wins over the mask).
     */
    val maskUsable: Boolean = true,
)

/**
 * TachiyomiAT: audit identity of one planner input — the original list index
 * plus the block id. `blockId` alone is insufficient (nullable and possibly
 * duplicated), so the identity is the pair.
 */
data class InputIdentity(val inputIndex: Int, val blockId: String?)

/**
 * TachiyomiAT: explicit reason a nonblank input produced no drawable layout.
 * Slice 3 emits only [INVALID_OR_SUBPIXEL_SOURCE_RECT] and
 * [EMPTY_SHARED_CELL]; the remaining reasons are declared now so the contract
 * is complete for the later slices that own them.
 */
enum class NonDrawReason {
    INVALID_OR_SUBPIXEL_SOURCE_RECT,
    EMPTY_SHARED_CELL,
    MASK_OR_WORK_BUDGET_EXHAUSTED,
    POSITIONED_LINE_BUDGET_EXHAUSTED,
    STATIC_LAYOUT_BUDGET_EXHAUSTED,
    NO_DISJOINT_POST_ANCHOR_PLACEMENT,
    INVALID_RENDER_METADATA,
}

/** TachiyomiAT: explicit per-input planner outcome — exactly one per nonblank input. */
sealed interface LayoutOutcome {
    data class Draw(val layout: BlockLayout) : LayoutOutcome
    data class NonDraw(val reason: NonDrawReason) : LayoutOutcome
}

/**
 * TachiyomiAT: one explicit planner result per nonblank input identity.
 * [chosenText] derives ONLY from [block] (never merged, concatenated, or
 * substituted). [planningOrdinal] is the 0-based rank among nonblank inputs in
 * placement (score-descending, then input index) order; [renderOrdinal] is
 * non-null only for [LayoutOutcome.Draw] and consecutive `0..k-1` in that
 * placement order — the order of [PageLayoutPlan.drawableInRenderOrder].
 */
data class LayoutResult(
    val identity: InputIdentity,
    val block: TranslationBlock,
    val chosenText: String,
    val planningOrdinal: Int,
    val renderOrdinal: Int?,
    val outcome: LayoutOutcome,
)

/**
 * TachiyomiAT: full page plan. [resultsInInputOrder] holds exactly one result
 * per nonblank input (blank inputs are the only intentional absence);
 * [drawableInRenderOrder] holds the Draw layouts in placement order and is the
 * only list the renderer consumes.
 */
data class PageLayoutPlan(
    val resultsInInputOrder: List<LayoutResult>,
    val drawableInRenderOrder: List<BlockLayout>,
)

/**
 * TachiyomiAT T912 slice 7: [PageLayoutPlan] plus the deterministic count of
 * evaluated final post-anchor placement candidates, summed over every colliding
 * block. Internal test seam — [TextLayoutPlanner.planPage] wraps this and
 * returns only the plan.
 */
internal data class PagePlanWithAttempts(val plan: PageLayoutPlan, val finalPlacementAttempts: Int)

/**
 * TachiyomiAT T912 slice 7: collision footprint of one accepted Draw layout —
 * the single conservative (stroke/AA/half-gap inflated, un-clipped) occupancy
 * rectangle plus the layout's hard cell rectangle ([BlockLayout.cellRect];
 * null for unmasked/legacy layouts). A pair whose hard cells are BOTH present
 * and non-overlapping is structurally pixel-separated and exempt from
 * occupancy collision detection.
 */
internal data class PlacedFootprint(val cellRect: FloatRect?, val occupancy: FloatRect)

/**
 * TachiyomiAT: per-page mask grouping for the layout plan.
 *
 * Walks block masks in INPUT order and assigns a compact group id:
 *  - repeated references to the SAME [BubbleMaskRle] instance reuse the group
 *    with no rescan (production always shares one mask instance per bubble);
 *  - distinct instances are matched through a bounded 64-bit streaming
 *    fingerprint (FNV-1a over width/height/bounds/run count/run pairs) used
 *    ONLY to select a small verification bucket; the group is reused after
 *    FULL geometry-defining equality (width, height, bounds, runs) is
 *    verified, so a hash collision can never merge two different masks;
 *  - [BubbleMaskRle.score] is deliberately excluded from fingerprint and
 *    equality — it has no geometric effect on partitioning or components.
 *
 * Fingerprint scans and conversion scans both consume the session's page
 * [MaskConversionBudgets]. Beyond [MAX_MASK_REFERENCES_PER_PAGE] references or
 * [MAX_UNIQUE_MASKS_PER_PAGE] unique masks, further masks are GROUPLESS
 * (negative group id): they keep legacy region partitioning grouped by
 * reference identity and never receive component metadata.
 *
 * `fingerprint` is injectable so tests can force bucket collisions.
 */
internal class SharedMaskSession(
    val budgets: MaskConversionBudgets = MaskConversionBudgets(),
    private val fingerprint: (BubbleMaskRle) -> Long = ::defaultMaskFingerprint,
) {
    private val referenceGroups = IdentityHashMap<BubbleMaskRle, Int>()
    private val grouplessIds = IdentityHashMap<BubbleMaskRle, Int>()
    private val fingerprintBuckets = HashMap<Long, MutableList<Int>>()
    private val groupMasks = ArrayList<BubbleMaskRle>()
    private val conversions = ArrayList<MaskGeometry?>()
    private var converted = false
    private var nextGrouplessId = -1

    /** Number of distinct verified mask groups on this page. */
    internal val groupCount: Int get() = groupMasks.size

    /**
     * Group id for [mask]: `0 until groupCount` when grouped, negative when
     * groupless. Safe to call repeatedly — reference identity short-circuits
     * with no rescan.
     */
    fun groupIdOf(mask: BubbleMaskRle): Int {
        referenceGroups[mask]?.let { return it }
        grouplessIds[mask]?.let { return it }
        if (referenceGroups.size + grouplessIds.size >= MAX_MASK_REFERENCES_PER_PAGE) {
            return assignGroupless(mask)
        }
        // The fingerprint scan consumes the page RLE-int budget; abort
        // (groupless) before scanning when the budget would be exceeded.
        if (budgets.rleIntsScanned + mask.runs.size > budgets.maxRleIntsScannedPerPage) {
            return assignGroupless(mask)
        }
        budgets.rleIntsScanned += mask.runs.size
        val hash = fingerprint(mask)
        fingerprintBuckets[hash]?.forEach { candidate ->
            if (geometricallyEqual(groupMasks[candidate], mask)) {
                referenceGroups[mask] = candidate
                return candidate
            }
        }
        if (groupMasks.size >= MAX_UNIQUE_MASKS_PER_PAGE) return assignGroupless(mask)
        val groupId = groupMasks.size
        groupMasks += mask
        fingerprintBuckets.getOrPut(hash) { mutableListOf() }.add(groupId)
        referenceGroups[mask] = groupId
        return groupId
    }

    /** Convert every grouped mask once, in group order. Idempotent. */
    fun convertAll() {
        if (converted) return
        converted = true
        repeat(groupMasks.size) { groupId ->
            conversions += when (val result = MaskGeometry.fromOrderedRle(groupMasks[groupId], budgets)) {
                is OrderedMaskResult.Success -> result.geometry
                is OrderedMaskResult.Fallback -> null
            }
        }
    }

    /** Geometry for a grouped id, or null when groupless or conversion fell back. */
    fun geometryFor(groupId: Int): MaskGeometry? {
        if (groupId < 0) return null
        convertAll()
        return conversions.getOrNull(groupId)
    }

    private fun assignGroupless(mask: BubbleMaskRle): Int {
        val id = nextGrouplessId
        nextGrouplessId -= 1
        grouplessIds[mask] = id
        return id
    }

    private fun geometricallyEqual(a: BubbleMaskRle, b: BubbleMaskRle): Boolean =
        a.width == b.width && a.height == b.height && a.bounds == b.bounds && a.runs == b.runs

    internal companion object {
        /** Hard guard: distinct mask references grouped per page (beyond → groupless). */
        internal const val MAX_MASK_REFERENCES_PER_PAGE = 128

        /** Hard guard: distinct unique masks grouped per page (beyond → groupless). */
        internal const val MAX_UNIQUE_MASKS_PER_PAGE = 32
    }
}

/** Bounded FNV-1a-style 64-bit streaming fingerprint over the geometry-defining mask fields. */
internal fun defaultMaskFingerprint(mask: BubbleMaskRle): Long {
    var hash = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
    fun mix(value: Int) {
        hash = (hash xor value.toLong()) * 0x100000001b3L
    }
    mix(mask.width)
    mix(mask.height)
    for (bound in mask.bounds) mix(bound)
    mix(mask.runs.size)
    for (run in mask.runs) mix(run)
    return hash
}

/**
 * TachiyomiAT T912 slice 5: the isolated adaptive-layout tuning envelope (task
 * contract: thresholds are isolated constants backed by focused tests).
 * Collision gap itself stays in [MaskTextRegionPlanner.collisionGapPx].
 */
internal object TextLayoutTuning {
    /**
     * Anti-aliasing guard: `max(1*scale, 0.5)` px kept around shaped text so
     * fractional transforms cannot push AA samples outside the planned rect.
     */
    fun aaGuard(scale: Float): Float = max(1f * scale, 0.5f)

    /**
     * StaticLayout width guard: `ceil(stroke/2 + AA_GUARD)` — conservative,
     * not an exact glyph bound; combining marks, emoji/ZWJ, italic overhang,
     * and fractional transforms are made structurally safe by the
     * component+cell/safety clips instead.
     */
    fun shapingGuardPx(fontPx: Float, scale: Float): Float =
        ceil(TextLayoutPlanner.computeStrokeWidth(fontPx, scale) / 2f + aaGuard(scale))

    /** Documented tunable: extra visual breathing room around banded lines (px). */
    const val VISUAL_PADDING_PX = 2f

    /** Band/line stroke inset: `ceil(stroke/2 + AA_GUARD + VISUAL_PADDING_PX)`. */
    fun strokeInsetPx(fontPx: Float, scale: Float): Float =
        ceil(TextLayoutPlanner.computeStrokeWidth(fontPx, scale) / 2f + aaGuard(scale) + VISUAL_PADDING_PX)

    /** Hard guard: positioned lines per block (beyond → legacy rectangular layout). */
    const val MAX_POSITIONED_LINES_PER_BLOCK = 24

    /** Hard guard: positioned lines per page (beyond → legacy single-layout retry). */
    const val MAX_POSITIONED_LINES_PER_PAGE = 256

    /** Hard guard: StaticLayouts reserved per page (legacy line = 2, positioned line = 2). */
    const val MAX_STATIC_LAYOUTS_PER_PAGE = 512

    /** Hard guard: font binary-search evaluations per fit (and per trial fitter). */
    const val MAX_FONT_BINARY_STEPS = 7

    /** Hard guard: vertical alignment attempts per font. */
    const val MAX_BAND_ALIGNMENTS = 3

    /** Hard guard: centering fixed-point passes per alignment. */
    const val MAX_BAND_FIXED_POINT_PASSES = 3

    /** Band-interval explosion guard: more intervals ⇒ treat as no valid band. */
    const val MAX_BAND_INTERVALS = 64

    // ---- T912 slice 6: bounded long `text_free` widening envelope ---------

    /** Min non-whitespace graphemes before a `text_free` widening trial may run. */
    const val FREE_TEXT_MIN_GRAPHEMES = 24

    /** Original OCR aspect (`height/width`) at or above which the trial may run. */
    const val FREE_TEXT_TALL_RATIO = 2.0f

    /** Widening trial factors, tried in this order; the smallest useful one wins. */
    const val FREE_TEXT_WIDEN_FACTOR_1 = 1.25f
    const val FREE_TEXT_WIDEN_FACTOR_2 = 1.50f

    /** Hard cap on the added width, relative to the page short side. */
    const val FREE_TEXT_MAX_PAGE_ADD_FRACTION = 0.08f

    /** A wider candidate must lift the fitted font by this factor (or remove overflow). */
    const val FREE_TEXT_MIN_FONT_GAIN = 1.15f

    /** Width-equality epsilon (px) for candidate dedup after clamping. */
    const val FREE_TEXT_WIDTH_EPSILON = 0.01f

    // ---- T912 slice 7: finite final post-anchor safety ---------------------

    /** Hard guard: evaluated post-anchor candidates per colliding block. */
    const val MAX_FINAL_PLACEMENT_ATTEMPTS = 8

    /**
     * Masked-only post-anchor relocation cap, relative to the smaller OCR
     * region dimension. This preserves small local collision nudges without
     * allowing a resolver candidate to escape into another bubble lobe.
     */
    const val MAX_MASK_SHIFT_REGION_FRACTION = 0.25f

    /** Masked-only relocation cap, relative to the page short side. */
    const val MAX_MASK_SHIFT_PAGE_FRACTION = 0.04f

    // ---- T912 contained-fit rescue: OCR-box-first tiered ladder -------------

    /**
     * Vertical-only growth factors of the OCR fit-region ladder (Director
     * iteration 9 contract: the text column keeps the OCR box's x-range;
     * only height grows, and only as needed). The final tier is the block's
     * own cell content region, offered only when a real component cell exists.
     */
    val OCR_GROW_FACTORS = floatArrayOf(1.0f, 1.25f, 1.5f, 2.0f)

    /** Bounded font walk-down steps per tier under the exact containment predicate. */
    const val MAX_CONTAINMENT_WALK_STEPS = 4

    /** Font step of the containment walk-down. */
    const val CONTAINMENT_WALK_STEP_PX = 0.5f

    /**
     * Containment-first ceiling dilation (px, page-space): the segmentation
     * mask is an estimate of the bubble, not its exact contour. Dilating the
     * ceiling by this margin stops the exact containment predicate from
     * shrinking edge-hugging text for segmentation tightness while still
     * keeping painted ink visually inside the bubble.
     */
    const val CONTAINMENT_CEILING_MARGIN_PX = 6

    /**
     * A tier's contained fit is accepted immediately when its font reaches
     * this fraction of the block's natural OCR-box fit — near-maximal size
     * without evaluating every remaining tier (entry-cost guard: planning
     * runs on the main thread; see MAX_RESCUE_FIT_CALLS_PER_PAGE).
     */
    const val MIN_RESCUE_ACCEPT_FRACTION = 0.9f

    /**
     * Hard page-wide cap on band-fit evaluations the contained-fit rescue may
     * spend (each tier trial + walk-down step is one call). When exhausted,
     * remaining masked blocks skip the rescue and fall through the standard
     * ladder + fail-open tail. Bounds the worst case regardless of block
     * density or JIT warmth. Sized for containment-FIRST participation —
     * every horizontal masked block now runs the rescue once — so the cap
     * covers a fully masked dense page (~13-20 blocks × 1-4 fits), not just
     * the colliding subset.
     */
    const val MAX_RESCUE_FIT_CALLS_PER_PAGE = 96

    // ---- T912 quality repair: band acceptance and sibling font harmony -----

    /**
     * Documented tunable: adaptive bands are accepted only when their fitted
     * font beats the conservative rectangle fit of the same cell by this
     * factor — or when the rectangle cannot host the text at all while the
     * bands consume it fully inside the cell.
     */
    const val BAND_ACCEPT_FACTOR = 1.15f

    /**
     * Documented tunable: same-component sibling fonts are capped DOWN to this
     * multiple of the group's median fitted font (never inflated).
     */
    const val FONT_HARMONY_MEDIAN_CAP = 1.4f
}

/**
 * TachiyomiAT: pure, neighbour-aware text-layout solver for the render stage.
 *
 * **Why this exists.** The previous per-block renderer laid each block out independently
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

    /** Page cap on distinct (groupId, componentId) metadata assignments. */
    private const val MAX_COMPONENT_ASSIGNMENTS_PER_PAGE = 64

    /**
     * Resolve a render plan for every block on the page. Thin wrapper over
     * [planPage] returning only the drawable layouts in render order, so
     * existing callers ([TranslationOverlayView], tests) keep working unchanged.
     */
    fun plan(
        blocks: List<TranslationBlock>,
        pageWidth: Float,
        pageHeight: Float,
        sampleSize: Int,
        renderSourceText: Boolean,
        measurer: TextMeasurer,
    ): List<BlockLayout> = planPage(blocks, pageWidth, pageHeight, sampleSize, renderSourceText, measurer)
        .drawableInRenderOrder

    /**
     * Full planner contract (T912): exactly one explicit [LayoutResult] for
     * every nonblank input — [LayoutOutcome.Draw] with its finalized layout, or
     * [LayoutOutcome.NonDraw] with a reason. Blank chosen texts remain the only
     * intentional absence. Blocks are placed highest-[TranslationBlock.score]
     * first (ties keep reading order); the most confident box takes its
     * preferred placement and lower-confidence boxes treat it as a fixed
     * obstacle. [PageLayoutPlan.drawableInRenderOrder] is the placement-ordered
     * Draw list the renderer consumes.
     */
    fun planPage(
        blocks: List<TranslationBlock>,
        pageWidth: Float,
        pageHeight: Float,
        sampleSize: Int,
        renderSourceText: Boolean,
        measurer: TextMeasurer,
    ): PageLayoutPlan {
        // T924 gate 7.5: observation only — every async planner entry counts;
        // hydrated binds never reach this (TextLayoutCoordinator consults the
        // persisted-plan hydrate hook first).
        TextLayoutPlannerProbe.recordInvocation()
        return planPageInternal(
            blocks,
            pageWidth,
            pageHeight,
            sampleSize,
            renderSourceText,
            measurer,
        ).plan
    }

    /**
     * T912 slice 7 test seam: [planPage] plus the deterministic count of
     * EVALUATED final post-anchor candidates, summed over every colliding
     * block. A plan with a single colliding block reports that block's exact
     * evaluated candidate count (e.g. 8 when every finite candidate failed,
     * or k when the k-th evaluated candidate won).
     */
    data class FitResult(
        val fontSize: Float,
        val lines: List<String>,
        val lineHeight: Float,
        val strokeWidth: Float,
    )

    fun fitTextInMask(
        text: String,
        box: FloatRect,
        geometry: MaskGeometry,
        componentId: Int,
        scale: Float,
        measurer: TextMeasurer,
    ): FitResult {
        val margin = 6f * scale
        val maxW = max(10f * scale, box.width() - 2f * margin)
        val maxH = max(10f * scale, box.height() - 2f * margin)
        val startSize = max(8f * scale, min(maxH / 1.15f, 72f * scale))
        var size = startSize
        val step = 1f * scale
        val minSize = 8f * scale
        val fractions = floatArrayOf(1.0f, 0.92f, 0.85f, 0.78f, 0.70f, 0.62f, 0.55f)
        val boxCx = box.left + box.width() / 2f
        val boxCy = box.top + box.height() / 2f

        while (size >= minSize) {
            val lineH = measurer.lineHeight(size)
            val sw = computeStrokeWidth(size, scale)
            for (frac in fractions) {
                val wrapW = maxW * frac
                val lines = TextLineBreaker.prewrap(text, size, wrapW, measurer)
                val totalH = lines.size * lineH + 2f * sw
                if (totalH > maxH) {
                    break
                }
                val maxTextW = lines.maxOfOrNull { measurer.measureTextWidth(it, size) } ?: 0f
                val blockW = maxTextW + 2f * sw
                val rx1 = floor(boxCx - blockW / 2f).toInt()
                val ry1 = floor(boxCy - totalH / 2f).toInt()
                val rx2 = ceil(boxCx + blockW / 2f).toInt()
                val ry2 = ceil(boxCy + totalH / 2f).toInt()
                if (geometry.containsRectangleInComponent(rx1, ry1, rx2, ry2, componentId)) {
                    return FitResult(size, lines, lineH, sw)
                }
            }
            size -= step
        }
        val fallbackSize = minSize
        val fallbackLines = TextLineBreaker.prewrap(text, fallbackSize, maxW, measurer)
        val fallbackLineH = measurer.lineHeight(fallbackSize)
        val fallbackSw = computeStrokeWidth(fallbackSize, scale)
        return FitResult(fallbackSize, fallbackLines, fallbackLineH, fallbackSw)
    }

    fun fitTextUnmasked(
        text: String,
        box: FloatRect,
        scale: Float,
        measurer: TextMeasurer,
    ): FitResult {
        val margin = 6f * scale
        val maxW = max(10f * scale, box.width() - 2f * margin)
        val maxH = max(10f * scale, box.height() - 2f * margin)
        val startSize = max(8f * scale, min(maxH / 1.15f, 72f * scale))
        var size = startSize
        val step = 1f * scale
        val minSize = 8f * scale

        while (size >= minSize) {
            val lineH = measurer.lineHeight(size)
            val lines = TextLineBreaker.prewrap(text, size, maxW, measurer)
            val sw = computeStrokeWidth(size, scale)
            if (lines.size * lineH + 2f * sw <= maxH) {
                return FitResult(size, lines, lineH, sw)
            }
            size -= step
        }
        val fallbackSize = minSize
        val fallbackLines = TextLineBreaker.prewrap(text, fallbackSize, maxW, measurer)
        val fallbackLineH = measurer.lineHeight(fallbackSize)
        val fallbackSw = computeStrokeWidth(fallbackSize, scale)
        return FitResult(fallbackSize, fallbackLines, fallbackLineH, fallbackSw)
    }

    fun fitTextVertical(
        text: String,
        box: FloatRect,
        scale: Float,
        measurer: TextMeasurer,
    ): FitResult {
        val margin = 6f * scale
        val maxW = max(10f * scale, box.width() - 2f * margin)
        val maxH = max(10f * scale, box.height() - 2f * margin)
        val startSize = max(8f * scale, min(maxH / 1.15f, 72f * scale))
        var size = startSize
        val step = 1f * scale
        val minSize = 8f * scale

        val chars = text.filterNot { it == '\r' || it == '\n' || it == ' ' }
        while (size >= minSize) {
            val charStep = size * VERTICAL_CHAR_STEP
            val colStep = size * VERTICAL_COL_STEP
            val charsPerCol = max(1, (maxH / charStep).toInt())
            val cols = (chars.length + charsPerCol - 1) / charsPerCol
            val totalW = cols * colStep
            if (totalW <= maxW) {
                return FitResult(size, listOf(text), charStep, computeStrokeWidth(size, scale))
            }
            size -= step
        }
        val fallbackSize = minSize
        return FitResult(fallbackSize, listOf(text), fallbackSize * VERTICAL_CHAR_STEP, computeStrokeWidth(fallbackSize, scale))
    }

    /**
     * Desktop 1:1 text layout engine:
     * - Symmetrical mask expansion for single-bubble regions
     * - Natural cluster intersection for multi-region conjoined bubbles
     * - Progressive aspect-ratio contraction fitting into bubble contours
     */
    internal fun planPageInternal(
        blocks: List<TranslationBlock>,
        pageWidth: Float,
        pageHeight: Float,
        sampleSize: Int,
        renderSourceText: Boolean,
        measurer: TextMeasurer,
    ): PagePlanWithAttempts {
        if (blocks.isEmpty()) return PagePlanWithAttempts(PageLayoutPlan(emptyList(), emptyList()), 0)
        val scale = 1f / sampleSize

        val ordered = blocks.withIndex().sortedWith(
            compareByDescending<IndexedValue<TranslationBlock>> { it.value.score }
                .thenBy { it.index },
        )

        val maskGrouping = buildMaskRegions(blocks)
        val resultsByInput = arrayOfNulls<LayoutResult>(blocks.size)
        val drawable = ArrayList<BlockLayout>(blocks.size)
        var planningOrdinal = 0

        // Step 1: Assign blocks to components (1:1 with desktop assignment loop)
        val assignments = HashMap<Int, Pair<Int, Int>>()
        for ((groupId, members) in maskGrouping.groups) {
            if (groupId < 0) continue
            val geometry = maskGrouping.session.geometryFor(groupId) ?: continue
            for (item in members) {
                val text = chosenText(item.value, renderSourceText)
                if (text.isBlank()) continue
                val componentId = resolveComponentId(geometry, item.value) ?: continue
                assignments[item.index] = Pair(groupId, componentId)
            }
        }

        // Step 2: Count regions per component (1:1 with desktop comp_counts)
        val compCounts = HashMap<Pair<Int, Int>, Int>()
        for (indexed in ordered) {
            val text = chosenText(indexed.value, renderSourceText)
            if (text.isBlank()) continue
            val a = assignments[indexed.index]
            if (a != null) {
                compCounts[a] = (compCounts[a] ?: 0) + 1
            }
        }

        // Step 3: Layout each block
        for (indexed in ordered) {
            val inputIndex = indexed.index
            val block = indexed.value
            val text = chosenText(block, renderSourceText)
            if (text.isBlank()) continue
            val ordinal = planningOrdinal++
            val identity = InputIdentity(inputIndex, block.blockId)

            if (block.width <= 0f || block.height <= 0f || block.width.isNaN() || block.height.isNaN()) {
                resultsByInput[inputIndex] = LayoutResult(
                    identity,
                    block,
                    text,
                    ordinal,
                    null,
                    LayoutOutcome.NonDraw(NonDrawReason.INVALID_OR_SUBPIXEL_SOURCE_RECT),
                )
                continue
            }

            val effectiveW = max(1f, block.width)
            val effectiveH = max(1f, block.height)
            val rbox = FloatRect(block.x, block.y, block.x + effectiveW, block.y + effectiveH)

            val hasParent = block.parentWidth > 0f && block.parentHeight > 0f
            val pbox = if (hasParent) {
                FloatRect(block.parentX, block.parentY, block.parentX + block.parentWidth, block.parentY + block.parentHeight)
            } else {
                null
            }
            val anchorCx = if (pbox != null) pbox.left + pbox.width() / 2f else rbox.left + rbox.width() / 2f
            val anchorCy = if (pbox != null) pbox.top + pbox.height() / 2f else rbox.top + rbox.height() / 2f

            val a = assignments[inputIndex]
            val fitBox: FloatRect
            val geometry: MaskGeometry?
            val componentId: Int?
            val groupId: Int?

            if (a != null) {
                groupId = a.first
                componentId = a.second
                geometry = maskGrouping.session.geometryFor(groupId)
                val cb = geometry?.componentBoundingBox(componentId)
                if (geometry != null && cb != null) {
                    val count = compCounts[a] ?: 1
                    if (count == 1) {
                        // Symmetrical expansion from anchor center within component bounds
                        val halfW = max(rbox.width() / 2f, min(anchorCx - cb[0], cb[2] - anchorCx))
                        val halfH = max(rbox.height() / 2f, min(anchorCy - cb[1], cb[3] - anchorCy))
                        fitBox = FloatRect(
                            max(cb[0].toFloat(), anchorCx - halfW),
                            max(cb[1].toFloat(), anchorCy - halfH),
                            min(cb[2].toFloat(), anchorCx + halfW),
                            min(cb[3].toFloat(), anchorCy + halfH),
                        )
                    } else {
                        // Conjoined / multi-region inside one component: intersect rbox with component
                        val inter = geometry.intersectComponentWithRect(
                            componentId,
                            floor(rbox.left).toInt(),
                            floor(rbox.top).toInt(),
                            ceil(rbox.right).toInt(),
                            ceil(rbox.bottom).toInt(),
                        )
                        fitBox = if (inter != null) {
                            FloatRect(inter[0].toFloat(), inter[1].toFloat(), inter[2].toFloat(), inter[3].toFloat())
                        } else {
                            rbox
                        }
                    }
                } else {
                    fitBox = pbox ?: rbox
                }
            } else {
                groupId = null
                componentId = null
                geometry = null
                fitBox = pbox ?: rbox
            }

            val isVertical = block.direction == "TTB" && shouldRenderVertical(text)
            val isMasked = a != null && geometry != null && componentId != null
            val layout: BlockLayout
            if (isMasked) {
                val fitResult = if (isVertical) {
                    fitTextVertical(text, fitBox, scale, measurer)
                } else {
                    fitTextInMask(text, fitBox, geometry!!, componentId!!, scale, measurer)
                }
                val originX = fitBox.left + fitBox.width() / 2f
                val originY = fitBox.top + fitBox.height() / 2f
                layout = BlockLayout(
                    block = block,
                    text = text,
                    isVertical = isVertical,
                    originX = originX,
                    originY = originY,
                    safeW = fitBox.width(),
                    safeH = fitBox.height(),
                    fontSizePx = fitResult.fontSize,
                    strokeWidth = fitResult.strokeWidth,
                    drawAlign = TextAlign.CENTER,
                    clipRect = null,
                    lines = fitResult.lines,
                    maskGeometry = geometry,
                    planGeometryId = groupId,
                    maskComponentId = componentId,
                    cellRect = null,
                    positionedLines = null,
                    conservativeOccupancy = emptyList(),
                    hardClip = HardClip(groupId, componentId, null),
                    maskUsable = true,
                )
            } else {
                val placedObstacles = drawable.map { extentOf(it, measurer) }
                val minLegible = minLegibleFont(pageWidth, pageHeight, scale)
                val rect = computeRects(block, sampleSize, pbox)
                layout = placeBlock(
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
                    regionOverride = pbox,
                )
            }

            drawable.add(layout)
            resultsByInput[inputIndex] = LayoutResult(
                identity = identity,
                block = block,
                chosenText = text,
                planningOrdinal = ordinal,
                renderOrdinal = drawable.size - 1,
                outcome = LayoutOutcome.Draw(layout),
            )
        }

        val results = ArrayList<LayoutResult>(blocks.size)
        for (i in blocks.indices) resultsByInput[i]?.let { results.add(it) }
        return PagePlanWithAttempts(PageLayoutPlan(results, drawable), 0)
    }

    /**
     * Slice 5: build the positioned-line [BlockLayout] for a successful
     * adaptive band fit. The alignment anchor is the origin; `safeW/safeH` are
     * the hard slab bounds; `lines` stays populated with the wrapped
     * (trial-resolved) texts so extentOf/tests keep a uniform handle;
     * `conservativeOccupancy` carries the per-line un-clipped envelopes and
     * `clipRect` stays null (containment is structural: component path + slab).
     * `layout.text` remains the block's chosen text — inserted trial hyphens
     * exist only in the planned lines, never in persisted data.
     */
    private fun adaptiveBlockLayout(
        block: TranslationBlock,
        text: String,
        slab: FloatRect,
        adaptive: AdaptiveResult,
        scale: Float,
    ): BlockLayout = BlockLayout(
        block = block,
        text = text,
        isVertical = false,
        originX = adaptive.anchorX,
        originY = adaptive.anchorY,
        safeW = slab.width(),
        safeH = slab.height(),
        fontSizePx = adaptive.fontPx,
        strokeWidth = computeStrokeWidth(adaptive.fontPx, scale),
        drawAlign = TextAlign.CENTER,
        clipRect = null,
        lines = adaptive.lines.map { it.text },
        positionedLines = adaptive.lines.map { line ->
            PositionedLine(
                text = line.text,
                leftPx = line.leftPx,
                topPx = line.topPx,
                layoutWidthPx = line.layoutWidthPx,
                layoutHeightPx = line.layoutHeightPx,
                conservativeOccupancy = line.conservativeOccupancy,
            )
        },
        conservativeOccupancy = adaptive.lines.map { it.conservativeOccupancy },
    )

    /**
     * T912 quality repair (Fix 2): the beats-the-rectangle rule. Adaptive
     * bands are accepted only when they MEANINGFULLY beat the conservative
     * rectangle layout of the same cell:
     *
     *  - the rectangle fit is the largest font whose wrapped text fits the
     *    cell's content bounds ([SharedCellPlan.fitRegion] — the span content
     *    bbox — else the slab) shrunk by the band planner's stroke inset
     *    (stroke/2 + AA guard + visual padding per side);
     *  - bands win when their fitted font is at least
     *    [TextLayoutTuning.BAND_ACCEPT_FACTOR] × the rectangle fit, OR when
     *    the rectangle overflows at its fitted font (nothing fits the shrunk
     *    rect) while the band result consumes the text fully without overflow.
     *    A successful band fit's lines are the complete wrap of the (trial)
     *    text, each validated band-contained and slab-contained by the band
     *    planner, so "consumes fully without overflow" holds by construction
     *    whenever the fit returned non-null with at least one line.
     */
    private fun bandBeatsRectangleFit(
        text: String,
        adaptiveFit: AdaptiveResult,
        cellPlan: SharedCellPlan,
        scale: Float,
        measurer: TextMeasurer,
    ): Boolean {
        val content = cellPlan.fitRegion ?: cellPlan.slab
        if (content == null || content.width() < 1f || content.height() < 1f) return true
        val inset = TextLayoutTuning.strokeInsetPx(adaptiveFit.fontPx, scale)
        val safeW = (content.width() - 2f * inset).coerceAtLeast(1f)
        val safeH = (content.height() - 2f * inset).coerceAtLeast(1f)
        val rectFitFont = binarySearchFontSize(text, safeW, safeH, safeW, false, scale, measurer)
        if (adaptiveFit.fontPx >= TextLayoutTuning.BAND_ACCEPT_FACTOR * rectFitFont) return true
        return overflows(text, rectFitFont, false, safeW, safeH, measurer) && adaptiveFit.lines.isNotEmpty()
    }

    /**
     * T912 quality repair (Fix 3): font harmony for same-component siblings.
     * For every `(group, componentId)` cell group with at least two accepted
     * Draw layouts, members whose fitted font exceeds
     * [TextLayoutTuning.FONT_HARMONY_MEDIAN_CAP] × the group's median font
     * are capped DOWN to that multiple (never inflated). Deterministic:
     * groups are processed in first-appearance (render) order, members by
     * input index; the median of an even-sized group is the LOWER-middle
     * element of the sorted fonts. Unmasked/legacy (non-cell) blocks and
     * bounds-rect cells (no component id) are never touched.
     */
    private fun applySiblingFontHarmony(
        drawable: ArrayList<BlockLayout>,
        resultsByInput: Array<LayoutResult?>,
        blocks: List<TranslationBlock>,
        cellPlans: Map<Int, SharedCellPlan>,
        grouping: MaskGrouping,
        collisionGap: Int,
        sampleSize: Int,
        scale: Float,
        minLegible: Float,
        pageWidth: Float,
        pageHeight: Float,
        measurer: TextMeasurer,
    ) {
        val groups = LinkedHashMap<Long, MutableList<Int>>()
        for (i in blocks.indices) {
            val result = resultsByInput[i] ?: continue
            if (result.outcome !is LayoutOutcome.Draw) continue
            val cellPlan = cellPlans[i]?.takeUnless { it.empty } ?: continue
            val componentId = cellPlan.componentId ?: continue
            if (!cellPlan.optimized || cellPlan.slab == null) continue
            val groupId = grouping.groupByIndex[i] ?: continue
            if (groupId < 0) continue
            groups.getOrPut((groupId.toLong() shl 32) or (componentId.toLong() and 0xFFFF_FFFFL)) {
                mutableListOf()
            }.add(i)
        }
        for ((_, members) in groups) {
            if (members.size < 2) continue
            val fonts = members.map { i ->
                (resultsByInput[i]!!.outcome as LayoutOutcome.Draw).layout.fontSizePx
            }
            // Even-sized groups take the lower-middle element (documented).
            val median = fonts.sorted()[(fonts.size - 1) / 2]
            val cap = TextLayoutTuning.FONT_HARMONY_MEDIAN_CAP * median
            for (inputIndex in members) {
                val result = resultsByInput[inputIndex] ?: continue
                val layout = (result.outcome as? LayoutOutcome.Draw)?.layout ?: continue
                if (layout.fontSizePx <= cap) continue
                val renderOrdinal = result.renderOrdinal ?: continue
                val cellPlan = cellPlans.getValue(inputIndex)
                val replacement = harmonizedReplacement(
                    layout = layout,
                    block = blocks[inputIndex],
                    cellSpans = cellPlan.spans,
                    slab = cellPlan.slab,
                    fitRegion = cellPlan.fitRegion,
                    cap = cap,
                    collisionGap = collisionGap,
                    sampleSize = sampleSize,
                    scale = scale,
                    minLegible = minLegible,
                    pageWidth = pageWidth,
                    pageHeight = pageHeight,
                    obstacles = drawable.subList(0, renderOrdinal).map { extentOf(it, measurer) },
                    measurer = measurer,
                )
                drawable[renderOrdinal] = replacement
                resultsByInput[inputIndex] = result.copy(outcome = LayoutOutcome.Draw(replacement))
            }
        }
    }

    /**
     * T912 quality repair (Fix 3): the replacement layout for one member
     * capped DOWN to [cap]. Fonts are never inflated. Legacy members are
     * re-wrapped at the capped font (vertical members keep their empty line
     * list — columns derive from the font at draw time) in their unchanged
     * box. Adaptive members re-run the band fitter with the capped maximum
     * font (bounded); when the refit fails — or the accepted form carried a
     * containment clip a re-fit could not honor — the member takes its legacy
     * rectangle form: the exact [placeBlock] call with the same fit region
     * the placement loop would have made without bands. Render metadata
     * (cell rect, ids, hard clip) is preserved on every path.
     *
     * Internal test seam: the refit-failure fallback cannot be reached
     * through [planPage] with well-formed span geometry (band fitting is
     * downward-closed in the font for a fixed cell), so tests drive this
     * function directly with a failing span set.
     */
    internal fun harmonizedReplacement(
        layout: BlockLayout,
        block: TranslationBlock,
        cellSpans: List<MaskGeometry.RowSpan>,
        slab: FloatRect?,
        fitRegion: FloatRect?,
        cap: Float,
        collisionGap: Int,
        sampleSize: Int,
        scale: Float,
        minLegible: Float,
        pageWidth: Float,
        pageHeight: Float,
        obstacles: List<FloatRect>,
        measurer: TextMeasurer,
    ): BlockLayout {
        val text = layout.text
        if (layout.positionedLines == null) {
            return layout.copy(
                fontSizePx = cap,
                strokeWidth = computeStrokeWidth(cap, scale),
                lines = if (layout.isVertical) layout.lines else cjkWrap(text, cap, layout.safeW, measurer),
            )
        }
        val refit = if (slab != null && layout.clipRect == null) {
            AdaptiveBandPlanner.fitAdaptiveBands(
                text = text,
                cellSpans = cellSpans,
                slab = slab,
                scale = scale,
                collisionGapPx = collisionGap,
                measurer = measurer,
                minFontPx = FIT_MIN_FONT_PX * scale,
                maxFontPx = cap,
                blockCenterX = block.x + block.width / 2f,
                blockCenterY = block.y + block.height / 2f,
            )
        } else {
            null
        }
        if (refit != null) {
            return adaptiveBlockLayout(block, text, slab!!, refit, scale).copy(
                maskGeometry = layout.maskGeometry,
                planGeometryId = layout.planGeometryId,
                maskComponentId = layout.maskComponentId,
                cellRect = layout.cellRect,
                hardClip = layout.hardClip,
            )
        }
        val rect = computeRects(block, sampleSize, fitRegion)
        return placeBlock(
            block = block,
            text = text,
            isVertical = false,
            rect = rect,
            obstacles = obstacles,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            minLegible = minLegible,
            scale = scale,
            measurer = measurer,
            regionOverride = fitRegion,
        )
    }

    // ---- T912 slice 7: finite final post-anchor safety ---------------------

    /** Outcome of the bounded final post-anchor resolution for one colliding block. */
    private class FinalResolution(
        /**
         * The accepted layout — never null since the T912 repair (R2): a
         * ladder that exhausts every candidate accepts a clipped draw instead.
         */
        val layout: BlockLayout,
        /** Deterministic count of EVALUATED candidates (duplicates are skipped uncounted). */
        val attempts: Int,
    )

    /** One axis-aligned free rectangle for the clip/refit candidate, with its side. */
    private class FreeRectCandidate(val rect: FloatRect, val direction: Int)

    /**
     * Single conservative occupancy rectangle of a finalized layout (un-clipped):
     *  - adaptive layouts (positioned lines): the union bounding box of the
     *    per-line conservative occupancy rects (already stroke+AA+half-gap
     *    inflated by the band planner);
     *  - legacy layouts: [extentOf] inflated on every side by
     *    `stroke + AA_GUARD + gap/2`.
     */
    private fun conservativeOccupancyOf(
        layout: BlockLayout,
        measurer: TextMeasurer,
        scale: Float,
        gapHalf: Float,
    ): FloatRect {
        val perLine = layout.conservativeOccupancy
        if (layout.positionedLines != null && perLine.isNotEmpty()) {
            var left = Float.MAX_VALUE
            var top = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            var bottom = -Float.MAX_VALUE
            for (rect in perLine) {
                if (rect.left < left) left = rect.left
                if (rect.top < top) top = rect.top
                if (rect.right > right) right = rect.right
                if (rect.bottom > bottom) bottom = rect.bottom
            }
            return FloatRect(left, top, right, bottom)
        }
        val inflate = computeStrokeWidth(layout.fontSizePx, scale) +
            TextLayoutTuning.aaGuard(scale) + gapHalf
        val extent = extentOf(layout, measurer)
        return FloatRect(
            extent.left - inflate,
            extent.top - inflate,
            extent.right + inflate,
            extent.bottom + inflate,
        )
    }

    /**
     * The painted (un-inflated) geometry of a finalized layout: the positioned
     * line rects for adaptive layouts, [extentOf] otherwise. This is the "box"
     * for page/component/cell containment checks — the conservative occupancy is
     * a planning envelope and may legitimately exceed them.
     */
    private fun inkRectOf(layout: BlockLayout, measurer: TextMeasurer): FloatRect {
        val lines = layout.positionedLines
        if (lines != null && lines.isNotEmpty()) {
            var left = Float.MAX_VALUE
            var top = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            var bottom = -Float.MAX_VALUE
            for (line in lines) {
                val l = line.leftPx.toFloat()
                val t = line.topPx.toFloat()
                if (l < left) left = l
                if (t < top) top = t
                if (l + line.layoutWidthPx > right) right = l + line.layoutWidthPx
                if (t + line.layoutHeightPx > bottom) bottom = t + line.layoutHeightPx
            }
            return FloatRect(left, top, right, bottom)
        }
        return extentOf(layout, measurer)
    }

    /** Closed containment: [inner] lies inside [outer] (touching edges allowed). */
    private fun containedIn(inner: FloatRect, outer: FloatRect): Boolean =
        inner.left >= outer.left - 0.001f &&
            inner.top >= outer.top - 0.001f &&
            inner.right <= outer.right + 0.001f &&
            inner.bottom <= outer.bottom + 0.001f

    /**
     * True when every integer pixel row touched by [rect], inflated by
     * [inflate], is continuously owned by one row-major component span.
     * No dense mask, row map, or candidate-local collection is built.
     */
    internal fun paintRectContainedInSpans(
        rect: FloatRect,
        inflate: Float,
        spans: List<MaskGeometry.RowSpan>,
    ): Boolean {
        if (spans.isEmpty()) return false
        val left = floor(rect.left - inflate).toInt()
        val top = floor(rect.top - inflate).toInt()
        val right = ceil(rect.right + inflate).toInt()
        val bottom = ceil(rect.bottom + inflate).toInt()
        if (left >= right || top >= bottom) return false

        var spanIndex = 0
        var y = top
        while (y < bottom) {
            while (spanIndex < spans.size && spans[spanIndex].y < y) spanIndex++
            var rowIndex = spanIndex
            var covered = false
            while (rowIndex < spans.size && spans[rowIndex].y == y) {
                val span = spans[rowIndex]
                if (span.start <= left && span.endExclusive >= right) {
                    covered = true
                    break
                }
                if (span.start > left) break
                rowIndex++
            }
            if (!covered) return false
            y++
        }
        return true
    }

    /** Exact component-row validation for one finalized layout. */
    internal fun paintEnvelopeContainedInSpans(
        layout: BlockLayout,
        spans: List<MaskGeometry.RowSpan>,
        measurer: TextMeasurer,
        scale: Float,
    ): Boolean {
        if (spans.isEmpty()) return false
        // Match the overlay's actual outline floor, then include an AA guard.
        val inflate = ceil(max(2f, layout.strokeWidth) / 2f + TextLayoutTuning.aaGuard(scale))
        val lines = layout.positionedLines
        if (lines != null) {
            if (lines.isEmpty()) return false
            // Painted-envelope model (visible-fit strategy §1): measured
            // advance + measured line height. layoutWidthPx already embeds the
            // shaping guard and must NOT be inflated a second time — doing so
            // rejects genuinely safe fits on curved ceilings.
            val lineH = measurer.lineHeight(layout.fontSizePx)
            for (line in lines) {
                if (line.text.isEmpty()) continue
                val advance = measurer.measureTextWidth(line.text, layout.fontSizePx)
                val rect = FloatRect(
                    line.leftPx.toFloat(),
                    line.topPx.toFloat(),
                    line.leftPx + advance,
                    line.topPx + lineH,
                )
                if (!paintRectContainedInSpans(rect, inflate, spans)) return false
            }
            return true
        }
        return paintRectContainedInSpans(inkRectOf(layout, measurer), inflate, spans)
    }

    /**
     * T912: ceiling spans dilated by [margin] px per row (merged when dilated
     * spans touch). The mask is a segmentation estimate, not ground truth — a
     * small dilation stops the exact containment predicate from punishing
     * edge-hugging text for segmentation tightness.
     */
    private fun dilateSpans(
        spans: List<MaskGeometry.RowSpan>,
        margin: Int,
    ): List<MaskGeometry.RowSpan> {
        if (margin <= 0 || spans.isEmpty()) return spans
        val out = ArrayList<MaskGeometry.RowSpan>(spans.size)
        for (span in spans) {
            val dilated = MaskGeometry.RowSpan(
                span.y,
                max(0, span.start - margin),
                span.endExclusive + margin,
            )
            val previous = out.lastOrNull()
            if (previous != null && previous.y == dilated.y && previous.endExclusive >= dilated.start) {
                out[out.lastIndex] = previous.copy(endExclusive = max(previous.endExclusive, dilated.endExclusive))
            } else {
                out += dilated
            }
        }
        return out
    }

    /** Row spans of [spans] clipped to [rect] (input is row-major sorted). */
    private fun clipSpansToRect(
        spans: List<MaskGeometry.RowSpan>,
        rect: FloatRect,
    ): List<MaskGeometry.RowSpan> {
        val top = floor(rect.top).toInt()
        val bottom = ceil(rect.bottom).toInt()
        val left = floor(rect.left).toInt()
        val right = ceil(rect.right).toInt()
        val out = ArrayList<MaskGeometry.RowSpan>()
        for (span in spans) {
            if (span.y < top) continue
            if (span.y >= bottom) break
            val start = max(span.start, left)
            val end = min(span.endExclusive, right)
            if (end > start) out += MaskGeometry.RowSpan(span.y, start, end)
        }
        return out
    }

    /** Per-page budget for the contained-fit rescue's band-fit evaluations. */
    private class RescueBudget(var remaining: Int = TextLayoutTuning.MAX_RESCUE_FIT_CALLS_PER_PAGE)

    /**
     * T912 containment-first ceiling: the row spans a masked block's text must
     * stay inside, resolvable for EVERY masked block — not only shared-cell
     * members. Priority:
     *  1. the block's own optimized cell spans (span-mode shared cell);
     *  2. the block's deterministically assigned component in its group's
     *     shared geometry (beyond-cap overflow members, group members whose
     *     partition produced no spans);
     *  3. the raw persisted mask converted under the page [budgets] (groupless
     *     masks), taking the OCR-overlapping component when one exists.
     * Null when no usable ceiling exists (dims mismatch page, conversion
     * budget/fallback) — the caller keeps the legacy path for the block.
     */
    private fun rescueCeilingSpans(
        block: TranslationBlock,
        cellPlan: SharedCellPlan?,
        grouping: MaskGrouping,
        inputIndex: Int,
        pageWidth: Float,
        pageHeight: Float,
        budgets: MaskConversionBudgets,
    ): List<MaskGeometry.RowSpan>? {
        cellPlan?.spans?.takeIf { it.isNotEmpty() }?.let { return it }
        val mask = block.segmentationMask ?: return null
        // Priority 2: the group's shared geometry — its spans are page-space
        // only when the geometry was built at page dimensions.
        val groupId = grouping.groupByIndex[inputIndex]
        if (groupId != null) {
            val geometry = grouping.session.geometryFor(groupId)
            if (geometry != null &&
                geometry.width == pageWidth.roundToInt() &&
                geometry.height == pageHeight.roundToInt()
            ) {
                resolveComponentId(geometry, block)?.let { id -> return geometry.components[id].spans }
                return geometry.spans
            }
        }
        // Priority 3: the raw persisted mask — RLE offsets are page-space
        // only when the mask itself is page-sized.
        if (mask.width != pageWidth.roundToInt() || mask.height != pageHeight.roundToInt()) return null
        return when (val result = MaskGeometry.fromOrderedRle(mask, budgets)) {
            is OrderedMaskResult.Success -> {
                resolveComponentId(result.geometry, block)?.let { id ->
                    result.geometry.components[id].spans
                } ?: result.geometry.spans
            }
            is OrderedMaskResult.Fallback -> null
        }
    }

    /**
     * T912 contained-fit rescue (Director-validated iteration 9 model): the
     * OCR bounding box is the home position. The text column keeps the OCR
     * box's x-range while tiers grow it VERTICALLY only; the font is capped
     * at the LARGER of the OCR box's natural reflow fit and the legacy
     * rectangle fit ([legacyFitW]/[legacyFitH]) — the size the old plan
     * actually rendered is preserved wherever the mask can host it, and the
     * exact containment walk shrinks only when it must. [ceilingSpans] — the
     * block's own cell spans, its assigned mask component, or the raw
     * persisted mask — is a pure ceiling validated per row with the exact
     * painted-envelope predicate. A tier is accepted immediately when its
     * contained font reaches [TextLayoutTuning.MIN_RESCUE_ACCEPT_FRACTION] of
     * the cap; otherwise the largest fully-contained font across tiers wins.
     * Returns null when there is no ceiling, no tier contains the complete
     * text at the render floor, or the [budget] is exhausted — the
     * mask-unusable signal.
     *
     * Bounded: every band-fit call charges the page-wide [budget]; no dense
     * allocation.
     */
    private fun containedReflowRescue(
        block: TranslationBlock,
        text: String,
        cellPlan: SharedCellPlan?,
        ceilingSpans: List<MaskGeometry.RowSpan>?,
        legacyFitW: Float,
        legacyFitH: Float,
        scale: Float,
        collisionGap: Int,
        measurer: TextMeasurer,
        budget: RescueBudget,
    ): BlockLayout? {
        val spans = ceilingSpans?.takeIf { it.isNotEmpty() } ?: return null
        val ocrLeft = block.x
        val ocrTop = block.y
        val ocrRight = block.x + block.width
        val ocrBottom = block.y + block.height
        val ocrW = ocrRight - ocrLeft
        val ocrH = ocrBottom - ocrTop
        if (ocrW < 4f || ocrH < 4f) return null
        val centerX = block.x + block.width / 2f
        val centerY = block.y + block.height / 2f
        // Font cap: the LARGER of the OCR box's natural reflow fit and the
        // legacy rectangle fit. The OCR fit alone regressed visible sizes on
        // real pages (page-15: 27→17, 24→14, 31→21) because the old plan's
        // larger fonts came from its wider boxes; the legacy fit restores
        // that size head-room and the containment walk still shrinks when the
        // mask cannot host it.
        val ocrFit = binarySearchFontSize(text, ocrW, ocrH, ocrW, false, scale, measurer)
        val maxFont = if (legacyFitW >= 4f && legacyFitH >= 4f) {
            max(
                ocrFit,
                binarySearchFontSize(text, legacyFitW, legacyFitH, legacyFitW, false, scale, measurer),
            )
        } else {
            ocrFit
        }
        val fitMinFont = FIT_MIN_FONT_PX * scale
        val regions = ArrayList<FloatRect>(TextLayoutTuning.OCR_GROW_FACTORS.size + 1)
        // T912 multi-block fix: when the assigned cell's fitRegion is wider than
        // the raw OCR column (common for multi-block bubbles where OCR detects a
        // narrow vertical Japanese column), use that wider x-range as the base for
        // the vertical-growth tiers. This prevents English text from being trapped
        // in a 40px-wide corridor because it was transcribed from a 40px-wide
        // vertical Japanese column.
        val tierLeft: Float
        val tierRight: Float
        val fitReg = cellPlan?.fitRegion
        if (fitReg != null && fitReg.width() > ocrW) {
            tierLeft = fitReg.left
            tierRight = fitReg.right
        } else {
            tierLeft = ocrLeft
            tierRight = ocrRight
        }
        for (factor in TextLayoutTuning.OCR_GROW_FACTORS) {
            val halfH = ocrH * factor / 2f
            regions += FloatRect(tierLeft, centerY - halfH, tierRight, centerY + halfH)
        }
        // The unbounded cell-content tier exists ONLY for a real assigned
        // component cell — the mask is a ceiling, never a region supplier.
        if (cellPlan?.componentId != null) cellPlan.fitRegion?.let { regions += it }
        var best: BlockLayout? = null
        var bestFont = -1f
        for (region in regions) {
            if (region.width() < 4f || region.height() < 4f) continue
            val regionSpans = clipSpansToRect(spans, region)
            if (regionSpans.isEmpty()) continue
            // The band fitter's internal row check can accept a top font whose
            // guarded ink crosses the real contour; walk the font down under
            // the exact predicate until contained or the floor is reached.
            // Containment class always outranks font size.
            var ceiling = maxFont
            var steps = 0
            while (steps < TextLayoutTuning.MAX_CONTAINMENT_WALK_STEPS) {
                steps++
                if (budget.remaining <= 0) return best
                budget.remaining--
                val fit = AdaptiveBandPlanner.fitAdaptiveBands(
                    text = text,
                    cellSpans = regionSpans,
                    slab = region,
                    scale = scale,
                    collisionGapPx = collisionGap,
                    measurer = measurer,
                    minFontPx = fitMinFont,
                    maxFontPx = ceiling,
                    blockCenterX = centerX,
                    blockCenterY = centerY,
                )
                ?: break
                val candidate = adaptiveBlockLayout(block, text, region, fit, scale)
                if (paintEnvelopeContainedInSpans(candidate, spans, measurer, scale)) {
                    if (fit.fontPx >= TextLayoutTuning.MIN_RESCUE_ACCEPT_FRACTION * maxFont) {
                        // Near-natural contained fit: accept immediately and
                        // leave the remaining budget for other blocks on the
                        // page (entry-cost guard).
                        return candidate
                    }
                    if (fit.fontPx > bestFont) {
                        bestFont = fit.fontPx
                        best = candidate
                    }
                    break // contained here; a smaller font in this tier cannot win
                }
                val next = fit.fontPx - TextLayoutTuning.CONTAINMENT_WALK_STEP_PX
                if (next < fitMinFont) break
                ceiling = next
            }
        }
        return best
    }

    /** Exact component-row validation for a finalized masked candidate. */
    private fun paintEnvelopeContainedInComponent(
        layout: BlockLayout,
        cellPlan: SharedCellPlan?,
        measurer: TextMeasurer,
        scale: Float,
    ): Boolean {
        if (cellPlan?.componentId == null || cellPlan.spans.isEmpty()) return true
        return paintEnvelopeContainedInSpans(layout, cellPlan.spans, measurer, scale)
    }

    /** Resolver-entry-based masked relocation budget. */
    internal fun maskedShiftCap(block: TranslationBlock, pageWidth: Float, pageHeight: Float): Float =
        min(
            TextLayoutTuning.MAX_MASK_SHIFT_REGION_FRACTION * min(block.width, block.height),
            TextLayoutTuning.MAX_MASK_SHIFT_PAGE_FRACTION * min(pageWidth, pageHeight),
        ).coerceAtLeast(0f)

    /** Anchor-distance check shared by the masked candidate-8 path and tests. */
    internal fun relocationWithinCap(
        entryX: Float,
        entryY: Float,
        candidateX: Float,
        candidateY: Float,
        cap: Float,
    ): Boolean = hypot(candidateX - entryX, candidateY - entryY) <= cap + 0.001f

    /**
     * Hard-cell disjointness exemption: when BOTH layouts carry a hard cell and
     * the cells do not overlap (touching is not overlap), the pair is structurally
     * pixel-separated by their clips — occupancy overlap between them is NOT a
     * collision.
     */
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
     * T912 repair (R2): the ladder NEVER drops the block. When every candidate
     * fails validation, a clipped draw is accepted (visibility override —
     * placement safety may never remove text from the page). Unmasked layouts
     * retain the original candidate-8 free-rect fallback.
     *
     * T912 contained-fit rescue (Director-validated model): on the masked
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
    private fun resolvePostAnchorPlacement(
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
        // T912 contained-fit rescue: precomputed by the placement loop for
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
                        if (other.top < occupancy.bottom && occupancy.top < other.bottom &&
                            other.left < occupancy.left && other.right > x1
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
                        if (other.top < occupancy.bottom && occupancy.top < other.bottom &&
                            other.right > occupancy.right && other.left < x2
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
                        if (other.left < occupancy.right && occupancy.left < other.right &&
                            other.top < occupancy.top && other.bottom > y1
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
                        if (other.left < occupancy.right && occupancy.left < other.right &&
                            other.bottom > occupancy.bottom && other.top < y2
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
            val refitWithinCap = block.segmentationMask == null || relocationWithinCap(
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

        // T912 repair (R2): ladder exhausted → accept a CLIPPED DRAW, never
        // `NonDraw(NO_DISJOINT_POST_ANCHOR_PLACEMENT)` (visibility override:
        // placement safety may never remove text from the page).
        // Unmasked layouts preserve the prior candidate-8 free-rect fallback.
        //
        // T912 contained-fit tail, in priority order:
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
     *
     * T912 slice 7: [allowGrowth] = false suppresses ONLY step 2's growth (the
     * baseline candidate re-places the pre-growth base rect through the exact
     * same fit/anchor/clip path). Every existing call defaults to true.
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
        allowGrowth: Boolean = true,
    ): BlockLayout {
        var baseX = rect.baseX
        var baseY = rect.baseY
        var baseW = rect.baseW
        var baseH = rect.baseH
        val safePad = max(0f, (baseW - rect.safeW) / 2f)

        val hasParent = block.parentWidth > 0f && block.parentHeight > 0f
        val parentBox = if (hasParent) {
            FloatRect(
                block.parentX,
                block.parentY,
                block.parentX + block.parentWidth,
                block.parentY + block.parentHeight,
            )
        } else {
            null
        }

        val anchorToOcrCenter = regionOverride != null ||
            hasParent ||
            block.label == 2 ||
            (block.direction == "TTB" && !isVertical)
        val region = if (parentBox != null) {
            parentBox
        } else if (regionOverride != null) {
            regionOverride
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

        val grown = if (allowGrowth) {
            growIntoFreeSpaceIfNeeded(
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
        } else {
            GrownBox(baseX, baseY, baseW, baseH, safeW, safeH, fontSize, grewRight = false, grewLeft = false)
        }
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

        if (hasParent && parentBox != null) {
            val targetRegion = regionOverride ?: parentBox
            val centerX = targetRegion.left + targetRegion.width() / 2f
            val centerY = targetRegion.top + targetRegion.height() / 2f
            originX = centerX
            originY = centerY
            drawAlign = TextAlign.CENTER

            safeW = max(1f, targetRegion.width() - 8f)
            safeH = max(1f, targetRegion.height() - 8f)
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
            clipRect = null
        } else if (regionOverride != null) {
            val centerX = regionOverride.left + regionOverride.width() / 2f
            val centerY = regionOverride.top + regionOverride.height() / 2f
            originX = centerX
            originY = centerY
            drawAlign = TextAlign.CENTER

            safeW = max(1f, regionOverride.width() - 8f)
            safeH = max(1f, regionOverride.height() - 8f)
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
            clipRect = null
        } else if (anchorToOcrCenter) {
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

    /**
     * T912 slice 6: the bounded long-`text_free` placement decision for one
     * eligible block — the [rect] to place and the [regionOverride] to place
     * it with (null keeps the block's own legacy containment/anchor behavior).
     */
    private class FreeTextWideningPlan(val rect: RectResult, val regionOverride: FloatRect?)

    /**
     * T912 slice 6: the bounded long-`text_free` widening trial, or null when
     * the block must take the exact legacy path.
     *
     * Eligibility (architecture "Slice 6", evaluated in order):
     *  0. path precondition — the trial is defined only on the plain unmasked
     *     legacy path (today's `regionOverride == null`); masked blocks are
     *     governed by the slice 2/3/5 mask/cell contracts and keep their
     *     current behavior (where the reshape can never fire anyway);
     *  1. `label == 2`;
     *  2. no valid parent;
     *  3. resolved horizontal (`isVertical == false`);
     *  4. at least [TextLayoutTuning.FREE_TEXT_MIN_GRAPHEMES] non-whitespace
     *     graphemes;
     *  5. original OCR `height / width >= [TextLayoutTuning.FREE_TEXT_TALL_RATIO]`
     *     (float division, `width > 0` guarded);
     *  6. OCR-box fit pressure: the largest font fitting the unreshaped OCR box
     *     (parentless padding, [binarySearchFontSize]) is below [minLegible] OR
     *     the text overflows the box at [minLegible].
     *
     * Candidate 1 is always the UNRESHAPED original OCR box; candidates 2/3
     * keep the OCR center X/Y and the original height with widths
     * `min(w*factor, w + 0.08*pageShortSide, page-clamped, collision-free)` for
     * the two [TextLayoutTuning] factors, deduplicated after clamping. The
     * smallest wider candidate whose fitted font improves by
     * [TextLayoutTuning.FREE_TEXT_MIN_FONT_GAIN] OR that removes overflow wins;
     * otherwise the OCR baseline is kept with NO region override so the block
     * takes today's exact label-2 legacy behavior (containment clip preserved
     * through the OCR-center anchor branch). The legacy reshape is unreachable
     * on this path. Final collision validation stays slice 7's job.
     */
    private fun boundedFreeTextWideningPlan(
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
     * T912 slice 6: widest symmetric box centered at [centerX] that stays on
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
                    while (i < text.length && !isCJK(text[i]) && !text[i].isWhitespace() && text[i] != '\n') {
                        if (text[i] == '-' && i > start) {
                            i++
                            break
                        }
                        i++
                    }
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
     *
     * Slice 5: adaptive layouts use the union bounding box of the per-line
     * conservative occupancy rects, so every block kind is measured with the
     * same conservative convention.
     */
    private fun extentOf(layout: BlockLayout, measurer: TextMeasurer): FloatRect {
        if (layout.positionedLines != null && layout.conservativeOccupancy.isNotEmpty()) {
            var left = Float.MAX_VALUE
            var top = Float.MAX_VALUE
            var right = Float.MIN_VALUE
            var bottom = Float.MIN_VALUE
            for (rect in layout.conservativeOccupancy) {
                if (rect.left < left) left = rect.left
                if (rect.top < top) top = rect.top
                if (rect.right > right) right = rect.right
                if (rect.bottom > bottom) bottom = rect.bottom
            }
            return FloatRect(left, top, right, bottom)
        }
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

    private class MaskGrouping(
        val regions: Map<Int, FloatRect>,
        val groupByIndex: Map<Int, Int>,
        val session: SharedMaskSession,
        /** Input-ordered members per group id (grouped and groupless). */
        val groups: Map<Int, List<IndexedValue<TranslationBlock>>>,
    )

    /**
     * Build strict layout regions from persisted segmentation masks. A fused mask
     * may contain multiple OCR children; partition its bounds at the midpoints of
     * the child centers so one child cannot consume the other's space.
     *
     * Grouping walks blocks in INPUT order through a [SharedMaskSession] instead
     * of hashing whole [BubbleMaskRle] data classes. Grouped masks share their
     * per-page group id (fingerprint-bucketed, full-equality verified); groupless
     * masks keep a deterministic reference-identity group, so the partition math
     * below produces the SAME outputs as before for the production case of one
     * shared mask instance per bubble.
     */
    private fun buildMaskRegions(blocks: List<TranslationBlock>): MaskGrouping {
        val session = SharedMaskSession()
        val regions = HashMap<Int, FloatRect>()
        val groups = LinkedHashMap<Int, MutableList<IndexedValue<TranslationBlock>>>()
        val groupByIndex = HashMap<Int, Int>()
        for (item in blocks.withIndex()) {
            val mask = item.value.segmentationMask ?: continue
            val groupId = session.groupIdOf(mask)
            groupByIndex[item.index] = groupId
            groups.getOrPut(groupId) { mutableListOf() }.add(item)
        }

        for ((_, indexedBlocks) in groups) {
            val mask = indexedBlocks.first().value.segmentationMask!!
            val bounds = mask.bounds
            val maskRect = FloatRect(
                bounds[0].toFloat(),
                bounds[1].toFloat(),
                bounds[2].toFloat(),
                bounds[3].toFloat(),
            )
            if (indexedBlocks.size == 1) {
                val single = indexedBlocks.single()
                val block = single.value
                if (block.parentWidth > 0f && block.parentHeight > 0f) {
                    val anchorX = block.parentX + block.parentWidth / 2f
                    val halfW = max(block.width / 2f, min(anchorX - maskRect.left, maskRect.right - anchorX))
                    regions[single.index] = FloatRect(
                        max(maskRect.left, anchorX - halfW),
                        maskRect.top,
                        min(maskRect.right, anchorX + halfW),
                        maskRect.bottom,
                    )
                } else {
                    regions[single.index] = maskRect
                }
                continue
            }

            // If blocks have distinct detected text_bubble boxes, use each block's own bubble box
            val distinctParentBoxes = indexedBlocks.all { it.value.parentWidth > 0f && it.value.parentHeight > 0f }
            val uniqueParents = indexedBlocks.map { "${it.value.parentX}_${it.value.parentY}_${it.value.parentWidth}_${it.value.parentHeight}" }.distinct()

            if (distinctParentBoxes && uniqueParents.size == indexedBlocks.size) {
                for (item in indexedBlocks) {
                    regions[item.index] = FloatRect(
                        item.value.parentX,
                        item.value.parentY,
                        item.value.parentX + item.value.parentWidth,
                        item.value.parentY + item.value.parentHeight,
                    )
                }
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
                val pBox = if (item.value.parentWidth > 0f && item.value.parentHeight > 0f) {
                    FloatRect(
                        item.value.parentX,
                        item.value.parentY,
                        item.value.parentX + item.value.parentWidth,
                        item.value.parentY + item.value.parentHeight,
                    )
                } else {
                    maskRect
                }

                val region = if (splitX) {
                    FloatRect(
                        if (position == 0) pBox.left else cuts[position - 1],
                        pBox.top,
                        if (position == ordered.lastIndex) pBox.right else cuts[position],
                        pBox.bottom,
                    )
                } else {
                    FloatRect(
                        pBox.left,
                        if (position == 0) pBox.top else cuts[position - 1],
                        pBox.right,
                        if (position == ordered.lastIndex) pBox.bottom else cuts[position],
                    )
                }
                regions[item.index] = region
            }
        }
        session.convertAll()
        return MaskGrouping(regions, groupByIndex, session, groups)
    }

    /** Per-input slice-3 shared-cell decision; null means the legacy region path. */
    private class SharedCellPlan(
        /**
         * Hard disjoint slab; null only when the member has no usable cell at
         * all (degenerate overflow slab / no cell from the partition).
         */
        val slab: FloatRect?,
        /** Placement override inside the slab; null for empty/non-optimized cells. */
        val fitRegion: FloatRect?,
        val optimized: Boolean,
        /** Non-null only for a span-mode member of a converted geometry group. */
        val componentId: Int?,
        /**
         * True when the cell owns no usable component pixels → R1 legacy-region
         * fallback (never `NonDraw(EMPTY_SHARED_CELL)` since the repair).
         */
        val empty: Boolean,
        /**
         * Slice 5: the cell's slab-intersected row-major spans (empty in
         * bounds-rect mode). Input to the adaptive band planner.
         */
        val spans: List<MaskGeometry.RowSpan> = emptyList(),
    )

    /**
     * Slice 3: build disjoint shared-cell plans per (group, componentId) group
     * of assigned nonblank members.
     *
     *  - Converted geometry with page-matching dimensions: partition per
     *    component so blocks on different components of one RLE never share
     *    cuts. Single-member component groups collapse to one cell over the
     *    component bounds (mask bounds ≈ component bounds keeps single masked
     *    bubbles on effectively the current behaviour). Members beyond
     *    [MaskTextRegionPlanner.MAX_SHARED_BLOCKS_OPTIMIZED] receive a disjoint
     *    bounds-rect slab cell from the same partition (R4b) — no component
     *    path, but exemption-protected.
     *  - Grouped mask whose conversion FELL BACK (empty runs / caps), OR whose
     *    geometry dimensions mismatch the page (R4a): disjoint BOUNDS-RECT mode
     *    cells over the mask bounds rectangle, scaled into page space when the
     *    dims mismatch — still one cell per member, still collision-disjoint,
     *    no geometry ids.
     *  - Groupless masks (reference/unique caps): no cells — the legacy
     *    [buildMaskRegions] partition stays in force.
     */
    private fun buildSharedCellPlans(
        blocks: List<TranslationBlock>,
        grouping: MaskGrouping,
        pageWidth: Float,
        pageHeight: Float,
        scale: Float,
        renderSourceText: Boolean,
    ): Map<Int, SharedCellPlan> {
        val plans = HashMap<Int, SharedCellPlan>()
        if (grouping.groups.isEmpty()) return plans
        val session = grouping.session
        session.convertAll()
        val pageWidthInt = pageWidth.roundToInt()
        val pageHeightInt = pageHeight.roundToInt()
        val gap = MaskTextRegionPlanner.collisionGapPx(min(pageWidth, pageHeight), scale)
        val budget = MaskTextRegionPlanner.CellSpanBudget()

        for ((groupId, members) in grouping.groups) {
            if (groupId < 0) continue
            val mask = members.first().value.segmentationMask ?: continue
            val nonblank = members.filter { chosenText(it.value, renderSourceText).isNotBlank() }
            if (nonblank.isEmpty()) continue
            val geometry = session.geometryFor(groupId)
            if (geometry != null && geometry.width == pageWidthInt && geometry.height == pageHeightInt) {
                val assigned = HashMap<Int, Int>()
                for (item in nonblank) {
                    val componentId = resolveComponentId(geometry, item.value) ?: continue
                    assigned[item.index] = componentId
                }
                val byComponent = assigned.entries.groupBy({ it.value }, { it.key })
                for (componentId in byComponent.keys.sorted()) {
                    val indexes = byComponent.getValue(componentId)
                    val component = geometry.components[componentId]
                    val bounds = componentBounds(component)
                    val cells = MaskTextRegionPlanner.partition(
                        MaskTextRegionPlanner.ComponentRegion(
                            bounds[0],
                            bounds[1],
                            bounds[2],
                            bounds[3],
                            component.spans,
                        ),
                        indexes.map { idx -> plannerMember(blocks[idx], idx) },
                        gap,
                        budget,
                    )
                    for (cell in cells) {
                        // R4b: beyond-cap members get their disjoint
                        // bounds-rect slab from the same partition — a hard
                        // cell with NO component path, so the hard-cell
                        // exemption protects them too.
                        val overflowSlab = cell.overflowSlab
                        if (overflowSlab != null) {
                            plans[cell.inputIndex] = SharedCellPlan(
                                slab = overflowSlab,
                                fitRegion = null,
                                optimized = true,
                                componentId = null,
                                empty = false,
                            )
                            continue
                        }
                        val fitRegion = if (indexes.size == 1) {
                            symmetricFitRegion(blocks[cell.inputIndex], cell.fitRegion)
                        } else {
                            cell.fitRegion
                        }
                        plans[cell.inputIndex] = SharedCellPlan(
                            slab = cell.slab,
                            fitRegion = fitRegion,
                            optimized = cell.optimized,
                            componentId = if (cell.optimized) componentId else null,
                            empty = cell.empty,
                            spans = cell.spans,
                        )
                    }
                }
            } else {
                // R4a + conversion fallback: disjoint BOUNDS-RECT cells over
                // the mask bounds, scaled into page space when the mask dims
                // mismatch the page (the slabs are page-space clip rects).
                val bounds = mask.bounds
                val scaleX = if (mask.width > 0) pageWidth / mask.width.toFloat() else 1f
                val scaleY = if (mask.height > 0) pageHeight / mask.height.toFloat() else 1f
                val left = (floor(bounds[0] * scaleX).toInt()).coerceIn(0, pageWidthInt)
                val top = (floor(bounds[1] * scaleY).toInt()).coerceIn(0, pageHeightInt)
                val right = (ceil(bounds[2] * scaleX).toInt()).coerceIn(0, pageWidthInt)
                val bottom = (ceil(bounds[3] * scaleY).toInt()).coerceIn(0, pageHeightInt)
                if (right <= left || bottom <= top) continue
                val cells = MaskTextRegionPlanner.partition(
                    MaskTextRegionPlanner.ComponentRegion(
                        left,
                        top,
                        right,
                        bottom,
                        spans = null,
                    ),
                    nonblank.map { plannerMember(it.value, it.index) },
                    gap,
                    budget,
                )
                for (cell in cells) {
                    val overflowSlab = cell.overflowSlab
                    if (overflowSlab != null) {
                        plans[cell.inputIndex] = SharedCellPlan(
                            slab = overflowSlab,
                            fitRegion = null,
                            optimized = true,
                            componentId = null,
                            empty = false,
                        )
                        continue
                    }
                    val fitRegion = if (nonblank.size == 1) {
                        symmetricFitRegion(blocks[cell.inputIndex], cell.fitRegion)
                    } else {
                        cell.fitRegion
                    }
                    plans[cell.inputIndex] = SharedCellPlan(
                        slab = cell.slab,
                        fitRegion = fitRegion,
                        optimized = cell.optimized,
                        componentId = null,
                        empty = cell.empty,
                    )
                }
            }
        }
        return plans
    }

    /**
     * Symmetrically expands a block's fit region from its parent bubble
     * center within the raw component/mask bounds, preventing lateral
     * shifting caused by asymmetric bubble tails.
     *
     * When there is no parent (parentWidth ≤ 0), the rawFit is returned
     * unchanged — the full component/mask pixel bbox is the best fitRegion
     * for a standalone block.
     */
    private fun symmetricFitRegion(
        block: TranslationBlock,
        rawFit: FloatRect?,
    ): FloatRect? {
        if (rawFit == null) return null
        if (block.parentWidth <= 0f) return rawFit
        val anchorX = block.parentX + block.parentWidth / 2f
        val halfW = max(block.width / 2f, min(anchorX - rawFit.left, rawFit.right - anchorX))
        return FloatRect(
            max(rawFit.left, anchorX - halfW),
            rawFit.top,
            min(rawFit.right, anchorX + halfW),
            rawFit.bottom,
        )
    }

    /**
     * Wire renderer clipping metadata for an optimized shared-cell member AFTER
     * placement. `cellRect` is the member's hard disjoint SLAB (not its fit
     * region). Span-mode members additionally carry the compact
     * `(planGeometryId, componentId)` pair; the page-wide
     * [MAX_COMPONENT_ASSIGNMENTS_PER_PAGE] cap applies to NEW distinct pairs,
     * and a capped member keeps its structural slab without ids. Bounds-rect
     * members (conversion fell back, R4a dims mismatch) and R4b beyond-cap
     * slab cells carry the slab without ids, so the renderer clips to the slab
     * rectangle only. Non-optimized members and the legacy path keep their
     * layouts untouched.
     */
    private fun withSharedCellMetadata(
        layout: BlockLayout,
        cellPlan: SharedCellPlan?,
        grouping: MaskGrouping,
        inputIndex: Int,
        componentAssignments: MutableSet<Long>,
    ): BlockLayout {
        // T912 contained-fit: the mask-unusable fallback must stay fully
        // visible — attach no exact component clip (visibility wins over a
        // mask that cannot host the text).
        if (!layout.maskUsable) {
            return layout.copy(
                maskGeometry = null,
                planGeometryId = null,
                maskComponentId = null,
                cellRect = null,
            )
        }
        if (cellPlan == null || !cellPlan.optimized) return layout
        val slab = cellPlan.slab ?: return layout
        val componentId = cellPlan.componentId ?: return layout.copy(cellRect = slab)
        val groupId = grouping.groupByIndex[inputIndex]
        if (groupId == null || groupId < 0) return layout.copy(cellRect = slab)
        val geometry = grouping.session.geometryFor(groupId) ?: return layout.copy(cellRect = slab)
        val assignment = (groupId.toLong() shl 32) or (componentId.toLong() and 0xFFFF_FFFFL)
        if (!componentAssignmentAllowed(assignment, componentAssignments)) {
            return layout.copy(cellRect = slab)
        }
        componentAssignments += assignment
        return layout.copy(
            maskGeometry = geometry,
            planGeometryId = groupId,
            maskComponentId = componentId,
            cellRect = slab,
        )
    }

    /**
     * Slice-2-carried page cap: a NEW distinct `(planGeometryId, componentId)`
     * pair is accepted only while fewer than
     * [MAX_COMPONENT_ASSIGNMENTS_PER_PAGE] distinct pairs exist; an already
     * assigned pair always passes. A capped member keeps its Draw result with
     * the structural slab `cellRect` but NO geometry ids. Extracted as an
     * internal pure predicate so tests can drive the 65th-distinct-pair
     * semantics directly (see the implementation report: through `planPage`
     * the page-wide 64-component conversion budget makes a 65th distinct pair
     * unreachable, so an integration-level fixture cannot exist).
     */
    internal fun componentAssignmentAllowed(assignment: Long, assigned: Set<Long>): Boolean =
        assignment in assigned || assigned.size < MAX_COMPONENT_ASSIGNMENTS_PER_PAGE

    /** Integer bbox `[left, top, right, bottom)` of a component's spans. */
    private fun componentBounds(component: MaskGeometry.Component): IntArray {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (span in component.spans) {
            if (span.start < left) left = span.start
            if (span.endExclusive > right) right = span.endExclusive
            if (span.y < top) top = span.y
            if (span.y + 1 > bottom) bottom = span.y + 1
        }
        return intArrayOf(left, top, right, bottom)
    }

    /**
     * T912 repair (R3): DETERMINISTIC component assignment for a block's OCR
     * rectangle (same clamped-rectangle resolution as slice 2, now over
     * [MaskGeometry.componentForRectangleDeterministic]). A tie across
     * components or a zero-overlap rectangle no longer leaves the block
     * cell-less: it resolves to the max-overlap component, else the tied (or,
     * when all overlaps are zero, any) component with the bounds center
     * nearest the OCR center, then the lower component id.
     */
    private fun resolveComponentId(geometry: MaskGeometry, block: TranslationBlock): Int? {
        val left = floor(block.x).toInt().coerceAtLeast(0)
        val top = floor(block.y).toInt().coerceAtLeast(0)
        val right = ceil(block.x + block.width).toInt().coerceAtMost(geometry.width)
        val bottom = ceil(block.y + block.height).toInt().coerceAtMost(geometry.height)
        return geometry.componentForRectangleDeterministic(left, top, right, bottom)
    }

    /** Build the pure planner's member descriptor for one block. */
    private fun plannerMember(block: TranslationBlock, inputIndex: Int): MaskTextRegionPlanner.Member {
        val hasParent = block.parentWidth > 0f && block.parentHeight > 0f
        return MaskTextRegionPlanner.Member(
            inputIndex = inputIndex,
            centerX = block.x + block.width / 2f,
            centerY = block.y + block.height / 2f,
            ocrRect = FloatRect(block.x, block.y, block.x + block.width, block.y + block.height),
            parentRect = if (hasParent) {
                FloatRect(
                    block.parentX,
                    block.parentY,
                    block.parentX + block.parentWidth,
                    block.parentY + block.parentHeight,
                )
            } else {
                null
            },
            parentValid = hasParent,
        )
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
                    if (text[i] == '-' && i > start) {
                        i++
                        break
                    }
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
        val startSize = max(max(containerW, safeH * 0.35f) * FIT_START_WIDTH_FACTOR, FIT_MIN_FONT_PX * scale * 4.5f).toInt()
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
