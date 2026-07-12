package eu.kanade.tachiyomi.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReaderPageWarmWindowTest {

    @Test
    fun `first page keeps available leading pages`() {
        ReaderPageWarmWindow.indices(currentIndex = 0, lastIndex = 199).toList() shouldBe
            listOf(0, 1, 2)
    }

    @Test
    fun `middle page keeps current plus two on each side`() {
        ReaderPageWarmWindow.indices(currentIndex = 16, lastIndex = 199).toList() shouldBe
            listOf(14, 15, 16, 17, 18)
    }

    @Test
    fun `last page keeps trailing pages`() {
        ReaderPageWarmWindow.indices(currentIndex = 199, lastIndex = 199).toList() shouldBe
            listOf(197, 198, 199)
    }

    @Test
    fun `contains rejects cold pages in a long chapter`() {
        ReaderPageWarmWindow.contains(pageIndex = 6, currentIndex = 16, lastIndex = 199) shouldBe false
        ReaderPageWarmWindow.contains(pageIndex = 14, currentIndex = 16, lastIndex = 199) shouldBe true
        ReaderPageWarmWindow.contains(pageIndex = 18, currentIndex = 16, lastIndex = 199) shouldBe true
    }
}
