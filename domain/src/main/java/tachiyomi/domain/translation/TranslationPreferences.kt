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
enum class AiEngine { GEMINI, OPENROUTER, DEEPSEEK, LMSTUDIO }

/**
 * OCR backends used before translation. Stored separately from translator
 * engines because OCR reads the source page language, not the target language.
 */
enum class OcrModel { MLKIT, MANGAOCR, PADDLEOCR_V6_SMALL }

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

    fun translationInpaintingMode() = preferenceStore.getString("translation_inpainting_mode", "FAST")

    /**
     * TachiyomiAT: ONNX Runtime execution-provider strategy.
     *
     * Values:
     *   "AUTO"   — pick the safe default for this device (NNAPI where supported,
     *              else CPU). This is the recommended value and the default.
     *   "NNAPI"  — force the NNAPI EP (NPU/GPU/DSP via Android's driver layer).
     *              Useful to opt back in after AUTO chose CPU on a misdetected
     *              device, or to force NNAPI for benchmarking.
     *   "CPU"    — force CPU-only (the legacy behavior). Use if a device's NNAPI
     *              driver is unstable (rare; the runtime also auto-disables
     *              NNAPI for the process after one failure).
     *
     * NOTE: "QNN" is intentionally NOT a value here. QNN (Qualcomm HTP) requires
     * the onnxruntime-android-qnn artifact and QNN-quantized models, neither of
     * which ship yet. It is gated behind [translationExperimentalQnn] below and
     * wired in a future cycle — enabling it without the prerequisites is a
     * silent no-op (see OnnxRuntimeProvider.registerQnnSafely).
     */
    fun translationOnnxEp() = preferenceStore.getString("translation_onnx_ep", "AUTO")

    /**
     * TachiyomiAT: experimental QNN (Qualcomm HTP/NPU) toggle. OFF by default
     * and NOT functional until onnxruntime-android-qnn + QNN-quantized models
     * are shipped. When ON, ChapterTranslator's ONNX sessions will be created
     * with EpStrategy.QNN — which is currently a logged no-op falling back to
     * CPU. Surfaced as a preference now so the integration point is stable.
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
