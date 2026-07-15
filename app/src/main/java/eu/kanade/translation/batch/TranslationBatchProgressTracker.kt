package eu.kanade.translation.batch

import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.model.PageIndexResolver
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.RevisionProgress
import eu.kanade.translation.model.StageCount
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationProgressStage
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isStageFailed
import eu.kanade.translation.model.isStageRunning
import eu.kanade.translation.model.isTextlessTerminal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    fun beginRevision(totalBlocks: Int, skippedBlocks: Int = 0, userEditedBlocks: Int = 0) = emit(TranslationBatchEvent.RevisionStarted(totalBlocks, skippedBlocks, userEditedBlocks))
    fun markRevisionChunkRunning(pageKeys: Collection<String>, blockCount: Int) = emit(TranslationBatchEvent.RevisionChunkRunning(pageKeys.toSet(), blockCount))
    fun markRevisionChunkCompleted(completedBlocks: Int) = emit(TranslationBatchEvent.RevisionChunkCompleted(completedBlocks))
    fun markRevisionChunkFailed(failedBlocks: Int) = emit(TranslationBatchEvent.RevisionChunkFailed(failedBlocks))
    fun markRevisionFinished() = emit(TranslationBatchEvent.RevisionFinished)

    fun markOcrRunning(pageKey: String) = phase(pageKey, BatchPhase.OCR, PhaseStatus.RUNNING)
    fun markOcrDone(pageKey: String) = phase(pageKey, BatchPhase.OCR, PhaseStatus.DONE)
    fun markOcrFailed(pageKey: String, reason: String) = phase(pageKey, BatchPhase.OCR, PhaseStatus.FAILED, reason)
    fun markTranslateRunning(pageKey: String) = phase(pageKey, BatchPhase.TRANSLATE, PhaseStatus.RUNNING)
    fun markTranslateDone(pageKey: String) = phase(pageKey, BatchPhase.TRANSLATE, PhaseStatus.DONE)
    fun markTranslateFailed(pageKey: String, reason: String) = phase(pageKey, BatchPhase.TRANSLATE, PhaseStatus.FAILED, reason)
    fun markTranslatePartial(pageKey: String) = phase(pageKey, BatchPhase.TRANSLATE, PhaseStatus.PARTIAL)
    fun markTranslateSkipped(pageKey: String) = phase(pageKey, BatchPhase.TRANSLATE, PhaseStatus.SKIPPED)
    fun markInpaintRunning(pageKey: String) = phase(pageKey, BatchPhase.INPAINT, PhaseStatus.RUNNING)
    fun markInpaintDone(pageKey: String) = phase(pageKey, BatchPhase.INPAINT, PhaseStatus.DONE)
    fun markInpaintFailed(pageKey: String, reason: String) = phase(pageKey, BatchPhase.INPAINT, PhaseStatus.FAILED, reason)
    fun markRenderRunning(pageKey: String) = phase(pageKey, BatchPhase.RENDER, PhaseStatus.RUNNING)
    fun markRenderDone(pageKey: String) = phase(pageKey, BatchPhase.RENDER, PhaseStatus.DONE)
    fun markRenderFailed(pageKey: String, reason: String) = phase(pageKey, BatchPhase.RENDER, PhaseStatus.FAILED, reason)
    fun markRenderSkipped(pageKey: String) = phase(pageKey, BatchPhase.RENDER, PhaseStatus.SKIPPED)

    private fun phase(pageKey: String, phase: BatchPhase, status: PhaseStatus, reason: String? = null) = emit(
        TranslationBatchEvent.PagePhase(pageKey, indexResolver[pageKey] ?: 0, phase, status, reason = reason),
    )

    fun finish(result: ReconciliationResult) = emit(TranslationBatchEvent.BatchFinished(result.chapterStatus, result.doneCount, result.failedCount, result.partialCount, orderedPageKeys.size))
    fun abort(remainingPageKeys: Set<String>, reason: String) = emit(TranslationBatchEvent.BatchAborted(reason, remainingPageKeys))
    suspend fun awaitTerminalSnapshot(): TranslationProgressSnapshot = terminalSnapshot.await()
    fun close() { finished = true; events.close(); reducerJob.cancel() }

    private fun snapshotFor(state: Projection): TranslationProgressSnapshot = computeSnapshot(
        // The batch's ordered keys define its work set. Persisted leftovers may be
        // useful diagnostically, but must never inflate progress totals.
        pageMap = orderedPageKeys.distinct().mapNotNull { pageKey ->
            store.state.value[pageKey]?.let { page ->
                pageKey to (state.pagePhases[pageKey]?.entries?.fold(page) { current, (phase, status) ->
                    when (phase) {
                        BatchPhase.OCR -> current.copy(ocrStatus = status)
                        BatchPhase.TRANSLATE -> current.copy(translationStatus = status)
                        BatchPhase.INPAINT -> current.copy(inpaintStatus = status)
                        BatchPhase.RENDER -> current.copy(renderStatus = status)
                    }
                } ?: page)
            }
        }.toMap(),
        chapterState = state.chapterState,
        indexResolver = indexResolver,
        permitHolderPageKey = permitHolderResolver?.invoke(),
        batchPhase = state.batchPhase,
        revision = state.revision,
        chapterId = chapterId,
    ).copy(aborted = state.aborted, abortedReason = state.abortReason)

    data class Projection(
        val chapterState: Translation.State = Translation.State.TRANSLATING,
        val batchPhase: TranslationBatchPhase = TranslationBatchPhase.FIRST_PASS,
        val revision: RevisionProgress = RevisionProgress(),
        val pagePhases: Map<String, Map<BatchPhase, String>> = emptyMap(),
        val aborted: Boolean = false,
        val abortReason: String? = null,
    )

    companion object {
        /** Pure reducer. This is the only mutation of progress projection state. */
        fun reduce(previous: Projection, event: TranslationBatchEvent): Projection = when (event) {
            is TranslationBatchEvent.PagePhase -> previous.copy(
                pagePhases = previous.pagePhases + (event.pageKey to (
                    previous.pagePhases[event.pageKey].orEmpty() + (event.phase to event.status.toStageStatus())
                )),
            )
            is TranslationBatchEvent.RevisionStarted -> previous.copy(batchPhase = if (event.totalBlocks > 0) TranslationBatchPhase.REVISING else TranslationBatchPhase.FINALIZING, revision = RevisionProgress(event.totalBlocks, skippedBlocks = event.skippedBlocks, userEditedBlocks = event.userEditedBlocks))
            is TranslationBatchEvent.RevisionChunkRunning -> previous.copy(revision = previous.revision.copy(activePageKey = event.pageKeys.firstOrNull(), activeChunkBlocks = event.blockCount))
            is TranslationBatchEvent.RevisionChunkCompleted -> previous.copy(revision = previous.revision.advance(completed = event.completedBlocks))
            is TranslationBatchEvent.RevisionChunkFailed -> previous.copy(revision = previous.revision.advance(failed = event.failedBlocks))
            TranslationBatchEvent.RevisionFinished -> previous.copy(batchPhase = TranslationBatchPhase.FINALIZING, revision = previous.revision.copy(activePageKey = null, activeChunkBlocks = 0))
            is TranslationBatchEvent.BatchFinished -> previous.copy(chapterState = event.state, batchPhase = TranslationBatchPhase.FINISHED, revision = previous.revision.copy(activePageKey = null, activeChunkBlocks = 0))
            is TranslationBatchEvent.BatchAborted -> previous.copy(chapterState = Translation.State.ERROR, batchPhase = TranslationBatchPhase.FINISHED, aborted = true, abortReason = event.reason)
            else -> previous
        }

        private fun PhaseStatus.toStageStatus(): String = when (this) {
            PhaseStatus.RUNNING -> StageStatus.RUNNING
            PhaseStatus.DONE -> StageStatus.READY
            PhaseStatus.FAILED -> StageStatus.FAILED
            PhaseStatus.SKIPPED -> StageStatus.SKIPPED
            PhaseStatus.PARTIAL -> StageStatus.PARTIAL
        }

        private fun RevisionProgress.advance(completed: Int = 0, failed: Int = 0): RevisionProgress {
            val remaining = (totalBlocks - processedBlocks).coerceAtLeast(0)
            val completedAdded = completed.coerceIn(0, remaining)
            val failedAdded = failed.coerceIn(0, remaining - completedAdded)
            return copy(completedBlocks = completedBlocks + completedAdded, failedBlocks = failedBlocks + failedAdded, activePageKey = null, activeChunkBlocks = 0)
        }

        fun computeSnapshot(
            pageMap: Map<String, PageTranslation>, chapterState: Translation.State,
            forcedPartialCount: Int = -1, forcedFailedCount: Int = -1, forcedDoneCount: Int = -1,
            indexResolver: Map<String, Int>? = null, permitHolderPageKey: String? = null,
            batchPhase: TranslationBatchPhase = if (chapterState == Translation.State.TRANSLATING) TranslationBatchPhase.FIRST_PASS else TranslationBatchPhase.IDLE,
            revision: RevisionProgress = RevisionProgress(), chapterId: Long = 0,
        ): TranslationProgressSnapshot {
            val rows = pageMap.entries.mapIndexed { order, (key, page) ->
                val stage = progressStage(page)
                TranslationProgressSnapshot.Page(key, PageIndexResolver.resolve(key, order, indexResolver), if (permitHolderPageKey != null && stage.isRunning && key != permitHolderPageKey) TranslationProgressStage.QUEUED else stage, page.errorMessage)
            }.sortedWith(compareBy<TranslationProgressSnapshot.Page> { it.index }.thenBy { it.pageKey })
            val stageCounts = BatchPhase.entries.associateWith { phase -> count(pageMap.values, phase) }
            val processedStages = stageCounts.values.sumOf { it.processed }
            val activeStages = pageMap.values.flatMap { page -> runningStages(page) }.toSet()
            val failed = if (forcedFailedCount >= 0) forcedFailedCount else rows.count { it.stage == TranslationProgressStage.FAILED }
            val done = if (forcedDoneCount >= 0) forcedDoneCount else rows.count { it.stage == TranslationProgressStage.DONE }
            val active = rows.firstOrNull { it.stage.isRunning } ?: rows.firstOrNull { it.stage == TranslationProgressStage.QUEUED }
            return TranslationProgressSnapshot(chapterId, chapterState, done + failed, rows.size, active?.index ?: 0, active?.pageKey, activeStages, rows.count { it.stage == TranslationProgressStage.QUEUED }, failed, rows, processedStages, pageMap.size * 4, stageCounts, if (forcedPartialCount >= 0) forcedPartialCount else pageMap.values.count { it.translationStatus == StageStatus.PARTIAL }, pageMap.entries.filter { it.value.isStageFailed || it.value.errorMessage != null }.groupBy({ it.value.errorMessage ?: "Unknown error" }, { it.key }), System.currentTimeMillis(), batchPhase = batchPhase, revision = revision)
        }

        private fun count(pages: Collection<PageTranslation>, phase: BatchPhase): StageCount {
            val statuses = pages.map { when (phase) { BatchPhase.OCR -> it.ocrStatus; BatchPhase.TRANSLATE -> it.translationStatus; BatchPhase.INPAINT -> it.inpaintStatus; BatchPhase.RENDER -> it.renderStatus } }
            return StageCount(statuses.count { it == StageStatus.READY || it == StageStatus.PARTIAL }, statuses.count { it == StageStatus.FAILED }, statuses.count { it == StageStatus.SKIPPED }, statuses.size)
        }
        private fun runningStages(page: PageTranslation) = buildSet {
            if (page.ocrStatus == StageStatus.RUNNING) add(TranslationProgressStage.OCR)
            if (page.inpaintStatus == StageStatus.RUNNING) add(TranslationProgressStage.INPAINT)
            if (page.translationStatus == StageStatus.RUNNING) add(TranslationProgressStage.TRANSLATE)
            if (page.renderStatus == StageStatus.RUNNING) add(TranslationProgressStage.RENDER)
        }
        private fun progressStage(page: PageTranslation): TranslationProgressStage = when {
            page.hasRenderedResult || page.isTextlessTerminal -> TranslationProgressStage.DONE
            page.isStageFailed -> TranslationProgressStage.FAILED
            page.renderStatus == StageStatus.RUNNING -> TranslationProgressStage.RENDER
            page.translationStatus == StageStatus.RUNNING -> TranslationProgressStage.TRANSLATE
            page.inpaintStatus == StageStatus.RUNNING -> TranslationProgressStage.INPAINT
            page.ocrStatus == StageStatus.RUNNING -> TranslationProgressStage.OCR
            else -> TranslationProgressStage.QUEUED
        }
    }
}
