package eu.kanade.translation

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.Translation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
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
    private val translator = ChapterTranslator(context, provider);

    /**
     * TachiyomiAT: owns single-page (and auto) translation jobs so they can be
     * cancelled on chapter change, reader exit, or translation disable. The
     * previous implementation launched these on `GlobalScope` and discarded the
     * returned [Job], which meant orphaned jobs from a previous chapter kept
     * running (and kept holding the translator's single `translatorPermit`),
     * starving all later work — appearing as "translate does nothing".
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Tracks in-flight single-page jobs by `"$chapterId:$pageKey"` so a repeat
     * request replaces (cancels) a pending one instead of queuing behind itself,
     * and so [cancelPageTranslations] / [cancelAllPageTranslations] can revoke
     * them when the chapter changes or the reader closes.
     */
    private val activePageJobs = ConcurrentHashMap<String, Job>()

    init {
        // Make the translator use the same store instance the reader observes
        // so live updates do not need a chapter reload.
        translator.activeStoreResolver = { translation ->
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
    }

    private val activeTranslationStores = mutableMapOf<Long, ChapterTranslationStore>()
    private val activeStoreJobs = mutableMapOf<Long, Job>()
    private val _activeStoreState = MutableStateFlow<Map<String, PageTranslation>>(emptyMap())
    val activeStoreState: StateFlow<Map<String, PageTranslation>> = _activeStoreState.asStateFlow()

    val isRunning: Boolean
        get() = translator.isRunning

    val queueState
        get() = translator.queueState

    fun translatorStart() = translator.start()
    fun translatorStop(reason: String? = null) = translator.stop(reason)

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
            Json.decodeFromStream<Map<String, PageTranslation>>(file.openInputStream()).isNotEmpty()
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
        // Launch the aggregate collector on the manager's OWN scope (not
        // GlobalScope) and track the Job so unregisterActiveTranslationStore can
        // cancel it. Previously this leaked a GlobalScope collector per chapter
        // that accumulated across navigations.
        activeStoreJobs[chapterId]?.cancel()
        activeStoreJobs[chapterId] = scope.launch {
            store.state.collect { pages ->
                _activeStoreState.value = pages
            }
        }
    }

    fun unregisterActiveTranslationStore(chapterId: Long) {
        activeStoreJobs.remove(chapterId)?.cancel()
        activeTranslationStores.remove(chapterId)
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

    fun observeActiveStore(chapterId: Long): StateFlow<Map<String, PageTranslation>>? {
        return activeTranslationStores[chapterId]?.state
    }

    fun deleteTranslation(chapter: Chapter, manga: Manga, source: Source) {
        launchIO {
            removeFromTranslationQueue(chapter)
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
        val file = provider.findPageCleanedImage(mangaTitle, source, chapterName, chapterScanlator, cleanedImageName)
        if (file?.exists() == true) {
            return { file.openInputStream() }
        }
        return null
    }

    fun getRenderedImageStream(mangaTitle: String, source: Source, chapterName: String, chapterScanlator: String?, renderedImageName: String): (() -> java.io.InputStream)? {
        val file = provider.findPageRenderedImage(mangaTitle, source, chapterName, chapterScanlator, renderedImageName)
        if (file?.exists() == true) {
            return { file.openInputStream() }
        }
        return null
    }

    fun getCompanionImageDirForChapter(chapterName: String, scanlator: String?, title: String, source: Source): UniFile? {
        return provider.findCompanionImageDir(title, source, chapterName, scanlator)
    }

    fun translatePage(manga: Manga, chapter: Chapter, source: HttpSource, pageKey: String) {
        val jobKey = "${chapter.id}:$pageKey"
        // TachiyomiAT: do NOT cancel an in-flight job for this same page on a
        // duplicate request. The previous `activePageJobs[jobKey]?.cancel()`
        // here meant every repeated page-selection event (auto-mode firing on
        // scroll, a page-holder re-binding, or a rapid double-tap) tore down and
        // restarted the same translation — oscillating between cancel/restart,
        // visibly blinking the processing overlay, and re-decoding the bitmap
        // each time. The translator already dedups via inFlightPageKeys once the
        // permit is acquired, so a second launch for the same page is a no-op
        // for the expensive work. We only clear/replace a prior entry when it is
        // no longer active (completed or already cancelled), keeping the
        // map free of dead jobs while leaving a genuine in-flight job alone.
        val existing = activePageJobs[jobKey]
        if (existing != null && existing.isActive) {
            try { java.io.File("/sdcard/at_diag.txt").appendText("TM_SKIP_ACTIVE $jobKey\n") } catch (_: Exception) {}
            return
        }
        if (existing != null) {
            activePageJobs.remove(jobKey)
        }
        try { java.io.File("/sdcard/at_diag.txt").appendText("TM_LAUNCH $jobKey\n") } catch (_: Exception) {}
        val job = scope.launch {
            try {
                translator.translateSinglePage(manga, chapter, source, pageKey)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) {
                    "TachiyomiAT single-page translation failed: pageKey=$pageKey " +
                        "chapter=${chapter.name} manga=${manga.title} source=${source.id}"
                }
            } finally {
                activePageJobs.remove(jobKey)
            }
        }
        activePageJobs[jobKey] = job
    }

    /**
     * TachiyomiAT: cancels the in-flight single-page translation job for one
     * specific [pageKey] within [chapterId], if any. This is the per-page
     * granularity that [cancelPageTranslations] (chapter-scoped) is too coarse
     * for: it backs the per-page button's cancel affordance, so a user can stop
     * a single slow/stuck page without abandoning the whole chapter.
     *
     * Returns true if a job was actually cancelled, false if none was running
     * for that page (e.g. it already finished or the translator deduped it).
     */
    fun cancelPageTranslation(chapterId: Long, pageKey: String): Boolean {
        val jobKey = "$chapterId:$pageKey"
        val job = activePageJobs.remove(jobKey)
        job?.cancel()
        return job != null
    }

    /**
     * Cancels all in-flight single-page translation jobs for [chapterId] and
     * evicts the reader page streams registered for that chapter. Also drops the
     * shared [ChapterTranslationStore] for this chapter so it doesn't leak
     * across chapter navigations. Call this when the reader navigates away from
     * a chapter so the previous chapter's work can no longer hold the
     * translator's single permit.
     */
    fun cancelPageTranslations(chapterId: Long) {
        val prefix = "$chapterId:"
        val iterator = activePageJobs.entries.iterator()
        while (iterator.hasNext()) {
            val (key, job) = iterator.next()
            if (key.startsWith(prefix)) {
                job.cancel()
                iterator.remove()
            }
        }
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
     */
    fun cancelAllPageTranslations() {
        val iterator = activePageJobs.entries.iterator()
        while (iterator.hasNext()) {
            val (_, job) = iterator.next()
            job.cancel()
            iterator.remove()
        }
        // Evict every shared store + its collector.
        val chapterIds = activeTranslationStores.keys.toList()
        chapterIds.forEach { unregisterActiveTranslationStore(it) }
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
