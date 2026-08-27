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
import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.LeaseAcquisition
import eu.kanade.translation.MemoryPressureClass
import eu.kanade.translation.MemoryPressurePolicy
import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.TranslationManager
import eu.kanade.translation.TranslationPipeline
import eu.kanade.translation.model.PageIndexResolver
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.displayImageName
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.shouldShowTranslationOverlay
import eu.kanade.translation.model.toPageDisplayProjection
import eu.kanade.translation.model.toPageView
import eu.kanade.translation.ocr.OcrModelCatalog
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.scheduling.AutoChapterIdentity
import eu.kanade.translation.scheduling.TranslationScheduler
import eu.kanade.translation.scheduling.TranslationStreamRegistry
import eu.kanade.translation.translator.AiModelFetcher
import eu.kanade.translation.translator.TranslatorComputeClass
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
import java.io.InputStream
import java.lang.ref.WeakReference
import java.time.Instant
import java.util.Date
import java.util.concurrent.atomic.AtomicLong

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
    private val translationScheduler: TranslationScheduler = Injekt.get(),
    private val streamRegistry: TranslationStreamRegistry = Injekt.get(),
    private val chapterCache: ChapterCache = Injekt.get(),
    val translationPreferences: tachiyomi.domain.translation.TranslationPreferences = Injekt.get(),
) : ViewModel() {

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

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

    private val aiModelFetchState = MutableStateFlow<AiModelListState>(AiModelListState.Idle)

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

    private val eventChannel = Channel<Event>()
    val eventFlow = eventChannel.receiveAsFlow()

    private var translationStoreJob: kotlinx.coroutines.Job? = null
    private var translationBatchProgressJob: kotlinx.coroutines.Job? = null
    private var translationStateJob: kotlinx.coroutines.Job? = null
    private var autoSnapshotJob: kotlinx.coroutines.Job? = null

    /** Resolver indirection is invalidated before any chapter/page resources are recycled. */
    private val autoPageResolver = ReaderAutoTranslationPageResolver(chapterCache)

    /**
     * Source readiness is a reader-lifecycle signal, not a page observer. The loader callback
     * keeps only weak references to the reader/chapter and is fenced by this generation.
     */
    private val autoReadinessLock = Any()
    private val autoReadinessGeneration = AtomicLong(0L)
    private var autoReadinessLoader: PageLoader? = null
    private var autoReadinessPokeInFlight = false

    /** Identity of the reader-owned rolling window currently bound to the UI. */
    @Volatile
    private var activeAutoIdentity: AutoChapterIdentity? = null

    /** Fences late emissions from a collector that was replaced by a new window. */
    private val autoSnapshotGeneration = AtomicLong(0L)

    /**
     * Stable-flow emissions can lag a synchronous coordinator replacement. Keep the last
     * accepted scheduler tuple so a same-identity collector cannot publish a lower owner or
     * window version while the flow switches stores.
     */
    private val autoSnapshotFenceLock = Any()
    private var acceptedAutoOwnerVersion: Long? = null
    private var acceptedAutoWindowVersion: Long = -1L
    private var activeAutoOwnerToken: Any? = null

    /** Distinguishes a reopened reader from an older coordinator session. */
    private val autoReaderSessionKey = "reader-${System.identityHashCode(this)}"

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

    /**
     * TachiyomiAT: cached value of the opt-in translation_diagnostics preference.
     * Gates the chatty per-page INFO log in [observeLiveTranslationStore] so the
     * collector's hot path isn't doing string interpolation + log dispatch on
     * every RUNNING stage of every page during a batch run. Read once at VM
     * creation (the pref is not toggled mid-read in practice).
     */
    private val translationDiagnosticsEnabled = translationPreferences.translationDiagnostics().get()

    init {
        // TachiyomiAT: Auto-translate is a per-session convenience, not a
        // persistent default. Force it off on reader entry so a chapter never
        // starts translating the instant the reader opens — the user must opt in
        // via the Translation settings sheet each session. It then stays on while
        // navigating within this reader session.
        translationPreferences.autoTranslate().set(false)

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
            .onEach { enabled ->
                if (enabled && translationPreferences.translationEnabled().get()) {
                    translateCurrentPageForAuto()
                } else {
                    resetAutoTranslationState()
                    val wasActive = getCurrentChapter()?.chapter?.id?.let {
                        translationScheduler.cancelAutoTranslations(it)
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
                        translationManager.cancelAllPageTranslationsOffMain(cancelBatchQueue = true)
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
    ) {
        val manga = manga ?: return
        val chapterId = chapter.chapter.id ?: return
        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        val pages = chapter.pages?.filterIsInstance<ReaderPage>() ?: return
        if (pages.isEmpty()) return

        val keepPageKeys = HashSet<String>()
        val changedPages = linkedSetOf<ReaderPage>()
        val warmRadius = ReaderPageWarmWindow.radiusFor(ReadingMode.fromPreference(getMangaReadingMode()))
        for ((listIndex, page) in pages.withIndex()) {
            val warm = ReaderPageWarmWindow.contains(listIndex, currentIndex, pages.lastIndex, radius = warmRadius)
            if (warm) {
                keepPageKeys += resolvePageKey(page)
                val hadStream = page.translatedStream != null
                attachTranslatedStreamIfWarm(page, manga, chapter, source)
                if (dispatchRefresh && hadStream != (page.translatedStream != null)) {
                    changedPages += page
                }
            } else {
                if (page.translatedStream != null || page.showTranslatedImage) {
                    changedPages += page
                }
                page.translatedStream = null
                page.showTranslatedImage = false
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
            eventChannel.trySend(Event.RefreshTranslationPages(changedPages))
            mutableState.update { it.copy(translationRefreshToken = System.currentTimeMillis()) }
        }
    }

    private fun attachTranslatedStreamIfWarm(
        page: ReaderPage,
        manga: Manga,
        chapter: ReaderChapter,
        source: HttpSource,
    ) {
        val batchActive = chapter.chapter.id?.let { translationManager.isBatchTranslationActive(it) } == true
        if (!batchActive && !isInTranslationWarmWindow(page)) {
            page.translatedStream = null
            page.showTranslatedImage = false
            return
        }
        val translation = page.translation
        page.translatedStream = when {
            translation?.displayImageName != null -> translationManager.getCleanedImageStream(
                manga.title,
                source,
                chapter.chapter.name,
                chapter.chapter.scanlator,
                translation.displayImageName!!,
                pageKey = resolvePageKey(page),
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
    fun attachTranslatedStreamForPage(page: ReaderPage) {
        val manga = manga ?: return
        val chapter = getCurrentChapter() ?: return
        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        attachTranslatedStreamIfWarm(page, manga, chapter, source)
    }

    private fun isInTranslationWarmWindow(page: ReaderPage): Boolean {
        val pages = page.chapter.pages?.filterIsInstance<ReaderPage>() ?: return false
        val pageIndex = pages.indexOfFirst { it === page }.takeIf { it >= 0 } ?: page.index
        val currentIndex = if (page.chapter === getCurrentChapter()) {
            chapterPageIndex
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
                        ch.id!!,
                        ch.name,
                        ch.scanlator,
                        m.title,
                        m.source,
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

    /**
     * Updates the sole reader Auto scheduler entry point. The configured value
     * is an ahead count, so the visible page is submitted separately and the
     * coordinator derives exactly N ordered pages after it.
     */
    private fun handleAutoTranslation(currentPage: ReaderPage) {
        viewModelScope.launchIO {
            if (state.value.viewerChapters?.currChapter !== currentPage.chapter) return@launchIO
            handleAutoTranslationOnIo(currentPage)
        }
    }

    private fun handleAutoTranslationOnIo(currentPage: ReaderPage) {
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
                val owner = WeakReference(this@ReaderViewModel)
                val boundChapter = WeakReference(chapter)
                autoReadinessLoader = pageLoader
                pageLoader.onPageStreamReady = {
                    val viewModel = owner.get()
                    val activeChapter = boundChapter.get()
                    if (viewModel != null && activeChapter != null) {
                        viewModel.onAutoSourceReady(activeChapter, identity, generation, readerGeneration)
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

    private fun resetAutoTranslationState() {
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
    private suspend fun cancelTranslationForChapter(chapter: ReaderChapter) {
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

    fun deleteCurrentChapterTranslation() {
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
            //     coroutine, so the cache-first openOrCreateActiveChapterTranslationStore
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
                eventChannel.trySend(Event.RefreshTranslationPages(readerPages))
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

    fun resetTranslationData(preserveEdits: Boolean) {
        val manga = manga ?: return
        val page = state.value.currentPage as? ReaderPage ?: return
        val chapter = page.chapter.chapter
        val source = sourceManager.get(manga.source) as? HttpSource ?: return

        page.translatedStream = null
        page.translation = null
        page.showTranslatedImage = false
        page.translationToggled = false

        val readerPages = setOf(page)
        eventChannel.trySend(Event.RefreshTranslationPages(readerPages))
        mutableState.update { it.copy(translationRefreshToken = System.currentTimeMillis()) }

        viewModelScope.launchIO {
            val pageKey = resolvePageKey(page)
            translationManager.resetTranslationData(chapter.toDomainChapter()!!, manga, source, pageKey, preserveEdits)
            recomputeTranslationState()
        }
    }

    fun resetInpaintData() {
        val manga = manga ?: return
        val page = state.value.currentPage as? ReaderPage ?: return
        val chapter = page.chapter.chapter
        val source = sourceManager.get(manga.source) as? HttpSource ?: return

        page.translatedStream = null
        page.translation = null
        page.showTranslatedImage = false
        page.translationToggled = false

        val readerPages = setOf(page)
        eventChannel.trySend(Event.RefreshTranslationPages(readerPages))
        mutableState.update { it.copy(translationRefreshToken = System.currentTimeMillis()) }

        viewModelScope.launchIO {
            val pageKey = resolvePageKey(page)
            translationManager.resetInpaintData(chapter.toDomainChapter()!!, manga, source, pageKey)
            recomputeTranslationState()
        }
    }

    fun resetOcrData() {
        val manga = manga ?: return
        val page = state.value.currentPage as? ReaderPage ?: return
        val chapter = page.chapter.chapter
        val source = sourceManager.get(manga.source) as? HttpSource ?: return

        page.translatedStream = null
        page.translation = null
        page.showTranslatedImage = false
        page.translationToggled = false

        val readerPages = setOf(page)
        eventChannel.trySend(Event.RefreshTranslationPages(readerPages))
        mutableState.update { it.copy(translationRefreshToken = System.currentTimeMillis()) }

        viewModelScope.launchIO {
            val pageKey = resolvePageKey(page)
            translationManager.resetOcrData(chapter.toDomainChapter()!!, manga, source, pageKey)
            recomputeTranslationState()
        }
    }

    fun resetEverything() {
        resetOcrData()
    }

    fun translateSinglePage(page: ReaderPage, force: Boolean? = null) {
        val manga = manga ?: run {
            logcat(LogPriority.WARN) { "translateSinglePage: manga is null, cannot translate" }
            return
        }
        // TachiyomiAT: use the page's own chapter context, not getCurrentChapter().
        // The global current-chapter may differ from the page's actual chapter
        // when a cross-chapter page-transition event fires before loadNewChapter
        // completes — translating against the wrong chapter would write results
        // under the wrong store key (skipped-pages bug) and register reader-page
        // streams for a chapter the translator can't match to disk files.
        val chapter = page.chapter.chapter
        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        val pageKey = resolvePageKey(page)
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
            translationScheduler.translatePage(manga, chapter.toDomainChapter()!!, source, pageKey, force = effectiveForce)
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
                translationScheduler.translatePage(manga, chapter.toDomainChapter()!!, source, pageKey, force = effectiveForce)
            }
        } ?: run {
            if (pageChapterDownloaded) {
                // Downloaded chapters may not expose a live ReaderPage.originalStream
                // after holder rebinding. The pipeline can reopen the page from disk,
                // so enqueue the job instead of silently falling through.
                logcat(LogPriority.INFO) {
                    "TachiyomiAT manual translate page request using downloaded chapter fallback: pageKey=$pageKey"
                }
                translationScheduler.translatePage(manga, chapter.toDomainChapter()!!, source, pageKey, force = effectiveForce)
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
    fun cancelSinglePageTranslation(page: ReaderPage) {
        // TachiyomiAT: use the page's own chapter ID so the cancel lands on
        // the correct activePageJobs key — otherwise the cancel would be a
        // no-op for a page whose chapter differs from getCurrentChapter().
        val chapterId = page.chapter.chapter.id ?: return
        val pageKey = resolvePageKey(page)
        translationScheduler.cancelPageTranslation(chapterId, pageKey)
    }

    /**
     * TachiyomiAT: cancels ALL in-flight translation work (single-page, auto and
     * batch), stops the translator engines, and resets the merged translation
     * state to neutral. Backs the "Stop all translation" control in the reader
     * — previously there was no way for the user to stop translation at all
     * short of navigating away or disabling the master toggle.
     */
    fun stopAllTranslation() {
        resetAutoTranslationState()
        batchTranslationState = Translation.State.NOT_TRANSLATED
        liveTranslationState = Translation.State.NOT_TRANSLATED
        recomputeTranslationState()
        viewModelScope.launchIO {
            translationManager.cancelAllPageTranslationsOffMain(cancelBatchQueue = true)
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
    fun retryCurrentBatch(force: Boolean = false) {
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

    fun setTranslationEnabled(enabled: Boolean) {
        translationPreferences.translationEnabled().set(enabled)
    }

    fun setAutoTranslate(auto: Boolean) {
        translationPreferences.autoTranslate().set(auto)
    }

    fun setAutoTranslatePrefetchCount(count: Int) {
        translationPreferences.autoTranslatePrefetchCount().set(count)
    }

    fun setTranslateFromLanguage(language: String) {
        translationPreferences.translateFromLanguage().set(language)
        val recent = TranslationPreferences.decodeRecentLanguages(translationPreferences.translationRecentLanguagesFrom().get())
        val updated = TranslationPreferences.encodeRecentLanguages(listOf(language) + recent)
        translationPreferences.translationRecentLanguagesFrom().set(updated)
    }

    fun setTranslateToLanguage(language: String) {
        translationPreferences.translateToLanguage().set(language)
        val recent = TranslationPreferences.decodeRecentLanguages(translationPreferences.translationRecentLanguagesTo().get())
        val updated = TranslationPreferences.encodeRecentLanguages(listOf(language) + recent)
        translationPreferences.translationRecentLanguagesTo().set(updated)
    }

    fun setOcrModel(model: OcrModel) {
        val fromValue = translationPreferences.translateFromLanguage().get()
        val language = TextRecognizerLanguage.entries.firstOrNull { it.name == fromValue } ?: TextRecognizerLanguage.CHINESE
        OcrModelCatalog.preferenceFor(translationPreferences, language).set(model)
    }

    fun setTranslationInpaintingMode(mode: String) {
        translationPreferences.translationInpaintingMode().set(mode)
    }

    fun setTranslationEngineCategory(category: TranslationEngineCategory) {
        translationPreferences.translationEngineCategory().set(category)
    }

    fun setTranslationStandardEngine(engine: StandardEngine) {
        translationPreferences.translationStandardEngine().set(engine)
    }

    fun setTranslationDeeplApiKey(key: String) {
        translationPreferences.translationDeeplApiKey().set(key)
    }

    fun setTranslationAiEngine(engine: AiEngine) {
        translationPreferences.translationAiEngine().set(engine)
    }

    fun setTranslationAiApiKey(key: String) {
        val engine = translationPreferences.translationAiEngine().get()
        translationPreferences.translationAiApiKey(engine).set(key)
    }

    fun setTranslationAiBaseUrl(url: String) {
        val engine = translationPreferences.translationAiEngine().get()
        translationPreferences.translationAiBaseUrl(engine)?.set(url)
    }

    fun setTranslationAiModel(model: String) {
        val engine = translationPreferences.translationAiEngine().get()
        translationPreferences.translationAiModel(engine).set(model)
        val recentPref = translationPreferences.translationAiRecentModels(engine)
        val recentModels = TranslationPreferences.decodeRecentModels(recentPref.get())
        recentPref.set(TranslationPreferences.encodeRecentModels(listOf(model) + recentModels))
    }

    fun fetchAiModels() {
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
    fun cancelTranslationsOnBackground() {
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
    fun resumeTranslationsOnForeground() {
        // Foreground resume is a real lifecycle/memory-recovery signal for a
        // fully Deferred(Memory) rolling window; it must not wait for a page
        // change before asking the coordinator to reconcile.
        translationManager.reconcileAutoWindow()
        if (!translationPreferences.translationEnabled().get()) return
        if (!translationPreferences.autoTranslate().get()) return
        val chapter = getCurrentChapter() ?: return
        if (chapterPageIndex < 0) return
        updateTranslationWorkingSet(chapter, chapterPageIndex, dispatchRefresh = true)
        translateCurrentPageForAuto()
    }

    fun onMemoryPressure(level: Int) {
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

    fun isCurrentChapterDownloaded(): Boolean {
        val manga = manga ?: return false
        val chapter = getCurrentChapter()?.chapter ?: return false
        return downloadManager.isChapterDownloaded(chapter.name, chapter.scanlator, manga.title, manga.source)
    }

    private fun observeTranslationState() {
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
        if (translationManager.isBatchTranslationRetained(chapterId)) {
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
            val lease = store.tryAcquirePageStageLease(pageKey, PageStage.Ocr, PageWriteOrigin.READER_ADHOC)
            if (lease !is LeaseAcquisition.Granted) continue
            try {
                store.updatePageFromCurrentSnapshot(pageKey, "reader stranded-page sweep") { existing ->
                    val safe = existing ?: return@updatePageFromCurrentSnapshot pt
                    if (safe.runGeneration != store.currentGeneration) return@updatePageFromCurrentSnapshot safe
                    val safeTerminal = safe.ocrStatus == StageStatus.FAILED ||
                        safe.inpaintStatus == StageStatus.FAILED ||
                        safe.translationStatus == StageStatus.FAILED ||
                        safe.renderStatus == StageStatus.FAILED ||
                        safe.displayImageName != null ||
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
                store.releasePageStageLease(pageKey, PageWriteOrigin.READER_ADHOC)
            }
        }
    }

    suspend fun observeLiveTranslationStore() {
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
        val store = translationManager.openOrCreateActiveChapterTranslationStoreSuspend(
            chapterId,
            chapter.name,
            chapter.scanlator,
            manga.title,
            source,
            manga.id,
        ) ?: return
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
                    updateTranslationWorkingSet(current, chapterPageIndex, dispatchRefresh = false)
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

    fun observePageView(page: ReaderPage): Flow<eu.kanade.translation.model.PageView>? {
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
                        eventChannel.trySend(Event.RefreshTranslationPages(setOf(page)))
                    }
                }
            }
            .map { it.toPageView() }
            .distinctUntilChanged()
    }

    /**
     * Resolves the page key the translator uses to write live updates, in order of
     * reliability:
     * 1. [ReaderPage.sourceFileName] — the local filename set by the page loader.
     * 2. [PageTranslation.sourceFileName] — populated once a translation exists.
     * 3. [ReaderPage.imageUrl] / [ReaderPage.url] — unstable fallbacks for HTTP sources.
     *
     * Returns an empty string only as a last resort so callers can still index without NPEs.
     */
    private fun resolvePageKey(page: ReaderPage): String {
        return resolveReaderPageTranslationKey(page)
    }

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
