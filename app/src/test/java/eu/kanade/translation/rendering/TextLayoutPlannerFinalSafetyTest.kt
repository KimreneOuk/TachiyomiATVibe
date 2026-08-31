package eu.kanade.translation.rendering

import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.segmentation.BubbleMaskRle
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * T912 slice 7: the finite final post-anchor safety pass — conservative
 * (stroke/AA/half-gap inflated) occupancy collision detection after EVERY
 * anchor branch, the hard-cell disjointness exemption, the bounded
 * eight-candidate resolution ladder, the exact evaluated-attempt counter, and
 * the explicit `NO_DISJOINT_POST_ANCHOR_PLACEMENT` non-draw.
 *
 * All expected values are hand-derived from the planner's pure math under the
 * deterministic fake measurer (0.6 px/char, 1.2x line height), stroke
 * `max(2, 0.12 * font)`, `AA_GUARD = 1` at scale 1, and
 * `COLLISION_GAP = 2` (so half-gap = 1) on these page sizes — i.e. a
 * conservative inflation of `stroke + 2` px per side for legacy extents.
 */
class TextLayoutPlannerFinalSafetyTest {

    /** Deterministic measurement: every char is `charWidth` wide at the given size. */
    private class FakeMeasurer(private val charWidth: Float = 0.6f) : TextMeasurer {
        override fun measureTextWidth(text: String, fontSizePx: Float): Float =
            text.length * charWidth * fontSizePx

        override fun lineHeight(fontSizePx: Float): Float = fontSizePx * 1.2f
    }

    private fun block(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        text: String,
        score: Float = 1f,
    ) = TranslationBlock(
        text = "",
        translation = text,
        width = w,
        height = h,
        x = x,
        y = y,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
        label = 1,
        score = score,
        direction = "LTR",
    )

    /** Parented block: the final anchor is the parent-box center. */
    private fun parented(x: Float, y: Float, w: Float, h: Float, text: String, score: Float) =
        block(x, y, w, h, text, score).copy(
            parentX = x,
            parentY = y,
            parentWidth = w,
            parentHeight = h,
        )

    private fun draw(result: LayoutResult): BlockLayout =
        (result.outcome as LayoutOutcome.Draw).layout

    /** Union bounding box of a layout's per-line conservative occupancies. */
    private fun adaptiveOccupancyBox(layout: BlockLayout): FloatRect {
        val rects = layout.conservativeOccupancy.shouldNotBeNull()
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (rect in rects) {
            if (rect.left < left) left = rect.left
            if (rect.top < top) top = rect.top
            if (rect.right > right) right = rect.right
            if (rect.bottom > bottom) bottom = rect.bottom
        }
        return FloatRect(left, top, right, bottom)
    }

    private val m = FakeMeasurer()

    // ---- detection + resolution: anchor-created collision ------------------

