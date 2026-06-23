package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock

/**
 * TachiyomiAT: post-translate validation for a single page's blocks.
 *
 * Catches the root cause of pages that mixed real translations with untouched
 * source text: an adapter (AI or standard) that returned blank / null / fewer
 * translations than blocks, then silently left `block.translation` empty — which
 * the old renderer papered over with `block.translation.ifBlank { block.text }`,
 * so the source text was drawn as if it were a translation and the page still
 * counted as READY.
 *
 * The renderer no longer falls back to `block.text`, so a blank translation now
 * renders as nothing. This helper classifies the page into one of three
 * outcomes so the pipeline can render the good translations instead of skipping
 * the whole page when a single block failed:
 *  - every source block translated  -> READY  (render normally)
 *  - some translated, some not      -> PARTIAL (still render; missing regions
 *    stay blank on the cleaned image; the page is NOT a retryable failure)
 *  - none translated                -> FAILED (bump retryCount; render skipped)
 *
 * Pure + side-effect-free so it is unit-testable without Android/Bitmap/ONNX.
 * Callers ([TranslationPipeline]) apply the resulting status to the
 * [PageTranslation] and the store.
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
     * Convenience: applies the validation result to [pageTranslation] in place,
     * setting [PageTranslation.translationStatus] + [PageTranslation.errorMessage]
     * and bumping retryCount only when NOTHING was translated. Returns the
     * status it set (READY / PARTIAL / FAILED) so the caller can persist it.
     *
     * Outcome map:
     *  - [TranslationValidationResult.AllTranslated] -> READY, error cleared
     *  - [TranslationValidationResult.Partial] with translatedCount > 0 ->
     *    PARTIAL (rendered, NOT a retryable failure; retryCount untouched so
     *    auto-translate doesn't burn the page's retry budget on a partial),
     *    with a message naming how many blocks were translated
     *  - [TranslationValidationResult.Partial] with translatedCount == 0 ->
     *    FAILED, retryCount bumped, message set (genuine adapter failure).
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
                    pageTranslation.retryCount++
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
