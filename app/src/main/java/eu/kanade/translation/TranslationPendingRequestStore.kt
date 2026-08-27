package eu.kanade.translation

import android.content.Context
import androidx.core.content.edit
import eu.kanade.translation.model.TranslationRequestPhase

/**
 * Persists translation requests that have not reached the translation queue.
 *
 * Queue membership is owned by [TranslationQueueStore]. These requests are a
 * separate, short-lived intent because a chapter without files cannot safely
 * enter the translation worker yet. Keeping the intent durable makes a
 * process death during preparation explainable and lets the downloader hand
 * the chapter to the batch worker after restart.
 */
class TranslationPendingRequestStore(
    context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun load(): Set<Long> = preferences.all.keys.mapNotNull { it.toLongOrNull() }.toSet()

    @Synchronized
    fun add(chapterId: Long, phase: TranslationRequestPhase, reason: String?) {
        preferences.edit(commit = true) {
            putString(chapterId.toString(), phase.name)
            if (reason.isNullOrBlank()) {
                remove(reasonKey(chapterId))
            } else {
                putString(reasonKey(chapterId), reason)
            }
        }
    }

    @Synchronized
    fun phase(chapterId: Long): TranslationRequestPhase? =
        preferences.getString(chapterId.toString(), null)
            ?.let { value -> runCatching { TranslationRequestPhase.valueOf(value) }.getOrNull() }

    @Synchronized
    fun reason(chapterId: Long): String? = preferences.getString(reasonKey(chapterId), null)

    private fun reasonKey(chapterId: Long): String = "$chapterId.reason"

    @Synchronized
    fun remove(chapterId: Long) {
        preferences.edit(commit = true) {
            remove(chapterId.toString())
            remove(reasonKey(chapterId))
        }
    }

    @Synchronized
    fun clear() {
        preferences.edit(commit = true) { clear() }
    }

    private companion object {
        const val PREFS_NAME = "translation_pending_requests"
    }
}
