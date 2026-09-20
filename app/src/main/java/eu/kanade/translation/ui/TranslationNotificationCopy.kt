package eu.kanade.translation.ui

import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot

/**
 *  Phase 5 (spec §2, §3.3): the batch translation notification's text is
 * a pure projection of the progress snapshot, so truthful terminal,
 * unknown-total, not-saved, paused, and cancelled copy is unit-testable. The
 * foreground service renders this record and attaches intents only for the
 * actions it can actually deliver (STOP always; RETRY when a chapter target
 * exists); it must not invent wording that contradicts [Copy.text].
 */
enum class NotificationAction { STOP, RETRY, DETAILS }

data class TranslationNotificationRecord(
    val title: String,
    val text: String,
    val ongoing: Boolean,
    val actions: Set<NotificationAction>,
    val retryAtEpochMs: Long?,
)

object TranslationNotificationCopy {

    /**
     * Precedence (spec §1.2, §5): explicit cancellation first, then the
     * persistence rejection, then pauses, then running progress (trusted
     * totals keep numeric progress; untrusted totals never look complete),
     * then durable terminal states.
     */
    fun of(chapterName: String, snapshot: TranslationProgressSnapshot?): TranslationNotificationRecord {
        if (snapshot == null) {
            return TranslationNotificationRecord(
                title = chapterName,
                text = "Preparing batch translation...",
                ongoing = true,
                actions = setOf(NotificationAction.STOP),
                retryAtEpochMs = null,
            )
        }
        return when {
            // Explicit cancellation of paid work is acknowledged, never silent.
            snapshot.aborted -> TranslationNotificationRecord(
                title = chapterName,
                text = "Batch translation cancelled — saved pages kept",
                ongoing = false,
                actions = setOf(NotificationAction.RETRY),
                retryAtEpochMs = null,
            )

            // A guarded publication was rejected: no completion copy, retry is
            // required (spec §4, condition C).   the typed rejection
            // reason rides along (bounded) — the bare "not saved" copy hid
            // which seam rejected the publication.
            snapshot.nonDurableFailure -> {
                val reason = snapshot.nonDurableFailureReason
                    ?.takeIf { it.isNotBlank() }
                    ?.let { it.take(120) }
                TranslationNotificationRecord(
                    title = chapterName,
                    text = if (reason != null) {
                        "Translation not saved — retry required: $reason"
                    } else {
                        "Translation not saved — retry required"
                    },
                    ongoing = false,
                    actions = setOf(NotificationAction.RETRY, NotificationAction.STOP),
                    retryAtEpochMs = null,
                )
            }

            snapshot.state == Translation.State.PAUSED || snapshot.nextEligibleRetryAtEpochMs != null -> {
                val reason = snapshot.pauseReason?.takeIf { it.isNotBlank() }
                    ?: "provider work temporarily unavailable"
                val epoch = snapshot.nextEligibleRetryAtEpochMs
                if (epoch != null) {
                    // Batch-owned pauses retry automatically: no Retry button.
                    TranslationNotificationRecord(
                        title = chapterName,
                        text = "Paused — $reason · automatic retry when eligible",
                        ongoing = false,
                        actions = setOf(NotificationAction.STOP),
                        retryAtEpochMs = epoch,
                    )
                } else {
                    TranslationNotificationRecord(
                        title = chapterName,
                        text = "Paused — $reason · manual retry available",
                        ongoing = false,
                        actions = setOf(NotificationAction.RETRY, NotificationAction.STOP),
                        retryAtEpochMs = null,
                    )
                }
            }

            snapshot.state == Translation.State.ERROR -> TranslationNotificationRecord(
                title = chapterName,
                text = "Translation failed — retry available",
                ongoing = false,
                actions = setOf(NotificationAction.RETRY, NotificationAction.STOP),
                retryAtEpochMs = null,
            )

            snapshot.state == Translation.State.TRANSLATING ||
                snapshot.state == Translation.State.QUEUE -> {
                val successes = (snapshot.donePages - snapshot.failedCount).coerceAtLeast(0)
                val text = when {
                    snapshot.totalPages <= 0 -> "Preparing batch translation..."
                    snapshot.expectedPageCountTrusted ->
                        "$successes of ${snapshot.totalPages} pages translated"
                    // Unknown source total: never render a fraction or percent
                    // that implies the chapter is nearly complete (spec §2.1).
                    else -> "${snapshot.totalPages} pages available · source total unknown"
                }
                TranslationNotificationRecord(
                    title = chapterName,
                    text = text,
                    ongoing = true,
                    actions = setOf(NotificationAction.STOP),
                    retryAtEpochMs = null,
                )
            }

            snapshot.state == Translation.State.READY_WITH_WARNINGS ->
                TranslationNotificationRecord(
                    title = chapterName,
                    text = "Translation complete with warnings — review flagged pages",
                    ongoing = false,
                    actions = setOf(NotificationAction.DETAILS),
                    retryAtEpochMs = null,
                )

            else -> TranslationNotificationRecord(
                title = chapterName,
                text = "Translation complete — ready to read",
                ongoing = false,
                actions = setOf(NotificationAction.DETAILS),
                retryAtEpochMs = null,
            )
        }
    }
}
