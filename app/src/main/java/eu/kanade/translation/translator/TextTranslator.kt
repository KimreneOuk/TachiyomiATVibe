package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.ocr.TextRecognizerLanguage
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.Closeable

interface TextTranslator : Closeable {
    val fromLang: TextRecognizerLanguage
    val toLang: TextTranslatorLanguage
    suspend fun translate(pages: MutableMap<String, PageTranslation>)

    suspend fun translatePage(pageKey: String, page: PageTranslation) {
        translate(linkedMapOf(pageKey to page))
    }
}

/**
 * Standard translators. These do not use dynamic LLM model selection and
 * require neither API keys nor model ids.
 */
enum class StandardTranslators(val label: String) {
    MLKIT("MlKit (On Device)"),
    GOOGLE("Google Translate");

    fun build(
        fromLang: TextRecognizerLanguage,
        toLang: TextTranslatorLanguage,
    ): TextTranslator = when (this) {
        MLKIT -> MLKitTranslator(fromLang, toLang)
        GOOGLE -> GoogleTranslator(fromLang, toLang)
    }

    companion object {
        fun fromPref(pref: Preference<StandardEngine>): StandardTranslators {
            val engine = pref.get()
            val translator = entries.firstOrNull { it.name == engine.name }
            return translator ?: MLKIT
        }
    }
}

/**
 * AI model translators. Each provider reads only its own API key and model
 * from [TranslationPreferences] so switching providers keeps credentials and
 * selection isolated.
 */
enum class AiTranslators(val engine: AiEngine, val label: String, val providerName: String) {
    GEMINI(AiEngine.GEMINI, "Gemini AI", "Gemini"),
    OPENROUTER(AiEngine.OPENROUTER, "OpenRouter", "OpenRouter"),
    DEEPSEEK(AiEngine.DEEPSEEK, "DeepSeek", "DeepSeek");

    fun build(
        pref: TranslationPreferences,
        fromLang: TextRecognizerLanguage,
        toLang: TextTranslatorLanguage,
    ): TextTranslator {
        val apiKey = pref.translationAiApiKey(engine).get()
        val modelName = pref.translationAiModel(engine).get()
        val maxOutputTokens = pref.translationAiOutputTokens().get().toIntOrNull() ?: 8192
        val temperature = pref.translationAiTemperature().get().toFloatOrNull() ?: 0.3f

        // Fail fast with a clear provider-specific message when the key is
        // missing. DeepSeek already checks this internally; the check here
        // makes the contract uniform across all AI engines.
        require(apiKey.isNotBlank()) { "$providerName API key is required" }

        return when (this) {
            GEMINI -> GeminiTranslator(fromLang, toLang, apiKey, modelName, maxOutputTokens, temperature)
            OPENROUTER -> OpenRouterTranslator(fromLang, toLang, apiKey, modelName, maxOutputTokens, temperature)
            DEEPSEEK -> DeepSeekTranslator(fromLang, toLang, apiKey, modelName, maxOutputTokens, temperature)
        }
    }

    companion object {
        fun fromPref(pref: Preference<AiEngine>): AiTranslators {
            val engine = pref.get()
            val translator = entries.firstOrNull { it.engine == engine }
            return translator ?: GEMINI
        }
    }
}

/**
 * Resolves the active [TextTranslator] from the stored engine category and
 * the selected standard/AI engine. This is the single entry point used by the
 * translation pipeline; it centralises the STANDARD vs AI_MODEL build rule.
 */
object TranslationEngineBuilder {

    fun build(
        pref: TranslationPreferences = Injekt.get(),
        fromLang: TextRecognizerLanguage = TextRecognizerLanguage.fromPref(pref.translateFromLanguage()),
        toLang: TextTranslatorLanguage = TextTranslatorLanguage.fromPref(pref.translateToLanguage()),
    ): TextTranslator {
        return when (pref.translationEngineCategory().get()) {
            TranslationEngineCategory.STANDARD ->
                StandardTranslators.fromPref(pref.translationStandardEngine()).build(fromLang, toLang)
            TranslationEngineCategory.AI_MODEL ->
                AiTranslators.fromPref(pref.translationAiEngine()).build(pref, fromLang, toLang)
        }
    }

    /**
     * True when the active category is STANDARD and the selected standard
     * engine is MLKit. Used to validate MLKit's narrower language coverage.
     */
    fun isMlKitActive(pref: TranslationPreferences = Injekt.get()): Boolean {
        if (pref.translationEngineCategory().get() != TranslationEngineCategory.STANDARD) return false
        return StandardTranslators.fromPref(pref.translationStandardEngine()) == StandardTranslators.MLKIT
    }
}
