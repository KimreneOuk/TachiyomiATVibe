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
import eu.kanade.presentation.more.settings.widget.AiModelListState
import eu.kanade.tachiyomi.data.cache.ChapterCache
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
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.util.chapter.filterDownloaded
import eu.kanade.tachiyomi.util.chapter.removeDuplicates
import eu.kanade.tachiyomi.util.editCover
import eu.kanade.tachiyomi.util.lang.byteSize
import eu.kanade.tachiyomi.util.lang.takeBytes
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.cacheImageDir
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.translation.diagnostics.ReaderEntryTrace
import eu.kanade.translation.model.PageIndexResolver
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.shouldShowTranslationOverlay
import eu.kanade.translation.ocr.OcrModelCatalog
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.orchestration.TranslationManager
import eu.kanade.translation.orchestration.TranslationSessionState
import eu.kanade.translation.rendering.PersistedLayoutReaderBridge
import eu.kanade.translation.scheduling.AutoChapterIdentity
import eu.kanade.translation.scheduling.SinglePageOutcome
import eu.kanade.translation.scheduling.TranslationScheduler
import eu.kanade.translation.scheduling.TranslationStreamRegistry
import eu.kanade.translation.storage.ChapterTranslationStore
import eu.kanade.translation.translator.NativeStallState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
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
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.i18n.at.ATMR
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.util.Date
import java.util.concurrent.atomic.AtomicLong

/**
 * Presenter used by the activity to perform background operations.
 */
