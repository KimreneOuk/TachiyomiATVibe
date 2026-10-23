package eu.kanade.tachiyomi.data.download

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.library.LibraryUpdateNotifier
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.translation.TranslationManager
import eu.kanade.translation.diagnostics.BatchDownloadCause
import eu.kanade.translation.diagnostics.BatchDownloadDiagnostics
import eu.kanade.translation.diagnostics.BatchDownloadQueueResult
import eu.kanade.translation.diagnostics.BatchDownloadResult
import eu.kanade.translation.diagnostics.BatchDownloadStage
import eu.kanade.translation.diagnostics.BatchDownloadTerminalState
import eu.kanade.translation.diagnostics.BatchDownloadTraceBoundary
import eu.kanade.translation.diagnostics.BatchDownloadTraceContext
import eu.kanade.translation.model.TranslationRequestFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNow
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Manages chapter download queuing and execution lifecycle. */
class Downloader private constructor(
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
    private val testStore: DownloadStore?,
    private val restoreOnStartup: Boolean,
) {

    constructor(
        context: Context,
        provider: DownloadProvider,
        cache: DownloadCache,
        sourceManager: SourceManager = Injekt.get(),
        chapterCache: ChapterCache = Injekt.get(),
        downloadPreferences: DownloadPreferences = Injekt.get(),
        xml: XML = Injekt.get(),
        getCategories: GetCategories = Injekt.get(),
        getTracks: GetTracks = Injekt.get(),
        translationManager: TranslationManager = Injekt.get(),
    ) : this(
        context,
        provider,
        cache,
        sourceManager,
        chapterCache,
        downloadPreferences,
        xml,
        getCategories,
        getTracks,
        translationManager,
        null,
        true,
    )

    /** Durably published files for a logical page, retaining handles to avoid SAF directory lag. */
    internal data class PublishedPageFiles(
        val files: List<UniFile>,
    ) {
        val primary: UniFile?
            get() = files.firstOrNull()
    }

    /**
     * Store for persisting downloads across restarts.
     */
    private val store = testStore ?: DownloadStore(context)

    /**
     * Queue where active downloads are kept.
     */
    private val _queueState = MutableStateFlow<List<Download>>(emptyList())
    val queueState = _queueState.asStateFlow()

    /**
     * Notifier for the downloader state and progress.
     */
    private val notifier by lazy { DownloadNotifier(context) }
    private val pageDownloadPipeline by lazy {
        PageDownloadPipeline(context, chapterCache, downloadPreferences, notifier)
    }
    private val chapterPackager by lazy {
        ChapterPackager(
            context = context,
            xml = xml,
            getCategories = getCategories,
            getTracks = getTracks,
            isPublishedFile = pageDownloadPipeline::isPublishedFile,
        )
    }
    private val downloadTranslationHandoff by lazy {
        DownloadTranslationHandoff(translationManager)
    }

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
        if (restoreOnStartup) {
            launchNow {
                val chapters = async { store.restore() }
                addAllToQueue(chapters.await())
                DownloadJob.stop(context)
                // Notify translation manager of restored queue for startup reconciliation.
                translationManager.onDownloadQueueRestored(
                    queueState.value.mapNotNull { it.chapter.id }.toSet(),
                )
            }
        }
    }

    /** Starts downloading if inactive and queue contains pending items. */
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
        // Notify pending-request owner of stopped in-flight or queued downloads
        val toNotify = if (reason != null) queueState.value else interrupted
        toNotify.forEach { download ->
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
        //  slice 2 (R5): clearing the queue cancels any pending
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
            // Pre-download failure (storage/dir init); fail pending translation if active.
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

    /** Enqueues chapters and optionally starts the downloader. */
    fun queueChapters(manga: Manga, chapters: List<Chapter>, autoStart: Boolean) {
        if (chapters.isEmpty()) return
        val traceQueue = chapters.any { chapter ->
            translationManager.pendingRequestGeneration(chapter.id) != null
        }

        val source = sourceManager.get(manga.source) as? HttpSource ?: run {
            // Unsupported source; fail pending translation requests explicitly.
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

    /** Downloads a chapter; internal for fault-injection test boundary driving. */
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

        // Finalize boundary ends at DOWNLOADED; rekey/handoff runs after,
        // preventing translation errors from flipping settled downloads.
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

                        withIOContext { pageDownloadPipeline.getOrDownloadImage(page, download, tmpDir, traceContext) }
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
            // Finalize boundary settled status; post-finalization handoff runs below
            // and is not covered by this catch.
            return
        }

        handOffAfterFinalization(download, pageList, onDiskKeys, traceContext)
    }

    /** Handles post-finalization rekeying and handoff to the translation manager. */
    internal suspend fun handOffAfterFinalization(
        download: Download,
        pageList: List<Page>,
        onDiskKeys: List<String>,
        traceContext: BatchDownloadTraceContext = BatchDownloadTraceContext(download.chapter.id) {
            translationManager.pendingRequestGeneration(download.chapter.id)
        },
    ) = downloadTranslationHandoff.handOffAfterFinalization(download, pageList, onDiskKeys, traceContext)

    internal fun collectChapterFiles(
        tmpDir: UniFile,
        publishedFiles: Map<Int, PublishedPageFiles>,
    ): ChapterFiles = chapterPackager.collectChapterFiles(tmpDir, publishedFiles)

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

    internal fun validateDownload(
        download: Download,
        tmpDir: UniFile,
        traceContext: BatchDownloadTraceContext,
        publishedFiles: Map<Int, PublishedPageFiles> = emptyMap(),
    ): DownloadValidation = chapterPackager.validateDownload(download, tmpDir, traceContext, publishedFiles)

    private fun archiveChapter(
        mangaDir: UniFile,
        dirname: String,
        tmpDir: UniFile,
        publishedFiles: Map<Int, PublishedPageFiles>,
    ): ArchiveObservation = chapterPackager.archiveChapter(mangaDir, dirname, tmpDir, publishedFiles)

    private fun Boolean.toTraceResult(): BatchDownloadResult =
        if (this) BatchDownloadResult.SUCCESS else BatchDownloadResult.FALSE

    private suspend fun createComicInfoFile(
        dir: UniFile,
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
    ) = chapterPackager.createComicInfoFile(dir, manga, chapter, source)

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
        removeFromQueueIf { it == download }
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
        //  slice 2 (R5): bulk removal (chapter delete, manga delete, user
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
        @VisibleForTesting
        internal fun createForTesting(
            context: Context,
            provider: DownloadProvider,
            cache: DownloadCache,
            sourceManager: SourceManager,
            chapterCache: ChapterCache,
            downloadPreferences: DownloadPreferences,
            xml: XML,
            getCategories: GetCategories,
            getTracks: GetTracks,
            translationManager: TranslationManager,
            store: DownloadStore,
        ): Downloader = Downloader(
            context,
            provider,
            cache,
            sourceManager,
            chapterCache,
            downloadPreferences,
            xml,
            getCategories,
            getTracks,
            translationManager,
            store,
            false,
        )

        const val TMP_DIR_SUFFIX = "_tmp"
        const val WARNING_NOTIF_TIMEOUT_MS = 30_000L
        const val CHAPTERS_PER_SOURCE_QUEUE_WARNING_THRESHOLD = 15
        private const val DOWNLOADS_QUEUED_WARNING_THRESHOLD = 30
    }
}

// Arbitrary minimum required space to start a download: 200 MB
private const val MIN_DISK_SPACE = 200L * 1024 * 1024
