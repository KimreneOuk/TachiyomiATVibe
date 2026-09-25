package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.translation.pipeline.onlinePageTranslationKey
import eu.kanade.translation.scheduling.AutoChapterIdentity
import eu.kanade.translation.scheduling.RollingAutoCoordinator
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Reader-owned indirection for rolling page resolution.
 *
 * The coordinator may retain its resolver until a later lifecycle call. The resolver therefore
 * retains only this indirection, never the reader page list directly. Invalidation clears the
 * page list and every stream handle before a chapter loader can be recycled, making a late
 * resolver call inert even when the coordinator still owns its current window specification.
 */
internal class ReaderAutoTranslationPageResolver(
    private val chapterCache: ChapterCache?,
) {

    private val generation = AtomicLong(0L)
    private val activeBinding = AtomicReference<BoundBinding?>(null)
    private val bindingLock = Any()

    /**
     * Binds a page list to one reader Auto identity. A later bind or [invalidate] fences every
     * resolver produced by an older reader generation or chapter/session identity. Same-identity
     * anchor updates reuse the binding so stream handles already handed to in-flight work remain
     * valid until the reader lifecycle invalidates the binding.
     */
    fun bind(
        identity: AutoChapterIdentity,
        pages: List<ReaderPage>,
    ): (Int) -> RollingAutoCoordinator.PageWorkItem? = bind(
        identity = identity,
        pages = pages,
        ownerVersion = null,
        ownerToken = null,
    )

    /**
     * Binds a reader page list to the scheduler owner that produced its snapshot. The store
     * token is intentionally compared by identity: a same-key session may replace its store
     * instance while retaining the same chapter identity, and that replacement must invalidate
     * old page/loader closures instead of reusing them.
     */
    fun bind(
        identity: AutoChapterIdentity,
        pages: List<ReaderPage>,
        ownerVersion: Long?,
        ownerToken: Any? = null,
    ): (Int) -> RollingAutoCoordinator.PageWorkItem? = synchronized(bindingLock) {
        val nextGeneration = generation.incrementAndGet()
        val existing = activeBinding.get()
        val nextBinding = existing
            ?.takeIf {
                it.identity == identity &&
                    it.ownerToken === ownerToken &&
                    (
                        ownerVersion == null ||
                            it.ownerVersion.get() == null ||
                            it.ownerVersion.get() == ownerVersion
                        )
            }
            ?.also {
                if (ownerVersion != null) it.ownerVersion.set(ownerVersion)
                it.binding.replacePages(pages)
            }
            ?: BoundBinding(
                binding = Binding(pages, chapterCache),
                identity = identity,
                ownerToken = ownerToken,
                ownerVersion = AtomicReference(ownerVersion),
            ).also { binding ->
                activeBinding.getAndSet(binding)?.binding?.clear()
            }

        return@synchronized resolver@{ index: Int ->
            val active = activeBinding.get()
            if (generation.get() != nextGeneration ||
                active !== nextBinding ||
                active.identity != identity
            ) {
                return@resolver null
            }
            nextBinding.binding.resolve(index)
        }
    }

    /** Positional convenience for tests and non-Reader callers that only have the owner version. */
    fun bind(
        identity: AutoChapterIdentity,
        ownerVersion: Long,
        pages: List<ReaderPage>,
        ownerToken: Any? = null,
    ): (Int) -> RollingAutoCoordinator.PageWorkItem? = bind(
        identity = identity,
        pages = pages,
        ownerVersion = ownerVersion as Long?,
        ownerToken = ownerToken,
    )

    /** Records the owner version once the stable scheduler snapshot reaches the Reader. */
    fun bindOwnerVersion(
        identity: AutoChapterIdentity,
        ownerToken: Any?,
        ownerVersion: Long,
    ) = synchronized(bindingLock) {
        activeBinding.get()
            ?.takeIf { it.identity == identity && it.ownerToken === ownerToken }
            ?.ownerVersion
            ?.set(ownerVersion)
    }

    /** Invalidates all retained page and stream references before reader teardown. */
    fun invalidate() = synchronized(bindingLock) {
        generation.incrementAndGet()
        activeBinding.getAndSet(null)?.binding?.clear()
    }

    private data class BoundBinding(
        val binding: Binding,
        val identity: AutoChapterIdentity,
        val ownerToken: Any?,
        val ownerVersion: AtomicReference<Long?>,
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

        fun resolve(index: Int): RollingAutoCoordinator.PageWorkItem? = synchronized(lock) {
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
            RollingAutoCoordinator.PageWorkItem(
                pageKey = pageKey,
                streamFn = issuedStream?.let { it::open },
            )
        }

        fun clear() = synchronized(lock) {
            pagesRef = null
            lifecycle.clear()
        }
    }

    /**
     * Stream functions can outlive the resolver call in the coordinator. Capture the exact
     * factory selected for that work item so a later page-list replacement cannot retarget an
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
                "Reader Auto page resolver was invalidated before stream access"
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
 * Pure identity/lifecycle gate used by the loader readiness callback. Keeping this predicate
 * outside the callback makes rapid chapter replacement and preference shutdown auditable without
 * constructing a ViewModel or subscribing to page status flows in tests.
 */
internal fun shouldReconcileAutoSourceReady(
    currentChapter: ReaderChapter?,
    boundChapter: ReaderChapter,
    activeIdentity: AutoChapterIdentity?,
    boundIdentity: AutoChapterIdentity,
    activeGeneration: Long,
    boundGeneration: Long,
    translationEnabled: Boolean,
    autoTranslate: Boolean,
): Boolean =
    currentChapter === boundChapter &&
        activeIdentity == boundIdentity &&
        activeGeneration == boundGeneration &&
        translationEnabled &&
        autoTranslate
