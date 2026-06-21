package eu.kanade.translation.model

/**
 * Rich per-chapter batch progress for pre-translation UI.
 *
 * This is intentionally pure app logic: no Compose, Android, or store
 * dependency. UI layers can render the summary while tests pin the lifecycle
 * rules independently from the translation engines.
 */
data class TranslationProgressSnapshot(
    val chapterId: Long,
    val state: Translation.State,
    val donePages: Int,
    val totalPages: Int,
    val activePage: Int,
    val activePageKey: String?,
    val activeStage: TranslationProgressStage?,
    val queuedCount: Int,
    val failedCount: Int,
    val pages: List<Page>,
    val doneStages: Int = 0,
    val totalStages: Int = 0,
) {
    val fraction: Float
        get() = if (totalStages > 0) (doneStages.toFloat() / totalStages).coerceIn(0f, 1f) else 0f

    val countPair: Pair<Int, Int>
        get() = donePages to totalPages

    data class Page(
        val pageKey: String,
        val index: Int,
        val stage: TranslationProgressStage,
        val errorMessage: String? = null,
    )

    companion object {
        fun empty(chapterId: Long, state: Translation.State = Translation.State.NOT_TRANSLATED) =
            TranslationProgressSnapshot(
                chapterId = chapterId,
                state = state,
                donePages = 0,
                totalPages = 0,
                activePage = 0,
                activePageKey = null,
                activeStage = null,
                queuedCount = 0,
                failedCount = 0,
                pages = emptyList(),
                doneStages = 0,
                totalStages = 0,
            )

        fun compute(
            chapterId: Long,
            state: Translation.State,
            pageMap: Map<String, PageTranslation>?,
        ): TranslationProgressSnapshot {
            if (pageMap.isNullOrEmpty()) return empty(chapterId, state)

            val rows = pageMap.entries
                .mapIndexed { insertionOrder, (pageKey, page) ->
                    Page(
                        pageKey = pageKey,
                        index = resolvePageIndex(pageKey, insertionOrder),
                        stage = page.progressStage(),
                        errorMessage = page.errorMessage,
                    )
                }
                .sortedWith(compareBy<Page> { it.index }.thenBy { it.pageKey })

            val done = rows.count { it.stage == TranslationProgressStage.DONE }
            val failed = rows.count { it.stage == TranslationProgressStage.FAILED }
            val queued = rows.count { it.stage == TranslationProgressStage.QUEUED }
            val active = rows.firstOrNull { it.stage.isRunning }
                ?: rows.firstOrNull { it.stage == TranslationProgressStage.QUEUED }

            val doneStages = pageMap.values.sumOf { it.completedStages() }
            val totalStages = pageMap.size * 4

            return TranslationProgressSnapshot(
                chapterId = chapterId,
                state = state,
                donePages = done + failed,
                totalPages = rows.size,
                activePage = active?.index ?: 0,
                activePageKey = active?.pageKey,
                activeStage = active?.stage,
                queuedCount = queued,
                failedCount = failed,
                pages = rows,
                doneStages = doneStages,
                totalStages = totalStages,
            )
        }

        private fun resolvePageIndex(pageKey: String, fallback: Int): Int {
            val match = Regex("""(\d+)(?!.*\d)""").find(pageKey)
            return match?.groupValues?.get(1)?.toIntOrNull() ?: (fallback + 1)
        }
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

    val isRunning: Boolean
        get() = this == OCR || this == INPAINT || this == TRANSLATE || this == RENDER
}

private fun PageTranslation.progressStage(): TranslationProgressStage {
    if (hasRenderedResult) return TranslationProgressStage.DONE
    if (renderStatus == StageStatus.FAILED ||
        inpaintStatus == StageStatus.FAILED ||
        translationStatus == StageStatus.FAILED ||
        ocrStatus == StageStatus.FAILED
    ) {
        return TranslationProgressStage.FAILED
    }
    if (renderStatus == StageStatus.RUNNING) return TranslationProgressStage.RENDER
    if (translationStatus == StageStatus.RUNNING) return TranslationProgressStage.TRANSLATE
    if (inpaintStatus == StageStatus.RUNNING) return TranslationProgressStage.INPAINT
    if (ocrStatus == StageStatus.RUNNING) return TranslationProgressStage.OCR
    if (isTextlessTerminal) return TranslationProgressStage.DONE
    return TranslationProgressStage.QUEUED
}

private fun PageTranslation.completedStages(): Int {
    if (hasRenderedResult || (isTextlessTerminal && !isStageRunning) || isStageFailed) return 4
    var count = 0
    if (ocrStatus == StageStatus.READY) count++
    if (translationStatus == StageStatus.READY) count++
    if (inpaintStatus == StageStatus.READY) count++
    if (renderStatus == StageStatus.READY) count++
    return count
}
