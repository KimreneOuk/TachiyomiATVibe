package eu.kanade.translation.engines.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.recordAttemptFailure

/**
 * TachiyomiAT: post-translate validation for a single page's blocks.
 *
 * Catches pages that mixed real translations with untouched source text: an adapter returning
 * blank/null/fewer translations left `block.translation` empty, which the old renderer papered over
 * with `block.translation.ifBlank { block.text }` — drawing source as if it were a translation and
 * still counting the page as READY. The renderer no longer falls back to `block.text`.
 *
 * Classifies the page so the pipeline can render good translations instead of skipping the whole page
 * when a single block failed:
 *  - every source block translated  -> READY
 *  - some translated, some not      -> PARTIAL (render; missing regions stay blank; NOT retryable)
 *  - none translated                -> FAILED (bump retryCount; render skipped)
 *
 * Pure + side-effect-free so it is unit-testable without Android/Bitmap/ONNX.
 */
object TranslationBlockValidation {

    /**
     * @param allowSourceEqual when false (the default), a non-blank translation
     *   that is identical to the source text also counts as untranslated. This
     *   guards against adapters that echo the source back verbatim and against
     *   any future regression to source-text fallback. Pass true only when the
     *   caller can prove same-language echo is legitimate (never on the
     *   translate path).
     */
    fun evaluate(
        pageTranslation: PageTranslation,
        allowSourceEqual: Boolean = false,
    ): TranslationValidationResult {
        val sourceBlocks = pageTranslation.blocks.filter { it.text.isNotBlank() }
        if (sourceBlocks.isEmpty()) {
            // A page with no OCR'd source text has nothing to translate; that is
            // a clean success (textless page), not a validation failure.
            return TranslationValidationResult.AllTranslated
        }
        val untranslated = sourceBlocks.filter { block ->
            isUntranslated(block, allowSourceEqual)
        }
        return if (untranslated.isEmpty()) {
            TranslationValidationResult.AllTranslated
        } else {
            TranslationValidationResult.Partial(
                translatedCount = sourceBlocks.size - untranslated.size,
                expectedCount = sourceBlocks.size,
            )
        }
    }

    /** True when this block still needs a translation (blank or source-equal). */
    private fun isUntranslated(block: TranslationBlock, allowSourceEqual: Boolean): Boolean {
        if (block.translation.isBlank()) return true
        if (!allowSourceEqual && block.translation.trim() == block.text.trim()) return true
        return false
    }

    /**
     * Convenience: applies the validation result to [pageTranslation] in place, setting
     * [PageTranslation.translationStatus] + [PageTranslation.errorMessage] and bumping retryCount
     * only when NOTHING was translated. Returns the status it set (READY / PARTIAL / FAILED).
     *
     * Outcome map:
     *  - AllTranslated -> READY, error cleared
     *  - Partial with translatedCount > 0 -> PARTIAL (rendered, NOT retryable; retryCount untouched
     *    so auto-translate doesn't burn the page's retry budget on a partial)
     *  - Partial with translatedCount == 0 -> FAILED, retryCount bumped (genuine adapter failure)
     */
    fun applyTo(
        pageTranslation: PageTranslation,
        allowSourceEqual: Boolean = false,
    ): String {
        return when (val result = evaluate(pageTranslation, allowSourceEqual)) {
            TranslationValidationResult.AllTranslated -> {
                pageTranslation.translationStatus = StageStatus.READY
                pageTranslation.errorMessage = null
                StageStatus.READY
            }
            is TranslationValidationResult.Partial -> {
                if (result.translatedCount == 0) {
                    pageTranslation.translationStatus = StageStatus.FAILED
                    // Nothing translated — genuine adapter failure. recordAttemptFailure charges the
                    // attempt exactly once (idempotent within an attempt) so a render cascade can't double-count.
                    pageTranslation.recordAttemptFailure()
                    pageTranslation.errorMessage =
                        "Translation incomplete: 0/${result.expectedCount} " +
                        "blocks translated (missing/blank/source-equal translations are not rendered)"
                    StageStatus.FAILED
                } else {
                    pageTranslation.translationStatus = StageStatus.PARTIAL
                    pageTranslation.errorMessage =
                        "Translation partial: ${result.translatedCount}/${result.expectedCount} " +
                        "blocks translated (missing regions left blank on the cleaned image)"
                    StageStatus.PARTIAL
                }
            }
        }
    }
}

sealed interface TranslationValidationResult {
    /** Every OCR'd source block has a non-blank, non-source-equal translation. */
    data object AllTranslated : TranslationValidationResult

    /**
     * At least one source block is untranslated (blank or source-equal). The
     * caller decides severity: [translatedCount] == 0 is a hard FAILED;
     * translatedCount > 0 is a renderable PARTIAL.
     */
    data class Partial(
        val translatedCount: Int,
        val expectedCount: Int,
    ) : TranslationValidationResult
}
