package eu.kanade.translation.translator.contextual

import eu.kanade.translation.artifact.AnalysisChunkResult
import eu.kanade.translation.artifact.StageFingerprints

/**
 *  WP3 (pure planner, S4): deterministic hierarchical analysis chunking
 * over the OCR corpus (design §6.1, schemas contract §1.3).
 *
 * Whole-page windows only — a page is never split across chunks (page
 * atomicity invariant). Every chunk carries `core` pages (primary extraction
 * duty) plus up to [AnalysisChunkPolicy.overlapPages] immediately preceding
 * `context` overlap pages (citation-only evidence, /V9 duty split).
 *
 * This planner is the pure windowing skeleton ONLY: chunk ids, contributing
 * sets, contributing corpus fingerprints and the evidence-universe predicate.
 * Response parsing, excerpt-hash validation (V8) and chunk persistence belong
 * to the analysis stage (WP5); the planner merely defines the universe every
 * evidence reference must resolve into (pure subset of V1/V9).
 */

/** Measured/planning constants for analysis chunking ( split: policy, not schema). */
data class AnalysisChunkPolicy(
    /**
     * Maximum core pages per chunk. Hard schema bound:
     * [AnalysisChunkResult.MAX_CORE_PAGES] (16, tunable T).
     */
    val maxCorePages: Int = AnalysisChunkResult.MAX_CORE_PAGES,
    /**
     * Context-overlap pages attached after the first chunk (design §6.1:
     * "initially up to one adjacent contributing page"). Hard schema bound:
     * [AnalysisChunkResult.MAX_OVERLAP_PAGES] (2, tunable T). `0` disables
     * overlap entirely.
     */
    val overlapPages: Int = 1,
    /**
     * Maximum blocks across the contributing set of one chunk (design §6.1:
     * "Cap input OCR tokens, contributing pages, blocks"). PROPOSED-GATE
     * experiment constant, not a product constant.
     */
    val maxBlocksPerChunk: Int = 512,
    /**
     * Maximum estimated input tokens across the contributing set of one
     * chunk (design §6.1 input cap). The per-page estimates cover the wire
     * envelope (text + per-block/per-page overhead) but NOT the chunk-level
     * fixed framing (system+user prompts, envelope scaffolding — ~400
     * tokens), so this cap must leave that framing room alongside the
     * output budget and the 512 dispatch margin under the 8k window:
     * 4_096 + framing + 3_072 output + 512 ≤ 8_192. PROPOSED-GATE.
     */
    val maxEstimatedInputTokens: Int = 4_096,
) {
    fun validationError(): String? = when {
        maxCorePages < 1 || maxCorePages > AnalysisChunkResult.MAX_CORE_PAGES ->
            "maxCorePages out of bounds: $maxCorePages"
        overlapPages < 0 || overlapPages > AnalysisChunkResult.MAX_OVERLAP_PAGES ->
            "overlapPages out of bounds: $overlapPages"
        maxBlocksPerChunk < 1 -> "maxBlocksPerChunk must be positive"
        maxEstimatedInputTokens < 1 -> "maxEstimatedInputTokens must be positive"
        else -> null
    }
}

/** Pure per-page input to analysis chunk planning, in corpus order upstream. */
data class ChunkPlannerPage(
    val pageKey: String,
    /** Provable natural index, or null (never-guess rule); nulls order last. */
    val naturalPageIndex: Int?,
    /**
     * The page's semantic content fingerprint
     * ([StageFingerprints.pageOcrContentFingerprint], ); feeds the
     * per-chunk contributing corpus fingerprint (schemas contract §1.3).
     */
    val contentFingerprint: String,
    /** Ordered stable block ids (`p<N>_b<M>`) of this page's OCR snapshot. */
    val blockIds: List<String>,
    /** Estimated source tokens for the whole page (pure estimate, caller-owned). */
    val estimatedInputTokens: Int,
)

/**
 * One planned analysis chunk — the planning-owned subset of
 * [AnalysisChunkResult] (schemas contract §1.3). Extraction records, status
 * and evidence hashes are filled by the analysis stage (WP5).
 */
