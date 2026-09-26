package eu.kanade.translation.util

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ResumeOrderingTest {

    private fun pages(n: Int): List<String> = List(n) { (it + 1).toString() }

    @Test
    fun `natural order ignores reader resume position`() {
        ResumeOrdering.naturalOrder(pages(20)) shouldBe pages(20)
    }
}
