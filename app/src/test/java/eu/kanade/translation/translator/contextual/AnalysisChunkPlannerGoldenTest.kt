package eu.kanade.translation.translator.contextual

import eu.kanade.translation.artifact.StageFingerprints
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

/**
 * T924 S4 pure-planner gates for deterministic analysis chunking (design
 * §6.1, schemas contract §1.3, WP3): whole-page core/overlap windows, block
 * arithmetic, edge cases (fewer pages than core cap, overlap 0 and 2),
 * deterministic ids/fingerprints, and the evidence-universe predicate.
 */
class AnalysisChunkPlannerGoldenTest {

    private fun page(
        index: Int,
        blocks: Int = 4,
        tokens: Int = 40,
        key: String = "p$index",
    ) = ChunkPlannerPage(
        pageKey = key,
        naturalPageIndex = index,
        contentFingerprint = StageFingerprints.sourceExcerptHash("content-$index"),
        blockIds = (0 until blocks).map { "p${index}_b$it" },
        estimatedInputTokens = tokens,
    )

    private fun success(result: AnalysisChunkPlanResult): AnalysisChunkPlanResult.Success {
        check(result is AnalysisChunkPlanResult.Success) { "expected Success, was: $result" }
        return result
    }

    private fun rejected(result: AnalysisChunkPlanResult): AnalysisChunkPlanResult.Rejected {
        check(result is AnalysisChunkPlanResult.Rejected) { "expected Rejected, was: $result" }
        return result
    }

    @Test
    fun `windows whole pages with adjacent overlap`() {
        val result = AnalysisChunkPlanner.plan(
            pages = (0 until 5).map { page(it) },
            policy = AnalysisChunkPolicy(maxCorePages = 2, overlapPages = 1),
        )
        val success = success(result)
        success.skippedTextlessPageKeys shouldBe emptyList()
        success.chunks.map { it.corePageKeys } shouldBe
            listOf(listOf("p0", "p1"), listOf("p2", "p3"), listOf("p4"))
        success.chunks.map { it.contextOverlapPageKeys } shouldBe
            listOf(emptyList(), listOf("p1"), listOf("p3"))
    }

    @Test
    fun `fewer pages than the core cap yields a single chunk without overlap`() {
        val result = AnalysisChunkPlanner.plan(
            pages = (0 until 3).map { page(it) },
            policy = AnalysisChunkPolicy(maxCorePages = 16, overlapPages = 2),
        )
        val success = success(result)
        success.chunks.size shouldBe 1
        success.chunks[0].corePageKeys shouldBe listOf("p0", "p1", "p2")
        success.chunks[0].contextOverlapPageKeys shouldBe emptyList()
    }

    @Test
    fun `overlap zero disables context pages entirely`() {
        val result = AnalysisChunkPlanner.plan(
            pages = (0 until 5).map { page(it) },
            policy = AnalysisChunkPolicy(maxCorePages = 2, overlapPages = 0),
        )
        val success = success(result)
        success.chunks.forEach { chunk -> chunk.contextOverlapPageKeys shouldBe emptyList() }
        success.chunks.map { it.corePageKeys } shouldBe
            listOf(listOf("p0", "p1"), listOf("p2", "p3"), listOf("p4"))
    }

    @Test
    fun `overlap two carries the two adjacent predecessors`() {
        val result = AnalysisChunkPlanner.plan(
            pages = (0 until 6).map { page(it) },
            policy = AnalysisChunkPolicy(maxCorePages = 2, overlapPages = 2),
        )
        val success = success(result)
        success.chunks.map { it.corePageKeys } shouldBe
            listOf(listOf("p0", "p1"), listOf("p2", "p3"), listOf("p4", "p5"))
        // Overlap = the adjacent predecessors (last core pages of the previous window).
        success.chunks.map { it.contextOverlapPageKeys } shouldBe
            listOf(emptyList(), listOf("p0", "p1"), listOf("p2", "p3"))
    }

