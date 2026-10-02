package eu.kanade.translation.engines.translator.providers

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.util.await
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class MLKitTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
) : BaseTranslator() {

    // A small fixed bound keeps the on-device client responsive while avoiding
    // unbounded line fan-out. Tune after line-count measurements are available.
    private val lineWorkerPool = LineTranslationWorkerPool()

    @Volatile
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
        pages.forEach { (_, page) ->
            page.blocks.forEach { block ->
                block.translation = translateBlockLines(activeTranslator, block.text)
            }
        }
    }

    private suspend fun translateBlockLines(activeTranslator: Translator, source: String): String {
        val lines = source.split("\n")
        val nonEmptyLines = lines.mapIndexedNotNull { index, line ->
            if (line.isNotEmpty()) index to line else null
        }

        // Snapshot line strings before suspension; E17a's immutable page-data boundary can then
        // remain separate from this fan-out, with the joined result applied after each block drains.
        val translatedLines = lineWorkerPool.mapOrdered(nonEmptyLines) { (_, line) ->
            currentCoroutineContext().ensureActive()
            val task = activeTranslator.translate(line)
            // Task.await does not cancel ML Kit work; hold the permit until that task drains.
            withContext(NonCancellable) { task.await() }
        }

        val result = lines.toMutableList()
        nonEmptyLines.forEachIndexed { translatedIndex, (lineIndex, _) ->
            result[lineIndex] = translatedLines[translatedIndex]
        }
        return result.joinToString("\n")
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
