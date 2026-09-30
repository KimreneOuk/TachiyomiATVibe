package eu.kanade.translation.pipeline.batch.progress

import eu.kanade.translation.model.AiBatchProgress
import eu.kanade.translation.model.AiPageProgressState
import eu.kanade.translation.model.BatchPhase
import eu.kanade.translation.model.BatchRebuildProgress
import eu.kanade.translation.model.PageIndexResolver
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.StageCount
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationProgressStage
import eu.kanade.translation.model.isStageFailed
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.toDraft
import eu.kanade.translation.model.toPageDisplayProjection
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.pipeline.batch.BatchPass1Outcome
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

    // The ordered work keys define the batch's real total, so the
    // first snapshot is derived from them at construction — never from an empty
    // store (a delayed/rejected pre-registration must not project 0/0).
    private val _snapshot = MutableStateFlow(snapshotFor(Projection()))
    val snapshot: StateFlow<TranslationProgressSnapshot> = _snapshot.asStateFlow()
    private val reducerJob: Job = scope.launch {
        for (event in events) {
            projection = reduce(projection, event)
            val updatedSnapshot = snapshotFor(projection)
            _snapshot.value = updatedSnapshot
            if (
                event is TranslationBatchEvent.BatchFinished ||
                event is TranslationBatchEvent.BatchAborted ||
                event is TranslationBatchEvent.BatchPaused
            ) {
                // Publish immutable terminal state before releasing store/tracker ownership.
                terminalSnapshot.complete(updatedSnapshot)
                onTerminalSnapshot?.invoke(updatedSnapshot)
                finished = true
                events.close()
            }
        }
    }

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
    fun markTranslateSkipped(pageKey: String) = phase(pageKey, BatchPhase.TRANSLATE, PhaseStatus.SKIPPED)
    fun markTranslatePaused(pageKey: String, reason: String) =
        phase(pageKey, BatchPhase.TRANSLATE, PhaseStatus.PAUSED, reason)
    fun markAiBuffered(pageKey: String) = aiProgress(pageKey, AiPageProgressState.BUFFERED)
    fun markAiRunning(pageKey: String) = aiProgress(pageKey, AiPageProgressState.RUNNING)
    fun markAiSucceeded(pageKey: String) = aiProgress(pageKey, AiPageProgressState.SUCCEEDED)
    fun markAiFailed(pageKey: String, reason: String) = aiProgress(pageKey, AiPageProgressState.FAILED, reason)
    fun markAiPaused(pageKey: String, reason: String) = aiProgress(pageKey, AiPageProgressState.PAUSED, reason)
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

    //   rebuild-window progress. The coordinator's envelope plan
    // build (resume hydration) fires these through the schedule listener so
    // the projection flips to REBUILDING while the window runs and returns to
    // FIRST_PASS when the plan commits — the sheet's indeterminate bar and
    // store-derived counters stay live instead of freezing for minutes.
    fun markEnvelopePlanStarted(totalPages: Int) =
        emit(TranslationBatchEvent.EnvelopePlanProgress(done = 0, total = totalPages))
    fun markEnvelopePlanProgress(done: Int, total: Int) =
        emit(TranslationBatchEvent.EnvelopePlanProgress(done = done, total = total))
    fun markEnvelopePlanCommitted() = emit(TranslationBatchEvent.EnvelopePlanCommitted)

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
                // The reconciler's non-durable
                // rejection fact must survive into the terminal snapshot.
                nonDurableFailure = result.nonDurableFailure,
                nonDurableFailureReason = result.nonDurableFailure.takeIf { it }?.let {
                    result.pauseReason ?: "Translation not saved — retry required"
                },
            ),
        )
    }

    fun pause(outcome: BatchPass1Outcome, totalPages: Int = orderedPageKeys.size) {
        terminalEventQueued.set(true)
        emit(
            TranslationBatchEvent.BatchPaused(
                anchorPageKey = outcome.anchorPageKey,
                completedPages = outcome.completedPageKeys.size,
                totalPages = totalPages,
                retryableCount = outcome.retryablePageKeys.size.coerceAtLeast(1),
                reason = outcome.reason ?: "Translation paused; retryable provider work remains",
                nextEligibleRetryAtEpochMs = outcome.nextEligibleRetryAtEpochMs,
                retryablePageKeys = outcome.retryablePageKeys,
                // PERSISTENCE_REJECTED is not a
                // durable pause — the snapshot must carry the not-saved warning.
                nonDurableFailure = outcome.isPersistenceRejected,
                nonDurableFailureReason = outcome.reason.takeIf { outcome.isPersistenceRejected },
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

    private fun snapshotFor(state: Projection): TranslationProgressSnapshot {
        val storePages = store.state.value
        return computeSnapshot(
            // The batch's ordered keys define its work set and its
            // total. A known key whose store placeholder was rejected/delayed is
            // projected as a fresh pending placeholder so the total is never
            // silently zero; completed counts still come only from the store
            // intersection (placeholders are queued and claim no completion).
            // Persisted leftovers may be useful diagnostically, but must never
            // inflate progress totals.
            pageMap = orderedPageKeys.distinct().associateWith { pageKey ->
                val stored = storePages[pageKey]
                // This is a progress-only overlay. The temporary draft is never
                // sent back to the authoritative store.
                val base = stored?.toDraft() ?: PageTranslation(sourceFileName = pageKey)
                // TODO(E17b): project phase statuses directly from the read-only view and drop this draft.
                state.pagePhases[pageKey]?.entries?.fold(base) { current, (phase, status) ->
                    when (phase) {
                        BatchPhase.OCR -> current.copy(ocrStatus = status)
                        BatchPhase.TRANSLATE -> current.copy(translationStatus = status)
                        BatchPhase.INPAINT -> current.copy(inpaintStatus = status)
                        BatchPhase.RENDER -> current.copy(renderStatus = status)
                        // DISPLAY has no per-page status override: it is
                        // derived from hasRenderedResult in count().
                        BatchPhase.DISPLAY -> current
                    }
                } ?: base
            },
            displayPageMap = store.display.value.let { displayPages ->
                orderedPageKeys.distinct().mapNotNull { pageKey ->
                    displayPages[pageKey]?.let { pageKey to it }
                }.toMap()
            },
            chapterState = state.chapterState,
            indexResolver = indexResolver,
            permitHolderPageKey = permitHolderResolver?.invoke(),
            batchPhase = state.batchPhase,
            chapterId = chapterId,
            aiPageStates = state.aiPageStates,
            // The batch's registered work set is its
            // trusted total; its aborted remainder is cancelled terminal work.
            cancelledPageKeys = state.cancelledPageKeys,
            expectedPageCountTrusted = true,
        ).copy(
            aborted = state.aborted,
            abortedReason = state.abortReason,
            pauseAnchorPageKey = state.pauseAnchorPageKey,
            pauseReason = state.pauseReason,
            nextEligibleRetryAtEpochMs = state.nextEligibleRetryAtEpochMs,
            // Bounded non-durable publication warning.
            nonDurableFailure = state.nonDurableFailure,
            nonDurableFailureReason = state.nonDurableFailureReason,
            //   the tracker-driven rebuild window carries its own
            // adoption counter alongside the REBUILDING phase.
            rebuildProgress = state.rebuildProgress,
        )
    }

    data class Projection(
        val chapterState: Translation.State = Translation.State.TRANSLATING,
        val batchPhase: TranslationBatchPhase = TranslationBatchPhase.FIRST_PASS,
        val pagePhases: Map<String, Map<BatchPhase, String>> = emptyMap(),
        val aiPageStates: Map<String, AiPageProgressState> = emptyMap(),
        val aborted: Boolean = false,
        val abortReason: String? = null,
        val pauseAnchorPageKey: String? = null,
        val pauseReason: String? = null,
        val nextEligibleRetryAtEpochMs: Long? = null,
        val nonDurableFailure: Boolean = false,
        val nonDurableFailureReason: String? = null,
        //   live envelope-plan rebuild counter while batchPhase is
        // REBUILDING; cleared when the plan commits.
        val rebuildProgress: BatchRebuildProgress? = null,
        /** Keys settled as cancelled by a batch abort. */
        val cancelledPageKeys: Set<String> = emptySet(),
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
                nonDurableFailure = event.nonDurableFailure,
                nonDurableFailureReason = event.nonDurableFailureReason,
            )
            is TranslationBatchEvent.BatchPaused -> previous.copy(
                chapterState = Translation.State.PAUSED,
                batchPhase = TranslationBatchPhase.FINISHED,
                pauseAnchorPageKey = event.anchorPageKey,
                pauseReason = event.reason,
                nextEligibleRetryAtEpochMs = event.nextEligibleRetryAtEpochMs,
                nonDurableFailure = event.nonDurableFailure,
                nonDurableFailureReason = event.nonDurableFailureReason,
            )
            is TranslationBatchEvent.BatchAborted -> previous.copy(
                chapterState = Translation.State.ERROR,
                batchPhase = TranslationBatchPhase.FINISHED,
                aborted = true,
                abortReason = event.reason,
                // The unfinished keys are
                // settled as cancelled terminal work, never fake failures.
                // Historical event field name: the keys the batch could NOT settle are
                // exactly the aborted remainder this projection settles as cancelled.
                cancelledPageKeys = event.failedPageKeys,
            )
            is TranslationBatchEvent.EnvelopePlanProgress -> previous.copy(
                //   the plan-build window is live work — REBUILDING
                // keeps the sheet's indeterminate bar and its store-derived
                // counters moving while the coordinator re-adopts pages.
                batchPhase = TranslationBatchPhase.REBUILDING,
                rebuildProgress = BatchRebuildProgress(
                    restoredPages = event.done.coerceAtLeast(0),
                    totalPages = event.total.coerceAtLeast(0),
                ),
            )
            is TranslationBatchEvent.EnvelopePlanCommitted -> previous.copy(
                //   the plan is durable again — the rebuild window
                // ends and ordinary first-pass projection resumes.
                batchPhase = TranslationBatchPhase.FIRST_PASS,
                rebuildProgress = null,
            )
            //   the when is exhaustive over the sealed event surface
            // again — a future event type must be reduced explicitly here.
        }

        private fun PhaseStatus.toStageStatus(): String = when (this) {
            PhaseStatus.RUNNING -> StageStatus.RUNNING
            PhaseStatus.DONE -> StageStatus.READY
            PhaseStatus.FAILED -> StageStatus.FAILED
            PhaseStatus.SKIPPED -> StageStatus.SKIPPED
            PhaseStatus.PARTIAL -> StageStatus.PARTIAL
            PhaseStatus.PAUSED -> StageStatus.PENDING
        }

        fun computeSnapshot(
            pageMap: Map<String, PageTranslationView>,
            chapterState: Translation.State,
            forcedPartialCount: Int = -1,
            forcedFailedCount: Int = -1,
            forcedDoneCount: Int = -1,
            indexResolver: Map<String, Int>? = null,
            permitHolderPageKey: String? = null,
            aiPageStates: Map<String, AiPageProgressState> = emptyMap(),
            //  restart-retry fix: a durable ERROR chapter is a run that
            // ENDED. Defaulting it to IDLE is why the progress sheet's Retry
            // affordance vanished after an app restart — the truth rule needs
            // ERROR+FINISHED, and only the live tracker used to emit FINISHED.
            //  field fix: a durable READY_WITH_WARNINGS chapter that ended
            // with unresolved pages is the same terminal shape (Chapter 21).
            batchPhase: TranslationBatchPhase = when (chapterState) {
                Translation.State.TRANSLATING -> TranslationBatchPhase.FIRST_PASS
                Translation.State.ERROR,
                Translation.State.READY_WITH_WARNINGS,
                -> TranslationBatchPhase.FINISHED
                else -> TranslationBatchPhase.IDLE
            },
            chapterId: Long = 0,
            /** Reader-facing committed pages; defaults to the live map for pure callers. */
            displayPageMap: Map<String, PageTranslationView>? = null,
            /**
             * Keys the batch settled as
             * cancelled — counted as cancelled terminal work unless the page
             * already reached a real terminal stage.
             */
            cancelledPageKeys: Set<String> = emptySet(),
            /**
             * Whether [TranslationProgressSnapshot.totalPages]
             * is the trusted source total. Defaults to false — trust must be
             * earned from a registered batch work set or a trusted manifest.
             */
            expectedPageCountTrusted: Boolean = false,
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
                    partial = page.translationStatus == StageStatus.PARTIAL,
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
                paused = rows.count { it.aiState == AiPageProgressState.PAUSED },
            )
            // Only unfinished pages count as
            // cancelled — a page that already reached DONE/FAILED keeps its own
            // terminal category and is never double-counted.
            val cancelled = rows.count {
                it.pageKey in cancelledPageKeys &&
                    it.stage != TranslationProgressStage.DONE &&
                    it.stage != TranslationProgressStage.FAILED
            }
            return TranslationProgressSnapshot(
                chapterId, chapterState, done + failed, rows.size, active?.index ?: 0, active?.pageKey, activeStages,
                rows.count {
                    it.stage ==
                        TranslationProgressStage.QUEUED
                },
                // totalStages must track the live BatchPhase count.
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
                //  stranded-page fix: the group key is the page's best
                // available reason ([activeError] = per-stage errors, then the
                // generic errorMessage), not errorMessage alone — a failed
                // page whose reason lives in a stage field used to fall into
                // the generic "Unknown error" bucket in the sheet's failure
                // groups. The INCLUSION set is unchanged (failed or
                // errorMessage-carrying pages only), so a stale stage error on
                // an eventually-successful page never becomes a phantom
                // failure row.
                pageMap.entries.filter {
                    it.value.isStageFailed ||
                        it.value.errorMessage != null
                }.groupBy({
                    it.value.activeError ?: "Unknown error"
                }, { it.key }),
                System.currentTimeMillis(),
                batchPhase = batchPhase,
                aiProgress = aiProgress,
                cancelledPages = cancelled,
                expectedPageCountTrusted = expectedPageCountTrusted,
            )
        }

        private fun count(
            pages: Map<String, PageTranslationView>,
            phase: BatchPhase,
            displayPages: Map<String, PageTranslationView> = pages,
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
        private fun runningStages(page: PageTranslationView) = buildSet {
            if (page.ocrStatus == StageStatus.RUNNING) add(TranslationProgressStage.OCR)
            if (page.inpaintStatus == StageStatus.RUNNING) add(TranslationProgressStage.INPAINT)
            if (page.translationStatus == StageStatus.RUNNING) add(TranslationProgressStage.TRANSLATE)
            if (page.renderStatus == StageStatus.RUNNING) add(TranslationProgressStage.RENDER)
        }
        private fun progressStage(
            page: PageTranslationView,
            committed: PageTranslationView? = null,
        ): TranslationProgressStage = when {
            page.toPageDisplayProjection(committed).displayReady || page.isTextlessTerminal -> TranslationProgressStage.DONE
            page.isStageFailed -> TranslationProgressStage.FAILED
            //  : both surviving lanes commit translations
            // WITHOUT an in-pass render — a page whose translation reached a
            // committed terminal state is this run's DONE even though its
            // display stays ORIGINAL_ONLY until the reader re-derives it.
            // Keying DONE solely on displayReady left every healthy page
            // QUEUED in the terminal snapshot (the deleted legacy render join
            // used to own this settle via markRenderDone).
            page.translationStatus == StageStatus.READY ||
                page.translationStatus == StageStatus.PARTIAL ||
                page.translationStatus == StageStatus.SKIPPED -> TranslationProgressStage.DONE
            page.renderStatus == StageStatus.RUNNING -> TranslationProgressStage.RENDER
            page.translationStatus == StageStatus.RUNNING -> TranslationProgressStage.TRANSLATE
            page.inpaintStatus == StageStatus.RUNNING -> TranslationProgressStage.INPAINT
            page.ocrStatus == StageStatus.RUNNING -> TranslationProgressStage.OCR
            else -> TranslationProgressStage.QUEUED
        }

        private fun inferAiState(page: PageTranslationView): AiPageProgressState = when {
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
            previous == AiPageProgressState.SUCCEEDED ||
            previous == AiPageProgressState.FAILED ||
            previous == AiPageProgressState.PAUSED
        ) {
            previous
        } else {
            next
        }
    }
}
