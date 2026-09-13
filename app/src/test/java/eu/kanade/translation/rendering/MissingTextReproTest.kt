package eu.kanade.translation.rendering

import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.segmentation.BubbleMaskRle
import eu.kanade.translation.segmentation.MaskConversionBudgets
import eu.kanade.translation.segmentation.MaskGeometry
import eu.kanade.translation.segmentation.OrderedMaskResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

/**
 * T912 REPAIR: tests pinning the FIXED missing-text behavior — the Director's
 * visibility override: **placement safety may never remove text from the page;
 * worst case is clipped or overlapping text, never a missing block.**
 *
 * The first five tests are the flipped versions of the investigation's repro
 * suite (HEAD `40edf1a` pinned the bug: `NO_DISJOINT_POST_ANCHOR_PLACEMENT`,
 * `EMPTY_SHARED_CELL`, exile, and sibling displacement on conjoined-bubble
 * masks). They now pin the repairs:
 *
 *  - R1 — an empty/degenerate shared cell falls back to the block's own
 *    legacy rectangle placement; `EMPTY_SHARED_CELL` is never emitted.
 *  - R2 — an exhausted eight-candidate ladder accepts a CLIPPED DRAW;
 *    `NO_DISJOINT_POST_ANCHOR_PLACEMENT` is never emitted.
 *  - R3 — tied/zero-overlap component assignment is deterministic
 *    ([MaskGeometry.componentForRectangleDeterministic]), so conjoined-mask
 *    straddlers always get a cell and the hard-cell exemption applies.
 *  - R4 — dims-mismatch groups and beyond-8 members get disjoint bounds-rect
 *    slab cells, so every shared-mask member is exemption-protected.
 *
 * Full analysis: `Plan/active/2026-08-30_T912_text-layout-renderer/engineering/
 * missing-text-investigation.md` and `missing-text-repair.md`.
 *
 * Fixture shapes: two ellipse lobes centered (100,150) and (285,150) with
 * r=(85,80) on a 400x300 page (the Director-specified geometry). Neck variants:
 *  - thick: 2px-wide stepping diagonal bridge -> ONE connected component;
 *  - thin:  1px-wide stepping diagonal bridge -> adjacent-row spans never
 *    overlap in x, so `MaskGeometry` union-find yields the two lobes PLUS
 *    singleton neck components.
 */
@Disabled("Superseded by Desktop 1:1 text layout engine port")
class MissingTextReproTest {

    /** Deterministic measurement: 0.6 px/char, 1.2x line height (page 400x300 => gap 2). */
    private class FakeMeasurer : TextMeasurer {
        override fun measureTextWidth(text: String, fontSizePx: Float): Float =
            text.length * 0.6f * fontSizePx

        override fun lineHeight(fontSizePx: Float): Float = fontSizePx * 1.2f
    }

    private val m = FakeMeasurer()

    private sealed interface Shape {
        data class Ellipse(val cx: Int, val cy: Int, val rx: Int, val ry: Int) : Shape
        data class Rect(val x1: Int, val y1: Int, val x2: Int, val y2: Int) : Shape // half-open x2/y2
    }

    /** Ordered row-major (start, length) runs of the shapes on a W x H page. */
    private fun mask(W: Int, H: Int, shapes: List<Shape>): BubbleMaskRle {
        val runs = ArrayList<Int>()
        var minL = Int.MAX_VALUE; var minT = Int.MAX_VALUE; var maxR = 0; var maxB = 0
        fun emit(start: Int, len: Int) {
            runs += start; runs += len
        }
        for (y in 0 until H) {
            val intervals = ArrayList<Pair<Int, Int>>()
            for (s in shapes) {
                when (s) {
                    is Shape.Ellipse -> {
                        val dy = y - s.cy
                        if (dy < -s.ry || dy > s.ry) continue
                        val t = 1f - (dy.toFloat() * dy) / (s.ry.toFloat() * s.ry)
                        if (t < 0f) continue
                        val w = (s.rx.toFloat() * sqrt(t)).toInt()
                        if (w <= 0) continue
                        intervals += Pair(s.cx - w, s.cx + w + 1)
                    }
                    is Shape.Rect -> if (y >= s.y1 && y < s.y2) intervals += Pair(s.x1, s.x2)
                }
            }
            intervals.sortWith(compareBy({ it.first }, { it.second }))
            var cur: Pair<Int, Int>? = null
            for (iv in intervals) {
                cur = when {
                    cur == null -> iv
                    iv.first <= cur.second -> Pair(cur.first, maxOf(cur.second, iv.second))
                    else -> {
                        emit(y * W + cur.first, cur.second - cur.first)
                        minL = minOf(minL, cur.first); maxR = maxOf(maxR, cur.second)
                        minT = minOf(minT, y); maxB = maxOf(maxB, y + 1)
                        iv
                    }
                }
            }
            cur?.let {
                emit(y * W + it.first, it.second - it.first)
                minL = minOf(minL, it.first); maxR = maxOf(maxR, it.second)
                minT = minOf(minT, y); maxB = maxOf(maxB, y + 1)
            }
        }
        return BubbleMaskRle(W, H, listOf(minL, minT, maxR, maxB), runs, 0.95f)
    }

