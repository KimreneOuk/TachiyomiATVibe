package eu.kanade.translation.model

/**
 * TachiyomiAT bug 3 fix: result of [eu.kanade.translation.orchestration.TranslationManager.translateChapterPreflight].
 *
 * Encodes the conflict state the UI must resolve before starting a new batch on
 * a chapter when another chapter of the same source is already queued or running.
 * Stale QUEUE entries are evicted automatically (they preserve artifacts); only
 * an actively running chapter requires explicit user confirmation because
 * cancelling it mid-OCR/inpaint discards in-flight native work.
 */
sealed interface ChapterQueuePreflight {
    /**
     * No conflict: the chapter can be queued immediately. Stale QUEUE entries
     * of the same source (if any) are evicted automatically by translateChapter.
     */
    data object NoConflict : ChapterQueuePreflight

    /**
     * Another chapter of the same source is actively translating. The UI should
     * ask the user to confirm cancellation of [chapterId] before proceeding.
     * On confirm, call
     * [eu.kanade.translation.orchestration.TranslationManager.cancelRunningChapterForReplace]
     * then [eu.kanade.translation.orchestration.TranslationManager.translateChapter].
     */
    data class RunningConflict(
        val chapterId: Long,
        val chapterName: String,
    ) : ChapterQueuePreflight
}