    @Test
    fun `anchor-created collision is detected and resolved by the minimum right shift`() {
        // Both blocks anchor to their parent-box centers: A at (200,150),
        // B at (350,160). At font 32 (10-char atomic words fitting the
        // 192px parent width) the extents are [104,296] and [254,446], so the
        // stroke/AA/gap-inflated occupancies ([98.16,301.84] and
        // [248.16,451.84]) overlap even though the bare extents do not.
        val a = parented(100f, 100f, 200f, 100f, "AAAAAAAAAA", score = 0.9f)
        val b = parented(250f, 110f, 200f, 100f, "BBBBBBBBBB", score = 0.5f)

        val resolution = TextLayoutPlanner.planPageInternal(listOf(a, b), 1000f, 1000f, 1, false, m)

        // Candidates: 1 (as-is, collides), 2 (font 31, still collides),
        // 3 (parented baseline == candidate 1: skipped as a duplicate), and
        // 4 (min left shift: page-clamped, still collides) all fail; candidate
        // 5, the minimum RIGHT shift of 53.68px, validates → exactly 4 evaluated.
        resolution.finalPlacementAttempts shouldBe 4

        val plan = resolution.plan
        val byText = plan.resultsInInputOrder.associate { it.chosenText to draw(it) }
        val layoutB = byText.getValue("BBBBBBBBBB")
        layoutB.fontSizePx shouldBe 32f
        layoutB.originX shouldBe 403.68f // 350 + 53.68: occupancies now touch, not overlap
        layoutB.clipRect.shouldBeNull()

        // Only the lower-priority block moved: A is bit-identical to its solo plan.
        val soloA = TextLayoutPlanner.planPageInternal(listOf(a), 1000f, 1000f, 1, false, m)
        draw(soloA.plan.resultsInInputOrder.single()) shouldBe byText.getValue("AAAAAAAAAA")

        // And the resolved pair is disjoint at the occupancy level: the gap
        // between the resolved extents is at least the shared inflation.
        val extentA = TextLayoutPlanner.plan(
            listOf(a),
            1000f,
            1000f,
            1,
            false,
            m,
        ).single().let { soloALayout ->
            // extentOf mirror for a single centered line
            val half = m.measureTextWidth("AAAAAAAAAA", soloALayout.fontSizePx) / 2f
            FloatRect(
                soloALayout.originX - half,
                soloALayout.originY - m.lineHeight(soloALayout.fontSizePx) / 2f,
                soloALayout.originX + half,
                soloALayout.originY + m.lineHeight(soloALayout.fontSizePx) / 2f,
            )
        }
        val extentB = layoutB.let {
            val half = m.measureTextWidth("BBBBBBBBBB", it.fontSizePx) / 2f
            FloatRect(
                it.originX - half,
                it.originY - m.lineHeight(it.fontSizePx) / 2f,
                it.originX + half,
                it.originY + m.lineHeight(it.fontSizePx) / 2f,
            )
        }
        (extentA.overlaps(extentB)) shouldBe false
        ((extentB.left - extentA.right) >= 5.84f) shouldBe true
    }

    // ---- thick-stroke collision --------------------------------------------

    @Test
    fun `thick stroke collision is caught though the bare extents are clear`() {
        // A: font 72 "WWWW" extent [213.6,386.4]; B: font 72 "VVVV" extent
        // [397.6,570.4]. The bare extents are 11.2px apart — a no-stroke check
        // would accept them — but the occupancies (inflation 10.64 per side at
        // font 72) overlap by 10px: [203,397] vs [387,580.8].
        val a = parented(200f, 450f, 200f, 100f, "WWWW", score = 0.9f)
        val b = parented(384f, 450f, 200f, 100f, "VVVV", score = 0.5f)

        val resolution = TextLayoutPlanner.planPageInternal(listOf(a, b), 1000f, 1000f, 1, false, m)

        // Candidate 1 collides; candidate 2 (font 71, occ left 388.28 < 397)
        // still collides; candidate 3 is a parented duplicate (skipped);
        // candidate 4, the min LEFT shift of 377.8px, validates → exactly 3.
        resolution.finalPlacementAttempts shouldBe 3

        val layoutB = draw(
            resolution.plan.resultsInInputOrder.first { it.chosenText == "VVVV" },
        )
        (abs(layoutB.originX - 105.92f) < 0.05f) shouldBe true // 484 - 378.08
        val halfB = m.measureTextWidth("VVVV", layoutB.fontSizePx) / 2f
        (layoutB.originX + halfB <= 386.4f - 10.64f) shouldBe true
    }

    // ---- concave external mask ---------------------------------------------

    @Test
    fun `concave external mask resolves on the conservative bbox without entering the empty interior`() {
        // One U-shaped component (two 40px arms joined at the rows): A wraps
        // into four lines at font 19 (region-rect placement, no clip), so its
        // conservative occupancy [~9.2,~290.8]x[~100.4,~199.6] spans the EMPTY
        // interior x[40,260) — the bbox of the concave component. B sits
        // exactly in that interior, so a hole-aware check would see no
        // conflict but the conservative rectangle does.
        val runs = buildList {
            for (y in 100 until 200) {
                add(y * 300)
                add(40)
                add(y * 300 + 260)
                add(40)
            }
        }
        val mask = BubbleMaskRle(300, 300, listOf(0, 100, 300, 200), runs, score = 1f)
        val a = block(0f, 100f, 300f, 100f, "ab ".repeat(26).trimEnd(), score = 0.9f)
            .copy(segmentationMask = mask)
        val b = block(140f, 140f, 20f, 20f, "Zz", score = 0.5f)

        val resolution = TextLayoutPlanner.planPageInternal(listOf(a, b), 300f, 300f, 1, false, m)

        // Candidates 1/2 collide inside the bbox; candidate 3 (the ungrown
        // baseline, re-centered on the OCR box) differs from candidate 1 and
        // still collides; left/right shifts stay inside the bbox; candidate 6,
        // the min UP shift to A's occupancy top edge, wins.
        resolution.finalPlacementAttempts shouldBe 6

        val plan = resolution.plan
        val layoutA = draw(plan.resultsInInputOrder.first { it.chosenText.startsWith("ab") })
        // The U-shaped OCR rectangle does not resolve to a single component
        // (its bbox also covers the interior), so A keeps the legacy region
        // path: a rectangular containment-clipped layout whose conservative
        // occupancy spans the whole component bbox.
        layoutA.positionedLines.shouldBeNull()

        val layoutB = draw(plan.resultsInInputOrder.first { it.chosenText == "Zz" })
        layoutB.fontSizePx shouldBe 10f
        layoutB.originX shouldBe 140f // LEFT edge anchor from the growth stage
        // The min UP shift moves B fully above A's conservative occupancy top
        // edge (~100.1): out of the bbox — never tucked into the concave
        // interior x[40,260) where B's box sits.
        (abs(layoutB.originY - 90.12f) < 0.05f) shouldBe true
    }

