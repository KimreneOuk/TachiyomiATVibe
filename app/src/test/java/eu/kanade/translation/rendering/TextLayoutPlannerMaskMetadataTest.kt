package eu.kanade.translation.rendering

import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.segmentation.BubbleMaskRle
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * T912 slice 2/3: planner metadata wiring for shared segmentation masks.
 *
 * Pins the metadata contract on top of the slice-1 fixtures: shared geometry
 * instances and group ids per mask group, per-block component assignment,
 * slice-3 disjoint slab cellRects with independent per-cell fonts, and —
 * critically — that every ambiguous fallback (tied component overlap,
 * groupless masks beyond the caps) still yields EXACTLY ONE layout per
 * nonblank input with NO metadata, while conversion-fallback groups now get
 * disjoint bounds-rect cells without geometry ids.
 */
@Disabled("Superseded by Desktop 1:1 text layout engine port")
class TextLayoutPlannerMaskMetadataTest {

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

    private fun fullMask(width: Int = 300, height: Int = 120) =
        BubbleMaskRle(width, height, listOf(0, 0, width, height), listOf(0, width * height), score = 1f)

    private fun assertNoMetadata(layout: BlockLayout) {
        layout.maskGeometry shouldBe null
        layout.planGeometryId shouldBe null
        layout.maskComponentId shouldBe null
        layout.cellRect shouldBe null
    }

    @Test
    fun `three blocks on one shared full-rectangle mask share geometry group and disjoint cells`() {
        val sharedMask = fullMask()
        val inputs = listOf(
            block(10f, 30f, 80f, 60f, "I WAS A FAN!").copy(blockId = "left", segmentationMask = sharedMask),
            block(110f, 30f, 80f, 60f, "I'M SORRY!").copy(blockId = "middle", segmentationMask = sharedMask),
            block(210f, 30f, 80f, 60f, "I FORCED YOU INTO THAT RELATIONSHIP.")
                .copy(blockId = "right", segmentationMask = sharedMask),
        )
        val m = FakeMeasurer()

        val plan = TextLayoutPlanner.plan(inputs, 300f, 120f, 1, false, m)

        plan shouldHaveSize 3
        val geometry = plan[0].maskGeometry.shouldNotBeNull()
        plan.all { it.maskGeometry === geometry } shouldBe true
        plan.map { it.planGeometryId }.toSet() shouldBe setOf(0)
        // One full-rectangle component: every block resolves to it.
        plan.map { it.maskComponentId }.toSet() shouldBe setOf(0)

        // Cell rects are the hard disjoint SLABS: centers 50/150/250 → cuts
        // 100/200, gap 2 → cells [0,99), [101,199), [201,300) with dead
        // columns {99,100} and {199,200}.
        val cells = plan.map { it.cellRect.shouldNotBeNull() }
        cells[0] shouldBe FloatRect(0f, 0f, 99f, 120f)
        cells[1] shouldBe FloatRect(101f, 0f, 199f, 120f)
        cells[2] shouldBe FloatRect(201f, 0f, 300f, 120f)
        for (i in cells.indices) {
            for (j in i + 1 until cells.size) {
                (cells[i].overlaps(cells[j])) shouldBe false
            }
        }
        for (deadColumn in listOf(99, 100, 199, 200)) {
            cells.none { it.left <= deadColumn && deadColumn < it.right } shouldBe true
        }

        // Per-block text unchanged by metadata wiring.
        plan.associate { it.block.blockId to it.text } shouldBe mapOf(
            "left" to "I WAS A FAN!",
            "middle" to "I'M SORRY!",
            "right" to "I FORCED YOU INTO THAT RELATIONSHIP.",
        )

        // T912 containment-first re-pin: each sibling is now placed by the
        // contained reflow rescue inside its own slab — positioned lines at
        // the OCR home, each envelope-contained in the shared mask — instead
        // of the legacy rectangle form. The metadata contract above (shared
        // geometry, disjoint slabs, wired ids) is unchanged, and the hard
        // clip still mirrors the wired metadata 1:1.
        plan.forEach { layout ->
            layout.positionedLines.shouldNotBeNull().isNotEmpty() shouldBe true
            layout.lines.isNotEmpty() shouldBe true
            val spans = layout.maskGeometry.shouldNotBeNull()
                .components[layout.maskComponentId.shouldNotBeNull()].spans
            TextLayoutPlanner.paintEnvelopeContainedInSpans(layout, spans, FakeMeasurer(), 1f) shouldBe true
            layout.hardClip shouldBe HardClip(layout.planGeometryId, layout.maskComponentId, layout.cellRect)
            layout.strokeWidth shouldBe TextLayoutPlanner.computeStrokeWidth(layout.fontSizePx, 1f)
        }
        // Fonts stay fitted PER RESULT — the longest text in an equal-width
        // cell keeps a strictly smaller size than the shortest sibling (no
        // equalization; the font-harmony median cap does not bind here).
        (plan[2].fontSizePx < plan[1].fontSizePx) shouldBe true
    }

