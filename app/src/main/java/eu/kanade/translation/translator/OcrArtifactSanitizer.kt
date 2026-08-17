package eu.kanade.translation.translator

/**
 * Strips OCR misreads that chat-style LLM translators sometimes leave in their
 * output even when told to omit them.
 *
 * The source text comes from OCR of manga/manhua, and CJK glyphs such as の are
 * frequently mis-scanned as Latin/symbol look-alikes: `N0`, `N°`, `Nº`, `№`,
 * `Ｎ０`. These carry no meaning and corrupt the rendered translation, so they
 * are removed. Originally inlined in [DeepSeekTranslator]; extracted so the rule
 * set is testable and reusable across translators.
 *
 * Pass order matters and mirrors the original. The first step keeps defensive
 * compatibility with older prompts or a model that echoes legacy metadata:
 *  1. echoed legacy `[SPEECH]` role-tag prefix → drop it
 *  2. artifact immediately before punctuation  → drop it
 *  3. artifact mid-line                        → collapse to single space
 *  4. artifact at the very start               → drop it
 *  5. collapse any resulting multi-space runs and trim
 */
object OcrArtifactSanitizer {

    // Class 0 is the CTC blank; the look-alikes below are the common の misreads.
    private const val ARTIFACT = "(?:[N\\uff2e][\\u00ba\\u00b0\\u02da]|[N\\uff2e]\\u2070|\\u2116|\\uff2e\\uff10|N0)"

    // Keep compatibility with older prompt packets or model output that echoes
    // the former input-only role tag. Current prompts do not emit this tag.
    private val leadingSpeechTagRe = Regex("^(?:\\[SPEECH\\]|\\(SPEECH\\)|SPEECH:)\\s*")
    private val beforePunctRe = Regex("\\s+$ARTIFACT(?=[.,!?;:\\-])")
    private val inlineRe = Regex("\\s+$ARTIFACT(?=\\s|$)")
    private val leadingRe = Regex("^$ARTIFACT\\s*")

    fun sanitize(text: String): String {
        var cleaned = leadingSpeechTagRe.replace(text, "")
        cleaned = beforePunctRe.replace(cleaned, "")
        cleaned = inlineRe.replace(cleaned, " ")
        cleaned = leadingRe.replace(cleaned, "")
        cleaned = Regex("\\s{2,}").replace(cleaned, " ").trim()
        return cleaned
    }
}
