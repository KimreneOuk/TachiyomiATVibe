package tachiyomi.domain.translation

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

/**
 * Top-level translation engine family. Standard translators do not use
 * dynamic LLM model selection; AI model translators require a provider
 * API key and a selectable model.
 */
enum class TranslationEngineCategory { STANDARD, AI_MODEL }

/**
 * Standard translators. These do not use dynamic LLM model selection and
 * do not require API keys. Keeps the list scalable for future providers
 * such as Microsoft or Yandex.
 */
enum class StandardEngine { MLKIT, GOOGLE }

/**
 * AI model translators. Each provider has its own API key and model
 * selection so switching providers does not overwrite credentials.
 */
enum class AiEngine { GEMINI, OPENROUTER, DEEPSEEK }

class TranslationPreferences(
    private val preferenceStore: PreferenceStore,
) {

    /**
     * Master gate for reader translation features (per-page translate
     * buttons, auto mode). Defaults to false: the Translation settings sheet
     * exposes a toggle, so translation is opt-in.
     */
    fun translationEnabled() = preferenceStore.getBoolean("translation_enabled", false)

    /**
     * Reader auto-translation: when enabled (and [translationEnabled] is on),
     * the reader proactively translates the current page and pre-processes
     * upcoming pages on each page change. Work is additive (never cancelled
     * on scroll) and clamped to the current chapter.
     */
    fun autoTranslate() = preferenceStore.getBoolean("auto_translate", false)

    /**
     * Number of upcoming pages (n+1 .. n+N) to pre-process ahead of the
     * current page when auto-translation is on. Clamped to 1..5 at call sites.
     */
    fun autoTranslatePrefetchCount() = preferenceStore.getInt("auto_translate_prefetch_count", 2)

    fun autoTranslateAfterDownload() = preferenceStore.getBoolean("auto_translate_after_download", false)
    fun translateFromLanguage() = preferenceStore.getString("translate_language_from", "CHINESE")
    fun translateToLanguage() = preferenceStore.getString("translate_language_to", "ENGLISH")
    fun translationFont() = preferenceStore.getInt("translation_font", 0)

    fun translationInpaintingMode() = preferenceStore.getString("translation_inpainting_mode", "QUALITY")

    fun translationRecentLanguagesFrom() = preferenceStore.getString("translation_recent_languages_from", "")
    fun translationRecentLanguagesTo() = preferenceStore.getString("translation_recent_languages_to", "")

    //region Category and engine selection
    fun translationEngineCategory() = preferenceStore.getEnum("translation_engine_category", TranslationEngineCategory.STANDARD)
    fun translationStandardEngine() = preferenceStore.getEnum("translation_standard_engine", StandardEngine.MLKIT)
    fun translationAiEngine() = preferenceStore.getEnum("translation_ai_engine", AiEngine.GEMINI)
    //endregion

    //region AI-only preferences

    // API keys are private: the __PRIVATE_ prefix excludes them from backups
    // and from any non-masked UI subtitle by convention.
    fun translationAiApiKeyGemini() =
        preferenceStore.getString("__PRIVATE_translation_ai_api_key_gemini", "")
    fun translationAiApiKeyOpenrouter() =
        preferenceStore.getString("__PRIVATE_translation_ai_api_key_openrouter", "")
    fun translationAiApiKeyDeepseek() =
        preferenceStore.getString("__PRIVATE_translation_ai_api_key_deepseek", "")

    fun translationAiApiKey(engine: AiEngine): Preference<String> = when (engine) {
        AiEngine.GEMINI -> translationAiApiKeyGemini()
        AiEngine.OPENROUTER -> translationAiApiKeyOpenrouter()
        AiEngine.DEEPSEEK -> translationAiApiKeyDeepseek()
    }

    fun translationAiModelGemini() = preferenceStore.getString("translation_ai_model_gemini", "gemini-1.5-pro")
    fun translationAiModelOpenrouter() = preferenceStore.getString("translation_ai_model_openrouter", "")
    fun translationAiModelDeepseek() = preferenceStore.getString("translation_ai_model_deepseek", "deepseek-chat")

    fun translationAiModel(engine: AiEngine): Preference<String> = when (engine) {
        AiEngine.GEMINI -> translationAiModelGemini()
        AiEngine.OPENROUTER -> translationAiModelOpenrouter()
        AiEngine.DEEPSEEK -> translationAiModelDeepseek()
    }

    // Recent models are kept private as well to avoid leaking usage hints
    // into backups. Stored as an ordered set (most-recent first within the
    // serialised string).
    fun translationAiRecentModelsGemini() =
        preferenceStore.getString("__PRIVATE_translation_ai_recent_models_gemini", "")
    fun translationAiRecentModelsOpenrouter() =
        preferenceStore.getString("__PRIVATE_translation_ai_recent_models_openrouter", "")
    fun translationAiRecentModelsDeepseek() =
        preferenceStore.getString("__PRIVATE_translation_ai_recent_models_deepseek", "")

    fun translationAiRecentModels(engine: AiEngine): Preference<String> = when (engine) {
        AiEngine.GEMINI -> translationAiRecentModelsGemini()
        AiEngine.OPENROUTER -> translationAiRecentModelsOpenrouter()
        AiEngine.DEEPSEEK -> translationAiRecentModelsDeepseek()
    }

    fun translationAiTemperature() = preferenceStore.getString("translation_ai_temperature", "0.3")
    fun translationAiOutputTokens() = preferenceStore.getString("translation_ai_output_tokens", "8192")

    //endregion

    companion object {
        /** Maximum number of entries kept in the per-provider recent models list. */
        const val RECENT_MODELS_LIMIT = 10
        const val RECENT_LANGUAGES_LIMIT = 3

        /**
         * Serialises a model id list to the stored string form. The first item
         * is the most recently selected model. Duplicate ids are collapsed
         * while preserving order.
         */
        fun encodeRecentModels(models: List<String>): String {
            val seen = LinkedHashSet<String>()
            models.forEach { id -> if (id.isNotBlank()) seen.add(id.trim()) }
            return seen.toList().take(RECENT_MODELS_LIMIT).joinToString("\n")
        }

        /** Deserialises the stored recent models string back into an ordered list. */
        fun decodeRecentModels(value: String): List<String> =
            value.split('\n').map { it.trim() }.filter { it.isNotBlank() }

        fun encodeRecentLanguages(languages: List<String>): String {
            val seen = LinkedHashSet<String>()
            languages.forEach { id -> if (id.isNotBlank()) seen.add(id.trim()) }
            return seen.toList().take(RECENT_LANGUAGES_LIMIT).joinToString("\n")
        }

        fun decodeRecentLanguages(value: String): List<String> =
            value.split('\n').map { it.trim() }.filter { it.isNotBlank() }
    }
}
