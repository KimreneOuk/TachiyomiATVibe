package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal data class DownloadedPageEntry(
    val name: String,
    val uri: Uri,
)

internal data class DownloadedPageEntriesResult(
    val entries: List<DownloadedPageEntry>,
    val nameReadNanos: Long,
    val nameReadCalls: Int,
    val skippedCount: Int,
    val errorCount: Int,
)

internal fun collectDownloadedPageEntries(
    rawFiles: Array<out UniFile>,
    elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    isImageName: (String) -> Boolean = { ImageUtil.isImage(it) },
): DownloadedPageEntriesResult {
    var nameReadNanos = 0L
    var nameReadCalls = 0
    var skippedCount = 0
    var errorCount = 0
    val entries = rawFiles.mapNotNull { file ->
        try {
            val nameReadStartedAt = elapsedRealtimeNanos()
            nameReadCalls++
            val name = try {
                file.name
            } finally {
                nameReadNanos += elapsedRealtimeNanos() - nameReadStartedAt
            }
            if (name == null || !isImageName(name)) {
                skippedCount++
                return@mapNotNull null
            }
            DownloadedPageEntry(name, file.uri)
        } catch (e: Exception) {
            errorCount++
            skippedCount++
            file.logcat(LogPriority.WARN, e) { "buildPageList: skipping unreadable page entry" }
            null
        }
    }
    return DownloadedPageEntriesResult(
        entries = entries,
        nameReadNanos = nameReadNanos,
        nameReadCalls = nameReadCalls,
        skippedCount = skippedCount,
        errorCount = errorCount,
    )
}

internal fun createDownloadedPageList(entries: List<DownloadedPageEntry>): List<Pair<String, Page>> {
    return entries.sortedBy { it.name }
        .mapIndexed { index, entry ->
            Pair(entry.name, Page(index, uri = entry.uri).apply { status = Page.State.READY })
        }
}

/**
 * This class is used to manage chapter downloads in the application. It must be instantiated once
 * and retrieved through dependency injection. You can use this class to queue new chapters or query
 * downloaded chapters.
 */
