package eu.kanade.translation.persistence.queue

import android.content.Context
import androidx.core.content.edit
import eu.kanade.translation.model.TranslationRequestFailureKind
import eu.kanade.translation.model.TranslationRequestPhase

/**
 * One durable pending-request record ( slice 2).
 *
 * Backward compatible with the legacy format (phase + free-text reason only):
 * entries written before slice 2 parse with [generation] 0, no group, no
 * timestamps and failure kind [TranslationRequestFailureKind.NONE].
 */
data class TranslationPendingRequestRecord(
    val chapterId: Long,
    val phase: TranslationRequestPhase,
    val reason: String? = null,
    /** Monotonically increasing per-chapter request generation. */
    val generation: Long = 0,
    /** Optional multi-chapter batch group this request belongs to. */
    val groupId: String? = null,
    val failureKind: TranslationRequestFailureKind = TranslationRequestFailureKind.NONE,
    val createdAtEpochMs: Long = 0,
    val updatedAtEpochMs: Long = 0,
)

/**
 * Persists translation requests that have not reached the translation queue.
 *
 * Queue membership is owned by [TranslationQueueStore]. These requests are a
 * separate, short-lived intent because a chapter without files cannot safely
 * enter the translation worker yet. Keeping the intent durable makes a
 * process death during preparation explainable and lets the downloader hand
 * the chapter to the batch worker after restart.
 *
 * The per-chapter generation counter survives [remove] as a tombstone key so
 * a request re-created after a cancel can never reuse the cancelled request's
 * generation (the fence for late downloader callbacks). Tombstones are one
 * tiny key per chapter and are filtered out of [load] by the numeric-key rule.
 */
class TranslationPendingRequestStore(
    context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun load(): Set<Long> = preferences.all.keys.mapNotNull { it.toLongOrNull() }.toSet()

    /** Reads the full record, or null when no pending request exists for the chapter. */
    @Synchronized
    fun record(chapterId: Long): TranslationPendingRequestRecord? {
        val phase = phase(chapterId) ?: return null
        return TranslationPendingRequestRecord(
            chapterId = chapterId,
            phase = phase,
            reason = reason(chapterId),
            generation = preferences.getLong(generationKey(chapterId), 0L),
            groupId = preferences.getString(groupKey(chapterId), null),
            failureKind = preferences.getString(failureKindKey(chapterId), null)
                ?.let { value -> runCatching { TranslationRequestFailureKind.valueOf(value) }.getOrNull() }
                ?: TranslationRequestFailureKind.NONE,
            createdAtEpochMs = preferences.getLong(createdAtKey(chapterId), 0L),
            updatedAtEpochMs = preferences.getLong(updatedAtKey(chapterId), 0L),
        )
    }

    /**
     * The durable per-chapter generation counter. Equal to the live record's
     * generation while a request exists; survives [remove] as a tombstone so
     * re-requests always observe a fresh generation.
     */
    @Synchronized
    fun generation(chapterId: Long): Long = preferences.getLong(generationKey(chapterId), 0L)

    /** Pure persistence write — the caller owns generation/timestamp values. */
    @Synchronized
    fun add(record: TranslationPendingRequestRecord) {
        preferences.edit(commit = true) {
            putString(record.chapterId.toString(), record.phase.name)
            if (record.reason.isNullOrBlank()) {
                remove(reasonKey(record.chapterId))
            } else {
                putString(reasonKey(record.chapterId), record.reason)
            }
            putLong(generationKey(record.chapterId), record.generation)
            if (record.groupId.isNullOrBlank()) {
                remove(groupKey(record.chapterId))
            } else {
                putString(groupKey(record.chapterId), record.groupId)
            }
            if (record.failureKind == TranslationRequestFailureKind.NONE) {
                remove(failureKindKey(record.chapterId))
            } else {
                putString(failureKindKey(record.chapterId), record.failureKind.name)
            }
            if (record.createdAtEpochMs > 0) {
                putLong(createdAtKey(record.chapterId), record.createdAtEpochMs)
            }
            if (record.updatedAtEpochMs > 0) {
                putLong(updatedAtKey(record.chapterId), record.updatedAtEpochMs)
            }
        }
    }

    /**
     * Legacy phase/reason write kept for compatibility: preserves the
     * record's generation/group and refreshes its timestamps.
     */
    @Synchronized
    fun add(chapterId: Long, phase: TranslationRequestPhase, reason: String?) {
        val existing = record(chapterId)
        val now = System.currentTimeMillis()
        add(
            TranslationPendingRequestRecord(
                chapterId = chapterId,
                phase = phase,
                reason = reason,
                generation = existing?.generation ?: generation(chapterId),
                groupId = existing?.groupId,
                failureKind = existing?.failureKind ?: TranslationRequestFailureKind.NONE,
                createdAtEpochMs = existing?.createdAtEpochMs ?: now,
                updatedAtEpochMs = now,
            ),
        )
    }

    @Synchronized
    fun phase(chapterId: Long): TranslationRequestPhase? =
        preferences.getString(chapterId.toString(), null)
            ?.let { value -> runCatching { TranslationRequestPhase.valueOf(value) }.getOrNull() }

    @Synchronized
    fun reason(chapterId: Long): String? = preferences.getString(reasonKey(chapterId), null)

    private fun reasonKey(chapterId: Long): String = "$chapterId.reason"

    private fun generationKey(chapterId: Long): String = "$chapterId.generation"

    private fun groupKey(chapterId: Long): String = "$chapterId.group"

    private fun failureKindKey(chapterId: Long): String = "$chapterId.failureKind"

    private fun createdAtKey(chapterId: Long): String = "$chapterId.createdAt"

    private fun updatedAtKey(chapterId: Long): String = "$chapterId.updatedAt"

    @Synchronized
    fun remove(chapterId: Long) {
        preferences.edit(commit = true) {
            remove(chapterId.toString())
            remove(reasonKey(chapterId))
            remove(groupKey(chapterId))
            remove(failureKindKey(chapterId))
            remove(createdAtKey(chapterId))
            remove(updatedAtKey(chapterId))
            // Tombstone: bump and keep the generation counter so a re-request
            // after this removal can never reuse the removed generation.
            putLong(generationKey(chapterId), generation(chapterId) + 1)
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
