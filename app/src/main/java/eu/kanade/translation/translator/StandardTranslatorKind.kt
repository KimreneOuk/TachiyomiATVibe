package eu.kanade.translation.translator

import eu.kanade.translation.ocr.TextRecognizerLanguage
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.translation.StandardEngine

/**
 * Built-in translators that do not use dynamic LLM model selection and require
 * neither API keys nor model ids (Google Translate, on-device ML Kit).
 */
enum class StandardTranslatorKind(val label: String) {
    MLKIT("MlKit (On Device)"),
    GOOGLE("Google Translate"),
    ;

    fun build(
        fromLang: TextRecognizerLanguage,
        toLang: TextTranslatorLanguage,
    ): TextTranslator = when (this) {
        MLKIT -> MLKitTranslator(fromLang, toLang)
        GOOGLE -> GoogleTranslator(fromLang, toLang)
    }

    companion object {
        fun fromPref(pref: Preference<StandardEngine>): StandardTranslatorKind {
            val engine = pref.get()
            val translator = entries.firstOrNull { it.name == engine.name }
            return translator ?: MLKIT
        }
    }
}
