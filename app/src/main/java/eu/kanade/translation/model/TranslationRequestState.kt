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

    /**
     * T911 slice 2: explicit terminal state for a request whose download was
     * cancelled/removed/cleared on the downloader side. A late completion
     * callback can never revive it (the generation fence drops it).
     */
    CANCELLED,

    /**
     * T911 slice 2 (R10): the chapter was ready but the translation queue
     * refused admission (non-HTTP source, invalid config). Never labeled as a
     * download failure — the files are fine.
     */
    ADMISSION_FAILED,
}

/**
 * T911 slice 2: typed last-failure classification for a pending request.
 * Replaces free-text-only reasons where practical; [reason] keeps the human
 * detail alongside the kind.
 */
enum class TranslationRequestFailureKind {
    /** No failure recorded (live request). */
    NONE,

    /** The chapter download pipeline failed (legacy path). */
    DOWNLOAD_FAILED,

    /** Storage/provider failure before or during the download. */
    STORAGE,

    /** The download was cancelled or removed by the user. */
    CANCELLED,

    /** The whole download queue was cleared. */
    QUEUE_CLEARED,

    /** The downloader stopped (offline, Wi-Fi policy, generic stop). */
    DOWNLOADER_STOPPED,

    /** No capable source exists for the chapter (missing/unsupported source). */
    SOURCE_UNSUPPORTED,

    /** The translation queue refused admission for a non-config reason. */
    QUEUE_ADMISSION_FAILED,

    /** The translation configuration is invalid (language/engine/ML Kit). */
    CONFIG_INVALID,

    /** Startup reconciliation found neither a queue nor files (interrupted). */
    INTERRUPTED,
}

@Immutable
data class TranslationRequestState(
    val chapterId: Long,
    val phase: TranslationRequestPhase,
    val reason: String? = null,
    /**
     * T911 slice 2: monotonic per-chapter request generation. Bumped on every
     * new request and on cancel; used to fence late download callbacks and
     * in-flight probe mutations (R7).
     */
    val generation: Long = 0,
    /** T911 slice 2: typed last-failure kind (pairs with [reason]). */
    val failureKind: TranslationRequestFailureKind = TranslationRequestFailureKind.NONE,
) {
    val isTerminal: Boolean
        get() = phase == TranslationRequestPhase.DOWNLOAD_FAILED ||
            phase == TranslationRequestPhase.CANCELLED ||
            phase == TranslationRequestPhase.ADMISSION_FAILED
}

/**
 * T911 slice 2 (R10): classifies a translation-queue admission rejection.
 * The source check runs first (a non-HTTP source can never be queued); an
 * invalid configuration is its own kind so the UI never shows a false
 * "Download failed" for an already-downloaded chapter.
 */
fun translationQueueAdmissionFailureKind(
    sourceIsHttp: Boolean,
    configValid: Boolean,
): TranslationRequestFailureKind = when {
    sourceIsHttp && configValid -> TranslationRequestFailureKind.QUEUE_ADMISSION_FAILED
    sourceIsHttp -> TranslationRequestFailureKind.CONFIG_INVALID
    else -> TranslationRequestFailureKind.SOURCE_UNSUPPORTED
}
