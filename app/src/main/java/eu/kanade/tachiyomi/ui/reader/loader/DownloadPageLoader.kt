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
import eu.kanade.translation.model.displayImageName
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
        val translations = dbChapter.id?.let {
            translationManager.getChapterTranslationForReader(
                it,
                chapter.chapter.name,
                chapter.chapter.scanlator,
                manga.title,
                source,
            )
        } ?: translationManager.getChapterTranslation(
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
        val loader = ArchivePageLoader(
            file.archiveReader(context),
            translations,
            ::resolveTranslatedStream,
        ).also {
            archivePageLoader = it
        }
        return loader.getPages()
    }

    private fun getPagesFromDirectory(translations: Map<String, PageTranslation>): List<ReaderPage> {
        val pages = downloadManager.buildPageList(source, manga, chapter.chapter.toDomainChapter()!!)
        return pages.map { (fileName, page) ->
            val pageTranslation = translations[fileName]
            val stream = pageTranslation?.let(::resolveTranslatedStream)
            ReaderPage(
                page.index,
                page.url,
                page.imageUrl,
                null,
                // TachiyomiAT: null-safe stream open — if the SAF URI is
                // inaccessible (revoked permission, deleted file, etc.), throw
                // an explicit IOException instead of an NPE so the reader's
                // error-handling can surface it gracefully.
                {
                    context.contentResolver.openInputStream(page.uri ?: Uri.EMPTY)
                        ?: throw java.io.IOException(
                            "Cannot open file for downloaded page: $fileName (uri=${page.uri})",
                        )
                },
            ).apply {
                sourceFileName = fileName
                translation = pageTranslation
                if (stream != null) {
                    translatedStream = stream
                    showTranslatedImage = true
                }
                status = Page.State.READY
            }
        }
    }

    fun resolveTranslatedStream(pageTranslation: PageTranslation): (() -> java.io.InputStream)? {
        val displayImageName = pageTranslation.displayImageName
        if (displayImageName != null) {
            return translationManager.getCleanedImageStream(
                manga.title,
                source,
                chapter.chapter.name,
                chapter.chapter.scanlator,
                displayImageName,
                pageKey = pageTranslation.sourceFileName,
                mangaId = manga.id,
                chapterId = chapter.chapter.id,
            )
        }
        return null
    }

    override suspend fun loadPage(page: ReaderPage) {
        archivePageLoader?.loadPage(page)
    }
}
