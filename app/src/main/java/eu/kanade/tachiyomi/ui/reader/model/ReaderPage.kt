package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.translation.model.PageTranslation
import java.io.InputStream

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    var translation : PageTranslation?=null,
    var originalStream: (() -> InputStream)? = null,
    var translatedStream: (() -> InputStream)? = null,
) : Page(index, url, imageUrl, null) {

    open lateinit var chapter: ReaderChapter

    var showTranslatedImage: Boolean = false

    val stream: (() -> InputStream)?
        get() = if (showTranslatedImage && translatedStream != null) translatedStream else originalStream
}
