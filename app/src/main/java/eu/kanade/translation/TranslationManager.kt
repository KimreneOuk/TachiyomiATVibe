package eu.kanade.translation

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.translation.TranslationForegroundService
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.artifact.ChapterDocumentIo
import eu.kanade.translation.artifact.ManifestAuthority
import eu.kanade.translation.batch.TranslationBatchProgressTracker
import eu.kanade.translation.batch.TranslationBatchTrackerRegistry
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.legacy.LegacyFlatFileDecoder
import eu.kanade.translation.model.ChapterQueuePreflight
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageView
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.manager.BatchProgressProjector
import eu.kanade.translation.manager.ChapterDataResetController
import eu.kanade.translation.manager.CleanedImageLifecycleController
import eu.kanade.translation.manager.DurableChapterKey
import eu.kanade.translation.manager.DurableChapterStatusResolver
import eu.kanade.translation.manager.DurableStatus
import eu.kanade.translation.manager.ReaderTeardownCoordinator
import eu.kanade.translation.manager.TranslationRequestCoordinator
import eu.kanade.translation.manager.TranslationDocument
import eu.kanade.translation.model.findRunningSameSourceConflict
import eu.kanade.translation.model.staleQueuedChaptersToEvict
import eu.kanade.translation.model.toQueuedChapterView
import eu.kanade.translation.scheduling.TranslationStreamRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// T909 Phase 16: the orphan-sweep constants and the freshness predicate moved
// to manager/CleanedImageLifecycleController.kt; this stub keeps the old
// qualified name (TranslationManagerArtifactReadTest).
internal fun isFreshOrphanedCleanedImage(lastModified: Long, nowEpochMs: Long): Boolean =
    eu.kanade.translation.manager.isFreshOrphanedCleanedImage(lastModified, nowEpochMs)

// T909 Phase 9: body moved to manager/TranslationRequestCoordinator.kt; this
// stub keeps the old qualified name (TranslationManagerPendingAcknowledgementTest).
internal fun acknowledgePendingTranslationState(
    current: Map<Long, TranslationRequestState>,
    chapterIds: Iterable<Long>,
): Map<Long, TranslationRequestState> =
    eu.kanade.translation.manager.acknowledgePendingTranslationState(current, chapterIds)

