package eu.kanade.translation.model

import androidx.compose.runtime.Immutable
import eu.kanade.translation.batch.BatchPhase

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
}

@Immutable
data class AiBatchProgress(
    val pending: Int = 0,
    val buffered: Int = 0,
    val running: Int = 0,
    val succeeded: Int = 0,
    val failed: Int = 0,
) {
    val total: Int get() = pending + buffered + running + succeeded + failed
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
            batchPhase: TranslationBatchPhase = if (state ==
                Translation.State.TRANSLATING
            ) {
                TranslationBatchPhase.FIRST_PASS
            } else {
                TranslationBatchPhase.IDLE
            },
        ): TranslationProgressSnapshot = eu.kanade.translation.batch.TranslationBatchProgressTracker.computeSnapshot(
            pageMap.orEmpty(),
            state,
            indexResolver = indexResolver,
            permitHolderPageKey = permitHolderPageKey,
            aiPageStates = aiPageStates,
            displayPageMap = displayPageMap,
            batchPhase = batchPhase,
            chapterId = chapterId,
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
