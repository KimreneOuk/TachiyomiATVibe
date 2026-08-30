package eu.kanade.translation.translator.providers
import eu.kanade.translation.translator.contextual.applyBatchToChunk
import eu.kanade.translation.translator.contextual.TranslationContextChunk
import eu.kanade.translation.translator.contextual.ContextualTranslationBatch
import eu.kanade.translation.translator.contextual.ContextualTextTranslator
import eu.kanade.translation.translator.contextual.ContextualRequestProtocol

import eu.kanade.translation.model.PageTranslation

/**
 * TachiyomiAT: Base abstraction for AI / LLM translators.
 */
abstract class AiTranslator : BaseTranslator(), ContextualTextTranslator {

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        val linkedPages = LinkedHashMap(pages)
        val blockCount = linkedPages.values.sumOf { it.blocks.size }
        val chunk = TranslationContextChunk(
            pages = linkedPages,
            blockCount = blockCount,
            rollingContext = "",
            glossary = "",
            estimatedPromptTokens = 0,
            maxOutputTokens = 8192,
            protocol = ContextualRequestProtocol.LEGACY,
        )
        translateContextual(chunk)
    }

    override suspend fun translateContextual(chunk: TranslationContextChunk): ContextualTranslationBatch {
        val batch = translateContextualStructured(chunk)
        applyBatchToChunk(chunk, batch)
        return batch
    }
}
