package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.translator.TranslationContextChunkPlanner.Constraints
import eu.kanade.translation.translator.TranslationContextChunkPlanner.constraintsFor
import eu.kanade.translation.translator.TranslationContextChunkPlanner.estimateTokens

/**
 * Streaming twin of [TranslationContextChunkPlanner.plan]: accepts pages one at
 * a time and flushes a [TranslationContextChunk] at the IDENTICAL points and
 * with IDENTICAL contents as the greedy batch planner, so a concurrent pipeline
 * can translate each chunk as it fills instead of planning everything up front.
 *
 * The only behaviour added over the batch path is completion tracking: because a
 * page may span several chunks, a page "completes" on the flush that emits its
 * LAST chunkable source block (see [Emission.completedPages]).
 */
class StreamingChunkPlanner(
    requestedOutputTokens: Int,
    profile: TranslationContextChunkPlanner.Profile = TranslationContextChunkPlanner.Profile.DEFAULT,
    maxBlocksPerChunk: Int? = null,
    maxPagesPerChunk: Int? = null,
) {
    /** One chunk flushed by the greedy buffer, plus page keys fully consumed by it.
     *  A page may span multiple chunks; it completes on the chunk holding its LAST
     *  source block. `chunk` is null ONLY for a "chunkless completion" — a page whose
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

    private val constraints: Constraints = constraintsFor(profile).let {
        it.copy(
            maxBlocksPerChunk = maxBlocksPerChunk ?: it.maxBlocksPerChunk,
            maxPagesPerChunk = maxPagesPerChunk ?: it.maxPagesPerChunk,
        )
    }
    private val outputUpperBound: Int = requestedOutputTokens.coerceAtLeast(constraints.minOutputTokens)
    private val maxPromptTokens: Int = constraints.maxContextTokens - constraints.safetyMargin - constraints.minOutputTokens

    private val emitted = mutableListOf<TranslationContextChunk>()
    private val rejected = linkedMapOf<String, String>()
    private var current = mutableListOf<BlockRef>()
    private var currentTokens = constraints.promptOverheadTokens

    // A page completes once emittedRefs == totalRefs: every chunkable ref must be flushed
    // into an emitted chunk (refs still in the un-flushed buffer don't count). This lets a
    // downstream pipeline mark a page READY exactly when its last source block is handed off.
    private val totalRefs = linkedMapOf<String, Int>()
    private val emittedRefs = linkedMapOf<String, Int>()

    /** Accumulating rejection map (page rejected because one block alone
     *  exceeds the context budget). Finalized after [flushRemaining]. */
    val rejectedPages: Map<String, String> get() = rejected

    /** Feed the next page IN ORDER. Returns null when nothing flushed and no
     *  page completed on this call; otherwise the emission. Flushes at the
     *  IDENTICAL points and with IDENTICAL chunk contents as
     *  [TranslationContextChunkPlanner.plan]. */
    fun accept(pageKey: String, page: PageTranslation): Emission? {
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
            if (constraints.promptOverheadTokens + refTokens > maxPromptTokens) {
                rejectReason = "Text block exceeds the ${constraints.maxContextTokens / 1024}k AI context budget"
                return@forEachIndexed
            }
            pageRefs += ref to refTokens
        }

        var lastChunk: TranslationContextChunk? = null
        val completed = linkedSetOf<String>()

        if (rejectReason != null) {
            flushForAccept()?.let { lastChunk = it.chunk; completed += it.completed }
            rejected[pageKey] = rejectReason
            // A rejected page contributes no refs and is intentionally never
            // reported as completed.
        } else {
            totalRefs[pageKey] = pageRefs.size
            pageRefs.forEach { (ref, refTokens) ->
                val prospectivePageCount = current.mapTo(linkedSetOf()) { it.pageKey }
                    .also { it += ref.pageKey }
                    .size
                if (current.isNotEmpty() &&
                    (
                        currentTokens + refTokens > maxPromptTokens ||
                            current.size >= constraints.maxBlocksPerChunk ||
                            prospectivePageCount > constraints.maxPagesPerChunk
                        )
                ) {
                    flushForAccept()?.let { lastChunk = it.chunk; completed += it.completed }
                }
                current += ref
                currentTokens += refTokens
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
        refs.groupBy { it.pageKey }.forEach { (pageKey, blockRefs) ->
            val newCount = (emittedRefs[pageKey] ?: 0) + blockRefs.size
            emittedRefs[pageKey] = newCount
            if (newCount == totalRefs[pageKey]) completed += pageKey
        }
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
            maxOutputTokens = effectiveOutputCap(promptTokens, requestedOutputTokens, constraints),
        )
    }

    private fun estimateBlockTokens(ref: BlockRef): Int {
        val keyOverhead = estimateTokens(ref.pageKey) + 8
        return keyOverhead + estimateTokens(ref.block.text)
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
        ): Int {
            val available = constraints.maxContextTokens - constraints.safetyMargin - promptTokens
            return requestedOutputTokens
                .coerceAtLeast(constraints.minOutputTokens)
                .coerceAtMost(available)
                .coerceAtLeast(constraints.minOutputTokens)
        }
    }
}
