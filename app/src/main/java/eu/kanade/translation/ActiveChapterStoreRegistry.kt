package eu.kanade.translation

import eu.kanade.translation.model.PageTranslation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Chapter-keyed owner of active translation stores.
 *
 * A reader must select a store by its own chapter ID; publishing another chapter's store can
 * never change that selected source. The map flow exists only to rebind a selected chapter when
 * its store is registered or removed.
 */
internal class ActiveChapterStoreRegistry {
    private val stores = LinkedHashMap<Long, ChapterTranslationStore>()
    private val fileStores = LinkedHashMap<String, ChapterTranslationStore>()
    private val openingLocks = LinkedHashMap<String, Mutex>()
    private val _snapshots = MutableStateFlow<Map<Long, ChapterTranslationStore>>(emptyMap())
    val snapshots: StateFlow<Map<Long, ChapterTranslationStore>> = _snapshots.asStateFlow()

    @Synchronized
    fun get(chapterId: Long): ChapterTranslationStore? = stores[chapterId]

    @Synchronized
    fun getByFile(fileKey: String): ChapterTranslationStore? = fileStores[fileKey]

    @Synchronized
    fun register(chapterId: Long, store: ChapterTranslationStore): Boolean {
        if (stores.containsKey(chapterId)) return false
        stores[chapterId] = store
        publish()
        return true
    }

    @Synchronized
    fun registerFile(fileKey: String, store: ChapterTranslationStore): Boolean {
        val existing = fileStores[fileKey]
        if (existing != null && existing !== store) return false
        fileStores[fileKey] = store
        return true
    }

    /**
     * Opens one chapter store without holding the registry monitor across disk I/O.
     * Concurrent reader and batch callers share the same per-chapter opening lock,
     * then recheck the registry before invoking [create].
     */
    suspend fun getOrCreate(
        chapterId: Long,
        fileKey: String? = null,
        create: suspend () -> ChapterTranslationStore?,
    ): ChapterTranslationStore? {
        get(chapterId)?.let { return it }
        fileKey?.let { getByFile(it) }?.let { store ->
            return if (register(chapterId, store)) store else get(chapterId)
        }
        val openingLock = synchronized(this) {
            openingLocks.getOrPut(fileKey ?: "chapter:$chapterId") { Mutex() }
        }
        return openingLock.withLock {
            get(chapterId)?.let { return@withLock it }
            fileKey?.let { getByFile(it) }?.let { store ->
                return@withLock if (register(chapterId, store)) store else get(chapterId)
            }
            val created = create() ?: return@withLock null
            if (!register(chapterId, created)) return@withLock get(chapterId)
            if (fileKey != null) registerFile(fileKey, created)
            created
        }
    }

    /** Opens one file-keyed store for callers that do not have a chapter id. */
    suspend fun getOrCreateFile(
        fileKey: String,
        create: suspend () -> ChapterTranslationStore?,
    ): ChapterTranslationStore? {
        getByFile(fileKey)?.let { return it }
        val openingLock = synchronized(this) {
            openingLocks.getOrPut(fileKey) { Mutex() }
        }
        return openingLock.withLock {
            getByFile(fileKey)?.let { return@withLock it }
            val created = create() ?: return@withLock null
            if (registerFile(fileKey, created)) created else getByFile(fileKey)
        }
    }

    @Synchronized
    fun remove(chapterId: Long): ChapterTranslationStore? {
        val removed = stores.remove(chapterId) ?: return null
        fileStores.entries.removeIf { it.value === removed }
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
