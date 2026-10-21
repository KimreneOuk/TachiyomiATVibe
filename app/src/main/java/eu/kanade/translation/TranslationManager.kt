package eu.kanade.translation

import android.content.Context
import androidx.annotation.VisibleForTesting
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.coordinator.BatchTranslationCoordinator
import eu.kanade.translation.coordinator.*
import eu.kanade.translation.coordinator.ReaderTranslationCoordinator
import eu.kanade.translation.coordinator.TranslationSessionCoordinator
import eu.kanade.translation.coordinator.TranslationRequestCoordinator
import eu.kanade.translation.coordinator.TranslationProgressProjection
import eu.kanade.translation.coordinator.ReaderSessionIntent
import eu.kanade.translation.coordinator.SessionAdmission
import eu.kanade.translation.coordinator.TranslationSessionState
import eu.kanade.translation.engines.inpainting.InpaintingHardwareOverride
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationRequestFailureKind
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.persistence.ArtifactManifestProbe
import eu.kanade.translation.persistence.*
import eu.kanade.translation.persistence.ActiveChapterStoreRegistry
import eu.kanade.translation.persistence.ChapterResetPreflight
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.TranslationFileProvider
import eu.kanade.translation.coordinator.TranslationPendingRequestStore
import eu.kanade.translation.processing.TranslationProcessor
import eu.kanade.translation.processing.batch.progress.TranslationBatchProgressTracker
import eu.kanade.translation.processing.batch.progress.TranslationBatchTrackerRegistry
import eu.kanade.translation.processing.execution.TranslationStreamRegistry
import eu.kanade.translation.coordinator.TranslationStoreResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class TranslationManager private constructor(
    internal val context: Context,
    internal val provider: TranslationFileProvider,
    internal val sourceManager: SourceManager,
    internal val translationPreferences: TranslationPreferences,
    // File truth for startup reconciliation (pending + valid files -> admit once).
    // DownloadProvider only resolves directories, avoiding a callback into this manager.
    internal val downloadProvider: eu.kanade.tachiyomi.data.download.DownloadProvider,
    internal val testConstruction: TestConstruction?,
) {
    constructor(
        context: Context,
        provider: TranslationFileProvider = Injekt.get(),
        sourceManager: SourceManager = Injekt.get(),
        translationPreferences: TranslationPreferences = Injekt.get(),
        downloadProvider: eu.kanade.tachiyomi.data.download.DownloadProvider = Injekt.get(),
    ) : this(context, provider, sourceManager, translationPreferences, downloadProvider, null)

    internal data class TestConstruction(
        val processor: TranslationProcessor,
        val batchCoordinator: BatchTranslationCoordinator,
        val pendingRequestStore: TranslationPendingRequestStore,
        val pendingRequests: MutableStateFlow<Map<Long, TranslationRequestState>>,
        val sessionCoordinator: TranslationSessionCoordinator?,
        val streamRegistry: TranslationStreamRegistry,
        val readerCoordinator: ReaderTranslationCoordinator?,
        val readerStoreResolver: (suspend (Manga, Chapter, HttpSource) -> ChapterTranslationStore?)?,
    )

    companion object {
        @VisibleForTesting
        internal fun createForTesting(
            context: Context,
            provider: TranslationFileProvider,
            sourceManager: SourceManager,
            translationPreferences: TranslationPreferences,
            downloadProvider: eu.kanade.tachiyomi.data.download.DownloadProvider,
            processor: TranslationProcessor,
            batchCoordinator: BatchTranslationCoordinator,
            pendingRequestStore: TranslationPendingRequestStore,
            pendingRequests: MutableStateFlow<Map<Long, TranslationRequestState>> = MutableStateFlow(emptyMap()),
            sessionCoordinator: TranslationSessionCoordinator? = null,
            streamRegistry: TranslationStreamRegistry = TranslationStreamRegistry(),
            readerCoordinator: ReaderTranslationCoordinator? = null,
            readerStoreResolver: (suspend (Manga, Chapter, HttpSource) -> ChapterTranslationStore?)? = null,
        ): TranslationManager = TranslationManager(
            context = context,
            provider = provider,
            sourceManager = sourceManager,
            translationPreferences = translationPreferences,
            downloadProvider = downloadProvider,
            testConstruction = TestConstruction(
                processor = processor,
                batchCoordinator = batchCoordinator,
                pendingRequestStore = pendingRequestStore,
                pendingRequests = pendingRequests,
                sessionCoordinator = sessionCoordinator,
                streamRegistry = streamRegistry,
                readerCoordinator = readerCoordinator,
                readerStoreResolver = readerStoreResolver,
            ),
        )
    }

    internal val isProductionConstruction: Boolean get() = testConstruction == null

    /** Single owner for reader/batch admission; lifecycle coordinators delegate here. */
    val sessionCoordinator = testConstruction?.sessionCoordinator ?: TranslationSessionCoordinator(
        onBatchSwitchRequested = { batchCoordinator.pause() },
    )

    // Held here so deleteTranslation can evict stale reader page-stream closures.
    internal val streamRegistry: TranslationStreamRegistry = testConstruction?.streamRegistry ?: Injekt.get()
    internal val downloadedPageStreams = DownloadPageStreamResolver(context, downloadProvider)
    internal val processor = testConstruction?.processor ?: TranslationProcessor(
        context = context,
        provider = provider,
        translationPreferences = translationPreferences,
        streamRegistry = streamRegistry,
    )
    internal val testReaderCoordinator: ReaderTranslationCoordinator? get() = testConstruction?.readerCoordinator
    internal val testReaderStoreResolver: (suspend (Manga, Chapter, HttpSource) -> ChapterTranslationStore?)?
        get() = testConstruction?.readerStoreResolver
    /**
     * Application-lifetime scope for one-off init work (queue rehydration). SupervisorJob so a
     * failure in restoreQueue does not cancel unrelated work; IO dispatcher because restoreQueue does DB reads.
     */
    internal val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal val activeStores = ActiveChapterStoreRegistry()
    internal val batchTrackerRegistry = TranslationBatchTrackerRegistry()

    /** Owns tracker reducer jobs; reader flows observe the selected store directly. */
    internal val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal val durableStatusResolver: DurableChapterStatusResolver by lazy { createDurableStatusResolver() }
    internal val cleanedImageLifecycle: CleanedImageLifecycleController by lazy { createCleanedImageLifecycle() }
    internal val chapterTranslationAccess: ChapterTranslationAccessController by lazy {
        ChapterTranslationAccessController(
            provider = provider,
            activeStores = activeStores,
            durableStatusResolver = durableStatusResolver,
            cleanedImageLifecycle = cleanedImageLifecycle,
        )
    }

    // Initialize these lazy owner references before runtime wiring captures their method refs.
    internal val requestCoordinator: TranslationRequestCoordinator by lazy { createRequestCoordinator() }

    internal val completedDownloadRekeyCoordinator: CompletedDownloadRekeyCoordinator by lazy {
        CompletedDownloadRekeyCoordinator(
            provider = provider,
            activeStores = activeStores,
            batchCoordinator = batchCoordinator,
            requestCoordinator = { requestCoordinator },
            readerCoordinator = { readerCoordinator },
        )
    }

    internal val coordinatorRuntime = createCoordinatorRuntime()

    /** Both lanes share one TranslationProcessor owned by the manager. */
    internal val readerCoordinator: ReaderTranslationCoordinator get() = coordinatorRuntime.readerCoordinator
    internal val batchCoordinator: BatchTranslationCoordinator
        get() = testConstruction?.batchCoordinator ?: coordinatorRuntime.batchCoordinator

    /**
     * Reader, batch, and download-rekey store binding share a chapter admission
     * gate. A re-key holds it through its guarded file transaction so a new
     * reader cannot bind a live online store between the ownership check and
     * the mutation.
     */
    /** Immediate, lifecycle-independent acknowledgement for pre-translation requests. */
    val pendingTranslationRequests: StateFlow<Map<Long, TranslationRequestState>>
        get() = requestCoordinator.pendingTranslationRequests

    /** Reader-visible native lane stall projection. */
    val nativeStall: StateFlow<eu.kanade.translation.engines.translator.NativeStallState?>
        get() = processor.nativeStall

    init { initializeTranslationManager() }

    val isRunning: Boolean
        get() = batchCoordinator.isRunning

    val queueState: StateFlow<List<Translation>>
        get() = batchCoordinator.queueState

    val isAnyBatchTranslationActive: Boolean
        get() = batchCoordinator.isAnyBatchTranslationActive

    /**
     * Quiesce the batch owner before admitting a confirmed reader request.
     * This method deliberately owns no manager/store/request lock while the
     * bounded native join retries; the coordinator invokes the translator's
     * non-blocking pause first and then joins the same parent job until it
     * genuinely unwinds.
     */
    suspend fun switchReaderSession(chapterId: Long?): SessionAdmission = readerCoordinator.switchReaderSession(chapterId)

    fun queueTranslationAfterDownload(manga: Manga, chapter: Chapter) = requestCoordinator.queueTranslationAfterDownload(manga, chapter)

    /** Drops the WAITING write when the request was cancelled or replaced. */
    fun queueTranslationAfterDownloadIfCurrent(
        manga: Manga,
        chapter: Chapter,
        expectedGeneration: Long,
    ): Boolean = requestCoordinator.queueTranslationAfterDownloadIfCurrent(manga, chapter, expectedGeneration)

    fun acknowledgeTranslationRequests(chapters: List<Chapter>) = requestCoordinator.acknowledgeTranslationRequests(chapters)

    fun markTranslationRequestPreparing(chapterId: Long) = requestCoordinator.markTranslationRequestPreparing(chapterId)

    /** Drops the PREPARING write when the request was cancelled or replaced. */
    fun markTranslationRequestPreparingIfCurrent(chapterId: Long, expectedGeneration: Long): Boolean =
        requestCoordinator.markTranslationRequestPreparingIfCurrent(chapterId, expectedGeneration)

    /** Current in-process request generation, or null when no request exists. */
    fun pendingRequestGeneration(chapterId: Long): Long? =
        requestCoordinator.pendingRequestGeneration(chapterId)

    /** Whether the live request still carries [generation]. */
    fun isTranslationRequestCurrent(chapterId: Long, generation: Long): Boolean =
        requestCoordinator.isTranslationRequestCurrent(chapterId, generation)

    fun markTranslationDownloadFailed(
        chapterId: Long,
        reason: String? = null,
        failureKind: TranslationRequestFailureKind = TranslationRequestFailureKind.DOWNLOAD_FAILED,
    ) = requestCoordinator.markTranslationDownloadFailed(chapterId, reason, failureKind)

    fun markTranslationHandoffFailed(
        chapterId: Long,
        reason: String? = null,
    ) = requestCoordinator.markTranslationHandoffFailed(chapterId, reason)

    fun onDownloadCancelledForTranslation(chapterId: Long) = requestCoordinator.onDownloadCancelled(chapterId)

    fun onDownloadQueueClearedForTranslation(chapterId: Long) = requestCoordinator.onDownloadQueueCleared(chapterId)

    fun onDownloadStoppedForTranslation(chapterId: Long, reason: String?) = requestCoordinator.onDownloadStopped(chapterId, reason)

    fun cancelTranslationRequest(chapterId: Long): Boolean =
        requestCoordinator.cancelTranslationRequest(chapterId)

    fun clearStaleDownloadFailedRequest(chapterId: Long) = requestCoordinator.clearStaleDownloadFailedRequest(chapterId)

    /** Requeues a request after a download failure. */
    fun rearmDownloadFailedRequest(chapterId: Long): Boolean =
        requestCoordinator.rearmDownloadFailedRequest(chapterId)

    fun hasPendingTranslationRequest(chapterId: Long): Boolean =
        requestCoordinator.hasPendingTranslationRequest(chapterId)

    fun isChapterTranslationProtected(chapterId: Long): Boolean =
        requestCoordinator.isChapterTranslationProtected(chapterId)

    fun protectedChapterIds(): Set<Long> = requestCoordinator.protectedChapterIds()

    suspend fun startTranslationAfterDownloadIfRequested(manga: Manga, chapter: Chapter) =
        requestCoordinator.startTranslationAfterDownload(manga, chapter)

    fun onDownloadQueueRestored(queuedChapterIds: Set<Long>) = requestCoordinator.onDownloadQueueRestored(queuedChapterIds)

    fun stopReaderTranslations(reason: String) = readerCoordinator.stopReaderTranslations(reason)

    fun requestReaderStop(reason: String): Deferred<Unit> = readerCoordinator.requestReaderStop(reason)

    suspend fun awaitReaderStop(reason: String) = readerCoordinator.awaitReaderStop(reason)

    /** Canonical batch projection used by every UI surface and the notification. */
    fun getTranslationProgress(chapterId: Long): Flow<TranslationProgressSnapshot> =
        progressProjection.observeBatchProgress(chapterId)

    fun translatorStop(reason: String? = null, closeEngines: Boolean = false) = batchCoordinator.stop(reason, closeEngines)

    fun onMemoryPressure(level: Int) = readerCoordinator.onMemoryPressure(level)

    fun startTranslation() = batchCoordinator.startWithSessionAdmission()

    fun pauseTranslation() = batchCoordinator.pause()

    suspend fun requeueTranslation(chapterId: Long, force: Boolean = false): Boolean =
        batchCoordinator.requeueWithSessionAdmission(chapterId, force)

    fun clearQueue() = requestCoordinator.clearTranslationQueue()

    fun getQueuedTranslationOrNull(chapterId: Long): Translation? = batchCoordinator.queuedTranslationOrNull(chapterId)

    fun prioritizeChapter(chapterId: Long) = batchCoordinator.prioritizeChapter(chapterId)

    suspend fun warmUp() = processor.warmUp()

    fun setInpaintingHardwareOverride(override: InpaintingHardwareOverride) = processor.setInpaintingHardwareOverride(override)

    /**
     * Whether a chapter owns batch work under the requested activity policy.
     * PAUSED is included only when the caller is retaining reader-owned artifacts.
     */
    fun isChapterBatchActive(chapterId: Long, includePaused: Boolean): Boolean =
        batchCoordinator.isChapterBatchActive(chapterId, includePaused)
    fun translateChapter(
        manga: Manga,
        chapters: Chapter,
        expectedRequestGeneration: Long? = null,
        // The trigger's admission-probe cross-check; null
        // keeps the existing no-cross-check path.
        admissionContext: eu.kanade.translation.processing.batch.BatchAdmissionContext? = null,
        // False for a restore-admitted request handed over by the
        // download completion — enqueue without starting.
        autoStart: Boolean = true,
    ) {
        requestCoordinator.translateChapterAndStart(
            manga,
            chapters,
            expectedRequestGeneration,
            admissionContext,
            autoStart,
        )
    }

    fun translateChapters(manga: Manga, chapters: List<Chapter>) {
        requestCoordinator.translateChaptersAndStart(manga, chapters)
    }

    fun translateChaptersIfCurrent(
        manga: Manga,
        chapters: List<Chapter>,
        expectedGenerations: Map<Long, Long>,
        admissionContexts: Map<Long, eu.kanade.translation.processing.batch.BatchAdmissionContext> = emptyMap(),
    ): Boolean = requestCoordinator.translateChaptersAndStart(manga, chapters, expectedGenerations, admissionContexts).isNotEmpty()

    fun translateChapterPreflight(manga: Manga, chapter: Chapter): ChapterQueuePreflight =
        batchCoordinator.queuePreflight(chapter.id, manga.source)

    suspend fun cancelRunningChapterForReplace(chapterId: Long) =
        readerCoordinator.cancelRunningChapterForReplace(chapterId)

    // Progress flow orchestration lives in TranslationProgressProjection;
    // page/display/status truth is projected by ChapterTranslationStore.
    internal val progressProjection: TranslationProgressProjection by lazy { createProgressProjection() }

    //  ANR fix: suspend — delegates to the projector whose durable leg
    // performs SAF/FUSE I/O. Callers (ReaderViewModel.loadChapter,
    // MangaScreenModel chapter list) invoke this from IO coroutines.
    suspend fun getChapterTranslationStatus(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        title: String,
        sourceId: Long,
    ): Translation.State = progressProjection.getChapterTranslationStatus(
        chapterId,
        chapterName,
        scanlator,
        title,
        sourceId,
    )

    fun observeChapterTranslationStatus(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        title: String,
        sourceId: Long,
    ): Flow<Translation.State> = progressProjection.observeChapterTranslationStatus(
        chapterId,
        chapterName,
        scanlator,
        title,
        sourceId,
    )

    //  ANR fix: suspend — this used to be reached synchronously from the
    // main thread (ReaderViewModel.loadChapter inside withUIContext,
    // MangaScreenModel's chapter-list build) and parked Main in
    // runBlocking(Dispatchers.IO) inside DurableChapterStatusResolver for
    // 5-9s on a large translated chapter. The chain is now fully suspend.
    fun getChapterTranslation(
        chapterName: String,
        scanlator: String?,
        title: String,
        source: Source,
    ): Map<String, PageTranslationView> = chapterTranslationAccess.getChapterTranslation(chapterName, scanlator, title, source)

    suspend fun getChapterTranslationForReader(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
    ): Map<String, PageTranslationView> = chapterTranslationAccess.getChapterTranslationForReader(
        chapterId,
        chapterName,
        scanlator,
        mangaTitle,
        source,
    )

    fun hasTranslationStore(chapter: Chapter, manga: Manga, source: Source): Boolean =
        chapterTranslationAccess.hasTranslationStore(
            chapterName = chapter.name,
            chapterScanlator = chapter.scanlator,
            mangaTitle = manga.title,
            source = source,
            chapterId = chapter.id,
        )

    suspend fun rekeyTranslationForCompletedDownload(
        chapter: Chapter,
        manga: Manga,
        source: Source,
        onlineKeyByPageIndex: List<String>,
        onDiskKeyByPageIndex: List<String>,
    ): DownloadRekeyOutcome = completedDownloadRekeyCoordinator.rekeyTranslationForCompletedDownload(
        chapter,
        manga,
        source,
        onlineKeyByPageIndex,
        onDiskKeyByPageIndex,
    )

    suspend fun unregisterActiveTranslationStore(chapterId: Long) = chapterTranslationAccess.unregisterActiveTranslationStore(chapterId)

    /**
     * Resolves the existing active [ChapterTranslationStore] for [chapterId],
     * or suspends while opening one from disk if it is not yet registered. If
     * neither an in-memory store nor an on-disk translation file exists, this
     * creates a fresh store tied to the expected translation path and registers
     * it, so a translator starting now and a reader observing now share the
     * same instance.
     *
     * This suspends on IO because opening a store can probe artifact storage
     * with SAF binder I/O; callers should not block their dispatcher while that
     * work runs.
     */
    suspend fun openOrCreateActiveChapterTranslationStoreSuspend(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
        mangaId: Long? = null,
    ): ChapterTranslationStore? = chapterTranslationAccess.openOrCreateActiveChapterTranslationStore(
        chapterId,
        chapterName,
        scanlator,
        mangaTitle,
        source,
        mangaId,
    )

    /**
     * Source factory for a manual tap on a downloaded chapter without live page
     * bytes. Each invocation rescans the chapter dir and opens a fresh stream;
     * a missing dir or page throws so the attempt fails with a typed pipeline
     * failure instead of a null dereference.
     */
    fun downloadedChapterPageStream(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
    ): (() -> java.io.InputStream)? =
        if (chapter.id == null) {
            null
        } else {
            downloadedPageStreams.resolve(manga, chapter, source, pageKey)
        }

    /**
     * Chapter-keyed active page source for reader state. Unlike a global active-store stream,
     * this never emits another chapter's pages and becomes empty when this chapter is removed.
     */
    fun selectActiveStore(chapterId: Long): Flow<Map<String, PageTranslationView>> = activeStores.select(chapterId)

    fun observeBatchProgress(
        chapterId: Long,
        durableStateHint: Translation.State? = null,
    ): Flow<TranslationProgressSnapshot> =
        progressProjection.observeBatchProgress(chapterId, durableStateHint)

    // Chapter and page deletion/reset ordering is owned by this collaborator.
    private val chapterDataReset: ChapterDataResetController by lazy { createChapterDataReset() }

    suspend fun deleteTranslation(chapter: Chapter, manga: Manga, source: Source) =
        chapterDataReset.deleteTranslation(chapter, manga, source)

    suspend fun chapterResetPreflight(
        chapter: Chapter,
        manga: Manga,
        source: Source,
    ): ChapterResetPreflight = chapterDataReset.chapterResetPreflight(chapter, manga, source)

    suspend fun resetChapterTranslationData(
        chapter: Chapter,
        manga: Manga,
        source: Source,
        preserveEdits: Boolean,
    ) = chapterDataReset.resetChapterTranslationData(chapter, manga, source, preserveEdits)

    suspend fun resetChapterInpaintData(chapter: Chapter, manga: Manga, source: Source) =
        chapterDataReset.resetChapterInpaintData(chapter, manga, source)

    suspend fun resetChapterOcrData(chapter: Chapter, manga: Manga, source: Source) =
        chapterDataReset.resetChapterOcrData(chapter, manga, source)

    suspend fun resetTranslationData(chapter: Chapter, manga: Manga, source: Source, pageKey: String, preserveEdits: Boolean) =
        chapterDataReset.resetTranslationData(chapter, manga, source, pageKey, preserveEdits)

    suspend fun resetInpaintData(chapter: Chapter, manga: Manga, source: Source, pageKey: String) =
        chapterDataReset.resetInpaintData(chapter, manga, source, pageKey)

    suspend fun resetOcrData(chapter: Chapter, manga: Manga, source: Source, pageKey: String) =
        chapterDataReset.resetOcrData(chapter, manga, source, pageKey)

    fun cancelQueuedTranslation(translation: Translation) = batchCoordinator.removeChapterAndResume(translation.chapter)

    // The manager exposes the cleaned-image stream through the lifecycle collaborator.
    fun getCleanedImageStream(
        mangaTitle: String,
        source: Source,
        chapterName: String,
        chapterScanlator: String?,
        cleanedImageName: String,
        pageKey: String? = null,
        mangaId: Long? = null,
        chapterId: Long? = null,
    ): (() -> java.io.InputStream)? =
        cleanedImageLifecycle.getCleanedImageStream(
            mangaTitle,
            source,
            chapterName,
            chapterScanlator,
            cleanedImageName,
            pageKey,
            mangaId,
            chapterId,
        )

    // Reader-page cancellation and teardown are owned by the lifecycle collaborator.

    fun cancelPageTranslation(chapterId: Long, pageKey: String): Boolean =
        readerCoordinator.cancelPageTranslation(chapterId, pageKey)

    suspend fun cancelPageTranslations(
        chapterId: Long,
        reason: String = "Translation cancelled",
    ) = readerCoordinator.cancelPageTranslations(chapterId, reason)

    suspend fun cancelAllPageTranslations(
        cancelBatchQueue: Boolean = false,
        reason: String = "All translation cancelled",
    ) = readerCoordinator.cancelAllPageTranslations(cancelBatchQueue, reason)

    suspend fun cancelAllPageTranslationsOffMain(
        cancelBatchQueue: Boolean = false,
        reason: String = "All translation cancelled",
    ) = readerCoordinator.cancelAllPageTranslationsOffMain(cancelBatchQueue, reason)

    fun statusFlow(): Flow<Translation> = batchCoordinator.statusFlow()
}