    @Test
    fun `block cap flushes the window before the exceeding page`() {
        val big = ChunkPlannerPage(
            pageKey = "p9",
            naturalPageIndex = 9,
            contentFingerprint = StageFingerprints.sourceExcerptHash("content-9"),
            blockIds = (0 until 10).map { "p9_b$it" },
            estimatedInputTokens = 10,
        )
        val result = AnalysisChunkPlanner.plan(
            pages = listOf(page(0, blocks = 4), big),
            policy = AnalysisChunkPolicy(maxCorePages = 16, overlapPages = 1, maxBlocksPerChunk = 8),
        )
        val success = success(result)
        // p9 alone exceeds the block cap: it still forms its own single-page chunk.
        success.chunks.map { it.corePageKeys } shouldBe listOf(listOf("p0"), listOf("p9"))
        success.chunks[1].coreBlockCount shouldBe 10
    }

    @Test
    fun `token cap flushes the window and overlap shrinks to stay in budget`() {
        val p0 = page(index = 0, blocks = 1, tokens = 600)
        val p1 = page(index = 1, blocks = 1, tokens = 400)
        val p2 = page(index = 2, blocks = 1, tokens = 500)
        val p3 = page(index = 3, blocks = 1, tokens = 600)
        val result = AnalysisChunkPlanner.plan(
            pages = listOf(p0, p1, p2, p3),
            policy = AnalysisChunkPolicy(maxCorePages = 16, overlapPages = 2, maxEstimatedInputTokens = 1000),
        )
        val success = success(result)
        success.chunks.map { it.corePageKeys } shouldBe
            listOf(listOf("p0", "p1"), listOf("p2"), listOf("p3"))
        // Chunk 1: core p2 (500) + both predecessors (1000) exceeds the cap;
        // the farthest predecessor p0 is shed first, p1 stays.
        success.chunks[1].contextOverlapPageKeys shouldBe listOf("p1")
        success.chunks[1].estimatedInputTokens shouldBe 900
        // Chunk 2: the adjacent predecessor is p2 (not p1); 600 + 500
        // exceeds the cap, so the overlap is shed entirely.
        success.chunks[2].contextOverlapPageKeys shouldBe emptyList()
        success.chunks[2].estimatedInputTokens shouldBe 600
    }

    @Test
    fun `textless pages are skipped and reported`() {
        val textless = ChunkPlannerPage(
            pageKey = "p1",
            naturalPageIndex = 1,
            contentFingerprint = StageFingerprints.sourceExcerptHash("empty"),
            blockIds = emptyList(),
            estimatedInputTokens = 0,
        )
        val result = AnalysisChunkPlanner.plan(pages = listOf(page(0), textless, page(2)))
        val success = success(result)
        success.skippedTextlessPageKeys shouldBe listOf("p1")
        success.chunks.size shouldBe 1
        success.chunks[0].corePageKeys shouldBe listOf("p0", "p2")
    }

    @Test
    fun `empty input plans zero chunks`() {
        val result = AnalysisChunkPlanner.plan(pages = emptyList())
        val success = success(result)
        success.chunks shouldBe emptyList()
        success.skippedTextlessPageKeys shouldBe emptyList()
    }

    @Test
    fun `chunk ids are deterministic and shaped per the schema contract`() {
        val policy = AnalysisChunkPolicy(maxCorePages = 1, overlapPages = 1)
        val first = success(AnalysisChunkPlanner.plan((0 until 3).map { page(it) }, policy))
        val second = success(AnalysisChunkPlanner.plan((2 downTo 0).map { page(it) }, policy))

        first.chunks.map { it.chunkId } shouldBe second.chunks.map { it.chunkId }
        first.chunks.forEachIndexed { ordinal, chunk ->
            chunk.chunkId shouldBe
                AnalysisChunkPlanner.chunkId(ordinal, chunk.contributingCorpusFingerprint)
            chunk.chunkId.startsWith("chunk-$ordinal-") shouldBe true
            chunk.chunkId.substringAfter("chunk-$ordinal-").length shouldBe 8
        }
    }

