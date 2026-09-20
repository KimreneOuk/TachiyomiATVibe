package eu.kanade.translation.model

/**
 * TachiyomiAT bug 3 fix: minimal view over a queue entry used by the pure
 * conflict/eviction helpers below. Extracted so the logic is unit-testable
 * without constructing a full [Translation] (which requires an HttpSource).
 */
data class QueuedChapterView(
    val chapterId: Long,
    val chapterName: String,
    val sourceId: Long,
    val status: Translation.State,
)

/**
 * TachiyomiAT bug 3 fix: pure conflict detection extracted from
 * [eu.kanade.translation.orchestration.TranslationManager.translateChapterPreflight] so the
 * queue-scan logic is unit-testable without constructing the full manager.
 *
 * Returns the first actively translating chapter of [sourceId] in [queue] that
 * is NOT [requestedChapterId]. Returns null when there is no conflict.
 *
 * Stale QUEUE entries are intentionally ignored here: dropping a not-yet-started
 * queue entry preserves artifacts, so it does not need user confirmation. Only
 * an actively TRANSLATING chapter requires confirmation because cancelling it
 * mid-OCR/inpaint discards in-flight native work.
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
 * TachiyomiAT bug 3 fix: pure selection of stale QUEUE entries to evict when a
 * new chapter of [sourceId] is queued. Returns every queued (status == QUEUE)
 * chapter of [sourceId] that is NOT [keepChapterId]. Evicting these preserves
 * their artifacts (the store is untouched); only the queue entry is dropped.
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
