package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Guards [RevisionPlanner] — the pure Pass-2 planner (Checkpoint 2 §6).
 */
class RevisionPlannerTest {

    @Test
    fun `targets collected in stable reading order across pages`() {
        val pages = linkedMapOf(
            "p0" to page(flagged("a0"), flagged("a1")),
            "p1" to page(flagged("b0")),
            "p2" to page(flagged("c0"), flagged("c1")),
        )
        val plan = RevisionPlanner.plan(pages, emptyMap(), requestedOutputTokens = 4096)

        plan.allTargets.map { it.pageKey } shouldBe listOf("p0", "p0", "p1", "p2", "p2")
        plan.allTargets.map { it.blockIndex } shouldBe listOf(0, 1, 0, 0, 1)
    }

    @Test
    fun `only flagged non-user-edited blocks become targets`() {
        val pages = linkedMapOf(
            "p0" to page(
                flagged("flagged"),
                block("not-flagged", needsRevision = false),
                userEdited("user-edited"),
                block("  ", needsRevision = true), // blank text, not a target
            ),
        )
        val plan = RevisionPlanner.plan(pages, emptyMap(), 4096)

        plan.allTargets shouldHaveSize 1
        plan.allTargets[0].block.text shouldBe "flagged"
    }

    @Test
    fun `at most 20 targets per request group`() {
        // 50 flagged blocks across pages.
        val pages = linkedMapOf(
            "p0" to page(*Array(50) { flagged("t$it") }),
        )
        val plan = RevisionPlanner.plan(pages, emptyMap(), 8192)

        plan.groups.forEach { group ->
            (group.targets.size <= RevisionPlanner.MAX_TARGETS_PER_REQUEST) shouldBe true
        }
        // 50 targets / 20 cap = 3 groups (20, 20, 10).
        plan.groups shouldHaveSize 3
        plan.allTargets shouldHaveSize 50
        plan.overBudget.shouldBeEmpty()
    }

    @Test
    fun `token budget split happens before exceeding the cap`() {
        // Build blocks large enough that only ONE fits per group, forcing a split
        // before the budget is exceeded. The budget is derived from the planner's
        // own token accounting so the split count is deterministic regardless of
        // the tokenizer's per-CJK-char ratio.
        val med = "\u65e5".repeat(400)
        val pages = linkedMapOf(
            "p0" to page(flagged(med), flagged(med), flagged(med), flagged(med)),
        )
        // Budget = base + exactly one target's full prompt cost (so a single-block
        // group fits, but a two-block group overflows). Derived from the planner's
        // own estimates so it is robust to tokenizer internals; no nearby context
        // exists here because every block is a target.
        val base = RevisionPlanner.basePromptTokens(emptyMap())
        val oneTargetTokens = RevisionPlanner.estimateTargetTokens(
            RevisionPlanner.Target("p0", 0, flagged(med)),
        )
        val maxPromptTokens = base + oneTargetTokens

        val plan = RevisionPlanner.plan(
            pages, emptyMap(),
            requestedOutputTokens = 2048,
            maxPromptTokens = maxPromptTokens,
        )

        // 4 blocks, one per group (each group overflows once a 2nd is added).
        plan.groups shouldHaveSize 4
        // No group may exceed the prompt budget (the core split invariant).
        plan.groups.forEach { group ->
            (group.estimatedPromptTokens <= maxPromptTokens) shouldBe true
        }
        plan.overBudget.shouldBeEmpty()
        plan.allTargets shouldHaveSize 4
    }

    @Test
    fun `individually over-budget target is reported not truncated`() {
        val giant = "\u65e5".repeat(5000)
        val pages = linkedMapOf(
            "p0" to page(flagged(giant), flagged("small")),
        )
        val plan = RevisionPlanner.plan(
            pages, emptyMap(),
            requestedOutputTokens = 2048,
            maxPromptTokens = 2000, // the giant alone exceeds base+tokens
        )

        plan.overBudget shouldHaveSize 1
        plan.overBudget[0].target.block.text shouldBe giant
        plan.overBudget[0].maxPromptTokens shouldBe 2000
        // The small target still gets planned, not dropped.
        ("small" in plan.allTargets.map { it.block.text }) shouldBe true
    }

    @Test
    fun `chapter glossary is included in every request group`() {
        val glossary = mapOf("東京" to "Tokyo", "少年" to "boy")
        val pages = linkedMapOf("p0" to page(flagged("a"), flagged("b")))
        val plan = RevisionPlanner.plan(pages, glossary, 4096)

        plan.groups.forEach { group ->
            group.chapterGlossary shouldBe glossary
        }
    }

    @Test
    fun `nearby non-target dialogue is included as context`() {
        val pages = linkedMapOf(
            "p0" to page(
                flagged("target"),
                block("nearby source", draft = "nearby draft", needsRevision = false),
            ),
        )
        val plan = RevisionPlanner.plan(pages, emptyMap(), 4096)

        val group = plan.groups.single()
        group.nearbyContext shouldHaveSize 1
        group.nearbyContext[0].source shouldBe "nearby source"
        group.nearbyContext[0].draft shouldBe "nearby draft"
        // The nearby line must NOT be an output target.
        group.targets.map { it.block.text } shouldBe listOf("target")
    }

    @Test
    fun `output targets contain only flagged ids`() {
        val pages = linkedMapOf(
            "p0" to page(flagged("flagged"), block("plain", needsRevision = false)),
        )
        val plan = RevisionPlanner.plan(pages, emptyMap(), 4096)

        plan.groups.single().targets.forEach { target ->
            (target.block.needsRevision) shouldBe true
            (target.block.userEditedAt == null) shouldBe true
        }
    }

    @Test
    fun `empty chapter yields no groups`() {
        val plan = RevisionPlanner.plan(linkedMapOf("p0" to page()), emptyMap(), 4096)
        plan.groups.shouldBeEmpty()
        plan.allTargets.shouldBeEmpty()
    }

    // ---- helpers ----

    private fun page(vararg blocks: TranslationBlock): PageTranslation =
        PageTranslation(blocks = blocks.toMutableList())

    private fun block(text: String, draft: String = "", needsRevision: Boolean = false): TranslationBlock =
        TranslationBlock(
            text = text,
            translation = draft,
            width = 10f, height = 10f, x = 0f, y = 0f,
            symHeight = 1f, symWidth = 1f, angle = 0f,
            needsRevision = needsRevision,
        )

    private fun flagged(text: String, draft: String = "draft"): TranslationBlock =
        block(text, draft, needsRevision = true)

    private fun userEdited(text: String): TranslationBlock =
        TranslationBlock(
            text = text,
            translation = "edited",
            width = 10f, height = 10f, x = 0f, y = 0f,
            symHeight = 1f, symWidth = 1f, angle = 0f,
            needsRevision = true,
            userEditedAt = 123L,
        )
}
