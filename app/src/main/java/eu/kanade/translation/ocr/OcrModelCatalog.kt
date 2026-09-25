package eu.kanade.translation.ocr

import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.TranslationPreferences

object OcrModelCatalog {

    data class Entry(
        val model: OcrModel,
        val label: String,
        val supportedLanguages: Set<TextRecognizerLanguage>,
    ) {
        fun supports(language: TextRecognizerLanguage): Boolean {
            return supportedLanguages.isEmpty() || language in supportedLanguages
        }
    }

    val entries: List<Entry> = listOf(
        Entry(
            model = OcrModel.MLKIT,
            label = "ML Kit",
            supportedLanguages = emptySet(),
        ),
        Entry(
            model = OcrModel.PADDLEOCR_V6_SMALL,
            label = "PaddleOCR v6 small",
            supportedLanguages = setOf(
                TextRecognizerLanguage.CHINESE,
                TextRecognizerLanguage.JAPANESE,
                TextRecognizerLanguage.ENGLISH,
            ),
        ),
        Entry(
            model = OcrModel.MANGAOCR,
            label = "MangaOCR",
            supportedLanguages = setOf(TextRecognizerLanguage.JAPANESE),
        ),
    )

    fun entriesFor(language: TextRecognizerLanguage): List<Entry> {
        return entries
            .filter { it.supports(language) }
            .sortedBy { priority(it.model, language) }
    }

    fun entryFor(model: OcrModel): Entry? {
        return entries.firstOrNull { it.model == model }
    }

    fun labelsFor(language: TextRecognizerLanguage): ImmutableMap<OcrModel, String> {
        return entriesFor(language).associate { it.model to it.label }.toImmutableMap()
    }

    fun defaultFor(language: TextRecognizerLanguage): OcrModel {
        return when (language) {
            TextRecognizerLanguage.JAPANESE -> OcrModel.MANGAOCR
            else -> OcrModel.MLKIT
        }
    }

    fun isCompatible(model: OcrModel, language: TextRecognizerLanguage): Boolean {
        return entryFor(model)?.supports(language) == true
    }

    fun coerce(model: OcrModel, language: TextRecognizerLanguage): OcrModel {
        return if (isCompatible(model, language)) model else defaultFor(language)
    }

    fun preferenceFor(
        preferences: TranslationPreferences,
        language: TextRecognizerLanguage,
    ): Preference<OcrModel> {
        return preferences.translationOcrModel(language.name)
    }

    fun selectedModel(
        preferences: TranslationPreferences,
        language: TextRecognizerLanguage,
        persistCorrection: Boolean = true,
    ): OcrModel {
        val pref = preferenceFor(preferences, language)
        val stored = pref.get()
        val corrected = coerce(stored, language)
        if (persistCorrection && corrected != stored) {
            pref.set(corrected)
        }
        return corrected
    }

    private fun priority(model: OcrModel, language: TextRecognizerLanguage): Int {
        return when {
            model == defaultFor(language) -> 0
            model == OcrModel.PADDLEOCR_V6_SMALL -> 1
            else -> 2
        }
    }
}
