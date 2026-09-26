package eu.kanade.translation.pipeline.execution

import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-process registry of reader page image streams.
 *
 * Each entry holds a `() -> InputStream`
 * factory closure — often capturing a downloaded [ByteArray] or a
 * `ReaderPage` reference — so the registry also owns those bytes until entries
 * are cleared. The reader registers streams and the translator peeks them; the
 * per-chapter and reader-lifecycle eviction rules live here.
 *
 * The key shape is:
 * `"$sourceId:$mangaId:$chapterId:$pageKey"`, so the prefix-based chapter
 * eviction continues to match the same entries it did before.
 */
class TranslationStreamRegistry(
    private val cleanedRetirementGraceMs: Long = DEFAULT_CLEANED_RETIREMENT_GRACE_MS,
) {

    private val streams = ConcurrentHashMap<String, () -> InputStream>()

    private data class CleanedLeaseKey(
        val sourceId: Long,
        val mangaId: Long,
        val chapterId: Long,
        val pageKey: String,
        val imageName: String,
    )

    private data class CleanedLeaseRecord(
        var readers: Int = 0,
        val pendingDeletes: MutableList<() -> Unit> = mutableListOf(),
    )

    private val cleanedLeases = mutableMapOf<CleanedLeaseKey, CleanedLeaseRecord>()
    private val cleanedLeaseLock = Any()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun key(
        sourceId: Long,
        mangaId: Long,
        chapterId: Long,
        pageKey: String,
    ): String = "$sourceId:$mangaId:$chapterId:$pageKey"

    private fun prefix(sourceId: Long, mangaId: Long, chapterId: Long): String =
        "$sourceId:$mangaId:$chapterId:"

    /**
     * Registers (or replaces) the stream factory for one page. The factory is a
     * `() -> InputStream` so it may be invoked multiple times (once per retry);
     * it is NOT evicted on first use, so a failed translation can be retried —
     * the stream is dropped only via [clearChapter] / [clearAll].
     */
    fun register(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        streamFn: () -> InputStream,
    ) = register(source.id, manga.id, chapter.id!!, pageKey, streamFn)

    /**
     * ID-based registration entry point — the typed overload delegates here.
     * Exposed so callers that already hold raw IDs (and unit tests) can skip
     * constructing full [Manga]/[Chapter]/[HttpSource] instances.
     */
    fun register(
        sourceId: Long,
        mangaId: Long,
        chapterId: Long,
        pageKey: String,
        streamFn: () -> InputStream,
    ) {
        streams[key(sourceId, mangaId, chapterId, pageKey)] = streamFn
    }

    /**
     * Returns the registered reader stream for this page WITHOUT removing it.
     * Null when no stream was ever registered for the page.
     */
    fun peek(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
    ): (() -> InputStream)? = peek(source.id, manga.id, chapter.id!!, pageKey)

    /** ID-based peek — the typed overload delegates here. */
    fun peek(
        sourceId: Long,
        mangaId: Long,
        chapterId: Long,
        pageKey: String,
    ): (() -> InputStream)? = streams[key(sourceId, mangaId, chapterId, pageKey)]

    /**
     * Evicts every reader page stream registered for the given chapter. Each
     * registered entry can keep a `ReaderPage` / captured `ByteArray` alive, so
     * this must run on chapter change to avoid leaking memory and stale streams
     * across chapters.
     */
    fun clearChapter(sourceId: Long, mangaId: Long, chapterId: Long) {
        val p = prefix(sourceId, mangaId, chapterId)
        val iterator = streams.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().key.startsWith(p)) {
                iterator.remove()
            }
        }
    }

    fun clearPage(sourceId: Long, mangaId: Long, chapterId: Long, pageKey: String) {
        streams.remove(key(sourceId, mangaId, chapterId, pageKey))
    }

    fun clearOutsideWindow(
        sourceId: Long,
        mangaId: Long,
        chapterId: Long,
        keepPageKeys: Set<String>,
    ) {
        val p = prefix(sourceId, mangaId, chapterId)
        val keep = keepPageKeys.mapTo(HashSet()) { key(sourceId, mangaId, chapterId, it) }
        val iterator = streams.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.startsWith(p) && entry.key !in keep) {
                iterator.remove()
            }
        }
    }

    fun size(): Int = streams.size

    /**
     * Evicts EVERY registered reader page stream, regardless of chapter. Used on
     * reader background / "stop all translation" so backgrounding the reader
     * releases the captured page bytes instead of pinning them until process
     * death.
     */
    fun clearAll() {
        streams.clear()
    }

    /**
     * Opens a cleaned-image stream under a bounded reader lease. Retired files
     * may be queued for deletion while the stream is open; the close callback
     * releases the lease and runs deletion immediately, while the grace job
     * provides a hard upper bound if a decoder never closes its stream.
     */
    fun openCleanedImageStream(
        sourceId: Long,
        mangaId: Long,
        chapterId: Long,
        pageKey: String,
        imageName: String,
        open: () -> InputStream,
    ): InputStream {
        val key = CleanedLeaseKey(sourceId, mangaId, chapterId, pageKey, imageName)
        synchronized(cleanedLeaseLock) {
            cleanedLeases.getOrPut(key) { CleanedLeaseRecord() }.readers++
        }
        val input = try {
            open()
        } catch (error: Throwable) {
            releaseCleanedImageLease(key)
            throw error
        }
        return object : FilterInputStream(input) {
            private var released = false

            override fun close() {
                if (!released) {
                    try {
                        super.close()
                    } finally {
                        released = true
                        releaseCleanedImageLease(key)
                    }
                    return
                }
                super.close()
            }
        }
    }

    /**
     * Queues a retired image for deletion. No deletion occurs while a reader
     * lease is held; once the last lease closes it runs immediately. The grace
     * timeout is the bounded fallback for a leaked decoder/stream.
     */
    fun retireCleanedImage(
        sourceId: Long,
        mangaId: Long,
        chapterId: Long,
        pageKey: String,
        imageName: String,
        delete: () -> Unit,
    ) {
        val key = CleanedLeaseKey(sourceId, mangaId, chapterId, pageKey, imageName)
        val deleteNow = synchronized(cleanedLeaseLock) {
            val record = cleanedLeases[key]
            if (record == null || record.readers == 0) {
                cleanedLeases.remove(key)
                true
            } else {
                record.pendingDeletes += delete
                false
            }
        }
        if (deleteNow) {
            runCatching(delete)
        } else {
            cleanupScope.launch {
                delay(cleanedRetirementGraceMs)
                expireCleanedImageLease(key)
            }
        }
    }

    /**
     * Defers a destructive chapter-image-directory removal until every cleaned
     * stream currently held for the chapter has closed or reached the bounded
     * grace deadline. Explicit reset/delete paths use this alongside the
     * per-image retirement used by normal promotion.
     */
    fun retireCleanedImagesForChapter(
        sourceId: Long,
        mangaId: Long,
        chapterId: Long,
        deleteChapter: () -> Unit,
    ) {
        val keys = synchronized(cleanedLeaseLock) {
            cleanedLeases.keys.filter {
                it.sourceId == sourceId && it.mangaId == mangaId && it.chapterId == chapterId
            }
        }
        if (keys.isEmpty()) {
            runCatching(deleteChapter)
            return
        }
        val remaining = java.util.concurrent.atomic.AtomicInteger(keys.size)
        val deleteWhenReleased = {
            if (remaining.decrementAndGet() == 0) runCatching(deleteChapter)
        }
        keys.forEach { key ->
            retireCleanedImage(
                sourceId = key.sourceId,
                mangaId = key.mangaId,
                chapterId = key.chapterId,
                pageKey = key.pageKey,
                imageName = key.imageName,
                delete = deleteWhenReleased,
            )
        }
    }

    fun activeCleanedImageReaders(
        sourceId: Long,
        mangaId: Long,
        chapterId: Long,
        pageKey: String,
        imageName: String,
    ): Int = synchronized(cleanedLeaseLock) {
        cleanedLeases[CleanedLeaseKey(sourceId, mangaId, chapterId, pageKey, imageName)]?.readers ?: 0
    }

    /** Returns leases for [imageName] regardless of the page key used by the reader. */
    fun activeCleanedImageReadersForChapter(
        sourceId: Long,
        mangaId: Long,
        chapterId: Long,
        imageName: String,
    ): Int = synchronized(cleanedLeaseLock) {
        cleanedLeases.entries
            .filter { (key, _) ->
                key.sourceId == sourceId &&
                    key.mangaId == mangaId &&
                    key.chapterId == chapterId &&
                    key.imageName == imageName
            }
            .sumOf { (_, record) -> record.readers }
    }

    private fun releaseCleanedImageLease(key: CleanedLeaseKey) {
        val deletes = synchronized(cleanedLeaseLock) {
            val record = cleanedLeases[key] ?: return
            record.readers = (record.readers - 1).coerceAtLeast(0)
            if (record.readers == 0 && record.pendingDeletes.isNotEmpty()) {
                val callbacks = record.pendingDeletes.toList()
                cleanedLeases.remove(key)
                callbacks
            } else {
                emptyList()
            }
        }
        deletes.forEach { callback -> runCatching(callback) }
    }

    private fun expireCleanedImageLease(key: CleanedLeaseKey) {
        val deletes = synchronized(cleanedLeaseLock) {
            val record = cleanedLeases.remove(key) ?: return
            record.pendingDeletes.toList()
        }
        deletes.forEach { callback -> runCatching(callback) }
    }

    companion object {
        const val DEFAULT_CLEANED_RETIREMENT_GRACE_MS = 30_000L
    }
}
