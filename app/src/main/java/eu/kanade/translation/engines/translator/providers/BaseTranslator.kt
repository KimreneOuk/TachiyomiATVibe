package eu.kanade.translation.engines.translator.providers
import eu.kanade.translation.engines.translator.TextTranslator
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock

/**
 * Base class for standard, non-LLM translation engines (Google, DeepL, MLKit, etc.).
 * Standard engines translate flat string lists directly without prompt template overhead,
 * few-shot formatting, or rolling context tokens.
 */
abstract class BaseTranslator : TextTranslator {

    /**
     * Translates a flat list of strings without prompt templating overhead.
     * Default implementation iterates or batches flat strings.
     */
    open suspend fun translateFlat(texts: List<String>): List<String> {
        return texts.map { text ->
            if (text.isBlank()) "" else translateSingleText(text)
        }
    }

    /**
     * Single string translation for standard engines that process items individually.
     */
    open suspend fun translateSingleText(text: String): String = text

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        val nonBlankBlocks = mutableListOf<TranslationBlock>()
        for (page in pages.values) {
            for (block in page.blocks) {
                if (block.text.isNotBlank()) {
                    nonBlankBlocks.add(block)
                }
            }
        }
        if (nonBlankBlocks.isEmpty()) return

        val sourceTexts = nonBlankBlocks.map { it.text }
        val translatedTexts = translateFlat(sourceTexts)
        for (i in nonBlankBlocks.indices) {
            val translated = translatedTexts.getOrNull(i).orEmpty()
            if (translated.isNotBlank()) {
                nonBlankBlocks[i].translation = translated
            }
        }
    }

    override fun close() {}
}
