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
import eu.kanade.translation.diagnostics.ReaderEntryTrace
import eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceLeaseKind
import eu.kanade.translation.diagnostics.TranslationTraceMode
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTracePlan
import eu.kanade.translation.diagnostics.TranslationTraceSite
import eu.kanade.translation.engines.rendering.HydratedLayout
import eu.kanade.translation.engines.rendering.LayoutPlanPublication
import eu.kanade.translation.engines.rendering.PersistedLayoutHydrator
import eu.kanade.translation.engines.rendering.PersistedLayoutReaderBridge
import eu.kanade.translation.engines.rendering.PersistedLayoutRuntime
import eu.kanade.translation.engines.translator.TranslatorComputeClass
import eu.kanade.translation.engines.translator.providers.AiModelFetcher
import eu.kanade.translation.engines.vision.ocr.OcrModelCatalog
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.displayImageName
import eu.kanade.translation.model.isCleanedImageReady
import eu.kanade.translation.model.isTranslationDisplayReady
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.shouldShowTranslationOverlay
import eu.kanade.translation.model.toDraft
import eu.kanade.translation.model.toPageDisplayProjection
import eu.kanade.translation.model.toPageView
import eu.kanade.translation.persistence.artifact.PageLayoutDrawPlan
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.LeaseAcquisition
import eu.kanade.translation.persistence.chapter.PageWriteOrigin
import eu.kanade.translation.pipeline.MemoryPressureClass
import eu.kanade.translation.pipeline.MemoryPressurePolicy
import eu.kanade.translation.pipeline.NativeVisionTelemetry
import eu.kanade.translation.pipeline.TranslationPipeline
import eu.kanade.translation.pipeline.execution.TranslationStreamRegistry
import eu.kanade.translation.scheduling.AutoChapterIdentity
import eu.kanade.translation.scheduling.AutoSmoothnessTelemetry
import eu.kanade.translation.scheduling.TranslationScheduler
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
    private val owner: ReaderViewModel,
) {
    private val mutableState get() = owner.mutableState
    private val state get() = owner.state
    private val eventChannel get() = owner.eventChannel
    private val viewModelScope get() = owner.viewModelScope
    private val sourceManager: tachiyomi.domain.source.service.SourceManager get() = owner.sourceManager
    private val downloadManager: DownloadManager get() = owner.downloadManager
    private val translationManager: TranslationManager get() = owner.translationManager
    private val translationScheduler: TranslationScheduler get() = owner.translationScheduler
    private val streamRegistry: TranslationStreamRegistry get() = owner.streamRegistry
    private val chapterCache: ChapterCache get() = owner.chapterCache
    private val translationPreferences: tachiyomi.domain.translation.TranslationPreferences get() = owner.translationPreferences
    private val aiModelFetchState get() = owner.aiModelFetchState
    private val manga get() = owner.manga
    private val chapterPageIndex get() = owner.chapterPageIndex
    private val translationDiagnosticsEnabled get() = owner.translationDiagnosticsEnabled
    private var translationStoreJob: Job?
        get() = owner.translationStoreJob
        set(value) {
            owner.translationStoreJob = value
        }
    private var translationBatchProgressJob: Job?
        get() = owner.translationBatchProgressJob
        set(value) {
            owner.translationBatchProgressJob = value
        }
    private var translationStateJob: Job?
        get() = owner.translationStateJob
        set(value) {
            owner.translationStateJob = value
        }
    private var autoSnapshotJob: Job?
        get() = owner.autoSnapshotJob
        set(value) {
            owner.autoSnapshotJob = value
        }
    private var currentTranslationStore: ChapterTranslationStore?
        get() = owner.currentTranslationStore
        set(value) {
            owner.currentTranslationStore = value
        }
    private var autoTranslationScrollJob: Job?
        get() = owner.autoTranslationScrollJob
        set(value) {
            owner.autoTranslationScrollJob = value
        }
    private val pageArrivalTimes get() = owner.pageArrivalTimes
    private var pendingDebouncePage: Int? = null
    private var pendingDebounceStartNs: Long = 0L

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
    private val autoPageResolver get() = owner.autoPageResolver
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
    private var activeAutoIdentity: AutoChapterIdentity?
        get() = owner.activeAutoIdentity
        set(value) {
            owner.activeAutoIdentity = value
        }
    private val autoSnapshotGeneration get() = owner.autoSnapshotGeneration
    private val autoSnapshotFenceLock get() = owner.autoSnapshotFenceLock
    private var acceptedAutoOwnerVersion: Long?
        get() = owner.acceptedAutoOwnerVersion
        set(value) {
            owner.acceptedAutoOwnerVersion = value
        }
    private var acceptedAutoWindowVersion: Long
        get() = owner.acceptedAutoWindowVersion
        set(value) {
            owner.acceptedAutoWindowVersion = value
        }
    private var activeAutoOwnerToken: Any?
        get() = owner.activeAutoOwnerToken
        set(value) {
            owner.activeAutoOwnerToken = value
        }
    private val autoReaderSessionKey get() = owner.autoReaderSessionKey
    private var batchTranslationState: Translation.State
        get() = owner.batchTranslationState
        set(value) {
            owner.batchTranslationState = value
        }
    private var liveTranslationState: Translation.State
        get() = owner.liveTranslationState
        set(value) {
            owner.liveTranslationState = value
        }

    private fun recomputeTranslationState() = owner.recomputeTranslationState()
    private fun getCurrentChapter(): ReaderChapter? = owner.getCurrentChapter()
    private fun getMangaReadingMode(resolveDefault: Boolean = true): Int =
        owner.getMangaReadingMode(resolveDefault)
    private fun resolvePageKey(page: ReaderPage): String = owner.resolvePageKey(page)

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

    internal fun handleAutoTranslation(currentPage: ReaderPage) {
        if (autoTranslationScrollJob?.isActive == true) {
            val prevTarget = pendingDebouncePage ?: -1
            val elapsedMs = (System.nanoTime() - pendingDebounceStartNs) / 1_000_000.0
            AutoSmoothnessTelemetry.logDebounceLifecycle(
                action = "cancel",
                targetPage = prevTarget,
                queuedDurationMs = elapsedMs,
            )
            autoTranslationScrollJob?.cancel()
        }
        val targetPage = currentPage.index
        val startNs = System.nanoTime()
        pendingDebouncePage = targetPage
        pendingDebounceStartNs = startNs
        AutoSmoothnessTelemetry.logDebounceLifecycle(
            action = "start",
            targetPage = targetPage,
            queuedDurationMs = 0.0,
        )
        autoTranslationScrollJob = viewModelScope.launchIO {
            kotlinx.coroutines.delay(150L)
            if (state.value.viewerChapters?.currChapter !== currentPage.chapter) {
                val cancelDurationMs = (System.nanoTime() - startNs) / 1_000_000.0
                AutoSmoothnessTelemetry.logDebounceLifecycle(
                    action = "cancel",
                    targetPage = targetPage,
                    queuedDurationMs = cancelDurationMs,
                )
                return@launchIO
            }
            val fireDurationMs = (System.nanoTime() - startNs) / 1_000_000.0
            AutoSmoothnessTelemetry.logDebounceLifecycle(
                action = "fire",
                targetPage = targetPage,
                queuedDurationMs = fireDurationMs,
            )
            handleAutoTranslationOnIo(currentPage)
        }
    }

    private suspend fun handleAutoTranslationOnIo(currentPage: ReaderPage) {
        val chapterId = currentPage.chapter.chapter.id ?: return
        val manga = manga ?: return
        val chapter = currentPage.chapter.chapter
        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        val pages = currentPage.chapter.pages ?: return
        val currentIndex = pages.indexOfFirst { it === currentPage }
        if (currentIndex < 0) return
        val domainChapter = chapter.toDomainChapter() ?: return
        val session = translationManager.openTranslationSession(manga, domainChapter, source) ?: return
        val computeClass = currentTranslatorComputeClass()
        val identity = AutoChapterIdentity(
            chapterId = chapterId,
            sessionKey = "$autoReaderSessionKey:${session.key}:${computeClass.name}",
        )
        val configuredAheadTarget = translationPreferences.autoTranslatePrefetchCount().get().coerceIn(1, 5)

        logcat(LogPriority.INFO) {
            "TachiyomiAT auto-translate window: visible=$currentIndex " +
                "ahead=$configuredAheadTarget pageCount=${pages.size} pageKey=${resolvePageKey(currentPage)}"
        }

        val ownerVersionHint = synchronized(autoSnapshotFenceLock) {
            acceptedAutoOwnerVersion.takeIf {
                activeAutoIdentity == identity && activeAutoOwnerToken === session.store
            }
        }
        // Fence the previous collector before the scheduler publishes its synchronous anchor
        // snapshot. The stable manager flow may deliver the old value after this call returns.
        observeAutoSnapshot(identity, session.store, currentIndex)
        val pageResolver = autoPageResolver.bind(
            identity = identity,
            pages = pages,
            ownerVersion = ownerVersionHint,
            ownerToken = session.store,
        )
        translationManager.updateAutoWindow(
            identity = identity,
            visiblePageIndex = currentIndex,
            configuredAheadTarget = configuredAheadTarget,
            pageCount = pages.size,
            session = session,
            pageResolver = pageResolver,
            computeClass = computeClass,
        )
        bindAutoSourceReadiness(currentPage.chapter, identity)
        // Covers the narrow race where the loader published originalStream between the window
        // update and installing the lifecycle callback. The coordinator trigger is conflated.
        translationManager.reconcileAutoWindow()
    }

    /**
     * Installs one loader-level source-readiness callback shared by pager and webtoon holders.
     * Both holders call PageLoader.loadPage(page), and HttpPageLoader invokes this hook after
     * originalStream is assigned. Weak references avoid making a loader retain the ViewModel or
     * chapter while the callback is waiting for a late network page.
     */
    private fun bindAutoSourceReadiness(
        chapter: ReaderChapter,
        identity: AutoChapterIdentity,
    ) {
        synchronized(autoReadinessLock) {
            clearAutoSourceReadinessLocked()
            val pageLoader = chapter.pageLoader
            if (pageLoader != null) {
                val generation = autoReadinessGeneration.incrementAndGet()
                val readerGeneration = autoSnapshotGeneration.get()
                val controllerRef = WeakReference(this@ReaderTranslationController)
                val boundChapter = WeakReference(chapter)
                autoReadinessLoader = pageLoader
                pageLoader.onPageStreamReady = {
                    val controller = controllerRef.get()
                    val activeChapter = boundChapter.get()
                    if (controller != null && activeChapter != null) {
                        controller.onAutoSourceReady(activeChapter, identity, generation, readerGeneration)
                    }
                }
            }
        }
    }

    private fun onAutoSourceReady(
        chapter: ReaderChapter,
        identity: AutoChapterIdentity,
        readinessGeneration: Long,
        readerGeneration: Long,
    ) {
        synchronized(autoReadinessLock) {
            val isActive = autoReadinessLoader != null &&
                readinessGeneration == autoReadinessGeneration.get() &&
                shouldReconcileAutoSourceReady(
                    currentChapter = state.value.currentChapter,
                    boundChapter = chapter,
                    activeIdentity = activeAutoIdentity,
                    boundIdentity = identity,
                    activeGeneration = autoSnapshotGeneration.get(),
                    boundGeneration = readerGeneration,
                    translationEnabled = translationPreferences.translationEnabled().get(),
                    autoTranslate = translationPreferences.autoTranslate().get(),
                )
            if (isActive && !autoReadinessPokeInFlight) {
                autoReadinessPokeInFlight = true
                try {
                    // RollingAutoCoordinator's trigger is CONFLATED. This guard only prevents
                    // callback re-entry; it is reset after each synchronous reconcile so a later
                    // source transition is still allowed to wake the same active window.
                    translationManager.reconcileAutoWindow()
                } finally {
                    autoReadinessPokeInFlight = false
                }
            }
        }
    }

    private fun clearAutoSourceReadinessLocked() {
        autoReadinessGeneration.incrementAndGet()
        autoReadinessLoader?.onPageStreamReady = null
        autoReadinessLoader = null
        autoReadinessPokeInFlight = false
    }

    private fun clearAutoSourceReadiness() {
        synchronized(autoReadinessLock) {
            clearAutoSourceReadinessLocked()
        }
    }

    private fun currentTranslatorComputeClass(): TranslatorComputeClass =
        TranslatorComputeClass.forConfiguration(
            category = translationPreferences.translationEngineCategory().get(),
            standardEngineName = translationPreferences.translationStandardEngine().get().name,
            aiEngineName = translationPreferences.translationAiEngine().get().name,
        )

    private fun observeAutoSnapshot(
        identity: AutoChapterIdentity,
        ownerToken: Any?,
        expectedVisiblePageIndex: Int,
    ) {
        // The manager exposes the active coordinator's StateFlow. Rebind after
        // every window update because a batch/revision suppression can replace
        // that coordinator while keeping the same chapter identity.
        val generation = synchronized(autoSnapshotFenceLock) {
            if (activeAutoIdentity != identity) {
                // A new chapter's owner counter is independent of the old chapter's counter.
                // The collector generation/identity checks still fence any old flow emission.
                acceptedAutoOwnerVersion = null
                acceptedAutoWindowVersion = -1L
            }
            activeAutoIdentity = identity
            activeAutoOwnerToken = ownerToken
            autoSnapshotGeneration.incrementAndGet()
        }
        autoSnapshotJob?.cancel()
        autoSnapshotJob = viewModelScope.launch {
            var awaitingFreshSnapshot = true
            translationManager.autoSnapshot.collect { snapshot ->
                if (snapshot != null && snapshot.visiblePageIndex != expectedVisiblePageIndex) {
                    return@collect
                }
                val accepted = synchronized(autoSnapshotFenceLock) {
                    if (!isReaderAutoTranslationSnapshotVersionAccepted(
                            snapshot = snapshot,
                            expectedIdentity = identity,
                            acceptedOwnerVersion = acceptedAutoOwnerVersion,
                            acceptedWindowVersion = acceptedAutoWindowVersion,
                            requireStrictlyNew = awaitingFreshSnapshot,
                        )
                    ) {
                        false
                    } else {
                        if (snapshot != null &&
                            (
                                acceptedAutoOwnerVersion == null ||
                                    snapshot.ownerVersion > acceptedAutoOwnerVersion!! ||
                                    snapshot.windowVersion > acceptedAutoWindowVersion
                                )
                        ) {
                            acceptedAutoOwnerVersion = snapshot.ownerVersion
                            acceptedAutoWindowVersion = snapshot.windowVersion
                            autoPageResolver.bindOwnerVersion(identity, ownerToken, snapshot.ownerVersion)
                            awaitingFreshSnapshot = false
                        }
                        true
                    }
                }
                if (!accepted) return@collect
                val projected = projectReaderAutoTranslationUiState(snapshot, identity)
                mutableState.update { current ->
                    val active = synchronized(autoSnapshotFenceLock) {
                        generation == autoSnapshotGeneration.get() &&
                            activeAutoIdentity == identity &&
                            activeAutoOwnerToken === ownerToken
                    }
                    if (!active) {
                        current
                    } else if (current.autoTranslation == projected) {
                        current
                    } else {
                        current.copy(autoTranslation = projected)
                    }
                }
            }
        }
    }

    internal fun resetAutoTranslationState() {
        // Invalidate loader callbacks and resolver-held page/stream handles before any caller
        // cancels a coordinator or unreferences the chapter's loader resources.
        clearAutoSourceReadiness()
        autoPageResolver.invalidate()
        autoSnapshotGeneration.incrementAndGet()
        autoSnapshotJob?.cancel()
        autoSnapshotJob = null
        activeAutoIdentity = null
        activeAutoOwnerToken = null
        val empty = ReaderAutoTranslationUiState.empty()
        mutableState.update { current ->
            if (current.autoTranslation == empty) current else current.copy(autoTranslation = empty)
        }
    }

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

    internal suspend fun cancelTranslationForChapter(chapter: ReaderChapter) {
        val manga = manga ?: return
        val chapterId = chapter.chapter.id ?: return
        resetAutoTranslationState()
        // Chapter navigation must join reader-owned work before the old chapter's loader is
        // released by ViewerChapters.unref(). Batch/revision ownership remains manager-gated.
        translationManager.awaitReaderStop("chapter switch")
        streamRegistry.clearChapter(
            sourceId = manga.source,
            mangaId = manga.id,
            chapterId = chapterId,
        )
        // TachiyomiAT: clear the per-page translation fields on the departing
        // chapter's pages. The translator jobs are cancelled above and the
        // registered streams evicted, but the ReaderPage objects themselves keep
        // their translatedStream / translation / showTranslatedImage fields set.
        // Because InsertPage (in the pager viewer) wraps the SAME underlying
        // ReaderPage, a holder reused across the chapter boundary could briefly
        // render the old chapter's translated image in the new chapter's slot
        // during the cancel→re-subscribe window. Null these out so a stale
        // stream closure (which also captured page bitmaps) is released and any
        // holder still pointing at these pages falls back to the original image.
        chapter.pages?.forEach { page ->
            page.translatedStream = null
            page.translation = null
            page.showTranslatedImage = false
        }
        // TachiyomiAT: reset the merged translation state to neutral. Without
        // this, an ERROR (or TRANSLATING) from the chapter we're leaving stays
        // sticky: recomputeTranslationState() treats ERROR as terminal-wins, so
        // the bottom-bar icon stayed red (and auto/manual translation on the
        // NEXT chapter was masked by the stale error) even though the failing
        // chapter's work had just been cancelled. observeLiveTranslationStore()
        // re-subscribes to the new chapter's store and resets liveTranslationState,
        // but batchTranslationState (driven by the queued-translation statusFlow)
        // was never cleared here — so a chapter-1 batch ERROR bled into chapter 2.
        // Mirror the reset that "Stop all translation" and "disable translation"
        // already perform.
        batchTranslationState = Translation.State.NOT_TRANSLATED
        liveTranslationState = Translation.State.NOT_TRANSLATED
        recomputeTranslationState()
    }

    internal fun deleteCurrentChapterTranslation() {
        val manga = manga ?: return
        val chapter = getCurrentChapter()?.chapter ?: return
        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        // Delete tears down the manager/store asynchronously; invalidate the active resolver and
        // collector before any manager cancellation can retain the old page resources.
        runAfterReaderAutoReset(::resetAutoTranslationState) {
            // TachiyomiAT: delete is now SUSPEND and fully tears down (cancels +
            // joins single/auto AND batch jobs, evicts + defuncts the shared store,
            // clears the reader stream registry, then deletes the on-disk file +
            // companion images) before returning. Split into two phases:
            //   - SYNCHRONOUS: clear the per-page translated fields + emit a refresh
            //     so holders fall back to the original image immediately and release
            //     captured translated-stream bitmaps instead of showing the
            //     about-to-be-deleted rendered image. This must reflect on the UI now.
            //   - ASYNC: run deleteTranslation, then re-subscribe + reset state ONLY
            //     after teardown completes. The previous code re-subscribed
            //     synchronously while delete's eviction still ran on a background
            //     coroutine, so the cache-first openOrCreateActiveChapterTranslationStoreSuspend
            //     could re-bind the reader to the about-to-be-evicted store while the
            //     translator wrote to a different instance — leaving the reader on
            //     ORIGINAL images with a stuck global spinner on delete-then-retranslate.
            getCurrentChapter()?.pages?.forEach { page ->
                page.translatedStream = null
                page.translation = null
                page.showTranslatedImage = false
                (page as? ReaderPage)?.translationToggled = false
            }

            val readerPages = getCurrentChapter()?.pages
                ?.filterIsInstance<ReaderPage>()
                ?.toSet()
                ?: emptySet()
            if (readerPages.isNotEmpty()) {
                eventChannel.trySend(ReaderViewModel.Event.RefreshTranslationPages(readerPages))
                mutableState.update { it.copy(translationRefreshToken = System.currentTimeMillis()) }
            }
        }

        viewModelScope.launchIO {
            translationManager.deleteTranslation(chapter.toDomainChapter()!!, manga, source)
            // Re-subscribe to the (now fresh) store AFTER teardown. deleteTranslation
            // has already evicted the old store, so openOrCreate... resolves a new
            // lazy store that the next translate also resolves — reader and
            // translator share the same instance and live updates flow again.
            observeLiveTranslationStore()
            batchTranslationState = Translation.State.NOT_TRANSLATED
            liveTranslationState = Translation.State.NOT_TRANSLATED
            mutableState.update { it.copy(translationState = Translation.State.NOT_TRANSLATED) }
            recomputeTranslationState()
        }
    }

    internal fun resetTranslationData(preserveEdits: Boolean) {
        val manga = manga ?: return
        val page = state.value.currentPage as? ReaderPage ?: return
        val chapter = page.chapter.chapter
        val source = sourceManager.get(manga.source) as? HttpSource ?: return

        page.translatedStream = null
        page.translation = null
        page.showTranslatedImage = false
        page.translationToggled = false

        val readerPages = setOf(page)
        eventChannel.trySend(ReaderViewModel.Event.RefreshTranslationPages(readerPages))
        mutableState.update { it.copy(translationRefreshToken = System.currentTimeMillis()) }

        viewModelScope.launchIO {
            val pageKey = resolvePageKey(page)
            translationManager.resetTranslationData(chapter.toDomainChapter()!!, manga, source, pageKey, preserveEdits)
            recomputeTranslationState()
        }
    }

    internal fun resetInpaintData() {
        val manga = manga ?: return
        val page = state.value.currentPage as? ReaderPage ?: return
        val chapter = page.chapter.chapter
        val source = sourceManager.get(manga.source) as? HttpSource ?: return

        page.translatedStream = null
        page.translation = null
        page.showTranslatedImage = false
        page.translationToggled = false

        val readerPages = setOf(page)
        eventChannel.trySend(ReaderViewModel.Event.RefreshTranslationPages(readerPages))
        mutableState.update { it.copy(translationRefreshToken = System.currentTimeMillis()) }

        viewModelScope.launchIO {
            val pageKey = resolvePageKey(page)
            translationManager.resetInpaintData(chapter.toDomainChapter()!!, manga, source, pageKey)
            recomputeTranslationState()
        }
    }

    internal fun resetOcrData() {
        val manga = manga ?: return
        val page = state.value.currentPage as? ReaderPage ?: return
        val chapter = page.chapter.chapter
        val source = sourceManager.get(manga.source) as? HttpSource ?: return

        page.translatedStream = null
        page.translation = null
        page.showTranslatedImage = false
        page.translationToggled = false

        val readerPages = setOf(page)
        eventChannel.trySend(ReaderViewModel.Event.RefreshTranslationPages(readerPages))
        mutableState.update { it.copy(translationRefreshToken = System.currentTimeMillis()) }

        viewModelScope.launchIO {
            val pageKey = resolvePageKey(page)
            translationManager.resetOcrData(chapter.toDomainChapter()!!, manga, source, pageKey)
            recomputeTranslationState()
        }
    }

    internal fun resetEverything() {
        resetOcrData()
    }

    internal fun translateSinglePage(page: ReaderPage, force: Boolean? = null) {
        val tapTimeNs = System.nanoTime()
        when (
            val admission = translationManager.sessionCoordinator.requestReaderSession(
                ReaderSessionIntent(chapterId = page.chapter.chapter.id),
            )
        ) {
            is SessionAdmission.Admitted,
            is SessionAdmission.Switched,
            -> translateSinglePageAfterAdmission(page, force, tapTimeNs)

            is SessionAdmission.Rejected -> when (admission.reason) {
                SessionRejection.BATCH_ACTIVE -> mutableState.update {
                    it.copy(dialog = ReaderViewModel.Dialog.BatchReaderSwitch(page = page, force = force))
                }

                SessionRejection.PAUSING_IN_PROGRESS -> {
                    Injekt.get<Application>().toast(ATMR.strings.reader_batch_switch_wait)
                }

                SessionRejection.READER_ACTIVE -> {
                    // The existing reader session owns this request; the
                    // scheduler's idempotent admission will continue it.
                    translateSinglePageAfterAdmission(page, force, tapTimeNs)
                }
            }
        }
    }

    /**
     * Completes the batch-to-reader handoff from the controller so the session
     * admission seam remains alongside the manual translation entry point.
     */
    internal fun confirmBatchReaderSwitch(request: ReaderViewModel.Dialog.BatchReaderSwitch) {
        mutableState.update { it.copy(dialog = null) }
        viewModelScope.launchIO {
            when (val admission = translationManager.switchReaderSession(request.page.chapter.chapter.id)) {
                is SessionAdmission.Admitted,
                is SessionAdmission.Switched,
                -> translateSinglePage(request.page, request.force)

                is SessionAdmission.Rejected -> {
                    withUIContext {
                        val message = when (admission.reason) {
                            SessionRejection.PAUSING_IN_PROGRESS -> ATMR.strings.reader_batch_switch_wait
                            SessionRejection.BATCH_ACTIVE -> ATMR.strings.reader_batch_switch_cancelled
                            SessionRejection.READER_ACTIVE -> ATMR.strings.reader_batch_switch_reader_active
                        }
                        Injekt.get<Application>().toast(message)
                    }
                }
            }
        }
    }

    private fun translateSinglePageAfterAdmission(
        page: ReaderPage,
        force: Boolean? = null,
        tapTimeNs: Long = System.nanoTime(),
    ) {
        val chapter = page.chapter.chapter
        val pageKey = resolvePageKey(page)
        val manga = manga ?: run {
            logcat(LogPriority.WARN) { "translateSinglePage: manga is null, cannot translate" }
            return
        }
        val source = sourceManager.get(manga.source) as? HttpSource ?: run {
            logcat(LogPriority.WARN) { "translateSinglePage: invalid HttpSource, cannot translate" }
            return
        }
        // TachiyomiAT: manual entry drops a stale DOWNLOAD_FAILED batch
        // request so its failed projection cannot shadow this manual work.
        chapter.id?.let(translationManager::clearStaleDownloadFailedRequest)
        // TachiyomiAT: resolve force from the page's live translation state.
        // A FAILED stage means the page is stuck (the "cannot reprocess /
        // retranslate" bug) and the retry MUST reset the bookkeeping via
        // prepareForcedRetry() — which only happens when force=true. For every
        // other state (PENDING/RUNNING/READY/PARTIAL/CANCELLED, or a healthy
        // rendered page), keep the resume default (force=false) so a manual tap
        // on an already-translated page resumes instead of burning a full
        // re-OCR + re-translate + re-inpaint + re-render. Callers may override
        // by passing an explicit force. See PageTranslation.prepareForcedRetry
        // + MAX_STAGE_RETRIES + hasExhaustedRetries.
        val effectiveForce = force ?: run {
            val t = page.translation
            t != null &&
                (
                    t.ocrStatus == "FAILED" ||
                        t.translationStatus == "FAILED" ||
                        t.inpaintStatus == "FAILED" ||
                        t.renderStatus == "FAILED"
                    )
        }
        logcat(LogPriority.INFO) {
            "TachiyomiAT manual translate page request: index=${page.index} pageKey=$pageKey " +
                "sourceFileName=${page.sourceFileName} imageUrl=${page.imageUrl} " +
                "loader=${page.chapter.pageLoader?.javaClass?.simpleName} force=$effectiveForce"
        }
        // TachiyomiAT: resolve a stream for the translator. A page is
        // translatable when ANY of these byte sources is available:
        //   1. page.originalStream  — already-cached/downloaded page bytes
        //   2. a downloaded chapter dir (translator opens the file directly)
        //   3. page.imageUrl        — lazy on-demand fetch from the source
        // Previously the guard below rejected (1)==null && (2)==false outright,
        // which is exactly the streamed-chapter case that (3) exists to cover —
        // so the lazy-HTTP fallback was unreachable dead code and tapping
        // translate on an online chapter silently did nothing ("Download the
        // chapter before translating"). Only bail when NONE of the three are
        // available. Check the page's own chapter (not getCurrentChapter()) so
        // the guard is correct when a cross-chapter transition fires before
        // loadNewChapter completes.
        val pageChapterDownloaded = downloadManager.isChapterDownloaded(
            chapter.name,
            chapter.scanlator,
            manga.title,
            manga.source,
        )
        if (page.originalStream == null && !pageChapterDownloaded && page.imageUrl == null) {
            logcat(LogPriority.WARN) {
                "translateSinglePage: no stream available for page $pageKey (originalStream=null, chapterDownloaded=$pageChapterDownloaded, imageUrl=${page.imageUrl != null})"
            }
            return
        }
        page.originalStream?.let { streamFn ->
            streamRegistry.register(
                manga,
                chapter.toDomainChapter()!!,
                source,
                pageKey,
                streamFn,
            )
            // TachiyomiAT: bytes already available — kick off translation now.
            // force is resolved from the page's live state: true when a stage is
            // FAILED (so prepareForcedRetry resets the bookkeeping and the page
            // can be reprocessed), false otherwise (resume optimization). See the
            // resolution above + PageTranslation.prepareForcedRetry.
            val delayMs = (System.nanoTime() - tapTimeNs) / 1_000_000.0
            NativeVisionTelemetry.logTapToDispatch(pageKey, delayMs)
            translationManager.translatePage(manga, chapter.toDomainChapter()!!, source, pageKey, force = effectiveForce)
        } ?: page.imageUrl?.let { imageUrl ->
            // TachiyomiAT: for online pages not yet cached (originalStream is
            // null), download the image bytes in a cancellable coroutine and
            // only THEN register the stream + start translation. Previously this
            // registered a runBlocking{ source.getImage() } closure, which blocked
            // an IO thread for the whole download and could not be cancelled on a
            // chapter switch. Now the download is suspend (cancellable) and the
            // translation is enqueued only after the bytes are in hand.
            viewModelScope.launchIO {
                val streamFn = try {
                    createLazyHttpStream(source, page, imageUrl)
                } catch (e: Throwable) {
                    logcat(LogPriority.WARN) {
                        "translateSinglePage: lazy download failed for $pageKey: ${e.message}"
                    }
                    return@launchIO
                }
                streamRegistry.register(
                    manga,
                    chapter.toDomainChapter()!!,
                    source,
                    pageKey,
                    streamFn,
                )
                val delayMs = (System.nanoTime() - tapTimeNs) / 1_000_000.0
                NativeVisionTelemetry.logTapToDispatch(pageKey, delayMs)
                translationManager.translatePage(manga, chapter.toDomainChapter()!!, source, pageKey, force = effectiveForce)
            }
        } ?: run {
            if (pageChapterDownloaded) {
                // Downloaded chapters may not expose a live ReaderPage.originalStream
                // after holder rebinding. The pipeline can reopen the page from disk,
                // so enqueue the job instead of silently falling through.
                logcat(LogPriority.INFO) {
                    "TachiyomiAT manual translate page request using downloaded chapter fallback: pageKey=$pageKey"
                }
                val delayMs = (System.nanoTime() - tapTimeNs) / 1_000_000.0
                NativeVisionTelemetry.logTapToDispatch(pageKey, delayMs)
                translationManager.translatePage(manga, chapter.toDomainChapter()!!, source, pageKey, force = effectiveForce)
            }
        }
    }

    /**
     * TachiyomiAT: downloads the page image from [source] and returns a stream
     * closure backed by the chapter disk cache. Used when
     * [ReaderPage.originalStream] is null (the page hasn't been cached yet by
     * HttpPageLoader) but the page has an [imageUrl].
     *
     * This is a [suspend] function (called from a tracked coroutine scope) so the
     * download is cancellable — if the reader switches chapters mid-download, the
     * coroutine is cancelled and the partially-downloaded bytes are dropped.
     * Previously this was a non-suspending closure whose body was `runBlocking {
     * source.getImage(...) }`; that blocked an IO dispatcher thread for the entire
     * download (thread-pool starvation) AND could not be cancelled, so a chapter
     * switch left the download running to completion against a recycled page.
     *
     * The bytes are downloaded eagerly, then stored in the chapter disk cache.
     */
    private suspend fun createLazyHttpStream(
        source: HttpSource,
        page: ReaderPage,
        imageUrl: String,
    ): () -> InputStream {
        val sPage = Page(page.index, page.url, imageUrl)
        if (!chapterCache.isImageInCache(imageUrl)) {
            val response = source.getImage(sPage)
            chapterCache.putImageToCache(imageUrl, response)
        }
        return { chapterCache.getImageFile(imageUrl).inputStream() }
    }

    /**
     * TachiyomiAT: cancels the in-flight translation job for a single [page].
     * Backs the per-page button's cancel affordance — lets the user stop one
     * slow/stuck page without abandoning the whole chapter. No-op if nothing is
     * running for that page.
     */
    internal fun cancelSinglePageTranslation(page: ReaderPage) {
        // TachiyomiAT: use the page's own chapter ID so the cancel lands on
        // the correct activePageJobs key — otherwise the cancel would be a
        // no-op for a page whose chapter differs from getCurrentChapter().
        val chapterId = page.chapter.chapter.id ?: return
        val pageKey = resolvePageKey(page)
        translationManager.cancelPageTranslation(chapterId, pageKey)
    }

    /**
     * TachiyomiAT: cancels ALL in-flight translation work (single-page, auto and
     * batch), stops the translator engines, and resets the merged translation
     * state to neutral. Backs the "Stop all translation" control in the reader
     * — previously there was no way for the user to stop translation at all
     * short of navigating away or disabling the master toggle.
     */
    internal fun stopAllTranslation() {
        resetAutoTranslationState()
        batchTranslationState = Translation.State.NOT_TRANSLATED
        liveTranslationState = Translation.State.NOT_TRANSLATED
        recomputeTranslationState()
        viewModelScope.launchIO {
            translationManager.cancelAllPageTranslationsOffMain(
                cancelBatchQueue = true,
                reason = "Manual stop requested from reader",
            )
            // TachiyomiAT: closeEngines = true so the cached textTranslator /
            // recognitionEngine are torn down and enginesClosed is set. Without this,
            // any config change made after stopping (engine, provider, API key, model,
            // OCR, language) was ignored on the next run — the rebuild gate never
            // fired because the old engine instances stayed cached.
            translationManager.translatorStop("user stop", closeEngines = true)
            // TachiyomiAT: evict all registered reader page streams so their captured
            // ReaderPage / ByteArray references are released. Without this, the
            // process-lifetime readerPageStreams map keeps page bytes alive after the
            // user explicitly stops translation.
            streamRegistry.clearAll()
        }
    }

    /** Re-arms the current durable batch; provider cooldowns are enforced by the manager. */
    internal fun retryCurrentBatch(force: Boolean = false) {
        val chapterId = getCurrentChapter()?.chapter?.id ?: return
        viewModelScope.launchIO {
            val resumed = translationManager.requeueTranslation(chapterId, force)
            if (resumed) {
                translationManager.startTranslation()
            } else {
                withUIContext {
                    Injekt.get<Application>().toast(
                        "Translation is still paused; retry when the provider cooldown expires",
                    )
                }
            }
        }
    }

    internal fun setTranslationEnabled(enabled: Boolean) {
        translationPreferences.translationEnabled().set(enabled)
    }

    internal fun setAutoTranslate(auto: Boolean) {
        translationPreferences.autoTranslate().set(auto)
    }

    internal fun setAutoTranslatePrefetchCount(count: Int) {
        translationPreferences.autoTranslatePrefetchCount().set(count)
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
        // Foreground resume is a real lifecycle/memory-recovery signal for a
        // fully Deferred(Memory) rolling window; it must not wait for a page
        // change before asking the coordinator to reconcile.
        translationManager.reconcileAutoWindow()
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
        translationManager.reconcileAutoWindow()
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

    internal fun observeTranslationState() {
        // TachiyomiAT: cancel any prior collector first. loadChapter() calls
        // this, so without cancelling

        // each chapter change stacked another statusFlow().launchIn(viewModelScope)
        // collector (the translationStateJob field was declared but never
        // assigned). They filtered by a captured chapterId and so no-op'd for
        // old chapters, but accumulated for the life of the ViewModel.
        translationStateJob?.cancel()
        val chapterId = getCurrentChapter()?.chapter?.id ?: return
        translationStateJob = translationManager.statusFlow()
            .filterNotNull()
            .onEach { translation ->
                if (translation.chapter.id == chapterId) {
                    // Merge the batch-queue status with live (per-page/auto)
                    // status instead of overwriting it. Overwriting here would
                    // erase a TRANSLATING signal coming from per-page work.
                    batchTranslationState = translation.status
                    recomputeTranslationState()
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * TachiyomiAT: heals pages left stranded in a non-terminal (RUNNING/PENDING)
     * status by a prior crash, an OOM-kill, or the native-code permit leak.
     * Such a page otherwise keeps the reader's liveTranslationState pinned at
     * TRANSLATING forever (spinner never stops, translate icon disabled,
     * auto-translate never retries it). Any non-terminal entry older than the
     * per-page timeout is treated as abandoned and flipped to FAILED. Never
     * touches a page that already produced a result (rendered/cleaned) or
     * already reached FAILED. Idempotent.
     */
    private suspend fun sweepStrandedPageStatus(store: ChapterTranslationStore, chapterId: Long) {
        if (translationManager.isChapterBatchActive(chapterId, includePaused = true)) {
            return
        }
        val currentGen = store.currentGeneration
        val now = System.currentTimeMillis()
        val staleAfterMs = TranslationPipeline.SINGLE_PAGE_TIMEOUT_MS
        val snapshot = store.state.value
        for ((pageKey, pt) in snapshot) {
            if (pt.runGeneration == currentGen) continue
            val isTerminal = pt.ocrStatus == StageStatus.FAILED ||
                pt.inpaintStatus == StageStatus.FAILED ||
                pt.translationStatus == StageStatus.FAILED ||
                pt.renderStatus == StageStatus.FAILED ||
                pt.displayImageName != null ||
                //  no-render batch design: a page whose cleaned image is
                // published and current already produced its durable result —
                // the batch leaves renderStatus PENDING forever (overlay is
                // drawn on demand), and treating that as stranded healed healthy
                // pages one by one (2026-09-16 field report).
                pt.isCleanedImageReady ||
                pt.isTextlessTerminal
            if (isTerminal) continue
            val isNonTerminal = pt.ocrStatus == StageStatus.RUNNING ||
                pt.ocrStatus == StageStatus.PENDING ||
                pt.inpaintStatus == StageStatus.RUNNING ||
                pt.inpaintStatus == StageStatus.PENDING ||
                pt.translationStatus == StageStatus.RUNNING ||
                pt.translationStatus == StageStatus.PENDING ||
                pt.renderStatus == StageStatus.RUNNING ||
                pt.renderStatus == StageStatus.PENDING
            if (!isNonTerminal) continue
            val age = now - pt.updatedAt
            if (age < staleAfterMs) continue
            logcat(LogPriority.WARN) {
                "TachiyomiAT stranded-page sweep: healing $pageKey " +
                    "(ocr=${pt.ocrStatus} inpaint=${pt.inpaintStatus} age=${age / 1000}s)"
            }
            //   the sweep is reader-side AUTOMATIC maintenance — its
            // lease is AUTO (never preempts; a reader tap evicts it).
            val repaired = withStrandedSweepTrace(chapterId, pageKey) {
                val lease = TranslationTrace.withLeaseWait(
                    site = TranslationTraceSite.READER_STRANDED_SWEEP,
                    leaseKind = TranslationTraceLeaseKind.OCR,
                ) {
                    store.tryAcquirePageStageLease(pageKey, PageStage.Ocr, PageWriteOrigin.AUTO)
                }
                if (lease !is LeaseAcquisition.Granted) return@withStrandedSweepTrace false
                try {
                    store.updatePageFromCurrentSnapshot(pageKey, "reader stranded-page sweep") { existing ->
                        val safe = existing ?: return@updatePageFromCurrentSnapshot pt.toDraft()
                        if (safe.runGeneration != store.currentGeneration) return@updatePageFromCurrentSnapshot safe
                        val safeTerminal = safe.ocrStatus == StageStatus.FAILED ||
                            safe.inpaintStatus == StageStatus.FAILED ||
                            safe.translationStatus == StageStatus.FAILED ||
                            safe.renderStatus == StageStatus.FAILED ||
                            safe.displayImageName != null ||
                            safe.isCleanedImageReady ||
                            safe.isTextlessTerminal
                        if (safeTerminal) return@updatePageFromCurrentSnapshot safe
                        safe.apply {
                            if (ocrStatus == StageStatus.RUNNING || ocrStatus == StageStatus.PENDING) {
                                ocrStatus = StageStatus.CANCELLED
                            }
                            if (inpaintStatus == StageStatus.RUNNING || inpaintStatus == StageStatus.PENDING) {
                                inpaintStatus = StageStatus.CANCELLED
                            }
                            if (translationStatus == StageStatus.RUNNING || translationStatus == StageStatus.PENDING) {
                                translationStatus = StageStatus.CANCELLED
                            }
                            if (renderStatus == StageStatus.RUNNING || renderStatus == StageStatus.PENDING) {
                                renderStatus = StageStatus.CANCELLED
                            }
                            errorMessage = activeError ?: "Page was stranded mid-translation; reset as cancelled on chapter reopen"
                            updatedAt = System.currentTimeMillis()
                        }
                    }
                } finally {
                    store.releasePageStageLease(pageKey, PageWriteOrigin.AUTO)
                }
                true
            }
            if (!repaired) continue
        }
    }

    private suspend fun <T> withStrandedSweepTrace(
        chapterId: Long,
        pageKey: String,
        block: suspend () -> T,
    ): T {
        if (!TranslationPipelineDiagnostics.detailedTracingEnabled) return block()
        val schedule = TranslationPipelineDiagnostics.startSchedule(
            mode = TranslationTraceMode.AUTO,
            origin = TranslationTraceMode.AUTO,
            chapterRaw = chapterId.toString(),
            pages = 1,
        )
        val run = TranslationPipelineDiagnostics.startRun(
            schedule = schedule,
            pageRaw = pageKey,
            plan = TranslationTracePlan.RESUME,
        )
        var outcome = TranslationTraceOutcome.SUCCESS
        return try {
            withContext(TranslationTrace.elementFor(run)) { block() }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            outcome = TranslationTraceOutcome.CANCELLED
            throw cancelled
        } catch (failure: Throwable) {
            outcome = TranslationTraceOutcome.FAILURE
            throw failure
        } finally {
            run.end(outcome)
            schedule.end(outcome)
        }
    }

    internal suspend fun observeLiveTranslationStore() {
        translationStoreJob?.cancel()
        translationBatchProgressJob?.cancel()
        val manga = manga ?: return
        val chapter = getCurrentChapter()?.chapter ?: return
        // A collector belongs to one reader chapter. Clear only when binding a
        // different chapter; re-entry keeps the last shared snapshot visible
        // while its collector reconnects, so lifecycle transitions do not hide
        // a running or paused batch.
        if (state.value.translationBatchProgress?.chapterId != chapter.id) {
            mutableState.update { it.copy(translationBatchProgress = null) }
        }
        val chapterId = chapter.id ?: return
        // Subscribe to the manager projection before opening the chapter store. A pending
        // request (for example, one waiting for a download) has no store yet, but it still
        // needs to be visible in the reader and survive an enter/exit cycle.
        translationBatchProgressJob = viewModelScope.launchIO {
            translationManager.observeBatchProgress(chapterId).collect { progress ->
                mutableState.update { it.copy(translationBatchProgress = progress) }
            }
        }
        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        // TachiyomiAT: use openOrCreate... instead of openActive.... The latter
        // returns null and bails out when no translation file exists yet (a
        // fresh chapter). That left the reader subscribed to *nothing* while the
        // translator wrote to a *different* store instance it created via its
        // own activeStoreResolver — so the very first translate click showed no
        // overlay/dim/spinner even though the pipeline ran. openOrCreate...
        // returns the exact same shared instance the translator resolves to,
        // so the reader observes the RUNNING write live.
        // TachiyomiAT (ANR fix): suspend variant — a first open runs legacy
        // artifact migration with SAF I/O and must not runBlocking a
        // dispatcher thread while holding readers waiting on the same store.
        val storeStage = ReaderEntryTrace.begin("vm.openActiveStore", chapterId)
        val store = translationManager.openOrCreateActiveChapterTranslationStoreSuspend(
            chapterId,
            chapter.name,
            chapter.scanlator,
            manga.title,
            source,
            manga.id,
        )
        storeStage.end()
        if (store == null) return
        currentTranslationStore = store
        installPersistedLayoutChapterSource(store)
        val storeState = store.state
        translationStoreJob = viewModelScope.launchIO {
            // TachiyomiAT: heal stranded RUNNING/PENDING pages left behind by a
            // prior crash, an OOM-kill, or the native-code permit leak this build
            // fixes — BEFORE the first collect emission is processed. A page
            // stuck at ocrStatus=RUNNING with no result keeps the observer's
            // anyRunning=true forever (so liveTranslationState never leaves
            // TRANSLATING, the spinner never stops, the translate icon stays
            // disabled, and auto-translate's isRunning skip never re-enqueues
            // it). Any non-terminal entry older than the per-page timeout is
            // treated as abandoned and flipped to CANCELLED. Runs once per
            // chapter open. Never clobbers a page that produced a result or
            // reached FAILED.
            sweepStrandedPageStatus(store, chapterId)
            storeState.collect { pageMap ->
                val pages = state.value.viewerChapters?.currChapter?.pages ?: return@collect
                var translatedCount = 0
                val totalPages = pages.size
                // Track whether ANY page currently has a RUNNING stage or a
                // FAILED stage across this emission, so we can derive a
                // liveTranslationState that reflects per-page/auto work (the
                // batch-queue status alone never flips for these).
                var anyRunning = false
                var anyError = false
                // Lowest 1-based index of a page currently mid-translation. Used
                // for the "Translating page N of M" label so it tracks the page
                // the spinner animates on. 0 means no page is running.
                var runningPageIndex = 0
                for ((pageIndex, page) in pages.withIndex()) {
                    val readerPage = page as? ReaderPage ?: continue
                    val pageKey = resolvePageKey(readerPage)
                    val updated = pageMap[pageKey] ?: continue
                    if (updated.ocrStatus == eu.kanade.translation.model.StageStatus.RUNNING ||
                        updated.inpaintStatus == eu.kanade.translation.model.StageStatus.RUNNING ||
                        updated.translationStatus == eu.kanade.translation.model.StageStatus.RUNNING ||
                        updated.renderStatus == eu.kanade.translation.model.StageStatus.RUNNING
                    ) {
                        anyRunning = true
                        // Pages may be emitted out of order; keep the lowest
                        // running index for a stable label.
                        val oneBased = pageIndex + 1
                        if (runningPageIndex == 0 || oneBased < runningPageIndex) {
                            runningPageIndex = oneBased
                        }
                    }
                    // The committed display projection is the reader authority;
                    // the live candidate still drives stage/error feedback.
                    val resolvedDisplay = store.resolveDisplayPage(pageKey) ?: updated
                    val display = resolvedDisplay.toPageDisplayProjection()
                    val hasCleaned = display.displayReady
                    val isFailed = updated.ocrStatus == eu.kanade.translation.model.StageStatus.FAILED ||
                        updated.inpaintStatus == eu.kanade.translation.model.StageStatus.FAILED ||
                        updated.translationStatus == eu.kanade.translation.model.StageStatus.FAILED ||
                        updated.renderStatus == eu.kanade.translation.model.StageStatus.FAILED
                    if (isFailed && !hasCleaned) anyError = true
                    // TachiyomiAT: this per-page INFO log fires on every RUNNING
                    // stage of every page during a batch run — thousands of string
                    // interpolations + log dispatches on a busy chapter. Gate it
                    // behind the opt-in translation_diagnostics pref so the hot
                    // path stays quiet unless the user is actively debugging.
                    if (translationDiagnosticsEnabled &&
                        (
                            updated.ocrStatus == eu.kanade.translation.model.StageStatus.RUNNING ||
                                updated.inpaintStatus == eu.kanade.translation.model.StageStatus.RUNNING ||
                                updated.translationStatus == eu.kanade.translation.model.StageStatus.RUNNING ||
                                updated.renderStatus == eu.kanade.translation.model.StageStatus.RUNNING ||
                                updated.activeError != null
                            )
                    ) {
                        logcat(LogPriority.INFO) {
                            "TachiyomiAT live translation update: pageKey=$pageKey " +
                                "ocr=${updated.ocrStatus} inpaint=${updated.inpaintStatus} " +
                                "translate=${updated.translationStatus} render=${updated.renderStatus} " +
                                "cleaned=${updated.cleanedImageName} " +
                                "error=${updated.activeError}"
                        }
                    }
                    // TachiyomiAT (Phase 3): the reader page observes the
                    // committed display bundle when one exists — a candidate
                    // retry emission can replace the live entry but never
                    // nulls the committed translated page.
                    if (display.displayReady || display.isTextless) translatedCount++
                    if (isFailed && !display.displayReady && !display.isTextless) translatedCount++
                    readerPage.translation = resolvedDisplay
                }
                state.value.viewerChapters?.currChapter?.let { current ->
                    val targetIndex = if (chapterPageIndex >= 0) chapterPageIndex else current.requestedPage
                    updateTranslationWorkingSet(current, targetIndex, dispatchRefresh = false)
                }
                // Derive the live state from what we just observed, then merge
                // with the batch state. TRANSLATING wins; then ERROR; then
                // TRANSLATED only when every page has produced a result; else
                // NOT_TRANSLATED (so the icon goes back to neutral when idle).
                liveTranslationState = when {
                    anyRunning -> Translation.State.TRANSLATING
                    anyError -> Translation.State.ERROR
                    totalPages > 0 && translatedCount >= totalPages -> Translation.State.TRANSLATED
                    else -> Translation.State.NOT_TRANSLATED
                }
                recomputeTranslationState()
                // TachiyomiAT: only push a State update when the progress OR the
                // currently-running page index actually changed. The old code used
                // System.currentTimeMillis() as a refresh token, which always
                // differs and forces Compose recomposition on every single
                // translation-stage emission (OCR → inpaint → translate → render
                // per page per prefetch), causing visible "blinking" on
                // auto-translate chapters.
                val newProgress = Pair(translatedCount, totalPages)
                val currentState = mutableState.value
                if (newProgress != currentState.translationProgress ||
                    runningPageIndex != currentState.translationCurrentPage
                ) {
                    mutableState.update {
                        it.copy(
                            translationProgress = newProgress,
                            translationCurrentPage = runningPageIndex,
                            translationRefreshToken = System.currentTimeMillis(),
                        )
                    }
                }
                // Holder-level observePageView(page) collectors consume page
                // image/status changes directly. This collector only keeps the
                // global translation state and ReaderPage snapshots current.
            }
        }
    }

    internal fun observePageView(page: ReaderPage): Flow<eu.kanade.translation.model.PageView>? {
        val manga = manga ?: run {
            logcat(LogPriority.WARN) { "[reader_translate_diag] observePageView BAIL: manga null (pageIdx=${page.index})" }
            return null
        }
        val chapter = page.chapter.chapter
        val chapterId = chapter.id ?: run {
            logcat(LogPriority.WARN) { "[reader_translate_diag] observePageView BAIL: chapterId null (pageIdx=${page.index})" }
            return null
        }
        val source = sourceManager.get(manga.source) as? HttpSource ?: run {
            logcat(LogPriority.WARN) { "[reader_translate_diag] observePageView BAIL: source null (pageIdx=${page.index})" }
            return null
        }
        val pageKey = resolvePageKey(page)
        // TachiyomiAT (diagnostic): log the lookup so a chapter-open capture can
        // name exactly why a pre-translated page shows its original image. Gated
        // behind translation_diagnostics (already a cached pref read) so there's
        // zero overhead in production. Remove once the display-path bug is fixed.
        val diag = translationDiagnosticsEnabled
        // TachiyomiAT (ANR fix): the store is resolved lazily inside this cold
        // flow on Dispatchers.IO. observePageView is called from page-holder
        // bind on the MAIN thread; the first open of a chapter store performs
        // legacy artifact migration with synchronous SAF binder I/O, so a
        // blocking resolution here froze RecyclerView layout and ANR'd the
        // reader on entry. Holders subscribe exactly as before; the first
        // emission simply arrives after the IO resolution completes.
        return flow {
            val store = translationManager.openOrCreateActiveChapterTranslationStoreSuspend(
                chapterId,
                chapter.name,
                chapter.scanlator,
                manga.title,
                source,
                manga.id,
            )
            if (store == null) {
                logcat(LogPriority.WARN) { "[reader_translate_diag] observePageView BAIL: store null (pageIdx=${page.index})" }
                return@flow
            }
            if (diag) {
                val storeSize = store.state.value.size
                val hasKey = store.state.value.containsKey(pageKey)
                val sampleKeys = store.state.value.keys.take(3)
                val entry = store.state.value[pageKey]
                logcat(LogPriority.INFO) {
                    "[reader_translate_diag] observePageView pageIdx=${page.index} pageKey=$pageKey " +
                        "sourceFileName=${page.sourceFileName} storeSize=$storeSize hasKey=$hasKey " +
                        "sampleKeys=$sampleKeys " +
                        "entryCleaned=${entry?.cleanedImageName} entryStatus=${entry?.let { "ocr=${it.ocrStatus} render=${it.renderStatus}" }}"
                }
            }
            // TachiyomiAT (Phase 3): the page view observes the store's committed
            // display projection. Each emission resolves to the immutable
            // committed display bundle when one exists (candidate retries never
            // null it) and to the live candidate entry otherwise, so the holder's
            // overlay binding can never flip a translated page back to the
            // original mid-refresh.
            emitAll(
                store.display
                    .map { pages -> pages[pageKey] }
                    .distinctUntilChanged(),
            )
        }
            .flowOn(Dispatchers.IO)
            .onEach { updated ->
                if (updated == null) {
                    if (diag) {
                        logcat(LogPriority.WARN) {
                            "[reader_translate_diag] store emitted NULL for pageKey=$pageKey (no matching entry)"
                        }
                    }
                    return@onEach
                }
                val tier2Finished = updated.displayImageName != null && page.translatedStream == null
                val wantsToShowOverlay = updated.toPageDisplayProjection().displayReady && !page.showTranslatedImage
                page.translation = updated
                attachTranslatedStreamIfWarm(page, manga, page.chapter, source)
                if (translationPreferences.translationEnabled().get()) {
                    if (tier2Finished || wantsToShowOverlay) {
                        page.showTranslatedImage = true
                        eventChannel.trySend(ReaderViewModel.Event.RefreshTranslationPages(setOf(page)))
                        NativeVisionTelemetry.logDisplayAttach(
                            pageKey = pageKey,
                            streamAttached = page.translatedStream != null,
                            showTranslatedImage = page.showTranslatedImage,
                            displayImageName = page.translation?.displayImageName != null,
                        )
                    }
                    if (updated.isTranslationDisplayReady) {
                        recordArrivalWait(page.index, pageKey)
                    }
                }
            }
            .map { it.toPageView() }
            .distinctUntilChanged()
    }

    /**
     * Stage 7: the persisted-layout reader install.
     * Installs the chapter hydration source on the process-wide
     * [PersistedLayoutReaderBridge] so Pager and Webtoon holder binds can
     * hydrate the page's durable draw plan ([PersistedLayoutHydrator] typed
     * outcomes) instead of running the async planner. EVERY non-Resolved
     * outcome (feature flag OFF, no pointer, corrupt, incompatible, lossy,
     * unsupported version) returns null from the source — the mandatory async
     * planner fallback; Manual/Auto behavior with the feature flag OFF is
     * byte-identical because nothing is installed. Plans are vector draw DTOs
     * only — the overlay keeps drawing text (R041, no rasterized output).
     */
    internal fun installPersistedLayoutChapterSource(store: ChapterTranslationStore) {
        if (!PersistedLayoutRuntime.flagEnabled()) {
            PersistedLayoutReaderBridge.installChapterSource(null)
            return
        }
        val artifact = store.artifactEngine
        if (artifact == null) {
            PersistedLayoutReaderBridge.installChapterSource(null)
            return
        }
        PersistedLayoutReaderBridge.installChapterSource(
            PersistedLayoutReaderBridge.PageKeyedSource { pageKey, blocks, width, height ->
                val manifest = store.artifactManifest ?: return@PageKeyedSource null
                val page = store.state.value[pageKey] ?: return@PageKeyedSource null
                if (page.imgWidth <= 0f || page.imgHeight <= 0f) return@PageKeyedSource null
                // Fresh manifest read per consult: the pointer/compat identity is
                // compared against the DURABLE state, never a stale snapshot.
                val currentManifest = store.artifactManifest ?: return@PageKeyedSource null
                val hydrated = PersistedLayoutHydrator(
                    resolve = { key ->
                        currentManifest.layoutPlans[key]?.let { pointer ->
                            PersistedLayoutHydrator.PersistedPlanRef(
                                pointer = pointer,
                                compatibilityFingerprint = currentManifest.pages[key]?.layout?.fingerprint,
                            )
                        }
                    },
                    readDocument = { pointer ->
                        artifact.readSidecarDocument(
                            pointer = pointer,
                            serializer = PageLayoutDrawPlan.serializer(),
                            currentSchemaVersion = PageLayoutDrawPlan.SCHEMA_VERSION,
                            expectedKind = PageLayoutDrawPlan.KIND,
                            schemaVersionOf = { it.schemaVersion },
                            kindOf = { it.kind },
                            isValid = { it.validationError() == null },
                        )
                    },
                    fontSha256 = { PersistedLayoutRuntime.productionFontSha256() },
                ).hydrate(
                    pageKey = pageKey,
                    blocks = blocks,
                    pageWidth = page.imgWidth,
                    pageHeight = page.imgHeight,
                    bindPageWidth = width,
                    bindPageHeight = height,
                    decodeSampleSize = page.decodeSampleSize,
                    expectedCompatibilityFingerprint = persistedLayoutExpectedFingerprint(page),
                )
                (hydrated as? HydratedLayout.Resolved)?.layouts
            },
        )
    }

    /** Reader-side FP-07 recompute for the stored-fingerprint comparison; null skips it. */
    private fun persistedLayoutExpectedFingerprint(page: PageTranslationView): String? {
        val fontDigest = PersistedLayoutRuntime.productionFontSha256() ?: return null
        return LayoutPlanPublication.compatibilityFingerprint(
            compatInputs = LayoutPlanPublication.CompatInputs(
                translationArtifactId = page.ocrArtifactId.orEmpty(),
                cleanedImageArtifactIdOrOriginalSourceId = page.cleanedImageName
                    ?: "original:${page.sourceFingerprint.orEmpty()}",
            ),
            fontAssetSha256 = fontDigest,
            decodeSampleSize = page.decodeSampleSize,
            pageWidth = page.imgWidth,
            pageHeight = page.imgHeight,
        )
    }
}
