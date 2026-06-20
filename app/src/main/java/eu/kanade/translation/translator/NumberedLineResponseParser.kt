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
 * Behavior (preserved byte-for-byte from the originals):
 *  1. Match every `^[index] text$` line, indexing the result by [index].
 *  2. If no numbered line matched at all, fall back to assigning the non-blank
 *     lines positionally (0, 1, 2, ...) up to [expectedCount]. This rescues
 *     models that ignore the format but still return one translation per line.
 */
object NumberedLineResponseParser {

    private val numberedLine = Pattern.compile("^\\[(\\d+)\\]\\s*(.+)$", Pattern.MULTILINE)

    fun parse(raw: String, expectedCount: Int): Map<Int, String> {
        val result = mutableMapOf<Int, String>()

        val matcher = numberedLine.matcher(raw)
        while (matcher.find()) {
            val idx = matcher.group(1)!!.toInt()
            val text = matcher.group(2)!!.trim()
            result[idx] = text
        }

        // Format-respecting parse found nothing — fall back to positional lines.
        if (result.isEmpty()) {
            raw.trim().split("\n").forEachIndexed { i, line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && i < expectedCount) {
                    result[i] = trimmed
                }
            }
        }
        return result
    }
}
