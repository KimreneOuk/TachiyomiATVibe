package eu.kanade.translation.rendering

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * T912 slice 5: the pure [TextLineBreaker] — cjkWrap-parity prewrap, token
 * classes, ALL-CAPS trial eligibility, exact balanced split points with the
 * 3-letter minimum, acceptance only on overflow removal or >= 15% font gain,
 * the two-insertion-per-block cap, and persisted-text immutability.
 */
class TextLineBreakerTest {

    /** Deterministic measurement: every char is `charWidth` wide at the given size. */
    private class FakeMeasurer(private val charWidth: Float = 0.6f) : TextMeasurer {
        override fun measureTextWidth(text: String, fontSizePx: Float): Float =
            text.length * charWidth * fontSizePx

        override fun lineHeight(fontSizePx: Float): Float = fontSizePx * 1.2f
    }

    // ---- tokenize ---------------------------------------------------------

    @Test
    fun `newline whitespace and CJK graphemes tokenize per contract`() {
        val tokens = TextLineBreaker.tokenize("AB\nこ x")
        tokens[0] shouldBe TextLineBreaker.Token.Word("AB")
        tokens[1] shouldBe TextLineBreaker.Token.Newline
        tokens[2] shouldBe TextLineBreaker.Token.Word("こ")
        tokens[3] shouldBe TextLineBreaker.Token.Whitespace
        tokens[4] shouldBe TextLineBreaker.Token.Word("x")
    }

    @Test
    fun `a source hyphen ends its token but stays attached`() {
        val tokens = TextLineBreaker.tokenize("NEE-CHAN")
        tokens.size shouldBe 2
        tokens[0] shouldBe TextLineBreaker.Token.Word("NEE-")
        tokens[1] shouldBe TextLineBreaker.Token.Word("CHAN")
    }

    @Test
    fun `a leading hyphen does not end the token`() {
        // '-' at the run start has no preceding letters to separate.
        val tokens = TextLineBreaker.tokenize("-CHAN")
        tokens.single() shouldBe TextLineBreaker.Token.Word("-CHAN")
    }

    // ---- prewrap behavior -------------------------------------------------

    @Test
    fun `forced newline stays forced and whitespace separates`() {
        val m = FakeMeasurer()
        TextLineBreaker.prewrap("AB\nCD", 10f, 1000f, m) shouldBe listOf("AB", "CD")
        TextLineBreaker.prewrap("Hello world", 10f, 30f, m) shouldBe listOf("Hello", "world")
    }

    @Test
    fun `cjk graphemes break individually`() {
        val m = FakeMeasurer()
        // Each glyph is 6px wide at font 10; width 12 fits exactly two glyphs.
        TextLineBreaker.prewrap("こんにちは", 10f, 12f, m) shouldBe listOf("こん", "にち", "は")
    }

    @Test
    fun `ordinary latin tokens are atomic and never hyphenated by prewrap`() {
        val m = FakeMeasurer()
        val lines = TextLineBreaker.prewrap("HANAZUMI", 10f, 30f, m)
        lines shouldBe listOf("HANAZUMI")
        lines.none { it.contains('-') } shouldBe true
    }

    @Test
    fun `prewrap matches cjkWrap line content on a mixed corpus`() {
        val corpus = listOf(
            "NEE-CHAN",
            "HANAZUMI",
            "こんにちは",
            "Hello world",
            "I WAS A FAN!",
            "A\nB",
            "  leading",
            "trailing  ",
            "multi\n\nnew\nlines",
            "CJK mixed 漢字test",
            "test-hyphen-word",
            "-",
            "a-b-c",
            "X".repeat(50),
            "!!!???",
            "今日は良い天気 day です",
            "",
            "   ",
        )
        val m = FakeMeasurer()
        for (text in corpus) {
            for (font in listOf(8f, 12f, 30f)) {
                for (width in listOf(10f, 50f, 200f)) {
                    TextLineBreaker.prewrap(text, font, width, m) shouldBe
                        TextLayoutPlanner.cjkWrap(text, font, width, m)
                }
            }
        }
    }

    // ---- trial eligibility ------------------------------------------------

    @Test
    fun `trial eligibility matrix matches the contract`() {
        TextLineBreaker.trialEligible("AAAAAAAA") shouldBe true
        TextLineBreaker.trialEligible("ABCDEFGHIJ") shouldBe true

        TextLineBreaker.trialEligible("AAAAAAA") shouldBe false // < 8
        TextLineBreaker.trialEligible("AAAaAAAA") shouldBe false // lowercase/mixed
        TextLineBreaker.trialEligible("aaaaaaaa") shouldBe false // lowercase
        TextLineBreaker.trialEligible("AAAAAAAA1") shouldBe false // digit
        TextLineBreaker.trialEligible("AAAA.AAAA") shouldBe false
        TextLineBreaker.trialEligible("AAAA/AAAA") shouldBe false
        TextLineBreaker.trialEligible("AAAA@AAA") shouldBe false
        TextLineBreaker.trialEligible("AAAA_AAAA") shouldBe false
        TextLineBreaker.trialEligible("AAAA:AAAA") shouldBe false
        TextLineBreaker.trialEligible("AAAA-AAAA") shouldBe false // existing hyphen
        TextLineBreaker.trialEligible("こんにちは") shouldBe false // CJK
    }

    // ---- balanced breaks --------------------------------------------------

