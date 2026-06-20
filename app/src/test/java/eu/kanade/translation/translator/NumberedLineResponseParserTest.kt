package eu.kanade.translation.translator

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Guards the shared `[index] text` parser used by the chat-style LLM
 * translators (DeepSeek, LM Studio). Extracted from those translators so a fix
 * to the parsing rule lives in one tested place rather than two copy-pasted
 * copies.
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
        // absent and the caller falls back to the original text for them.
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

    @Test
    fun `when no numbered prefix is present, non-blank lines fall back to positional indices`() {
        // A model that ignores the format but still returns one translation per
        // line must not yield an empty map — the positional fallback rescues it.
        val parsed = NumberedLineResponseParser.parse("first\nsecond\nthird", expectedCount = 3)

        parsed shouldBe mapOf(0 to "first", 1 to "second", 2 to "third")
    }

    @Test
    fun `positional fallback stops at expectedCount even if more lines are present`() {
        val parsed = NumberedLineResponseParser.parse("a\nb\nc\nd", expectedCount = 2)

        parsed shouldBe mapOf(0 to "a", 1 to "b")
    }

    @Test
    fun `positional fallback skips blank lines but keeps counting position`() {
        // Mirrors the original: forEachIndexed assigns by line position, and
        // blank lines are skipped (not assigned) but still advance the index.
        val parsed = NumberedLineResponseParser.parse("keep\n\nalso", expectedCount = 3)

        parsed shouldBe mapOf(0 to "keep", 2 to "also")
    }

    @Test
    fun `empty input yields an empty map`() {
        NumberedLineResponseParser.parse("", expectedCount = 3) shouldBe emptyMap()
    }

    @Test
    fun `numbered-line indices above expectedCount are preserved as-is`() {
        // Contract pin: the numbered path does NOT clamp to expectedCount (only
        // the positional fallback does). An out-of-range index is kept, so the
        // caller decides what to do with a model that returns index 5 when only
        // 2 blocks were sent. This is intentional — document it, don't silently
        // change it.
        val parsed = NumberedLineResponseParser.parse("[0] a\n[5] b", expectedCount = 2)

        parsed shouldBe mapOf(0 to "a", 5 to "b")
    }

    @Test
    fun `colliding numbered indices keep the last value`() {
        val parsed = NumberedLineResponseParser.parse("[0] first\n[0] second", expectedCount = 2)

        parsed shouldBe mapOf(0 to "second")
    }
}
