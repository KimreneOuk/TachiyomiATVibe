package eu.kanade.translation.batch

import eu.kanade.translation.model.Translation

enum class BatchPhase { OCR, TRANSLATE, INPAINT, RENDER, DISPLAY }
enum class PhaseStatus { RUNNING, DONE, FAILED, SKIPPED, PARTIAL }

/**
 * Input to the batch-progress projection. Pipeline/store own all actual page state.
 *
 * CP9: the batch start/resume lifecycle is owned by [BatchCoordinator] and is not
 * surfaced through this event stream — the projection derives chapter/batch framing
 * from the ordered page keys it is constructed with, and from PagePhase/BatchFinished/
 * BatchAborted events. The previous `BatchStarted` / `BatchResumed` event types had no
 * emitter anywhere in `app/src` (verified) and were only ever handled by the reducer's
 * defensive `else -> previous` branch, so they have been removed.
 */
sealed class TranslationBatchEvent {
    data class PagePhase(
        val pageKey: String,
        val pageIndex: Int,
        val phase: BatchPhase,
        val status: PhaseStatus,
        val elapsedMs: Long = 0L,
        val heapMiB: Long? = null,
        val reason: String? = null,
    ) : TranslationBatchEvent()
    data class RevisionStarted(
        val totalBlocks: Int,
        val skippedBlocks: Int,
        val userEditedBlocks: Int,
    ) : TranslationBatchEvent()
    data class RevisionChunkRunning(val pageKeys: Set<String>, val blockCount: Int) : TranslationBatchEvent()
    data class RevisionChunkCompleted(val completedBlocks: Int) : TranslationBatchEvent()
    data class RevisionChunkFailed(val failedBlocks: Int) : TranslationBatchEvent()
    data object RevisionFinished : TranslationBatchEvent()
    data class BatchAborted(val reason: String, val failedPageKeys: Set<String>) : TranslationBatchEvent()
    data class BatchFinished(
        val state: Translation.State,
        val donePages: Int,
        val failedPages: Int,
        val partialPages: Int,
        val totalPages: Int,
    ) : TranslationBatchEvent()
}
