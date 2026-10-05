package eu.kanade.translation.engines.translator.contextual
import eu.kanade.translation.engines.translator.retry.AiTranslationRetryPlanner
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
        result.chunks.single().protocol shouldBe ContextualRequestProtocol.BATCH_V1
    }

    @Test
    fun `unannotated chunks remain on legacy protocol`() {
        TranslationContextChunk(
            pages = linkedMapOf(),
            blockCount = 0,
            rollingContext = "",
            estimatedPromptTokens = 0,
            maxOutputTokens = 256,
        ).protocol shouldBe ContextualRequestProtocol.LEGACY
    }

    @Test
    fun `cjk heavy text is estimated conservatively`() {
        val cjk = "\u65e5".repeat(100)
        val latin = "a".repeat(100)

        TranslationContextChunkPlanner.estimateTokens(cjk) shouldBe 100
        TranslationContextChunkPlanner.estimateTokens(latin) shouldBe 13
    }

    @Test
    fun `large page is rejected whole instead of splitting its blocks`() {
        val pages = linkedMapOf(
            "001.jpg" to PageTranslation(
                // CJK text tokenizes at roughly one token per character, so
                // the complete page cannot fit one MAX_CONTEXT_TOKENS envelope.
                blocks = MutableList(20) { index -> block("block-$index " + "\u65e5".repeat(TranslationContextChunkPlanner.MAX_CONTEXT_TOKENS / 6)) },
            ),
        )

        val result = TranslationContextChunkPlanner.plan(pages, requestedOutputTokens = 8192)

        result.rejectedPages.keys shouldContainExactly listOf("001.jpg")
        result.chunks shouldBe emptyList()
    }

    @Test
    fun `single oversized block is rejected instead of planned`() {
        val pages = linkedMapOf(
            "001.jpg" to page("\u65e5".repeat(TranslationContextChunkPlanner.MAX_CONTEXT_TOKENS)),
            "002.jpg" to page("small"),
        )

        val result = TranslationContextChunkPlanner.plan(pages, requestedOutputTokens = 8192)

        result.rejectedPages.keys shouldContainExactly listOf("001.jpg")
        result.chunks.flatMap { it.pages.keys } shouldContainExactly listOf("002.jpg")
    }

    @Test
    fun `output cap is upper bound and shrinks to fit budget`() {
        val pages = linkedMapOf(
            "001.jpg" to page("\u65e5".repeat(TranslationContextChunkPlanner.MAX_CONTEXT_TOKENS / 2)),
        )

        val requested = TranslationContextChunkPlanner.MAX_CONTEXT_TOKENS
        val result = TranslationContextChunkPlanner.plan(pages, requestedOutputTokens = requested)
        val chunk = result.chunks.single()

        (chunk.maxOutputTokens < requested) shouldBe true
        (
            chunk.estimatedPromptTokens + chunk.maxOutputTokens +
                TranslationContextChunkPlanner.SAFETY_MARGIN <=
                TranslationContextChunkPlanner.MAX_CONTEXT_TOKENS
            ) shouldBe true
    }

    @Test
    fun `batch output cap reserves envelope overhead`() {
        val pages = linkedMapOf("001.jpg" to page("hello"))
        val chunk = TranslationContextChunkPlanner.plan(
            pages,
            requestedOutputTokens = TranslationContextChunkPlanner.MAX_CONTEXT_TOKENS,
        ).chunks.single()
        val constraints = TranslationContextChunkPlanner.constraintsFor(
            TranslationContextChunkPlanner.Profile.DEFAULT,
        )
        val legacyCap = StreamingChunkPlanner.effectiveOutputCap(
            promptTokens = chunk.estimatedPromptTokens,
            requestedOutputTokens = TranslationContextChunkPlanner.MAX_CONTEXT_TOKENS,
            constraints = constraints,
            protocol = ContextualRequestProtocol.LEGACY,
        )
        val envelopeReserve = TranslationContextChunkPlanner.batchResponseOverheadTokens(
            blockCount = chunk.blockCount,
            pageCount = chunk.pages.size,
        )

        (chunk.maxOutputTokens < legacyCap) shouldBe true
        (
            chunk.estimatedPromptTokens + chunk.maxOutputTokens +
                TranslationContextChunkPlanner.SAFETY_MARGIN + envelopeReserve <=
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
    fun `T11 rolling budget evicts oldest pairs before dropping history and preserves request floor`() {
        val currentPages = linkedMapOf("current.jpg" to page("CURRENT REQUEST CONTENT"))
        val base = TranslationContextChunkPlanner.plan(
            currentPages,
            requestedOutputTokens = 1024,
        ).chunks.single()
        val history = (0 until TranslationContextChunkPlanner.MAX_ROLLING_PAIRS).joinToString("\n") { index ->
            "source-$index => " + "日".repeat(72)
        }

        val enriched = TranslationContextChunkPlanner.withRollingContext(
            chunk = base,
            rollingContext = history,
            requestedOutputTokens = 1024,
        )

        enriched.rollingContext shouldContain "source-31 =>"
        enriched.rollingContext.contains("source-0 =>") shouldBe false
        val keptLines = enriched.rollingContext.lineSequence().filter { it.isNotBlank() }.count()
        (keptLines in 1 until TranslationContextChunkPlanner.MAX_ROLLING_PAIRS) shouldBe true
        enriched.pages["current.jpg"]!!.blocks.single().text shouldBe "CURRENT REQUEST CONTENT"
        (enriched.maxOutputTokens >= TranslationContextChunkPlanner.MIN_OUTPUT_TOKENS) shouldBe true
    }

    @Test
    fun `lm studio profile retains complete pages while respecting the token budget`() {
        val pages = linkedMapOf(
            "001.jpg" to PageTranslation(blocks = MutableList(10) { block("p1-$it") }),
            "002.jpg" to PageTranslation(blocks = MutableList(10) { block("p2-$it") }),
            "003.jpg" to PageTranslation(blocks = MutableList(10) { block("p3-$it") }),
            "004.jpg" to PageTranslation(blocks = MutableList(10) { block("p4-$it") }),
            "005.jpg" to PageTranslation(blocks = MutableList(10) { block("p5-$it") }),
            "006.jpg" to PageTranslation(blocks = MutableList(10) { block("p6-$it") }),
            "007.jpg" to PageTranslation(blocks = MutableList(10) { block("p7-$it") }),
            "008.jpg" to PageTranslation(blocks = MutableList(10) { block("p8-$it") }),
            "009.jpg" to PageTranslation(blocks = MutableList(10) { block("p9-$it") }),
            "010.jpg" to PageTranslation(blocks = MutableList(10) { block("p10-$it") }),
            "011.jpg" to PageTranslation(blocks = MutableList(10) { block("p11-$it") }),
            "012.jpg" to PageTranslation(blocks = MutableList(10) { block("p12-$it") }),
        )

        val result = TranslationContextChunkPlanner.plan(
            pages = pages,
            requestedOutputTokens = 8192,
            profile = TranslationContextChunkPlanner.Profile.LM_STUDIO,
        )

        val constraints = TranslationContextChunkPlanner.constraintsFor(
            TranslationContextChunkPlanner.Profile.LM_STUDIO,
        )
        constraints.maxContextTokens shouldBe 8_192
        constraints.maxRollingContextTokens shouldBe 512
        result.rejectedPages shouldBe emptyMap()
        result.chunks.forEach { chunk ->
            (
                chunk.estimatedPromptTokens + chunk.maxOutputTokens +
                    constraints.safetyMargin <= constraints.maxContextTokens
                ) shouldBe true
        }
        // Every accepted block is planned exactly once across the chunks.
        result.chunks.sumOf { it.blockCount } shouldBe 120
        result.chunks.flatMap { it.pages.keys }.distinct().size shouldBe 12
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
    fun `resume skips scriptless equality but replans a meaningful Japanese echo`() {
        // Common Latin words/names are not evidence of an echo, while an
        // unchanged Japanese source remains requestable for ja->en.
        val pages = linkedMapOf(
            "001.jpg" to PageTranslation(
                blocks = mutableListOf(
                    blockWith(text = "OK!", translation = "OK!"),
                    blockWith(text = "待て！", translation = "待て！"),
                ),
            ),
        )

        val result = TranslationContextChunkPlanner.plan(
            pages = pages,
            requestedOutputTokens = 8192,
            sourceLanguageCode = "ja",
            targetLanguageCode = "en",
        )

        result.rejectedPages shouldBe emptyMap()
        result.chunks.single().blockCount shouldBe 1
        result.chunks.single().pages.values.single().blocks.single().text shouldBe "待て！"
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

    @Test
    fun `updateRollingContext keeps plain source and translation pairs`() {
        val page = PageTranslation(
            blocks = mutableListOf(
                blockWith("行く", "I'm going.").copy(parentWidth = 40f, parentHeight = 40f),
                blockWith("三年後、東京。", "Three years later, Tokyo."),
            ),
        )

        val out = TranslationContextChunkPlanner.updateRollingContext("", mapOf("001.jpg" to page))

        out shouldContain "行く => I'm going."
        out shouldContain "三年後、東京。 => Three years later, Tokyo."
        out.contains("[SPEECH]") shouldBe false
    }

    @Test
    fun `rolling window is bounded to MAX_ROLLING_PAIRS lines`() {
        // Build a chapter with more translatable blocks than the pair cap; the
        // rolling context must keep only the most recent MAX_ROLLING_PAIRS.
        val pages = linkedMapOf(
            "001.jpg" to PageTranslation(
                blocks = MutableList(TranslationContextChunkPlanner.MAX_ROLLING_PAIRS + 5) {
                    blockWith("blk$it", "trans$it")
                },
            ),
        )
        val out = TranslationContextChunkPlanner.updateRollingContext("", pages)
        val lineCount = out.lineSequence().filter { it.isNotBlank() }.count()
        lineCount shouldBe TranslationContextChunkPlanner.MAX_ROLLING_PAIRS
        // The newest pairs survive; the oldest are evicted.
        out shouldContain
            "blk${TranslationContextChunkPlanner.MAX_ROLLING_PAIRS + 4} => trans${TranslationContextChunkPlanner.MAX_ROLLING_PAIRS + 4}"
    }

    @Test
    fun `rolling context stays an independent history section`() {
        val pages = linkedMapOf("001.jpg" to page("hello"))
        val chunk = TranslationContextChunkPlanner.plan(pages, requestedOutputTokens = 8192).chunks.single()

        val withHistory = TranslationContextChunkPlanner.withRollingContext(
            chunk = chunk,
            rollingContext = "源 => source",
            requestedOutputTokens = 8192,
        )
        withHistory.rollingContext shouldBe "源 => source"

        // The tokenizer keeps this below the section cap, so the complete
        // current history remains intact.
        val retainedHistory = TranslationContextChunkPlanner.withRollingContext(
            chunk = chunk,
            rollingContext = "x".repeat(2_000),
            requestedOutputTokens = 8192,
        )
        retainedHistory.rollingContext shouldBe "x".repeat(2_000)

        // A runaway history section is dropped as a whole, without silent
        // output-cap shrinkage.
        val hugeHistory = TranslationContextChunkPlanner.withRollingContext(
            chunk = chunk,
            rollingContext = "x".repeat(50_000),
            requestedOutputTokens = 8192,
        )
        hugeHistory.rollingContext shouldBe ""
    }

    @Test
    fun `lm studio rolling context respects 512 budget`() {
        val pages = linkedMapOf("001.jpg" to page("hello"))
        val chunk = TranslationContextChunkPlanner.plan(
            pages,
            requestedOutputTokens = 8192,
            profile = TranslationContextChunkPlanner.Profile.LM_STUDIO,
        ).chunks.single()

        val constraints = TranslationContextChunkPlanner.constraintsFor(
            TranslationContextChunkPlanner.Profile.LM_STUDIO,
        )
        constraints.maxContextTokens shouldBe 8_192
        constraints.maxRollingContextTokens shouldBe 512

        // Small rolling context fits within 512
        val withSmall = TranslationContextChunkPlanner.withRollingContext(
            chunk = chunk,
            rollingContext = "源 => source",
            requestedOutputTokens = 8192,
            profile = TranslationContextChunkPlanner.Profile.LM_STUDIO,
        )
        withSmall.rollingContext shouldBe "源 => source"

        // Context exceeding 512 tokens is dropped
        val withLarge = TranslationContextChunkPlanner.withRollingContext(
            chunk = chunk,
            rollingContext = "x".repeat(5_000),
            requestedOutputTokens = 8192,
            profile = TranslationContextChunkPlanner.Profile.LM_STUDIO,
        )
        withLarge.rollingContext shouldBe ""
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
