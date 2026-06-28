package eu.kanade.translation.translator

import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocr.TextRecognizerLanguage

/**
 * Single source of truth for the AI-translator prompts and the [SPEECH_TAG]
 * structural hint, shared by all four AI translators (DeepSeek, LM Studio,
 * Gemini, OpenRouter) so the localization guidance never diverges between
 * providers.
 *
 * The [SPEECH_TAG] is a **positive-only** structural cue: a block INSIDE a speech
 * bubble (conversation) is tagged; free text (narration / self-dialogue) is left
 * untagged. The tag is embedded ONLY in the prompt INPUT — it is never stored on
 * the block and never echoed in output — and any accidental echo is stripped
 * defensively by [OcrArtifactSanitizer].
 *
 * Relies on the recognition engine populating `parentWidth/Height` for in-bubble
 * blocks. On the live path only [RoiPageRecognitionEngine] does this; the (unused)
 * ML Kit full-page engine does not, so tag-coverage is logged by the pipeline.
 *
 * [RoiPageRecognitionEngine]: eu.kanade.translation.recognition.RoiPageRecognitionEngine
 */
object TranslationPrompts {

    const val SPEECH_TAG = "SPEECH"

    /** True when a block sits inside a detected speech bubble (conversation).
     *  Mirrors the parent-bubble predicate used by the render/inpaint paths
     *  (TextLayoutPlanner / RenderColorEstimator / PageInpaintingPlanner). */
    fun TranslationBlock.isInsideBubble(): Boolean = parentWidth > 0f && parentHeight > 0f

    /** Numbered-engine source line: `[index] [SPEECH] text` for in-bubble blocks,
     *  otherwise `[index] text`. */
    fun numberedSourceLine(index: Int, block: TranslationBlock): String {
        val tag = if (block.isInsideBubble()) "[$SPEECH_TAG] " else ""
        return "[$index] $tag${block.text}"
    }

    /** JSON-engine source value: `[SPEECH] text` for in-bubble blocks, otherwise
     *  the raw text. Embedded as one element of the source array. */
    fun jsonSourceValue(block: TranslationBlock): String {
        val tag = if (block.isInsideBubble()) "[$SPEECH_TAG] " else ""
        return "$tag${block.text}"
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

    fun numberedSystemPrompt(from: TextRecognizerLanguage, to: TextTranslatorLanguage): String =
        baseGuidance(from, to, numbered = true)

    fun jsonSystemPrompt(from: TextRecognizerLanguage, to: TextTranslatorLanguage): String =
        baseGuidance(from, to, numbered = false)

    private fun baseGuidance(
        from: TextRecognizerLanguage,
        to: TextTranslatorLanguage,
        numbered: Boolean,
    ): String {
        val dir = readingDirectionHint(from)
        val outputRule = if (numbered) {
            "Output ONLY one line per block in the exact format `[index] translation` — no preambles, notes, or explanations."
        } else {
            "Return ONLY a JSON object with the same keys and array lengths as the input; each element is ONLY the translation string (no explanations)."
        }
        return """
            You are an expert manga/manhwa/manhua translator and localization specialist. Translate the source text blocks from ${from.label} to ${to.label}.

            SOURCE-LANGUAGE CONTEXT: ${from.label} frequently omits subjects and pronouns (it is a pro-drop language). English requires an explicit subject. Infer the implied subject from the line itself, the surrounding blocks, and the provided "previous pairs" context, then choose ONE consistent pronoun and keep it. Never leave a subject ambiguous and never switch person mid-utterance.

            POINT OF VIEW / PERSON (critical):
            - Lines prefixed [$SPEECH_TAG] are CONVERSATION inside a speech bubble: a character speaking aloud to an addressee. The speaker = "I/we", the addressee = "you", anyone else mentioned = "he/she/they". When the subject is omitted and cannot be resolved, a [$SPEECH_TAG] line defaults to the speaker ("I/we") — UNLESS the line is an imperative (often subjectless in English), an offer/question directed at the addressee ("you"), or quoted/reported speech (keep the quoted clause in its original person).
            - Lines with NO [$SPEECH_TAG] tag are narration or self-dialogue (inner monologue). These are VERY OFTEN the point-of-view character's FIRST-PERSON voice (narrating or thinking): use "I" when it reads as a character's own thought or recount. Use third person ONLY for objective external description (scene/location/time, e.g. "Three years later — Tokyo"). Do NOT assume free text is third-person; first-person narration and self-dialogue are the common case.
            - If a character refers to themselves by their own name (illeism), convert it to the matching first-person pronoun ("I").
            - Keep the point of view consistent across a scene; use the provided previous pairs for speaker/name/pronoun continuity.

            DEICTICS: directional/location words (来る / 行く / ここ / そこ, or 来 / 去 / 这 / 那) are anchored to the speaker; resolve them consistently with the person you chose.

            READING ORDER: blocks are roughly ordered $dir, but comic layouts are irregular — use narrative judgment to connect adjacent bubbles, not the numbers alone.

            OTHER RULES:
            - Honorifics (-san / -kun / -chan / -sama / -senpai etc.) may be preserved for a character-driven tone or naturalized for a western localization, as fits the dialogue.
            - Sound effects / onomatopoeia: provide standard comic-style equivalents (e.g. "Gasp", "Thud", *rumble*).
            - Script fidelity: if the target is a Latin-script language, do NOT output Japanese/Chinese/Korean characters; localize markers like (笑) to "lol" / "(laugh)".

            THE [$SPEECH_TAG] TAG IS METADATA, NOT TEXT: use it only to choose voice/POV. NEVER include the word "$SPEECH_TAG" or the bracketed tag in your translation.

            FEW-SHOT (source -> target):
            - [$SPEECH_TAG] 行く。 -> I'm going.            (speech -> first person)
            - あの日、彼と出会った。 -> That day, I met him.  (free-text narration/self-dialogue -> first person)
            - 三年後、東京。 -> Three years later — Tokyo.   (objective free text -> impersonal)
            - 彼は来ないと言っていた。 -> He said he wouldn't come. (quoted/reported speech -> keep matrix person)

            $outputRule
        """.trimIndent()
    }
}
