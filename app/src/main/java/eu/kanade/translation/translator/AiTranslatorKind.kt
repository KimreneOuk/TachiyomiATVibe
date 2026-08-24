package eu.kanade.translation.translator

import eu.kanade.translation.ocr.TextRecognizerLanguage
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.TranslationPreferences

/**
 * AI model translators. Each provider reads only its own API key and model
 * from [TranslationPreferences] so switching providers keeps credentials and
 * selection isolated.
 */
enum class AiTranslatorKind(val engine: AiEngine, val label: String, val providerName: String) {
    GEMINI(AiEngine.GEMINI, "Gemini AI", "Gemini"),
    OPENROUTER(AiEngine.OPENROUTER, "OpenRouter", "OpenRouter"),
    DEEPSEEK(AiEngine.DEEPSEEK, "DeepSeek", "DeepSeek"),
    LMSTUDIO(AiEngine.LMSTUDIO, "LM Studio", "LM Studio"),
    ;

    fun build(
        pref: TranslationPreferences,
        fromLang: TextRecognizerLanguage,
        toLang: TextTranslatorLanguage,
    ): TextTranslator {
        val apiKey = pref.translationAiApiKey(engine).get()
        val modelName = pref.translationAiModel(engine).get()
        val maxOutputTokens = pref.translationAiOutputTokens().get().toIntOrNull() ?: 8192
        val temperature = pref.translationAiTemperature().get().toFloatOrNull() ?: 0.3f

        // Fail fast with a provider-specific message on a missing key/base URL.
        if (engine == AiEngine.LMSTUDIO) {
            require(pref.translationAiBaseUrlLmStudio().get().isNotBlank()) { "$providerName base URL is required" }
        } else {
            require(apiKey.isNotBlank()) { "$providerName API key is required" }
        }

        return when (this) {
            GEMINI -> GeminiTranslator(
                fromLang,
                toLang,
                apiKey,
                modelName,
                maxOutputTokens,
                temperature,
                pref.translationGeminiThinkingMode().get(),
            )
            OPENROUTER -> OpenRouterTranslator(fromLang, toLang, apiKey, modelName, maxOutputTokens, temperature)
            DEEPSEEK -> DeepSeekTranslator(fromLang, toLang, apiKey, modelName, maxOutputTokens, temperature)
            LMSTUDIO -> LmStudioTranslator(
                fromLang = fromLang,
                toLang = toLang,
                baseUrl = pref.translationAiBaseUrlLmStudio().get(),
                modelName = modelName,
                maxOutputToken = maxOutputTokens,
                temperature = temperature,
            )
        }
    }

    companion object {
        fun fromPref(pref: Preference<AiEngine>): AiTranslatorKind {
            val engine = pref.get()
            val translator = entries.firstOrNull { it.engine == engine }
            return translator ?: GEMINI
        }
    }
}
