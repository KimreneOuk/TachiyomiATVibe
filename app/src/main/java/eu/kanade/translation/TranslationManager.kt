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
import eu.kanade.translation.model.hasRecognizedTranslation
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
    private val translator = ChapterTranslator(context, provider);

    companion object {
        /**
         * Maximum time [cancelPageTranslations] will wait for a chapter's
         * cancelled jobs to finish unwinding (their finally blocks reset a
         * stranded page's RUNNING status on a NonCancellable child). Bounded so
         * chapter navigation stays responsive even if a job is slow to tear down.
         */
        private const val JOIN_TIMEOUT_MS = 2_000L
    }

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
     * Tracks independently-launched single-page jobs by `"$chapterId:$pageKey"`.
     * Sequential auto-prefetch does not create one coroutine per page, so it is
     * deduped separately by [queuedPageKeys] below.
     */
    private val activePageJobs = ConcurrentHashMap<String, Job>()

    /**
     * Tracks pages that are queued or currently executing inside an ordered
     * auto-prefetch batch. This closes the gap where page selection events
     * enqueue overlapping windows:
     *
     * page 4 -> [4,5,6]
     * page 5 -> [5,6,7]
     * page 4 again -> [4,5,6]
     *
     * Before this set, only the actively executing page was protected, so page
     * 6 could sit in multiple pending batches and run two to four times before
     * any persisted "done" state was visible to later batches.
     */
    private val queuedPageKeys = ConcurrentHashMap.newKeySet<String>()

    /**
     * Manager-owned auto-prefetch windows. These are the jobs the reader used to
     * launch on its own ViewModel scope; keeping them here makes auto off,
     * chapter switch, memory pressure, and reader close able to cancel the work
     * that is actually running.
     */
    private val activeAutoJobs = ConcurrentHashMap<String, Job>()
    private val autoWindowIds = AtomicLong(0L)

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

        // TachiyomiAT: wire the permit-watchdog callback. When a page's worker
        // is stuck in uncancellable native/HTTP code, the translator's watchdog
        // force-releases the permit (so other pages can proceed) and invokes
        // this. We then evict the dead job from activePageJobs, otherwise its
        // entry stays "active" and the existing.isActive dedup in translatePage
        // would silently drop every future retry of that page forever.
        translator.onPageStuck = { chapterId, pageKey ->
            if (chapterId != null && pageKey.isNotEmpty()) {
                markPageJobStuck(chapterId, pageKey)
            }
        }
    }

    /**
     * TachiyomiAT: evicts a single-page translation job that its worker has
     * abandoned (stuck in uncancellable native code past its deadline). The
     * coroutine itself can't be interrupted, but its [activePageJobs] entry is
     * stale: it reads as "active" so [translatePage]'s dedup keeps rejecting
     * retries for this page. Remove it (best-effort cancel first) so the page
     * becomes eligible for translation again. Idempotent.
     */
    fun markPageJobStuck(chapterId: Long, pageKey: String) {
        val jobKey = "$chapterId:$pageKey"
        val job = activePageJobs.remove(jobKey)
        try { job?.cancel() } catch (_: Throwable) {}
        queuedPageKeys.removeIf { it.startsWith("auto:$chapterId:") && it.endsWith(":${pageKey.replace(':', '_')}") }
        logcat(LogPriority.WARN) {
            "TachiyomiAT evicted stuck page job: jobKey=$jobKey (worker abandoned in native/HTTP code)"
        }
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
    fun translatorStop(reason: String? = null, closeEngines: Boolean = false) = translator.stop(reason, closeEngines)

    fun onMemoryPressure(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            cancelAllPageTranslations()
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
    ) {
        if (requests.isEmpty()) return
        val accepted = mutableListOf<Pair<String, TranslationPageRequest>>()
        val distinctRequests = requests.distinctBy { it.id }
            .sortedWith(compareBy<TranslationPageRequest> { it.priority }.thenBy { it.id.pageIndex })

        for (request in distinctRequests) {
            val current = session.store.state.value[request.storageKey]
            if (current != null && current.shouldSkipAutoScheduling) {
                logAutoDecision("skipped", request, current, request.streamAvailable)
                continue
            }

            val manualJobKey = "${request.id.chapterId}:${request.storageKey}"
            val manualJob = activePageJobs[manualJobKey]
            if (manualJob != null && manualJob.isActive) {
                logAutoDecision("skipped", request, current, request.streamAvailable, "manual-active")
                continue
            }
            if (manualJob != null) activePageJobs.remove(manualJobKey)

            val reservationKey = autoReservationKey(request)
            if (!queuedPageKeys.add(reservationKey)) {
                logAutoDecision("skipped", request, current, request.streamAvailable, "already-queued")
                continue
            }

            accepted.add(reservationKey to request)
            logAutoDecision("scheduled", request, current, request.streamAvailable)
        }

        if (accepted.isEmpty()) return

        val jobKey = "auto:${session.chapter.id}:${session.key}:${autoWindowIds.incrementAndGet()}"
        val job = scope.launch {
            val completedReservations = mutableSetOf<String>()
            try {
                for ((reservationKey, request) in accepted) {
                    coroutineContext.ensureActive()
                    val current = session.store.state.value[request.storageKey]
                    if (current != null && current.shouldSkipAutoScheduling) {
                        logAutoDecision("skipped", request, current, request.streamAvailable, "completed-while-queued")
                        completedReservations.add(reservationKey)
                        queuedPageKeys.remove(reservationKey)
                        continue
                    }
                    val manualJobKey = "${request.id.chapterId}:${request.storageKey}"
                    val manualJob = activePageJobs[manualJobKey]
                    if (manualJob != null && manualJob.isActive) {
                        logAutoDecision("skipped", request, current, request.streamAvailable, "manual-active-while-queued")
                        completedReservations.add(reservationKey)
                        queuedPageKeys.remove(reservationKey)
                        continue
                    }
                    if (manualJob != null) activePageJobs.remove(manualJobKey)

                    if (current != null &&
                        current.hasRecognizedTranslation &&
                        current.renderedImageName == null &&
                        current.cleanedImageName != null
                    ) {
                        try {
                            translator.translateSinglePage(
                                session.manga,
                                session.chapter,
                                session.source,
                                request.storageKey,
                                force = false,
                            )
                        } catch (e: CancellationException) {
                            markPageCancelled(session.chapter, request.storageKey)
                            logAutoDecision("cancelled", request, session.store.state.value[request.storageKey], request.streamAvailable)
                            throw e
                        } catch (e: Throwable) {
                            logAutoDecision("failed:resume-render", request, session.store.state.value[request.storageKey], request.streamAvailable)
                            logcat(LogPriority.ERROR, e) {
                                "TachiyomiAT auto resume render failed: id=${request.id} storageKey=${request.storageKey}"
                            }
                        } finally {
                            completedReservations.add(reservationKey)
                            queuedPageKeys.remove(reservationKey)
                        }
                        continue
                    }

                    val streamFn = try {
                        request.streamProvider()
                    } catch (e: CancellationException) {
                        logAutoDecision("cancelled", request, current, request.streamAvailable)
                        throw e
                    } catch (e: Throwable) {
                        logcat(LogPriority.WARN, e) {
                            "TachiyomiAT auto stream provider failed: id=${request.id} " +
                                "index=${request.id.pageIndex} storageKey=${request.storageKey}"
                        }
                        null
                    }

                    if (streamFn == null) {
                        logAutoDecision("soft-skip:no-stream", request, current, false)
                        completedReservations.add(reservationKey)
                        queuedPageKeys.remove(reservationKey)
                        continue
                    }

                    try {
                        translator.translateSinglePageFromStream(
                            session.manga,
                            session.chapter,
                            session.source,
                            request.storageKey,
                            streamFn,
                            force = false,
                        )
                    } catch (e: CancellationException) {
                        markPageCancelled(session.chapter, request.storageKey)
                        logAutoDecision("cancelled", request, session.store.state.value[request.storageKey], true)
                        throw e
                    } catch (e: Throwable) {
                        logAutoDecision("failed:pipeline", request, session.store.state.value[request.storageKey], true)
                        logcat(LogPriority.ERROR, e) {
                            "TachiyomiAT auto page translation failed: id=${request.id} " +
                                "storageKey=${request.storageKey} chapter=${session.chapter.name} " +
                                "manga=${session.manga.title} source=${session.source.id}"
                        }
                    } finally {
                        completedReservations.add(reservationKey)
                        queuedPageKeys.remove(reservationKey)
                    }
                }
            } catch (e: CancellationException) {
                for ((reservationKey, request) in accepted) {
                    if (reservationKey !in completedReservations) {
                        logAutoDecision(
                            "cancelled",
                            request,
                            session.store.state.value[request.storageKey],
                            request.streamAvailable,
                        )
                    }
                }
                throw e
            } finally {
                accepted.forEach { (reservationKey, _) -> queuedPageKeys.remove(reservationKey) }
                activeAutoJobs.remove(jobKey)
            }
        }
        activeAutoJobs[jobKey] = job
    }

    fun cancelAutoTranslations(chapterId: Long? = null): Boolean {
        val jobPrefix = chapterId?.let { "auto:$it:" }
        val queuePrefix = chapterId?.let { "auto:$it:" }
        var cancelled = false

        val jobIterator = activeAutoJobs.entries.iterator()
        while (jobIterator.hasNext()) {
            val (key, job) = jobIterator.next()
            if (jobPrefix == null || key.startsWith(jobPrefix)) {
                job.cancel()
                jobIterator.remove()
                cancelled = true
            }
        }

        if (queuePrefix == null) {
            queuedPageKeys.removeIf { it.startsWith("auto:") }
        } else {
            queuedPageKeys.removeIf { it.startsWith(queuePrefix) }
        }

        return cancelled
    }

    private fun autoReservationKey(request: TranslationPageRequest): String {
        val safeStorageKey = request.storageKey.replace(':', '_')
        return "auto:${request.id.chapterId}:${request.id.sourceId}:${request.id.mangaId}:${request.id.pageIndex}:$safeStorageKey"
    }

    private fun logAutoDecision(
        decision: String,
        request: TranslationPageRequest,
        page: PageTranslation?,
        streamAvailable: Boolean?,
        reason: String? = null,
    ) {
        logcat(LogPriority.INFO) {
            "TachiyomiAT auto page $decision: id=${request.id} index=${request.id.pageIndex} " +
                "storageKey=${request.storageKey} lifecycle=${page?.lifecycle ?: eu.kanade.translation.model.PageLifecycle.Pending} " +
                "retry=${page?.retryCount ?: 0} streamAvailable=${streamAvailable ?: "unknown"} " +
                "rendered=${page?.renderedImageName != null} cleaned=${page?.cleanedImageName != null}" +
                (reason?.let { " reason=$it" } ?: "")
        }
    }

    fun observeActiveStore(chapterId: Long): StateFlow<Map<String, PageTranslation>>? {
        return activeTranslationStores[chapterId]?.state
    }

    fun observePageView(chapterId: Long, pageKey: String): Flow<PageView>? {
        return observeActiveStore(chapterId)
            ?.map { pages -> pages[pageKey].toPageView() }
            ?.distinctUntilChanged()
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
            logcat(LogPriority.DEBUG) { "translatePage: skipping $jobKey (already active)" }
            return
        }
        if (existing != null) {
            activePageJobs.remove(jobKey)
        }
        logcat(LogPriority.DEBUG) { "translatePage: launching $jobKey" }
        val job = scope.launch {
            var cancelledMidFlight = false
            try {
                translator.translateSinglePage(manga, chapter, source, pageKey)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The job was cancelled (chapter switch, reader exit, Stop all).
                // If this happened after the translator wrote ocrStatus=RUNNING
                // but before it reached a terminal state, the page is stranded
                // RUNNING — the store never gets updated because the coroutine
                // is torn down. Flag it so the finally can reset the status,
                // matching the single-page timeout handling in ChapterTranslator.
                cancelledMidFlight = true
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) {
                    "TachiyomiAT single-page translation failed: pageKey=$pageKey " +
                        "chapter=${chapter.name} manga=${manga.title} source=${source.id}"
                }
            } finally {
                activePageJobs.remove(jobKey)
                if (cancelledMidFlight) {
                    // Best-effort: clear a stranded RUNNING status so the reader's
                    // overlay/spinner and disabled translate icon recover. Runs on
                    // a non-cancellable child launch so the reset itself can't be
                    // torn down by the cancellation that triggered it. Guarded so
                    // a reset failure never masks the original CancellationException.
                    try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            markPageCancelled(chapter, pageKey)
                        }
                    } catch (resetError: Throwable) {
                        logcat(LogPriority.WARN, resetError) {
                            "TachiyomiAT reset of stranded page status failed: pageKey=$pageKey"
                        }
                    }
                }
            }
        }
        activePageJobs[jobKey] = job
    }

    /**
     * TachiyomiAT: strictly-ordered sequential translation for a list of
     * [pageKeys] within the same chapter. Each page is processed to completion
     * (or failure) before the next begins — absolute ordering guarantee.
     *
     * This is the prefetch backbone for auto-translate: the reader hands the
     * current page plus lookahead pages here, and this method translates them
     * strictly in page order. Overlapping page-selection events are expected,
     * so pages are first reserved in [queuedPageKeys]. That reservation covers
     * both "waiting in an older sequential batch" and "currently executing",
     * which prevents the same prefetch page from being processed several times
     * before the first batch reaches a persisted terminal state.
     *
     * Cancellation is cooperative: [ensureActive] runs before reservation and
     * before each page; all reservations are cleared in finally blocks.
     */
    suspend fun translatePagesSequential(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKeys: List<String>,
    ) {
        val chapterId = chapter.id ?: return
        val accepted = mutableListOf<Pair<String, String>>()

        for (pageKey in pageKeys.distinct()) {
            coroutineContext.ensureActive()
            val jobKey = "$chapterId:$pageKey"
            val existing = activePageJobs[jobKey]
            if (existing != null && existing.isActive) {
                logcat(LogPriority.DEBUG) { "TachiyomiAT sequential SKIP (active): pageKey=$pageKey" }
                continue
            }
            if (existing != null) activePageJobs.remove(jobKey)

            val current = activeTranslationStores[chapterId]?.state?.value?.get(pageKey)
            if (current != null) {
                if (current.shouldSkipAutoScheduling) {
                    logcat(LogPriority.INFO) {
                        "TachiyomiAT sequential SKIP (already done): pageKey=$pageKey " +
                            "rendered=${current.renderedImageName != null} trans=${current.translationStatus}"
                    }
                    continue
                }
            }

            if (!queuedPageKeys.add(jobKey)) {
                logcat(LogPriority.DEBUG) { "TachiyomiAT sequential SKIP (already queued): pageKey=$pageKey" }
                continue
            }

            accepted.add(jobKey to pageKey)
        }

        try {
            for ((jobKey, pageKey) in accepted) {
                coroutineContext.ensureActive()
                val current = activeTranslationStores[chapterId]?.state?.value?.get(pageKey)
                if (current != null && current.shouldSkipAutoScheduling) {
                    logcat(LogPriority.INFO) {
                        "TachiyomiAT sequential SKIP (completed while queued): pageKey=$pageKey " +
                            "rendered=${current.renderedImageName != null} trans=${current.translationStatus}"
                    }
                    continue
                }
                val existing = activePageJobs[jobKey]
                if (existing != null && existing.isActive) {
                    logcat(LogPriority.DEBUG) { "TachiyomiAT sequential SKIP (active while queued): pageKey=$pageKey" }
                    continue
                }
                if (existing != null) activePageJobs.remove(jobKey)

                try {
                    translator.translateSinglePage(manga, chapter, source, pageKey)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logcat(LogPriority.ERROR, e) {
                        "TachiyomiAT sequential page translation failed: pageKey=$pageKey " +
                            "chapter=${chapter.name} manga=${manga.title} source=${source.id}"
                    }
                } finally {
                    queuedPageKeys.remove(jobKey)
                }
            }
        } finally {
            accepted.forEach { (jobKey, _) -> queuedPageKeys.remove(jobKey) }
        }
            }

    /**
     * Clears a non-terminal RUNNING/PENDING status for [pageKey] left behind
     * when translation was cancelled mid-flight. Normal cancellation is not a
     * stage failure and must not increment retryCount.
     */
    private suspend fun markPageCancelled(chapter: Chapter, pageKey: String) {
        val chapterId = chapter.id ?: return
        val store = activeTranslationStores[chapterId] ?: return
        // Peek the live state first. If the page has no entry (it was never
        // tracked) or is already terminal, there is nothing stranded to reset —
        // skip the write entirely rather than creating a spurious FAILED entry.
        val existing = store.state.value[pageKey] ?: return
        val hasResult = existing.renderedImageName != null
        val failed = existing.ocrStatus == StageStatus.FAILED ||
            existing.inpaintStatus == StageStatus.FAILED ||
            existing.translationStatus == StageStatus.FAILED ||
            existing.renderStatus == StageStatus.FAILED
        if (hasResult || failed) return
        // Stranded RUNNING/PENDING: flip the active stage(s) to CANCELLED so the
        // reader clears the overlay and auto can schedule the page again later.
        store.updatePage(pageKey) { current ->
            // Re-check inside the lock in case it changed between peek and write.
            val cur = current ?: return@updatePage PageTranslation(
                sourceFileName = pageKey,
                ocrStatus = StageStatus.CANCELLED,
                errorMessage = "Translation cancelled",
                updatedAt = System.currentTimeMillis(),
            )
            val curHasResult = cur.renderedImageName != null
            val curFailed = cur.ocrStatus == StageStatus.FAILED ||
                cur.inpaintStatus == StageStatus.FAILED ||
                cur.translationStatus == StageStatus.FAILED ||
                cur.renderStatus == StageStatus.FAILED
            if (curHasResult || curFailed) return@updatePage cur
            cur.apply {
                if (ocrStatus == StageStatus.RUNNING || ocrStatus == StageStatus.PENDING) {
                    ocrStatus = StageStatus.CANCELLED
                }
                if (inpaintStatus == StageStatus.RUNNING) {
                    inpaintStatus = StageStatus.CANCELLED
                }
                if (translationStatus == StageStatus.RUNNING) {
                    translationStatus = StageStatus.CANCELLED
                }
                if (renderStatus == StageStatus.RUNNING) {
                    renderStatus = StageStatus.CANCELLED
                }
                errorMessage = "Translation cancelled"
                updatedAt = System.currentTimeMillis()
            }
        }
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
        queuedPageKeys.remove(jobKey)
        queuedPageKeys.removeIf { it.startsWith("auto:$chapterId:") && it.endsWith(":${pageKey.replace(':', '_')}") }
        val job = activePageJobs.remove(jobKey)
        job?.cancel()
        val autoCancelled = cancelAutoTranslations(chapterId)
        return job != null || autoCancelled
    }

    /**
     * Cancels all in-flight single-page translation jobs for [chapterId] and
     * evicts the reader page streams registered for that chapter. Also drops the
     * shared [ChapterTranslationStore] for this chapter so it doesn't leak
     * across chapter navigations. Call this when the reader navigates away from
     * a chapter so the previous chapter's work can no longer hold the
     * translator's single permit.
     *
     * TachiyomiAT: suspend + bounded join. After cancelling, it waits (up to
     * [JOIN_TIMEOUT_MS]) for those jobs to actually finish their finally blocks.
     * translatePage's finally resets a stranded page's RUNNING status on a
     * NonCancellable child, which itself needs the coroutine to unwind. Without
     * waiting, the previous chapter's reset could land AFTER the reader
     * subscribed to the new chapter's store — briefly surfacing chapter 1's
     * state (or its RUNNING overlay) while chapter 2 is on screen. The timeout
     * keeps chapter navigation responsive even if a job is slow to unwind.
     */
    suspend fun cancelPageTranslations(chapterId: Long) {
        val prefix = "$chapterId:"
        queuedPageKeys.removeIf { it.startsWith(prefix) }
        queuedPageKeys.removeIf { it.startsWith("auto:$chapterId:") }
        val toJoin = mutableListOf<Job>()
        val autoIterator = activeAutoJobs.entries.iterator()
        while (autoIterator.hasNext()) {
            val (key, job) = autoIterator.next()
            if (key.startsWith("auto:$chapterId:")) {
                job.cancel()
                toJoin.add(job)
                autoIterator.remove()
            }
        }
        val iterator = activePageJobs.entries.iterator()
        while (iterator.hasNext()) {
            val (key, job) = iterator.next()
            if (key.startsWith(prefix)) {
                job.cancel()
                toJoin.add(job)
                iterator.remove()
            }
        }
        // Wait for the cancelled jobs to finish their finally blocks (including
        // the stranded-status reset) so the previous chapter is fully wound down
        // before the caller subscribes to the next chapter's store. Bounded so a
        // stuck unwind can't hang chapter navigation.
        if (toJoin.isNotEmpty()) {
            kotlinx.coroutines.withTimeoutOrNull(JOIN_TIMEOUT_MS) {
                toJoin.joinAll()
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
        queuedPageKeys.clear()
        val autoIterator = activeAutoJobs.entries.iterator()
        while (autoIterator.hasNext()) {
            val (_, job) = autoIterator.next()
            job.cancel()
            autoIterator.remove()
        }
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