    @Test
    fun `single block on a mask gets metadata with the mask bounds rect as cell`() {
        val width = 300
        val height = 120
        val runs = buildList {
            for (y in 30 until 100) {
                add(y * width + 40)
                add(220)
            }
        }
        val mask = BubbleMaskRle(width, height, listOf(40, 30, 260, 100), runs, score = 1f)
        val input = block(60f, 50f, 120f, 40f, "Hello").copy(segmentationMask = mask)

        val plan = TextLayoutPlanner.plan(listOf(input), 300f, 120f, 1, false, FakeMeasurer())

        plan shouldHaveSize 1
        val layout = plan.first()
        layout.planGeometryId shouldBe 0
        layout.maskComponentId shouldBe 0
        // Single masked block ⇒ region = the mask bounds rect.
        layout.cellRect shouldBe FloatRect(40f, 30f, 260f, 100f)
    }

    @Test
    fun `blocks over different components of one mask get their own component ids`() {
        val width = 100
        val height = 10
        val mask = BubbleMaskRle(
            width,
            height,
            listOf(0, 0, width, height),
            listOf(5 * width + 10, 1, 5 * width + 90, 1),
            score = 1f,
        )
        val left = block(10f, 5f, 1f, 1f, "L").copy(segmentationMask = mask)
        val right = block(90f, 5f, 1f, 1f, "R").copy(segmentationMask = mask)

        val plan = TextLayoutPlanner.plan(listOf(left, right), 100f, 10f, 1, false, FakeMeasurer())

        plan shouldHaveSize 2
        plan.forEach { it.maskGeometry.shouldNotBeNull() }
        plan.map { it.planGeometryId }.toSet() shouldBe setOf(0)
        // Row-major first-appearance ids: left island 0, right island 1.
        plan.first { it.text == "L" }.maskComponentId shouldBe 0
        plan.first { it.text == "R" }.maskComponentId shouldBe 1
    }

    @Test
    fun `tied overlap resolves deterministically to the lower component id with metadata (R3 repair)`() {
        val width = 100
        val height = 10
        val mask = BubbleMaskRle(
            width,
            height,
            listOf(0, 0, width, height),
            listOf(5 * width + 10, 1, 5 * width + 90, 1),
            score = 1f,
        )
        // The block rect overlaps both 1px islands by exactly one pixel: a tie.
        // T912 REPAIR (R3, documented deviation): this fixture previously
        // asserted NO metadata (null-on-tie assignment left the block
        // cell-less). The deterministic assignment resolves the tie to the
        // nearest bounds center — symmetric here — then the LOWER component
        // id 0, so the block gets a cell and full span-mode metadata.
        val spanning = block(10f, 5f, 81f, 1f, "X").copy(segmentationMask = mask)

        val plan = TextLayoutPlanner.plan(listOf(spanning), 100f, 10f, 1, false, FakeMeasurer())

        plan shouldHaveSize 1
        val layout = plan.first()
        layout.planGeometryId shouldBe 0
        layout.maskComponentId shouldBe 0
        layout.maskGeometry.shouldNotBeNull()
        // Single member on component 0: the slab is the island's own bounds.
        layout.cellRect shouldBe FloatRect(10f, 5f, 11f, 6f)
    }

