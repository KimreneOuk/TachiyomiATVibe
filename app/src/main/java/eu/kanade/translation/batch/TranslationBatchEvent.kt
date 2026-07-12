package eu.kanade.translation.batch

enum class BatchPhase { OCR, TRANSLATE, INPAINT, RENDER }

enum class PhaseStatus { RUNNING, DONE, FAILED, SKIPPED, PARTIAL }

sealed class TranslationBatchEvent {
    data class BatchStarted(
        val chapterId: Long,
        val totalPages: Int,
        val orderedPageKeys: List<String>,
        val resumeIndex: Int,
    ) : TranslationBatchEvent()

    data class BatchResumed(
        val chapterId: Long,
        val totalPages: Int,
        val orderedPageKeys: List<String>,
    ) : TranslationBatchEvent()

    data class PagePhase(
        val pageKey: String,
        val pageIndex: Int,
        val phase: BatchPhase,
        val status: PhaseStatus,
        val elapsedMs: Long,
        val heapMiB: Long? = null,
    ) : TranslationBatchEvent()

    data class StageSummary(
        val phase: BatchPhase,
        val done: Int,
        val failed: Int,
        val total: Int,
    ) : TranslationBatchEvent()

    data class BatchAborted(
        val reason: String,
        val failedPageKeys: Set<String>,
    ) : TranslationBatchEvent()

    data class BatchFinished(
        val state: eu.kanade.translation.model.Translation.State,
        val donePages: Int,
        val failedPages: Int,
        val partialPages: Int,
        val totalPages: Int,
    ) : TranslationBatchEvent()
}
