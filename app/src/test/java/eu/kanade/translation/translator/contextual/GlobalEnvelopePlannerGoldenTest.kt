package eu.kanade.translation.translator.contextual

import eu.kanade.translation.artifact.EnvelopePlan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * T924 S4 pure-planner gates for the global whole-page envelope planner
 * (design §8, schemas contract §1.5, WP3): block/page budgets (32/8
 * experiment constants), gap-free ordered coverage, page atomicity,
 * deterministic tie-breaks, scene preference, oversized-page rejection, and
 * the golden small-chapter fixture.
 */
class GlobalEnvelopePlannerGoldenTest {

    private fun block(pageIndex: Int, blockIndex: Int, chars: Int = 12) = EnvelopePlannerBlock(
        stableBlockId = "p${pageIndex}_b$blockIndex",
        sourceText = "あ".repeat(chars),
    )

    private fun page(
        pageIndex: Int,
        blockCount: Int = 6,
        chars: Int = 12,
        sceneBoundaryBefore: Boolean = false,
    ) = EnvelopePlannerPage(
        pageKey = "p$pageIndex",
        naturalPageIndex = pageIndex,
        contentFingerprint = PlannerFingerprints.sha256(listOf("page-content", pageIndex)),
        blocks = (0 until blockCount).map { block(pageIndex, it, chars) },
        sceneBoundaryBefore = sceneBoundaryBefore,
    )

    private fun success(result: EnvelopePlanResult): EnvelopePlanResult.Success {
        check(result is EnvelopePlanResult.Success) { "expected Success, was: $result" }
        return result
    }

    private fun rejected(result: EnvelopePlanResult): EnvelopePlanResult.Rejected {
        check(result is EnvelopePlanResult.Rejected) { "expected Rejected, was: $result" }
        return result
    }

    @Test
    fun `block budget closes the envelope at the structural cap`() {
        val result = GlobalEnvelopePlanner.plan(
            pages = (0 until 3).map { page(it, blockCount = 12) },
            corpusFingerprint = "c".repeat(64),
            policy = EnvelopePlannerPolicy(maxBlocksPerEnvelope = 32, maxContributingPages = 8),
            createdAtEpochMs = 1_757_050_000_000,
        )
        val success = success(result)
        success.plan.envelopes.map { it.orderedPageKeys } shouldBe
            listOf(listOf("p0", "p1"), listOf("p2"))
        success.plan.envelopes.map { it.structuralBlockCount } shouldBe listOf(24, 12)
    }

    @Test
    fun `page budget closes the envelope at eight contributing pages`() {
        val result = GlobalEnvelopePlanner.plan(
            pages = (0 until 10).map { page(it, blockCount = 1) },
            corpusFingerprint = "c".repeat(64),
            policy = EnvelopePlannerPolicy(maxBlocksPerEnvelope = 32, maxContributingPages = 8),
            createdAtEpochMs = 1_757_050_000_000,
        )
        val success = success(result)
        success.plan.envelopes.map { it.orderedPageKeys } shouldBe
            listOf((0 until 8).map { "p$it" }, listOf("p8", "p9"))
        success.plan.envelopes.map { it.contributingPageCount } shouldBe listOf(8, 2)
    }

    @Test
    fun `token budgets close the envelope when exhausted`() {
        val result = GlobalEnvelopePlanner.plan(
            pages = (0 until 3).map { page(it, blockCount = 1, chars = 800) },
            corpusFingerprint = "c".repeat(64),
            policy = EnvelopePlannerPolicy(
                maxBlocksPerEnvelope = 32,
                maxContributingPages = 8,
                maxEstimatedInputTokens = 500,
            ),
            createdAtEpochMs = 1_757_050_000_000,
        )
        val success = success(result)
        // One block of 800 chars ≈ 200 input + 8 framing = 208 tokens per page.
        success.plan.envelopes.map { it.orderedPageKeys } shouldBe
            listOf(listOf("p0", "p1"), listOf("p2"))
        success.plan.envelopes.all { it.estimatedInputTokens <= 500 } shouldBe true
    }