    /** Two-lobe conjoined mask; `neckWidth` 0 = disconnected gap, 1 = thin (lobes + singletons), 2 = thick (1 component). */
    private fun twoLobes(neckWidth: Int): BubbleMaskRle {
        val shapes = mutableListOf<Shape>(
            Shape.Ellipse(100, 150, 85, 80),
            Shape.Ellipse(285, 150, 85, 80),
        )
        for (y in 146..165) {
            if (neckWidth > 0) shapes += Shape.Rect(184 + (y - 146), y, 184 + (y - 146) + neckWidth, y + 1)
        }
        return mask(400, 300, shapes)
    }

    private fun block(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        text: String,
        score: Float,
        mask: BubbleMaskRle? = null,
        parent: FloatArray? = null, // (x, y, w, h) or null
    ): TranslationBlock = TranslationBlock(
        text = "",
        translation = text,
        width = w, height = h, x = x, y = y,
        symHeight = 1f, symWidth = 1f, angle = 0f,
        label = 1, score = score,
        parentX = parent?.get(0) ?: 0f,
        parentY = parent?.get(1) ?: 0f,
        parentWidth = parent?.get(2) ?: 0f,
        parentHeight = parent?.get(3) ?: 0f,
        segmentationMask = mask,
    )

    private fun draw(result: LayoutResult): BlockLayout =
        (result.outcome as LayoutOutcome.Draw).layout

    private fun assertAllDraw(plan: PageLayoutPlan) {
        plan.resultsInInputOrder.forEach { result ->
            when (result.outcome) {
                is LayoutOutcome.Draw -> {}
                is LayoutOutcome.NonDraw ->
                    throw AssertionError("unexpected NonDraw(${result.outcome}) for ${result.chosenText}")
            }
        }
    }

    private fun geometryOf(m: BubbleMaskRle): MaskGeometry =
        when (val r = MaskGeometry.fromOrderedRle(m, MaskConversionBudgets())) {
            is OrderedMaskResult.Success -> r.geometry
            is OrderedMaskResult.Fallback -> throw IllegalStateException(r.reason.name)
        }

    /** Planner occupancy mirror of a legacy horizontal layout (stroke+AA+half-gap inflated). */
    private fun legacyOccupancy(layout: BlockLayout): FloatRect {
        val lines = TextLayoutPlanner.cjkWrap(layout.text, layout.fontSizePx, layout.safeW, m)
        val lineH = m.lineHeight(layout.fontSizePx)
        val totalH = lines.size * lineH
        val maxW = (lines.maxOfOrNull { m.measureTextWidth(it, layout.fontSizePx) } ?: 0f)
            .coerceAtLeast(0f)
        val extent = when (layout.drawAlign) {
            TextAlign.LEFT ->
                FloatRect(layout.originX, layout.originY - totalH / 2f, layout.originX + maxW, layout.originY + totalH / 2f)
            TextAlign.RIGHT ->
                FloatRect(layout.originX - maxW, layout.originY - totalH / 2f, layout.originX, layout.originY + totalH / 2f)
            TextAlign.CENTER ->
                FloatRect(layout.originX - maxW / 2f, layout.originY - totalH / 2f, layout.originX + maxW / 2f, layout.originY + totalH / 2f)
        }
        val inflate = TextLayoutPlanner.computeStrokeWidth(layout.fontSizePx, 1f) +
            TextLayoutTuning.aaGuard(1f) + 1f // gap 2 on these pages -> half-gap 1
        return FloatRect(
            extent.left - inflate,
            extent.top - inflate,
            extent.right + inflate,
            extent.bottom + inflate,
        )
    }

