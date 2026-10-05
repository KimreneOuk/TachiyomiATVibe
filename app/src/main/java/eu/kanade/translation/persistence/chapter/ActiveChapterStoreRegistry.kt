package eu.kanade.translation.persistence.chapter

import eu.kanade.translation.model.PageTranslationView
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
    private val probeStores = LinkedHashMap<String, ChapterTranslationStore>()
    private val openingLocks = LinkedHashMap<String, Mutex>()
    private val storeWriterRegistrations = LinkedHashMap<Long, AutoCloseable>()
    private val probeWriterRegistrations = LinkedHashMap<String, AutoCloseable>()
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
        store.chapterKey?.let { mainStores[it] = store }
        storeWriterRegistrations[chapterId] = registerWriter(
            chapterId = chapterId,
            chapterKey = store.chapterKey,
            origin = WriterOrigin.MAIN_STORE,
        )
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
        val openingLock = synchronized(this) {
            openingLocks.getOrPut(fileKey ?: "chapter:$chapterId") { Mutex() }
        }
        return openingLock.withLock {
            get(chapterId)?.let { return@withLock it }
            fileKey?.let { getByFile(it) }?.let { store ->
                return@withLock if (register(chapterId, store)) store else get(chapterId)
            }
            fileKey?.let { takeProbe(it) }?.let { store ->
                return@withLock if (register(chapterId, store)) {
                    registerFile(fileKey, store)
                    store
                } else {
                    store.closeAndFlush()
                    get(chapterId)
                }
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
        val openingLock = synchronized(this) {
            openingLocks.getOrPut(fileKey) { Mutex() }
        }
        return openingLock.withLock {
            getByFile(fileKey)?.let { return@withLock it }
            takeProbe(fileKey)?.let { probe ->
                return@withLock if (registerFile(fileKey, probe)) {
                    probe
                } else {
                    probe.closeAndFlush()
                    getByFile(fileKey)
                }
            }
            val created = create() ?: return@withLock null
            if (registerFile(fileKey, created)) created else getByFile(fileKey)
        }
    }

    /** Opens a non-published store used only for a durable read probe. */
    suspend fun getOrCreateProbe(
        fileKey: String,
        create: suspend () -> ChapterTranslationStore?,
    ): ProbeResult? {
        val openingLock = synchronized(this) {
            openingLocks.getOrPut(fileKey) { Mutex() }
        }
        return openingLock.withLock {
            getByFile(fileKey)?.let { return@withLock ProbeResult(it, owned = false) }
            synchronized(this@ActiveChapterStoreRegistry) { probeStores[fileKey] }?.let {
                return@withLock ProbeResult(it, owned = true)
            }
            val created = create() ?: return@withLock null
            synchronized(this@ActiveChapterStoreRegistry) {
                probeStores[fileKey] = created
                probeWriterRegistrations[fileKey] = registerWriter(
                    chapterKey = fileKey,
                    origin = WriterOrigin.PROBE_STORE,
                )
            }
            ProbeResult(created, owned = true, created = true)
        }
    }

    /**
     * Runs a file-backed maintenance transaction under the same admission
     * mutex used by chapter, file, and probe opens. Callers must inspect
     * [hasStoreForFile] while holding this lock before opening an unregistered
     * maintenance instance. This closes the race where an opener passes its
     * fast-path lookup while a migration is preparing the same artifact.
     */
    suspend fun <T> withFileOpeningLock(fileKey: String, block: suspend () -> T): T {
        val openingLock = synchronized(this) {
            openingLocks.getOrPut(fileKey) { Mutex() }
        }
        return openingLock.withLock { block() }
    }

    /** Caller should hold [withFileOpeningLock] when using this as an admission guard. */
    @Synchronized
    fun hasStoreForFile(fileKey: String): Boolean =
        fileStores.containsKey(fileKey) ||
            probeStores.containsKey(fileKey) ||
            stores.values.any { it.chapterKey == fileKey }

    /**
     * Removes a probe unless an active chapter or file reader adopted it while
     * the probe was running. The caller closes the returned store only when the
     * result is true; active stores retain their persist scope.
     */
    @Synchronized
    fun releaseProbe(fileKey: String, store: ChapterTranslationStore): Boolean {
        if (probeStores[fileKey] !== store) return false
        val active = stores.values.any { it === store } || fileStores[fileKey] === store
        probeStores.remove(fileKey)
        probeWriterRegistrations.remove(fileKey)?.close()
        return !active
    }

    @Synchronized
    fun remove(chapterId: Long): ChapterTranslationStore? {
        val removed = stores.remove(chapterId) ?: return null
        removed.chapterKey?.let { mainStores.remove(it) }
        storeWriterRegistrations.remove(chapterId)?.close()
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
    fun observe(chapterId: Long): StateFlow<Map<String, PageTranslationView>>? = get(chapterId)?.state

    /**
     * Rebinds only when this [chapterId]'s source changes. Updates from another chapter's store
     * cannot be emitted through this flow.
     */
    fun select(chapterId: Long): Flow<Map<String, PageTranslationView>> = snapshots.flatMapLatest { map ->
        map[chapterId]?.state ?: flowOf(emptyMap())
    }

    private fun publish() {
        _snapshots.value = stores.toMap()
    }

    data class ProbeResult(
        val store: ChapterTranslationStore,
        val owned: Boolean,
        /**
         * True only when this call ran [create] — a freshly opened probe may
         * have recovered or seeded journal-backed state and advanced durable truth, so
         * observers must drop statuses cached before it. A reused probe
         * already had that wipe when it was first created.
         */
        val created: Boolean = false,
    )

    @Synchronized
    private fun takeProbe(fileKey: String): ChapterTranslationStore? {
        probeWriterRegistrations.remove(fileKey)?.close()
        return probeStores.remove(fileKey)
    }

    companion object {
        private val mainStores = java.util.concurrent.ConcurrentHashMap<String, ChapterTranslationStore>()
        private val globalWriters = LinkedHashSet<ActiveWriter>()
        private val globalMutex = Any()

        fun mainStoreFor(chapterKey: String): ChapterTranslationStore? = mainStores[chapterKey]

        /**
         * Registers an active writer process-wide.
         *
         * Registers a process-wide writer for diagnostics and inspection.
         */
        fun registerWriter(
            chapterId: Long? = null,
            chapterKey: String? = null,
            origin: WriterOrigin,
            tag: String? = null,
            nowEpochMs: Long = System.currentTimeMillis(),
        ): AutoCloseable = synchronized(globalMutex) {
            val writer = ActiveWriter(
                chapterId = chapterId,
                chapterKey = chapterKey,
                origin = origin,
                tag = tag,
                registeredAtEpochMs = nowEpochMs,
            )
            globalWriters.add(writer)
            AutoCloseable {
                synchronized(globalMutex) {
                    globalWriters.remove(writer)
                }
            }
        }

        fun getActiveWriters(chapterId: Long? = null, chapterKey: String? = null): List<ActiveWriter> =
            synchronized(globalMutex) {
                globalWriters.filter {
                    (chapterId != null && it.chapterId == chapterId) ||
                        (chapterKey != null && it.chapterKey == chapterKey)
                }
            }

        fun hasActiveWriter(
            chapterId: Long? = null,
            chapterKey: String? = null,
            origin: WriterOrigin,
        ): Boolean = synchronized(globalMutex) {
            globalWriters.any {
                (
                    (chapterId != null && it.chapterId == chapterId) ||
                        (chapterKey != null && it.chapterKey == chapterKey)
                    ) &&
                    it.origin == origin
            }
        }

        fun allActiveWriters(): List<ActiveWriter> = synchronized(globalMutex) {
            globalWriters.toList()
        }

        fun clearGlobalWriters() = synchronized(globalMutex) {
            globalWriters.clear()
        }
    }
}