class ReaderViewModel @JvmOverloads constructor(
    private val savedState: SavedStateHandle,
    internal val sourceManager: SourceManager = Injekt.get(),
    internal val downloadManager: DownloadManager = Injekt.get(),
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
    internal val translationManager: TranslationManager = Injekt.get(),
    internal val translationScheduler: TranslationScheduler = Injekt.get(),
    internal val streamRegistry: TranslationStreamRegistry = Injekt.get(),
    internal val chapterCache: ChapterCache = Injekt.get(),
    val translationPreferences: tachiyomi.domain.translation.TranslationPreferences = Injekt.get(),
) : ViewModel() {

    internal val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    val translationSessionState: kotlinx.coroutines.flow.StateFlow<TranslationSessionState>
        get() = translationManager.sessionCoordinator.state

    /** Reader-specific rolling Auto projection; batch state remains separate. */
    val autoTranslationUiState: kotlinx.coroutines.flow.StateFlow<ReaderAutoTranslationUiState> =
        state
            .map { it.autoTranslation }
            .distinctUntilChanged()
            .stateIn(
                viewModelScope,
                kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
                ReaderAutoTranslationUiState.empty(),
            )

    /**
     *  P5 (spec §0.2.2, ): the pipeline's native stall state. Consumed
     * by the page holders so the chip can reflect the stalled pageKey through
     * the shared truth mapper. Pass-through of the manager's single bounded
     * [NativeStallState] StateFlow — no new buffering, no polling.
     */
    val nativeStallState: kotlinx.coroutines.flow.StateFlow<NativeStallState?>
        get() = translationManager.nativeStall

    /**
     *  P5 (spec §0.2.2, §6.2.8): read-only typed outcome of the last
     * completed manual single-page intent. The (chapterId, pageKey) pair IS
     * the identity fence: an outcome recorded for any other page or chapter is
     * never returned, so the page-holder chip join cannot bleed results across
     * pages. Callers must treat the value through the pure
     * TranslationUiTruth.forManualOutcome mapper.
     */
    fun manualSinglePageOutcome(chapterId: Long, pageKey: String): SinglePageOutcome? =
        translationScheduler.manualOutcomeFor(chapterId, pageKey)

    /** The queue is selected by the current chapter ID; no other chapter may replace it. */
    val translationQueueState: kotlinx.coroutines.flow.StateFlow<ImmutableList<QueuedPageInfo>> =
        state
            .map { it.currentChapter?.chapter?.id }
            .distinctUntilChanged()
            .flatMapLatest { chapterId ->
                translationManager.selectActiveStore(chapterId ?: -1L)
                    .map { pages -> buildQueuedPageInfo(pages, currentPageIndexResolver()).toImmutableList() }
            }
            .distinctUntilChanged()
            .stateIn(
                viewModelScope,
                kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
                persistentListOf(),
            )

    internal val aiModelFetchState = MutableStateFlow<AiModelListState>(AiModelListState.Idle)

    val translationSettingsState: kotlinx.coroutines.flow.StateFlow<TranslationSettingsState> = combine(
        combine(
            combine(
                translationPreferences.translationEnabled().changes(),
                translationPreferences.autoTranslate().changes(),
                translationPreferences.autoTranslatePrefetchCount().changes(),
                translationPreferences.translateFromLanguage().changes(),
            ) { a, b, c, d -> Quad(a, b, c, d) },
            combine(
                translationPreferences.translationRecentLanguagesFrom().changes(),
                translationPreferences.translateToLanguage().changes(),
                translationPreferences.translationRecentLanguagesTo().changes(),
                translationPreferences.translationInpaintingMode().changes(),
            ) { a, b, c, d -> Quad(a, b, c, d) },
        ) { q1, q2 -> Pair(q1, q2) },
        combine(
            combine(
                translationPreferences.translationEngineCategory().changes(),
                translationPreferences.translationStandardEngine().changes(),
                translationPreferences.translationDeeplApiKey().changes(),
                translationPreferences.translationAiEngine().changes(),
            ) { a, b, c, d -> Quad(a, b, c, d) },
            aiModelFetchState,
        ) { q, fetchState -> Pair(q, fetchState) },
        translationPreferences.translateFromLanguage().changes().flatMapLatest { fromValue ->
            val language = TextRecognizerLanguage.entries.firstOrNull { it.name == fromValue } ?: TextRecognizerLanguage.CHINESE
            val ocrPref = OcrModelCatalog.preferenceFor(translationPreferences, language)
            ocrPref.changes().map { storedModel ->
                val coerced = OcrModelCatalog.coerce(storedModel, language)
                val labels = OcrModelCatalog.labelsFor(language)
                coerced to labels
            }
        },
        translationPreferences.translationAiEngine().changes().flatMapLatest { engine ->
            val apiKeyFlow = translationPreferences.translationAiApiKey(engine).changes()
            val baseUrlFlow = translationPreferences.translationAiBaseUrl(engine)?.changes() ?: kotlinx.coroutines.flow.flowOf("")
            val modelFlow = translationPreferences.translationAiModel(engine).changes()
            val recentModelsFlow = translationPreferences.translationAiRecentModels(engine).changes()
            combine(apiKeyFlow, baseUrlFlow, modelFlow, recentModelsFlow) { apiKey, baseUrl, model, recentRaw ->
                AiSubPrefs(apiKey, baseUrl, model, recentRaw)
            }
        },
    ) { part1, part2, ocrInfo, aiSubPrefs ->
        val (q1, q2) = part1
        val (q3, fetchState) = part2
        val (ocrModel, ocrModelEntries) = ocrInfo

        val recentLangsFrom = TranslationPreferences.decodeRecentLanguages(q2.a).toImmutableList()
        val recentLangsTo = TranslationPreferences.decodeRecentLanguages(q2.c).toImmutableList()
        val recentAiModels = TranslationPreferences.decodeRecentModels(aiSubPrefs.recentRaw).toImmutableList()

        TranslationSettingsState(
            enabled = q1.a,
            autoTranslate = q1.b,
            autoTranslatePrefetchCount = q1.c,
            translateFromLanguage = q1.d,
            translateToLanguage = q2.b,
            translationRecentLanguagesFrom = recentLangsFrom,
            translationRecentLanguagesTo = recentLangsTo,
            ocrModel = ocrModel,
            ocrModelEntries = ocrModelEntries,
            inpaintingMode = q2.d,
            engineCategory = q3.a,
            standardEngine = q3.b,
            deeplApiKey = q3.c,
            aiEngine = q3.d,
            aiApiKey = aiSubPrefs.apiKey,
            aiBaseUrl = aiSubPrefs.baseUrl,
            aiModel = aiSubPrefs.model,
            aiRecentModels = recentAiModels,
            aiModelFetchState = fetchState,
        )
    }.stateIn(
        viewModelScope,
        kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
        TranslationSettingsState(),
    )

    /**
     * One row in the translation queue view. [index] is 1-based and stable
     * (derived from the page key's natural order within the chapter); [stage]
     * is the human-readable current stage for display.
     */
    @Immutable
    data class QueuedPageInfo(
        val pageKey: String,
        val index: Int,
        val stage: QueueStage,
    )

    enum class QueueStage { QUEUED, OCR, INPAINT, TRANSLATE, RENDER, DONE, FAILED }

    /**
     * Maps the live per-page store into an ordered, index-resolved list for the
     * queue view. Pages are ordered by their 1-based chapter index (parsed from
     * the page key where possible, else by insertion order). Only pages that have
     * an entry in the store (i.e. are/were being translated) appear — untouched
     * pages are omitted to keep the list focused on active work.
     */
    private fun buildQueuedPageInfo(
        pages: Map<String, PageTranslation>,
        indexResolver: Map<String, Int> = emptyMap(),
    ): List<QueuedPageInfo> {
        if (pages.isEmpty()) return emptyList()
        return pages.entries
            .mapIndexedNotNull { insertionOrder, (pageKey, pt) ->
                val stage = stageOf(pt) ?: return@mapIndexedNotNull null
                QueuedPageInfo(
                    pageKey = pageKey,
                    index = PageIndexResolver.resolve(pageKey, insertionOrder, indexResolver),
                    stage = stage,
                )
            }
            .sortedBy { it.index }
    }

    private fun currentPageIndexResolver(): Map<String, Int> {
        return state.value.currentChapter?.pages
            ?.mapIndexed { index, page -> resolvePageKey(page) to index + 1 }
            ?.toMap()
            ?: emptyMap()
    }

    /** Derives the display stage from a page's four stage statuses. */
    private fun stageOf(pt: PageTranslation): QueueStage? {
        // The store is persisted history. Only active work and actionable
        // failures belong in the queue view; old pending/cancelled/textless/done
        // rows are not live queue reservations.
        if (pt.hasRenderedResult) return null
        // First RUNNING stage wins, in pipeline order: render → translate →
        // inpaint → ocr (checked newest-first so the label reflects the current
        // step, not an earlier one that hasn't been cleared yet).
        if (pt.renderStatus == StageStatus.RUNNING) return QueueStage.RENDER
        if (pt.translationStatus == StageStatus.RUNNING) return QueueStage.TRANSLATE
        if (pt.inpaintStatus == StageStatus.RUNNING) return QueueStage.INPAINT
        if (pt.ocrStatus == StageStatus.RUNNING) return QueueStage.OCR
        // Any FAILED stage with no result yet → Failed.
        if (pt.ocrStatus == StageStatus.FAILED ||
            pt.inpaintStatus == StageStatus.FAILED ||
            pt.translationStatus == StageStatus.FAILED ||
            pt.renderStatus == StageStatus.FAILED
        ) {
            return QueueStage.FAILED
        }
        return null
    }

    internal val eventChannel = Channel<Event>()
    val eventFlow = eventChannel.receiveAsFlow()

    internal var translationStoreJob: kotlinx.coroutines.Job? = null
    internal var translationBatchProgressJob: kotlinx.coroutines.Job? = null
    internal var translationStateJob: kotlinx.coroutines.Job? = null
    internal var autoSnapshotJob: kotlinx.coroutines.Job? = null
    internal var currentTranslationStore: eu.kanade.translation.storage.ChapterTranslationStore? = null

    /** Resolver indirection is invalidated before any chapter/page resources are recycled. */
    internal val autoPageResolver = ReaderAutoTranslationPageResolver(chapterCache)

    /**
     * Source readiness is a reader-lifecycle signal, not a page observer. The loader callback
     * keeps only weak references to the reader/chapter and is fenced by this generation.
     */
    internal val autoReadinessLock = Any()
    internal val autoReadinessGeneration = AtomicLong(0L)
    internal var autoReadinessLoader: PageLoader? = null
    internal var autoReadinessPokeInFlight = false

    /** Identity of the reader-owned rolling window currently bound to the UI. */
    @Volatile
    internal var activeAutoIdentity: AutoChapterIdentity? = null

    /** Fences late emissions from a collector that was replaced by a new window. */
    internal val autoSnapshotGeneration = AtomicLong(0L)

    /**
     * Stable-flow emissions can lag a synchronous coordinator replacement. Keep the last
     * accepted scheduler tuple so a same-identity collector cannot publish a lower owner or
     * window version while the flow switches stores.
     */
    internal val autoSnapshotFenceLock = Any()
    internal var acceptedAutoOwnerVersion: Long? = null
    internal var acceptedAutoWindowVersion: Long = -1L
    internal var activeAutoOwnerToken: Any? = null

    /** Distinguishes a reopened reader from an older coordinator session. */
    internal val autoReaderSessionKey = "reader-${System.identityHashCode(this)}"

    // TachiyomiAT: the published [State.translationState] now reflects BOTH the
    // batch-queue status (batchTranslationState, driven by observeTranslationState)
    // and per-page/auto work (liveTranslationState, driven by
    // observeLiveTranslationStore). Previously only the batch queue was
    // observed, so the only path the UI actually triggers (per-page/auto) never
    // flipped the bottom-bar icon to TRANSLATING — making taps look like they
    // did nothing. [recomputeTranslationState] merges the two each time either
    // source changes.
    internal var batchTranslationState = Translation.State.NOT_TRANSLATED
    internal var liveTranslationState = Translation.State.NOT_TRANSLATED

    // TachiyomiAT: guard so recompute only fires [mutableState.update] when the
    // effective translation state actually changes. Without this, every single
    // stage-emission from the live store (OCR→inpaint→translate→render for
    // every auto-translated page) triggers a recompose, causing the per-page
    // button to rapidly toggle between translate and cancel affordances —
    // visible as "blinking" on non-downloaded chapters where pages stream
    // through translation stages at network speed.
    private var lastEffectiveTranslationState = Translation.State.NOT_TRANSLATED

    internal fun recomputeTranslationState() {
        val effective = when {
            batchTranslationState == Translation.State.TRANSLATING ||
                liveTranslationState == Translation.State.TRANSLATING -> Translation.State.TRANSLATING
            batchTranslationState == Translation.State.ERROR ||
                liveTranslationState == Translation.State.ERROR -> Translation.State.ERROR
            batchTranslationState == Translation.State.PAUSED ||
                liveTranslationState == Translation.State.PAUSED -> Translation.State.PAUSED
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
    internal var chapterPageIndex = savedState.get<Int>("page_index") ?: -1
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

    /**
     * TachiyomiAT: cached value of the opt-in translation_diagnostics preference.
     * Gates the chatty per-page INFO log in [observeLiveTranslationStore] so the
     * collector's hot path isn't doing string interpolation + log dispatch on
     * every RUNNING stage of every page during a batch run. Read once at VM
     * creation (the pref is not toggled mid-read in practice).
     */
    internal val translationDiagnosticsEnabled = translationPreferences.translationDiagnostics().get()

    private val translationController by lazy { ReaderTranslationController(this) }

    init {
        // TachiyomiAT: Auto-translate is a per-session convenience, not a
        // persistent default. Force it off on reader entry so a chapter never
        // starts translating the instant the reader opens — the user must opt in
        // via the Translation settings sheet each session. It then stays on while
        // navigating within this reader session.
        translationPreferences.autoTranslate().set(false)

        // S3 (Milestone M2): Warm up recognition engines when translation is enabled.
        if (translationPreferences.translationEnabled().get()) {
            viewModelScope.launchIO {
                try {
                    translationManager.warmUp()
                } catch (_: Exception) {}
            }
        }

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
                // S7 / B1 (Milestone M2): Steer queue toward current reader chapter.
                translationManager.prioritizeChapter(chapterId)
            }
            .launchIn(viewModelScope)

        translationPreferences.autoTranslate().changes()
            .onEach { enabled ->
                if (enabled && translationPreferences.translationEnabled().get()) {
                    translateCurrentPageForAuto()
                } else {
                    resetAutoTranslationState()
                    val wasActive = getCurrentChapter()?.chapter?.id?.let {
                        translationManager.cancelAutoTranslations(it)
                    } ?: false
                    // TachiyomiAT bug 4 fix: surface the cancellation so the user
                    // sees the toggle had an effect; the dim clear (sync CANCELLED
                    // write from B4 step 1) happens inside cancelAutoTranslations.
                    if (wasActive) {
                        val context = Injekt.get<Application>()
                        context.toast(ATMR.strings.auto_translation_disabled_toast)
                    }
                }
            }
            .launchIn(viewModelScope)

        // The target is a live reader preference. Reconcile the existing
        // rolling window immediately when the user moves the slider; the
        // visible anchor and its current stage remain unchanged.
        translationPreferences.autoTranslatePrefetchCount().changes()
            .onEach {
                if (translationPreferences.translationEnabled().get() &&
                    translationPreferences.autoTranslate().get()
                ) {
                    translateCurrentPageForAuto()
                }
            }
            .launchIn(viewModelScope)

        // Rebind the lane classification when the active translator family
        // changes while Auto is on (for example ML Kit ↔ remote provider).
        combine(
            translationPreferences.translationEngineCategory().changes(),
            translationPreferences.translationStandardEngine().changes(),
            translationPreferences.translationAiEngine().changes(),
        ) { _, _, _ -> Unit }
            .onEach {
                if (translationPreferences.translationEnabled().get() &&
                    translationPreferences.autoTranslate().get()
                ) {
                    translateCurrentPageForAuto()
                }
            }
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
            .distinctUntilChanged()
            .drop(1)
            .onEach { enabled ->
                refreshVisiblePages()
                if (!enabled) {
                    // User disabled translation: cancel everything in flight so
                    // no orphaned page jobs keep running (and keep holding the
                    // translator permit) after the per-page buttons disappear.
                    resetAutoTranslationState()
                    withIOContext {
                        translationManager.cancelAllPageTranslationsOffMain(
                            cancelBatchQueue = true,
                            reason = "Translation disabled by user",
                        )
                        // TachiyomiAT: user disabled translation — tear down engines so a
                        // subsequent re-enable picks up any config changes made while off.
                        translationManager.translatorStop("translation disabled", closeEngines = true)
                        streamRegistry.clearAll()
                    }
                    // Reset both halves of the merged state so the bottom-bar
                    // icon returns to neutral instead of staying stuck on
                    // TRANSLATING/ERROR after the work was just cancelled.
                    batchTranslationState = Translation.State.NOT_TRANSLATED
                    liveTranslationState = Translation.State.NOT_TRANSLATED
                    lastEffectiveTranslationState = Translation.State.NOT_TRANSLATED
                    recomputeTranslationState()
                    // TachiyomiAT bug 4 fix: tell the user the cancellation landed.
                    val context = Injekt.get<Application>()
                    context.toast(ATMR.strings.translation_cancelled_toast)
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

    /**
     * TachiyomiAT: per-page original/translated compare toggle state for the
     * current page. Derived from [state] (current page index + translation
     * enabled) and the current page's [ReaderPage.translatedStream]. The
     * side-mounted compare handle observes this to know whether to enable the
     * Original/Translated rows and which one to highlight. Exposed as its own
     * StateFlow so the handle recomposes independently of the rest of the reader.
     */
    val compareState: kotlinx.coroutines.flow.StateFlow<CompareState> =
        state
            .map { s ->
                val enabled = translationPreferences.translationEnabled().get()
                val page = currentPageReaderPage(s)
                val hasTranslation = page?.translatedStream != null || page?.translation?.shouldShowTranslationOverlay == true
                val showingTranslated = page?.showTranslatedImage == true
                CompareState(
                    translationEnabled = enabled,
                    hasTranslation = hasTranslation,
                    showingTranslated = showingTranslated,
                )
            }
            .distinctUntilChanged()
            .stateIn(
                viewModelScope,
                kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
                CompareState(),
            )

    @Immutable
    data class CompareState(
        val translationEnabled: Boolean = false,
        val hasTranslation: Boolean = false,
        val showingTranslated: Boolean = false,
    )

    /** Resolves the [ReaderPage] currently displayed, or null. */
    private fun currentPageReaderPage(s: State = state.value): ReaderPage? {
        val pages = getCurrentChapter()?.pages ?: return null
        // state.currentPage is 1-based for display ("N/M"); chapter.pages is 0-based.
        val idx = s.currentPage - 1
        if (idx !in pages.indices) return null
        return pages[idx] as? ReaderPage
    }

    /**
     * TachiyomiAT: flips the current page between its original and translated
     * image, per-page (does NOT touch the global showTranslations pref, so other
     * pages are unaffected). Sticky: the choice survives scroll-away/return. The
     * flip reuses the existing holder refresh path (Event.RefreshTranslationPages
     * for just this page), so the holder re-runs setImage() with the new
     * showTranslatedImage value — one decode for one page, cheap on both viewers.
     */
    fun setCurrentPageShowTranslated(showTranslated: Boolean) {
        val page = currentPageReaderPage() ?: return
        // Nothing to do (and nothing to show) if there's no translation to swap to.
        if (showTranslated && page.translatedStream == null && page.translation?.shouldShowTranslationOverlay != true) return
        if (page.showTranslatedImage == showTranslated) return
        page.showTranslatedImage = showTranslated
        page.translationToggled = true
        eventChannel.trySend(Event.RefreshTranslationPages(setOf(page)))
        // Bump a no-op state update so compareState (which derives from state)
        // re-emits and the handle highlight flips immediately. currentPage itself
        // is unchanged; we nudge via translationRefreshToken which already exists
        // for this purpose.
        mutableState.update { it.copy(translationRefreshToken = System.currentTimeMillis()) }
    }

    private fun updateTranslationWorkingSet(
        chapter: ReaderChapter,
        currentIndex: Int,
        dispatchRefresh: Boolean,
    ) = translationController.updateTranslationWorkingSet(chapter, currentIndex, dispatchRefresh)

    private fun attachTranslatedStreamIfWarm(
        page: ReaderPage,
        manga: Manga,
        chapter: ReaderChapter,
        source: HttpSource,
    ) = translationController.attachTranslatedStreamIfWarm(page, manga, chapter, source)

    /**
     * Delegates the holder-facing stream attachment seam to the translation controller.
     */
    fun attachTranslatedStreamForPage(page: ReaderPage) =
        translationController.attachTranslatedStreamForPage(page)

    private fun isInTranslationWarmWindow(page: ReaderPage): Boolean =
        translationController.isInTranslationWarmWindow(page)

    override fun onCleared() {
        // Invalidate reader-owned callbacks/resolvers before requesting joined teardown. The
        // manager's Deferred is owned by applicationScope, so this cleanup still runs after the
        // ViewModel scope has been cancelled and only then recycles ReaderPage/loaders.
        resetAutoTranslationState()
        val currentChapters = state.value.viewerChapters
        val pendingDownload = chapterToDownload
        translationStoreJob?.cancel()
        translationBatchProgressJob?.cancel()
        translationStateJob?.cancel()
        //  Stage 7: drop the chapter hydration source with the store
        // — without it the overlay keeps the byte-identical planner fallback.
        PersistedLayoutReaderBridge.installChapterSource(null)
        currentTranslationStore = null
        val readerStop = translationManager.requestReaderStop("reader closed")
        registerReaderCleanupAfterStop(readerStop) {
            // The joined manager boundary guarantees no in-flight work can open a retained page
            // stream. Keep this callback independent of viewModelScope so cleanup survives
            // onCleared and cannot recycle an Epub/HTTP loader early.
            currentChapters?.unref()
            pendingDownload?.let { downloadManager.addDownloadsToStartOfQueue(listOf(it)) }
        }
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
        val entryStage = ReaderEntryTrace.begin("vm.init", initialChapterId)
        return try {
            withIOContext {
                try {
                    val manga = getManga.await(mangaId)
                    if (manga != null) {
                        sourceManager.isInitialized.first { it }
                        mutableState.update { it.copy(manga = manga) }
                        if (chapterId == -1L) chapterId = initialChapterId

                        val context = Injekt.get<Application>()
                        val source = sourceManager.getOrStub(manga.source)
                        loader = ChapterLoader(context, downloadManager, downloadProvider, manga, source)

                        val loadStage = ReaderEntryTrace.begin("vm.loadChapter", chapterId)
                        try {
                            loadChapter(loader!!, chapterList.first { chapterId == it.chapter.id })
                        } finally {
                            loadStage.end()
                        }
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
        } finally {
            entryStage.end()
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

        val landingIndex = chapter.requestedPage.coerceAtLeast(0)
        if (chapterPageIndex < 0) {
            chapterPageIndex = landingIndex
        }

        val chapterPos = chapterList.indexOf(chapter)
        val newChapters = ViewerChapters(
            chapter,
            chapterList.getOrNull(chapterPos - 1),
            chapterList.getOrNull(chapterPos + 1),
        )

        //  ANR fix: resolve the chapter translation status BEFORE the
        // withUIContext block. The query falls through to the durable store
        // over SAF/UniFile (O(pages) FUSE/binder round-trips — 5-9s on a
        // 68-page translated chapter) and must never execute on Main. All
        // loadChapter call sites already run on IO (init: withIOContext,
        // loadNewChapter: launchIO, loadAdjacent: withIOContext). Same value,
        // same downstream assignment — only the thread changed.
        val statusStage = ReaderEntryTrace.begin("vm.translationStatus", chapter.chapter.id)
        val translationStatus = this@ReaderViewModel.manga?.let { m ->
            val ch = newChapters.currChapter.chapter
            translationManager.getChapterTranslationStatus(
                ch.id!!,
                ch.name,
                ch.scanlator,
                m.title,
                m.source,
            )
        } ?: Translation.State.NOT_TRANSLATED
        statusStage.end()

        val stateStage = ReaderEntryTrace.begin("vm.stateUpdate", chapter.chapter.id)
        withUIContext {
            mutableState.update {
                // Add new references first to avoid unnecessary recycling
                newChapters.ref()
                it.viewerChapters?.unref()

                chapterToDownload = cancelQueuedDownloads(newChapters.currChapter)

                it.copy(
                    viewerChapters = newChapters,
                    bookmarked = newChapters.currChapter.chapter.bookmark,
                    translationState = translationStatus,
                )
            }
        }
        stateStage.end()
        observeLiveTranslationStore()
        if (manga != null) observeTranslationState()

        // TachiyomiAT: kick auto-translate for the new chapter's landing page.
        // handleAutoTranslation() was previously only called from onPageSelected()
        // (viewer-driven), but the initial/landing page after loadChapter does
        // not always produce an onPageSelected event — e.g. opening the reader
        // directly on a chapter, or jumping via the prev/next toolbar buttons.
        // The result: the landing page sat untranslated until the user scrolled.
        // This central kick covers all loadChapter paths (initial open, prev/next,
        // cross-chapter scroll) and runs AFTER cancelTranslationForChapter() so
        // the previous chapter's work has been wound down. The viewer-driven
        // onPageSelected kick (below in onPageSelected) stays as a defensive
        // second kick; translatePage()'s dedup makes the double-kick a no-op.
        if (translationPreferences.translationEnabled().get() &&
            translationPreferences.autoTranslate().get()
        ) {
            val pages = chapter.pages
            val landingIndex = chapter.requestedPage.coerceAtLeast(0)
                .coerceAtMost((pages?.lastIndex ?: 0))
            val landingPage = pages?.getOrNull(landingIndex) as? ReaderPage
            if (landingPage != null) {
                handleAutoTranslation(landingPage)
            }
        }

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
        val pageIndex = page.index

        // Keep the in-memory visible page pointer synchronous. Persistence runs
        // below, but auto-toggle reads chapterPageIndex immediately; if this is
        // only updated by the background progress job, a quick scroll + auto-on
        // can enqueue from the old page.
        chapterPageIndex = pageIndex
        selectedChapter.requestedPage = pageIndex
        mutableState.update {
            it.copy(currentPage = pageIndex + 1)
        }

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

        updateTranslationWorkingSet(selectedChapter, pageIndex, dispatchRefresh = true)

        if (translationPreferences.translationEnabled().get() &&
            translationPreferences.autoTranslate().get()
        ) {
            // TachiyomiAT: drop stale cross-chapter events. onPageSelected can
            // fire for a page whose chapter has already been replaced by a newer
            // loadChapter (e.g. rapid chapter-swiping fires several page events
            // before the state catches up). Enqueuing against a chapter that's
            // no longer in viewerChapters writes results the reader will never
            // show and can race the cancellation the new loadChapter just issued.
            val active = state.value.viewerChapters
            val stillActive = active != null &&
                selectedChapter === active.currChapter
            if (stillActive) {
                handleAutoTranslation(page)
            } else {
                logcat {
                    "Dropping auto-translate for page ${page.index}: chapter ${selectedChapter.chapter.url} " +
                        "no longer active (stale cross-chapter event)"
                }
            }
        }

        eventChannel.trySend(Event.PageChanged)
    }

    internal var autoTranslationScrollJob: kotlinx.coroutines.Job? = null
    private fun handleAutoTranslation(currentPage: ReaderPage) =
        translationController.handleAutoTranslation(currentPage)

    private fun resetAutoTranslationState() =
        translationController.resetAutoTranslationState()

    private fun translateCurrentPageForAuto() =
        translationController.translateCurrentPageForAuto()
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
    internal fun getCurrentChapter(): ReaderChapter? {
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

    fun dismissBatchReaderSwitch() {
        closeDialog()
        viewModelScope.launchIO {
            withUIContext {
                Injekt.get<Application>().toast(ATMR.strings.reader_batch_switch_cancelled)
            }
        }
    }

    /**
     * Runs the confirmed batch-to-reader handoff off the manager/reader/store
     * locks. A timeout only logs and retries inside the coordinator; this
     * method does not admit the page until the old batch job really joins.
     */
    fun confirmBatchReaderSwitch(request: Dialog.BatchReaderSwitch) {
        translationController.confirmBatchReaderSwitch(request)
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
        val chapterId = chapter.chapter.id ?: return
        if (translationManager.isChapterTranslationProtected(chapterId)) {
            // Reader auto-delete is a convenience, while a queued/paused batch
            // still owns the source files it must translate. Keep the chapter
            // on disk; an explicit user delete remains available.
            logcat(LogPriority.INFO) {
                "TachiyomiAT retaining chapter $chapterId for batch translation"
            }
            return
        }

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
            downloadManager.deletePendingChapters(translationManager.protectedChapterIds())
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
        val translationBatchProgress: TranslationProgressSnapshot? = null,
        /** Rolling Auto state; batch progress and manual state remain separate. */
        val autoTranslation: ReaderAutoTranslationUiState = ReaderAutoTranslationUiState.empty(),
        // TachiyomiAT: 1-based index of the page currently being translated (the
        // lowest-index page with a RUNNING stage), or 0 when none. The bottom bar
        // uses this so "Translating page N of M" tracks the page the spinner is
        // actually animating on, instead of the completed-pages count (which used
        // to read "36" while the spinner sat on page 34 — a confusing mismatch).
        val translationCurrentPage: Int = 0,
        val translationRefreshToken: Long = 0L,
    ) {
        val currentChapter: ReaderChapter?
            get() = viewerChapters?.currChapter

        val totalPages: Int
            get() = currentChapter?.pages?.size ?: -1
    }

    /**
     * TachiyomiAT: cancels reader-owned translation work tied to [chapter] so navigating
     * away from it cannot leave orphaned single-page jobs running on the singleton
     * ChapterTranslator. Revokes in-flight single-page jobs for this chapter and evicts
     * the reader page streams its holders registered (those closures otherwise keep page
     * bitmaps alive).
     *
     * CP8: this is a chapter-NAVIGATION teardown, NOT process ownership. It must NOT
     * touch the batch queue: a chapter the reader is leaving may still be queued for
     * background batch translation, and the batch entry is process-owned (it survives
     * reader open/close/chapter-switch per the CP8 ownership contract). The joined
     * manager stop preserves that ownership while ensuring reader-owned work has
     * terminated before the old chapter is recycled.
     */
    private suspend fun cancelTranslationForChapter(chapter: ReaderChapter) =
        translationController.cancelTranslationForChapter(chapter)

    fun deleteCurrentChapterTranslation() =
        translationController.deleteCurrentChapterTranslation()

    fun resetTranslationData(preserveEdits: Boolean) =
        translationController.resetTranslationData(preserveEdits)

    fun resetInpaintData() =
        translationController.resetInpaintData()

    fun resetOcrData() =
        translationController.resetOcrData()

    fun resetEverything() =
        translationController.resetEverything()

    fun translateSinglePage(page: ReaderPage, force: Boolean? = null) =
        translationController.translateSinglePage(page, force)

    fun cancelSinglePageTranslation(page: ReaderPage) =
        translationController.cancelSinglePageTranslation(page)

    fun stopAllTranslation() =
        translationController.stopAllTranslation()

    fun retryCurrentBatch(force: Boolean = false) =
        translationController.retryCurrentBatch(force)

    fun setTranslationEnabled(enabled: Boolean) =
        translationController.setTranslationEnabled(enabled)

    fun setAutoTranslate(auto: Boolean) =
        translationController.setAutoTranslate(auto)

    fun setAutoTranslatePrefetchCount(count: Int) =
        translationController.setAutoTranslatePrefetchCount(count)

    fun setTranslateFromLanguage(language: String) =
        translationController.setTranslateFromLanguage(language)

    fun setTranslateToLanguage(language: String) =
        translationController.setTranslateToLanguage(language)

    fun setOcrModel(model: OcrModel) =
        translationController.setOcrModel(model)

    fun setTranslationInpaintingMode(mode: String) =
        translationController.setTranslationInpaintingMode(mode)

    fun setTranslationEngineCategory(category: TranslationEngineCategory) =
        translationController.setTranslationEngineCategory(category)

    fun setTranslationStandardEngine(engine: StandardEngine) =
        translationController.setTranslationStandardEngine(engine)

    fun setTranslationDeeplApiKey(key: String) =
        translationController.setTranslationDeeplApiKey(key)

    fun setTranslationAiEngine(engine: AiEngine) =
        translationController.setTranslationAiEngine(engine)

    fun setTranslationAiApiKey(key: String) =
        translationController.setTranslationAiApiKey(key)

    fun setTranslationAiBaseUrl(url: String) =
        translationController.setTranslationAiBaseUrl(url)

    fun setTranslationAiModel(model: String) =
        translationController.setTranslationAiModel(model)

    fun fetchAiModels() =
        translationController.fetchAiModels()

    fun cancelTranslationsOnBackground() =
        translationController.cancelTranslationsOnBackground()

    fun resumeTranslationsOnForeground() =
        translationController.resumeTranslationsOnForeground()

    fun onMemoryPressure(level: Int) =
        translationController.onMemoryPressure(level)

    fun isCurrentChapterDownloaded(): Boolean =
        translationController.isCurrentChapterDownloaded()
    private fun observeTranslationState() =
        translationController.observeTranslationState()

    suspend fun observeLiveTranslationStore() =
        translationController.observeLiveTranslationStore()

    fun observePageView(page: ReaderPage): Flow<eu.kanade.translation.model.PageView>? =
        translationController.observePageView(page)
    internal fun resolvePageKey(page: ReaderPage): String {
        return resolveReaderPageTranslationKey(page)
    }

    sealed interface Dialog {
        data object Loading : Dialog
        data object Settings : Dialog
        data object TranslationSettings : Dialog
        data object ReadingModeSelect : Dialog
        data object OrientationModeSelect : Dialog
        data class PageActions(val page: ReaderPage) : Dialog
        data class BatchReaderSwitch(val page: ReaderPage, val force: Boolean? = null) : Dialog
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

@Immutable
data class TranslationSettingsState(
    val enabled: Boolean = false,
    val autoTranslate: Boolean = false,
    val autoTranslatePrefetchCount: Int = 3,
    val translateFromLanguage: String = "",
    val translateToLanguage: String = "",
    val translationRecentLanguagesFrom: ImmutableList<String> = persistentListOf(),
    val translationRecentLanguagesTo: ImmutableList<String> = persistentListOf(),
    val ocrModel: OcrModel = OcrModel.MLKIT,
    val ocrModelEntries: ImmutableMap<OcrModel, String> = persistentMapOf(),
    val inpaintingMode: String = "",
    val engineCategory: TranslationEngineCategory = TranslationEngineCategory.STANDARD,
    val standardEngine: StandardEngine = StandardEngine.GOOGLE,
    val deeplApiKey: String = "",
    val aiEngine: AiEngine = AiEngine.GEMINI,
    val aiApiKey: String = "",
    val aiBaseUrl: String = "",
    val aiModel: String = "",
    val aiRecentModels: ImmutableList<String> = persistentListOf(),
    val aiModelFetchState: AiModelListState = AiModelListState.Idle,
)

private data class AiSubPrefs(
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val recentRaw: String,
)

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
