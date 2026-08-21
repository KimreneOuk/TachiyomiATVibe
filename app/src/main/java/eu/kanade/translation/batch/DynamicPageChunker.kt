package eu.kanade.translation.batch

data class PageChunk(
    val index: Int,
    val pageKeys: List<String>,
    val totalBubbles: Int,
)

object DynamicPageChunker {
    private const val MAX_BUBBLES_PER_CHUNK = 35

    fun targetPageCount(totalPages: Int): Int = when {
        totalPages <= 35 -> 5
        totalPages <= 60 -> 7
        else -> 10
    }

    fun computeChunks(
        pageKeys: List<String>,
        bubbleCountByPage: Map<String, Int> = emptyMap(),
    ): List<PageChunk> {
        if (pageKeys.isEmpty()) return emptyList()
        val totalPages = pageKeys.size

        val defaultTargetSize = targetPageCount(totalPages)

        val chunks = mutableListOf<PageChunk>()
        var currentChunkPages = mutableListOf<String>()
        var currentBubbleCount = 0

        for (key in pageKeys) {
            val bubbles = (bubbleCountByPage[key] ?: 0).coerceAtLeast(0)

            currentChunkPages.add(key)
            currentBubbleCount += bubbles

            val reachedBubbleThreshold = currentBubbleCount >= MAX_BUBBLES_PER_CHUNK
            val reachedTargetSize = currentChunkPages.size >= defaultTargetSize

            if (reachedBubbleThreshold || reachedTargetSize) {
                chunks.add(
                    PageChunk(
                        index = chunks.size,
                        pageKeys = currentChunkPages.toList(),
                        totalBubbles = currentBubbleCount,
                    ),
                )
                currentChunkPages = mutableListOf()
                currentBubbleCount = 0
            }
        }

        if (currentChunkPages.isNotEmpty()) {
            chunks.add(
                PageChunk(
                    index = chunks.size,
                    pageKeys = currentChunkPages.toList(),
                    totalBubbles = currentBubbleCount,
                ),
            )
        }

        return chunks
    }
}