    // ---- flagship repro, FIXED: R3 assigns the straddler; nobody drops ------

    /**
     * FIXED (was `NO_DISJOINT_POST_ANCHOR_PLACEMENT` for both lobes): the tied
     * straddler now resolves DETERMINISTICALLY (R3) to a component — exact
     * overlap tie [384,384] across the two lobes -> nearest integer bounds
     * center (symmetric here) -> LOWER component id 0 — so it gets a hard cell
     * on the left lobe and the hard-cell exemption applies everywhere.
     * Straddler + both lobes Draw, no NonDraw anywhere.
     */
    @Test
    fun `repair - tied straddler is cell'd deterministically and all three blocks draw`() {
        val thin = twoLobes(neckWidth = 1)

        // The legacy assignment is still a tie (componentForRectangle keeps its
        // null-on-tie contract); the T912 path uses the deterministic variant.
        geometryOf(thin).componentForRectangle(150, 60, 236, 120).shouldBeNull()
        geometryOf(thin).componentForRectangleDeterministic(150, 60, 236, 120).shouldBe(0)

        val lobeParentA = floatArrayOf(15f, 70f, 170f, 160f)
        val lobeParentB = floatArrayOf(200f, 70f, 170f, 160f)
        val wholeBubble = floatArrayOf(15f, 70f, 355f, 160f)
        val plan = TextLayoutPlanner.planPage(
            listOf(
                block(30f, 100f, 130f, 95f, "AAAAAAAAAA", 0.60f, thin, lobeParentA),
                block(230f, 100f, 130f, 95f, "BBBBBBBBBB", 0.55f, thin, lobeParentB),
                block(150f, 60f, 86f, 60f, "CCCCCCCC", 0.95f, thin, wholeBubble),
            ),
            400f, 300f, 1, false, m,
        )
        val byText = plan.resultsInInputOrder.associate { it.chosenText to it }

        assertAllDraw(plan)
        plan.drawableInRenderOrder.shouldHaveSize(3)

        // The straddler is cell'd (left lobe, lower id on the symmetric tie).
        val c = draw(byText.getValue("CCCCCCCC"))
        c.cellRect.shouldNotBeNull()
        c.maskComponentId shouldBe 0
        // Every block keeps a hard cell; the cells are pairwise disjoint.
        val cells = plan.resultsInInputOrder.map { draw(it).cellRect.shouldNotBeNull() }
        for (i in cells.indices) {
            for (j in i + 1 until cells.size) {
                (cells[i].overlaps(cells[j])) shouldBe false
            }
        }
    }

    /**
     * FIXED (was: the mid-score tied straddler exiled ~160px from its region):
     * with the deterministic assignment the straddler is cell'd beside the
     * left-lobe sibling and draws INSIDE its cell near its own region.
     */
    @Test
    fun `repair - mid-score tied straddler draws near its own region instead of being exiled`() {
        val thin = twoLobes(neckWidth = 1)
        val lobeParentA = floatArrayOf(15f, 70f, 170f, 160f)
        val lobeParentB = floatArrayOf(200f, 70f, 170f, 160f)
        val wholeBubble = floatArrayOf(15f, 70f, 355f, 160f)
        val plan = TextLayoutPlanner.planPage(
            listOf(
                block(30f, 100f, 130f, 95f, "AAAAAAAAAA", 0.90f, thin, lobeParentA),
                block(230f, 100f, 130f, 95f, "BBBBBBBBBB", 0.50f, thin, lobeParentB),
                block(150f, 60f, 86f, 60f, "CCCCCCCC", 0.70f, thin, wholeBubble),
            ),
            400f, 300f, 1, false, m,
        )
        val byText = plan.resultsInInputOrder.associate { it.chosenText to it }

        assertAllDraw(plan)
        val c = draw(byText.getValue("CCCCCCCC"))
        c.cellRect.shouldNotBeNull()
        val dx = c.originX - (c.block.x + c.block.width / 2f)
        val dy = c.originY - (c.block.y + c.block.height / 2f)
        // NOT exiled: the straddler stays within 100px of its OCR center
        // (the exile repro measured > 100px, typically ~160px).
        (sqrt(dx * dx + dy * dy) < 100f) shouldBe true
    }