    @Test
    fun `empty-runs shared mask gets disjoint bounds-rect cells with no geometry ids`() {
        val mask = BubbleMaskRle(
            width = 1000,
            height = 1000,
            bounds = listOf(100, 100, 700, 450),
            runs = emptyList(),
            score = 0.95f,
        )
        val leftLobe = block(
            x = 170f,
            y = 220f,
            w = 170f,
            h = 120f,
            text = "No way... To meet in a place like this...",
            score = 0.9f,
        ).copy(
            parentX = 100f,
            parentY = 130f,
            parentWidth = 285f,
            parentHeight = 320f,
            segmentationMask = mask,
        )
        val rightLobe = block(
            x = 460f,
            y = 200f,
            w = 190f,
            h = 140f,
            text = "I've been searching for you all along! Thank goodness you're safe!",
            score = 0.9f,
        ).copy(
            parentX = 415f,
            parentY = 95f,
            parentWidth = 300f,
            parentHeight = 350f,
            segmentationMask = mask,
        )

        val plan = TextLayoutPlanner.plan(listOf(leftLobe, rightLobe), 1000f, 1000f, 1, false, FakeMeasurer())

        plan shouldHaveSize 2
        (plan[0].fontSizePx >= 16f) shouldBe true
        (plan[1].fontSizePx >= 16f) shouldBe true
        (plan[0].originX >= 100f && plan[0].originX <= 385f) shouldBe true
        (plan[1].originX >= 415f && plan[1].originX <= 715f) shouldBe true

        // Slice 3: conversion fell back (empty runs) ⇒ disjoint BOUNDS-RECT
        // cells over the mask bounds. Parents are valid and facing
        // (385 <= 415) so the cut biases to the parent-edge midpoint 400;
        // gap 2 → slabs [100,399) and [401,700) × [100,450). cellRect is set
        // but there are no geometry ids to clip with.
        plan[0].cellRect shouldBe FloatRect(100f, 100f, 399f, 450f)
        plan[1].cellRect shouldBe FloatRect(401f, 100f, 700f, 450f)
        (plan[0].cellRect!!.overlaps(plan[1].cellRect!!)) shouldBe false
        plan.forEach { layout ->
            layout.maskGeometry shouldBe null
            layout.planGeometryId shouldBe null
            layout.maskComponentId shouldBe null
        }
    }

    @Test
    fun `mask with more than 64 components still yields exactly one layout without metadata`() {
        val width = 300
        val height = 120
        // 100 disjoint 1px islands on row 60: conversion falls back at the cap.
        val runs = buildList {
            for (i in 0 until 100) {
                add(60 * width + i * 3)
                add(1)
            }
        }
        val mask = BubbleMaskRle(width, height, listOf(0, 0, width, height), runs, score = 1f)
        val input = block(0f, 60f, 300f, 1f, "Islands").copy(segmentationMask = mask)

        val plan = TextLayoutPlanner.plan(listOf(input), 300f, 120f, 1, false, FakeMeasurer())

        plan shouldHaveSize 1
        val layout = plan.first()
        // Slice 3: single-member fallback group ⇒ one bounds-rect cell over the
        // mask bounds, still without geometry ids.
        layout.maskGeometry shouldBe null
        layout.planGeometryId shouldBe null
        layout.maskComponentId shouldBe null
        layout.cellRect shouldBe FloatRect(0f, 0f, 300f, 120f)
    }

    @Test
    fun `more than 32 distinct masks leave late masks groupless with one explicit result per block`() {
        val width = 300
        val height = 120
        val inputs = (0 until 34).map { i ->
            // Distinct geometry per mask (island at column i on row 0).
            val mask = BubbleMaskRle(width, height, listOf(0, 0, width, height), listOf(i, 1), score = 1f)
            block(i * 9f, 0f, 9f, 10f, "T$i").copy(blockId = "b$i", segmentationMask = mask)
        }

        val page = TextLayoutPlanner.planPage(inputs, 300f, 120f, 1, false, FakeMeasurer())

        // Slice 7: one EXPLICIT result per nonblank input — the identity
        // multiset stays exact even though the 34 giant legacy layouts overlap
        // one shared spot and the final post-anchor safety pass must non-draw
        // the unresolvable ones (at HEAD they stacked with overlapping ink).
        page.resultsInInputOrder shouldHaveSize 34
        page.resultsInInputOrder.map { it.identity.blockId }.toSet() shouldBe (0 until 34).map { "b$it" }.toSet()
        page.resultsInInputOrder.forEach {
            when (val outcome = it.outcome) {
                is LayoutOutcome.Draw -> {}
                is LayoutOutcome.NonDraw -> outcome.reason shouldBe NonDrawReason.NO_DISJOINT_POST_ANCHOR_PLACEMENT
            }
        }
        // Masks beyond the 32-unique cap are groupless: no geometry, no metadata.
        val b32 = page.resultsInInputOrder.first { it.identity.blockId == "b32" }
        when (val outcome = b32.outcome) {
            is LayoutOutcome.Draw -> assertNoMetadata(outcome.layout)
            is LayoutOutcome.NonDraw -> outcome.reason shouldBe NonDrawReason.NO_DISJOINT_POST_ANCHOR_PLACEMENT
        }
    }

