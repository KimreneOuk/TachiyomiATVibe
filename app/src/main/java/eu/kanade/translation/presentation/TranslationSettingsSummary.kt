package eu.kanade.translation.presentation

import eu.kanade.translation.engines.translator.AiTranslatorKind
import eu.kanade.translation.engines.translator.StandardTranslatorKind
import eu.kanade.translation.engines.vision.ocr.OcrModelCatalog
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.domain.translation.TranslationPreferences

/**
 * Read-only snapshot of the active translation configuration, used by the
 * pre-translation confirmation popup. All fields carry ready-to-render labels;
 * the composable never re-derives anything, so this object is the single
 * source of truth for "what will run".
 *
 * [inpaintingMode] is kept as the raw stored value ("FAST"/"QUALITY") because
 * resource strings are not reachable from pure JVM code; the composable maps
 * it through [tachiyomi.i18n.at.ATMR.strings.pref_inpainting_mode_*].
 *
 * [modelId] and [maxOutputTokens] are non-null only for the [TranslationEngineCategory.AI_MODEL]
 * category, where they are meaningful. They are null for STANDARD so the popup
 * omits those rows entirely instead of showing "n/a".
 *
 * This helper is ★ pure JVM-testable logic (no Android/Bitmap/ONNX/ML Kit
 * dependency). It reads but never writes the preference store: language
 * resolution uses label lookups instead of the side-effecting `*.fromPref(...)`
 * helpers, and OCR coercion uses [OcrModelCatalog.coerce] (which is itself
 * side-effect-free — only [OcrModelCatalog.selectedModel] writes corrections),
 * so a transient incompatible value degrades to the default label without
 * rewriting the user's stored preference.
 */
data class TranslationSettingsSummary(
    val fromLanguageLabel: String,
    val toLanguageLabel: String,
    val engineLabel: String,
    val modelId: String?,
    val ocrModelLabel: String,
    val maxOutputTokens: String?,
    val inpaintingMode: String,
    val category: TranslationEngineCategory,
)

/**
 * Resolve the current translation configuration into a display snapshot.
 *
 * Reads but never writes preferences. Unknown source/target language names fall
 * back to the enum defaults (CHINESE / ENGLISH) rather than mutating the store,
 * keeping this function side-effect-free for the UI layer.
 */
fun TranslationPreferences.snapshotTranslationSummary(): TranslationSettingsSummary {
    val category = translationEngineCategory().get()
    val fromLanguage = resolveRecognizerLanguage(translateFromLanguage().get())
    val toLanguage = resolveTranslatorLanguage(translateToLanguage().get())

    // coerce() is read-only, so display never rewrites the stored OCR preference.
    val coercedOcr = OcrModelCatalog.coerce(
        translationOcrModel(fromLanguage.name).get(),
        fromLanguage,
    )
    val ocrModelLabel = OcrModelCatalog.entryFor(coercedOcr)?.label.orEmpty()

    val engineLabel: String
    val modelId: String?
    val maxOutputTokens: String?
    when (category) {
        TranslationEngineCategory.STANDARD -> {
            engineLabel = resolveStandardEngine(translationStandardEngine().get()).label
            modelId = null
            maxOutputTokens = null
        }
        TranslationEngineCategory.AI_MODEL -> {
            val kind = resolveAiEngine(translationAiEngine().get())
            engineLabel = kind.label
            modelId = translationAiModel(kind.engine).get().ifBlank { null }
            maxOutputTokens = translationAiOutputTokens().get().ifBlank { null }
        }
    }

    return TranslationSettingsSummary(
        fromLanguageLabel = fromLanguage.label,
        toLanguageLabel = toLanguage.label,
        engineLabel = engineLabel,
        modelId = modelId,
        ocrModelLabel = ocrModelLabel,
        maxOutputTokens = maxOutputTokens,
        inpaintingMode = translationInpaintingMode().get(),
        category = category,
    )
}

private fun resolveRecognizerLanguage(name: String): TextRecognizerLanguage =
    TextRecognizerLanguage.entries.firstOrNull { it.name.equals(name, true) }
        ?: TextRecognizerLanguage.CHINESE

private fun resolveTranslatorLanguage(name: String): TextTranslatorLanguage =
    TextTranslatorLanguage.entries.firstOrNull { it.name.equals(name, true) }
        ?: TextTranslatorLanguage.ENGLISH

private fun resolveStandardEngine(engine: StandardEngine): StandardTranslatorKind =
    StandardTranslatorKind.entries.firstOrNull { it.name == engine.name }
        ?: StandardTranslatorKind.MLKIT

private fun resolveAiEngine(engine: AiEngine): AiTranslatorKind =
    AiTranslatorKind.entries.firstOrNull { it.engine == engine } ?: AiTranslatorKind.GEMINI
