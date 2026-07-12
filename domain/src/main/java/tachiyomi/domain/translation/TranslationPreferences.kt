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
 * Standard translators. These do not use dynamic LLM model selection. Most
 * require no credentials; DeepL is the exception (it needs an API key, stored
 * via [translationDeeplApiKey]). Keeps the list scalable for future providers
 * such as Microsoft or Yandex.
 */
enum class StandardEngine { MLKIT, GOOGLE, DEEPL }

/**
 * AI model translators. Each provider has its own API key and model
 * selection so switching providers does not overwrite credentials.
 */
enum class AiEngine { GEMINI, OPENROUTER, DEEPSEEK, LMSTUDIO }

/**
 * OCR backends used before translation. Stored separately from translator
 * engines because OCR reads the source page language, not the target language.
 */
enum class OcrModel { MLKIT, MANGAOCR, PADDLEOCR_V6_SMALL }

/**
 * TachiyomiAT: source reading order for a manga/comic page. Determines how the
 * recognition engine orders detected blocks and which direction the inpainter
 * assumes for text flow.
 *
 * - [AUTO]: derive from the source language (CJK languages -> RTL manga flow,
 *   everything else -> LTR comic flow). The historical default.
 * - [RTL_MANGA]: force right-to-left manga ordering regardless of language.
 * - [LTR_COMIC]: force left-to-right western-comic ordering regardless of
 *   language.
 */
