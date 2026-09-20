package eu.kanade.tachiyomi.data.download

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.getComicInfo
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.library.LibraryUpdateNotifier
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.DiskUtil.NOMEDIA_FILE
import eu.kanade.tachiyomi.util.storage.saveTo
import eu.kanade.translation.orchestration.TranslationManager
import eu.kanade.translation.diagnostics.BatchDownloadCause
import eu.kanade.translation.diagnostics.BatchDownloadDiagnostics
import eu.kanade.translation.diagnostics.BatchDownloadQueueResult
import eu.kanade.translation.diagnostics.BatchDownloadResult
import eu.kanade.translation.diagnostics.BatchDownloadStage
import eu.kanade.translation.diagnostics.BatchDownloadTerminalState
import eu.kanade.translation.diagnostics.BatchDownloadTraceBoundary
import eu.kanade.translation.diagnostics.BatchDownloadTraceContext
import eu.kanade.translation.model.TranslationRequestFailureKind
import eu.kanade.translation.onlinePageTranslationKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import mihon.core.archive.ZipWriter
import nl.adaptivity.xmlutil.serialization.XML
import okhttp3.Response
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNow
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * This class is the one in charge of downloading chapters.
 *
 * Its queue contains the list of chapters to download.
 */
class Downloader(
    private val context: Context,
    private val provider: DownloadProvider,
    private val cache: DownloadCache,
    private val sourceManager: SourceManager = Injekt.get(),
    private val chapterCache: ChapterCache = Injekt.get(),
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
    private val xml: XML = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val translationManager: TranslationManager = Injekt.get(),
) {

    /**
     * Files that were durably published for one logical page during the
     * current download. A page can have more than one file when a tall image
     * is split. Keeping the handles avoids depending on a freshly-written SAF
     * directory being immediately enumerable.
     */
    internal data class PublishedPageFiles(
        val files: List<UniFile>,
    ) {
        val primary: UniFile?
            get() = files.firstOrNull()
    }

    /**
     * Store for persisting downloads across restarts.
     */
    private val store = DownloadStore(context)

    /**
     * Queue where active downloads are kept.
     */
    private val _queueState = MutableStateFlow<List<Download>>(emptyList())
    val queueState = _queueState.asStateFlow()

    /**
     * Notifier for the downloader state and progress.
     */
    private val notifier by lazy { DownloadNotifier(context) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloaderJob: Job? = null

    /**
     * Whether the downloader is running.
     */
    val isRunning: Boolean
        get() = downloaderJob?.isActive ?: false

    /**
     * Whether the downloader is paused
     */
    @Volatile
    var isPaused: Boolean = true

    init {
        launchNow {
            val chapters = async { store.restore() }
            addAllToQueue(chapters.await())
            DownloadJob.stop(context)
            // T911 slice 2 (R9): downloader side of the startup reconciliation
            // readiness barrier. No-op for the downloader; the translation
            // manager runs its one-shot pending-request pass once both queues
            // have restored.
            translationManager.onDownloadQueueRestored(
                queueState.value.mapNotNull { it.chapter.id }.toSet(),
            )
        }
    }

    /**
     * Starts the downloader. It doesn't do anything if it's already running or there isn't anything
     * to download.
     *
     * @return true if the downloader is started, false otherwise.
     */
    fun start(): Boolean {
        if (isRunning || queueState.value.isEmpty()) {
            return false
        }

        val pending = queueState.value.filter { it.status != Download.State.DOWNLOADED }
        pending.forEach { if (it.status != Download.State.QUEUE) it.status = Download.State.QUEUE }
        // Milestone M6 (S2): rearm any download-failed translation requests for pending chapters
        pending.forEach { download ->
            download.chapter.id?.let(translationManager::rearmDownloadFailedRequest)
        }

        isPaused = false

        launchDownloaderJob()

        return pending.isNotEmpty()
    }

    /**
     * Stops the downloader.
     */
    fun stop(reason: String? = null) {
        cancelDownloaderJob()
        val interrupted = queueState.value.filter { it.status == Download.State.DOWNLOADING }
        interrupted.forEach { it.status = Download.State.ERROR }
        // T911 slice 2 (R5): a stop that kills in-flight downloads (offline,
        // Wi-Fi policy, generic stop) must reach the pending-request owner, or
        // the batch drawer waits forever. No-op without a pending request.
        interrupted.forEach { download ->
            translationManager.onDownloadStoppedForTranslation(
                download.chapter.id,
                reason ?: "Downloader stopped",
            )
        }

        if (reason != null) {
            notifier.onWarning(reason)
            return
        }

        if (isPaused && queueState.value.isNotEmpty()) {
            notifier.onPaused()
        } else {
            notifier.onComplete()
        }

        isPaused = false

        DownloadJob.stop(context)
    }

    /**
     * Pauses the downloader
     */
    fun pause() {
        cancelDownloaderJob()
        queueState.value
            .filter { it.status == Download.State.DOWNLOADING }
            .forEach { it.status = Download.State.QUEUE }
        isPaused = true
    }

    /**
     * Removes everything from the queue.
     */
    fun clearQueue() {
        cancelDownloaderJob()

        val clearedChapterIds = queueState.value
            .filter { it.status == Download.State.DOWNLOADING || it.status == Download.State.QUEUE }
            .mapNotNull { it.chapter.id }
        internalClearQueue()
        // T911 slice 2 (R5): clearing the queue cancels any pending
        // translation waiting on those downloads. No-op without a request.
        clearedChapterIds.forEach { chapterId ->
            translationManager.onDownloadQueueClearedForTranslation(chapterId)
        }
        notifier.dismissProgress()
    }

    /**
     * Prepares the subscriptions to start downloading.
     */
    private fun launchDownloaderJob() {
        if (isRunning) return

        downloaderJob = scope.launch {
            val activeDownloadsFlow = queueState.transformLatest { queue ->
                while (true) {
                    val activeDownloads = queue.asSequence()
                        // Ignore completed downloads, leave them in the queue
                        .filter { it.status.value <= Download.State.DOWNLOADING.value }
                        .groupBy { it.source }
                        .toList()
                        // Concurrently download from 5 different sources
                        .take(5)
                        .map { (_, downloads) -> downloads.first() }
                    emit(activeDownloads)

                    if (activeDownloads.isEmpty()) break
                    // Suspend until a download enters the ERROR state
                    val activeDownloadsErroredFlow =
                        combine(activeDownloads.map(Download::statusFlow)) { states ->
                            states.contains(Download.State.ERROR)
                        }.filter { it }
                    activeDownloadsErroredFlow.first()
                }
            }.distinctUntilChanged()

            // Use supervisorScope to cancel child jobs when the downloader job is cancelled
            supervisorScope {
                val downloadJobs = mutableMapOf<Download, Job>()

                activeDownloadsFlow.collectLatest { activeDownloads ->
                    val downloadJobsToStop = downloadJobs.filter { it.key !in activeDownloads }
                    downloadJobsToStop.forEach { (download, job) ->
                        job.cancel()
                        downloadJobs.remove(download)
                    }

                    val downloadsToStart = activeDownloads.filter { it !in downloadJobs }
                    downloadsToStart.forEach { download ->
                        downloadJobs[download] = launchDownloadJob(download)
                    }
                }
            }
        }
    }

    private fun CoroutineScope.launchDownloadJob(download: Download) = launchIO {
        try {
            downloadChapter(download)

            // Remove successful download from queue
            if (download.status == Download.State.DOWNLOADED) {
                removeFromQueue(download)
            }
            if (areAllDownloadsFinished()) {
                stop()
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            // T911 slice 2 (R5): failures before the protected try (manga dir,
            // storage probing, tmp-dir creation) reach this outer catch, which
            // previously stopped the downloader without telling the
            // pending-request owner. Existence-checked, so it is a no-op for
            // ordinary downloads.
            logcat(LogPriority.ERROR, e)
            notifier.onError(e.message)
            translationManager.markTranslationDownloadFailed(
                download.chapter.id,
                e.message ?: "Download failed before it could start",
            )
            stop()
        }
    }

    /**
     * Destroys the downloader subscriptions.
     */
    private fun cancelDownloaderJob() {
        downloaderJob?.cancel()
        downloaderJob = null
    }

    /**
     * Creates a download object for every chapter and adds them to the downloads queue.
     *
     * @param manga the manga of the chapters to download.
     * @param chapters the list of chapters to download.
     * @param autoStart whether to start the downloader after enqueing the chapters.
     */
    fun queueChapters(manga: Manga, chapters: List<Chapter>, autoStart: Boolean) {
        if (chapters.isEmpty()) return
        val traceQueue = chapters.any { chapter ->
            translationManager.pendingRequestGeneration(chapter.id) != null
        }

        val source = sourceManager.get(manga.source) as? HttpSource ?: run {
            // T911 slice 2 (R5): a silent rejection used to strand a pending
            // translation request in WAITING forever. Fail it explicitly;
            // no-op for chapters without a pending request.
            chapters.forEach { chapter ->
                if (traceQueue) {
                    traceQueueResult(
                        chapter.id,
                        BatchDownloadQueueResult.UNSUPPORTED_SOURCE,
                        autoStart,
                    )
                }
                translationManager.markTranslationDownloadFailed(
                    chapter.id,
                    "Source does not support downloads",
                    TranslationRequestFailureKind.SOURCE_UNSUPPORTED,
                )
            }
            return
        }
        val wasEmpty = queueState.value.isEmpty()
        val alreadyDownloadedIds = if (traceQueue) mutableSetOf<Long>() else null
        val alreadyQueuedIds = if (traceQueue) mutableSetOf<Long>() else null
        val chaptersToQueue = chapters.asSequence()
            // Filter out those already downloaded.
            .filter { chapter ->
                val shouldQueue = provider.findChapterDir(
                    chapter.name,
                    chapter.scanlator,
                    manga.title,
                    source,
                ) == null
                if (!shouldQueue) alreadyDownloadedIds?.add(chapter.id)
                shouldQueue
            }
            // Add chapters to queue from the start.
            .sortedByDescending { it.sourceOrder }
            // Filter out those already enqueued.
            .filter { chapter ->
                val shouldQueue = queueState.value.none { it.chapter.id == chapter.id }
                if (!shouldQueue) alreadyQueuedIds?.add(chapter.id)
                shouldQueue
            }
            // Create a download for each one.
            .map { Download(source, manga, it) }
            .toList()

        if (chaptersToQueue.isNotEmpty()) {
            addAllToQueue(chaptersToQueue)

            // Start downloader if needed
            if (autoStart && wasEmpty) {
                val queuedDownloads = queueState.value.count { it.source !is UnmeteredSource }
                val maxDownloadsFromSource = queueState.value
                    .groupBy { it.source }
                    .filterKeys { it !is UnmeteredSource }
                    .maxOfOrNull { it.value.size }
                    ?: 0
                if (
                    queuedDownloads > DOWNLOADS_QUEUED_WARNING_THRESHOLD ||
                    maxDownloadsFromSource > CHAPTERS_PER_SOURCE_QUEUE_WARNING_THRESHOLD
                ) {
                    notifier.onWarning(
                        context.stringResource(MR.strings.download_queue_size_warning),
                        WARNING_NOTIF_TIMEOUT_MS,
                        NotificationHandler.openUrl(context, LibraryUpdateNotifier.HELP_WARNING_URL),
                    )
                }
                DownloadJob.start(context)
            }
        }
        if (traceQueue) {
            val enqueuedIds = chaptersToQueue.mapTo(mutableSetOf()) { it.chapter.id }
            chapters.forEach { chapter ->
                val result = when (chapter.id) {
                    in alreadyDownloadedIds.orEmpty() -> BatchDownloadQueueResult.ALREADY_DOWNLOADED
                    in alreadyQueuedIds.orEmpty() -> BatchDownloadQueueResult.ALREADY_QUEUED
                    in enqueuedIds -> BatchDownloadQueueResult.ENQUEUED
                    else -> return@forEach
                }
                traceQueueResult(chapter.id, result, autoStart)
            }
        }
    }

    /**
     * Downloads a chapter.
     *
     * T911 slice 3: internal so fault-injection tests can drive the
     * finalize-stage failure boundary directly.
     *
     * @param download the chapter to be downloaded.
     */
    internal suspend fun downloadChapter(download: Download) {
        val traceContext = BatchDownloadTraceContext(download.chapter.id) {
            translationManager.pendingRequestGeneration(download.chapter.id)
        }
        val mangaDir = provider.getMangaDir(download.manga.title, download.source)

        val availSpace = DiskUtil.getAvailableStorageSpace(mangaDir)
        if (availSpace != -1L && availSpace < MIN_DISK_SPACE) {
            download.status = Download.State.ERROR
            traceContext.generation(BatchDownloadTraceBoundary.DOWNLOAD_TERMINAL)?.let { generation ->
                BatchDownloadDiagnostics.downloadTerminal(
                    chapterId = download.chapter.id,
                    generation = generation,
                    state = BatchDownloadTerminalState.ERROR,
                    cause = BatchDownloadCause.STORAGE,
                )
            }
            translationManager.markTranslationDownloadFailed(
                download.chapter.id,
                "Insufficient storage",
                TranslationRequestFailureKind.STORAGE,
            )
            notifier.onError(
                context.stringResource(MR.strings.download_insufficient_space),
                download.chapter.name,
                download.manga.title,
                download.manga.id,
            )
            return
        }

        val chapterDirname = provider.getChapterDirName(download.chapter.name, download.chapter.scanlator)
        val tmpDir = mangaDir.createDirectory(chapterDirname + TMP_DIR_SUFFIX)!!

        // T911 slice 3 (R8): the finalize boundary (page validation, metadata
        // write, archive/rename) ends at `DOWNLOADED`. Rekey and translation
        // handoff run AFTER it, so a failure there can never flip the already
        // finalized download back to ERROR.
        val pageList: List<Page>
        val onDiskKeys: List<String>
        val publishedFiles = ConcurrentHashMap<Int, PublishedPageFiles>()
        var finalizationStage: BatchDownloadStage? = null
        var validation: DownloadValidation? = null
        try {
            // If the page list already exists, start from the file
            pageList = download.pages ?: run {
                // Otherwise, pull page list from network and add them to download object
                val pages = download.source.getPageList(download.chapter.toSChapter())

                if (pages.isEmpty()) {
                    throw Exception(context.stringResource(MR.strings.page_list_empty_error))
                }
                // Don't trust index from source
                val reIndexedPages = pages.mapIndexed { index, page -> Page(index, page.url, page.imageUrl, page.uri) }
                download.pages = reIndexedPages
                reIndexedPages
            }
            traceContext.generation(BatchDownloadTraceBoundary.CHAPTER_START)?.let { generation ->
                BatchDownloadDiagnostics.chapterStart(
                    chapterId = download.chapter.id,
                    generation = generation,
                    pageTotal = pageList.size,
                    resumedReady = pageList.count { it.status == Page.State.READY },
                    saveAsCbz = downloadPreferences.saveChaptersAsCBZ().get(),
                )
            }

            // Delete all temporary (unfinished) files
            val cleanupFiles = try {
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
                throw error
            }
            if (cleanupFiles == null) {
                tracePathOperation(
                    download.chapter.id,
                    traceContext,
                    null,
                    BatchDownloadStage.DIRECTORY_LIST,
                    BatchDownloadResult.NULL,
                )
            }
            cleanupFiles
                ?.filter { it.extension == "tmp" }
                ?.forEach { file ->
                    val deleted = try {
                        file.delete()
                    } catch (error: Throwable) {
                        tracePathOperation(
                            download.chapter.id,
                            traceContext,
                            null,
                            BatchDownloadStage.TEMP_DELETE,
                            BatchDownloadResult.THREW,
                            error,
                        )
                        throw error
                    }
                    if (!deleted) {
                        tracePathOperation(
                            download.chapter.id,
                            traceContext,
                            null,
                            BatchDownloadStage.TEMP_DELETE,
                            BatchDownloadResult.FALSE,
                        )
                    }
                }

            download.status = Download.State.DOWNLOADING

            // Start downloading images, consider we can have downloaded images already
            // Concurrently do 2 pages at a time
            pageList.asFlow()
                .flatMapMerge(concurrency = 2) { page ->
                    flow {
                        // Fetch image URL if necessary
                        if (page.imageUrl.isNullOrEmpty()) {
                            page.status = Page.State.LOAD_PAGE
                            try {
                                page.imageUrl = download.source.getImageUrl(page)
                            } catch (e: Throwable) {
                                page.status = Page.State.ERROR
                                traceContext.generation(BatchDownloadTraceBoundary.PAGE)?.let { generation ->
                                    BatchDownloadDiagnostics.pageAttemptFailed(
                                        download.chapter.id,
                                        generation,
                                        page.index,
                                        page.number,
                                        1,
                                        BatchDownloadStage.RESOLVE_IMAGE_URL,
                                        BatchDownloadCause.SOURCE,
                                        e,
                                    )
                                    BatchDownloadDiagnostics.pageTerminalFailed(
                                        download.chapter.id,
                                        generation,
                                        page.index,
                                        page.number,
                                        BatchDownloadStage.RESOLVE_IMAGE_URL,
                                        BatchDownloadCause.SOURCE,
                                        e,
                                    )
                                }
                            }
                        }

                        withIOContext { getOrDownloadImage(page, download, tmpDir, traceContext) }
                            ?.let { publishedFiles[page.index] = it }
                        emit(page)
                    }.flowOn(Dispatchers.IO)
                }
                .collect {
                    // Do when page is downloaded.
                    notifier.onProgressChange(download)
                }

            // Do after download completes

            val currentValidation = validateDownload(download, tmpDir, traceContext, publishedFiles)
            validation = currentValidation
            if (!currentValidation.success) {
                download.status = Download.State.ERROR
                traceContext.generation(BatchDownloadTraceBoundary.DOWNLOAD_TERMINAL)?.let { generation ->
                    BatchDownloadDiagnostics.downloadTerminal(
                        chapterId = download.chapter.id,
                        generation = generation,
                        state = BatchDownloadTerminalState.ERROR,
                        cause = if (currentValidation.errorPages.isNotEmpty()) {
                            BatchDownloadCause.INVALID_PAGE
                        } else {
                            BatchDownloadCause.STORAGE
                        },
                        expected = currentValidation.expected,
                        ready = currentValidation.ready,
                        onDisk = currentValidation.onDisk,
                    )
                }
                translationManager.markTranslationDownloadFailed(download.chapter.id, "Chapter download failed")
                return
            }

            finalizationStage = BatchDownloadStage.METADATA
            traceFinalization(download.chapter.id, traceContext, BatchDownloadStage.METADATA, BatchDownloadResult.START)
            createComicInfoFile(
                tmpDir,
                download.manga,
                download.chapter,
                download.source,
            )
            traceFinalization(download.chapter.id, traceContext, BatchDownloadStage.METADATA, BatchDownloadResult.SUCCESS)
            finalizationStage = null

            onDiskKeys = if (translationManager.hasTranslationStore(download.chapter, download.manga, download.source)) {
                val chapterFiles = collectChapterFiles(tmpDir, publishedFiles)
                if (!chapterFiles.listingAvailable) {
                    tracePathOperation(
                        download.chapter.id,
                        traceContext,
                        null,
                        BatchDownloadStage.DIRECTORY_LIST,
                        BatchDownloadResult.NULL,
                    )
                }
                chapterFiles.files
                    .filter { file ->
                        if (!file.isFile) {
                            false
                        } else {
                            val name = file.name ?: return@filter false
                            runCatching { ImageUtil.isImage(name) { file.openInputStream() } }
                                .getOrDefault(false)
                        }
                    }
                    .mapNotNull { it.name }
                    .sorted()
            } else {
                emptyList()
            }

            // Only rename the directory if it's downloaded
            if (downloadPreferences.saveChaptersAsCBZ().get()) {
                finalizationStage = BatchDownloadStage.ARCHIVE
                traceFinalization(
                    download.chapter.id,
                    traceContext,
                    BatchDownloadStage.ARCHIVE,
                    BatchDownloadResult.START,
                )
                val archive = archiveChapter(mangaDir, chapterDirname, tmpDir, publishedFiles)
                if (!archive.entriesListed) {
                    tracePathOperation(
                        download.chapter.id,
                        traceContext,
                        null,
                        BatchDownloadStage.DIRECTORY_LIST,
                        BatchDownloadResult.NULL,
                    )
                }
                if (!archive.renamed) {
                    throw IOException("Unable to publish chapter archive")
                }
                traceFinalization(
                    download.chapter.id,
                    traceContext,
                    BatchDownloadStage.ARCHIVE,
                    archive.renamed.toTraceResult(),
                )
            } else {
                finalizationStage = BatchDownloadStage.RENAME
                traceFinalization(
                    download.chapter.id,
                    traceContext,
                    BatchDownloadStage.RENAME,
                    BatchDownloadResult.START,
                )
                mangaDir.findFile(chapterDirname)?.let { existing ->
                    if (existing.uri != tmpDir.uri) {
                        existing.delete()
                    }
                }
                val renamed = tmpDir.renameTo(chapterDirname)
                traceFinalization(
                    download.chapter.id,
                    traceContext,
                    BatchDownloadStage.RENAME,
                    renamed.toTraceResult(),
                )
                if (!renamed) {
                    throw IOException("Unable to publish chapter directory")
                }
                mangaDir.findFile(chapterDirname)?.let { publishedDir ->
                    DiskUtil.createNoMediaFile(publishedDir, context)
                }
            }
            finalizationStage = BatchDownloadStage.CACHE
            traceFinalization(download.chapter.id, traceContext, BatchDownloadStage.CACHE, BatchDownloadResult.START)
            cache.addChapter(chapterDirname, mangaDir, download.manga)
            traceFinalization(download.chapter.id, traceContext, BatchDownloadStage.CACHE, BatchDownloadResult.SUCCESS)
            finalizationStage = null

            download.status = Download.State.DOWNLOADED
            traceContext.generation(BatchDownloadTraceBoundary.DOWNLOAD_TERMINAL)?.let { generation ->
                BatchDownloadDiagnostics.downloadTerminal(
                    chapterId = download.chapter.id,
                    generation = generation,
                    state = BatchDownloadTerminalState.DOWNLOADED,
                    cause = BatchDownloadCause.UNKNOWN,
                    expected = currentValidation.expected,
                    ready = currentValidation.ready,
                    onDisk = currentValidation.onDisk,
                )
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            finalizationStage?.let { stage ->
                traceFinalization(download.chapter.id, traceContext, stage, BatchDownloadResult.FAILED, error)
            }
            traceContext.generation(BatchDownloadTraceBoundary.DOWNLOAD_TERMINAL)?.let { generation ->
                BatchDownloadDiagnostics.downloadTerminal(
                    chapterId = download.chapter.id,
                    generation = generation,
                    state = BatchDownloadTerminalState.ERROR,
                    cause = finalizationStage?.let(::causeForStage) ?: BatchDownloadCause.UNKNOWN,
                    expected = validation?.expected ?: download.pages?.size,
                    ready = validation?.ready ?: download.downloadedImages,
                    onDisk = validation?.onDisk,
                    error = error,
                )
            }
            // If the page list threw, it will resume here
            logcat(LogPriority.ERROR, error)
            download.status = Download.State.ERROR
            translationManager.markTranslationDownloadFailed(download.chapter.id, "Chapter download failed")
            notifier.onError(error.message, download.chapter.name, download.manga.title, download.manga.id)
            // T911 slice 3 (R8): the finalize boundary settled the download's
            // terminal status; the post-finalization handoff runs below and is
            // NOT covered by this catch. (Behavior-neutral `return`: the catch
            // previously fell through to the end of the function.)
            return
        }

        handOffAfterFinalization(download, pageList, onDiskKeys, traceContext)
    }

    /**
     * T911 slice 3 (R8): post-finalization translation boundary. The chapter's
     * files are final and the download's terminal status is settled
     * (`DOWNLOADED`); a failure in translation artifact rekeying or in the
     * handoff/admission must NOT flip the download back to `ERROR` — the
     * translation intent gets the real typed failure instead
     * ([TranslationManager.markTranslationHandoffFailed]). Normal downloads
     * without a pending request are unaffected (the seam is existence-checked).
     * Internal so fault-injection tests can drive the exact boundary.
     */
    internal suspend fun handOffAfterFinalization(
        download: Download,
        pageList: List<Page>,
        onDiskKeys: List<String>,
        traceContext: BatchDownloadTraceContext = BatchDownloadTraceContext(download.chapter.id) {
            translationManager.pendingRequestGeneration(download.chapter.id)
        },
    ) {
        var stage = BatchDownloadStage.ADMISSION
        try {
            if (onDiskKeys.isNotEmpty()) {
                stage = BatchDownloadStage.REKEY
                traceContext.generation(BatchDownloadTraceBoundary.HANDOFF)?.let { generation ->
                    BatchDownloadDiagnostics.handoff(
                        download.chapter.id,
                        generation,
                        stage,
                        BatchDownloadResult.START,
                    )
                }
                val onlineKeys = pageList.map { page -> onlinePageTranslationKey(page.imageUrl, page.url) }
                translationManager.rekeyTranslationForCompletedDownload(
                    chapter = download.chapter,
                    manga = download.manga,
                    source = download.source,
                    onlineKeyByPageIndex = onlineKeys,
                    onDiskKeyByPageIndex = onDiskKeys,
                )
                traceContext.generation(BatchDownloadTraceBoundary.HANDOFF)?.let { generation ->
                    BatchDownloadDiagnostics.handoff(
                        download.chapter.id,
                        generation,
                        stage,
                        BatchDownloadResult.SUCCESS,
                    )
                }
            }
            stage = BatchDownloadStage.ADMISSION
            traceContext.generation(BatchDownloadTraceBoundary.HANDOFF)?.let { generation ->
                BatchDownloadDiagnostics.handoff(
                    download.chapter.id,
                    generation,
                    stage,
                    BatchDownloadResult.START,
                )
            }
            translationManager.startTranslationAfterDownloadIfRequested(download.manga, download.chapter)
            traceContext.generation(BatchDownloadTraceBoundary.HANDOFF)?.let { generation ->
                BatchDownloadDiagnostics.handoff(
                    download.chapter.id,
                    generation,
                    stage,
                    BatchDownloadResult.SUCCESS,
                )
            }
        } catch (error: Throwable) {
            if (error is CancellationException) {
                traceContext.generation(BatchDownloadTraceBoundary.HANDOFF)?.let { generation ->
                    BatchDownloadDiagnostics.handoff(
                        download.chapter.id,
                        generation,
                        stage,
                        BatchDownloadResult.CANCELLED,
                        error,
                    )
                }
                throw error
            }
            traceContext.generation(BatchDownloadTraceBoundary.HANDOFF)?.let { generation ->
                BatchDownloadDiagnostics.handoff(
                    download.chapter.id,
                    generation,
                    stage,
                    BatchDownloadResult.FAILED,
                    error,
                )
            }
            logcat(LogPriority.ERROR, error)
            translationManager.markTranslationHandoffFailed(
                download.chapter.id,
                "Translation could not start after the chapter download: " +
                    (error.message ?: error::class.java.simpleName),
            )
        }
    }

    /**
     * Gets the image from the filesystem if it exists or downloads it otherwise.
     *
     * @param page the page to download.
     * @param download the download of the page.
     * @param tmpDir the temporary directory of the download.
     */
    private suspend fun getOrDownloadImage(
        page: Page,
        download: Download,
        tmpDir: UniFile,
        traceContext: BatchDownloadTraceContext,
    ): PublishedPageFiles? {
        // If the image URL is empty, do nothing
        if (page.imageUrl == null) {
            return page.uri?.let { uri ->
                UniFile.fromUri(context, uri)?.let { PublishedPageFiles(listOf(it)) }
            }
        }

        val digitCount = (download.pages?.size ?: 0).toString().length.coerceAtLeast(3)
        val filename = "%0${digitCount}d".format(Locale.ENGLISH, page.number)
        val tmpFile = try {
            tmpDir.findFile("$filename.tmp")
        } catch (error: Throwable) {
            tracePathOperation(
                download.chapter.id,
                traceContext,
                page,
                BatchDownloadStage.TEMP_LOOKUP,
                BatchDownloadResult.THREW,
                error,
            )
            throw error
        }

        // Delete temp file if it exists
        if (tmpFile != null) {
            try {
                val deleted = tmpFile.delete()
                if (!deleted) {
                    tracePathOperation(
                        download.chapter.id,
                        traceContext,
                        page,
                        BatchDownloadStage.TEMP_DELETE,
                        BatchDownloadResult.FALSE,
                    )
                }
            } catch (error: Throwable) {
                tracePathOperation(
                    download.chapter.id,
                    traceContext,
                    page,
                    BatchDownloadStage.TEMP_DELETE,
                    BatchDownloadResult.THREW,
                    error,
                )
                throw error
            }
        }

        var stage = BatchDownloadStage.TEMP_LOOKUP
        try {
            // Resolve known final names directly first. Some SAF providers lag
            // behind on directory enumeration immediately after a write.
            val imageFile = findExistingPageFile(tmpDir, filename)

            // If the image is already downloaded, do nothing. Otherwise download from network
            val file = when {
                imageFile != null -> imageFile
                chapterCache.isImageInCache(
                    page.imageUrl!!,
                ) -> {
                    stage = BatchDownloadStage.CACHE_COPY
                    copyImageFromCache(
                        chapterCache.getImageFile(page.imageUrl!!),
                        tmpDir,
                        filename,
                        download.chapter.id,
                        page,
                        traceContext,
                    )
                }
                else -> {
                    stage = BatchDownloadStage.HTTP_FETCH
                    downloadImage(
                        page,
                        download.source,
                        tmpDir,
                        filename,
                        download.chapter.id,
                        traceContext,
                    )
                }
            }

            // When the page is ready, set page path, progress (just in case) and status.
            // The splitter receives the exact file handle returned by the
            // publication step; it must not rediscover a freshly-written SAF
            // entry through directory enumeration.
            stage = BatchDownloadStage.SPLIT
            val publishedPage = splitTallImageIfNeeded(
                page = page,
                tmpDir = tmpDir,
                imageFile = file,
                filename = filename,
                chapterId = download.chapter.id,
                traceContext = traceContext,
            )

            page.uri = publishedPage.primary?.uri
            page.progress = 100
            page.status = Page.State.READY
            return publishedPage
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            if (stage != BatchDownloadStage.HTTP_FETCH) {
                traceContext.generation(BatchDownloadTraceBoundary.PAGE)?.let { generation ->
                    val cause = causeForStage(stage)
                    BatchDownloadDiagnostics.pageAttemptFailed(
                        download.chapter.id,
                        generation,
                        page.index,
                        page.number,
                        1,
                        stage,
                        cause,
                        e,
                    )
                    BatchDownloadDiagnostics.pageTerminalFailed(
                        download.chapter.id,
                        generation,
                        page.index,
                        page.number,
                        stage,
                        cause,
                        e,
                    )
                }
            }
            // Mark this page as error and allow to download the remaining
            page.progress = 0
            page.status = Page.State.ERROR
            notifier.onError(e.message, download.chapter.name, download.manga.title, download.manga.id)
            return null
        }
    }

    /**
     * Downloads the image from network to a file in tmpDir.
     *
     * @param page the page to download.
     * @param source the source of the page.
     * @param tmpDir the temporary directory of the download.
     * @param filename the filename of the image.
     */
    private suspend fun downloadImage(
        page: Page,
        source: HttpSource,
        tmpDir: UniFile,
        filename: String,
        chapterId: Long,
        traceContext: BatchDownloadTraceContext,
    ): UniFile {
        page.status = Page.State.DOWNLOAD_IMAGE
        page.progress = 0
        var attempt = 0
        var stage = BatchDownloadStage.HTTP_FETCH
        return try {
            flow {
                attempt++
                stage = BatchDownloadStage.HTTP_FETCH
                val response = try {
                    source.getImage(page)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    tracePageAttemptFailure(chapterId, traceContext, page, attempt, stage, error)
                    throw error
                }
                stage = BatchDownloadStage.CREATE_TEMP
                val file = try {
                    tmpDir.createFile("$filename.tmp")!!
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    tracePageAttemptFailure(chapterId, traceContext, page, attempt, stage, error)
                    throw error
                }
                val published = try {
                    stage = BatchDownloadStage.WRITE_TEMP
                    response.body.source().saveTo(file.openOutputStream())
                    stage = BatchDownloadStage.DETECT_TYPE
                    val extension = getImageExtension(response, file)
                    stage = BatchDownloadStage.RENAME_TEMP
                    val finalName = "$filename.$extension"
                    try {
                        publishDownloadedFile(file, finalName)
                    } catch (error: IOException) {
                        tracePageAttemptFailure(chapterId, traceContext, page, attempt, stage, null)
                        tracePageTerminalFailure(chapterId, traceContext, page, stage, null)
                        throw error
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    tracePageAttemptFailure(chapterId, traceContext, page, attempt, stage, e)
                    response.close()
                    file.delete()
                    throw e
                }
                emit(published)
            }
                // Retry 3 times, waiting 2, 4 and 8 seconds between attempts.
                .retryWhen { _, retry ->
                    if (retry < 3) {
                        delay((2L shl retry.toInt()) * 1000)
                        true
                    } else {
                        false
                    }
                }
                .first()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            traceContext.generation(BatchDownloadTraceBoundary.PAGE)?.let { generation ->
                BatchDownloadDiagnostics.pageTerminalFailed(
                    chapterId,
                    generation,
                    page.index,
                    page.number,
                    stage,
                    causeForStage(stage, error),
                    error,
                )
            }
            throw error
        }
    }

    /**
     * Copies the image from cache to file in tmpDir.
     *
     * @param cacheFile the file from cache.
     * @param tmpDir the temporary directory of the download.
     * @param filename the filename of the image.
     */
    private fun copyImageFromCache(
        cacheFile: File,
        tmpDir: UniFile,
        filename: String,
        chapterId: Long,
        page: Page,
        traceContext: BatchDownloadTraceContext,
    ): UniFile {
        val tmpFile = tmpDir.createFile("$filename.tmp")!!
        cacheFile.inputStream().use { input ->
            tmpFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }
        val extension = ImageUtil.findImageType(cacheFile.inputStream()) ?: run {
            tracePageAttemptFailure(
                chapterId,
                traceContext,
                page,
                1,
                BatchDownloadStage.DETECT_TYPE,
                null,
            )
            tracePageTerminalFailure(
                chapterId,
                traceContext,
                page,
                BatchDownloadStage.DETECT_TYPE,
                null,
            )
            tmpFile.delete()
            throw IOException("Cached image is not a valid image")
        }
        try {
            publishDownloadedFile(tmpFile, "$filename.${extension.extension}")
        } catch (error: IOException) {
            tracePageAttemptFailure(
                chapterId,
                traceContext,
                page,
                1,
                BatchDownloadStage.RENAME_TEMP,
                null,
            )
            tracePageTerminalFailure(
                chapterId,
                traceContext,
                page,
                BatchDownloadStage.RENAME_TEMP,
                null,
            )
            tmpFile.delete()
            throw error
        }
        cacheFile.delete()
        return tmpFile
    }

    /**
     * Returns the extension of the downloaded image from the network response, or if it's null,
     * analyze the file. If everything fails, assume it's a jpg.
     *
     * @param response the network response of the image.
     * @param file the file where the image is already downloaded.
     */
    private fun getImageExtension(response: Response, file: UniFile): String {
        val mime = response.body.contentType()?.run { if (type == "image") "image/$subtype" else null }
        return ImageUtil.getExtensionFromMimeType(mime) { file.openInputStream() }
    }

    private suspend fun splitTallImageIfNeeded(
        page: Page,
        tmpDir: UniFile,
        imageFile: UniFile,
        filename: String,
        chapterId: Long,
        traceContext: BatchDownloadTraceContext,
    ): PublishedPageFiles {
        if (!downloadPreferences.splitTallImages().get()) {
            return PublishedPageFiles(listOf(imageFile))
        }

        val resolvedImageFile = findExistingPageFile(tmpDir, filename) ?: imageFile

        try {
            // If the original page was previously split, then skip
            if (resolvedImageFile.name.orEmpty().startsWith("${filename}__")) {
                val splitFiles = findSplitFiles(tmpDir, filename)
                return PublishedPageFiles(splitFiles.ifEmpty { listOf(resolvedImageFile) })
            }

            val splitSucceeded = runCatching {
                ImageUtil.splitTallImage(tmpDir, resolvedImageFile, filename)
            }.getOrDefault(false)

            if (!splitSucceeded) {
                // ImageUtil preserves the original when splitting cannot be
                // completed. It remains a valid published page in that case.
                val fallback = findExistingPageFile(tmpDir, filename) ?: resolvedImageFile
                if (isPublishedFile(fallback)) return PublishedPageFiles(listOf(fallback))
                error(context.stringResource(MR.strings.download_notifier_split_page_not_found, page.number))
            }

            val splitFiles = findSplitFiles(tmpDir, filename)
            if (splitFiles.isNotEmpty()) return PublishedPageFiles(splitFiles)

            // A non-tall/animated image returns true without creating split
            // files, so retain the original handle when it is still present.
            val fallback = findExistingPageFile(tmpDir, filename) ?: resolvedImageFile
            if (isPublishedFile(fallback)) return PublishedPageFiles(listOf(fallback))
            error(context.stringResource(MR.strings.download_notifier_split_page_not_found, page.number))
        } catch (e: Exception) {
            tracePageAttemptFailure(
                chapterId,
                traceContext,
                page,
                1,
                BatchDownloadStage.SPLIT,
                e,
            )
            logcat(LogPriority.ERROR, e) { "Failed to split downloaded image" }
            val fallback = findExistingPageFile(tmpDir, filename) ?: resolvedImageFile
            if (isPublishedFile(fallback)) return PublishedPageFiles(listOf(fallback))
            throw e
        }
    }

    private suspend fun findSplitFiles(tmpDir: UniFile, filename: String): List<UniFile> {
        val splitFiles = mutableListOf<UniFile>()
        for (index in 1..MAX_SPLIT_PARTS) {
            val splitName = "${filename}__${"%03d".format(Locale.ENGLISH, index)}.jpg"
            val splitFile = findFileWithRetry(tmpDir, splitName) ?: break
            if (isPublishedFile(splitFile)) {
                splitFiles += splitFile
            }
        }
        return splitFiles
    }

    private suspend fun findExistingPageFile(tmpDir: UniFile, filename: String): UniFile? {
        val listedFiles = runCatching { tmpDir.listFiles() }.getOrNull()
        listedFiles?.firstOrNull { file ->
            (file.name.orEmpty().startsWith("$filename.") || file.name.orEmpty().startsWith("${filename}__001")) &&
                isPublishedFile(file)
        }?.let { return it }

        findFileWithRetry(tmpDir, "${filename}__001.jpg")?.takeIf(::isPublishedFile)?.let { return it }
        IMAGE_EXTENSIONS.forEach { extension ->
            runCatching { tmpDir.findFile("$filename.$extension") }
                .getOrNull()
                ?.takeIf(::isPublishedFile)
                ?.let { return it }
        }
        return null
    }

    private suspend fun findFileWithRetry(tmpDir: UniFile, name: String): UniFile? {
        repeat(FILE_LOOKUP_ATTEMPTS) { attempt ->
            val file = tmpDir.findFile(name)
            if (file != null) return file
            if (attempt + 1 < FILE_LOOKUP_ATTEMPTS) delay(FILE_LOOKUP_DELAY_MS)
        }
        return null
    }

    private fun isPublishedFile(file: UniFile): Boolean = runCatching {
        file.exists() && file.isFile && file.length() > 0
    }.getOrDefault(false)

    internal data class ChapterFiles(
        val files: List<UniFile>,
        val listingAvailable: Boolean,
    )

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

    private fun traceQueueResult(
        chapterId: Long,
        result: BatchDownloadQueueResult,
        startRequested: Boolean,
    ) {
        val generation = translationManager.pendingRequestGeneration(chapterId) ?: return
        BatchDownloadDiagnostics.queueResult(
            chapterId = chapterId,
            generation = generation,
            result = result,
            queueSize = queueState.value.size,
            startRequested = startRequested,
        )
    }

    private fun tracePageAttemptFailure(
        chapterId: Long,
        traceContext: BatchDownloadTraceContext,
        page: Page,
        attempt: Int,
        stage: BatchDownloadStage,
        error: Throwable?,
    ) {
        val generation = traceContext.generation(BatchDownloadTraceBoundary.PAGE) ?: return
        BatchDownloadDiagnostics.pageAttemptFailed(
            chapterId = chapterId,
            generation = generation,
            pageIndex = page.index,
            pageNumber = page.number,
            attempt = attempt,
            stage = stage,
            cause = causeForStage(stage, error),
            error = error,
        )
    }

    private fun tracePageTerminalFailure(
        chapterId: Long,
        traceContext: BatchDownloadTraceContext,
        page: Page,
        stage: BatchDownloadStage,
        error: Throwable?,
    ) {
        val generation = traceContext.generation(BatchDownloadTraceBoundary.PAGE) ?: return
        BatchDownloadDiagnostics.pageTerminalFailed(
            chapterId = chapterId,
            generation = generation,
            pageIndex = page.index,
            pageNumber = page.number,
            stage = stage,
            cause = causeForStage(stage, error),
            error = error,
        )
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

    private fun traceFinalization(
        chapterId: Long,
        traceContext: BatchDownloadTraceContext,
        stage: BatchDownloadStage,
        result: BatchDownloadResult,
        error: Throwable? = null,
    ) {
        val generation = traceContext.generation(BatchDownloadTraceBoundary.FINALIZATION) ?: return
        BatchDownloadDiagnostics.finalization(
            chapterId = chapterId,
            generation = generation,
            stage = stage,
            result = result,
            error = error,
        )
    }

    private fun causeForStage(stage: BatchDownloadStage, error: Throwable? = null): BatchDownloadCause = when (stage) {
        BatchDownloadStage.TEMP_LOOKUP,
        BatchDownloadStage.TEMP_DELETE,
        BatchDownloadStage.DIRECTORY_LIST,
        -> BatchDownloadCause.STORAGE
        BatchDownloadStage.RESOLVE_IMAGE_URL -> BatchDownloadCause.SOURCE
        BatchDownloadStage.HTTP_FETCH -> if (error?.javaClass?.simpleName?.contains("Http", ignoreCase = true) == true) {
            BatchDownloadCause.HTTP
        } else {
            BatchDownloadCause.NETWORK
        }
        BatchDownloadStage.CREATE_TEMP,
        BatchDownloadStage.WRITE_TEMP,
        BatchDownloadStage.RENAME_TEMP,
        BatchDownloadStage.CACHE_COPY,
        BatchDownloadStage.ARCHIVE,
        BatchDownloadStage.RENAME,
        BatchDownloadStage.CACHE,
        -> BatchDownloadCause.STORAGE
        BatchDownloadStage.DETECT_TYPE,
        BatchDownloadStage.SPLIT,
        -> BatchDownloadCause.INVALID_PAGE
        BatchDownloadStage.METADATA,
        BatchDownloadStage.REKEY,
        BatchDownloadStage.ADMISSION,
        -> BatchDownloadCause.UNKNOWN
    }

    /**
     * Checks if the download was successful.
     *
     * @param download the download to check.
     * @param tmpDir the directory where the download is currently stored.
     */
    internal fun validateDownload(
        download: Download,
        tmpDir: UniFile,
        traceContext: BatchDownloadTraceContext,
        publishedFiles: Map<Int, PublishedPageFiles> = emptyMap(),
    ): DownloadValidation {
        // Page list hasn't been initialized
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

        // Ensure that the chapter folder has all the pages
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
                    // Only count the first split page and not the others
                    fileName.contains("__") && !fileName.endsWith("__001.jpg") -> false
                    else -> true
                }
            }
        }

        val countPublishedFiles = publishedFiles.values.count { pageFiles ->
            pageFiles.primary?.let(::isPublishedFile) == true
        }
        // A directory listing can lag on SAF. Prefer the verified handles from
        // this download, and fall back to the listing only when no complete
        // handle set is available. Missing evidence still fails validation.
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

    internal data class DownloadValidation(
        val expected: Int,
        val ready: Int,
        val onDisk: Int?,
        val errorPages: List<Int>,
        val success: Boolean,
    )

    /**
     * Archive the chapter pages as a CBZ.
     */
    private fun archiveChapter(
        mangaDir: UniFile,
        dirname: String,
        tmpDir: UniFile,
        publishedFiles: Map<Int, PublishedPageFiles>,
    ): ArchiveObservation {
        val zip = mangaDir.createFile("$dirname.cbz$TMP_DIR_SUFFIX")!!
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

    private data class ArchiveObservation(
        val entriesListed: Boolean,
        val renamed: Boolean,
    )

    private fun Boolean.toTraceResult(): BatchDownloadResult =
        if (this) BatchDownloadResult.SUCCESS else BatchDownloadResult.FALSE

    /**
     * Creates a ComicInfo.xml file inside the given directory.
     */
    private suspend fun createComicInfoFile(
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

        // Remove the old file
        dir.findFile(COMIC_INFO_FILE)?.delete()
        dir.createFile(COMIC_INFO_FILE)!!.openOutputStream().use {
            val comicInfoString = xml.encodeToString(ComicInfo.serializer(), comicInfo)
            it.write(comicInfoString.toByteArray())
        }
    }

    /**
     * Returns true if all the queued downloads are in DOWNLOADED or ERROR state.
     */
    private fun areAllDownloadsFinished(): Boolean {
        return queueState.value.none { it.status.value <= Download.State.DOWNLOADING.value }
    }

    private fun addAllToQueue(downloads: List<Download>) {
        _queueState.update {
            downloads.forEach { download ->
                download.status = Download.State.QUEUE
            }
            store.addAll(downloads)
            it + downloads
        }
    }

    private fun removeFromQueue(download: Download) {
        // Only an active download's removal cancels the wait; a DOWNLOADED
        // chapter leaves the queue as part of its successful completion.
        val wasActive = download.status == Download.State.DOWNLOADING ||
            download.status == Download.State.QUEUE
        _queueState.update {
            store.remove(download)
            if (download.status == Download.State.DOWNLOADING || download.status == Download.State.QUEUE) {
                download.status = Download.State.NOT_DOWNLOADED
            }
            it - download
        }
        // T911 slice 2 (R5): removing/cancelling the download must reach the
        // pending-request owner. No-op without a pending request.
        if (wasActive) {
            translationManager.onDownloadCancelledForTranslation(download.chapter.id)
        }
    }

    private inline fun removeFromQueueIf(predicate: (Download) -> Boolean) {
        val removedChapterIds = mutableListOf<Long>()
        _queueState.update { queue ->
            val downloads = queue.filter { predicate(it) }
            store.removeAll(downloads)
            downloads.forEach { download ->
                if (download.status == Download.State.DOWNLOADING || download.status == Download.State.QUEUE) {
                    download.status = Download.State.NOT_DOWNLOADED
                    download.chapter.id?.let(removedChapterIds::add)
                }
            }
            queue - downloads
        }
        // T911 slice 2 (R5): bulk removal (chapter delete, manga delete, user
        // cancel) fails the attached translation requests explicitly.
        removedChapterIds.forEach { chapterId ->
            translationManager.onDownloadCancelledForTranslation(chapterId)
        }
    }

    fun removeFromQueue(chapters: List<Chapter>) {
        val chapterIds = chapters.map { it.id }
        removeFromQueueIf { it.chapter.id in chapterIds }
    }

    fun removeFromQueue(manga: Manga) {
        removeFromQueueIf { it.manga.id == manga.id }
    }

    private fun internalClearQueue() {
        _queueState.update {
            it.forEach { download ->
                if (download.status == Download.State.DOWNLOADING || download.status == Download.State.QUEUE) {
                    download.status = Download.State.NOT_DOWNLOADED
                }
            }
            store.clear()
            emptyList()
        }
    }

    fun updateQueue(downloads: List<Download>) {
        val wasRunning = isRunning

        if (downloads.isEmpty()) {
            clearQueue()
            stop()
            return
        }

        pause()
        internalClearQueue()
        addAllToQueue(downloads)

        if (wasRunning) {
            start()
        }
    }

    companion object {
        const val TMP_DIR_SUFFIX = "_tmp"
        const val WARNING_NOTIF_TIMEOUT_MS = 30_000L
        const val CHAPTERS_PER_SOURCE_QUEUE_WARNING_THRESHOLD = 15
        private const val DOWNLOADS_QUEUED_WARNING_THRESHOLD = 30
        private const val FILE_LOOKUP_ATTEMPTS = 3
        private const val FILE_LOOKUP_DELAY_MS = 50L
        private const val MAX_SPLIT_PARTS = 1024
        private val IMAGE_EXTENSIONS = arrayOf("avif", "gif", "heif", "jpg", "jxl", "png", "webp")
    }
}

/**
 * Publishes a temporary page under its final name. A false UniFile rename is
 * not a successful download: accepting it would make the page appear READY
 * while validation can no longer find durable bytes.
 */
internal fun publishDownloadedFile(file: UniFile, finalName: String): UniFile {
    val parent = runCatching { file.parentFile }.getOrNull()
    runCatching {
        parent?.findFile(finalName)?.let { existing ->
            if (existing.uri != file.uri) {
                existing.delete()
            }
        }
    }
    if (!file.renameTo(finalName)) {
        throw IOException("Unable to publish downloaded page")
    }
    return runCatching { parent?.findFile(finalName) }.getOrNull() ?: file
}

// Arbitrary minimum required space to start a download: 200 MB
private const val MIN_DISK_SPACE = 200L * 1024 * 1024