    // ---- page edge ----------------------------------------------------------

    @Test
    fun `page edge blocks the rightward resolution and the upward shift wins instead`() {
        // B anchors at parent center (304,200): its extent touches the page
        // right edge exactly (ink right = 400), so the collision with A's
        // occupancy can only be cleared leftwards/upwards/downwards. The min
        // LEFT shift is page-clamped and still overlaps; RIGHT has zero legal
        // room (maxDisplacement = 0 → skipped); the min UP shift of 50.08px
        // validates.
        val a = parented(50f, 150f, 200f, 100f, "AAAAAAAAAA", score = 0.9f)
        val b = parented(204f, 150f, 200f, 100f, "BBBBBBBBBB", score = 0.5f)

        val resolution = TextLayoutPlanner.planPageInternal(listOf(a, b), 400f, 400f, 1, false, m)

        resolution.finalPlacementAttempts shouldBe 4

        val layoutB = draw(
            resolution.plan.resultsInInputOrder.first { it.chosenText == "BBBBBBBBBB" },
        )
        layoutB.originX shouldBe 304f // right shift was blocked, x unchanged
        (abs(layoutB.originY - 149.92f) < 0.01f) shouldBe true // 200 - 50.08
    }

    // ---- exact attempt counter ---------------------------------------------

    @Test
    fun `winning at the second candidate reports exactly two evaluated attempts`() {
        // The occupancies overlap by 0.54px ([96.16,203.84] vs [203.3,311]):
        // one font step (72 → 71) shrinks B's extent enough that its inflated
        // left edge clears A's inflated right edge (204.02 > 203.84).
        val a = parented(50f, 450f, 200f, 100f, "AB", score = 0.9f)
        val b = parented(157.14f, 450f, 200f, 100f, "CD", score = 0.5f)

        val resolution = TextLayoutPlanner.planPageInternal(listOf(a, b), 1000f, 1000f, 1, false, m)

        resolution.finalPlacementAttempts shouldBe 2
        val layoutB = draw(resolution.plan.resultsInInputOrder.first { it.chosenText == "CD" })
        layoutB.fontSizePx shouldBe 71f
        layoutB.originX shouldBe 257.14f // no shift: the smaller font alone resolved it
    }

