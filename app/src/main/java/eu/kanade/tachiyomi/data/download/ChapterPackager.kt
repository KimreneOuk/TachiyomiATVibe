package eu.kanade.tachiyomi.data.download

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.domain.manga.model.getComicInfo
import eu.kanade.tachiyomi.data.download.Downloader.PublishedPageFiles
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.diagnostics.BatchDownloadDiagnostics
import eu.kanade.translation.diagnostics.BatchDownloadResult
import eu.kanade.translation.diagnostics.BatchDownloadStage
import eu.kanade.translation.diagnostics.BatchDownloadTraceBoundary
import eu.kanade.translation.diagnostics.BatchDownloadTraceContext
import mihon.core.archive.ZipWriter
import nl.adaptivity.xmlutil.serialization.XML
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.util.storage.DiskUtil.NOMEDIA_FILE
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.interactor.GetTracks

internal data class ChapterFiles(
    val files: List<UniFile>,
    val listingAvailable: Boolean,
)

internal data class ArchiveObservation(
    val entriesListed: Boolean,
    val renamed: Boolean,
)

internal data class DownloadValidation(
    val expected: Int,
    val ready: Int,
    val onDisk: Int?,
    val errorPages: List<Int>,
    val success: Boolean,
)

/**
 * Handles chapter page file collection, validation, metadata generation, and CBZ archiving.
 */
internal class ChapterPackager(
    private val context: Context,
    private val xml: XML,
    private val getCategories: GetCategories,
    private val getTracks: GetTracks,
    private val isPublishedFile: (UniFile) -> Boolean,
) {

    internal fun collectChapterFiles(
        tmpDir: UniFile,
        publishedFiles: Map<Int, PublishedPageFiles>,
    ): ChapterFiles {
        val listedFiles = runCatching { tmpDir.listFiles() }.getOrNull()
        val knownFiles = publishedFiles.values.flatMap { it.files }
        val metadataFiles = listOfNotNull(
            runCatching { tmpDir.findFile(COMIC_INFO_FILE) }.getOrNull(),
        )
        val files = (listedFiles.orEmpty().asList() + knownFiles + metadataFiles)
            .distinctBy { it.name ?: it.uri.toString() }
        return ChapterFiles(files, listingAvailable = listedFiles != null)
    }

    internal fun validateDownload(
        download: Download,
        tmpDir: UniFile,
        traceContext: BatchDownloadTraceContext,
        publishedFiles: Map<Int, PublishedPageFiles> = emptyMap(),
    ): DownloadValidation {
        val pages = download.pages ?: return DownloadValidation(
            expected = 0,
            ready = 0,
            onDisk = null,
            errorPages = emptyList(),
            success = false,
        )
        val downloadPageCount = pages.size
        val readyCount = download.downloadedImages
        val traceGeneration = traceContext.generation(BatchDownloadTraceBoundary.VALIDATION)
        val errorCount = if (traceGeneration != null) {
            pages.count { it.status == Page.State.ERROR }
        } else {
            0
        }
        val errorPages = if (errorCount > 0) {
            pages.asSequence()
                .filter { it.status == Page.State.ERROR }
                .map { it.index }
                .take(BatchDownloadDiagnostics.ERROR_PAGE_LIMIT)
                .toList()
        } else {
            emptyList()
        }

        val countListedFiles = fun(): Int? {
            val files = try {
                tmpDir.listFiles()
            } catch (error: Throwable) {
                tracePathOperation(
                    download.chapter.id,
                    traceContext,
                    null,
                    BatchDownloadStage.DIRECTORY_LIST,
                    BatchDownloadResult.THREW,
                    error,
                )
                return null
            } ?: return null
            return files.count {
                val fileName = it.name.orEmpty()
                when {
                    fileName in listOf(COMIC_INFO_FILE, NOMEDIA_FILE) -> false
                    fileName.endsWith(".tmp") -> false
                    fileName.contains("__") && !fileName.endsWith("__001.jpg") -> false
                    else -> true
                }
            }
        }

        val countPublishedFiles = publishedFiles.values.count { pageFiles ->
            pageFiles.primary?.let(isPublishedFile) == true
        }

        val downloadedImagesCount = when {
            readyCount == downloadPageCount -> {
                val listedCount = countListedFiles()
                when {
                    countPublishedFiles == downloadPageCount -> downloadPageCount
                    listedCount != null -> listedCount
                    else -> countPublishedFiles.takeIf { it > 0 }
                }
            }
            traceGeneration != null -> runCatching(countListedFiles).getOrNull()
            else -> null
        }
        traceGeneration?.let { generation ->
            BatchDownloadDiagnostics.validation(
                chapterId = download.chapter.id,
                generation = generation,
                expected = downloadPageCount,
                ready = readyCount,
                onDisk = downloadedImagesCount,
                errorCount = errorCount,
                errorPages = errorPages,
            )
        }
        return DownloadValidation(
            expected = downloadPageCount,
            ready = readyCount,
            onDisk = downloadedImagesCount,
            errorPages = errorPages,
            success = readyCount == downloadPageCount && (downloadedImagesCount ?: 0) == downloadPageCount,
        )
    }

    internal fun archiveChapter(
        mangaDir: UniFile,
        dirname: String,
        tmpDir: UniFile,
        publishedFiles: Map<Int, PublishedPageFiles>,
    ): ArchiveObservation {
        val zip = mangaDir.createFile("$dirname.cbz${Downloader.TMP_DIR_SUFFIX}")!!
        var entriesListed = false
        ZipWriter(context, zip).use { writer ->
            val chapterFiles = collectChapterFiles(tmpDir, publishedFiles)
            entriesListed = chapterFiles.listingAvailable
            chapterFiles.files.forEach { file ->
                writer.write(file)
            }
        }
        mangaDir.findFile("$dirname.cbz")?.let { existing ->
            if (existing.uri != zip.uri) {
                existing.delete()
            }
        }
        val renamed = zip.renameTo("$dirname.cbz")
        if (renamed) tmpDir.delete()
        return ArchiveObservation(
            entriesListed = entriesListed,
            renamed = renamed,
        )
    }

    internal suspend fun createComicInfoFile(
        dir: UniFile,
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
    ) {
        val categories = getCategories.await(manga.id).map { it.name.trim() }.takeUnless { it.isEmpty() }
        val urls = getTracks.await(manga.id)
            .mapNotNull { track ->
                track.remoteUrl.takeUnless { url -> url.isBlank() }?.trim()
            }
            .plus(source.getChapterUrl(chapter.toSChapter()).trim())
            .distinct()

        val comicInfo = getComicInfo(
            manga,
            chapter,
            urls,
            categories,
            source.name,
        )

        dir.findFile(COMIC_INFO_FILE)?.delete()
        dir.createFile(COMIC_INFO_FILE)!!.openOutputStream().use {
            val comicInfoString = xml.encodeToString(ComicInfo.serializer(), comicInfo)
            it.write(comicInfoString.toByteArray())
        }
    }

    private fun tracePathOperation(
        chapterId: Long,
        traceContext: BatchDownloadTraceContext,
        page: Page?,
        stage: BatchDownloadStage,
        result: BatchDownloadResult,
        error: Throwable? = null,
    ) {
        val generation = traceContext.generation(BatchDownloadTraceBoundary.PAGE) ?: return
        BatchDownloadDiagnostics.pathOperation(
            chapterId = chapterId,
            generation = generation,
            pageIndex = page?.index,
            pageNumber = page?.number,
            stage = stage,
            result = result,
            error = error,
        )
    }
}
