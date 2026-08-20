package eu.kanade.translation.batch

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.translator.StreamingChunkPlanner
import eu.kanade.translation.translator.TranslationContextChunk
import eu.kanade.translation.translator.TranslationContextChunkPlanner.Profile
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Phase 5 batch AI request envelope bounds: the streaming planner is configured
 * by the production batch path with the coordinator's page/block caps, so every
 * post-OCR envelope stays within 1-4 consecutive pages and at most 24 accepted
 * blocks while covering all accepted blocks exactly once.
 */
class BatchEnvelopeLimitsTest {

    @Test
    fun `dense chapter splits into envelopes of at most four pages and twenty four blocks`() {
        val pages = linkedMapOf(*Array(10) { i -> "p$i" to page(*Array(8) { block("text-$i-$it") }) })
        val chunks = planAll(pages)

        chunks.isNotEmpty() shouldBe true
        chunks.forEach { chunk ->
            (chunk.pages.size in 1..SequentialBatchCoordinator.MAX_AI_ENVELOPE_PAGES) shouldBe true
            (chunk.blockCount <= SequentialBatchCoordinator.MAX_AI_ENVELOPE_BLOCKS) shouldBe true
        }
        chunks.sumOf { it.blockCount } shouldBe 80
        chunks.flatMap { it.pages.keys } shouldBe pages.keys.toList()
    }

    @Test
    fun `envelopes keep pages in natural order and never split non-consecutively`() {
        val pages = linkedMapOf(*Array(6) { i -> "p$i" to page(*Array(10) { block("text-$i-$it") }) })
        val chunks = planAll(pages)

        chunks.forEach { chunk ->
            val indexes = chunk.pages.keys.map { naturalIndex(it) }
            indexes shouldBe indexes.sorted()
        }
        // A page may span two envelopes when the block cap lands mid-page, but
        // envelope order across the chapter is never decreasing.
        val seenOrder = chunks.flatMap { it.pages.keys.map(::naturalIndex) }
        (seenOrder == seenOrder.sorted()) shouldBe true
        seenOrder.first() shouldBe 0
        seenOrder.last() shouldBe 5
    }

    @Test
    fun `single dense page splits within the block cap without exceeding the page cap`() {
        val pages = linkedMapOf("p0" to page(*Array(40) { block("text-$it") }))
        val chunks = planAll(pages)

        chunks.forEach { chunk ->
            (chunk.pages.size <= SequentialBatchCoordinator.MAX_AI_ENVELOPE_PAGES) shouldBe true
            (chunk.blockCount <= SequentialBatchCoordinator.MAX_AI_ENVELOPE_BLOCKS) shouldBe true
        }
        chunks.sumOf { it.blockCount } shouldBe 40
    }

    private fun planAll(pages: LinkedHashMap<String, PageTranslation>): List<TranslationContextChunk> {
        val planner = StreamingChunkPlanner(
            requestedOutputTokens = 8192,
            profile = Profile.DEFAULT,
            maxBlocksPerChunk = SequentialBatchCoordinator.MAX_AI_ENVELOPE_BLOCKS,
            maxPagesPerChunk = SequentialBatchCoordinator.MAX_AI_ENVELOPE_PAGES,
            naturalPageIndexes = pages.keys.mapIndexed { index, key -> key to index }.toMap(),
        )
        val chunks = mutableListOf<TranslationContextChunk>()
        pages.forEach { (key, page) ->
            val emission = planner.accept(key, page)
            emission?.chunk?.let { chunks += it }
        }
        planner.flushRemaining().finalChunk?.let { chunks += it }
        return chunks
    }

    private fun naturalIndex(pageKey: String): Int = pageKey.removePrefix("p").toInt()

    private fun page(vararg blocks: TranslationBlock): PageTranslation =
        PageTranslation(blocks = blocks.toMutableList())

    private fun block(text: String): TranslationBlock =
        TranslationBlock(
            text = text,
            translation = "",
            width = 10f,
            height = 10f,
            x = 0f,
            y = 0f,
            symHeight = 1f,
            symWidth = 1f,
            angle = 0f,
        )
}
