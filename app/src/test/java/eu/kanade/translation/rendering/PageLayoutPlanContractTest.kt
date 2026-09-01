package eu.kanade.translation.rendering

import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.segmentation.BubbleMaskRle
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * T912 slice 3: the explicit [PageLayoutPlan] planner contract — identity
 * multiset over nonblank inputs in input order, planning/render ordinals,
 * blank absence, explicit non-draw reasons, and equivalence of the legacy
 * [TextLayoutPlanner.plan] wrapper with the plan's drawable list.
 */
class PageLayoutPlanContractTest {

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
        translation: String,
        score: Float = 1f,
        blockId: String? = null,
    ) = TranslationBlock(
        text = "",
        translation = translation,
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
    ).copy(blockId = blockId)

    private fun fullMask(width: Int = 300, height: Int = 120) =
        BubbleMaskRle(width, height, listOf(0, 0, width, height), listOf(0, width * height), score = 1f)

    /** Narrow helper to keep outcome assertions readable. */
    private inline fun <reified T : LayoutOutcome> LayoutOutcome.shouldBeInstanceOf(): T = when (this) {
        is T -> this
        else -> throw AssertionError("expected ${T::class.java.simpleName}, was $this")
    }

    @Test
    fun `identity multiset matches nonblank inputs exactly in input order`() {
        val a = block(100f, 100f, 100f, 40f, "A", score = 0.5f, blockId = "a")
        val b = block(300f, 100f, 100f, 40f, "B", score = 0.9f, blockId = "b")
        val blank = block(500f, 100f, 100f, 40f, "", score = 1f, blockId = "blank")
        val d = block(700f, 100f, 100f, 40f, "D", score = 0.7f, blockId = "d")

        val page = TextLayoutPlanner.planPage(listOf(a, b, blank, d), 1000f, 1000f, 1, false, FakeMeasurer())

        page.resultsInInputOrder.map { it.identity.inputIndex } shouldBe listOf(0, 1, 3)
        page.resultsInInputOrder.map { it.identity.blockId } shouldBe listOf("a", "b", "d")
        page.resultsInInputOrder.map { it.chosenText } shouldBe listOf("A", "B", "D")
    }

    @Test
    fun `planning ordinal is score desc then input and render ordinal counts draws only`() {
        val a = block(100f, 100f, 100f, 40f, "A", score = 0.5f, blockId = "a")
        val b = block(300f, 100f, 100f, 40f, "B", score = 0.9f, blockId = "b")
        val blank = block(500f, 100f, 100f, 40f, "", score = 1f, blockId = "blank")
        val d = block(700f, 100f, 100f, 40f, "D", score = 0.7f, blockId = "d")

        val page = TextLayoutPlanner.planPage(listOf(a, b, blank, d), 1000f, 1000f, 1, false, FakeMeasurer())

        // Placement order: b (0.9), d (0.7), a (0.5).
        page.resultsInInputOrder.associate { it.identity.blockId to it.planningOrdinal } shouldBe
            mapOf("a" to 2, "b" to 0, "d" to 1)
        page.resultsInInputOrder.associate { it.identity.blockId to it.renderOrdinal } shouldBe
            mapOf("a" to 2, "b" to 0, "d" to 1)
        page.drawableInRenderOrder.map { it.text } shouldBe listOf("B", "D", "A")
    }

    @Test
    fun `blank inputs are absent from results and drawable output`() {
        val a = block(100f, 100f, 100f, 40f, "A", blockId = "a")
        val blank = block(500f, 100f, 100f, 40f, "   ", blockId = "blank")

        val page = TextLayoutPlanner.planPage(listOf(a, blank), 1000f, 1000f, 1, false, FakeMeasurer())

        page.resultsInInputOrder shouldHaveSize 1
        page.resultsInInputOrder.single().identity.blockId shouldBe "a"
        page.drawableInRenderOrder shouldHaveSize 1
        page.drawableInRenderOrder.single().text shouldBe "A"
    }

    @Test
    fun `empty shared cell falls back to the legacy region and still draws (R1 repair)`() {
        // T912 REPAIR (R1, documented deviation): this fixture previously
        // asserted `NonDraw(EMPTY_SHARED_CELL)` for the middle member of three
        // near-equal centers (cuts 100/101 with gap 2 make its slab [101,100)
        // degenerate). The Director's visibility override abolishes the drop:
        // the block now falls back to its own pre-slice-3 legacy region and
        // DRAWS without a cell, while the siblings keep their disjoint cells.
        val mask = fullMask()
        val a = block(90f, 50f, 20f, 20f, "A", score = 0.9f, blockId = "a").copy(segmentationMask = mask)
        val b = block(91f, 50f, 20f, 20f, "B", score = 0.8f, blockId = "b").copy(segmentationMask = mask)
        val c = block(92f, 50f, 20f, 20f, "C", score = 0.7f, blockId = "c").copy(segmentationMask = mask)

        val page = TextLayoutPlanner.planPage(listOf(a, b, c), 300f, 120f, 1, false, FakeMeasurer())

        page.resultsInInputOrder shouldHaveSize 3
        val byId = page.resultsInInputOrder.associateBy { it.identity.blockId }
        val bOutcome = byId.getValue("b").outcome
        (bOutcome as LayoutOutcome.Draw).layout.cellRect.shouldBeNull()
        byId.getValue("a").outcome.shouldBeInstanceOf<LayoutOutcome.Draw>()
        byId.getValue("c").outcome.shouldBeInstanceOf<LayoutOutcome.Draw>()
        // Render ordinals stay consecutive over the drawn layouts.
        page.resultsInInputOrder.mapNotNull { it.renderOrdinal } shouldBe listOf(0, 1, 2)
    }

    @Test
    fun `sub pixel source rects stay drawable through the legacy clamp`() {
        // The INVALID_OR_SUBPIXEL_SOURCE_RECT branch preserves the legacy
        // `safeW < 1f` predicate; computeRects floors safeW/safeH at 1, so a
        // tiny source rect still draws with the clamped 1px safe rect — pinned
        // here as a compatibility regression guard.
        val tiny = block(400f, 400f, 0.5f, 0.5f, "t", blockId = "tiny")

        val page = TextLayoutPlanner.planPage(listOf(tiny), 1000f, 1000f, 1, false, FakeMeasurer())

        page.resultsInInputOrder shouldHaveSize 1
        page.resultsInInputOrder.single().outcome.shouldBeInstanceOf<LayoutOutcome.Draw>()
        page.drawableInRenderOrder.single().safeW shouldBe 1f
        page.drawableInRenderOrder.single().safeH shouldBe 1f
    }

    @Test
    fun `plan wrapper equals the page plan drawable list for the same inputs`() {
        val mask = fullMask()
        val shared1 = block(10f, 30f, 80f, 60f, "LEFT", score = 0.8f, blockId = "l")
            .copy(segmentationMask = mask)
        val shared2 = block(210f, 30f, 80f, 60f, "RIGHT", score = 0.4f, blockId = "r")
            .copy(segmentationMask = mask)
        val plain = block(500f, 500f, 120f, 60f, "PLAIN", score = 0.9f, blockId = "p")
        val inputs = listOf(shared1, plain, shared2)

        val page = TextLayoutPlanner.planPage(inputs, 1000f, 1000f, 1, false, FakeMeasurer())
        val wrapped = TextLayoutPlanner.plan(inputs, 1000f, 1000f, 1, false, FakeMeasurer())

        wrapped shouldHaveSize page.drawableInRenderOrder.size
        wrapped.zip(page.drawableInRenderOrder).forEach { (legacy, planned) ->
            legacy.text shouldBe planned.text
            legacy.isVertical shouldBe planned.isVertical
            legacy.originX shouldBe planned.originX
            legacy.originY shouldBe planned.originY
            legacy.safeW shouldBe planned.safeW
            legacy.safeH shouldBe planned.safeH
            legacy.fontSizePx shouldBe planned.fontSizePx
            legacy.strokeWidth shouldBe planned.strokeWidth
            legacy.drawAlign shouldBe planned.drawAlign
            legacy.clipRect shouldBe planned.clipRect
            legacy.lines shouldBe planned.lines
            legacy.planGeometryId shouldBe planned.planGeometryId
            legacy.maskComponentId shouldBe planned.maskComponentId
            legacy.cellRect shouldBe planned.cellRect
            // Separate plan calls build separate geometry instances; compare
            // by value.
            legacy.maskGeometry?.spans shouldBe planned.maskGeometry?.spans
            legacy.maskGeometry?.components?.map { it.id to it.spans } shouldBe
                planned.maskGeometry?.components?.map { it.id to it.spans }
        }
    }
}
