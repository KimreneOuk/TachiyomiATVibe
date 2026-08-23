package eu.kanade.translation.batch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DynamicPageChunkerTest {

    @Test
    fun `target page count is four pages`() {
        assertEquals(4, DynamicPageChunker.targetPageCount(1))
        assertEquals(4, DynamicPageChunker.targetPageCount(35))
        assertEquals(4, DynamicPageChunker.targetPageCount(60))
        assertEquals(4, DynamicPageChunker.targetPageCount(100))
    }

    @Test
    fun `short manga chapter uses 4 page chunks`() {
        val pages = (1..24).map { "page_$it.jpg" }
        val chunks = DynamicPageChunker.computeChunks(pages)
        assertTrue(chunks.all { it.pageKeys.size == 4 })
        assertEquals(24, chunks.sumOf { it.pageKeys.size })
    }

    @Test
    fun `webtoon chapter uses 4 page chunks`() {
        val pages = (1..100).map { "slice_$it.jpg" }
        val chunks = DynamicPageChunker.computeChunks(pages)
        assertTrue(chunks.all { it.pageKeys.size == 4 })
        assertEquals(100, chunks.sumOf { it.pageKeys.size })
    }

    @Test
    fun `density clamp seals chunk when bubbles reach threshold`() {
        val pages = (1..10).map { "page_$it.jpg" }
        val bubbleCounts = mapOf(
            "page_1.jpg" to 15,
            "page_2.jpg" to 15, // 15 + 15 = 30 >= 28 threshold
            "page_3.jpg" to 5,
        )
        val chunks = DynamicPageChunker.computeChunks(pages, bubbleCounts)
        assertEquals(listOf("page_1.jpg", "page_2.jpg"), chunks[0].pageKeys)
        assertEquals(30, chunks[0].totalBubbles)
    }
}
