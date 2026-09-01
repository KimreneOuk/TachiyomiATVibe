package eu.kanade.translation.rendering

import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.segmentation.BubbleMaskRle
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * T912 slice 5: planner integration of adaptive bands and the page budgets —
 * eligibility, positioned-line contract fields, ALL-CAPS trial wiring,
 * StaticLayout/positioned-line reservation in placement order with the
 * legacy retry and explicit `STATIC_LAYOUT_BUDGET_EXHAUSTED`, the 24-line
 * block cap fallback, and the 65th-distinct-pair cap semantics.
 *
 * T912 containment-first re-pins: masked horizontal blocks are placed by the
 * contained reflow rescue before the band/legacy machinery; the tests below
 * exercise the band/legacy machinery via fixtures whose rescue declines.
 */
class TextLayoutPlannerSlice5Test {

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
        direction: String = "LTR",
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
        direction = direction,
    )

    private fun fullMask(width: Int, height: Int) =
        BubbleMaskRle(width, height, listOf(0, 0, width, height), listOf(0, width * height), score = 1f)

    private val m = FakeMeasurer()

    // ---- eligibility and positioned-line contract -------------------------

    @Test
    fun `span mode horizontal block on a rectangle cell takes the contained rescue from its ocr home`() {
        // Re-pinned for T912 containment-first: the masked block is placed by
        // the contained reflow rescue BEFORE any band/legacy machinery. The
        // OCR box (60,40)-(180,80) is the home: the column keeps its x-range
        // and the natural reflow fit (font 16 for the 18-char text in 120px)
        // wraps to two positioned lines inside the mask ceiling. Still a Draw
        // with the identical hard-cell metadata contract.
        val mask = fullMask(300, 120)
        val input = block(60f, 40f, 120f, 40f, "Hello there friend").copy(segmentationMask = mask)

        val plan = TextLayoutPlanner.planPage(listOf(input), 300f, 120f, 1, false, m)

        val result = plan.resultsInInputOrder.single()
        val layout = (result.outcome as LayoutOutcome.Draw).layout
        val lines = layout.positionedLines.shouldNotBeNull()
        lines.map { it.text } shouldBe listOf("Hello there", "friend")
        lines.joinToString(" ") { it.text } shouldBe "Hello there friend"
        layout.text shouldBe "Hello there friend"
        // Contained rescue placement: anchored at the OCR home. The font cap
        // is the LARGER of the OCR box fit and the legacy rectangle fit (16
        // vs 43 here) — containment against the dilated ceiling decided 43.
        layout.originX shouldBe 120f
        layout.originY shouldBe 60f
        layout.fontSizePx shouldBe 43f
        (layout.safeW >= 120f) shouldBe true
        (layout.safeH >= 40f) shouldBe true
        layout.maskUsable shouldBe true
        // Contained: every line's painted envelope lies inside the mask
        // ceiling (the assigned component's row spans).
        val spans = layout.maskGeometry.shouldNotBeNull()
            .components[layout.maskComponentId.shouldNotBeNull()].spans
        TextLayoutPlanner.paintEnvelopeContainedInSpans(layout, spans, m, 1f) shouldBe true
        layout.drawAlign shouldBe TextAlign.CENTER
        layout.clipRect.shouldBeNull()
        layout.strokeWidth shouldBe TextLayoutPlanner.computeStrokeWidth(43f, 1f)
        // Hard clip mirrors the span-mode metadata: ids + the slab.
        layout.hardClip shouldBe HardClip(0, 0, FloatRect(0f, 0f, 300f, 120f))
        layout.cellRect shouldBe FloatRect(0f, 0f, 300f, 120f)
        layout.planGeometryId shouldBe 0
        layout.maskComponentId shouldBe 0
    }

    @Test
    fun `all caps trial inserts hyphens into planned lines only`() {
        // Re-pinned fixture for T912 containment-first: with the original
        // 120x40 OCR box the contained reflow rescue places the atomic token
        // whole at its natural fit, so the ALL-CAPS trial is never needed.
        // The degenerate 3x3 OCR box (same cell center) is below the rescue's
        // 4px home minimum, the rescue declines, and the block reaches the
        // adaptive band ladder whose trial machinery is under test.
        val mask = fullMask(300, 120)
        val original = "A".repeat(16)
        val input = block(118.5f, 58.5f, 3f, 3f, original).copy(segmentationMask = mask)

        val plan = TextLayoutPlanner.planPage(listOf(input), 300f, 120f, 1, false, m)

        val layout = (plan.resultsInInputOrder.single().outcome as LayoutOutcome.Draw).layout
        // Baseline (unhyphenated) is one atomic token that can never wrap; the
        // balanced one-break trial removes the slab-rect overflow and wins.
        layout.lines shouldBe listOf("AAAAAAAA-", "AAAAAAAA")
        layout.positionedLines.shouldNotBeNull().map { it.text } shouldBe layout.lines
        // Persisted translation untouched; the layout text stays the block's own.
        layout.text shouldBe original
        input.translation shouldBe original
    }

    // ---- page budgets -----------------------------------------------------

    @Test
    fun `static layout budget exhaustion emits explicit non draw with identity intact`() {
        // 257 legacy horizontal blocks: 256 draw (2 StaticLayouts each = 512),
        // the 257th exceeds the page budget → explicit NonDraw.
        val inputs = (0 until 257).map { i ->
            block((i * 13 % 900).toFloat(), (i * 29 % 900).toFloat(), 20f, 8f, "T$i", score = 0.5f)
                .copy(blockId = "b$i")
        }

        val plan = TextLayoutPlanner.planPage(inputs, 1000f, 1000f, 1, false, m)

        plan.resultsInInputOrder shouldHaveSize 257
        plan.resultsInInputOrder.map { it.identity.blockId }.toSet() shouldBe (0 until 257).map { "b$it" }.toSet()
        plan.drawableInRenderOrder shouldHaveSize 256
        val nonDraws = plan.resultsInInputOrder.filter { it.outcome is LayoutOutcome.NonDraw }
        nonDraws shouldHaveSize 1
        val nonDraw = nonDraws.single()
        nonDraw.identity.blockId shouldBe "b256"
        nonDraw.outcome shouldBe LayoutOutcome.NonDraw(NonDrawReason.STATIC_LAYOUT_BUDGET_EXHAUSTED)
        nonDraw.renderOrdinal.shouldBeNull()
        // Render ordinals stay consecutive over the draws only.
        plan.resultsInInputOrder.mapNotNull { it.renderOrdinal } shouldBe (0 until 256).toList()
    }

    @Test
    fun `contained rescue positioned lines reserve zero static layouts`() {
        // Re-pinned for T912 containment-first (was: the adaptive band path
        // reserved 2 StaticLayouts per positioned line and the 255th legacy
        // block NonDrawed). One contained-rescue block with 1 positioned line,
        // then 256 legacy horizontal blocks: positioned lines draw via
        // drawText and consume ONLY the positioned-line lane, so the 512
        // StaticLayouts suffice for all 256 legacy blocks and EVERY block
        // draws. The count discriminates: had the rescue charged its line to
        // the StaticLayout lane (2), l255 would NonDraw.
        val mask = fullMask(300, 1200)
        val rescue = block(50f, 550f, 100f, 100f, "A".repeat(8), score = 1f)
            .copy(blockId = "rescue", segmentationMask = mask)
        val legacy = (0 until 256).map { i ->
            block((i * 7 % 290).toFloat(), (i * 11 % 1150).toFloat(), 20f, 8f, "M", score = 0.5f)
                .copy(blockId = "l$i")
        }

        val plan = TextLayoutPlanner.planPage(listOf(rescue) + legacy, 300f, 1200f, 1, false, m)

        plan.resultsInInputOrder shouldHaveSize 257
        val rescueLayout = plan.drawableInRenderOrder.first()
        rescueLayout.positionedLines.shouldNotBeNull() shouldHaveSize 1
        // The rescue layout drew, and every legacy block drew behind it: the
        // StaticLayout lane was never charged for the positioned line.
        plan.drawableInRenderOrder shouldHaveSize 257
        plan.resultsInInputOrder.none { it.outcome is LayoutOutcome.NonDraw } shouldBe true
    }

    @Test
    fun `vertical layout reserves zero static layouts`() {
        // One vertical block plus 257 legacy horizontal blocks: the vertical
        // consumes nothing, so all 256 horizontal blocks draw and only the
        // 257th NonDraws.
        val vertical = block(300f, 300f, 60f, 200f, "こんにちは", score = 1f, direction = "TTB")
            .copy(blockId = "vertical")
        val legacy = (0 until 257).map { i ->
            block((i * 5 % 1400).toFloat(), (i * 17 % 1400).toFloat(), 20f, 8f, "M", score = 0.5f)
                .copy(blockId = "l$i")
        }

        val plan = TextLayoutPlanner.planPage(listOf(vertical) + legacy, 1500f, 1500f, 1, false, m)

        plan.resultsInInputOrder shouldHaveSize 258
        val verticalLayout = plan.drawableInRenderOrder.first { it.block.blockId == "vertical" }
        verticalLayout.isVertical shouldBe true
        verticalLayout.positionedLines.shouldBeNull() // vertical never uses positioned lines
        plan.drawableInRenderOrder shouldHaveSize 257
        val nonDraw = plan.resultsInInputOrder.last { it.outcome is LayoutOutcome.NonDraw }
        nonDraw.identity.blockId shouldBe "l256"
        nonDraw.outcome shouldBe LayoutOutcome.NonDraw(NonDrawReason.STATIC_LAYOUT_BUDGET_EXHAUSTED)
    }

    @Test
    fun `per block 24 line cap falls back to the legacy rectangular layout`() {
        // The 120-row slab cannot host the >24 lines this text needs at the
        // minimum font, so every font fails and the block keeps a Draw result
        // in the stroke-inset rectangular cell form.
        val mask = fullMask(300, 120)
        val input = block(50f, 30f, 100f, 60f, "ab ".repeat(300).trimEnd())
            .copy(blockId = "long", segmentationMask = mask)

        val plan = TextLayoutPlanner.planPage(listOf(input), 300f, 120f, 1, false, m)

        val result = plan.resultsInInputOrder.single()
        val layout = (result.outcome as LayoutOutcome.Draw).layout
        layout.positionedLines.shouldBeNull()
        layout.cellRect.shouldNotBeNull()
        (layout.fontSizePx >= 8f) shouldBe true
    }

    @Test
    fun `page positioned line budget retries the legacy single layout form`() {
        // 11 blocks, each on its own full-width strip of one 300x2596 page.
        // Every strip fits 24 positioned lines at the minimum font (the stack
        // spans the full strip height), so blocks 0-9 consume 24 lines each
        // (240 total) and block 10 would push past
        // MAX_POSITIONED_LINES_PER_PAGE=256 → legacy single-layout retry.
        // T912 quality repair (Fix 2): the 236-row strip is exactly the shape
        // where the rectangle fit overflows at its floor font while the bands
        // consume the text fully — the guard's second acceptance clause.
        val pageWidth = 300
        val pageHeight = 2596
        val strip = 236
        val text24 = "ab ".repeat(480).trimEnd() // 20 tokens per 292px line → 24 lines
        val inputs = (0 until 11).map { k ->
            val top = k * strip
            val runs = buildList {
                for (y in top until top + strip) {
                    add(y * pageWidth)
                    add(pageWidth)
                }
            }
            val mask = BubbleMaskRle(pageWidth, pageHeight, listOf(0, top, pageWidth, top + strip), runs, score = 1f)
            block(50f, (top + 80).toFloat(), 100f, 80f, text24, score = 1f)
                .copy(blockId = "b$k", segmentationMask = mask)
        }

        val plan = TextLayoutPlanner.planPage(inputs, pageWidth.toFloat(), pageHeight.toFloat(), 1, false, m)

        plan.resultsInInputOrder shouldHaveSize 11
        plan.drawableInRenderOrder shouldHaveSize 11
        for (k in 0 until 10) {
            val layout = plan.drawableInRenderOrder[k]
            layout.positionedLines.shouldNotBeNull() shouldHaveSize 24
            layout.positionedLines.map { it.text } shouldBe TextLineBreaker.prewrap(text24, 8f, 292f, m)
        }
        // The block that would exceed the page line budget keeps a Draw result
        // in the legacy single-layout form.
        val retried = plan.drawableInRenderOrder[10]
        retried.positionedLines.shouldBeNull()
        retried.cellRect.shouldNotBeNull()
        retried.block.blockId shouldBe "b10"
    }

    // ---- 65th distinct (group, component) pair cap ------------------------

    @Test
    fun `the 65th distinct pair is refused while existing and earlier pairs pass`() {
        // Semantics of the carried-over page cap, extracted as a pure
        // predicate: 64 distinct pairs fit; the 65th NEW pair is refused (the
        // member then keeps Draw with the slab cellRect and no ids); an
        // already-assigned pair still passes at the cap.
        val assigned = (0 until 64).map { it.toLong() }.toSet()
        TextLayoutPlanner.componentAssignmentAllowed(63L, assigned) shouldBe true // 64th distinct pair
        TextLayoutPlanner.componentAssignmentAllowed(64L, assigned) shouldBe false // 65th new pair
        TextLayoutPlanner.componentAssignmentAllowed(63L, assigned + 64L) shouldBe true // existing pair at cap
    }

}
