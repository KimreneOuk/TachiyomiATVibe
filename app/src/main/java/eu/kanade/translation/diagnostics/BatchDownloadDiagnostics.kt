package eu.kanade.translation.diagnostics

import eu.kanade.translation.model.TranslationRequestFailureKind
import eu.kanade.translation.model.TranslationRequestPhase
import logcat.LogPriority
import logcat.logcat
import java.util.Locale

internal enum class BatchDownloadStage {
    TEMP_LOOKUP,
    TEMP_DELETE,
    DIRECTORY_LIST,
    RESOLVE_IMAGE_URL,
    HTTP_FETCH,
    CREATE_TEMP,
    WRITE_TEMP,
    DETECT_TYPE,
    RENAME_TEMP,
    CACHE_COPY,
    SPLIT,
    METADATA,
    ARCHIVE,
    RENAME,
    CACHE,
    REKEY,
    ADMISSION,
}

internal enum class BatchDownloadResult {
    START,
    SUCCESS,
    FAILED,
    FALSE,
    NULL,
    THREW,
    CANCELLED,
}

internal enum class BatchDownloadTerminalState {
    ERROR,
    DOWNLOADED,
}

internal enum class BatchDownloadTraceBoundary {
    CHAPTER_START,
    PAGE,
    VALIDATION,
    FINALIZATION,
    DOWNLOAD_TERMINAL,
    HANDOFF,
}

internal enum class BatchDownloadCause {
    SOURCE,
    NETWORK,
    HTTP,
    STORAGE,
    INVALID_PAGE,
    CANCELLED,
    QUEUE_CLEARED,
    DOWNLOADER_STOPPED,
    UNKNOWN,
}

internal enum class BatchDownloadQueueResult {
    ENQUEUED,
    ALREADY_DOWNLOADED,
    ALREADY_QUEUED,
    UNSUPPORTED_SOURCE,
}

/** Bounded correlation state for one physical chapter download. */
internal class BatchDownloadTraceContext(
    private val chapterId: Long,
    private val generationProvider: () -> Long?,
) {
    private var observedGeneration: Long? = generationProvider()

    @Synchronized
    fun generation(boundary: BatchDownloadTraceBoundary): Long? {
        val current = generationProvider()
        if (current != observedGeneration) {
            BatchDownloadDiagnostics.attachment(
                chapterId = chapterId,
                fromGeneration = observedGeneration,
                toGeneration = current,
                boundary = boundary,
            )
            observedGeneration = current
        }
        return current
    }
}

/**
 * Privacy-safe, bounded trace for translation-driven chapter downloads.
 *
 * Every string accepted by the formatter originates from a controlled enum or
 * a throwable class name. User content, URLs, paths, and throwable messages
 * are intentionally not accepted by this API.
 */
internal object BatchDownloadDiagnostics {
    const val TAG = "BatchDownloadTrace"
    private const val SCHEMA_VERSION = 1
    internal const val ERROR_PAGE_LIMIT = 8
    private val safeTokenPattern = Regex("[A-Za-z0-9_.,\\$-]+")

    @Volatile
    internal var recordObserver: ((String) -> Unit)? = null

    fun attachment(
        chapterId: Long,
        fromGeneration: Long?,
        toGeneration: Long?,
        boundary: BatchDownloadTraceBoundary,
    ) = emit(
        event = "attachment",
        chapterId = chapterId,
        generation = toGeneration,
        "from_generation" to (fromGeneration?.toString() ?: "none"),
        "to_generation" to (toGeneration?.toString() ?: "none"),
        "boundary" to token(boundary),
    )

    fun requestPhase(
        chapterId: Long,
        generation: Long,
        from: TranslationRequestPhase?,
        to: TranslationRequestPhase,
        failureKind: TranslationRequestFailureKind,
    ) = emit(
        event = "request_phase",
        chapterId = chapterId,
        generation = generation,
        "from" to token(from),
        "to" to token(to),
        "failure_kind" to token(failureKind),
    )

    fun requestCleared(chapterId: Long, generation: Long?, from: TranslationRequestPhase?) = emit(
        event = "request_cleared",
        chapterId = chapterId,
        generation = generation,
        "from" to token(from),
    )

    fun queueResult(
        chapterId: Long,
        generation: Long,
        result: BatchDownloadQueueResult,
        queueSize: Int,
        startRequested: Boolean,
    ) = emit(
        event = "queue_result",
        chapterId = chapterId,
        generation = generation,
        "result" to token(result),
        "queue_size" to queueSize.toString(),
        "start_requested" to startRequested.toString(),
    )

    fun chapterStart(
        chapterId: Long,
        generation: Long,
        pageTotal: Int,
        resumedReady: Int,
        saveAsCbz: Boolean,
    ) = emit(
        event = "chapter_start",
        chapterId = chapterId,
        generation = generation,
        "page_total" to pageTotal.toString(),
        "resumed_ready" to resumedReady.toString(),
        "save_as_cbz" to saveAsCbz.toString(),
    )