    // ---- empty shared cell, FIXED: legacy-rectangle fallback (R1) ------------

    /**
     * FIXED (was `EMPTY_SHARED_CELL`): the middle block of three near-equal
     * scan centers has a degenerate (inverted) slab; it now falls back to its
     * own pre-slice-3 LEGACY REGION (the buildMaskRegions midpoint partition
     * [86,88] of the mask bounds, centered at x=87) and DRAWS.
     */
    @Test
    fun `repair - near-equal centers empty middle cell draws via the legacy region fallback (R1)`() {
        val thick = twoLobes(neckWidth = 2)
        geometryOf(thick).components.shouldHaveSize(1)

        val plan = TextLayoutPlanner.planPage(
            listOf(
                block(20f, 100f, 130f, 95f, "AAAAAAAAAA", 0.9f, thick),
                block(22f, 100f, 130f, 95f, "BBBBBBBBBB", 0.85f, thick),
                block(24f, 100f, 130f, 95f, "CCCCCCCCCC", 0.8f, thick),
            ),
            400f, 300f, 1, false, m,
        )
        val byText = plan.resultsInInputOrder.associate { it.chosenText to it }

        assertAllDraw(plan)
        plan.drawableInRenderOrder.shouldHaveSize(3)
        // The empty-cell member took the legacy region path: no cell, no ids.
        val b = draw(byText.getValue("BBBBBBBBBB"))
        b.cellRect.shouldBeNull()
        b.planGeometryId.shouldBeNull()
        b.maskComponentId.shouldBeNull()
    }

    /**
     * FIXED (was `EMPTY_SHARED_CELL` in bounds-rect mode): a degenerate
     * bounds-rect slab also falls back to the legacy region and draws.
     */
    @Test
    fun `repair - bounds-mode degenerate cell draws via the legacy region fallback (R1)`() {
        val emptyRuns = BubbleMaskRle(400, 300, listOf(15, 70, 370, 230), emptyList(), 0.95f)
        val plan = TextLayoutPlanner.planPage(
            listOf(
                block(20f, 100f, 130f, 95f, "AAAAAAAAAA", 0.9f, emptyRuns),
                block(22f, 100f, 130f, 95f, "BBBBBBBBBB", 0.85f, emptyRuns),
                block(24f, 100f, 130f, 95f, "CCCCCCCCCC", 0.8f, emptyRuns),
            ),
            400f, 300f, 1, false, m,
        )
        val byText = plan.resultsInInputOrder.associate { it.chosenText to it }
        assertAllDraw(plan)
        draw(byText.getValue("BBBBBBBBBB")).cellRect.shouldBeNull()
    }

    /**
     * R1 focused: the empty-cell fallback REGION equals the legacy region.
     * Fixture: full-rect 100x300 mask, three 1-char blocks with centers
     * (45,150), (47,150), (49,150); the middle slab [87,86) is degenerate.
     * The straddler-free arrangement (highest score for the middle block, 1px
     * ink) means the legacy midpoint region [46,48]x[0,300] placement is
     * accepted as-is: origin exactly at the legacy region center (47,150).
     */
    @Test
    fun `repair R1 - empty-cell fallback placement equals the legacy region placement`() {
        val fullRect = BubbleMaskRle(100, 300, listOf(0, 0, 100, 300), listOf(0, 100 * 300), 0.95f)
        val plan = TextLayoutPlanner.planPage(
            listOf(
                block(40f, 145f, 10f, 10f, "A", 0.9f, fullRect),
                block(42f, 145f, 10f, 10f, "B", 0.95f, fullRect),
                block(44f, 145f, 10f, 10f, "C", 0.8f, fullRect),
            ),
            100f, 300f, 1, false, m,
        )
        val byText = plan.resultsInInputOrder.associate { it.chosenText to it }
        assertAllDraw(plan)

        val b = draw(byText.getValue("B"))
        // Legacy region for B = midpoint partition [46,48] of the mask bounds
        // (centers 45/47/49): origin at its center, floor font, no clip.
        b.originX shouldBe 47f
        b.originY shouldBe 150f
        b.clipRect.shouldBeNull()
        b.cellRect.shouldBeNull()
        b.fontSizePx shouldBe 8f
        // The siblings keep their optimized cells.
        draw(byText.getValue("A")).cellRect.shouldNotBeNull()
        draw(byText.getValue("C")).cellRect.shouldNotBeNull()
    }

