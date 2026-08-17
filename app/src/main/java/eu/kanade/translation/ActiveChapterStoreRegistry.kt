package eu.kanade.translation

import eu.kanade.translation.model.PageTranslation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * Chapter-keyed owner of active translation stores.
 *
 * A reader must select a store by its own chapter ID; publishing another chapter's store can
 * never change that selected source. The map flow exists only to rebind a selected chapter when
 * its store is registered or removed.
 */
internal class ActiveChapterStoreRegistry {
    private val stores = LinkedHashMap<Long, ChapterTranslationStore>()
    private val _snapshots = MutableStateFlow<Map<Long, ChapterTranslationStore>>(emptyMap())
    val snapshots: StateFlow<Map<Long, ChapterTranslationStore>> = _snapshots.asStateFlow()

    @Synchronized
    fun get(chapterId: Long): ChapterTranslationStore? = stores[chapterId]

    @Synchronized
    fun register(chapterId: Long, store: ChapterTranslationStore): Boolean {
        if (stores[chapterId] === store) return false
        stores[chapterId] = store
        publish()
        return true
    }

    @Synchronized
    fun remove(chapterId: Long): ChapterTranslationStore? {
        val removed = stores.remove(chapterId) ?: return null
        publish()
        return removed
    }

    @Synchronized
    fun chapterIds(): List<Long> = stores.keys.toList()

    @Synchronized
    fun storesFor(chapterIds: Collection<Long>): List<ChapterTranslationStore> =
        chapterIds.mapNotNull(stores::get)

    /** The currently selected chapter source, or null when that chapter is not active. */
    fun observe(chapterId: Long): StateFlow<Map<String, PageTranslation>>? = get(chapterId)?.state

    /**
     * Rebinds only when this [chapterId]'s source changes. Updates from another chapter's store
     * cannot be emitted through this flow.
     */
    fun select(chapterId: Long): Flow<Map<String, PageTranslation>> = snapshots.flatMapLatest { map ->
        map[chapterId]?.state ?: flowOf(emptyMap())
    }

    private fun publish() {
        _snapshots.value = stores.toMap()
    }
}
