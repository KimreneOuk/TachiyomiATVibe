package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.translation.pipeline.onlinePageTranslationKey
import eu.kanade.translation.scheduling.ReaderPageWork
import eu.kanade.translation.scheduling.ReaderSessionToken
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Reader-owned indirection between a bound queue session and the live reader page list.
 *
 * The queue resolves page work lazily through the function this class issues, so the resolver
 * retains only this indirection, never the queue's demand state. Invalidation clears the page
 * list and every stream handle before a chapter loader can be recycled, making a late resolver
 * call inert even when the queue still holds the issued function.
 */
internal class ReaderPageWorkResolver(
    private val chapterCache: ChapterCache?,
) {

    private val generation = AtomicLong(0L)
    private val activeBinding = AtomicReference<BoundBinding?>(null)
    private val bindingLock = Any()

    /**
     * Binds a reader page list to one reader queue session. The token is compared by value and
     * the store by identity: a same-key session may replace its store instance while retaining
     * the same chapter identity, and that replacement must invalidate old page/loader closures
     * instead of reusing them. Rebinding the same session and store replaces only the page list;
     * already-issued stream handles stay valid.
     */
    fun bind(
        session: ReaderSessionToken,
        store: Any?,
        pages: List<ReaderPage>,
    ): (Int) -> ReaderPageWork? = synchronized(bindingLock) {
        val nextGeneration = generation.incrementAndGet()
        val existing = activeBinding.get()
        val nextBinding = existing
            ?.takeIf { it.session == session && it.store === store }
            ?.also { it.binding.replacePages(pages) }
            ?: BoundBinding(
                binding = Binding(pages, chapterCache),
                session = session,
                store = store,
            ).also { binding ->
                activeBinding.getAndSet(binding)?.binding?.clear()
            }

        return@synchronized resolver@{ index: Int ->
            val active = activeBinding.get()
            if (generation.get() != nextGeneration ||
                active !== nextBinding ||
                active.session != session
            ) {
                return@resolver null
            }
            nextBinding.binding.resolve(index)
        }
    }

    /** Invalidates all retained page and stream references before reader teardown. */
    fun invalidate() = synchronized(bindingLock) {
        generation.incrementAndGet()
        activeBinding.getAndSet(null)?.binding?.clear()
    }

    private data class BoundBinding(
        val binding: Binding,
        val session: ReaderSessionToken,
        val store: Any?,
    )

    private class Binding(
        pages: List<ReaderPage>,
        private val chapterCache: ChapterCache?,
    ) {
        private val lock = Any()
        private var pagesRef: List<ReaderPage>? = pages
        private val lifecycle = LifecycleGate()

        /** Updates the active lookup without invalidating already-issued stream handles. */
        fun replacePages(pages: List<ReaderPage>) = synchronized(lock) {
            pagesRef = pages
        }

        fun resolve(index: Int): ReaderPageWork? = synchronized(lock) {
            val page = pagesRef?.getOrNull(index) ?: return@synchronized null
            val pageKey = resolveReaderPageTranslationKey(page)
            val imageUrl = page.imageUrl
            val cachedStream = imageUrl?.let { url ->
                val cache = chapterCache ?: return@let null
                if (cache.isImageInCache(url)) {
                    { cache.getImageFile(url).inputStream() }
                } else {
                    null
                }
            }
            val stream = page.originalStream ?: cachedStream
            if (stream == null && page.chapter.pageLoader?.isLocal != true) {
                return@synchronized null
            }

            val issuedStream = stream?.let { factory -> IssuedStream(factory, lifecycle) }
            ReaderPageWork(
                pageKey = pageKey,
                readerPageIndex = index,
                streamFn = issuedStream?.let { it::open },
            )
        }

        fun clear() = synchronized(lock) {
            pagesRef = null
            lifecycle.clear()
        }
    }

    /**
     * Stream functions can outlive the resolver call in the queue. Capture the exact factory
     * selected for that work item so a later page-list replacement cannot retarget an
     * already-issued item. The binding lock remains the lifecycle gate: invalidation is ordered
     * before loader/page recycling, and an open already holding the gate completes before clear.
     */
    private class IssuedStream(
        private val factory: () -> InputStream,
        private val lifecycle: LifecycleGate,
    ) {
        fun open(): InputStream = lifecycle.open(factory)
    }

    /** Lifecycle gate shared by all issued items from one binding. */
    private class LifecycleGate {
        private val lock = Any()
        private var active = true

        fun open(factory: () -> InputStream): InputStream = synchronized(lock) {
            check(active) {
                "Reader page work resolver was invalidated before stream access"
            }
            factory()
        }

        fun clear() = synchronized(lock) {
            active = false
        }
    }
}

internal fun resolveReaderPageTranslationKey(page: ReaderPage): String {
    // A page can be resolved once while it is still online and later rebound to
    // a downloaded loader. In that transition the cached URL key is stale; the
    // loader's source filename is the canonical key used by the store and must
    // supersede the cached fallback for both writes and observation.
    val sourceKey = page.sourceFileName ?: page.translation?.sourceFileName
    if (sourceKey != null) {
        page.translationStorageKey = sourceKey
        return sourceKey
    }
    page.translationStorageKey?.let { return it }
    return onlinePageTranslationKey(page.imageUrl, page.url).also {
        page.translationStorageKey = it
    }
}

/**
 * Pure identity/lifecycle gate used by the loader readiness callback. Shared reader semantics:
 * a source-ready signal reconciles any captured reader session — including a manual deferred
 * demand while auto is off — as long as the chapter, session token and readiness generation
 * still match and translation is enabled. Keeping this predicate outside the callback makes
 * rapid chapter replacement and preference shutdown auditable without constructing a ViewModel.
 */
internal fun shouldReconcileReaderSourceReady(
    currentChapter: ReaderChapter?,
    boundChapter: ReaderChapter,
    activeSession: ReaderSessionToken?,
    boundSession: ReaderSessionToken,
    activeReadinessGeneration: Long,
    boundReadinessGeneration: Long,
    translationEnabled: Boolean,
): Boolean =
    currentChapter === boundChapter &&
        activeSession == boundSession &&
        activeReadinessGeneration == boundReadinessGeneration &&
        translationEnabled
