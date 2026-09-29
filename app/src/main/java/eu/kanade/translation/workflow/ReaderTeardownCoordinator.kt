package eu.kanade.translation.workflow

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.persistence.chapter.ActiveChapterStoreRegistry
import eu.kanade.translation.scheduling.TranslationScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/** Coordinates reader-page cancellation and teardown against the active session and batch. */
internal class ReaderTeardownCoordinator(
    private val applicationScopeProvider: () -> CoroutineScope,
    private val readerTeardownMutexProvider: () -> Mutex,
    private val schedulerProvider: () -> TranslationScheduler,
    private val activeStoresProvider: () -> ActiveChapterStoreRegistry,
    private val translatorProvider: () -> ChapterTranslator,
    private val sessionCoordinatorProvider: () -> TranslationSessionCoordinator,
    private val isAnyBatchTranslationActiveProvider: () -> Boolean,
    private val isBatchTranslationRetainedFn: (Long) -> Boolean,
    private val unregisterActiveTranslationStoreFn: suspend (Long) -> Unit,
    private val disposeBatchTrackerFn: (Long) -> Unit,
    private val clearAllPendingTranslationRequestsFn: () -> Unit,
) {

    // Resolve lifecycle owners on demand; teardown must operate on the current session.
    private val applicationScope get() = applicationScopeProvider()

    private val readerTeardownMutex get() = readerTeardownMutexProvider()

    private val scheduler get() = schedulerProvider()

    private val activeStores get() = activeStoresProvider()

    private val translator get() = translatorProvider()

    private val sessionCoordinator get() = sessionCoordinatorProvider()

    private val isAnyBatchTranslationActive get() = isAnyBatchTranslationActiveProvider()

    private fun isBatchTranslationRetained(chapterId: Long): Boolean = isBatchTranslationRetainedFn(chapterId)

    private suspend fun unregisterActiveTranslationStore(chapterId: Long) = unregisterActiveTranslationStoreFn(chapterId)

    private fun disposeBatchTracker(chapterId: Long) = disposeBatchTrackerFn(chapterId)

    private fun clearAllPendingTranslationRequests() = clearAllPendingTranslationRequestsFn()

    private fun translatorStop(reason: String? = null, closeEngines: Boolean = false) =
        translator.stop(reason, closeEngines)

    fun stopReaderTranslations(reason: String) {
        // Keep the suspending durable cleanup and bounded persist joins on the manager's IO
        // scope so ReaderActivity lifecycle callbacks return without doing storage work on main.
        applicationScope.launch(start = CoroutineStart.DEFAULT) {
            readerTeardownMutex.withLock {
                sessionCoordinator.abortPausingToBatch()
                cancelAllPageTranslations(cancelBatchQueue = false, reason = reason)
                if (!isAnyBatchTranslationActive) {
                    translatorStop(reason, closeEngines = false)
                }
                sessionCoordinator.finishSession(TranslationSessionState.READER_SESSION)
            }
        }
    }

    /**
     * Starts reader-owned teardown on the manager lifetime rather than the
     * ReaderViewModel scope. The deferred completes only after scheduler
     * coordinator/native/page jobs have joined and reader stores are evicted.
     */
    fun requestReaderStop(reason: String): Deferred<Unit> =
        applicationScope.async(start = CoroutineStart.DEFAULT) {
            awaitReaderStop(reason)
        }

    /** Joined counterpart for callers that already own a non-cancelled scope. */
    suspend fun awaitReaderStop(reason: String) {
        // This method is also called directly by chapter-switch work. Enforce the same IO fence
        // here so a future lifecycle caller cannot reintroduce a main-thread synchronous prefix.
        withContext(Dispatchers.IO) {
            readerTeardownMutex.withLock {
                sessionCoordinator.abortPausingToBatch()
                scheduler.awaitReaderStop()
                val chapterIdsToEvict = activeStores.chapterIds()
                    .filter { !isBatchTranslationRetained(it) }
                chapterIdsToEvict.forEach { unregisterActiveTranslationStore(it) }
                sessionCoordinator.finishSession(TranslationSessionState.READER_SESSION)
            }
        }
    }

    fun translatePage(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        force: Boolean = false,
    ) = scheduler.translatePage(manga, chapter, source, pageKey, force)

    /**
     * Cancels the in-flight single-page translation job for one [pageKey] within [chapterId] —
     * the per-page granularity [cancelPageTranslations] (chapter-scoped) is too coarse for.
     * Returns true if a job was actually cancelled, false if none was running for that page.
     */
    fun cancelPageTranslation(chapterId: Long, pageKey: String): Boolean =
        scheduler.cancelPageTranslation(chapterId, pageKey)

    /**
     * Cancels all in-flight single-page translation jobs for [chapterId] and evicts the shared
     * [eu.kanade.translation.persistence.chapter.ChapterTranslationStore] so it does not leak across chapter navigations. Call this on
     * reader navigate-away so the previous chapter's work can no longer hold the executor's
     * single permit. Job cancellation is delegated to the scheduler; store eviction is manager-owned.
     */
    suspend fun cancelPageTranslations(
        chapterId: Long,
        reason: String = "Translation cancelled",
    ) {
        scheduler.cancelPageTranslations(chapterId)
        if (isBatchTranslationRetained(chapterId)) {
            return
        }
        disposeBatchTracker(chapterId)
        activeStores.get(chapterId)?.clearTransientQueuePages(reason)
        // Evict the store on chapter exit; the reader re-opens it via observeLiveTranslationStore on the next loadChapter.
        withContext(Dispatchers.IO) {
            unregisterActiveTranslationStore(chapterId)
        }
    }

    /**
     * Cancels every in-flight single-page translation job and drops all shared stores. Call this
     * when the reader is destroyed or the master toggle is switched off, so no orphaned work
     * keeps running and no collector outlives the session. Job cancellation is delegated to the
     * scheduler; store eviction + chapter queue clearing are manager-owned.
     */
    suspend fun cancelAllPageTranslations(
        cancelBatchQueue: Boolean = false,
        reason: String = "All translation cancelled",
    ) {
        scheduler.cancelAllPageTranslations()
        val chapterIdsToEvict = activeStores.chapterIds()
            .filter { cancelBatchQueue || !isBatchTranslationRetained(it) }
        val stores = chapterIdsToEvict.mapNotNull { activeStores.get(it) }
        if (stores.isNotEmpty()) {
            // TachiyomiAT bug 4 fix: the durable CANCELLED write MUST land before
            // unregisterActiveTranslationStore marks these stores defunct below.
            // The previous code launched clearTransientQueuePages on storeScope
            // and then synchronously called markDefunct in the same pass; the
            // async clear was rejected as defunct and the durable state was
            // silently dropped, leaving pages RUNNING in the next session's
            // rehydrated snapshot. Run the clear to completion here (bounded by
            // the small number of active chapter stores) before eviction.
            stores.forEach { store ->
                store.clearTransientQueuePages(reason)
            }
        }
        chapterIdsToEvict.forEach { unregisterActiveTranslationStore(it) }
        if (cancelBatchQueue) {
            translator.clearQueue()
            clearAllPendingTranslationRequests()
            sessionCoordinator.finishSession()
        } else {
            sessionCoordinator.finishSession(TranslationSessionState.READER_SESSION)
        }
    }

    /** Runs suspending teardown and SAF-backed store cleanup away from reader UI dispatchers. */
    suspend fun cancelAllPageTranslationsOffMain(
        cancelBatchQueue: Boolean = false,
        reason: String = "All translation cancelled",
    ) {
        withContext(Dispatchers.IO) {
            cancelAllPageTranslations(cancelBatchQueue, reason)
        }
    }
}
