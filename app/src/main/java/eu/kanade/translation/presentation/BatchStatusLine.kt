package eu.kanade.translation.presentation

/** Bounded vocabulary for chapter-level batch status lines. */
enum class BatchStatusLineKind {
    RESUMING,
    REQUEST_STARTING,
    REQUEST_WAITING_FOR_DOWNLOAD,
    REQUEST_PREPARING,
    REQUEST_DOWNLOAD_FAILED,
    REQUEST_CANCELLED,
    REQUEST_ADMISSION_FAILED,
    QUEUE_POSITION,
    QUEUED_READY,
    PAUSED,

    /** The current batch request is revalidating sources. */
    REBUILDING,

    /** The current batch request is adopting reusable page work. */
    RESTORING,
    BUILDING_CONTEXT,
    FIRST_PASS_STAGES,
    FIRST_PASS_PAGES,
    FINALIZING,
    COMPLETED,

    /**
     * completion oracle: the batch phase finished but the run carries
     * failure/attention pages or a failed/paused/unsaved outcome — the
     * attention state that replaces the celebratory completed copy.
     * [formatArgs] carries the attention page count.
     */
    ATTENTION_REQUIRED,
    NO_ACTIVE_BATCH,
}

/**
 * One chapter-level batch status line. [formatArgs] feeds the
 * surface's string-resource lookup for [kind]; [fallback] is the English
 * source wording for surfaces (or locales) that render without the resource.
 */
data class BatchStatusLine(
    val kind: BatchStatusLineKind,
    val formatArgs: List<Any> = emptyList(),
    val fallback: String,
)
