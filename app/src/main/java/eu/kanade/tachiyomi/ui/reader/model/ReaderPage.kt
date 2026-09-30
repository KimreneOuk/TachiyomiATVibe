package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.translation.model.PageTranslationView
import java.io.InputStream

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    var translation: PageTranslationView? = null,
    var originalStream: (() -> InputStream)? = null,
    var translatedStream: (() -> InputStream)? = null,
    /**
     * The local on-disk filename of this page (e.g. an archive entry name or a
     * downloaded file name). This is the same key the translator uses to write
     * page updates into the [ChapterTranslationStore], so the reader can match
     * live store updates back to this in-memory page without relying on the
     * unstable [url] or [imageUrl] fallbacks.
     */
    var sourceFileName: String? = null,
) : Page(index, url, imageUrl, null) {

    open lateinit var chapter: ReaderChapter

    var translationStorageKey: String? = null

    var showTranslatedImage: Boolean = false
    var translationToggled: Boolean = false

    val stream: (() -> InputStream)?
        get() = if (showTranslatedImage && translatedStream != null) translatedStream else originalStream
}