    @Test
    fun `every pending block appears exactly once in canonical order`() {
        val pages = (0 until 7).map { page(it, blockCount = it + 1) }
        val success = success(
            GlobalEnvelopePlanner.plan(
                pages,
                "c".repeat(64),
                EnvelopePlannerPolicy(maxBlocksPerEnvelope = 10),
                createdAtEpochMs = 1_757_050_000_000,
            ),
        )
        val expected = pages.flatMap { p -> p.blocks.map { it.stableBlockId } }
        val actual = success.plan.envelopes.flatMap { it.blockIds }
        actual shouldBe expected
        actual.size shouldBe actual.toSet().size
        success.plan.envelopes.flatMap { it.orderedPageKeys } shouldBe pages.map { it.pageKey }
    }

    @Test
    fun `page atomicity keeps every whole page inside one envelope`() {
        val success = success(
            GlobalEnvelopePlanner.plan(
                pages = (0 until 12).map { page(it, blockCount = 8) },
                corpusFingerprint = "c".repeat(64),
                policy = EnvelopePlannerPolicy(maxBlocksPerEnvelope = 20),
                createdAtEpochMs = 1_757_050_000_000,
            ),
        )
        val pageOwners = success.plan.envelopes.flatMap { env ->
            env.orderedPageKeys.map { it to env.envelopeId }
        }
        pageOwners.map { it.first }.size shouldBe pageOwners.map { it.first }.toSet().size
        // A page's blocks are contiguous inside their single envelope.
        success.plan.envelopes.forEach { env ->
            env.blockIds.groupingBy { it.substringBefore("_b") }.eachCount()
                .forEach { (pageKey, count) ->
                    (pageKey in env.orderedPageKeys) shouldBe true
                    count shouldBe 8
                }
        }
    }

    @Test
    fun `scene preference closes envelopes at scene starts`() {
        val pages = (0 until 4).map { page(it, blockCount = 2, sceneBoundaryBefore = it == 2) }
        val preferring = success(
            GlobalEnvelopePlanner.plan(
                pages,
                "c".repeat(64),
                EnvelopePlannerPolicy(preferSceneBreaks = true),
                createdAtEpochMs = 1_757_050_000_000,
            ),
        )
        preferring.plan.envelopes.map { it.orderedPageKeys } shouldBe
            listOf(listOf("p0", "p1"), listOf("p2", "p3"))
        preferring.plan.envelopes.forEach { env -> env.crossesSceneBoundary shouldBe false }

        val ignoring = success(
            GlobalEnvelopePlanner.plan(
                pages,
                "c".repeat(64),
                EnvelopePlannerPolicy(preferSceneBreaks = false),
                createdAtEpochMs = 1_757_050_000_000,
            ),
        )
        ignoring.plan.envelopes.map { it.orderedPageKeys } shouldBe listOf(listOf("p0", "p1", "p2", "p3"))
        ignoring.plan.envelopes.single().crossesSceneBoundary shouldBe true
    }

    @Test
    fun `oversized single page is rejected whole, never split`() {
        val tooManyBlocks = GlobalEnvelopePlanner.plan(
            pages = listOf(page(0, blockCount = 33)),
            corpusFingerprint = "c".repeat(64),
            createdAtEpochMs = 1_757_050_000_000,
        )
        val rejectedBlocks = rejected(tooManyBlocks)
        rejectedBlocks.reasons.single() shouldContain "page p0 oversized: 33 blocks > 32"

        val tooManyTokens = GlobalEnvelopePlanner.plan(
            pages = listOf(page(0, blockCount = 1, chars = 4_000_000)),
            corpusFingerprint = "c".repeat(64),
            createdAtEpochMs = 1_757_050_000_000,
        )
        val rejectedTokens = rejected(tooManyTokens)
        rejectedTokens.reasons.first() shouldContain "input tokens"

        val tooMuchOutput = GlobalEnvelopePlanner.plan(
            pages = listOf(page(0, blockCount = 1, chars = 4_000_000)),
            corpusFingerprint = "c".repeat(64),
            policy = EnvelopePlannerPolicy(
                maxEstimatedInputTokens = 10_000_000, // keep the input check out of the way
                maxEstimatedOutputTokens = 1_000,
            ),
            createdAtEpochMs = 1_757_050_000_000,
        )
        rejected(tooMuchOutput)
            .reasons.first() shouldContain "output tokens"
    }

