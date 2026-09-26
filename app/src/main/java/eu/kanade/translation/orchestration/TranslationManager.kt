package eu.kanade.translation.orchestration

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.translation.TranslationForegroundService
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.artifact.ArtifactManifestProbe
import eu.kanade.translation.artifact.ArtifactStageStatus
import eu.kanade.translation.artifact.ChapterAttemptLedgerDocument
import eu.kanade.translation.artifact.toUiPauseReason
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.diagnostics.ReaderEntryTrace
import eu.kanade.translation.manager.ChapterDataResetController
import eu.kanade.translation.manager.CleanedImageLifecycleController
import eu.kanade.translation.manager.DurableChapterKey
import eu.kanade.translation.manager.DurableChapterStatusResolver
import eu.kanade.translation.manager.DurableDocumentKey
import eu.kanade.translation.manager.DurableStatus
import eu.kanade.translation.manager.ReaderTeardownCoordinator
import eu.kanade.translation.manager.TranslationDocument
import eu.kanade.translation.manager.TranslationProgressProjection
import eu.kanade.translation.manager.TranslationRequestCoordinator
import eu.kanade.translation.manager.isReconstructibleDurableState
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageView
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationRequestFailureKind
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.model.translationQueueAdmissionFailureKind
import eu.kanade.translation.orchestration.BatchSessionIntent
import eu.kanade.translation.orchestration.ReaderSessionIntent
import eu.kanade.translation.orchestration.SessionAdmission
import eu.kanade.translation.orchestration.TranslationSessionCoordinator
import eu.kanade.translation.orchestration.TranslationSessionState
import eu.kanade.translation.pipeline.MemoryPressurePolicy
import eu.kanade.translation.pipeline.TranslationPipeline
import eu.kanade.translation.pipeline.batch.TranslationBatchProgressTracker
import eu.kanade.translation.pipeline.batch.TranslationBatchTrackerRegistry
import eu.kanade.translation.scheduling.TranslationStoreResolver
import eu.kanade.translation.scheduling.TranslationStreamRegistry
import eu.kanade.translation.storage.ActiveChapterStoreRegistry
import eu.kanade.translation.storage.ChapterTranslationStore
import eu.kanade.translation.storage.TranslationPendingRequestStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class TranslationManager(
    private val context: Context,
    private val provider: TranslationProvider = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
    //  slice 2: file truth for the startup reconciler (pending + valid
    // files -> admit once). DownloadProvider only resolves directories, so it
    // cannot cycle back into the manager the way DownloadManager would.
    private val downloadProvider: eu.kanade.tachiyomi.data.download.DownloadProvider = Injekt.get(),
) {
    private val pipeline = TranslationPipeline(context, provider)
    private val translator = ChapterTranslator(context, provider, pipeline = pipeline)

    /** Single owner for reader/batch admission; lifecycle coordinators delegate here. */
    val sessionCoordinator = TranslationSessionCoordinator(
        onBatchSwitchRequested = { translator.pause() },
    )

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

    /**  reader-visible native lane stall projection. */
    val nativeStall: StateFlow<eu.kanade.translation.translator.NativeStallState?>
        get() = pipeline.nativeStall

    /** Serializes request versioning, state publication, and durable writes. */
    private val pendingRequestMutationLock = Any()

    //  slice 2: per-chapter request generations (bumped on every new
    // request and on cancel) and the generation captured when a request was
    // attached to a download. The pair fences late downloader callbacks and
    // in-flight probe mutations (R7).
    private val pendingRequestGenerationCounters = ConcurrentHashMap<Long, AtomicLong>()
    private val downloadAttachGenerations = ConcurrentHashMap<Long, Long>()
    private val pendingGroupIdSequence = AtomicLong(0)

    //  hotfix: one-shot marks for pending requests the startup reconciler
    // admitted (process-local is sufficient — the reconciler and the later
    // download handoff run in the same process; a crash mid-download re-runs
    // the reconciler next process). A restore-admitted request must not
    // auto-start translation when its download completes (Director policy: no
    // OCR/LLM after a restart without an explicit user action); a live
    // in-session request never carries the mark and starts unchanged.
    // Nullable backing + self-heal: reflection-built test fixtures skip field
    // initializers, so an absent set simply means "no marks".
    @Volatile
    private var restoreAdmittedChapterIds: MutableSet<Long>? = null

    private fun restoreAdmittedMarks(): MutableSet<Long> =
        restoreAdmittedChapterIds ?: ConcurrentHashMap.newKeySet<Long>()
            .also { restoreAdmittedChapterIds = it }

    //  slice 2 (R9): startup reconciliation readiness barrier. The pass
    // runs once, after BOTH the downloader queue restore and the translation
    // queue restore have completed.
    @Volatile
    private var downloadQueueRestoredChapterIds: Set<Long>? = null

    @Volatile
    private var translationQueueRestoreJob: Job? = null

    private val startupReconciliationOnce = AtomicBoolean(false)

    //  Phase 9: pending-request subsystem bodies moved to
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
            translateChapter = { manga, chapter, expectedRequestGeneration, autoStart ->
                translateChapter(manga, chapter, expectedRequestGeneration, autoStart = autoStart)
            },
            pendingRequestGenerationCountersProvider = { pendingRequestGenerationCounters },
            downloadAttachGenerationsProvider = { downloadAttachGenerations },
            groupIdSequenceProvider = { pendingGroupIdSequence },
        )

    //  Phase 13: DurableChapterKey/DurableStatus/TranslationDocument and the
    // durable-status resolution region moved to manager/DurableChapterStatusResolver.kt.
    // The cache field below stays — the durable tests reflection-write this exact
    // field — and the resolver is built per access from the current field values.

    private val durableStatusCache = ConcurrentHashMap<DurableChapterKey, DurableStatus>()

    // Memoized translation-document locations, invalidated by the same
    // clearDurableStatusCache protocol. One reader entry used to walk the
    // SAF tree for the same document 5-7 times.
    private val durableDocumentCache = ConcurrentHashMap<DurableDocumentKey, TranslationDocument>()

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
        sessionCoordinator = sessionCoordinator,
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
            if (!isAnyBatchTranslationActive && queueState.value.none { it.status == Translation.State.PAUSED }) {
                sessionCoordinator.finishSession(TranslationSessionState.BATCH_SESSION)
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
            // Any queue emission can follow a durable transition (completion,
            // failure, cancel). Membership-set comparison is not enough: a
            // conflated remove→re-add of the same chapter reproduces the same
            // membership set while durable truth changed in between, and the
            // arm-after-pause path re-emits the same list verbatim. Wipe on
            // every emission — the clear is two map clears, and emissions only
            // fire on queue mutations, never on batch progress ticks.
            translator.queueState.collect {
                durableStatusResolver.clearDurableStatusCache()
            }
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
        applicationScope.launch {
            //  slice 2 (R9): once this restore completes AND the downloader
            // reports its own restore, run the one-shot pending-request
            // reconciler (idempotent, generation-fenced).
            translator.restoreQueue()
            runStartupReconciliationIfReady()
        }.also { translationQueueRestoreJob = it }
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

    /** Owns tracker reducer jobs; reader flows observe the selected store directly. */
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val isRunning: Boolean
        get() = translator.isRunning

    val queueState
        get() = translator.queueState

    val isAnyBatchTranslationActive: Boolean
        get() = queueState.value.any { it.status == Translation.State.QUEUE || it.status == Translation.State.TRANSLATING }

    private fun admitBatchSession(chapterIds: Set<Long>): Boolean {
        if (chapterIds.isEmpty()) return false
        return when (val admission = sessionCoordinator.requestBatchSession(BatchSessionIntent(chapterIds))) {
            is SessionAdmission.Admitted,
            is SessionAdmission.Switched,
            -> true

            is SessionAdmission.Rejected -> {
                logcat(LogPriority.INFO) {
                    "TachiyomiAT batch admission rejected: reason=${admission.reason} chapters=$chapterIds"
                }
                false
            }
        }
    }

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

    //  Phase 9: bodies moved to manager/TranslationRequestCoordinator.kt;
    // same-signature stubs keep the manager's public (and reflection-tested) seams.

    fun queueTranslationAfterDownload(manga: Manga, chapter: Chapter) {
        //  hotfix: a live request supersedes any restore-admitted mark.
        chapter.id?.let { chapterId -> restoreAdmittedMarks().remove(chapterId) }
        requestCoordinator.queueTranslationAfterDownload(manga, chapter)
    }

    /**  slice 2 (R7): fenced WAITING write — dropped when the request was cancelled/re-requested. */
    fun queueTranslationAfterDownloadIfCurrent(
        manga: Manga,
        chapter: Chapter,
        expectedGeneration: Long,
    ): Boolean = requestCoordinator.queueTranslationAfterDownloadIfCurrent(manga, chapter, expectedGeneration)

    fun acknowledgeTranslationRequests(chapters: List<Chapter>) {
        //  hotfix: live acknowledgements supersede restore-admitted marks.
        chapters.forEach { chapter ->
            chapter.id?.let { chapterId -> restoreAdmittedMarks().remove(chapterId) }
        }
        requestCoordinator.acknowledgeTranslationRequests(chapters)
    }

    fun markTranslationRequestPreparing(chapterId: Long) {
        requestCoordinator.markTranslationRequestPreparing(chapterId)
    }

    /**  slice 2 (R7): fenced PREPARING write — dropped when the request was cancelled/re-requested. */
    fun markTranslationRequestPreparingIfCurrent(chapterId: Long, expectedGeneration: Long): Boolean =
        requestCoordinator.markTranslationRequestPreparingIfCurrent(chapterId, expectedGeneration)

    /** Current in-process request generation, or null when no request exists. */
    fun pendingRequestGeneration(chapterId: Long): Long? =
        pendingTranslationRequestsState.value[chapterId]?.generation

    /**  slice 2 (R7): true when the live request still carries [generation]. */
    fun isTranslationRequestCurrent(chapterId: Long, generation: Long): Boolean =
        requestCoordinator.isTranslationRequestCurrent(chapterId, generation)

    fun markTranslationDownloadFailed(
        chapterId: Long,
        reason: String? = null,
        failureKind: TranslationRequestFailureKind = TranslationRequestFailureKind.DOWNLOAD_FAILED,
    ) {
        requestCoordinator.markTranslationDownloadFailed(chapterId, reason, failureKind)
    }

    /**
     *  slice 3 (R8): the chapter's files finalized, but the translation
     * start after the download failed (rekey/handoff/admission). The download
     * keeps its `DOWNLOADED` status; the request gets the R10 admission-failure
     * typing instead of a false download failure. No-op without a pending
     * request, so ordinary downloads are unaffected.
     */
    fun markTranslationHandoffFailed(
        chapterId: Long,
        reason: String? = null,
    ) {
        requestCoordinator.markTranslationHandoffFailed(chapterId, reason)
    }

    //  slice 2 (R5): download-side lifecycle notifications. Every one is a
    // no-op without a pending request, so ordinary downloads are unaffected.

    /** The chapter's download was cancelled or removed from the download queue. */
    fun onDownloadCancelledForTranslation(chapterId: Long) {
        requestCoordinator.onDownloadCancelled(chapterId)
    }

    /** The whole download queue was cleared while the chapter's request waited. */
    fun onDownloadQueueClearedForTranslation(chapterId: Long) {
        requestCoordinator.onDownloadQueueCleared(chapterId)
    }

    /** The downloader stopped (offline / Wi-Fi policy / generic) with the chapter unfinished. */
    fun onDownloadStoppedForTranslation(chapterId: Long, reason: String?) {
        requestCoordinator.onDownloadStopped(chapterId, reason)
    }

    fun cancelTranslationRequest(chapterId: Long): Boolean =
        requestCoordinator.cancelTranslationRequest(chapterId)

    fun clearStaleDownloadFailedRequest(chapterId: Long) {
        requestCoordinator.clearStaleDownloadFailedRequest(chapterId)
    }

    /** Milestone M6 (S2): Recovers from download-failure starvation. */
    fun rearmDownloadFailedRequest(chapterId: Long): Boolean =
        requestCoordinator.rearmDownloadFailedRequest(chapterId)

    fun hasPendingTranslationRequest(chapterId: Long): Boolean =
        requestCoordinator.hasPendingTranslationRequest(chapterId)

    fun isChapterTranslationProtected(chapterId: Long): Boolean =
        requestCoordinator.isChapterTranslationProtected(chapterId)

    fun protectedChapterIds(): Set<Long> = requestCoordinator.protectedChapterIds()

    private fun setPendingTranslationRequest(
        chapterId: Long,
        phase: TranslationRequestPhase,
        reason: String? = null,
        failureKind: TranslationRequestFailureKind = TranslationRequestFailureKind.NONE,
    ) {
        requestCoordinator.setPendingTranslationRequest(chapterId, phase, reason, failureKind)
    }

    private fun clearPendingTranslationRequest(chapterId: Long) {
        requestCoordinator.clearPendingTranslationRequest(chapterId)
    }

    private fun clearAllPendingTranslationRequests() {
        requestCoordinator.clearAllPendingTranslationRequests()
    }

    private fun loadPendingTranslationRequests(): Map<Long, TranslationRequestState> =
        pendingRequestStore.load().associateWith { chapterId ->
            val record = pendingRequestStore.record(chapterId)
            TranslationRequestState(
                chapterId = chapterId,
                phase = record?.phase ?: TranslationRequestPhase.WAITING_FOR_DOWNLOAD,
                reason = pendingRequestStore.reason(chapterId),
                generation = record?.generation ?: 0L,
                failureKind = record?.failureKind ?: TranslationRequestFailureKind.NONE,
            )
        }

    suspend fun startTranslationAfterDownloadIfRequested(manga: Manga, chapter: Chapter) {
        //  hotfix: one-shot gate. A pending request the startup reconciler
        // admitted is enqueued PAUSED when its download completes in this
        // process; live requests carry no mark and auto-start unchanged.
        val chapterId = chapter.id
        val restoreAdmitted = chapterId != null && restoreAdmittedMarks().remove(chapterId)
        requestCoordinator.startTranslationAfterDownloadIfRequested(manga, chapter, !restoreAdmitted)
    }

    //  slice 2 (R9): startup reconciliation ------------------------------

    /**
     * Called by the downloader once its asynchronous queue restore has
     * completed (both sides of the readiness barrier). Idempotent.
     */
    fun onDownloadQueueRestored(queuedChapterIds: Set<Long>) {
        downloadQueueRestoredChapterIds = queuedChapterIds
        applicationScope.launch { runStartupReconciliationIfReady() }
    }

    /**
     * Readiness barrier: runs once after BOTH queue restores completed. The
     * once-guard is only consumed when the downloader snapshot is present, so
     * a late downloader restore still triggers the pass.
     */
    private fun runStartupReconciliationIfReady() {
        val downloadSnapshot = downloadQueueRestoredChapterIds ?: return
        if (!startupReconciliationOnce.compareAndSet(false, true)) return
        applicationScope.launch {
            translationQueueRestoreJob?.join()
            reconcilePendingRequestsForStartup(downloadQueueChapterIds = downloadSnapshot)
            //  Phase 3: consume interrupted-attempt ledger entries for
            // exactly the bounded chapter set (never a library scan).
            reconcileAttemptLedgersForStartup(
                chapterIds = translator.persistedQueueChapterIds() +
                    pendingTranslationRequestsState.value.keys +
                    pendingRequestStore.load(),
            )
        }
    }

    /**
     * One idempotent, bounded pass over the durable pending requests:
     * - pending + translation-queue member -> queue wins: clear pending;
     * - pending + download-queue member -> normalize to WAITING;
     * - pending + valid downloaded files (no owner) -> admit translation once;
     * - pending + neither -> explicit FAILED (interrupted), never deleted;
     * - chapter/source missing -> purge stale ownership.
     *
     * Race-safe against a concurrently completing download or cancel: the
     * record (with its generation) is re-read at decision time and every
     * durable mutation is fenced on that generation, so a stale decision
     * cannot resurrect a cancelled request or double-admit.
     *
     * Admission intentionally does NOT auto-start OCR/LLM: the chapter lands
     * in the translation queue as QUEUE and the user resumes explicitly,
     * matching the restored-work policy.
     */
    internal suspend fun reconcilePendingRequestsForStartup(
        downloadQueueChapterIds: Set<Long>,
        resolveTranslation: suspend (Long) -> Translation? = { chapterId ->
            Translation.fromChapterId(chapterId)
        },
        hasDownloadedFiles: (Translation) -> Boolean = { translation ->
            downloadProvider.findChapterDir(
                translation.chapter.name,
                translation.chapter.scanlator,
                translation.manga.title,
                translation.source,
            ) != null
        },
    ) {
        val pendingIds = (pendingRequestStore.load() + pendingTranslationRequestsState.value.keys)
            .distinct()
        if (pendingIds.isEmpty()) return
        val translationQueueIds = queueState.value.mapNotNull { it.chapter.id }.toSet()
        pendingIds.forEach { chapterId ->
            val current = pendingTranslationRequestsState.value[chapterId]
                ?: pendingRequestStore.record(chapterId)?.let { record ->
                    TranslationRequestState(
                        chapterId = record.chapterId,
                        phase = record.phase,
                        reason = record.reason,
                        generation = record.generation,
                        failureKind = record.failureKind,
                    )
                }
            if (current == null || current.isTerminal) return@forEach
            val translation = resolveTranslation(chapterId)
            when {
                translation == null -> {
                    logcat(LogPriority.INFO) {
                        "T911 reconcile: purging stale pending request $chapterId (chapter/source missing)"
                    }
                    clearPendingTranslationRequest(chapterId)
                }
                chapterId in translationQueueIds -> {
                    logcat(LogPriority.INFO) {
                        "T911 reconcile: pending $chapterId is owned by the translation queue; clearing pending"
                    }
                    clearPendingTranslationRequest(chapterId)
                }
                chapterId in downloadQueueChapterIds -> {
                    //  hotfix: restore-admitted WAITING request — mark it
                    // one-shot so the download handoff in this process enqueues
                    // PAUSED instead of auto-starting translation.
                    restoreAdmittedMarks().add(chapterId)
                    if (current.phase != TranslationRequestPhase.WAITING_FOR_DOWNLOAD) {
                        setPendingTranslationRequest(
                            chapterId,
                            TranslationRequestPhase.WAITING_FOR_DOWNLOAD,
                        )
                    }
                }
                hasDownloadedFiles(translation) -> {
                    logcat(LogPriority.INFO) {
                        "T911 reconcile: pending $chapterId has downloaded files and no owner; admitting translation"
                    }
                    admitRestoredPendingTranslation(translation, current.generation)
                }
                else -> {
                    logcat(LogPriority.INFO) {
                        "T911 reconcile: pending $chapterId has no queue owner and no files; failing as interrupted"
                    }
                    markTranslationDownloadFailed(
                        chapterId,
                        "Interrupted before the chapter download or translation could run",
                        TranslationRequestFailureKind.INTERRUPTED,
                    )
                }
            }
        }
    }

    /**
     *  Startup reconciliation for the
     * durable attempt ledger. Bounded to the caller-supplied chapter set
     * (persisted translation-queue members ∪ pending request ids ∪ active
     * stores) — NEVER a library scan. Every unresolved entry is a paid call a
     * process death interrupted; each consumes one consecutive attempt for its
     * page. A page reaching the cap records an INTERRUPTED-class durable
     * failure (never auto-retryable) and flips the page PARTIAL, and the
     * chapter's queue entry moves to the existing PAUSED state so the user
     * sees it needs attention.
     */
    internal suspend fun reconcileAttemptLedgersForStartup(
        chapterIds: Set<Long>,
        resolveStore: TranslationStoreResolver = TranslationStoreResolver { chapterId ->
            activeStores.get(chapterId)
        },
    ) {
        if (chapterIds.isEmpty()) return
        chapterIds.forEach { chapterId ->
            val store = resolveStore.resolve(chapterId) ?: return@forEach
            val counters = runCatching { store.consumeUnresolvedAttemptsAtStartup() }
                .onFailure {
                    logcat(LogPriority.WARN) {
                        "T917 D9: startup attempt-ledger consume failed (fail-open): chapterId=$chapterId"
                    }
                }
                .getOrNull() ?: return@forEach
            val cappedPages = counters
                .filterValues { count -> count >= ChapterAttemptLedgerDocument.MAX_CONSECUTIVE_UNRESOLVED }
            if (cappedPages.isEmpty()) return@forEach
            cappedPages.forEach { (pageKey, count) ->
                runCatching { store.applyAttemptCapPause(pageKey, count) }
                    .onFailure {
                        logcat(LogPriority.WARN) {
                            "T917 D9: attempt-cap pause failed (fail-open): " +
                                "chapterId=$chapterId pageKey=$pageKey"
                        }
                    }
            }
            translator.queueState.value
                .firstOrNull { entry ->
                    entry.chapter.id == chapterId && entry.status == Translation.State.QUEUE
                }
                ?.let { entry ->
                    entry.status = Translation.State.PAUSED
                    logcat(LogPriority.INFO) {
                        "T917 D9: chapter paused after repeated interrupted attempts: chapterId=$chapterId"
                    }
                }
        }
    }

    /** Generation-fenced admission of a restored pending request (no auto-start). */
    private fun admitRestoredPendingTranslation(translation: Translation, expectedGeneration: Long) {
        val manga = translation.manga
        val chapter = translation.chapter
        val chapterId = chapter.id ?: return
        if (!admitBatchSession(setOf(chapterId))) return
        synchronized(pendingRequestMutationLock) {
            // The fence accepts the generation from either the live state or
            // the durable record: after a restart the request may exist only
            // durably. A moved generation (cancel/re-request during the pass)
            // drops the admission.
            val current = pendingTranslationRequestsState.value[chapterId]
            val currentGeneration = current?.generation
                ?: pendingRequestStore.record(chapterId)?.generation
            if (currentGeneration != expectedGeneration) return
            scheduler.shutdownAutoCoordinator(chapterId)
            markTranslationRequestPreparing(chapterId)
            translator.queueChapter(manga, chapter)
            if (queueState.value.any { it.chapter.id == chapterId }) {
                //  hotfix: mark the admission one-shot so any later
                // handoff for this chapter cannot auto-start it either.
                restoreAdmittedMarks().add(chapterId)
                clearPendingTranslationRequest(chapterId)
            } else {
                markTranslationQueueFailureIfAcknowledged(manga, chapterId)
            }
        }
        // Deliberately no startTranslation(): restored work never auto-resumes
        // OCR/LLM; the admitted QUEUE entry is resumed by the user.
    }

    //  Phase 18: reader/page teardown bodies moved to
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
            sessionCoordinatorProvider = { sessionCoordinator },
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
        if (!admitBatchSession(queueState.value.mapNotNull { it.chapter.id }.toSet())) return
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
    suspend fun requeueTranslation(chapterId: Long, force: Boolean = false): Boolean {
        if (!admitBatchSession(setOf(chapterId))) return false
        return translator.requeueExisting(chapterId, force)
    }

    fun clearQueue() {
        translator.clearQueue()
        translator.stop()
        clearAllPendingTranslationRequests()
        sessionCoordinator.finishSession()
    }

    fun getQueuedTranslationOrNull(chapterId: Long): Translation? {
        return queueState.value.find { it.chapter.id == chapterId }
    }

    /**
     * S7 / B1 (Milestone M2): Steers the specified chapter to the head of
     * the pending queue.
     */
    fun prioritizeChapter(chapterId: Long) {
        translator.prioritizeChapter(chapterId)
    }

    /**
     * S3 (Milestone M2): Warmed-up engine sessions outside critical path.
     */
    suspend fun warmUp() {
        pipeline.warmUp()
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

    /**
     *  slice 2 (R7): when [expectedRequestGeneration] is supplied, the
     * whole admit sequence runs under the request mutation lock and is
     * aborted when the live request's generation moved (user cancel between
     * check and use). Existing callers keep the unfenced behavior.
     */
    fun translateChapter(
        manga: Manga,
        chapters: Chapter,
        expectedRequestGeneration: Long? = null,
        //  Phase 4: the trigger's admission-probe cross-check; null
        // keeps the legacy no-cross-check path byte-identical.
        admissionContext: eu.kanade.translation.pipeline.batch.BatchAdmissionContext? = null,
        //  hotfix: false for a restore-admitted request handed over by the
        // download completion — enqueue without starting.
        autoStart: Boolean = true,
    ) {
        val chapterId = chapters.id ?: return
        if (!admitBatchSession(setOf(chapterId))) return
        synchronized(pendingRequestMutationLock) {
            if (expectedRequestGeneration != null &&
                pendingTranslationRequestsState.value[chapterId]?.let { it.generation } !=
                expectedRequestGeneration
            ) {
                logcat(LogPriority.INFO) {
                    "T911 dropped stale translate admission for chapter $chapterId " +
                        "(expectedGeneration=$expectedRequestGeneration)"
                }
                return
            }
            scheduler.shutdownAutoCoordinator(chapterId)
            evictStaleQueuedChapters(chapterId, manga.source)
            markTranslationRequestPreparing(chapterId)
            translator.queueChapter(
                manga,
                chapters,
                admissionContext?.probedSourcePageCount,
                admissionContext?.sourceCountKnown ?: false,
            )
            if (queueState.value.any { it.chapter.id == chapterId }) {
                clearPendingTranslationRequest(chapterId)
            } else {
                markTranslationQueueFailureIfAcknowledged(manga, chapterId)
            }
        }
        if (autoStart) {
            //  hotfix: an explicit per-chapter request is the only gate
            // that re-arms a PAUSED/ERROR queue entry (queueChapter is a no-op
            // for an existing entry); a generic queue start must not resurrect
            // that work.
            queueState.value.firstOrNull {
                it.chapter.id == chapterId &&
                    (
                        it.status == Translation.State.PAUSED ||
                            it.status == Translation.State.ERROR
                        )
            }?.status = Translation.State.QUEUE
            startTranslation()
        } else {
            //  hotfix: restore-admitted work is enqueued but stays paused —
            // the UI shows paused, not a fake-active spinner, and no OCR/LLM
            // runs without an explicit user action.
            queueState.value.firstOrNull {
                it.chapter.id == chapterId && it.status == Translation.State.QUEUE
            }?.status = Translation.State.PAUSED
        }
    }

    fun translateChapters(manga: Manga, chapters: List<Chapter>) {
        translateChaptersInternal(manga, chapters, expectedGenerations = null)
    }

    /**
     *  slice 2 (R7): list admission fenced per chapter by the request
     * generation captured by the confirmation probe. Chapters whose request
     * was cancelled or re-requested after the acknowledgement are dropped;
     * the surviving set is admitted atomically against cancel.
     *
     *  Phase 4: [admissionContexts] carries the per-chapter
     * download-probe cross-check for subset admissions; chapters without an
     * entry keep the legacy no-cross-check behavior.
     */
    fun translateChaptersIfCurrent(
        manga: Manga,
        chapters: List<Chapter>,
        expectedGenerations: Map<Long, Long>,
        admissionContexts: Map<Long, eu.kanade.translation.pipeline.batch.BatchAdmissionContext> = emptyMap(),
    ): Boolean {
        if (chapters.isEmpty()) return false
        return translateChaptersInternal(manga, chapters, expectedGenerations, admissionContexts).isNotEmpty()
    }

    private fun translateChaptersInternal(
        manga: Manga,
        chapters: List<Chapter>,
        expectedGenerations: Map<Long, Long>?,
        admissionContexts: Map<Long, eu.kanade.translation.pipeline.batch.BatchAdmissionContext> = emptyMap(),
    ): List<Chapter> {
        if (chapters.isEmpty()) return emptyList()
        if (!admitBatchSession(chapters.mapNotNull { it.id }.toSet())) return emptyList()
        val admitted = mutableListOf<Chapter>()
        synchronized(pendingRequestMutationLock) {
            chapters.forEach { chapter ->
                val chapterId = chapter.id ?: return@forEach
                if (expectedGenerations != null) {
                    val expected = expectedGenerations[chapterId] ?: return@forEach
                    val current = pendingTranslationRequestsState.value[chapterId]
                    if (current == null || current.generation != expected) {
                        logcat(LogPriority.INFO) {
                            "T911 dropped stale translate admission for chapter $chapterId " +
                                "(expectedGeneration=$expected currentGeneration=${current?.generation})"
                        }
                        return@forEach
                    }
                }
                val admissionContext = admissionContexts[chapterId]
                scheduler.shutdownAutoCoordinator(chapterId)
                markTranslationRequestPreparing(chapterId)
                translator.queueChapter(
                    manga,
                    chapter,
                    admissionContext?.probedSourcePageCount,
                    admissionContext?.sourceCountKnown ?: false,
                )
                if (queueState.value.any { it.chapter.id == chapterId }) {
                    clearPendingTranslationRequest(chapterId)
                    //  hotfix: an explicit selection re-arms a PAUSED/ERROR
                    // queue entry (queueChapter is a no-op for an existing
                    // entry); a generic queue start must not resurrect that work.
                    queueState.value.firstOrNull {
                        it.chapter.id == chapterId &&
                            (
                                it.status == Translation.State.PAUSED ||
                                    it.status == Translation.State.ERROR
                                )
                    }?.status = Translation.State.QUEUE
                    admitted += chapter
                } else {
                    markTranslationQueueFailureIfAcknowledged(manga, chapterId)
                }
            }
        }
        if (admitted.isNotEmpty()) {
            startTranslation()
        }
        return admitted
    }

    /**
     *  slice 2 (R10): a translation-queue admission rejection is never a
     * download failure. The phase is [TranslationRequestPhase.ADMISSION_FAILED]
     * with a typed kind (source unsupported / config invalid / admission).
     */
    private fun markTranslationQueueFailureIfAcknowledged(manga: Manga, chapterId: Long) {
        if (pendingTranslationRequestsState.value.containsKey(chapterId)) {
            val failureKind = translationQueueAdmissionFailureKind(
                sourceIsHttp = sourceManager.get(manga.source) is HttpSource,
                configValid = translator.isQueueConfigValid(),
            )
            setPendingTranslationRequest(
                chapterId,
                TranslationRequestPhase.ADMISSION_FAILED,
                "Translation could not be queued",
                failureKind,
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

    // Progress flow orchestration lives in TranslationProgressProjection;
    // page/display/status truth is projected by ChapterTranslationStore.
    private val progressProjection: TranslationProgressProjection
        get() = TranslationProgressProjection(
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
            reconstructDurableTerminalSnapshot = { chapterId ->
                reconstructDurableTerminalSnapshot(chapterId)
            },
        )

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

    /** True when persisted output is readable, including a retry/review-ready warning outcome. */
    //  ANR fix: suspend for the same reason as [persistedChapterStatus]
    // below (durable resolution performs I/O). Currently has no production
    // callers; kept API-compatible.
    suspend fun isChapterTranslated(
        chapterName: String,
        chapterScanlator: String?,
        mangaTitle: String,
        sourceId: Long,
    ): Boolean = persistedChapterStatus(null, chapterName, chapterScanlator, mangaTitle, sourceId)
        .let { it == Translation.State.TRANSLATED || it == Translation.State.READY_WITH_WARNINGS }

    //  Phase 13: durable-status resolution region moved to
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
            durableDocumentCacheProvider = { durableDocumentCache },
        )

    //  ANR fix: suspend — this used to be reached synchronously from the
    // main thread (ReaderViewModel.loadChapter inside withUIContext,
    // MangaScreenModel's chapter-list build) and parked Main in
    // runBlocking(Dispatchers.IO) inside DurableChapterStatusResolver for
    // 5-9s on a large translated chapter. The chain is now fully suspend.
    private suspend fun persistedChapterStatus(
        chapterId: Long?,
        chapterName: String,
        chapterScanlator: String?,
        mangaTitle: String,
        sourceId: Long,
    ): Translation.State? =
        durableStatusResolver.persistedChapterStatus(chapterId, chapterName, chapterScanlator, mangaTitle, sourceId)

    /**
     *  slice 3 (contract item 4): read-through terminal reconstruction.
     * When the bounded tracker registry misses (process death, 20-entry
     * eviction) and no queue owner exists, the projection rebuilds a
     * completed/failed chapter's terminal snapshot from the durable store and
     * artifacts. Read-only by design: it never creates an active store and
     * adds no new cache — the bounded probe registry and the existing
     * durable-status cache are the only intermediaries.
     *
     * [resolveTranslation] is injectable so focused unit tests can drive the
     * gate without the database.
     */
    internal suspend fun reconstructDurableTerminalSnapshot(
        chapterId: Long,
        resolveTranslation: suspend (Long) -> Translation? = { Translation.fromChapterId(it) },
    ): TranslationProgressSnapshot? {
        val translation = resolveTranslation(chapterId) ?: return null
        val chapter = translation.chapter
        val sourceId = translation.manga.source
        val state = persistedChapterStatus(
            chapterId,
            chapter.name,
            chapter.scanlator,
            translation.manga.title,
            sourceId,
        ) ?: return null
        if (!isReconstructibleDurableState(state)) return null
        return durableStatusResolver.withDurableStore(
            chapterId = chapterId,
            chapterName = chapter.name,
            chapterScanlator = chapter.scanlator,
            mangaTitle = translation.manga.title,
            sourceId = sourceId,
        ) { store ->
            TranslationBatchProgressTracker.computeSnapshot(
                chapterId = chapterId,
                chapterState = state,
                pageMap = store.state.value,
                displayPageMap = store.display.value,
                //  Phase 5: trusted totals come from the manifest;
                // a partial download's available pages are never "all pages".
                expectedPageCountTrusted =
                store.artifactManifest?.expectedPageCountTrusted == true,
            ).withDurablePause(store)
        }
    }

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
    ): Map<String, PageTranslation> {
        val entryStage = ReaderEntryTrace.begin("translation.getForReader", chapterId)
        return try {
            withContext(Dispatchers.IO) {
                activeStores.get(chapterId)?.state?.value?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
                val document = findTranslationDocument(chapterName, scanlator, mangaTitle, source)
                    ?: return@withContext emptyMap()
                val manifestProbe = ChapterTranslationStore.probeArtifactManifest(document.parent, document.fileName)
                if (manifestProbe.exists) {
                    return@withContext openExistingChapterTranslationStore(
                        chapterId,
                        chapterName,
                        scanlator,
                        mangaTitle,
                        source,
                        prefoundDocument = document,
                        preflightManifestProbe = manifestProbe,
                    )?.state?.value.orEmpty()
                }
                return@withContext emptyMap()
            }
        } finally {
            entryStage.end()
        }
    }

    fun getChapterTranslation(
        file: UniFile,
    ): Map<String, PageTranslation> = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val manifestProbe = ChapterTranslationStore.probeArtifactManifest(file)
        if (manifestProbe.exists) {
            val parent = file.parentFile ?: return@runBlocking emptyMap()
            val store = activeStores.getOrCreateFile(file.registryKey()) {
                ChapterTranslationStore.openArtifact(parent, file.name ?: "translation.json")
            }
            durableStatusResolver.clearDurableStatusCache()
            return@runBlocking store?.state?.value.orEmpty()
        }
        return@runBlocking emptyMap()
    }

    private suspend fun openExistingChapterTranslationStore(
        chapterId: Long?,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
        prefoundDocument: TranslationDocument? = null,
        preflightManifestProbe: ArtifactManifestProbe? = null,
    ): ChapterTranslationStore? {
        // Callers that already resolved the document and probed the manifest
        // (getChapterTranslationForReader) hand them through — re-running the
        // SAF walks here doubled the cost on every artifact-chapter open.
        val document = prefoundDocument ?: findTranslationDocument(chapterName, scanlator, mangaTitle, source)
            ?: return null
        val manifestProbe = preflightManifestProbe
            ?: ChapterTranslationStore.probeArtifactManifest(document.parent, document.fileName)
        if (!manifestProbe.exists) return null
        val hadActive = if (chapterId != null) {
            activeStores.get(chapterId) != null
        } else {
            activeStores.getByFile(document.registryKey) != null
        }
        val store = if (chapterId != null) {
            activeStores.getOrCreate(chapterId, document.registryKey) {
                ChapterTranslationStore.openArtifact(document.parent, document.fileName)
            }
        } else {
            activeStores.getOrCreateFile(document.registryKey) {
                ChapterTranslationStore.openArtifact(document.parent, document.fileName)
            }
        }
        if (!hadActive) {
            // See the hadActive comment above: only a fresh open can have
            // advanced durable truth (rescue / preservation marker).
            durableStatusResolver.clearDurableStatusCache()
        }
        return store
    }

    //  Phase 13: body moved to manager/DurableChapterStatusResolver.kt.
    // Same-signature stub keeps the call sites.
    private fun findTranslationDocument(
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
    ): TranslationDocument? =
        durableStatusResolver.findTranslationDocument(chapterName, scanlator, mangaTitle, source)

    private fun UniFile.registryKey(): String = filePath ?: uri.toString()

    /** Returns whether this chapter has an existing or active translation store. */
    fun hasTranslationStore(chapter: Chapter, manga: Manga, source: Source): Boolean {
        chapter.id?.let { activeStores.get(it) }?.let { return it.state.value.isNotEmpty() }
        val file = provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source)
            ?: return false
        return ChapterTranslationStore.probeArtifactManifest(file).exists
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
        // Active reader/batch stores publish live state first; unit/probe stores
        // retain the synchronous ChapterTranslationStore default until they are
        // explicitly registered here.
        store.enableLazyPersistence()
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
        // Registry fast path: the live store for this chapter needs no
        // document walk or manifest probe — both ran when it was opened and
        // their result is already baked into the registry entry.
        activeStores.get(chapterId)?.let { registered ->
            registered.enableLazyPersistence()
            scheduleRetiredCleanedImageCleanup(registered, chapterId, chapterName, scanlator, mangaTitle, source, mangaId)
            return registered
        }
        val document = findTranslationDocument(chapterName, scanlator, mangaTitle, source)
        val fileName = document?.fileName ?: provider.getTranslationFileName(chapterName, scanlator)
        val manifestProbe = document?.let {
            ChapterTranslationStore.probeArtifactManifest(it.parent, it.fileName)
        }
        val registered = activeStores.getOrCreate(
            chapterId,
            document?.registryKey,
        ) {
            if (manifestProbe?.exists == true) {
                ChapterTranslationStore.openArtifact(document.parent, fileName)
            } else {
                // Create a lazy artifact store: opening a chapter never
                // creates an empty manifest; the first real write materializes it.
                ChapterTranslationStore.lazy(
                    artifactParent = document?.parent,
                    artifactFileName = fileName,
                    fileCreator = { provider.getMangaDir(mangaTitle, source) },
                )
            }
        } ?: return null
        registered.enableLazyPersistence()
        scheduleRetiredCleanedImageCleanup(registered, chapterId, chapterName, scanlator, mangaTitle, source, mangaId)
        return registered
    }

    //  Phase 16: cleaned-image lifecycle region moved to
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
    ) {
        //   same-chapter auto is suppressed for the WHOLE chapter-batch
        // lifetime (queue entry in QUEUE|TRANSLATING|PAUSED retained state).
        // This legacy auto entry launches real page work, so it must not arm
        // while the chapter's batch is queued; the reader's next window update
        // after the queue drains re-arms normally.
        val requestChapterId = session.chapter.id
        if (requestChapterId != null && isBatchTranslationRetained(requestChapterId)) {
            logcat(LogPriority.INFO) {
                "TachiyomiAT auto window suppressed while the chapter batch is queued: chapterId=$requestChapterId"
            }
            return
        }
        scheduler.requestAutoWindow(session, requests)
    }

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
        when (val admission = sessionCoordinator.requestReaderSession(ReaderSessionIntent(identity.chapterId))) {
            is SessionAdmission.Admitted,
            is SessionAdmission.Switched,
            -> Unit

            is SessionAdmission.Rejected -> {
                logcat(LogPriority.INFO) {
                    "TachiyomiAT reader auto admission rejected: reason=${admission.reason} " +
                        "chapterId=${identity.chapterId}"
                }
                return
            }
        }
        //   same-chapter auto is suppressed for the WHOLE chapter-batch
        // lifetime (queue entry in QUEUE|TRANSLATING|PAUSED retained state).
        // Reader-window updates must not re-arm the rolling coordinator while
        // the chapter's batch is queued; the next update after the queue drains
        // arms normally. `translateChapter`'s one-shot shutdownAutoCoordinator
        // remains what retires an already-live window at admission.
        if (isBatchTranslationRetained(identity.chapterId)) {
            logcat(LogPriority.INFO) {
                "TachiyomiAT auto window suppressed while the chapter batch is queued: chapterId=${identity.chapterId}"
            }
            return
        }
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
        //   a stale window cannot be re-admitted mid-batch — the
        // scheduler's admission hook rejects same-chapter reconcile while the
        // chapter's batch queue entry is retained.
        scheduler.reconcileAutoWindow(admissionGuard = { chapterId -> !isBatchTranslationRetained(chapterId) })
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

    fun observeBatchProgress(
        chapterId: Long,
        durableStateHint: Translation.State? = null,
    ): Flow<TranslationProgressSnapshot> =
        progressProjection.observeBatchProgress(chapterId, durableStateHint)

    fun observeTranslationProgress(chapterId: Long): Flow<TranslationProgressSnapshot> =
        progressProjection.observeTranslationProgress(chapterId)

    fun observePageView(chapterId: Long, pageKey: String): Flow<PageView>? =
        progressProjection.observePageView(chapterId, pageKey)

    // Compatibility seams retained for the paused-affordance characterization
    // tests. Store-backed progress now applies durable pause metadata directly;
    // these pure helpers remain stable for the manager's historical reflective
    // contract and for the durable-terminal reconstruction path below.
    private fun TranslationProgressSnapshot.withDurablePause(
        store: ChapterTranslationStore,
    ): TranslationProgressSnapshot {
        if (state != Translation.State.PAUSED) return this
        val failure = store.durableFailuresSnapshot().values
            .filter { it.status == ArtifactStageStatus.FAILED_RETRYABLE }
            .minWithOrNull(
                compareBy<eu.kanade.translation.artifact.DurableFailureMetadata>(
                    { if (it.category == eu.kanade.translation.artifact.FailureCategory.PROTOCOL) 0 else 1 },
                    { it.stage.ordinal },
                    { it.pageKey },
                ),
            )
            ?: return this
        return copy(
            pauseAnchorPageKey = pauseAnchorPageKey ?: failure.pageKey,
            pauseReason = pauseReason ?: failure.toUiPauseReason(),
            nextEligibleRetryAtEpochMs = nextEligibleRetryAtEpochMs ?: failure.nextEligibleRetryAtEpochMs,
        )
    }

    private fun TranslationProgressSnapshot.projectQueueStatus(
        queueStatus: Translation.State?,
    ): TranslationProgressSnapshot = when (queueStatus) {
        null -> this
        Translation.State.QUEUE -> copy(
            state = queueStatus,
            batchPhase = eu.kanade.translation.model.TranslationBatchPhase.IDLE,
            pauseAnchorPageKey = null,
            pauseReason = null,
            nextEligibleRetryAtEpochMs = null,
        )
        Translation.State.TRANSLATING -> copy(
            state = queueStatus,
            batchPhase = if (batchPhase == eu.kanade.translation.model.TranslationBatchPhase.IDLE) {
                eu.kanade.translation.model.TranslationBatchPhase.FIRST_PASS
            } else {
                batchPhase
            },
        )
        Translation.State.PAUSED -> copy(
            state = queueStatus,
            batchPhase = eu.kanade.translation.model.TranslationBatchPhase.FINISHED,
        )
        else -> copy(state = queueStatus)
    }

    //  Phase 19: the delete/reset region moved to
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
        if (!isAnyBatchTranslationActive && queueState.value.none { it.status == Translation.State.PAUSED }) {
            sessionCoordinator.finishSession(TranslationSessionState.BATCH_SESSION)
        }
    }

    //  Phase 16: body moved to manager/CleanedImageLifecycleController.kt.
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

    //  Phase 18: page-job control bodies moved to
    // manager/ReaderTeardownCoordinator.kt (runBlocking bridge and
    // dispatcher-constraint comments moved with them). Same-signature stubs
    // keep the public seams.

    fun translatePage(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        force: Boolean = false,
    ) = readerTeardown.translatePage(manga, chapter, source, pageKey, force)

    fun cancelPageTranslation(chapterId: Long, pageKey: String): Boolean =
        readerTeardown.cancelPageTranslation(chapterId, pageKey)

    suspend fun cancelPageTranslations(
        chapterId: Long,
        reason: String = "Translation cancelled",
    ) = readerTeardown.cancelPageTranslations(chapterId, reason)

    fun cancelAllPageTranslations(
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
