package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.RevisionScope

/**
 * TachiyomiAT: pure revision planner. Builds bounded Pass-2 request groups from
 * the chapter's flagged blocks WITHOUT touching OCR/inpaint/store, so the
 * schedule is unit-testable.
 *
 * Invariants (Checkpoint 2 §6):
 *  - Stable reading order: targets are emitted in (page order, block order).
 *  - At most [MAX_TARGETS_PER_REQUEST] output targets per request.
 *  - Strict existing token budget: a request's estimated prompt tokens never
 *    exceed [maxPromptTokens]; the planner splits BEFORE exceeding the budget.
 *  - An individually over-budget target (its own prompt exceeds the cap) is
 *    reported as [OverBudgetTarget] rather than silently truncated or dropped.
 *  - Output targets are ONLY flagged blocks (`needsRevision && userEditedAt ==
 *    null && text.isNotBlank()`); glossary/nearby dialogue are context-only.
 */
object RevisionPlanner {

    /** Hard cap on output targets per single Pass-2 request. */
    const val MAX_TARGETS_PER_REQUEST = 20

    data class Target(
        val pageKey: String,
        val blockIndex: Int,
        val block: TranslationBlock,
    )

    /** A flagged block whose own prompt exceeds the per-request budget. */
    data class OverBudgetTarget(
        val target: Target,
        val estimatedTokens: Int,
        val maxPromptTokens: Int,
    )

    data class RequestGroup(
        /** Flagged targets the provider must correct, in reading order. */
        val targets: List<Target>,
        /**
         * Source/draft dialogue for nearby (non-target) blocks, included as
         * context only. Never appears in the output id set.
         */
        val nearbyContext: List<DialogueLine>,
        val chapterGlossary: Map<String, String>,
        val estimatedPromptTokens: Int,
        val maxOutputTokens: Int,
    )

    /** A source/draft pair for context (not a revision target). */
    data class DialogueLine(
        val pageKey: String,
        val source: String,
        val draft: String,
    )

    data class Plan(
        val groups: List<RequestGroup>,
        val overBudget: List<OverBudgetTarget>,
        /** All flagged targets the planner considered, in reading order. */
        val allTargets: List<Target>,
    )

    /**
     * @param orderedPages chapter pages in reading order (pageKey -> page).
     * @param chapterGlossary stable term renderings to include as context.
     * @param requestedOutputTokens per-request output token cap from prefs.
     * @param maxPromptTokens hard prompt budget (context + targets). Defaults to
     *   the DEFAULT profile's usable prompt budget.
     * @param maxNearbyContextLines cap on nearby dialogue lines included as
     *   context per request (bounds prompt size on dense pages).
     */
    fun plan(
        orderedPages: LinkedHashMap<String, PageTranslation>,
        chapterGlossary: Map<String, String>,
        requestedOutputTokens: Int,
        maxPromptTokens: Int = defaultMaxPromptTokens(),
        maxNearbyContextLines: Int = DEFAULT_NEARBY_CONTEXT_LINES,
        scope: RevisionScope = RevisionScope.FLAGGED,
    ): Plan {
        val targets = collectTargets(orderedPages, scope)
        if (targets.isEmpty()) return Plan(emptyList(), emptyList(), emptyList())

        // Every revision target in the chapter is an OUTPUT id somewhere; none of
        // them may appear as mere "nearby context" (that would double-count a
        // block the provider is also asked to correct). Precompute the full set.
        val allTargetKeys = targets.map { it.pageKey to it.blockIndex }.toHashSet()

        val groups = mutableListOf<RequestGroup>()
        val overBudget = mutableListOf<OverBudgetTarget>()
        val glossaryTokens = estimateGlossaryTokens(chapterGlossary)

        val current = mutableListOf<Target>()
        var currentTokens = basePromptTokens(glossaryTokens)
        val maxPerRequest = MAX_TARGETS_PER_REQUEST

        fun targetTokens(t: Target): Int = estimateTargetTokens(t)

        /** Nearby-context tokens for the current group, recomputed as it grows. */
        fun currentNearbyTokens(): Int =
            collectNearbyContext(orderedPages, current, allTargetKeys, maxNearbyContextLines)
                .sumOf { estimateTokens(it.source) + estimateTokens(it.draft) }

        fun flush() {
            if (current.isEmpty()) return
            val nearby = collectNearbyContext(orderedPages, current, allTargetKeys, maxNearbyContextLines)
            val nearbyTokens = nearby.sumOf { estimateTokens(it.source) + estimateTokens(it.draft) }
            groups += RequestGroup(
                targets = current.toList(),
                nearbyContext = nearby,
                chapterGlossary = chapterGlossary,
                // Includes targets + base + glossary + nearby, matching the cap
                // check so a flushed group can never report more than the budget.
                estimatedPromptTokens = currentTokens + nearbyTokens,
                maxOutputTokens = requestedOutputTokens,
            )
            current.clear()
            currentTokens = basePromptTokens(glossaryTokens)
        }

        for (target in targets) {
            val tTokens = targetTokens(target)
            // Individually over-budget: the target's OWN prompt (base + this
            // target, no nearby) already exceeds the cap. Report it rather than
            // truncating or falling back. Nearby context is a group-level concern
            // accounted for in the wouldExceed recheck below.
            if (basePromptTokens(glossaryTokens) + tTokens > maxPromptTokens) {
                overBudget += OverBudgetTarget(target, tTokens, maxPromptTokens)
                continue
            }
            // Flush BEFORE adding when the current group is already at capacity
            // (20 targets) so no group ever exceeds the cap.
            if (current.size >= maxPerRequest) {
                flush()
            }
            // Tentatively add, then verify the FULL group (targets + nearby)
            // stays in budget. If it overflows and the group has more than one
            // target, split: flush the previous targets and start fresh with
            // this one. Guarantees no flushed group exceeds budget.
            current += target
            currentTokens += tTokens
            val fullGroup = currentTokens + currentNearbyTokens()
            if (fullGroup > maxPromptTokens && current.size > 1) {
                current.removeAt(current.lastIndex)
                currentTokens -= tTokens
                flush()
                current += target
                currentTokens += tTokens
            }
        }
        flush()

        return Plan(groups = groups, overBudget = overBudget, allTargets = targets)
    }

