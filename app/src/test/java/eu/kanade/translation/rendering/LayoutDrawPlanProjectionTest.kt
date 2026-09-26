package eu.kanade.translation.rendering

import eu.kanade.translation.persistence.artifact.DrawPlanFontIdentity
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.segmentation.BubbleMaskRle
import eu.kanade.translation.segmentation.MaskGeometry
import eu.kanade.translation.segmentation.MaskGeometry.RowSpan
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 *  WP8 gate 7.1: the [LayoutDrawPlanProjection] round trip —
 * project → serialize → deserialize → rehydrate — reproduces the real
 * planner's geometry EXACTLY (floats bit-for-bit; the DTO has no Int
 * truncation). Runs the production [TextLayoutPlanner.planPage] against
 * representative blocks (multi-line, mixed alignment, stroke widths, masked
 * and vertical layouts) with a deterministic fake measurer, exactly like the
 * other planner suites.
 */
class LayoutDrawPlanProjectionTest {

    /** Deterministic measurement: every char is `charWidth` wide at the given size. */
    private class FakeMeasurer(private val charWidth: Float = 0.6f) : TextMeasurer {
        override fun measureTextWidth(text: String, fontSizePx: Float): Float =
            text.length * charWidth * fontSizePx

        override fun lineHeight(fontSizePx: Float): Float = fontSizePx * 1.2f
    }

    private val fontIdentity: DrawPlanFontIdentity =
        DrawPlanFingerprint.drawPlanFontIdentity(assetSha256 = FAKE_ASSET_SHA256)

    private fun block(
        blockId: String,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        text: String,
        score: Float = 1f,
        direction: String = "LTR",
        mask: BubbleMaskRle? = null,
    ) = TranslationBlock(
        blockId = blockId,
        text = "",
        translation = text,
        width = w,
        height = h,
        x = x,
        y = y,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
        score = score,
        direction = direction,
        segmentationMask = mask,
    )

    private fun planOf(
        inputs: List<TranslationBlock>,
        pageWidth: Float = 1000f,
        pageHeight: Float = 1400f,
        sampleSize: Int = 1,
    ): PageLayoutPlan = TextLayoutPlanner.planPage(
        inputs,
        pageWidth,
        pageHeight,
        sampleSize,
        false,
        FakeMeasurer(),
    )

    private fun project(
        plan: PageLayoutPlan,
        pageWidth: Float = 1000f,
        pageHeight: Float = 1400f,
        sampleSize: Int = 1,
    ) = LayoutDrawPlanProjection.projectToDrawPlan(
        results = plan.resultsInInputOrder,
        pageWidth = pageWidth,
        pageHeight = pageHeight,
        decodeSampleSize = sampleSize,
        fontIdentity = fontIdentity,
        platformShapingKey = PLATFORM_KEY,
    )

    private fun roundTripped(plan: PageLayoutPlan, inputs: List<TranslationBlock>): List<BlockLayout> {
        val drawn = project(plan)
        drawn.validationError() shouldBe null
        val json = LayoutDrawPlanProjection.encodeToCanonicalJson(drawn)
        // 06: encode → decode → encode is byte-identical.
        val decoded = LayoutDrawPlanProjection.decodeFromCanonicalJson(json)
        LayoutDrawPlanProjection.encodeToCanonicalJson(decoded) shouldBe json
        return LayoutDrawPlanProjection.rehydrate(decoded, inputs)
    }

