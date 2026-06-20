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
    private val pipeline: TranslationPipeline = TranslationPipeline(
        context,
        provider,
        downloadProvider,
        translationPreferences,
        streamRegistry,
    ),
) {

    companion object {
        // TachiyomiAT: reader page streams now live in [TranslationStreamRegistry]
        // (a dedicated, testable singleton). These companion functions are kept
        // as thin delegates to the DI singleton so existing static call sites
        // (ReaderViewModel, ChapterTranslator.onMemoryPressure) keep compiling
        // during the incremental migration; they will be replaced by direct
        // registry calls in the Tier 5 call-site migration.
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
         * Returns the registered reader stream for this page WITHOUT removing
         * it. The stream is a `() -> InputStream` factory, so it can be invoked
         * multiple times (once per retry); evicting it on first use (the old
         * `readerPageStreams.remove(...)` behaviour) meant a failed translation
         * could never be retried — the second attempt found no stream, fell
         * through to findChapterDir()==null for streamed chapters, and silently
         * wrote a FAILED placeholder. The stream is dropped only on chapter
         * cleanup via [clearReaderPageStreams] (chapter change / reader exit).
         */
        private fun peekReaderPageStream(
            manga: Manga,
            chapter: Chapter,
            source: HttpSource,
            pageKey: String,
        ): (() -> InputStream)? = streamRegistry.peek(manga, chapter, source, pageKey)

        /**
         * Evicts every reader page stream registered for [mangaId]/[sourceId] in
         * [chapterId]. Each registered entry holds a `() -> InputStream` closure
         * over a [eu.kanade.tachiyomi.ui.reader.model.ReaderPage], which can keep
         * page bitmaps/sources alive — so this must run on chapter change to
         * avoid leaking memory and stale streams across chapters.
         */
        fun clearReaderPageStreams(sourceId: Long, mangaId: Long, chapterId: Long) {
            streamRegistry.clearChapter(sourceId, mangaId, chapterId)
        }

        /**
         * TachiyomiAT: evicts EVERY registered reader page stream, regardless of
         * chapter. Each entry holds a `() -> InputStream` closure over a
         * [eu.kanade.tachiyomi.ui.reader.model.ReaderPage] (and for the new eager
         * prefetch path, a captured downloaded [ByteArray]), so leaving them in
         * the process-lifetime map on reader background / "stop all translation"
         * keeps those bytes/pages alive until the process dies. Call this from
         * [ReaderViewModel.cancelTranslationsOnBackground] and [stopAllTranslation]
         * so backgrounding the reader releases the streams instead of pinning
         * page bitmaps in memory.
         */
        fun clearAllReaderPageStreams() {
            streamRegistry.clearAll()
        }
    }

    private val _queueState = MutableStateFlow<List<Translation>>(emptyList())
    val queueState = _queueState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        queueState.value.filter { it.status == Translation.State.TRANSLATING }
            .forEach { it.status = Translation.State.ERROR }
        // TachiyomiAT: the historical `if (reason != null) return` skipped
        // closeEngines() for EVERY non-null-reason stop — including the user's
        // explicit "Stop all translation". That left the cached textTranslator /
        // recognitionEngine alive (enginesClosed stayed false), so any config
        // change made after stopping (engine, provider, API key, model, language,
        // OCR model) was ignored on the next run: the rebuild gate never fired.
        //
        // closeEngines now tears down + rearms (sets enginesClosed = true) when a
        // caller explicitly asks for it. User-initiated stops pass closeEngines =
        // true so the next translate rebuilds unconditionally from live prefs.
        // Background / memory-pressure stops leave closeEngines = false to stay
        // lightweight (the engines may be reused shortly).
        if (reason != null && !closeEngines) return
        isPaused = false
        pipeline.closeEngines()
    }

    fun onMemoryPressure(level: Int) {
        tachiyomi.domain.translation.pools.BitmapPool.releaseAll()
        when {
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                stop("memory pressure")
                pipeline.closeEngines()
                clearAllReaderPageStreams()
            }
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                cancelTranslatorJob()
                queueState.value.filter { it.status == Translation.State.TRANSLATING }
                    .forEach { it.status = Translation.State.QUEUE }
                clearAllReaderPageStreams()
            }
        }
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
            if (translation.status == Translation.State.TRANSLATED) {
                removeFromQueue(translation)
            }
            if (areAllTranslationsFinished()) {
                stop()
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e)
            stop()
        }
    }

    private fun cancelTranslatorJob() {
        translationJob?.cancel()
        translationJob = null
    }

    fun queueChapter(manga: Manga, chapter: Chapter) {
        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source)?.let { existing ->
            logcat(LogPriority.INFO) { "Replacing existing translation file for ${chapter.name}" }
            existing.delete()
        }
        provider.deleteCompanionImages(manga.title, source, chapter.name, chapter.scanlator)
        if (queueState.value.any { it.chapter.id == chapter.id }) return
        val fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
        val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
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
        try {
            // Prefer the shared active store from TranslationManager so the
            // reader observes the same instance that the pipeline writes to.
            store = pipeline.activeStoreResolver?.invoke(translation)
            if (store == null) {
                val translationMangaDir = provider.getMangaDir(translation.manga.title, translation.source)
                val saveFile = provider.getTranslationFileName(translation.chapter.name, translation.chapter.scanlator)
                // SAF createFile can return null on a missing/revoked storage
                // location; treat that as a hard failure instead of NPE'ing.
                val translationFile = translationMangaDir.createFile(saveFile)
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

            // TachiyomiAT: for archive chapters, share one ArchiveReader across
            // the entire batch instead of reopening + full decompression per page
            // (the old getChapterPages closures called archiveReader().use {}
            // on every streamFn() invocation, O(pages) re-decompressions).
            // Directory chapters use direct file opens, which is already cheap.
            val streams: List<Pair<String, () -> InputStream>>
            val sharedArchive: mihon.core.archive.ArchiveReader?
            if (chapterPath.isFile) {
                sharedArchive = chapterPath.archiveReader(context)
                // Build the list eagerly; each closure reads from the shared
                // reader. The reader is mmap'd so reads are seek-based, not
                // re-decompressing.
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

            try {
                // TachiyomiAT: run the chapter through the STAGED BATCH pipeline
                // (DETECT+OCR batch → inpaint ‖ translate → render) instead of the
                // old page-1-first sequential loop. Order pages so the user's
                // resume position is translated first: forward from the last-read
                // page to the end, then backfill the pages before it. This makes
                // pre-translating a chapter you're mid-way through actually useful
                // — the page you'll read next is ready first, not page 1.
                val resumeIndex = translation.chapter.lastPageRead.toInt()
                val orderedStreams = eu.kanade.translation.util.ResumeOrdering
                    .forwardFirstThenBackfill(streams, resumeIndex)
                if (translationJob?.isActive != true) {
                    logcat(LogPriority.INFO) { "TachiyomiAT batch cancelled before start: ${translation.chapter.name}" }
                } else {
                    pipeline.translateBatch(
                        translation.manga,
                        translation.chapter,
                        translation.source,
                        store,
                        orderedStreams,
                    )
                }
            } finally {
                // TachiyomiAT: close the shared archive that was opened once for the
                // entire batch (instead of per-page). The directory path doesn't
                // open one, so sharedArchive is null there.
                try {
                    sharedArchive?.close()
                } catch (_: Exception) {}
            }

            translation.status = Translation.State.TRANSLATED
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
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
                        // getInputStream return null; ImageUtil.isImage itself
                        // handles a null name. Skip unreadable entries instead
                        // of NPE'ing on the `!!` that used to be here.
                        entry.isFile &&
                            ImageUtil.isImage(entry.name) {
                                reader.getInputStream(entry.name)
                                    ?: throw java.io.IOException("Archive entry '${entry.name}' could not be opened")
                            }
                    }
                        .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }.map { entry ->
                            Pair(entry.name) {
                                chapterPath.archiveReader(context).use { archive ->
                                    // Null-safe stream: if the entry vanished or
                                    // the archive is corrupt, throw an explicit,
                                    // loggable IOException instead of an NPE so
                                    // the caller's try/catch reports the real cause.
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
            // return an empty list (the caller treats "no pages" as a clean
            // no-op) instead of NPE'ing.
            val files = chapterPath.listFiles() ?: run {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT getChapterPages: listFiles() returned null for ${chapterPath.filePath}"
                }
                return emptyList()
            }
            return files.mapNotNull { entry ->
                // entry.name is nullable on some SAF providers; skip nameless
                // entries instead of NPE'ing on entry.name!!.
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
    }

    private fun removeFromQueue(translation: Translation) {
        _queueState.update {
            if (translation.status == Translation.State.TRANSLATING || translation.status == Translation.State.QUEUE) {
                translation.status = Translation.State.NOT_TRANSLATED
            }
            it - translation
        }
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
    }
}
