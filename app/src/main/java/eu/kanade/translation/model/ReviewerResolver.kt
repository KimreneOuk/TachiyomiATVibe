package eu.kanade.translation.model

import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.TranslationEngineCategory

/**
 * Resolves the effective standalone-revision reviewer engine from durable
 * preference state. Pure (no Android, no IO, no credentials read here) so it is
 * unit-testable on the JVM and reusable by both the reader and manga preflight
 * paths.
 *
 * The configured options are produced by
 * [eu.kanade.translation.TranslationManager.revisionReviewerOptions] (a provider
 * appears only when it has a credential AND a model); this function never
 * recomputes that — it only picks among what the manager already validated.
 */
fun resolveEffectiveReviewerEngine(
    auto: Boolean,
    pass1Category: TranslationEngineCategory,
    pass1AiEngine: AiEngine,
    configuredOptions: List<RevisionReviewerOption>,
    persistedEngine: AiEngine,
): AiEngine {
    // Explicit mode: honor the user's persisted reviewer engine verbatim.
    // Preflight still re-validates that this engine is among the configured
    // options, so a stale/explicit choice correctly rejects with
    // NO_REVIEWER_CONFIGURED rather than silently running.
    if (!auto) return persistedEngine

    // Auto mode: prefer the same AI provider that produced the Pass-1 draft,
    // but only when it is actually configured (credential + model present).
    if (pass1Category == TranslationEngineCategory.AI_MODEL &&
        configuredOptions.any { it.engine == pass1AiEngine }
    ) {
        return pass1AiEngine
    }

    // Pass-1 is non-AI (Google/DeepL/ML Kit), or its AI provider has no
    // credential: fall back to the first configured provider. Option order is
    // the stable enum order from revisionReviewerOptions(), so this is
    // deterministic.
    if (configuredOptions.isNotEmpty()) {
        return configuredOptions.first().engine
    }

    // Nothing is configured. Return the persisted engine so preflight reports a
    // recoverable NO_REVIEWER_CONFIGURED (the user can now resolve it from the
    // new reviewer settings) instead of silently picking a dead default.
    return persistedEngine
}
