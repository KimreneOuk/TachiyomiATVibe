package eu.kanade.translation.scheduling

import eu.kanade.tachiyomi.source.online.HttpSource
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * TachiyomiAT: per-process registry of reader page image streams.
 *
 * Previously this state lived as a process-global `ConcurrentHashMap` on the
 * `ChapterTranslator` companion object. Each entry holds a `() -> InputStream`
 * factory closure — often capturing a downloaded [ByteArray] or a
 * `ReaderPage` reference — so the registry doubles as a memory owner for those
 * bytes. Extracting it into its own class lets the reader (which registers
 * streams) and the translator (which peeks them) share one well-named,
 * testable collaborator instead of touching a static map, and makes the
 * memory-eviction rules (per-chapter clear, clear-all on background/stop)
 * explicit on a single type.
 *
 * The key shape is unchanged from the previous companion implementation:
 * `"$sourceId:$mangaId:$chapterId:$pageKey"`, so the prefix-based chapter
 * eviction continues to match the same entries it did before.
 */
class TranslationStreamRegistry {

    private val streams = ConcurrentHashMap<String, () -> InputStream>()

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
}
