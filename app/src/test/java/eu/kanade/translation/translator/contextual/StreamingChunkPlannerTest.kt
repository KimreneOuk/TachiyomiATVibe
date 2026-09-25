package eu.kanade.translation.translator.contextual

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.translator.contextual.TranslationContextChunkPlanner.Profile
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class StreamingChunkPlannerTest {

    @Test
    fun `streaming equals batch plan across varied inputs - DEFAULT profile`() {
        defaultScenarios().forEach { (tokens, pages) -> assertParity(pages, tokens, Profile.DEFAULT) }
    }

    @Test
    fun `streaming equals batch plan across varied inputs - LM_STUDIO profile`() {
        lmStudioScenarios().forEach { (tokens, pages) -> assertParity(pages, tokens, Profile.LM_STUDIO) }
    }

    @Test
    fun `streaming equals batch plan with dense and multi-page inputs`() {
        val fortyBlocks = linkedMapOf(
            "001" to PageTranslation(blocks = MutableList(40) { block("b$it") }),
        )
        assertParity(fortyBlocks, 4096, Profile.LM_STUDIO)

        val manyPages = linkedMapOf(*Array(8) { i -> "p$i" to page(block("x$i")) })
        assertParity(manyPages, 4096, Profile.DEFAULT)
    }

    @Test
    fun `streaming output is deterministic across independent runs`() {
        val pages = mixedScenario()
        val first = streamRun(pages, 8192, Profile.DEFAULT)
        val second = streamRun(pages, 8192, Profile.DEFAULT)
        first.chunks shouldBe second.chunks
        first.rejectedPages shouldBe second.rejectedPages
    }

    @Test
    fun `single small page yields one chunk covering all blocks`() {
        val pages = linkedMapOf("001" to page(block("a"), block("b"), block("c")))
        val r = streamRun(pages, 8192, Profile.DEFAULT)

        r.chunks.size shouldBe 1
        r.chunks.single().blockCount shouldBe 3
        r.chunks.single().pages.keys shouldContainExactly listOf("001")
        r.rejectedPages shouldBe emptyMap()
    }

    @Test
    fun `page whose combined source exceeds the budget is rejected whole`() {
        val pages = linkedMapOf(
            "001" to page(*Array(6) { block(cjk(TranslationContextChunkPlanner.MAX_CONTEXT_TOKENS / 6)) }),
        )
        val r = streamRun(pages, 8192, Profile.DEFAULT)

        r.chunks shouldBe emptyList()
        r.rejectedPages.keys shouldContainExactly listOf("001")
    }

    @Test
    fun `single oversized block page is rejected, others still planned`() {
        val pages = linkedMapOf(
            "big" to page(block(cjk(TranslationContextChunkPlanner.MAX_CONTEXT_TOKENS))),
            "ok" to page(block("small")),
        )
        val r = streamRun(pages, 8192, Profile.DEFAULT)

        r.rejectedPages.keys shouldContainExactly listOf("big")
        r.chunks.flatMap { it.pages.keys } shouldContainExactly listOf("ok")
    }

    @Test
    fun `LM_STUDIO packs more than four complete pages when the token budget allows`() {
        val pages = linkedMapOf(*Array(14) { i -> "p$i" to page(block("x$i")) })
        val r = streamRun(pages, 8192, Profile.LM_STUDIO)
        r.chunks.size shouldBe 1
        r.chunks.single().pages.keys.toList() shouldContainExactly pages.keys.toList()
    }

    @Test
    fun `dense page is never split by the historical block limit`() {
        val pages = linkedMapOf("p" to PageTranslation(blocks = MutableList(90) { block("b$it") }))
        val r = streamRun(pages, 8192, Profile.LM_STUDIO)
        r.chunks.size shouldBe 1
        r.chunks.single().blockCount shouldBe 90
        r.chunks.single().pages.keys shouldContainExactly listOf("p")
    }

    @Test
    fun `completedPages covers each chunkable page exactly once, textless never, chunkless once`() {
        val pages = linkedMapOf(
            "p_normal" to page(block("a"), block("b")),
            "p_chunkless" to page(block("源", "src"), block("分", "part")),
            "p_textless" to page(block("   "), block("")),
            "p_normal2" to page(block("c")),
            "p_empty" to PageTranslation(blocks = mutableListOf()),
        )
        val planner = StreamingChunkPlanner(8192, Profile.DEFAULT)
        val emissions = mutableListOf<StreamingChunkPlanner.Emission>()
        pages.forEach { (k, v) -> planner.accept(k, v)?.let(emissions::add) }
        val flush = planner.flushRemaining()
        if (flush.finalChunk != null || flush.completedPages.isNotEmpty()) {
            emissions += StreamingChunkPlanner.Emission(flush.finalChunk, flush.completedPages)
        }

        val completionCount = mutableMapOf<String, Int>()
        val allChunkedPages = mutableSetOf<String>()
        emissions.forEach { emission ->
            emission.completedPages.forEach { completionCount[it] = (completionCount[it] ?: 0) + 1 }
            emission.chunk?.pages?.keys?.let(allChunkedPages::addAll)
        }

        pages.forEach { (key, pg) ->
            val nonBlank = pg.blocks.count { it.text.isNotBlank() }
            val alreadyTranslated: (TranslationBlock) -> Boolean = {
                it.translation.isNotBlank() && it.translation.trim() != it.text.trim()
            }
            val chunkable = pg.blocks.count { it.text.isNotBlank() && !alreadyTranslated(it) }
            when {
                chunkable > 0 -> {
                    completionCount[key] shouldBe 1
                    (key in allChunkedPages) shouldBe true
                }
                nonBlank > 0 -> {
                    // chunkless (resume): completes once, never appears in a chunk
                    completionCount[key] shouldBe 1
                    (key in allChunkedPages) shouldBe false
                }
                else -> {
                    // textless: never completes, never in a chunk
                    (key in completionCount) shouldBe false
                    (key in allChunkedPages) shouldBe false
                }
            }
        }
        completionCount.values.forEach { it shouldBe 1 }
        flush.rejectedPages shouldBe emptyMap()
    }

    @Test
    fun `fully translated page emits a chunkless completion with null chunk`() {
        val planner = StreamingChunkPlanner(8192, Profile.DEFAULT)
        val emission = planner.accept("p", page(block("源", "src"), block("分", "part")))

        emission shouldNotBe null
        emission!!.chunk shouldBe null
        emission.completedPages shouldBe setOf("p")

        val flush = planner.flushRemaining()
        flush.finalChunk shouldBe null
        flush.completedPages shouldBe emptySet()
        flush.rejectedPages shouldBe emptyMap()
    }

    @Test
    fun `textless page with only blank blocks never completes`() {
        val planner = StreamingChunkPlanner(8192, Profile.DEFAULT)
        planner.accept("p", page(block("   "), block(""))) shouldBe null

        val flush = planner.flushRemaining()
        flush.finalChunk shouldBe null
        flush.completedPages shouldBe emptySet()
    }

    @Test
    fun `empty page never completes and produces no chunk`() {
        val planner = StreamingChunkPlanner(8192, Profile.DEFAULT)
        planner.accept("p", PageTranslation(blocks = mutableListOf())) shouldBe null

        val flush = planner.flushRemaining()
        flush.finalChunk shouldBe null
        flush.completedPages shouldBe emptySet()
    }

    @Test
    fun `fallback page indexes stay above supplied natural indexes`() {
        val planner = StreamingChunkPlanner(
            requestedOutputTokens = 8192,
            profile = Profile.DEFAULT,
            naturalPageIndexes = mapOf("natural" to 0),
        )
        planner.accept("fallback", page(block("fallback")))
        planner.accept("natural", page(block("natural")))

        val chunk = planner.flushRemaining().finalChunk!!
        chunk.pageIndexes["fallback"] shouldBe 1
        chunk.pageIndexes["natural"] shouldBe 0
    }

    @Test
    fun `complete dense page stays intact in its batch envelope`() {
        val pages = linkedMapOf(
            "dense" to page(*Array(11) { block("text $it") }),
        )
        val planner = StreamingChunkPlanner(8192, Profile.DEFAULT)

        planner.accept("dense", pages.getValue("dense")) shouldBe null
        val flush = planner.flushRemaining()
        flush.finalChunk!!.pages.keys shouldContainExactly listOf("dense")
        flush.finalChunk!!.blockCount shouldBe 11
        flush.completedPages shouldBe setOf("dense")
        flush.rejectedPages shouldBe emptyMap()
    }

    private data class StreamOutcome(
        val chunks: List<TranslationContextChunk>,
        val rejectedPages: Map<String, String>,
    )

    private fun streamRun(
        pages: LinkedHashMap<String, PageTranslation>,
        requestedOutputTokens: Int,
        profile: Profile,
    ): StreamOutcome {
        val planner = StreamingChunkPlanner(requestedOutputTokens, profile)
        pages.forEach { (k, v) -> planner.accept(k, v) }
        val flush = planner.flushRemaining()
        val chunks = planner.emittedChunks() + listOfNotNull(flush.finalChunk)
        return StreamOutcome(chunks, flush.rejectedPages)
    }

    private fun assertParity(
        pages: LinkedHashMap<String, PageTranslation>,
        requestedOutputTokens: Int,
        profile: Profile,
    ) {
        val batch = TranslationContextChunkPlanner.plan(
            pages,
            requestedOutputTokens,
            profile,
        )
        val stream = streamRun(pages, requestedOutputTokens, profile)

        // Data-class equality pins page keys (ordered), blockCount,
        // estimatedPromptTokens and maxOutputTokens element-by-element.
        stream.chunks shouldBe batch.chunks
        stream.rejectedPages shouldBe batch.rejectedPages
    }

    private fun defaultScenarios(): List<Pair<Int, LinkedHashMap<String, PageTranslation>>> = listOf(
        8192 to linkedMapOf("001" to page(block("a"), block("b"), block("c"))),
        8192 to linkedMapOf(*Array(6) { i -> "p$i" to page(block("x$i")) }),
        8192 to linkedMapOf("001" to page(*Array(6) { block(cjk(2500)) })),
        8192 to linkedMapOf(
            "001" to page(block(cjk(2500)), block(cjk(2500))),
            "002" to page(block(cjk(2500)), block(cjk(2500))),
            "003" to page(block(cjk(2500)), block(cjk(2500))),
        ),
        8192 to linkedMapOf(
            "001" to page(block("源", "src"), block("分")),
            "002" to page(block("same", "same"), block("new")),
        ),
        8192 to linkedMapOf(
            "big" to page(block(cjk(7000))),
            "ok" to page(block("small")),
        ),
        1024 to linkedMapOf(
            "001" to page(block("a")),
            "002" to page(block(cjk(2500))),
            "003" to page(block("源", "done")),
        ),
        8192 to linkedMapOf(
            "001" to page(block("源", "src"), block("分", "part")),
            "002" to page(block("new")),
        ),
        8192 to linkedMapOf(
            "001" to page(block("   "), block("")),
            "002" to page(block("real")),
        ),
        8192 to linkedMapOf(
            "001" to PageTranslation(blocks = mutableListOf()),
            "002" to page(block("only")),
        ),
    )

    private fun lmStudioScenarios(): List<Pair<Int, LinkedHashMap<String, PageTranslation>>> = listOf(
        8192 to linkedMapOf(*Array(6) { i -> "p$i" to page(block("x$i")) }),
        8192 to linkedMapOf("p" to PageTranslation(blocks = MutableList(40) { block("b$it") })),
        8192 to linkedMapOf(
            "001" to page(block(cjk(2500))),
            "002" to page(block(cjk(2500))),
            "003" to page(block(cjk(2500))),
        ),
        8192 to linkedMapOf(
            "big" to page(block(cjk(3000))),
            "ok" to page(block("small")),
        ),
        8192 to linkedMapOf(
            "001" to page(block("源", "src"), block("分")),
            "002" to page(block("same", "same")),
        ),
        4096 to mixedScenario(),
    )

    private fun mixedScenario(): LinkedHashMap<String, PageTranslation> = linkedMapOf(
        "001" to page(block("a"), block("b")),
        "002" to page(block(cjk(2500)), block(cjk(2500))),
        "003" to page(block("源", "src"), block("分")),
        "004" to page(block("   ")),
        "005" to page(block("e"), block("f"), block("g")),
        "006" to page(block(cjk(7000))),
        "007" to page(block("after")),
    )

    private fun cjk(n: Int): String = "\u65e5".repeat(n)

    private fun page(vararg blocks: TranslationBlock): PageTranslation =
        PageTranslation(blocks = blocks.toMutableList())

    private fun block(text: String, translation: String = ""): TranslationBlock =
        TranslationBlock(
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