    @Test
    fun `impossible space evaluates exactly eight candidates and non-draws explicitly`() {
        // Page 100x70. D (top band) and A (mid box) and C (lower box) tile the
        // page; B is a tall box reshaped to 31.62x31.62, displaced right of A,
        // grown right, and clipped by the clip net to [49.2,28.19,96,51.81]
        // (font 12 after the clip fit). Its occupancy [45.2,96.4]x[28.8,51.2]
        // overlaps BOTH A and C, and:
        //  - candidate 1 still overlaps A and C;
        //  - candidate 2 (font 11) still overlaps both;
        //  - candidate 3 (ungrown baseline through the same clip net, font 8)
        //    differs from candidate 1 and still collides;
        //  - left/right/up/down shifts are page-clamped into remaining overlaps
        //    (A above-left, C below, D above, page right edge);
        //  - candidate 8 refits into the up free rectangle [45.2,23.8,96.4,28.8],
        //    but the floor-font ink pokes above it into D's occupancy.
        // All eight finite candidates fail → explicit non-draw.
        val d = block(0f, 10f, 10f, 10f, "DDDDDDDDDDDDDDD", score = 0.98f)
        val a = block(30f, 35f, 10f, 10f, "AAAA", score = 0.9f)
        val c = block(40f, 53f, 10f, 10f, "CCCCCC", score = 0.95f)
        val b = block(30f, 15f, 20f, 50f, "BBBBBB", score = 0.5f)

        val first = TextLayoutPlanner.planPageInternal(listOf(d, a, c, b), 100f, 70f, 1, false, m)
        val second = TextLayoutPlanner.planPageInternal(listOf(d, a, c, b), 100f, 70f, 1, false, m)

        first.finalPlacementAttempts shouldBe 8
        second.finalPlacementAttempts shouldBe 8

        val plan = first.plan
        plan.resultsInInputOrder shouldHaveSize 4
        plan.resultsInInputOrder.map { it.identity.blockId } shouldBe listOf(null, null, null, null)
        plan.resultsInInputOrder.map { it.chosenText } shouldBe listOf(
            "DDDDDDDDDDDDDDD",
            "AAAA",
            "CCCCCC",
            "BBBBBB",
        )

        val bResult = plan.resultsInInputOrder.last()
        bResult.outcome shouldBe LayoutOutcome.NonDraw(NonDrawReason.NO_DISJOINT_POST_ANCHOR_PLACEMENT)
        bResult.renderOrdinal.shouldBeNull()

        // Survivors keep consecutive render ordinals in placement order
        // (score desc: D 0.98, C 0.95, A 0.9 — B non-draws).
        plan.resultsInInputOrder.take(3).map { it.renderOrdinal } shouldBe listOf(0, 2, 1)
        plan.drawableInRenderOrder.map { it.text } shouldBe listOf(
            "DDDDDDDDDDDDDDD",
            "CCCCCC",
            "AAAA",
        )

        // Deterministic replay: identical plans and attempt counts.
        second.plan shouldBe first.plan
    }

    // ---- hard-cell disjointness exemption ----------------------------------

    @Test
    fun `adjacent adaptive slabs with overlapping occupancy boxes are exempt at zero attempts`() {
        // Two members of one full-rectangle mask, OCR centers (100,30) and
        // (140,90) on a 300x120 page: the Y spread wins so the partition is
        // VERTICAL — cut 60 with the 2px dead zone gives slabs
        // [0,300]x[0,59] and [0,300]x[61,120]. Both take adaptive layouts at
        // large fonts whose per-line conservative occupancies (line rect plus
        // stroke + AA + half-gap, ~7.9px per side) poke several px PAST their
        // own slabs into the dead zone, so the union boxes overlap even though
        // the painted ink cannot. The hard-cell exemption classifies the pair
        // as structurally separated: zero evaluated attempts, outcomes
        // unchanged.
        val mask = BubbleMaskRle(300, 120, listOf(0, 0, 300, 120), listOf(0, 300 * 120), score = 1f)
        val upper = block(60f, 10f, 80f, 40f, "HI", score = 0.9f)
            .copy(blockId = "upper", segmentationMask = mask)
        val lower = block(100f, 70f, 80f, 40f, "YO", score = 0.8f)
            .copy(blockId = "lower", segmentationMask = mask)

        val resolution = TextLayoutPlanner.planPageInternal(listOf(upper, lower), 300f, 120f, 1, false, m)

        resolution.finalPlacementAttempts shouldBe 0

        val plan = resolution.plan
        plan.resultsInInputOrder shouldHaveSize 2
        val layoutUpper = draw(plan.resultsInInputOrder[0])
        val layoutLower = draw(plan.resultsInInputOrder[1])
        layoutUpper.positionedLines.shouldNotBeNull()
        layoutLower.positionedLines.shouldNotBeNull()
        layoutUpper.cellRect shouldBe FloatRect(0f, 0f, 300f, 59f)
        layoutLower.cellRect shouldBe FloatRect(0f, 61f, 300f, 120f)

        // Non-vacuous: the conservative occupancy boxes really do overlap.
        val boxUpper = adaptiveOccupancyBox(layoutUpper)
        val boxLower = adaptiveOccupancyBox(layoutLower)
        (boxUpper.overlaps(boxLower)) shouldBe true
    }
}
