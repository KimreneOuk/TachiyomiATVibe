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
import eu.kanade.translation.diagnostics.TelemetryTrace
import eu.kanade.translation.engines.inpainting.InpaintingHardwareOverride
import eu.kanade.translation.engines.vision.ocr.OcrModelCatalog
import eu.kanade.translation.model.PageIndexResolver
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isTranslationDisplayReady
import eu.kanade.translation.model.shouldShowTranslationOverlay
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.pipeline.execution.TranslationStreamRegistry
import eu.kanade.translation.diagnostics.AutoSmoothnessTelemetry
import eu.kanade.translation.workflow.TranslationManager
import eu.kanade.translation.workflow.TranslationSessionState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
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
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.PaddleOcrComputeDevice
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.i18n.at.ATMR
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Presenter used by the activity to perform background operations.
 */
class ReaderViewModel @JvmOverloads constructor(
    private val savedState: SavedStateHandle,
    internal val sourceManager: SourceManager = Injekt.get(),
    internal val downloadManager: DownloadManager = Injekt.get(),
    internal val downloadProvider: DownloadProvider = Injekt.get(),
    internal val imageSaver: ImageSaver = Injekt.get(),
    preferences: BasePreferences = Injekt.get(),
    val readerPreferences: ReaderPreferences = Injekt.get(),
    internal val basePreferences: BasePreferences = Injekt.get(),
    internal val downloadPreferences: DownloadPreferences = Injekt.get(),
    internal val trackPreferences: TrackPreferences = Injekt.get(),
    internal val trackChapter: TrackChapter = Injekt.get(),
    internal val getManga: GetManga = Injekt.get(),
    internal val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    internal val getNextChapters: GetNextChapters = Injekt.get(),
    internal val upsertHistory: UpsertHistory = Injekt.get(),
    internal val updateChapter: UpdateChapter = Injekt.get(),
    internal val setMangaViewerFlags: SetMangaViewerFlags = Injekt.get(),
    internal val translationManager: TranslationManager = Injekt.get(),
    internal val streamRegistry: TranslationStreamRegistry = Injekt.get(),
    internal val chapterCache: ChapterCache = Injekt.get(),
    val translationPreferences: tachiyomi.domain.translation.TranslationPreferences = Injekt.get(),
) : ViewModel() {

    internal val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    val translationSessionState: kotlinx.coroutines.flow.StateFlow<TranslationSessionState>
        get() = translationManager.sessionCoordinator.state

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

    private val paddleOcrComputeDeviceOverride = MutableStateFlow<PaddleOcrComputeDevice?>(null)

    val translationSettingsState: kotlinx.coroutines.flow.StateFlow<TranslationSettingsState> = combine(
        combine(
            combine(
                translationPreferences.translationEnabled().changes(),
                translationPreferences.autoTranslate().changes(),
                translationPreferences.translateFromLanguage().changes(),
            ) { enabled, auto, from -> Triple(enabled, auto, from) },
            combine(
                translationPreferences.translationRecentLanguagesFrom().changes(),
                translationPreferences.translateToLanguage().changes(),
                translationPreferences.translationRecentLanguagesTo().changes(),
                combine(
                    translationPreferences.translationInpaintingMode().changes(),
                    translationPreferences.translationInpaintingNeuralModel().changes(),
                ) { inpaintingMode, neuralModel -> inpaintingMode to neuralModel },
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
        combine(
            translationPreferences.paddleOcrComputeDevice().changes(),
            paddleOcrComputeDeviceOverride,
        ) { defaultDevice, override -> defaultDevice to override },
    ) { part1, part2, ocrInfo, aiSubPrefs, paddleDevice ->
        val (q1, q2) = part1
        val (q3, fetchState) = part2
        val (ocrModel, ocrModelEntries) = ocrInfo

        val recentLangsFrom = TranslationPreferences.decodeRecentLanguages(q2.a).toImmutableList()
        val recentLangsTo = TranslationPreferences.decodeRecentLanguages(q2.c).toImmutableList()
        val recentAiModels = TranslationPreferences.decodeRecentModels(aiSubPrefs.recentRaw).toImmutableList()

        TranslationSettingsState(
            enabled = q1.first,
            autoTranslate = q1.second,
            translateFromLanguage = q1.third,
            translateToLanguage = q2.b,
            translationRecentLanguagesFrom = recentLangsFrom,
            translationRecentLanguagesTo = recentLangsTo,
            ocrModel = ocrModel,
            ocrModelEntries = ocrModelEntries,
            paddleOcrDefaultComputeDevice = paddleDevice.first,
            paddleOcrComputeDeviceOverride = paddleDevice.second,
            inpaintingMode = q2.d.first,
            inpaintingNeuralModel = q2.d.second,
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

    enum class QueueStage { QUEUED, OCR, INPAINT, TRANSLATE, DONE, FAILED }

    /**
     * Maps the live per-page store into an ordered, index-resolved list for the
     * queue view. Pages are ordered by their 1-based chapter index (parsed from
     * the page key where possible, else by insertion order). Only pages that have
     * an entry in the store (i.e. are/were being translated) appear — untouched
     * pages are omitted to keep the list focused on active work.
     */
    private fun buildQueuedPageInfo(
        pages: Map<String, PageTranslationView>,
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

    /** Derives the display stage from a page's processing statuses. */
    private fun stageOf(pt: PageTranslationView): QueueStage? {
        // The store is persisted history. Only active work and actionable
        // failures belong in the queue view; old pending/cancelled/textless/done
        // rows are not live queue reservations.
        if (pt.hasRenderedResult) return null
        // First RUNNING stage wins, checked newest-first so the label reflects
        // the current operation rather than an earlier uncleared status.
        if (pt.translationStatus == StageStatus.RUNNING) return QueueStage.TRANSLATE
        if (pt.inpaintStatus == StageStatus.RUNNING) return QueueStage.INPAINT
        if (pt.ocrStatus == StageStatus.RUNNING) return QueueStage.OCR
        // Any FAILED stage with no result yet → Failed.
        if (pt.ocrStatus == StageStatus.FAILED ||
            pt.inpaintStatus == StageStatus.FAILED ||
            pt.translationStatus == StageStatus.FAILED
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
    internal var currentTranslationStore: eu.kanade.translation.persistence.ChapterTranslationStore? = null

    /** Resolver indirection is invalidated before any chapter/page resources are recycled. */
    internal val autoPageResolver = ReaderPageWorkResolver(chapterCache)

    /**
     * Source readiness is a reader-lifecycle signal, not a page observer. The loader callback
     * keeps only weak references to the reader/chapter and is fenced by this generation.
     */
    internal val autoReadinessLock = Any()
    internal val autoReadinessGeneration = AtomicLong(0L)
    internal var autoReadinessLoader: PageLoader? = null
    internal var autoReadinessPokeInFlight = false

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
    // stage-emission from the live store (OCR→translate→inpaint for
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
    internal var chapterId = savedState.get<Long>("chapter_id") ?: -1L
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
    internal var loader: ChapterLoader? = null

    /**
     * The time the chapter was started reading
     */
    internal var chapterReadStartTime: Long? = null

    internal var chapterToDownload: Download? = null

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
    internal val chapterList by lazy {
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

    internal val incognitoMode = preferences.incognitoMode().get()
    internal val downloadAheadAmount = downloadPreferences.autoDownloadWhileReading().get()

    /**
     * TachiyomiAT: cached value of the opt-in translation_diagnostics preference.
     * Gates the chatty per-page INFO log in [observeLiveTranslationStore] so the
     * collector's hot path isn't doing string interpolation + log dispatch on
     * every RUNNING stage of every page during a batch run. Read once at VM
     * creation (the pref is not toggled mid-read in practice).
     */
    internal val translationDiagnosticsEnabled = translationPreferences.translationDiagnostics().get()

    internal val translationController by lazy { ReaderTranslationController(this) }
    internal val pageArrivalTimes = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    init {
        translationManager.setInpaintingHardwareOverride(InpaintingHardwareOverride.CPU)
        translationManager.setReaderPaddleOcrComputeDeviceOverride(null)
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
                translationController.onReaderAutoToggled(enabled)
            }
            .launchIn(viewModelScope)

        // Compute-epoch rebind trigger: when the active translator family
        // changes, drop the captured queue session so the next dispatch
        // rebinds it with the fresh compute class (the queue's rebind drains
        // the old epoch's demand set), then re-kick the current page.
        combine(
            translationPreferences.translationEngineCategory().changes(),
            translationPreferences.translationStandardEngine().changes(),
            translationPreferences.translationAiEngine().changes(),
        ) { _, _, _ -> Unit }
            .onEach {
                translationController.rebindForComputeEpochChange()
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

    internal fun updateTranslationWorkingSet(
        chapter: ReaderChapter,
        currentIndex: Int,
        dispatchRefresh: Boolean,
    ) {
        viewModelScope.launch {
            translationController.updateTranslationWorkingSet(chapter, currentIndex, dispatchRefresh)
        }
    }

    private suspend fun attachTranslatedStreamIfWarm(
        page: ReaderPage,
        manga: Manga,
        chapter: ReaderChapter,
        source: HttpSource,
    ) = translationController.attachTranslatedStreamIfWarm(page, manga, chapter, source)

    /**
     * Delegates the holder-facing stream attachment seam to the translation controller.
     */
    suspend fun attachTranslatedStreamForPage(page: ReaderPage) =
        translationController.attachTranslatedStreamForPage(page)

    private fun isInTranslationWarmWindow(page: ReaderPage): Boolean =
        translationController.isInTranslationWarmWindow(page)

    override fun onCleared() {
        translationManager.setReaderPaddleOcrComputeDeviceOverride(null)
        // Invalidate reader-owned callbacks/resolvers before requesting joined teardown. The
        // manager's Deferred is owned by applicationScope, so this cleanup still runs after the
        // ViewModel scope has been cancelled and only then recycles ReaderPage/loaders.
        resetAutoTranslationState()
        val currentChapters = state.value.viewerChapters
        val pendingDownload = chapterToDownload
        translationStoreJob?.cancel()
        translationBatchProgressJob?.cancel()
        translationStateJob?.cancel()
        currentTranslationStore = null
        val readerStop = translationManager.requestReaderStop("reader closed")
        registerReaderCleanupAfterStop(readerStop) {
            translationManager.setInpaintingHardwareOverride(InpaintingHardwareOverride.CPU)
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
    private val navigationController by lazy { ReaderNavigationController(this) }
    private fun resetAutoTranslationState() = translationController.resetAutoTranslationState()
    private fun translateCurrentPageForAuto() = translationController.translateCurrentPageForAuto()

    fun onActivityFinish() = navigationController.onActivityFinish()
    fun needsInit() = navigationController.needsInit()
    suspend fun init(mangaId: Long, initialChapterId: Long): Result<Boolean> = navigationController.init(mangaId, initialChapterId)
    suspend fun preload(chapter: ReaderChapter) = navigationController.preload(chapter)
    fun onViewerLoaded(viewer: Viewer?) = navigationController.onViewerLoaded(viewer)
    fun onPageSelected(page: ReaderPage) = navigationController.onPageSelected(page)
    fun restartReadTimer() = navigationController.restartReadTimer()
    fun flushReadTimer() = navigationController.flushReadTimer()
    suspend fun loadNextChapter() = navigationController.loadNextChapter()
    suspend fun loadPreviousChapter() = navigationController.loadPreviousChapter()
    internal fun getCurrentChapter(): ReaderChapter? = navigationController.getCurrentChapter()
    fun getSource() = navigationController.getSource()
    fun getChapterUrl(): String? = navigationController.getChapterUrl()
    fun toggleChapterBookmark() = navigationController.toggleChapterBookmark()
    fun getMangaReadingMode(resolveDefault: Boolean = true): Int = navigationController.getMangaReadingMode(resolveDefault)
    fun setMangaReadingMode(readingMode: ReadingMode) = navigationController.setMangaReadingMode(readingMode)
    fun getMangaOrientation(resolveDefault: Boolean = true): Int = navigationController.getMangaOrientation(resolveDefault)
    fun setMangaOrientationType(orientation: ReaderOrientation) = navigationController.setMangaOrientationType(orientation)
    fun toggleCropBorders(): Boolean = navigationController.toggleCropBorders()
    fun showMenus(visible: Boolean) = navigationController.showMenus(visible)
    fun showLoadingDialog() = navigationController.showLoadingDialog()
    fun openReadingModeSelectDialog() = navigationController.openReadingModeSelectDialog()
    fun openOrientationModeSelectDialog() = navigationController.openOrientationModeSelectDialog()
    fun openPageDialog(page: ReaderPage) = navigationController.openPageDialog(page)
    fun openSettingsDialog() = navigationController.openSettingsDialog()
    fun openTranslationSettingsDialog() = navigationController.openTranslationSettingsDialog()
    fun closeDialog() = navigationController.closeDialog()
    fun dismissBatchReaderSwitch() = navigationController.dismissBatchReaderSwitch()
    fun confirmBatchReaderSwitch(request: Dialog.BatchReaderSwitch) = navigationController.confirmBatchReaderSwitch(request)
    fun setBrightnessOverlayValue(value: Int) = navigationController.setBrightnessOverlayValue(value)
    fun saveImage() = navigationController.saveImage()
    fun shareImage(copyToClipboard: Boolean) = navigationController.shareImage(copyToClipboard)
    fun setAsCover() = navigationController.setAsCover()
    enum class SetAsCoverResult {
        Success,
        AddToLibraryFirst,
        Error,
    }

    sealed interface SaveImageResult {
        class Success(val uri: Uri) : SaveImageResult
        class Error(val error: Throwable) : SaveImageResult
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

    fun setTranslateFromLanguage(language: String) =
        translationController.setTranslateFromLanguage(language)

    fun setTranslateToLanguage(language: String) =
        translationController.setTranslateToLanguage(language)

    fun setOcrModel(model: OcrModel) =
        translationController.setOcrModel(model)

    fun setPaddleOcrComputeDeviceOverride(device: PaddleOcrComputeDevice?) {
        translationManager.setReaderPaddleOcrComputeDeviceOverride(device)
        paddleOcrComputeDeviceOverride.value = device
    }

    fun setTranslationInpaintingMode(mode: String) =
        translationController.setTranslationInpaintingMode(mode)

    fun setTranslationInpaintingNeuralModel(model: NeuralInpaintModel) =
        translationController.setTranslationInpaintingNeuralModel(model)

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

private data class AiSubPrefs(
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val recentRaw: String,
)

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
