package eu.kanade.translation.storage

import eu.kanade.translation.*

import kotlinx.coroutines.CancellationException
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

    /**
     * Publishes one verified cleaned-image file, then commits its name to the
     * store. The previous file is deleted only after the commit is accepted
     * AND [mayDeletePrevious] allows it: a file that still backs a committed
     * display bundle (last-known-good) must survive until a newer bundle
     * promotes, so the reader never resolves a deleted file (lifecycle
     * contract §15).
     */
    suspend fun publish(
        chapter: String,
        pageKey: String,
        previousName: String?,
        commit: suspend (newName: String) -> ChapterTranslationStore.PatchResult,
        mayDeletePrevious: (String) -> Boolean = { true },
        retirePrevious: ((String, () -> Unit) -> Unit)? = null,
    ): Result {
        val newName = try {
            files.writeVerifiedVersionedFile()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            logcat(LogPriority.ERROR, failure) {
                "TachiyomiAT cleaned publication failed: chapter=$chapter pageKey=$pageKey phase=write"
            }
            return Result.WriteFailed(failure)
        }
        val patch = try {
            commit(newName)
        } catch (cancelled: CancellationException) {
            runCatching { files.delete(newName) }
            throw cancelled
        } catch (failure: Throwable) {
            val cleaned = runCatching { files.delete(newName) }.getOrDefault(false)
            logcat(LogPriority.ERROR, failure) {
                "TachiyomiAT cleaned publication failed: chapter=$chapter pageKey=$pageKey phase=commit " +
                    "unpublishedCleaned=$cleaned"
            }
            return Result.Rejected(
                name = newName,
                reason = failure.message ?: failure::class.java.simpleName,
                unpublishedCleaned = cleaned,
            )
        }
        return when (patch) {
            is ChapterTranslationStore.PatchResult.Accepted -> {
                if (previousName != null && previousName != newName) {
                    when {
                        !mayDeletePrevious(previousName) -> logcat(LogPriority.INFO) {
                            "TachiyomiAT cleaned publication retained previous file (committed display): " +
                                "chapter=$chapter pageKey=$pageKey old=$previousName new=$newName"
                        }
                        retirePrevious != null -> retirePrevious(previousName) {
                            files.delete(previousName)
                        }
                        !files.delete(previousName) -> logcat(LogPriority.WARN) {
                            "TachiyomiAT cleaned publication old-file cleanup failed: chapter=$chapter " +
                                "pageKey=$pageKey old=$previousName new=$newName"
                        }
                    }
                }
                Result.Published(newName, patch.snapshot)
            }
            is ChapterTranslationStore.PatchResult.Rejected -> {
                val cleaned = runCatching { files.delete(newName) }.getOrDefault(false)
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