    private fun assertGeometryIdentical(expected: BlockLayout, actual: BlockLayout) {
        actual.text shouldBe expected.text
        actual.isVertical shouldBe expected.isVertical
        actual.drawAlign shouldBe expected.drawAlign
        actual.originX.toRawBits() shouldBe expected.originX.toRawBits()
        actual.originY.toRawBits() shouldBe expected.originY.toRawBits()
        actual.safeW.toRawBits() shouldBe expected.safeW.toRawBits()
        actual.safeH.toRawBits() shouldBe expected.safeH.toRawBits()
        actual.fontSizePx.toRawBits() shouldBe expected.fontSizePx.toRawBits()
        actual.strokeWidth.toRawBits() shouldBe expected.strokeWidth.toRawBits()
        actual.lines shouldBe expected.lines
        actual.maskUsable shouldBe expected.maskUsable
        actual.maskComponentId shouldBe expected.maskComponentId
        expected.clipRect?.let { clip ->
            actual.clipRect?.let { restored ->
                restored.left.toRawBits() shouldBe clip.left.toRawBits()
                restored.top.toRawBits() shouldBe clip.top.toRawBits()
                restored.right.toRawBits() shouldBe clip.right.toRawBits()
                restored.bottom.toRawBits() shouldBe clip.bottom.toRawBits()
            }
        }
        expected.cellRect?.let { cell ->
            actual.cellRect?.let { restored ->
                restored.left.toRawBits() shouldBe cell.left.toRawBits()
                restored.top.toRawBits() shouldBe cell.top.toRawBits()
                restored.right.toRawBits() shouldBe cell.right.toRawBits()
                restored.bottom.toRawBits() shouldBe cell.bottom.toRawBits()
            }
        }
        // Positioned lines: integer placement and exact widths/heights survive.
        if (expected.positionedLines == null) {
            actual.positionedLines shouldBe null
        } else {
            val restored = actual.positionedLines
            restored shouldNotBe null
            restored!!.size shouldBe expected.positionedLines.size
            expected.positionedLines.zip(restored) { e, a ->
                a.text shouldBe e.text
                a.leftPx shouldBe e.leftPx
                a.topPx shouldBe e.topPx
                a.layoutWidthPx shouldBe e.layoutWidthPx
                a.layoutHeightPx shouldBe e.layoutHeightPx
            }
        }
    }

    @Test
    fun `round trip preserves planner geometry exactly across multi-line, alignment, stroke and mask cases`() {
        // Page-covering full-rectangle mask (mask dims == page dims, the
        // optimized shared-cell path per TextLayoutPlannerMaskMetadataTest).
        val sharedMask = BubbleMaskRle(
            width = 1000,
            height = 1400,
            bounds = listOf(0, 0, 1000, 1400),
            runs = listOf(0, 1000 * 1400),
            score = 1f,
        )
        val inputs = listOf(
            // Multi-line long text: exercises positioned lines and stroke width.
            block("p1_b0", x = 100f, y = 100f, w = 300f, h = 120f, text = "THE RITUAL BEGINS TONIGHT AND NO ONE MAY LEAVE", score = 0.95f),
            // Shared-mask pair: exercises maskComponentRef + cellRect durability.
            block("p1_b1", x = 20f, y = 30f, w = 80f, h = 60f, text = "I WAS A FAN!", score = 0.9f, mask = sharedMask),
            block("p1_b2", x = 120f, y = 30f, w = 80f, h = 60f, text = "I'M SORRY!", score = 0.6f, mask = sharedMask),
            // Edge-hugging block: grows into free space (mixed alignment).
            block("p1_b3", x = 930f, y = 1100f, w = 60f, h = 40f, text = "RUN.", score = 0.5f),
            // Vertical CJK block: legacy column lines path.
            block("p1_b4", x = 800f, y = 200f, w = 80f, h = 200f, text = "これはテストです", direction = "TTB", score = 0.4f),
            // Sub-pixel geometry: fractional floats must keep their bits.
            block("p1_b5", x = 401.25f, y = 703.75f, w = 97.5f, h = 55.25f, text = "0.5 px budget", score = 0.3f),
            // Unfittable tiny box: containment clip path (LEFT anchor + clipRect).
            block("p1_b6", x = 400f, y = 400f, w = 30f, h = 30f, text = "A very long translation sentence", score = 0.2f),
        )

        val plan = planOf(inputs)
        val draws = plan.drawableInRenderOrder
        draws shouldHaveSize inputs.size

        val restored = roundTripped(plan, inputs)
        restored shouldHaveSize draws.size
        // Render order (score-descending, then input index) is preserved.
        restored.map { it.text } shouldBe draws.map { it.text }
        draws.zip(restored) { expected, actual -> assertGeometryIdentical(expected, actual) }

        // Mixed alignment really exercised (CENTER plus the clip-path LEFT
        // anchor), and the clip net round-trips too.
        draws.map { it.drawAlign }.toSet().size shouldBeGreaterThan 1
        draws.firstOrNull { it.drawAlign == TextAlign.LEFT && it.clipRect != null } shouldNotBe null

        // Masked blocks carry a durable, content-addressed component ref; both
        // share one mask instance, so one identical content hash.
        val projected = project(plan)
        val maskedRefs = projected.blocks.mapNotNull { it.maskComponentRef }
        maskedRefs.size shouldBe 2
        maskedRefs[0].maskGeometryContentHash shouldBe maskedRefs[1].maskGeometryContentHash
        maskedRefs.forEach { ref -> ref.componentId shouldBeGreaterThan -1 }
    }

