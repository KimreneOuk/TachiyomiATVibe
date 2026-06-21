package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class TranslationContextChunkPlannerTest {

    @Test
    fun `chunks preserve page order`() {
        val pages = linkedMapOf(
            "003.jpg" to page("third"),
            "001.jpg" to page("first"),
            "002.jpg" to page("second"),
        )

        val result = TranslationContextChunkPlanner.plan(pages, requestedOutputTokens = 8192)

        result.rejectedPages shouldBe emptyMap()
        result.chunks.flatMap { it.pages.keys } shouldContainExactly listOf("003.jpg", "001.jpg", "002.jpg")
    }

    @Test
    fun `cjk heavy text is estimated conservatively`() {
        val cjk = "\u65e5".repeat(100)
        val latin = "a".repeat(100)

        TranslationContextChunkPlanner.estimateTokens(cjk) shouldBe 100
        TranslationContextChunkPlanner.estimateTokens(latin) shouldBe 25
    }

    @Test
    fun `large pages split by block without exceeding context`() {
        val pages = linkedMapOf(
            "001.jpg" to PageTranslation(
                blocks = MutableList(20) { index -> block("block-$index " + "x".repeat(2000)) },
            ),
        )

        val result = TranslationContextChunkPlanner.plan(pages, requestedOutputTokens = 8192)

        result.rejectedPages shouldBe emptyMap()
        result.chunks.size shouldNotBe 1
        result.chunks.forEach { chunk ->
            (
                chunk.estimatedPromptTokens + chunk.maxOutputTokens +
                    TranslationContextChunkPlanner.SAFETY_MARGIN <=
                    TranslationContextChunkPlanner.MAX_CONTEXT_TOKENS
                ) shouldBe true
        }
    }

    @Test
    fun `single oversized block is rejected instead of planned`() {
        val pages = linkedMapOf(
            "001.jpg" to page("x".repeat(40_000)),
            "002.jpg" to page("small"),
        )

        val result = TranslationContextChunkPlanner.plan(pages, requestedOutputTokens = 8192)

        result.rejectedPages.keys shouldContainExactly listOf("001.jpg")
        result.chunks.flatMap { it.pages.keys } shouldContainExactly listOf("002.jpg")
    }

    @Test
    fun `output cap is upper bound and shrinks to fit budget`() {
        val pages = linkedMapOf(
            "001.jpg" to page("x".repeat(20_000)),
        )

        val result = TranslationContextChunkPlanner.plan(pages, requestedOutputTokens = 8192)
        val chunk = result.chunks.single()

        (chunk.maxOutputTokens < 8192) shouldBe true
        (
            chunk.estimatedPromptTokens + chunk.maxOutputTokens +
                TranslationContextChunkPlanner.SAFETY_MARGIN <=
                TranslationContextChunkPlanner.MAX_CONTEXT_TOKENS
            ) shouldBe true
    }

    @Test
    fun `rolling context is only included when it fits`() {
        val pages = linkedMapOf("001.jpg" to page("hello"))
        val chunk = TranslationContextChunkPlanner.plan(pages, requestedOutputTokens = 1024).chunks.single()

        val withContext = TranslationContextChunkPlanner.withRollingContext(
            chunk = chunk,
            rollingContext = "name => proper noun",
            requestedOutputTokens = 1024,
        )
        val withoutHugeContext = TranslationContextChunkPlanner.withRollingContext(
            chunk = chunk,
            rollingContext = "x".repeat(20_000),
            requestedOutputTokens = 1024,
        )

        withContext.rollingContext shouldBe "name => proper noun"
        withoutHugeContext.rollingContext shouldBe ""
    }

    private fun page(text: String): PageTranslation {
        return PageTranslation(blocks = mutableListOf(block(text)))
    }

    private fun block(text: String): TranslationBlock {
        return TranslationBlock(
            text = text,
            width = 10f,
            height = 10f,
            x = 0f,
            y = 0f,
            symHeight = 1f,
            symWidth = 1f,
            angle = 0f,
        )
    }
}
