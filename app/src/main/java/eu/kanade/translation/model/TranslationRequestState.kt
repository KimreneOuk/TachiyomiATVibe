package eu.kanade.translation.model

import androidx.compose.runtime.Immutable

/**
 * Immediate acknowledgement for a batch request before a tracker exists.
 *
 * This is deliberately separate from [Translation.State]: a request waiting
 * for a chapter download is observable work, but it must not be advertised as
 * an active foreground translation job.
 */
enum class TranslationRequestPhase {
    STARTING,
    WAITING_FOR_DOWNLOAD,
    PREPARING,
    DOWNLOAD_FAILED,
}

@Immutable
data class TranslationRequestState(
    val chapterId: Long,
    val phase: TranslationRequestPhase,
    val reason: String? = null,
)
