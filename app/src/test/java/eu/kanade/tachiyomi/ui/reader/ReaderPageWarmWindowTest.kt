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

    @Test
    fun `contains rejects all pages when currentIndex is negative`() {
        ReaderPageWarmWindow.contains(pageIndex = 0, currentIndex = -1, lastIndex = 199) shouldBe false
        ReaderPageWarmWindow.contains(pageIndex = 1, currentIndex = -1, lastIndex = 199) shouldBe false
    }

    @Test
    fun `contains respects webtoon radius`() {
        ReaderPageWarmWindow.contains(pageIndex = 10, currentIndex = 14, lastIndex = 50, radius = 4) shouldBe true
        ReaderPageWarmWindow.contains(pageIndex = 9, currentIndex = 14, lastIndex = 50, radius = 4) shouldBe false
        ReaderPageWarmWindow.contains(pageIndex = 18, currentIndex = 14, lastIndex = 50, radius = 4) shouldBe true
        ReaderPageWarmWindow.contains(pageIndex = 19, currentIndex = 14, lastIndex = 50, radius = 4) shouldBe false
    }

    @Test
    fun `hysteresis radii are correctly configured for Webtoon and Pager`() {
        ReaderPageWarmWindow.attachRadiusFor(eu.kanade.tachiyomi.ui.reader.setting.ReadingMode.WEBTOON) shouldBe 4
        ReaderPageWarmWindow.evictionRadiusFor(eu.kanade.tachiyomi.ui.reader.setting.ReadingMode.WEBTOON) shouldBe 10

        ReaderPageWarmWindow.attachRadiusFor(eu.kanade.tachiyomi.ui.reader.setting.ReadingMode.RIGHT_TO_LEFT) shouldBe 2
        ReaderPageWarmWindow.evictionRadiusFor(eu.kanade.tachiyomi.ui.reader.setting.ReadingMode.RIGHT_TO_LEFT) shouldBe 5
    }

    @Test
    fun `hysteresis deadband keeps items without attaching or evicting`() {
        val currentIndex = 20
        val lastIndex = 100
        val attachRadius = ReaderPageWarmWindow.attachRadiusFor(eu.kanade.tachiyomi.ui.reader.setting.ReadingMode.WEBTOON)
        val evictionRadius = ReaderPageWarmWindow.evictionRadiusFor(eu.kanade.tachiyomi.ui.reader.setting.ReadingMode.WEBTOON)

        // Page within attach radius (16..24)
        ReaderPageWarmWindow.contains(16, currentIndex, lastIndex, attachRadius) shouldBe true
        ReaderPageWarmWindow.contains(24, currentIndex, lastIndex, attachRadius) shouldBe true

        // Page in deadband (10..15 and 25..30)
        ReaderPageWarmWindow.contains(12, currentIndex, lastIndex, attachRadius) shouldBe false
        ReaderPageWarmWindow.contains(12, currentIndex, lastIndex, evictionRadius) shouldBe true
        ReaderPageWarmWindow.contains(28, currentIndex, lastIndex, attachRadius) shouldBe false
        ReaderPageWarmWindow.contains(28, currentIndex, lastIndex, evictionRadius) shouldBe true

        // Page outside eviction radius (<10 or >30)
        ReaderPageWarmWindow.contains(9, currentIndex, lastIndex, evictionRadius) shouldBe false
        ReaderPageWarmWindow.contains(31, currentIndex, lastIndex, evictionRadius) shouldBe false
    }
}
