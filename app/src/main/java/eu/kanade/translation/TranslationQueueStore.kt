package eu.kanade.translation

import android.content.Context
import androidx.core.content.edit

/**
 * TachiyomiAT: persists the batch translation queue across application restarts
 * so a crash mid-batch no longer loses the entire queue.
 *
 * Mirrors the established [eu.kanade.tachiyomi.data.download.DownloadStore]
 * pattern: a private [SharedPreferences] file holds an ordered list of chapter
 * ids (keyed by position). On launch the queue is rehydrated via
 * [Translation.fromChapterId], which rebuilds the full [Translation] (source,
 * manga, chapter, languages) from a single durable key. Deleted chapters
 * self-heal — `fromChapterId` returns null for a gone chapter, so stale ids
 * are silently dropped on rehydration.
 *
 * Only queue MEMBERSHIP + ORDER is persisted here. [Translation.State] is
 * `@Transient` and rebuilt as [Translation.State.QUEUE] on rehydration (per
 * the owner decision: rehydrate but require Start — never auto-start
 * background OCR/LLM work on launch).
 *
 * Why SharedPreferences (not a SQLDelight table): the download queue solves
 * the identical problem (persist an ordered chapter list across restart,
 * rebuild via lookups) with SharedPreferences, so this mirrors that pattern
 * for consistency. A new `translation_queue` table was considered and
 * rejected: it would diverge from the established download-queue mechanism
 * and the `chapters` table's UPDATE triggers would cause version/sync churn
 * if a column were added there.
 */
class TranslationQueueStore(
    context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Persists the ordered list of chapter ids. Clears the store first so
     * removals are reflected (a removed chapter id is simply absent from
     * [chapterIds]). Cheap: one editor batch regardless of queue length.
     */
    fun save(chapterIds: List<Long>) {
        preferences.edit {
            clear()
            chapterIds.forEachIndexed { index, id ->
                putString("$index", id.toString())
            }
        }
    }

    /**
     * Returns the persisted chapter ids in queue order. Returns an empty list
     * if nothing is persisted (first launch, or queue was cleared).
     */
    fun load(): List<Long> {
        val result = mutableListOf<Long>()
        var i = 0
        while (true) {
            val raw = preferences.getString("$i", null) ?: break
            result += raw.toLongOrNull() ?: break
            i++
        }
        return result
    }

    /**
     * Removes all persisted entries. Called when the queue legitimately
     * empties (all chapters translated or explicitly cleared).
     */
    fun clear() {
        preferences.edit { clear() }
    }

    private companion object {
        const val PREFS_NAME = "translation_queue"
    }
}
