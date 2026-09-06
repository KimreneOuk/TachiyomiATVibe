package eu.kanade.translation.model

import androidx.compose.runtime.Immutable
import eu.kanade.translation.pipeline.batch.BatchPhase

/** Terminal and successful work are both processed; skipped work is successful terminal work. */
@Immutable
data class StageCount(
    val succeeded: Int,
    val failed: Int,
    val skipped: Int,
    val total: Int,
) {
    /** Compatibility name for existing callers. */
    val done: Int get() = succeeded
    val processed: Int get() = succeeded + failed + skipped
    val fraction: Float get() = if (total == 0) 0f else processed.toFloat() / total
}

enum class AiPageProgressState {
    PENDING,
    BUFFERED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    PAUSED,
}

@Immutable
data class AiBatchProgress(
    val pending: Int = 0,
    val buffered: Int = 0,
    val running: Int = 0,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val paused: Int = 0,
) {
    val total: Int get() = pending + buffered + running + succeeded + failed + paused
    val processed: Int get() = succeeded + failed
}

enum class TranslationBatchPhase { IDLE, FIRST_PASS, FINALIZING, FINISHED }

@Immutable
data class TranslationProgressSnapshot(
    val chapterId: Long,
    val state: Translation.State,
    val donePages: Int,
    val totalPages: Int,
    val activePage: Int,
    val activePageKey: String?,
    val activeStages: Set<TranslationProgressStage> = emptySet(),
    val queuedCount: Int,
    val failedCount: Int,
    val pages: List<Page>,
    val doneStages: Int = 0,
    val totalStages: Int = 0,
    val perStage: Map<BatchPhase, StageCount> = emptyMap(),
    val partialPages: Int = 0,
    val groupedFailures: Map<String, List<String>> = emptyMap(),
    val elapsedMs: Long = 0L,
    val aborted: Boolean = false,
    val abortedReason: String? = null,
    val batchPhase: TranslationBatchPhase = TranslationBatchPhase.IDLE,
    val aiProgress: AiBatchProgress = AiBatchProgress(),
    val pauseAnchorPageKey: String? = null,
    val pauseReason: String? = null,
    val nextEligibleRetryAtEpochMs: Long? = null,
    /** Immediate pre-tracker acknowledgement, when a request is still preparing or downloading. */
    val requestState: TranslationRequestState? = null,
    /**
     * T911 slice 2: this chapter's 1-based position among the outstanding
     * translation-queue entries (QUEUE/TRANSLATING) while it waits behind
     * other work. Null when not queued.
     */
    val queuePosition: Int? = null,
    /** Total outstanding queue entries when [queuePosition] is set. */
    val queueTotal: Int? = null,
    /**
     * T917 Phase 5 (spec §4.1): a guarded artifact publication was rejected
     * (`BatchPass1Status.PERSISTENCE_REJECTED` / `ReconciliationResult
     * .nonDurableFailure`). The affected page produced in-memory work but NO
     * durable result: it must never count as terminal success and every
     * surface must suppress completion copy in favor of
     * "Translation not saved — retry required". Bounded value field, no enum.
     */
    val nonDurableFailure: Boolean = false,
    /** Safe rejection reason carried alongside [nonDurableFailure]. */
    val nonDurableFailureReason: String? = null,
    /**
     * T917 Phase 5 (spec §2.1 CANCELLED): pages the batch contract settled as
     * cancelled (aborted) terminal work. Never fake failures, never success.
     */
    val cancelledPages: Int = 0,
    /**
     * T917 Phase 5 (spec §2.1/D10): whether [totalPages] is the trusted source
     * total. A partially downloaded chapter's available page set is NOT its
     * trusted total — unknown totals must never render a percentage or a
     * fabricated complete chapter. Only a registered batch work set or a
     * trusted manifest earns `true`.
     */
    val expectedPageCountTrusted: Boolean = false,
) {
    /** Failures are processed, so a terminal failed stage reaches 100%. */
    val fraction: Float get() = if (totalStages == 0) 0f else doneStages.toFloat() / totalStages
    val countPair: Pair<Int, Int> get() = donePages to totalPages

    val aiPendingPages: Int get() = aiProgress.pending
    val aiBufferedPages: Int get() = aiProgress.buffered
    val aiRunningPages: Int get() = aiProgress.running
    val aiSucceededPages: Int get() = aiProgress.succeeded
    val aiFailedPages: Int get() = aiProgress.failed
    val aiTotalPages: Int get() = aiProgress.total

    /** Exact count of pages whose committed display bundle can be read now. */
    val displayReadyPages: Int get() = pages.count { it.displayReady }

    /** Terminal page work, including failures and textless pages. */
    val processedPages: Int get() = pages.count {
        it.processed ||
            it.stage == TranslationProgressStage.DONE ||
            it.stage == TranslationProgressStage.FAILED
    }

    /**
     * T917 Phase 5 (spec §2.1): pages in exactly one current-pass terminal
     * category — translated/reused-valid, textless, failed, partial, or
     * cancelled. Queued, running, buffered, uncommitted, and missing-source
     * pages are never terminal.
     */
    val terminalPages: Int get() = processedPages + cancelledPages

    /**
     * T917 Phase 5 (spec §2.1): the readable-success subset of terminal work —
     * committed display-ready pages excluding partial results, plus textless
     * terminals. Failures, partials, and cancellations stay OUT of this count
     * so no surface can render them as done.
     */
    val terminalSuccessPages: Int get() =
        pages.count { it.stage == TranslationProgressStage.DONE && !it.partial }

    /** Read Now/Open Translated actions are valid only when a result exists. */
    val canReadTranslated: Boolean get() = displayReadyPages > 0

    @Immutable
    data class Page(
        val pageKey: String,
        val index: Int,
        val stage: TranslationProgressStage,
        val ocrDone: Boolean = false,
        val translateDone: Boolean = false,
        val inpaintDone: Boolean = false,
        val renderDone: Boolean = false,
        val errorMessage: String? = null,
        val displayState: PageDisplayState = PageDisplayState.ORIGINAL_ONLY,
        val displayReady: Boolean = false,
        val processed: Boolean = false,
        val aiState: AiPageProgressState = AiPageProgressState.PENDING,
        /**
         * T917 Phase 5: the current pass produced a PARTIAL translation for
         * this page. Partial work is terminal-retryable, never clean success.
         */
        val partial: Boolean = false,
    )

    companion object {
        fun empty(
            chapterId: Long,
            state: Translation.State = Translation.State.NOT_TRANSLATED,
        ) = TranslationProgressSnapshot(
            chapterId = chapterId, state = state, donePages = 0, totalPages = 0,
            activePage = 0, activePageKey = null, queuedCount = 0, failedCount = 0, pages = emptyList(),
        )

        fun compute(
            chapterId: Long,
            state: Translation.State,
            pageMap: Map<String, PageTranslation>?,
            indexResolver: Map<String, Int>? = null,
            permitHolderPageKey: String? = null,
            aiPageStates: Map<String, AiPageProgressState> = emptyMap(),
            displayPageMap: Map<String, PageTranslation>? = null,
            // T924 restart-retry fix: a durable ERROR chapter is a run that
            // ENDED (IDLE here is why the sheet's Retry affordance vanished
            // after an app restart — the truth rule needs ERROR + FINISHED).
            // T924 field fix: READY_WITH_WARNINGS that ENDED with unresolved
            // pages is the same terminal shape — it must read FINISHED too,
            // or its Retry affordance vanishes the same way.
            batchPhase: TranslationBatchPhase = when (state) {
                Translation.State.TRANSLATING -> TranslationBatchPhase.FIRST_PASS
                Translation.State.ERROR,
                Translation.State.READY_WITH_WARNINGS,
                -> TranslationBatchPhase.FINISHED
                else -> TranslationBatchPhase.IDLE
            },
            /** T917 Phase 5 (D10): trusted source-total fact from the manifest. */
            expectedPageCountTrusted: Boolean = false,
        ): TranslationProgressSnapshot = eu.kanade.translation.pipeline.batch.TranslationBatchProgressTracker.computeSnapshot(
            pageMap.orEmpty(),
            state,
            indexResolver = indexResolver,
            permitHolderPageKey = permitHolderPageKey,
            aiPageStates = aiPageStates,
            displayPageMap = displayPageMap,
            batchPhase = batchPhase,
            chapterId = chapterId,
            expectedPageCountTrusted = expectedPageCountTrusted,
        )
    }
}

/** Resolves page numbers from the chapter's real ordered keys when available. */
internal object PageIndexResolver {
    fun resolve(pageKey: String, fallback: Int, indexResolver: Map<String, Int>? = null): Int {
        indexResolver?.get(pageKey)?.let { return it }
        val leaf = pageKey.substringAfterLast('/').substringBeforeLast('.')
        val primary = leaf.substringBefore("__")
        val start = primary.indexOfFirst { it.isDigit() }
        if (start >= 0) {
            val end = (start until primary.length).firstOrNull { !primary[it].isDigit() } ?: primary.length
            primary.substring(start, end).toIntOrNull()?.let { return it }
        }
        return fallback + 1
    }
}

enum class TranslationProgressStage {
    QUEUED,
    OCR,
    INPAINT,
    TRANSLATE,
    RENDER,
    DONE,
    FAILED,
    ;

    val isRunning: Boolean get() = this == OCR || this == INPAINT || this == TRANSLATE || this == RENDER
}
