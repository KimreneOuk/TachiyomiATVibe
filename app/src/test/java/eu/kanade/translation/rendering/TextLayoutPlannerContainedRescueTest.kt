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

/**
 * Focused regressions for the T912 contained-fit rescue: the OCR-box-first
 * tiered reflow that replaces the clip-first masked tail. Contract pinned
 * against the Director-validated iteration 9 model:
 *
 *  - a colliding masked block is rescued by a fully contained reflow whose
 *    painted envelopes lie inside the component's exact row spans;
 *  - the never-drop contract still holds everywhere (Draw, never NonDraw);
 *  - a mask that cannot host the text at the render floor fails OPEN: the
 *    unshifted OCR-region draw with NO exact component clip (maskUsable=false
 *    strips the wired metadata).
 */
@Disabled("Superseded by Desktop 1:1 text layout engine port")
class TextLayoutPlannerContainedRescueTest {
    private class FakeMeasurer : TextMeasurer {
        override fun measureTextWidth(text: String, fontSizePx: Float): Float =
            text.length * 0.6f * fontSizePx

        override fun lineHeight(fontSizePx: Float): Float = fontSizePx * 1.2f
    }

    private val measurer = FakeMeasurer()

    private fun block(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        text: String,
        score: Float,
        mask: BubbleMaskRle? = null,
        blockId: String? = null,
    ) = TranslationBlock(
        blockId = blockId,
        text = "",
        translation = text,
        width = width,
        height = height,
        x = x,
        y = y,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
        label = 1,
        score = score,
        direction = "LTR",
        parentX = x,
        parentY = y,
        parentWidth = width,
        parentHeight = height,
        segmentationMask = mask,
    )

    /** Rectangular row-major mask with a SLOPED top: [slopeEnd) rows are narrow. */
    private fun slopedTopMask(
        width: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        narrowLeft: Int,
        narrowRight: Int,
        slopeEnd: Int,
    ) = BubbleMaskRle(
        width = width,
        height = bottom.coerceAtMost(width),
        bounds = listOf(left, top, right, bottom),
        runs = buildList {
            for (y in top until bottom) {
                val (l, r) = if (y < slopeEnd) narrowLeft to narrowRight else left to right
                add(y * width + l)
                add(r - l)
            }
        },
        score = 1f,
    )

    private fun rectMask(width: Int, left: Int, top: Int, right: Int, bottom: Int) =
        BubbleMaskRle(
            width = width,
            height = bottom.coerceAtMost(width),
            bounds = listOf(left, top, right, bottom),
            runs = buildList {
                for (y in top until bottom) {
                    add(y * width + left)
                    add(right - left)
                }
            },
            score = 1f,
        )

    /** The rescue's ceiling is the raw component spans dilated by the margin. */
    private fun dilatedMaskSpans(mask: BubbleMaskRle): List<MaskGeometry.RowSpan> =
        spansOf(mask).map {
            MaskGeometry.RowSpan(it.y, (it.start - 6).coerceAtLeast(0), it.endExclusive + 6)
        }

    private fun draw(result: LayoutResult): BlockLayout = (result.outcome as LayoutOutcome.Draw).layout

    private fun spansOf(mask: BubbleMaskRle): List<MaskGeometry.RowSpan> =
        when (val r = MaskGeometry.fromOrderedRle(mask, MaskConversionBudgets())) {
            is OrderedMaskResult.Success -> r.geometry.components[0].spans
            is OrderedMaskResult.Fallback -> throw AssertionError("fixture mask must convert")
        }

