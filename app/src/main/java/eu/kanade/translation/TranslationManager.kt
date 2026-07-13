package eu.kanade.translation

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageView
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.hasRecognizedTranslation
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.batch.TranslationBatchProgressTracker
import eu.kanade.translation.model.shouldSkipAutoScheduling
import eu.kanade.translation.model.toPageView
import eu.kanade.translation.scheduling.TranslationStreamRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class TranslationManager(
    private val context: Context,
    private val provider: TranslationProvider = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
) {
    private val pipeline = TranslationPipeline(context, provider)
    private val translator = ChapterTranslator(context, provider, pipeline = pipeline);

    // Held here (DI singleton) so deleteTranslation can evict stale reader page-stream closures pointing at the deleted rendered/cleaned PNGs.
    private val streamRegistry: TranslationStreamRegistry = Injekt.get()

    /**
     * Application-lifetime scope for one-off init work (queue rehydration). SupervisorJob so a
     * failure in restoreQueue does not cancel unrelated work; IO dispatcher because restoreQueue does DB reads.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Owns single-page + auto-prefetch job scheduling, dedup, and cancellation. This manager
     * keeps the store lifecycle (open/evict/observe), chapter queue, and translation-file I/O.
     * The scheduler resolves the per-chapter store back through this manager via [storeResolver]
     * — store instances are shared between reader and translator, so they must not be owned by the scheduler.
     */
    val scheduler = eu.kanade.translation.scheduling.TranslationScheduler(
        executor = pipeline,
        storeResolver = eu.kanade.translation.scheduling.TranslationStoreResolver { chapterId ->
            activeTranslationStores[chapterId]
        },
        immediateStoreResolver = { chapterId -> activeTranslationStores[chapterId] },
    )

    init {
        // Share the store instance between reader and translator so live updates do not need a chapter reload.
        pipeline.activeStoreResolver = { translation ->
            openOrCreateActiveChapterTranslationStore(
                translation.chapter.id!!,
                translation.chapter.name,
                translation.chapter.scanlator,
                translation.manga.title,
                translation.source,
            )
        }
        // NOTE: activeStoreUnregister is intentionally NOT wired. Evicting after
        // each single-page translation broke live updates (reader captured the
        // StateFlow once; next translate got a fresh unobserved store). Eviction
        // now happens only on chapter change / reader exit (cancelPageTranslations /
        // cancelAllPageTranslations callers).

        // Permit-watchdog: when a worker is stuck in uncancellable native/HTTP code the
        // watchdog force-releases the permit and calls this; we evict the dead job so the
        // dedup in translatePage does not drop every future retry of that page forever.
        pipeline.onPageStuck = { chapterId, pageKey ->
            if (chapterId != null && pageKey.isNotEmpty()) {
                scheduler.markPageJobStuck(chapterId, pageKey)
            }
        }

        // Batch tracker factory: lets the pipeline create and register a tracker per active batch (observable via observeBatchProgress).
        pipeline.batchTrackerFactory = { chapterId, store, orderedPageKeys ->
            createBatchTracker(chapterId, store, orderedPageKeys)
        }

        // Rehydrate persisted batch queue on IO so a crash mid-batch no longer loses it.
        // Entries get status QUEUE; user taps Start to resume — never auto-starts OCR/LLM on launch.
        applicationScope.launch { translator.restoreQueue() }
    }

    /**
     * Evicts a single-page translation job whose worker is stuck in uncancellable native
     * code past its deadline. Delegates to the scheduler, which owns the [activePageJobs] map.
     */
    fun markPageJobStuck(chapterId: Long, pageKey: String) {
        scheduler.markPageJobStuck(chapterId, pageKey)
    }

    private val activeTranslationStores = java.util.concurrent.ConcurrentHashMap<Long, ChapterTranslationStore>()
    private val activeStoreJobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()
    private val _activeStoreMap = MutableStateFlow<Map<Long, ChapterTranslationStore>>(emptyMap())
    private val _activeStoreState = MutableStateFlow<Map<String, PageTranslation>>(emptyMap())
    val activeStoreState: StateFlow<Map<String, PageTranslation>> = _activeStoreState.asStateFlow()

    private val batchTrackers = java.util.concurrent.ConcurrentHashMap<Long, TranslationBatchProgressTracker>()
    private val _batchTrackerMap = MutableStateFlow<Map<Long, TranslationBatchProgressTracker>>(emptyMap())

    /**
     * Separate scope for per-chapter store collectors (the `_activeStoreState` fan-in) so
     * cancelling translation jobs never tears down a collector the reader observes.
     * SupervisorJob so one chapter's failure does not cancel another's.
     */
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val isRunning: Boolean
        get() = translator.isRunning

    val queueState
        get() = translator.queueState

    val isAnyBatchTranslationActive: Boolean
        get() = queueState.value.any { it.status == Translation.State.QUEUE || it.status == Translation.State.TRANSLATING }

    fun stopReaderTranslations(reason: String) {
        cancelAllPageTranslations(cancelBatchQueue = false)
        if (!isAnyBatchTranslationActive) {
            translatorStop(reason, closeEngines = false)
        }
    }

    fun translatorStart() = translator.start()
    fun translatorStop(reason: String? = null, closeEngines: Boolean = false) = translator.stop(reason, closeEngines)

    fun onMemoryPressure(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            scheduler.cancelAllPageTranslations()
        }
        translator.onMemoryPressure(level)
    }

    fun startTranslation() {
        if (translator.isRunning) return
        translator.start()
    }

    fun pauseTranslation() {
        translator.pause()
        translator.stop()
    }

    fun clearQueue() {
        translator.clearQueue()
        translator.stop()
    }

    fun getQueuedTranslationOrNull(chapterId: Long): Translation? {
        return queueState.value.find { it.chapter.id == chapterId }
    }

    fun isBatchTranslationActive(chapterId: Long): Boolean {
        return queueState.value.any { translation ->
            translation.chapter.id == chapterId &&
                (translation.status == Translation.State.QUEUE || translation.status == Translation.State.TRANSLATING)
        }
    }

    fun translateChapter(manga: Manga, chapters: Chapter) {
        translator.queueChapter(manga, chapters);
        startTranslation();
    }

    fun getChapterTranslationStatus(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        title: String,
        sourceId: Long,
    ): Translation.State {
        val translation = getQueuedTranslationOrNull(chapterId)
        if (translation != null) return translation.status
        if (isChapterTranslated(chapterName, scanlator, title, sourceId)) return Translation.State.TRANSLATED
        return Translation.State.NOT_TRANSLATED
    }

    fun isChapterTranslated(
        chapterName: String,
        chapterScanlator: String?,
        mangaTitle: String,
        sourceId: Long,
    ): Boolean = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val source = sourceManager.get(sourceId);
        if (source == null) return@runBlocking false
        val file = provider.findTranslationFile(chapterName, chapterScanlator, mangaTitle, source)
            ?: return@runBlocking false
        // Existence alone is NOT enough: opening a chapter creates an empty translation file so
        // reader and translator share a store. Treat empty/blank files as not-translated to avoid
        // a false TRANSLATED state; a truly-translated chapter has a non-trivial page map.
        if (!file.exists() || file.length() <= 2L) return@runBlocking false
        try {
            val pages = Json.decodeFromStream<Map<String, PageTranslation>>(file.openInputStream())
            // Counts as translated ONLY when a page produced real output — a rendered image
            // (hasRenderedResult) or recognized text blocks READY to translate (hasRecognizedTranslation).
            // Reusing the same helpers the reader treats as Done/NeedsRender keeps this consistent
            // with the live UI. Previously `pages.isNotEmpty()`, which counted a single placeholder
            // page (written by the stranded-page sweep on chapter open or by failed/aborted
            // translations) as fully translated forever — a false-positive now closed.
            pages.values.any { it.hasRenderedResult || it.hasRecognizedTranslation }
        } catch (e: Exception) {
            // Corrupt/empty file isn't a translation; getChapterTranslation(file) deletes it on read.
            logcat(LogPriority.WARN, e) { "Translation file for $chapterName unreadable; treating as not translated" }
            false
        }
    }
    fun getChapterTranslation(
        chapterName: String,
        scanlator: String?,
        title: String,
        source: Source,
    ): Map<String, PageTranslation> {
        try {
            val file = provider.findTranslationFile(
                chapterName,
                scanlator,
                title,
                source,
            ) ?: return emptyMap()
            return getChapterTranslation(file)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) {
                "TachiyomiAT failed to read chapter translation for $chapterName"
            }
        }
        return emptyMap()

    }

    fun getChapterTranslation(
        file: UniFile,
    ): Map<String, PageTranslation> = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        try {
            return@runBlocking Json.decodeFromStream<Map<String, PageTranslation>>(file.openInputStream())
        } catch (e: Exception) {
            file.delete()
        }
        return@runBlocking emptyMap()
    }

    fun openChapterTranslationStore(file: UniFile): StateFlow<Map<String, PageTranslation>> {
        return ChapterTranslationStore.open(file).state
    }

    fun registerActiveTranslationStore(chapterId: Long, store: ChapterTranslationStore) {
        synchronized(activeTranslationStores) {
            // Keep the existing instance if already registered so the reader's captured StateFlow keeps observing the same object.
            if (activeTranslationStores[chapterId] === store) return
            activeTranslationStores[chapterId] = store
            _activeStoreMap.value = activeTranslationStores.toMap()
            // Track the collector Job on the manager's own scope (not GlobalScope, which leaked per chapter across navigations) so unregister can cancel it.
            activeStoreJobs[chapterId]?.cancel()
            activeStoreJobs[chapterId] = storeScope.launch {
                store.state.collect { pages ->
                    _activeStoreState.value = pages
                }
            }
        }
    }

    fun unregisterActiveTranslationStore(chapterId: Long) {
        synchronized(activeTranslationStores) {
            activeStoreJobs.remove(chapterId)?.cancel()
            // Mark the evicted store defunct BEFORE removing it from the registry. A worker still
            // holding a reference (e.g. mid-uncancellable ONNX when cancel() was requested) has its
            // late writes rejected by the defunct guard instead of recreating the deleted file or
            // stranding a page at RUNNING on a store the reader no longer observes.
            activeTranslationStores.remove(chapterId)?.markDefunct()
            _activeStoreMap.value = activeTranslationStores.toMap()
            if (activeTranslationStores.isEmpty()) {
                _activeStoreState.value = emptyMap()
            }
        }
    }

    /**
     * Returns the existing active [ChapterTranslationStore] for [chapterId], or
     * opens one from disk if it is not yet registered. If neither an in-memory
     * store nor an on-disk translation file exists, this creates a fresh store
     * tied to the expected translation path and registers it, so a translator
     * starting now and a reader observing now share the same instance.
     */
    fun openOrCreateActiveChapterTranslationStore(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        mangaTitle: String,
        source: Source,
    ): ChapterTranslationStore? = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        synchronized(activeTranslationStores) {
            activeTranslationStores[chapterId]?.let { return@runBlocking it }
            val file = provider.findTranslationFile(chapterName, scanlator, mangaTitle, source)
            val store = if (file != null && file.exists()) {
                ChapterTranslationStore.open(file)
            } else {
                // Create a LAZY store: the on-disk file materializes only on the first real write
                // (persistLocked), so merely opening a chapter never leaves an empty file behind that
                // would make isChapterTranslated report a false TRANSLATED state.
                val saveFile = provider.getTranslationFileName(chapterName, scanlator)
                ChapterTranslationStore.lazy {
                    provider.getMangaDir(mangaTitle, source)?.createFile(saveFile)
                        ?: throw java.io.IOException("Cannot create translation file for $chapterName")
                }
            }
            registerActiveTranslationStore(chapterId, store)
            return@runBlocking store
        }
    }

    fun openActiveChapterTranslationStore(chapterId: Long, chapterName: String, scanlator: String?, mangaTitle: String, sourceId: Long): StateFlow<Map<String, PageTranslation>>? {
        val source = sourceManager.get(sourceId) ?: return null
        return openOrCreateActiveChapterTranslationStore(chapterId, chapterName, scanlator, mangaTitle, source)?.state
    }

    fun openTranslationSession(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
    ): TranslationSession? {
        val chapterId = chapter.id ?: return null
        val store = openOrCreateActiveChapterTranslationStore(
            chapterId,
            chapter.name,
            chapter.scanlator,
            manga.title,
            source,
        ) ?: return null
        val key = "${source.id}:${manga.id}:$chapterId"
        return TranslationSession(key, manga, chapter, source, store)
    }

    fun requestAutoWindow(
        session: TranslationSession,
        requests: List<TranslationPageRequest>,
    ) = scheduler.requestAutoWindow(session, requests)

    fun cancelAutoTranslations(chapterId: Long? = null): Boolean =
        scheduler.cancelAutoTranslations(chapterId)

    fun observeActiveStore(chapterId: Long): StateFlow<Map<String, PageTranslation>>? {
        return activeTranslationStores[chapterId]?.state
    }

    fun createBatchTracker(
        chapterId: Long,
        store: ChapterTranslationStore,
        orderedPageKeys: List<String>,
    ): TranslationBatchProgressTracker {
        disposeBatchTracker(chapterId)
        val tracker = TranslationBatchProgressTracker(
            chapterId = chapterId,
            store = store,
            orderedPageKeys = orderedPageKeys,
            scope = storeScope,
            permitHolderResolver = { pipeline.permitHolderPageKeySnapshot() },
        )
        batchTrackers[chapterId] = tracker
        _batchTrackerMap.value = batchTrackers.toMap()
        return tracker
    }

    fun disposeBatchTracker(chapterId: Long) {
        val removed = batchTrackers.remove(chapterId)
        if (removed != null) {
            removed.close()
            _batchTrackerMap.value = batchTrackers.toMap()
        }
    }

    fun getBatchTracker(chapterId: Long): TranslationBatchProgressTracker? = batchTrackers[chapterId]

    /**
     * Live batch progress for [chapterId]: emits from the tracker's snapshot StateFlow when a
     * tracker is active, else falls back to store-derived progress. Switches reactively when a
     * tracker is created or disposed.
     */
    fun observeBatchProgress(chapterId: Long): Flow<TranslationProgressSnapshot> {
        return _batchTrackerMap
            .flatMapLatest { trackers ->
                val tracker = trackers[chapterId]
                if (tracker != null) {
                    tracker.snapshot
                } else {
                    val state = getQueuedTranslationOrNull(chapterId)?.status
                        ?: Translation.State.NOT_TRANSLATED
                    flowOf(
                        TranslationProgressSnapshot.compute(
                            chapterId = chapterId,
                            state = state,
                            pageMap = activeTranslationStores[chapterId]?.state?.value,
                            permitHolderPageKey = pipeline.permitHolderPageKeySnapshot(),
                        )
                    )
                }
            }
            .distinctUntilChanged()
    }

    /**
     * Per-chapter batch progress (done/total) for the manga-screen chapter-list indicator, so
     * the user can watch pre-translation advance without opening the reader. Emits the active
     * store's page-count progress; empty when no active store exists (no batch in flight).
     */
    fun observeTranslationProgress(chapterId: Long): Flow<TranslationProgressSnapshot> {
        return _activeStoreMap
            .flatMapLatest { stores ->
                val state = getQueuedTranslationOrNull(chapterId)?.status ?: Translation.State.NOT_TRANSLATED
                val store = stores[chapterId]
                if (store == null) {
                    flowOf(TranslationProgressSnapshot.empty(chapterId, state))
                } else {
                    store.state.map { pages ->
                        TranslationProgressSnapshot.compute(
                            chapterId = chapterId,
                            state = getQueuedTranslationOrNull(chapterId)?.status ?: state,
                            pageMap = pages,
                            permitHolderPageKey = pipeline.permitHolderPageKeySnapshot(),
                        )
                    }
                }
            }
            .distinctUntilChanged()
    }

    fun observePageView(chapterId: Long, pageKey: String): Flow<PageView>? {
        return observeActiveStore(chapterId)
            ?.map { pages -> pages[pageKey].toPageView() }
            ?.distinctUntilChanged()
    }

    suspend fun deleteTranslation(chapter: Chapter, manga: Manga, source: Source) {
        val chapterId = chapter.id ?: return
        // SYNCHRONOUS teardown (was fire-and-forget): a delete-then-retranslate let the reader
        // re-bind to the about-to-be-evicted store while the translator wrote to a fresh instance,
        // and a cancelled-but-not-joined batch worker kept writing into the old store after its
        // file/PNGs were deleted (recreating the JSON or stranding pages at RUNNING). Suspending
        // guarantees callers land on clean state.
        //
        // Ordering is load-bearing and strictly sequenced:
        //   1. cancelAutoTranslations bumps the auto generation so the window stops dispatching new pages.
        //   2. cancelPageTranslations cancels + JOINs each auto/single-page job so native work unwinds.
        //   3. removeFromTranslationQueue + cancelTranslatorJobAndJoin drop the batch entry and JOIN the
        //      batch worker (plain removeFrom only cancel()s) so it releases the translator permit before deletion.
        //   4. unregisterActiveTranslationStore marks the store defunct so a still-unwinding worker's late writes no-op.
        //   5. streamRegistry.clearChapter drops stale reader closures pointing at the about-to-be-deleted PNGs.
        //   6. Only once all work is wound down is it safe to delete the on-disk file + companion images.
        scheduler.cancelAutoTranslations(chapterId)
        cancelPageTranslations(chapterId)
        removeFromTranslationQueue(chapter)
        translator.cancelTranslatorJobAndJoin()
        disposeBatchTracker(chapterId)
        unregisterActiveTranslationStore(chapterId)
        streamRegistry.clearChapter(source.id, manga.id, chapterId)
        val file = provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source);
        file?.delete()
        provider.deleteCompanionImages(manga.title, source, chapter.name, chapter.scanlator)
    }

    suspend fun deletePageTranslation(chapter: Chapter, manga: Manga, source: Source, pageKey: String) {
        val chapterId = chapter.id ?: return
        
        cancelPageTranslation(chapterId, pageKey)
        streamRegistry.clearPage(source.id, manga.id, chapterId, pageKey)
        
        val activeStore = activeTranslationStores[chapterId]
        val persistedCleanedName = activeStore?.state?.value?.get(pageKey)?.cleanedImageName
        if (activeStore != null) {
            activeStore.deletePage(pageKey)
        } else {
            val file = provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source)
            if (file?.exists() == true) {
                val store = ChapterTranslationStore.open(file)
                store.deletePage(pageKey)
                store.flush()
            }
        }
        
        val companionDir = provider.findCompanionImageDir(manga.title, source, chapter.name, chapter.scanlator)
        if (companionDir != null) {
            persistedCleanedName?.let { companionDir.findFile(it)?.delete() }
            val safePageKey = pageKey.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_")
            companionDir.findFile("$safePageKey.cleaned.png")?.delete()
            companionDir.findFile("$safePageKey.cleaned.jpg")?.delete()
            companionDir.findFile("$safePageKey.rendered.png")?.delete()
            // Versioned publication names are unique per replacement attempt;
            // remove any orphaned versions left after a deleted store entry.
            companionDir.listFiles()?.asSequence().orEmpty()
                .filter { it.name?.startsWith("$safePageKey.cleaned.") == true }
                .forEach { it.delete() }
        }
    }

    fun deleteManga(manga: Manga, source: Source, removeQueued: Boolean = true) {
        launchIO {
            if (removeQueued) {
                translator.removeFromQueue(manga)
            }
            provider.findMangaDir(manga.title, source)?.delete()
            val sourceDir = provider.findSourceDir(source)
            if (sourceDir?.listFiles()?.isEmpty() == true) {
                sourceDir.delete()
            }
        }
    }

    fun cancelQueuedTranslation(translation: Translation) {
        removeFromTranslationQueue(translation.chapter)
    }

    private fun removeFromTranslationQueue(chapter: Chapter) {
        val wasRunning = translator.isRunning
        if (wasRunning) {
            translator.pause()
        }
        translator.removeFromQueue(chapter)
        if (wasRunning) {
            if (queueState.value.isEmpty()) {
                translator.stop()
            } else if (queueState.value.isNotEmpty()) {
                translator.start()
            }
        }
    }

    fun getCleanedImageStream(mangaTitle: String, source: Source, chapterName: String, chapterScanlator: String?, cleanedImageName: String): (() -> java.io.InputStream)? {
        return {
            val file = provider.findPageCleanedImage(mangaTitle, source, chapterName, chapterScanlator, cleanedImageName)
            if (file?.exists() == true) {
                file.openInputStream()
            } else {
                throw java.io.FileNotFoundException("Cleaned image not found: $cleanedImageName")
            }
        }
    }



    fun getCompanionImageDirForChapter(chapterName: String, scanlator: String?, title: String, source: Source): UniFile? {
        return provider.findCompanionImageDir(title, source, chapterName, scanlator)
    }

    fun translatePage(manga: Manga, chapter: Chapter, source: HttpSource, pageKey: String) =
        scheduler.translatePage(manga, chapter, source, pageKey)

    /**
     * Cancels the in-flight single-page translation job for one [pageKey] within [chapterId] —
     * the per-page granularity [cancelPageTranslations] (chapter-scoped) is too coarse for.
     * Returns true if a job was actually cancelled, false if none was running for that page.
     */
    fun cancelPageTranslation(chapterId: Long, pageKey: String): Boolean =
        scheduler.cancelPageTranslation(chapterId, pageKey)

    /**
     * Cancels all in-flight single-page translation jobs for [chapterId] and evicts the shared
     * [ChapterTranslationStore] so it does not leak across chapter navigations. Call this on
     * reader navigate-away so the previous chapter's work can no longer hold the executor's
     * single permit. Job cancellation is delegated to the scheduler; store eviction is manager-owned.
     */
    suspend fun cancelPageTranslations(chapterId: Long) {
        scheduler.cancelPageTranslations(chapterId)
        if (isBatchTranslationActive(chapterId)) {
            return
        }
        disposeBatchTracker(chapterId)
        activeTranslationStores[chapterId]?.clearTransientQueuePages("Translation cancelled")
        // Evict the store on chapter exit; the reader re-opens it via observeLiveTranslationStore on the next loadChapter.
        unregisterActiveTranslationStore(chapterId)
    }

    /**
     * Cancels every in-flight single-page translation job and drops all shared stores. Call this
     * when the reader is destroyed or the master toggle is switched off, so no orphaned work
     * keeps running and no collector outlives the session. Job cancellation is delegated to the
     * scheduler; store eviction + chapter queue clearing are manager-owned.
     */
    fun cancelAllPageTranslations(cancelBatchQueue: Boolean = false) {
        scheduler.cancelAllPageTranslations()
        val chapterIdsToEvict = activeTranslationStores.keys
            .filter { cancelBatchQueue || !isBatchTranslationActive(it) }
        val stores = chapterIdsToEvict.mapNotNull { activeTranslationStores[it] }
        if (stores.isNotEmpty()) {
            storeScope.launch {
                stores.forEach { store ->
                    store.clearTransientQueuePages("All translation cancelled")
                }
            }
        }
        chapterIdsToEvict.forEach { unregisterActiveTranslationStore(it) }
        if (cancelBatchQueue) {
            translator.clearQueue()
        }
    }

    fun statusFlow(): Flow<Translation> = queueState
        .flatMapLatest { translations ->
            translations
                .map { translation ->
                    translation.statusFlow.drop(1).map { translation }
                }
                .merge()
        }
        .onStart {
            emitAll(
                queueState.value.filter { translation -> translation.status == Translation.State.TRANSLATING }.asFlow(),
            )
        }
}
