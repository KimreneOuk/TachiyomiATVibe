package eu.kanade.tachiyomi.ui.manga

import eu.kanade.translation.model.TranslationProgressSnapshot
import java.util.concurrent.ConcurrentHashMap

/**
 *  slice 1: screen-scoped keyed store of the last live/terminal batch
 * translation snapshot per chapter id.
 *
 * Full chapter-list rebuilds (manga DB, download cache, download queue,
 * translation queue, pending request emissions) reconstruct every
 * [ChapterList.Item] without progress, the canonical per-chapter flow does not
 * replay an unchanged snapshot while its collector is active, and the collector
 * is cancelled at terminal status. Reading item progress through this registry
 * means neither an unrelated rebuild nor collector cancellation can erase the
 * last live or terminal snapshot.
 *
 * Bounded by construction: at most one compact snapshot per chapter of this
 * screen, for the lifetime of the screen model. Backed by a
 * [ConcurrentHashMap]: writes arrive from the per-chapter collectors'
 * `withUIContext` delivery and reads can happen from list rebuilds on IO
 * dispatchers, so the map must be safe across both threads.
 */
internal class ChapterTranslationSnapshotRegistry {

    private val snapshots = ConcurrentHashMap<Long, TranslationProgressSnapshot>()

    /** Records the latest canonical snapshot; a null emission retains the previous value. */
    fun remember(chapterId: Long, snapshot: TranslationProgressSnapshot?) {
        if (snapshot != null) snapshots[chapterId] = snapshot
    }

    fun snapshotFor(chapterId: Long?): TranslationProgressSnapshot? =
        chapterId?.let { snapshots[it] }

    /** Drops the snapshot when its data is explicitly invalidated (chapter reset/delete). */
    fun forget(chapterId: Long) {
        snapshots.remove(chapterId)
    }

    fun size(): Int = snapshots.size
}

/**
 * Attaches retained snapshots to freshly rebuilt chapter-list items. An item
 * that already carries a live snapshot keeps it; the registry only fills the
 * gaps left by the reconstruction.
 */
internal fun List<ChapterList.Item>.carryingTranslationSnapshots(
    registry: ChapterTranslationSnapshotRegistry,
): List<ChapterList.Item> = map { item ->
    if (item.translationProgress != null) {
        item
    } else {
        item.copy(translationProgress = registry.snapshotFor(item.id))
    }
}