    @Test
    fun `invalid policy, empty corpus and duplicates are rejected`() {
        GlobalEnvelopePlanner.plan(
            pages = listOf(page(0)),
            corpusFingerprint = "c".repeat(64),
            policy = EnvelopePlannerPolicy(maxBlocksPerEnvelope = 0),
            createdAtEpochMs = 1L,
        ).let(::rejected).reasons.first() shouldContain "policy:"

        GlobalEnvelopePlanner.plan(
            pages = emptyList(),
            corpusFingerprint = "c".repeat(64),
            createdAtEpochMs = 1L,
        ).let(::rejected).reasons.single() shouldBe "no pending blocks"

        GlobalEnvelopePlanner.plan(
            pages = listOf(page(0, blockCount = 0)),
            corpusFingerprint = "c".repeat(64),
            createdAtEpochMs = 1L,
        ).let(::rejected).reasons.single() shouldBe "no pending blocks"

        GlobalEnvelopePlanner.plan(
            pages = listOf(page(0), page(0)),
            corpusFingerprint = "c".repeat(64),
            createdAtEpochMs = 1L,
        ).let(::rejected).reasons.first() shouldContain "duplicate pageKey: p0"

        GlobalEnvelopePlanner.plan(
            pages = listOf(
                page(0).copy(blocks = listOf(EnvelopePlannerBlock("p1_b0", "x"))),
                page(1),
            ),
            corpusFingerprint = "c".repeat(64),
            createdAtEpochMs = 1L,
        ).let(::rejected).reasons.single() shouldBe "duplicate blockId: p1_b0"
    }

    @Test
    fun `textless pages contribute nothing and are excluded from the plan`() {
        val textless = page(1, blockCount = 0)
        val success = success(
            GlobalEnvelopePlanner.plan(
                pages = listOf(page(0, blockCount = 2), textless, page(2, blockCount = 2)),
                corpusFingerprint = "c".repeat(64),
                createdAtEpochMs = 1_757_050_000_000,
            ),
        )
        success.plan.envelopes.single().orderedPageKeys shouldBe listOf("p0", "p2")
    }

    @Test
    fun `envelope schema bound rejects plans beyond 4096 envelopes`() {
        val pages = (0 until 4097).map { page(it, blockCount = 1, chars = 4) }
        val result = GlobalEnvelopePlanner.plan(
            pages = pages,
            corpusFingerprint = "c".repeat(64),
            policy = EnvelopePlannerPolicy(maxBlocksPerEnvelope = 1, maxContributingPages = 1),
            createdAtEpochMs = 1_757_050_000_000,
        )
        rejected(result)
            .reasons.single() shouldBe "too many envelopes: 4097 > 4096"
    }

    @Test
    fun `plan input fingerprint is sensitive to corpus, policy and pending set`() {
        val base = success(
            GlobalEnvelopePlanner.plan(
                pages = (0 until 3).map { page(it) },
                corpusFingerprint = "c".repeat(64),
                createdAtEpochMs = 1_757_050_000_000,
            ),
        )
        val changedPolicy = success(
            GlobalEnvelopePlanner.plan(
                pages = (0 until 3).map { page(it) },
                corpusFingerprint = "c".repeat(64),
                policy = EnvelopePlannerPolicy(maxBlocksPerEnvelope = 31),
                createdAtEpochMs = 1_757_050_000_000,
            ),
        )
        val changedCorpus = success(
            GlobalEnvelopePlanner.plan(
                pages = (0 until 3).map { page(it) },
                corpusFingerprint = "d".repeat(64),
                createdAtEpochMs = 1_757_050_000_000,
            ),
        )
        val changedBlocks = success(
            GlobalEnvelopePlanner.plan(
                pages = (0 until 3).map { page(it, blockCount = 7) },
                corpusFingerprint = "c".repeat(64),
                createdAtEpochMs = 1_757_050_000_000,
            ),
        )
        val same = success(
            GlobalEnvelopePlanner.plan(
                pages = (2 downTo 0).map { page(it) },
                corpusFingerprint = "c".repeat(64),
                createdAtEpochMs = 42L,
            ),
        )

        val baseInput = base.plan.planInputFingerprint
        changedPolicy.plan.planInputFingerprint shouldNotBe baseInput
        changedCorpus.plan.planInputFingerprint shouldNotBe baseInput
        changedBlocks.plan.planInputFingerprint shouldNotBe baseInput
        same.plan.planInputFingerprint shouldBe baseInput
        // Operational fields are never fingerprint inputs (T924-FP-01).
        same.plan.planFingerprint shouldBe base.plan.planFingerprint
    }

