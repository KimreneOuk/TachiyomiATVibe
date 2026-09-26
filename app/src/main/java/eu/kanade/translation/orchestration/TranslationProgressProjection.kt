package eu.kanade.translation.orchestration

import eu.kanade.tachiyomi.source.Source
import eu.kanade.translation.persistence.artifact.ArtifactStageStatus
import eu.kanade.translation.persistence.artifact.ChapterRunRecord
import eu.kanade.translation.persistence.artifact.ChapterRunState
import eu.kanade.translation.persistence.artifact.DurableFailureMetadata
import eu.kanade.translation.persistence.artifact.FailureCategory
import eu.kanade.translation.persistence.artifact.toUiPauseReason
import eu.kanade.translation.model.BatchRebuildProgress
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageView
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.model.toPageDisplayProjection
import eu.kanade.translation.model.toPageView
import eu.kanade.translation.pipeline.TranslationPipeline
import eu.kanade.translation.pipeline.batch.ChapterProfileBatchCoordinator
import eu.kanade.translation.pipeline.batch.TranslationBatchProgressTracker
import eu.kanade.translation.pipeline.batch.TranslationBatchTrackerRegistry
import eu.kanade.translation.persistence.chapter.ActiveChapterStoreRegistry
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

/** Minimum interval between durable run-record probes for a live chapter flow. */
private const val REBUILD_PROBE_MIN_INTERVAL_MS = 500L

/** Builds reader and manga progress from the live page and committed display state. */
internal fun ChapterTranslationStore.progressSnapshot(
    chapterId: Long,
    orchestrationState: Translation.State,
    permitHolderPageKey: String?,
): TranslationProgressSnapshot {
    val projectedState = when (orchestrationState) {
        Translation.State.QUEUE,
        Translation.State.TRANSLATING,
        -> orchestrationState
        else -> artifactStatus() ?: orchestrationState
    }
    val snapshot = TranslationBatchProgressTracker.computeSnapshot(
        chapterId = chapterId,
        chapterState = projectedState,
        pageMap = state.value,
        displayPageMap = display.value,
        permitHolderPageKey = permitHolderPageKey,
        expectedPageCountTrusted = artifactManifest?.expectedPageCountTrusted == true,
    )
    return snapshot.withDurablePause(this)
}