    @Test
    fun `contributing fingerprint is the corpus oracle over the contributing set in order`() {
        val pages = (0 until 4).map { page(it) }
        val chunk = success(AnalysisChunkPlanner.plan(pages, AnalysisChunkPolicy(maxCorePages = 2, overlapPages = 1)))
            .chunks[1]
        // Contributing order = core pages first, then context overlap pages
        // (the T924-AP-03 request payload order).
        val oracle = StageFingerprints.ocrCorpusFingerprint(
            pages = listOf(
                "p2" to pages[2].contentFingerprint,
                "p3" to pages[3].contentFingerprint,
                "p1" to pages[1].contentFingerprint,
            ),
            expectedPageCount = 3,
            expectedPageCountTrusted = true,
            naturalOrderProven = true,
        )
        chunk.contributingCorpusFingerprint shouldBe oracle
        chunk.corePageKeys shouldBe listOf("p2", "p3")
        chunk.contextOverlapPageKeys shouldBe listOf("p1")
    }

    @Test
    fun `evidence universe resolves core and overlap blocks only`() {
        val pages = (0 until 4).map { page(it) }
        val chunk = success(AnalysisChunkPlanner.plan(pages, AnalysisChunkPolicy(maxCorePages = 2, overlapPages = 1)))
            .chunks[1]

        chunk.evidenceResolves("p2", "p2_b0") shouldBe true // core
        chunk.evidenceResolves("p1", "p1_b3") shouldBe true // overlap context
        chunk.evidenceResolves("p0", "p0_b0") shouldBe false // outside contributing set (V1)
        chunk.evidenceResolves("p3", "p3_b0") shouldBe true // core
        chunk.evidenceResolves("p2", "p1_b0") shouldBe false // foreign page prefix (V1 guard)
        chunk.evidenceResolves("p2", "p2_b99") shouldBe false // unknown block (V1)
        chunk.evidenceResolves("p2", "p3_b0") shouldBe false // wrong prefix under page (V1)
    }

    @Test
    fun `core page sets partition the corpus exactly once`() {
        val pages = (0 until 13).map { page(it, blocks = it + 1) }
        val success = success(AnalysisChunkPlanner.plan(pages, AnalysisChunkPolicy(maxCorePages = 4, overlapPages = 2)))
        val corePages = success.chunks.flatMap { it.corePageKeys }
        corePages.size shouldBe corePages.toSet().size
        corePages.toSet() shouldBe pages.map { it.pageKey }.toSet()
    }

    @Test
    fun `duplicate page key and negative tokens are rejected`() {
        rejected(AnalysisChunkPlanner.plan(listOf(page(0), page(0))))
            .reason shouldBe "duplicate pageKey: p0"

        rejected(AnalysisChunkPlanner.plan(listOf(page(0, tokens = -1))))
            .reason shouldBe "negative estimatedInputTokens on p0"
    }

    @Test
    fun `policy bounds are enforced`() {
        rejected(AnalysisChunkPlanner.plan(listOf(page(0)), AnalysisChunkPolicy(maxCorePages = 0)))
        rejected(AnalysisChunkPlanner.plan(listOf(page(0)), AnalysisChunkPolicy(overlapPages = 3)))
    }

