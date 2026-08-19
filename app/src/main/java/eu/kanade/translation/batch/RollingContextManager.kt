package eu.kanade.translation.batch

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

class RollingContextManager {
    private val accumulatedGlossary = mutableMapOf<String, String>()
    private var currentMicroSummary: String = ""

    @Synchronized
    fun getRollingContext(): RollingContextPacket {
        return RollingContextPacket(
            glossary = accumulatedGlossary.toMap(),
            microSummary = currentMicroSummary,
        )
    }

    @Synchronized
    fun updateContext(newGlossary: Map<String, String>, newMicroSummary: String) {
        accumulatedGlossary.putAll(newGlossary)
        if (newMicroSummary.isNotBlank()) {
            currentMicroSummary = newMicroSummary.trim()
        }
    }

    @Synchronized
    fun reset() {
        accumulatedGlossary.clear()
        currentMicroSummary = ""
    }
}
