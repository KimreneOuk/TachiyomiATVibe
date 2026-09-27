package eu.kanade.translation.workflow

import eu.kanade.translation.model.Translation

/**
 * Queue fields needed to apply same-source admission rules without constructing
 * a full [Translation] and its source dependency.
 */
data class QueuedChapterView(
    val chapterId: Long,
    val chapterName: String,
    val sourceId: Long,
    val status: Translation.State,
)

/**
 * Returns the first active same-source chapter that conflicts with a new request.
 *
 * Queued entries are handled separately: removing a not-yet-started item keeps
 * its artifacts, while cancelling a running item can discard native stage work.
 */
fun findRunningSameSourceConflict(
    queue: List<QueuedChapterView>,
    requestedChapterId: Long,
    sourceId: Long,
): QueuedChapterView? = queue
    .asSequence()
    .filter { it.chapterId != requestedChapterId }
    .filter { it.sourceId == sourceId }
    .filter { it.status == Translation.State.TRANSLATING }
    .firstOrNull()

/**
 * Selects queued same-source chapters to remove when admitting [keepChapterId].
 * Eviction drops only the queue entry and leaves each chapter's artifacts intact.
 */
fun staleQueuedChaptersToEvict(
    queue: List<QueuedChapterView>,
    keepChapterId: Long,
    sourceId: Long,
): List<QueuedChapterView> = queue
    .asSequence()
    .filter { it.chapterId != keepChapterId }
    .filter { it.sourceId == sourceId }
    .filter { it.status == Translation.State.QUEUE }
    .toList()

/**
 * Maps a [Translation] queue entry to the minimal [QueuedChapterView] used by
 * the pure conflict/eviction helpers. Entries with no chapter id are dropped by
 * the callers (they cannot conflict with a known requested chapter id).
 */
fun Translation.toQueuedChapterView(): QueuedChapterView = QueuedChapterView(
    chapterId = chapter.id ?: -1L,
    chapterName = chapter.name,
    sourceId = source.id,
    status = status,
)
