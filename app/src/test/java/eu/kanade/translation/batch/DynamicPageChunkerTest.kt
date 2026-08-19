package eu.kanade.translation.batch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DynamicPageChunkerTest {

    @Test
    fun `short manga chapter uses 5-6 page chunks`() {
        val pages = (1..20).map { "page_$it.jpg" }
        val chunks = DynamicPageChunker.computeChunks(pages)
        assertTrue(chunks.all { it.pageKeys.size in 5..6 })
        assertEquals(20, chunks.sumOf { it.pageKeys.size })
    }

    @Test
    fun `webtoon chapter uses 8-10 page chunks`() {
        val pages = (1..100).map { "slice_$it.jpg" }
        val chunks = DynamicPageChunker.computeChunks(pages)
        assertTrue(chunks.all { it.pageKeys.size in 8..10 })
        assertEquals(100, chunks.sumOf { it.pageKeys.size })
    }

    @Test
    fun `density clamp seals chunk when bubbles reach threshold`() {
        val pages = (1..10).map { "page_$it.jpg" }
        val bubbleCounts = mapOf(
            "page_1.jpg" to 15,
            "page_2.jpg" to 22, // 15 + 22 = 37 >= 35 threshold
            "page_3.jpg" to 5,
        )
        val chunks = DynamicPageChunker.computeChunks(pages, bubbleCounts)
        assertEquals(listOf("page_1.jpg", "page_2.jpg"), chunks[0].pageKeys)
        assertEquals(37, chunks[0].totalBubbles)
    }
}