/** Adds the durable retry details that explain a paused chapter. */
internal fun TranslationProgressSnapshot.withDurablePause(
    store: ChapterTranslationStore,
): TranslationProgressSnapshot {
    if (state != Translation.State.PAUSED) return this
    // A protocol rejection can happen before translation, such as an OCR
    // checkpoint arriving before page registration. Preserve its retry details
    // so the UI does not misreport a store failure as provider unavailability.
    val failure = store.durableFailuresSnapshot().values
        .filter { it.status == ArtifactStageStatus.FAILED_RETRYABLE }
        .minWithOrNull(
            compareBy<DurableFailureMetadata>(
                { if (it.category == FailureCategory.PROTOCOL) 0 else 1 },
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

/** Projects a queue state onto a progress snapshot without changing its page truth. */
internal fun TranslationProgressSnapshot.projectQueueStatus(
    queueStatus: Translation.State?,
): TranslationProgressSnapshot = when (queueStatus) {
    null -> this
    Translation.State.QUEUE -> copy(
        state = queueStatus,
        batchPhase = TranslationBatchPhase.IDLE,
        pauseAnchorPageKey = null,
        pauseReason = null,
        nextEligibleRetryAtEpochMs = null,
    )
    Translation.State.TRANSLATING -> copy(
        state = queueStatus,
        batchPhase = if (batchPhase == TranslationBatchPhase.IDLE) {
            TranslationBatchPhase.FIRST_PASS
        } else {
            batchPhase
        },
    )
    Translation.State.PAUSED -> copy(
        state = queueStatus,
        batchPhase = TranslationBatchPhase.FINISHED,
    )
    else -> copy(state = queueStatus)
}

/**
 * Pure rebuild/restore truth derived from the durable run record.
 *
 * The resume-rebuild window is the run record's preflight preamble:
 *
 *  - `RUN_SNAPSHOT` / `SOURCE_VALIDATION`: the resumed run re-validates its
 *    frozen configuration and sources → [TranslationBatchPhase.REBUILDING]
 *    ("Rebuilding pipeline…").
 *  - `OCR_PLAN` with `ocrPagesDone > 0`: the coordinator re-adopts durable
 *    page work and republishes its counters per adopted page →
 *    [TranslationBatchPhase.RESTORING] with the restored/total payload.
 *
 * `OCR_PLAN` with a zero done count is deliberately NOT a rebuild: a FIRST
 * run also publishes `OCR_PLAN` before its whole fresh OCR pass and never
 * republishes it until the pass ends, so a zero count cannot distinguish a
 * resume preamble from ordinary fresh OCR — never fake rebuild copy over a
 * normal first translation. Every post-preflight state (OCR_PREFLIGHT and
 * later, terminal states included) is running/terminal work → null (normal
 * copy).
 */
internal fun rebuildTruthFromRunRecord(
    record: ChapterRunRecord?,
): Pair<TranslationBatchPhase, BatchRebuildProgress?>? {
    if (record == null) return null
    val total = record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_TOTAL] ?: 0
    val done = record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_DONE] ?: 0
    return when (record.state) {
        ChapterRunState.RUN_SNAPSHOT,
        ChapterRunState.SOURCE_VALIDATION,
        -> TranslationBatchPhase.REBUILDING to BatchRebuildProgress(
            restoredPages = done.coerceAtLeast(0),
            totalPages = total.coerceAtLeast(0),
        )
        ChapterRunState.OCR_PLAN -> {
            if (total <= 0 || done <= 0) return null
            TranslationBatchPhase.RESTORING to BatchRebuildProgress(
                restoredPages = done.coerceAtMost(total),
                totalPages = total,
            )
        }
        //   the envelope plan-build window. The run record parks in
        // ENVELOPE_PLAN while the coordinator rebuilds the dispatch work (the
        // resume hydration loop) — without a branch this window projected null
        // and the sheet froze on a stale numeric hero. REBUILDING (no rendered
        // counter) also keeps the record-driven stamp from fighting the live
        // tracker's own EnvelopePlanProgress events during the same window.
        ChapterRunState.ENVELOPE_PLAN -> TranslationBatchPhase.REBUILDING to BatchRebuildProgress(
            restoredPages = done.coerceAtLeast(0),
            totalPages = total.coerceAtLeast(0),
        )
        else -> null
    }
}

/**
 * Stamps run-record rebuild truth onto a snapshot, but only
 * inside the live FIRST_PASS window (a TRANSLATING chapter whose phase is
 * FIRST_PASS or already a rebuild phase). Terminal snapshots, queued
 * chapters, and pauses are never restamped, so the rebuild phase can never
 * outlive the run that produced it.
 */
internal fun TranslationProgressSnapshot.withRunRecordTruth(
    record: ChapterRunRecord?,
): TranslationProgressSnapshot {
    val truth = rebuildTruthFromRunRecord(record) ?: return this
    val liveRunWindow = state == Translation.State.TRANSLATING &&
        (
            batchPhase == TranslationBatchPhase.FIRST_PASS ||
                batchPhase == TranslationBatchPhase.REBUILDING ||
                batchPhase == TranslationBatchPhase.RESTORING
            )
    if (!liveRunWindow) return this
    return copy(
        batchPhase = truth.first,
        rebuildProgress = truth.second,
    )
}

/**
 * Returns the chapter's 1-based position among outstanding QUEUE/TRANSLATING entries and the
 * total. Returns null when the chapter is not part of outstanding work.
 */
internal fun translationQueuePosition(
    queue: List<Translation>,
    chapterId: Long,
): Pair<Int, Int>? {
    val outstanding = queue.filter { translation ->
        translation.status == Translation.State.QUEUE ||
            translation.status == Translation.State.TRANSLATING
    }
    val index = outstanding.indexOfFirst { it.chapter.id == chapterId }
    if (index < 0) return null
    return (index + 1) to outstanding.size
}

/**
 * Whether a durable artifact status is a reconstructible terminal outcome. Live queue and
 * translation states are never reconstructed; a crash mid-run must not look like active work.
 */
internal fun isReconstructibleDurableState(state: Translation.State?): Boolean = when (state) {
    Translation.State.TRANSLATED,
    Translation.State.READY_WITH_WARNINGS,
    Translation.State.ERROR,
    Translation.State.PAUSED,
    -> true
    else -> false
}

/**
 * Reader-facing flow graph for progress projection.
 *
 * The store owns page/display/status truth; this class only combines queue,
 * tracker, and request orchestration around that unified store projection.
 * Orchestration state arrives through providers so the projection can combine existing owners
 * without taking ownership of their stores or scopes.
 */
internal class TranslationProgressProjection(
    private val activeStoresProvider: () -> ActiveChapterStoreRegistry,
    private val batchTrackerRegistryProvider: () -> TranslationBatchTrackerRegistry,
    private val queueStateProvider: () -> StateFlow<List<Translation>>,
    private val pendingTranslationRequestsProvider: () -> StateFlow<Map<Long, TranslationRequestState>>,
    private val pipelineProvider: () -> TranslationPipeline,
    private val getQueuedTranslationOrNull: (Long) -> Translation?,
    // Durable resolution performs SAF/FUSE I/O, so keep the call suspendable for reader/UI users.
    private val persistedChapterStatus: suspend (
        chapterId: Long?,
        chapterName: String,
        chapterScanlator: String?,
        mangaTitle: String,
        sourceId: Long,
    ) -> Translation.State?,
    private val openOrCreateStoreSuspend: suspend (
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
        mangaId: Long?,
    ) -> ChapterTranslationStore?,
    private val observeActiveDisplayStore: (Long) -> StateFlow<Map<String, PageTranslation>>?,
    /** Read-only terminal reconstruction after registry eviction/process death; it never opens or caches a store. */
    private val reconstructDurableTerminalSnapshot: suspend (chapterId: Long) -> TranslationProgressSnapshot? = { null },
) {

    private val activeStores get() = activeStoresProvider()
    private val batchTrackerRegistry get() = batchTrackerRegistryProvider()
    private val queueState get() = queueStateProvider()
    private val pendingTranslationRequests get() = pendingTranslationRequestsProvider()
    private val pipeline get() = pipelineProvider()

    private suspend fun openOrCreateActiveChapterTranslationStoreSuspend(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
        mangaId: Long? = null,
    ): ChapterTranslationStore? =
        openOrCreateStoreSuspend(chapterId, chapterName, scanlator, mangaTitle, source, mangaId)

    // Keep queue and active-store checks synchronous; only the durable SAF lookup suspends on IO.
    suspend fun getChapterTranslationStatus(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        title: String,
        sourceId: Long,
    ): Translation.State {
        val translation = getQueuedTranslationOrNull(chapterId)
        if (translation != null) return translation.status
        activeStores.get(chapterId)?.let { store ->
            // The reader-facing committed projection is authoritative. A live
            // OCR/translation candidate must not make the chapter appear ready
            // or hide an older committed bundle while it is being refreshed.
            val pages = store.display.value
            if (pages.values.any { it.toPageDisplayProjection().displayReady }) {
                return store.artifactStatus() ?: Translation.State.READY_WITH_WARNINGS
            }
        }
        return persistedChapterStatus(chapterId, chapterName, scanlator, title, sourceId)
            ?: Translation.State.NOT_TRANSLATED
    }

    fun observeChapterTranslationStatus(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        title: String,
        sourceId: Long,
    ): Flow<Translation.State> {
        val queueStatusFlow = queueState.map { queue ->
            queue.find { it.chapter.id == chapterId }?.status
        }.distinctUntilChanged()

        val activeStoreStateFlow = activeStores.snapshots.flatMapLatest { map ->
            val store = map[chapterId]
            if (store != null) {
                combine(store.state, store.display) { _, _ ->
                    getChapterTranslationStatus(chapterId, chapterName, scanlator, title, sourceId)
                }
            } else {
                kotlinx.coroutines.flow.flowOf(null)
            }
        }.distinctUntilChanged()

        return kotlinx.coroutines.flow.combine(queueStatusFlow, activeStoreStateFlow) { qStatus, diskStatus ->
            qStatus ?: diskStatus ?: getChapterTranslationStatus(chapterId, chapterName, scanlator, title, sourceId)
        }.distinctUntilChanged()
    }

    /**
     * Live batch progress for [chapterId]. This is the one projection shared
     * by manga, reader, and notification surfaces. It starts with an immediate
     * request acknowledgement, follows queue status changes, then switches to
     * the live tracker or shared store without consulting download-cache state.
     */
    fun observeBatchProgress(
        chapterId: Long,
        durableStateHint: Translation.State? = null,
    ): Flow<TranslationProgressSnapshot> {
        val pending = pendingTranslationRequests
            .map { requests -> requests[chapterId] }
            .distinctUntilChanged()
        return combine(pending, observeQueuedTranslationStatus(chapterId)) { request, queueStatus ->
            request to queueStatus
        }
            .flatMapLatest { (request, queueStatus) ->
                if (request != null && queueStatus == null) {
                    flowOf(
                        TranslationProgressSnapshot.empty(chapterId).copy(
                            requestState = request,
                        ),
                    )
                } else {
                    observeBatchProgressProjection(chapterId, queueStatus, durableStateHint)
                        .map { snapshot -> snapshot.copy(requestState = null) }
                }
            }
            .distinctUntilChanged()
    }

    private fun observeQueuedTranslationStatus(chapterId: Long): Flow<Translation.State?> =
        queueState
            .flatMapLatest { queue ->
                val translation = queue.firstOrNull { it.chapter.id == chapterId }
                if (translation == null) {
                    flowOf(null)
                } else {
                    translation.statusFlow
                        .drop(1)
                        .map { it }
                        .onStart { emit(translation.status) }
                }
            }
            .distinctUntilChanged()

    private fun observeBatchProgressProjection(
        chapterId: Long,
        queueStatus: Translation.State?,
        durableStateHint: Translation.State? = null,
    ): Flow<TranslationProgressSnapshot> = batchTrackerRegistry.live
        .flatMapLatest { trackers ->
            val tracker = trackers[chapterId]
            if (tracker != null) {
                tracker.snapshot
            } else {
                val terminal = batchTrackerRegistry.terminal.value[chapterId]
                if (terminal != null) {
                    flowOf(terminal)
                } else {
                    val queued = getQueuedTranslationOrNull(chapterId)
                    //  restart-retry fix: after a restart there is no queue
                    // entry and no live tracker, so the fallback used to hard-
                    // code NOT_TRANSLATED — hiding a durably FAILED chapter's
                    // terminal state from the sheet (and its Retry affordance).
                    // The caller's projected persisted state is the honest
                    // fallback; a queued/live status always wins.
                    val state = queueStatus
                        ?: queued?.status
                        ?: durableStateHint
                        ?: Translation.State.NOT_TRANSLATED
                    val store = activeStores.get(chapterId)
                    if (store == null && queued != null) {
                        flow {
                            val resolvedStore = openOrCreateActiveChapterTranslationStoreSuspend(
                                chapterId = chapterId,
                                chapterName = queued.chapter.name,
                                scanlator = queued.chapter.scanlator,
                                mangaTitle = queued.manga.title,
                                source = queued.source,
                                mangaId = queued.manga.id,
                            )
                            if (resolvedStore == null) {
                                emit(TranslationProgressSnapshot.empty(chapterId, state))
                            } else {
                                emitAll(
                                    combine(resolvedStore.state, resolvedStore.display) { _, _ ->
                                        snapshotFromStore(
                                            chapterId = chapterId,
                                            state = state,
                                            store = resolvedStore,
                                        )
                                    },
                                )
                            }
                        }
                    } else if (store == null) {
                        flow {
                            // Registry miss with no queue owner — reconstruct a completed/failed chapter's terminal
                            // detail from the durable store/artifacts so
                            // re-entry and eviction keep truthful totals and
                            // reasons. Read-through only: no store is created
                            // and nothing is cached here.
                            emit(
                                reconstructDurableTerminalSnapshot(chapterId)
                                    ?: TranslationProgressSnapshot.empty(chapterId, state),
                            )
                        }
                    } else {
                        combine(store.state, store.display) { _, _ ->
                            snapshotFromStore(
                                chapterId = chapterId,
                                state = state,
                                store = store,
                            )
                        }
                    }
                }
            }
        }
        .map { snapshot ->
            snapshot
                .projectQueueStatus(queueStatus)
                .withQueuePosition(chapterId)
        }
        // Live snapshots are stamped with the durable run record's
        // rebuild/restore truth (bounded probe; see withRunRecordRebuildTruth).
        .withRunRecordRebuildTruth(chapterId)
        .distinctUntilChanged()

    /** Adds the chapter's position among outstanding queue entries for reader progress. */
    private fun TranslationProgressSnapshot.withQueuePosition(
        chapterId: Long,
    ): TranslationProgressSnapshot {
        if (state != Translation.State.QUEUE) return this
        val (position, total) = translationQueuePosition(queueState.value, chapterId) ?: return this
        return copy(queuePosition = position, queueTotal = total)
    }

    /**
     * Augments live snapshots with the active run record's
     * rebuild/restore truth. Probes are bounded: they run only while the
     * snapshot shows a live FIRST_PASS/rebuild window, re-arm immediately
     * when such a window (re)starts so a new run never renders a previous
     * run's truth, and otherwise run at most once per
     * [REBUILD_PROBE_MIN_INTERVAL_MS]. The record read is small sidecar
     * I/O and is confined to [Dispatchers.IO] — the projection itself stays
     * pure for the collector's context.
     */
    private fun Flow<TranslationProgressSnapshot>.withRunRecordRebuildTruth(
        chapterId: Long,
    ): Flow<TranslationProgressSnapshot> = flow {
        var nextProbeAllowedAtMs = 0L
        var cachedRecord: ChapterRunRecord? = null
        var wasLiveRun = false
        collect { snapshot ->
            val liveRun = snapshot.state == Translation.State.TRANSLATING &&
                (
                    snapshot.batchPhase == TranslationBatchPhase.FIRST_PASS ||
                        snapshot.batchPhase == TranslationBatchPhase.REBUILDING ||
                        snapshot.batchPhase == TranslationBatchPhase.RESTORING
                    )
            if (liveRun) {
                val now = System.currentTimeMillis()
                if (!wasLiveRun) nextProbeAllowedAtMs = 0L
                if (now >= nextProbeAllowedAtMs) {
                    nextProbeAllowedAtMs = now + REBUILD_PROBE_MIN_INTERVAL_MS
                    cachedRecord = withContext(Dispatchers.IO) {
                        readActiveRunRecord(chapterId)
                    }
                }
                emit(snapshot.withRunRecordTruth(cachedRecord))
            } else {
                cachedRecord = null
                emit(snapshot)
            }
            wasLiveRun = liveRun
        }
    }

    /** Read-only look at the chapter's durable active run record, if any. */
    private fun readActiveRunRecord(chapterId: Long): ChapterRunRecord? {
        val store = activeStores.get(chapterId) ?: return null
        return store.readActiveRunRecord()
    }

    private fun snapshotFromStore(
        chapterId: Long,
        state: Translation.State,
        store: ChapterTranslationStore,
    ): TranslationProgressSnapshot = store.progressSnapshot(
        chapterId = chapterId,
        orchestrationState = state,
        permitHolderPageKey = pipeline.permitHolderPageKeySnapshot(),
    )

    /**
     * Per-chapter batch progress (done/total) for the manga-screen chapter-list indicator, so
     * the user can watch pre-translation advance without opening the reader. Emits the active
     * store's page-count progress; empty when no active store exists (no batch in flight).
     */
    fun observeTranslationProgress(chapterId: Long): Flow<TranslationProgressSnapshot> {
        return observeBatchProgress(chapterId)
    }

    fun observePageView(chapterId: Long, pageKey: String): Flow<PageView>? {
        return observeActiveDisplayStore(chapterId)
            ?.map { pages -> pages[pageKey].toPageView() }
            ?.distinctUntilChanged()
    }
}
