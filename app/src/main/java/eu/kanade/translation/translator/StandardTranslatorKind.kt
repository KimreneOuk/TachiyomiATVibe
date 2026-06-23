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
        /**
         * TachiyomiAT: STRICT no-fallback. The old code silently returned MLKIT
         * for an unknown stored engine — a corrupted/migrated pref quietly ran
         * on-device ML Kit instead of the configured Google/AI engine. Under
         * the strict policy an invalid value throws; the pipeline's try/catch
         * surfaces it as a FAILED page so the user fixes the setting.
         */
        fun fromPref(pref: Preference<StandardEngine>): StandardTranslatorKind {
            val engine = pref.get()
            val translator = entries.firstOrNull { it.name == engine.name }
            if (translator == null) {
                throw IllegalArgumentException(
                    "Unknown translator engine '${engine.name}'. Reconfigure translation settings.",
                )
            }
            return translator
        }
    }
}
