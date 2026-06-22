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

    @Test
    fun `lm studio profile limits pages and blocks per chunk`() {
        val pages = linkedMapOf(
            "001.jpg" to PageTranslation(blocks = MutableList(10) { block("p1-$it") }),
            "002.jpg" to PageTranslation(blocks = MutableList(10) { block("p2-$it") }),
            "003.jpg" to PageTranslation(blocks = MutableList(10) { block("p3-$it") }),
            "004.jpg" to PageTranslation(blocks = MutableList(10) { block("p4-$it") }),
            "005.jpg" to PageTranslation(blocks = MutableList(10) { block("p5-$it") }),
        )

        val result = TranslationContextChunkPlanner.plan(
            pages = pages,
            requestedOutputTokens = 8192,
            profile = TranslationContextChunkPlanner.Profile.LM_STUDIO,
        )

        result.rejectedPages shouldBe emptyMap()
        result.chunks.size shouldBe 2
        result.chunks.forEach { chunk ->
            (chunk.blockCount <= 32) shouldBe true
            (chunk.pages.size <= 4) shouldBe true
            (
                chunk.estimatedPromptTokens + chunk.maxOutputTokens +
                    TranslationContextChunkPlanner.constraintsFor(
                        TranslationContextChunkPlanner.Profile.LM_STUDIO,
                    ).safetyMargin <=
                    TranslationContextChunkPlanner.constraintsFor(
                        TranslationContextChunkPlanner.Profile.LM_STUDIO,
                    ).maxContextTokens
                ) shouldBe true
        }
    }

    @Test
    fun `retry planner retries only untranslated blocks`() {
        val missing = block("missing")
        val translated = block("done").apply { translation = "finished" }
        val sourceEqual = block("same").apply { translation = "same" }
        val chunk = TranslationContextChunk(
            pages = linkedMapOf(
                "001.jpg" to PageTranslation(blocks = mutableListOf(translated, missing)),
                "002.jpg" to PageTranslation(blocks = mutableListOf(sourceEqual)),
            ),
            blockCount = 3,
            rollingContext = "",
            estimatedPromptTokens = 1000,
            maxOutputTokens = 1024,
        )

        val retryPages = AiTranslationRetryPlanner.untranslatedPages(chunk)

        retryPages.keys.toList() shouldContainExactly listOf("001.jpg", "002.jpg")
        retryPages["001.jpg"]!!.blocks shouldContainExactly listOf(missing)
        retryPages["002.jpg"]!!.blocks shouldContainExactly listOf(sourceEqual)
    }

    @Test
    fun `retry planner splits thrown chunks smaller until single block`() {
        val chunk = TranslationContextChunk(
            pages = linkedMapOf(
                "001.jpg" to PageTranslation(blocks = MutableList(4) { block("b$it") }),
            ),
            blockCount = 4,
            rollingContext = "",
            estimatedPromptTokens = 1000,
            maxOutputTokens = 1024,
        )

        val split = AiTranslationRetryPlanner.planFailureSplit(
            chunk = chunk,
            requestedOutputTokens = 1024,
            profile = TranslationContextChunkPlanner.Profile.LM_STUDIO,
        )
        val terminal = AiTranslationRetryPlanner.planFailureSplit(
            chunk = split.chunks.first(),
            requestedOutputTokens = 1024,
            profile = TranslationContextChunkPlanner.Profile.LM_STUDIO,
        )

        split.chunks.map { it.blockCount } shouldContainExactly listOf(2, 2)
        terminal.chunks.map { it.blockCount } shouldContainExactly listOf(1, 1)
    }

    @Test
    fun `resume - already translated blocks are skipped`() {
        // On a resumed AI batch, blocks that already carry a real translation
        // must NOT be re-sent to the LLM. Only the still-untranslated block
        // should be planned.
        val pages = linkedMapOf(
            "001.jpg" to PageTranslation(
                blocks = mutableListOf(
                    blockWith(text = "源", translation = "source"), // already done
                    blockWith(text = "分", translation = ""), // needs translation
                ),
            ),
        )

        val result = TranslationContextChunkPlanner.plan(pages, requestedOutputTokens = 8192)

        result.rejectedPages shouldBe emptyMap()
        val chunk = result.chunks.single()
        chunk.blockCount shouldBe 1
        // The single planned block is the untranslated one.
        chunk.pages.values.single().blocks.single().text shouldBe "分"
    }

    @Test
    fun `resume - source-equal translation is treated as untranslated`() {
        // A block whose translation equals its source (adapter echoed it back)
        // has NOT actually been translated — it must be re-planned.
        val pages = linkedMapOf(
            "001.jpg" to PageTranslation(
                blocks = mutableListOf(
                    blockWith(text = "源", translation = "源"), // source-equal
                ),
            ),
        )

        val result = TranslationContextChunkPlanner.plan(pages, requestedOutputTokens = 8192)

        result.rejectedPages shouldBe emptyMap()
        result.chunks.single().blockCount shouldBe 1
    }

    @Test
    fun `resume - page where all blocks are translated produces no chunks for it`() {
        // A page whose blocks are all already translated contributes no refs,
        // so it appears in no chunk. The downstream batch loop detects this
        // case and marks the page READY (tested at the pipeline level; here we
        // only assert the planner omits the page).
        val pages = linkedMapOf(
            "001.jpg" to PageTranslation(
                blocks = mutableListOf(
                    blockWith(text = "源", translation = "source"),
                    blockWith(text = "分", translation = "part"),
                ),
            ),
            "002.jpg" to PageTranslation(
                blocks = mutableListOf(blockWith(text = "新", translation = "")), // needs work
            ),
        )

        val result = TranslationContextChunkPlanner.plan(pages, requestedOutputTokens = 8192)

        result.rejectedPages shouldBe emptyMap()
        // Only the page with untranslated blocks is planned.
        result.chunks.flatMap { it.pages.keys } shouldContainExactly listOf("002.jpg")
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

    private fun blockWith(text: String, translation: String): TranslationBlock {
        return TranslationBlock(
            text = text,
            translation = translation,
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
