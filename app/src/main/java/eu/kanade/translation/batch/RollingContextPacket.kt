package eu.kanade.translation.batch

/**
 * Compatibility payload for the reader/translator prompt APIs. The Phase 6
 * scene engine owns production context state; this immutable packet remains
 * only for existing reader-facing overloads and tests.
 */
data class RollingContextPacket(
    val glossary: Map<String, String> = emptyMap(),
    val microSummary: String = "",
) {
    fun toPromptContext(): String {
        if (glossary.isEmpty() && microSummary.isBlank()) return ""
        val sb = StringBuilder()
        if (microSummary.isNotBlank()) {
            sb.appendLine("Previous Scene Summary: ${microSummary.trim()}")
        }
        if (glossary.isNotEmpty()) {
            sb.appendLine("Established Glossary:")
            glossary.forEach { (term, definition) ->
                sb.appendLine("- $term: $definition")
            }
        }
        return sb.toString().trim()
    }
}
