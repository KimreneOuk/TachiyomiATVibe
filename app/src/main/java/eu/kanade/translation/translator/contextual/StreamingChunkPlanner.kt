package eu.kanade.translation.translator.contextual
import eu.kanade.translation.translator.contextual.TranslationContextChunkPlanner

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.translator.contextual.TranslationContextChunkPlanner.Constraints
import eu.kanade.translation.translator.contextual.TranslationContextChunkPlanner.constraintsFor
import eu.kanade.translation.translator.contextual.TranslationContextChunkPlanner.estimateTokens

/**
 * Streaming twin of [TranslationContextChunkPlanner.plan]: accepts pages one at
 * a time and flushes a [TranslationContextChunk] at the IDENTICAL points and
 * with IDENTICAL contents as the greedy batch planner, so a concurrent pipeline
 * can translate each chunk as it fills instead of planning everything up front.
 *
 * Pages are atomic: all unfinished text blocks from a page stay in one
 * envelope. The greedy admission rule adds complete pages until the provider
 * token budget is reached, then flushes before the next page. This prevents a
 * dense page from losing its intra-page context across separate requests.
 */
class StreamingChunkPlanner(
    requestedOutputTokens: Int,
    profile: TranslationContextChunkPlanner.Profile = TranslationContextChunkPlanner.Profile.DEFAULT,
    private val naturalPageIndexes: Map<String, Int> = emptyMap(),
) {
    /** One chunk flushed by the greedy buffer, plus page keys fully consumed by it.
     *  `chunk` is null ONLY for a "chunkless completion" — a page whose
     *  non-blank source blocks are ALL already translated (resume case), needing no
     *  chunk but still reported downstream to be marked READY. A textless page (zero
     *  non-blank blocks) is NEVER completed. When a single [accept] flushes several
     *  chunks, [chunk] holds the last; all are available via [emittedChunks]. */
    data class Emission(
        val chunk: TranslationContextChunk?,
        val completedPages: Set<String>,
    )

    /** Final partial chunk (or null) + pages completed by it + the full
     *  accumulated rejectedPages map. Planner is exhausted after this. */
    data class FlushResult(
        val finalChunk: TranslationContextChunk?,
        val completedPages: Set<String>,
        val rejectedPages: Map<String, String>,
    )

    private val constraints: Constraints = constraintsFor(profile)
    private val outputUpperBound: Int = requestedOutputTokens.coerceAtLeast(constraints.minOutputTokens)

    private val emitted = mutableListOf<TranslationContextChunk>()
    private val rejected = linkedMapOf<String, String>()
    private var current = mutableListOf<BlockRef>()
    private var currentTokens = constraints.promptOverheadTokens
    private val fallbackPageIndexes = linkedMapOf<String, Int>()
    private val usedPageIndexes = naturalPageIndexes.values.toMutableSet()
    private var nextFallbackPageIndex = naturalPageIndexes.values.maxOrNull()
        ?.let { if (it == Int.MAX_VALUE) 0 else it + 1 }
        ?: 0

    /** Accumulating rejection map (page rejected because all of its unfinished blocks together
     *  exceeds the context budget). Finalized after [flushRemaining]. */
    val rejectedPages: Map<String, String> get() = rejected

    /** Records a natural-order page refusal discovered before envelope flush. */
    fun reject(pageKey: String, reason: String) {
        rejected.putIfAbsent(pageKey, reason)
    }

    /** Feed the next page IN ORDER. Returns null when nothing flushed and no
     *  page completed on this call; otherwise the emission. Flushes at the
     *  IDENTICAL points and with IDENTICAL chunk contents as
     *  [TranslationContextChunkPlanner.plan]. */
    fun accept(pageKey: String, page: PageTranslation): Emission? {
        if (pageKey !in fallbackPageIndexes) {
            while (nextFallbackPageIndex in usedPageIndexes) {
                nextFallbackPageIndex = if (nextFallbackPageIndex == Int.MAX_VALUE) 0 else nextFallbackPageIndex + 1
            }
            fallbackPageIndexes[pageKey] = nextFallbackPageIndex
            usedPageIndexes += nextFallbackPageIndex
            nextFallbackPageIndex = if (nextFallbackPageIndex == Int.MAX_VALUE) 0 else nextFallbackPageIndex + 1
        }
        val pageRefs = mutableListOf<Pair<BlockRef, Int>>()
        var rejectReason: String? = null
        var nonBlankSourceBlocks = 0
        page.blocks.forEachIndexed { blockIndex, block ->
            if (rejectReason != null) return@forEachIndexed
            if (block.text.isBlank()) return@forEachIndexed
            nonBlankSourceBlocks += 1
            // Resume: a block with a real translation (non-blank, not source-equal) is done; don't re-send it.
            if (block.translation.isNotBlank() && block.translation.trim() != block.text.trim()) {
                return@forEachIndexed
            }
            val ref = BlockRef(pageKey, blockIndex, block)
            val refTokens = estimateBlockTokens(ref)
            if (constraints.promptOverheadTokens + refTokens > promptBudget(1, 1)) {
                rejectReason = "Text block exceeds the ${constraints.maxContextTokens / 1024}k AI context budget"
                return@forEachIndexed
            }
            pageRefs += ref to refTokens
        }

        var lastChunk: TranslationContextChunk? = null
        val completed = linkedSetOf<String>()

        if (rejectReason != null) {
            flushForAccept()?.let {
                lastChunk = it.chunk
                completed += it.completed
            }
            rejected[pageKey] = rejectReason
            // A rejected page contributes no refs and is intentionally never
            // reported as completed.
        } else {
            val pageTokens = pageRefs.sumOf { it.second }
            if (pageRefs.isNotEmpty() &&
                constraints.promptOverheadTokens + pageTokens > promptBudget(pageRefs.size, 1)
            ) {
                flushForAccept()?.let {
                    lastChunk = it.chunk
                    completed += it.completed
                }
                rejected[pageKey] = "Page text exceeds the ${constraints.maxContextTokens / 1024}k AI context budget"
            } else if (pageRefs.isNotEmpty()) {
                val prospectivePageCount = current.mapTo(linkedSetOf()) { it.pageKey }.size + 1
                if (current.isNotEmpty() &&
                    currentTokens + pageTokens > promptBudget(current.size + pageRefs.size, prospectivePageCount)
                ) {
                    flushForAccept()?.let {
                        lastChunk = it.chunk
                        completed += it.completed
                    }
                }
                pageRefs.forEach { (ref, refTokens) ->
                    current += ref
                    currentTokens += refTokens
                }
            }
            // Chunkless (resume) completion: only non-blank already-translated blocks, so no
            // chunk is needed but the page must still surface downstream to be marked READY.
            if (pageRefs.isEmpty() && nonBlankSourceBlocks > 0) {
                completed += pageKey
            }
        }

        if (lastChunk == null && completed.isEmpty()) return null
        return Emission(chunk = lastChunk, completedPages = completed)
    }

    /** Flush the final partial chunk. */
    fun flushRemaining(): FlushResult {
        if (current.isEmpty()) {
            return FlushResult(finalChunk = null, completedPages = emptySet(), rejectedPages = rejected)
        }
        val built = buildAndAccount()
        // The tail is returned ONLY via finalChunk, not appended to `emitted`, so
        // [emittedChunks] keeps excluding it and plan() concatenates them exactly once.
        return FlushResult(finalChunk = built.chunk, completedPages = built.completed, rejectedPages = rejected)
    }

    /** All full chunks emitted via [accept] so far (excludes the un-flushed tail). */
    fun emittedChunks(): List<TranslationContextChunk> = emitted.toList()

    private data class BuiltChunk(
        val chunk: TranslationContextChunk,
        val completed: Set<String>,
    )

    private fun buildAndAccount(): BuiltChunk {
        val refs = current
        val chunk = buildChunk(refs, outputUpperBound, constraints)
        val completed = linkedSetOf<String>()
        completed += refs.mapTo(linkedSetOf()) { it.pageKey }
        current = mutableListOf()
        currentTokens = constraints.promptOverheadTokens
        return BuiltChunk(chunk, completed)
    }

    private fun flushForAccept(): BuiltChunk? {
        if (current.isEmpty()) return null
        val built = buildAndAccount()
        emitted += built.chunk
        return built
    }

    private fun buildChunk(
        refs: List<BlockRef>,
        requestedOutputTokens: Int,
        constraints: Constraints,
    ): TranslationContextChunk {
        val grouped = linkedMapOf<String, PageTranslation>()
        refs.groupBy { it.pageKey }.forEach { (pageKey, blockRefs) ->
            grouped[pageKey] = PageTranslation(
                blocks = blockRefs.map { it.block }.toMutableList(),
            )
        }
        val promptTokens = constraints.promptOverheadTokens + refs.sumOf(::estimateBlockTokens)
        return TranslationContextChunk(
            pages = grouped,
            blockCount = refs.size,
            rollingContext = "",
            estimatedPromptTokens = promptTokens,
            maxOutputTokens = effectiveOutputCap(
                promptTokens,
                requestedOutputTokens,
                constraints,
                protocol = ContextualRequestProtocol.BATCH_V1,
                blockCount = refs.size,
                pageCount = grouped.size,
            ),
            protocol = ContextualRequestProtocol.BATCH_V1,
            pageIndexes = grouped.keys.associateWith { pageKey ->
                naturalPageIndexes[pageKey] ?: fallbackPageIndexes.getValue(pageKey)
            },
        )
    }

    private fun estimateBlockTokens(ref: BlockRef): Int {
        val keyOverhead = estimateTokens(ref.pageKey) + 8
        return keyOverhead + estimateTokens(ref.block.text)
    }

    private fun promptBudget(blockCount: Int, pageCount: Int): Int {
        return constraints.maxContextTokens - constraints.safetyMargin - constraints.minOutputTokens -
            TranslationContextChunkPlanner.batchResponseOverheadTokens(blockCount, pageCount)
    }

    private data class BlockRef(
        val pageKey: String,
        val blockIndex: Int,
        val block: TranslationBlock,
    )

    companion object {
        /**
         * Output cap shared with [TranslationContextChunkPlanner.withRollingContext] so the runtime
         * rolling-context path and the plan/build path coerce identically. Module-internal to avoid duplication.
         */
        internal fun effectiveOutputCap(
            promptTokens: Int,
            requestedOutputTokens: Int,
            constraints: Constraints,
            protocol: ContextualRequestProtocol = ContextualRequestProtocol.LEGACY,
            blockCount: Int = 0,
            pageCount: Int = 0,
        ): Int {
            val protocolReserve = if (protocol == ContextualRequestProtocol.BATCH_V1) {
                TranslationContextChunkPlanner.batchResponseOverheadTokens(blockCount, pageCount)
            } else {
                0
            }
            val available = constraints.maxContextTokens - constraints.safetyMargin - promptTokens - protocolReserve
            if (available < constraints.minOutputTokens) {
                return -1
            }
            // The BATCH_V1 reserve is scaffolding the model must EMIT, not
            // merely context to fit: the JSON envelope rides inside
            // maxOutputTokens, so it is part of the request — still bounded
            // by the same post-reserve window so
            // prompt + output + reserve <= maxContextTokens.
            return (requestedOutputTokens + protocolReserve)
                .coerceAtLeast(constraints.minOutputTokens)
                .coerceAtMost(available)
        }
    }
}
