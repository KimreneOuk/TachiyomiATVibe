package eu.kanade.translation.coordinator

import eu.kanade.tachiyomi.source.Source
import eu.kanade.translation.model.BatchRebuildProgress
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.PageView
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.model.toPageDisplayProjection
import eu.kanade.translation.model.toPageView
import eu.kanade.translation.persistence.ArtifactStageStatus
import eu.kanade.translation.persistence.DurableFailureMetadata
import eu.kanade.translation.persistence.FailureCategory
import eu.kanade.translation.persistence.toUiPauseReason
import eu.kanade.translation.persistence.ActiveChapterStoreRegistry
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.DurableChapterState
import eu.kanade.translation.persistence.DurableChapterAccess
import eu.kanade.translation.persistence.DurableChapterStatusResolver
import eu.kanade.translation.processing.batch.progress.TranslationBatchProgressTracker
import eu.kanade.translation.processing.batch.progress.TranslationBatchTrackerRegistry
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
    durableFailures: Map<String, DurableFailureMetadata>,
): TranslationProgressSnapshot {
    if (state != Translation.State.PAUSED) return this
    // A protocol rejection can happen before translation, such as a page write
    // arriving before page registration. Preserve its retry details
    // so the UI does not misreport a store failure as provider unavailability.
    val failure = durableFailures.values
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

/** Store-backed pause projection; delegates to the pure failure-map overload. */
internal fun TranslationProgressSnapshot.withDurablePause(
    store: ChapterTranslationStore,
): TranslationProgressSnapshot = withDurablePause(store.durableFailuresSnapshot())

/**
 * Terminal snapshot truth for a live store (the registry-hit branch of durable
 * terminal reconstruction): totals from the manifest, pause details from the
 * store's accepted status snapshot.
 */
internal fun ChapterTranslationStore.terminalProgressSnapshot(
    chapterId: Long,
    chapterState: Translation.State,
): TranslationProgressSnapshot = TranslationBatchProgressTracker.computeSnapshot(
    chapterId = chapterId,
    chapterState = chapterState,
    pageMap = state.value,
    displayPageMap = display.value,
    // Trusted totals come from the manifest;
    // a partial download's available pages are never "all pages".
    expectedPageCountTrusted = artifactManifest?.expectedPageCountTrusted == true,
).withDurablePause(durableFailuresSnapshot())

/**
 * Terminal snapshot truth for a publication-free durable read (the registry-
 * miss branch): the same totals and pause rules projected from the opener's
 * verified hydration — no store is constructed for the projection.
 */
internal fun DurableChapterState.terminalProgressSnapshot(
    chapterId: Long,
    chapterState: Translation.State,
): TranslationProgressSnapshot = TranslationBatchProgressTracker.computeSnapshot(
    chapterId = chapterId,
    chapterState = chapterState,
    pageMap = pages,
    displayPageMap = display,
    // Trusted totals come from the manifest;
    // a partial download's available pages are never "all pages".
    expectedPageCountTrusted = manifest.expectedPageCountTrusted,
).withDurablePause(durableFailures)

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

/** Rebuilds a terminal read model from durable truth without opening or caching a writer store. */
internal suspend fun reconstructDurableTerminalSnapshot(
    chapterId: Long,
    resolveTranslation: suspend (Long) -> Translation?,
    durableStatusResolver: DurableChapterStatusResolver,
): TranslationProgressSnapshot? {
    val translation = resolveTranslation(chapterId) ?: return null
    val chapter = translation.chapter
    val sourceId = translation.manga.source
    val state = durableStatusResolver.persistedChapterStatus(
        chapterId,
        chapter.name,
        chapter.scanlator,
        translation.manga.title,
        sourceId,
    ) ?: return null
    if (!isReconstructibleDurableState(state)) return null
    return durableStatusResolver.withDurableRead(
        chapterId = chapterId,
        chapterName = chapter.name,
        chapterScanlator = chapter.scanlator,
        mangaTitle = translation.manga.title,
        sourceId = sourceId,
    ) { access ->
        when (access) {
            is DurableChapterAccess.ActiveStore -> access.store.terminalProgressSnapshot(chapterId, state)
            is DurableChapterAccess.DurableRead -> access.read.terminalProgressSnapshot(chapterId, state)
        }
    }
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
    private val permitHolderPageKeyProvider: () -> String?,
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
    private val observeActiveDisplayStore: (Long) -> StateFlow<Map<String, PageTranslationView>>?,
    /** Read-only terminal reconstruction after registry eviction/process death; it never opens or caches a store. */
    private val reconstructDurableTerminalSnapshot: suspend (chapterId: Long) -> TranslationProgressSnapshot? = { null },
) {

    private val activeStores get() = activeStoresProvider()
    private val batchTrackerRegistry get() = batchTrackerRegistryProvider()
    private val queueState get() = queueStateProvider()
    private val pendingTranslationRequests get() = pendingTranslationRequestsProvider()

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
.distinctUntilChanged()

    /** Adds the chapter's position among outstanding queue entries for reader progress. */
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
    ): TranslationProgressSnapshot = store.progressSnapshot(
        chapterId = chapterId,
        orchestrationState = state,
        permitHolderPageKey = permitHolderPageKeyProvider(),
    )

    fun observePageView(chapterId: Long, pageKey: String): Flow<PageView>? {
        return observeActiveDisplayStore(chapterId)
            ?.map { pages -> pages[pageKey].toPageView() }
            ?.distinctUntilChanged()
    }
}
