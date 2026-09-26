package eu.kanade.translation.engines.translator.contextual

import eu.kanade.translation.persistence.artifact.ArtifactDocumentJson
import eu.kanade.translation.persistence.artifact.EnvelopePlan
import eu.kanade.translation.persistence.artifact.EnvelopePlanInputPage
import eu.kanade.translation.persistence.artifact.PlannedEnvelope
import eu.kanade.translation.persistence.artifact.StageFingerprints

/**
 *  WP3 (pure planner, S4): global multi-budget whole-page envelope
 * planning (design §8, schemas contract §1.5).
 *
 * Deterministic, native-free and provider-free. Operates on lightweight
 * per-page OCR planning data and produces the durable [EnvelopePlan] before
 * any provider translation. Core rules:
 *
 * - gap-free ordered coverage: every pending block appears exactly once
 *   chapter-wide, in page order then reading order (ordering total);
 * - page atomicity: a page's blocks are never split across envelopes
 *   (product invariant);
 * - budgets: structural caps of [EnvelopePlannerPolicy.maxBlocksPerEnvelope]
 *   blocks and [EnvelopePlannerPolicy.maxContributingPages] contributing
 *   pages per envelope (design §8 "approximately 32 blocks and 8 contributing
 *   pages as a starting experiment"), with token checks stricter;
 * - scene preference: a frozen-profile scene start closes the current
 *   envelope when preferred (design §8: scene boundary is a preference, not
 *   a correctness fence);
 * - a single page that alone exceeds any budget is REJECTED whole — page
 *   atomicity is invariant, so the page can never be split to fit;
 * - deterministic tie-breaks: pages in proven natural order (else sorted
 *   pageKey), blocks in reading order — input iteration order never matters.
 *
 * No IO, no coroutines; serialization is touched only to compute the content
 * fingerprint  and the bounded-size sanity check.
 */

/**
 * Envelope policy constants (design §8). All numbers are MEASURED-EXPERIMENT
 * constants (PROPOSED-GATE), never product constants and never feature flags
 * ( split; invalidation matrix row 7: envelope-policy-only changes
 * must not invalidate compatible translations).
 */
data class EnvelopePlannerPolicy(
    /** Structural block budget per envelope (design §8 experiment: 12). */
    val maxBlocksPerEnvelope: Int = 12,
    /** Contributing-page budget per envelope (design §8 experiment: 3). */
    val maxContributingPages: Int = 3,
    /** Estimated source-token input budget (always stricter than context ceiling). */
    val maxEstimatedInputTokens: Int = 4_096,
    /**
     * Estimated output-token reserve (framing + target expansion). Together
     * with [maxEstimatedInputTokens] it sums to the default 8k window minus
     * the safety margin, so envelopes pack adaptively to what one response
     * can carry. Estimator v2's honest per-block output makes this ceiling
     * split text-heavy groups at PLAN time instead of shipping a request
     * whose response cannot fit (device fix 2026-09-17).
     */
    val maxEstimatedOutputTokens: Int = 3_584,
    /**
     * Prefer closing the envelope at a frozen-profile scene start
     * (`page.sceneBoundaryBefore`). Never a correctness fence.
     */
    val preferSceneBreaks: Boolean = true,
) {
    fun validationError(): String? = when {
        maxBlocksPerEnvelope < 1 -> "maxBlocksPerEnvelope must be positive"
        maxContributingPages < 1 -> "maxContributingPages must be positive"
        maxEstimatedInputTokens < 1 -> "maxEstimatedInputTokens must be positive"
        maxEstimatedOutputTokens < 1 -> "maxEstimatedOutputTokens must be positive"
        else -> null
    }

    /** Stable policy identity for [EnvelopePlan.planInputFingerprint]. */
    fun policyFingerprint(): String = StageFingerprints.envelopePolicyFingerprint(
        maxBlocksPerEnvelope = maxBlocksPerEnvelope,
        maxContributingPages = maxContributingPages,
        maxEstimatedInputTokens = maxEstimatedInputTokens,
        maxEstimatedOutputTokens = maxEstimatedOutputTokens,
        preferSceneBreaks = preferSceneBreaks,
    )
}

