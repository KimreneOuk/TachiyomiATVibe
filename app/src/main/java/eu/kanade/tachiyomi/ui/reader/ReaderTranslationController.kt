package eu.kanade.tachiyomi.ui.reader

import android.app.Application
import androidx.lifecycle.viewModelScope
import eu.kanade.presentation.more.settings.widget.AiModelListState
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.translation.diagnostics.NativeVisionTelemetry
import eu.kanade.translation.diagnostics.ReaderEntryTrace
import eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceLeaseKind
import eu.kanade.translation.diagnostics.TranslationTraceMode
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTracePlan
import eu.kanade.translation.diagnostics.TranslationTraceSite
import eu.kanade.translation.engines.translator.TranslatorComputeClass
import eu.kanade.translation.engines.translator.providers.AiModelFetcher
import eu.kanade.translation.engines.vision.ocr.OcrModelCatalog
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.displayImageName
import eu.kanade.translation.model.isCleanedImageReady
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.isTranslationDisplayReady
import eu.kanade.translation.model.shouldShowTranslationOverlay
import eu.kanade.translation.model.toDraft
import eu.kanade.translation.model.toPageDisplayProjection
import eu.kanade.translation.model.toPageView
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.repairStrandedPageWithAcquiredAutoLease
import eu.kanade.translation.pipeline.MemoryPressureClass
import eu.kanade.translation.pipeline.MemoryPressurePolicy
import eu.kanade.translation.pipeline.execution.TranslationStreamRegistry
import eu.kanade.translation.diagnostics.AutoSmoothnessTelemetry
import eu.kanade.translation.coordinator.ReaderPageWork
import eu.kanade.translation.coordinator.ReaderSessionToken
import eu.kanade.translation.coordinator.ReaderSubmission
import eu.kanade.translation.coordinator.ReaderTrigger
import eu.kanade.translation.workflow.ReaderSessionIntent
import eu.kanade.translation.workflow.SessionAdmission
import eu.kanade.translation.workflow.SessionRejection
import eu.kanade.translation.workflow.TranslationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.i18n.at.ATMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream
import java.lang.ref.WeakReference

