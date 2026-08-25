package eu.kanade.translation

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.model.Translation
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.scheduling.TranslationStreamRegistry
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TranslationEngineBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.core.archive.ArchiveReader
import mihon.core.archive.archiveReader
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.domain.translation.pools.BitmapPool
import tachiyomi.i18n.at.ATMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream

class ChapterTranslator(
    private val context: Context,
    private val provider: TranslationProvider,
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
    private val streamRegistry: TranslationStreamRegistry = Injekt.get(),
    private val queueStore: TranslationQueueStore = TranslationQueueStore(context),
    private val pipeline: TranslationPipeline = TranslationPipeline(
        context,
        provider,
        downloadProvider,
        translationPreferences,
        streamRegistry,
    ),
) {

    companion object {
        // TachiyomiAT: bounded join for the batch translator job during delete.
        // The batch worker can be mid-uncancellable native ONNX (OrtSession.run)
        // when cancel() is requested; coroutine cancellation only lands at the
        // next suspension point. Bounding the join prevents a delete from hanging
        // on a job that won't unwind promptly (matches TranslationScheduler.JOIN_TIMEOUT_MS).
        // On timeout the file delete proceeds and the defunct-store guard
        // (ChapterTranslationStore.markDefunct) neutralizes any late write.
        const val BATCH_JOIN_TIMEOUT_MS = 2_000L

        // TachiyomiAT: reader page streams now live in [TranslationStreamRegistry]
        // (a dedicated DI singleton). These companions are thin delegates kept so
        // existing static call sites compile during the incremental migration.
        private val streamRegistry: TranslationStreamRegistry
            get() = Injekt.get()

        fun registerReaderPageStream(
            manga: Manga,
            chapter: Chapter,
            source: HttpSource,
            pageKey: String,
            streamFn: () -> InputStream,
        ) {
            streamRegistry.register(manga, chapter, source, pageKey, streamFn)
        }

        /**
         * Returns the registered reader stream for this page WITHOUT removing it.
         * The stream is a `() -> InputStream` factory (one invocation per retry),
         * so evicting on first use (the old `readerPageStreams.remove(...)` path)
         * meant a failed translation could never be retried — it silently wrote a
         * FAILED placeholder. The stream is dropped only on chapter cleanup via
         * [clearReaderPageStreams].
         */
        private fun peekReaderPageStream(
            manga: Manga,
            chapter: Chapter,
            source: HttpSource,
            pageKey: String,
        ): (() -> InputStream)? = streamRegistry.peek(manga, chapter, source, pageKey)

        /**
         * Evicts every reader page stream registered for [mangaId]/[sourceId] in
         * [chapterId]. Each entry holds a `() -> InputStream` closure over a
         * [eu.kanade.tachiyomi.ui.reader.model.ReaderPage] that can keep page
         * bitmaps/sources alive, so this must run on chapter change to avoid
         * leaking memory and stale streams across chapters.
         */
        fun clearReaderPageStreams(sourceId: Long, mangaId: Long, chapterId: Long) {
            streamRegistry.clearChapter(sourceId, mangaId, chapterId)
        }

        /**
         * TachiyomiAT: evicts EVERY registered reader page stream. Each entry
         * holds a `() -> InputStream` closure over a
         * [eu.kanade.tachiyomi.ui.reader.model.ReaderPage] (and, on the eager
         * prefetch path, a captured downloaded [ByteArray]); leaving them in the
         * process-lifetime map on reader background / "stop all translation"
         * pins those bytes/pages until the process dies. Call on reader
         * background / stop-all so memory is released.
         */
        fun clearAllReaderPageStreams() {
            streamRegistry.clearAll()
        }
    }

    private val _queueState = MutableStateFlow<List<Translation>>(emptyList())
    val queueState = _queueState.asStateFlow()

    /**
     * TachiyomiAT: persists the queue (ordered chapter ids) to disk after every
     * mutation so a crash mid-batch no longer loses it. One SharedPreferences
     * editor batch; idempotent.
     */
    private fun persistQueue() {
        queueStore.save(_queueState.value.map { it.chapter.id ?: return })
    }

    /**
     * TachiyomiAT: rehydrates the queue from disk on launch via
     * [Translation.fromChapterId]. Deleted chapters self-heal (fromChapterId
     * returns null, so stale ids drop). Rehydrated entries get status QUEUE:
     * rehydrate but require Start — never auto-start background OCR/LLM work on
     * launch. Must run in a coroutine (suspend lookups).
     */
    suspend fun restoreQueue() {
        val ids = queueStore.load()
        if (ids.isEmpty()) return
        val restored = mutableListOf<Translation>()
        for (id in ids) {
            val translation = Translation.fromChapterId(id) ?: continue
            translation.status = Translation.State.QUEUE
            restored += translation
        }
        if (restored.isNotEmpty()) {
            _queueState.update { restored }
            // Re-save so any self-healed (null) drops are persisted.
            queueStore.save(restored.mapNotNull { it.chapter.id })
            logcat(LogPriority.INFO) {
                "TachiyomiAT restored ${restored.size}/${ids.size} queued translations from disk"
            }
        } else {
            // All persisted ids were stale (chapters deleted) — clear the store.
            queueStore.clear()
            logcat(LogPriority.INFO) {
                "TachiyomiAT queue restore: all ${ids.size} persisted ids were stale; cleared store"
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var translationJob: Job? = null

    val isRunning: Boolean
        get() = translationJob?.isActive == true

    @Volatile
    var isPaused: Boolean = false

    fun start(): Boolean {
        if (isRunning || queueState.value.isEmpty()) {
            return false
        }

        val pending = queueState.value.filter { it.status != Translation.State.TRANSLATED }
        pending.forEach { if (it.status != Translation.State.QUEUE) it.status = Translation.State.QUEUE }
        isPaused = false
        launchTranslatorJob()
        return pending.isNotEmpty()
    }

    fun stop(reason: String? = null, closeEngines: Boolean = false) {
        cancelTranslatorJob()
        // Interrupted chapters stay resumable: their per-page artifacts are
        // durable, so a later Start continues from the planner's reuse scan.
        queueState.value.filter { it.status == Translation.State.TRANSLATING }
            .forEach { it.status = Translation.State.QUEUE }

        if (reason == "reader backgrounded") {
            try {
                pipeline.forceReleaseNativeBuffers()
            } catch (_: Exception) {}
        }

        // TachiyomiAT: the historical `if (reason != null) return` skipped
        // closeEngines() for EVERY non-null-reason stop — including the user's
        // explicit "Stop all translation". That left the cached textTranslator /
        // recognitionEngine alive (enginesClosed stayed false), so a later config
        // change (engine, provider, API key, model, language, OCR model) was
        // ignored on the next run: the rebuild gate never fired.
        // closeEngines now tears down + rearms when a caller explicitly asks for
        // it; background/memory-pressure stops leave it false to stay lightweight.
        if (reason != null && !closeEngines) return
        isPaused = false
        pipeline.closeEngines()
    }

    /** Releases transient native and bitmap memory in response to OS pressure. */
    fun onMemoryPressure(level: Int, pressureClass: MemoryPressureClass) {
        tachiyomi.domain.translation.pools.BitmapPool.releaseAll()
        try {
            pipeline.forceReleaseNativeBuffers()
        } catch (_: Exception) {}
        // TachiyomiAT: Do NOT cancel the translator job even under Critical memory pressure.
        // If we cancel the job, we prematurely drop the HTTP connection to the AI engine while
        // it is still generating, causing the UI to "lag behind" (showing Aborted while the engine translates).
        // If the OS truly needs memory, it will kill the process and we will resume via restoreQueue() on restart.
    }

    fun pause() {
        cancelTranslatorJob()
        queueState.value.filter { it.status == Translation.State.TRANSLATING }
            .forEach { it.status = Translation.State.QUEUE }
        isPaused = true
    }

    fun clearQueue() {
        cancelTranslatorJob()
        internalClearQueue()
    }

    private fun launchTranslatorJob() {
        if (isRunning) return

        translationJob = scope.launch {
            val activeTranslationFlow = queueState.transformLatest { queue ->
                while (true) {
                    val activeTranslations =
                        queue.asSequence().filter { it.status.value <= Translation.State.TRANSLATING.value }
                            .groupBy { it.source }.toList().take(1).map { (_, translations) -> translations.first() }
                    emit(activeTranslations)

                    if (activeTranslations.isEmpty()) break
                    val activeTranslationsErroredFlow =
                        combine(activeTranslations.map(Translation::statusFlow)) { states ->
                            states.contains(Translation.State.ERROR)
                        }.filter { it }
                    activeTranslationsErroredFlow.first()
                }
            }.distinctUntilChanged()
            supervisorScope {
                val translationJobs = mutableMapOf<Translation, Job>()

                activeTranslationFlow.collectLatest { activeTranslations ->
                    val translationJobsToStop = translationJobs.filter { it.key !in activeTranslations }
                    translationJobsToStop.forEach { (download, job) ->
                        job.cancel()
                        translationJobs.remove(download)
                    }

                    val translationsToStart = activeTranslations.filter { it !in translationJobs }
                    translationsToStart.forEach { translation ->
                        translationJobs[translation] = launchTranslationJob(translation)
                    }
                }
            }
        }
    }

    private fun CoroutineScope.launchTranslationJob(translation: Translation) = launchIO {
        try {
            translateChapter(translation)
            if (translation.status == Translation.State.TRANSLATED ||
                translation.status == Translation.State.READY_WITH_WARNINGS
            ) {
                removeFromQueue(translation)
            }
            if (areAllTranslationsFinished()) {
                stop()
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            // One chapter's unexpected failure must not kill the rest of the
            // queue: mark the chapter failed so the queue loop advances.
            logcat(LogPriority.ERROR, e)
            translation.status = Translation.State.ERROR
            if (areAllTranslationsFinished()) {
                stop()
            }
        }
    }

    private fun cancelTranslatorJob() {
        translationJob?.cancel()
        translationJob = null
    }

    /**
     * Cancels the batch translator job AND waits for it to unwind, bounded by
     * [BATCH_JOIN_TIMEOUT_MS]. Used only by the delete path: a chapter's on-disk
     * translation file + images must not be deleted while the batch coroutine
     * still holds the old [ChapterTranslationStore] reference and may write
     * RUNNING/rendered results into it. Joining guarantees the job reaches a
     * suspension point (finally blocks run, permit released) before
     * [TranslationManager.deleteTranslation] deletes files. Plain
     * [cancelTranslatorJob] stays non-blocking so pause/stop/clearQueue keep
     * their behaviour. On timeout this returns anyway; the defunct-store guard
     * rejects subsequent writes and NativeRunQuarantine invalidates the timed-out
     * native generation.
     */
    suspend fun cancelTranslatorJobAndJoin() {
        val job = translationJob ?: return
        translationJob = null
        val joined = withTimeoutOrNull(BATCH_JOIN_TIMEOUT_MS) { job.cancelAndJoin() }
        if (joined == null) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT batch translator job did not unwind within " +
                    "$BATCH_JOIN_TIMEOUT_MS ms on delete; proceeding (defunct guard + " +
                    "native-run quarantine will neutralize any late write)"
            }
        }
    }

    fun queueChapter(manga: Manga, chapter: Chapter) {
        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        if (queueState.value.any { it.chapter.id == chapter.id }) return
        // TachiyomiAT: STRICT no-fallback. fromPref now throws on invalid config
        // (corrupted/migrated pref). This runs on a UI action, so a thrown
        // exception would crash the UI thread; catch it and surface as a toast
        // instead of queuing a translation that fails every page with the same error.
        val fromLang: TextRecognizerLanguage
        val toLang: TextTranslatorLanguage
        try {
            fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
            toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
        } catch (e: IllegalArgumentException) {
            logcat(LogPriority.ERROR, e) { "TachiyomiAT queueChapter aborted: invalid translation config" }
            scope.launchUI {
                context.toast(e.message ?: "Invalid translation configuration")
            }
            return
        }
        if (TranslationEngineBuilder.isMlKitActive(translationPreferences) &&
            !TextTranslatorLanguage.mlkitSupportedLanguages().contains(toLang)
        ) {
            scope.launchUI {
                context.toast(ATMR.strings.error_mlkit_language_unsupported)
            }
            return
        }
        val translation = Translation(source, manga, chapter, fromLang, toLang)
        addToQueue(translation)
    }

    private suspend fun translateChapter(translation: Translation) {
        translateChapterInternal(translation)
    }

    private suspend fun translateChapterInternal(translation: Translation) {
        var store: ChapterTranslationStore? = null
        var tracker: eu.kanade.translation.batch.TranslationBatchProgressTracker? = null
        var batchOrderedPageKeys: List<String> = emptyList()
        try {
            // Prefer the shared active store from TranslationManager so the reader
            // observes the same instance the pipeline writes to.
            store = pipeline.activeStoreResolver?.invoke(translation)
            if (store == null) {
                val existingFile = provider.findTranslationFile(
                    translation.chapter.name,
                    translation.chapter.scanlator,
                    translation.manga.title,
                    translation.source,
                )
                val translationFile = if (existingFile != null && existingFile.exists()) {
                    existingFile
                } else {
                    val translationMangaDir = provider.getMangaDir(translation.manga.title, translation.source)
                    val saveFile = provider.getTranslationFileName(
                        translation.chapter.name,
                        translation.chapter.scanlator,
                    )
                    translationMangaDir.createFile(saveFile)
                }
                if (translationFile == null) {
                    logcat(LogPriority.ERROR) {
                        "TachiyomiAT cannot create translation file for ${translation.chapter.name}"
                    }
                    translation.status = Translation.State.ERROR
                    return
                }
                store = ChapterTranslationStore.open(translationFile)
            }

            val chapterPath = downloadProvider.findChapterDir(
                translation.chapter.name,
                translation.chapter.scanlator,
                translation.manga.title,
                translation.source,
            )
            // Chapter files may be gone (deleted mid-translation) or never
            // existed for a streamed chapter; fail the chapter cleanly rather
            // than NPE'ing.
            if (chapterPath == null) {
                logcat(LogPriority.ERROR) {
                    "TachiyomiAT chapter files not found for ${translation.chapter.name}"
                }
                translation.status = Translation.State.ERROR
                return
            }
            translation.status = Translation.State.TRANSLATING

            // TachiyomiAT: for archive chapters, share one ArchiveReader across
            // the whole batch instead of reopening + full decompression per page
            // (the old getChapterPages closures did archiveReader().use {} on every
            // streamFn() — O(pages) re-decompressions). Directory chapters use
            // cheap direct file opens.
            val streams: List<Pair<String, () -> InputStream>>
            val sharedArchive: mihon.core.archive.ArchiveReader?
            if (chapterPath.isFile) {
                sharedArchive = chapterPath.archiveReader(context)
                // Each closure reads from the shared reader; it's mmap'd so
                // reads are seek-based, not re-decompressing.
                streams = sharedArchive.useEntries { entries ->
                    entries.filter { it.isFile && ImageUtil.isImage(it.name) }
                        .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
                        .map { entry ->
                            Pair(entry.name) {
                                sharedArchive.getInputStream(entry.name)
                                    ?: throw java.io.IOException(
                                        "Archive entry '${entry.name}' could not be opened (mmap)",
                                    )
                            }
                        }.toList()
                }
            } else {
                sharedArchive = null
                streams = getChapterPages(chapterPath)
            }
            val naturalPageIndexes = streams.mapIndexed { index, (pageKey, _) -> pageKey to index }.toMap()

            try {
                // Chapter batches are deterministic natural-order traversals.
                // Reader viewport/last-read position is intentionally not a
                // batch scheduling input; resume is decided per stage by the
                // pipeline planner while pages remain 1..N.
                val orderedStreams = eu.kanade.translation.util.ResumeOrdering.naturalOrder(streams)
                batchOrderedPageKeys = orderedStreams.map { it.first }
                store.preRegisterPages(batchOrderedPageKeys)
                val chapterId = translation.chapter.id
                tracker = if (chapterId != null) {
                    pipeline.batchTrackerFactory?.invoke(chapterId, store, orderedStreams.map { it.first })
                } else {
                    null
                }
                // Publish durable page progress before resumed work can emit
                // new phase events. Reuse-only pages may otherwise leave the
                // live tracker at its empty 0/0 snapshot until batch finish.
                tracker?.rebuildFromStore()
                if (translationJob?.isActive != true) {
                    logcat(LogPriority.INFO) { "TachiyomiAT batch cancelled before start: ${translation.chapter.name}" }
                } else {
                    pipeline.translateBatch(
                        translation.manga,
                        translation.chapter,
                        translation.source,
                        store,
                        orderedStreams,
                        tracker,
                        naturalPageIndexes,
                    )
                }
            } finally {
                try {
                    sharedArchive?.close()
                } catch (_: Exception) {}
            }

            val pageStates = store.state.value
            val reconciliation = eu.kanade.translation.batch.BatchProgressReconciler.reconcile(
                pageMap = pageStates,
                orderedKeys = batchOrderedPageKeys,
                activeGeneration = store.currentGeneration,
            )
            translation.status = reconciliation.chapterStatus
        } catch (error: Throwable) {
            if (error is CancellationException) {
                // If it's no longer in the queue, it was explicitly removed (cancelled).
                // If it's still in the queue, it was merely paused or memory-requeued; do not abort the tracker so it can be resumed.
                if (!queueState.value.contains(translation)) {
                    tracker?.abort(batchOrderedPageKeys.toSet(), "Batch cancelled")
                }
                store?.flush()
                throw error
            }
            BitmapPool.releaseAll()
            translation.status = Translation.State.ERROR
            logcat(LogPriority.ERROR, error)
        }
    }

    private fun getChapterPages(chapterPath: UniFile): List<Pair<String, () -> InputStream>> {
        if (chapterPath.isFile) {
            chapterPath.archiveReader(context).use { reader ->
                return reader.useEntries { entries ->
                    entries.filter { entry ->
                        // Null-safe: a corrupt/revoked archive can make
                        // getInputStream return null; ImageUtil.isImage handles
                        // a null name. Skip unreadable entries instead of NPE'ing.
                        entry.isFile &&
                            ImageUtil.isImage(entry.name) {
                                reader.getInputStream(entry.name)
                                    ?: throw java.io.IOException("Archive entry '${entry.name}' could not be opened")
                            }
                    }
                        .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }.map { entry ->
                            Pair(entry.name) {
                                chapterPath.archiveReader(context).use { archive ->
                                    // Null-safe stream: throw an explicit, loggable
                                    // IOException instead of an NPE if the entry
                                    // vanished or the archive is corrupt, so the
                                    // caller's try/catch reports the real cause.
                                    val stream = archive.getInputStream(entry.name)
                                        ?: throw java.io.IOException(
                                            "Archive entry '${entry.name}' could not be opened",
                                        )
                                    stream.use { it.readBytes() }.inputStream()
                                }
                            }
                        }.toList()
                }
            }
        } else {
            // listFiles() returns null on I/O error or a revoked SAF tree URI;
            // return empty (the caller treats "no pages" as a clean no-op) instead
            // of NPE'ing.
            val files = chapterPath.listFiles() ?: run {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT getChapterPages: listFiles() returned null for ${chapterPath.filePath}"
                }
                return emptyList()
            }
            return files.mapNotNull { entry ->
                // entry.name is nullable on some SAF providers; skip nameless
                // entries instead of NPE'ing.
                val name = entry.name ?: return@mapNotNull null
                if (!ImageUtil.isImage(name)) return@mapNotNull null
                Pair(name) { entry.openInputStream() }
            }.sortedWith { f1, f2 -> f1.first.compareToCaseInsensitiveNaturalOrder(f2.first) }.toList()
        }
    }

    private fun areAllTranslationsFinished(): Boolean {
        return queueState.value.none { it.status.value <= Translation.State.TRANSLATING.value }
    }

    private fun addToQueue(translation: Translation) {
        translation.status = Translation.State.QUEUE
        _queueState.update {
            it + translation
        }
        persistQueue()
    }

    private fun removeFromQueue(translation: Translation) {
        _queueState.update {
            if (translation.status == Translation.State.TRANSLATING || translation.status == Translation.State.QUEUE) {
                translation.status = Translation.State.NOT_TRANSLATED
            }
            it - translation
        }
        persistQueue()
    }

    private inline fun removeFromQueueIf(predicate: (Translation) -> Boolean) {
        _queueState.update { queue ->
            val translations = queue.filter { predicate(it) }
            translations.forEach { translation ->
                if (translation.status == Translation.State.TRANSLATING ||
                    translation.status == Translation.State.QUEUE
                ) {
                    translation.status = Translation.State.NOT_TRANSLATED
                }
            }
            queue - translations
        }
        persistQueue()
    }

    fun removeFromQueue(chapter: Chapter) {
        removeFromQueueIf { it.chapter.id == chapter.id }
    }

    fun removeFromQueue(manga: Manga) {
        removeFromQueueIf { it.manga.id == manga.id }
    }

    private fun internalClearQueue() {
        _queueState.update {
            it.forEach { translation ->
                if (translation.status == Translation.State.TRANSLATING ||
                    translation.status == Translation.State.QUEUE
                ) {
                    translation.status = Translation.State.NOT_TRANSLATED
                }
            }
            emptyList()
        }
        persistQueue()
    }
}
