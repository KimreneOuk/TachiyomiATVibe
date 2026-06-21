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
import eu.kanade.translation.model.lifecycle
import eu.kanade.translation.model.shouldSkipAutoScheduling
import eu.kanade.translation.model.toPageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

    /**
     * TachiyomiAT: owns single-page + auto-prefetch job scheduling, dedup, and
     * cancellation. Extracted from this class so the scheduling surface has its
     * own type; this manager keeps the store lifecycle (open/evict/observe),
     * chapter queue, and translation-file I/O. The scheduler resolves the
     * per-chapter store back through this manager via [storeResolver] (the store
     * instances are shared between reader and translator, so they must not be
     * owned by the scheduler).
     */
    val scheduler = eu.kanade.translation.scheduling.TranslationScheduler(
        executor = pipeline,
        storeResolver = eu.kanade.translation.scheduling.TranslationStoreResolver { chapterId ->
            activeTranslationStores[chapterId]
        },
    )

    init {
        // Make the translator use the same store instance the reader observes
        // so live updates do not need a chapter reload.
        pipeline.activeStoreResolver = { translation ->
            openOrCreateActiveChapterTranslationStore(
                translation.chapter.id!!,
                translation.chapter.name,
                translation.chapter.scanlator,
                translation.manga.title,
                translation.source,
            )
        }
        // NOTE: activeStoreUnregister is intentionally NOT wired. The previous
        // wiring evicted the shared store from activeTranslationStores after
        // every single-page translation finished, but the reader captured the
        // store's StateFlow once at observe time — so the next translate got a
        // fresh store the reader never observed, breaking live updates for
        // every page after the first. Store eviction now happens only on
        // chapter change / reader exit (see cancelPageTranslations /
        // cancelAllPageTranslations callers).

        // TachiyomiAT: wire the permit-watchdog callback. When a page's worker
        // is stuck in uncancellable native/HTTP code, the translator's watchdog
        // force-releases the permit (so other pages can proceed) and invokes
        // this. We then evict the dead job from the scheduler's activePageJobs,
        // otherwise its entry stays "active" and the dedup in translatePage
        // would silently drop every future retry of that page forever.
        pipeline.onPageStuck = { chapterId, pageKey ->
            if (chapterId != null && pageKey.isNotEmpty()) {
                scheduler.markPageJobStuck(chapterId, pageKey)
            }
        }
    }

    /**
     * TachiyomiAT: evicts a single-page translation job that its worker has
     * abandoned (stuck in uncancellable native code past its deadline). Delegates
     * to the scheduler, which owns the [activePageJobs] map; kept on this manager
     * as a public entry point so existing callers keep compiling during the
     * incremental migration (call sites migrate to the scheduler directly in a
     * later tier).
     */
    fun markPageJobStuck(chapterId: Long, pageKey: String) {
        scheduler.markPageJobStuck(chapterId, pageKey)
    }

    private val activeTranslationStores = mutableMapOf<Long, ChapterTranslationStore>()
    private val activeStoreJobs = mutableMapOf<Long, Job>()
    private val _activeStoreMap = MutableStateFlow<Map<Long, ChapterTranslationStore>>(emptyMap())
    private val _activeStoreState = MutableStateFlow<Map<String, PageTranslation>>(emptyMap())
    val activeStoreState: StateFlow<Map<String, PageTranslation>> = _activeStoreState.asStateFlow()

    /**
     * TachiyomiAT: scope owned by this manager purely for per-chapter store
     * collectors (the aggregate `_activeStoreState` fan-in). Job scheduling +
     * translation work live on the scheduler's/translator's own scopes; this one
     * is kept separate so cancelling translation jobs never tears down a store
     * collector that the reader is still observing. `SupervisorJob` so one
     * chapter's collector failing does not cancel another's.
     */
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val isRunning: Boolean
        get() = translator.isRunning

    val queueState
        get() = translator.queueState

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
    ): Boolean {
        val source = sourceManager.get(sourceId);
        if (source == null) return false
        val file = provider.findTranslationFile(chapterName, chapterScanlator, mangaTitle, source)
            ?: return false
        // Existence alone is NOT enough: openOrCreateActiveChapterTranslationStore
        // (called when the reader opens a chapter) creates an empty translation
        // file so the reader and translator share a store instance. Treat an
        // empty/blank file as "not translated" so the reader never shows a false
        // TRANSLATED state. A truly-translated chapter has a non-trivial page
        // map; decode it and require at least one entry.
        if (!file.exists() || file.length() <= 2L) return false
        return try {
            val pages = Json.decodeFromStream<Map<String, PageTranslation>>(file.openInputStream())
            // TachiyomiAT: a chapter counts as translated ONLY when at least one
            // page produced real output — a rendered/displayable image
            // (hasRenderedResult) OR recognized text blocks whose translation is
            // READY (hasRecognizedTranslation). Both helpers already define the
            // exact stage/image-name conditions the reader uses to treat a page
            // as Done / NeedsRender, so this stays consistent with the live UI.
            //
            // Previously this was `pages.isNotEmpty()`, which meant any single
            // placeholder page — an all-PENDING/CANCELLED entry with no blocks,
            // no rendered image — made the chapter show TRANSLATED. Those
            // placeholders are routinely written by the stranded-page sweep on
            // chapter open (ReaderViewModel.sweepStrandedPageStatus) and by
            // failed/aborted translations, so a chapter merely opened once (or
            // attempted and abandoned) read as fully translated forever after.
            // Requiring real content here closes that false-positive.
            pages.values.any { it.hasRenderedResult || it.hasRecognizedTranslation }
        } catch (e: Exception) {
            // Corrupt/empty file isn't a translation. getChapterTranslation(file)
            // will delete it on read; here just report not-translated.
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
    ): Map<String, PageTranslation> {
        try {
            return Json.decodeFromStream<Map<String, PageTranslation>>(file.openInputStream())
        } catch (e: Exception) {
            file.delete()
        }
        return emptyMap()
    }

    fun openChapterTranslationStore(file: UniFile): StateFlow<Map<String, PageTranslation>> {
        return ChapterTranslationStore.open(file).state
    }

    fun registerActiveTranslationStore(chapterId: Long, store: ChapterTranslationStore) {
        // If a store is already registered for this chapter (e.g. a previous
        // translation re-registered one), keep the existing instance so the
        // reader's already-captured StateFlow keeps observing the same object.
        if (activeTranslationStores[chapterId] === store) return
        activeTranslationStores[chapterId] = store
        _activeStoreMap.value = activeTranslationStores.toMap()
        // Launch the aggregate collector on the manager's OWN scope (not
        // GlobalScope) and track the Job so unregisterActiveTranslationStore can
        // cancel it. Previously this leaked a GlobalScope collector per chapter
        // that accumulated across navigations.
        activeStoreJobs[chapterId]?.cancel()
        activeStoreJobs[chapterId] = storeScope.launch {
            store.state.collect { pages ->
                _activeStoreState.value = pages
            }
        }
    }

    fun unregisterActiveTranslationStore(chapterId: Long) {
        activeStoreJobs.remove(chapterId)?.cancel()
        activeTranslationStores.remove(chapterId)
        _activeStoreMap.value = activeTranslationStores.toMap()
        if (activeTranslationStores.isEmpty()) {
            _activeStoreState.value = emptyMap()
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
    ): ChapterTranslationStore? {
        activeTranslationStores[chapterId]?.let { return it }
        val file = provider.findTranslationFile(chapterName, scanlator, mangaTitle, source)
        val store = if (file != null && file.exists()) {
            ChapterTranslationStore.open(file)
        } else {
            // No translation file yet: create a LAZY store. The on-disk file is
            // materialized only on the first real write (persistLocked), so
            // merely opening a chapter never leaves an empty file behind that
            // would make isChapterTranslated report a false TRANSLATED state.
            val saveFile = provider.getTranslationFileName(chapterName, scanlator)
            ChapterTranslationStore.lazy {
                provider.getMangaDir(mangaTitle, source)?.createFile(saveFile)
                    ?: throw java.io.IOException("Cannot create translation file for $chapterName")
            }
        }
        registerActiveTranslationStore(chapterId, store)
        return store
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

    /**
     * TachiyomiAT: per-chapter batch translation progress (done/total), derived
     * from the active chapter store. Used by the manga-screen chapter-list
     * indicator so the user can watch pre-translation advance ("12/40") without
     * opening the reader. Emits the active store's page-count progress for
     * [chapterId]; null when no active store exists for the chapter (no batch in
     * flight) — callers should then show no progress fraction.
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

    fun deleteTranslation(chapter: Chapter, manga: Manga, source: Source) {
        val chapterId = chapter.id ?: return
        launchIO {
            // TachiyomiAT: tear down ALL in-flight translation work for this
            // chapter BEFORE deleting any on-disk artifacts. The previous version
            // only removed the chapter from the batch queue and deleted files,
            // which left single-page / auto-prefetch jobs running. An auto job
            // can be mid-native-call inside OrtSession.run() at the instant the
            // user taps delete; the subsequent translate then rebuilds/closes the
            // recognition engine (recognitionEngine.close() frees the native
            // session), freeing a session out from under the still-running
            // inference. That is a native use-after-free (SIGSEGV) that kills the
            // process — exactly the "app exits/crashes on delete-then-translate"
            // symptom. The close()/run() race is also acknowledged in
            // RoiPageRecognitionEngine's comments.
            //
            // Ordering is load-bearing:
            //   1. cancelAutoTranslations bumps the chapter's auto generation so
            //      any in-flight auto window stops dispatching NEW pages at its
            //      next iteration (without this, a window between pages could
            //      re-launch work against the about-to-be-deleted store/file).
            //   2. cancelPageTranslations cancels + JOINs every auto and
            //      single-page job for the chapter (bounded by JOIN_TIMEOUT_MS),
            //      so the in-flight native work unwinds to its next suspension
            //      point before we proceed. It ALSO evicts the shared
            //      ChapterTranslationStore (unregisterActiveTranslationStore),
            //      so a subsequent translate resolves a fresh lazy store instead
            //      of reusing one bound to the file we're about to delete.
            //   3. removeFromTranslationQueue drops the batch-queue entry and
            //      stops the batch engine if the queue is now empty.
            //   4. Only once all work is wound down is it safe to delete the
            //      on-disk translation file + companion images.
            scheduler.cancelAutoTranslations(chapterId)
            cancelPageTranslations(chapterId)
            removeFromTranslationQueue(chapter)
            unregisterActiveTranslationStore(chapterId)
            val file = provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source);
            file?.delete()
            provider.deleteCompanionImages(manga.title, source, chapter.name, chapter.scanlator)
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

    fun getRenderedImageStream(mangaTitle: String, source: Source, chapterName: String, chapterScanlator: String?, renderedImageName: String): (() -> java.io.InputStream)? {
        return {
            val file = provider.findPageRenderedImage(mangaTitle, source, chapterName, chapterScanlator, renderedImageName)
            if (file?.exists() == true) {
                file.openInputStream()
            } else {
                throw java.io.FileNotFoundException("Rendered image not found: $renderedImageName")
            }
        }
    }

    fun getCompanionImageDirForChapter(chapterName: String, scanlator: String?, title: String, source: Source): UniFile? {
        return provider.findCompanionImageDir(title, source, chapterName, scanlator)
    }

    fun translatePage(manga: Manga, chapter: Chapter, source: HttpSource, pageKey: String) =
        scheduler.translatePage(manga, chapter, source, pageKey)

    /**
     * TachiyomiAT: cancels the in-flight single-page translation job for one
     * specific [pageKey] within [chapterId], if any. This is the per-page
     * granularity that [cancelPageTranslations] (chapter-scoped) is too coarse
     * for: it backs the per-page button's cancel affordance, so a user can stop
     * a single slow/stuck page without abandoning the whole chapter.
     *
     * Returns true if a job was actually cancelled, false if none was running
     * for that page (e.g. it already finished or the translator deduped it).
     * Delegates to the scheduler; kept here so existing callers keep compiling
     * during the incremental migration.
     */
    fun cancelPageTranslation(chapterId: Long, pageKey: String): Boolean =
        scheduler.cancelPageTranslation(chapterId, pageKey)

    /**
     * Cancels all in-flight single-page translation jobs for [chapterId] and
     * evicts the reader page streams registered for that chapter. Also drops the
     * shared [ChapterTranslationStore] for this chapter so it doesn't leak
     * across chapter navigations. Call this when the reader navigates away from
     * a chapter so the previous chapter's work can no longer hold the
     * executor's single permit.
     *
     * The job cancellation (incl. the bounded join that waits for stranded-
     * status resets) is delegated to the scheduler; the store eviction that
     * follows is a manager-owned concern (the manager owns store lifecycle).
     */
    suspend fun cancelPageTranslations(chapterId: Long) {
        scheduler.cancelPageTranslations(chapterId)
        if (isBatchTranslationActive(chapterId)) {
            return
        }
        activeTranslationStores[chapterId]?.clearTransientQueuePages("Translation cancelled")
        // Evict the shared store for this chapter now that we've left it; the
        // reader re-opens the store via observeLiveTranslationStore on the next
        // loadChapter for whatever chapter becomes active.
        unregisterActiveTranslationStore(chapterId)
    }

    /**
     * Cancels every in-flight single-page translation job and drops all shared
     * stores. Call this when the reader is destroyed or the master translation
     * toggle is switched off, so no orphaned work keeps running in the
     * background and no collector outlives the session.
     *
     * Job cancellation is delegated to the scheduler; store eviction + chapter
     * queue clearing are manager-owned concerns.
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
        // Evict every shared store + its collector.
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
