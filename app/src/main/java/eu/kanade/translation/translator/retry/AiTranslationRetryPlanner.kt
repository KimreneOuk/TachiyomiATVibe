package eu.kanade.translation.translator.retry
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.translator.contextual.TranslationContextChunk

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
}
