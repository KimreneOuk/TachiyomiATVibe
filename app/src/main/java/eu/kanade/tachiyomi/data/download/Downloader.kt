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
import eu.kanade.translation.TranslationManager
import eu.kanade.translation.diagnostics.BatchDownloadCause
import eu.kanade.translation.diagnostics.BatchDownloadDiagnostics
import eu.kanade.translation.diagnostics.BatchDownloadQueueResult
import eu.kanade.translation.diagnostics.BatchDownloadStage
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
import java.util.Locale

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
    var isPaused: Boolean = false

    init {
        launchNow {
            val chapters = async { store.restore() }
            addAllToQueue(chapters.await())
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

        val source = sourceManager.get(manga.source) as? HttpSource ?: run {
            // T911 slice 2 (R5): a silent rejection used to strand a pending
            // translation request in WAITING forever. Fail it explicitly;
            // no-op for chapters without a pending request.
            chapters.forEach { chapter ->
                traceQueueResult(
                    chapter.id,
                    BatchDownloadQueueResult.UNSUPPORTED_SOURCE,
                    autoStart,
                )
                translationManager.markTranslationDownloadFailed(
                    chapter.id,
                    "Source does not support downloads",
                    TranslationRequestFailureKind.SOURCE_UNSUPPORTED,
                )
            }
            return
        }
        val wasEmpty = queueState.value.isEmpty()
        val alreadyDownloadedIds = mutableSetOf<Long>()
        val alreadyQueuedIds = mutableSetOf<Long>()
        val chaptersToQueue = chapters.asSequence()
            // Filter out those already downloaded.
            .filter { chapter ->
                val shouldQueue = provider.findChapterDir(
                    chapter.name,
                    chapter.scanlator,
                    manga.title,
                    source,
                ) == null
                if (!shouldQueue) alreadyDownloadedIds += chapter.id
                shouldQueue
            }
            // Add chapters to queue from the start.
            .sortedByDescending { it.sourceOrder }
            // Filter out those already enqueued.
            .filter { chapter ->
                val shouldQueue = queueState.value.none { it.chapter.id == chapter.id }
                if (!shouldQueue) alreadyQueuedIds += chapter.id
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
        val enqueuedIds = chaptersToQueue.mapTo(mutableSetOf()) { it.chapter.id }
        chapters.forEach { chapter ->
            val result = when (chapter.id) {
                in alreadyDownloadedIds -> BatchDownloadQueueResult.ALREADY_DOWNLOADED
                in alreadyQueuedIds -> BatchDownloadQueueResult.ALREADY_QUEUED
                in enqueuedIds -> BatchDownloadQueueResult.ENQUEUED
                else -> return@forEach
            }
            traceQueueResult(chapter.id, result, autoStart)
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
        val traceGeneration = translationManager.pendingRequestGeneration(download.chapter.id)
        val mangaDir = provider.getMangaDir(download.manga.title, download.source)

        val availSpace = DiskUtil.getAvailableStorageSpace(mangaDir)
        if (availSpace != -1L && availSpace < MIN_DISK_SPACE) {
            download.status = Download.State.ERROR
            traceGeneration?.let { generation ->
                BatchDownloadDiagnostics.downloadTerminal(
                    chapterId = download.chapter.id,
                    generation = generation,
                    state = Download.State.ERROR.name.lowercase(Locale.ROOT),
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
            traceGeneration?.let { generation ->
                BatchDownloadDiagnostics.chapterStart(
                    chapterId = download.chapter.id,
                    generation = generation,
                    pageTotal = pageList.size,
                    resumedReady = pageList.count { it.status == Page.State.READY },
                    saveAsCbz = downloadPreferences.saveChaptersAsCBZ().get(),
                )
            }

            // Delete all temporary (unfinished) files
            tmpDir.listFiles()
                ?.filter { it.extension == "tmp" }
                ?.forEach { it.delete() }

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
                                traceGeneration?.let { generation ->
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

                        withIOContext { getOrDownloadImage(page, download, tmpDir, traceGeneration) }
                        emit(page)
                    }.flowOn(Dispatchers.IO)
                }
                .collect {
                    // Do when page is downloaded.
                    notifier.onProgressChange(download)
                }

            // Do after download completes

            val currentValidation = validateDownload(download, tmpDir, traceGeneration)
            validation = currentValidation
            if (!currentValidation.success) {
                download.status = Download.State.ERROR
                traceGeneration?.let { generation ->
                    BatchDownloadDiagnostics.downloadTerminal(
                        chapterId = download.chapter.id,
                        generation = generation,
                        state = Download.State.ERROR.name.lowercase(Locale.ROOT),
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
            traceFinalization(download.chapter.id, traceGeneration, BatchDownloadStage.METADATA, "start")
            createComicInfoFile(
                tmpDir,
                download.manga,
                download.chapter,
                download.source,
            )
            traceFinalization(download.chapter.id, traceGeneration, BatchDownloadStage.METADATA, "success")
            finalizationStage = null

            onDiskKeys = if (translationManager.hasTranslationStore(download.chapter, download.manga, download.source)) {
                tmpDir.listFiles().orEmpty()
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
                traceFinalization(download.chapter.id, traceGeneration, BatchDownloadStage.ARCHIVE, "start")
                archiveChapter(mangaDir, chapterDirname, tmpDir)
                traceFinalization(download.chapter.id, traceGeneration, BatchDownloadStage.ARCHIVE, "success")
            } else {
                finalizationStage = BatchDownloadStage.RENAME
                traceFinalization(download.chapter.id, traceGeneration, BatchDownloadStage.RENAME, "start")
                tmpDir.renameTo(chapterDirname)
                traceFinalization(download.chapter.id, traceGeneration, BatchDownloadStage.RENAME, "success")
            }
            finalizationStage = BatchDownloadStage.CACHE
            traceFinalization(download.chapter.id, traceGeneration, BatchDownloadStage.CACHE, "start")
            cache.addChapter(chapterDirname, mangaDir, download.manga)
            traceFinalization(download.chapter.id, traceGeneration, BatchDownloadStage.CACHE, "success")
            finalizationStage = null

            DiskUtil.createNoMediaFile(tmpDir, context)

            download.status = Download.State.DOWNLOADED
            traceGeneration?.let { generation ->
                BatchDownloadDiagnostics.downloadTerminal(
                    chapterId = download.chapter.id,
                    generation = generation,
                    state = Download.State.DOWNLOADED.name.lowercase(Locale.ROOT),
                    cause = BatchDownloadCause.UNKNOWN,
                    expected = currentValidation.expected,
                    ready = currentValidation.ready,
                    onDisk = currentValidation.onDisk,
                )
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            finalizationStage?.let { stage ->
                traceFinalization(download.chapter.id, traceGeneration, stage, "failed", error)
            }
            traceGeneration?.let { generation ->
                BatchDownloadDiagnostics.downloadTerminal(
                    chapterId = download.chapter.id,
                    generation = generation,
                    state = Download.State.ERROR.name.lowercase(Locale.ROOT),
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

        handOffAfterFinalization(download, pageList, onDiskKeys)
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
    ) {
        val traceGeneration = translationManager.pendingRequestGeneration(download.chapter.id)
        var stage = BatchDownloadStage.ADMISSION
        try {
            if (onDiskKeys.isNotEmpty()) {
                stage = BatchDownloadStage.REKEY
                traceGeneration?.let { generation ->
                    BatchDownloadDiagnostics.handoff(
                        download.chapter.id,
                        generation,
                        stage,
                        "start",
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
                traceGeneration?.let { generation ->
                    BatchDownloadDiagnostics.handoff(
                        download.chapter.id,
                        generation,
                        stage,
                        "success",
                    )
                }
            }
            stage = BatchDownloadStage.ADMISSION
            traceGeneration?.let { generation ->
                BatchDownloadDiagnostics.handoff(download.chapter.id, generation, stage, "start")
            }
            translationManager.startTranslationAfterDownloadIfRequested(download.manga, download.chapter)
            traceGeneration?.let { generation ->
                BatchDownloadDiagnostics.handoff(download.chapter.id, generation, stage, "success")
            }
        } catch (error: Throwable) {
            if (error is CancellationException) {
                traceGeneration?.let { generation ->
                    BatchDownloadDiagnostics.handoff(
                        download.chapter.id,
                        generation,
                        stage,
                        "cancelled",
                        error,
                    )
                }
                throw error
            }
            traceGeneration?.let { generation ->
                BatchDownloadDiagnostics.handoff(
                    download.chapter.id,
                    generation,
                    stage,
                    "failed",
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
        traceGeneration: Long?,
    ) {
        // If the image URL is empty, do nothing
        if (page.imageUrl == null) {
            return
        }

        val digitCount = (download.pages?.size ?: 0).toString().length.coerceAtLeast(3)
        val filename = "%0${digitCount}d".format(Locale.ENGLISH, page.number)
        val tmpFile = tmpDir.findFile("$filename.tmp")

        // Delete temp file if it exists
        tmpFile?.delete()

        // Try to find the image file
        val imageFile = tmpDir.listFiles()?.firstOrNull {
            it.name!!.startsWith("$filename.") || it.name!!.startsWith("${filename}__001")
        }

        var stage = BatchDownloadStage.SPLIT
        try {
            // If the image is already downloaded, do nothing. Otherwise download from network
            val file = when {
                imageFile != null -> imageFile
                chapterCache.isImageInCache(
                    page.imageUrl!!,
                ) -> {
                    stage = BatchDownloadStage.CACHE_COPY
                    copyImageFromCache(chapterCache.getImageFile(page.imageUrl!!), tmpDir, filename)
                }
                else -> {
                    stage = BatchDownloadStage.HTTP_FETCH
                    downloadImage(
                        page,
                        download.source,
                        tmpDir,
                        filename,
                        download.chapter.id,
                        traceGeneration,
                    )
                }
            }

            // When the page is ready, set page path, progress (just in case) and status
            stage = BatchDownloadStage.SPLIT
            splitTallImageIfNeeded(page, tmpDir, download.chapter.id, traceGeneration)

            page.uri = file.uri
            page.progress = 100
            page.status = Page.State.READY
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            if (stage != BatchDownloadStage.HTTP_FETCH) {
                traceGeneration?.let { generation ->
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
        traceGeneration: Long?,
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
                    tracePageAttemptFailure(chapterId, traceGeneration, page, attempt, stage, error)
                    throw error
                }
                stage = BatchDownloadStage.CREATE_TEMP
                val file = try {
                    tmpDir.createFile("$filename.tmp")!!
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    tracePageAttemptFailure(chapterId, traceGeneration, page, attempt, stage, error)
                    throw error
                }
                try {
                    stage = BatchDownloadStage.WRITE_TEMP
                    response.body.source().saveTo(file.openOutputStream())
                    stage = BatchDownloadStage.DETECT_TYPE
                    val extension = getImageExtension(response, file)
                    stage = BatchDownloadStage.RENAME_TEMP
                    file.renameTo("$filename.$extension")
                } catch (e: Exception) {
                    tracePageAttemptFailure(chapterId, traceGeneration, page, attempt, stage, e)
                    response.close()
                    file.delete()
                    throw e
                }
                emit(file)
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
            traceGeneration?.let { generation ->
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
    private fun copyImageFromCache(cacheFile: File, tmpDir: UniFile, filename: String): UniFile {
        val tmpFile = tmpDir.createFile("$filename.tmp")!!
        cacheFile.inputStream().use { input ->
            tmpFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }
        val extension = ImageUtil.findImageType(cacheFile.inputStream()) ?: return tmpFile
        tmpFile.renameTo("$filename.${extension.extension}")
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

    private fun splitTallImageIfNeeded(
        page: Page,
        tmpDir: UniFile,
        chapterId: Long,
        traceGeneration: Long?,
    ) {
        if (!downloadPreferences.splitTallImages().get()) return

        try {
            val filenamePrefix = "%03d".format(Locale.ENGLISH, page.number)
            val imageFile = tmpDir.listFiles()?.firstOrNull { it.name.orEmpty().startsWith(filenamePrefix) }
                ?: error(context.stringResource(MR.strings.download_notifier_split_page_not_found, page.number))

            // If the original page was previously split, then skip
            if (imageFile.name.orEmpty().startsWith("${filenamePrefix}__")) return

            ImageUtil.splitTallImage(tmpDir, imageFile, filenamePrefix)
        } catch (e: Exception) {
            tracePageAttemptFailure(
                chapterId,
                traceGeneration,
                page,
                1,
                BatchDownloadStage.SPLIT,
                e,
            )
            logcat(LogPriority.ERROR, e) { "Failed to split downloaded image" }
        }
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
        traceGeneration: Long?,
        page: Page,
        attempt: Int,
        stage: BatchDownloadStage,
        error: Throwable,
    ) {
        traceGeneration ?: return
        BatchDownloadDiagnostics.pageAttemptFailed(
            chapterId = chapterId,
            generation = traceGeneration,
            pageIndex = page.index,
            pageNumber = page.number,
            attempt = attempt,
            stage = stage,
            cause = causeForStage(stage, error),
            error = error,
        )
    }

    private fun traceFinalization(
        chapterId: Long,
        traceGeneration: Long?,
        stage: BatchDownloadStage,
        result: String,
        error: Throwable? = null,
    ) {
        traceGeneration ?: return
        BatchDownloadDiagnostics.finalization(
            chapterId = chapterId,
            generation = traceGeneration,
            stage = stage,
            result = result,
            error = error,
        )
    }

    private fun causeForStage(stage: BatchDownloadStage, error: Throwable? = null): BatchDownloadCause = when (stage) {
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
    private fun validateDownload(
        download: Download,
        tmpDir: UniFile,
        traceGeneration: Long?,
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
        val errorPages = pages.filter { it.status == Page.State.ERROR }.map { it.index }

        // Ensure that the chapter folder has all the pages
        val countOnDisk = {
            tmpDir.listFiles().orEmpty().count {
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
        // Preserve the original success-path exception behavior. On a page
        // mismatch, the extra best-effort count is diagnostic-only.
        val downloadedImagesCount = if (readyCount == downloadPageCount) {
            countOnDisk()
        } else {
            runCatching(countOnDisk).getOrNull()
        }
        traceGeneration?.let { generation ->
            BatchDownloadDiagnostics.validation(
                chapterId = download.chapter.id,
                generation = generation,
                expected = downloadPageCount,
                ready = readyCount,
                onDisk = downloadedImagesCount,
                errorPages = errorPages,
            )
        }
        return DownloadValidation(
            expected = downloadPageCount,
            ready = readyCount,
            onDisk = downloadedImagesCount,
            errorPages = errorPages,
            success = readyCount == downloadPageCount && downloadedImagesCount == downloadPageCount,
        )
    }

    private data class DownloadValidation(
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
    ) {
        val zip = mangaDir.createFile("$dirname.cbz$TMP_DIR_SUFFIX")!!
        ZipWriter(context, zip).use { writer ->
            tmpDir.listFiles()?.forEach { file ->
                writer.write(file)
            }
        }
        zip.renameTo("$dirname.cbz")
        tmpDir.delete()
    }

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
    }
}

// Arbitrary minimum required space to start a download: 200 MB
private const val MIN_DISK_SPACE = 200L * 1024 * 1024
