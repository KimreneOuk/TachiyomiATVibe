package eu.kanade.translation.translator

import eu.kanade.translation.util.await
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage

class MLKitTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
) : TextTranslator {

    private var translator = Translation.getClient(
        TranslatorOptions.Builder().setSourceLanguage(fromLang.code)
            .setTargetLanguage(TranslateLanguage.fromLanguageTag(toLang.code) ?: TranslateLanguage.ENGLISH)
            .build(),
    )

    private var conditions = DownloadConditions.Builder()
        .build()

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        translator.downloadModelIfNeeded(conditions).await()
        pages.forEach { (_, v) ->
            v.blocks.forEach { b ->
                b.translation = b.text.split("\n").map { line ->
                    if (line.isNotEmpty()) {
                        translator.translate(line).await()
                    } else {
                        ""
                    }
                }.joinToString("\n")
            }
        }
    }

    override fun close() {
        translator.close()
    }


}
