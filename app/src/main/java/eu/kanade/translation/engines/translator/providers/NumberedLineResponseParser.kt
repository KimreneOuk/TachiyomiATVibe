package eu.kanade.translation.engines.translator.providers
import eu.kanade.translation.engines.translator.TextTranslatorLanguage
import java.util.regex.Pattern

/**
 * Parses the numbered translation format emitted by chat-style LLM translators (DeepSeek, LM Studio)
 * that are prompted with `[index] translation`. Centralized (previously duplicated verbatim in
 * [DeepSeekTranslator] and [LmStudioTranslator]) so a fix propagates from one tested source of truth.
 *
 * STRICT no-fallback contract. The old parser had a positional fallback that assigned
 * unnumbered prose lines to block indices 0, 1, 2, ... when no `[index]` line matched. That silently
 * rescued malformed output, hiding the real failure mode (prose/refusal/partial response still
 * "parsed", leaving blocks with junk or blank and the page slipping through as READY). Now nothing is
 * guessed: a model that doesn't emit `[index] text` contributes nothing, blocks stay blank, and
 * [TranslationBlockValidation] turns the page PARTIAL/FAILED so the cause is visible.
 *
 * Rejection rules (a violating entry is dropped, not guessed):
 *  - no `[index] text` prefix — dropped (no positional fallback)
 *  - index outside `[0, expectedCount)` — dropped
 *  - duplicate index — FIRST occurrence wins, later dropped
 *  - blank translation — dropped
 *  - source-script leakage when [targetLang] is non-CJK — dropped (guards against e.g. `(笑)` echoed
 *    in an English translation). Pass `targetLang = null` to skip (pure-format-only callers).
 */
object NumberedLineResponseParser {

    private val numberedLine = Pattern.compile("^\\[(\\d+)\\]\\s*(.+)$", Pattern.MULTILINE)

    fun parse(
        raw: String,
        expectedCount: Int,
        targetLang: TextTranslatorLanguage? = null,
    ): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        val enforceNoCjkLeak = targetLang != null && !isCjkTarget(targetLang)

        val matcher = numberedLine.matcher(raw)
        while (matcher.find()) {
            val idx = matcher.group(1)!!.toInt()
            val text = matcher.group(2)!!.trim()
            if (idx < 0 || idx >= expectedCount) continue
            if (text.isBlank()) continue
            if (enforceNoCjkLeak && containsCjk(text)) continue
            // First occurrence wins: a duplicate index means the model is confused; overwriting
            // would let a later junk line clobber an earlier good one.
            if (idx in result) continue
            result[idx] = text
        }
        return result
    }

    /**
     * True when [lang] is one of the CJK script languages, for which CJK
     * characters in the output are expected (so the leakage check is skipped).
     */
    private fun isCjkTarget(lang: TextTranslatorLanguage): Boolean = when (lang) {
        TextTranslatorLanguage.JAPANESE,
        TextTranslatorLanguage.KOREAN,
        TextTranslatorLanguage.CHINESESIM,
        TextTranslatorLanguage.CHINESETRAD,
        -> true
        else -> false
    }

    private fun containsCjk(text: String): Boolean {
        for (ch in text) {
            val cp = ch.code
            if (isCjkCodePoint(cp)) return true
        }
        return false
    }

    private fun isCjkCodePoint(cp: Int): Boolean =
        (cp in 0x4E00..0x9FFF) ||
            (cp in 0x3400..0x4DBF) ||
            (cp in 0x20000..0x2A6DF) ||
            (cp in 0x2A700..0x2B73F) ||
            (cp in 0x2B740..0x2B81F) ||
            (cp in 0xF900..0xFAFF) ||
            (cp in 0x2F800..0x2FA1F) ||
            (cp in 0x3000..0x303F) ||
            (cp in 0x3040..0x309F) ||
            (cp in 0x30A0..0x30FF) ||
            (cp in 0x31F0..0x31FF) ||
            (cp in 0xAC00..0xD7AF) ||
            (cp in 0xFF00..0xFFEF) ||
            (cp in 0xFE30..0xFE4F)
}
