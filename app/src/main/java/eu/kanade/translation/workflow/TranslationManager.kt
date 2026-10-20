package eu.kanade.translation.workflow

import android.content.Context
import androidx.annotation.VisibleForTesting
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.coordinator.BatchTranslationCoordinator
import eu.kanade.translation.coordinator.ReaderTranslationCoordinator
import eu.kanade.translation.engines.inpainting.InpaintingHardwareOverride
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationRequestFailureKind
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.persistence.ArtifactManifestProbe
import eu.kanade.translation.persistence.ActiveChapterStoreRegistry
import eu.kanade.translation.persistence.ChapterResetPreflight
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.TranslationFileProvider
import eu.kanade.translation.coordinator.TranslationPendingRequestStore
import eu.kanade.translation.processing.TranslationProcessor
import eu.kanade.translation.pipeline.batch.progress.TranslationBatchProgressTracker
import eu.kanade.translation.pipeline.batch.progress.TranslationBatchTrackerRegistry
import eu.kanade.translation.pipeline.execution.TranslationStreamRegistry
import eu.kanade.translation.coordinator.TranslationStoreResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class TranslationManager private constructor(
    internal val context: Context,
    internal val provider: TranslationFileProvider,
    internal val sourceManager: SourceManager,
    internal val translationPreferences: TranslationPreferences,
    // File truth for startup reconciliation (pending + valid files -> admit once).
    // DownloadProvider only resolves directories, avoiding a callback into this manager.
    internal val downloadProvider: eu.kanade.tachiyomi.data.download.DownloadProvider,
    private val testConstruction: TestConstruction?,
) {
    constructor(
        context: Context,
        provider: TranslationFileProvider = Injekt.get(),
        sourceManager: SourceManager = Injekt.get(),
        translationPreferences: TranslationPreferences = Injekt.get(),
        downloadProvider: eu.kanade.tachiyomi.data.download.DownloadProvider = Injekt.get(),
    ) : this(context, provider, sourceManager, translationPreferences, downloadProvider, null)

    private data class TestConstruction(
        val processor: TranslationProcessor,
        val translator: ChapterTranslator,
        val pendingRequestStore: TranslationPendingRequestStore,
        val pendingRequests: MutableStateFlow<Map<Long, TranslationRequestState>>,
        val sessionCoordinator: TranslationSessionCoordinator?,
        val streamRegistry: TranslationStreamRegistry,
        val readerCoordinator: ReaderTranslationCoordinator?,
        val readerStoreResolver: (suspend (Manga, Chapter, HttpSource) -> ChapterTranslationStore?)?,
    )

    internal enum class AdmissionResult {
        STALE_GENERATION,
        QUEUED,
        QUEUE_FAILED,
    }

    companion object {
        @VisibleForTesting
        internal fun createForTesting(
            context: Context,
            provider: TranslationFileProvider,
            sourceManager: SourceManager,
            translationPreferences: TranslationPreferences,
            downloadProvider: eu.kanade.tachiyomi.data.download.DownloadProvider,
            processor: TranslationProcessor,
            translator: ChapterTranslator,
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
                translator = translator,
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
        onBatchSwitchRequested = { translator.pause() },
    )

    // Held here so deleteTranslation can evict stale reader page-stream closures.
    internal val streamRegistry: TranslationStreamRegistry = testConstruction?.streamRegistry ?: Injekt.get()
    internal val processor = testConstruction?.processor ?: TranslationProcessor(
        context = context,
        provider = provider,
        translationPreferences = translationPreferences,
        streamRegistry = streamRegistry,
    )
    internal val testReaderCoordinator: ReaderTranslationCoordinator? get() = testConstruction?.readerCoordinator
    internal val testReaderStoreResolver: (suspend (Manga, Chapter, HttpSource) -> ChapterTranslationStore?)?
        get() = testConstruction?.readerStoreResolver
    internal fun createPendingRequestStoreForCoordinator(): TranslationPendingRequestStore =
        testConstruction?.pendingRequestStore ?: TranslationPendingRequestStore(context)
    internal fun initialPendingRequestsForCoordinator(): MutableStateFlow<Map<Long, TranslationRequestState>>? =
        testConstruction?.pendingRequests

    /**
     * Application-lifetime scope for one-off init work (queue rehydration). SupervisorJob so a
     * failure in restoreQueue does not cancel unrelated work; IO dispatcher because restoreQueue does DB reads.
     */
    internal val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal val coordinatorRuntime = createCoordinatorRuntime()

    /** Both lanes share one TranslationProcessor owned by the manager. */
    internal val readerCoordinator: ReaderTranslationCoordinator get() = coordinatorRuntime.readerCoordinator
    internal val batchCoordinator: BatchTranslationCoordinator get() = coordinatorRuntime.batchCoordinator

    internal val translator = testConstruction?.translator ?: ChapterTranslator(
        context = context,
        provider = provider,
        processor = processor,
        batchCoordinator = batchCoordinator,
        resolveStore = { translation ->
            translation.chapter.id?.let { chapterId ->
                openOrCreateActiveChapterTranslationStoreSuspend(
                    chapterId,
                    translation.chapter.name,
                    translation.chapter.scanlator,
                    translation.manga.title,
                    translation.source,
                    translation.manga.id,
                )
            }
        },
        batchTrackerFactory = ::createBatchTracker,
        onBatchClosed = ::handleBatchClosed,
    )

    /** Serializes reader lifecycle teardown so pause/finish cannot race store eviction. */
    internal val readerTeardownMutex = Mutex()

    /**
     * Reader, batch, and download-rekey store binding share a chapter admission
     * gate. A re-key holds it through its guarded file transaction so a new
     * reader cannot bind a live online store between the ownership check and
     * the mutation.
     */
    private val chapterStoreAdmissionLocks = ConcurrentHashMap<Long, Mutex>()
    internal val chaptersUnderDownloadRekey = ConcurrentHashMap.newKeySet<Long>()

    internal suspend fun <T> withChapterStoreAdmissionLock(chapterId: Long, block: suspend () -> T): T =
        chapterStoreAdmissionLocks.computeIfAbsent(chapterId) { Mutex() }.withLock { block() }

    /** Immediate, lifecycle-independent acknowledgement for pre-translation requests. */
    val pendingTranslationRequests: StateFlow<Map<Long, TranslationRequestState>>
        get() = requestCoordinator.pendingTranslationRequestsState.asStateFlow()

    /** Reader-visible native lane stall projection. */
    val nativeStall: StateFlow<eu.kanade.translation.engines.translator.NativeStallState?>
        get() = processor.nativeStall

    // One-shot marks for pending requests startup reconciliation
    // admitted (process-local is sufficient — the reconciler and the later
    // download handoff run in the same process; a crash mid-download re-runs
    // the reconciler next process). A restore-admitted request must not
    // auto-start translation when its download completes (Director policy: no
    // OCR/LLM after a restart without an explicit user action); a live
    // in-session request never carries the mark and starts unchanged.
    internal val restoreAdmittedChapterIds = ConcurrentHashMap.newKeySet<Long>()

    // Startup reconciliation waits for both the downloader and translation
    // queue restores to complete.
    @Volatile
    internal var downloadQueueRestoredChapterIds: Set<Long>? = null

    @Volatile
    internal var translationQueueRestoreJob: Job? = null

    internal val startupReconciliationOnce = AtomicBoolean(false)

    // The request coordinator owns pending request state, persistence, and mutation fences.
    internal val requestCoordinator: TranslationRequestCoordinator by lazy { createRequestCoordinator() }

    internal fun downloadedPageStreamFactory(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
    ): (() -> java.io.InputStream)? = downloadedPageStreamFactoryImpl(manga, chapter, source, pageKey)

    init { initializeTranslationManager() }

    internal val activeStores = ActiveChapterStoreRegistry()
    internal val batchTrackerRegistry = TranslationBatchTrackerRegistry()

    /** Owns tracker reducer jobs; reader flows observe the selected store directly. */
    internal val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val isRunning: Boolean
        get() = translator.isRunning

    val queueState: StateFlow<List<Translation>>
        get() = translator.queueState

    val isAnyBatchTranslationActive: Boolean
        get() = queueState.value.any { it.status == Translation.State.QUEUE || it.status == Translation.State.TRANSLATING }

    private suspend fun handleBatchClosed(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        store: ChapterTranslationStore,
    ) {
        chapter.id?.let { chapterId ->
            sweepOrphanedCleanedImages(
                store = store,
                chapterId = chapterId,
                chapterName = chapter.name,
                scanlator = chapter.scanlator,
                mangaTitle = manga.title,
                source = source,
                mangaId = manga.id,
            )
        }
        val hasPausedTranslation = queueState.value.any { it.status == Translation.State.PAUSED }
        if (!isAnyBatchTranslationActive && !hasPausedTranslation) {
            sessionCoordinator.finishSession(TranslationSessionState.BATCH_SESSION)
        }
    }

    internal fun admitBatchSession(chapterIds: Set<Long>): Boolean = admitBatchSessionImpl(chapterIds)

    /**
     * Quiesce the batch owner before admitting a confirmed reader request.
     * This method deliberately owns no manager/store/request lock while the
     * bounded native join retries; the coordinator invokes the translator's
     * non-blocking pause first and then joins the same parent job until it
     * genuinely unwinds.
     */
    suspend fun switchReaderSession(chapterId: Long?): SessionAdmission = withContext(Dispatchers.IO) {
        sessionCoordinator.switchBatchToReader(
            intent = ReaderSessionIntent(chapterId = chapterId, confirmBatchSwitch = true),
            pauseBatch = translator::pauseForSessionSwitch,
            joinBatch = translator::cancelTranslatorJobAndJoinForSession,
            onJoinTimeout = { timeoutMs ->
                logcat(LogPriority.WARN) {
                    "TachiyomiAT reader admission waiting for batch unwind after $timeoutMs ms"
                }
            },
        )
    }

    fun queueTranslationAfterDownload(manga: Manga, chapter: Chapter) = queueTranslationAfterDownloadImpl(manga, chapter)

    /** Drops the WAITING write when the request was cancelled or replaced. */
    fun queueTranslationAfterDownloadIfCurrent(
        manga: Manga,
        chapter: Chapter,
        expectedGeneration: Long,
    ): Boolean = requestCoordinator.queueTranslationAfterDownloadIfCurrent(manga, chapter, expectedGeneration)

    fun acknowledgeTranslationRequests(chapters: List<Chapter>) = acknowledgeTranslationRequestsImpl(chapters)

    fun markTranslationRequestPreparing(chapterId: Long) = markTranslationRequestPreparingImpl(chapterId)

    /** Drops the PREPARING write when the request was cancelled or replaced. */
    fun markTranslationRequestPreparingIfCurrent(chapterId: Long, expectedGeneration: Long): Boolean =
        requestCoordinator.markTranslationRequestPreparingIfCurrent(chapterId, expectedGeneration)

    /** Current in-process request generation, or null when no request exists. */
    fun pendingRequestGeneration(chapterId: Long): Long? =
        requestCoordinator.pendingTranslationRequestsState.value[chapterId]?.generation

    /** Whether the live request still carries [generation]. */
    fun isTranslationRequestCurrent(chapterId: Long, generation: Long): Boolean =
        requestCoordinator.isTranslationRequestCurrent(chapterId, generation)

    fun markTranslationDownloadFailed(
        chapterId: Long,
        reason: String? = null,
        failureKind: TranslationRequestFailureKind = TranslationRequestFailureKind.DOWNLOAD_FAILED,
    ) = markTranslationDownloadFailedImpl(chapterId, reason, failureKind)

    fun markTranslationHandoffFailed(
        chapterId: Long,
        reason: String? = null,
    ) = markTranslationHandoffFailedImpl(chapterId, reason)

    fun onDownloadCancelledForTranslation(chapterId: Long) = onDownloadCancelledForTranslationImpl(chapterId)

    fun onDownloadQueueClearedForTranslation(chapterId: Long) = onDownloadQueueClearedForTranslationImpl(chapterId)

    fun onDownloadStoppedForTranslation(chapterId: Long, reason: String?) = onDownloadStoppedForTranslationImpl(chapterId, reason)

    fun cancelTranslationRequest(chapterId: Long): Boolean =
        requestCoordinator.cancelTranslationRequest(chapterId)

    fun clearStaleDownloadFailedRequest(chapterId: Long) = clearStaleDownloadFailedRequestImpl(chapterId)

    /** Requeues a request after a download failure. */
    fun rearmDownloadFailedRequest(chapterId: Long): Boolean =
        requestCoordinator.rearmDownloadFailedRequest(chapterId)

    fun hasPendingTranslationRequest(chapterId: Long): Boolean =
        requestCoordinator.hasPendingTranslationRequest(chapterId)

    fun isChapterTranslationProtected(chapterId: Long): Boolean =
        requestCoordinator.isChapterTranslationProtected(chapterId)

    fun protectedChapterIds(): Set<Long> = requestCoordinator.protectedChapterIds()

    internal fun setPendingTranslationRequest(
        chapterId: Long,
        phase: TranslationRequestPhase,
        reason: String? = null,
        failureKind: TranslationRequestFailureKind = TranslationRequestFailureKind.NONE,
    ) = setPendingTranslationRequestImpl(chapterId, phase, reason, failureKind)

    internal fun clearPendingTranslationRequest(chapterId: Long) = clearPendingTranslationRequestImpl(chapterId)

    internal fun clearAllPendingTranslationRequests() = clearAllPendingTranslationRequestsImpl()

    suspend fun startTranslationAfterDownloadIfRequested(manga: Manga, chapter: Chapter) = startTranslationAfterDownloadIfRequestedImpl(manga, chapter)

    fun onDownloadQueueRestored(queuedChapterIds: Set<Long>) = onDownloadQueueRestoredImpl(queuedChapterIds)

    internal fun runStartupReconciliationIfReady() = runStartupReconciliationIfReadyImpl()

    internal suspend fun reconcilePendingRequestsForStartup(
        downloadQueueChapterIds: Set<Long>,
        resolveTranslation: suspend (Long) -> Translation? = { chapterId ->
            resolveTranslationFromChapterId(chapterId)
        },
        hasDownloadedFiles: (Translation) -> Boolean = { translation ->
            downloadProvider.findChapterDir(
                translation.chapter.name,
                translation.chapter.scanlator,
                translation.manga.title,
                translation.source,
            ) != null
        },
    ) = reconcilePendingRequestsForStartupImpl(downloadQueueChapterIds, resolveTranslation, hasDownloadedFiles)

    internal fun admitRestoredPendingTranslation(translation: Translation, expectedGeneration: Long) = admitRestoredPendingTranslationImpl(translation, expectedGeneration)

    // The mutex stays on the manager so both public stop paths serialize through the same owner.
    private val readerTeardown: ReaderTeardownCoordinator by lazy { createReaderTeardown() }

    fun stopReaderTranslations(reason: String) = readerTeardown.stopReaderTranslations(reason)

    fun requestReaderStop(reason: String): Deferred<Unit> = readerTeardown.requestReaderStop(reason)

    suspend fun awaitReaderStop(reason: String) = readerTeardown.awaitReaderStop(reason)

    /** Canonical batch projection used by every UI surface and the notification. */
    fun getTranslationProgress(chapterId: Long): Flow<TranslationProgressSnapshot> =
        observeBatchProgress(chapterId)

    fun translatorStop(reason: String? = null, closeEngines: Boolean = false) = translator.stop(reason, closeEngines)

    fun onMemoryPressure(level: Int) = onMemoryPressureImpl(level)

    fun startTranslation() = startTranslationImpl()

    fun pauseTranslation() = pauseTranslationImpl()

    suspend fun requeueTranslation(chapterId: Long, force: Boolean = false): Boolean = requeueTranslationImpl(chapterId, force)

    fun clearQueue() = clearQueueImpl()

    fun getQueuedTranslationOrNull(chapterId: Long): Translation? = getQueuedTranslationOrNullImpl(chapterId)

    fun prioritizeChapter(chapterId: Long) = prioritizeChapterImpl(chapterId)

    suspend fun warmUp() = warmUpImpl()

    fun setInpaintingHardwareOverride(override: InpaintingHardwareOverride) = setInpaintingHardwareOverrideImpl(override)

    fun setReaderPaddleOcrComputeDeviceOverride(device: tachiyomi.domain.translation.PaddleOcrComputeDevice?) = setReaderPaddleOcrComputeDeviceOverrideImpl(device)

    /**
     * Whether a chapter owns batch work under the requested activity policy.
     * PAUSED is included only when the caller is retaining reader-owned artifacts.
     */
    fun isChapterBatchActive(chapterId: Long, includePaused: Boolean): Boolean = queueState.value.any { translation ->
        translation.chapter.id == chapterId &&
            (
                translation.status == Translation.State.QUEUE ||
                    translation.status == Translation.State.TRANSLATING ||
                    (includePaused && translation.status == Translation.State.PAUSED)
                )
    }
    internal fun admitChapterThroughQueue(
        manga: Manga,
        chapter: Chapter,
        expectedGeneration: Long?,
        admissionContext: eu.kanade.translation.pipeline.batch.BatchAdmissionContext?,
        evictStaleQueuedEntry: Boolean,
    ): AdmissionResult = admitChapterThroughQueueImpl(manga, chapter, expectedGeneration, admissionContext, evictStaleQueuedEntry)

    fun translateChapter(
        manga: Manga,
        chapters: Chapter,
        expectedRequestGeneration: Long? = null,
        // The trigger's admission-probe cross-check; null
        // keeps the existing no-cross-check path.
        admissionContext: eu.kanade.translation.pipeline.batch.BatchAdmissionContext? = null,
        // False for a restore-admitted request handed over by the
        // download completion — enqueue without starting.
        autoStart: Boolean = true,
    ) = translateChapterImpl(manga, chapters, expectedRequestGeneration, admissionContext, autoStart)

    fun translateChapters(manga: Manga, chapters: List<Chapter>) = translateChaptersImpl(manga, chapters)

    fun translateChaptersIfCurrent(
        manga: Manga,
        chapters: List<Chapter>,
        expectedGenerations: Map<Long, Long>,
        admissionContexts: Map<Long, eu.kanade.translation.pipeline.batch.BatchAdmissionContext> = emptyMap(),
    ): Boolean = translateChaptersIfCurrentImpl(manga, chapters, expectedGenerations, admissionContexts)

    internal fun translateChaptersInternal(
        manga: Manga,
        chapters: List<Chapter>,
        expectedGenerations: Map<Long, Long>?,
        admissionContexts: Map<Long, eu.kanade.translation.pipeline.batch.BatchAdmissionContext> = emptyMap(),
    ): List<Chapter> = translateChaptersInternalImpl(manga, chapters, expectedGenerations, admissionContexts)

    internal fun markTranslationQueueFailureIfAcknowledged(manga: Manga, chapterId: Long) = markTranslationQueueFailureIfAcknowledgedImpl(manga, chapterId)

    fun translateChapterPreflight(manga: Manga, chapter: Chapter): ChapterQueuePreflight = translateChapterPreflightImpl(manga, chapter)

    internal fun evictStaleQueuedChapters(keepChapterId: Long, sourceId: Long) = evictStaleQueuedChaptersImpl(keepChapterId, sourceId)

    suspend fun cancelRunningChapterForReplace(chapterId: Long) = cancelRunningChapterForReplaceImpl(chapterId)

    // Progress flow orchestration lives in TranslationProgressProjection;
    // page/display/status truth is projected by ChapterTranslationStore.
    private val progressProjection: TranslationProgressProjection by lazy { createProgressProjection() }

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

    // Durable status and document lookup share these manager-lifetime caches and invalidation rules.
    internal val durableStatusResolver: DurableChapterStatusResolver by lazy { createDurableStatusResolver() }

    //  ANR fix: suspend — this used to be reached synchronously from the
    // main thread (ReaderViewModel.loadChapter inside withUIContext,
    // MangaScreenModel's chapter-list build) and parked Main in
    // runBlocking(Dispatchers.IO) inside DurableChapterStatusResolver for
    // 5-9s on a large translated chapter. The chain is now fully suspend.
    internal suspend fun persistedChapterStatus(
        chapterId: Long?,
        chapterName: String,
        chapterScanlator: String?,
        mangaTitle: String,
        sourceId: Long,
    ): Translation.State? =
        durableStatusResolver.persistedChapterStatus(chapterId, chapterName, chapterScanlator, mangaTitle, sourceId)

    internal suspend fun reconstructDurableTerminalSnapshot(
        chapterId: Long,
        resolveTranslation: suspend (Long) -> Translation? = { resolveTranslationFromChapterId(it) },
    ): TranslationProgressSnapshot? = reconstructDurableTerminalSnapshotImpl(chapterId, resolveTranslation)

    fun getChapterTranslation(
        chapterName: String,
        scanlator: String?,
        title: String,
        source: Source,
    ): Map<String, PageTranslationView> = getChapterTranslationImpl(chapterName, scanlator, title, source)

    suspend fun getChapterTranslationForReader(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
    ): Map<String, PageTranslationView> = getChapterTranslationForReaderImpl(chapterId, chapterName, scanlator, mangaTitle, source)

    internal suspend fun openExistingChapterTranslationStore(
        chapterId: Long?,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
        prefoundDocument: TranslationDocument? = null,
        preflightManifestProbe: ArtifactManifestProbe? = null,
    ): ChapterTranslationStore? = if (chapterId != null) {
        withChapterStoreAdmissionLock(chapterId) {
            openExistingChapterTranslationStoreLocked(
                chapterId,
                chapterName,
                scanlator,
                mangaTitle,
                source,
                prefoundDocument,
                preflightManifestProbe,
            )
        }
    } else {
        openExistingChapterTranslationStoreLocked(
            chapterId,
            chapterName,
            scanlator,
            mangaTitle,
            source,
            prefoundDocument,
            preflightManifestProbe,
        )
    }

    private suspend fun openExistingChapterTranslationStoreLocked(
        chapterId: Long?,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
        prefoundDocument: TranslationDocument? = null,
        preflightManifestProbe: ArtifactManifestProbe? = null,
    ): ChapterTranslationStore? = openExistingChapterTranslationStoreLockedImpl(chapterId, chapterName, scanlator, mangaTitle, source, prefoundDocument, preflightManifestProbe)

    // Document lookup uses the same cache invalidation protocol as durable status resolution.
    internal fun findTranslationDocument(
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
    ): TranslationDocument? =
        durableStatusResolver.findTranslationDocument(chapterName, scanlator, mangaTitle, source)
    fun hasTranslationStore(chapter: Chapter, manga: Manga, source: Source): Boolean = hasTranslationStoreImpl(chapter, manga, source)

    /**
     * Typed result of the completed-download re-key handoff. [Performed] with
     * [Performed.skippedCollisions] > 0 is partial truth — never a full success.
     * [Deferred] means the mapping was retained for a later joined-release retry.
     */
    sealed class DownloadRekeyOutcome {
        data class Performed(val moved: Int, val skippedCollisions: Int) : DownloadRekeyOutcome()

        data object Noop : DownloadRekeyOutcome()

        /** No prior translation artifact records exist; continue to first batch admission. */
        data object NoTranslationRecords : DownloadRekeyOutcome()

        data class Deferred(
            val reason: String,
            val waitingForRegisteredStoreRelease: Boolean = false,
            val requestPreserved: Boolean = false,
        ) : DownloadRekeyOutcome()

        data class Rejected(val reason: String, val cause: Throwable? = null) : DownloadRekeyOutcome()
    }

    /**
     * Immutable completed-download mapping retained per chapter while a live
     * reader or in-flight batch defers the re-key. Latest validated mapping
     * wins; it is consumed only by a successful/no-op reconcile after an
     * actual joined reader leave (or clean pre-admission reconcile). In-memory
     * only: process death loses the pending mapping — the stale online-key
     * records remain typed diagnostics, not durable corruption.
     */
    internal data class PendingDownloadRekey(
        val chapter: Chapter,
        val manga: Manga,
        val source: Source,
        val onlineKeyByPageIndex: List<String>,
        val onDiskKeyByPageIndex: List<String>,
        val revision: Long,
        val requestGeneration: Long?,
    )

    internal val pendingDownloadRekeys = ConcurrentHashMap<Long, PendingDownloadRekey>()
    internal val pendingDownloadRekeyRevision = AtomicLong()

    /** Re-key reconciliation attempted and mapped to the handoff outcome space. */
    @VisibleForTesting
    internal fun hasPendingDownloadRekey(chapterId: Long): Boolean =
        pendingDownloadRekeys.containsKey(chapterId)

    /**
     * Attempts the retained-mapping reconcile for one chapter. Returns true
     * only when the chapter is clean for batch admission: no retained mapping,
     * or the reconcile completed with a full success or an honest no-op.
     * Deferred/Rejected retains the mapping so a later joined release retries.
     */
    @VisibleForTesting
    internal suspend fun reconcilePendingDownloadRekeyForAdmission(chapterId: Long): Boolean = reconcilePendingDownloadRekeyForAdmissionImpl(chapterId)

    internal suspend fun retryPendingDownloadRekeysAfterReaderLeave() = retryPendingDownloadRekeysAfterReaderLeaveImpl()

    suspend fun rekeyTranslationForCompletedDownload(
        chapter: Chapter,
        manga: Manga,
        source: Source,
        onlineKeyByPageIndex: List<String>,
        onDiskKeyByPageIndex: List<String>,
    ): DownloadRekeyOutcome = rekeyTranslationForCompletedDownloadImpl(chapter, manga, source, onlineKeyByPageIndex, onDiskKeyByPageIndex)

    internal fun stashPendingDownloadRekey(chapterId: Long, pending: PendingDownloadRekey) = stashPendingDownloadRekeyImpl(chapterId, pending)

    internal fun consumeReconciledMapping(chapterId: Long, pending: PendingDownloadRekey): Boolean = consumeReconciledMappingImpl(chapterId, pending)

    internal fun DownloadRekeyOutcome.isFullyReconciled(): Boolean =
        this is DownloadRekeyOutcome.Noop ||
            this is DownloadRekeyOutcome.NoTranslationRecords ||
            (this is DownloadRekeyOutcome.Performed && skippedCollisions == 0)

    internal suspend fun attemptDownloadRekey(pending: PendingDownloadRekey): DownloadRekeyOutcome = attemptDownloadRekeyImpl(pending)

    internal fun deferForRegisteredReaderStore(
        pending: PendingDownloadRekey,
        chapterId: Long,
        reason: String,
    ): DownloadRekeyOutcome.Deferred = deferForRegisteredReaderStoreImpl(pending, chapterId, reason)

    suspend fun unregisterActiveTranslationStore(chapterId: Long) = unregisterActiveTranslationStoreImpl(chapterId)

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
    ): ChapterTranslationStore? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        withChapterStoreAdmissionLock(chapterId) {
            openOrCreateActiveChapterTranslationStoreImpl(
                chapterId,
                chapterName,
                scanlator,
                mangaTitle,
                source,
                mangaId,
            )
        }
    }

    private suspend fun openOrCreateActiveChapterTranslationStoreImpl(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
        mangaId: Long?,
    ): ChapterTranslationStore? = openOrCreateActiveChapterTranslationStoreImplImpl(chapterId, chapterName, scanlator, mangaTitle, source, mangaId)

    // Cleaned-image lookup and retirement are owned by the lifecycle collaborator.
    private val cleanedImageLifecycle: CleanedImageLifecycleController by lazy { createCleanedImageLifecycle() }

    internal fun scheduleRetiredCleanedImageCleanup(
        store: ChapterTranslationStore,
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
        mangaId: Long?,
    ) = cleanedImageLifecycle.scheduleRetiredCleanedImageCleanup(
        store,
        chapterId,
        chapterName,
        scanlator,
        mangaTitle,
        source,
        mangaId,
    )

    internal fun sweepOrphanedCleanedImages(
        store: ChapterTranslationStore,
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
        mangaId: Long,
    ) = cleanedImageLifecycle.sweepOrphanedCleanedImages(
        store,
        chapterId,
        chapterName,
        scanlator,
        mangaTitle,
        source,
        mangaId,
    )

    internal fun retireChapterCompanionImages(
        manga: Manga,
        chapter: Chapter,
        source: Source,
    ) = cleanedImageLifecycle.retireChapterCompanionImages(manga, chapter, source)

    internal fun retirePageCompanionImage(
        manga: Manga,
        chapter: Chapter,
        source: Source,
        pageKey: String,
        imageName: String,
        isReferencedElsewhere: () -> Boolean,
    ) = cleanedImageLifecycle.retirePageCompanionImage(
        manga,
        chapter,
        source,
        pageKey,
        imageName,
        isReferencedElsewhere,
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
            downloadedPageStreamFactory(manga, chapter, source, pageKey)
        }

    /** Reader-facing projection with the committed display pointer applied. */
    fun observeActiveDisplayStore(chapterId: Long): StateFlow<Map<String, PageTranslationView>>? =
        activeStores.get(chapterId)?.display

    /**
     * Chapter-keyed active page source for reader state. Unlike a global active-store stream,
     * this never emits another chapter's pages and becomes empty when this chapter is removed.
     */
    fun selectActiveStore(chapterId: Long): Flow<Map<String, PageTranslationView>> = activeStores.select(chapterId)

    fun createBatchTracker(
        chapterId: Long,
        store: ChapterTranslationStore,
        orderedPageKeys: List<String>,
    ): TranslationBatchProgressTracker = batchTrackerRegistry.createTracker(
        chapterId = chapterId,
        store = store,
        orderedPageKeys = orderedPageKeys,
        scope = storeScope,
        permitHolderResolver = { processor.permitHolderPageKeySnapshot() },
    )

    fun disposeBatchTracker(chapterId: Long) = disposeBatchTrackerImpl(chapterId)

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

    fun cancelQueuedTranslation(translation: Translation) = cancelQueuedTranslationImpl(translation)

    internal fun removeFromTranslationQueue(chapter: Chapter) = removeFromTranslationQueueImpl(chapter)

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
        readerTeardown.cancelPageTranslation(chapterId, pageKey)

    suspend fun cancelPageTranslations(
        chapterId: Long,
        reason: String = "Translation cancelled",
    ) = readerTeardown.cancelPageTranslations(chapterId, reason)

    suspend fun cancelAllPageTranslations(
        cancelBatchQueue: Boolean = false,
        reason: String = "All translation cancelled",
    ) = readerTeardown.cancelAllPageTranslations(cancelBatchQueue, reason)

    suspend fun cancelAllPageTranslationsOffMain(
        cancelBatchQueue: Boolean = false,
        reason: String = "All translation cancelled",
    ) = readerTeardown.cancelAllPageTranslationsOffMain(cancelBatchQueue, reason)

    fun statusFlow(): Flow<Translation> = queueState
        .flatMapLatest { translations ->
            translations
                .map { translation ->
                    // Queue membership is the acknowledgement. Replay the
                    // current status for every subscriber instead of dropping
                    // QUEUE and relying on a later TRANSLATING transition.
                    translation.statusFlow
                        .drop(1)
                        .map { translation }
                        .onStart { emit(translation) }
                }
                .merge()
        }
}
