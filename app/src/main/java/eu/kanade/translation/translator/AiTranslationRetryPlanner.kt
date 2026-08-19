package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock

/**
 * Pure retry-planning helpers for AI batch translation.
 *
 * These helpers preserve the original [TranslationBlock] object references
 * inside each [PageTranslation], so retry results mutate the same page state
 * that the renderer and store later consume.
 */
object AiTranslationRetryPlanner {

    /**
     * Blocks on a single page that still need translation: non-blank source whose translation is
     * blank or source-equal. Pure predicate so the single-page retry loop can decide which blocks
     * to re-request without re-running the (multi-page token-budget-oriented) chunk planner.
     */
    fun untranslatedBlocks(page: PageTranslation): List<TranslationBlock> =
        page.blocks.filter { block ->
            block.text.isNotBlank() &&
                (block.translation.isBlank() || block.translation.trim() == block.text.trim())
        }

    fun untranslatedPages(chunk: TranslationContextChunk): LinkedHashMap<String, PageTranslation> {
        val pages = linkedMapOf<String, PageTranslation>()
        chunk.pages.forEach { (pageKey, page) ->
            val missing = page.blocks.filter { block ->
                block.text.isNotBlank() &&
                    (block.translation.isBlank() || block.translation.trim() == block.text.trim())
            }
            if (missing.isNotEmpty()) {
                pages[pageKey] = PageTranslation(blocks = missing.toMutableList())
            }
        }
        return pages
    }

    fun planFailureSplit(
        chunk: TranslationContextChunk,
        requestedOutputTokens: Int,
        profile: TranslationContextChunkPlanner.Profile,
    ): TranslationContextChunkPlanner.Result {
        return planSmaller(
            pages = chunk.pages,
            requestedOutputTokens = requestedOutputTokens,
            profile = profile,
            sourceBlockCount = chunk.blockCount,
            sourcePageCount = chunk.pages.size,
            pageIndexes = chunk.pageIndexes,
        )
    }

    fun planMissingRetry(
        chunk: TranslationContextChunk,
        requestedOutputTokens: Int,
        profile: TranslationContextChunkPlanner.Profile,
    ): TranslationContextChunkPlanner.Result {
        val missing = untranslatedPages(chunk)
        return planSmaller(
            pages = missing,
            requestedOutputTokens = requestedOutputTokens,
            profile = profile,
            sourceBlockCount = chunk.blockCount,
            sourcePageCount = chunk.pages.size,
            pageIndexes = chunk.pageIndexes,
        )
    }

    private fun planSmaller(
        pages: LinkedHashMap<String, PageTranslation>,
        requestedOutputTokens: Int,
        profile: TranslationContextChunkPlanner.Profile,
        sourceBlockCount: Int,
        sourcePageCount: Int,
        pageIndexes: Map<String, Int>,
    ): TranslationContextChunkPlanner.Result {
        val nextMaxBlocks = (sourceBlockCount / 2).coerceAtLeast(1)
        val nextMaxPages = (sourcePageCount / 2).coerceAtLeast(1)
        return TranslationContextChunkPlanner.plan(
            pages = pages,
            requestedOutputTokens = requestedOutputTokens,
            profile = profile,
            maxBlocksPerChunk = nextMaxBlocks,
            maxPagesPerChunk = nextMaxPages,
            pageIndexes = pageIndexes,
        )
    }
}
