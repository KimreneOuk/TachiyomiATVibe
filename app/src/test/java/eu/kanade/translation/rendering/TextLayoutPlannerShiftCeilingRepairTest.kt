package eu.kanade.translation.rendering

import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.segmentation.BubbleMaskRle
import eu.kanade.translation.segmentation.MaskGeometry
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** Focused regressions for the masked post-anchor shift/ceiling repair. */
class TextLayoutPlannerShiftCeilingRepairTest {
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
    ) = TranslationBlock(
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
    )

    private fun rectMask(
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) = BubbleMaskRle(
        width = width,
        height = height,
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

    @Test
    fun `masked resolver replaces a far free-strip refit with the fully contained reflow`() {
        // The mask component is deliberately much larger than B's OCR box, so
        // its resolver-entry layout is centered at (500,200). A's wide text
        // overlaps it deeply; every useful directional move/free-strip anchor
        // is farther than B's 25px masked cap. The tiered contained reflow
        // rescue takes candidate 1 and, when no capped candidate clears the
        // collision, is returned as the terminal contained-with-overlap draw.
        val a = block(100f, 130f, 500f, 140f, "AAAAAAAAAAAAAAAAAAAA", 0.9f)
        val b = block(400f, 150f, 200f, 100f, "BBBBBBBBBBBBBBBBBBBB", 0.5f)
            .copy(
                blockId = "masked",
                segmentationMask = rectMask(1000, 1000, 200, 0, 800, 400),
            )
        val solo = draw(
            TextLayoutPlanner.planPageInternal(listOf(b), 1000f, 1000f, 1, false, measurer)
                .plan.resultsInInputOrder.single(),
        )

        val first = TextLayoutPlanner.planPageInternal(listOf(a, b), 1000f, 1000f, 1, false, measurer)
        val second = TextLayoutPlanner.planPageInternal(listOf(a, b), 1000f, 1000f, 1, false, measurer)
        val moved = draw(first.plan.resultsInInputOrder.single { it.identity.blockId == "masked" })
        val replay = draw(second.plan.resultsInInputOrder.single { it.identity.blockId == "masked" })
        val cap = TextLayoutPlanner.maskedShiftCap(b, 1000f, 1000f)

        abs(moved.originX - solo.originX).shouldBeLessThanOrEqual(cap + 0.001f)
        abs(moved.originY - solo.originY).shouldBeLessThanOrEqual(cap + 0.001f)
        moved.originX shouldBe solo.originX
        moved.originY shouldBe solo.originY
        // Contained-fit contract: no hard clip; the planned positioned lines
        // reconstruct the complete text and every painted envelope is inside
        // the component's exact row spans.
        moved.clipRect shouldBe null
        moved.maskUsable shouldBe true
        val lines = moved.positionedLines.shouldNotBeNull()
        lines.filter { it.text.isNotEmpty() }.shouldNotBeNull()
        lines.sumOf { line -> line.text.count { ch -> ch == 'B' } } shouldBe 20
        val geometry = when (val r = MaskGeometry.fromOrderedRle(b.segmentationMask!!, eu.kanade.translation.segmentation.MaskConversionBudgets())) {
            is eu.kanade.translation.segmentation.OrderedMaskResult.Success -> r.geometry
            else -> null
        }.shouldNotBeNull()
        // The rescue's ceiling is the component spans dilated by the
        // CONTAINMENT_CEILING_MARGIN_PX margin.
        val ceiling = geometry.components[0].spans.map {
            eu.kanade.translation.segmentation.MaskGeometry.RowSpan(
                it.y,
                (it.start - TextLayoutTuning.CONTAINMENT_CEILING_MARGIN_PX).coerceAtLeast(0),
                it.endExclusive + TextLayoutTuning.CONTAINMENT_CEILING_MARGIN_PX,
            )
        }
        TextLayoutPlanner.paintEnvelopeContainedInSpans(moved, ceiling, measurer, 1f) shouldBe true
        first.plan.resultsInInputOrder shouldHaveSize 2
        moved.copy(maskGeometry = null) shouldBe replay.copy(maskGeometry = null)
        first.finalPlacementAttempts shouldBe second.finalPlacementAttempts
        (first.finalPlacementAttempts > 0) shouldBe true
        (first.finalPlacementAttempts <= TextLayoutTuning.MAX_FINAL_PLACEMENT_ATTEMPTS) shouldBe true
    }

    @Test
    fun `mask shift budget uses the tighter region or page ceiling`() {
        val small = block(0f, 0f, 80f, 40f, "A", 1f)
        TextLayoutPlanner.maskedShiftCap(small, 1000f, 600f) shouldBe 10f

        val large = block(0f, 0f, 800f, 500f, "A", 1f)
        TextLayoutPlanner.maskedShiftCap(large, 1000f, 600f) shouldBe 24f

        // The two pre-repair resolver fixtures required these displacements.
        TextLayoutPlanner.relocationWithinCap(0f, 0f, 53.68f, 0f, 25f) shouldBe false
        TextLayoutPlanner.relocationWithinCap(0f, 0f, 378.08f, 0f, 25f) shouldBe false
        TextLayoutPlanner.relocationWithinCap(0f, 0f, 24f, 0f, 25f) shouldBe true
    }

    @Test
    fun `stroke envelope at a sloped ceiling is rejected until every touched row covers it`() {
        val rect = FloatRect(10f, 10f, 20f, 16f)
        val crossing = (8 until 18).map { y ->
            MaskGeometry.RowSpan(y, 0, if (y == 8) 21 else 22)
        }
        val inside = (8 until 18).map { y -> MaskGeometry.RowSpan(y, 0, 22) }

        TextLayoutPlanner.paintRectContainedInSpans(rect, 2f, crossing) shouldBe false
        TextLayoutPlanner.paintRectContainedInSpans(rect, 2f, inside) shouldBe true

        val layout = legacyLayout(originX = 15f, originY = 13f, strokeWidth = 2f)
        val layoutCrossing = (8 until 18).map { y ->
            MaskGeometry.RowSpan(y, 0, if (y == 8) 18 else 19)
        }
        val layoutInside = (8 until 18).map { y -> MaskGeometry.RowSpan(y, 0, 19) }
        TextLayoutPlanner.paintEnvelopeContainedInSpans(layout, layoutCrossing, measurer, 1f) shouldBe false
        TextLayoutPlanner.paintEnvelopeContainedInSpans(layout, layoutInside, measurer, 1f) shouldBe true
    }

    @Test
    fun `component hole cannot satisfy one continuous shifted paint envelope`() {
        val spans = buildList {
            for (y in 8 until 18) {
                add(MaskGeometry.RowSpan(y, 0, 14))
                add(MaskGeometry.RowSpan(y, 18, 32))
            }
        }

        TextLayoutPlanner.paintRectContainedInSpans(
            FloatRect(10f, 10f, 22f, 16f),
            inflate = 2f,
            spans = spans,
        ) shouldBe false

        val line = PositionedLine(
            text = "MMMM",
            leftPx = 10,
            topPx = 10,
            layoutWidthPx = 12,
            layoutHeightPx = 6,
            conservativeOccupancy = FloatRect(8f, 8f, 24f, 18f),
        )
        val positioned = legacyLayout(16f, 13f, 2f).copy(
            positionedLines = listOf(line),
            conservativeOccupancy = listOf(line.conservativeOccupancy),
        )
        TextLayoutPlanner.paintEnvelopeContainedInSpans(positioned, spans, measurer, 1f) shouldBe false
    }


    private fun legacyLayout(originX: Float, originY: Float, strokeWidth: Float) = BlockLayout(
        block = block(originX - 5f, originY - 3f, 10f, 6f, "A", 1f),
        text = "A",
        isVertical = false,
        originX = originX,
        originY = originY,
        safeW = 10f,
        safeH = 6f,
        fontSizePx = 5f,
        strokeWidth = strokeWidth,
        drawAlign = TextAlign.CENTER,
        clipRect = null,
        lines = listOf("A"),
    )
}
