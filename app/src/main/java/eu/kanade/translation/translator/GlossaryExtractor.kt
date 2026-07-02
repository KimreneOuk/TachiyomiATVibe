package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

object GlossaryExtractor {

    suspend fun extractGlossary(
        translator: ContextualTextTranslator,
        pages: List<PageTranslation>
    ): String {
        // Collect text from all blocks in the first few pages
        val textBuilder = StringBuilder()
        pages.forEach { page ->
            page.blocks.forEach { block ->
                if (block.text.isNotBlank()) {
                    textBuilder.appendLine(block.text)
                }
            }
        }
        val sourceText = textBuilder.toString().trim()
        if (sourceText.isEmpty()) return ""

        val prompt = """
            Extract recurring character names, locations, and special terms from this ${translator.fromLang.label} manga text.
            Format as a strict list like:
            OriginalTerm: TranslatedTerm
            Only return the terms, no other text.
            
            Text:
            $sourceText
        """.trimIndent()

        logcat(LogPriority.INFO) { "Extracting glossary from first ${pages.size} pages..." }
        
        return try {
            val response = translator.promptText(prompt)
            if (response.isNotBlank()) {
                logcat(LogPriority.INFO) { "Extracted glossary:\n$response" }
                // Prepend a header so the model knows what it is
                "[GLOSSARY]\n$response\n[END GLOSSARY]\n"
            } else {
                ""
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to extract glossary" }
            ""
        }
    }
}
