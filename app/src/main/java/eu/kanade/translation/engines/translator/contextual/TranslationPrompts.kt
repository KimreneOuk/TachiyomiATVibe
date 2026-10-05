package eu.kanade.translation.engines.translator.contextual
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.model.TranslationBlock

/**
 * Single source of truth for the AI-translator prompts,
 * shared by all four AI translators (DeepSeek, LM Studio, Gemini, OpenRouter) so localization
 * guidance never diverges between providers.
 */
object TranslationPrompts {

    /** Pure rendering of approved, machine-classified output evidence. */
    fun correctionLine(hint: TranslationCorrectionHint?, to: TextTranslatorLanguage): String? {
        hint ?: return null
        val echo = hint.sourceEcho
        val wrongTarget = hint.wrongTargetLanguage
        return when {
            echo && wrongTarget ->
                "Translate source-language text into ${to.label}; do not copy it unchanged. " +
                    "Use ${to.label} for the translations."
            echo -> "Translate source-language text into ${to.label}; do not copy it unchanged."
            wrongTarget -> "Use ${to.label} for the translations."
            else -> null
        }
    }

    fun idMappedSourceLine(id: String, block: TranslationBlock): String {
        val flattened = block.text
            .replace("\r\n", " ")
            .replace('\r', ' ')
            .replace('\n', ' ')
        return "$id|$flattened"
    }

    data class ParsedLine(val id: String, val text: String)

    private val lineIdRegex: Regex = Regex("""\b((?:p\d+_)?b\d+)[^\w]*(.*)""")

    fun parseLine(line: String): ParsedLine? {
        val cleanLine = line.trim(' ', '\t', '\r', '\n', '`', '*')
        val match = lineIdRegex.find(cleanLine) ?: return null
        val id = match.groupValues[1]
        val content = match.groupValues[2].trim().removePrefix("|").removeSuffix("|").trim()
        return ParsedLine(id, content)
    }

    /** Input-only background; empty history adds no framing or placeholder text. */
    fun contextPrefix(rollingContext: String): String = rollingContext.trim()
        .takeIf(String::isNotEmpty)
        ?.let { "BACKGROUND:\n$it\n\n" }
        .orEmpty()

    /** Manga (Japanese) reads right-to-left; manhwa/manhua (Korean/Chinese) and
     *  Latin sources read left-to-right. Used to nudge reading-order inference. */
    fun readingDirectionHint(from: TextRecognizerLanguage): String = when (from) {
        TextRecognizerLanguage.JAPANESE -> "right-to-left, top-to-bottom"
        else -> "left-to-right, top-to-bottom"
    }

    /** True for source languages that habitually drop the subject pronoun
     *  (Japanese, Chinese, Korean, plus the Romance pro-drop languages Spanish,
     *  Portuguese, Italian). English, German, French, Indonesian, Vietnamese and
     *  Russian are non-pro-drop, so the subject-inference guidance does not apply
     *  to them and would mislead the model if always emitted. */
    fun isProDrop(from: TextRecognizerLanguage): Boolean = when (from) {
        TextRecognizerLanguage.JAPANESE,
        TextRecognizerLanguage.CHINESE,
        TextRecognizerLanguage.KOREAN,
        TextRecognizerLanguage.SPANISH,
        TextRecognizerLanguage.PORTUGUESE,
        TextRecognizerLanguage.ITALIAN,
        -> true
        else -> false
    }

    fun pass1SystemPrompt(
        from: TextRecognizerLanguage,
        to: TextTranslatorLanguage,
        batchProtocol: Boolean = false,
    ): String {
        val idInstruction = if (batchProtocol) {
            """Copy each item's numeric ID unchanged before "|" and write its translation after it."""
        } else {
            """Copy each item's ID unchanged before "|" and write its translation after it."""
        }

        return """
            The BACKGROUND section is context only; do not translate or reproduce it.
            Translate each item in SOURCE ITEMS from ${from.label} into natural ${to.label}.
            Preserve meaning and tone, including sound effects.
            $idInstruction
            Return one line per source item only, with no additional text.
        """.trimIndent()
    }
}
