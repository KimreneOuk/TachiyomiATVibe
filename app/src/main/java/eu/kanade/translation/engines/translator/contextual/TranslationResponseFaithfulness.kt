package eu.kanade.translation.engines.translator.contextual

/**
 * TachiyomiAT: best-effort, deterministic checks that classify obvious
 * provider unfaithfulness in strict batch responses (Phase 6 contract §8/§13).
 *
 * Honest scope: a structurally valid but sanitized paraphrase cannot be
 * detected perfectly by parsing. [isStructuralRefusal] catches outright
 * in-protocol refusal prose; [coverageConcern] flags suspicious source
 * coverage for retry/review. Neither claims semantic faithfulness — curated
 * and manual quality evaluation remain the final gate.
 */
object TranslationResponseFaithfulness {

    private val REFUSAL_MARKERS = listOf(
        "i can't assist",
        "i cannot assist",
        "i can't help with",
        "i cannot help with",
        "i can't translate",
        "i cannot translate",
        "i'm sorry, but",
        "i am sorry, but",
        "as an ai language model",
        "as an ai assistant",
        "i'm not able to",
        "i am not able to",
        "violates my guidelines",
        "against my guidelines",
        "i can't provide",
        "i cannot provide",
        "i can't fulfill",
        "i cannot fulfill",
    )

    /**
     * True when [text] is in-protocol refusal/moralizing prose rather than a
     * translation. Checked per accepted result so a single refusing block
     * fails its page instead of promoting refusal text.
     */
    fun isStructuralRefusal(text: String): Boolean {
        val normalized = text.trim().lowercase()
        if (normalized.isEmpty()) return false
        return REFUSAL_MARKERS.any(normalized::contains)
    }

    /**
     * Best-effort source-coverage concern: the target is suspiciously terse
     * relative to a non-trivial source line, which often indicates silent
     * euphemization or dropped content. Purely advisory — callers flag for
     * retry/review and never auto-fail on this signal alone.
     */
    fun coverageConcern(source: String, target: String): Boolean {
        val sourceChars = source.trim().length
        val targetChars = target.trim().length
        if (sourceChars < 12 || targetChars == 0) return false
        // CJK sources translate to more Latin characters than source chars; a
        // target under a quarter of the source length is worth a second look.
        val sourceIsCjk = source.any { ch ->
            Character.UnicodeBlock.of(ch).let { block ->
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                    block == Character.UnicodeBlock.HIRAGANA ||
                    block == Character.UnicodeBlock.KATAKANA ||
                    block == Character.UnicodeBlock.HANGUL_SYLLABLES
            }
        }
        val floor = if (sourceIsCjk) sourceChars * 0.25 else sourceChars * 0.2
        return targetChars < floor
    }
}
