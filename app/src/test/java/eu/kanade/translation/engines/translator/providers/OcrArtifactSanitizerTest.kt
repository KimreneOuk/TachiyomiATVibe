package eu.kanade.translation.engines.translator.providers

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Guards the OCR-artifact sanitizer. OCR of CJK manga frequently misreads glyphs
 * like の as Latin/symbol look-alikes (N°, Nº, №, Ｎ０). These carry no meaning
 * and corrupt the rendered translation, so the translator strips them. The rule
 * set was previously inlined in DeepSeekTranslator; centralizing it makes the
 * three regex passes (before-punct / inline / leading) testable.
 */
class OcrArtifactSanitizerTest {

    @Test
    fun `clean text is returned unchanged`() {
        OcrArtifactSanitizer.sanitize("Hello world") shouldBe "Hello world"
    }

    @Test
    fun `artifact before punctuation is dropped`() {
        OcrArtifactSanitizer.sanitize("Wait N°, what?") shouldBe "Wait, what?"
    }

    @Test
    fun `inline artifact collapses to a single space`() {
        OcrArtifactSanitizer.sanitize("Hello N0 world") shouldBe "Hello world"
    }

    @Test
    fun `leading artifact at the start is dropped`() {
        OcrArtifactSanitizer.sanitize("N0 Start here") shouldBe "Start here"
    }

    @Test
    fun `fullwidth and symbol variants of the artifact are all stripped`() {
        // Ｎ０ (fullwidth), №, Nº — all recognized の-misreads.
        OcrArtifactSanitizer.sanitize("text Ｎ０ mid") shouldBe "text mid"
        OcrArtifactSanitizer.sanitize("Run № now") shouldBe "Run now"
        OcrArtifactSanitizer.sanitize("Said Nº.") shouldBe "Said."
    }

    @Test
    fun `multiple artifacts are all removed`() {
        OcrArtifactSanitizer.sanitize("N0 one N0 two N0") shouldBe "one two"
    }

    @Test
    fun `resulting multi-space runs are collapsed and trimmed`() {
        OcrArtifactSanitizer.sanitize("  N0   spaced   out  ") shouldBe "spaced out"
    }

    @Test
    fun `superscript-zero variant N-superscript-0 is stripped`() {
        // Covers the `[N\\uff2e]\\u2070` alternative (N⁰ / Ｎ⁰).
        OcrArtifactSanitizer.sanitize("text N\u2070 mid") shouldBe "text mid"
    }

    @Test
    fun `artifact at the end of the string is dropped`() {
        // Exercises the `(?=\\s|$)` end-of-string boundary of the inline regex,
        // which is otherwise only positively tested mid-string.
        OcrArtifactSanitizer.sanitize("Hello N0") shouldBe "Hello"
        OcrArtifactSanitizer.sanitize("trail Nº") shouldBe "trail"
    }

    @Test
    fun `artifact glued to a word with no preceding space is NOT stripped`() {
        // Limitation pin: both beforePunctRe and inlineRe require `\s+` before
        // the artifact, so "HelloN0" passes through unchanged. Documented so a
        // future "fix" is a deliberate decision, not an accident.
        OcrArtifactSanitizer.sanitize("HelloN0 world") shouldBe "HelloN0 world"
    }

    @Test
    fun `a leading legacy SPEECH role-tag echoed by the model is stripped`() {
        // Current prompts do not emit this tag, but defensive cleanup keeps old
        // prompt packets or model echoes from reaching the renderer.
        OcrArtifactSanitizer.sanitize("[SPEECH] I'm going.") shouldBe "I'm going."
    }

    @Test
    fun `paren and colon variants of the SPEECH tag are stripped`() {
        OcrArtifactSanitizer.sanitize("(SPEECH) I'm going.") shouldBe "I'm going."
        OcrArtifactSanitizer.sanitize("SPEECH: I'm going.") shouldBe "I'm going."
    }

    @Test
    fun `a SPEECH tag is only stripped at the very start`() {
        // A legitimate translation that happens to contain the word mid-line is
        // left intact — the strip is a leading-prefix rule, not a global one.
        OcrArtifactSanitizer.sanitize("He said SPEECH: now") shouldBe "He said SPEECH: now"
    }

    @Test
    fun `closed XML and markdown thinking blocks are stripped`() {
        OcrArtifactSanitizer.stripThinkingTags("<think>internal</think>Answer") shouldBe "Answer"
        OcrArtifactSanitizer.stripThinkingTags("```thought\ninternal\n```\nAnswer") shouldBe "Answer"
    }

    @Test
    fun `unclosed thinking blocks and stray tags are stripped`() {
        OcrArtifactSanitizer.stripThinkingTags("Answer <think>internal") shouldBe "Answer"
        OcrArtifactSanitizer.stripThinkingTags("Answer </think>") shouldBe "Answer"
        OcrArtifactSanitizer.stripThinkingTags("   ") shouldBe ""
    }
}
