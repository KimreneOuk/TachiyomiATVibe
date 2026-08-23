package eu.kanade.translation.translator

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

    private val lineIdRegex: Regex = Regex("""\b(b\d+)[^\w]*(.*)""")

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

    /** Overload accepting a [eu.kanade.translation.batch.RollingContextPacket] directly. */
    fun contextPrefix(
        packet: eu.kanade.translation.batch.RollingContextPacket,
        extraGlossary: String = "",
    ): String {
        val g = extraGlossary.trim()
        val p = packet.toPromptContext().trim()
        if (g.isEmpty() && p.isEmpty()) return ""
        val sb = StringBuilder()
        if (g.isNotEmpty()) {
            sb.append("Established terms (reuse these exact English renderings; keep names consistent):\n")
            sb.append(g).append("\n\n")
        }
        if (p.isNotEmpty()) {
            sb.append(p).append("\n\n")
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
        val dir = readingDirectionHint(from)
        // Subject-inference guidance only helps pro-drop sources (JP/ZH/KO + Romance pro-drop).
        // For non-pro-drop sources it misleads the model into inventing omitted subjects that aren't there.
        val sourceLanguageContext = if (isProDrop(from)) {
            """
            SOURCE-LANGUAGE CONTEXT: ${from.label} frequently omits subjects and pronouns (it is a pro-drop language). English requires an explicit subject. Infer the implied subject from the line itself, the surrounding blocks, and the provided "previous pairs" context, then choose ONE consistent pronoun and keep it. Never leave a subject ambiguous and never switch person mid-utterance.
            """.trimIndent()
        } else {
            ""
        }
        val outputFormat = if (batchProtocol) {
            """
            BATCH TRANSLATION FORMAT:
            - Output MUST be lines formatted as `ID|Translated Text`.
            - Translate each block faithfully preserving the exact ID prefix (e.g. `p0000_b0000|Translated text`).
            - Output ONLY these lines, one per block. No preambles, notes, or markdown formatting.
            """.trimIndent()
        } else {
            """
            OUTPUT FORMAT:
            Output MUST be in the exact format: `ID|Translated Text`. Output ONLY these lines, one per block. No preambles, notes, or explanations.
            """.trimIndent()
        }
        val examples = if (batchProtocol) {
            """
            Input: p0000_b0000|行く。
            Output: p0000_b0000|I'm going.

            Input: p0000_b0001|あの日、彼と出会った。
            Output: p0000_b0001|That day, I met him.

            Input: p0000_b0002|三年後、東京。
            Output: p0000_b0002|Three years later — Tokyo.

            Input: p0000_b0003|彼は来ないと言っていた。
            Output: p0000_b0003|He said he wouldn't come.
            """.trimIndent()
        } else {
            """
            Input: b0|行く。
            Output: b0|I'm going.

            Input: b1|あの日、彼と出会った。
            Output: b1|That day, I met him.

            Input: b2|三年後、東京。
            Output: b2|Three years later — Tokyo.

            Input: b3|彼は来ないと言っていた。
            Output: b3|He said he wouldn't come.
            """.trimIndent()
        }
        return """
            You are an expert manga/manhwa/manhua translator and localization specialist. Translate the source text blocks from ${from.label} to ${to.label}.

            $sourceLanguageContext

            SCENE CONTEXT & VOICE (CRITICAL):
            - The input blocks form a continuous comic dialogue scene. Translate them as an interconnected conversation rather than isolated sentences.
            - Preserve distinct character voice (cheeky, polite, timid, gruff, arrogant), emotional subtext, comedic timing, and interpersonal dynamics.
            - Naturalize dialogue into contemporary, lively spoken English (use natural contractions, colloquialisms, and idioms where appropriate).
            - Sound effects / onomatopoeia: provide standard comic-style equivalents (e.g. "Gasp", "Thud", *rumble*).

            POINT OF VIEW / PERSON (critical):
            - Dialogue is usually a character speaking aloud to an addressee. The speaker = "I/we", the addressee = "you", anyone else mentioned = "he/she/they". When the subject is omitted and cannot be resolved, a line often defaults to the speaker ("I/we") — UNLESS the line is an imperative (often subjectless in English), an offer/question directed at the addressee ("you"), or quoted/reported speech.
            - Narration or self-dialogue (inner monologue) are VERY OFTEN the point-of-view character's FIRST-PERSON voice (narrating or thinking): use "I" when it reads as a character's own thought or recount. Use third person ONLY for objective external description (scene/location/time, e.g. "Three years later — Tokyo"). Do NOT assume free text is third-person; first-person narration and self-dialogue are the common case.
            - If a character refers to themselves by their own name (illeism), convert it to the matching first-person pronoun ("I").
            - Keep the point of view consistent across a scene; use the provided previous pairs for speaker/name/pronoun continuity.

            DEICTICS: directional/location words (来る / 行く / ここ / そこ, or 来 / 去 / 这 / 那) are anchored to the speaker; resolve them consistently with the person you chose.

            READING ORDER: blocks are roughly ordered $dir, but comic layouts are irregular — use narrative judgment to connect adjacent bubbles, not the numbers alone.

            OTHER RULES:
            - Honorifics (-san / -kun / -chan / -sama / -senpai etc.) may be preserved for a character-driven tone or naturalized for a western localization, as fits the dialogue.
            - Script fidelity: if the target is a Latin-script language, do NOT output Japanese/Chinese/Korean characters; localize markers like (笑) to "lol" / "(laugh)".

            $outputFormat

            FEW-SHOT EXAMPLES:
            $examples
        """.trimIndent()
    }
}
