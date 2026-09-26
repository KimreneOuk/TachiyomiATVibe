package eu.kanade.translation.orchestration

/**
 * Queue-admission result returned before replacing or adding chapter work.
 *
 * Stale queued entries can be evicted automatically because no stage work has
 * started. Replacing an actively translating chapter requires confirmation,
 * since cancellation can discard in-flight OCR or inpainting work.
 */
sealed interface ChapterQueuePreflight {
    /**
     * No conflict: the chapter can be queued immediately. Stale QUEUE entries
     * of the same source (if any) are evicted automatically by translateChapter.
     */
    data object NoConflict : ChapterQueuePreflight

    /**
     * Another chapter of the same source is actively translating. The UI should
     * confirm cancellation of [chapterId] before calling
     * [TranslationManager.cancelRunningChapterForReplace] and
     * [TranslationManager.translateChapter].
     */
    data class RunningConflict(
        val chapterId: Long,
        val chapterName: String,
    ) : ChapterQueuePreflight
}