data class PlannedAnalysisChunk(
    /** 0-based position within the plan. */
    val chunkOrdinal: Int,
    /** Deterministic `chunk-<ordinal>-<corpus8>` (schemas contract §1.3). */
    val chunkId: String,
    /** CORE pages, ordered, natural order. */
    val corePageKeys: List<String>,
    /** CONTEXT overlap pages (adjacent predecessors), ordered, may be empty. */
    val contextOverlapPageKeys: List<String>,
    /** Corpus fingerprint over the contributing set in order. */
    val contributingCorpusFingerprint: String,
    /** Block count carried by CORE pages only. */
    val coreBlockCount: Int,
    /** Block count across core + overlap. */
    val contributingBlockCount: Int,
    /** Summed [ChunkPlannerPage.estimatedInputTokens] over the contributing set. */
    val estimatedInputTokens: Int,
    /** Ordered stable block ids per contributing page (the evidence universe). */
    val contributingBlockIds: Map<String, List<String>>,
) {
    /** Contributing set: core ∪ overlap, core first then overlap, no repeats. */
    val contributingPageKeys: List<String>
        get() = corePageKeys + contextOverlapPageKeys

    /**
     * Pure subset of evidence validation V1/V9: the reference
     * must name a contributing page, the block id must belong to that page's
     * contributing block list, and it must start with its page key (guards
     * the common `p12_b4` cited under `p13`). Excerpt-hash recomputation
     * (V8) and the core-page duty check (V9 record-level rule) stay with the
     * WP5 validator.
     */
    fun evidenceResolves(pageKey: String, blockId: String): Boolean {
        if (!blockId.startsWith(pageKey)) return false
        val blocks = contributingBlockIds[pageKey] ?: return false
        return blockId in blocks
    }
}

/** Result of pure analysis chunk planning. */
sealed class AnalysisChunkPlanResult {
    data class Success(
        val chunks: List<PlannedAnalysisChunk>,
        /**
         * Pages with zero blocks (textless) — never chunked; recorded so the
         * caller can reconcile the plan against the corpus manifest.
         */
        val skippedTextlessPageKeys: List<String>,
    ) : AnalysisChunkPlanResult()

    data class Rejected(val reason: String) : AnalysisChunkPlanResult()
}

object AnalysisChunkPlanner {

    /** Pure-planner algorithm version (bump on any windowing change). */
    const val PLANNER_VERSION = 1

    private const val CORPUS_ID_LENGTH = 8

    /**
     * Plan the whole-corpus chunk windows. Deterministic: pages are taken in
     * canonical order (natural index when provable, else sorted pageKey; never
     * input iteration order) and the greedy windowing is a pure fold.
     *
     * A page that alone exceeds the block or token cap still forms its own
     * single-page chunk — chunking never splits a page; per-chunk caps are
     * upper bounds on multi-page windows, not page admission filters (the
     * oversized-page policy for translation envelopes lives in
     * [GlobalEnvelopePlanner]).
     */
    fun plan(
        pages: List<ChunkPlannerPage>,
        policy: AnalysisChunkPolicy = AnalysisChunkPolicy(),
    ): AnalysisChunkPlanResult {
        policy.validationError()?.let { return AnalysisChunkPlanResult.Rejected("policy: $it") }

        val ordered = pages.sortedWith(
            compareBy<ChunkPlannerPage> { it.naturalPageIndex ?: Int.MAX_VALUE }
                .thenBy { it.pageKey },
        )
        ordered
            .groupBy { it.pageKey }
            .filterValues { it.size > 1 }
            .keys
            .firstOrNull()
            ?.let { return AnalysisChunkPlanResult.Rejected("duplicate pageKey: $it") }
        ordered.firstOrNull { it.estimatedInputTokens < 0 }?.let {
            return AnalysisChunkPlanResult.Rejected("negative estimatedInputTokens on ${it.pageKey}")
        }
        ordered.firstOrNull { it.blockIds.any(String::isBlank) }?.let {
            return AnalysisChunkPlanResult.Rejected("blank blockId on ${it.pageKey}")
        }
        val seenBlocks = HashSet<String>()
        for (page in ordered) {
            for (blockId in page.blockIds) {
                if (!seenBlocks.add(blockId)) {
                    return AnalysisChunkPlanResult.Rejected("duplicate blockId: $blockId")
                }
            }
        }

        val chunkable = ordered.filter { it.blockIds.isNotEmpty() }
        val skipped = ordered.filter { it.blockIds.isEmpty() }.map { it.pageKey }
        if (chunkable.isEmpty()) {
            return AnalysisChunkPlanResult.Success(chunks = emptyList(), skippedTextlessPageKeys = skipped)
        }

        val windows = buildWindows(chunkable, policy)
        val chunks = windows.mapIndexed { ordinal, window ->
            val contributing = window.corePages + window.overlapPages
            val contributingFingerprint = StageFingerprints.ocrCorpusFingerprint(
                pages = contributing.map { it.pageKey to it.contentFingerprint },
                expectedPageCount = contributing.size,
                expectedPageCountTrusted = true,
                naturalOrderProven = true,
            )
            PlannedAnalysisChunk(
                chunkOrdinal = ordinal,
                chunkId = chunkId(ordinal, contributingFingerprint),
                corePageKeys = window.corePages.map { it.pageKey },
                contextOverlapPageKeys = window.overlapPages.map { it.pageKey },
                contributingCorpusFingerprint = contributingFingerprint,
                coreBlockCount = window.corePages.sumOf { it.blockIds.size },
                contributingBlockCount = contributing.sumOf { it.blockIds.size },
                estimatedInputTokens = contributing.sumOf { it.estimatedInputTokens },
                contributingBlockIds = contributing.associate { it.pageKey to it.blockIds },
            )
        }
        return AnalysisChunkPlanResult.Success(chunks = chunks, skippedTextlessPageKeys = skipped)
    }