    @Test
    fun `one balanced break splits at floor(len div 2) keeping the hyphen on the first segment`() {
        val m = FakeMeasurer()
        // Baseline fits at 10; exactly-one-hyphen candidates get 20.
        val fitter = { candidate: String -> if (candidate.count { it == '-' } == 1) 20f else 10f }
        val result = TextLineBreaker.applyBestHyphenTrial("ABCDEFGHIJ", minFontPx = 8f, baselineFontFitter = fitter)
        // len 10 → split at 5: "ABCDE-" + "FGHIJ"; the one-break trial beats the
        // equal-font two-break trial (ties keep fewer insertions).
        result shouldBe "ABCDE-FGHIJ"
    }

    @Test
    fun `two balanced breaks split at floor(len div 3) and floor(2len div 3)`() {
        // Two hyphens fit at 30, one at 20, baseline at 10 → two-break trial wins.
        val fitter = { candidate: String ->
            when (candidate.count { it == '-' }) {
                2 -> 30f
                1 -> 20f
                else -> 10f
            }
        }
        val result = TextLineBreaker.applyBestHyphenTrial("ABCDEFGHIJ", minFontPx = 8f, baselineFontFitter = fitter)
        result shouldBe "ABC-DEF-GHIJ"
    }

    @Test
    fun `two breaks are refused when a segment would keep fewer than three letters`() {
        // len 8 cannot host two 3-letter segments: the two-break trial must not
        // be offered even at an extreme font gain — only the one-break split at
        // floor(8/2) = 4 is available.
        val fitter = { candidate: String ->
            when (candidate.count { it == '-' }) {
                2 -> 100f
                1 -> 20f
                else -> 10f
            }
        }
        val result = TextLineBreaker.applyBestHyphenTrial("ABCDEFGH", minFontPx = 8f, baselineFontFitter = fitter)
        result shouldBe "ABCD-EFGH"
        result!!.count { it == '-' } shouldBe 1
    }

    @Test
    fun `length nine admits exactly two three letter segments`() {
        val fitter = { candidate: String -> if (candidate.count { it == '-' } == 2) 30f else 10f }
        val result = TextLineBreaker.applyBestHyphenTrial("ABCDEFGHI", minFontPx = 8f, baselineFontFitter = fitter)
        result shouldBe "ABC-DEF-GHI"
    }

    // ---- acceptance rules -------------------------------------------------

    @Test
    fun `trial is accepted when it removes overflow at the minimum font`() {
        // Baseline cannot fit at min font (fitter floors at 8); any trial that
        // fits strictly larger removes the overflow. floor(9/2) = 4 →
        // "ABCD-" + "EFGHI"; the equal-font two-break trial loses the tie.
        val fitter = { candidate: String -> if (candidate.contains('-')) 12f else 8f }
        val result = TextLineBreaker.applyBestHyphenTrial("ABCDEFGHI", minFontPx = 8f, baselineFontFitter = fitter)
        result shouldBe "ABCD-EFGHI"
    }

    @Test
    fun `trial is rejected below the 15 percent font gain`() {
        val fitter = { candidate: String -> if (candidate.contains('-')) 22.9f else 20f }
        TextLineBreaker.applyBestHyphenTrial("ABCDEFGHIJ", minFontPx = 8f, baselineFontFitter = fitter) shouldBe null
    }

    @Test
    fun `trial is accepted exactly at the 15 percent font gain`() {
        val fitter = { candidate: String -> if (candidate.contains('-')) 23f else 20f }
        TextLineBreaker.applyBestHyphenTrial("ABCDEFGHIJ", minFontPx = 8f, baselineFontFitter = fitter) shouldBe
            "ABCDE-FGHIJ"
    }

    @Test
    fun `no eligible token yields no trial`() {
        val fitter = { _: String -> 20f }
        TextLineBreaker.applyBestHyphenTrial("aaaaaaaa", minFontPx = 8f, baselineFontFitter = fitter) shouldBe null
        TextLineBreaker.applyBestHyphenTrial("AAA1 AAAA", minFontPx = 8f, baselineFontFitter = fitter) shouldBe null
        TextLineBreaker.applyBestHyphenTrial("HELLO-WORLD", minFontPx = 8f, baselineFontFitter = fitter) shouldBe null
        TextLineBreaker.applyBestHyphenTrial("こんにちは", minFontPx = 8f, baselineFontFitter = fitter) shouldBe null
    }

    @Test
    fun `only the first eligible token in reading order is trialed`() {
        val fitter = { candidate: String -> if (candidate.contains('-')) 30f else 10f }
        val result = TextLineBreaker.applyBestHyphenTrial("keep AAAAAAAA keep BBBBBBBB", minFontPx = 8f, baselineFontFitter = fitter)
        result shouldBe "keep AAAA-AAAA keep BBBBBBBB"
    }

    @Test
    fun `at most two hyphens are inserted per block`() {
        val fitter = { candidate: String -> if (candidate.count { it == '-' } == 2) 30f else 10f }
        val result = TextLineBreaker.applyBestHyphenTrial("ABCDEFGHIJ", minFontPx = 8f, baselineFontFitter = fitter)
        result!!.count { it == '-' } shouldBe 2
    }

    @Test
    fun `persisted text is never mutated by a trial`() {
        val original = "ABCDEFGHIJ"
        val fitter = { candidate: String -> if (candidate.contains('-')) 30f else 10f }
        val result = TextLineBreaker.applyBestHyphenTrial(original, minFontPx = 8f, baselineFontFitter = fitter)
        original shouldBe "ABCDEFGHIJ"
        (result != null && result !== original) shouldBe true
    }
}
