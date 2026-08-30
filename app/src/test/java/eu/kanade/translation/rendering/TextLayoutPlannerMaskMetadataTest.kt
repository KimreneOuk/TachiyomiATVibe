package eu.kanade.translation.rendering

import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.segmentation.BubbleMaskRle
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * T912 slice 2: planner metadata wiring for shared segmentation masks.
 *
 * Pins the metadata contract on top of the slice-1 fixtures: shared geometry
 * instances and group ids per mask group, per-block component assignment,
 * pairwise-disjoint cell rects, and — critically — that every fallback
 * (empty runs, >64 components, unique/reference caps, tied component overlap)
 * still yields EXACTLY ONE layout per nonblank input with NO metadata.
 */
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

        val plan = TextLayoutPlanner.plan(inputs, 300f, 120f, 1, false, FakeMeasurer())

        plan shouldHaveSize 3
        val geometry = plan[0].maskGeometry.shouldNotBeNull()
        plan.all { it.maskGeometry === geometry } shouldBe true
        plan.map { it.planGeometryId }.toSet() shouldBe setOf(0)
        // One full-rectangle component: every block resolves to it.
        plan.map { it.maskComponentId }.toSet() shouldBe setOf(0)

        // Cell rects are the mask-region partitions: non-null and pairwise disjoint.
        val cells = plan.map { it.cellRect.shouldNotBeNull() }
        for (i in cells.indices) {
            for (j in i + 1 until cells.size) {
                (cells[i].overlaps(cells[j])) shouldBe false
            }
        }

        // Per-block text unchanged by metadata wiring.
        plan.associate { it.block.blockId to it.text } shouldBe mapOf(
            "left" to "I WAS A FAN!",
            "middle" to "I'M SORRY!",
            "right" to "I FORCED YOU INTO THAT RELATIONSHIP.",
        )
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
    fun `tied overlap across two components yields no metadata but keeps the result`() {
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
        val spanning = block(10f, 5f, 81f, 1f, "X").copy(segmentationMask = mask)

        val plan = TextLayoutPlanner.plan(listOf(spanning), 100f, 10f, 1, false, FakeMeasurer())

        plan shouldHaveSize 1
        assertNoMetadata(plan.first())
    }

    @Test
    fun `empty-runs shared mask produces no metadata and keeps the legacy assertions`() {
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
        plan.forEach(::assertNoMetadata)
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
        assertNoMetadata(plan.first())
    }

    @Test
    fun `more than 32 distinct masks leave late masks groupless with one layout per block`() {
        val width = 300
        val height = 120
        val inputs = (0 until 34).map { i ->
            // Distinct geometry per mask (island at column i on row 0).
            val mask = BubbleMaskRle(width, height, listOf(0, 0, width, height), listOf(i, 1), score = 1f)
            block(i * 9f, 0f, 9f, 10f, "T$i").copy(blockId = "b$i", segmentationMask = mask)
        }

        val plan = TextLayoutPlanner.plan(inputs, 300f, 120f, 1, false, FakeMeasurer())

        plan shouldHaveSize 34
        plan.map { it.block.blockId }.toSet() shouldBe (0 until 34).map { "b$it" }.toSet()
        // Masks beyond the 32-unique cap are groupless: no geometry, no metadata.
        assertNoMetadata(plan.first { it.block.blockId == "b32" })
    }

    @Test
    fun `129 masked blocks hit the reference cap and every block still yields one layout`() {
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

        val plan = TextLayoutPlanner.plan(inputs, 300f, 120f, 1, false, FakeMeasurer())

        plan shouldHaveSize 129
        plan.map { it.block.blockId }.toSet() shouldBe (0 until 129).map { "b$it" }.toSet()
    }
}