class DownloadManager(
    private val context: Context,
    private val provider: DownloadProvider = Injekt.get(),
    private val cache: DownloadCache = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
) {

    /**
     * Downloader whose only task is to download chapters.
     */
    private val downloader = Downloader(context, provider, cache)

    val isRunning: Boolean
        get() = downloader.isRunning

    /**
     * Queue to delay the deletion of a list of chapters until triggered.
     */
    private val pendingDeleter = DownloadPendingDeleter(context)

    val queueState
        get() = downloader.queueState

    // For use by DownloadService only
    fun downloaderStart() = downloader.start()
    fun downloaderStop(reason: String? = null) = downloader.stop(reason)

    val isDownloaderRunning
        get() = DownloadJob.isRunningFlow(context)

    /**
     * Tells the downloader to begin downloads.
     */
    fun startDownloads() {
        if (downloader.isRunning) return

        if (DownloadJob.isRunning(context)) {
            downloader.start()
        } else {
            DownloadJob.start(context)
        }
    }

    /**
     * Tells the downloader to pause downloads.
     */
    fun pauseDownloads() {
        downloader.pause()
        downloader.stop()
    }

    /**
     * Empties the download queue.
     */
    fun clearQueue() {
        downloader.clearQueue()
        downloader.stop()
    }

    /**
     * Returns the download from queue if the chapter is queued for download
     * else it will return null which means that the chapter is not queued for download
     *
     * @param chapterId the chapter to check.
     */
    fun getQueuedDownloadOrNull(chapterId: Long): Download? {
        return queueState.value.find { it.chapter.id == chapterId }
    }

    fun startDownloadNow(chapterId: Long) {
        val existingDownload = getQueuedDownloadOrNull(chapterId)
        // If not in queue try to start a new download
        val toAdd = existingDownload ?: runBlocking { Download.fromChapterId(chapterId) } ?: return
        queueState.value.toMutableList().apply {
            existingDownload?.let { remove(it) }
            add(0, toAdd)
            reorderQueue(this)
        }
        startDownloads()
    }

    /**
     * Reorders the download queue.
     *
     * @param downloads value to set the download queue to
     */
    fun reorderQueue(downloads: List<Download>) {
        downloader.updateQueue(downloads)
    }

    /**
     * Tells the downloader to enqueue the given list of chapters.
     *
     * @param manga the manga of the chapters.
     * @param chapters the list of chapters to enqueue.
     * @param autoStart whether to start the downloader after enqueing the chapters.
     */
    fun downloadChapters(manga: Manga, chapters: List<Chapter>, autoStart: Boolean = true) {
        downloader.queueChapters(manga, chapters, autoStart)
    }

    /**
     * Tells the downloader to enqueue the given list of downloads at the start of the queue.
     *
     * @param downloads the list of downloads to enqueue.
     */
    fun addDownloadsToStartOfQueue(downloads: List<Download>) {
        if (downloads.isEmpty()) return
        queueState.value.toMutableList().apply {
            addAll(0, downloads)
            reorderQueue(this)
        }
        if (!DownloadJob.isRunning(context)) startDownloads()
    }

    /**
     * Builds the page list of a downloaded chapter.
     *
     * @param chapterDir the already resolved downloaded chapter directory.
     * @return the list of pages from the chapter.
     */
    fun buildPageList(chapterDir: UniFile?): List<Pair<String, Page>> {
        val startedAt = SystemClock.elapsedRealtimeNanos()
        var findChapterDirNanos = 0L
        var listFilesNanos = 0L
        var filterNanos = 0L
        var isFileNanos = 0L
        var nameReadNanos = 0L
        var sniffNanos = 0L
        var sortAndMapNanos = 0L
        var rawCount = 0
        var isFileCalls = 0
        var nameReadCalls = 0
        var sniffCalls = 0
        var acceptedCount = 0
        var skippedCount = 0
        var errorCount = 0
        var resultCount = 0
        var errorClass = "none"

        try {
            chapterDir ?: throw Exception(context.stringResource(MR.strings.page_list_empty_error))
            // TachiyomiAT: harden against stale/revoked SAF paths. listFiles() can
            // return null on a revoked tree URI or a moved folder; previously the
            // `!!` chain could throw here, which surfaced as an unhandled reader
            // crash instead of the clean "no pages" error.
            val listFilesStartedAt = SystemClock.elapsedRealtimeNanos()
            val rawFiles = try {
                chapterDir.listFiles().orEmpty()
            } catch (e: Exception) {
                errorCount++
                logcat(LogPriority.WARN, e) {
                    "buildPageList: listFiles() threw for ${chapterDir.filePath}; treating as empty"
                }
                emptyArray()
            }
            listFilesNanos = SystemClock.elapsedRealtimeNanos() - listFilesStartedAt
            rawCount = rawFiles.size

            val filterStartedAt = SystemClock.elapsedRealtimeNanos()
            val entryResult = collectDownloadedPageEntries(rawFiles)
            filterNanos = SystemClock.elapsedRealtimeNanos() - filterStartedAt
            nameReadNanos = entryResult.nameReadNanos
            nameReadCalls = entryResult.nameReadCalls
            skippedCount += entryResult.skippedCount
            errorCount += entryResult.errorCount
            acceptedCount = entryResult.entries.size

            if (entryResult.entries.isEmpty()) {
                throw Exception(context.stringResource(MR.strings.page_list_empty_error))
            }

            val sortAndMapStartedAt = SystemClock.elapsedRealtimeNanos()
            val pages = createDownloadedPageList(entryResult.entries)
            sortAndMapNanos = SystemClock.elapsedRealtimeNanos() - sortAndMapStartedAt
            resultCount = pages.size
            return pages
        } catch (error: Throwable) {
            errorClass = error.javaClass.simpleName
            throw error
        } finally {
            logcat(LogPriority.INFO) {
                "[reader_entry] DownloadManager.buildPageList " +
                    "findDirMs=${findChapterDirNanos.toMillis()} listFilesMs=${listFilesNanos.toMillis()} rawCount=$rawCount " +
                    "isFileMs=${isFileNanos.toMillis()} isFileCalls=$isFileCalls " +
                    "nameReadMs=${nameReadNanos.toMillis()} nameReadCalls=$nameReadCalls " +
                    "sniffMs=${sniffNanos.toMillis()} sniffCalls=$sniffCalls " +
                    "filterMs=${filterNanos.toMillis()} accepted=$acceptedCount skipped=$skippedCount errors=$errorCount " +
                    "sortMapMs=${sortAndMapNanos.toMillis()} totalMs=${(SystemClock.elapsedRealtimeNanos() - startedAt).toMillis()} " +
                    "resultCount=$resultCount error=$errorClass"
            }
        }
    }

    private fun Long.toMillis(): Double = this / 1_000_000.0

    /**
     * Returns true if the chapter is downloaded.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query
     * @param mangaTitle the title of the manga to query.
     * @param sourceId the id of the source of the chapter.
     * @param skipCache whether to skip the directory cache and check in the filesystem.
     */
    fun isChapterDownloaded(
        chapterName: String,
        chapterScanlator: String?,
        mangaTitle: String,
        sourceId: Long,
        skipCache: Boolean = false,
    ): Boolean {
        return cache.isChapterDownloaded(chapterName, chapterScanlator, mangaTitle, sourceId, skipCache)
    }

    /**
     * Returns the amount of downloaded chapters.
     */
    fun getDownloadCount(): Int {
        return cache.getTotalDownloadCount()
    }

    /**
     * Returns the amount of downloaded chapters for a manga.
     *
     * @param manga the manga to check.
     */
    fun getDownloadCount(manga: Manga): Int {
        return cache.getDownloadCount(manga)
    }

    fun cancelQueuedDownloads(downloads: List<Download>) {
        removeFromDownloadQueue(downloads.map { it.chapter })
    }

    /**
     * Deletes the directories of a list of downloaded chapters.
     *
     * @param chapters the list of chapters to delete.
     * @param manga the manga of the chapters.
     * @param source the source of the chapters.
     */
    fun deleteChapters(chapters: List<Chapter>, manga: Manga, source: Source) {
        launchIO {
            val filteredChapters = getChaptersToDelete(chapters, manga)
            if (filteredChapters.isEmpty()) {
                return@launchIO
            }

            removeFromDownloadQueue(filteredChapters)

            val (mangaDir, chapterDirs) = provider.findChapterDirs(filteredChapters, manga, source)
            chapterDirs.forEach { it.delete() }
            cache.removeChapters(filteredChapters, manga)

            // Delete manga directory if empty
            if (mangaDir?.listFiles()?.isEmpty() == true) {
                deleteManga(manga, source, removeQueued = false)
            }
        }
    }

    /**
     * Deletes the directory of a downloaded manga.
     *
     * @param manga the manga to delete.
     * @param source the source of the manga.
     * @param removeQueued whether to also remove queued downloads.
     */
    fun deleteManga(manga: Manga, source: Source, removeQueued: Boolean = true) {
        launchIO {
            if (removeQueued) {
                downloader.removeFromQueue(manga)
            }
            provider.findMangaDir(manga.title, source)?.delete()
            cache.removeManga(manga)

            // Delete source directory if empty
            val sourceDir = provider.findSourceDir(source)
            if (sourceDir?.listFiles()?.isEmpty() == true) {
                sourceDir.delete()
                cache.removeSource(source)
            }
        }
    }

    private fun removeFromDownloadQueue(chapters: List<Chapter>) {
        val wasRunning = downloader.isRunning
        if (wasRunning) {
            downloader.pause()
        }

        downloader.removeFromQueue(chapters)

        if (wasRunning) {
            if (queueState.value.isEmpty()) {
                downloader.stop()
            } else if (queueState.value.isNotEmpty()) {
                downloader.start()
            }
        }
    }

    /**
     * Adds a list of chapters to be deleted later.
     *
     * @param chapters the list of chapters to delete.
     * @param manga the manga of the chapters.
     */
    suspend fun enqueueChaptersToDelete(chapters: List<Chapter>, manga: Manga) {
        pendingDeleter.addChapters(getChaptersToDelete(chapters, manga), manga)
    }

    /**
     * Triggers the execution of the deletion of pending chapters.
     */
    fun deletePendingChapters(protectedChapterIds: Set<Long> = emptySet()) {
        val pendingChapters = pendingDeleter.getPendingChapters()
        for ((manga, chapters) in pendingChapters) {
            val source = sourceManager.get(manga.source) ?: continue
            val (protected, deletable) = chapters.partition { it.id in protectedChapterIds }
            if (protected.isNotEmpty()) {
                // The pending-deletion store is consumed as a batch. Reinsert
                // chapters that a live/paused translation still owns so a
                // later reader finish can retry the convenience deletion.
                pendingDeleter.addChapters(protected, manga)
            }
            if (deletable.isNotEmpty()) {
                deleteChapters(deletable, manga, source)
            }
        }
    }

    /**
     * Renames source download folder
     *
     * @param oldSource the old source.
     * @param newSource the new source.
     */
    fun renameSource(oldSource: Source, newSource: Source) {
        val oldFolder = provider.findSourceDir(oldSource) ?: return
        val newName = provider.getSourceDirName(newSource)

        if (oldFolder.name == newName) return

        val capitalizationChanged = oldFolder.name.equals(newName, ignoreCase = true)
        if (capitalizationChanged) {
            val tempName = newName + Downloader.TMP_DIR_SUFFIX
            if (!oldFolder.renameTo(tempName)) {
                logcat(LogPriority.ERROR) { "Failed to rename source download folder: ${oldFolder.name}" }
                return
            }
        }

        if (!oldFolder.renameTo(newName)) {
            logcat(LogPriority.ERROR) { "Failed to rename source download folder: ${oldFolder.name}" }
        }
    }

    /**
     * Renames an already downloaded chapter
     *
     * @param source the source of the manga.
     * @param manga the manga of the chapter.
     * @param oldChapter the existing chapter with the old name.
     * @param newChapter the target chapter with the new name.
     */
    suspend fun renameChapter(source: Source, manga: Manga, oldChapter: Chapter, newChapter: Chapter) {
        val oldNames = provider.getValidChapterDirNames(oldChapter.name, oldChapter.scanlator)
        val mangaDir = provider.getMangaDir(manga.title, source)

        // Assume there's only 1 version of the chapter name formats present
        val oldDownload = oldNames.asSequence()
            .mapNotNull { mangaDir.findFile(it) }
            .firstOrNull() ?: return

        var newName = provider.getChapterDirName(newChapter.name, newChapter.scanlator)
        if (oldDownload.isFile && oldDownload.extension == "cbz") {
            newName += ".cbz"
        }

        if (oldDownload.name == newName) return

        if (oldDownload.renameTo(newName)) {
            cache.removeChapter(oldChapter, manga)
            cache.addChapter(newName, mangaDir, manga)
        } else {
            logcat(LogPriority.ERROR) { "Could not rename downloaded chapter: ${oldNames.joinToString()}" }
        }
    }

    private suspend fun getChaptersToDelete(chapters: List<Chapter>, manga: Manga): List<Chapter> {
        // Retrieve the categories that are set to exclude from being deleted on read
        val categoriesToExclude = downloadPreferences.removeExcludeCategories().get().map(String::toLong)

        val categoriesForManga = getCategories.await(manga.id)
            .map { it.id }
            .ifEmpty { listOf(0) }
        val filteredCategoryManga = if (categoriesForManga.intersect(categoriesToExclude).isNotEmpty()) {
            chapters.filterNot { it.read }
        } else {
            chapters
        }

        return if (!downloadPreferences.removeBookmarkedChapters().get()) {
            filteredCategoryManga.filterNot { it.bookmark }
        } else {
            filteredCategoryManga
        }
    }

    fun statusFlow(): Flow<Download> = queueState
        .flatMapLatest { downloads ->
            downloads
                .map { download ->
                    download.statusFlow.drop(1).map { download }
                }
                .merge()
        }
        .onStart {
            emitAll(
                queueState.value.filter { download -> download.status == Download.State.DOWNLOADING }.asFlow(),
            )
        }

    fun progressFlow(): Flow<Download> = queueState
        .flatMapLatest { downloads ->
            downloads
                .map { download ->
                    download.progressFlow.drop(1).map { download }
                }
                .merge()
        }
        .onStart {
            emitAll(
                queueState.value.filter { download -> download.status == Download.State.DOWNLOADING }
                    .asFlow(),
            )
        }
}
