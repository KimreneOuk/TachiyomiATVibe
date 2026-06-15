package eu.kanade.tachiyomi.ui.reader

import android.app.Application
import android.net.Uri
import androidx.annotation.IntRange
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.model.toDbChapter
import eu.kanade.domain.manga.interactor.SetMangaViewerFlags
import eu.kanade.domain.manga.model.readerOrientation
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.saver.Location
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.util.chapter.filterDownloaded
import eu.kanade.tachiyomi.util.chapter.removeDuplicates
import eu.kanade.tachiyomi.util.editCover
import eu.kanade.tachiyomi.util.lang.byteSize
import eu.kanade.tachiyomi.util.lang.takeBytes
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.cacheImageDir
import eu.kanade.translation.TranslationManager
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import tachiyomi.core.common.preference.toggle
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream
import java.time.Instant
import java.util.Date

/**
 * Presenter used by the activity to perform background operations.
 */
class ReaderViewModel @JvmOverloads constructor(
    private val savedState: SavedStateHandle,
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val imageSaver: ImageSaver = Injekt.get(),
    preferences: BasePreferences = Injekt.get(),
    val readerPreferences: ReaderPreferences = Injekt.get(),
    private val basePreferences: BasePreferences = Injekt.get(),
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
    private val trackPreferences: TrackPreferences = Injekt.get(),
    private val trackChapter: TrackChapter = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val getNextChapters: GetNextChapters = Injekt.get(),
    private val upsertHistory: UpsertHistory = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val setMangaViewerFlags: SetMangaViewerFlags = Injekt.get(),
    private val translationManager: TranslationManager = Injekt.get(),
    private val translationPreferences: tachiyomi.domain.translation.TranslationPreferences = Injekt.get(),
) : ViewModel() {

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    private val eventChannel = Channel<Event>()
    val eventFlow = eventChannel.receiveAsFlow()

    private var translationStoreJob: kotlinx.coroutines.Job? = null
    private var translationStateJob: kotlinx.coroutines.Job? = null

    // TachiyomiAT: the published [State.translationState] now reflects BOTH the
    // batch-queue status (batchTranslationState, driven by observeTranslationState)
    // and per-page/auto work (liveTranslationState, driven by
    // observeLiveTranslationStore). Previously only the batch queue was
    // observed, so the only path the UI actually triggers (per-page/auto) never
    // flipped the bottom-bar icon to TRANSLATING — making taps look like they
    // did nothing. [recomputeTranslationState] merges the two each time either
    // source changes.
    private var batchTranslationState = Translation.State.NOT_TRANSLATED
    private var liveTranslationState = Translation.State.NOT_TRANSLATED

    // TachiyomiAT: guard so recompute only fires [mutableState.update] when the
    // effective translation state actually changes. Without this, every single
    // stage-emission from the live store (OCR→inpaint→translate→render for
    // every auto-translated page) triggers a recompose, causing the per-page
    // button to rapidly toggle between translate and cancel affordances —
    // visible as "blinking" on non-downloaded chapters where pages stream
    // through translation stages at network speed.
    private var lastEffectiveTranslationState = Translation.State.NOT_TRANSLATED

    private fun recomputeTranslationState() {
        val effective = when {
            batchTranslationState == Translation.State.TRANSLATING ||
                liveTranslationState == Translation.State.TRANSLATING -> Translation.State.TRANSLATING
            batchTranslationState == Translation.State.ERROR ||
                liveTranslationState == Translation.State.ERROR -> Translation.State.ERROR
            liveTranslationState == Translation.State.TRANSLATED -> Translation.State.TRANSLATED
            else -> Translation.State.NOT_TRANSLATED
        }
        if (effective != lastEffectiveTranslationState) {
            lastEffectiveTranslationState = effective
            mutableState.update { it.copy(translationState = effective) }
        }
    }

    /**
     * The manga loaded in the reader. It can be null when instantiated for a short time.
     */
    val manga: Manga?
        get() = state.value.manga

    /**
     * The chapter id of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterId = savedState.get<Long>("chapter_id") ?: -1L
        set(value) {
            savedState["chapter_id"] = value
            field = value
        }

    /**
     * The visible page index of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterPageIndex = savedState.get<Int>("page_index") ?: -1
        set(value) {
            savedState["page_index"] = value
            field = value
        }

    /**
     * The chapter loader for the loaded manga. It'll be null until [manga] is set.
     */
    private var loader: ChapterLoader? = null

    /**
     * The time the chapter was started reading
     */
    private var chapterReadStartTime: Long? = null

    private var chapterToDownload: Download? = null

/**
     * Chapter list for the active manga. It's retrieved lazily and should be accessed for the first
     * time in a background thread to avoid blocking the UI.
     *
     * TachiyomiAT: the [runBlocking] call here must only execute from off-main.
     * The sole first-access path from [loadChapter] already runs inside
     * [withIOContext]; a future call site that touches [chapterList] from main
     * would ANR. A safer long-term fix is a full suspend conversion deferred to
     * a follow-up.
     */
    private val chapterList by lazy {
        val manga = manga!!
        val chapters = runBlocking { getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true) }

        val selectedChapter = chapters.find { it.id == chapterId }
            ?: error("Requested chapter of id $chapterId not found in chapter list")

        val chaptersForReader = when {
            (readerPreferences.skipRead().get() || readerPreferences.skipFiltered().get()) -> {
                val filteredChapters = chapters.filterNot {
                    when {
                        readerPreferences.skipRead().get() && it.read -> true
                        readerPreferences.skipFiltered().get() -> {
                            (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_READ && !it.read) ||
                                (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_UNREAD && it.read) ||
                                (
                                    manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_DOWNLOADED &&
                                        !downloadManager.isChapterDownloaded(
                                            it.name,
                                            it.scanlator,
                                            manga.title,
                                            manga.source,
                                        )
                                    ) ||
                                (
                                    manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_NOT_DOWNLOADED &&
                                        downloadManager.isChapterDownloaded(
                                            it.name,
                                            it.scanlator,
                                            manga.title,
                                            manga.source,
                                        )
                                    ) ||
                                (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_BOOKMARKED && !it.bookmark) ||
                                (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_NOT_BOOKMARKED && it.bookmark)
                        }
                        else -> false
                    }
                }

                if (filteredChapters.any { it.id == chapterId }) {
                    filteredChapters
                } else {
                    filteredChapters + listOf(selectedChapter)
                }
            }
            else -> chapters
        }

        chaptersForReader
            .sortedWith(getChapterSort(manga, sortDescending = false))
            .run {
                if (readerPreferences.skipDupe().get()) {
                    removeDuplicates(selectedChapter)
                } else {
                    this
                }
            }
            .run {
                if (basePreferences.downloadedOnly().get()) {
                    filterDownloaded(manga)
                } else {
                    this
                }
            }
            .map { it.toDbChapter() }
            .map(::ReaderChapter)
    }

    private val incognitoMode = preferences.incognitoMode().get()
    private val downloadAheadAmount = downloadPreferences.autoDownloadWhileReading().get()

    init {
        // To save state
        state.map { it.viewerChapters?.currChapter }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach { currentChapter ->
                if (chapterPageIndex >= 0) {
                    // Restore from SavedState
                    currentChapter.requestedPage = chapterPageIndex
                } else if (!currentChapter.chapter.read) {
                    currentChapter.requestedPage = currentChapter.chapter.last_page_read
                }
                chapterId = currentChapter.chapter.id!!
            }
            .launchIn(viewModelScope)

        translationPreferences.autoTranslate().changes()
            .filter { it && translationPreferences.translationEnabled().get() }
            .onEach { translateCurrentPageForAuto() }
            .launchIn(viewModelScope)

        // TachiyomiAT: when the master translation toggle flips, force every
        // visible page holder to re-run setImage() so the per-page translate
        // button appears/disappears immediately and the displayed image swaps
        // between original and translated. Without this, holders only pick up
        // the new value on the next scroll (translationEnabled is read once at
        // holder construction, matching the existing showTranslations pattern).
        // If Enable flips ON while Auto is already on, also kick off current-
        // page translation so the user doesn't have to toggle Auto separately.
        translationPreferences.translationEnabled().changes()
            .onEach { enabled ->
                refreshVisiblePages()
                if (!enabled) {
                    // User disabled translation: cancel everything in flight so
                    // no orphaned page jobs keep running (and keep holding the
                    // translator permit) after the per-page buttons disappear.
                    translationManager.cancelAllPageTranslations()
                    translationManager.translatorStop("translation disabled")
                    // Reset both halves of the merged state so the bottom-bar
                    // icon returns to neutral instead of staying stuck on
                    // TRANSLATING/ERROR after the work was just cancelled.
                    batchTranslationState = Translation.State.NOT_TRANSLATED
                    liveTranslationState = Translation.State.NOT_TRANSLATED
                    lastEffectiveTranslationState = Translation.State.NOT_TRANSLATED
                    recomputeTranslationState()
                } else if (translationPreferences.autoTranslate().get()) {
                    translateCurrentPageForAuto()
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Asks the viewer to re-render every laid-out page holder in the current
     * chapter. Used when a translation pref change should be reflected on
     * already-visible pages (e.g. the master Enable toggle).
     */
    private fun refreshVisiblePages() {
        val pages = getCurrentChapter()?.pages ?: return
        val readerPages = pages.filterIsInstance<ReaderPage>().toSet()
        if (readerPages.isNotEmpty()) {
            eventChannel.trySend(Event.RefreshTranslationPages(readerPages))
        }
    }

    override fun onCleared() {
        val currentChapters = state.value.viewerChapters
        if (currentChapters != null) {
            currentChapters.unref()
            chapterToDownload?.let {
                downloadManager.addDownloadsToStartOfQueue(listOf(it))
            }
        }
        // TachiyomiAT: stop all translation work when the reader is destroyed.
        // Without this, single-page jobs launched on the singleton
        // ChapterTranslator keep running (and keep holding its single
        // translatorPermit) after the reader closes, leaking work and memory
        // across reader sessions.
        translationStoreJob?.cancel()
        translationStateJob?.cancel()
        translationManager.cancelAllPageTranslations()
        translationManager.translatorStop("reader closed")
    }

    /**
     * Called when the user pressed the back button and is going to leave the reader. Used to
     * trigger deletion of the downloaded chapters.
     */
    fun onActivityFinish() {
        deletePendingChapters()
    }

    /**
     * Whether this presenter is initialized yet.
     */
    fun needsInit(): Boolean {
        return manga == null
    }

    /**
     * Initializes this presenter with the given [mangaId] and [initialChapterId]. This method will
     * fetch the manga from the database and initialize the initial chapter.
     */
    suspend fun init(mangaId: Long, initialChapterId: Long): Result<Boolean> {
        if (!needsInit()) return Result.success(true)
        return withIOContext {
            try {
                val manga = getManga.await(mangaId)
                if (manga != null) {
                    sourceManager.isInitialized.first { it }
                    mutableState.update { it.copy(manga = manga) }
                    if (chapterId == -1L) chapterId = initialChapterId

                    val context = Injekt.get<Application>()
                    val source = sourceManager.getOrStub(manga.source)
                    loader = ChapterLoader(context, downloadManager, downloadProvider, manga, source)

                    loadChapter(loader!!, chapterList.first { chapterId == it.chapter.id })
                    Result.success(true)
                } else {
                    // Unlikely but okay
                    Result.success(false)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                Result.failure(e)
            }
        }
    }

    /**
     * Loads the given [chapter] with this [loader] and updates the currently active chapters.
     * Callers must handle errors.
     */
    private suspend fun loadChapter(
        loader: ChapterLoader,
        chapter: ReaderChapter,
    ): ViewerChapters {
        // TachiyomiAT: if we're switching to a different chapter, cancel all
        // translation work belonging to the chapter we're leaving. Otherwise the
        // previous chapter's single-page jobs (running on the singleton
        // ChapterTranslator) keep holding its single `translatorPermit` and
        // starve the new chapter's work — appearing as "translate does nothing"
        // after navigating.
        val previousChapter = state.value.currentChapter
        if (previousChapter != null && previousChapter !== chapter) {
            cancelTranslationForChapter(previousChapter)
        }

        loader.loadChapter(chapter)

        val chapterPos = chapterList.indexOf(chapter)
        val newChapters = ViewerChapters(
            chapter,
            chapterList.getOrNull(chapterPos - 1),
            chapterList.getOrNull(chapterPos + 1),
        )

        withUIContext {
            mutableState.update {
                // Add new references first to avoid unnecessary recycling
                newChapters.ref()
                it.viewerChapters?.unref()

                chapterToDownload = cancelQueuedDownloads(newChapters.currChapter)

                val translationStatus = this@ReaderViewModel.manga?.let { m ->
                    val ch = newChapters.currChapter.chapter
                    translationManager.getChapterTranslationStatus(
                        ch.id!!, ch.name, ch.scanlator, m.title, m.source,
                    )
                } ?: Translation.State.NOT_TRANSLATED

                it.copy(
                    viewerChapters = newChapters,
                    bookmarked = newChapters.currChapter.chapter.bookmark,
                    translationState = translationStatus,
                )
            }
        }
        observeLiveTranslationStore()
        if (manga != null) observeTranslationState()
        return newChapters
    }

    /**
     * Called when the user changed to the given [chapter] when changing pages from the viewer.
     * It's used only to set this chapter as active.
     */
    private fun loadNewChapter(chapter: ReaderChapter) {
        val loader = loader ?: return

        viewModelScope.launchIO {
            logcat { "Loading ${chapter.chapter.url}" }

            flushReadTimer()
            restartReadTimer()

            try {
                loadChapter(loader, chapter)
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    /**
     * Called when the user is going to load the prev/next chapter through the toolbar buttons.
     */
    private suspend fun loadAdjacent(chapter: ReaderChapter) {
        val loader = loader ?: return

        logcat { "Loading adjacent ${chapter.chapter.url}" }

        mutableState.update { it.copy(isLoadingAdjacentChapter = true) }
        try {
            withIOContext {
                loadChapter(loader, chapter)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            logcat(LogPriority.ERROR, e)
        } finally {
            mutableState.update { it.copy(isLoadingAdjacentChapter = false) }
        }
    }

    /**
     * Called when the viewers decide it's a good time to preload a [chapter] and improve the UX so
     * that the user doesn't have to wait too long to continue reading.
     */
    suspend fun preload(chapter: ReaderChapter) {
        if (chapter.state is ReaderChapter.State.Loaded || chapter.state == ReaderChapter.State.Loading) {
            return
        }

        if (chapter.pageLoader?.isLocal == false) {
            val manga = manga ?: return
            val dbChapter = chapter.chapter
            val isDownloaded = downloadManager.isChapterDownloaded(
                dbChapter.name,
                dbChapter.scanlator,
                manga.title,
                manga.source,
                skipCache = true,
            )
            if (isDownloaded) {
                chapter.state = ReaderChapter.State.Wait
            }
        }

        if (chapter.state != ReaderChapter.State.Wait && chapter.state !is ReaderChapter.State.Error) {
            return
        }

        val loader = loader ?: return
        try {
            logcat { "Preloading ${chapter.chapter.url}" }
            loader.loadChapter(chapter)
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            return
        }
        eventChannel.trySend(Event.ReloadViewerChapters)
    }

    fun onViewerLoaded(viewer: Viewer?) {
        mutableState.update {
            it.copy(viewer = viewer)
        }
    }

    /**
     * Called every time a page changes on the reader. Used to mark the flag of chapters being
     * read, update tracking services, enqueue downloaded chapter deletion, and updating the active chapter if this
     * [page]'s chapter is different from the currently active.
     */
    fun onPageSelected(page: ReaderPage) {
        // InsertPage doesn't change page progress
        if (page is InsertPage) {
            return
        }

        val selectedChapter = page.chapter
        val pages = selectedChapter.pages ?: return

        // Save last page read and mark as read if needed
        viewModelScope.launchNonCancellable {
            updateChapterProgress(selectedChapter, page)
        }

        if (selectedChapter != getCurrentChapter()) {
            logcat { "Setting ${selectedChapter.chapter.url} as active" }
            loadNewChapter(selectedChapter)
        }

        val inDownloadRange = page.number.toDouble() / pages.size > 0.25
        if (inDownloadRange) {
            downloadNextChapters()
        }

        if (translationPreferences.translationEnabled().get() &&
            translationPreferences.autoTranslate().get()
        ) {
            try { java.io.File("/sdcard/at_diag.txt").appendText("AUTO_TRIGGERED page=${page.index}\n") } catch (_: Exception) {}
            handleAutoTranslation(page)
        }

        eventChannel.trySend(Event.PageChanged)
    }

    /**
     * Enqueues translation of the current page and the next [depth] pages
     * (within the current chapter) for background processing.
     *
     * Behaviour (per the refactor decisions):
     * - Additive only: never cancels prior enqueued work on page change.
     * - Clamped to the current chapter: no cross-chapter lookahead.
     * - Skips pages that already have a rendered/cleaned translation.
     */
    private fun handleAutoTranslation(currentPage: ReaderPage) {
        // No isCurrentChapterDownloaded() guard: the manual translateSinglePage
        // path also has none, and the translator writes a FAILED status when
        // chapter files are missing instead of crashing. Matching the manual
        // path keeps auto-mode usable for streamed and freshly-opened chapters.
        val manga = manga ?: return
        // TachiyomiAT: use the page's own chapter context, not getCurrentChapter().
        // When a cross-chapter page transition fires via onPageSelected, the
        // global current-chapter may still point to the old chapter while page
        // belongs to the new one (loadNewChapter is async). Translating against
        // the wrong chapter ID writes page results under the wrong store key,
        // which is the root cause of "pages appear to skip during auto-translate".
        val chapter = currentPage.chapter.chapter
        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        val pages = currentPage.chapter.pages ?: return

        val currentIndex = pages.indexOfFirst { it === currentPage }
        if (currentIndex < 0) return

        val depth = translationPreferences.autoTranslatePrefetchCount().get().coerceIn(1, 5)
        val lastIndex = (currentIndex + depth).coerceAtMost(pages.lastIndex)

        logcat(LogPriority.INFO) {
            "TachiyomiAT auto-translate enqueue: currentIndex=$currentIndex lastIndex=$lastIndex " +
                "depth=$depth pageKey=${resolvePageKey(currentPage)} loader=${currentPage.chapter.pageLoader?.javaClass?.simpleName}"
        }

        for (i in currentIndex..lastIndex) {
            val readerPage = pages[i] as? ReaderPage ?: continue
            // TachiyomiAT: skip pages that don't need (re)translation. Previously
            // this only skipped pages with a rendered/cleaned result, so pages
            // that were in-flight (RUNNING) or had already finished with an
            // error (FAILED) got re-enqueued on EVERY page change — causing the
            // translator to cancel and restart them repeatedly, which manifested
            // as visible blinking/flicker of the processing overlay with no new
            // results appearing. Now we skip a page when:
            //  - it has a rendered or cleaned result (done), OR
            //  - any stage is RUNNING (already in-flight), OR
            //  - any stage is FAILED (already attempted — don't retry in auto)
            val t = readerPage.translation
            if (t != null) {
                val hasResult = t.renderedImageName != null || t.cleanedImageName != null
                val isRunning = t.ocrStatus == StageStatus.RUNNING ||
                    t.inpaintStatus == StageStatus.RUNNING ||
                    t.translationStatus == StageStatus.RUNNING ||
                    t.renderStatus == StageStatus.RUNNING
                val isFailed = t.ocrStatus == StageStatus.FAILED ||
                    t.inpaintStatus == StageStatus.FAILED
                if (hasResult || isRunning || isFailed) continue
            }

            val pageKey = resolvePageKey(readerPage)
            logcat(LogPriority.INFO) {
                "TachiyomiAT auto-translate page request: index=${readerPage.index} pageKey=$pageKey " +
                    "sourceFileName=${readerPage.sourceFileName} imageUrl=${readerPage.imageUrl}"
            }
            // TachiyomiAT: register a stream for the translator. Prefer the
            // reader's originalStream when available (already-cached pages).
            // For online pages not yet downloaded into the chapter cache,
            // register a LAZY stream that fetches the image from the source
            // on demand when the translator actually needs the bytes. Without
            // this, lookahead pages (which haven't been viewed/cached yet)
            // have originalStream==null, the translator falls through to
            // findChapterDir()==null and writes a FAILED placeholder — so
            // auto-translate silently does nothing for uncached pages.
            val streamFn = readerPage.originalStream
                ?: readerPage.imageUrl?.let { imageUrl ->
                    createLazyHttpStream(source, readerPage, imageUrl)
                }
            streamFn?.let {
                eu.kanade.translation.ChapterTranslator.registerReaderPageStream(
                    manga,
                    chapter.toDomainChapter()!!,
                    source,
                    pageKey,
                    it,
                )
            }
            viewModelScope.launchIO {
                translationManager.translatePage(
                    manga,
                    chapter.toDomainChapter()!!,
                    source,
                    pageKey,
                )
            }
        }
    }

    /**
     * Translates just the page the user is currently viewing. Used when the
     * Auto toggle is switched ON so the current page starts processing
     * immediately, instead of waiting for the next page-change event.
     */
    private fun translateCurrentPageForAuto() {
        val pages = getCurrentChapter()?.pages ?: return
        val page = pages.getOrNull(chapterPageIndex) as? ReaderPage ?: return
        handleAutoTranslation(page)
    }

    private fun downloadNextChapters() {
        if (downloadAheadAmount == 0) return
        val manga = manga ?: return

        // Only download ahead if current + next chapter is already downloaded too to avoid jank
        if (getCurrentChapter()?.pageLoader !is DownloadPageLoader) return
        val nextChapter = state.value.viewerChapters?.nextChapter?.chapter ?: return

        viewModelScope.launchIO {
            val isNextChapterDownloaded = downloadManager.isChapterDownloaded(
                nextChapter.name,
                nextChapter.scanlator,
                manga.title,
                manga.source,
            )
            if (!isNextChapterDownloaded) return@launchIO

            val chaptersToDownload = getNextChapters.await(manga.id, nextChapter.id!!).run {
                if (readerPreferences.skipDupe().get()) {
                    removeDuplicates(nextChapter.toDomainChapter()!!)
                } else {
                    this
                }
            }.take(downloadAheadAmount)

            downloadManager.downloadChapters(
                manga,
                chaptersToDownload,
            )
        }
    }

    /**
     * Removes [currentChapter] from download queue
     * if setting is enabled and [currentChapter] is queued for download
     */
    private fun cancelQueuedDownloads(currentChapter: ReaderChapter): Download? {
        return downloadManager.getQueuedDownloadOrNull(currentChapter.chapter.id!!.toLong())?.also {
            downloadManager.cancelQueuedDownloads(listOf(it))
        }
    }

    /**
     * Determines if deleting option is enabled and nth to last chapter actually exists.
     * If both conditions are satisfied enqueues chapter for delete
     * @param currentChapter current chapter, which is going to be marked as read.
     */
    private fun deleteChapterIfNeeded(currentChapter: ReaderChapter) {
        val removeAfterReadSlots = downloadPreferences.removeAfterReadSlots().get()
        if (removeAfterReadSlots == -1) return

        // Determine which chapter should be deleted and enqueue
        val currentChapterPosition = chapterList.indexOf(currentChapter)
        val chapterToDelete = chapterList.getOrNull(currentChapterPosition - removeAfterReadSlots)

        // If chapter is completely read, no need to download it
        chapterToDownload = null

        if (chapterToDelete != null) {
            enqueueDeleteReadChapters(chapterToDelete)
        }
    }

    /**
     * Saves the chapter progress (last read page and whether it's read)
     * if incognito mode isn't on.
     */
    private suspend fun updateChapterProgress(readerChapter: ReaderChapter, page: Page) {
        val pageIndex = page.index

        mutableState.update {
            it.copy(currentPage = pageIndex + 1)
        }
        readerChapter.requestedPage = pageIndex
        chapterPageIndex = pageIndex

        if (!incognitoMode && page.status != Page.State.ERROR) {
            readerChapter.chapter.last_page_read = pageIndex

            if (readerChapter.pages?.lastIndex == pageIndex) {
                readerChapter.chapter.read = true
                updateTrackChapterRead(readerChapter)
                deleteChapterIfNeeded(readerChapter)
            }

            updateChapter.await(
                ChapterUpdate(
                    id = readerChapter.chapter.id!!,
                    read = readerChapter.chapter.read,
                    lastPageRead = readerChapter.chapter.last_page_read.toLong(),
                ),
            )
        }
    }

    fun restartReadTimer() {
        chapterReadStartTime = Instant.now().toEpochMilli()
    }

    fun flushReadTimer() {
        getCurrentChapter()?.let {
            viewModelScope.launchNonCancellable {
                updateHistory(it)
            }
        }
    }

    /**
     * Saves the chapter last read history if incognito mode isn't on.
     */
    private suspend fun updateHistory(readerChapter: ReaderChapter) {
        if (incognitoMode) return

        val chapterId = readerChapter.chapter.id!!
        val endTime = Date()
        val sessionReadDuration = chapterReadStartTime?.let { endTime.time - it } ?: 0

        upsertHistory.await(HistoryUpdate(chapterId, endTime, sessionReadDuration))
        chapterReadStartTime = null
    }

    /**
     * Called from the activity to load and set the next chapter as active.
     */
    suspend fun loadNextChapter() {
        val nextChapter = state.value.viewerChapters?.nextChapter ?: return
        loadAdjacent(nextChapter)
    }

    /**
     * Called from the activity to load and set the previous chapter as active.
     */
    suspend fun loadPreviousChapter() {
        val prevChapter = state.value.viewerChapters?.prevChapter ?: return
        loadAdjacent(prevChapter)
    }

    /**
     * Returns the currently active chapter.
     */
    private fun getCurrentChapter(): ReaderChapter? {
        return state.value.currentChapter
    }

    fun getSource() = manga?.source?.let { sourceManager.getOrStub(it) } as? HttpSource

    fun getChapterUrl(): String? {
        val sChapter = getCurrentChapter()?.chapter ?: return null
        val source = getSource() ?: return null

        return try {
            source.getChapterUrl(sChapter)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            null
        }
    }

    /**
     * Bookmarks the currently active chapter.
     */
    fun toggleChapterBookmark() {
        val chapter = getCurrentChapter()?.chapter ?: return
        val bookmarked = !chapter.bookmark
        chapter.bookmark = bookmarked

        viewModelScope.launchNonCancellable {
            updateChapter.await(
                ChapterUpdate(
                    id = chapter.id!!.toLong(),
                    bookmark = bookmarked,
                ),
            )
        }

        mutableState.update {
            it.copy(
                bookmarked = bookmarked,
            )
        }
    }

    /**
     * Returns the viewer position used by this manga or the default one.
     */
    fun getMangaReadingMode(resolveDefault: Boolean = true): Int {
        val default = readerPreferences.defaultReadingMode().get()
        val readingMode = ReadingMode.fromPreference(manga?.readingMode?.toInt())
        return when {
            resolveDefault && readingMode == ReadingMode.DEFAULT -> default
            else -> manga?.readingMode?.toInt() ?: default
        }
    }

    /**
     * Updates the viewer position for the open manga.
     */
    fun setMangaReadingMode(readingMode: ReadingMode) {
        val manga = manga ?: return
        runBlocking(Dispatchers.IO) {
            setMangaViewerFlags.awaitSetReadingMode(manga.id, readingMode.flagValue.toLong())
            val currChapters = state.value.viewerChapters
            if (currChapters != null) {
                // Save current page
                val currChapter = currChapters.currChapter
                currChapter.requestedPage = currChapter.chapter.last_page_read

                mutableState.update {
                    it.copy(
                        manga = getManga.await(manga.id),
                        viewerChapters = currChapters,
                    )
                }
                eventChannel.send(Event.ReloadViewerChapters)
            }
        }
    }

    /**
     * Returns the orientation type used by this manga or the default one.
     */
    fun getMangaOrientation(resolveDefault: Boolean = true): Int {
        val default = readerPreferences.defaultOrientationType().get()
        val orientation = ReaderOrientation.fromPreference(manga?.readerOrientation?.toInt())
        return when {
            resolveDefault && orientation == ReaderOrientation.DEFAULT -> default
            else -> manga?.readerOrientation?.toInt() ?: default
        }
    }

    /**
     * Updates the orientation type for the open manga.
     */
    fun setMangaOrientationType(orientation: ReaderOrientation) {
        val manga = manga ?: return
        viewModelScope.launchIO {
            setMangaViewerFlags.awaitSetOrientation(manga.id, orientation.flagValue.toLong())
            val currChapters = state.value.viewerChapters
            if (currChapters != null) {
                // Save current page
                val currChapter = currChapters.currChapter
                currChapter.requestedPage = currChapter.chapter.last_page_read

                mutableState.update {
                    it.copy(
                        manga = getManga.await(manga.id),
                        viewerChapters = currChapters,
                    )
                }
                eventChannel.send(Event.SetOrientation(getMangaOrientation()))
                eventChannel.send(Event.ReloadViewerChapters)
            }
        }
    }

    fun toggleCropBorders(): Boolean {
        val isPagerType = ReadingMode.isPagerType(getMangaReadingMode())
        return if (isPagerType) {
            readerPreferences.cropBorders().toggle()
        } else {
            readerPreferences.cropBordersWebtoon().toggle()
        }
    }

    /**
     * Generate a filename for the given [manga] and [page]
     */
    private fun generateFilename(
        manga: Manga,
        page: ReaderPage,
    ): String {
        val chapter = page.chapter.chapter
        val filenameSuffix = " - ${page.number}"
        return DiskUtil.buildValidFilename(
            "${manga.title} - ${chapter.name}".takeBytes(DiskUtil.MAX_FILE_NAME_BYTES - filenameSuffix.byteSize()),
        ) + filenameSuffix
    }

    fun showMenus(visible: Boolean) {
        mutableState.update { it.copy(menuVisible = visible) }
    }

    fun showLoadingDialog() {
        mutableState.update { it.copy(dialog = Dialog.Loading) }
    }

    fun openReadingModeSelectDialog() {
        mutableState.update { it.copy(dialog = Dialog.ReadingModeSelect) }
    }

    fun openOrientationModeSelectDialog() {
        mutableState.update { it.copy(dialog = Dialog.OrientationModeSelect) }
    }

    fun openPageDialog(page: ReaderPage) {
        mutableState.update { it.copy(dialog = Dialog.PageActions(page)) }
    }

    fun openSettingsDialog() {
        mutableState.update { it.copy(dialog = Dialog.Settings) }
    }

    fun openTranslationSettingsDialog() {
        mutableState.update { it.copy(dialog = Dialog.TranslationSettings) }
    }

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    fun setBrightnessOverlayValue(value: Int) {
        mutableState.update { it.copy(brightnessOverlayValue = value) }
    }

    /**
     * Saves the image of the selected page on the pictures directory and notifies the UI of the result.
     * There's also a notification to allow sharing the image somewhere else or deleting it.
     */
    fun saveImage() {
        val page = (state.value.dialog as? Dialog.PageActions)?.page
        if (page?.status != Page.State.READY) return
        val manga = manga ?: return

        val context = Injekt.get<Application>()
        val notifier = SaveImageNotifier(context)
        notifier.onClear()

        val filename = generateFilename(manga, page)

        // Pictures directory.
        val relativePath = if (readerPreferences.folderPerManga().get()) {
            DiskUtil.buildValidFilename(
                manga.title,
            )
        } else {
            ""
        }

        // Copy file in background.
        viewModelScope.launchNonCancellable {
            try {
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = page.stream!!,
                        name = filename,
                        location = Location.Pictures.create(relativePath),
                    ),
                )
                withUIContext {
                    notifier.onComplete(uri)
                    eventChannel.send(Event.SavedImage(SaveImageResult.Success(uri)))
                }
            } catch (e: Throwable) {
                notifier.onError(e.message)
                eventChannel.send(Event.SavedImage(SaveImageResult.Error(e)))
            }
        }
    }

    /**
     * Shares the image of the selected page and notifies the UI with the path of the file to share.
     * The image must be first copied to the internal partition because there are many possible
     * formats it can come from, like a zipped chapter, in which case it's not possible to directly
     * get a path to the file and it has to be decompressed somewhere first. Only the last shared
     * image will be kept so it won't be taking lots of internal disk space.
     */
    fun shareImage(copyToClipboard: Boolean) {
        val page = (state.value.dialog as? Dialog.PageActions)?.page
        if (page?.status != Page.State.READY) return
        val manga = manga ?: return

        val context = Injekt.get<Application>()
        val destDir = context.cacheImageDir

        val filename = generateFilename(manga, page)

        try {
            viewModelScope.launchNonCancellable {
                destDir.deleteRecursively()
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = page.stream!!,
                        name = filename,
                        location = Location.Cache,
                    ),
                )
                eventChannel.send(if (copyToClipboard) Event.CopyImage(uri) else Event.ShareImage(uri, page))
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
        }
    }

    /**
     * Sets the image of the selected page as cover and notifies the UI of the result.
     */
    fun setAsCover() {
        val page = (state.value.dialog as? Dialog.PageActions)?.page
        if (page?.status != Page.State.READY) return
        val manga = manga ?: return
        val stream = page.stream ?: return

        viewModelScope.launchNonCancellable {
            val result = try {
                manga.editCover(Injekt.get(), stream())
                if (manga.isLocal() || manga.favorite) {
                    SetAsCoverResult.Success
                } else {
                    SetAsCoverResult.AddToLibraryFirst
                }
            } catch (e: Exception) {
                SetAsCoverResult.Error
            }
            eventChannel.send(Event.SetCoverResult(result))
        }
    }

    enum class SetAsCoverResult {
        Success,
        AddToLibraryFirst,
        Error,
    }

    sealed interface SaveImageResult {
        class Success(val uri: Uri) : SaveImageResult
        class Error(val error: Throwable) : SaveImageResult
    }

    /**
     * Starts the service that updates the last chapter read in sync services. This operation
     * will run in a background thread and errors are ignored.
     */
    private fun updateTrackChapterRead(readerChapter: ReaderChapter) {
        if (incognitoMode) return
        if (!trackPreferences.autoUpdateTrack().get()) return

        val manga = manga ?: return
        val context = Injekt.get<Application>()

        viewModelScope.launchNonCancellable {
            trackChapter.await(context, manga.id, readerChapter.chapter.chapter_number.toDouble())
        }
    }

    /**
     * Enqueues this [chapter] to be deleted when [deletePendingChapters] is called. The download
     * manager handles persisting it across process deaths.
     */
    private fun enqueueDeleteReadChapters(chapter: ReaderChapter) {
        if (!chapter.chapter.read) return
        val manga = manga ?: return

        viewModelScope.launchNonCancellable {
            downloadManager.enqueueChaptersToDelete(listOf(chapter.chapter.toDomainChapter()!!), manga)
        }
    }

    /**
     * Deletes all the pending chapters. This operation will run in a background thread and errors
     * are ignored.
     */
    private fun deletePendingChapters() {
        viewModelScope.launchNonCancellable {
            downloadManager.deletePendingChapters()
        }
    }

    @Immutable
    data class State(
        val manga: Manga? = null,
        val viewerChapters: ViewerChapters? = null,
        val bookmarked: Boolean = false,
        val isLoadingAdjacentChapter: Boolean = false,
        val currentPage: Int = -1,
        val viewer: Viewer? = null,
        val dialog: Dialog? = null,
        val menuVisible: Boolean = false,
        @IntRange(from = -100, to = 100) val brightnessOverlayValue: Int = 0,
        val translationState: Translation.State = Translation.State.NOT_TRANSLATED,
        val translationProgress: Pair<Int, Int> = Pair(0, 0),
        val translationRefreshToken: Long = 0L,
    ) {
        val currentChapter: ReaderChapter?
            get() = viewerChapters?.currChapter

        val totalPages: Int
            get() = currentChapter?.pages?.size ?: -1
    }

    fun startCurrentChapterTranslation() {
        val manga = manga ?: return
        val chapter = getCurrentChapter()?.chapter ?: return
        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        viewModelScope.launchIO {
            translationManager.translateChapter(manga, chapter.toDomainChapter()!!)
            observeTranslationState()
        }
    }

    fun cancelCurrentChapterTranslation() {
        val chapter = getCurrentChapter()?.chapter ?: return
        translationManager.cancelQueuedTranslation(
            translationManager.getQueuedTranslationOrNull(chapter.id!!) ?: return
        )
    }

    /**
     * TachiyomiAT: cancels all translation work tied to [chapter] so navigating
     * away from it cannot leave orphaned jobs running on the singleton
     * ChapterTranslator. Revokes in-flight single-page jobs, removes the chapter
     * from the batch queue if present, and evicts the reader page streams its
     * holders registered (those closures otherwise keep page bitmaps alive).
     */
    private fun cancelTranslationForChapter(chapter: ReaderChapter) {
        val manga = manga ?: return
        val chapterId = chapter.chapter.id ?: return
        translationManager.cancelPageTranslations(chapterId)
        translationManager.getQueuedTranslationOrNull(chapterId)?.let {
            translationManager.cancelQueuedTranslation(it)
        }
        eu.kanade.translation.ChapterTranslator.clearReaderPageStreams(
            sourceId = manga.source,
            mangaId = manga.id,
            chapterId = chapterId,
        )
    }

    fun deleteCurrentChapterTranslation() {
        val manga = manga ?: return
        val chapter = getCurrentChapter()?.chapter ?: return
        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        translationManager.deleteTranslation(chapter.toDomainChapter()!!, manga, source)
        mutableState.update { it.copy(translationState = Translation.State.NOT_TRANSLATED) }
    }

    fun translateSinglePage(page: ReaderPage) {
        try { java.io.File("/sdcard/at_diag.txt").appendText("TSS_ENTER idx=${page.index}\n") } catch (_: Exception) {}
        val manga = manga ?: run { try { java.io.File("/sdcard/at_diag.txt").appendText("TSS_MANGA_NULL\n") } catch (_: Exception) {}; return }
        // TachiyomiAT: use the page's own chapter context, not getCurrentChapter().
        // The global current-chapter may differ from the page's actual chapter
        // when a cross-chapter page-transition event fires before loadNewChapter
        // completes — translating against the wrong chapter would write results
        // under the wrong store key (skipped-pages bug) and register reader-page
        // streams for a chapter the translator can't match to disk files.
        val chapter = page.chapter.chapter
        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        val pageKey = resolvePageKey(page)
        logcat(LogPriority.INFO) {
            "TachiyomiAT manual translate page request: index=${page.index} pageKey=$pageKey " +
                "sourceFileName=${page.sourceFileName} imageUrl=${page.imageUrl} " +
                "loader=${page.chapter.pageLoader?.javaClass?.simpleName}"
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
            chapter.name, chapter.scanlator, manga.title, manga.source,
        )
        if (page.originalStream == null && !pageChapterDownloaded && page.imageUrl == null) {
            try { java.io.File("/sdcard/at_diag.txt").appendText("TSS_GUARD_RETURN stream=${page.originalStream!=null} dl=${pageChapterDownloaded} imgUrl=${page.imageUrl!=null}\n") } catch (_: Exception) {}
            return
        }
        try { java.io.File("/sdcard/at_diag.txt").appendText("TSS_GUARD_PASSED stream=${page.originalStream!=null} dl=${pageChapterDownloaded} imgUrl=${page.imageUrl!=null}\n") } catch (_: Exception) {}
        page.originalStream?.let { streamFn ->
            eu.kanade.translation.ChapterTranslator.registerReaderPageStream(
                manga,
                chapter.toDomainChapter()!!,
                source,
                pageKey,
                streamFn,
            )
        } ?: page.imageUrl?.let { imageUrl ->
            // TachiyomiAT: for online pages not yet cached (originalStream is
            // null), register a lazy stream that fetches from the source so the
            // translator can read the image bytes without a local chapter dir.
            eu.kanade.translation.ChapterTranslator.registerReaderPageStream(
                manga,
                chapter.toDomainChapter()!!,
                source,
                pageKey,
                createLazyHttpStream(source, page, imageUrl),
            )
        }
        viewModelScope.launchIO {
            translationManager.translatePage(manga, chapter.toDomainChapter()!!, source, pageKey)
        }
    }

    /**
     * TachiyomiAT: creates a lazy stream closure that downloads the page image
     * from the [source] on demand. Used when [ReaderPage.originalStream] is null
     * (the page hasn't been cached yet by HttpPageLoader) but the page has an
     * [imageUrl]. The translator invokes this closure when it needs the image
     * bytes to run OCR/inpaint — at which point the image is fetched fresh.
     */
    private fun createLazyHttpStream(
        source: HttpSource,
        page: ReaderPage,
        imageUrl: String,
    ): () -> InputStream = {
        runBlocking {
            val sPage = Page(page.index, page.url, imageUrl)
            val response = source.getImage(sPage)
            response.body.byteStream()
        }
    }

    /**
     * TachiyomiAT: cancels the in-flight translation job for a single [page].
     * Backs the per-page button's cancel affordance — lets the user stop one
     * slow/stuck page without abandoning the whole chapter. No-op if nothing is
     * running for that page.
     */
    fun cancelSinglePageTranslation(page: ReaderPage) {
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
    fun stopAllTranslation() {
        translationManager.cancelAllPageTranslations()
        translationManager.translatorStop("user stop")
        batchTranslationState = Translation.State.NOT_TRANSLATED
        liveTranslationState = Translation.State.NOT_TRANSLATED
        recomputeTranslationState()
    }

    fun isCurrentChapterDownloaded(): Boolean {
        val manga = manga ?: return false
        val chapter = getCurrentChapter()?.chapter ?: return false
        return downloadManager.isChapterDownloaded(chapter.name, chapter.scanlator, manga.title, manga.source)
    }

    private fun observeTranslationState() {
        // TachiyomiAT: cancel any prior collector first. loadChapter() and
        // startCurrentChapterTranslation() both call this, so without cancelling
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

    fun observeLiveTranslationStore() {
        translationStoreJob?.cancel()
        val manga = manga ?: return
        val chapter = getCurrentChapter()?.chapter ?: return
        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        // TachiyomiAT: use openOrCreate... instead of openActive.... The latter
        // returns null and bails out when no translation file exists yet (a
        // fresh chapter). That left the reader subscribed to *nothing* while the
        // translator wrote to a *different* store instance it created via its
        // own activeStoreResolver — so the very first translate click showed no
        // overlay/dim/spinner even though the pipeline ran. openOrCreate...
        // returns the exact same shared instance the translator resolves to,
        // so the reader observes the RUNNING write live.
        val store = translationManager.openOrCreateActiveChapterTranslationStore(
            chapter.id!!, chapter.name, chapter.scanlator, manga.title, source,
        ) ?: return
        val storeState = store.state
        translationStoreJob = viewModelScope.launchIO {
            storeState.collect { pageMap ->
                val pages = state.value.viewerChapters?.currChapter?.pages ?: return@collect
                var translatedCount = 0
                val changedPages = mutableSetOf<ReaderPage>()
                val totalPages = pages.size
                // Track whether ANY page currently has a RUNNING stage or a
                // FAILED stage across this emission, so we can derive a
                // liveTranslationState that reflects per-page/auto work (the
                // batch-queue status alone never flips for these).
                var anyRunning = false
                var anyError = false
                for (page in pages) {
                    val readerPage = page as? ReaderPage ?: continue
                    val pageKey = resolvePageKey(readerPage)
                    val updated = pageMap[pageKey] ?: continue
                    if (updated.ocrStatus == eu.kanade.translation.model.StageStatus.RUNNING ||
                        updated.inpaintStatus == eu.kanade.translation.model.StageStatus.RUNNING ||
                        updated.translationStatus == eu.kanade.translation.model.StageStatus.RUNNING ||
                        updated.renderStatus == eu.kanade.translation.model.StageStatus.RUNNING
                    ) {
                        anyRunning = true
                    }
                    val hasRendered = updated.renderedImageName != null
                    val hasCleaned = updated.cleanedImageName != null
                    val isFailed = updated.ocrStatus == eu.kanade.translation.model.StageStatus.FAILED ||
                        updated.inpaintStatus == eu.kanade.translation.model.StageStatus.FAILED
                    if (isFailed && !hasRendered && !hasCleaned) anyError = true
                    if (updated.ocrStatus == eu.kanade.translation.model.StageStatus.RUNNING ||
                        updated.inpaintStatus == eu.kanade.translation.model.StageStatus.RUNNING ||
                        updated.translationStatus == eu.kanade.translation.model.StageStatus.RUNNING ||
                        updated.renderStatus == eu.kanade.translation.model.StageStatus.RUNNING ||
                        updated.errorMessage != null
                    ) {
                        logcat(LogPriority.INFO) {
                            "TachiyomiAT live translation update: pageKey=$pageKey " +
                                "ocr=${updated.ocrStatus} inpaint=${updated.inpaintStatus} " +
                                "translate=${updated.translationStatus} render=${updated.renderStatus} " +
                                "rendered=${updated.renderedImageName} cleaned=${updated.cleanedImageName} " +
                                "error=${updated.errorMessage}"
                        }
                    }
                    val previous = readerPage.translation
                    if (hasRendered || hasCleaned) translatedCount++
                    if (isFailed && !hasRendered && !hasCleaned) translatedCount++
                    val renderedChanged = hasRendered && updated.renderedImageName != previous?.renderedImageName
                    val cleanedChanged = hasCleaned && updated.cleanedImageName != previous?.cleanedImageName
                    val streamChanged = updated.renderedImageName != null || updated.cleanedImageName != null
                    val needsStreamUpdate = streamChanged && (renderedChanged || cleanedChanged || readerPage.translatedStream == null)
                    val runningChanged = updated.ocrStatus != previous?.ocrStatus ||
                        updated.inpaintStatus != previous?.inpaintStatus ||
                        updated.renderStatus != previous?.renderStatus ||
                        updated.translationStatus != previous?.translationStatus
                    if (!needsStreamUpdate && !runningChanged) {
                        readerPage.translation = updated
                        continue
                    }
                    if (needsStreamUpdate && hasRendered) {
                        readerPage.translatedStream = translationManager.getRenderedImageStream(
                            manga.title, source, chapter.name, chapter.scanlator, updated.renderedImageName!!,
                        )
                    } else if (needsStreamUpdate && hasCleaned) {
                        readerPage.translatedStream = translationManager.getCleanedImageStream(
                            manga.title, source, chapter.name, chapter.scanlator, updated.cleanedImageName!!,
                        )
                    }
                    readerPage.translation = updated
                    changedPages.add(readerPage)
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
                // TachiyomiAT: only push a State update when the progress actually
                // changed. The old code used System.currentTimeMillis() as a
                // refresh token, which always differs and forces Compose
                // recomposition on every single translation-stage emission (OCR →
                // inpaint → translate → render per page per prefetch), causing
                // visible "blinking" on auto-translate chapters.
                val newProgress = Pair(translatedCount, totalPages)
                val currentState = mutableState.value
                if (newProgress != currentState.translationProgress) {
                    mutableState.update {
                        it.copy(
                            translationProgress = newProgress,
                            translationRefreshToken = System.currentTimeMillis(),
                        )
                    }
                }
                if (changedPages.isNotEmpty()) {
                    eventChannel.trySend(Event.RefreshTranslationPages(changedPages))
                }
            }
        }
    }

    private val ReaderPage.renderedImageName: String?
        get() = translation?.renderedImageName

    /**
     * Resolves the page key the translator uses to write live updates, in order of
     * reliability:
     * 1. [ReaderPage.sourceFileName] — the local filename set by the page loader.
     * 2. [PageTranslation.sourceFileName] — populated once a translation exists.
     * 3. [ReaderPage.imageUrl] / [ReaderPage.url] — unstable fallbacks for HTTP sources.
     *
     * Returns an empty string only as a last resort so callers can still index without NPEs.
     */
    private fun resolvePageKey(page: ReaderPage): String =
        page.sourceFileName
            ?: page.translation?.sourceFileName
            ?: page.imageUrl?.substringAfterLast('/')?.substringBefore('?')
            ?: page.url.substringAfterLast('/').substringBefore('?')

    sealed interface Dialog {
        data object Loading : Dialog
        data object Settings : Dialog
        data object TranslationSettings : Dialog
        data object ReadingModeSelect : Dialog
        data object OrientationModeSelect : Dialog
        data class PageActions(val page: ReaderPage) : Dialog
    }

    sealed interface Event {
        data object ReloadViewerChapters : Event
        data object PageChanged : Event
        data class SetOrientation(val orientation: Int) : Event
        data class SetCoverResult(val result: SetAsCoverResult) : Event

        data class SavedImage(val result: SaveImageResult) : Event
        data class ShareImage(val uri: Uri, val page: ReaderPage) : Event
        data class CopyImage(val uri: Uri) : Event
        data class RefreshTranslationPages(val pages: Set<ReaderPage>) : Event
    }
}
                                                                                                                               