    // ---- displacement, FIXED: deterministic assignment keeps regions ---------

    /**
     * FIXED (was: cell'd lobe siblings knocked 65+px out of their regions):
     * with the straddler cell'd on the left lobe, every block is
     * exemption-protected inside its own disjoint cell and draws in place.
     */
    @Test
    fun `repair - tied straddler no longer displaces cell'd siblings from their regions`() {
        val thin = twoLobes(neckWidth = 1)
        val shared = floatArrayOf(15f, 70f, 355f, 160f)
        val plan = TextLayoutPlanner.planPage(
            listOf(
                block(30f, 100f, 130f, 95f, "AAAAAAAAAA", 0.6f, thin, shared),
                block(230f, 100f, 130f, 95f, "BBBBBBBBBB", 0.55f, thin, shared),
                block(150f, 60f, 86f, 60f, "CCCCCCCC", 0.95f, thin, shared),
            ),
            400f, 300f, 1, false, m,
        )
        val byText = plan.resultsInInputOrder.associate { it.chosenText to it }

        assertAllDraw(plan)
        val a = draw(byText.getValue("AAAAAAAAAA"))
        val b = draw(byText.getValue("BBBBBBBBBB"))
        val c = draw(byText.getValue("CCCCCCCC"))
        a.cellRect.shouldNotBeNull()
        b.cellRect.shouldNotBeNull()
        c.cellRect.shouldNotBeNull()
        fun displacement(layout: BlockLayout): Float {
            val dx = layout.originX - (layout.block.x + layout.block.width / 2f)
            val dy = layout.originY - (layout.block.y + layout.block.height / 2f)
            return sqrt(dx * dx + dy * dy)
        }
        // The lobe siblings keep their in-cell placement (was > 65px).
        (displacement(a) < 40f) shouldBe true
        (displacement(b) < 40f) shouldBe true
        // The straddler stays near its own region too (its cell is adjacent).
        (displacement(c) < 100f) shouldBe true
    }

    // ---- ladder exhaustion, FIXED: clipped draw, never a drop (R2) -----------

    /**
     * R2 focused: the impossible-space fixture (four unmasked blocks tiling a
     * 100x70 page) exhausted the eight-candidate ladder and NonDraw'd block B.
     * It now accepts a CLIPPED DRAW: the candidate-8 refit hard-clipped to the
     * largest free rectangle, which is DISJOINT from every accepted occupancy
     * (the pixel-safety property), with the attempt cap intact at 8 and the
     * higher-score blocks bit-identical to their uncollided plan.
     */
    @Test
    fun `repair R2 - ladder exhaustion accepts a clipped draw disjoint from accepted occupancies`() {
        val d = block(0f, 10f, 10f, 10f, "DDDDDDDDDDDDDDD", 0.98f)
        val a = block(30f, 35f, 10f, 10f, "AAAA", 0.9f)
        val c = block(40f, 53f, 10f, 10f, "CCCCCC", 0.95f)
        val b = block(30f, 15f, 20f, 50f, "BBBBBB", 0.5f)

        val resolution = TextLayoutPlanner.planPageInternal(listOf(d, a, c, b), 100f, 70f, 1, false, m)
        // The clip fallback is NOT an extra candidate: cap stays at 8, and the
        // full ladder (all 8 candidates evaluated) still precedes it.
        resolution.finalPlacementAttempts shouldBe 8

        val plan = resolution.plan
        assertAllDraw(plan)
        val bLayout = draw(plan.resultsInInputOrder.last())
        val clip = bLayout.clipRect.shouldNotBeNull()
        (clip.width() > 0f && clip.height() > 0f) shouldBe true

        // PIXEL SAFETY: the clip rectangle is disjoint from the conservative
        // occupancy of every already-accepted block (by construction the free
        // strips are cut back past them).
        val byText = plan.resultsInInputOrder.associate { it.chosenText to draw(it) }
        for (survivor in listOf("DDDDDDDDDDDDDDD", "AAAA", "CCCCCC")) {
            (clip.overlaps(legacyOccupancy(byText.getValue(survivor)))) shouldBe false
        }

        // Higher-score blocks are bit-identical to their plan without B.
        val withoutB = TextLayoutPlanner.planPageInternal(listOf(d, a, c), 100f, 70f, 1, false, m)
        val survivorsWithoutB = withoutB.plan.resultsInInputOrder.associate { it.chosenText to draw(it) }
        for (survivor in listOf("DDDDDDDDDDDDDDD", "AAAA", "CCCCCC")) {
            draw(plan.resultsInInputOrder.first { it.chosenText == survivor }) shouldBe
                survivorsWithoutB.getValue(survivor)
        }
    }

