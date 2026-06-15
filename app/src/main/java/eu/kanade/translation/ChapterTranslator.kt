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
import kotlinx.coroutines.ensureActive
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
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
import java.util.concurrent.ConcurrentHashMap


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

        private fun takeReaderPageStream(
            manga: Manga,
            chapter: Chapter,
            source: HttpSource,
            pageKey: String,
        ): (() -> InputStream)? = readerPageStreams.remove(readerPageStreamKey(manga, chapter, source, pageKey))

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

    val isRunning: Boolean
        get() = translationJob?.isActive == true

    @Volatile
    var isPaused: Boolean = false

    private var currentFromLang: TextRecognizerLanguage
    private var textTranslator: TextTranslator
    private var recognitionEngine: PageRecognitionEngine
    private var currentInpaintingMode: InpaintingMode

    init {
        val fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
        val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
        currentFromLang = fromLang
        currentInpaintingMode = inpaintingModeFromPref()
        recognitionEngine = createRecognitionEngine(fromLang, currentInpaintingMode)
        textTranslator = TranslationEngineBuilder.build(translationPreferences, fromLang, toLang)
    }

    private fun inpaintingModeFromPref(): InpaintingMode {
        return when (translationPreferences.translationInpaintingMode().get()) {
            "FAST" -> InpaintingMode.FAST
            else -> InpaintingMode.QUALITY
        }
    }

    private fun createRecognitionEngine(lang: TextRecognizerLanguage, mode: InpaintingMode): PageRecognitionEngine {
        val onnx = RoiPageRecognitionEngine(context, lang, mode)
        if (onnx.isAvailable) {
            logcat(LogPriority.INFO) { "Using ONNX recognition engine for $lang" }
            return onnx
        }
        onnx.close()
        logcat(LogPriority.INFO) { "ONNX unavailable, falling back to ML Kit recognition engine for $lang" }
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
        // NOTE: This does NOT acquire translatorPermit to avoid a re-entrancy
        // deadlock (callers like stop() invoked from inside a withPermit block
        // would deadlock on the non-reentrant Semaphore(1)). The concurrent-close
        // NPE risk is instead eliminated at the engine layer: RoiPageRecognitionEngine
        // captures detector/roiOcrEngine into locals and throws a catchable
        // IllegalStateException if they were nulled mid-analyze, which
        // processSinglePage catches and degrades gracefully.
        enginesClosed = true
        try { recognitionEngine.close() } catch (_: Exception) {}
        try { textTranslator.close() } catch (_: Exception) {}
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
        )
    }

    private suspend fun translateChapter(translation: Translation) = translatorPermit.withPermit {
        translateChapterInternal(translation)
    }

    private suspend fun translateChapterInternal(translation: Translation) {
        var store: ChapterTranslationStore? = null
        try {
            if (enginesClosed || translation.fromLang != currentFromLang) {
                recognitionEngine.close()
                currentFromLang = translation.fromLang
                currentInpaintingMode = inpaintingModeFromPref()
                recognitionEngine = createRecognitionEngine(translation.fromLang, currentInpaintingMode)
                enginesClosed = false
            }
            val newMode = inpaintingModeFromPref()
            if (newMode != currentInpaintingMode) {
                recognitionEngine.close()
                currentInpaintingMode = newMode
                recognitionEngine = createRecognitionEngine(currentFromLang, currentInpaintingMode)
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

            val streams = getChapterPages(chapterPath)
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
                store.updatePage(fileName) {
                    (it ?: PageTranslation()).apply {
                        sourceFileName = fileName
                        ocrStatus = StageStatus.RUNNING
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
                    val placeholder = createFailedPagePlaceholder(
                        fileName,
                        "OutOfMemory decoding page: ${oom.message}",
                    )
                    store.updatePage(fileName) { placeholder }
                    BitmapPool.releaseAll()
                    continue
                }
                if (decoded == null) {
                    logcat(LogPriority.WARN) { "Failed to decode $fileName (null bitmap, bounds invalid)" }
                    val placeholder = createFailedPagePlaceholder(
                        fileName,
                        "Failed to decode page: null bitmap or invalid bounds",
                    )
                    store.updatePage(fileName) { placeholder }
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
                    val cleanedBitmap = pageTranslation.cleanedBitmap
                    if (cleanedBitmap != null) {
                        try {
                            pageTranslation.renderStatus = StageStatus.RUNNING
                            renderer.render(cleanedBitmap, pageTranslation.blocks)

                            val cDir = companionDir ?: provider.getCompanionImageDir(
                                translation.manga.title, translation.source,
                                translation.chapter.name, translation.chapter.scanlator,
                            ).also { companionDir = it }
                            val safeName = fileName.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_")
                            val renderedFileName = "${safeName}.rendered.webp"
                            val renderedFile = cDir?.createFile(renderedFileName)
                            if (renderedFile != null) {
                                renderedFile.openOutputStream().use { os ->
                                    cleanedBitmap.compress(Bitmap.CompressFormat.WEBP, 90, os)
                                }
                                pageTranslation.renderedImageName = renderedFileName
                                pageTranslation.renderStatus = StageStatus.READY
                                logcat(LogPriority.INFO) { "Saved rendered image: $renderedFileName for $fileName" }
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            pageTranslation.renderStatus = StageStatus.FAILED
                            pageTranslation.errorMessage = e.message
                            logcat(LogPriority.ERROR, e) { "Failed to render text for $fileName" }
                        } finally {
                            // Always recycle cleanedBitmap after the render attempt
                            try { cleanedBitmap.recycle() } catch (_: Exception) {}
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
    ) = translatorPermit.withPermit {
        // Dedup: if this exact page is already being translated (e.g. an
        // auto-mode re-enqueue on scroll), drop the redundant request instead
        // of queuing it behind itself. Safe without an extra lock because the
        // permit serializes entry.
        if (!inFlightPageKeys.add(pageKey)) return@withPermit
        try {
            // Bound how long a single page can hold the (sole) translatorPermit.
            // Without this, a hung ONNX inference or a stalled AI/HTTP request
            // would block EVERY other page's translation for the whole session
            // (only chapter-change / reader-exit cancels it). On timeout the
            // permit is released (withPermit's finally), and we log it; the page
            // just doesn't get translated this run.
            withTimeoutOrNull(SINGLE_PAGE_TIMEOUT_MS) {
                translateSinglePageInternal(manga, chapter, source, pageKey)
            } ?: run {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT single-page translation timed out after ${SINGLE_PAGE_TIMEOUT_MS}ms: " +
                        "pageKey=$pageKey chapter=${chapter.name}"
                }
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
    ) = translatorPermit.withPermit {
        if (!inFlightPageKeys.add(pageKey)) return@withPermit
        try {
            withTimeoutOrNull(SINGLE_PAGE_TIMEOUT_MS) {
                translateSinglePageInternal(manga, chapter, source, pageKey, streamFn)
            } ?: run {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT single-page (stream) translation timed out after ${SINGLE_PAGE_TIMEOUT_MS}ms: " +
                        "pageKey=$pageKey chapter=${chapter.name}"
                }
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
        val streamFromReader = readerStreamFn ?: takeReaderPageStream(manga, chapter, source, pageKey)
        val fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
        val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
        val syntheticTranslation = Translation(source, manga, chapter, fromLang, toLang)
        syntheticTranslation.status = Translation.State.TRANSLATING

        // Ensure the translation engine matches the current preferences.
        if (enginesClosed || fromLang != currentFromLang) {
            recognitionEngine.close()
            currentFromLang = fromLang
            currentInpaintingMode = inpaintingModeFromPref()
            recognitionEngine = createRecognitionEngine(fromLang, currentInpaintingMode)
            enginesClosed = false
        }
        if (enginesClosed || fromLang != textTranslator.fromLang || toLang != textTranslator.toLang) {
            withContext(Dispatchers.IO) { textTranslator.close() }
            textTranslator = TranslationEngineBuilder.build(translationPreferences, fromLang, toLang)
            enginesClosed = false
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
                        updatedAt = System.currentTimeMillis()
                    }
                }
                return
            }

            // Emit RUNNING early so the reader shows the overlay for this page.
            store.updatePage(pageKey) {
                (it ?: PageTranslation()).apply {
                    sourceFileName = pageKey
                    ocrStatus = StageStatus.RUNNING
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

            if (pageTranslation.blocks.isNotEmpty() && pageTranslation.translationStatus == StageStatus.READY) {
                val cleanedBitmap = pageTranslation.cleanedBitmap
                if (cleanedBitmap != null) {
                    try {
                        pageTranslation.renderStatus = StageStatus.RUNNING
                        val renderer = PageTextRenderer(context)
                        renderer.render(cleanedBitmap, pageTranslation.blocks)
                        val companionDir = provider.getCompanionImageDir(
                            manga.title, source,
                            chapter.name, chapter.scanlator,
                        )
                        val safeName = pageKey.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_")
                        val renderedFileName = "${safeName}.rendered.webp"
                        val renderedFile = companionDir?.createFile(renderedFileName)
                        if (renderedFile != null) {
                            renderedFile.openOutputStream().use { os ->
                                cleanedBitmap.compress(Bitmap.CompressFormat.WEBP, 90, os)
                            }
                            pageTranslation.renderedImageName = renderedFileName
                            pageTranslation.renderStatus = StageStatus.READY
                            logcat(LogPriority.INFO) { "Saved single-page rendered image: $renderedFileName for $pageKey" }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        pageTranslation.renderStatus = StageStatus.FAILED
                        pageTranslation.errorMessage = e.message
                        logcat(LogPriority.ERROR, e) { "Failed to render text for single page $pageKey" }
                    } finally {
                        try { cleanedBitmap.recycle() } catch (_: Exception) {}
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
            if (consecutiveOomCount >= 3 && !autoFallbackToFast) {
                autoFallbackToFast = true
                logcat(LogPriority.WARN) { "Auto-fallback to fast mode after ${consecutiveOomCount} OOMs" }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e) {
                "Recognition failed for $fileName; falling back to ML Kit OCR recognition"
            }
            // Replace the engine with persistent ML Kit instead of creating one-shot fallbacks
            if (recognitionEngine is RoiPageRecognitionEngine) {
                try { recognitionEngine.close() } catch (_: Exception) {}
                val fromLang = translation.fromLang
                recognitionEngine = MlKitFullPageRecognitionEngine(fromLang)
                logcat(LogPriority.WARN) { "Switched to persistent ML Kit engine after ONNX failure" }
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
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        streamFn().use { BitmapFactory.decodeStream(it, null, bounds) }
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
        val bitmap = streamFn().use { BitmapFactory.decodeStream(it, null, options) } ?: return null
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
                    entries.filter { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                        .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }.map { entry ->
                            Pair(entry.name) {
                                chapterPath.archiveReader(context).use { archive ->
                                    archive.getInputStream(entry.name)!!.use { it.readBytes() }.inputStream()
                                }
                            }
                        }.toList()
                }
            }
        } else {
            return chapterPath.listFiles()!!.filter { ImageUtil.isImage(it.name) }.map { entry ->
                Pair(entry.name!!) { entry.openInputStream() }
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
