package eu.kanade.translation.rendering

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pins the vertical-vs-horizontal layout decision in [TextLayoutPlanner].
 *
 * TachiyomiAT: the old rule was `text.any(::isCJK)`, which flipped the WHOLE
 * translated block vertical the moment a single CJK glyph appeared. That meant
 * an English translation with one residual Japanese char — e.g. a leaked
 * `(笑)` SFX, or an untranslated name — got its Latin letters stacked
 * top-to-bottom inside a vertical bubble. These tests lock the majority rule
 * so that regression can't return: vertical layout only when CJK chars are the
 * majority of the non-whitespace text.
 *
 * Pure logic tests — call [TextLayoutPlanner.cjkRatio] and
 * [TextLayoutPlanner.shouldRenderVertical] directly; no
 * Android/Bitmap/Canvas is instantiated.
 */
class PageTextRendererDirectionTest {

    @Test
    fun `pure CJK text is vertical`() {
        TextLayoutPlanner.shouldRenderVertical("こんにちは") shouldBe true
        TextLayoutPlanner.shouldRenderVertical("今日は良い天気") shouldBe true
        TextLayoutPlanner.cjkRatio("こんにちは") shouldBe 1.0f
    }

    @Test
    fun `pure Latin text is horizontal`() {
        TextLayoutPlanner.shouldRenderVertical("What's up?") shouldBe false
        TextLayoutPlanner.shouldRenderVertical("Hello, world!") shouldBe false
        TextLayoutPlanner.cjkRatio("What's up?") shouldBe 0.0f
    }

    @Test
    fun `one residual CJK glyph in Latin text stays horizontal`() {
        // The exact regression: (笑) leaked into an English translation must
        // NOT flip the whole line vertical. 1 of 3 non-ws chars is CJK (0.33).
        TextLayoutPlanner.shouldRenderVertical("(笑)") shouldBe false
        TextLayoutPlanner.cjkRatio("(笑)") shouldBe (1f / 3f)
    }

    @Test
    fun `majority CJK with some Latin still goes vertical`() {
        // 5 CJK + 3 Latin non-ws chars (5/8 = 0.625) — a CJK-dominant phrase
        // mixed with a Latin word still stacks vertically (correct for a
        // CJK-target bubble that keeps a loanword in Latin script).
        TextLayoutPlanner.shouldRenderVertical("あいうえお day") shouldBe true
    }

    @Test
    fun `minority CJK with mostly Latin stays horizontal`() {
        // The counter-case: a mostly-English phrase with a couple of CJK chars
        // (2/10 = 0.2) must stay horizontal, even though it contains some CJK.
        TextLayoutPlanner.shouldRenderVertical("今日 is the day") shouldBe false
    }

    @Test
    fun `exact fifty-fifty split defaults to horizontal`() {
        // Tie goes to horizontal: avoid surprising vertical layout on a coin
        // flip. 1 CJK of 2 non-ws chars == 0.5, which is NOT > 0.5.
        TextLayoutPlanner.shouldRenderVertical("Aあ") shouldBe false
    }

    @Test
    fun `whitespace is ignored when computing the ratio`() {
        // Leading/trailing/internal whitespace must not dilute the ratio.
        TextLayoutPlanner.cjkRatio("  こんにちは  ") shouldBe 1.0f
        TextLayoutPlanner.shouldRenderVertical("   ") shouldBe false
    }

    @Test
    fun `blank text is not vertical`() {
        TextLayoutPlanner.shouldRenderVertical("") shouldBe false
        TextLayoutPlanner.cjkRatio("") shouldBe 0.0f
    }
}
