package eu.kanade.translation.translator.contextual
import eu.kanade.translation.translator.TextTranslatorLanguage

import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocr.TextRecognizerLanguage

/**
 * Single source of truth for the AI-translator prompts,
 * shared by all four AI translators (DeepSeek, LM Studio, Gemini, OpenRouter) so localization
 * guidance never diverges between providers.
 */
object TranslationPrompts {

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

    /** Combined context prefix from a glossary (stable term renderings) and a
     *  rolling recent-pairs buffer. Empty when both are blank so the first chunk
     *  of a chapter adds no framing noise. */
    fun contextPrefix(rollingContext: String, glossary: String): String {
        val g = glossary.trim()
        val r = rollingContext.trim()
        if (g.isEmpty() && r.isEmpty()) return ""
        val sb = StringBuilder()
        if (g.isNotEmpty()) {
            sb.append("Established terms (reuse these exact English renderings; keep names consistent):\n")
            sb.append(g).append("\n\n")
        }
        if (r.isNotEmpty()) {
            sb.append("Previous context / recent translated pairs (use for speaker, name & pronoun continuity):\n")
            sb.append(r).append("\n\n")
        }
        return sb.toString()
    }

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
        val proDropContext = if (isProDrop(from)) {
            "Note: ${from.label} frequently omits subjects (pro-drop). Infer explicit subjects and maintain consistent character voice and pronouns.\n"
        } else {
            ""
        }
        val exampleId1 = if (batchProtocol) "p0_b0" else "b0"
        val exampleId2 = if (batchProtocol) "p0_b1" else "b1"

        return """
            You are a manga localization specialist. Translate the comic dialogue text blocks from ${from.label} to ${to.label}.

            $proDropContext
            RULES:
            - Output format: `ID|Translated Text`, exactly one line per block.
            - Naturalize dialogue into lively spoken comic English, preserving tone and humor.
            - Localize sound effects (e.g. *gasp*, *thud*).
            - Output ONLY the `ID|Translated Text` lines. No preambles, markdown formatting, or explanations.

            EXAMPLE:
            Input:
            $exampleId1|行く。
            $exampleId2|あの日、彼と出会った。
            Output:
            $exampleId1|I'm going.
            $exampleId2|That day, I met him.
        """.trimIndent()
    }
}
