package eu.kanade.translation.translator.providers
import eu.kanade.translation.translator.TextTranslatorLanguage

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.util.await

class MLKitTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
) : BaseTranslator() {

    private var translator = Translation.getClient(
        TranslatorOptions.Builder().setSourceLanguage(fromLang.code)
            .setTargetLanguage(TranslateLanguage.fromLanguageTag(toLang.code) ?: TranslateLanguage.ENGLISH)
            .build(),
    )

    // close() can run concurrently with translate() because the shared translator is rebuilt when
    // the language changes (or released on stop). Once closed, ML Kit throws on further use, so we
    // track closed state and bail out gracefully instead of crashing an in-flight job.
    @Volatile
    private var closed = false

    private var conditions = DownloadConditions.Builder()
        .build()

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        // Strict no-fallback: a closed/unavailable engine is a real failure. The old code silently
        // returned, leaving blocks blank with no visible cause. Throwing lets the pipeline mark the
        // page FAILED so the user sees "ML Kit translator was closed/unavailable", not a blank page.
        if (closed) {
            throw IllegalStateException("ML Kit translator was closed/unavailable")
        }
        val activeTranslator = translator
        activeTranslator.downloadModelIfNeeded(conditions).await()
        pages.forEach { (_, v) ->
            v.blocks.forEach { b ->
                b.translation = b.text.split("\n").map { line ->
                    if (line.isNotEmpty()) {
                        activeTranslator.translate(line).await()
                    } else {
                        ""
                    }
                }.joinToString("\n")
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            translator.close()
        } catch (_: Exception) {
        }
    }
}
