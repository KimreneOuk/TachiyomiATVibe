package eu.kanade.translation.rendering

import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.segmentation.BubbleMaskRle
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.ceil

/**
 * T912 slice 5: planner integration of adaptive bands and the page budgets —
 * eligibility, positioned-line contract fields, ALL-CAPS trial wiring,
 * StaticLayout/positioned-line reservation in placement order with the
 * legacy retry and explicit `STATIC_LAYOUT_BUDGET_EXHAUSTED`, the 24-line
 * block cap fallback, and the 65th-distinct-pair cap semantics.
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
    fun `span mode horizontal block becomes adaptive with the full positioned line contract`() {
        val mask = fullMask(300, 120)
        val input = block(60f, 40f, 120f, 40f, "Hello there friend").copy(segmentationMask = mask)

        val plan = TextLayoutPlanner.planPage(listOf(input), 300f, 120f, 1, false, m)

        val result = plan.resultsInInputOrder.single()
        val layout = (result.outcome as LayoutOutcome.Draw).layout
        val lines = layout.positionedLines.shouldNotBeNull()
        (lines.isEmpty()) shouldBe false
        // `lines` keeps the uniform wrapped-texts handle; text stays the block's own.
        layout.lines shouldBe lines.map { it.text }
        layout.text shouldBe "Hello there friend"
        layout.conservativeOccupancy shouldHaveSize lines.size
        layout.conservativeOccupancy shouldBe lines.map { it.conservativeOccupancy }
        // Adaptive geometry: slab bounds, centered anchor at the OCR center,
        // structural (not collision) clipping.
        layout.safeW shouldBe 300f
        layout.safeH shouldBe 120f
        layout.drawAlign shouldBe TextAlign.CENTER
        layout.clipRect.shouldBeNull()
        layout.originX shouldBe 120f // block OCR center wins alignment (a)
        layout.originY shouldBe 60f
        // hard clip mirrors the span-mode metadata: ids + the slab.
        layout.hardClip shouldBe HardClip(0, 0, FloatRect(0f, 0f, 300f, 120f))
        layout.cellRect shouldBe FloatRect(0f, 0f, 300f, 120f)
        layout.planGeometryId shouldBe 0
        layout.maskComponentId shouldBe 0

        // Per-line contract: exact integer width formula and floor placement
        // inside the hard slab.
        for (line in lines) {
            val advance = m.measureTextWidth(line.text, layout.fontSizePx)
            line.layoutWidthPx shouldBe
                maxOf(1, ceil(advance + 2f * TextLayoutTuning.shapingGuardPx(layout.fontSizePx, 1f)).toInt())
            (line.leftPx >= 0 && line.leftPx + line.layoutWidthPx <= 300) shouldBe true
            (line.topPx >= 0 && line.topPx + line.layoutHeightPx <= 120) shouldBe true
            // Conservative occupancy is un-clipped and inflated past the line rect.
            val occ = line.conservativeOccupancy
            (occ.width() >= line.layoutWidthPx) shouldBe true
            (occ.height() >= line.layoutHeightPx) shouldBe true
        }
    }

    @Test
    fun `all caps trial inserts hyphens into planned lines only`() {
        val mask = fullMask(300, 120)
        val original = "A".repeat(16)
        val input = block(60f, 40f, 120f, 40f, original).copy(segmentationMask = mask)

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
    fun `adaptive block reserves two static layouts per positioned line`() {
        // One adaptive block with 2 positioned lines (4 StaticLayouts), then
        // 255 legacy horizontal blocks: 4 + 2k <= 512 → k <= 254 draws, the
        // 255th legacy block NonDraws. If the adaptive block reserved only 2,
        // all 255 legacy blocks would draw — the count discriminates.
        val mask = fullMask(300, 1200)
        val adaptive = block(50f, 550f, 100f, 100f, "AAA BBB", score = 1f)
            .copy(blockId = "adaptive", segmentationMask = mask)
        val legacy = (0 until 255).map { i ->
            block((i * 7 % 290).toFloat(), (i * 11 % 1150).toFloat(), 20f, 8f, "M", score = 0.5f)
                .copy(blockId = "l$i")
        }

        val plan = TextLayoutPlanner.planPage(listOf(adaptive) + legacy, 300f, 1200f, 1, false, m)

        plan.resultsInInputOrder shouldHaveSize 256
        val adaptiveLayout = plan.drawableInRenderOrder.first()
        adaptiveLayout.positionedLines.shouldNotBeNull() shouldHaveSize 2
        plan.drawableInRenderOrder shouldHaveSize 255
        val nonDraw = plan.resultsInInputOrder.last { it.outcome is LayoutOutcome.NonDraw }
        nonDraw.identity.blockId shouldBe "l254"
        nonDraw.outcome shouldBe LayoutOutcome.NonDraw(NonDrawReason.STATIC_LAYOUT_BUDGET_EXHAUSTED)
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
        // 11 blocks, each on its own full-width 240-row strip of one 300x2640
        // page. Every strip fits 24 positioned lines at the minimum font, so
        // blocks 0-9 consume 24 lines each (240 total) and block 10 would push
        // past MAX_POSITIONED_LINES_PER_PAGE=256 → legacy single-layout retry.
        val pageWidth = 300
        val pageHeight = 2640
        val strip = 240
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
