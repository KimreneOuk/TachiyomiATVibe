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
    fun `non-colliding masked block never pays for the rescue`() {
        val mask = rectMask(400, 40, 40, 360, 280)
        val b = block(80f, 80f, 160f, 120f, "hello small world", 1f, mask, "b")
        val plan = TextLayoutPlanner.planPageInternal(listOf(b), 400f, 300f, 1, false, measurer)
        val layout = draw(plan.plan.resultsInInputOrder.single())
        layout.maskUsable shouldBe true
        plan.finalPlacementAttempts shouldBe 0
        val replay = TextLayoutPlanner.planPageInternal(listOf(b), 400f, 300f, 1, false, measurer)
        draw(replay.plan.resultsInInputOrder.single()) shouldBe layout
    }
}