    @Test
    fun `different mask contents produce different durable hashes`() {
        val g1 = MaskGeometry.fromSpans(10, 10, listOf(RowSpan(0, 0, 5)))
        val g2 = MaskGeometry.fromSpans(10, 10, listOf(RowSpan(0, 0, 6)))
        LayoutDrawPlanProjection.maskGeometryContentHash(g1) shouldNotBe
            LayoutDrawPlanProjection.maskGeometryContentHash(g2)
    }

    @Test
    fun `non-draw outcomes are excluded and input ordinals survive`() {
        val blank = block("p2_b0", x = 100f, y = 100f, w = 100f, h = 40f, text = "", score = 0.9f)
        val first = block("p2_b1", x = 110f, y = 100f, w = 100f, h = 40f, text = "Hi", score = 0.5f)
        val plan = planOf(listOf(blank, first))

        // Blank inputs are the ONLY intentional absence ( contract): they
        // produce no result at all, hence no plan block either.
        plan.resultsInInputOrder shouldHaveSize 1
        plan.resultsInInputOrder.single().identity.inputIndex shouldBe 1
        plan.drawableInRenderOrder shouldHaveSize 1

        val projected = project(plan)
        projected.blocks.map { it.inputIndex } shouldBe
            plan.drawableInRenderOrder.mapNotNull { draw ->
                plan.resultsInInputOrder
                    .first { it.outcome is LayoutOutcome.Draw && it.block === draw.block }
                    .identity.inputIndex
            }
        projected.blocks.single().inputIndex shouldBe 1
        projected.blocks.single().stableBlockId shouldBe "p2_b1"
    }

    @Test
    fun `stroke width varies with decode sample size and survives the round trip`() {
        val inputs = listOf(
            block("p3_b0", x = 100f, y = 100f, w = 200f, h = 80f, text = "TINY", score = 1f),
        )
        val planFull = planOf(inputs, sampleSize = 1)
        val planDown = planOf(inputs, sampleSize = 2)

        val restoredFull = roundTripped(planFull, inputs).single()
        val restoredDown = roundTripped(planDown, inputs).single()
        restoredFull.strokeWidth shouldBe TextLayoutPlanner.computeStrokeWidth(restoredFull.fontSizePx, 1f)
        restoredDown.strokeWidth shouldBe TextLayoutPlanner.computeStrokeWidth(restoredDown.fontSizePx, 0.5f)
        restoredFull.strokeWidth shouldNotBe restoredDown.strokeWidth
    }

    @Test
    fun `rehydration skips plan blocks whose inputs no longer match (stale, never mis-draw)`() {
        val inputs = listOf(
            block("p4_b0", x = 100f, y = 100f, w = 200f, h = 80f, text = "FIRST", score = 0.9f),
            block("p4_b1", x = 400f, y = 100f, w = 200f, h = 80f, text = "SECOND", score = 0.5f),
        )
        val plan = planOf(inputs)
        val drawn = project(plan)
        drawn.validationError() shouldBe null

        // Changed page: one id replaced — that plan block must be skipped.
        val changedInputs = listOf(
            inputs[0],
            inputs[1].copy(blockId = "p4_b1_revised"),
        )
        val restored = LayoutDrawPlanProjection.rehydrate(drawn, changedInputs)
        restored.map { it.block.blockId } shouldBe listOf("p4_b0")
    }

    private companion object {
        const val PLATFORM_KEY = "sdk34-UPSIDE_DOWN_CAKE"
        val FAKE_ASSET_SHA256 = "aa".repeat(32)
    }
}
