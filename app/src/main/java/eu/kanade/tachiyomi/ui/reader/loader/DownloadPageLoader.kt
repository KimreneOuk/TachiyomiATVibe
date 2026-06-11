package eu.kanade.tachiyomi.ui.reader.loader

import android.app.Application
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.translation.TranslationManager
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.model.PageTranslation
import mihon.core.archive.archiveReader
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

/**
 * Loader used to load a chapter from the downloaded chapters.
 */
internal class DownloadPageLoader(
    private val chapter: ReaderChapter,
    private val manga: Manga,
    private val source: Source,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val translationManager: TranslationManager = Injekt.get(),
) : PageLoader() {

    private val context: Application by injectLazy()
    private val translationProvider: TranslationProvider = Injekt.get()

    private var archivePageLoader: ArchivePageLoader? = null

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        val dbChapter = chapter.chapter
        val chapterPath = downloadProvider.findChapterDir(dbChapter.name, dbChapter.scanlator, manga.title, source)
        val translations = translationManager.getChapterTranslation(
            chapter.chapter.name,
            chapter.chapter.scanlator,
            manga.title,
            source,
        )
        return if (chapterPath?.isFile == true) {
            getPagesFromArchive(chapterPath, translations)
        } else {
            getPagesFromDirectory(translations)
        }
    }

    override fun recycle() {
        super.recycle()
        archivePageLoader?.recycle()
    }

    private suspend fun getPagesFromArchive(
        file: UniFile,
        translations: Map<String, PageTranslation>,
    ): List<ReaderPage> {
        val resolver: ((String, PageTranslation) -> (() -> java.io.InputStream)?)? = { _, pageTranslation ->
            if (pageTranslation.renderedImageName != null) {
                translationManager.getRenderedImageStream(
                    manga.title, source, chapter.chapter.name, chapter.chapter.scanlator,
                    pageTranslation.renderedImageName!!,
                )
            } else if (pageTranslation.cleanedImageName != null) {
                translationManager.getCleanedImageStream(
                    manga.title, source, chapter.chapter.name, chapter.chapter.scanlator,
                    pageTranslation.cleanedImageName!!,
                )
            } else null
        }
        val loader = ArchivePageLoader(file.archiveReader(context), translations, resolver).also { archivePageLoader = it }
        return loader.getPages()
    }

    private fun getPagesFromDirectory(translations: Map<String, PageTranslation>): List<ReaderPage> {
        val pages = downloadManager.buildPageList(source, manga, chapter.chapter.toDomainChapter()!!)
        return pages.map { (fileName, page) ->
            ReaderPage(
                page.index, page.url, page.imageUrl,
                null,
                { context.contentResolver.openInputStream(page.uri ?: Uri.EMPTY)!! },
            ).apply {
                translation = translations[fileName]
                if (translation?.renderedImageName != null) {
                    translatedStream = translationManager.getRenderedImageStream(
                        manga.title, source, chapter.chapter.name, chapter.chapter.scanlator,
                        translation!!.renderedImageName!!,
                    )
                } else if (translation?.cleanedImageName != null) {
                    translatedStream = translationManager.getCleanedImageStream(
                        manga.title, source, chapter.chapter.name, chapter.chapter.scanlator,
                        translation!!.cleanedImageName!!,
                    )
                }
                status = Page.State.READY
            }
        }
    }

    fun resolveTranslatedStream(pageTranslation: PageTranslation): (() -> java.io.InputStream)? {
        if (pageTranslation.renderedImageName != null) {
            return translationManager.getRenderedImageStream(
                manga.title, source, chapter.chapter.name, chapter.chapter.scanlator,
                pageTranslation.renderedImageName!!,
            )
        } else if (pageTranslation.cleanedImageName != null) {
            return translationManager.getCleanedImageStream(
                manga.title, source, chapter.chapter.name, chapter.chapter.scanlator,
                pageTranslation.cleanedImageName!!,
            )
        }
        return null
    }

    override suspend fun loadPage(page: ReaderPage) {
        archivePageLoader?.loadPage(page)
    }
}