internal class ReaderTranslationController(
    internal val owner: ReaderViewModel,
) {
    internal val mutableState get() = owner.mutableState
    internal val state get() = owner.state
    internal val eventChannel get() = owner.eventChannel
    internal val viewModelScope get() = owner.viewModelScope
    internal val sourceManager: tachiyomi.domain.source.service.SourceManager get() = owner.sourceManager
    internal val downloadManager: DownloadManager get() = owner.downloadManager
    internal val translationManager: TranslationManager get() = owner.translationManager
    internal val readerCoordinator get() = translationManager.readerCoordinator
    internal val streamRegistry: TranslationStreamRegistry get() = owner.streamRegistry
    internal val chapterCache: ChapterCache get() = owner.chapterCache
    internal val translationPreferences: tachiyomi.domain.translation.TranslationPreferences get() = owner.translationPreferences
    internal val aiModelFetchState get() = owner.aiModelFetchState
    internal val manga get() = owner.manga
    internal val chapterPageIndex get() = owner.chapterPageIndex
    internal val translationDiagnosticsEnabled get() = owner.translationDiagnosticsEnabled
    internal var translationStoreJob: Job?
        get() = owner.translationStoreJob
        set(value) {
            owner.translationStoreJob = value
        }
    internal var translationBatchProgressJob: Job?
        get() = owner.translationBatchProgressJob
        set(value) {
            owner.translationBatchProgressJob = value
        }
    internal var translationStateJob: Job?
        get() = owner.translationStateJob
        set(value) {
            owner.translationStateJob = value
        }
    internal var currentTranslationStore: ChapterTranslationStore?
        get() = owner.currentTranslationStore
        set(value) {
            owner.currentTranslationStore = value
        }
    private val pageArrivalTimes get() = owner.pageArrivalTimes

    internal fun recordArrivalWait(pageIndex: Int, pageKey: String) {
        val arrivalEpoch = pageArrivalTimes.remove(pageIndex) ?: return
        val waitMs = (System.currentTimeMillis() - arrivalEpoch).coerceAtLeast(0L).toDouble()
        AutoSmoothnessTelemetry.logArrivalWaitRecorded(
            pageIndex = pageIndex,
            pageKey = pageKey,
            waitMs = waitMs,
            hadToWait = waitMs > 50.0,
        )
    }
    internal val autoPageResolver get() = owner.autoPageResolver
    private val autoReadinessLock get() = owner.autoReadinessLock
    private val autoReadinessGeneration get() = owner.autoReadinessGeneration
    private var autoReadinessLoader: PageLoader?
        get() = owner.autoReadinessLoader
        set(value) {
            owner.autoReadinessLoader = value
        }
    private var autoReadinessPokeInFlight: Boolean
        get() = owner.autoReadinessPokeInFlight
        set(value) {
            owner.autoReadinessPokeInFlight = value
        }
    internal var batchTranslationState: Translation.State
        get() = owner.batchTranslationState
        set(value) {
            owner.batchTranslationState = value
        }
    internal var liveTranslationState: Translation.State
        get() = owner.liveTranslationState
        set(value) {
            owner.liveTranslationState = value
        }

    internal fun recomputeTranslationState() = owner.recomputeTranslationState()
    internal fun getCurrentChapter(): ReaderChapter? = owner.getCurrentChapter()
    internal fun getMangaReadingMode(resolveDefault: Boolean = true): Int =
        owner.getMangaReadingMode(resolveDefault)
    internal fun resolvePageKey(page: ReaderPage): String = owner.resolvePageKey(page)

    internal suspend fun updateTranslationWorkingSet(
        chapter: ReaderChapter,
        currentIndex: Int,
        dispatchRefresh: Boolean,
    ) {
        val manga = manga ?: return
        val chapterId = chapter.chapter.id ?: return
        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        val pages = chapter.pages?.filterIsInstance<ReaderPage>() ?: return
        if (pages.isEmpty()) return

        val keepPageKeys = HashSet<String>()
        val changedPages = linkedSetOf<ReaderPage>()
        val readingMode = ReadingMode.fromPreference(getMangaReadingMode())
        val attachRadius = ReaderPageWarmWindow.attachRadiusFor(readingMode)
        val evictionRadius = ReaderPageWarmWindow.evictionRadiusFor(readingMode)
        for ((listIndex, page) in pages.withIndex()) {
            val shouldAttach = ReaderPageWarmWindow.contains(listIndex, currentIndex, pages.lastIndex, radius = attachRadius)
            val shouldEvict = !ReaderPageWarmWindow.contains(listIndex, currentIndex, pages.lastIndex, radius = evictionRadius)
            if (shouldAttach) {
                keepPageKeys += resolvePageKey(page)
                val hadStream = page.translatedStream != null
                attachTranslatedStreamIfWarm(page, manga, chapter, source)
                if (dispatchRefresh && hadStream != (page.translatedStream != null)) {
                    changedPages += page
                }
            } else if (shouldEvict) {
                if (page.translatedStream != null || page.showTranslatedImage) {
                    changedPages += page
                }
                page.translatedStream = null
                page.showTranslatedImage = false
            } else {
                // Hysteresis deadband: retain already-attached streams without thrashing
                if (page.translatedStream != null) {
                    keepPageKeys += resolvePageKey(page)
                }
            }
        }

        streamRegistry.clearOutsideWindow(
            sourceId = source.id,
            mangaId = manga.id,
            chapterId = chapterId,
            keepPageKeys = keepPageKeys,
        )
        if (translationDiagnosticsEnabled) {
            logcat(LogPriority.INFO) {
                "[translation_working_set] chapterId=$chapterId current=$currentIndex " +
                    "warm=${keepPageKeys.size} cold=${pages.size - keepPageKeys.size} " +
                    "registrySize=${streamRegistry.size()}"
            }
        }
        if (dispatchRefresh && changedPages.isNotEmpty()) {
            eventChannel.trySend(ReaderViewModel.Event.RefreshTranslationPages(changedPages))
            mutableState.update { it.copy(translationRefreshToken = System.currentTimeMillis()) }
        }
    }

    internal suspend fun attachTranslatedStreamIfWarm(
        page: ReaderPage,
        manga: Manga,
        chapter: ReaderChapter,
        source: HttpSource,
    ) {
        val pageKey = resolvePageKey(page)
        val batchActive = chapter.chapter.id?.let { translationManager.isChapterBatchActive(it, includePaused = false) } == true
        if (!batchActive && !isInTranslationWarmWindow(page)) {
            page.translatedStream = null
            page.showTranslatedImage = false
            NativeVisionTelemetry.logDisplayAttach(
                pageKey = pageKey,
                streamAttached = false,
                showTranslatedImage = false,
                displayImageName = page.translation?.displayImageName != null,
            )
            return
        }
        val store = currentTranslationStore
        if (store != null) {
            val translation = page.translation
            if (translation != null && translation.blocks.isEmpty()) {
                val hydrated = withContext(Dispatchers.IO) { store.getOrLoadPageSnapshot(pageKey) }
                if (hydrated != null) {
                    page.translation = hydrated
                }
            }
        }
        val translation = page.translation
        page.translatedStream = when {
            translation?.displayImageName != null -> translationManager.getCleanedImageStream(
                manga.title,
                source,
                chapter.chapter.name,
                chapter.chapter.scanlator,
                translation.displayImageName!!,
                pageKey = pageKey,
                mangaId = manga.id,
                chapterId = chapter.chapter.id,
            )
            else -> null
        }
        if (page.translatedStream == null) {
            if (translation == null || !translation.shouldShowTranslationOverlay) {
                page.showTranslatedImage = false
            }
        }
        NativeVisionTelemetry.logDisplayAttach(
            pageKey = pageKey,
            streamAttached = page.translatedStream != null,
            showTranslatedImage = page.showTranslatedImage,
            displayImageName = translation?.displayImageName != null,
        )
        if (translation?.isTranslationDisplayReady == true || page.translatedStream != null || page.showTranslatedImage) {
            recordArrivalWait(page.index, pageKey)
        }
    }

    /**
     * TachiyomiAT: eagerly resolve a page's translated image stream from the
     * store, callable from a page holder's [setImage] path BEFORE deciding
     * which image to decode.
     *
     * Background: the per-holder `observePageView` collector attaches the
     * translated stream on `Dispatchers.Main.immediate`, which in the common
     * case delivers the store value synchronously inside the holder `init`
     * block — so the stream is usually attached before `setImage()` runs. But
     * there is NO explicit ordering guarantee, and the attachment is bypassed
     * in two real paths: (a) a holder built just outside the warm window,
     * where [attachTranslatedStreamIfWarm] actively nulls the stream until
     * scrolling makes the page warm; (b) `observePageView` bails on a null
     * store/source. In both, `setImage()` reads `page.translatedStream == null`
     * and decodes the ORIGINAL image, then a later collector emission flips
     * it to the translated image — the user sees a brief flash of the source
     * page. The in-code diagnostic at observePageView confirms this is a real,
     * unfixed display-path bug.
     *
     * This public wrapper lets the holder guarantee the stream is attached
     * (when the page is in the warm window and has a rendered/cleaned result)
     * before the single decode pass, eliminating the flash. It is main-thread
     * safe: [TranslationManager.getRenderedImageStream] / getCleanedImageStream
     * return LAZY `(() -> InputStream)?` factories — no disk I/O happens here,
     * only when Coil invokes the lambda on its decoder thread.
     */
    internal suspend fun attachTranslatedStreamForPage(page: ReaderPage) {
        val manga = manga ?: return
        val chapter = getCurrentChapter() ?: return
        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        attachTranslatedStreamIfWarm(page, manga, chapter, source)
    }

    internal fun isInTranslationWarmWindow(page: ReaderPage): Boolean {
        val pages = page.chapter.pages?.filterIsInstance<ReaderPage>() ?: return false
        val pageIndex = pages.indexOfFirst { it === page }.takeIf { it >= 0 } ?: page.index
        val currentIndex = if (page.chapter === getCurrentChapter()) {
            if (chapterPageIndex >= 0) chapterPageIndex else page.chapter.requestedPage
        } else {
            page.chapter.requestedPage
        }
        return ReaderPageWarmWindow.contains(
            pageIndex,
            currentIndex,
            pages.lastIndex,
            radius = ReaderPageWarmWindow.radiusFor(ReadingMode.fromPreference(getMangaReadingMode())),
        )
    }

    /**
     * Auto viewport entry point. Records the latest desired viewport and arms
     * it on the captured queue session immediately — scroll updates the
     * viewport without debounce, so async store/source preparation can never
     * drop the newest position. A session bind still in flight dispatches the
     * latest viewport when it completes.
     */
    internal fun handleAutoTranslation(currentPage: ReaderPage) {
        val chapterKey = currentPage.chapter.chapter.id?.toString() ?: return
        readerCoordinator.sessionTokenForChapter(chapterKey)?.let { token ->
            dispatchViewport(token, currentPage)
            return
        }
        viewModelScope.launchIO {
            val token = ensureQueueSession(currentPage.chapter) ?: return@launchIO
            val latestPage = (state.value.currentPage as? ReaderPage)
                ?.takeIf { it.chapter === currentPage.chapter }
                ?: return@launchIO
            dispatchViewport(token, latestPage)
        }
    }

    /**
     * Binds (or reuses) the reader queue session for [chapter]: opens the
     * admitted translation session, binds the queue with the live compute
     * class, and installs the page work resolver. The async preparation joins
     * a concurrent bind instead of duplicating it.
     */
    internal suspend fun ensureQueueSession(chapter: ReaderChapter): ReaderSessionToken? {
        val manga = manga ?: return null
        val chapterId = chapter.chapter.id ?: return null
        val source = sourceManager.get(manga.source) as? HttpSource ?: return null
        val domainChapter = chapter.chapter.toDomainChapter() ?: return null
        val chapterKey = chapterId.toString()
        val expectedKey = "${source.id}:${manga.id}:$chapterId"
        val session = readerCoordinator.sessionForChapter(chapterKey)
            ?.takeIf { it.key == expectedKey }
            ?: readerCoordinator.openSession(manga, domainChapter, source)
            ?: return null
        val computeClass = currentTranslatorComputeClass()
        val previousToken = readerCoordinator.sessionTokenForChapter(chapterKey)
        val token = readerCoordinator.bindSession(session, computeClass) ?: return null
        val pages = chapter.pages.orEmpty()
        if (previousToken != token || autoReadinessLoader !== chapter.pageLoader) {
            installReaderSourceReadiness(chapter, token)
        }
        logcat(LogPriority.INFO) {
            "TachiyomiAT reader queue session bound: chapterId=$chapterId " +
                "generation=${token.readerGeneration} computeClass=${computeClass.name} pageCount=${pages.size}"
        }
        return token
    }

    /** Re-drives this chapter's queued demands after a lifecycle/memory/source event. */
    private fun reconcileCapturedSession() {
        val chapterKey = getCurrentChapter()?.chapter?.id?.toString() ?: return
        readerCoordinator.reconcileChapter(chapterKey)
    }

    /**
     * Arms the auto window demand set (exactly N, N+1) on the captured session
     * for the latest viewport. Rebinding the resolver keeps already-issued
     * stream handles valid when the reader replaced its page list.
     */
    private fun dispatchViewport(token: ReaderSessionToken, currentPage: ReaderPage) {
        val session = readerCoordinator.sessionFor(token) ?: return
        val pages = currentPage.chapter.pages ?: return
        val currentIndex = pages.indexOfFirst { it === currentPage }
        if (currentIndex < 0) return
        val resolve = autoPageResolver.bind(token, session.store, pages)
        val configuredAheadTarget = 1
        logcat(LogPriority.INFO) {
            "TachiyomiAT auto-translate window: visible=$currentIndex " +
                "ahead=$configuredAheadTarget pageCount=${pages.size} pageKey=${resolvePageKey(currentPage)}"
        }
        readerCoordinator.updateViewport(
            token = token,
            pageIndex = currentIndex,
            pageCount = pages.size,
            resolve = resolve,
        )
    }

    /**
     * Installs one loader-level source-readiness callback shared by pager and webtoon holders.
     * Both holders call PageLoader.loadPage(page), and HttpPageLoader invokes this hook after
     * originalStream is assigned. Weak references avoid making a loader retain the ViewModel or
     * chapter while the callback is waiting for a late network page.
     */
    private fun installReaderSourceReadiness(
        chapter: ReaderChapter,
        token: ReaderSessionToken,
    ) {
        synchronized(autoReadinessLock) {
            clearReaderSourceReadinessLocked()
            val pageLoader = chapter.pageLoader
            if (pageLoader != null) {
                val generation = autoReadinessGeneration.incrementAndGet()
                val controllerRef = WeakReference(this@ReaderTranslationController)
                val boundChapter = WeakReference(chapter)
                autoReadinessLoader = pageLoader
                pageLoader.onPageStreamReady = {
                    val controller = controllerRef.get()
                    val activeChapter = boundChapter.get()
                    if (controller != null && activeChapter != null) {
                        controller.onReaderSourceReady(activeChapter, token, generation)
                    }
                }
            }
        }
    }

    private fun onReaderSourceReady(
        chapter: ReaderChapter,
        token: ReaderSessionToken,
        readinessGeneration: Long,
    ) {
        synchronized(autoReadinessLock) {
            val activeToken = chapter.chapter.id?.let { readerCoordinator.sessionTokenForChapter(it.toString()) }
            val isActive = autoReadinessLoader != null &&
                readinessGeneration == autoReadinessGeneration.get() &&
                shouldReconcileReaderSourceReady(
                    currentChapter = state.value.currentChapter,
                    boundChapter = chapter,
                    activeSession = activeToken,
                    boundSession = token,
                    activeReadinessGeneration = autoReadinessGeneration.get(),
                    boundReadinessGeneration = readinessGeneration,
                    translationEnabled = translationPreferences.translationEnabled().get(),
                )
            if (isActive && !autoReadinessPokeInFlight) {
                autoReadinessPokeInFlight = true
                try {
                    readerCoordinator.reconcileChapter(chapter.chapter.id.toString())
                } finally {
                    autoReadinessPokeInFlight = false
                }
            }
        }
    }

    private fun clearReaderSourceReadinessLocked() {
        autoReadinessGeneration.incrementAndGet()
        autoReadinessLoader?.onPageStreamReady = null
        autoReadinessLoader = null
        autoReadinessPokeInFlight = false
    }

    private fun clearReaderSourceReadiness() {
        synchronized(autoReadinessLock) {
            clearReaderSourceReadinessLocked()
        }
    }

    private fun currentTranslatorComputeClass(): TranslatorComputeClass =
        TranslatorComputeClass.forConfiguration(
            category = translationPreferences.translationEngineCategory().get(),
            standardEngineName = translationPreferences.translationStandardEngine().get().name,
            aiEngineName = translationPreferences.translationAiEngine().get().name,
        )

    /**
     * Translates just the page the user is currently viewing. Used when the
     * Auto toggle is switched ON so the current page starts processing
     * immediately, instead of waiting for the next page-change event.
     */
    internal fun translateCurrentPageForAuto() {
        val pages = getCurrentChapter()?.pages ?: return
        val page = pages.getOrNull(chapterPageIndex) as? ReaderPage ?: return
        handleAutoTranslation(page)
    }

    /** Reader auto-toggle transition; explicit taps remain in the captured session after auto-off. */
    internal fun onReaderAutoToggled(enabled: Boolean) {
        val chapterKey = getCurrentChapter()?.chapter?.id?.toString() ?: return
        val hadAutomaticDemand = readerCoordinator.setAutoEnabled(chapterKey, enabled)
        if (enabled) {
            if (translationPreferences.translationEnabled().get()) {
                translateCurrentPageForAuto()
            }
            return
        }
        if (hadAutomaticDemand) {
            val context = Injekt.get<Application>()
            context.toast(ATMR.strings.auto_translation_disabled_toast)
        }
    }

    /**
     * Engine-family change with Auto on: drop the captured binding so the next
     * dispatch rebinds the queue session with the fresh compute class — the
     * queue's rebind drains the old epoch's demand set — then re-kick the
     * current page through the same viewport path.
     */
    internal fun rebindForComputeEpochChange() {
        viewModelScope.launchIO {
            if (translationPreferences.autoTranslate().get() &&
                translationPreferences.translationEnabled().get()
            ) {
                val chapter = getCurrentChapter() ?: return@launchIO
                val token = ensureQueueSession(chapter) ?: return@launchIO
                val currentPage = state.value.currentPage as? ReaderPage ?: return@launchIO
                if (currentPage.chapter === chapter) dispatchViewport(token, currentPage)
            }
        }
    }

    internal fun resetAutoTranslationState() {
        // Invalidate loader callbacks and resolver-held page/stream handles before any caller
        // cancels a queue session or unreferences the chapter's loader resources.
        clearReaderSourceReadiness()
        autoPageResolver.invalidate()
    }

    private val actions by lazy { ReaderTranslationActions(this) }
    private val observation by lazy { ReaderTranslationObservationController(this) }

    internal suspend fun cancelTranslationForChapter(chapter: ReaderChapter) = actions.cancelTranslationForChapter(chapter)
    internal fun deleteCurrentChapterTranslation() = actions.deleteCurrentChapterTranslation()
    internal fun resetTranslationData(preserveEdits: Boolean) = actions.resetTranslationData(preserveEdits)
    internal fun resetInpaintData() = actions.resetInpaintData()
    internal fun resetOcrData() = actions.resetOcrData()
    internal fun resetEverything() = actions.resetEverything()
    internal fun translateSinglePage(page: ReaderPage, force: Boolean? = null) = actions.translateSinglePage(page, force)
    internal fun confirmBatchReaderSwitch(request: ReaderViewModel.Dialog.BatchReaderSwitch) = actions.confirmBatchReaderSwitch(request)
    internal fun cancelSinglePageTranslation(page: ReaderPage) = actions.cancelSinglePageTranslation(page)
    internal fun stopAllTranslation() = actions.stopAllTranslation()
    internal fun retryCurrentBatch(force: Boolean = false) = actions.retryCurrentBatch(force)
    internal fun setTranslationEnabled(enabled: Boolean) {
        translationPreferences.translationEnabled().set(enabled)
    }

    internal fun setAutoTranslate(auto: Boolean) {
        translationPreferences.autoTranslate().set(auto)
    }

    internal fun setTranslateFromLanguage(language: String) {
        translationPreferences.translateFromLanguage().set(language)
        val recent = TranslationPreferences.decodeRecentLanguages(translationPreferences.translationRecentLanguagesFrom().get())
        val updated = TranslationPreferences.encodeRecentLanguages(listOf(language) + recent)
        translationPreferences.translationRecentLanguagesFrom().set(updated)
    }

    internal fun setTranslateToLanguage(language: String) {
        translationPreferences.translateToLanguage().set(language)
        val recent = TranslationPreferences.decodeRecentLanguages(translationPreferences.translationRecentLanguagesTo().get())
        val updated = TranslationPreferences.encodeRecentLanguages(listOf(language) + recent)
        translationPreferences.translationRecentLanguagesTo().set(updated)
    }

    internal fun setOcrModel(model: OcrModel) {
        val fromValue = translationPreferences.translateFromLanguage().get()
        val language = TextRecognizerLanguage.entries.firstOrNull { it.name == fromValue } ?: TextRecognizerLanguage.CHINESE
        OcrModelCatalog.preferenceFor(translationPreferences, language).set(model)
    }

    internal fun setTranslationInpaintingMode(mode: String) {
        translationPreferences.translationInpaintingMode().set(mode)
    }

    internal fun setTranslationInpaintingNeuralModel(model: NeuralInpaintModel) {
        translationPreferences.translationInpaintingNeuralModel().set(model)
    }

    internal fun setTranslationEngineCategory(category: TranslationEngineCategory) {
        translationPreferences.translationEngineCategory().set(category)
    }

    internal fun setTranslationStandardEngine(engine: StandardEngine) {
        translationPreferences.translationStandardEngine().set(engine)
    }

    internal fun setTranslationDeeplApiKey(key: String) {
        translationPreferences.translationDeeplApiKey().set(key)
    }

    internal fun setTranslationAiEngine(engine: AiEngine) {
        translationPreferences.translationAiEngine().set(engine)
    }

    internal fun setTranslationAiApiKey(key: String) {
        val engine = translationPreferences.translationAiEngine().get()
        translationPreferences.translationAiApiKey(engine).set(key)
    }

    internal fun setTranslationAiBaseUrl(url: String) {
        val engine = translationPreferences.translationAiEngine().get()
        translationPreferences.translationAiBaseUrl(engine)?.set(url)
    }

    internal fun setTranslationAiModel(model: String) {
        val engine = translationPreferences.translationAiEngine().get()
        translationPreferences.translationAiModel(engine).set(model)
        val recentPref = translationPreferences.translationAiRecentModels(engine)
        val recentModels = TranslationPreferences.decodeRecentModels(recentPref.get())
        recentPref.set(TranslationPreferences.encodeRecentModels(listOf(model) + recentModels))
    }

    internal fun fetchAiModels() {
        val aiEngine = translationPreferences.translationAiEngine().get()
        val key = translationPreferences.translationAiApiKey(aiEngine).get()
        val url = translationPreferences.translationAiBaseUrl(aiEngine)?.get().orEmpty()

        aiModelFetchState.value = AiModelListState.Loading(if (aiEngine == AiEngine.LMSTUDIO) url else key)
        viewModelScope.launch {
            try {
                val result = AiModelFetcher.fetch(aiEngine, key, url)
                aiModelFetchState.value = when (result) {
                    is AiModelFetcher.Result.Success -> AiModelListState.Loaded(result.models)
                    is AiModelFetcher.Result.InvalidKey -> AiModelListState.Failed("Invalid or expired API key")
                    is AiModelFetcher.Result.NoModels -> AiModelListState.Loaded(emptyList())
                    is AiModelFetcher.Result.Error -> AiModelListState.Failed(result.message)
                }
            } catch (e: Exception) {
                aiModelFetchState.value = AiModelListState.Failed(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * TachiyomiAT: cancel all translation work when the reader
     * is backgrounded or the activity is finishing. Previously, only onCleared()
     * (ViewModel destruction) cancelled translation work, which meant background
     * translation kept consuming battery, bandwidth, and the single translator
     * permit for minutes after the user switched apps. Called from onPause()
     * and finish() in ReaderActivity.
     */
    internal fun cancelTranslationsOnBackground() {
        resetAutoTranslationState()
        translationManager.stopReaderTranslations("reader backgrounded")
        // TachiyomiAT: evict registered reader page streams so their captured
        // page bytes are freed while the reader sits in the background, instead
        // of pinning them in the process-lifetime map.
        streamRegistry.clearAll()
    }

    /**
     * TachiyomiAT: counterpart to [cancelTranslationsOnBackground]. On resume,
     * re-arm auto-translation only when the user still has it enabled: re-attach
     * the translated-image streams background evicted ([streamRegistry.clearAll]),
     * bounded to the warm window, then re-kick the current page through the
     * existing auto dispatch path ([translateCurrentPageForAuto]).
     *
     * Manual-stop gating: there is NO separate "stopped" sentinel in this VM.
     * The only reliable stop signal is autoTranslate()==false — the init block
     * forces it off on reader entry, and the settings sheet flips it off when the
     * user stops Auto (its .changes() collector then cancels). Backgrounding does
     * NOT touch the pref, so a still-on Auto surviving a background trip is
     * exactly the case to resume, while a user-stopped Auto stays false and is
     * skipped. translationEnabled() is gated as well to match the live auto
     * dispatch sites (onPageSelected / loadChapter), which all require both.
     */
    internal fun resumeTranslationsOnForeground() {
        // Foreground resume is a real lifecycle/memory-recovery signal for
        // deferred demands (memory/source); it must not wait for a page change
        // before re-driving the captured session's queued work.
        reconcileCapturedSession()
        if (!translationPreferences.translationEnabled().get()) return
        if (!translationPreferences.autoTranslate().get()) return
        val chapter = getCurrentChapter() ?: return
        if (chapterPageIndex < 0) return
        viewModelScope.launch {
            updateTranslationWorkingSet(chapter, chapterPageIndex, dispatchRefresh = true)
            translateCurrentPageForAuto()
        }
    }

    internal fun onMemoryPressure(level: Int) {
        translationManager.onMemoryPressure(level)
        // The trim callback is the reader's real memory-budget signal. A later
        // callback or foreground resume re-opens admission without navigation.
        reconcileCapturedSession()
        // CP8: only reset the live display state under genuine foreground memory pressure.
        // The previous `level >= TRIM_MEMORY_RUNNING_LOW` check fired on UI_HIDDEN(20)/
        // BACKGROUND(40)/MODERATE(60) too (they all exceed RUNNING_LOW(15)), so every
        // app-background trip blanked the translation indicator. Gate on the classified
        // Critical class instead so a benign background trim leaves the display state alone.
        if (MemoryPressurePolicy.classify(level) == MemoryPressureClass.Critical) {
            liveTranslationState = Translation.State.NOT_TRANSLATED
            recomputeTranslationState()
        }
    }

    internal fun isCurrentChapterDownloaded(): Boolean {
        val manga = manga ?: return false
        val chapter = getCurrentChapter()?.chapter ?: return false
        return downloadManager.isChapterDownloaded(chapter.name, chapter.scanlator, manga.title, manga.source)
    }

    internal fun observeTranslationState() = observation.observeTranslationState()
    internal suspend fun observeLiveTranslationStore() = observation.observeLiveTranslationStore()
    internal fun observePageView(page: ReaderPage): Flow<eu.kanade.translation.model.PageView>? = observation.observePageView(page)
}
