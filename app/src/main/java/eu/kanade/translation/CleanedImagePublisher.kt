package eu.kanade.translation

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/** File-system-independent publication ordering used by both translation paths. */
class CleanedImagePublisher(
    private val files: Files,
) {
    interface Files {
        fun writeVerifiedVersionedFile(): String
        fun delete(name: String): Boolean
    }

    suspend fun publish(
        chapter: String,
        pageKey: String,
        previousName: String?,
        commit: suspend (newName: String) -> ChapterTranslationStore.PatchResult,
    ): Result {
        val newName = try {
            files.writeVerifiedVersionedFile()
        } catch (failure: Throwable) {
            logcat(LogPriority.ERROR, failure) {
                "TachiyomiAT cleaned publication failed: chapter=$chapter pageKey=$pageKey phase=write"
            }
            return Result.WriteFailed(failure)
        }
        return when (val patch = commit(newName)) {
            is ChapterTranslationStore.PatchResult.Accepted -> {
                if (previousName != null && previousName != newName && !files.delete(previousName)) {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT cleaned publication old-file cleanup failed: chapter=$chapter " +
                            "pageKey=$pageKey old=$previousName new=$newName"
                    }
                }
                Result.Published(newName, patch.snapshot)
            }
            is ChapterTranslationStore.PatchResult.Rejected -> {
                val cleaned = files.delete(newName)
                logcat(LogPriority.WARN) {
                    "TachiyomiAT cleaned publication rejected: chapter=$chapter pageKey=$pageKey " +
                        "new=$newName reason=${patch.reason} unpublishedCleaned=$cleaned"
                }
                Result.Rejected(newName, patch.reason, cleaned)
            }
        }
    }

    sealed interface Result {
        data class Published(
            val name: String,
            val snapshot: ChapterTranslationStore.PageSnapshot,
        ) : Result
        data class Rejected(val name: String, val reason: String, val unpublishedCleaned: Boolean) : Result
        data class WriteFailed(val failure: Throwable) : Result
    }
}