class TranslationManager(
    private val context: Context,
    private val provider: TranslationProvider = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
) {
    private val pipeline = TranslationPipeline(context, provider)
    private val translator = ChapterTranslator(context, provider, pipeline = pipeline)

    // Held here (DI singleton) so deleteTranslation can evict stale reader page-stream closures pointing at the deleted rendered/cleaned PNGs.
    private val streamRegistry: TranslationStreamRegistry = Injekt.get()

    /**
     * Application-lifetime scope for one-off init work (queue rehydration). SupervisorJob so a
     * failure in restoreQueue does not cancel unrelated work; IO dispatcher because restoreQueue does DB reads.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serializes reader lifecycle teardown so pause/finish cannot race store eviction. */
    private val readerTeardownMutex = Mutex()

    private val pendingRequestStore = TranslationPendingRequestStore(context)
    private val pendingTranslationRequestsState = MutableStateFlow(loadPendingTranslationRequests())

    /**
     * Versions fence the asynchronous STARTING commit from a later download,
     * preparation, or cancellation update. The version is advanced before a
     * synchronous normal mutation writes its new durable state.
     */
    private val pendingRequestWriteVersions = ConcurrentHashMap<Long, AtomicLong>()

    /** Immediate, lifecycle-independent acknowledgement for pre-translation requests. */
    val pendingTranslationRequests: StateFlow<Map<Long, TranslationRequestState>> =
        pendingTranslationRequestsState.asStateFlow()

    /** Serializes request versioning, state publication, and durable writes. */
    private val pendingRequestMutationLock = Any()

    // T909 Phase 9: pending-request subsystem bodies moved to
    // manager/TranslationRequestCoordinator.kt. The state objects stay here —
    // the pending tests reflection-write these exact fields — so the
    // coordinator is built per access from the current field values.
    private val requestCoordinator: TranslationRequestCoordinator
        get() = TranslationRequestCoordinator(
            pendingRequestStoreProvider = { pendingRequestStore },
            pendingTranslationRequestsStateProvider = { pendingTranslationRequestsState },
            pendingRequestWriteVersionsProvider = { pendingRequestWriteVersions },
            pendingRequestMutationLockProvider = { pendingRequestMutationLock },
            storeScopeProvider = { storeScope },
            queueStateProvider = { queueState },
            translatorProvider = { translator },
            getQueuedTranslationOrNull = { chapterId -> getQueuedTranslationOrNull(chapterId) },
            translateChapter = { manga, chapter -> translateChapter(manga, chapter) },
        )

    // T909 Phase 13: DurableChapterKey/DurableStatus/TranslationDocument and the
    // durable-status resolution region moved to manager/DurableChapterStatusResolver.kt.
    // The cache field below stays — the durable tests reflection-write this exact
    // field — and the resolver is built per access from the current field values.

    private val durableStatusCache = ConcurrentHashMap<DurableChapterKey, DurableStatus>()

    /**
     * Owns single-page + auto-prefetch job scheduling, dedup, and cancellation. This manager
     * keeps the store lifecycle (open/evict/observe), chapter queue, and translation-file I/O.
     * The scheduler resolves the per-chapter store back through this manager via [storeResolver]
     * — store instances are shared between reader and translator, so they must not be owned by the scheduler.
     */
    val scheduler = eu.kanade.translation.scheduling.TranslationScheduler(
        executor = pipeline,
        storeResolver = eu.kanade.translation.scheduling.TranslationStoreResolver { chapterId ->
            activeStores.get(chapterId)
        },
        immediateStoreResolver = { chapterId -> activeStores.get(chapterId) },
    )

    init {
        // Share the store instance between reader and translator so live updates do not need a chapter reload.
        pipeline.activeStoreResolver = { translation ->
            openOrCreateActiveChapterTranslationStore(
                translation.chapter.id!!,
                translation.chapter.name,
                translation.chapter.scanlator,
                translation.manga.title,
                translation.source,
                translation.manga.id,
            )
        }
        pipeline.onBatchClosed = { manga, chapter, source, store ->
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
        }
        // NOTE: activeStoreUnregister is intentionally NOT wired. Evicting after
        // each single-page translation broke live updates (reader captured the
        // StateFlow once; next translate got a fresh unobserved store). Eviction
        // now happens only on chapter change / reader exit (cancelPageTranslations /
        // cancelAllPageTranslations callers).

        // Native quarantine reports timeout only after the underlying call exits;
        // evict the stale job so a subsequent request can be admitted safely.
        pipeline.onPageStuck = { chapterId, pageKey ->
            if (chapterId != null && pageKey.isNotEmpty()) {
                scheduler.markPageJobStuck(chapterId, pageKey)
            }
        }

        // Batch tracker factory: lets the pipeline create and register a tracker per active batch (observable via observeBatchProgress).
        pipeline.batchTrackerFactory = { chapterId, store, orderedPageKeys ->
            createBatchTracker(chapterId, store, orderedPageKeys)
        }

        // Rehydrate persisted batch queue on IO so a crash mid-batch no longer loses it.
        // Entries get status QUEUE; user taps Start to resume — never auto-starts OCR/LLM on launch.
        applicationScope.launch {
            translator.queueState.collect { durableStatusResolver.clearDurableStatusCache() }
        }
        applicationScope.launch {
            // A paused chapter is not an active foreground job, but its durable
            // outcome still deserves a visible notification after process
            // restart. The service owns only the active QUEUE/TRANSLATING
            // predicate; this collector owns the detached paused projection.
            statusFlow().collect { translation ->
                if (translation.status == Translation.State.PAUSED && !isAnyBatchTranslationActive) {
                    val snapshot = translation.chapter.id?.let { chapterId ->
                        getTranslationProgress(chapterId)?.firstOrNull()
                    }
                    TranslationForegroundService.showPaused(
                        context = context,
                        chapterName = translation.chapter.name,
                        chapterId = translation.chapter.id,
                        snapshot = snapshot,
                    )
                }
            }
        }
        applicationScope.launch { translator.restoreQueue() }
    }

    /**
     * Evicts a single-page translation job whose worker is stuck in uncancellable native
     * code past its deadline. Delegates to the scheduler, which owns the [activePageJobs] map.
     */
    fun markPageJobStuck(chapterId: Long, pageKey: String) {
        scheduler.markPageJobStuck(chapterId, pageKey)
    }

    private val activeStores = ActiveChapterStoreRegistry()
    private val batchTrackerRegistry = TranslationBatchTrackerRegistry()
    // T909 Phase 3a: the single legacy Json config lives in LegacyFlatFileDecoder;
    // this field stays for the reflective test seam.
    private val legacyPageJson = LegacyFlatFileDecoder.legacyPageJson

    /** Owns tracker reducer jobs; reader flows observe the selected store directly. */
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val isRunning: Boolean
        get() = translator.isRunning

    val queueState
        get() = translator.queueState

    val isAnyBatchTranslationActive: Boolean
        get() = queueState.value.any { it.status == Translation.State.QUEUE || it.status == Translation.State.TRANSLATING }

    // T909 Phase 9: bodies moved to manager/TranslationRequestCoordinator.kt;
    // same-signature stubs keep the manager's public (and reflection-tested) seams.

    fun queueTranslationAfterDownload(manga: Manga, chapter: Chapter) {
        requestCoordinator.queueTranslationAfterDownload(manga, chapter)
    }

    fun acknowledgeTranslationRequests(chapters: List<Chapter>) {
        requestCoordinator.acknowledgeTranslationRequests(chapters)
    }

    fun markTranslationRequestPreparing(chapterId: Long) {
        requestCoordinator.markTranslationRequestPreparing(chapterId)
    }

    fun markTranslationDownloadFailed(chapterId: Long, reason: String? = null) {
        requestCoordinator.markTranslationDownloadFailed(chapterId, reason)
    }

    fun cancelTranslationRequest(chapterId: Long): Boolean =
        requestCoordinator.cancelTranslationRequest(chapterId)

    fun clearStaleDownloadFailedRequest(chapterId: Long) {
        requestCoordinator.clearStaleDownloadFailedRequest(chapterId)
    }

    fun hasPendingTranslationRequest(chapterId: Long): Boolean =
        requestCoordinator.hasPendingTranslationRequest(chapterId)

    fun isChapterTranslationProtected(chapterId: Long): Boolean =
        requestCoordinator.isChapterTranslationProtected(chapterId)

    fun protectedChapterIds(): Set<Long> = requestCoordinator.protectedChapterIds()

    private fun setPendingTranslationRequest(
        chapterId: Long,
        phase: TranslationRequestPhase,
        reason: String? = null,
    ) {
        requestCoordinator.setPendingTranslationRequest(chapterId, phase, reason)
    }

    private fun clearPendingTranslationRequest(chapterId: Long) {
        requestCoordinator.clearPendingTranslationRequest(chapterId)
    }

    private fun clearAllPendingTranslationRequests() {
        requestCoordinator.clearAllPendingTranslationRequests()
    }

    private fun loadPendingTranslationRequests(): Map<Long, TranslationRequestState> =
        pendingRequestStore.load().associateWith { chapterId ->
            TranslationRequestState(
                chapterId = chapterId,
                phase = pendingRequestStore.phase(chapterId) ?: TranslationRequestPhase.WAITING_FOR_DOWNLOAD,
                reason = pendingRequestStore.reason(chapterId),
            )
        }

    suspend fun startTranslationAfterDownloadIfRequested(manga: Manga, chapter: Chapter) {
        requestCoordinator.startTranslationAfterDownloadIfRequested(manga, chapter)
    }

    // T909 Phase 18: reader/page teardown bodies moved to
    // manager/ReaderTeardownCoordinator.kt. The readerTeardownMutex field
    // above stays — TranslationManagerReaderTeardownTest reflection-writes it —
    // so the coordinator is built per access from the current field values and
    // resolves the mutex through a provider (a swapped mutex still serializes
    // both stop paths). Same-signature stubs keep the public seams.
    private val readerTeardown: ReaderTeardownCoordinator
        get() = ReaderTeardownCoordinator(
            applicationScopeProvider = { applicationScope },
            readerTeardownMutexProvider = { readerTeardownMutex },
            schedulerProvider = { scheduler },
            activeStoresProvider = { activeStores },
            translatorProvider = { translator },
            isAnyBatchTranslationActiveProvider = { isAnyBatchTranslationActive },
            isBatchTranslationRetainedFn = { chapterId -> isBatchTranslationRetained(chapterId) },
            unregisterActiveTranslationStoreFn = { chapterId -> unregisterActiveTranslationStore(chapterId) },
            disposeBatchTrackerFn = { chapterId -> disposeBatchTracker(chapterId) },
            clearAllPendingTranslationRequestsFn = { clearAllPendingTranslationRequests() },
        )

    fun stopReaderTranslations(reason: String) = readerTeardown.stopReaderTranslations(reason)

    fun requestReaderStop(reason: String): Deferred<Unit> = readerTeardown.requestReaderStop(reason)

    suspend fun awaitReaderStop(reason: String) = readerTeardown.awaitReaderStop(reason)

    fun isTranslating(): Boolean = queueState.value.any {
        it.status == Translation.State.QUEUE || it.status == Translation.State.TRANSLATING
    }

    fun isPageActive(chapterId: Long, pageKey: String): Boolean {
        val store = activeStores.get(chapterId) ?: return false
        val page = store.state.value[pageKey] ?: return false
        return page.ocrStatus == StageStatus.RUNNING ||
            page.translationStatus == StageStatus.RUNNING ||
            page.inpaintStatus == StageStatus.RUNNING ||
            page.renderStatus == StageStatus.RUNNING
    }

    /** Canonical batch projection used by every UI surface and the notification. */
    fun getTranslationProgress(chapterId: Long): Flow<TranslationProgressSnapshot> =
        observeBatchProgress(chapterId)

    fun isTranslationActive(chapterId: Long): Boolean {
        return isBatchTranslationActive(chapterId)
    }

    fun translatorStop(reason: String? = null, closeEngines: Boolean = false) = translator.stop(reason, closeEngines)

    fun onMemoryPressure(level: Int) {
        val pressureClass = MemoryPressurePolicy.classify(level)
        translator.onMemoryPressure(level, pressureClass)
    }

    fun startTranslation() {
        if (!translator.isRunning) {
            translator.start()
        }
        if (isAnyBatchTranslationActive) {
            TranslationForegroundService.start(context)
        }
    }

    fun pauseTranslation() {
        translator.pause()
    }

    /** Re-admits retryable paused work without disturbing another active chapter. */
    suspend fun requeueTranslation(chapterId: Long, force: Boolean = false): Boolean =
        translator.requeueExisting(chapterId, force)

    fun clearQueue() {
        translator.clearQueue()
        translator.stop()
        clearAllPendingTranslationRequests()
    }

    fun getQueuedTranslationOrNull(chapterId: Long): Translation? {
        return queueState.value.find { it.chapter.id == chapterId }
    }

    fun isBatchTranslationActive(chapterId: Long): Boolean {
        return queueState.value.any { translation ->
            translation.chapter.id == chapterId &&
                (translation.status == Translation.State.QUEUE || translation.status == Translation.State.TRANSLATING)
        }
    }

    /**
     * Whether a chapter still owns batch artifacts across reader lifecycle
     * transitions. PAUSED is intentionally included here even though it is
     * excluded from the foreground-service active predicate.
     */
    fun isBatchTranslationRetained(chapterId: Long): Boolean = queueState.value.any { translation ->
        translation.chapter.id == chapterId &&
            (
                translation.status == Translation.State.QUEUE ||
                    translation.status == Translation.State.TRANSLATING ||
                    translation.status == Translation.State.PAUSED
                )
    }

    fun translateChapter(manga: Manga, chapters: Chapter) {
        val chapterId = chapters.id ?: return
        scheduler.shutdownAutoCoordinator(chapterId)
        evictStaleQueuedChapters(chapterId, manga.source)
        markTranslationRequestPreparing(chapterId)
        translator.queueChapter(manga, chapters)
        if (queueState.value.any { it.chapter.id == chapterId }) {
            clearPendingTranslationRequest(chapterId)
        } else {
            markTranslationQueueFailureIfAcknowledged(chapterId)
        }
        startTranslation()
    }

    fun translateChapters(manga: Manga, chapters: List<Chapter>) {
        if (chapters.isEmpty()) return
        chapters.forEach { chapter ->
            val chapterId = chapter.id ?: return@forEach
            scheduler.shutdownAutoCoordinator(chapterId)
            markTranslationRequestPreparing(chapterId)
            translator.queueChapter(manga, chapter)
            if (queueState.value.any { it.chapter.id == chapterId }) {
                clearPendingTranslationRequest(chapterId)
            } else {
                markTranslationQueueFailureIfAcknowledged(chapterId)
            }
        }
        startTranslation()
    }

    private fun markTranslationQueueFailureIfAcknowledged(chapterId: Long) {
        if (pendingTranslationRequestsState.value.containsKey(chapterId)) {
            setPendingTranslationRequest(
                chapterId,
                TranslationRequestPhase.DOWNLOAD_FAILED,
                "Translation could not be queued",
            )
        }
    }

    /**
     * TachiyomiAT bug 3 fix: preflight check for an explicit Start Batch action.
     * Returns the running conflict (if any) so the UI can ask the user before
     * cancelling in-flight work on a different chapter of the same source.
     *
     * Stale QUEUE entries are NOT reported here; they are evicted automatically
     * by [translateChapter] since dropping a not-yet-started queue entry never
     * loses accepted artifacts. Only an actively TRANSLATING chapter needs user
     * confirmation because cancelling it mid-OCR/inpaint discards the in-flight
     * page's native work.
     */
    fun translateChapterPreflight(manga: Manga, chapter: Chapter): ChapterQueuePreflight {
        val chapterId = chapter.id
            ?: return ChapterQueuePreflight.NoConflict
        val view = queueState.value.map { it.toQueuedChapterView() }
        val conflict = findRunningSameSourceConflict(view, chapterId, manga.source)
            ?: return ChapterQueuePreflight.NoConflict
        return ChapterQueuePreflight.RunningConflict(
            chapterId = conflict.chapterId,
            chapterName = conflict.chapterName,
        )
    }

    /**
     * Evicts every queued (status == QUEUE) chapter of [sourceId] other than
     * [keepChapterId] from the batch queue. Preserves accepted artifacts:
     * [removeFromTranslationQueue] only drops the queue entry; the chapter's
     * ChapterTranslationStore and its persisted OCR/inpaint/translation data
     * stay intact, so a later Start Batch on that chapter resumes via the
     * BatchResumeGateDecider's artifact scan without redoing completed work.
     */
    private fun evictStaleQueuedChapters(keepChapterId: Long, sourceId: Long) {
        val staleIds = staleQueuedChaptersToEvict(
            queueState.value.map { it.toQueuedChapterView() },
            keepChapterId,
            sourceId,
        ).map { it.chapterId }.toSet()
        if (staleIds.isEmpty()) return
        val stale = queueState.value
            .filter { it.chapter.id != null && it.chapter.id in staleIds }
            .map { it.chapter }
        stale.forEach { chapter ->
            logcat(LogPriority.INFO) {
                "TachiyomiAT evicting stale queued chapter ${chapter.id} (source=$sourceId) in favor of $keepChapterId; artifacts preserved"
            }
            removeFromTranslationQueue(chapter)
        }
    }

    /**
     * TachiyomiAT bug 3 fix: cancels an actively running translation of
     * [chapterId] (same source) so a subsequent [translateChapter] can start on
     * a different chapter. Used after the UI confirms a [ChapterQueuePreflight.RunningConflict].
     * Cancels in-flight page jobs, durably clears transient queue pages
     * (preserving rendered/terminal artifacts), and drops the queue entry.
     */
    fun cancelRunningChapterForReplace(chapterId: Long) {
        val chapter = queueState.value
            .firstOrNull { it.chapter.id == chapterId }
            ?.chapter
            ?: return
        kotlinx.coroutines.runBlocking {
            scheduler.cancelPageTranslations(chapterId)
        }
        activeStores.get(chapterId)?.let { store ->
            kotlinx.coroutines.runBlocking {
                store.clearTransientQueuePages("Replaced by another chapter's batch")
            }
        }
        removeFromTranslationQueue(chapter)
    }

    // T909 Phase 11: status/progress projection flow graph moved to
    // manager/BatchProgressProjector.kt (flows only, no locks). Same-signature
    // stubs keep the manager's public seams.
    private val progressProjector: BatchProgressProjector
        get() = BatchProgressProjector(
            activeStoresProvider = { activeStores },
            batchTrackerRegistryProvider = { batchTrackerRegistry },
            queueStateProvider = { queueState },
            pendingTranslationRequestsProvider = { pendingTranslationRequests },
            pipelineProvider = { pipeline },
            getQueuedTranslationOrNull = { chapterId -> getQueuedTranslationOrNull(chapterId) },
            persistedChapterStatus = { chapterId, chapterName, chapterScanlator, mangaTitle, sourceId ->
                persistedChapterStatus(chapterId, chapterName, chapterScanlator, mangaTitle, sourceId)
            },
            openOrCreateStoreSuspend = { chapterId, chapterName, scanlator, mangaTitle, source, mangaId ->
                openOrCreateActiveChapterTranslationStoreSuspend(
                    chapterId,
                    chapterName,
                    scanlator,
                    mangaTitle,
                    source,
                    mangaId,
                )
            },
            observeActiveDisplayStore = { chapterId -> observeActiveDisplayStore(chapterId) },
        )

    fun getChapterTranslationStatus(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        title: String,
        sourceId: Long,
    ): Translation.State = progressProjector.getChapterTranslationStatus(
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
    ): Flow<Translation.State> = progressProjector.observeChapterTranslationStatus(
        chapterId,
        chapterName,
        scanlator,
        title,
        sourceId,
    )

    /** True when persisted output is readable, including a retry/review-ready warning outcome. */
    fun isChapterTranslated(
        chapterName: String,
        chapterScanlator: String?,
        mangaTitle: String,
        sourceId: Long,
    ): Boolean = persistedChapterStatus(null, chapterName, chapterScanlator, mangaTitle, sourceId)
        .let { it == Translation.State.TRANSLATED || it == Translation.State.READY_WITH_WARNINGS }

    // T909 Phase 13: durable-status resolution region moved to
    // manager/DurableChapterStatusResolver.kt (cache read/write, probe,
    // document lookup, probe-store adoption). The durableStatusCache field
    // above stays — the durable tests reflection-write this exact field — so
    // the resolver is built per access from the current field values, and all
    // cache invalidations route through it.
    private val durableStatusResolver: DurableChapterStatusResolver
        get() = DurableChapterStatusResolver(
            providerProvider = { provider },
            sourceManagerProvider = { sourceManager },
            activeStoresProvider = { activeStores },
            durableStatusCacheProvider = { durableStatusCache },
        )

    private fun persistedChapterStatus(
        chapterId: Long?,
        chapterName: String,
        chapterScanlator: String?,
        mangaTitle: String,
        sourceId: Long,
    ): Translation.State? =
        durableStatusResolver.persistedChapterStatus(chapterId, chapterName, chapterScanlator, mangaTitle, sourceId)

    private fun statusFromReadablePages(
        pages: Map<String, PageTranslation>,
    ): Translation.State? = LegacyFlatFileDecoder.statusFromReadablePages(pages)

    fun getChapterTranslation(
        chapterName: String,
        scanlator: String?,
        title: String,
        source: Source,
    ): Map<String, PageTranslation> {
        try {
            val file = provider.findTranslationFile(
                chapterName,
                scanlator,
                title,
                source,
            ) ?: return emptyMap()
            return getChapterTranslation(file)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) {
                "TachiyomiAT failed to read chapter translation for $chapterName"
            }
        }
        return emptyMap()
    }

    suspend fun getChapterTranslationForReader(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
    ): Map<String, PageTranslation> = withContext(Dispatchers.IO) {
        activeStores.get(chapterId)?.state?.value?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        val document = findTranslationDocument(chapterName, scanlator, mangaTitle, source)
            ?: return@withContext emptyMap()
        val manifestProbe = ChapterTranslationStore.probeArtifactManifest(document.parent, document.fileName)
        if (manifestProbe.exists && manifestProbe.manifest?.authority != ManifestAuthority.LEGACY) {
            return@withContext openExistingChapterTranslationStore(
                chapterId,
                chapterName,
                scanlator,
                mangaTitle,
                source,
            )?.state?.value.orEmpty()
        }
        return@withContext document.file?.let { decodeLegacyChapterTranslation(it, quarantineOnFailure = true) }.orEmpty()
    }

    fun getChapterTranslation(
        file: UniFile,
    ): Map<String, PageTranslation> = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val manifestProbe = ChapterTranslationStore.probeArtifactManifest(file)
        if (manifestProbe.exists && manifestProbe.manifest?.authority != ManifestAuthority.LEGACY) {
            val store = activeStores.getOrCreateFile(file.registryKey()) {
                ChapterTranslationStore.open(file)
            }
            // Opening an artifact store may complete a LEGACY rescue and
            // advance its preservation marker. Do not retain a status observed
            // before that durable transition.
            durableStatusResolver.clearDurableStatusCache()
            return@runBlocking store?.state?.value.orEmpty()
        }
        return@runBlocking decodeLegacyChapterTranslation(file, quarantineOnFailure = true)
    }

    private suspend fun openExistingChapterTranslationStore(
        chapterId: Long?,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
    ): ChapterTranslationStore? {
        val document = findTranslationDocument(chapterName, scanlator, mangaTitle, source) ?: return null
        val manifestProbe = ChapterTranslationStore.probeArtifactManifest(document.parent, document.fileName)
        if (document.file?.exists() != true && !manifestProbe.exists) return null
        val store = if (chapterId != null) {
            activeStores.getOrCreate(chapterId, document.registryKey) {
                if (document.file?.exists() == true) {
                    ChapterTranslationStore.open(document.file)
                } else {
                    ChapterTranslationStore.openArtifact(document.parent, document.fileName)
                }
            }
        } else {
            activeStores.getOrCreateFile(document.registryKey) {
                if (document.file?.exists() == true) {
                    ChapterTranslationStore.open(document.file)
                } else {
                    ChapterTranslationStore.openArtifact(document.parent, document.fileName)
                }
            }
        }
        durableStatusResolver.clearDurableStatusCache()
        return store
    }

    // T909 Phase 13: body moved to manager/DurableChapterStatusResolver.kt.
    // Same-signature stub keeps the call sites.
    private fun findTranslationDocument(
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
    ): TranslationDocument? =
        durableStatusResolver.findTranslationDocument(chapterName, scanlator, mangaTitle, source)

    // T909 Phase 3a: legacy decode/quarantine bodies moved to legacy/LegacyFlatFileDecoder.kt.
    private fun decodeLegacyChapterTranslation(
        file: UniFile,
        quarantineOnFailure: Boolean,
    ): Map<String, PageTranslation> =
        LegacyFlatFileDecoder.decodeLegacyChapterTranslation(file, quarantineOnFailure)

    private fun quarantineCorruptTranslationFile(file: UniFile, error: Throwable) {
        LegacyFlatFileDecoder.quarantineCorruptTranslationFile(file, error)
    }

    internal fun quarantineCorruptDocument(io: ChapterDocumentIo, name: String): String? =
        LegacyFlatFileDecoder.quarantineCorruptDocument(io, name)

    private fun UniFile.registryKey(): String = filePath ?: uri.toString()

    /** Returns whether this chapter has an existing or active translation store. */
    fun hasTranslationStore(chapter: Chapter, manga: Manga, source: Source): Boolean {
        chapter.id?.let { activeStores.get(it) }?.let { return it.state.value.isNotEmpty() }
        return provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source)
            ?.exists() == true
    }

    /** Re-keys source URL pages to the names written by a completed download. */
    suspend fun rekeyTranslationForCompletedDownload(
        chapter: Chapter,
        manga: Manga,
        source: Source,
        onlineKeyByPageIndex: List<String>,
        onDiskKeyByPageIndex: List<String>,
    ) {
        val chapterId = chapter.id ?: return
        if (onlineKeyByPageIndex.size != onDiskKeyByPageIndex.size) return

        val activeStore = activeStores.get(chapterId)
        val store = activeStore ?: run {
            val file = provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source)
                ?.takeIf { it.exists() }
                ?: return
            activeStores.getOrCreate(chapterId, file.registryKey()) {
                ChapterTranslationStore.open(file)
            } ?: return
        }
        val pages = store.state.value
        if (pages.size != onlineKeyByPageIndex.size) return
        if (pages.keys.none { it in onlineKeyByPageIndex }) return
        if (pages.keys.all { it in onDiskKeyByPageIndex }) return

        // Match deleteTranslation's cancellation ordering while retaining the
        // active store instance so an open reader observes the new snapshot.
        // Joining here cannot deadlock with a running batch. This method is called from
        // the downloader's IO coroutine, making the bounded blocking bridge safe.
        kotlinx.coroutines.runBlocking { translator.cancelTranslatorJobAndJoin() }
        scheduler.cancelAutoTranslations(chapterId)
        scheduler.cancelPageTranslations(chapterId)
        store.beginGeneration("completed download re-key")
        val moves = store.rekeyPages(onlineKeyByPageIndex, onDiskKeyByPageIndex)
        if (moves.isEmpty()) return

        store.flush()
        val companionDir = provider.findCompanionImageDir(
            manga.title,
            source,
            chapter.name,
            chapter.scanlator,
        )
        moves.forEach { (oldKey, newKey) ->
            val oldImage = provider.companionImageNameForPage(oldKey)
            val newImage = provider.companionImageNameForPage(newKey)
            companionDir?.findFile(oldImage)?.renameTo(newImage)
        }
    }

    fun registerActiveTranslationStore(chapterId: Long, store: ChapterTranslationStore) {
        // Keep the existing instance if already registered so a reader keeps observing the same object.
        activeStores.register(chapterId, store)
        durableStatusResolver.clearDurableStatusCache()
    }

    fun unregisterActiveTranslationStore(chapterId: Long) {
        // Mark the evicted store defunct BEFORE removing it from the registry. A worker still
        // holding a reference has late writes rejected rather than recreating deleted output.
        activeStores.remove(chapterId)?.markDefunct()
        durableStatusResolver.clearDurableStatusCache()
    }

    /**
     * Returns the existing active [ChapterTranslationStore] for [chapterId], or
     * opens one from disk if it is not yet registered. If neither an in-memory
     * store nor an on-disk translation file exists, this creates a fresh store
     * tied to the expected translation path and registers it, so a translator
     * starting now and a reader observing now share the same instance.
     */
    fun openOrCreateActiveChapterTranslationStore(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
        mangaId: Long? = null,
    ): ChapterTranslationStore? = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        openOrCreateActiveChapterTranslationStoreImpl(
            chapterId,
            chapterName,
            scanlator,
            mangaTitle,
            source,
            mangaId,
        )
    }

    /**
     * Non-blocking variant of [openOrCreateActiveChapterTranslationStore] for
     * coroutine callers (reader loadChapter / per-page view subscription).
     * Opening a store for the first time performs legacy artifact migration
     * with SAF binder I/O; callers must never runBlocking on that path from
     * the main thread (reader-entry ANR) — they should suspend on IO instead.
     */
    suspend fun openOrCreateActiveChapterTranslationStoreSuspend(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
        mangaId: Long? = null,
    ): ChapterTranslationStore? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        openOrCreateActiveChapterTranslationStoreImpl(
            chapterId,
            chapterName,
            scanlator,
            mangaTitle,
            source,
            mangaId,
        )
    }

    /**
     * Shared open-or-create body. The registry monitor is held only for the
     * map operations themselves — never across store open / artifact
     * migration / SAF I/O. A concurrent open for the same chapter resolves
     * through the registry's keep-existing [registerActiveTranslationStore]
     * semantics: exactly one instance survives and both callers observe it.
     */
    private suspend fun openOrCreateActiveChapterTranslationStoreImpl(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
        mangaId: Long?,
    ): ChapterTranslationStore? {
        val document = findTranslationDocument(chapterName, scanlator, mangaTitle, source)
        val fileName = document?.fileName ?: provider.getTranslationFileName(chapterName, scanlator)
        val manifestProbe = document?.let {
            ChapterTranslationStore.probeArtifactManifest(it.parent, it.fileName)
        }
        val registered = activeStores.getOrCreate(
            chapterId,
            document?.registryKey,
        ) {
            val file = document?.file
            if (file?.exists() == true) {
                ChapterTranslationStore.open(file)
            } else if (manifestProbe?.exists == true) {
                ChapterTranslationStore.openArtifact(document.parent, fileName)
            } else {
                // Create a LAZY store: the artifact manifest materializes only on the first real
                // write, so merely opening a chapter never leaves an empty compatibility document
                // behind that could make isChapterTranslated report a false TRANSLATED state.
                ChapterTranslationStore.lazy(
                    artifactParent = document?.parent,
                    artifactFileName = fileName,
                )
            }
        } ?: return null
        scheduleRetiredCleanedImageCleanup(registered, chapterId, chapterName, scanlator, mangaTitle, source, mangaId)
        return registered
    }

    // T909 Phase 16: cleaned-image lifecycle region moved to
    // manager/CleanedImageLifecycleController.kt (retired-image drains, orphan
    // sweeps, companion-image retirement). Same-signature stubs keep the call
    // sites; the controller is built per access from the current field values.
    private val cleanedImageLifecycle: CleanedImageLifecycleController
        get() = CleanedImageLifecycleController(
            applicationScopeProvider = { applicationScope },
            streamRegistryProvider = { streamRegistry },
            providerProvider = { provider },
        )

    private fun scheduleRetiredCleanedImageCleanup(
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

    private fun sweepOrphanedCleanedImages(
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

    private fun retireChapterCompanionImages(
        manga: Manga,
        chapter: Chapter,
        source: Source,
    ) = cleanedImageLifecycle.retireChapterCompanionImages(manga, chapter, source)

    private fun retirePageCompanionImage(
        manga: Manga,
        chapter: Chapter,
        source: Source,
        pageKey: String,
        imageName: String,
    ) = cleanedImageLifecycle.retirePageCompanionImage(manga, chapter, source, pageKey, imageName)

    fun openTranslationSession(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
    ): TranslationSession? {
        val chapterId = chapter.id ?: return null
        // Rolling-auto owns the chapter from here; a stale DOWNLOAD_FAILED
        // batch request must not keep projecting its failed state.
        clearStaleDownloadFailedRequest(chapterId)
        val store = openOrCreateActiveChapterTranslationStore(
            chapterId,
            chapter.name,
            chapter.scanlator,
            manga.title,
            source,
            manga.id,
        ) ?: return null
        val key = "${source.id}:${manga.id}:$chapterId"
        return TranslationSession(key, manga, chapter, source, store)
    }

    fun requestAutoWindow(
        session: TranslationSession,
        requests: List<TranslationPageRequest>,
    ) = scheduler.requestAutoWindow(session, requests)

    fun cancelAutoTranslations(chapterId: Long? = null): Boolean =
        scheduler.cancelAutoTranslations(chapterId)

    /**
     * Ticket 03: live auto-translation snapshot from the rolling coordinator.
     * Null when no coordinator is active. The reader observes this to render
     * the compact ready-ahead status without polling the durable store.
     */
    val autoSnapshot: kotlinx.coroutines.flow.StateFlow<eu.kanade.translation.scheduling.AutoTranslationSnapshot?> =
        scheduler.autoSnapshot

    /** Ticket 03: rolling coordinator window update. The scheduler owns the coordinator. */
    fun updateAutoWindow(
        identity: eu.kanade.translation.scheduling.AutoChapterIdentity,
        visiblePageIndex: Int,
        configuredAheadTarget: Int,
        pageCount: Int,
        session: TranslationSession,
        pageResolver: (Int) -> eu.kanade.translation.scheduling.RollingAutoCoordinator.PageWorkItem?,
        computeClass: eu.kanade.translation.translator.TranslatorComputeClass,
    ) {
        scheduler.updateAutoWindow(
            identity,
            visiblePageIndex,
            configuredAheadTarget,
            pageCount,
            session,
            pageResolver,
            computeClass,
        )
    }

    fun shutdownAutoCoordinator() = scheduler.shutdownAutoCoordinator()

    /** Reconciles the active rolling window after a reader lifecycle/memory signal. */
    fun reconcileAutoWindow() {
        scheduler.reconcileAutoWindow()
    }

    /** Reader-facing projection with the committed display pointer applied. */
    fun observeActiveDisplayStore(chapterId: Long): StateFlow<Map<String, PageTranslation>>? =
        activeStores.get(chapterId)?.display

    /**
     * Chapter-keyed active page source for reader state. Unlike a global active-store stream,
     * this never emits another chapter's pages and becomes empty when this chapter is removed.
     */
    fun selectActiveStore(chapterId: Long): Flow<Map<String, PageTranslation>> = activeStores.select(chapterId)

    fun createBatchTracker(
        chapterId: Long,
        store: ChapterTranslationStore,
        orderedPageKeys: List<String>,
    ): TranslationBatchProgressTracker = batchTrackerRegistry.createTracker(
        chapterId = chapterId,
        store = store,
        orderedPageKeys = orderedPageKeys,
        scope = storeScope,
        permitHolderResolver = { pipeline.permitHolderPageKeySnapshot() },
    )

    fun disposeBatchTracker(chapterId: Long) {
        batchTrackerRegistry.dispose(chapterId)
    }

    internal fun terminalSnapshotCacheSize(): Int = batchTrackerRegistry.terminalSnapshotCacheSize()

    fun observeBatchProgress(chapterId: Long): Flow<TranslationProgressSnapshot> =
        progressProjector.observeBatchProgress(chapterId)

    fun observeTranslationProgress(chapterId: Long): Flow<TranslationProgressSnapshot> =
        progressProjector.observeTranslationProgress(chapterId)

    fun observePageView(chapterId: Long, pageKey: String): Flow<PageView>? =
        progressProjector.observePageView(chapterId, pageKey)

    // T909 Phase 11: pure projection bodies moved to BatchProgressProjector; these
    // same-signature stubs stay because TranslationManagerPausedAffordanceTest
    // invokes them reflectively on TranslationManager.
    private fun TranslationProgressSnapshot.withDurablePause(
        store: ChapterTranslationStore,
    ): TranslationProgressSnapshot = progressProjector.withDurablePauseOf(this, store)

    private fun TranslationProgressSnapshot.projectQueueStatus(
        queueStatus: Translation.State?,
    ): TranslationProgressSnapshot = progressProjector.projectQueueStatusOf(this, queueStatus)

    // T909 Phase 19: the delete/reset region moved to
    // manager/ChapterDataResetController.kt as a pure move (the copy-paste
    // dedupe between the active-store and open-store branches stays out of
    // scope). Same-signature stubs keep the manager's public seams; the
    // controller is built per access from the current field values.
    private val chapterDataReset: ChapterDataResetController
        get() = ChapterDataResetController(
            findTranslationDocumentFn = { chapterName, scanlator, mangaTitle, source ->
                findTranslationDocument(chapterName, scanlator, mangaTitle, source)
            },
            schedulerProvider = { scheduler },
            cancelPageTranslationsFn = { chapterId -> cancelPageTranslations(chapterId) },
            cancelPageTranslationFn = { chapterId, pageKey -> cancelPageTranslation(chapterId, pageKey) },
            removeFromTranslationQueueFn = { chapter -> removeFromTranslationQueue(chapter) },
            translatorProvider = { translator },
            disposeBatchTrackerFn = { chapterId -> disposeBatchTracker(chapterId) },
            unregisterActiveTranslationStoreFn = { chapterId -> unregisterActiveTranslationStore(chapterId) },
            streamRegistryProvider = { streamRegistry },
            providerProvider = { provider },
            retireChapterCompanionImagesFn = { manga, chapter, source ->
                retireChapterCompanionImages(manga, chapter, source)
            },
            retirePageCompanionImageFn = { manga, chapter, source, pageKey, imageName ->
                retirePageCompanionImage(manga, chapter, source, pageKey, imageName)
            },
            durableStatusResolverProvider = { durableStatusResolver },
            activeStoresProvider = { activeStores },
            openExistingChapterTranslationStoreFn = { chapterId, chapterName, scanlator, mangaTitle, source ->
                openExistingChapterTranslationStore(chapterId, chapterName, scanlator, mangaTitle, source)
            },
        )

    suspend fun deleteTranslation(chapter: Chapter, manga: Manga, source: Source) =
        chapterDataReset.deleteTranslation(chapter, manga, source)

    suspend fun deletePageTranslation(chapter: Chapter, manga: Manga, source: Source, pageKey: String) =
        chapterDataReset.deletePageTranslation(chapter, manga, source, pageKey)

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

    fun deleteManga(manga: Manga, source: Source, removeQueued: Boolean = true) {
        launchIO {
            if (removeQueued) {
                translator.removeFromQueue(manga)
            }
            provider.findMangaDir(manga.title, source)?.delete()
            val sourceDir = provider.findSourceDir(source)
            if (sourceDir?.listFiles()?.isEmpty() == true) {
                sourceDir.delete()
            }
        }
    }

    fun cancelQueuedTranslation(translation: Translation) {
        removeFromTranslationQueue(translation.chapter)
    }

    private fun removeFromTranslationQueue(chapter: Chapter) {
        val wasRunning = translator.isRunning
        if (wasRunning) {
            translator.pause()
        }
        translator.removeFromQueue(chapter)
        if (wasRunning) {
            if (queueState.value.isEmpty()) {
                translator.stop()
            } else if (queueState.value.isNotEmpty()) {
                translator.start()
            }
        }
    }

    // T909 Phase 16: body moved to manager/CleanedImageLifecycleController.kt.
    // Same-signature stub keeps the call sites.
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

    // T909 Phase 18: page-job control bodies moved to
    // manager/ReaderTeardownCoordinator.kt (runBlocking bridge and
    // dispatcher-constraint comments moved with them). Same-signature stubs
    // keep the public seams.

    fun translatePage(manga: Manga, chapter: Chapter, source: HttpSource, pageKey: String) =
        readerTeardown.translatePage(manga, chapter, source, pageKey)

    fun cancelPageTranslation(chapterId: Long, pageKey: String): Boolean =
        readerTeardown.cancelPageTranslation(chapterId, pageKey)

    suspend fun cancelPageTranslations(chapterId: Long) =
        readerTeardown.cancelPageTranslations(chapterId)

    fun cancelAllPageTranslations(cancelBatchQueue: Boolean = false) =
        readerTeardown.cancelAllPageTranslations(cancelBatchQueue)

    suspend fun cancelAllPageTranslationsOffMain(cancelBatchQueue: Boolean = false) =
        readerTeardown.cancelAllPageTranslationsOffMain(cancelBatchQueue)

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