    @Test
    fun `token estimator arithmetic`() {
        GlobalEnvelopePlanner.estimateSourceTokens("a".repeat(8)) shouldBe 10 // 2 + 8 framing
        GlobalEnvelopePlanner.estimateSourceTokens("a".repeat(9)) shouldBe 11 // ceil(9/4)=3 + 8
        GlobalEnvelopePlanner.estimateSourceTokens("") shouldBe 8
        GlobalEnvelopePlanner.estimateOutputTokens("a".repeat(9)) shouldBe 5 // ceil(9/2)
        GlobalEnvelopePlanner.estimateOutputTokens("") shouldBe 0
    }

    @Test
    fun `serialization budget formula and sanity bound`() {
        GlobalEnvelopePlanner.serializationBudgetBytes(1) shouldBe 256 * 1024
        GlobalEnvelopePlanner.serializationBudgetBytes(8) shouldBe 8 * 256 * 1024
        GlobalEnvelopePlanner.serializationBudgetBytes(0) shouldBe 256 * 1024

        val success = success(
            GlobalEnvelopePlanner.plan(
                pages = (0 until 5).map { page(it, blockCount = 6) },
                corpusFingerprint = "c".repeat(64),
                createdAtEpochMs = 1_757_050_000_000,
            ),
        )
        val budget = GlobalEnvelopePlanner.serializationBudgetBytes(
            success.plan.envelopes.sumOf { it.contributingPageCount },
        )
        (success.encodedSizeBytes > 0) shouldBe true
        (success.encodedSizeBytes <= budget) shouldBe true
    }

    @Test
    fun `produced plan passes its own schema validation`() {
        val success = success(
            GlobalEnvelopePlanner.plan(
                pages = (0 until 4).map { page(it) },
                corpusFingerprint = "c".repeat(64),
                createdAtEpochMs = 1_757_050_000_000,
            ),
        )
        success.plan.validationError() shouldBe null
        success.plan.envelopes.forEach { envelope -> envelope.validationError() shouldBe null }
        success.plan.plannerVersion shouldBe GlobalEnvelopePlanner.PLANNER_VERSION
    }

    @Test
    fun `permuted input order never changes the plan (100 seeded iterations)`() {
        val policy = EnvelopePlannerPolicy(maxBlocksPerEnvelope = 14)
        val base = (0 until 9).map { page(it, blockCount = it + 2, chars = 8 + it) }
        val expected = success(
            GlobalEnvelopePlanner.plan(base, "c".repeat(64), policy, createdAtEpochMs = 1_757_050_000_000),
        )
        var seed = 0x5EED_0003L
        repeat(100) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val shuffled = base.shuffled(java.util.Random(seed))
            val actual = success(
                GlobalEnvelopePlanner.plan(shuffled, "c".repeat(64), policy, createdAtEpochMs = 1_757_050_000_000),
            )
            // Operational timestamp fixed above so even the byte size must match.
            actual.plan shouldBe expected.plan
            actual.encodedSizeBytes shouldBe expected.encodedSizeBytes
        }
    }

    @Test
    fun `golden small-chapter fixture is byte-stable`() {
        val success = success(
            GlobalEnvelopePlanner.plan(
                pages = (0 until 5).map { index ->
                    page(
                        index,
                        blockCount = if (index == 4) 3 else 6,
                        sceneBoundaryBefore = index == 3,
                    )
                },
                corpusFingerprint = ("1a").repeat(32),
                policy = EnvelopePlannerPolicy(maxBlocksPerEnvelope = 12, maxContributingPages = 8),
                createdAtEpochMs = 1_757_050_000_000,
            ),
        )
        val goldenBytes = javaClass.getResourceAsStream(GOLDEN_RESOURCE)?.use { it.readBytes() }
            ?: error("missing golden fixture $GOLDEN_RESOURCE")
        val actual = eu.kanade.translation.artifact.ArtifactDocumentJson
            .encodeToString(EnvelopePlan.serializer(), success.plan)
        actual.toByteArray(Charsets.UTF_8) shouldBe goldenBytes
        success.plan.planFingerprint shouldBe GOLDEN_PLAN_FINGERPRINT
    }

    private companion object {
        const val GOLDEN_RESOURCE = "/t924/golden/envelope-plan-small.json"
        const val GOLDEN_PLAN_FINGERPRINT =
            "5643a00c7f98e158e61246c6ad7413f933ff1eaade91b3efa06f45e6b0339df8"
    }
}
