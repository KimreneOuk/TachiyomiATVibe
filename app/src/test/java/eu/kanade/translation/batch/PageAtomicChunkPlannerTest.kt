package eu.kanade.translation.batch

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.translator.TranslationContextChunkPlanner
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PageAtomicChunkPlannerTest {

    @Test
    fun `selects largest whole-page prefix that fits one request`() {
        val pages = linkedMapOf(
            "001.webp" to pageWithBlocks(10),
            "002.webp" to pageWithBlocks(8),
            "003.webp" to pageWithBlocks(9),
            "004.webp" to pageWithBlocks(2),
        )

        val plan = PageAtomicChunkPlanner.plan(
            pages = pages,
            requestedOutputTokens = 8_192,
            profile = TranslationContextChunkPlanner.Profile.LM_STUDIO,
            maxBlocksPerRequest = 24,
            maxPagesPerRequest = 10,
        )

        plan.pageKeys shouldBe listOf("001.webp", "002.webp")
        plan.transportChunks.size shouldBe 1
        plan.transportChunks.single().blockCount shouldBe 18
        plan.oversizedSinglePage shouldBe false
    }

    @Test
    fun `textless pages stay in logical chunk without consuming request blocks`() {
        val pages = linkedMapOf(
            "001.webp" to pageWithBlocks(0),
            "002.webp" to pageWithBlocks(4),
        )

        val plan = PageAtomicChunkPlanner.plan(
            pages = pages,
            requestedOutputTokens = 8_192,
            profile = TranslationContextChunkPlanner.Profile.LM_STUDIO,
            maxBlocksPerRequest = 24,
            maxPagesPerRequest = 10,
        )

        plan.pageKeys shouldBe listOf("001.webp", "002.webp")
        plan.transportChunks.single().pages.keys shouldBe setOf("002.webp")
    }

    @Test
    fun `single oversized page remains one logical chunk with transport slices`() {
        val plan = PageAtomicChunkPlanner.plan(
            pages = linkedMapOf("001.webp" to pageWithBlocks(30)),
            requestedOutputTokens = 8_192,
            profile = TranslationContextChunkPlanner.Profile.LM_STUDIO,
            maxBlocksPerRequest = 24,
            maxPagesPerRequest = 10,
        )

        plan.pageKeys shouldBe listOf("001.webp")
        plan.transportChunks.size shouldBe 2
        plan.oversizedSinglePage shouldBe true
    }

    private fun pageWithBlocks(count: Int): PageTranslation = PageTranslation(
        blocks = MutableList(count) { index ->
            TranslationBlock(
                text = "source text $index",
                width = 10f,
                height = 10f,
                x = 0f,
                y = 0f,
                symHeight = 10f,
                symWidth = 10f,
                angle = 0f,
            )
        },
    )
}
