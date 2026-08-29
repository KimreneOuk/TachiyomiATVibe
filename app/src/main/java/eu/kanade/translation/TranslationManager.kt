package eu.kanade.translation

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.translation.TranslationForegroundService
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.ChapterArtifactDeletionPlan
import eu.kanade.translation.artifact.ChapterDocumentIo
import eu.kanade.translation.artifact.ManifestAuthority
import eu.kanade.translation.artifact.UniFileChapterDocumentIo
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
import eu.kanade.translation.manager.TranslationRequestCoordinator
import eu.kanade.translation.model.findRunningSameSourceConflict
import eu.kanade.translation.model.staleQueuedChaptersToEvict
import eu.kanade.translation.model.toPageDisplayProjection
import eu.kanade.translation.model.toPageView
import eu.kanade.translation.model.toQueuedChapterView
import eu.kanade.translation.scheduling.TranslationStreamRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
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
import kotlinx.coroutines.sync.withLock
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

private const val MAX_ORPHANED_CLEANED_IMAGES_PER_SWEEP = 64
private const val ORPHANED_CLEANED_IMAGE_FRESHNESS_GRACE_MS = 30_000L

internal fun isFreshOrphanedCleanedImage(lastModified: Long, nowEpochMs: Long): Boolean =
    lastModified <= 0L || nowEpochMs - lastModified < ORPHANED_CLEANED_IMAGE_FRESHNESS_GRACE_MS

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

    private data class DurableChapterKey(
        val chapterId: Long?,
        val chapterName: String,
        val chapterScanlator: String?,
        val mangaTitle: String,
        val sourceId: Long,
    )

    private data class DurableStatus(val state: Translation.State)

    private data class TranslationDocument(
        val parent: UniFile,
        val fileName: String,
        val file: UniFile?,
    ) {
        val registryKey: String get() = "${parent.filePath ?: parent.uri}:$fileName"
    }

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
            translator.queueState.collect { durableStatusCache.clear() }
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

    fun stopReaderTranslations(reason: String) {
        // The cancellation path includes synchronous runBlocking bridges for durable store
        // cleanup and bounded persist joins. Keep the entire chain on the manager's IO scope so
        // ReaderActivity lifecycle callbacks return without touching those bridges on main.
        applicationScope.launch(start = CoroutineStart.DEFAULT) {
            readerTeardownMutex.withLock {
                cancelAllPageTranslations(cancelBatchQueue = false)
                if (!isAnyBatchTranslationActive) {
                    translatorStop(reason, closeEngines = false)
                }
            }
        }
    }

    /**
     * Starts reader-owned teardown on the manager lifetime rather than the
     * ReaderViewModel scope. The deferred completes only after scheduler
     * coordinator/native/page jobs have joined and reader stores are evicted.
     */
    fun requestReaderStop(reason: String): Deferred<Unit> =
        applicationScope.async(start = CoroutineStart.DEFAULT) {
            awaitReaderStop(reason)
        }

    /** Joined counterpart for callers that already own a non-cancelled scope. */
    suspend fun awaitReaderStop(reason: String) {
        // This method is also called directly by chapter-switch work. Enforce the same IO fence
        // here so a future lifecycle caller cannot reintroduce a main-thread synchronous prefix.
        withContext(Dispatchers.IO) {
            readerTeardownMutex.withLock {
                scheduler.awaitReaderStop()
                val chapterIdsToEvict = activeStores.chapterIds()
                    .filter { !isBatchTranslationRetained(it) }
                chapterIdsToEvict.forEach { unregisterActiveTranslationStore(it) }
            }
        }
    }

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

    private fun persistedChapterStatus(
        chapterId: Long?,
        chapterName: String,
        chapterScanlator: String?,
        mangaTitle: String,
        sourceId: Long,
    ): Translation.State? {
        val key = DurableChapterKey(chapterId, chapterName, chapterScanlator, mangaTitle, sourceId)
        durableStatusCache[key]?.let { return it.state }
        val state = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            resolveDurableChapterStatus(
                chapterId,
                chapterName,
                chapterScanlator,
                mangaTitle,
                sourceId,
            )
        }
        // A null result includes an absent document, a failed probe, and a
        // recoverable rescue/permission error. Do not turn that transient
        // outcome into a same-manager cache hit that hides a later retry.
        state?.let { durableStatusCache[key] = DurableStatus(it) }
        return state
    }

    private suspend fun resolveDurableChapterStatus(
        chapterId: Long?,
        chapterName: String,
        chapterScanlator: String?,
        mangaTitle: String,
        sourceId: Long,
    ): Translation.State? {
        val source = sourceManager.get(sourceId) ?: return null
        val document = findTranslationDocument(chapterName, chapterScanlator, mangaTitle, source)
            ?: return null
        val manifestProbe = ChapterTranslationStore.probeArtifactManifest(document.parent, document.fileName)
        return when {
            manifestProbe.exists && manifestProbe.manifest?.authority == ManifestAuthority.ARTIFACTS -> {
                withProbeStore(document, chapterId) { store ->
                    store.artifactStatus()
                }
            }
            manifestProbe.exists && manifestProbe.manifest == null -> {
                // A present but unreadable manifest must not fall back to stale flat JSON.
                withProbeStore(document, chapterId) { store ->
                    store.artifactStatus()
                }
            }
            else -> document.file?.let { decodeLegacyChapterStatus(it, chapterName) }
        }
    }

    // T909 Phase 3a: legacy decode/quarantine bodies moved to legacy/LegacyFlatFileDecoder.kt.
    private fun decodeLegacyChapterStatus(
        file: UniFile,
        chapterName: String,
    ): Translation.State? = LegacyFlatFileDecoder.decodeLegacyChapterStatus(file, chapterName)

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
            durableStatusCache.clear()
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
        durableStatusCache.clear()
        return store
    }

    private fun findTranslationDocument(
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
    ): TranslationDocument? {
        val file = provider.findTranslationFile(chapterName, scanlator, mangaTitle, source)
        val parent = file?.parentFile ?: provider.findMangaDir(mangaTitle, source) ?: return null
        val fileName = file?.name ?: provider.getTranslationFileName(chapterName, scanlator)
        return TranslationDocument(parent, fileName, file ?: parent.findFile(fileName))
    }

    private suspend fun <T> withProbeStore(
        document: TranslationDocument,
        chapterId: Long?,
        block: suspend (ChapterTranslationStore) -> T,
    ): T? {
        if (chapterId != null) {
            activeStores.get(chapterId)?.let { return block(it) }
        }
        val result = activeStores.getOrCreateProbe(document.registryKey) {
            if (document.file?.exists() == true) {
                ChapterTranslationStore.open(document.file)
            } else {
                ChapterTranslationStore.openArtifact(document.parent, document.fileName)
            }
        } ?: return null
        // A probe can perform the one-way rescue and rename intent recovery
        // while it opens. Any status cached before that transition is stale.
        durableStatusCache.clear()
        return try {
            block(result.store)
        } finally {
            if (result.owned && activeStores.releaseProbe(document.registryKey, result.store)) {
                result.store.closeAndFlush()
            }
        }
    }

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
        durableStatusCache.clear()
    }

    fun unregisterActiveTranslationStore(chapterId: Long) {
        // Mark the evicted store defunct BEFORE removing it from the registry. A worker still
        // holding a reference has late writes rejected rather than recreating deleted output.
        activeStores.remove(chapterId)?.markDefunct()
        durableStatusCache.clear()
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

    /**
     * Reclaims previous committed cleaned images discovered while opening a
     * chapter. The store only exposes names after its committed pointer is
     * reconstructed; deletion still goes through the stream registry so a
     * reader stream held across a reopen cannot be invalidated.
     *
     * Launched on the application IO scope: the registry executes a retired
     * image's delete callback inline when no lease is held, which is SAF
     * binder I/O — it must never run on the caller's thread (the reader
     * resolves stores from page binds).
     */
    private fun scheduleRetiredCleanedImageCleanup(
        store: ChapterTranslationStore,
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
        mangaId: Long?,
    ) {
        val stableMangaId = mangaId ?: return
        applicationScope.launch {
            store.state.value.keys.forEach { pageKey ->
                store.drainRetiredCleanedImages(pageKey).forEach { imageName ->
                    streamRegistry.retireCleanedImage(
                        sourceId = source.id,
                        mangaId = stableMangaId,
                        chapterId = chapterId,
                        pageKey = pageKey,
                        imageName = imageName,
                    ) {
                        if (!store.mayDeleteCleanedImage(pageKey, imageName)) return@retireCleanedImage
                        val deleted = provider.findPageCleanedImage(
                            mangaTitle,
                            source,
                            chapterName,
                            scanlator,
                            imageName,
                        )?.delete() == true
                        logcat(if (deleted) LogPriority.INFO else LogPriority.WARN) {
                            "TachiyomiAT chapter-load retired cleaned image drain: " +
                                "pageKey=$pageKey file=$imageName deleted=$deleted"
                        }
                    }
                }
            }
            sweepOrphanedCleanedImages(
                store = store,
                chapterId = chapterId,
                chapterName = chapterName,
                scanlator = scanlator,
                mangaTitle = mangaTitle,
                source = source,
                mangaId = stableMangaId,
            )
        }
    }

    private fun sweepOrphanedCleanedImages(
        store: ChapterTranslationStore,
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
        mangaId: Long,
    ) {
        val directory = provider.findCompanionImageDir(mangaTitle, source, chapterName, scanlator) ?: return
        val referenced = store.referencedCleanedImageNames()
        val pageKeys = store.state.value.keys
        val now = System.currentTimeMillis()
        directory.listFiles()
            ?.asSequence()
            ?.mapNotNull { file -> file.name?.let { it to file } }
            ?.filter { (name, file) -> file.isFile && name.contains(".cleaned.") }
            ?.filterNot { (name, file) ->
                name in referenced ||
                    streamRegistry.activeCleanedImageReadersForChapter(source.id, mangaId, chapterId, name) > 0 ||
                    pageKeys.any { pageKey -> !store.mayDeleteCleanedImage(pageKey, name) } ||
                    isFreshOrphanedCleanedImage(file.lastModified(), now)
            }
            ?.take(MAX_ORPHANED_CLEANED_IMAGES_PER_SWEEP)
            ?.forEach { (name, file) ->
                val deleted = runCatching { file.delete() }.getOrDefault(false)
                logcat(if (deleted) LogPriority.INFO else LogPriority.WARN) {
                    "TachiyomiAT orphaned cleaned image sweep: chapter=$chapterName file=$name deleted=$deleted"
                }
            }
    }

    private fun retireChapterCompanionImages(
        manga: Manga,
        chapter: Chapter,
        source: Source,
    ) {
        val chapterId = chapter.id ?: return
        val directory = provider.findCompanionImageDir(manga.title, source, chapter.name, chapter.scanlator)
        val namesAtRetirement = directory
            ?.listFiles()
            ?.asSequence()
            ?.mapNotNull { it.name }
            ?.filterNot { it == ".nomedia" }
            ?.toSet()
            .orEmpty()
        streamRegistry.retireCleanedImagesForChapter(
            sourceId = source.id,
            mangaId = manga.id,
            chapterId = chapterId,
        ) {
            namesAtRetirement.forEach { imageName ->
                directory?.findFile(imageName)?.delete()
            }
        }
    }

    private fun retirePageCompanionImage(
        manga: Manga,
        chapter: Chapter,
        source: Source,
        pageKey: String,
        imageName: String,
    ) {
        val chapterId = chapter.id ?: return
        streamRegistry.retireCleanedImage(
            sourceId = source.id,
            mangaId = manga.id,
            chapterId = chapterId,
            pageKey = pageKey,
            imageName = imageName,
        ) {
            provider.findPageCleanedImage(
                manga.title,
                source,
                chapter.name,
                chapter.scanlator,
                imageName,
            )?.delete()
        }
    }

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

    suspend fun deleteTranslation(chapter: Chapter, manga: Manga, source: Source) {
        val chapterId = chapter.id ?: return
        // Capture the validated authority/legacy-preservation marker before any
        // teardown can evict the only store that still knows the exact names.
        // This is read-only and runs off the caller thread because SAF reads can
        // block; the cancellation ordering below remains unchanged.
        val deletionDocument = withContext(Dispatchers.IO) {
            findTranslationDocument(chapter.name, chapter.scanlator, manga.title, source)
        }
        val artifactDeletionPlan = deletionDocument?.let { document ->
            withContext(Dispatchers.IO) {
                ChapterArtifactDeletionPlan.capture(
                    UniFileChapterDocumentIo(document.parent),
                    document.fileName,
                )
            }
        }
        // SYNCHRONOUS teardown (was fire-and-forget): a delete-then-retranslate let the reader
        // re-bind to the about-to-be-evicted store while the translator wrote to a fresh instance,
        // and a cancelled-but-not-joined batch worker kept writing into the old store after its
        // file/PNGs were deleted (recreating the JSON or stranding pages at RUNNING). Suspending
        // guarantees callers land on clean state.
        //
        // Ordering is load-bearing and strictly sequenced:
        //   1. cancelAutoTranslations bumps the auto generation so the window stops dispatching new pages.
        //   2. cancelPageTranslations cancels + JOINs each auto/single-page job so native work unwinds.
        //   3. removeFromTranslationQueue + cancelTranslatorJobAndJoin drop the batch entry and JOIN the
        //      batch worker (plain removeFrom only cancel()s) so it releases the translator permit before deletion.
        //   4. unregisterActiveTranslationStore marks the store defunct so a still-unwinding worker's late writes no-op.
        //   5. streamRegistry.clearChapter drops stale reader closures pointing at the about-to-be-deleted PNGs.
        //   6. Only once all work is wound down is it safe to delete the on-disk file + companion images.
        scheduler.cancelAutoTranslations(chapterId)
        cancelPageTranslations(chapterId)
        removeFromTranslationQueue(chapter)
        translator.cancelTranslatorJobAndJoin()
        disposeBatchTracker(chapterId)
        unregisterActiveTranslationStore(chapterId)
        streamRegistry.clearChapter(source.id, manga.id, chapterId)
        val file = deletionDocument?.file ?: provider.findTranslationFile(
            chapter.name,
            chapter.scanlator,
            manga.title,
            source,
        )
        var authorityRemoved = true
        artifactDeletionPlan?.let { plan ->
            val result = withContext(Dispatchers.IO) { plan.delete() }
            authorityRemoved = result.manifestRemoved
            logcat(if (result.complete) LogPriority.INFO else LogPriority.ERROR) {
                "TachiyomiAT chapter artifact deletion: chapter=${chapter.name} " +
                    "manifestRemoved=${result.manifestRemoved} " +
                    "artifactTreeRemoved=${result.artifactTreeRemoved} " +
                    "deletedLegacy=${result.deletedLegacyNames.size} " +
                    "retainedLegacy=${result.retainedLegacyNames.size} " +
                    "failures=${result.failures.size}"
            }
            // An artifact-authoritative chapter owns its flat source only when
            // the migration marker proves its current identity. Unknown or
            // mismatched legacy files remain untouched by the plan.
            if (!plan.isArtifactAuthoritative) file?.delete()
        } ?: file?.delete()
        if (authorityRemoved) retireChapterCompanionImages(manga, chapter, source)
        durableStatusCache.clear()
    }

    suspend fun deletePageTranslation(chapter: Chapter, manga: Manga, source: Source, pageKey: String) {
        resetOcrData(chapter, manga, source, pageKey)
    }

    suspend fun chapterResetPreflight(
        chapter: Chapter,
        manga: Manga,
        source: Source,
    ): ChapterResetPreflight {
        val chapterId = chapter.id
        val activeStore = chapterId?.let(activeStores::get)
        if (activeStore != null) return activeStore.resetPreflight()

        return openExistingChapterTranslationStore(
            chapterId,
            chapter.name,
            chapter.scanlator,
            manga.title,
            source,
        )?.resetPreflight() ?: ChapterResetPreflight(0, 0, 0, 0)
    }

    suspend fun resetChapterTranslationData(
        chapter: Chapter,
        manga: Manga,
        source: Source,
        preserveEdits: Boolean,
    ) {
        resetChapterData(chapter, manga, source) { page ->
            val blocks = page.blocks.map { block ->
                if (preserveEdits && block.userEditedAt != null) {
                    block
                } else {
                    block.copy(
                        translation = "",
                        textColor = 0xFF000000,
                        strokeColor = 0xFFFFFFFF,
                        strokeWidth = 0f,
                    )
                }
            }.toMutableList()
            page.copy(
                blocks = blocks,
                translationStatus = StageStatus.PENDING,
                renderStatus = StageStatus.PENDING,
            ).also {
                it.translationError = null
                it.renderError = null
            }
        }
    }

    suspend fun resetChapterInpaintData(chapter: Chapter, manga: Manga, source: Source) {
        resetChapterData(chapter, manga, source) { page ->
            page.copy(
                cleanedImageName = null,
                inpaintStatus = StageStatus.PENDING,
                renderStatus = StageStatus.PENDING,
            ).also {
                it.inpaintError = null
                it.renderError = null
            }
        }
        retireChapterCompanionImages(manga, chapter, source)
    }

    suspend fun resetChapterOcrData(chapter: Chapter, manga: Manga, source: Source) {
        deleteTranslation(chapter, manga, source)
    }

    private suspend fun resetChapterData(
        chapter: Chapter,
        manga: Manga,
        source: Source,
        transform: (PageTranslation) -> PageTranslation,
    ) {
        val chapterId = chapter.id ?: return
        durableStatusCache.clear()
        scheduler.cancelAutoTranslations(chapterId)
        cancelPageTranslations(chapterId)
        removeFromTranslationQueue(chapter)
        translator.cancelTranslatorJobAndJoin()
        streamRegistry.clearChapter(source.id, manga.id, chapterId)

        val activeStore = activeStores.get(chapterId)
        if (activeStore != null) {
            activeStore.state.value.keys.forEach { pageKey ->
                activeStore.updatePageFromCurrentSnapshot(pageKey, "chapter data reset") { page -> page?.let(transform) ?: PageTranslation.EMPTY }
                // Phase 3: an explicit user reset drops the committed display
                // pointer too, so the reader stops showing the cleared bundle.
                activeStore.demoteCommittedDisplay(pageKey, "chapter data reset")
            }
            activeStore.flush()
        } else {
            openExistingChapterTranslationStore(
                chapterId,
                chapter.name,
                chapter.scanlator,
                manga.title,
                source,
            )?.let { store ->
                store.state.value.keys.forEach { pageKey ->
                    store.updatePageFromCurrentSnapshot(pageKey, "chapter data reset") { page -> page?.let(transform) ?: PageTranslation.EMPTY }
                    store.demoteCommittedDisplay(pageKey, "chapter data reset")
                }
                store.flush()
            }
        }
        reconcileBatchProgress(chapterId, chapter.name, chapter.scanlator, manga.title, source)
    }

    suspend fun resetTranslationData(chapter: Chapter, manga: Manga, source: Source, pageKey: String, preserveEdits: Boolean) {
        val chapterId = chapter.id ?: return
        durableStatusCache.clear()
        cancelPageTranslation(chapterId, pageKey)
        streamRegistry.clearPage(source.id, manga.id, chapterId, pageKey)

        val store = activeStores.get(chapterId)
        if (store != null) {
            store.updatePageFromCurrentSnapshot(pageKey, "translation data reset") { page ->
                page ?: return@updatePageFromCurrentSnapshot eu.kanade.translation.model.PageTranslation.EMPTY
                val newBlocks = page.blocks.map { block ->
                    if (preserveEdits && block.userEditedAt != null) {
                        block
                    } else {
                        block.copy(
                            translation = "",
                            textColor = 0xFF000000,
                            strokeColor = 0xFFFFFFFF,
                            strokeWidth = 0f,
                        )
                    }
                }.toMutableList()

                page.copy(
                    blocks = newBlocks,
                    translationStatus = eu.kanade.translation.model.StageStatus.PENDING,
                    renderStatus = eu.kanade.translation.model.StageStatus.PENDING,
                ).also {
                    it.translationError = null
                    it.renderError = null
                }
            }
            store.demoteCommittedDisplay(pageKey, "translation data reset")
            store.flush()
        } else {
            openExistingChapterTranslationStore(
                chapterId,
                chapter.name,
                chapter.scanlator,
                manga.title,
                source,
            )?.let { s ->
                s.updatePageFromCurrentSnapshot(pageKey, "translation data reset") { page ->
                    page ?: return@updatePageFromCurrentSnapshot eu.kanade.translation.model.PageTranslation.EMPTY
                    val newBlocks = page.blocks.map { block ->
                        if (preserveEdits && block.userEditedAt != null) {
                            block
                        } else {
                            block.copy(
                                translation = "",
                                textColor = 0xFF000000,
                                strokeColor = 0xFFFFFFFF,
                                strokeWidth = 0f,
                            )
                        }
                    }.toMutableList()

                    page.copy(
                        blocks = newBlocks,
                        translationStatus = eu.kanade.translation.model.StageStatus.PENDING,
                        renderStatus = eu.kanade.translation.model.StageStatus.PENDING,
                    ).also {
                        it.translationError = null
                        it.renderError = null
                    }
                }
                s.demoteCommittedDisplay(pageKey, "translation data reset")
                s.flush()
            }
        }

        // Reconcile batch progress so summary drops cleared data
        reconcileBatchProgress(chapterId, chapter.name, chapter.scanlator, manga.title, source)
    }

    suspend fun resetInpaintData(chapter: Chapter, manga: Manga, source: Source, pageKey: String) {
        val chapterId = chapter.id ?: return
        durableStatusCache.clear()
        cancelPageTranslation(chapterId, pageKey)
        streamRegistry.clearPage(source.id, manga.id, chapterId, pageKey)

        val store = activeStores.get(chapterId)
        val persistedCleanedName = store?.state?.value?.get(pageKey)?.cleanedImageName
        if (store != null) {
            store.updatePageFromCurrentSnapshot(pageKey, "inpaint data reset") { page ->
                page ?: return@updatePageFromCurrentSnapshot eu.kanade.translation.model.PageTranslation.EMPTY
                page.copy(
                    cleanedImageName = null,
                    inpaintStatus = eu.kanade.translation.model.StageStatus.PENDING,
                    renderStatus = eu.kanade.translation.model.StageStatus.PENDING,
                ).also {
                    it.inpaintError = null
                    it.renderError = null
                }
            }
            store.demoteCommittedDisplay(pageKey, "inpaint data reset")
            store.flush()
        } else {
            openExistingChapterTranslationStore(
                chapterId,
                chapter.name,
                chapter.scanlator,
                manga.title,
                source,
            )?.let { s ->
                s.updatePageFromCurrentSnapshot(pageKey, "inpaint data reset") { page ->
                    page ?: return@updatePageFromCurrentSnapshot eu.kanade.translation.model.PageTranslation.EMPTY
                    page.copy(
                        cleanedImageName = null,
                        inpaintStatus = eu.kanade.translation.model.StageStatus.PENDING,
                        renderStatus = eu.kanade.translation.model.StageStatus.PENDING,
                    ).also {
                        it.inpaintError = null
                        it.renderError = null
                    }
                }
                s.demoteCommittedDisplay(pageKey, "inpaint data reset")
                s.flush()
            }
        }

        val safePageKey = pageKey.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val retiredNames = buildSet {
            persistedCleanedName?.let(::add)
            add("$safePageKey.cleaned.png")
            add("$safePageKey.cleaned.jpg")
            add("$safePageKey.rendered.png")
            provider.findCompanionImageDir(manga.title, source, chapter.name, chapter.scanlator)
                ?.listFiles()
                ?.asSequence()
                .orEmpty()
                .mapNotNull { it.name }
                .filter { it.startsWith("$safePageKey.cleaned.") }
                .forEach(::add)
        }
        retiredNames.forEach { imageName ->
            retirePageCompanionImage(manga, chapter, source, pageKey, imageName)
        }

        reconcileBatchProgress(chapterId, chapter.name, chapter.scanlator, manga.title, source)
    }

    suspend fun resetOcrData(chapter: Chapter, manga: Manga, source: Source, pageKey: String) {
        val chapterId = chapter.id ?: return
        durableStatusCache.clear()

        cancelPageTranslation(chapterId, pageKey)
        streamRegistry.clearPage(source.id, manga.id, chapterId, pageKey)

        val activeStore = activeStores.get(chapterId)
        val persistedCleanedName = activeStore?.state?.value?.get(pageKey)?.cleanedImageName
        if (activeStore != null) {
            activeStore.deletePage(pageKey)
            activeStore.flush()
        } else {
            openExistingChapterTranslationStore(
                chapterId,
                chapter.name,
                chapter.scanlator,
                manga.title,
                source,
            )?.let { store ->
                store.deletePage(pageKey)
                store.flush()
            }
        }

        val safePageKey = pageKey.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val retiredNames = buildSet {
            persistedCleanedName?.let(::add)
            add("$safePageKey.cleaned.png")
            add("$safePageKey.cleaned.jpg")
            add("$safePageKey.rendered.png")
            // Versioned publication names are unique per replacement attempt;
            // remove any orphaned versions left after a deleted store entry.
            provider.findCompanionImageDir(manga.title, source, chapter.name, chapter.scanlator)
                ?.listFiles()
                ?.asSequence()
                .orEmpty()
                .mapNotNull { it.name }
                .filter { it.startsWith("$safePageKey.cleaned.") }
                .forEach(::add)
        }
        retiredNames.forEach { imageName ->
            retirePageCompanionImage(manga, chapter, source, pageKey, imageName)
        }
    }

    private suspend fun reconcileBatchProgress(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
    ) {
        // Refresh the active-store summary after a stage reset so the chapter
        // list can drop stale progress data. Only runs when the store is open
        // (i.e. the reader is active for this chapter); persisted-only chapters
        // are unaffected because their summary is rebuilt on the next open.
        val store = activeStores.get(chapterId) ?: return
        store.flush()
    }

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

    fun getCleanedImageStream(
        mangaTitle: String,
        source: Source,
        chapterName: String,
        chapterScanlator: String?,
        cleanedImageName: String,
        pageKey: String? = null,
        mangaId: Long? = null,
        chapterId: Long? = null,
    ): (() -> java.io.InputStream)? {
        return {
            val file = provider.findPageCleanedImage(mangaTitle, source, chapterName, chapterScanlator, cleanedImageName)
            if (file?.exists() == true) {
                val raw = { file.openInputStream() }
                if (pageKey == null || mangaId == null || chapterId == null) {
                    raw()
                } else {
                    streamRegistry.openCleanedImageStream(
                        sourceId = source.id,
                        mangaId = mangaId,
                        chapterId = chapterId,
                        pageKey = pageKey,
                        imageName = cleanedImageName,
                        open = raw,
                    )
                }
            } else {
                throw java.io.FileNotFoundException("Cleaned image not found: $cleanedImageName")
            }
        }
    }

    fun translatePage(manga: Manga, chapter: Chapter, source: HttpSource, pageKey: String) =
        scheduler.translatePage(manga, chapter, source, pageKey)

    /**
     * Cancels the in-flight single-page translation job for one [pageKey] within [chapterId] —
     * the per-page granularity [cancelPageTranslations] (chapter-scoped) is too coarse for.
     * Returns true if a job was actually cancelled, false if none was running for that page.
     */
    fun cancelPageTranslation(chapterId: Long, pageKey: String): Boolean =
        scheduler.cancelPageTranslation(chapterId, pageKey)

    /**
     * Cancels all in-flight single-page translation jobs for [chapterId] and evicts the shared
     * [ChapterTranslationStore] so it does not leak across chapter navigations. Call this on
     * reader navigate-away so the previous chapter's work can no longer hold the executor's
     * single permit. Job cancellation is delegated to the scheduler; store eviction is manager-owned.
     */
    suspend fun cancelPageTranslations(chapterId: Long) {
        scheduler.cancelPageTranslations(chapterId)
        if (isBatchTranslationRetained(chapterId)) {
            return
        }
        disposeBatchTracker(chapterId)
        activeStores.get(chapterId)?.clearTransientQueuePages("Translation cancelled")
        // Evict the store on chapter exit; the reader re-opens it via observeLiveTranslationStore on the next loadChapter.
        unregisterActiveTranslationStore(chapterId)
    }

    /**
     * Cancels every in-flight single-page translation job and drops all shared stores. Call this
     * when the reader is destroyed or the master toggle is switched off, so no orphaned work
     * keeps running and no collector outlives the session. Job cancellation is delegated to the
     * scheduler; store eviction + chapter queue clearing are manager-owned.
     */
    fun cancelAllPageTranslations(cancelBatchQueue: Boolean = false) {
        scheduler.cancelAllPageTranslations()
        val chapterIdsToEvict = activeStores.chapterIds()
            .filter { cancelBatchQueue || !isBatchTranslationRetained(it) }
        val stores = chapterIdsToEvict.mapNotNull { activeStores.get(it) }
        if (stores.isNotEmpty()) {
            // TachiyomiAT bug 4 fix: the durable CANCELLED write MUST land before
            // unregisterActiveTranslationStore marks these stores defunct below.
            // The previous code launched clearTransientQueuePages on storeScope
            // and then synchronously called markDefunct in the same pass; the
            // async clear was rejected as defunct and the durable state was
            // silently dropped, leaving pages RUNNING in the next session's
            // rehydrated snapshot. Run the clear to completion here (bounded by
            // the small number of active chapter stores) before eviction.
            kotlinx.coroutines.runBlocking {
                stores.forEach { store ->
                    store.clearTransientQueuePages("All translation cancelled")
                }
            }
        }
        chapterIdsToEvict.forEach { unregisterActiveTranslationStore(it) }
        if (cancelBatchQueue) {
            translator.clearQueue()
            clearAllPendingTranslationRequests()
        }
    }

    /**
     * Runs the synchronous teardown bridge away from the reader main thread.
     * The underlying method remains synchronous for existing lifecycle callers,
     * but its SAF-backed store cleanup must never execute on UI dispatchers.
     */
    suspend fun cancelAllPageTranslationsOffMain(cancelBatchQueue: Boolean = false) {
        withContext(Dispatchers.IO) {
            cancelAllPageTranslations(cancelBatchQueue)
        }
    }

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
