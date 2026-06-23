package eu.kanade.translation.translator

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
) : TextTranslator {

    private var translator = Translation.getClient(
        TranslatorOptions.Builder().setSourceLanguage(fromLang.code)
            .setTargetLanguage(TranslateLanguage.fromLanguageTag(toLang.code) ?: TranslateLanguage.ENGLISH)
            .build(),
    )

    // TachiyomiAT: close() can run concurrently with translate() because the
    // shared translator instance is rebuilt by the pipeline when the language
    // changes (or released on stop()). Once closed, the underlying MLKit
    // Translator throws IllegalStateException on any further use, so we track
    // the closed state and bail out gracefully instead of crashing an in-flight
    // job that still references this instance.
    @Volatile
    private var closed = false

    private var conditions = DownloadConditions.Builder()
        .build()

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        // TachiyomiAT: strict no-fallback. A closed/unavailable ML Kit engine
        // is a real failure — the old code silently returned, leaving every
        // block's translation blank, and the page slipped through as READY
        // (or PARTIAL) with no visible cause. Throwing lets the pipeline's
        // try/catch mark the page FAILED with this message so the user sees
        // "ML Kit translator was closed/unavailable" instead of a blank page.
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
