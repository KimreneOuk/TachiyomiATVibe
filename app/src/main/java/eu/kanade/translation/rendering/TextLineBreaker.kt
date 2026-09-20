package eu.kanade.translation.rendering

/**
 * TachiyomiAT  slice 5: pure, deterministic line breaker for adaptive
 * positioned lines (architecture revision 2, "Line breaking contract").
 *
 * Contract:
 *  1. A forced newline stays forced; whitespace separates; CJK graphemes stay
 *     breakable individually.
 *  2. A source hyphen stays in the displayed text and permits a break
 *     immediately after itself. [prewrap] therefore produces the SAME line
 *     content as [TextLayoutPlanner.cjkWrap] for identical inputs (pinned by a
 *     cross-check test over a mixed corpus).
 *  3. Ordinary/mixed/lowercase Latin tokens are atomic — no hyphen is ever
 *     invented by [prewrap].
 *  4/5. A render-only inserted-hyphen trial is eligible only for pure ASCII
 *     `[A-Z]{8,}` tokens (which subsumes "no digit, no `. / @ _ :`, no
 *     existing hyphen"), keeps at least three letters per segment, trials
 *     EXACTLY {baseline, one balanced break, two balanced breaks}, accepts
 *     only on overflow removal or a >= 15% fitted-font gain, and never
 *     mutates persisted text — trials operate on copies and only affect the
 *     planned lines.
 *
 * Pure JVM: no `android` imports; all measurement goes through the injected
 * [TextMeasurer].
 */
internal object TextLineBreaker {

    /** One token of the breaker stream. */
    internal sealed class Token {
        /** A forced line break (`\n`). */
        object Newline : Token()

        /** A single whitespace character (separator; never displayed verbatim). */
        object Whitespace : Token()

        /**
         * A display word: one CJK grapheme (individually breakable) or a
         * maximal non-CJK, non-whitespace run that stops right after a source
         * hyphen (the hyphen stays attached to the run).
         */
        data class Word(val text: String) : Token()
    }