    @Test
    fun `unproven natural index falls back to sorted pageKey determinism`() {
        val keyed = listOf(
            ChunkPlannerPage("b.jpg", null, StageFingerprints.sourceExcerptHash("b"), listOf("b_b0"), 10),
            ChunkPlannerPage("a.jpg", null, StageFingerprints.sourceExcerptHash("a"), listOf("a_b0"), 10),
            ChunkPlannerPage("c.jpg", null, StageFingerprints.sourceExcerptHash("c"), listOf("c_b0"), 10),
        )
        val success = success(AnalysisChunkPlanner.plan(keyed, AnalysisChunkPolicy(maxCorePages = 2, overlapPages = 1)))
        success.chunks.map { it.corePageKeys } shouldBe listOf(listOf("a.jpg", "b.jpg"), listOf("c.jpg"))
        success.chunks[1].contextOverlapPageKeys shouldBe listOf("b.jpg")
    }

    @Test
    fun `permuted input order never changes the plan (100 seeded iterations)`() {
        val policy = AnalysisChunkPolicy(maxCorePages = 3, overlapPages = 2)
        val base = (0 until 10).map { page(it, blocks = it + 1, tokens = 10 * (it + 1)) }
        val expected = success(AnalysisChunkPlanner.plan(base, policy))
        var seed = 0x5EED_0002L
        repeat(100) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val shuffled = base.shuffled(java.util.Random(seed))
            val actual = success(AnalysisChunkPlanner.plan(shuffled, policy))
            actual.chunks shouldBe expected.chunks
            actual.skippedTextlessPageKeys shouldBe expected.skippedTextlessPageKeys
        }
    }

    @Test
    fun `golden small-corpus fixture is byte-stable`() {
        val success = success(AnalysisChunkPlanner.plan(goldenPages(), AnalysisChunkPolicy()))
        val goldenBytes = javaClass.getResourceAsStream(GOLDEN_RESOURCE)?.use { it.readBytes() }
            ?: error("missing golden fixture $GOLDEN_RESOURCE")
        val actual = Json
            .encodeToString(JsonElement.serializer(), canonicalPlanJson(success))
        actual.toByteArray(Charsets.UTF_8) shouldBe goldenBytes
        // In-source pins of the deterministic first-chunk identity (mirrors the
        // envelope golden test's fingerprint-literal pin).
        success.chunks.first().chunkId shouldBe GOLDEN_FIRST_CHUNK_ID
        success.chunks.first().contributingCorpusFingerprint shouldBe
            GOLDEN_FIRST_CHUNK_FINGERPRINT
    }

    /**
     * The fixed golden corpus: five pages with fixed 64-hex content
     * fingerprints, provable natural indexes and varied block counts (one
     * textless page). Under the default policy the summed token estimate
     * (15000 + 5000) exceeds the default 16384 input cap, forcing a second
     * chunk that carries the adjacent p2 as its context overlap.
     */
    private fun goldenPages(): List<ChunkPlannerPage> = listOf(
        ChunkPlannerPage(
            pageKey = "p0",
            naturalPageIndex = 0,
            contentFingerprint = GOLDEN_PAGE_FP_0,
            blockIds = listOf("p0_b0", "p0_b1", "p0_b2", "p0_b3"),
            estimatedInputTokens = 5000,
        ),
        ChunkPlannerPage(
            pageKey = "p1",
            naturalPageIndex = 1,
            contentFingerprint = GOLDEN_PAGE_FP_1,
            blockIds = listOf("p1_b0", "p1_b1"),
            estimatedInputTokens = 5000,
        ),
        ChunkPlannerPage(
            pageKey = "p2",
            naturalPageIndex = 2,
            contentFingerprint = GOLDEN_PAGE_FP_2,
            blockIds = listOf("p2_b0", "p2_b1", "p2_b2", "p2_b3", "p2_b4", "p2_b5"),
            estimatedInputTokens = 5000,
        ),
        ChunkPlannerPage(
            pageKey = "p3",
            naturalPageIndex = 3,
            contentFingerprint = GOLDEN_PAGE_FP_3,
            blockIds = emptyList(),
            estimatedInputTokens = 0,
        ),
        ChunkPlannerPage(
            pageKey = "p4",
            naturalPageIndex = 4,
            contentFingerprint = GOLDEN_PAGE_FP_4,
            blockIds = listOf("p4_b0", "p4_b1", "p4_b2"),
            estimatedInputTokens = 5000,
        ),
    )

    /**
     * Canonical serialization of a successful plan (field order is the
     * canonical byte order; compact single-line JSON, no trailing newline).
     * The plan data classes are deliberately pure (not persisted), so the
     * golden harness owns this stable projection of [AnalysisChunkPlanner]
     * output; any planner-visible change breaks the byte pin below.
     */
    private fun canonicalPlanJson(result: AnalysisChunkPlanResult.Success): JsonElement =
        buildJsonObject {
            put("plannerVersion", AnalysisChunkPlanner.PLANNER_VERSION)
            put(
                "chunks",
                JsonArray(
                    result.chunks.map { chunk ->
                        buildJsonObject {
                            put("chunkOrdinal", chunk.chunkOrdinal)
                            put("chunkId", chunk.chunkId)
                            put(
                                "corePageKeys",
                                JsonArray(chunk.corePageKeys.map(::JsonPrimitive)),
                            )
                            put(
                                "contextOverlapPageKeys",
                                JsonArray(chunk.contextOverlapPageKeys.map(::JsonPrimitive)),
                            )
                            put("contributingCorpusFingerprint", chunk.contributingCorpusFingerprint)
                            put("coreBlockCount", chunk.coreBlockCount)
                            put("contributingBlockCount", chunk.contributingBlockCount)
                            put("estimatedInputTokens", chunk.estimatedInputTokens)
                            put(
                                "contributingBlockIds",
                                JsonObject(
                                    chunk.contributingBlockIds.mapValues { (_, ids) ->
                                        JsonArray(ids.map(::JsonPrimitive))
                                    },
                                ),
                            )
                        }
                    },
                ),
            )
            put(
                "skippedTextlessPageKeys",
                JsonArray(result.skippedTextlessPageKeys.map(::JsonPrimitive)),
            )
        }

    @Test
    fun `changing any plan input changes chunk fingerprints`() {
        val base = success(AnalysisChunkPlanner.plan((0 until 3).map { page(it) }, AnalysisChunkPolicy(maxCorePages = 2)))
        val differentContent = success(
            AnalysisChunkPlanner.plan(
                (0 until 3).map { page(it).copy(contentFingerprint = StageFingerprints.sourceExcerptHash("x-$it")) },
                AnalysisChunkPolicy(maxCorePages = 2),
            ),
        )
        base.chunks.map { it.contributingCorpusFingerprint } shouldNotBe
            differentContent.chunks.map { it.contributingCorpusFingerprint }
    }

    private companion object {
        const val GOLDEN_RESOURCE = "/t924/golden/analysis-chunks-small.json"
        const val GOLDEN_FIRST_CHUNK_ID = "chunk-0-8a5beeb9"
        const val GOLDEN_FIRST_CHUNK_FINGERPRINT =
            "8a5beeb944022a8b0ea2bf1e8ead7cd5e4b42f9032bf3874c0d9cd361ff6b407"
        const val GOLDEN_PAGE_FP_0 = "c02088c751c66ba1aa8cd4f430bcee7eb496d044510917726e218561e84b00af"
        const val GOLDEN_PAGE_FP_1 = "ca3d946850027ff5f4bd86e9bef3c1d39533d3252928dc7ea0251f724d3facfc"
        const val GOLDEN_PAGE_FP_2 = "d8f50e1c4858d4796205be8e2b7e27bf8fbbd3728b1688c363a045ce0860ff8f"
        const val GOLDEN_PAGE_FP_3 = "e6693f052d07c6cf45a4f8f944c557d7c7e670af4c6874e8c6e5d518e33e171b"
        const val GOLDEN_PAGE_FP_4 = "22aa8335f92bac13ed6ce6a704fc48b3798ead3586452c14e969a1daaf902a37"
    }
}
