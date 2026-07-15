package eu.kanade.translation.batch

import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.StageCount
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.RevisionProgress
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.PageIndexResolver
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isStageFailed
import eu.kanade.translation.model.isStageRunning
import eu.kanade.translation.model.isTextlessTerminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TranslationBatchProgressTracker(
    private val chapterId: Long,
    private val store: ChapterTranslationStore,
    private val orderedPageKeys: List<String>,
    private val scope: CoroutineScope,
    private val permitHolderResolver: (() -> String?)? = null,
) {
    private var tickJob: Job? = null
    private var batchStartTime = System.currentTimeMillis()
    private var finished = false
    private var batchPhase = TranslationBatchPhase.FIRST_PASS
    private var revisionProgress = RevisionProgress()
    private val indexResolver = orderedPageKeys.withIndex().associate { it.value to it.index + 1 }

    private val _snapshot = MutableStateFlow(emptySnapshot())
    val snapshot: StateFlow<TranslationProgressSnapshot> = _snapshot.asStateFlow()

    private fun computeSnapshotFor(
        pageMap: Map<String, PageTranslation>,
        chapterState: Translation.State,
        forcedPartialCount: Int = -1,
        forcedFailedCount: Int = -1,
        forcedDoneCount: Int = -1,
    ): TranslationProgressSnapshot = computeSnapshot(
        pageMap = pageMap,
        chapterState = chapterState,
        forcedPartialCount = forcedPartialCount,
        forcedFailedCount = forcedFailedCount,
        forcedDoneCount = forcedDoneCount,
        indexResolver = indexResolver,
        permitHolderPageKey = permitHolderResolver?.invoke(),
        batchPhase = batchPhase,
        revision = revisionProgress,
    )

    init {
        rebuildFromStore()
        startTick()
    }

    private fun emptySnapshot(): TranslationProgressSnapshot {
        return TranslationProgressSnapshot.empty(chapterId, Translation.State.TRANSLATING).copy(
            batchPhase = batchPhase,
            revision = revisionProgress,
        )
    }

    fun rebuildFromStore() {
        if (finished) return
        val pageMap = store.state.value
        if (pageMap.isEmpty()) {
            _snapshot.value = emptySnapshot()
            return
        }
        _snapshot.value = computeSnapshotFor(pageMap, Translation.State.TRANSLATING)
    }

    fun beginRevision(
        totalBlocks: Int,
        skippedBlocks: Int = 0,
        userEditedBlocks: Int = 0,
    ) {
        if (finished) return
        batchPhase = if (totalBlocks > 0) {
            TranslationBatchPhase.REVISING
        } else {
            TranslationBatchPhase.FINALIZING
        }
        revisionProgress = RevisionProgress(
            totalBlocks = totalBlocks,
            skippedBlocks = skippedBlocks,
            userEditedBlocks = userEditedBlocks,
        )
        rebuildFromStore()
    }

    fun markRevisionChunkRunning(pageKeys: Collection<String>, blockCount: Int) {
        if (finished || batchPhase != TranslationBatchPhase.REVISING) return
        revisionProgress = revisionProgress.copy(
            activePageKey = pageKeys.firstOrNull(),
            activeChunkBlocks = blockCount,
        )
        rebuildFromStore()
    }

    fun markRevisionChunkCompleted(completedBlocks: Int) {
        if (finished) return
        val remaining = (revisionProgress.totalBlocks -
            revisionProgress.completedBlocks - revisionProgress.failedBlocks).coerceAtLeast(0)
        revisionProgress = revisionProgress.copy(
            completedBlocks = revisionProgress.completedBlocks + completedBlocks.coerceAtMost(remaining),
            activePageKey = null,
            activeChunkBlocks = 0,
        )
        rebuildFromStore()
    }

    fun markRevisionChunkFailed(failedBlocks: Int) {
        if (finished) return
        val remaining = (revisionProgress.totalBlocks -
            revisionProgress.completedBlocks - revisionProgress.failedBlocks).coerceAtLeast(0)
        revisionProgress = revisionProgress.copy(
            failedBlocks = revisionProgress.failedBlocks + failedBlocks.coerceAtMost(remaining),
            activePageKey = null,
            activeChunkBlocks = 0,
        )
        rebuildFromStore()
    }

    fun markRevisionFinished() {
        if (finished) return
        batchPhase = TranslationBatchPhase.FINALIZING
        revisionProgress = revisionProgress.copy(activePageKey = null, activeChunkBlocks = 0)
        rebuildFromStore()
    }

    suspend fun markOcrRunning(pageKey: String) {
        transition(pageKey) { (it ?: PageTranslation(sourceFileName = pageKey)).apply { ocrStatus = StageStatus.RUNNING } }
    }

    suspend fun markOcrDone(pageKey: String) {
        transition(pageKey) { (it ?: PageTranslation(sourceFileName = pageKey)).apply { ocrStatus = StageStatus.READY } }
    }

    suspend fun markOcrFailed(pageKey: String, reason: String) {
        transition(pageKey) { (it ?: PageTranslation(sourceFileName = pageKey)).apply { ocrStatus = StageStatus.FAILED; errorMessage = reason } }
    }

    suspend fun markTranslateRunning(pageKey: String) {
        transition(pageKey) { (it ?: PageTranslation(sourceFileName = pageKey)).apply { translationStatus = StageStatus.RUNNING } }
    }

    suspend fun markTranslateDone(pageKey: String) {
        transition(pageKey) { (it ?: PageTranslation(sourceFileName = pageKey)).apply { translationStatus = StageStatus.READY } }
    }

    suspend fun markTranslateFailed(pageKey: String, reason: String) {
        transition(pageKey) { (it ?: PageTranslation(sourceFileName = pageKey)).apply { translationStatus = StageStatus.FAILED; errorMessage = reason } }
    }

    suspend fun markTranslatePartial(pageKey: String) {
        transition(pageKey) { (it ?: PageTranslation(sourceFileName = pageKey)).apply { translationStatus = StageStatus.PARTIAL } }
    }

    suspend fun markInpaintRunning(pageKey: String) {
        transition(pageKey) { (it ?: PageTranslation(sourceFileName = pageKey)).apply { inpaintStatus = StageStatus.RUNNING } }
    }

    suspend fun markInpaintDone(pageKey: String) {
        transition(pageKey) { (it ?: PageTranslation(sourceFileName = pageKey)).apply { inpaintStatus = StageStatus.READY } }
    }

    suspend fun markInpaintFailed(pageKey: String, reason: String) {
        transition(pageKey) { (it ?: PageTranslation(sourceFileName = pageKey)).apply { inpaintStatus = StageStatus.FAILED; errorMessage = reason } }
    }

    suspend fun markRenderRunning(pageKey: String) {
        transition(pageKey) { (it ?: PageTranslation(sourceFileName = pageKey)).apply { renderStatus = StageStatus.RUNNING } }
    }

    suspend fun markRenderDone(pageKey: String) {
        transition(pageKey) { (it ?: PageTranslation(sourceFileName = pageKey)).apply { renderStatus = StageStatus.READY } }
    }

    suspend fun markRenderFailed(pageKey: String, reason: String) {
        transition(pageKey) { (it ?: PageTranslation(sourceFileName = pageKey)).apply { renderStatus = StageStatus.FAILED; errorMessage = reason } }
    }

    suspend fun markPageReady(pageKey: String) {
        transition(pageKey) { existing ->
            (existing ?: PageTranslation(sourceFileName = pageKey)).apply {
                if (ocrStatus == StageStatus.PENDING) ocrStatus = StageStatus.RUNNING
                if (translationStatus == StageStatus.PENDING) translationStatus = StageStatus.READY
                if (inpaintStatus == StageStatus.PENDING) inpaintStatus = StageStatus.READY
                if (renderStatus == StageStatus.PENDING) renderStatus = StageStatus.READY
            }
        }
    }

    suspend fun markChapterError(reason: String) {
        orderedPageKeys.forEach { pageKey ->
            store.updatePage(pageKey) { existing ->
                (existing ?: PageTranslation(sourceFileName = pageKey)).apply {
                    if (!hasRenderedResult && !isTextlessTerminal) {
                        errorMessage = errorMessage ?: reason
                        ocrStatus = if (ocrStatus == StageStatus.PENDING) StageStatus.FAILED else ocrStatus
                        translationStatus = if (translationStatus == StageStatus.PENDING) StageStatus.FAILED else translationStatus
                        inpaintStatus = if (inpaintStatus == StageStatus.PENDING) StageStatus.FAILED else inpaintStatus
                        if (renderStatus == StageStatus.PENDING) renderStatus = StageStatus.FAILED
                    }
                }
            }
        }
        rebuildFromStore()
    }

    fun finish(result: ReconciliationResult) {
        finished = true
        tickJob?.cancel()
        batchPhase = TranslationBatchPhase.FINISHED
        revisionProgress = revisionProgress.copy(activePageKey = null, activeChunkBlocks = 0)
        val pageMap = store.state.value
        val snapshot = computeSnapshotFor(
            pageMap = pageMap,
            chapterState = result.chapterStatus,
            forcedPartialCount = result.partialCount,
            forcedFailedCount = result.failedCount + result.strandedPages.size,
            forcedDoneCount = result.doneCount,
        )
        _snapshot.value = snapshot
    }

    suspend fun abort(remainingPageKeys: Set<String>, reason: String) {
        finished = true
        tickJob?.cancel()
        batchPhase = TranslationBatchPhase.FINISHED
        revisionProgress = revisionProgress.copy(activePageKey = null, activeChunkBlocks = 0)
        remainingPageKeys.forEach { pageKey ->
            store.updatePage(pageKey) { existing ->
                (existing ?: PageTranslation(sourceFileName = pageKey)).apply {
                    if (!hasRenderedResult && !isTextlessTerminal) {
                        ocrStatus = StageStatus.FAILED
                        translationStatus = StageStatus.FAILED
                        inpaintStatus = StageStatus.FAILED
                        renderStatus = StageStatus.FAILED
                        errorMessage = reason
                    }
                }
            }
        }
        val pageMap = store.state.value
        _snapshot.value = computeSnapshotFor(pageMap, Translation.State.ERROR).copy(
            aborted = true,
            abortedReason = reason,
        )
    }

    fun close() {
        finished = true
        tickJob?.cancel()
    }

    private suspend fun transition(pageKey: String, update: (PageTranslation?) -> PageTranslation) {
        if (finished) return
        store.updatePage(pageKey, update)
    }

    private fun startTick() {
        tickJob = scope.launch {
            while (isActive && !finished) {
                delay(500)
                if (!finished) {
                    val pageMap = store.state.value
                    if (pageMap.isNotEmpty()) {
                        _snapshot.value = computeSnapshotFor(pageMap, Translation.State.TRANSLATING)
                    }
                }
            }
        }
    }

    companion object {
        fun computeSnapshot(
            pageMap: Map<String, PageTranslation>,
            chapterState: Translation.State,
            forcedPartialCount: Int = -1,
            forcedFailedCount: Int = -1,
            forcedDoneCount: Int = -1,
            indexResolver: Map<String, Int>? = null,
            permitHolderPageKey: String? = null,
            batchPhase: TranslationBatchPhase = when (chapterState) {
                Translation.State.TRANSLATING -> TranslationBatchPhase.FIRST_PASS
                Translation.State.TRANSLATED -> TranslationBatchPhase.FINISHED
                else -> TranslationBatchPhase.IDLE
            },
            revision: RevisionProgress = RevisionProgress(),
        ): TranslationProgressSnapshot {
            if (pageMap.isEmpty()) {
                return TranslationProgressSnapshot.empty(0, chapterState).copy(
                    batchPhase = batchPhase,
                    revision = revision,
                )
            }

            val rows = pageMap.entries
                .mapIndexed { insertionOrder, (pageKey, page) ->
                    val rawStage = progressStage(page)
                    TranslationProgressSnapshot.Page(
                        pageKey = pageKey,
                        index = PageIndexResolver.resolve(pageKey, insertionOrder, indexResolver),
                        stage = if (permitHolderPageKey != null && rawStage.isRunning && pageKey != permitHolderPageKey) {
                            eu.kanade.translation.model.TranslationProgressStage.QUEUED
                        } else rawStage,
                        errorMessage = page.errorMessage,
                    )
                }
                .sortedWith(compareBy<TranslationProgressSnapshot.Page> { it.index }.thenBy { it.pageKey })

            val done = if (forcedDoneCount >= 0) forcedDoneCount else rows.count { it.stage == eu.kanade.translation.model.TranslationProgressStage.DONE }
            val failed = if (forcedFailedCount >= 0) forcedFailedCount else rows.count { it.stage == eu.kanade.translation.model.TranslationProgressStage.FAILED }
            val partial = if (forcedPartialCount >= 0) forcedPartialCount else rows.count { pageMap[it.pageKey]?.translationStatus == StageStatus.PARTIAL }
            val queued = rows.count { it.stage == eu.kanade.translation.model.TranslationProgressStage.QUEUED }
            val active = rows.firstOrNull { it.stage.isRunning }
                ?: rows.firstOrNull { it.stage == eu.kanade.translation.model.TranslationProgressStage.QUEUED }

            val doneStages = pageMap.values.sumOf { completedStages(it) }
            val totalStages = pageMap.size * 4

            val elapsedMs = System.currentTimeMillis()

            val ocrDone = pageMap.values.count { it.ocrStatus == StageStatus.READY }
            val translateDone = pageMap.values.count { it.translationStatus == StageStatus.READY }
            val inpaintDone = pageMap.values.count { it.inpaintStatus == StageStatus.READY }
            val renderDone = pageMap.values.count { it.renderStatus == StageStatus.READY }

            val ocrFailed = pageMap.values.count { it.ocrStatus == StageStatus.FAILED }
            val translateFailed = pageMap.values.count { it.translationStatus == StageStatus.FAILED }
            val inpaintFailed = pageMap.values.count { it.inpaintStatus == StageStatus.FAILED }
            val renderFailed = pageMap.values.count { it.renderStatus == StageStatus.FAILED }

            val total = rows.size

            val groupedFailures = pageMap.entries
                .filter { it.value.isStageFailed || it.value.errorMessage != null }
                .groupBy { it.value.errorMessage ?: "Unknown error" }
                .mapValues { (_, entries) -> entries.map { it.key } }

            return TranslationProgressSnapshot(
                chapterId = 0,
                state = chapterState,
                donePages = done + failed,
                totalPages = total,
                activePage = active?.index ?: 0,
                activePageKey = active?.pageKey,
                activeStage = active?.stage,
                queuedCount = queued,
                failedCount = failed,
                pages = rows,
                doneStages = doneStages,
                totalStages = totalStages,
                perStage = mapOf(
                    BatchPhase.OCR to StageCount(done = ocrDone, failed = ocrFailed, total = total),
                    BatchPhase.TRANSLATE to StageCount(done = translateDone, failed = translateFailed, total = total),
                    BatchPhase.INPAINT to StageCount(done = inpaintDone, failed = inpaintFailed, total = total),
                    BatchPhase.RENDER to StageCount(done = renderDone, failed = renderFailed, total = total),
                ),
                partialPages = partial,
                groupedFailures = groupedFailures,
                elapsedMs = elapsedMs,
                aborted = false,
                abortedReason = null,
                batchPhase = batchPhase,
                revision = revision,
            )
        }

        private fun progressStage(page: PageTranslation): eu.kanade.translation.model.TranslationProgressStage {
            if (page.hasRenderedResult) return eu.kanade.translation.model.TranslationProgressStage.DONE
            if (page.renderStatus == StageStatus.FAILED ||
                page.inpaintStatus == StageStatus.FAILED ||
                page.translationStatus == StageStatus.FAILED ||
                page.ocrStatus == StageStatus.FAILED
            ) {
                return eu.kanade.translation.model.TranslationProgressStage.FAILED
            }
            if (page.renderStatus == StageStatus.RUNNING) return eu.kanade.translation.model.TranslationProgressStage.RENDER
            if (page.translationStatus == StageStatus.RUNNING) return eu.kanade.translation.model.TranslationProgressStage.TRANSLATE
            if (page.inpaintStatus == StageStatus.RUNNING) return eu.kanade.translation.model.TranslationProgressStage.INPAINT
            if (page.ocrStatus == StageStatus.RUNNING) return eu.kanade.translation.model.TranslationProgressStage.OCR
            if (page.isTextlessTerminal) return eu.kanade.translation.model.TranslationProgressStage.DONE
            return eu.kanade.translation.model.TranslationProgressStage.QUEUED
        }

        private fun completedStages(page: PageTranslation): Int {
            if (page.hasRenderedResult || (page.isTextlessTerminal && !page.isStageRunning) || page.isStageFailed) return 4
            var count = 0
            if (page.ocrStatus == StageStatus.READY) count++
            if (page.translationStatus == StageStatus.READY) count++
            if (page.inpaintStatus == StageStatus.READY) count++
            if (page.renderStatus == StageStatus.READY) count++
            return count
        }

    }
}