    /** Deterministic chunk id per schemas contract §1.3: `chunk-<ordinal>-<corpus8>`. */
    fun chunkId(chunkOrdinal: Int, contributingCorpusFingerprint: String): String =
        "chunk-$chunkOrdinal-${contributingCorpusFingerprint.take(CORPUS_ID_LENGTH)}"

    /**
     * Greedy whole-page windowing: accumulate core pages until a cap would be
     * exceeded, then close the window. Overlap pages for window i>0 are the
     * last [AnalysisChunkPolicy.overlapPages] core pages of window i-1 (the
     * immediately adjacent predecessors), shrunk first by the input-token cap
     * so core + overlap never exceeds the budget. Overlap pages are never
     * duplicated into the window's own core.
     */
    private fun buildWindows(
        pages: List<ChunkPlannerPage>,
        policy: AnalysisChunkPolicy,
    ): List<PlannedWindow> {
        val windows = mutableListOf<PlannedWindow>()
        var current = mutableListOf<ChunkPlannerPage>()
        var currentBlocks = 0
        var currentTokens = 0

        fun flush() {
            if (current.isEmpty()) return
            val overlap = if (windows.isEmpty() || policy.overlapPages == 0) {
                emptyList()
            } else {
                val candidates = windows.last().corePages.takeLast(policy.overlapPages)
                    .filter { candidate -> current.none { it.pageKey == candidate.pageKey } }
                var overlapTokens = candidates.sumOf { it.estimatedInputTokens }
                var kept = candidates
                while (kept.isNotEmpty() && currentTokens + overlapTokens > policy.maxEstimatedInputTokens) {
                    kept = kept.drop(1) // shed the farthest predecessor first
                    overlapTokens = kept.sumOf { it.estimatedInputTokens }
                }
                kept
            }
            windows += PlannedWindow(corePages = current.toList(), overlapPages = overlap)
            current = mutableListOf()
            currentBlocks = 0
            currentTokens = 0
        }

        for (page in pages) {
            val pageBlocks = page.blockIds.size
            val pageTokens = page.estimatedInputTokens
            val fitsCurrent = current.isEmpty() ||
                (
                    current.size + 1 <= policy.maxCorePages &&
                        currentBlocks + pageBlocks <= policy.maxBlocksPerChunk &&
                        currentTokens + pageTokens <= policy.maxEstimatedInputTokens
                    )
            if (!fitsCurrent) flush()
            current += page
            currentBlocks += pageBlocks
            currentTokens += pageTokens
        }
        flush()
        return windows
    }

    private data class PlannedWindow(
        val corePages: List<ChunkPlannerPage>,
        val overlapPages: List<ChunkPlannerPage>,
    )
}