    fun pageAttemptFailed(
        chapterId: Long,
        generation: Long,
        pageIndex: Int,
        pageNumber: Int,
        attempt: Int,
        stage: BatchDownloadStage,
        cause: BatchDownloadCause,
        error: Throwable?,
    ) = emit(
        event = "page_attempt_failed",
        chapterId = chapterId,
        generation = generation,
        "page_index" to pageIndex.toString(),
        "page_number" to pageNumber.toString(),
        "attempt" to attempt.coerceIn(1, 4).toString(),
        "stage" to token(stage),
        "cause" to token(cause),
        "error_class" to errorClass(error),
    )

    fun pageTerminalFailed(
        chapterId: Long,
        generation: Long,
        pageIndex: Int,
        pageNumber: Int,
        stage: BatchDownloadStage,
        cause: BatchDownloadCause,
        error: Throwable?,
    ) = emit(
        event = "page_terminal_failed",
        chapterId = chapterId,
        generation = generation,
        "page_index" to pageIndex.toString(),
        "page_number" to pageNumber.toString(),
        "stage" to token(stage),
        "cause" to token(cause),
        "error_class" to errorClass(error),
    )

    fun pathOperation(
        chapterId: Long,
        generation: Long,
        pageIndex: Int?,
        pageNumber: Int?,
        stage: BatchDownloadStage,
        result: BatchDownloadResult,
        error: Throwable? = null,
    ) = emit(
        event = "path_operation",
        chapterId = chapterId,
        generation = generation,
        "page_index" to count(pageIndex),
        "page_number" to count(pageNumber),
        "stage" to token(stage),
        "result" to token(result),
        "error_class" to errorClass(error),
    )

    fun validation(
        chapterId: Long,
        generation: Long,
        expected: Int,
        ready: Int,
        onDisk: Int?,
        errorCount: Int,
        errorPages: List<Int>,
    ) = emit(validationRecord(chapterId, generation, expected, ready, onDisk, errorCount, errorPages))

    fun finalization(
        chapterId: Long,
        generation: Long,
        stage: BatchDownloadStage,
        result: BatchDownloadResult,
        error: Throwable? = null,
    ) = emit(
        event = "finalization",
        chapterId = chapterId,
        generation = generation,
        "stage" to token(stage),
        "result" to token(result),
        "error_class" to errorClass(error),
    )

    fun downloadTerminal(
        chapterId: Long,
        generation: Long,
        state: BatchDownloadTerminalState,
        cause: BatchDownloadCause,
        expected: Int? = null,
        ready: Int? = null,
        onDisk: Int? = null,
        error: Throwable? = null,
    ) = emit(
        event = "download_terminal",
        chapterId = chapterId,
        generation = generation,
        "state" to token(state),
        "cause" to token(cause),
        "expected" to count(expected),
        "ready" to count(ready),
        "on_disk" to count(onDisk),
        "error_class" to errorClass(error),
    )

    fun handoff(
        chapterId: Long,
        generation: Long,
        stage: BatchDownloadStage,
        result: BatchDownloadResult,
        error: Throwable? = null,
    ) = emit(
        event = "handoff",
        chapterId = chapterId,
        generation = generation,
        "stage" to token(stage),
        "result" to token(result),
        "error_class" to errorClass(error),
    )

    internal fun validationRecord(
        chapterId: Long,
        generation: Long,
        expected: Int,
        ready: Int,
        onDisk: Int?,
        errorCount: Int,
        errorPages: List<Int>,
    ): String {
        val boundedPages = errorPages.take(ERROR_PAGE_LIMIT)
        return record(
            event = "validation",
            chapterId = chapterId,
            generation = generation,
            "expected" to expected.toString(),
            "ready" to ready.toString(),
            "on_disk" to count(onDisk),
            "error_count" to errorCount.toString(),
            "error_pages" to if (boundedPages.isEmpty()) "none" else boundedPages.joinToString(","),
            "error_pages_truncated" to (errorCount > ERROR_PAGE_LIMIT).toString(),
        )
    }

    internal fun errorClass(error: Throwable?): String =
        error?.javaClass?.simpleName?.takeIf { safeTokenPattern.matches(it) } ?: "none"

    internal fun safeToken(value: String): String =
        value.takeIf { safeTokenPattern.matches(it) } ?: "invalid"

    private fun token(value: Enum<*>?): String =
        value?.name?.lowercase(Locale.ROOT) ?: "none"

    private fun count(value: Int?): String = value?.toString() ?: "none"

    private fun emit(record: String) {
        recordObserver?.invoke(record)
        logcat(tag = TAG, priority = LogPriority.INFO) { record }
    }

    private fun emit(
        event: String,
        chapterId: Long,
        generation: Long?,
        vararg fields: Pair<String, String>,
    ) = emit(record(event, chapterId, generation, *fields))

    private fun record(
        event: String,
        chapterId: Long,
        generation: Long?,
        vararg fields: Pair<String, String>,
    ): String = buildString {
        append("schema=")
        append(SCHEMA_VERSION)
        append(" event=")
        append(safeToken(event))
        append(" chapter_id=")
        append(chapterId)
        append(" generation=")
        append(generation ?: "none")
        fields.forEach { (key, value) ->
            append(' ')
            append(safeToken(key))
            append('=')
            append(safeToken(value))
        }
    }
}
