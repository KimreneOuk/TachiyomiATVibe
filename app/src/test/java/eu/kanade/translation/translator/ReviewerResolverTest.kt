package eu.kanade.translation.translator

import eu.kanade.translation.model.RevisionReviewerOption
import eu.kanade.translation.model.resolveEffectiveReviewerEngine
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.TranslationEngineCategory

/**
 * Pure tests for [resolveEffectiveReviewerEngine]. No Android, no manager.
 * Covers the four resolution branches and the explicit/auto invariant.
 */
class ReviewerResolverTest {

    private fun option(engine: AiEngine, model: String = "$engine-model") =
        RevisionReviewerOption(engine = engine, model = model, displayLabel = "$engine · $model")

    private val gemini = option(AiEngine.GEMINI)
    private val openrouter = option(AiEngine.OPENROUTER)
    private val deepseek = option(AiEngine.DEEPSEEK)

    @Test
    fun `auto with configured AI Pass-1 follows the translation engine`() {
        val resolved = resolveEffectiveReviewerEngine(
            auto = true,
            pass1Category = TranslationEngineCategory.AI_MODEL,
            pass1AiEngine = AiEngine.OPENROUTER,
            configuredOptions = listOf(gemini, openrouter, deepseek),
            persistedEngine = AiEngine.GEMINI,
        )
        resolved shouldBe AiEngine.OPENROUTER
    }

    @Test
    fun `auto falls back to first configured provider when Pass-1 engine lacks a credential`() {
        // Pass-1 is Gemini but Gemini has no key/model (not in configuredOptions).
        val resolved = resolveEffectiveReviewerEngine(
            auto = true,
            pass1Category = TranslationEngineCategory.AI_MODEL,
            pass1AiEngine = AiEngine.GEMINI,
            configuredOptions = listOf(openrouter, deepseek),
            persistedEngine = AiEngine.GEMINI,
        )
        // Deterministic first-option fallback, not the persisted default.
        resolved shouldBe AiEngine.OPENROUTER
    }

    @Test
    fun `auto falls back to first configured provider when Pass-1 is a non-AI engine`() {
        val resolved = resolveEffectiveReviewerEngine(
            auto = true,
            pass1Category = TranslationEngineCategory.STANDARD,
            pass1AiEngine = AiEngine.GEMINI, // irrelevant for STANDARD
            configuredOptions = listOf(deepseek, openrouter),
            persistedEngine = AiEngine.GEMINI,
        )
        resolved shouldBe AiEngine.DEEPSEEK
    }

    @Test
    fun `auto with no configured provider returns persisted engine so preflight rejects recoverably`() {
        val resolved = resolveEffectiveReviewerEngine(
            auto = true,
            pass1Category = TranslationEngineCategory.AI_MODEL,
            pass1AiEngine = AiEngine.OPENROUTER,
            configuredOptions = emptyList(),
            persistedEngine = AiEngine.GEMINI,
        )
        // No silent dead-default run; manager preflight will reject with
        // NO_REVIEWER_CONFIGURED, which the new reviewer settings can resolve.
        resolved shouldBe AiEngine.GEMINI
    }

    @Test
    fun `explicit mode honors the persisted engine regardless of Pass-1`() {
        val resolved = resolveEffectiveReviewerEngine(
            auto = false,
            pass1Category = TranslationEngineCategory.AI_MODEL,
            pass1AiEngine = AiEngine.OPENROUTER,
            configuredOptions = listOf(openrouter, deepseek),
            persistedEngine = AiEngine.DEEPSEEK,
        )
        resolved shouldBe AiEngine.DEEPSEEK
    }

    @Test
    fun `explicit mode ignores Pass-1 category and configured options`() {
        val resolved = resolveEffectiveReviewerEngine(
            auto = false,
            pass1Category = TranslationEngineCategory.STANDARD,
            pass1AiEngine = AiEngine.OPENROUTER,
            configuredOptions = emptyList(),
            persistedEngine = AiEngine.LMSTUDIO,
        )
        resolved shouldBe AiEngine.LMSTUDIO
    }
}
