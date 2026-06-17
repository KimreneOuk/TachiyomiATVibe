package eu.kanade.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.inpainting.InpaintingMode
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.rendering.PageTextRenderer
import eu.kanade.translation.rendering.RenderColorEstimator
import eu.kanade.translation.ocr.OcrModelCatalog
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.recognition.MlKitFullPageRecognitionEngine
import eu.kanade.translation.recognition.PageRecognitionEngine
import eu.kanade.translation.recognition.RoiPageRecognitionEngine
import eu.kanade.translation.translator.TextTranslator
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TranslationEngineBuilder
import eu.kanade.translation.util.TranslationMemoryBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.coroutineContext
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.core.archive.archiveReader
import mihon.core.archive.ArchiveReader
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.domain.translation.pools.BitmapPool
import tachiyomi.i18n.at.ATMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean


class ChapterTranslator(
    private val context: Context,
    private val provider: TranslationProvider,
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
) {

    companion object {
        /**
         * Maximum wall-clock time a single page may hold the sole
         * [translatorPermit] during [translateSinglePage]. Bounds the damage of
         * a hung ONNX inference or a stalled AI/HTTP call so it can't starve
         * every other page's translation for the whole session. Generous
         * (on-device OCR + inpaint + render of one page can take tens of seconds
         * on a large image), but finite.
         */
        const val SINGLE_PAGE_TIMEOUT_MS = 120_000L

        private val readerPageStreams = ConcurrentHashMap<String, () -> InputStream>()

        private fun readerPageStreamKey(
            manga: Manga,
            chapter: Chapter,
            source: HttpSource,
            pageKey: String,
        ): String = "${source.id}:${manga.id}:${chapter.id}:$pageKey"

        fun registerReaderPageStream(
            manga: Manga,
            chapter: Chapter,
            source: HttpSource,
            pageKey: String,
            streamFn: () -> InputStream,
        ) {
            readerPageStreams[readerPageStreamKey(manga, chapter, source, pageKey)] = streamFn
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
        ): (() -> InputStream)? = readerPageStreams[readerPageStreamKey(manga, chapter, source, pageKey)]

        /**
         * Evicts every reader page stream registered for [mangaId]/[sourceId] in
         * [chapterId]. Each registered entry holds a `() -> InputStream` closure
         * over a [eu.kanade.tachiyomi.ui.reader.model.ReaderPage], which can keep
         * page bitmaps/sources alive — so this must run on chapter change to
         * avoid leaking memory and stale streams across chapters.
         */
        fun clearReaderPageStreams(sourceId: Long, mangaId: Long, chapterId: Long) {
            val prefix = "$sourceId:$mangaId:$chapterId:"
            val iterator = readerPageStreams.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().key.startsWith(prefix)) {
                    iterator.remove()
                }
            }
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
            readerPageStreams.clear()
        }
    }

    private val _queueState = MutableStateFlow<List<Translation>>(emptyList())
    val queueState = _queueState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var translationJob: Job? = null

    /**
     * TachiyomiAT: serializes access to the shared translation engines
     * (textTranslator + recognitionEngine). Both the single-page path
     * ([translateSinglePage]) and the batch path ([translateChapter]) acquire
     * this permit, so:
     *  - only one page's bitmap/tensor set is alive at a time (fixes the
     *    ~20-page OOM), and
     *  - a language-change rebuild or stop()/close() in one path cannot run
     *    concurrently with an in-flight translate() in the other path (fixes
     *    the "Translator has been closed" IllegalStateException).
     */
    private val translatorPermit = Semaphore(1)

    /**
     * pageKeys currently mid-flight in [translateSinglePage]. Guards against
     * the same page being queued behind itself (e.g. auto-mode re-enqueue on
     * scroll, or a user double-tapping the per-page button). Access is
     * serialized through [translatorPermit], so no extra lock is needed.
     */
    private val inFlightPageKeys = mutableSetOf<String>()

    /**
     * TachiyomiAT: independent scope for the permit watchdog. It uses a
     * [SupervisorJob] on purpose: a child launched here is NOT cancelled when
     * the translation coroutine that owns the permit is cancelled/torn down.
     * That is the whole point — if the worker is stuck inside uncancellable
     * native JNI code (ONNX detect/recognize) or [runBlocking] HTTP code,
     * coroutine cancellation is queued but never delivered, so the standard
     * [withPermit] finally never runs and the singleton [translatorPermit] is
     * leaked forever, deadlocking ALL translation (auto + manual) for the rest
     * of the process. This watchdog fires a real wall-clock deadline that runs
     * independently of the hung coroutine and force-releases the permit.
     */
    private val permitWatchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Listener notified by [withLeakProofPermit] when a page overruns its
     * deadline, after the permit has been force-released. Wired by
     * [TranslationManager] so it can also evict the dead job from
     * `activePageJobs`, otherwise the `existing.isActive` dedup keeps silently
     * dropping every retry of that page forever.
     */
    @Volatile
    var onPageStuck: ((chapterId: Long?, pageKey: String) -> Unit)? = null

    /**
     * TachiyomiAT: leak-proof equivalent of `translatorPermit.withPermit { }`.
     *
     * Guarantees [permit] is released within [timeoutMs] of acquisition even
     * when [block] enters uncancellable native/HTTP code that the coroutine
     * machinery can't interrupt. Achieves this with an independent watchdog
     * coroutine (on [permitWatchdogScope], so it survives cancellation of the
     * calling coroutine) that fires the deadline on a wall-clock [delay] and
     * force-releases. Release is guarded by an [AtomicBoolean] so it happens
     * exactly once whether the watchdog or the normal finally wins the race.
     *
     * On timeout, [onTimeout] runs (on the watchdog dispatcher) before
     * release so the page is marked FAILED and the dead job is evicted; the
     * still-hung native coroutine is abandoned to finish (or not) on its own —
     * it no longer holds the permit, so other pages can proceed.
     */
    private suspend fun <T> withLeakProofPermit(
        permit: Semaphore,
        timeoutMs: Long,
        chapterId: Long?,
        pageKey: String,
        onTimeout: suspend () -> Unit,
        block: suspend () -> T,
    ): T {
        permit.acquire()
        val released = AtomicBoolean(false)
        fun releaseOnce() {
            if (released.compareAndSet(false, true)) {
                permit.release()
            }
        }
        val watchdog = permitWatchdogScope.launch {
            delay(timeoutMs)
            // Deadline fired while block was still holding the permit — the
            // worker is almost certainly stuck in uncancellable code. Run the
            // timeout callback, then force-release so the rest of the app
            // isn't deadlocked behind this one page.
            try {
                onTimeout()
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) {
                    "TachiyomiAT permit-watchdog onTimeout threw: pageKey=$pageKey"
                }
            }
            logcat(LogPriority.ERROR) {
                "TachiyomiAT permit-watchdog FORCE-RELEASED after ${timeoutMs}ms " +
                    "(worker stuck in uncancellable code): pageKey=$pageKey chapterId=$chapterId"
            }
            onPageStuck?.invoke(chapterId, pageKey)
            releaseOnce()
        }
        return try {
            block()
        } finally {
            watchdog.cancel()
            releaseOnce()
        }
    }


    val isRunning: Boolean
        get() = translationJob?.isActive == true

    @Volatile
    var isPaused: Boolean = false

    private var currentFromLang: TextRecognizerLanguage
    private var currentOcrModel: OcrModel
    // TachiyomiAT: these engine references are reassigned from a translation
    // coroutine (on language change) and read/closed from closeEngines() which
    // runs WITHOUT the permit (called from stop() on the main thread). Mark them
    // @Volatile so a stop()/language-change race reads a consistent reference
    // instead of a stale value — closing the wrong client or seeing a half-
    // published field. See closeEngines() for the full lifecycle note.
    @Volatile
    private var textTranslator: TextTranslator
    @Volatile
    private var recognitionEngine: PageRecognitionEngine
    private var currentInpaintingMode: InpaintingMode

    init {
        val fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
        val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
        val ocrModel = OcrModelCatalog.selectedModel(translationPreferences, fromLang)
        currentFromLang = fromLang
        currentOcrModel = ocrModel
        currentInpaintingMode = inpaintingModeFromPref()
        recognitionEngine = createRecognitionEngine(fromLang, ocrModel, currentInpaintingMode)
        textTranslator = TranslationEngineBuilder.build(translationPreferences, fromLang, toLang)
    }

    private fun inpaintingModeFromPref(): InpaintingMode {
        return when (translationPreferences.translationInpaintingMode().get()) {
            "FAST" -> InpaintingMode.FAST
            else -> InpaintingMode.QUALITY
        }
    }

    private fun createRecognitionEngine(
        lang: TextRecognizerLanguage,
        ocrModel: OcrModel,
        mode: InpaintingMode,
    ): PageRecognitionEngine {
        // TachiyomiAT: when a prior batch run hit repeated OOMs and flipped
        // autoFallbackToFast, skip ONNX entirely for this batch/page so we
        // don't keep re-OOM'ing after the threshold was already crossed.
        // Previously this flag was written but never read (dead state), so
        // pages kept retrying ONNX and each OOM filled the heap further.
        if (autoFallbackToFast) {
            logcat(LogPriority.WARN) { "autoFallbackToFast is set — skipping ONNX for $lang/$ocrModel, using ML Kit directly" }
            return MlKitFullPageRecognitionEngine(lang)
        }
        val onnx = RoiPageRecognitionEngine(context, lang, ocrModel, mode)
        if (onnx.isAvailable) {
            logcat(LogPriority.INFO) { "Using ONNX recognition engine for $lang with OCR model $ocrModel" }
            return onnx
        }
        onnx.close()
        logcat(LogPriority.INFO) { "ONNX unavailable, falling back to ML Kit recognition engine for $lang/$ocrModel" }
        return MlKitFullPageRecognitionEngine(lang)
    }

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

    fun stop(reason: String? = null) {
        cancelTranslatorJob()
        queueState.value.filter { it.status == Translation.State.TRANSLATING }
            .forEach { it.status = Translation.State.ERROR }
        if (reason != null) return
        isPaused = false
        closeEngines()
    }

    fun onMemoryPressure(level: Int) {
        tachiyomi.domain.translation.pools.BitmapPool.releaseAll()
        when {
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                stop("memory pressure")
                closeEngines()
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

    private fun closeEngines() {
        if (!translatorPermit.tryAcquire()) {
            enginesClosed = true
            return
        }
        try {
            enginesClosed = true
            try { recognitionEngine.close() } catch (_: Exception) {}
            try { textTranslator.close() } catch (_: Exception) {}
        } finally {
            translatorPermit.release()
        }
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
        val translation = Translation(source, manga, chapter, fromLang, toLang);
        addToQueue(translation);
    }

    private var consecutiveOomCount = 0
    private var autoFallbackToFast = false
    private var currentChapterTranslation: Translation? = null
    private var currentChapterPath: UniFile? = null
    @Volatile
    private var enginesClosed = false

    /**
     * Listener that lets the translator share the same [ChapterTranslationStore]
     * with the [TranslationManager] (and the reader observing it). If no
     * listener is provided, the translator falls back to opening a local store.
     */
    @Volatile
    var activeStoreResolver: ((Translation) -> ChapterTranslationStore?)? = null

    @Volatile
    var activeStoreUnregister: ((Translation) -> Unit)? = null

    private fun registerActiveStore(translation: Translation, store: ChapterTranslationStore) {
        currentChapterTranslation = translation
    }

    private fun unregisterActiveStore(translation: Translation) {
        // Clear only the translator-local pointer. We intentionally do NOT call
        // activeStoreUnregister here: that callback removes the shared
        // ChapterTranslationStore from TranslationManager's cache, but the reader
        // captured this store's StateFlow once at observe time and never
        // re-resolves it. Evicting it after every single-page translation meant
        // the next translate created a fresh store the reader never saw — so
        // only the first page after opening the reader ever updated live.
        // The shared store is now managed by TranslationManager's own lifecycle
        // (chapter change / reader exit), not per-translation.
        if (currentChapterTranslation?.chapter?.id == translation.chapter.id) {
            currentChapterTranslation = null
            currentChapterPath = null
        }
    }

    private fun createFailedPagePlaceholder(
        fileName: String,
        errorMessage: String?,
        imgWidth: Float = 0f,
        imgHeight: Float = 0f,
        originalImgWidth: Float = 0f,
        originalImgHeight: Float = 0f,
        decodeSampleSize: Int = 1,
        retryCount: Int = 0,
    ): PageTranslation {
        return PageTranslation(
            sourceFileName = fileName,
            imgWidth = imgWidth,
            imgHeight = imgHeight,
            originalImgWidth = originalImgWidth,
            originalImgHeight = originalImgHeight,
            decodeSampleSize = decodeSampleSize,
            ocrStatus = StageStatus.FAILED,
            translationStatus = StageStatus.PENDING,
            inpaintStatus = StageStatus.FAILED,
            renderStatus = StageStatus.PENDING,
            errorMessage = errorMessage,
            updatedAt = System.currentTimeMillis(),
            retryCount = retryCount,
        )
    }

    /**
     * Writes a FAILED placeholder for [pageKey] into the chapter's shared store
     * when the single-page translation times out. This is the single-page path's
     * counterpart to the batch path's timeout handling: without it the page is
     * stranded as ocrStatus=RUNNING (the first thing [translateSinglePageInternal]
     * writes) for the rest of the session, which keeps the reader's
     * anyRunning flag true — pinning the TRANSLATING state, disabling the
     * translate icon, and making auto-translate skip the page forever.
     *
     * Resolves the store through [activeStoreResolver] (the shared instance the
     * reader is observing); if none is registered for this chapter the page's
     * RUNNING status will be cleared on the next chapter open instead. Never
     * clobbers an already-completed page — only overwrites entries that are still
     * in a non-terminal (RUNNING/PENDING) state.
     */
    private suspend fun markPageTimedOut(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
    ) {
        val fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
        val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
        val syntheticTranslation = Translation(source, manga, chapter, fromLang, toLang)
        val store = activeStoreResolver?.invoke(syntheticTranslation) ?: return
        store.updatePage(pageKey) { existing ->
            // Don't overwrite a page that already produced a result (rendered/
            // cleaned) — a late timeout after a successful persist would erase it.
            if (existing != null &&
                (existing.renderedImageName != null || existing.cleanedImageName != null)
            ) {
                existing
            } else {
                createFailedPagePlaceholder(
                    pageKey,
                    "Translation timed out after ${SINGLE_PAGE_TIMEOUT_MS / 1000}s",
                    imgWidth = existing?.imgWidth ?: 0f,
                    imgHeight = existing?.imgHeight ?: 0f,
                    originalImgWidth = existing?.originalImgWidth ?: 0f,
                    originalImgHeight = existing?.originalImgHeight ?: 0f,
                    decodeSampleSize = existing?.decodeSampleSize ?: 1,
                    retryCount = (existing?.retryCount ?: 0) + 1,
                )
            }
        }
    }

    private suspend fun translateChapter(translation: Translation) = translatorPermit.withPermit {
        translateChapterInternal(translation)
    }

    private suspend fun translateChapterInternal(translation: Translation) {
        var store: ChapterTranslationStore? = null
        try {
            val selectedOcrModel = OcrModelCatalog.selectedModel(translationPreferences, translation.fromLang)
            if (enginesClosed || translation.fromLang != currentFromLang || selectedOcrModel != currentOcrModel) {
                recognitionEngine.close()
                currentFromLang = translation.fromLang
                currentOcrModel = selectedOcrModel
                currentInpaintingMode = inpaintingModeFromPref()
                recognitionEngine = createRecognitionEngine(translation.fromLang, currentOcrModel, currentInpaintingMode)
                enginesClosed = false
            }
            val newMode = inpaintingModeFromPref()
            if (newMode != currentInpaintingMode) {
                recognitionEngine.close()
                currentInpaintingMode = newMode
                recognitionEngine = createRecognitionEngine(currentFromLang, currentOcrModel, currentInpaintingMode)
            }
            if (enginesClosed || translation.fromLang != textTranslator.fromLang || translation.toLang != textTranslator.toLang) {
                withContext(Dispatchers.IO) {
                    textTranslator.close()
                }
                textTranslator = TranslationEngineBuilder.build(
                    translationPreferences, translation.fromLang, translation.toLang,
                )
                enginesClosed = false
            }

            consecutiveOomCount = 0
            autoFallbackToFast = false

            // Prefer the shared active store from TranslationManager so the
            // reader observes the same instance that the translator writes to.
            store = activeStoreResolver?.invoke(translation)
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
            registerActiveStore(translation, store)

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
            currentChapterPath = chapterPath

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
                                    ?: throw java.io.IOException("Archive entry '${entry.name}' could not be opened (mmap)")
                            }
                        }.toList()
                }
            } else {
                sharedArchive = null
                streams = getChapterPages(chapterPath)
            }
            var companionDir: UniFile? = null
            val renderer = PageTextRenderer(context)

            var pageIndex = 0
            while (pageIndex < streams.size) {
                val (fileName, streamFn) = streams[pageIndex]
                pageIndex++
                if (translationJob?.isActive != true) break
                // Emit a running placeholder as early as possible so the
                // reader can show a dimmed-image + spinner overlay on the
                // currently processed page in real time.
                // TachiyomiAT: clear stale result names (see the single-page
                // path at ~line 1119 for the full rationale). Without this, a
                // chapter re-translate leaves each page's old renderedImageName
                // set while ocrStatus=RUNNING, so isPageBeingTranslated() stays
                // false and the reader never shows the overlay for in-flight
                // pages that had a prior result.
                store.updatePage(fileName) {
                    (it ?: PageTranslation()).apply {
                        sourceFileName = fileName
                        ocrStatus = StageStatus.RUNNING
                        renderedImageName = null
                        cleanedImageName = null
                        errorMessage = null
                        updatedAt = System.currentTimeMillis()
                    }
                }
                val decoded = try {
                    decodePageBitmap(fileName, streamFn)
                } catch (oom: OutOfMemoryError) {
                    BitmapPool.releaseAll()
                    System.gc()
                    consecutiveOomCount++
                    logcat(LogPriority.ERROR, oom) { "Out of memory decoding $fileName" }
                    store.updatePage(fileName) { prev ->
                        createFailedPagePlaceholder(
                            fileName,
                            "OutOfMemory decoding page: ${oom.message}",
                            imgWidth = prev?.imgWidth ?: 0f,
                            imgHeight = prev?.imgHeight ?: 0f,
                            originalImgWidth = prev?.originalImgWidth ?: 0f,
                            originalImgHeight = prev?.originalImgHeight ?: 0f,
                            decodeSampleSize = prev?.decodeSampleSize ?: 1,
                            retryCount = (prev?.retryCount ?: 0) + 1,
                        )
                    }
                    BitmapPool.releaseAll()
                    continue
                }
                if (decoded == null) {
                    logcat(LogPriority.WARN) { "Failed to decode $fileName (null bitmap, bounds invalid)" }
                    store.updatePage(fileName) { prev ->
                        createFailedPagePlaceholder(
                            fileName,
                            "Failed to decode page: null bitmap or invalid bounds",
                            imgWidth = prev?.imgWidth ?: 0f,
                            imgHeight = prev?.imgHeight ?: 0f,
                            originalImgWidth = prev?.originalImgWidth ?: 0f,
                            originalImgHeight = prev?.originalImgHeight ?: 0f,
                            decodeSampleSize = prev?.decodeSampleSize ?: 1,
                            retryCount = (prev?.retryCount ?: 0) + 1,
                        )
                    }
                    continue
                }
                val bitmap = decoded.bitmap
                store.updatePage(fileName) {
                    (it ?: PageTranslation()).apply {
                        sourceFileName = fileName
                        ocrStatus = StageStatus.RUNNING
                        updatedAt = System.currentTimeMillis()
                    }
                }

                // TachiyomiAT: the single-page path caps each page at
                // SINGLE_PAGE_TIMEOUT_MS (currently 120s). The batch path had
                // NO timeout before — a hung ONNX inference or stalled AI/HTTP
                // request held the single translatorPermit indefinitely. Wrap
                // the expensive recognition+render chain so a page that takes
                // too long releases the permit and the next page can proceed.
                val timedOut = withTimeoutOrNull(SINGLE_PAGE_TIMEOUT_MS) {
                    val pageTranslation: PageTranslation
                    try {
                        pageTranslation = processSinglePage(
                            fileName, bitmap, decoded, translation, store, streams,
                        ) {
                            val dir = provider.getCompanionImageDir(
                                translation.manga.title, translation.source,
                                translation.chapter.name, translation.chapter.scanlator,
                            )
                            companionDir = dir
                            dir
                        }
                    } finally {
                        try {
                            bitmap.recycle()
                        } catch (_: Exception) {}
                        companionDir = provider.getCompanionImageDir(
                            translation.manga.title, translation.source,
                            translation.chapter.name, translation.chapter.scanlator,
                        )
                        BitmapPool.releaseAll()
                    }

                    // TachiyomiAT: cooperative cancellation checkpoint between
                    // recognize/inpaint and text-translation in the batch path.
                    // The loop's isActive check at the top of each iteration
                    // covers page boundaries, but a single page's stages
                    // (recognize -> translate -> render) had no checkpoint between
                    // them — a chapter-switch cancel during an LLM HTTP call on
                    // page N would still finish rendering/persisting page N before
                    // the next iteration noticed. Drop out here if cancelled.
                    coroutineContext.ensureActive()

                    if (pageTranslation.blocks.isNotEmpty()) {
                        try {
                            pageTranslation.translationStatus = StageStatus.RUNNING
                            textTranslator.translatePage(fileName, pageTranslation)
                            pageTranslation.translationStatus = StageStatus.READY
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            pageTranslation.translationStatus = StageStatus.FAILED
                            pageTranslation.errorMessage = e.message
                            logcat(LogPriority.ERROR, e) { "Failed to translate text for $fileName" }
                        }
                    }

                    if (pageTranslation.blocks.isNotEmpty() && pageTranslation.translationStatus == StageStatus.READY) {
                        // TachiyomiAT: retry-then-block for inpaint (batch path).
                        // When the first recognize() produced no cleaned bitmap,
                        // retry inpaint once on a half-sampled decode (reduces
                        // heap — the usual inpaint-failure cause). Previously
                        // this fell back to `cleanedBitmap ?: bitmap`, but `bitmap`
                        // was already recycled by the finally above (line ~722),
                        // so renderer.render() on the recycled bitmap threw
                        // IllegalStateException and the page showed the original
                        // untranslated image with an error. And even when it
                        // didn't crash (early builds), overlaying text on the
                        // original left the source Japanese visible — the exact
                        // "text on non-inpainted image" bug reported. Now: retry,
                        // and if the retry also fails, REFUSE to render rather
                        // than overlay on the original.
                        var renderTarget: Bitmap? = pageTranslation.cleanedBitmap
                        var retryTargetOwned = false
                        if (renderTarget == null) {
                            retryInpaintDownscaled(
                                translation.manga, translation.chapter, translation.source,
                                fileName, streams, decoded, pageTranslation,
                            )
                            renderTarget = pageTranslation.cleanedBitmap
                            retryTargetOwned = renderTarget != null
                        }
                        if (renderTarget != null) {
                            try {
                                pageTranslation.renderStatus = StageStatus.RUNNING
                                // TachiyomiAT: re-derive block text/stroke colors
                                // AGAINST THE CLEANED BITMAP right before render. The
                                // recognition-time estimate (in computeRenderColors)
                                // sampled the ORIGINAL image, but inpainting may have
                                // replaced the box background with a different median
                                // color (e.g. mid-gray on a grayscale panel). Without
                                // this recompute, a dark inpaint could get dark text
                                // (illegible) and vice versa. Cheap (~1ms/block) and
                                // gated on the cleaned bitmap being non-null.
                                RenderColorEstimator.recomputeFor(renderTarget, pageTranslation.blocks)
                                // TachiyomiAT: render() returns the bitmap it actually
                                // drew on — it may be a mutable COPY of renderTarget
                                // if renderTarget was immutable. Compress the returned
                                // bitmap, not renderTarget, or the rendered text is lost.
                                val renderedBitmap = renderer.render(renderTarget, pageTranslation.blocks)

                                val cDir = companionDir ?: provider.getCompanionImageDir(
                                    translation.manga.title, translation.source,
                                    translation.chapter.name, translation.chapter.scanlator,
                                ).also { companionDir = it }
                                persistRenderedBitmap(
                                    pageTranslation = pageTranslation,
                                    renderedBitmap = renderedBitmap,
                                    companionDir = cDir,
                                    pageKey = fileName,
                                    successMessageSuffix = if (retryTargetOwned) " (retry path)" else "",
                                    successErrorMessage = if (retryTargetOwned) {
                                        "Inpainted on retry (downscaled decode)"
                                    } else {
                                        null
                                    },
                                )
                                // renderedBitmap may be a distinct copy of
                                // renderTarget (when renderTarget was immutable);
                                // recycle the copy so it doesn't leak.
                                if (renderedBitmap !== renderTarget) {
                                    try { renderedBitmap.recycle() } catch (_: Exception) {}
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                pageTranslation.renderStatus = StageStatus.FAILED
                                pageTranslation.retryCount++
                                pageTranslation.errorMessage = e.message
                                logcat(LogPriority.ERROR, e) { "Failed to render text for $fileName" }
                            } finally {
                                try { renderTarget.recycle() } catch (_: Exception) {}
                            }
                        } else {
                            // Retry also produced no cleaned bitmap. Block the
                            // render: do NOT overlay text on the original (which
                            // is recycled anyway and would crash). Surface an
                            // honest, retryable error.
                            pageTranslation.renderStatus = StageStatus.FAILED
                            pageTranslation.retryCount++
                            val reason = pageTranslation.errorMessage ?: "inpaint unavailable"
                            pageTranslation.errorMessage =
                                "Inpainting unavailable ($reason) — original text would show through, so the " +
                                    "translated text was not rendered. Retry, or switch recognition mode."
                            logcat(LogPriority.WARN) {
                                "TachiyomiAT batch render BLOCKED for $fileName: inpaint unavailable after retry " +
                                    "(reason=$reason). Page left on original image with error instead of a half-translated overlay."
                            }
                        }
                    } else {
                        // No rendering needed (no blocks or translation not ready),
                        // still recycle cleanedBitmap so it doesn't leak.
                        pageTranslation.cleanedBitmap?.let {
                            try { it.recycle() } catch (_: Exception) {}
                        }
                    }
                    pageTranslation.cleanedBitmap = null
                    pageTranslation.updatedAt = System.currentTimeMillis()

                    persistPageWithOomRecovery(store, fileName, pageTranslation)

                    BitmapPool.releaseAll()
                }

                if (timedOut == null) {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT batch page timed out after ${SINGLE_PAGE_TIMEOUT_MS}ms: $fileName"
                    }
                    val placeholder = createFailedPagePlaceholder(
                        fileName,
                        "Translation timed out after ${SINGLE_PAGE_TIMEOUT_MS / 1000}s",
                    )
                    store.updatePage(fileName) { placeholder }
                    // continue so the permit is released and the next page gets a turn
                    continue
                }
            }

            // TachiyomiAT: close the shared archive that was opened once for the
            // entire batch (instead of per-page). The directory path doesn't
            // open one, so sharedArchive is null there.
            try { sharedArchive?.close() } catch (_: Exception) {}

            if (consecutiveOomCount > 0) {
                autoFallbackToFast = true
            }

            translation.status = Translation.State.TRANSLATED

        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            BitmapPool.releaseAll()
            translation.status = Translation.State.ERROR
            logcat(LogPriority.ERROR, error)
        } finally {
            unregisterActiveStore(translation)
        }
    }

    /**
     * Translates a single page identified by [pageKey] within [chapter] of [manga].
     *
     * This method opens the translation store via [activeStoreResolver] (or falls
     * back to a local store), decodes the corresponding page file from disk, runs
     * OCR + inpainting + text translation + render, writes the rendered image and
     * persists the result to the store so the reader sees live updates.
     */
    suspend fun translateSinglePage(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
    ) = withLeakProofPermit(
        permit = translatorPermit,
        timeoutMs = SINGLE_PAGE_TIMEOUT_MS,
        chapterId = chapter.id,
        pageKey = pageKey,
        // Force-release safety net: runs on the watchdog dispatcher only when
        // the worker is truly stuck in uncancellable native/HTTP code and the
        // cooperative withTimeoutOrNull below could NOT fire. Marks the page
        // FAILED so the reader overlay clears and the page is retryable.
        onTimeout = { markPageTimedOut(manga, chapter, source, pageKey) },
    ) {
        // Dedup: if this exact page is already being translated (e.g. an
        // auto-mode re-enqueue on scroll), drop the redundant request instead
        // of queuing it behind itself. Safe without an extra lock because the
        // permit serializes entry.
        if (!inFlightPageKeys.add(pageKey)) {
            return@withLeakProofPermit
        }
        try {
            // Cooperative timeout: bound how long a single page holds the
            // permit when its native calls actually RETURN (just slowly). On a
            // cooperative timeout the permit is released by the watchdog-free
            // finally below. This does NOT cover the case where the native call
            // never returns at all — that is what the outer watchdog is for.
            withTimeoutOrNull(SINGLE_PAGE_TIMEOUT_MS) {
                translateSinglePageInternal(manga, chapter, source, pageKey)
            } ?: run {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT single-page translation timed out after ${SINGLE_PAGE_TIMEOUT_MS}ms: " +
                        "pageKey=$pageKey chapter=${chapter.name}"
                }
                // Reset the stranded RUNNING status on cooperative timeout:
                // translateSinglePageInternal writes ocrStatus=RUNNING as its
                // very first store update. On timeout the coroutine is torn
                // down but the store entry is left RUNNING forever — so the
                // reader's observer keeps anyRunning=true (TRANSLATING never
                // clears), the translate icon stays disabled, and auto-translate
                // never retries the page. Mirror the batch path's FAILED
                // placeholder so the overlay clears, the icon re-enables, and
                // the page is eligible for retry.
                markPageTimedOut(manga, chapter, source, pageKey)
            }
        } finally {
            inFlightPageKeys.remove(pageKey)
        }
    }

    suspend fun translateSinglePageFromStream(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        streamFn: () -> InputStream,
    ) = withLeakProofPermit(
        permit = translatorPermit,
        timeoutMs = SINGLE_PAGE_TIMEOUT_MS,
        chapterId = chapter.id,
        pageKey = pageKey,
        onTimeout = { markPageTimedOut(manga, chapter, source, pageKey) },
    ) {
        if (!inFlightPageKeys.add(pageKey)) return@withLeakProofPermit
        try {
            withTimeoutOrNull(SINGLE_PAGE_TIMEOUT_MS) {
                translateSinglePageInternal(manga, chapter, source, pageKey, streamFn)
            } ?: run {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT single-page (stream) translation timed out after ${SINGLE_PAGE_TIMEOUT_MS}ms: " +
                        "pageKey=$pageKey chapter=${chapter.name}"
                }
                markPageTimedOut(manga, chapter, source, pageKey)
            }
        } finally {
            inFlightPageKeys.remove(pageKey)
        }
    }

    private suspend fun translateSinglePageInternal(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        readerStreamFn: (() -> InputStream)? = null,
    ) {
        val streamFromReader = readerStreamFn ?: peekReaderPageStream(manga, chapter, source, pageKey)
        val fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
        val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
        val selectedOcrModel = OcrModelCatalog.selectedModel(translationPreferences, fromLang)
        val syntheticTranslation = Translation(source, manga, chapter, fromLang, toLang)
        syntheticTranslation.status = Translation.State.TRANSLATING

        // Ensure the translation engine matches the current preferences.
        if (enginesClosed || fromLang != currentFromLang || selectedOcrModel != currentOcrModel) {
            recognitionEngine.close()
            currentFromLang = fromLang
            currentOcrModel = selectedOcrModel
            currentInpaintingMode = inpaintingModeFromPref()
            recognitionEngine = createRecognitionEngine(fromLang, currentOcrModel, currentInpaintingMode)
            enginesClosed = false
        }
        if (enginesClosed || fromLang != textTranslator.fromLang || toLang != textTranslator.toLang) {
            withContext(Dispatchers.IO) { textTranslator.close() }
            textTranslator = TranslationEngineBuilder.build(translationPreferences, fromLang, toLang)
            enginesClosed = false
        }

        // TachiyomiAT: recover from the OOM-driven ML Kit fallback. When the
        // ONNX engine hit memory pressure on a prior chapter, downgradeOnnxAfterOom
        // set autoFallbackToFast=true and swapped in MlKitFullPageRecognitionEngine.
        // MlKitFullPageRecognitionEngine.inpaint() ALWAYS returns FAILED (it has no
        // inpainter), so every page on every SUBSEQUENT chapter failed at inpaint,
        // the render step was skipped, and the page showed the ORIGINAL image with a
        // red ERROR button — even though OCR and text translation succeeded.
        // The batch path resets autoFallbackToFast at the start of each chapter, but
        // the single-page path (used by auto-translate and the per-page button) did
        // not, so the fallback was permanent for the rest of the session. Reset it
        // here when the heap has recovered, and rebuild the ONNX engine so inpainting
        // works again. If the heap is still tight, TranslationMemoryBudget will catch
        // the next OOM and downgrade once more — so this self-corrects.
        if (autoFallbackToFast && TranslationMemoryBudget.hasHeadroomForPrefetch()) {
            logcat(LogPriority.INFO) {
                "TachiyomiAT single-page: heap recovered — restoring ONNX engine (autoFallbackToFast was true)"
            }
            autoFallbackToFast = false
            consecutiveOomCount = 0
            recognitionEngine.close()
            recognitionEngine = createRecognitionEngine(fromLang, currentOcrModel, currentInpaintingMode)
        }

        // Use the shared active store so the reader observes our writes live.
        var ownStore: ChapterTranslationStore? = null
        val store = activeStoreResolver?.invoke(syntheticTranslation).also {
            ownStore = if (it == null) null else syntheticTranslation.let { _ -> it }
        } ?: run {
            val mangaDir = provider.getMangaDir(manga.title, source)
            val saveFile = provider.getTranslationFileName(chapter.name, chapter.scanlator)
            val file = mangaDir?.createFile(saveFile) ?: return
            ChapterTranslationStore.open(file).also { ownStore = it }
        }

        try {
            registerActiveStore(syntheticTranslation, store)

            val streams = if (streamFromReader != null) {
                logcat(LogPriority.INFO) {
                    "TachiyomiAT single-page translation using reader stream: pageKey=$pageKey " +
                        "chapter=${chapter.name} manga=${manga.title} source=${source.id}"
                }
                listOf(pageKey to streamFromReader)
            } else {
                val chapterPath = downloadProvider.findChapterDir(
                    chapter.name, chapter.scanlator, manga.title, source,
                ) ?: run {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT single-page translation cannot start: chapter files not found " +
                            "pageKey=$pageKey chapter=${chapter.name} manga=${manga.title} source=${source.id}"
                    }
                    store.updatePage(pageKey) {
                        (it ?: PageTranslation()).apply {
                            sourceFileName = pageKey
                            ocrStatus = StageStatus.FAILED
                            errorMessage = "Chapter files not found on device"
                            updatedAt = System.currentTimeMillis()
                        }
                    }
                    return
                }
                currentChapterPath = chapterPath
                getChapterPages(chapterPath)
            }
            val entry = streams.find { it.first == pageKey } ?: run {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT single-page translation cannot find requested page: pageKey=$pageKey " +
                        "available=${streams.map { it.first }.take(5)} total=${streams.size}"
                }
                store.updatePage(pageKey) {
                    (it ?: PageTranslation()).apply {
                        sourceFileName = pageKey
                        ocrStatus = StageStatus.FAILED
                        errorMessage = "Page $pageKey not found in chapter files"
                        retryCount = (it?.retryCount ?: 0) + 1
                        updatedAt = System.currentTimeMillis()
                    }
                }
                return
            }

            // Emit RUNNING early so the reader shows the overlay for this page.
            // TachiyomiAT: clear any STALE result names from a prior successful
            // translation of this page. The reader's isPageBeingTranslated()
            // predicate requires renderedImageName == null && cleanedImageName ==
            // null to treat a page as "in progress" (so it shows the processing
            // overlay + the cancel affordance instead of the idle translate
            // button). On a RE-translation (user taps translate again on an
            // already-translated page), ocrStatus flips to RUNNING here but the
            // old renderedImageName was still set — so the predicate's `&&`
            // short-circuited to false, the overlay never appeared, and the
            // re-translate ran invisibly ("click again, nothing happens").
            // Clearing the names here makes the predicate report correctly; they
            // are rewritten downstream (lines ~1227/1306 for rendered, ~1600 for
            // cleaned) when the new translation produces its result. This only
            // mutates the in-memory/ persisted store STATE, not the image files
            // themselves, so no translated images are lost.
            store.updatePage(pageKey) {
                (it ?: PageTranslation()).apply {
                    sourceFileName = pageKey
                    ocrStatus = StageStatus.RUNNING
                    renderedImageName = null
                    cleanedImageName = null
                    errorMessage = null
                    updatedAt = System.currentTimeMillis()
                }
            }

            val decoded = decodePageBitmap(pageKey, entry.second)
            if (decoded == null) {
                store.updatePage(pageKey) {
                    (it ?: PageTranslation()).apply {
                        sourceFileName = pageKey
                        ocrStatus = StageStatus.FAILED
                        errorMessage = "Failed to decode page: null bitmap"
                        retryCount = (it?.retryCount ?: 0) + 1
                        updatedAt = System.currentTimeMillis()
                    }
                }
                return
            }

            val bitmap = decoded.bitmap
            store.updatePage(pageKey) {
                (it ?: PageTranslation()).apply {
                    sourceFileName = pageKey
                    ocrStatus = StageStatus.RUNNING
                    updatedAt = System.currentTimeMillis()
                }
            }

            val pageTranslation: PageTranslation
            try {
                pageTranslation = processSinglePage(
                    pageKey, bitmap, decoded, syntheticTranslation, store, streams,
                ) {
                    provider.getCompanionImageDir(
                        manga.title, source,
                        chapter.name, chapter.scanlator,
                    )
                }
            } finally {
                try { bitmap.recycle() } catch (_: Exception) {}
                BitmapPool.releaseAll()
            }

            // TachiyomiAT: cooperative cancellation checkpoint between the
            // expensive recognize/inpaint stage and the text-translation stage.
            // If the reader switched chapters (or exited) during recognition,
            // drop out here instead of making an LLM/HTTP call whose result
            // nothing will consume. The stranded-status reset handles the store.
            coroutineContext.ensureActive()

            if (pageTranslation.blocks.isNotEmpty()) {
                try {
                    pageTranslation.translationStatus = StageStatus.RUNNING
                    textTranslator.translatePage(pageKey, pageTranslation)
                    pageTranslation.translationStatus = StageStatus.READY
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    pageTranslation.translationStatus = StageStatus.FAILED
                    pageTranslation.errorMessage = e.message
                    logcat(LogPriority.ERROR, e) { "Failed to translate text for single page $pageKey" }
                }
            }

            // TachiyomiAT: cooperative cancellation checkpoint before the render
            // stage. textTranslator.translatePage() may make a network call; if
            // the job was cancelled while we were waiting on it, don't proceed
            // to render/compress/persist for a page the reader has left behind.
            coroutineContext.ensureActive()

            if (pageTranslation.blocks.isNotEmpty() && pageTranslation.translationStatus == StageStatus.READY) {
                // TachiyomiAT: decouple render from inpaint. Previously render
                // REQUIRED pageTranslation.cleanedBitmap (the inpainted, text-erased
                // bitmap); when inpaint failed or was unavailable (ML Kit mode has
                // no inpainter), cleanedBitmap was null, render was skipped, and the
                // page showed the ORIGINAL untranslated image with a red ERROR.
                // Now: if we have translated text blocks, render onto the cleaned
                // bitmap when available, otherwise onto a copy of the original
                // decoded bitmap (translated text overlays the source image — less
                // pretty but always produces a result). The copy is made lazily and
                // only for the fallback path to avoid the extra allocation when
                // inpaint succeeded.
                val hasCleaned = pageTranslation.cleanedBitmap != null
                if (hasCleaned) {
                    val cleanedBitmap = pageTranslation.cleanedBitmap!!
                    try {
                        pageTranslation.renderStatus = StageStatus.RUNNING
                        val renderer = PageTextRenderer(context)
                        // TachiyomiAT: re-derive text/stroke colors against the
                        // CLEANED bitmap before render (see batch path for why).
                        RenderColorEstimator.recomputeFor(cleanedBitmap, pageTranslation.blocks)
                        // TachiyomiAT: render() returns the bitmap it actually
                        // drew on — it may be a mutable COPY of cleanedBitmap if
                        // the cleaned bitmap was immutable. Compress the returned
                        // bitmap, not the input, or the rendered text is lost.
                        val renderedBitmap = renderer.render(cleanedBitmap, pageTranslation.blocks)
                        try {
                            val companionDir = provider.getCompanionImageDir(
                                manga.title, source,
                                chapter.name, chapter.scanlator,
                            )
                            persistRenderedBitmap(pageTranslation, renderedBitmap, companionDir, pageKey)
                        } finally {
                            // renderedBitmap may be a distinct copy of
                            // cleanedBitmap (when the cleaned bitmap was
                            // immutable); recycle it independently of the input.
                            if (renderedBitmap !== cleanedBitmap) {
                                try { renderedBitmap.recycle() } catch (_: Exception) {}
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        pageTranslation.renderStatus = StageStatus.FAILED
                        pageTranslation.retryCount++
                        pageTranslation.errorMessage = e.message
                        logcat(LogPriority.ERROR, e) { "Failed to render text for single page $pageKey" }
                    } finally {
                        try { cleanedBitmap.recycle() } catch (_: Exception) {}
                    }
                } else {
                    // TachiyomiAT: retry-then-block. The first recognize() produced
                    // no cleaned bitmap (inpaint failed/unavailable — most often
                    // heap pressure, or ML Kit mode which has no inpainter at all).
                    // Previously this fell back to renderOverOriginalSinglePage(),
                    // which overlays the translated text on the ORIGINAL image —
                    // leaving the source Japanese visible underneath and producing
                    // a deceptive "translated" result. The user reported seeing
                    // exactly this (text on a non-inpainted image).
                    //
                    // Now: retry inpaint once on a half-sampled decode (reduces
                    // heap, the usual failure cause). If the retry yields a cleaned
                    // bitmap, render on it (the success path above, duplicated here
                    // since we already took the else-branch). If the retry also
                    // fails, REFUSE to render — mark renderStatus=FAILED with a
                    // clear message so the page shows the original image plus an
                    // honest error/retry affordance, never a half-translated page.
                    retryInpaintDownscaled(
                        manga, chapter, source, pageKey, streams, decoded, pageTranslation,
                    )
                    val retriedCleaned = pageTranslation.cleanedBitmap
                    if (retriedCleaned != null) {
                        try {
                            pageTranslation.renderStatus = StageStatus.RUNNING
                            val renderer = PageTextRenderer(context)
                            // TachiyomiAT: re-derive colors against the retried
                            // cleaned bitmap too (the retry may have produced a
                            // different background than the first attempt).
                            RenderColorEstimator.recomputeFor(retriedCleaned, pageTranslation.blocks)
                            val renderedBitmap = renderer.render(retriedCleaned, pageTranslation.blocks)
                            try {
                                val companionDir = provider.getCompanionImageDir(
                                    manga.title, source, chapter.name, chapter.scanlator,
                                )
                                persistRenderedBitmap(
                                    pageTranslation = pageTranslation,
                                    renderedBitmap = renderedBitmap,
                                    companionDir = companionDir,
                                    pageKey = pageKey,
                                    successMessageSuffix = " (retry path)",
                                    successErrorMessage = "Inpainted on retry (downscaled decode)",
                                )
                            } finally {
                                if (renderedBitmap !== retriedCleaned) {
                                    try { renderedBitmap.recycle() } catch (_: Exception) {}
                                }
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            pageTranslation.renderStatus = StageStatus.FAILED
                            pageTranslation.retryCount++
                            pageTranslation.errorMessage = e.message
                            logcat(LogPriority.ERROR, e) { "Failed to render text for single page (retry path) $pageKey" }
                        } finally {
                            try { retriedCleaned.recycle() } catch (_: Exception) {}
                        }
                    } else {
                        // Retry also produced no cleaned bitmap. Block the render:
                        // do NOT overlay text on the original image. Surface a
                        // clear, retryable error so the user understands the page
                        // wasn't translated rather than being misled by a half-
                        // translated overlay.
                        pageTranslation.renderStatus = StageStatus.FAILED
                        pageTranslation.retryCount++
                        val reason = pageTranslation.errorMessage ?: "inpaint unavailable"
                        pageTranslation.errorMessage =
                            "Inpainting unavailable ($reason) — original text would show through, so the " +
                                "translated text was not rendered. Retry, or switch recognition mode."
                        logcat(LogPriority.WARN) {
                            "TachiyomiAT single-page render BLOCKED for $pageKey: inpaint unavailable after retry " +
                                "(reason=$reason). Showing original image with error instead of a half-translated overlay."
                        }
                    }
                }
            } else {
                pageTranslation.cleanedBitmap?.let {
                    try { it.recycle() } catch (_: Exception) {}
                }
            }
            pageTranslation.cleanedBitmap = null
            pageTranslation.updatedAt = System.currentTimeMillis()
            persistPageWithOomRecovery(store, pageKey, pageTranslation)
        } finally {
            unregisterActiveStore(syntheticTranslation)
        }
    }

    /**
     * TachiyomiAT: retry-then-block for inpainting. When the first recognize()
     * produced no cleaned bitmap (inpaint failed/unavailable), re-run the full
     * recognize() pipeline on a half-sampled decode before giving up.
     *
     * Why half-sample: the dominant inpaint-failure cause is heap pressure
     * (neural inpaint allocates ~WxH float buffers). Re-decoding at sampleSize*2
     * quarters the pixel count and usually lets the inpainter succeed — a much
     * better outcome than either overlaying text on the original (the old,
     * deceptive fallback) or refusing outright.
     *
     * Returns a [PageTranslation] whose [PageTranslation.cleanedBitmap] is set
     * when the retry succeeded, or the original [pageTranslation] (unchanged)
     * when it also failed. Never renders over the original image — the caller
     * is responsible for surfacing a FAILED render when this returns without a
     * cleaned bitmap, so the user sees an honest error instead of a half-
     * translated page.
     */
    private suspend fun retryInpaintDownscaled(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        streams: List<Pair<String, () -> InputStream>>,
        decoded: DecodedPage,
        pageTranslation: PageTranslation,
    ): PageTranslation {
        // If the page already has a cleaned bitmap there is nothing to retry.
        if (pageTranslation.cleanedBitmap != null) return pageTranslation
        val retrySampleSize = (decoded.sampleSize * 2).coerceAtMost(8)
        if (retrySampleSize == decoded.sampleSize) {
            // Already at the cap; can't downscale further.
            return pageTranslation
        }
        val retryBitmap = try {
            decodePageBitmapAtSize(pageKey, retrySampleSize, streams)
        } catch (oom: OutOfMemoryError) {
            BitmapPool.releaseAll()
            System.gc()
            logcat(LogPriority.WARN, oom) { "Inpaint-retry decode OOM: $pageKey" }
            return pageTranslation
        } ?: return pageTranslation

        try {
            logcat(LogPriority.INFO) {
                "TachiyomiAT inpaint retry at sampleSize=$retrySampleSize for $pageKey (first attempt produced no cleaned bitmap)"
            }
            // Re-run the full pipeline on the smaller bitmap. analyze()+inpaint
            // both run; we only need the cleaned bitmap from this, the original
            // pageTranslation's OCR/translation results are already good.
            val retryTranslation = try {
                recognitionEngine.recognize(retryBitmap)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "Inpaint-retry recognize failed: $pageKey" }
                return pageTranslation
            }
            val cleaned = retryTranslation.cleanedBitmap
            if (cleaned != null) {
                // Scale the cleaned bitmap back up to the original decode
                // dimensions. render() sizes text from the block coordinates,
                // which are in the original decode's coordinate space; drawing
                // the smaller cleaned bitmap then rendering original-scale text
                // on it would misalign. Use decoded.originalWidth/Height (not
                // decoded.bitmap.width — by the time the batch render step runs,
                // the original bitmap has already been recycled by its finally).
                val targetW = decoded.originalWidth / decoded.sampleSize
                val targetH = decoded.originalHeight / decoded.sampleSize
                val scaledCleaned = if (cleaned.width == targetW && cleaned.height == targetH) {
                    cleaned
                } else {
                    val s = Bitmap.createScaledBitmap(cleaned, targetW, targetH, true)
                    if (s !== cleaned) try { cleaned.recycle() } catch (_: Exception) {}
                    s
                }
                pageTranslation.cleanedBitmap = scaledCleaned
                pageTranslation.inpaintStatus = StageStatus.READY
                pageTranslation.errorMessage = null
            }
            retryTranslation.cleanedBitmap = null // we own it now
        } finally {
            try { retryBitmap.recycle() } catch (_: Exception) {}
            BitmapPool.releaseAll()
        }
        return pageTranslation
    }

    private fun persistRenderedBitmap(
        pageTranslation: PageTranslation,
        renderedBitmap: Bitmap,
        companionDir: UniFile?,
        pageKey: String,
        successMessageSuffix: String = "",
        successErrorMessage: String? = null,
    ) {
        val safeName = pageKey.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val renderedFileName = "${safeName}.rendered.webp"
        val renderedFile = companionDir?.createFile(renderedFileName)
        if (renderedFile != null) {
            renderedFile.openOutputStream().use { os ->
                renderedBitmap.compress(Bitmap.CompressFormat.WEBP, 90, os)
            }
            pageTranslation.renderedImageName = renderedFileName
            pageTranslation.renderRevision++
            pageTranslation.renderStatus = StageStatus.READY
            if (successErrorMessage != null) {
                pageTranslation.errorMessage = successErrorMessage
            }
            logcat(LogPriority.INFO) {
                "Saved rendered image: $renderedFileName for $pageKey$successMessageSuffix"
            }
        } else {
            pageTranslation.renderStatus = StageStatus.FAILED
            pageTranslation.retryCount++
            pageTranslation.errorMessage =
                "Could not save translated image — translation output folder is unavailable. " +
                    "Grant storage permission to the app and retry."
            logcat(LogPriority.ERROR) {
                "Could not create rendered image file for $pageKey " +
                    "(companionDir=${companionDir == null}); render marked FAILED"
            }
        }
    }

    private suspend fun processSinglePage(
        fileName: String,
        bitmap: Bitmap,
        decoded: DecodedPage,
        translation: Translation,
        store: ChapterTranslationStore,
        streams: List<Pair<String, () -> InputStream>>,
        ensureCompanionDir: suspend () -> UniFile?,
    ): PageTranslation {
        val pageStart = System.nanoTime()
        var pageTranslation: PageTranslation
        try {
            downgradeOnnxIfMemoryLow(bitmap, fileName)
            pageTranslation = recognitionEngine.recognize(bitmap)
            consecutiveOomCount = 0
        } catch (oom: OutOfMemoryError) {
            handleCriticalTranslationOom("recognizing/inpainting $fileName", oom)
            consecutiveOomCount++
            logcat(LogPriority.ERROR, oom) {
                "Out of memory recognizing/inpainting $fileName (oomCount=$consecutiveOomCount)"
            }
            val retrySampleSize = (decoded.sampleSize * 2).coerceAtMost(8)
            if (retrySampleSize != decoded.sampleSize && retrySampleSize <= 8) {
                logcat(LogPriority.INFO) { "Retrying $fileName at sample size $retrySampleSize after OOM" }
                try {
                    val retryBitmap = decodePageBitmapAtSize(fileName, retrySampleSize, streams)
                    if (retryBitmap != null) {
                        try {
                            downgradeOnnxAfterOom(fileName)
                            pageTranslation = recognitionEngine.recognize(retryBitmap)
                            consecutiveOomCount = 0
                        } finally {
                            try { retryBitmap.recycle() } catch (_: Exception) {}
                        }
                    } else {
                        pageTranslation = createFailedPagePlaceholder(
                            fileName,
                            "OOM during recognition/inpainting. decode retry returned null.",
                            imgWidth = bitmap.width.toFloat(),
                            imgHeight = bitmap.height.toFloat(),
                            originalImgWidth = decoded.originalWidth.toFloat(),
                            originalImgHeight = decoded.originalHeight.toFloat(),
                            decodeSampleSize = decoded.sampleSize,
                        )
                    }
                } catch (retryOom: OutOfMemoryError) {
                    handleCriticalTranslationOom("retrying $fileName", retryOom)
                    pageTranslation = createFailedPagePlaceholder(
                        fileName,
                        "OOM during recognition/inpainting (retry also OOM).",
                        imgWidth = bitmap.width.toFloat(),
                        imgHeight = bitmap.height.toFloat(),
                        originalImgWidth = decoded.originalWidth.toFloat(),
                        originalImgHeight = decoded.originalHeight.toFloat(),
                        decodeSampleSize = decoded.sampleSize,
                    )
                }
            } else {
                pageTranslation = createFailedPagePlaceholder(
                    fileName,
                    "OOM during recognition/inpainting.",
                    imgWidth = bitmap.width.toFloat(),
                    imgHeight = bitmap.height.toFloat(),
                    originalImgWidth = decoded.originalWidth.toFloat(),
                    originalImgHeight = decoded.originalHeight.toFloat(),
                    decodeSampleSize = decoded.sampleSize,
                )
            }
            // TachiyomiAT: lowered from 3 to 2 so the auto-fallback kicks in
            // after fewer blows to the heap. Every OOM is expensive (System.gc()
            // + BitmapPool.releaseAll() above); waiting for 3 pages to fail
            // before switching to the lightweight ML Kit path is too late for
            // the user — they've already seen multiple "nothing happened"
            // experiences by that point.
            if (consecutiveOomCount >= 2 && !autoFallbackToFast) {
                autoFallbackToFast = true
                logcat(LogPriority.WARN) { "Auto-fallback to fast (ML Kit) mode after ${consecutiveOomCount} consecutive OOMs" }
                // Surface the downgrade to the UI through the page that just
                // failed so the user understands WHY subsequent pages may look
                // different (no ONNX inpainting, ML Kit OCR only).
                store.updatePage(fileName) {
                    (it ?: PageTranslation()).apply {
                        errorMessage = "ONNX recognition disabled due to memory pressure — using fast mode"
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e) {
                "Recognition failed for $fileName; falling back to ML Kit OCR recognition"
            }
            // TachiyomiAT: a transient ONNX failure (e.g. the intermittent idx-out-
            // of-bounds error under memory pressure, or a native allocation failure)
            // must NOT permanently kill inpainting for the rest of the chapter.
            // Previously this catch block directly replaced recognitionEngine with
            // MlKitFullPageRecognitionEngine and set NO flag — so the recovery
            // logic (which keys on autoFallbackToFast) never triggered, and every
            // subsequent page showed "Inpainting unavailable in ML Kit mode" until
            // the chapter reloaded. Mirror the OOM path: set autoFallbackToFast so
            // (a) the single-page path restores ONNX once the heap recovers
            // (line ~1051), and (b) the batch path resets it at the next chapter
            // start (line ~594). This makes a transient failure a TEMPORARY
            // downgrade that self-heals, not a permanent one.
            if (recognitionEngine is RoiPageRecognitionEngine) {
                try { recognitionEngine.close() } catch (_: Exception) {}
                val fromLang = translation.fromLang
                recognitionEngine = MlKitFullPageRecognitionEngine(fromLang)
                autoFallbackToFast = true
                logcat(LogPriority.WARN) {
                    "Switched to persistent ML Kit engine after ONNX failure (autoFallbackToFast=true; will self-restore on heap recovery)"
                }
            }
            pageTranslation = recognitionEngine.recognize(bitmap)
        }
        pageTranslation.decodeSampleSize = decoded.sampleSize
        pageTranslation.originalImgWidth = decoded.originalWidth.toFloat()
        pageTranslation.originalImgHeight = decoded.originalHeight.toFloat()
        pageTranslation.sourceFileName = fileName
        pageTranslation.updatedAt = System.currentTimeMillis()
        logcat(LogPriority.INFO) {
            "[translation_page] $fileName blocks=${pageTranslation.blocks.size} " +
                "engine=${pageTranslation.recognitionEngine} sample=${pageTranslation.decodeSampleSize} " +
                "cleaned=${pageTranslation.cleanedBitmap != null} " +
                "elapsedMs=${(System.nanoTime() - pageStart) / 1_000_000}"
        }

        // TachiyomiAT: cooperative cancellation checkpoint. recognize()/inpaint()
        // are suspending but their internals (ONNX native calls) can't observe a
        // cancel, so a chapter-switch or reader-exit cancel issued during those
        // calls only lands at the next suspend point. Drop out here if the job
        // was cancelled mid-recognition so we don't burn cycles rendering/text-
        // translating/persisting a page the caller no longer wants — the
        // stranded-status reset in translatePage's finally will clean up the store.
        coroutineContext.ensureActive()

        if (pageTranslation.cleanedBitmap != null) {
            // Save the cleaned companion image but keep cleanedBitmap alive
            // so the render stage downstream can draw translated text onto it.
            val cDir = ensureCompanionDir()
            val safeName = fileName.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val cleanedFileName = "${safeName}.cleaned.png"
            val cleanedFile = cDir?.createFile(cleanedFileName)
            if (cleanedFile != null) {
                cleanedFile.openOutputStream().use { os ->
                    pageTranslation.cleanedBitmap!!.compress(Bitmap.CompressFormat.PNG, 100, os)
                }
                pageTranslation.cleanedImageName = cleanedFileName
                pageTranslation.inpaintStatus = StageStatus.READY
            } else {
                // TachiyomiAT: a null cDir/createFile failure used to leave
                // inpaintStatus non-terminal and cleanedImageName unset, so the
                // downstream render had no cleaned bitmap to draw on and the
                // page silently showed the original. Surface the storage failure
                // so it's distinguishable from "no text detected".
                pageTranslation.inpaintStatus = StageStatus.FAILED
                pageTranslation.errorMessage =
                    "Could not save cleaned image — translation output folder is unavailable. " +
                    "Grant storage permission to the app and retry."
                logcat(LogPriority.ERROR) {
                    "Could not create cleaned image file for $fileName " +
                        "(cDir=${cDir == null}); inpaint marked FAILED"
                }
            }
        }

        return pageTranslation
    }

    private fun downgradeOnnxIfMemoryLow(bitmap: Bitmap, fileName: String) {
        if (recognitionEngine !is RoiPageRecognitionEngine) return
        if (TranslationMemoryBudget.canStartOnnxRecognition(bitmap.width, bitmap.height)) return
        TranslationMemoryBudget.logSnapshot(
            tag = "onnx_preflight_fallback",
            width = bitmap.width,
            height = bitmap.height,
            extra = "file=$fileName",
        )
        downgradeOnnxAfterOom(fileName)
    }

    private fun downgradeOnnxAfterOom(fileName: String) {
        if (recognitionEngine !is RoiPageRecognitionEngine) return
        try { recognitionEngine.close() } catch (_: Exception) {}
        BitmapPool.releaseAll()
        System.gc()
        recognitionEngine = MlKitFullPageRecognitionEngine(currentFromLang)
        autoFallbackToFast = true
        logcat(LogPriority.WARN) { "Switched to ML Kit recognition for $fileName to preserve heap after ONNX memory pressure" }
    }

    private fun handleCriticalTranslationOom(stage: String, oom: OutOfMemoryError) {
        BitmapPool.releaseAll()
        downgradeOnnxAfterOom(stage)
        System.gc()
        TranslationMemoryBudget.logSnapshot(tag = "oom_recovery", extra = "stage=$stage message=${oom.message}")
    }

    private suspend fun persistPageWithOomRecovery(
        store: ChapterTranslationStore,
        fileName: String,
        pageTranslation: PageTranslation,
    ) {
        try {
            if (TranslationMemoryBudget.isCriticalHeap()) {
                handleCriticalTranslationOom("persisting $fileName", OutOfMemoryError("critical heap before persist"))
            }
            store.updatePage(fileName) { pageTranslation }
        } catch (oom: OutOfMemoryError) {
            handleCriticalTranslationOom("persisting $fileName", oom)
            pageTranslation.cleanedBitmap = null
            try {
                store.updatePage(fileName) { pageTranslation }
            } catch (retryOom: OutOfMemoryError) {
                handleCriticalTranslationOom("persisting lightweight failure $fileName", retryOom)
                store.updatePage(fileName) {
                    createFailedPagePlaceholder(
                        fileName,
                        "OOM while saving translation metadata: ${retryOom.message}",
                        imgWidth = pageTranslation.imgWidth,
                        imgHeight = pageTranslation.imgHeight,
                        originalImgWidth = pageTranslation.originalImgWidth,
                        originalImgHeight = pageTranslation.originalImgHeight,
                        decodeSampleSize = pageTranslation.decodeSampleSize,
                    )
                }
            }
        }
    }

    private fun decodePageBitmapAtSize(fileName: String, sampleSize: Int, streams: List<Pair<String, () -> InputStream>>): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize
        }
        return try {
            val entry = streams.find { it.first == fileName } ?: return null
            entry.second().use { BitmapFactory.decodeStream(it, null, options) }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodePageBitmap(fileName: String, streamFn: () -> InputStream): DecodedPage? {
        // TachiyomiAT: this runs on the translation coroutine, but for DOWNLOADED
        // chapters the reader's own decode is happening concurrently on the main
        // thread. A second OOM here used to propagate uncaught from the bounds
        // pass (the decode pass was guarded by the caller's try/catch, the bounds
        // pass was not). Wrap BOTH passes so an OOM releases the BitmapPool, GCs,
        // and returns null (caller writes a FAILED placeholder) instead of
        // throwing — which on a concurrent reader decode could surface as a crash.
        //
        // We also avoid opening the stream twice for non-seekable sources: the
        // archive fallback (getChapterPages) rebuilds a fresh ArchiveReader and
        // readBytes()s the whole image on EVERY invocation, so the old bounds +
        // decode double-open held two full-image byte arrays at once. Buffer the
        // first read once and replay it for the decode pass.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val buffered: ByteArray = try {
            streamFn().use { it.readBytes() }
        } catch (oom: OutOfMemoryError) {
            BitmapPool.releaseAll()
            System.gc()
            logcat(LogPriority.ERROR, oom) { "Out of memory buffering page bytes for $fileName" }
            return null
        }
        java.io.ByteArrayInputStream(buffered).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sampleSize = TranslationMemoryBudget.chooseDecodeSampleSize(bounds.outWidth, bounds.outHeight)
        TranslationMemoryBudget.logSnapshot(
            tag = "decode",
            width = bounds.outWidth,
            height = bounds.outHeight,
            extra = "sample=$sampleSize file=$fileName",
        )
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize
        }
        val bitmap = try {
            java.io.ByteArrayInputStream(buffered).use { BitmapFactory.decodeStream(it, null, options) }
        } catch (oom: OutOfMemoryError) {
            BitmapPool.releaseAll()
            System.gc()
            logcat(LogPriority.ERROR, oom) { "Out of memory decoding bitmap for $fileName" }
            return null
        } ?: return null
        return DecodedPage(
            bitmap = bitmap,
            sampleSize = sampleSize,
            originalWidth = bounds.outWidth,
            originalHeight = bounds.outHeight,
        )
    }

    private data class DecodedPage(
        val bitmap: Bitmap,
        val sampleSize: Int,
        val originalWidth: Int,
        val originalHeight: Int,
    )

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
        translation.status = Translation.State.QUEUE;
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
                if (translation.status == Translation.State.TRANSLATING || translation.status == Translation.State.QUEUE) {
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
                if (translation.status == Translation.State.TRANSLATING || translation.status == Translation.State.QUEUE) {
                    translation.status = Translation.State.NOT_TRANSLATED
                }
            }
            emptyList()
        }
    }
}