    /**
     * Tokenize [text] exactly like the legacy `cjkWrap` tokenizer: a newline is
     * a forced-break marker, each whitespace char is a separator, each CJK char
     * is its own word, and a Latin-ish run ends immediately after a `-` that is
     * not the run's first character.
     */
    fun tokenize(text: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '\n' -> {
                    tokens += Token.Newline
                    i++
                }
                ch.isWhitespace() -> {
                    tokens += Token.Whitespace
                    i++
                }
                TextLayoutPlanner.isCJK(ch) -> {
                    tokens += Token.Word(ch.toString())
                    i++
                }
                else -> {
                    val start = i
                    while (i < text.length && !TextLayoutPlanner.isCJK(text[i]) && !text[i].isWhitespace() && text[i] != '\n') {
                        if (text[i] == '-' && i > start) {
                            i++
                            break
                        }
                        i++
                    }
                    tokens += Token.Word(text.substring(start, i))
                }
            }
        }
        return tokens
    }

    /**
     * Greedy wrap with the exact semantics of [TextLayoutPlanner.cjkWrap]:
     * a token that does not fit starts a new line (atomic overflow is kept
     * whole); a line break drops a directly-following separator; trailing
     * separators are trimmed. An empty token stream yields `listOf(text)`.
     */
    fun prewrap(text: String, fontPx: Float, maxWidthPx: Float, measurer: TextMeasurer): List<String> {
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (token in tokenize(text)) {
            when (token) {
                is Token.Newline -> {
                    lines += current.toString()
                    current = StringBuilder()
                }
                is Token.Whitespace -> {
                    val candidate = current.toString() + " "
                    if (measurer.measureTextWidth(candidate, fontPx) > maxWidthPx && current.isNotEmpty()) {
                        lines += current.toString().trimEnd()
                        current = StringBuilder()
                    } else {
                        current.append(' ')
                    }
                }
                is Token.Word -> {
                    val candidate = current.toString() + token.text
                    if (measurer.measureTextWidth(candidate, fontPx) > maxWidthPx && current.isNotEmpty()) {
                        lines += current.toString().trimEnd()
                        current = StringBuilder(token.text)
                    } else {
                        current.append(token.text)
                    }
                }
            }
        }
        if (current.isNotEmpty()) {
            lines += current.toString().trimEnd()
        }
        return if (lines.isEmpty()) listOf(text) else lines
    }

    /**
     * ALL-CAPS render-only trial eligibility: pure ASCII A-Z with length >= 8.
     * Letters-only subsumes the digit / `. / @ _ :` / existing-hyphen bans of
     * the contract; anything lowercase, mixed, CJK, or short is ineligible.
     */
    fun trialEligible(token: String): Boolean {
        if (token.length < TRIAL_MIN_TOKEN_LENGTH) return false
        return token.all { it in 'A'..'Z' }
    }

    /**
     * Run the ALL-CAPS inserted-hyphen trial ONCE for a block on the
     * baseline-resolved [text]. [baselineFontFitter] returns the largest font
     * (floored at [minFontPx]) at which a candidate text fits the conservative
     * target rectangle; a baseline AT [minFontPx] therefore means "could not
     * fit without overflow at the minimum font".
     *
     * Trial set is EXACTLY: baseline, one balanced break, and (when the token
     * length permits two >= 3-letter segments) two balanced breaks. The first
     * eligible token in reading order is trialed; at most two hyphens are
     * inserted (the per-block maximum). A trial is accepted only when it
     * removes baseline overflow (strictly larger fitted font) or improves the
     * final fitted font by >= 15%. Among qualifying trials the largest fitted
     * font wins; ties keep the earlier (fewer-insertion) candidate. Returns
     * the accepted trial text, or null when nothing qualifies — the persisted
     * translation is never mutated.
     */
    fun applyBestHyphenTrial(
        text: String,
        minFontPx: Float,
        baselineFontFitter: (String) -> Float,
    ): String? {
        val token = tokenize(text)
            .filterIsInstance<Token.Word>()
            .firstOrNull { trialEligible(it.text) }?.text ?: return null
        val length = token.length

        val oneBreak = oneBreakPoint(length)?.let { point ->
            text.replaceFirst(token, token.substring(0, point) + "-" + token.substring(point))
        }
        val twoBreak = twoBreakPoints(length)?.let { (first, second) ->
            text.replaceFirst(
                token,
                token.substring(0, first) + "-" + token.substring(first, second) + "-" + token.substring(second),
            )
        }

        val baselineFont = baselineFontFitter(text)
        val baselineOverflows = baselineFont <= minFontPx
        var best: Pair<String, Float>? = null
        for (candidate in listOfNotNull(oneBreak, twoBreak)) {
            val trialFont = baselineFontFitter(candidate)
            val qualifies = (baselineOverflows && trialFont > baselineFont) ||
                trialFont >= TRIAL_MIN_FONT_GAIN * baselineFont
            if (qualifies && (best == null || trialFont > best.second)) {
                best = candidate to trialFont
            }
        }
        return best?.first
    }

    /**
     * One balanced break at `floor(len/2)`, shifted within `[3, len-3]` toward
     * balance when the 3-letter minimum is violated. Always valid for an
     * eligible token (`len >= 8`).
     */
    private fun oneBreakPoint(length: Int): Int? {
        if (length < TRIAL_MIN_TOKEN_LENGTH) return null
        return (length / 2).coerceIn(TRIAL_MIN_SEGMENT, length - TRIAL_MIN_SEGMENT)
    }

    /**
     * Two balanced breaks at `floor(len/3)` / `floor(2len/3)`, each shifted
     * within its feasible range toward balance so every segment keeps at least
     * three letters; null when the length cannot host two 3-letter segments
     * (`len < 9`).
     */
    private fun twoBreakPoints(length: Int): Pair<Int, Int>? {
        if (length < 3 * TRIAL_MIN_SEGMENT) return null // need three >= 3-letter segments
        val first = (length / 3).coerceIn(TRIAL_MIN_SEGMENT, length - 2 * TRIAL_MIN_SEGMENT)
        val second = (2 * length / 3).coerceIn(first + TRIAL_MIN_SEGMENT, length - TRIAL_MIN_SEGMENT)
        return first to second
    }

    /** Minimum letters per trial segment (contract rule 4). */
    internal const val TRIAL_MIN_SEGMENT = 3

    /** Minimum eligible token length (contract rule 4: `[A-Z]{8,}`). */
    internal const val TRIAL_MIN_TOKEN_LENGTH = 8

    /** Minimum relative fitted-font gain for trial acceptance (contract rule 5). */
    internal const val TRIAL_MIN_FONT_GAIN = 1.15f
}
