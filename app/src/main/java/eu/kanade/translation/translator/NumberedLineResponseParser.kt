package eu.kanade.translation.translator

import java.util.regex.Pattern

/**
 * Parses the numbered translation format emitted by chat-style LLM translators
 * (DeepSeek, LM Studio) that are prompted with:
 *
 *   [index] translation
 *
 * Previously this exact parser was duplicated verbatim in [DeepSeekTranslator]
 * and [LmStudioTranslator]; a fix to one never propagated to the other.
 * Centralizing it gives a single tested source of truth.
 *
 * TachiyomiAT: STRICT no-fallback contract. The old parser had a positional
 * fallback that assigned unnumbered prose lines to block indices 0, 1, 2, ...
 * when no `[index]` line matched. That fallback silently rescued malformed
 * model output (a model that ignored the format but returned one translation
 * per line was treated as if it had obeyed), which hid the real failure mode
 * — a model returning prose, a refusal, or a partial response was still
 * "parsed", leaving some blocks with junk and some blank, and the page
 * slipped through as READY. Under the strict no-fallback policy nothing is
 * ever guessed: a model that does not emit the exact `[index] text` format
 * contributes nothing, the blocks stay blank, and [TranslationBlockValidation]
 * turns the page PARTIAL/FAILED so the cause is visible.
 *
 * Rejection rules (an entry that violates any rule is dropped, not guessed):
 *  - no `[index] text` prefix on the line — dropped (no positional fallback)
 *  - index outside `[0, expectedCount)` — dropped
 *  - duplicate index — the FIRST occurrence wins, later ones dropped
 *  - blank translation — dropped
 *  - source-script leakage when [targetLang] is a non-CJK language — dropped.
 *    Guards against a model echoing e.g. `(笑)` back in an English translation.
 *    Pass `targetLang = null` to skip this check (pure-format-only callers).
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
            // First occurrence wins — a duplicate index means the model is
            // confused, and silently overwriting would let a later junk line
            // clobber an earlier good one.
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
        TextTranslatorLanguage.CHINESETRAD -> true
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
