package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.model.AiPageProgressState
import eu.kanade.translation.model.Translation

enum class BatchPhase { OCR, TRANSLATE, INPAINT, RENDER, DISPLAY }
enum class PhaseStatus { RUNNING, DONE, FAILED, SKIPPED, PARTIAL, PAUSED }

/**
 * Input to the batch-progress projection. Pipeline/store own all actual page state.
 *
 * CP9: the batch start/resume lifecycle is owned by the batch coordinator pass
 * (`runPass1`) and is not surfaced through this event stream — the projection derives chapter/batch framing
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
    data class AiPageProgress(
        val pageKey: String,
        val pageIndex: Int,
        val state: AiPageProgressState,
        val reason: String? = null,
    ) : TranslationBatchEvent()
    /**
     * T934 LI-4: the coordinator is rebuilding the envelope dispatch work
     * (resume hydration). Flips the projection into the REBUILDING phase and
     * carries the per-page adoption counter, so the progress sheet shows the
     * indeterminate live window and store-derived counters keep moving during
     * the potentially minutes-long rebuild instead of freezing on a stale
     * snapshot.
     */
    data class EnvelopePlanProgress(
        val done: Int,
        val total: Int,
    ) : TranslationBatchEvent()

    /** T934 LI-4: the envelope plan committed (or was reused) — rebuild window ended. */
    data object EnvelopePlanCommitted : TranslationBatchEvent()

    data class BatchAborted(val reason: String, val failedPageKeys: Set<String>) : TranslationBatchEvent()
    data class BatchPaused(
        val anchorPageKey: String?,
        val completedPages: Int,
        val totalPages: Int,
        val retryableCount: Int,
        val reason: String,
        val nextEligibleRetryAtEpochMs: Long? = null,
        val retryablePageKeys: Set<String> = emptySet(),
        // T917 Phase 5 (spec §4.1): the pause is a guarded-publication
        // rejection — no durable result exists for the affected page.
        val nonDurableFailure: Boolean = false,
        val nonDurableFailureReason: String? = null,
    ) : TranslationBatchEvent()
    data class BatchFinished(
        val state: Translation.State,
        val donePages: Int,
        val failedPages: Int,
        val partialPages: Int,
        val totalPages: Int,
        // T917 Phase 5 (spec §4.1): carried from ReconciliationResult so a
        // finished chapter keeps its non-durable warning visible.
        val nonDurableFailure: Boolean = false,
        val nonDurableFailureReason: String? = null,
    ) : TranslationBatchEvent()
}
