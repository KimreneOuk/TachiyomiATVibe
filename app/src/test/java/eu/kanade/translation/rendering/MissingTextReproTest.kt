package eu.kanade.translation.rendering

import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.segmentation.BubbleMaskRle
import eu.kanade.translation.segmentation.MaskConversionBudgets
import eu.kanade.translation.segmentation.MaskGeometry
import eu.kanade.translation.segmentation.OrderedMaskResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

/**
 * T912 investigation: committed DOCUMENTATION tests pinning the CURRENT
 * missing-text behavior on realistic conjoined-bubble fixtures.
 *
 * Every test here PASSES at HEAD `40edf1a` — they document the reported bug,
 * not the desired behavior. The legacy planner (base `ece0e72`) had no
 * non-draw path at all: every nonblank block was drawn. T912 slices 3-7 added
 * explicit `NonDraw` outcomes, and on conjoined segmentation masks (several
 * text blocks sharing one bubble mask) blocks are now silently dropped from
 * `drawableInRenderOrder` — a visible blank region, because the inpaint mask
 * already erased the original text.
 *
 * Each repro names the responsible mechanism and carries a `TODO(T912-fix)`
 * describing the legacy-compatible expectation. Full analysis:
 * `Plan/active/2026-08-30_T912_text-layout-renderer/engineering/missing-text-investigation.md`.
 *
 * Fixture shapes: two ellipse lobes centered (100,150) and (285,150) with
 * r=(85,80) on a 400x300 page (the Director-specified geometry). Neck variants:
 *  - thick: 2px-wide stepping diagonal bridge -> ONE connected component;
 *  - thin:  1px-wide stepping diagonal bridge -> adjacent-row spans never
 *    overlap in x, so `MaskGeometry` union-find yields TWO (plus singleton)
 *    components. This is the realistic conjoined "thin diagonal neck".
 */
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

    /** Two-lobe conjoined mask; `neckWidth` 0 = disconnected gap, 1 = thin (2 components), 2 = thick (1 component). */
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
        mask: BubbleMaskRle,
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

    private fun outcomeText(result: LayoutResult): String = when (val o = result.outcome) {
        is LayoutOutcome.Draw -> "Draw"
        is LayoutOutcome.NonDraw -> o.reason.name
    }

    private fun geometryOf(m: BubbleMaskRle): MaskGeometry =
        when (val r = MaskGeometry.fromOrderedRle(m, MaskConversionBudgets())) {
            is OrderedMaskResult.Success -> r.geometry
            is OrderedMaskResult.Fallback -> throw IllegalStateException(r.reason.name)
        }

    // ---- flagship repro: cell'd lobe siblings dropped -----------------------

    /**
     * repro: conjoined thin-neck mask loses the lobe siblings of a tied
     * straddling block (NO_DISJOINT_POST_ANCHOR_PLACEMENT).
     *
     * The thin diagonal neck splits the conjoined mask into components. The
     * symmetric straddling OCR box [150,236)x[60,120) overlaps both lobe
     * components by EXACTLY the same pixel count, so
     * `MaskGeometry.componentForRectangle` returns null (tie;
     * MaskGeometry.kt:55-82) and the block gets no shared cell
     * (TextLayoutPlanner.kt:2482). It falls to the legacy region path with the
     * whole-bubble parent box as anchor region, and its stroke/AA/half-gap
     * inflated occupancy blankets both lobes' slabs. When it has the highest
     * score it is placed first; both cell'd lobe blocks are then constrained to
     * their own slabs while every post-anchor candidate collides, so the
     * bounded 8-candidate ladder exhausts and BOTH are dropped
     * (TextLayoutPlanner.kt:760-772). Legacy drew all three.
     *
     * TODO(T912-fix): blocks that would NonDraw under the slice-7 safety pass
     * must fall back to drawing like legacy (their own legacy rectangle plus a
     * structural clip), never disappear from drawableInRenderOrder.
     */
    @Test
    fun `repro - conjoined thin-neck mask loses the tied straddler's cell'd lobe siblings (NO_DISJOINT_POST_ANCHOR_PLACEMENT)`() {
        val thin = twoLobes(neckWidth = 1)

        // Document WHY the straddler is cell-less: exact overlap tie.
        geometryOf(thin).componentForRectangle(150, 60, 236, 120).shouldBeNull()

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

        // Both cell'd lobe blocks are dropped -> VISIBLE BLANK REGIONS (the
        // inpaint mask already erased the original text).
        outcomeText(byText.getValue("AAAAAAAAAA")).shouldBe("NO_DISJOINT_POST_ANCHOR_PLACEMENT")
        outcomeText(byText.getValue("BBBBBBBBBB")).shouldBe("NO_DISJOINT_POST_ANCHOR_PLACEMENT")

        // The tied, cell-less straddler is the ONLY thing drawn (legacy drew it
        // at the same parent-centered anchor, so this part is not the change).
        val c = byText.getValue("CCCCCCCC").outcome as LayoutOutcome.Draw
        c.layout.cellRect.shouldBeNull()
        plan.drawableInRenderOrder.shouldHaveSize(1)
    }

    /**
     * repro: same conjoined mask, mid-range straddler score — nobody is
     * dropped, but the tied straddler's 8-candidate ladder cannot find a legal
     * spot near its OCR box and instead EXILES it far outside the conjoined
     * bubble. To a reader scanning the page, the text is missing from the
     * region it belongs to (the legacy renderer drew it at its own anchor).
     *
     * TODO(T912-fix): the tied straddler must draw at/near its own region with
     * a structural clip, not be exiled; the lobe blocks must keep their cells.
     */
    @Test
    fun `repro - mid-score tied straddler escapes and draws far outside the bubble`() {
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

        // Nobody is dropped in this arrangement — the ladder instead EXILES the
        // tied straddler ~160px away from its OCR box (legacy: drawn at its own
        // region anchor). The lobe blocks keep their cells.
        outcomeText(byText.getValue("AAAAAAAAAA")).shouldBe("Draw")
        outcomeText(byText.getValue("BBBBBBBBBB")).shouldBe("Draw")
        val c = byText.getValue("CCCCCCCC").outcome as LayoutOutcome.Draw
        c.layout.cellRect.shouldBeNull()
        val dx = c.layout.originX - (c.layout.block.x + c.layout.block.width / 2f)
        val dy = c.layout.originY - (c.layout.block.y + c.layout.block.height / 2f)
        kotlin.math.sqrt(dx * dx + dy * dy).shouldBeGreaterThan(100f)
    }

    // ---- empty shared cell ---------------------------------------------------

    /**
     * repro: near-equal scan centers in one conjoined component produce an
     * empty middle cell (EMPTY_SHARED_CELL), dropping that block.
     *
     * Slice 3 cuts between adjacent scan centers (floor midpoints), then
     * shrinks each slab by the dead-zone gap (`gapBefore/gapAfter`,
     * MaskTextRegionPlanner.kt:189-206). With member centers only ~2px apart on
     * the partition axis the cuts end up within the 2px dead zone and the
     * middle slab degenerates (`start >= endExclusive`), so the cell owns no
     * pixels and the planner emits `NonDraw(EMPTY_SHARED_CELL)`
     * (TextLayoutPlanner.kt:595-605). The legacy renderer drew this block.
     *
     * TODO(T912-fix): an empty cell must fall back to the block's own legacy
     * rectangle (or a bounds-rect cell), not drop the block.
     */
    @Test
    fun `repro - near-equal centers in one conjoined component produce an empty middle cell (EMPTY_SHARED_CELL)`() {
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

        outcomeText(byText.getValue("BBBBBBBBBB")).shouldBe("EMPTY_SHARED_CELL")
        outcomeText(byText.getValue("AAAAAAAAAA")).shouldBe("Draw")
        outcomeText(byText.getValue("CCCCCCCCCC")).shouldBe("Draw")
        plan.drawableInRenderOrder.shouldHaveSize(2)
    }

    /**
     * repro (bounds-rect mode): a fallback conversion (empty runs) uses the
     * same cut/dead-zone logic on the mask bounds rectangle, so the identical
     * near-equal-center regime degenerates there too
     * (MaskTextRegionPlanner.kt:260-271).
     *
     * TODO(T912-fix): same as the span-mode empty-cell repro.
     */
    @Test
    fun `repro - bounds-mode cells degenerate under near-equal centers as well (EMPTY_SHARED_CELL)`() {
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
        outcomeText(byText.getValue("BBBBBBBBBB")).shouldBe("EMPTY_SHARED_CELL")
    }

    // ---- displacement (text drawn OUTSIDE its region, not blank) -------------

    /**
     * repro: a tied straddler that the ladder cannot drop instead shoves the
     * cell'd lobe siblings far outside their bubble regions.
     *
     * With a shared single parent (the realistic conjoined case: one detected
     * bubble, several OCR children), the straddler draws near the neck and its
     * inflated occupancy collides with both lobe blocks' adaptive layouts. The
     * ladder then accepts shifted/refit candidates that land ~66px from each
     * block's OCR center — text rendered OUTSIDE its region (legacy: 0px,
     * centered in the block's own midpoint region). To a reader the text is
     * missing from the bubble it belongs to.
     *
     * TODO(T912-fix): cell'd siblings must keep their in-cell placement; the
     * cell-less block (tie) should be the one falling back to a clipped
     * legacy rectangle, not the assigned blocks being pushed out.
     */
    @Test
    fun `repro - tied straddler displaces cell'd siblings far outside their regions`() {
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
        val byText = plan.resultsInInputOrder.associate { it.chosenText to it.outcome }

        // All three draw, but the lobe blocks are knocked out of their regions.
        val a = (byText.getValue("AAAAAAAAAA") as LayoutOutcome.Draw).layout
        val b = (byText.getValue("BBBBBBBBBB") as LayoutOutcome.Draw).layout
        val dispA = a.originX - (a.block.x + a.block.width / 2f)
        val dispB = b.originX - (b.block.x + b.block.width / 2f)
        val dispAy = a.originY - (a.block.y + a.block.height / 2f)
        val dispBy = b.originY - (b.block.y + b.block.height / 2f)
        val euclidA = kotlin.math.sqrt(dispA * dispA + dispAy * dispAy)
        val euclidB = kotlin.math.sqrt(dispB * dispB + dispBy * dispBy)
        euclidA.shouldBeGreaterThan(40f)
        euclidB.shouldBeGreaterThan(40f)
    }

    // ---- contrast: the designed path works ----------------------------------

    /**
     * contrast (current behavior is CORRECT here): a thick-neck conjoined mask
     * with one block per lobe partitions into disjoint cells and draws every
     * block. This is the behavior the fix must preserve.
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
}