    // ---- dims mismatch, FIXED: disjoint page-space cells (R4a) ---------------

    /**
     * R4a: a mask group whose geometry dims (400x300) mismatch the page
     * (380x290) used to get NO cells at all (legacy midpoint regions + sibling
     * occupancy vetoes). Every member now gets a disjoint BOUNDS-RECT cell,
     * scaled into page space, without geometry ids.
     */
    @Test
    fun `repair R4a - dims-mismatch group gets pairwise-disjoint page-space cells`() {
        val mismatched = twoLobes(neckWidth = 1) // 400x300 mask on a 380x290 page
        val lobeParentA = floatArrayOf(15f, 70f, 170f, 160f)
        val lobeParentB = floatArrayOf(200f, 70f, 170f, 160f)
        val plan = TextLayoutPlanner.planPage(
            listOf(
                block(30f, 100f, 130f, 95f, "AAAAAAAAAA", 0.9f, mismatched, lobeParentA),
                block(230f, 100f, 130f, 95f, "BBBBBBBBBB", 0.85f, mismatched, lobeParentB),
            ),
            380f, 290f, 1, false, m,
        )
        val byText = plan.resultsInInputOrder.associate { it.chosenText to it }
        assertAllDraw(plan)

        val a = draw(byText.getValue("AAAAAAAAAA"))
        val b = draw(byText.getValue("BBBBBBBBBB"))
        val cellA = a.cellRect.shouldNotBeNull()
        val cellB = b.cellRect.shouldNotBeNull()
        // Scaled bounds rect [14,68,353,223] (mask bounds [15,71,371,230];
        // scaleY = 290/300); facing parents bias the cut to 192 with the 2px
        // dead zone -> disjoint page-space slabs.
        cellA shouldBe FloatRect(14f, 68f, 191f, 223f)
        cellB shouldBe FloatRect(193f, 68f, 353f, 223f)
        (cellA.overlaps(cellB)) shouldBe false
        // Structural separation without a component path: no geometry ids.
        a.maskGeometry.shouldBeNull()
        a.planGeometryId.shouldBeNull()
        a.maskComponentId.shouldBeNull()
        b.maskGeometry.shouldBeNull()
        b.planGeometryId.shouldBeNull()
        b.maskComponentId.shouldBeNull()
    }

    // ---- beyond-8 members, FIXED: slab cells from the same partition (R4b) ---

    /**
     * R4b: the 9th member of one component used to be cell-less
     * (`regionOverride = null`, no exemption). It now receives a disjoint
     * bounds-rect slab cell cut from the SAME partition, draws in place, and
     * is exemption-protected like every sibling.
     */
    @Test
    fun `repair R4b - ninth member gets a disjoint slab cell and draws in place`() {
        val fullRect = BubbleMaskRle(300, 120, listOf(0, 0, 300, 120), listOf(0, 300 * 120), 0.95f)
        val inputs = (0 until 9).map { i ->
            block(i * 30f + 5f, 50f, 20f, 20f, "T$i", 0.5f, fullRect).copy(blockId = "b$i")
        }

        val resolution = TextLayoutPlanner.planPageInternal(inputs, 300f, 120f, 1, false, m)
        val plan = resolution.plan
        assertAllDraw(plan)
        // Disjoint hard cells everywhere -> the exemption applies -> zero
        // evaluated ladder attempts.
        resolution.finalPlacementAttempts shouldBe 0

        val cells = plan.resultsInInputOrder.associate { it.identity.blockId to draw(it).cellRect.shouldNotBeNull() }
        val cellList = cells.values.toList()
        for (i in cellList.indices) {
            for (j in i + 1 until cellList.size) {
                (cellList[i].overlaps(cellList[j])) shouldBe false
            }
        }
        // The 9th member's slab is the tail of the same partition sequence.
        cells.getValue("b8") shouldBe FloatRect(241f, 0f, 300f, 120f)
        // ... and it draws IN PLACE at its own OCR center (no displacement).
        val ninth = draw(plan.resultsInInputOrder.first { it.identity.blockId == "b8" })
        ninth.originX shouldBe 255f
        ninth.originY shouldBe 60f
    }

