package eu.kanade.translation.batch

import eu.kanade.translation.model.TranslationProgressSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns live batch trackers and detached terminal progress snapshots.
 *
 * Terminal snapshots are copied before the live tracker is disposed so UI can observe the final
 * result without retaining a tracker or its store. The bounded cache uses access order.
 */
internal class TranslationBatchTrackerRegistry(
    private val maxTerminalSnapshots: Int = DEFAULT_MAX_TERMINAL_SNAPSHOTS,
) {
    private val liveTrackers = LinkedHashMap<Long, TranslationBatchProgressTracker>()
    private val terminalSnapshots = object : LinkedHashMap<Long, TranslationProgressSnapshot>(
        maxTerminalSnapshots,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Long, TranslationProgressSnapshot>?,
        ): Boolean = size > maxTerminalSnapshots
    }

    private val _live = MutableStateFlow<Map<Long, TranslationBatchProgressTracker>>(emptyMap())
    val live: StateFlow<Map<Long, TranslationBatchProgressTracker>> = _live.asStateFlow()
    private val _terminal = MutableStateFlow<Map<Long, TranslationProgressSnapshot>>(emptyMap())
    val terminal: StateFlow<Map<Long, TranslationProgressSnapshot>> = _terminal.asStateFlow()

    @Synchronized
    fun getLive(chapterId: Long): TranslationBatchProgressTracker? = liveTrackers[chapterId]

    @Synchronized
    fun replace(chapterId: Long, tracker: TranslationBatchProgressTracker) {
        liveTrackers.remove(chapterId)?.close()
        liveTrackers[chapterId] = tracker
        publishLive()
    }

    /** Caches the immutable terminal projection before releasing the live tracker. */
    @Synchronized
    fun complete(chapterId: Long, snapshot: TranslationProgressSnapshot) {
        terminalSnapshots[chapterId] = snapshot.detachedCopy()
        publishTerminal()
        liveTrackers.remove(chapterId)?.close()
        publishLive()
    }

    @Synchronized
    fun dispose(chapterId: Long) {
        val removed = liveTrackers.remove(chapterId) ?: return
        removed.close()
        publishLive()
    }

    @Synchronized
    fun terminalSnapshotCacheSize(): Int = terminalSnapshots.size

    @Synchronized
    fun terminalSnapshot(chapterId: Long): TranslationProgressSnapshot? = terminalSnapshots[chapterId]

    private fun publishLive() {
        _live.value = liveTrackers.toMap()
    }

    private fun publishTerminal() {
        _terminal.value = terminalSnapshots.toMap()
    }

    private fun TranslationProgressSnapshot.detachedCopy(): TranslationProgressSnapshot = copy(
        pages = pages.toList(),
        activeStages = activeStages.toSet(),
        perStage = perStage.toMap(),
        groupedFailures = groupedFailures.mapValues { (_, pageKeys) -> pageKeys.toList() },
    )

    private companion object {
        const val DEFAULT_MAX_TERMINAL_SNAPSHOTS = 20
    }
}
