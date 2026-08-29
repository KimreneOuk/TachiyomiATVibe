package eu.kanade.translation.ocr

import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * TachiyomiAT: shared lazy cache for the `translation_diagnostics` preference,
 * consulted by the OCR engines' per-ROI timing logs ([MangaOcrEngine],
 * [PaddleOcrV6DetEngine], [PaddleOcrV6SmallEngine] — previously three
 * line-for-line identical copies of this read-once flag).
 *
 * The per-ROI timing log fires once per text region (30+ on a text-heavy
 * page), so reading the preference through SharedPreferences on every call
 * would itself be hot-path overhead. The `@Volatile` flags are initialized
 * lazily once per process; the diagnostics pref is not toggled mid-read.
 */
internal object OcrDiagnostics {

    @Volatile
    private var initialized = false

    @Volatile
    private var enabled = false

    fun isEnabled(): Boolean {
        if (initialized) return enabled
        enabled = try {
            Injekt.get<TranslationPreferences>().translationDiagnostics().get()
        } catch (_: Throwable) {
            false
        }
        initialized = true
        return enabled
    }
}