/** Pure per-block planning input. */
data class EnvelopePlannerBlock(
    /** Stable OCR block id (`p<N>_b<M>`, [StableBlockIds] scheme). */
    val stableBlockId: String,
    /** Persisted source text (used only through the pure token estimator). */
    val sourceText: String,
)

/** Pure per-page planning input. */
data class EnvelopePlannerPage(
    val pageKey: String,
    /** Provable natural index, or null (never-guess rule); nulls order last. */
    val naturalPageIndex: Int?,
    /**
     * The page's semantic content fingerprint
     * ([eu.kanade.translation.persistence.artifact.StageFingerprints.pageOcrContentFingerprint],
     * 02); feeds the per-envelope contributing corpus fingerprint.
     */
    val contentFingerprint: String,
    /** Ordered (reading-order) blocks of this page; never reordered by the planner. */
    val blocks: List<EnvelopePlannerBlock>,
    /** A frozen-profile scene starts at this page (scene preference input). */
    val sceneBoundaryBefore: Boolean = false,
)

/** Result of pure envelope planning. */
sealed class EnvelopePlanResult {
    data class Success(
        val plan: EnvelopePlan,
        /** UTF-8 byte size of the canonical serialization of [plan]. */
        val encodedSizeBytes: Int,
    ) : EnvelopePlanResult()

    data class Rejected(val reasons: List<String>) : EnvelopePlanResult() {
        constructor(reason: String) : this(listOf(reason))
    }
}

object GlobalEnvelopePlanner {

    /** Pure-planner algorithm version ( §1.5 `plannerVersion`). */
    const val PLANNER_VERSION = 2

    /**
     * Serialization sanity cap per contributing page (UTF-8 bytes). The plan
     * document of an N-contributing-page chapter must stay within
     * [SERIALIZATION_CAP_BYTES_PER_PAGE] × N; a pure in-memory check, never IO.
     */
    const val SERIALIZATION_CAP_BYTES_PER_PAGE = 256 * 1024

    // Token estimator v2 (device-calibrated 2026-09-17, deterministic):
    // CJK-heavy OCR source ≈ 4 chars/token on INPUT; per-block request
    // framing overhead. v1 estimated output at ceil(len/2) with no framing
    // floor, capping a 64-block envelope at ~345 text tokens — the requested
    // maxOutput could not carry its own JSON reserve plus the translations
    // and the model truncated mid-JSON on every attempt (device cascade:
    // "ambiguous (protocol); response discarded"). Latin-script targets
    // expand to roughly one output token per source char, and each block
    // re-emits framing; budget both.
    private const val CHARS_PER_TOKEN = 4
    private const val BLOCK_FRAMING_TOKENS = 8
    private const val OUTPUT_BLOCK_FRAMING_TOKENS = 6
    private const val OUTPUT_MIN_BLOCK_TOKENS = 4
    private const val OUTPUT_CHARS_PER_TOKEN = 1

