package eu.kanade.translation.batch

import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.model.AiBatchProgress
import eu.kanade.translation.model.AiPageProgressState
import eu.kanade.translation.model.PageIndexResolver
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageCount
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationProgressStage
import eu.kanade.translation.model.isStageFailed
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.toPageDisplayProjection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serialized, projection-only progress tracker. It never changes [store]; pipeline workers
 * publish store state first, then submit typed events which this tracker reduces in order.
 */
class TranslationBatchProgressTracker(
    private val chapterId: Long,
    private val store: ChapterTranslationStore,
    private val orderedPageKeys: List<String>,
    scope: CoroutineScope,
    private val permitHolderResolver: (() -> String?)? = null,
    private val onTerminalSnapshot: ((TranslationProgressSnapshot) -> Unit)? = null,
) {
    private val events = Channel<TranslationBatchEvent>(Channel.UNLIMITED)
    private var finished = false

    // A terminal event already accepted by the channel must be allowed to
    // drain after replacement so the production identity-fenced callback can
    // observe and reject it. close() still cancels trackers with no terminal
    // work queued, preserving prompt teardown for ordinary replacements.
    private val terminalEventQueued = AtomicBoolean(false)
    private val terminalSnapshot = CompletableDeferred<TranslationProgressSnapshot>()
    private val indexResolver = orderedPageKeys.withIndex().associate { it.value to it.index + 1 }
    private var projection = Projection()
    private val _snapshot = MutableStateFlow(emptySnapshot())
    val snapshot: StateFlow<TranslationProgressSnapshot> = _snapshot.asStateFlow()
    private val reducerJob: Job = scope.launch {
        for (event in events) {
            projection = reduce(projection, event)
            val updatedSnapshot = snapshotFor(projection)
            _snapshot.value = updatedSnapshot
            if (event is TranslationBatchEvent.BatchFinished || event is TranslationBatchEvent.BatchAborted) {
                // Publish immutable terminal state before releasing store/tracker ownership.
                terminalSnapshot.complete(updatedSnapshot)
                onTerminalSnapshot?.invoke(updatedSnapshot)
                finished = true
                events.close()
            }
        }
    }

    private fun emptySnapshot() = TranslationProgressSnapshot.empty(chapterId, Translation.State.TRANSLATING)

    fun rebuildFromStore() {
        if (!finished) _snapshot.value = snapshotFor(projection)
    }

    fun emit(event: TranslationBatchEvent) {
        if (finished || !events.trySend(event).isSuccess) return
    }

    fun markOcrRunning(pageKey: String) = phase(pageKey, BatchPhase.OCR, PhaseStatus.RUNNING)
    fun markOcrDone(pageKey: String) = phase(pageKey, BatchPhase.OCR, PhaseStatus.DONE)
    fun markOcrFailed(pageKey: String, reason: String) = phase(pageKey, BatchPhase.OCR, PhaseStatus.FAILED, reason)
    fun markTranslateRunning(pageKey: String) = phase(pageKey, BatchPhase.TRANSLATE, PhaseStatus.RUNNING)
    fun markTranslateDone(pageKey: String) = phase(pageKey, BatchPhase.TRANSLATE, PhaseStatus.DONE)
    fun markTranslateFailed(
        pageKey: String,
        reason: String,
    ) = phase(pageKey, BatchPhase.TRANSLATE, PhaseStatus.FAILED, reason)
    fun markTranslatePartial(pageKey: String) = phase(pageKey, BatchPhase.TRANSLATE, PhaseStatus.PARTIAL)
    fun markTranslateSkipped(pageKey: String) = phase(pageKey, BatchPhase.TRANSLATE, PhaseStatus.SKIPPED)
    fun markAiPending(pageKey: String) = aiProgress(pageKey, AiPageProgressState.PENDING)
    fun markAiBuffered(pageKey: String) = aiProgress(pageKey, AiPageProgressState.BUFFERED)
    fun markAiRunning(pageKey: String) = aiProgress(pageKey, AiPageProgressState.RUNNING)
    fun markAiSucceeded(pageKey: String) = aiProgress(pageKey, AiPageProgressState.SUCCEEDED)
    fun markAiFailed(pageKey: String, reason: String) = aiProgress(pageKey, AiPageProgressState.FAILED, reason)
    fun markInpaintRunning(pageKey: String) = phase(pageKey, BatchPhase.INPAINT, PhaseStatus.RUNNING)
    fun markInpaintDone(pageKey: String) = phase(pageKey, BatchPhase.INPAINT, PhaseStatus.DONE)
    fun markInpaintFailed(
        pageKey: String,
        reason: String,
    ) = phase(pageKey, BatchPhase.INPAINT, PhaseStatus.FAILED, reason)
    fun markRenderRunning(pageKey: String) = phase(pageKey, BatchPhase.RENDER, PhaseStatus.RUNNING)
    fun markRenderDone(pageKey: String) = phase(pageKey, BatchPhase.RENDER, PhaseStatus.DONE)
    fun markRenderFailed(
        pageKey: String,
        reason: String,
    ) = phase(pageKey, BatchPhase.RENDER, PhaseStatus.FAILED, reason)
    fun markRenderSkipped(pageKey: String) = phase(pageKey, BatchPhase.RENDER, PhaseStatus.SKIPPED)

    private fun phase(pageKey: String, phase: BatchPhase, status: PhaseStatus, reason: String? = null) = emit(
        TranslationBatchEvent.PagePhase(pageKey, indexResolver[pageKey] ?: 0, phase, status, reason = reason),
    )

    private fun aiProgress(
        pageKey: String,
        state: AiPageProgressState,
        reason: String? = null,
    ) = emit(
        TranslationBatchEvent.AiPageProgress(pageKey, indexResolver[pageKey] ?: 0, state, reason),
    )

    fun finish(result: ReconciliationResult) {
        terminalEventQueued.set(true)
        emit(
            TranslationBatchEvent.BatchFinished(
                result.chapterStatus,
                result.doneCount,
                result.failedCount,
                result.partialCount,
                orderedPageKeys.size,
            ),
        )
    }
    fun abort(
        remainingPageKeys: Set<String>,
        reason: String,
    ) {
        terminalEventQueued.set(true)
        emit(TranslationBatchEvent.BatchAborted(reason, remainingPageKeys))
    }
    suspend fun awaitTerminalSnapshot(): TranslationProgressSnapshot = terminalSnapshot.await()
    fun close() {
        finished = true
        events.close()
        if (!terminalEventQueued.get()) reducerJob.cancel()
    }

    private fun snapshotFor(state: Projection): TranslationProgressSnapshot = computeSnapshot(
        // The batch's ordered keys define its work set. Persisted leftovers may be
        // useful diagnostically, but must never inflate progress totals.
        pageMap = orderedPageKeys.distinct().mapNotNull { pageKey ->
            store.state.value[pageKey]?.let { page ->
                pageKey to (
                    state.pagePhases[pageKey]?.entries?.fold(page) { current, (phase, status) ->
                        when (phase) {
                            BatchPhase.OCR -> current.copy(ocrStatus = status)
                            BatchPhase.TRANSLATE -> current.copy(translationStatus = status)
                            BatchPhase.INPAINT -> current.copy(inpaintStatus = status)
                            BatchPhase.RENDER -> current.copy(renderStatus = status)
                            // DISPLAY has no per-page status override: it is
                            // derived from hasRenderedResult in count().
                            BatchPhase.DISPLAY -> current
                        }
                    } ?: page
                    )
            }
        }.toMap(),
        displayPageMap = orderedPageKeys.distinct().mapNotNull { pageKey ->
            store.display.value[pageKey]?.let { pageKey to it }
        }.toMap(),
        chapterState = state.chapterState,
        indexResolver = indexResolver,
        permitHolderPageKey = permitHolderResolver?.invoke(),
        batchPhase = state.batchPhase,
        chapterId = chapterId,
        aiPageStates = state.aiPageStates,
    ).copy(aborted = state.aborted, abortedReason = state.abortReason)

    data class Projection(
        val chapterState: Translation.State = Translation.State.TRANSLATING,
        val batchPhase: TranslationBatchPhase = TranslationBatchPhase.FIRST_PASS,
        val pagePhases: Map<String, Map<BatchPhase, String>> = emptyMap(),
        val aiPageStates: Map<String, AiPageProgressState> = emptyMap(),
        val aborted: Boolean = false,
        val abortReason: String? = null,
    )

    companion object {
        /** Pure reducer. This is the only mutation of progress projection state. */
        fun reduce(previous: Projection, event: TranslationBatchEvent): Projection = when (event) {
            is TranslationBatchEvent.PagePhase -> previous.copy(
                pagePhases = previous.pagePhases + (
                    event.pageKey to (
                        previous.pagePhases[event.pageKey].orEmpty() + (event.phase to event.status.toStageStatus())
                        )
                    ),
            )
            is TranslationBatchEvent.AiPageProgress -> previous.copy(
                aiPageStates = previous.aiPageStates + (
                    event.pageKey to nextAiState(previous.aiPageStates[event.pageKey], event.state)
                    ),
            )
            is TranslationBatchEvent.BatchFinished -> previous.copy(
                chapterState = event.state,
                batchPhase = TranslationBatchPhase.FINISHED,
            )
            is TranslationBatchEvent.BatchAborted -> previous.copy(
                chapterState = Translation.State.ERROR,
                batchPhase = TranslationBatchPhase.FINISHED,
                aborted = true,
                abortReason = event.reason,
            )
            else -> previous
        }

        private fun PhaseStatus.toStageStatus(): String = when (this) {
            PhaseStatus.RUNNING -> StageStatus.RUNNING
            PhaseStatus.DONE -> StageStatus.READY
            PhaseStatus.FAILED -> StageStatus.FAILED
            PhaseStatus.SKIPPED -> StageStatus.SKIPPED
            PhaseStatus.PARTIAL -> StageStatus.PARTIAL
        }

        fun computeSnapshot(
            pageMap: Map<String, PageTranslation>,
            chapterState: Translation.State,
            forcedPartialCount: Int = -1,
            forcedFailedCount: Int = -1,
            forcedDoneCount: Int = -1,
            indexResolver: Map<String, Int>? = null,
            permitHolderPageKey: String? = null,
            aiPageStates: Map<String, AiPageProgressState> = emptyMap(),
            batchPhase: TranslationBatchPhase = if (chapterState ==
                Translation.State.TRANSLATING
            ) {
                TranslationBatchPhase.FIRST_PASS
            } else {
                TranslationBatchPhase.IDLE
            },
            chapterId: Long = 0,
            /** Reader-facing committed pages; defaults to the live map for pure callers. */
            displayPageMap: Map<String, PageTranslation>? = null,
        ): TranslationProgressSnapshot {
            val committedPages = displayPageMap ?: pageMap
            val rows = pageMap.entries.mapIndexed { order, (key, page) ->
                val stage = progressStage(page, committedPages[key])
                val display = page.toPageDisplayProjection(committedPages[key])
                val aiState = aiPageStates[key] ?: inferAiState(page)
                TranslationProgressSnapshot.Page(
                    pageKey = key,
                    index = PageIndexResolver.resolve(key, order, indexResolver),
                    stage = if (permitHolderPageKey !=
                        null &&
                        stage.isRunning &&
                        key != permitHolderPageKey
                    ) {
                        TranslationProgressStage.QUEUED
                    } else {
                        stage
                    },
                    ocrDone = page.ocrStatus == StageStatus.READY,
                    translateDone = page.translationStatus == StageStatus.READY ||
                        page.translationStatus == StageStatus.PARTIAL,
                    inpaintDone = page.inpaintStatus == StageStatus.READY,
                    renderDone = page.renderStatus == StageStatus.READY,
                    errorMessage = page.activeError,
                    displayState = display.state,
                    displayReady = display.displayReady,
                    processed = display.processed,
                    aiState = aiState,
                )
            }.sortedWith(compareBy<TranslationProgressSnapshot.Page> { it.index }.thenBy { it.pageKey })
            val stageCounts = BatchPhase.entries.associateWith { phase ->
                count(pageMap, phase, committedPages)
            }
            val processedStages = stageCounts.values.sumOf { it.processed }
            val activeStages = pageMap.values.flatMap { page -> runningStages(page) }.toSet()
            val failed = if (forcedFailedCount >=
                0
            ) {
                forcedFailedCount
            } else {
                rows.count { it.stage == TranslationProgressStage.FAILED }
            }
            val done = if (forcedDoneCount >=
                0
            ) {
                forcedDoneCount
            } else {
                rows.count { it.stage == TranslationProgressStage.DONE }
            }
            val active =
                rows.firstOrNull { it.stage.isRunning }
                    ?: rows.firstOrNull { it.stage == TranslationProgressStage.QUEUED }
            val aiProgress = AiBatchProgress(
                pending = rows.count { it.aiState == AiPageProgressState.PENDING },
                buffered = rows.count { it.aiState == AiPageProgressState.BUFFERED },
                running = rows.count { it.aiState == AiPageProgressState.RUNNING },
                succeeded = rows.count { it.aiState == AiPageProgressState.SUCCEEDED },
                failed = rows.count { it.aiState == AiPageProgressState.FAILED },
            )
            return TranslationProgressSnapshot(
                chapterId, chapterState, done + failed, rows.size, active?.index ?: 0, active?.pageKey, activeStages,
                rows.count {
                    it.stage ==
                        TranslationProgressStage.QUEUED
                },
                // TachiyomiAT: totalStages must track the live BatchPhase count.
                // Was hardcoded `* 4`, which under-counted after BatchPhase.DISPLAY
                // was added and inflated the fraction past 1.0 (one failed page
                // across 5 phases = 5 processed / 4 total = 1.25).
                failed, rows, processedStages, pageMap.size * BatchPhase.entries.size, stageCounts,
                if (forcedPartialCount >=
                    0
                ) {
                    forcedPartialCount
                } else {
                    pageMap.values.count { it.translationStatus == StageStatus.PARTIAL }
                },
                pageMap.entries.filter {
                    it.value.isStageFailed ||
                        it.value.errorMessage != null
                }.groupBy({
                    it.value.errorMessage ?: "Unknown error"
                }, { it.key }),
                System.currentTimeMillis(),
                batchPhase = batchPhase,
                aiProgress = aiProgress,
            )
        }

        private fun count(
            pages: Map<String, PageTranslation>,
            phase: BatchPhase,
            displayPages: Map<String, PageTranslation> = pages,
        ): StageCount {
            // TachiyomiAT bug 2 fix: RENDER (color estimation) and DISPLAY are
            // reported separately so the indicator stops claiming a page is
            // rendered when only its fill colors were computed. DISPLAY success
            // is derived from the same PageDisplayProjection used by the reader.
            // RENDER stays keyed on renderStatus == READY/PARTIAL (color
            // estimation done).
            if (phase == BatchPhase.DISPLAY) {
                val total = pages.size
                val succeeded = pages.count { (key, page) ->
                    page.toPageDisplayProjection(displayPages[key]).displayReady
                }
                val failed = pages.count { (key, page) ->
                    page.isStageFailed && !page.toPageDisplayProjection(displayPages[key]).displayReady
                }
                val skipped = pages.count { it.value.isTextlessTerminal }
                return StageCount(succeeded, failed, skipped, total)
            }
            val statuses = pages.values.map {
                when (phase) {
                    BatchPhase.OCR -> it.ocrStatus
                    BatchPhase.TRANSLATE -> it.translationStatus
                    BatchPhase.INPAINT -> it.inpaintStatus
                    BatchPhase.RENDER -> it.renderStatus
                    BatchPhase.DISPLAY -> it.renderStatus // unreachable; kept for exhaustiveness
                }
            }
            return StageCount(
                statuses.count { it == StageStatus.READY || it == StageStatus.PARTIAL },
                statuses.count {
                    it ==
                        StageStatus.FAILED
                },
                statuses.count { it == StageStatus.SKIPPED },
                statuses.size,
            )
        }
        private fun runningStages(page: PageTranslation) = buildSet {
            if (page.ocrStatus == StageStatus.RUNNING) add(TranslationProgressStage.OCR)
            if (page.inpaintStatus == StageStatus.RUNNING) add(TranslationProgressStage.INPAINT)
            if (page.translationStatus == StageStatus.RUNNING) add(TranslationProgressStage.TRANSLATE)
            if (page.renderStatus == StageStatus.RUNNING) add(TranslationProgressStage.RENDER)
        }
        private fun progressStage(page: PageTranslation, committed: PageTranslation? = null): TranslationProgressStage = when {
            page.toPageDisplayProjection(committed).displayReady || page.isTextlessTerminal -> TranslationProgressStage.DONE
            page.isStageFailed -> TranslationProgressStage.FAILED
            page.renderStatus == StageStatus.RUNNING -> TranslationProgressStage.RENDER
            page.translationStatus == StageStatus.RUNNING -> TranslationProgressStage.TRANSLATE
            page.inpaintStatus == StageStatus.RUNNING -> TranslationProgressStage.INPAINT
            page.ocrStatus == StageStatus.RUNNING -> TranslationProgressStage.OCR
            else -> TranslationProgressStage.QUEUED
        }

        private fun inferAiState(page: PageTranslation): AiPageProgressState = when {
            page.translationStatus == StageStatus.READY ||
                page.translationStatus == StageStatus.PARTIAL ||
                page.translationStatus == StageStatus.SKIPPED -> AiPageProgressState.SUCCEEDED
            page.translationStatus == StageStatus.FAILED -> AiPageProgressState.FAILED
            page.translationStatus == StageStatus.RUNNING -> AiPageProgressState.RUNNING
            else -> AiPageProgressState.PENDING
        }

        private fun nextAiState(
            previous: AiPageProgressState?,
            next: AiPageProgressState,
        ): AiPageProgressState = if (
            previous == AiPageProgressState.SUCCEEDED || previous == AiPageProgressState.FAILED
        ) {
            previous
        } else {
            next
        }
    }
}
