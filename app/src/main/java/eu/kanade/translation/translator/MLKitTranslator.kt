package eu.kanade.translation.translator

import eu.kanade.translation.util.await
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.ocr.TextRecognizerLanguage
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

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
        if (closed) {
            logcat(LogPriority.WARN) { "MLKitTranslator.translate() called after close(); skipping" }
            return
        }
        val activeTranslator = translator
        try {
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
        } catch (e: IllegalStateException) {
            // The underlying Translator was closed out from under us (a
            // concurrent rebuild/stop). Leave translations blank for this call
            // rather than crashing the translation pipeline.
            logcat(LogPriority.WARN, e) { "MLKit Translator unavailable mid-translate; skipping" }
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
