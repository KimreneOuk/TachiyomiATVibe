package eu.kanade.translation.engines.rendering

import eu.kanade.translation.model.TranslationBlock

/**
 * Content identity of one overlay text-layout planning
 * request. Blocks participate by VALUE (data-class equals over their current
 * field content), so a rebind carrying equal block content in a fresh list
 * instance still hits, while any content/size/page-dimension change misses.
 *
 * Because [TranslationBlock] is mutable, [hashCode]/[equals] are evaluated
 * against the content LIVE at each cache operation. A block mutated after an
 * entry was stored can therefore only cause a false MISS (different hash bucket
 * or failed equals), never a stale hit — the failure mode is a re-plan, which
 * is fail-closed.
 */
internal data class TextLayoutCacheKey(
    val blocks: List<TranslationBlock>,
    val pageWidth: Int,
    val pageHeight: Int,
)

/**
 * Bounded LRU cache of prepared overlay layouts, keyed by
 * [TextLayoutCacheKey] (blocks content + page dimensions). A hit lets
 * [eu.kanade.tachiyomi.ui.reader.viewer.TranslationOverlayView.bind] apply
 * layouts synchronously with zero planner work; the planner itself is never
 * invoked on the calling thread.
 *
 * The cache holds hydrated/prepared draw objects only —
 * never persisted plan DTO bytes or documents — whether the value was produced
 * by the async planner or by persisted-layout hydration (a hydrated hit keeps
 * the planner-invocation counter at 0 across LRU re-binds and process-restart
 * re-binds). NOT thread-safe by design: production confines every
 * `get`/`put` to the Main
 * thread (lookups happen inside `bind`, stores happen inside the Main-thread
 * apply callback), so no synchronization is needed. The bound is strict: the
 * least-recently-used entry is evicted the moment [maxEntries] would be
 * exceeded, keeping memory bounded regardless of chapter length.
 */
internal class ReaderTextLayoutCache<T : Any>(
    private val maxEntries: Int,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    // accessOrder=true turns iteration order into LRU order (eldest first).
    private val entries = LinkedHashMap<TextLayoutCacheKey, T>(16, 0.75f, true)

    val size: Int get() = entries.size

    /** Returns the prepared layouts for [key], or null on a miss. Refreshes recency. */
    fun get(key: TextLayoutCacheKey): T? = entries[key]

    /** Stores [value], evicting least-recently-used entries beyond [maxEntries]. */
    fun put(key: TextLayoutCacheKey, value: T) {
        entries[key] = value
        val iterator = entries.entries.iterator()
        while (entries.size > maxEntries) {
            iterator.next()
            iterator.remove()
        }
    }

    fun clear() = entries.clear()
}
