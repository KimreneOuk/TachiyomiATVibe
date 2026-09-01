package eu.kanade.translation.manager

import eu.kanade.tachiyomi.source.Source
import eu.kanade.translation.ActiveChapterStoreRegistry
import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.TranslationPipeline
import eu.kanade.translation.artifact.ArtifactStage
import eu.kanade.translation.artifact.ArtifactStageStatus
import eu.kanade.translation.pipeline.batch.TranslationBatchTrackerRegistry
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageView
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.model.toPageDisplayProjection
import eu.kanade.translation.model.toPageView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * T911 slice 2: the chapter's 1-based position among the outstanding
 * translation-queue entries (QUEUE/TRANSLATING), and the total. Null when the
 * chapter is not part of the outstanding work. Pure so the projection and its
 * test agree on what "2nd of 3" means.
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
 * T911 slice 3: whether a durable artifact status is a reconstructible
 * terminal outcome (completed / warnings / failed, or a durable pause with
 * explicit resume detail). Live-looking states (queue/translating) are never
 * reconstructed — a crash mid-run must not look like running work.
 * Pure so the projection and its tests agree on the gate.
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
 * Batch progress projection moved from `TranslationManager` (T909 Phase 11).
 * Pure flow graph — no locks. Manager state arrives as providers and is
 * re-read on every access, matching the manager's per-access construction.
 */
internal class BatchProgressProjector(
    private val activeStoresProvider: () -> ActiveChapterStoreRegistry,
    private val batchTrackerRegistryProvider: () -> TranslationBatchTrackerRegistry,
    private val queueStateProvider: () -> StateFlow<List<Translation>>,
    private val pendingTranslationRequestsProvider: () -> StateFlow<Map<Long, TranslationRequestState>>,
    private val pipelineProvider: () -> TranslationPipeline,
    private val getQueuedTranslationOrNull: (Long) -> Translation?,
    // T912 ANR fix: suspend — durable resolution performs SAF/FUSE I/O and
    // must never be synchronously reachable from the main thread.
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
    /**
     * T911 slice 3: read-through terminal reconstruction from the durable
     * store/artifacts, used when the bounded registry misses (process death /
     * eviction) and no queue owner exists. Null when nothing durable is
     * reconstructible. Read-only: never creates a store, never caches.
     */
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

    // T912 ANR fix: suspend. Priority order and returned states are unchanged
    // (queued translation → active-store display → durable store →
    // NOT_TRANSLATED); only the threading changed. In-memory steps (queue
    // check, active-store shortcut) stay synchronous inside the suspend body.
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
    fun observeBatchProgress(chapterId: Long): Flow<TranslationProgressSnapshot> {
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
                    observeBatchProgressProjection(chapterId, queueStatus)
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
                    val state = queueStatus ?: queued?.status ?: Translation.State.NOT_TRANSLATED
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
                                    combine(resolvedStore.state, resolvedStore.display) { pages, display ->
                                        snapshotFromStore(
                                            chapterId = chapterId,
                                            state = state,
                                            store = resolvedStore,
                                            pages = pages,
                                            display = display,
                                        )
                                    },
                                )
                            }
                        }
                    } else if (store == null) {
                        flow {
                            // T911 slice 3: registry miss and no queue owner —
                            // reconstruct a completed/failed chapter's terminal
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
                        combine(store.state, store.display) { pages, display ->
                            snapshotFromStore(
                                chapterId = chapterId,
                                state = state,
                                store = store,
                                pages = pages,
                                display = display,
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
        .distinctUntilChanged()

    /**
     * T911 slice 2: attach the chapter's truthful position among the
     * outstanding translation-queue entries so a later chapter's drawer can
     * say "Queued (2nd of 3)" instead of implying it can resume now.
     */
    private fun TranslationProgressSnapshot.withQueuePosition(
        chapterId: Long,
    ): TranslationProgressSnapshot {
        if (state != Translation.State.QUEUE) return this
        val (position, total) = translationQueuePosition(queueState.value, chapterId) ?: return this
        return copy(queuePosition = position, queueTotal = total)
    }

    private fun snapshotFromStore(
        chapterId: Long,
        state: Translation.State,
        store: ChapterTranslationStore,
        pages: Map<String, PageTranslation>,
        display: Map<String, PageTranslation>,
    ): TranslationProgressSnapshot = TranslationProgressSnapshot.compute(
        chapterId = chapterId,
        state = state,
        pageMap = pages,
        displayPageMap = display,
        permitHolderPageKey = pipeline.permitHolderPageKeySnapshot(),
    ).withDurablePause(store)

    private fun TranslationProgressSnapshot.withDurablePause(
        store: ChapterTranslationStore,
    ): TranslationProgressSnapshot {
        if (state != Translation.State.PAUSED) return this
        val failure = store.durableFailuresSnapshot().values
            .firstOrNull {
                it.stage == ArtifactStage.TRANSLATION &&
                    it.status == ArtifactStageStatus.FAILED_RETRYABLE
            }
            ?: return this
        return copy(
            pauseAnchorPageKey = pauseAnchorPageKey ?: failure.pageKey,
            pauseReason = pauseReason ?: failure.lastFailureMessage,
            nextEligibleRetryAtEpochMs = nextEligibleRetryAtEpochMs ?: failure.nextEligibleRetryAtEpochMs,
        )
    }

    /** Bridge for the manager's reflection-pinned same-name stub (paused-affordance test). */
    internal fun withDurablePauseOf(
        snapshot: TranslationProgressSnapshot,
        store: ChapterTranslationStore,
    ): TranslationProgressSnapshot = snapshot.withDurablePause(store)

    /** Bridge for the manager's reflection-pinned same-name stub (paused-affordance test). */
    internal fun projectQueueStatusOf(
        snapshot: TranslationProgressSnapshot,
        queueStatus: Translation.State?,
    ): TranslationProgressSnapshot = snapshot.projectQueueStatus(queueStatus)

    private fun TranslationProgressSnapshot.projectQueueStatus(
        queueStatus: Translation.State?,
    ): TranslationProgressSnapshot {
        return when (queueStatus) {
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
    }

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