    /** Estimated provider input tokens for one block (pure, versioned by planner). */
    fun estimateSourceTokens(sourceText: String): Int =
        (sourceText.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN + BLOCK_FRAMING_TOKENS

    /** Estimated provider output tokens for one block (pure, versioned by planner). */
    fun estimateOutputTokens(sourceText: String): Int =
        if (sourceText.isEmpty()) {
            0
        } else {
            OUTPUT_BLOCK_FRAMING_TOKENS +
                maxOf(
                    OUTPUT_MIN_BLOCK_TOKENS,
                    (sourceText.length + OUTPUT_CHARS_PER_TOKEN - 1) / OUTPUT_CHARS_PER_TOKEN,
                )
        }

    /** In-memory serialization budget for a plan over [contributingPageCount] pages. */
    fun serializationBudgetBytes(contributingPageCount: Int): Int =
        SERIALIZATION_CAP_BYTES_PER_PAGE * contributingPageCount.coerceAtLeast(1)

    /**
     * Plan whole-page envelopes over the pending OCR corpus.
     *
     * @param pages pending pages with their OCR planning payloads; any input
     *   order is accepted (canonicalized internally).
     * @param corpusFingerprint whole-corpus identity
     *   ( `OcrCorpusFingerprint`); a [EnvelopePlan.planInputFingerprint] input.
     * @param policy measured experiment constants (never flags).
     * @param createdAtEpochMs operational timestamp; never a fingerprint input
     *
     */
    fun plan(
        pages: List<EnvelopePlannerPage>,
        corpusFingerprint: String,
        policy: EnvelopePlannerPolicy = EnvelopePlannerPolicy(),
        createdAtEpochMs: Long,
    ): EnvelopePlanResult {
        policy.validationError()?.let { return EnvelopePlanResult.Rejected("policy: $it") }

        // Canonical page order: natural index when provable, else sorted pageKey.
        val ordered = pages.sortedWith(
            compareBy<EnvelopePlannerPage> { it.naturalPageIndex ?: Int.MAX_VALUE }
                .thenBy { it.pageKey },
        )
        // Zero-block (textless) pages carry nothing translatable and are
        // excluded from envelopes; they remain part of the corpus manifest.
        val plannable = ordered.filter { it.blocks.isNotEmpty() }

        val reasons = mutableListOf<String>()
        ordered
            .groupBy { it.pageKey }
            .filterValues { it.size > 1 }
            .keys
            .sorted()
            .forEach { reasons += "duplicate pageKey: $it" }
        val seenBlocks = HashSet<String>()
        for (page in plannable) {
            for (block in page.blocks) {
                if (block.stableBlockId.isBlank()) {
                    reasons += "blank blockId on ${page.pageKey}"
                } else if (!seenBlocks.add(block.stableBlockId)) {
                    reasons += "duplicate blockId: ${block.stableBlockId}"
                }
            }
        }
        if (seenBlocks.isEmpty()) {
            reasons += "no pending blocks"
        }
        if (reasons.isNotEmpty()) return EnvelopePlanResult.Rejected(reasons)

        // Per-page budgets (page atomicity: an oversized page can never fit).
        for (page in plannable) {
            val pageInput = page.blocks.sumOf { estimateSourceTokens(it.sourceText) }
            val pageOutput = page.blocks.sumOf { estimateOutputTokens(it.sourceText) }
            if (page.blocks.size > policy.maxBlocksPerEnvelope) {
                reasons += "page ${page.pageKey} oversized: ${page.blocks.size} blocks > ${policy.maxBlocksPerEnvelope}"
            }
            if (pageInput > policy.maxEstimatedInputTokens) {
                reasons += "page ${page.pageKey} oversized: $pageInput input tokens > ${policy.maxEstimatedInputTokens}"
            }
            if (pageOutput > policy.maxEstimatedOutputTokens) {
                reasons += "page ${page.pageKey} oversized: $pageOutput output tokens > ${policy.maxEstimatedOutputTokens}"
            }
        }
        if (reasons.isNotEmpty()) return EnvelopePlanResult.Rejected(reasons)

        // Greedy whole-page accumulation with scene preference.
        val groups = mutableListOf<List<EnvelopePlannerPage>>()
        var current = Accumulator()
        for (page in plannable) {
            val pageInput = page.blocks.sumOf { estimateSourceTokens(it.sourceText) }
            val pageOutput = page.blocks.sumOf { estimateOutputTokens(it.sourceText) }
            val sceneBreak = policy.preferSceneBreaks &&
                page.sceneBoundaryBefore &&
                current.pages.isNotEmpty()
            val fits = current.pages.isEmpty() ||
                (
                    current.pages.size + 1 <= policy.maxContributingPages &&
                        current.blocks + page.blocks.size <= policy.maxBlocksPerEnvelope &&
                        current.inputTokens + pageInput <= policy.maxEstimatedInputTokens &&
                        current.outputTokens + pageOutput <= policy.maxEstimatedOutputTokens
                    )
            if (sceneBreak || !fits) {
                groups += current.pages.toList()
                current = Accumulator()
            }
            current.add(page)
        }
        if (current.pages.isNotEmpty()) groups += current.pages.toList()

        if (groups.size > EnvelopePlan.MAX_ENVELOPES) {
            return EnvelopePlanResult.Rejected(
                "too many envelopes: ${groups.size} > ${EnvelopePlan.MAX_ENVELOPES}",
            )
        }

        val envelopes = groups.mapIndexed { ordinal, group ->
            val contributingFingerprint = StageFingerprints.envelopeContributingCorpusFingerprint(
                group.map { page -> page.pageKey to page.contentFingerprint },
            )
            val blockIds = group.flatMap { page -> page.blocks.map { it.stableBlockId } }
            val crossesScene = group.drop(1).any { it.sceneBoundaryBefore }
            PlannedEnvelope(
                envelopeId = "e-$ordinal",
                orderedPageKeys = group.map { it.pageKey },
                blockIds = blockIds,
                contributingCorpusFingerprint = contributingFingerprint,
                estimatedInputTokens = group.sumOf { page ->
                    page.blocks.sumOf { estimateSourceTokens(it.sourceText) }
                },
                estimatedOutputTokens = group.sumOf { page ->
                    page.blocks.sumOf { estimateOutputTokens(it.sourceText) }
                },
                structuralBlockCount = blockIds.size,
                contributingPageCount = group.size,
                sceneRefs = emptyList(),
                profileSubsetRefs = emptyList(),
                crossesSceneBoundary = crossesScene,
            )
        }

        val plan = EnvelopePlan(
            planFingerprint = "",
            planInputFingerprint = planInputFingerprint(ordered, corpusFingerprint, policy),
            plannerVersion = PLANNER_VERSION,
            envelopes = envelopes,
            createdAtEpochMs = createdAtEpochMs,
        )
        // 10: content fingerprint = SHA-256 over canonical re-encoded
        // JSON of the DTO with operational fields excluded (createdAtEpochMs
        // zeroed; planFingerprint blanked — a value cannot contain its own hash).
        val hashingView = plan.copy(planFingerprint = "", createdAtEpochMs = 0L)
        val canonical = ArtifactDocumentJson.encodeToString(EnvelopePlan.serializer(), hashingView)
        val planFingerprint = StageFingerprints.envelopePlanContentFingerprint(canonical)
        val finished = plan.copy(planFingerprint = planFingerprint)

        // Independent re-verification before returning success.
        finished.validationError()?.let { return EnvelopePlanResult.Rejected("plan validation: $it") }
        verifyCoverage(finished, plannable)?.let { return EnvelopePlanResult.Rejected(it) }
        verifyBudgets(finished, policy)?.let { return EnvelopePlanResult.Rejected(it) }

        val encodedSizeBytes = ArtifactDocumentJson
            .encodeToString(EnvelopePlan.serializer(), finished)
            .encodeToByteArray()
            .size
        val budget = serializationBudgetBytes(finished.envelopes.sumOf { it.contributingPageCount })
        if (encodedSizeBytes > budget) {
            return EnvelopePlanResult.Rejected(
                "serialized plan $encodedSizeBytes bytes exceeds budget $budget",
            )
        }
        return EnvelopePlanResult.Success(finished, encodedSizeBytes)
    }

    /**
     * Independent coverage check: every pending block appears exactly once
     * chapter-wide, and the envelope order is the canonical page order with
     * reading order preserved inside each envelope (ordering total).
     */
    private fun verifyCoverage(plan: EnvelopePlan, ordered: List<EnvelopePlannerPage>): List<String>? {
        val expectedBlocks = ordered.flatMap { page -> page.blocks.map { it.stableBlockId } }
        val actualBlocks = plan.envelopes.flatMap { it.blockIds }
        if (actualBlocks.size != expectedBlocks.size) {
            return listOf("coverage: ${actualBlocks.size} planned blocks != ${expectedBlocks.size} pending")
        }
        val counts = actualBlocks.groupingBy { it }.eachCount()
        val duplicated = counts.filterValues { it > 1 }
        if (duplicated.isNotEmpty()) {
            return listOf("coverage: block planned more than once: ${duplicated.keys.first()}")
        }
        val missing = expectedBlocks.filterNot { blockId -> counts.containsKey(blockId) }
        if (missing.isNotEmpty()) {
            return listOf("coverage: pending block not planned: ${missing.first()}")
        }
        if (actualBlocks != expectedBlocks) {
            return listOf("coverage: planned block order is not the canonical page/reading order")
        }
        val plannedPageOrder = plan.envelopes.flatMap { it.orderedPageKeys }
        val expectedPageOrder = ordered.map { it.pageKey }
        if (plannedPageOrder != expectedPageOrder) {
            return listOf("coverage: envelope page order is not the canonical order")
        }
        return null
    }

    /** Independent budget re-check on the produced envelopes. */
    private fun verifyBudgets(plan: EnvelopePlan, policy: EnvelopePlannerPolicy): List<String>? {
        plan.envelopes.forEach { envelope ->
            if (envelope.structuralBlockCount > policy.maxBlocksPerEnvelope) {
                return listOf("budget: ${envelope.envelopeId} blocks ${envelope.structuralBlockCount}")
            }
            if (envelope.contributingPageCount > policy.maxContributingPages) {
                return listOf("budget: ${envelope.envelopeId} pages ${envelope.contributingPageCount}")
            }
            if (envelope.estimatedInputTokens > policy.maxEstimatedInputTokens) {
                return listOf("budget: ${envelope.envelopeId} input tokens ${envelope.estimatedInputTokens}")
            }
            if (envelope.estimatedOutputTokens > policy.maxEstimatedOutputTokens) {
                return listOf("budget: ${envelope.envelopeId} output tokens ${envelope.estimatedOutputTokens}")
            }
        }
        return null
    }

    /**
     * 08 composite input fingerprint: corpus slice + pending-block
     * set + envelope policy. Encoding lives in the single consolidated
     * [StageFingerprints] core (wave-2 review F2 — the former planner-local
     * `PlannerFingerprints` hasher was byte-identical and is retired).
     */
    private fun planInputFingerprint(
        ordered: List<EnvelopePlannerPage>,
        corpusFingerprint: String,
        policy: EnvelopePlannerPolicy,
    ): String = StageFingerprints.envelopePlanInputFingerprint(
        corpusFingerprint = corpusFingerprint,
        plannerVersion = PLANNER_VERSION,
        policyFingerprint = policy.policyFingerprint(),
        pages = ordered.filter { it.blocks.isNotEmpty() }.map { page ->
            EnvelopePlanInputPage(
                pageKey = page.pageKey,
                orderedStableBlockIds = page.blocks.map { it.stableBlockId },
            )
        },
    )
}

/** Mutable accumulator for one in-progress envelope (pure planning state). */
private class Accumulator {
    val pages = mutableListOf<EnvelopePlannerPage>()
    var blocks: Int = 0
        private set
    var inputTokens: Int = 0
        private set
    var outputTokens: Int = 0
        private set

    fun add(page: EnvelopePlannerPage) {
        pages.add(page)
        blocks += page.blocks.size
        inputTokens += page.blocks.sumOf { GlobalEnvelopePlanner.estimateSourceTokens(it.sourceText) }
        outputTokens += page.blocks.sumOf { GlobalEnvelopePlanner.estimateOutputTokens(it.sourceText) }
    }
}
