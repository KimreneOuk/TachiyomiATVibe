package eu.kanade.translation.translator

/**
 * Declares which contextual operation a translator can perform.
 *
 * A validation-only adapter may parse and account for Pass-1 output, but it must
 * not be selected for Pass-2 contextual review.
 */
enum class ContextualTranslationCapability {
    CONTEXTUAL_REVIEW,
    VALIDATION_ONLY,
}
