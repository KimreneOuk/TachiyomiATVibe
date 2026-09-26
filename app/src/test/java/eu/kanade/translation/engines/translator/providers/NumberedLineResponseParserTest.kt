package eu.kanade.translation.engines.translator.providers
import eu.kanade.translation.engines.translator.TextTranslatorLanguage
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Guards the shared `[index] text` parser used by the chat-style LLM
 * translators (DeepSeek, LM Studio).
 *
 * TachiyomiAT: STRICT no-fallback contract. The parser must NEVER guess a
 * block's translation. A model that emits unnumbered prose, a duplicate
 * index, an out-of-range index, a blank value, or source-script leakage
 * contributes nothing for that block — the block stays blank and the page
 * is turned PARTIAL/FAILED by [TranslationBlockValidation] so the failure is
 * visible instead of papered over with junk/source text. These tests pin
 * every one of those rejection rules so the old positional fallback can never
 * be re-introduced.
 */
class NumberedLineResponseParserTest {

    @Test
    fun `numbered lines map to their indices`() {
        val parsed = NumberedLineResponseParser.parse("[0] alpha\n[1] beta\n[2] gamma", expectedCount = 3)

        parsed shouldBe mapOf(
            0 to "alpha",
            1 to "beta",
            2 to "gamma",
        )
    }

    @Test
    fun `out-of-order or sparse indices are preserved as-is`() {
        val parsed = NumberedLineResponseParser.parse("[2] c\n[0] a", expectedCount = 3)

        // The format-respecting path keeps the model's own indices; gaps stay
        // absent and the caller leaves the block blank for them.
        parsed shouldBe mapOf(0 to "a", 2 to "c")
    }

    @Test
    fun `blank lines between numbered lines are ignored`() {
        val parsed = NumberedLineResponseParser.parse("[0] one\n\n[1] two", expectedCount = 2)

        parsed shouldBe mapOf(0 to "one", 1 to "two")
    }

    @Test
    fun `text after the index may contain trailing spaces that are trimmed`() {
        val parsed = NumberedLineResponseParser.parse("[0]    spaced out   ", expectedCount = 1)

        parsed[0] shouldBe "spaced out"
    }

    // ── STRICT: rejection rules (no positional fallback) ────────────────────

    @Test
    fun `unnumbered prose is rejected with no positional fallback`() {
        // The single most important pin: a model that ignores the format and
        // returns one translation per line must yield NOTHING. The old parser
        // would have assigned these to indices 0/1/2 — masking the failure.
        val parsed = NumberedLineResponseParser.parse("first\nsecond\nthird", expectedCount = 3)

        parsed shouldBe emptyMap()
    }

    @Test
    fun `a refusal paragraph is rejected`() {
        val parsed = NumberedLineResponseParser.parse(
            "I'm sorry, but I can't help with translating this content.",
            expectedCount = 4,
        )

        parsed shouldBe emptyMap()
    }

    @Test
    fun `duplicate index keeps the first occurrence`() {
        val parsed = NumberedLineResponseParser.parse("[0] first\n[0] second", expectedCount = 2)

        parsed shouldBe mapOf(0 to "first")
    }

    @Test
    fun `out-of-range indices are dropped`() {
        // The old parser kept out-of-range indices verbatim. Under the strict
        // contract anything outside [0, expectedCount) is dropped — the caller
        // only asked for `expectedCount` blocks, an index beyond that is noise.
        val parsed = NumberedLineResponseParser.parse("[0] a\n[5] b", expectedCount = 2)

        parsed shouldBe mapOf(0 to "a")
    }

    @Test
    fun `blank translation value is dropped`() {
        val parsed = NumberedLineResponseParser.parse("[0] hello\n[1]    ", expectedCount = 2)

        parsed shouldBe mapOf(0 to "hello")
    }

    @Test
    fun `empty input yields an empty map`() {
        NumberedLineResponseParser.parse("", expectedCount = 3) shouldBe emptyMap()
    }

    // ── STRICT: source-script leakage (no-CJK for Latin targets) ────────────

    @Test
    fun `cjk leakage into English target is dropped when targetLang is English`() {
        // (笑) echoed back in an English translation is a leak, not a
        // translation. The strict parser drops it so the block stays blank.
        val parsed = NumberedLineResponseParser.parse(
            "[0] (笑)\n[1] What's up?",
            expectedCount = 2,
            targetLang = TextTranslatorLanguage.ENGLISH,
        )

        parsed shouldBe mapOf(1 to "What's up?")
    }

    @Test
    fun `cjk content is kept when targetLang is a CJK language`() {
        // Japanese -> Chinese (Traditional) translation legitimately contains
        // CJK characters; the leakage check must not fire for CJK targets.
        val parsed = NumberedLineResponseParser.parse(
            "[0] 你好\n[1] 沒事吧？",
            expectedCount = 2,
            targetLang = TextTranslatorLanguage.CHINESETRAD,
        )

        parsed shouldBe mapOf(0 to "你好", 1 to "沒事吧？")
    }

    @Test
    fun `cjk content is kept when targetLang is null (format-only parsing)`() {
        // Callers that don't care about script fidelity (pure-format callers,
        // legacy tests) get the raw strict-parse without the leakage check.
        val parsed = NumberedLineResponseParser.parse("[0] (笑)", expectedCount = 1)

        parsed shouldBe mapOf(0 to "(笑)")
    }
}