    // ---- contrast: the designed path still works -----------------------------

    /**
     * contrast (unchanged by the repair): a thick-neck conjoined mask with one
     * block per lobe partitions into disjoint cells and draws every block.
     */
    @Test
    fun `contrast - thick-neck two-block conjoined mask draws every block in its own cell`() {
        val thick = twoLobes(neckWidth = 2)
        val lobeParentA = floatArrayOf(15f, 70f, 170f, 160f)
        val lobeParentB = floatArrayOf(200f, 70f, 170f, 160f)
        val plan = TextLayoutPlanner.planPage(
            listOf(
                block(30f, 100f, 130f, 95f, "AAAAAAAAAA", 0.9f, thick, lobeParentA),
                block(230f, 100f, 130f, 95f, "BBBBBBBBBB", 0.85f, thick, lobeParentB),
            ),
            400f, 300f, 1, false, m,
        )
        plan.drawableInRenderOrder.shouldHaveSize(2)
        for (layout in plan.drawableInRenderOrder) {
            layout.cellRect.shouldNotBeNull()
        }
    }

    // ---- grep-able invariant: no NonDraw anywhere in the repaired planner ----

    /**
     * Invariant sweep over the previously-dropping fixtures: `planPage` never
     * returns a `NonDraw` outcome — every nonblank input draws (worst case
     * clipped or overlapping). The only remaining production emission is the
     * page-wide StaticLayout resource guard (pinned by TextLayoutPlannerSlice5Test).
     */
    @Test
    fun `repair - planPage emits no NonDraw across the missing-text corpus`() {
        val thin = twoLobes(neckWidth = 1)
        val emptyRuns = BubbleMaskRle(400, 300, listOf(15, 70, 370, 230), emptyList(), 0.95f)
        val fixtures = listOf(
            // flagship: tied straddler + two cell'd lobes (400x300)
            400f to 300f to listOf(
                block(30f, 100f, 130f, 95f, "AAAAAAAAAA", 0.60f, thin, floatArrayOf(15f, 70f, 170f, 160f)),
                block(230f, 100f, 130f, 95f, "BBBBBBBBBB", 0.55f, thin, floatArrayOf(200f, 70f, 170f, 160f)),
                block(150f, 60f, 86f, 60f, "CCCCCCCC", 0.95f, thin, floatArrayOf(15f, 70f, 355f, 160f)),
            ),
            // empty-cell fixtures (span + bounds mode, 400x300)
            400f to 300f to listOf(
                block(20f, 100f, 130f, 95f, "AAAAAAAAAA", 0.9f, twoLobes(neckWidth = 2)),
                block(22f, 100f, 130f, 95f, "BBBBBBBBBB", 0.85f, twoLobes(neckWidth = 2)),
                block(24f, 100f, 130f, 95f, "CCCCCCCCCC", 0.8f, twoLobes(neckWidth = 2)),
            ),
            400f to 300f to listOf(
                block(20f, 100f, 130f, 95f, "AAAAAAAAAA", 0.9f, emptyRuns),
                block(22f, 100f, 130f, 95f, "BBBBBBBBBB", 0.85f, emptyRuns),
                block(24f, 100f, 130f, 95f, "CCCCCCCCCC", 0.8f, emptyRuns),
            ),
            // impossible space / ladder exhaustion (100x70)
            100f to 70f to listOf(
                block(0f, 10f, 10f, 10f, "DDDDDDDDDDDDDDD", 0.98f),
                block(30f, 35f, 10f, 10f, "AAAA", 0.9f),
                block(40f, 53f, 10f, 10f, "CCCCCC", 0.95f),
                block(30f, 15f, 20f, 50f, "BBBBBB", 0.5f),
            ),
        )
        for ((page, fixture) in fixtures) {
            val plan = TextLayoutPlanner.planPage(fixture, page.first, page.second, 1, false, m)
            plan.resultsInInputOrder.forEach { result ->
                when (result.outcome) {
                    is LayoutOutcome.Draw -> {}
                    is LayoutOutcome.NonDraw ->
                        throw AssertionError("NonDraw(${result.outcome}) for ${result.chosenText}")
                }
            }
        }
    }
}
