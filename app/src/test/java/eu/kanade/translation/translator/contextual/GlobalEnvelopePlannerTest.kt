package eu.kanade.translation.translator.contextual

import eu.kanade.translation.artifact.StageFingerprints
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class GlobalEnvelopePlannerTest {

    private fun block(pageIndex: Int, blockIndex: Int, chars: Int = 12) = EnvelopePlannerBlock(
        stableBlockId = "p${pageIndex}_b$blockIndex",
        sourceText = "あ".repeat(chars),
    )

    private fun page(
        pageIndex: Int,
        blockCount: Int = 4,
        chars: Int = 12,
        sceneBoundaryBefore: Boolean = false,
    ) = EnvelopePlannerPage(
        pageKey = "p$pageIndex",
        naturalPageIndex = pageIndex,
        contentFingerprint = StageFingerprints.canonicalFingerprint(listOf("page-content", pageIndex)),
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
    fun `default policy aligns with 8k constraints`() {
        val policy = EnvelopePlannerPolicy()
        policy.maxBlocksPerEnvelope shouldBe 12
        policy.maxContributingPages shouldBe 3
        policy.maxEstimatedInputTokens shouldBe 4_096
        policy.maxEstimatedOutputTokens shouldBe 3_584
        policy.validationError() shouldBe null

        (policy.maxEstimatedInputTokens + policy.maxEstimatedOutputTokens + 512) shouldBe 8_192
    }

    @Test
    fun `block budget closes envelope at twelve blocks default`() {
        // 3 pages with 6 blocks each: p0 (6) + p1 (6) = 12 blocks, p2 (6) starts next envelope
        val pages = (0 until 3).map { page(it, blockCount = 6) }
        val result = success(
            GlobalEnvelopePlanner.plan(
                pages = pages,
                corpusFingerprint = "c".repeat(64),
                createdAtEpochMs = 1_757_050_000_000,
            ),
        )
        result.plan.envelopes.map { it.orderedPageKeys } shouldBe listOf(listOf("p0", "p1"), listOf("p2"))
        result.plan.envelopes.map { it.structuralBlockCount } shouldBe listOf(12, 6)
    }

    @Test
    fun `contributing page budget closes envelope at three pages default`() {
        // 5 pages with 1 block each: p0, p1, p2 fit in envelope 0; p3, p4 in envelope 1
        val pages = (0 until 5).map { page(it, blockCount = 1) }
        val result = success(
            GlobalEnvelopePlanner.plan(
                pages = pages,
                corpusFingerprint = "c".repeat(64),
                createdAtEpochMs = 1_757_050_000_000,
            ),
        )
        result.plan.envelopes.map { it.orderedPageKeys } shouldBe listOf(listOf("p0", "p1", "p2"), listOf("p3", "p4"))
        result.plan.envelopes.map { it.contributingPageCount } shouldBe listOf(3, 2)
    }

    @Test
    fun `oversized page beyond twelve blocks is rejected under default policy`() {
        val tooManyBlocks = GlobalEnvelopePlanner.plan(
            pages = listOf(page(0, blockCount = 13)),
            corpusFingerprint = "c".repeat(64),
            createdAtEpochMs = 1_757_050_000_000,
        )
        val rej = rejected(tooManyBlocks)
        rej.reasons.single() shouldContain "page p0 oversized: 13 blocks > 12"
    }

    @Test
    fun `oversized page beyond 4096 input tokens is rejected under default policy`() {
        val tooManyTokens = GlobalEnvelopePlanner.plan(
            pages = listOf(page(0, blockCount = 1, chars = 20_000)),
            corpusFingerprint = "c".repeat(64),
            createdAtEpochMs = 1_757_050_000_000,
        )
        val rej = rejected(tooManyTokens)
        rej.reasons.first() shouldContain "input tokens > 4096"
    }
}
