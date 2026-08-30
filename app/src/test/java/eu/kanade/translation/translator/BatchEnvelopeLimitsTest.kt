package eu.kanade.translation.translator

import eu.kanade.translation.translator.TranslationContextChunkPlanner.Profile

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BatchEnvelopeLimitsTest {

    @Test
    fun `dense chapter packs complete pages without static page or block caps`() {
        val pages = linkedMapOf(*Array(10) { i -> "p$i" to page(*Array(8) { block("text-$i-$it") }) })
        val chunks = planAll(pages)

        chunks.size shouldBe 1
        chunks.single().blockCount shouldBe 80
        chunks.single().pages.keys.toList() shouldContainExactly pages.keys.toList()
    }

    @Test
    fun `single dense page remains one envelope`() {
        val pages = linkedMapOf("p0" to page(*Array(40) { block("text-$it") }))
        val chunks = planAll(pages)

        chunks.size shouldBe 1
        chunks.single().blockCount shouldBe 40
        chunks.single().pages.keys.toList() shouldContainExactly listOf("p0")
    }

    private fun planAll(pages: LinkedHashMap<String, PageTranslation>): List<TranslationContextChunk> {
        val planner = StreamingChunkPlanner(
            requestedOutputTokens = 8192,
            profile = Profile.DEFAULT,
            naturalPageIndexes = pages.keys.mapIndexed { index, key -> key to index }.toMap(),
        )
        val chunks = mutableListOf<TranslationContextChunk>()
        pages.forEach { (key, page) ->
            planner.accept(key, page)?.chunk?.let { chunks += it }
        }
        planner.flushRemaining().finalChunk?.let { chunks += it }
        return chunks
    }

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
