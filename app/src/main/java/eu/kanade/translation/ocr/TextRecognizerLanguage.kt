package eu.kanade.translation.ocr

import com.google.mlkit.nl.translate.TranslateLanguage
import tachiyomi.core.common.preference.Preference

enum class TextRecognizerLanguage(var code: String, val label: String) {
    CHINESE(TranslateLanguage.CHINESE, "Chinese (trad/sim)"),
    JAPANESE(TranslateLanguage.JAPANESE, "Japanese"),
    KOREAN(TranslateLanguage.KOREAN, "Korean"),
    ENGLISH(TranslateLanguage.ENGLISH, "English"),
    SPANISH(TranslateLanguage.SPANISH, "Spanish"),
    PORTUGUESE(TranslateLanguage.PORTUGUESE, "Portuguese (Brazil)"),
    INDONESIAN(TranslateLanguage.INDONESIAN, "Indonesian"),
    FRENCH(TranslateLanguage.FRENCH, "French"),
    GERMAN(TranslateLanguage.GERMAN, "German"),
    ITALIAN(TranslateLanguage.ITALIAN, "Italian"),
    VIETNAMESE(TranslateLanguage.VIETNAMESE, "Vietnamese"),
    RUSSIAN(TranslateLanguage.RUSSIAN, "Russian"),
    ;

    /**
     * TachiyomiAT: inter-line join used when a multi-line bubble is OCR'd line by
     * line (PaddleOCR rec reads one strip at a time). CJK ideograph lines
     * concatenate with no separator; word-based scripts (Latin, Korean, etc.)
     * need a space, otherwise stacked lines fuse into an untranslatable run.
     */
    fun joinSeparator(): String = when (this) {
        CHINESE, JAPANESE -> ""
        else -> " "
    }

    companion object {
        /**
         * TachiyomiAT: STRICT no-fallback. The old code silently rewrote an
         * unknown stored value to CHINESE and returned it — a corrupted or
         * migrated pref quietly picked Chinese as the OCR source language,
         * running the wrong recognizer with no signal. Under the strict
         * policy an invalid value throws; the pipeline's try/catch surfaces
         * it as a FAILED page with a clear "reconfigure translation settings"
         * message so the user fixes the setting instead of getting a silent
         * wrong-language run.
         */
        fun fromPref(pref: Preference<String>): TextRecognizerLanguage {
            val name = pref.get()
            val lang = entries.firstOrNull { it.name.equals(name, true) }
            if (lang == null) {
                throw IllegalArgumentException(
                    "Unknown OCR source language '$name'. Reconfigure translation settings.",
                )
            }
            return lang
        }
    }
}
