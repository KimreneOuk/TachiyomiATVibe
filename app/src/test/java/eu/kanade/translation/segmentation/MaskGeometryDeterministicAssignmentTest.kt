package eu.kanade.translation.segmentation

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 *  repair (R3): DETERMINISTIC component assignment —
 * [MaskGeometry.componentForRectangleDeterministic]. A block whose OCR
 * rectangle ties across components (or overlaps none of them) must never go
 * cell-less in the  layout path. Resolution:
 *  1. unique positive max overlap wins;
 *  2. exact overlap tie -> among the TIED components, the nearest integer
 *     bounds center to the rectangle center;
 *  3. further distance tie -> LOWER component id;
 *  4. zero overlap with all -> nearest bounds center over all components.
 *
 * [MaskGeometry.componentForRectangle] keeps its null-on-tie contract for its
 * other callers (pinned here as the contrast).
 */
class MaskGeometryDeterministicAssignmentTest {

    /** 100x2 mask with two 1px islands at x [10,11) and [80,81) on each row. */
    private fun twoIslands(): MaskGeometry = MaskGeometry.fromSpans(
        100,
        2,
        listOf(MaskGeometry.RowSpan(0, 10, 11), MaskGeometry.RowSpan(0, 80, 81)),
    )

    @Test
    fun `unique max overlap still wins without tie-breaking`() {
        val geometry = twoIslands()
        // Rect covering island 0 only.
        geometry.componentForRectangleDeterministic(10, 0, 20, 1) shouldBe 0
        // Rect covering island 1 only.
        geometry.componentForRectangleDeterministic(80, 0, 90, 1) shouldBe 1
        // Unequal overlaps: island 1 contributes more pixels.
        geometry.componentForRectangleDeterministic(79, 0, 90, 1) shouldBe 1
    }

    @Test
    fun `exact overlap tie resolves to the nearest bounds center among the tied components`() {
        val geometry = twoIslands()
        // Rect [10,91) overlaps both islands by exactly one pixel: a tie.
        // Rectangle center x = 50.5 -> distances 40.0 vs 30.0 -> island 1.
        geometry.componentForRectangle(10, 0, 91, 1).shouldBeNull() // legacy: null on tie
        geometry.componentForRectangleDeterministic(10, 0, 91, 1) shouldBe 1
        // Rect [0,81): same tie, center x = 40.5 -> distances 30.0 vs 40.0 -> island 0.
        geometry.componentForRectangleDeterministic(0, 0, 81, 1) shouldBe 0
    }

    @Test
    fun `distance tie on an exact overlap tie resolves to the lower component id`() {
        val geometry = twoIslands()
        // Rect [10,81): overlap tie 1/1; center x = 45.5 is EXACTLY 35.0 from
        // both island bounds centers (10.5 and 80.5) -> lower id wins.
        geometry.componentForRectangleDeterministic(10, 0, 81, 1) shouldBe 0
    }

    @Test
    fun `zero overlap with all components resolves to the nearest bounds center`() {
        val geometry = twoIslands()
        // Legacy contract: zero overlap -> null.
        geometry.componentForRectangle(30, 0, 40, 1).shouldBeNull()
        // Deterministic: rect center x = 35.0 -> distances 24.5 vs 45.5 -> island 0.
        geometry.componentForRectangleDeterministic(30, 0, 40, 1) shouldBe 0
        // Rect center x = 75.0 -> distances 64.5 vs 5.5 -> island 1.
        geometry.componentForRectangleDeterministic(70, 0, 80, 1) shouldBe 1
    }

    @Test
    fun `degenerate rectangles and empty geometries stay null`() {
        val geometry = twoIslands()
        geometry.componentForRectangleDeterministic(20, 0, 20, 1).shouldBeNull() // right <= left
        geometry.componentForRectangleDeterministic(20, 1, 30, 1).shouldBeNull() // bottom <= top
        val empty = MaskGeometry.fromSpans(10, 2, listOf(MaskGeometry.RowSpan(0, 0, 1)))
        // One island only: a rect over it resolves; degenerate stays null.
        empty.componentForRectangleDeterministic(0, 0, 1, 1) shouldBe 0
    }

    @Test
    fun `assignment is deterministic across repeated calls`() {
        val geometry = twoIslands()
        val expected = geometry.componentForRectangleDeterministic(10, 0, 91, 1)
        repeat(16) { geometry.componentForRectangleDeterministic(10, 0, 91, 1) shouldBe expected }
    }
}