    /** Collect flagged targets in stable reading order. */
    private fun collectTargets(orderedPages: LinkedHashMap<String, PageTranslation>, scope: RevisionScope): List<Target> {
        val out = mutableListOf<Target>()
        for ((pageKey, page) in orderedPages) {
            for ((blockIndex, block) in page.blocks.withIndex()) {
                if (!isRevisionTarget(block, scope)) continue
                out += Target(pageKey, blockIndex, block)
            }
        }
        return out
    }

    /**
     * Gather nearby non-target source/draft dialogue as context. Includes blocks
     * on the same page(s) as the request's targets that are NOT themselves any
     * chapter revision target ([allTargetKeys]), bounded by [maxLines]. Only
     * dialogue (non-blank text) is included; watermarks/empty are skipped.
     *
     * [allTargetKeys] is the full set of chapter targets (not just this group's),
     * so a block that is a revision target in another group is never surfaced as
     * mere context here.
     */
    private fun collectNearbyContext(
        orderedPages: LinkedHashMap<String, PageTranslation>,
        targets: List<Target>,
        allTargetKeys: Set<Pair<String, Int>>,
        maxLines: Int,
    ): List<DialogueLine> {
        if (maxLines <= 0) return emptyList()
        val pagesInGroup = targets.map { it.pageKey }.distinct()
        val out = mutableListOf<DialogueLine>()
        for (pageKey in pagesInGroup) {
            val page = orderedPages[pageKey] ?: continue
            for ((blockIndex, block) in page.blocks.withIndex()) {
                if ((pageKey to blockIndex) in allTargetKeys) continue
                val src = block.text.trim()
                if (src.isBlank()) continue
                out += DialogueLine(pageKey, src, block.translation.trim())
                if (out.size >= maxLines) return out
            }
        }
        return out
    }

    private fun isRevisionTarget(block: TranslationBlock, scope: RevisionScope): Boolean =
        block.userEditedAt == null && block.text.isNotBlank() && block.translation.isNotBlank() &&
            (scope == RevisionScope.ALL_TRANSLATED || block.needsRevision)

    /**
     * Per-target prompt token cost: anchored id overhead + "Source: ... | Draft:"
     * framing + source + draft text. Public so callers (and tests) can derive a
     * budget from the planner's own accounting rather than guessing tokenizer
     * ratios.
     */
    fun estimateTargetTokens(t: Target): Int {
        // Anchored id overhead + "ID|Source: ... | Draft: ..." framing.
        val idOverhead = 8
        val framingOverhead = estimateTokens("Source: ") + estimateTokens(" | Draft: ")
        return idOverhead + framingOverhead + estimateTokens(t.block.text) + estimateTokens(t.block.translation)
    }

    /**
     * Base prompt tokens shared by every request group (prompt template overhead
     * + glossary). Public so budgets can be derived consistently.
     */
    fun basePromptTokens(chapterGlossary: Map<String, String>): Int =
        TranslationContextChunkPlanner.PROMPT_OVERHEAD_TOKENS + estimateGlossaryTokens(chapterGlossary)

    private fun estimateGlossaryTokens(glossary: Map<String, String>): Int {
        if (glossary.isEmpty()) return 0
        return glossary.entries.sumOf { (k, v) -> estimateTokens(k) + estimateTokens(v) + 4 }
    }

    private fun basePromptTokens(glossaryTokens: Int): Int =
        TranslationContextChunkPlanner.PROMPT_OVERHEAD_TOKENS + glossaryTokens

    private fun estimateTokens(text: String): Int = TranslationContextChunkPlanner.estimateTokens(text)

    fun defaultMaxPromptTokens(): Int =
        TranslationContextChunkPlanner.MAX_CONTEXT_TOKENS -
            TranslationContextChunkPlanner.SAFETY_MARGIN -
            TranslationContextChunkPlanner.MIN_OUTPUT_TOKENS

    const val DEFAULT_NEARBY_CONTEXT_LINES: Int = 16
}
