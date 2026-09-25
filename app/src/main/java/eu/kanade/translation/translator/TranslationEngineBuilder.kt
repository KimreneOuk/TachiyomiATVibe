package eu.kanade.translation.translator

import eu.kanade.translation.ocr.TextRecognizerLanguage
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Resolves the active [TextTranslator] from the stored engine category and the
 * selected standard/AI engine. Single entry point used by the translation
 * pipeline; it centralises the STANDARD vs AI_MODEL build rule.
 */
object TranslationEngineBuilder {

    fun build(
        pref: TranslationPreferences = Injekt.get(),
        fromLang: TextRecognizerLanguage = TextRecognizerLanguage.fromPref(pref.translateFromLanguage()),
        toLang: TextTranslatorLanguage = TextTranslatorLanguage.fromPref(pref.translateToLanguage()),
    ): TextTranslator {
        return when (pref.translationEngineCategory().get()) {
            TranslationEngineCategory.STANDARD ->
                StandardTranslatorKind.fromPref(pref.translationStandardEngine()).build(pref, fromLang, toLang)
            TranslationEngineCategory.AI_MODEL ->
                AiTranslatorKind.fromPref(pref.translationAiEngine()).build(pref, fromLang, toLang)
        }
    }

    /**
     * True when the active category is STANDARD and the selected standard
     * engine is MLKit. Used to validate MLKit's narrower language coverage.
     */
    fun isMlKitActive(pref: TranslationPreferences = Injekt.get()): Boolean {
        if (pref.translationEngineCategory().get() != TranslationEngineCategory.STANDARD) return false
        return StandardTranslatorKind.fromPref(pref.translationStandardEngine()) == StandardTranslatorKind.MLKIT
    }
}