    @Test
    fun `129 masked blocks hit the reference cap and every block still yields one explicit result`() {
        val width = 300
        val height = 120
        val sharedContent = fullMask(width, height)
        val inputs = (0 until 129).map { i ->
            // Distinct instances of the SAME geometry: the reference-identity cap
            // applies while fingerprint verification keeps them one group.
            val mask = if (i == 0) sharedContent else sharedContent.copy()
            block((i % 13) * 23f, (i / 13) * 9f, 20f, 8f, "T$i")
                .copy(blockId = "b$i", segmentationMask = mask)
        }

        val page = TextLayoutPlanner.planPage(inputs, 300f, 120f, 1, false, FakeMeasurer())

        // Slice 7: identity cardinality is intact — one explicit result per
        // input — but the 13x10 grid on a 300x120 page needs more conservative
        // (stroke/AA/gap-inflated) occupancy than the page holds, so blocks with
        // no disjoint post-anchor placement are explicit non-draws instead of
        // overlapping draws (the pre-slice-7 behavior).
        page.resultsInInputOrder shouldHaveSize 129
        page.resultsInInputOrder.map { it.identity.blockId }.toSet() shouldBe (0 until 129).map { "b$it" }.toSet()
        page.resultsInInputOrder.mapNotNull { it.renderOrdinal } shouldBe
            (0 until page.drawableInRenderOrder.size).toList()
        page.resultsInInputOrder.forEach {
            when (val outcome = it.outcome) {
                is LayoutOutcome.Draw -> {}
                is LayoutOutcome.NonDraw -> outcome.reason shouldBe NonDrawReason.NO_DISJOINT_POST_ANCHOR_PLACEMENT
            }
        }
    }

    // ---- T912 slice 4 formal deferral: joined lobes are golden and --------
    // repeatable on the deterministic midpoint/parent-biased partition.

    /** All LayoutResult/BlockLayout fields equal; geometry compared by value. */
    private fun assertByteIdentical(first: PageLayoutPlan, second: PageLayoutPlan) {
        first.resultsInInputOrder.map { it.identity } shouldBe second.resultsInInputOrder.map { it.identity }
        first.resultsInInputOrder.map { it.chosenText } shouldBe second.resultsInInputOrder.map { it.chosenText }
        first.resultsInInputOrder.map { it.planningOrdinal } shouldBe second.resultsInInputOrder.map { it.planningOrdinal }
        first.resultsInInputOrder.map { it.renderOrdinal } shouldBe second.resultsInInputOrder.map { it.renderOrdinal }
        first.resultsInInputOrder.zip(second.resultsInInputOrder).forEach { (a, b) ->
            val da = (a.outcome as? LayoutOutcome.Draw)?.layout
            val db = (b.outcome as? LayoutOutcome.Draw)?.layout
            if (da == null || db == null) {
                a.outcome shouldBe b.outcome
            } else {
                assertSameLayout(da, db)
            }
        }
        first.drawableInRenderOrder shouldHaveSize second.drawableInRenderOrder.size
        first.drawableInRenderOrder.zip(second.drawableInRenderOrder).forEach { (a, b) ->
            assertSameLayout(a, b)
        }
    }

    /** MaskGeometry instances are per-plan; every other field must match exactly. */
    private fun assertSameLayout(a: BlockLayout, b: BlockLayout) {
        a.copy(maskGeometry = null) shouldBe b.copy(maskGeometry = null)
        a.maskGeometry?.spans shouldBe b.maskGeometry?.spans
        a.maskGeometry?.components?.map { it.id to it.spans } shouldBe
            b.maskGeometry?.components?.map { it.id to it.spans }
    }

