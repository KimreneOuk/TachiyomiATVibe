package eu.kanade.translation.engines.translator

import eu.kanade.translation.engines.vision.ocr.TextRecognizerLanguage
import eu.kanade.translation.model.PageTranslation
import java.io.Closeable

/**
 * A text translator: maps page text blocks from [fromLang] to [toLang] in place.
 * Implementations are [Closeable] so their underlying HTTP pools / model
 * sessions can be released on engine rebuild.
 */
interface TextTranslator : Closeable {
    val fromLang: TextRecognizerLanguage
    val toLang: TextTranslatorLanguage
    suspend fun translate(pages: MutableMap<String, PageTranslation>)

    /** Convenience wrapper translating a single page. */
    suspend fun translatePage(pageKey: String, page: PageTranslation) {
        translate(linkedMapOf(pageKey to page))
    }
}
