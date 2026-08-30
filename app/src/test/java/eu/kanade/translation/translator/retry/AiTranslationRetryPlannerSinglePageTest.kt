package eu.kanade.translation.translator.retry

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Guards [AiTranslationRetryPlanner.untranslatedBlocks] — the single-page
 * analog of `untranslatedPages`. The single-page retry loop in
 * `TranslationPipeline.translateSinglePageInternal` uses this predicate to
 * decide which blocks to re-request after a PARTIAL translation, so it must
 * agree with the batch path's "blank OR source-equal == untranslated" rule.
 */
class AiTranslationRetryPlannerSinglePageTest {

    @Test
    fun `fully translated page has no untranslated blocks`() {
        val page = page(
            block(text = "源", translation = "source"),
            block(text = "分", translation = "part"),
        )

        AiTranslationRetryPlanner.untranslatedBlocks(page) shouldHaveSize 0
    }

    @Test
    fun `blank translation on a source block is untranslated`() {
        val page = page(
            block(text = "源", translation = "source"),
            block(text = "分", translation = ""), // model returned nothing
        )

        val missing = AiTranslationRetryPlanner.untranslatedBlocks(page)
        missing shouldHaveSize 1
        missing[0].text shouldBe "分"
    }

    @Test
    fun `translation identical to source is untranslated`() {
        // Guards against adapters that echo the source back verbatim — those
        // count as a failed translation and must be re-requested.
        val page = page(
            block(text = "源", translation = "源"), // source-equal
            block(text = "分", translation = "part"),
        )

        val missing = AiTranslationRetryPlanner.untranslatedBlocks(page)
        missing shouldHaveSize 1
        missing[0].text shouldBe "源"
    }

    @Test
    fun `blank source text is never reported as untranslated`() {
        // A textless block has nothing to translate — it must not be picked up
        // by the retry predicate (would cause spurious retries on pages with
        // image-only / whitespace-only detections).
        val page = page(
            block(text = "", translation = ""),
            block(text = "源", translation = "source"),
        )

        AiTranslationRetryPlanner.untranslatedBlocks(page) shouldHaveSize 0
    }

    @Test
    fun `translation differing only by surrounding whitespace from source is source-equal`() {
        // trim() comparison — a model that wraps the source in stray spaces
        // (e.g. " 源 ") has not actually translated it.
        val page = page(
            block(text = "源", translation = " 源 "),
        )

        AiTranslationRetryPlanner.untranslatedBlocks(page) shouldHaveSize 1
    }

    private fun page(vararg blocks: TranslationBlock) = PageTranslation(
        blocks = blocks.toMutableList(),
    )

    private fun block(text: String, translation: String) = TranslationBlock(
        text = text,
        translation = translation,
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )
}