    @Test
    fun `joined lobes with diagonal centers keep exact parent biased slabs and repeat byte identically`() {
        // One continuous converted mask (full 200x300 rectangle) with two
        // members whose centers are diagonal: (60, 80) and (140, 220). The Y
        // spread wins, so the partition is VERTICAL; the facing parents
        // (bottom 160 <= top 180) bias the cut to floor((160+180)/2) = 170.
        val mask = fullMask(200, 300)
        val inputs = listOf(
            block(30f, 50f, 60f, 60f, "A", score = 0.9f).copy(
                blockId = "upper",
                segmentationMask = mask,
                parentX = 0f,
                parentY = 0f,
                parentWidth = 100f,
                parentHeight = 160f,
            ),
            block(110f, 190f, 60f, 60f, "B", score = 0.8f).copy(
                blockId = "lower",
                segmentationMask = mask,
                parentX = 50f,
                parentY = 180f,
                parentWidth = 150f,
                parentHeight = 120f,
            ),
        )

        val first = TextLayoutPlanner.planPage(inputs, 200f, 300f, 1, false, FakeMeasurer())
        val second = TextLayoutPlanner.planPage(inputs, 200f, 300f, 1, false, FakeMeasurer())

        assertByteIdentical(first, second)

        // gap = ceil(clamp(0.2, 2, 4)) = 2 -> one dead row on each side of the cut.
        val byId = first.resultsInInputOrder.associate { it.identity.blockId to it }
        val upper = (byId.getValue("upper").outcome as LayoutOutcome.Draw).layout
        val lower = (byId.getValue("lower").outcome as LayoutOutcome.Draw).layout
        upper.cellRect shouldBe FloatRect(0f, 0f, 200f, 169f)
        lower.cellRect shouldBe FloatRect(0f, 171f, 200f, 300f)
        (upper.cellRect!!.overlaps(lower.cellRect!!)) shouldBe false
        for (deadRow in listOf(169, 170)) {
            listOf(upper, lower).none {
                val cell = it.cellRect!!
                cell.top <= deadRow && deadRow < cell.bottom
            } shouldBe true
        }
        upper.planGeometryId shouldBe 0
        upper.maskComponentId shouldBe 0
        lower.planGeometryId shouldBe 0
        lower.maskComponentId shouldBe 0
        (upper.maskGeometry.shouldNotBeNull() === lower.maskGeometry) shouldBe true
    }

    @Test
    fun `joined lobes with equal centers keep exact midpoint slabs and repeat byte identically`() {
        // One continuous converted mask with two members sharing the center
        // (150, 60): X spread >= Y spread keeps the partition horizontal and
        // the single midpoint cut lands ON the shared center 150.
        val mask = fullMask(300, 120)
        val inputs = listOf(
            block(110f, 30f, 80f, 60f, "L", score = 0.9f).copy(blockId = "a", segmentationMask = mask),
            block(120f, 30f, 60f, 60f, "R", score = 0.8f).copy(blockId = "b", segmentationMask = mask),
        )

        val first = TextLayoutPlanner.planPage(inputs, 300f, 120f, 1, false, FakeMeasurer())
        val second = TextLayoutPlanner.planPage(inputs, 300f, 120f, 1, false, FakeMeasurer())

        assertByteIdentical(first, second)

        // gap 2 -> slabs [0,149) and [151,300); dead columns {149, 150}.
        val byId = first.resultsInInputOrder.associate { it.identity.blockId to it }
        val la = (byId.getValue("a").outcome as LayoutOutcome.Draw).layout
        val lb = (byId.getValue("b").outcome as LayoutOutcome.Draw).layout
        la.cellRect shouldBe FloatRect(0f, 0f, 149f, 120f)
        lb.cellRect shouldBe FloatRect(151f, 0f, 300f, 120f)
        (la.cellRect!!.overlaps(lb.cellRect!!)) shouldBe false
        for (deadColumn in listOf(149, 150)) {
            listOf(la, lb).none {
                val cell = it.cellRect!!
                cell.left <= deadColumn && deadColumn < cell.right
            } shouldBe true
        }
    }
}
