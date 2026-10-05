package eu.kanade.translation.engines.translator.retry
import eu.kanade.translation.engines.translator.TranslationOutputSemantics
import eu.kanade.translation.engines.translator.contextual.TranslationContextChunk
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
     * Blocks on a single page that still need translation under the shared
     * language-aware output classifier. Pure predicate so the single-page retry
     * loop can decide which blocks to re-request without re-running the
     * multi-page token-budget-oriented chunk planner.
     */
    fun untranslatedBlocks(
        page: PageTranslation,
        sourceLanguageCode: String? = null,
        targetLanguageCode: String? = null,
    ): List<TranslationBlock> =
        page.blocks.filter { block ->
            block.text.isNotBlank() &&
                !TranslationOutputSemantics.isResolved(
                    source = block.text,
                    output = block.translation,
                    sourceLanguageCode = sourceLanguageCode,
                    targetLanguageCode = targetLanguageCode,
                )
        }

    fun untranslatedPages(
        chunk: TranslationContextChunk,
        sourceLanguageCode: String? = null,
        targetLanguageCode: String? = null,
    ): LinkedHashMap<String, PageTranslation> {
        val pages = linkedMapOf<String, PageTranslation>()
        chunk.pages.forEach { (pageKey, page) ->
            val missing = page.blocks.filter { block ->
                block.text.isNotBlank() &&
                    !TranslationOutputSemantics.isResolved(
                        source = block.text,
                        output = block.translation,
                        sourceLanguageCode = sourceLanguageCode,
                        targetLanguageCode = targetLanguageCode,
                    )
            }
            if (missing.isNotEmpty()) {
                pages[pageKey] = PageTranslation(blocks = missing.toMutableList())
            }
        }
        return pages
    }
}
