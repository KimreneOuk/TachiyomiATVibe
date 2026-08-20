package eu.kanade.translation.util

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ResumeOrderingTest {

    private fun pages(n: Int): List<String> = List(n) { (it + 1).toString() }

    @Test
    fun `natural order ignores reader resume position`() {
        ResumeOrdering.naturalOrder(pages(20)) shouldBe pages(20)
    }

    @Test
    fun `resume mid-chapter puts forward pages first then backfills`() {
        // 200 pages, resume at page 51 (index 50): expect 51..200 then 1..50.
        val ordered = ResumeOrdering.forwardFirstThenBackfill(pages(200), resumeIndex = 50)

        ordered.take(150) shouldBe (51..200).map { it.toString() }
        ordered.drop(150) shouldBe (1..50).map { it.toString() }
        ordered.size shouldBe 200
    }

    @Test
    fun `resume at start is unchanged`() {
        ResumeOrdering.forwardFirstThenBackfill(pages(5), resumeIndex = 0) shouldBe
            listOf("1", "2", "3", "4", "5")
    }

    @Test
    fun `resume past end is unchanged`() {
        // Fully read — whole chapter in natural order.
        ResumeOrdering.forwardFirstThenBackfill(pages(5), resumeIndex = 5) shouldBe
            listOf("1", "2", "3", "4", "5")
        ResumeOrdering.forwardFirstThenBackfill(pages(5), resumeIndex = 99) shouldBe
            listOf("1", "2", "3", "4", "5")
    }

    @Test
    fun `negative resume index is clamped to start`() {
        ResumeOrdering.forwardFirstThenBackfill(pages(5), resumeIndex = -3) shouldBe
            listOf("1", "2", "3", "4", "5")
    }

    @Test
    fun `single element list is unchanged regardless of resume`() {
        ResumeOrdering.forwardFirstThenBackfill(listOf("only"), resumeIndex = 0) shouldBe listOf("only")
        ResumeOrdering.forwardFirstThenBackfill(listOf("only"), resumeIndex = 1) shouldBe listOf("only")
    }

    @Test
    fun `empty list returns empty`() {
        ResumeOrdering.forwardFirstThenBackfill(emptyList<String>(), resumeIndex = 5) shouldBe emptyList()
    }

    @Test
    fun `resume at last page keeps whole chapter forward`() {
        // Resume at the final page: everything before is backfill, final page first.
        val ordered = ResumeOrdering.forwardFirstThenBackfill(pages(4), resumeIndex = 3)
        ordered shouldBe listOf("4", "1", "2", "3")
    }

    @Test
    fun `result does not alias the input list`() {
        val input = mutableListOf("a", "b", "c")
        val ordered = ResumeOrdering.forwardFirstThenBackfill(input, resumeIndex = 1).toMutableList()
        ordered.add("d") // mutate result

        input shouldBe mutableListOf("a", "b", "c") // input untouched
    }
}
