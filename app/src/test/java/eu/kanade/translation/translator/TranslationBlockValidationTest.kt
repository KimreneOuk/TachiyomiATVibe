package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Guards the post-translate validation that catches pages mixing real
 * translations with untranslated source blocks.
 *
 * The renderer no longer falls back to `block.text`, and adapters no longer
 * pre-fill `block.translation` with the source text — so a blank translation
 * renders as nothing. This validation turns an incomplete page into a
 * detectable FAILED (instead of READY) so the chapter status reflects it and
 * the render stage is skipped.
 */
class TranslationBlockValidationTest {

    @Test
    fun `page with every block translated is AllTranslated`() {
        val page = PageTranslation(
            blocks = mutableListOf(
                block(text = "源", translation = "source"),
                block(text = "分", translation = "part"),
            ),
        )

        TranslationBlockValidation.evaluate(page) shouldBe TranslationValidationResult.AllTranslated
    }

    @Test
    fun `blank translation on any source block is Partial`() {
        val page = PageTranslation(
            blocks = mutableListOf(
                block(text = "源", translation = "source"),
                block(text = "分", translation = ""), // model returned nothing
            ),
        )

        val result = TranslationBlockValidation.evaluate(page)
        result.shouldBeInstanceOf<TranslationValidationResult.Partial>()
        result.translatedCount shouldBe 1
        result.expectedCount shouldBe 2
    }

    @Test
    fun `translation identical to source is Partial by default`() {
        // Guards against adapters that echo the source back verbatim and against
        // any future regression to source-text fallback.
        val page = PageTranslation(
            blocks = mutableListOf(
                block(text = "源", translation = "源"), // source-equal
                block(text = "分", translation = "part"),
            ),
        )

        TranslationBlockValidation.evaluate(page) shouldBe
            TranslationValidationResult.Partial(translatedCount = 1, expectedCount = 2)
    }

    @Test
    fun `source-equal translation is allowed when allowSourceEqual is true`() {
        val page = PageTranslation(
            blocks = mutableListOf(
                block(text = "源", translation = "源"),
                block(text = "分", translation = "part"),
            ),
        )

        TranslationBlockValidation.evaluate(page, allowSourceEqual = true) shouldBe
            TranslationValidationResult.AllTranslated
    }

    @Test
    fun `whitespace-only difference between source and translation still counts as source-equal`() {
        val page = PageTranslation(
            blocks = mutableListOf(block(text = "  abc  ", translation = "abc")),
        )

        TranslationBlockValidation.evaluate(page) shouldBe
            TranslationValidationResult.Partial(translatedCount = 0, expectedCount = 1)
    }

    @Test
    fun `textless page (no source blocks) is AllTranslated`() {
        // A page whose OCR found nothing has nothing to translate; that is a
        // clean success, not a validation failure.
        val page = PageTranslation(blocks = mutableListOf())

        TranslationBlockValidation.evaluate(page) shouldBe TranslationValidationResult.AllTranslated
    }

    @Test
    fun `blocks with blank source text are ignored`() {
        // OCR can emit a blank-text block (a degenerate crop); it has nothing to
        // translate and must not poison the page's validation.
        val page = PageTranslation(
            blocks = mutableListOf(
                block(text = "", translation = ""),
                block(text = "源", translation = "source"),
            ),
        )

        TranslationBlockValidation.evaluate(page) shouldBe TranslationValidationResult.AllTranslated
    }

    @Test
    fun `applyTo sets READY and clears error on full translation`() {
        val page = PageTranslation(
            blocks = mutableListOf(block(text = "源", translation = "source")),
            translationStatus = StageStatus.RUNNING,
            errorMessage = "prior",
        )

        val status = TranslationBlockValidation.applyTo(page)

        status shouldBe StageStatus.READY
        page.translationStatus shouldBe StageStatus.READY
    }

    @Test
    fun `applyTo sets PARTIAL and leaves retryCount untouched when some blocks are translated`() {
        // 1 of 2 translated: render the one good block, leave the missing region
        // blank. NOT a retryable failure, so retryCount is not bumped — the page
        // already produced usable output and burning its retry budget on a
        // partial would risk pushing it to exhausted-retries for no benefit.
        val page = PageTranslation(
            blocks = mutableListOf(
                block(text = "源", translation = "source"),
                block(text = "分", translation = ""),
            ),
            translationStatus = StageStatus.RUNNING,
            retryCount = 1,
        )

        val status = TranslationBlockValidation.applyTo(page)

        status shouldBe StageStatus.PARTIAL
        page.translationStatus shouldBe StageStatus.PARTIAL
        page.retryCount shouldBe 1 // unchanged
        page.errorMessage shouldContain "1/2"
    }

    @Test
    fun `applyTo sets FAILED, bumps retryCount, and writes a reason when no block is translated`() {
        // 0 of 2 translated: genuine adapter failure. Skip render, bump the
        // retry budget so auto-translate eventually stops re-queueing a page
        // whose engine is persistently broken.
        val page = PageTranslation(
            blocks = mutableListOf(
                block(text = "源", translation = ""),
                block(text = "分", translation = ""),
            ),
            translationStatus = StageStatus.RUNNING,
            retryCount = 1,
        )

        val status = TranslationBlockValidation.applyTo(page)

        status shouldBe StageStatus.FAILED
        page.translationStatus shouldBe StageStatus.FAILED
        page.retryCount shouldBe 2
        page.errorMessage shouldContain "0/2"
    }

    @Test
    fun `applyTo leaves a textless page READY`() {
        val page = PageTranslation(blocks = mutableListOf())

        TranslationBlockValidation.applyTo(page) shouldBe StageStatus.READY
        page.translationStatus shouldBe StageStatus.READY
    }

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