    @Test
    fun `colliding masked block ends fully inside a sloped ceiling`() {
        // Component with a sloped upper ceiling: rows [80,100) only span
        // [200,400); rows [100,340) span the full [100,500). B's OCR box
        // reaches above the slope and A collides with it deeply. Whichever
        // capped ladder candidate wins, the INVARIANT is: complete text, Draw,
        // no hard clip, and every painted envelope inside the component.
        val mask = slopedTopMask(600, 100, 80, 500, 340, 200, 400, 100)
        val a = block(120f, 140f, 360f, 110f, "AAAAAAAAAAAAAAAAAAAAAAAAAAAA", 0.9f)
        val b = block(240f, 60f, 160f, 200f, "bbbbbbbbbbbbbbbbbbbbbbbb", 0.5f, mask, "b")

        val plan = TextLayoutPlanner.planPageInternal(listOf(a, b), 600f, 400f, 1, false, measurer)
        val moved = draw(plan.plan.resultsInInputOrder.single { it.identity.blockId == "b" })

        moved.clipRect.shouldBeNull()
        moved.maskUsable shouldBe true
        moved.text shouldBe "bbbbbbbbbbbbbbbbbbbbbbbb"
        TextLayoutPlanner.paintEnvelopeContainedInSpans(moved, spansOf(mask), measurer, 1f) shouldBe true
        // The high-priority sibling keeps its own complete layout.
        val aLayout = draw(plan.plan.resultsInInputOrder.single { it.identity.blockId == null })
        aLayout.text shouldBe "AAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }

    @Test
    fun `mask that cannot host the text fails open to the visible unshifted draw`() {
        // The proven anchor-collision pair: A and B anchor to parent-box
        // centers and B's resolver-entry layout deeply overlaps A. B's mask is
        // a DEGENERATE empty-runs mask: its bounds-mode cell has no component
        // spans, so the tiered rescue is null by construction (nothing to
        // contain against) and every capped ladder candidate fails — exactly
        // the mask-unusable condition. The tail must fail OPEN: unshifted
        // entry geometry, Draw, and NO wired component clip.
        val mask = BubbleMaskRle(
            width = 1000,
            height = 1000,
            bounds = listOf(250, 110, 450, 210),
            runs = emptyList(),
            score = 1f,
        )
        val a = block(100f, 100f, 200f, 100f, "AAAAAAAAAA", 0.9f)
            .copy(parentX = 100f, parentY = 100f, parentWidth = 200f, parentHeight = 100f)
        val b = block(250f, 110f, 200f, 100f, "BBBBBBBBBB", 0.5f, mask, "b")
            .copy(parentX = 250f, parentY = 110f, parentWidth = 200f, parentHeight = 100f)

        val soloPlan = TextLayoutPlanner.planPageInternal(listOf(b), 1000f, 1000f, 1, false, measurer)
        val solo = draw(soloPlan.plan.resultsInInputOrder.single())

        val plan = TextLayoutPlanner.planPageInternal(listOf(a, b), 1000f, 1000f, 1, false, measurer)
        val fallback = draw(plan.plan.resultsInInputOrder.single { it.identity.blockId == "b" })

        // The resolver ran and exhausted its capped ladder.
        (plan.finalPlacementAttempts > 0) shouldBe true
        (plan.finalPlacementAttempts <= TextLayoutTuning.MAX_FINAL_PLACEMENT_ATTEMPTS) shouldBe true
        // Fail-open contract.
        fallback.maskUsable shouldBe false
        fallback.maskGeometry.shouldBeNull()
        fallback.maskComponentId.shouldBeNull()
        fallback.cellRect.shouldBeNull()
        fallback.clipRect.shouldNotBeNull()
        // Unshifted: the same geometry the solo plan chose.
        fallback.originX shouldBe solo.originX
        fallback.originY shouldBe solo.originY
        fallback.fontSizePx shouldBe solo.fontSizePx
        fallback.text shouldBe "BBBBBBBBBB"
        // Never drop.
        plan.plan.resultsInInputOrder.map { it.outcome }.filterIsInstance<LayoutOutcome.Draw>() shouldHaveSize 2
    }

    @Test
    fun `non-colliding masked block is placed by containment-first at its OCR home`() {
        // T912 containment-first: the rescue runs for EVERY horizontal masked
        // block BEFORE any shift machinery — not only for colliding ones (the
        // collision-tail wiring left the shipped planner a no-op on real
        // pages). A solo masked block must come out positioned at home,
        // contained, unclipped, and the plan must stay deterministic.
        val mask = rectMask(400, 40, 40, 360, 300)
        val b = block(80f, 80f, 160f, 120f, "hello small world", 1f, mask, "b")
        val plan = TextLayoutPlanner.planPageInternal(listOf(b), 400f, 300f, 1, false, measurer)
        val layout = draw(plan.plan.resultsInInputOrder.single())

        layout.positionedLines.shouldNotBeNull()
        layout.clipRect.shouldBeNull()
        layout.maskUsable shouldBe true
        layout.fontSizePx shouldBe
            TextLayoutPlanner.planPageInternal(listOf(b), 400f, 300f, 1, false, measurer)
                .let { draw(it.plan.resultsInInputOrder.single()) }.fontSizePx
        // At home: the origin stays inside the OCR box.
        (layout.originX >= b.x && layout.originX <= b.x + b.width) shouldBe true
        (layout.originY >= b.y && layout.originY <= b.y + b.height) shouldBe true
        TextLayoutPlanner.paintEnvelopeContainedInSpans(layout, dilatedMaskSpans(mask), measurer, 1f) shouldBe true
        plan.finalPlacementAttempts shouldBe 0
    }

    @Test
    fun `beyond-cap shared-mask members are contained too`() {
        // The shipped regression: members past the shared-cell optimization
        // cap kept legacy far-shifted layouts because their disjoint slabs
        // made every pair collision-exempt. With the raw-mask/component
        // ceiling, EVERY member — optimized or not — comes out positioned and
        // contained inside the one component.
        val mask = rectMask(400, 20, 20, 380, 300)
        val blocks = (0 until 9).map { i ->
            block(
                x = 40f + (i % 3) * 110f,
                y = 40f + (i / 3) * 90f,
                width = 80f,
                height = 60f,
                text = "block number $i text",
                score = 1f - i * 0.01f,
                mask = mask,
                blockId = "b$i",
            )
        }
        val plan = TextLayoutPlanner.planPageInternal(blocks, 400f, 300f, 1, false, measurer)
        val layouts = plan.plan.resultsInInputOrder.map { draw(it) }
        layouts.forEachIndexed { i, layout ->
            layout.positionedLines.shouldNotBeNull()
            layout.maskUsable shouldBe true
            TextLayoutPlanner.paintEnvelopeContainedInSpans(layout, dilatedMaskSpans(mask), measurer, 1f) shouldBe true
            val b = blocks[i]
            (layout.originY >= b.y - 1f && layout.originY <= b.y + b.height + 1f) shouldBe true
        }
        plan.plan.resultsInInputOrder.map { it.outcome }.filterIsInstance<LayoutOutcome.Draw>() shouldHaveSize 9
    }
}