enum class TranslationReadingOrder { AUTO, RTL_MANGA, LTR_COMIC }

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
     * Show a read-only confirmation popup (current source/target language,
     * engine/model, OCR model, output tokens) before the manga-screen batch
     * "Translate chapter" action runs. The reader per-page/on-the-fly path is
     * unaffected. Defaults to true so users review their settings at least
     * once; the popup's "Don't show this again" checkbox and the Translation
     * settings sheet both toggle this off.
     */
    fun translationConfirmPretranslate() = preferenceStore.getBoolean("translation_confirm_pretranslate", true)

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

    fun translateFromLanguage() = preferenceStore.getString("translate_language_from", "CHINESE")
    fun translateToLanguage() = preferenceStore.getString("translate_language_to", "ENGLISH")
    fun translationFont() = preferenceStore.getInt("translation_font", 0)

    fun translationInpaintingMode() = preferenceStore.getString("translation_inpainting_mode", "FAST")

    /**
     * Opt-in fallback: when QUALITY inpainting is selected but the neural AOT
     * model is absent, fall back to FAST instead of failing. Default OFF — keeps
     * the strict QUALITY behaviour unless the user explicitly accepts the
     * quality trade-off.
     */
    fun translationInpaintQualityFallback() = preferenceStore.getBoolean("translation_inpaint_quality_fallback", false)

    /**
     * Legacy ONNX execution-provider preference.
     *
     * Translation ONNX sessions are CPU-only now. This key remains for backward
     * compatibility with existing installs, but runtime code intentionally
     * ignores it. Future NPU support should be a separate Qualcomm QNN/QAIRT
     * backend with converted models, not generic NNAPI.
     */
    fun translationOnnxEp() = preferenceStore.getString("translation_onnx_ep", "AUTO")

    /**
     * Legacy experimental QNN toggle.
     *
     * Kept only so old preference files deserialize cleanly. Current translation
     * ONNX runtime ignores it and always creates CPU sessions.
     */
    fun translationExperimentalQnn() = preferenceStore.getBoolean("translation_experimental_qnn", false)

    fun translationRecentLanguagesFrom() = preferenceStore.getString("translation_recent_languages_from", "")
    fun translationRecentLanguagesTo() = preferenceStore.getString("translation_recent_languages_to", "")

    fun translationOcrModel(languageName: String): Preference<OcrModel> {
        val normalized = languageName.lowercase()
        return preferenceStore.getEnum("translation_ocr_model_$normalized", defaultOcrModel(languageName))
    }

    //region Category and engine selection
    fun translationEngineCategory() = preferenceStore.getEnum("translation_engine_category", TranslationEngineCategory.STANDARD)
    fun translationStandardEngine() = preferenceStore.getEnum("translation_standard_engine", StandardEngine.MLKIT)
    fun translationAiEngine() = preferenceStore.getEnum("translation_ai_engine", AiEngine.GEMINI)

    /**
     * DeepL (Standard engine) API key. DeepL is the only Standard engine that
     * requires credentials. Stored private (excluded from backups / masked in
     * the UI) via the same __PRIVATE_ convention as the AI keys.
     */
    fun translationDeeplApiKey() =
        preferenceStore.getString("__PRIVATE_translation_deepl_api_key", "")
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
        AiEngine.LMSTUDIO -> preferenceStore.getString("__PRIVATE_translation_ai_api_key_lmstudio", "")
    }

    fun translationAiBaseUrlLmStudio() =
        preferenceStore.getString("translation_ai_base_url_lmstudio", "")

    fun translationAiModelGemini() = preferenceStore.getString("translation_ai_model_gemini", "gemini-1.5-pro")
    fun translationAiModelOpenrouter() = preferenceStore.getString("translation_ai_model_openrouter", "")
    fun translationAiModelDeepseek() = preferenceStore.getString("translation_ai_model_deepseek", "deepseek-chat")
    fun translationAiModelLmStudio() = preferenceStore.getString("translation_ai_model_lmstudio", "")

    fun translationAiModel(engine: AiEngine): Preference<String> = when (engine) {
        AiEngine.GEMINI -> translationAiModelGemini()
        AiEngine.OPENROUTER -> translationAiModelOpenrouter()
        AiEngine.DEEPSEEK -> translationAiModelDeepseek()
        AiEngine.LMSTUDIO -> translationAiModelLmStudio()
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
    fun translationAiRecentModelsLmStudio() =
        preferenceStore.getString("__PRIVATE_translation_ai_recent_models_lmstudio", "")

    fun translationAiRecentModels(engine: AiEngine): Preference<String> = when (engine) {
        AiEngine.GEMINI -> translationAiRecentModelsGemini()
        AiEngine.OPENROUTER -> translationAiRecentModelsOpenrouter()
        AiEngine.DEEPSEEK -> translationAiRecentModelsDeepseek()
        AiEngine.LMSTUDIO -> translationAiRecentModelsLmStudio()
    }

    fun translationAiBaseUrl(engine: AiEngine): Preference<String>? = when (engine) {
        AiEngine.LMSTUDIO -> translationAiBaseUrlLmStudio()
        else -> null
    }

    fun translationAiTemperature() = preferenceStore.getString("translation_ai_temperature", "0.3")
    fun translationAiOutputTokens() = preferenceStore.getString("translation_ai_output_tokens", "8192")

    /**
     * TachiyomiAT: opt-in verbose logging for the translation pipeline. When on,
     * the OCR/inpaint/translate/render stages emit per-stage and per-ROI INFO
     * logs to logcat, plus heap snapshots. When off (default), only ERROR-level
     * diagnostics are emitted, so the hot path isn't doing string interpolation
     * + log dispatch on every stage of every page. Useful for debugging a flaky
     * translation run without rebuilding.
     */
    fun translationDiagnostics() = preferenceStore.getBoolean("translation_diagnostics", false)

    /**
     * TachiyomiAT: opt-in "Analytical Mode" for AI translation. When enabled,
     * the translation pipeline assembles extra context for each chunk — past
     * translated pairs from earlier pages (speaker/voice continuity) and future
     * OCR'd-but-not-yet-translated source text (so the model can see what comes
     * next) — and injects it via the sliding-window context planner. Off by
     * default to keep the non-analytical path (and its token budget) unchanged.
     */
    fun translationAnalyticalMode() = preferenceStore.getBoolean("translation_analytical_mode", false)

    /**
     * TachiyomiAT: source-page reading order (RTL manga / LTR comic / auto from
     * language). The recognition engine caches its resolved RTL/LTR decision
     * once per instance, so changing this at runtime forces a recognition
     * rebuild (see the engine-rebuild gate in TranslationPipeline).
     */
    fun translationReadingOrder() = preferenceStore.getEnum("translation_reading_order", TranslationReadingOrder.AUTO)

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

        fun defaultOcrModel(languageName: String): OcrModel {
            return when (languageName.uppercase()) {
                "JAPANESE" -> OcrModel.MANGAOCR
                else -> OcrModel.MLKIT
            }
        }
    }
}
