package eu.kanade.tachiyomi.ui.reader.loader

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.displayImageName
import eu.kanade.translation.diagnostics.ReaderEntryTrace
import eu.kanade.translation.orchestration.TranslationManager
import logcat.LogPriority
import mihon.core.archive.archiveReader
import tachiyomi.core.common.util.system.logcat
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
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val entryStage = ReaderEntryTrace.begin("download.getPages", chapter.chapter.id)
        val dbChapter = chapter.chapter
        var findChapterDirNanos = 0L
        var translationNanos = 0L
        var buildPageListNanos = 0L
        var mappingNanos = 0L
        var chapterPathFound = false
        var translationCount = 0
        var branch = "unresolved"
        var resultCount = 0
        var errorClass = "none"

        try {
            val findChapterDirStartedAt = SystemClock.elapsedRealtimeNanos()
            val chapterPath = try {
                downloadProvider.findChapterDir(dbChapter.name, dbChapter.scanlator, manga.title, source)
                    .also { chapterPathFound = it != null }
            } finally {
                findChapterDirNanos = SystemClock.elapsedRealtimeNanos() - findChapterDirStartedAt
            }

            val translationStartedAt = SystemClock.elapsedRealtimeNanos()
            val translations = try {
                dbChapter.id?.let {
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
            } finally {
                translationNanos = SystemClock.elapsedRealtimeNanos() - translationStartedAt
            }
            translationCount = translations.size

            val pages = if (chapterPath?.isFile == true) {
                branch = "archive"
                getPagesFromArchive(chapterPath, translations)
            } else {
                branch = "directory"
                val result = getPagesFromDirectory(chapterPath, translations)
                buildPageListNanos = result.buildPageListNanos
                mappingNanos = result.mappingNanos
                result.pages
            }
            resultCount = pages.size
            return pages
        } catch (error: Throwable) {
            errorClass = error.javaClass.simpleName
            throw error
        } finally {
            entryStage.end()
            logcat(LogPriority.INFO) {
                "[reader_entry] DownloadPageLoader.getPages " +
                    "chapterId=${dbChapter.id ?: -1L} requestedPage=${chapter.requestedPage} " +
                    "findDirMs=${findChapterDirNanos.toMillis()} handleFound=$chapterPathFound " +
                    "translationMs=${translationNanos.toMillis()} translationCount=$translationCount " +
                    "branch=$branch buildListMs=${buildPageListNanos.toMillis()} " +
                    "mappingMs=${mappingNanos.toMillis()} totalMs=${(SystemClock.elapsedRealtimeNanos() - startedAt).toMillis()} " +
                    "resultCount=$resultCount error=$errorClass"
            }
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

    private fun getPagesFromDirectory(
        chapterDir: UniFile?,
        translations: Map<String, PageTranslation>,
    ): DirectoryPagesResult {
        val buildPageListStartedAt = SystemClock.elapsedRealtimeNanos()
        val pages = downloadManager.buildPageList(chapterDir)
        val buildPageListNanos = SystemClock.elapsedRealtimeNanos() - buildPageListStartedAt
        val mappingStartedAt = SystemClock.elapsedRealtimeNanos()
        val readerPages = pages.map { (fileName, page) ->
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
        return DirectoryPagesResult(
            pages = readerPages,
            buildPageListNanos = buildPageListNanos,
            mappingNanos = SystemClock.elapsedRealtimeNanos() - mappingStartedAt,
        )
    }

    private data class DirectoryPagesResult(
        val pages: List<ReaderPage>,
        val buildPageListNanos: Long,
        val mappingNanos: Long,
    )

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

    private fun Long.toMillis(): Double = this / 1_000_000.0
}
