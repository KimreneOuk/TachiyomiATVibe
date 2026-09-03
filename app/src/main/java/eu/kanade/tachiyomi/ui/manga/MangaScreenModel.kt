package eu.kanade.tachiyomi.ui.manga

import android.app.Application
import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.util.fastAny
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.asState
import eu.kanade.core.util.addOrRemove
import eu.kanade.core.util.insertSeparators
import eu.kanade.domain.chapter.interactor.GetAvailableScanlators
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.SetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.chaptersFiltered
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.ChapterTranslationAction
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import eu.kanade.tachiyomi.util.removeCovers
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.translation.TranslationManager
import eu.kanade.translation.model.ChapterQueuePreflight
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.model.TranslationSettingsSummary
import eu.kanade.translation.model.TranslationUiProjection
import eu.kanade.translation.model.snapshotTranslationSummary
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.chapter.service.calculateChapterGap
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.floor

class MangaScreenModel(
    private val context: Context,
    private val lifecycle: Lifecycle,
    private val mangaId: Long,
    private val isFromSource: Boolean,
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val trackPreferences: TrackPreferences = Injekt.get(),
    readerPreferences: ReaderPreferences = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
    private val trackChapter: TrackChapter = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    // TachiyomiAT
    private val translationManager: TranslationManager = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
    private val downloadCache: DownloadCache = Injekt.get(),
    private val getMangaAndChapters: GetMangaWithChapters = Injekt.get(),
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga = Injekt.get(),
    private val getAvailableScanlators: GetAvailableScanlators = Injekt.get(),
    private val getExcludedScanlators: GetExcludedScanlators = Injekt.get(),
    private val setExcludedScanlators: SetExcludedScanlators = Injekt.get(),
    private val setMangaChapterFlags: SetMangaChapterFlags = Injekt.get(),
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags = Injekt.get(),
    private val setReadStatus: SetReadStatus = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val syncChaptersWithSource: SyncChaptersWithSource = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val addTracks: AddTracks = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val filterChaptersForDownload: FilterChaptersForDownload = Injekt.get(),
    val snackbarHostState: SnackbarHostState = SnackbarHostState(),
) : StateScreenModel<MangaScreenModel.State>(State.Loading) {

    private val successState: State.Success?
        get() = state.value as? State.Success

    val manga: Manga?
        get() = successState?.manga

    val source: Source?
        get() = successState?.source

    private val isFavorited: Boolean
        get() = manga?.favorite ?: false

    private val allChapters: List<ChapterList.Item>?
        get() = successState?.chapters

    private val filteredChapters: List<ChapterList.Item>?
        get() = successState?.processedChapters

    val chapterSwipeStartAction = libraryPreferences.swipeToEndAction().get()
    val chapterSwipeEndAction = libraryPreferences.swipeToStartAction().get()
    var autoTrackState = trackPreferences.autoUpdateTrackOnMarkRead().get()

    private val skipFiltered by readerPreferences.skipFiltered().asState(screenModelScope)

    val isUpdateIntervalEnabled =
        LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in libraryPreferences.autoUpdateMangaRestrictions().get()

    private val selectedPositions: Array<Int> = arrayOf(-1, -1) // first and last selected index in list
    private val selectedChapterIds: HashSet<Long> = HashSet()
    private var pendingTranslationGroup: List<ChapterList.Item> = emptyList()

    /**
     * Helper function to update the UI state only if it's currently in success state
     */
    private inline fun updateSuccessState(func: (State.Success) -> State.Success) {
        mutableState.update {
            when (it) {
                State.Loading -> it
                is State.Success -> func(it)
            }
        }
    }

    init {
        screenModelScope.launchIO {
            combine(
                getMangaAndChapters.subscribe(mangaId, applyScanlatorFilter = true).distinctUntilChanged(),
                downloadCache.changes,
                downloadManager.queueState,
                // TachiyomiAT
                translationManager.queueState,
                translationManager.pendingTranslationRequests,
            ) { mangaAndChapters, _, _, _, _ -> mangaAndChapters }
                .flowWithLifecycle(lifecycle)
                .collectLatest { (manga, chapters) ->
                    updateSuccessState {
                        it.copy(
                            manga = manga,
                            // T912 ANR fix: the per-downloaded-chapter
                            // getChapterTranslationStatus query inside
                            // toChapterListItems is now suspend and runs here
                            // on the IO collector — it used to park Main via
                            // runBlocking in the durable resolver.
                            chapters = chapters.toChapterListItems(manga),
                        )
                    }
                }
        }

        screenModelScope.launchIO {
            getExcludedScanlators.subscribe(mangaId)
                .flowWithLifecycle(lifecycle)
                .distinctUntilChanged()
                .collectLatest { excludedScanlators ->
                    updateSuccessState {
                        it.copy(excludedScanlators = excludedScanlators)
                    }
                }
        }

        screenModelScope.launchIO {
            getAvailableScanlators.subscribe(mangaId)
                .flowWithLifecycle(lifecycle)
                .distinctUntilChanged()
                .collectLatest { availableScanlators ->
                    updateSuccessState {
                        it.copy(availableScanlators = availableScanlators)
                    }
                }
        }

        observeDownloads()
        // TachiyomiAT
        observeTranslations()
        observeTranslationRequests()

        screenModelScope.launchIO {
            val manga = getMangaAndChapters.awaitManga(mangaId)
            val chapters = getMangaAndChapters.awaitChapters(mangaId, applyScanlatorFilter = true)
                .toChapterListItems(manga)

            if (!manga.favorite) {
                setMangaDefaultChapterFlags.await(manga)
            }

            val needRefreshInfo = !manga.initialized
            val needRefreshChapter = chapters.isEmpty()

            // Show what we have earlier
            mutableState.update {
                State.Success(
                    manga = manga,
                    source = Injekt.get<SourceManager>().getOrStub(manga.source),
                    isFromSource = isFromSource,
                    chapters = chapters,
                    availableScanlators = getAvailableScanlators.await(mangaId),
                    excludedScanlators = getExcludedScanlators.await(mangaId),
                    isRefreshingData = needRefreshInfo || needRefreshChapter,
                    dialog = null,
                )
            }

            // Start observe tracking since it only needs mangaId
            observeTrackers()

            // Fetch info-chapters when needed
            if (screenModelScope.isActive) {
                val fetchFromSourceTasks = listOf(
                    async { if (needRefreshInfo) fetchMangaFromSource() },
                    async { if (needRefreshChapter) fetchChaptersFromSource() },
                )
                fetchFromSourceTasks.awaitAll()
            }

            // Initial loading finished
            updateSuccessState { it.copy(isRefreshingData = false) }
        }
    }

    fun fetchAllFromSource(manualFetch: Boolean = true) {
        screenModelScope.launch {
            updateSuccessState { it.copy(isRefreshingData = true) }
            val fetchFromSourceTasks = listOf(
                async { fetchMangaFromSource(manualFetch) },
                async { fetchChaptersFromSource(manualFetch) },
            )
            fetchFromSourceTasks.awaitAll()
            updateSuccessState { it.copy(isRefreshingData = false) }
        }
    }

    // Manga info - start

    /**
     * Fetch manga information from source.
     */
    private suspend fun fetchMangaFromSource(manualFetch: Boolean = false) {
        val state = successState ?: return
        try {
            withIOContext {
                val networkManga = state.source.getMangaDetails(state.manga.toSManga())
                updateManga.awaitUpdateFromSource(state.manga, networkManga, manualFetch)
            }
        } catch (e: Throwable) {
            // Ignore early hints "errors" that aren't handled by OkHttp
            if (e is HttpException && e.code == 103) return

            logcat(LogPriority.ERROR, e)
            screenModelScope.launch {
                snackbarHostState.showSnackbar(message = with(context) { e.formattedMessage })
            }
        }
    }

    fun toggleFavorite() {
        toggleFavorite(
            onRemoved = {
                screenModelScope.launch {
                    if (!hasDownloads()) return@launch
                    val result = snackbarHostState.showSnackbar(
                        message = context.stringResource(MR.strings.delete_downloads_for_manga),
                        actionLabel = context.stringResource(MR.strings.action_delete),
                        withDismissAction = true,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        deleteDownloads()
                    }
                }
            },
        )
    }

    /**
     * Update favorite status of manga, (removes / adds) manga (to / from) library.
     */
    fun toggleFavorite(
        onRemoved: () -> Unit,
        checkDuplicate: Boolean = true,
    ) {
        val state = successState ?: return
        screenModelScope.launchIO {
            val manga = state.manga

            if (isFavorited) {
                // Remove from library
                if (updateManga.awaitUpdateFavorite(manga.id, false)) {
                    // Remove covers and update last modified in db
                    if (manga.removeCovers() != manga) {
                        updateManga.awaitUpdateCoverLastModified(manga.id)
                    }
                    withUIContext { onRemoved() }
                }
            } else {
                // Add to library
                // First, check if duplicate exists if callback is provided
                if (checkDuplicate) {
                    val duplicate = getDuplicateLibraryManga.await(manga).getOrNull(0)

                    if (duplicate != null) {
                        updateSuccessState { it.copy(dialog = Dialog.DuplicateManga(manga, duplicate)) }
                        return@launchIO
                    }
                }

                // Now check if user previously set categories, when available
                val categories = getCategories()
                val defaultCategoryId = libraryPreferences.defaultCategory().get().toLong()
                val defaultCategory = categories.find { it.id == defaultCategoryId }
                when {
                    // Default category set
                    defaultCategory != null -> {
                        val result = updateManga.awaitUpdateFavorite(manga.id, true)
                        if (!result) return@launchIO
                        moveMangaToCategory(defaultCategory)
                    }

                    // Automatic 'Default' or no categories
                    defaultCategoryId == 0L || categories.isEmpty() -> {
                        val result = updateManga.awaitUpdateFavorite(manga.id, true)
                        if (!result) return@launchIO
                        moveMangaToCategory(null)
                    }

                    // Choose a category
                    else -> showChangeCategoryDialog()
                }

                // Finally match with enhanced tracking when available
                addTracks.bindEnhancedTrackers(manga, state.source)
            }
        }
    }

    fun showChangeCategoryDialog() {
        val manga = successState?.manga ?: return
        screenModelScope.launch {
            val categories = getCategories()
            val selection = getMangaCategoryIds(manga)
            updateSuccessState { successState ->
                successState.copy(
                    dialog = Dialog.ChangeCategory(
                        manga = manga,
                        initialSelection = categories.mapAsCheckboxState { it.id in selection }.toImmutableList(),
                    ),
                )
            }
        }
    }

    fun showSetFetchIntervalDialog() {
        val manga = successState?.manga ?: return
        updateSuccessState {
            it.copy(dialog = Dialog.SetFetchInterval(manga))
        }
    }

    fun setFetchInterval(manga: Manga, interval: Int) {
        screenModelScope.launchIO {
            if (
                updateManga.awaitUpdateFetchInterval(
                    // Custom intervals are negative
                    manga.copy(fetchInterval = -interval),
                )
            ) {
                val updatedManga = mangaRepository.getMangaById(manga.id)
                updateSuccessState { it.copy(manga = updatedManga) }
            }
        }
    }

    /**
     * Returns true if the manga has any downloads.
     */
    private fun hasDownloads(): Boolean {
        val manga = successState?.manga ?: return false
        return downloadManager.getDownloadCount(manga) > 0
    }

    /**
     * Deletes all the downloads for the manga.
     */
    private fun deleteDownloads() {
        val state = successState ?: return
        downloadManager.deleteManga(state.manga, state.source)
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    suspend fun getCategories(): List<Category> {
        return getCategories.await().filterNot { it.isSystemCategory }
    }

    /**
     * Gets the category id's the manga is in, if the manga is not in a category, returns the default id.
     *
     * @param manga the manga to get categories from.
     * @return Array of category ids the manga is in, if none returns default id
     */
    private suspend fun getMangaCategoryIds(manga: Manga): List<Long> {
        return getCategories.await(manga.id)
            .map { it.id }
    }

    fun moveMangaToCategoriesAndAddToLibrary(manga: Manga, categories: List<Long>) {
        moveMangaToCategory(categories)
        if (manga.favorite) return

        screenModelScope.launchIO {
            updateManga.awaitUpdateFavorite(manga.id, true)
        }
    }

    /**
     * Move the given manga to categories.
     *
     * @param categories the selected categories.
     */
    private fun moveMangaToCategories(categories: List<Category>) {
        val categoryIds = categories.map { it.id }
        moveMangaToCategory(categoryIds)
    }

    private fun moveMangaToCategory(categoryIds: List<Long>) {
        screenModelScope.launchIO {
            setMangaCategories.await(mangaId, categoryIds)
        }
    }

    /**
     * Move the given manga to the category.
     *
     * @param category the selected category, or null for default category.
     */
    private fun moveMangaToCategory(category: Category?) {
        moveMangaToCategories(listOfNotNull(category))
    }

    // Manga info - end

    // Chapters list - start

    private fun observeDownloads() {
        screenModelScope.launchIO {
            downloadManager.statusFlow()
                .filter { it.manga.id == successState?.manga?.id }
                .catch { error -> logcat(LogPriority.ERROR, error) }
                .flowWithLifecycle(lifecycle)
                .collect {
                    withUIContext {
                        updateDownloadState(it)
                    }
                }
        }

        screenModelScope.launchIO {
            downloadManager.progressFlow()
                .filter { it.manga.id == successState?.manga?.id }
                .catch { error -> logcat(LogPriority.ERROR, error) }
                .flowWithLifecycle(lifecycle)
                .collect {
                    withUIContext {
                        updateDownloadState(it)
                    }
                }
        }
    }

    // TachiyomiAT
    private fun observeTranslations() {
        screenModelScope.launchIO {
            translationManager.statusFlow()
                .filter { it.manga.id == successState?.manga?.id }
                .catch { error -> logcat(LogPriority.ERROR, error) }
                .flowWithLifecycle(lifecycle)
                .collect {
                    withUIContext {
                        updateTranslationState(it)
                    }
                }
        }
    }

    private fun observeTranslationRequests() {
        screenModelScope.launchIO {
            translationManager.pendingTranslationRequests
                .flowWithLifecycle(lifecycle)
                .collectLatest { requests ->
                    withUIContext {
                        updateTranslationRequests(requests)
                    }
                }
        }
    }

    // TachiyomiAT: active per-chapter batch-progress collectors. Keyed by
    // chapterId so we start one collector when a chapter begins translating and
    // stop it when it leaves the translating state, feeding (done,total) into
    // the chapter list item for the determinate "12/40" indicator.
    private val translationProgressJobs = mutableMapOf<Long, kotlinx.coroutines.Job>()

    // TachiyomiAT T911 slice 1: keyed retention of the last live/terminal batch
    // snapshot so full chapter-list rebuilds (download cache/queue, translation
    // queue, pending request emissions) and collector cancellation at terminal
    // status cannot erase an unchanged live or terminal snapshot.
    private val translationSnapshots = ChapterTranslationSnapshotRegistry()

    private fun observeTranslationProgress(chapterId: Long) {
        if (translationProgressJobs[chapterId]?.isActive == true) return
        translationProgressJobs[chapterId] = screenModelScope.launchIO {
            val chapterItem = successState?.chapters?.firstOrNull { it.id == chapterId }
            val manga = successState?.manga
            val currentSource = successState?.source
            if (chapterItem != null && manga != null && currentSource != null) {
                translationManager.openOrCreateActiveChapterTranslationStoreSuspend(
                    chapterId = chapterId,
                    chapterName = chapterItem.chapter.name,
                    scanlator = chapterItem.chapter.scanlator,
                    mangaTitle = manga.title,
                    source = currentSource,
                    mangaId = manga.id,
                )
            }
            translationManager.observeBatchProgress(chapterId)
                .distinctUntilChanged()
                .catch { error -> logcat(LogPriority.ERROR, error) }
                .flowWithLifecycle(lifecycle)
                .collect { progress ->
                    withUIContext { updateTranslationProgress(chapterId, progress) }
                }
        }
    }

    private fun stopTranslationProgress(chapterId: Long) {
        translationProgressJobs.remove(chapterId)?.cancel()
        // Keep the terminal snapshot in the open progress sheet so the user can
        // see whether automatic Pass 2 completed or failed after the last page
        // rendered. A later batch replaces it when its tracker emits again.
    }

    private fun updateTranslationProgress(chapterId: Long, progress: TranslationProgressSnapshot?) {
        // TachiyomiAT T911 slice 1: retain the snapshot in keyed screen-model
        // state before it reaches the item, so a later full list rebuild reads
        // it back through the registry.
        translationSnapshots.remember(chapterId, progress)
        reconcileAbortedBatch(chapterId, progress)
        updateSuccessState { successState ->
            val idx = successState.chapters.indexOfFirst { it.id == chapterId }
            if (idx < 0) return@updateSuccessState successState
            val item = successState.chapters[idx]
            if (item.translationProgress == progress) return@updateSuccessState successState
            val newChapters = successState.chapters.toMutableList().apply {
                set(idx, item.copy(translationProgress = progress))
            }
            successState.copy(chapters = newChapters)
        }
    }

    // TachiyomiAT T918: a batch cancelled mid-run removes its queue entry, and
    // the removed entry's statusFlow simply stops (no terminal emission), so
    // the projected chapter state stays stranded at QUEUE/TRANSLATING/PAUSED
    // forever — the indicator routes every tap into the progress drawer and
    // offers no restart. When the observed snapshot is terminal-aborted and
    // the queue holds no entry for the chapter anymore, reconcile the stale
    // projection to its restartable state. Reconciliation happens ONLY for a
    // stranded state: a terminal/idle projection is already honest and a
    // newly queued batch (guard below) must never be clobbered by the stale
    // abort.
    private fun reconcileAbortedBatch(chapterId: Long, progress: TranslationProgressSnapshot?) {
        if (progress?.aborted != true) return
        if (translationManager.getQueuedTranslationOrNull(chapterId) != null) return
        updateSuccessState { successState ->
            val idx = successState.chapters.indexOfFirst { it.id == chapterId }
            if (idx < 0) return@updateSuccessState successState
            val item = successState.chapters[idx]
            val reconciled = TranslationUiProjection.reconcileAbortedBatchState(item.translationState)
                ?: return@updateSuccessState successState
            val newChapters = successState.chapters.toMutableList().apply {
                set(idx, item.copy(translationState = reconciled))
            }
            successState.copy(chapters = newChapters)
        }
    }

    // TachiyomiAT
    private fun updateTranslationState(translation: Translation) {
        // Start/stop the per-chapter batch-progress collector so the "12/40"
        // indicator only tracks chapters actively translating, and stops (and
        // resets to no-fraction) once the chapter reaches a terminal state.
        val chapterId = translation.chapter.id
        when (translation.status) {
            Translation.State.QUEUE,
            Translation.State.TRANSLATING,
            Translation.State.PAUSED,
            -> observeTranslationProgress(chapterId)
            else -> stopTranslationProgress(chapterId)
        }
        updateSuccessState { successState ->
            val modifiedIndex = successState.chapters.indexOfFirst { it.id == translation.chapter.id }
            if (modifiedIndex < 0) return@updateSuccessState successState

            val newChapters = successState.chapters.toMutableList().apply {
                val item = removeAt(modifiedIndex)
                    .copy(translationState = translation.status)
                add(modifiedIndex, item)
            }
            successState.copy(chapters = newChapters)
        }
    }

    private fun updateTranslationRequests(requests: Map<Long, TranslationRequestState>) {
        updateSuccessState { successState ->
            val newChapters = successState.chapters.map { item ->
                item.copy(translationRequest = item.id?.let(requests::get))
            }
            if (newChapters == successState.chapters) successState else successState.copy(chapters = newChapters)
        }
    }

    private fun updateDownloadState(download: Download) {
        updateSuccessState { successState ->
            val modifiedIndex = successState.chapters.indexOfFirst { it.id == download.chapter.id }
            if (modifiedIndex < 0) return@updateSuccessState successState

            val newChapters = successState.chapters.toMutableList().apply {
                val item = removeAt(modifiedIndex)
                    .copy(downloadState = download.status, downloadProgress = download.progress)
                add(modifiedIndex, item)
            }
            successState.copy(chapters = newChapters)
        }
    }

    // T911 slice 1: internal for the snapshot-retention unit test.
    // T912 ANR fix: suspend — the per-downloaded-chapter
    // getChapterTranslationStatus query reaches the durable store over
    // SAF/UniFile (O(pages) FUSE reads) and must not run on Main. Both
    // production callers are IO coroutines (launchIO/collectLatest).
    internal suspend fun List<Chapter>.toChapterListItems(manga: Manga): List<ChapterList.Item> {
        val isLocal = manga.isLocal()
        return map { chapter ->
            val activeDownload = if (isLocal) {
                null
            } else {
                downloadManager.getQueuedDownloadOrNull(chapter.id)
            }
            val downloaded = if (isLocal) {
                true
            } else {
                downloadManager.isChapterDownloaded(chapter.name, chapter.scanlator, manga.title, manga.source)
            }
            val downloadState = when {
                activeDownload != null -> activeDownload.status
                downloaded -> Download.State.DOWNLOADED
                else -> Download.State.NOT_DOWNLOADED
            }
            // TachiyomiAT
            val queuedTranslation = chapter.id?.let(translationManager::getQueuedTranslationOrNull)
            val translationRequest = chapter.id?.let { translationManager.pendingTranslationRequests.value[it] }
            val persistedTranslationState = if (downloadState == Download.State.DOWNLOADED) {
                translationManager.getChapterTranslationStatus(
                    chapter.id,
                    chapter.name,
                    chapter.scanlator,
                    manga.title,
                    manga.source,
                )
            } else {
                null
            }
            val translationState = TranslationUiProjection.chapterState(
                queuedState = queuedTranslation?.status,
                persistedState = persistedTranslationState,
                requestState = translationRequest,
                downloaded = downloadState == Download.State.DOWNLOADED,
            )
            if (queuedTranslation != null || translationRequest != null) {
                chapter.id?.let(::observeTranslationProgress)
            }

            ChapterList.Item(
                chapter = chapter,
                downloadState = downloadState,
                downloadProgress = activeDownload?.progress ?: 0,
                selected = chapter.id in selectedChapterIds,
                // TachiyomiAT
                translationState = translationState,
                translationRequest = translationRequest,
            )
        }.carryingTranslationSnapshots(translationSnapshots)
    }

    /**
     * Requests an updated list of chapters from the source.
     */
    private suspend fun fetchChaptersFromSource(manualFetch: Boolean = false) {
        val state = successState ?: return
        try {
            withIOContext {
                val chapters = state.source.getChapterList(state.manga.toSManga())

                val newChapters = syncChaptersWithSource.await(
                    chapters,
                    state.manga,
                    state.source,
                    manualFetch,
                )

                if (manualFetch) {
                    downloadNewChapters(newChapters)
                }
            }
        } catch (e: Throwable) {
            val message = if (e is NoChaptersException) {
                context.stringResource(MR.strings.no_chapters_error)
            } else {
                logcat(LogPriority.ERROR, e)
                with(context) { e.formattedMessage }
            }

            screenModelScope.launch {
                snackbarHostState.showSnackbar(message = message)
            }
            val newManga = mangaRepository.getMangaById(mangaId)
            updateSuccessState { it.copy(manga = newManga, isRefreshingData = false) }
        }
    }

    /**
     * @throws IllegalStateException if the swipe action is [LibraryPreferences.ChapterSwipeAction.Disabled]
     */
    fun chapterSwipe(chapterItem: ChapterList.Item, swipeAction: LibraryPreferences.ChapterSwipeAction) {
        screenModelScope.launch {
            executeChapterSwipeAction(chapterItem, swipeAction)
        }
    }

    /**
     * @throws IllegalStateException if the swipe action is [LibraryPreferences.ChapterSwipeAction.Disabled]
     */
    private fun executeChapterSwipeAction(
        chapterItem: ChapterList.Item,
        swipeAction: LibraryPreferences.ChapterSwipeAction,
    ) {
        val chapter = chapterItem.chapter
        when (swipeAction) {
            LibraryPreferences.ChapterSwipeAction.ToggleRead -> {
                markChaptersRead(listOf(chapter), !chapter.read)
            }

            LibraryPreferences.ChapterSwipeAction.ToggleBookmark -> {
                bookmarkChapters(listOf(chapter), !chapter.bookmark)
            }

            LibraryPreferences.ChapterSwipeAction.Download -> {
                val downloadAction: ChapterDownloadAction = when (chapterItem.downloadState) {
                    Download.State.ERROR,
                    Download.State.NOT_DOWNLOADED,
                    -> ChapterDownloadAction.START_NOW

                    Download.State.QUEUE,
                    Download.State.DOWNLOADING,
                    -> ChapterDownloadAction.CANCEL

                    Download.State.DOWNLOADED -> ChapterDownloadAction.DELETE
                }
                runChapterDownloadActions(
                    items = listOf(chapterItem),
                    action = downloadAction,
                )
            }

            LibraryPreferences.ChapterSwipeAction.Disabled -> throw IllegalStateException()
        }
    }

    /**
     * Returns the next unread chapter or null if everything is read.
     */
    fun getNextUnreadChapter(): Chapter? {
        val successState = successState ?: return null
        return successState.chapters.getNextUnread(successState.manga)
    }

    private fun getUnreadChapters(): List<Chapter> {
        val chapterItems = if (skipFiltered) filteredChapters.orEmpty() else allChapters.orEmpty()
        return chapterItems
            .filter { (chapter, dlStatus) -> !chapter.read && dlStatus == Download.State.NOT_DOWNLOADED }
            .map { it.chapter }
    }

    private fun getUnreadChaptersSorted(): List<Chapter> {
        val manga = successState?.manga ?: return emptyList()
        val chaptersSorted = getUnreadChapters().sortedWith(getChapterSort(manga))
        return if (manga.sortDescending()) chaptersSorted.reversed() else chaptersSorted
    }

    private fun startDownload(
        chapters: List<Chapter>,
        startNow: Boolean,
    ) {
        val successState = successState ?: return

        screenModelScope.launchNonCancellable {
            if (startNow) {
                val chapterId = chapters.singleOrNull()?.id ?: return@launchNonCancellable
                downloadManager.startDownloadNow(chapterId)
            } else {
                downloadChapters(chapters)
            }

            if (!isFavorited && !successState.hasPromptedToAddBefore) {
                updateSuccessState { state ->
                    state.copy(hasPromptedToAddBefore = true)
                }
                val result = snackbarHostState.showSnackbar(
                    message = context.stringResource(MR.strings.snack_add_to_library),
                    actionLabel = context.stringResource(MR.strings.action_add),
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed && !isFavorited) {
                    toggleFavorite()
                }
            }
        }
    }

    // TachiyomiAT
    fun setTranslationQueuePaused(paused: Boolean) {
        if (paused) {
            translationManager.pauseTranslation()
        } else {
            translationManager.startTranslation()
        }
    }

    /**
     * TachiyomiAT T911 slice 1: read-only view of the downloader queue for the
     * batch drawer's download phase (state/progress/page counts). The drawer
     * only displays this; the downloader never becomes an owner of translation
     * state.
     */
    fun activeDownloadFor(chapterId: Long): Download? =
        downloadManager.getQueuedDownloadOrNull(chapterId)

    // TachiyomiAT
    fun runChapterTranslationActions(
        item: ChapterList.Item,
        action: ChapterTranslationAction,
    ) {
        runChapterTranslationActions(listOf(item), action)
    }

    /**
     * TachiyomiAT T911 slice 1: opens the chapter's batch progress drawer in the
     * same UI transaction that acknowledges or inspects the request, so accepted
     * work is observable without a second tap. The manga screen is the only
     * navigation owner; downloader/translation callbacks never select this
     * dialog.
     */
    private fun openTranslationProgressDrawer(item: ChapterList.Item) {
        val chapterId = item.chapter.id ?: return
        observeTranslationProgress(chapterId)
        updateSuccessState { it.copy(dialog = Dialog.TranslationProgress(chapterId)) }
    }

    fun runChapterTranslationActions(
        items: List<ChapterList.Item>,
        action: ChapterTranslationAction,
    ) {
        if (items.isEmpty()) return
        val item = items.first()
        when (action) {
            ChapterTranslationAction.START -> {
                // T911 slice 2 (R6): the whole selection is ONE batch — the
                // group is stored once and (when enabled) one confirmation
                // dialog represents all selected chapters. The bottom bar now
                // calls this list path once instead of looping the
                // single-item callback, which used to keep only the last item.
                pendingTranslationGroup = items
                // TachiyomiAT: gate batch translation behind a read-only settings
                // review popup so the user can verify source/target language,
                // engine/model, OCR model, and output tokens before the chapter is
                // processed. Suppressed via the "Don't show this again" checkbox
                // (translationConfirmPretranslate preference); the reader per-page
                // path is unaffected.
                if (translationPreferences.translationConfirmPretranslate().get()) {
                    showConfirmTranslationDialog(items)
                } else {
                    confirmChapterTranslation(item)
                }
            }

            ChapterTranslationAction.DETAILS -> openTranslationProgressDrawer(item)

            ChapterTranslationAction.CANCEL -> {
                items.forEach { item ->
                    val activeTranslation = translationManager.getQueuedTranslationOrNull(item.chapter.id)
                    val wasPendingRequest = activeTranslation == null &&
                        translationManager.isChapterTranslationProtected(item.chapter.id)
                    if (activeTranslation != null) {
                        translationManager.cancelQueuedTranslation(activeTranslation)
                        updateTranslationState(activeTranslation.apply { status = Translation.State.NOT_TRANSLATED })
                    } else if (!translationManager.cancelTranslationRequest(item.chapter.id)) {
                        return@forEach
                    } else {
                        updateTranslationRequests(translationManager.pendingTranslationRequests.value)
                    }
                    // TachiyomiAT bug 4 fix: confirm the cancellation visibly and
                    // offer Undo. The store-level dim clear is handled by
                    // cancelPageTranslations; this snackbar closes the loop on the
                    // user's tap. Undo re-queues via translateChapter, whose
                    // artifact scan (BatchResumeGateDecider) reuses READY work so
                    // no completed page is re-OCR'd.
                    val manga = successState?.manga
                    screenModelScope.launch {
                        val context = Injekt.get<Application>()
                        val result = snackbarHostState.showSnackbar(
                            message = context.stringResource(ATMR.strings.batch_cancelled_toast),
                            actionLabel = context.stringResource(ATMR.strings.translation_cancelled_undo),
                            withDismissAction = true,
                        )
                        if (result == SnackbarResult.ActionPerformed && manga != null) {
                            if (wasPendingRequest) {
                                confirmChapterTranslation(item)
                            } else {
                                translationManager.translateChapter(manga, item.chapter)
                            }
                        }
                    }
                }
            }

            ChapterTranslationAction.DELETE -> showChapterResetDialog(item)
        }
    }

    /**
     * TachiyomiAT T918: the progress sheet's Retry control for a batch that
     * was cancelled mid-run (terminal-aborted snapshot) or failed terminally.
     * Same re-queue as the cancel snackbar's Undo: [TranslationManager
     * .translateChapter]'s artifact scan (BatchResumeGateDecider) reuses READY
     * work, so completed pages are not re-OCR'd and only the remainder re-runs
     * (contract pinned by T918CancelledBatchRestartTest).
     */
    fun retryBatchTranslation(chapterId: Long) {
        val manga = successState?.manga ?: return
        val item = successState?.chapters?.firstOrNull { it.id == chapterId } ?: return
        translationManager.translateChapter(manga, item.chapter)
    }

    fun showChapterResetDialog(item: ChapterList.Item) {
        val state = successState ?: return
        screenModelScope.launch {
            val preflight = translationManager.chapterResetPreflight(item.chapter, state.manga, state.source)
            updateSuccessState { it.copy(dialog = Dialog.ChapterReset(item, preflight)) }
        }
    }

    fun resetChapterTranslation(item: ChapterList.Item, preserveEdits: Boolean) = runChapterReset(item) {
        resetChapterTranslationData(item.chapter, it.manga, it.source, preserveEdits)
    }

    fun resetChapterInpaint(item: ChapterList.Item) = runChapterReset(item) {
        resetChapterInpaintData(item.chapter, it.manga, it.source)
    }

    fun resetChapterOcr(item: ChapterList.Item) = runChapterReset(item) {
        resetChapterOcrData(item.chapter, it.manga, it.source)
    }

    fun deleteChapterTranslation(item: ChapterList.Item) = runChapterReset(item) {
        deleteTranslation(item.chapter, it.manga, it.source)
    }

    private fun runChapterReset(
        item: ChapterList.Item,
        action: suspend TranslationManager.(State.Success) -> Unit,
    ) {
        screenModelScope.launchNonCancellable {
            try {
                val state = successState ?: return@launchNonCancellable
                action(translationManager, state)
                // T911 slice 1: reset/delete invalidates the retained snapshot.
                item.chapter.id?.let(translationSnapshots::forget)
                updateSuccessState { current ->
                    val index = current.chapters.indexOfFirst { it.id == item.chapter.id }
                    if (index < 0) return@updateSuccessState current
                    current.copy(
                        chapters = current.chapters.toMutableList().apply {
                            this[index] = this[index].copy(translationState = Translation.State.NOT_TRANSLATED)
                        },
                    )
                }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    /**
     * TachiyomiAT: shows the read-only settings review popup before a batch
     * translation runs. The popup renders the current [TranslationSettingsSummary]
     * and lets the user proceed, open settings, or suppress future popups.
     * T911 slice 2 (R6): the popup represents the WHOLE selection; the dialog
     * lists every selected chapter name.
     */
    fun showConfirmTranslationDialog(item: ChapterList.Item) {
        showConfirmTranslationDialog(listOf(item))
    }

    fun showConfirmTranslationDialog(items: List<ChapterList.Item>) {
        val summary = translationPreferences.snapshotTranslationSummary()
        val primary = items.first()
        updateSuccessState {
            it.copy(dialog = Dialog.ConfirmTranslation(primary, summary, items))
        }
    }

    /**
     * TachiyomiAT: proceeds with the batch translation after the confirmation
     * popup is accepted (or when the popup is suppressed via the
     * `translationConfirmPretranslate` preference). Encapsulates the download
     * guard + launch that previously lived inline in the START branch.
     *
     * Bug 3 fix: before queueing, run a preflight check. If another chapter of
     * the same source is actively TRANSLATING, surface a confirmation dialog
     * (the in-flight native work would be discarded by cancel). Stale QUEUE
     * entries are evicted automatically inside translateChapter.
     */
    fun confirmChapterTranslation(item: ChapterList.Item) {
        val manga = successState?.manga ?: return
        val group = pendingTranslationGroup
            .takeIf { it.any { candidate -> candidate.chapter.id == item.chapter.id } }
            ?: listOf(item)
        pendingTranslationGroup = emptyList()
        // Publish an acknowledgement before the live download probe or any
        // store/engine setup. The row can immediately open details/cancel even
        // when the selected chapter is not downloaded yet.
        translationManager.acknowledgeTranslationRequests(
            chapters = group.map { it.chapter },
        )
        updateTranslationRequests(translationManager.pendingTranslationRequests.value)
        // TachiyomiAT T911 slice 1: confirmation opens the progress drawer
        // immediately — accepted work must be observable without a second tap.
        // The drawer opens for the primary (first) chapter of the batch; this
        // is the same UI transaction as the acknowledgement above and the
        // async probe below never performs navigation.
        openTranslationProgressDrawer(item)
        screenModelScope.launch {
            // TachiyomiAT bug 5 fix: decide with the same live provider check that
            // Downloader.queueChapters uses to drop already-downloaded chapters. The
            // item's cached downloadState can be wrong when the DownloadCache index
            // is stale or empty, which previously made this branch queue an
            // in-memory translate-after-download request that the downloader then
            // filtered out silently, so the batch never started at all.
            //
            // T911 slice 2 (R7): the request generation captured at
            // acknowledgement fences every durable mutation below, so a user
            // cancel landing between a check and its use cannot be undone by
            // the in-flight probe.
            val generations = group.mapNotNull { candidate ->
                candidate.chapter.id?.let { chapterId ->
                    chapterId to translationManager.pendingRequestGeneration(chapterId)
                }
            }.filter { (_, generation) -> generation != null }
                .associate({ (chapterId, generation) -> chapterId to generation!! })
            val requestedGroup = group.filter { candidate ->
                candidate.chapter.id?.let { chapterId -> generations.containsKey(chapterId) } == true
            }
            if (requestedGroup.isEmpty()) return@launch
            val (downloaded, awaitingDownload) = withIOContext {
                requestedGroup.partition {
                    downloadManager.isChapterDownloaded(
                        it.chapter.name,
                        it.chapter.scanlator,
                        manga.title,
                        manga.source,
                        skipCache = true,
                    )
                }
            }
            // Fenced WAITING writes + download attach (drop cancelled candidates).
            val admittedAwaitingDownload = awaitingDownload.filter { candidate ->
                val chapterId = candidate.chapter.id ?: return@filter false
                val generation = generations[chapterId] ?: return@filter false
                translationManager.queueTranslationAfterDownloadIfCurrent(
                    manga,
                    candidate.chapter,
                    generation,
                )
            }
            if (admittedAwaitingDownload.isNotEmpty()) {
                enqueueTranslationDownloads(
                    downloadManager,
                    manga,
                    admittedAwaitingDownload.map { it.chapter },
                )
            }
            // Re-check immediately before admission: still the same request.
            val pendingDownloaded = downloaded.filter { candidate ->
                val chapterId = candidate.chapter.id ?: return@filter false
                val generation = generations[chapterId] ?: return@filter false
                translationManager.isTranslationRequestCurrent(chapterId, generation)
            }
            if (pendingDownloaded.isEmpty()) return@launch
            // T917 Phase 4 (D10, phase4-design §3.2): a directory-exists hit can
            // still be a MID-DOWNLOAD chapter (audit M-08 — the downloader owns
            // a partial dir while the trigger reads it as "downloaded").
            // Cross-check every candidate that still has a live queue entry;
            // a settled download has none, so there is nothing to probe and
            // the pre-D10 truth stands. Local-only: the probe reads the
            // Download the UI already observes — no network.
            val partialProbes: Map<Long, eu.kanade.translation.pipeline.batch.BatchAdmissionDecision> =
                pendingDownloaded.mapNotNull { candidate ->
                    val chapterId = candidate.chapter.id ?: return@mapNotNull null
                    val queuedDownload = downloadManager.getQueuedDownloadOrNull(chapterId)
                        ?: return@mapNotNull null
                    val decision = eu.kanade.translation.pipeline.batch.BatchAdmissionProbe.evaluate(
                        downloadedPageCount = queuedDownload.pages?.count {
                            it.status == eu.kanade.tachiyomi.source.model.Page.State.READY
                        } ?: 0,
                        sourcePageList = queuedDownload.pages,
                    )
                    if (decision == eu.kanade.translation.pipeline.batch.BatchAdmissionDecision.Complete) {
                        null
                    } else {
                        chapterId to decision
                    }
                }.toMap()
            suspend fun admitDownloaded(candidates: List<ChapterList.Item>) {
                if (candidates.isEmpty()) return
                if (candidates.size > 1) {
                    // List API: one fenced admission for the whole batch, no
                    // same-source silent eviction (R6).
                    translationManager.translateChaptersIfCurrent(
                        manga,
                        candidates.map { it.chapter },
                        generations,
                    )
                    return
                }
                val target = candidates.single()
                val targetGeneration = generations[target.chapter.id]
                // Keep the row in PREPARING while the conflict preflight runs.
                target.chapter.id?.let { chapterId ->
                    targetGeneration?.let { generation ->
                        translationManager.markTranslationRequestPreparingIfCurrent(chapterId, generation)
                    }
                }
                logcat(LogPriority.INFO) {
                    "TachiyomiAT translate START: chapter=${target.chapter.name} manga=${manga.title} " +
                        "lastPageRead=${target.chapter.lastPageRead}"
                }
                when (val preflight = translationManager.translateChapterPreflight(manga, target.chapter)) {
                    is ChapterQueuePreflight.NoConflict ->
                        launchTranslateChapter(manga, target.chapter, targetGeneration)
                    is ChapterQueuePreflight.RunningConflict -> {
                        updateSuccessState {
                            it.copy(dialog = Dialog.RunningTranslationConflict(target, preflight))
                        }
                    }
                }
            }
            if (partialProbes.isNotEmpty()) {
                // Complete chapters of the group admit immediately (no
                // group-wide gate); partials are NEVER silently admitted —
                // the PLAN-mandated choice dialog routes each one.
                admitDownloaded(
                    pendingDownloaded.filter { candidate ->
                        candidate.chapter.id == null || candidate.chapter.id !in partialProbes
                    },
                )
                val partialItems = pendingDownloaded.filter { candidate ->
                    candidate.chapter.id != null && candidate.chapter.id in partialProbes
                }
                updateSuccessState {
                    it.copy(
                        dialog = Dialog.PartialDownloadTranslation(
                            group = partialItems,
                            decisions = partialProbes,
                        ),
                    )
                }
                return@launch
            }
            admitDownloaded(pendingDownloaded)
        }
    }

    /** Re-arms a durable PAUSED batch without bypassing its cooldown. */
    fun resumeChapterTranslation(item: ChapterList.Item, force: Boolean = false) {
        val chapterId = item.chapter.id ?: return
        screenModelScope.launchIO {
            val resumed = translationManager.requeueTranslation(chapterId, force)
            if (resumed) {
                // requeueExisting owns cooldown and queue membership; the
                // manager entry point also re-arms the foreground service for
                // the newly active QUEUE state.
                translationManager.startTranslation()
            } else {
                withUIContext {
                    snackbarHostState.showSnackbar(
                        message = "Translation is still paused; retry when the provider cooldown expires",
                        duration = SnackbarDuration.Short,
                    )
                }
            }
        }
    }

    /** Cancels an acknowledgement that was waiting for a conflict decision. */
    fun cancelPendingTranslationRequest(item: ChapterList.Item) {
        item.chapter.id?.let { chapterId ->
            if (translationManager.cancelTranslationRequest(chapterId)) {
                updateTranslationRequests(translationManager.pendingTranslationRequests.value)
            }
        }
        dismissDialog()
    }

    /**
     * TachiyomiAT bug 3 fix: called after the user confirms the
     * [Dialog.RunningTranslationConflict] dialog. Cancels the in-flight chapter
     * (preserving its accepted artifacts via clearTransientQueuePages) and then
     * starts the requested chapter's batch.
     */
    fun confirmReplaceRunningChapter(item: ChapterList.Item) {
        val manga = successState?.manga ?: return
        val preflight = (successState?.dialog as? Dialog.RunningTranslationConflict)?.conflict ?: return
        dismissDialog()
        screenModelScope.launchNonCancellable {
            translationManager.cancelRunningChapterForReplace(preflight.chapterId)
            translationManager.translateChapter(manga, item.chapter)
        }
    }

    private fun launchTranslateChapter(
        manga: Manga,
        chapter: Chapter,
        expectedRequestGeneration: Long? = null,
    ) {
        screenModelScope.launchNonCancellable {
            translationManager.translateChapter(manga, chapter, expectedRequestGeneration)
        }
    }

    /**
     * T917 Phase 4 (D10, phase4-design §3.2): the user chose FINISH first —
     * route through the EXISTING fenced WAITING_FOR_DOWNLOAD path (the same
     * one the awaiting partition uses); the downloader's post-finalization
     * handoff admits the batch only after the download completes. Zero batch
     * work starts now. Generations are captured at tap time so a user cancel
     * landing between the dialog and this handler still fences the writes.
     */
    fun finishDownloadBeforeTranslation(dialog: Dialog.PartialDownloadTranslation) {
        dismissDialog()
        val manga = successState?.manga ?: return
        screenModelScope.launchNonCancellable {
            val admitted = dialog.group.mapNotNull { item ->
                val chapterId = item.chapter.id ?: return@mapNotNull null
                val generation = translationManager.pendingRequestGeneration(chapterId)
                    ?: return@mapNotNull null
                if (
                    translationManager.queueTranslationAfterDownloadIfCurrent(
                        manga,
                        item.chapter,
                        generation,
                    )
                ) {
                    item.chapter
                } else {
                    null
                }
            }
            if (admitted.isNotEmpty()) {
                enqueueTranslationDownloads(downloadManager, manga, admitted)
            }
        }
    }

    /**
     * T917 Phase 4 (D10, phase4-design §3.3): the user chose TRANSLATE WHAT
     * EXISTS — subset admission carrying the probe's cross-check so the batch
     * records its partial truth (manifest `PartialBatchInfo`, source-total or
     * honestly-unknown expected count), never a fake 100%.
     */
    fun translatePartialDownloadNow(dialog: Dialog.PartialDownloadTranslation) {
        dismissDialog()
        val manga = successState?.manga ?: return
        screenModelScope.launchNonCancellable {
            val generations = mutableMapOf<Long, Long>()
            val contexts = mutableMapOf<Long, eu.kanade.translation.pipeline.batch.BatchAdmissionContext>()
            val chapters = mutableListOf<Chapter>()
            dialog.group.forEach { item ->
                val chapterId = item.chapter.id ?: return@forEach
                val generation = translationManager.pendingRequestGeneration(chapterId) ?: return@forEach
                val decision = dialog.decisions[chapterId] ?: return@forEach
                generations[chapterId] = generation
                contexts[chapterId] = when (decision) {
                    is eu.kanade.translation.pipeline.batch.BatchAdmissionDecision.Partial ->
                        eu.kanade.translation.pipeline.batch.BatchAdmissionContext(
                            probedSourcePageCount = decision.expectedSourcePageCount,
                            sourceCountKnown = true,
                        )
                    eu.kanade.translation.pipeline.batch.BatchAdmissionDecision.UnknownCount ->
                        eu.kanade.translation.pipeline.batch.BatchAdmissionContext(
                            probedSourcePageCount = null,
                            sourceCountKnown = true,
                        )
                    eu.kanade.translation.pipeline.batch.BatchAdmissionDecision.Complete ->
                        eu.kanade.translation.pipeline.batch.BatchAdmissionContext(null, false)
                }
                chapters += item.chapter
            }
            if (chapters.isNotEmpty()) {
                translationManager.translateChaptersIfCurrent(manga, chapters, generations, contexts)
            }
        }
    }

    /**
     * TachiyomiAT: toggles the confirmation popup for future batch translations.
     * Bound to the popup's "Don't show this again" checkbox so the choice is
     * applied immediately whether the user proceeds or cancels.
     */
    fun setConfirmPretranslate(show: Boolean) {
        translationPreferences.translationConfirmPretranslate().set(show)
    }

    /**
     * TachiyomiAT: reads whether the confirmation popup will show for the next
     * batch translation. The popup checkbox binds to this so it reflects the
     * live preference value.
     */
    fun translationConfirmPretranslate(): Boolean =
        translationPreferences.translationConfirmPretranslate().get()

    fun runChapterDownloadActions(
        items: List<ChapterList.Item>,
        action: ChapterDownloadAction,
    ) {
        when (action) {
            ChapterDownloadAction.START -> {
                startDownload(items.map { it.chapter }, false)
                if (items.any { it.downloadState == Download.State.ERROR }) {
                    downloadManager.startDownloads()
                }
            }

            ChapterDownloadAction.START_NOW -> {
                val chapter = items.singleOrNull()?.chapter ?: return
                startDownload(listOf(chapter), true)
            }

            ChapterDownloadAction.CANCEL -> {
                val chapterId = items.singleOrNull()?.id ?: return
                cancelDownload(chapterId)
            }

            ChapterDownloadAction.DELETE -> {
                deleteChapters(items.map { it.chapter })
            }
        }
    }

    fun runDownloadAction(action: DownloadAction) {
        val chaptersToDownload = when (action) {
            DownloadAction.NEXT_1_CHAPTER -> getUnreadChaptersSorted().take(1)
            DownloadAction.NEXT_5_CHAPTERS -> getUnreadChaptersSorted().take(5)
            DownloadAction.NEXT_10_CHAPTERS -> getUnreadChaptersSorted().take(10)
            DownloadAction.NEXT_25_CHAPTERS -> getUnreadChaptersSorted().take(25)
            DownloadAction.UNREAD_CHAPTERS -> getUnreadChapters()
        }
        if (chaptersToDownload.isNotEmpty()) {
            startDownload(chaptersToDownload, false)
        }
    }

    private fun cancelDownload(chapterId: Long) {
        val activeDownload = downloadManager.getQueuedDownloadOrNull(chapterId) ?: return
        downloadManager.cancelQueuedDownloads(listOf(activeDownload))
        updateDownloadState(activeDownload.apply { status = Download.State.NOT_DOWNLOADED })
    }

    fun markPreviousChapterRead(pointer: Chapter) {
        val manga = successState?.manga ?: return
        val chapters = filteredChapters.orEmpty().map { it.chapter }
        val prevChapters = if (manga.sortDescending()) chapters.asReversed() else chapters
        val pointerPos = prevChapters.indexOf(pointer)
        if (pointerPos != -1) markChaptersRead(prevChapters.take(pointerPos), true)
    }

    /**
     * Mark the selected chapter list as read/unread.
     * @param chapters the list of selected chapters.
     * @param read whether to mark chapters as read or unread.
     */
    fun markChaptersRead(chapters: List<Chapter>, read: Boolean) {
        toggleAllSelection(false)
        if (chapters.isEmpty()) return
        screenModelScope.launchIO {
            setReadStatus.await(
                read = read,
                chapters = chapters.toTypedArray(),
            )

            if (!read || successState?.hasLoggedInTrackers == false || autoTrackState == AutoTrackState.NEVER) {
                return@launchIO
            }

            val tracks = getTracks.await(mangaId)
            val maxChapterNumber = chapters.maxOf { it.chapterNumber }
            val shouldPromptTrackingUpdate = tracks.any { track -> maxChapterNumber > track.lastChapterRead }

            if (!shouldPromptTrackingUpdate) return@launchIO
            if (autoTrackState == AutoTrackState.ALWAYS) {
                trackChapter.await(context, mangaId, maxChapterNumber)
                withUIContext {
                    context.toast(context.stringResource(MR.strings.trackers_updated_summary, maxChapterNumber.toInt()))
                }
                return@launchIO
            }

            val result = snackbarHostState.showSnackbar(
                message = context.stringResource(MR.strings.confirm_tracker_update, maxChapterNumber.toInt()),
                actionLabel = context.stringResource(MR.strings.action_ok),
                duration = SnackbarDuration.Short,
                withDismissAction = true,
            )

            if (result == SnackbarResult.ActionPerformed) {
                trackChapter.await(context, mangaId, maxChapterNumber)
            }
        }
    }

    /**
     * Downloads the given list of chapters with the manager.
     * @param chapters the list of chapters to download.
     */
    private fun downloadChapters(chapters: List<Chapter>) {
        val manga = successState?.manga ?: return
        downloadManager.downloadChapters(manga, chapters)
        toggleAllSelection(false)
    }

    /**
     * Bookmarks the given list of chapters.
     * @param chapters the list of chapters to bookmark.
     */
    fun bookmarkChapters(chapters: List<Chapter>, bookmarked: Boolean) {
        screenModelScope.launchIO {
            chapters
                .filterNot { it.bookmark == bookmarked }
                .map { ChapterUpdate(id = it.id, bookmark = bookmarked) }
                .let { updateChapter.awaitAll(it) }
        }
        toggleAllSelection(false)
    }

    /**
     * Deletes the given list of chapter.
     *
     * @param chapters the list of chapters to delete.
     */
    fun deleteChapters(chapters: List<Chapter>) {
        screenModelScope.launchNonCancellable {
            try {
                successState?.let { state ->
                    downloadManager.deleteChapters(
                        chapters,
                        state.manga,
                        state.source,
                    )
                }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    private fun downloadNewChapters(chapters: List<Chapter>) {
        screenModelScope.launchNonCancellable {
            val manga = successState?.manga ?: return@launchNonCancellable
            val chaptersToDownload = filterChaptersForDownload.await(manga, chapters)

            if (chaptersToDownload.isNotEmpty()) {
                downloadChapters(chaptersToDownload)
            }
        }
    }

    /**
     * Sets the read filter and requests an UI update.
     * @param state whether to display only unread chapters or all chapters.
     */
    fun setUnreadFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_UNREAD
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_READ
        }
        screenModelScope.launchNonCancellable {
            setMangaChapterFlags.awaitSetUnreadFilter(manga, flag)
        }
    }

    /**
     * Sets the download filter and requests an UI update.
     * @param state whether to display only downloaded chapters or all chapters.
     */
    fun setDownloadedFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_DOWNLOADED
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_DOWNLOADED
        }

        screenModelScope.launchNonCancellable {
            setMangaChapterFlags.awaitSetDownloadedFilter(manga, flag)
        }
    }

    /**
     * Sets the bookmark filter and requests an UI update.
     * @param state whether to display only bookmarked chapters or all chapters.
     */
    fun setBookmarkedFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_BOOKMARKED
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_BOOKMARKED
        }

        screenModelScope.launchNonCancellable {
            setMangaChapterFlags.awaitSetBookmarkFilter(manga, flag)
        }
    }

    /**
     * Sets the active display mode.
     * @param mode the mode to set.
     */
    fun setDisplayMode(mode: Long) {
        val manga = successState?.manga ?: return

        screenModelScope.launchNonCancellable {
            setMangaChapterFlags.awaitSetDisplayMode(manga, mode)
        }
    }

    /**
     * Sets the sorting method and requests an UI update.
     * @param sort the sorting mode.
     */
    fun setSorting(sort: Long) {
        val manga = successState?.manga ?: return

        screenModelScope.launchNonCancellable {
            setMangaChapterFlags.awaitSetSortingModeOrFlipOrder(manga, sort)
        }
    }

    fun setCurrentSettingsAsDefault(applyToExisting: Boolean) {
        val manga = successState?.manga ?: return
        screenModelScope.launchNonCancellable {
            libraryPreferences.setChapterSettingsDefault(manga)
            if (applyToExisting) {
                setMangaDefaultChapterFlags.awaitAll()
            }
            snackbarHostState.showSnackbar(message = context.stringResource(MR.strings.chapter_settings_updated))
        }
    }

    fun resetToDefaultSettings() {
        val manga = successState?.manga ?: return
        screenModelScope.launchNonCancellable {
            setMangaDefaultChapterFlags.await(manga)
        }
    }

    fun toggleSelection(
        item: ChapterList.Item,
        selected: Boolean,
        userSelected: Boolean = false,
        fromLongPress: Boolean = false,
    ) {
        updateSuccessState { successState ->
            val newChapters = successState.processedChapters.toMutableList().apply {
                val selectedIndex = successState.processedChapters.indexOfFirst { it.id == item.chapter.id }
                if (selectedIndex < 0) return@apply

                val selectedItem = get(selectedIndex)
                if ((selectedItem.selected && selected) || (!selectedItem.selected && !selected)) return@apply

                val firstSelection = none { it.selected }
                set(selectedIndex, selectedItem.copy(selected = selected))
                selectedChapterIds.addOrRemove(item.id, selected)

                if (selected && userSelected && fromLongPress) {
                    if (firstSelection) {
                        selectedPositions[0] = selectedIndex
                        selectedPositions[1] = selectedIndex
                    } else {
                        // Try to select the items in-between when possible
                        val range: IntRange
                        if (selectedIndex < selectedPositions[0]) {
                            range = selectedIndex + 1..<selectedPositions[0]
                            selectedPositions[0] = selectedIndex
                        } else if (selectedIndex > selectedPositions[1]) {
                            range = (selectedPositions[1] + 1)..<selectedIndex
                            selectedPositions[1] = selectedIndex
                        } else {
                            // Just select itself
                            range = IntRange.EMPTY
                        }

                        range.forEach {
                            val inbetweenItem = get(it)
                            if (!inbetweenItem.selected) {
                                selectedChapterIds.add(inbetweenItem.id)
                                set(it, inbetweenItem.copy(selected = true))
                            }
                        }
                    }
                } else if (userSelected && !fromLongPress) {
                    if (!selected) {
                        if (selectedIndex == selectedPositions[0]) {
                            selectedPositions[0] = indexOfFirst { it.selected }
                        } else if (selectedIndex == selectedPositions[1]) {
                            selectedPositions[1] = indexOfLast { it.selected }
                        }
                    } else {
                        if (selectedIndex < selectedPositions[0]) {
                            selectedPositions[0] = selectedIndex
                        } else if (selectedIndex > selectedPositions[1]) {
                            selectedPositions[1] = selectedIndex
                        }
                    }
                }
            }
            successState.copy(chapters = newChapters)
        }
    }

    fun toggleAllSelection(selected: Boolean) {
        updateSuccessState { successState ->
            val newChapters = successState.chapters.map {
                selectedChapterIds.addOrRemove(it.id, selected)
                it.copy(selected = selected)
            }
            selectedPositions[0] = -1
            selectedPositions[1] = -1
            successState.copy(chapters = newChapters)
        }
    }

    fun invertSelection() {
        updateSuccessState { successState ->
            val newChapters = successState.chapters.map {
                selectedChapterIds.addOrRemove(it.id, !it.selected)
                it.copy(selected = !it.selected)
            }
            selectedPositions[0] = -1
            selectedPositions[1] = -1
            successState.copy(chapters = newChapters)
        }
    }

    // Chapters list - end

    // Track sheet - start

    private fun observeTrackers() {
        val manga = successState?.manga ?: return

        screenModelScope.launchIO {
            combine(
                getTracks.subscribe(manga.id).catch { logcat(LogPriority.ERROR, it) },
                trackerManager.loggedInTrackersFlow(),
            ) { mangaTracks, loggedInTrackers ->
                // Show only if the service supports this manga's source
                val supportedTrackers = loggedInTrackers.filter { (it as? EnhancedTracker)?.accept(source!!) ?: true }
                val supportedTrackerIds = supportedTrackers.map { it.id }.toHashSet()
                val supportedTrackerTracks = mangaTracks.filter { it.trackerId in supportedTrackerIds }
                supportedTrackerTracks.size to supportedTrackers.isNotEmpty()
            }
                .flowWithLifecycle(lifecycle)
                .distinctUntilChanged()
                .collectLatest { (trackingCount, hasLoggedInTrackers) ->
                    updateSuccessState {
                        it.copy(
                            trackingCount = trackingCount,
                            hasLoggedInTrackers = hasLoggedInTrackers,
                        )
                    }
                }
        }
    }

    // Track sheet - end

    sealed interface Dialog {
        data class ChangeCategory(
            val manga: Manga,
            val initialSelection: ImmutableList<CheckboxState<Category>>,
        ) : Dialog

        data class DeleteChapters(val chapters: List<Chapter>) : Dialog
        data class DuplicateManga(val manga: Manga, val duplicate: Manga) : Dialog
        data class Migrate(val newManga: Manga, val oldManga: Manga) : Dialog
        data class SetFetchInterval(val manga: Manga) : Dialog
        data object SettingsSheet : Dialog
        data object TrackSheet : Dialog
        data object FullCover : Dialog
        data class TranslationProgress(val chapterId: Long) : Dialog
        data class ChapterReset(
            val item: ChapterList.Item,
            val preflight: eu.kanade.translation.ChapterResetPreflight,
        ) : Dialog
        data class ConfirmTranslation(
            val item: ChapterList.Item,
            val summary: TranslationSettingsSummary,
            // T911 slice 2 (R6): the confirmation represents the whole
            // selection; empty means the single [item] (legacy shape).
            val group: List<ChapterList.Item> = emptyList(),
        ) : Dialog

        // TachiyomiAT bug 3: another chapter of the same source is actively
        // translating; the user must confirm cancelling it before this chapter's
        // batch can start. The conflict carries enough identity for the dialog
        // copy and for cancelRunningChapterForReplace.
        data class RunningTranslationConflict(
            val item: ChapterList.Item,
            val conflict: eu.kanade.translation.model.ChapterQueuePreflight.RunningConflict,
        ) : Dialog

        // T917 Phase 4 (D10, phase4-design §3.2): a probed "downloaded"
        // candidate is actually MID-DOWNLOAD (audit M-08). The user picks per
        // chapter: finish the download first (the existing fenced
        // WAITING_FOR_DOWNLOAD path) or translate the found subset (honest
        // partial accounting in the manifest). [decisions] is keyed by chapter
        // id. Functional Phase-4 structure; final copy is Phase 5 (D13).
        data class PartialDownloadTranslation(
            val group: List<ChapterList.Item>,
            val decisions: Map<Long, eu.kanade.translation.pipeline.batch.BatchAdmissionDecision>,
        ) : Dialog
    }

    fun dismissDialog() {
        updateSuccessState { it.copy(dialog = null) }
    }

    fun showDeleteChapterDialog(chapters: List<Chapter>) {
        updateSuccessState { it.copy(dialog = Dialog.DeleteChapters(chapters)) }
    }

    fun showSettingsDialog() {
        updateSuccessState { it.copy(dialog = Dialog.SettingsSheet) }
    }

    fun showTrackDialog() {
        updateSuccessState { it.copy(dialog = Dialog.TrackSheet) }
    }

    fun showCoverDialog() {
        updateSuccessState { it.copy(dialog = Dialog.FullCover) }
    }

    fun showMigrateDialog(duplicate: Manga) {
        val manga = successState?.manga ?: return
        updateSuccessState { it.copy(dialog = Dialog.Migrate(newManga = manga, oldManga = duplicate)) }
    }

    fun setExcludedScanlators(excludedScanlators: Set<String>) {
        screenModelScope.launchIO {
            setExcludedScanlators.await(mangaId, excludedScanlators)
        }
    }

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data class Success(
            val manga: Manga,
            val source: Source,
            val isFromSource: Boolean,
            val chapters: List<ChapterList.Item>,
            val availableScanlators: Set<String>,
            val excludedScanlators: Set<String>,
            val trackingCount: Int = 0,
            val hasLoggedInTrackers: Boolean = false,
            val isRefreshingData: Boolean = false,
            val dialog: Dialog? = null,
            val hasPromptedToAddBefore: Boolean = false,
        ) : State {
            val processedChapters by lazy {
                chapters.applyFilters(manga).toList()
            }

            val isAnySelected by lazy {
                chapters.fastAny { it.selected }
            }

            val chapterListItems by lazy {
                processedChapters.insertSeparators { before, after ->
                    val (lowerChapter, higherChapter) = if (manga.sortDescending()) {
                        after to before
                    } else {
                        before to after
                    }
                    if (higherChapter == null) return@insertSeparators null

                    if (lowerChapter == null) {
                        floor(higherChapter.chapter.chapterNumber)
                            .toInt()
                            .minus(1)
                            .coerceAtLeast(0)
                    } else {
                        calculateChapterGap(higherChapter.chapter, lowerChapter.chapter)
                    }
                        .takeIf { it > 0 }
                        ?.let { missingCount ->
                            ChapterList.MissingCount(
                                id = "${lowerChapter?.id}-${higherChapter.id}",
                                count = missingCount,
                            )
                        }
                }
            }

            val scanlatorFilterActive: Boolean
                get() = excludedScanlators.intersect(availableScanlators).isNotEmpty()

            val filterActive: Boolean
                get() = scanlatorFilterActive || manga.chaptersFiltered()

            /**
             * Applies the view filters to the list of chapters obtained from the database.
             * @return an observable of the list of chapters filtered and sorted.
             */
            private fun List<ChapterList.Item>.applyFilters(manga: Manga): Sequence<ChapterList.Item> {
                val isLocalManga = manga.isLocal()
                val unreadFilter = manga.unreadFilter
                val downloadedFilter = manga.downloadedFilter
                val bookmarkedFilter = manga.bookmarkedFilter
                return asSequence()
                    .filter { (chapter) -> applyFilter(unreadFilter) { !chapter.read } }
                    .filter { (chapter) -> applyFilter(bookmarkedFilter) { chapter.bookmark } }
                    .filter { applyFilter(downloadedFilter) { it.isDownloaded || isLocalManga } }
                    .sortedWith { (chapter1), (chapter2) -> getChapterSort(manga).invoke(chapter1, chapter2) }
            }
        }
    }
}

@Immutable
sealed class ChapterList {
    @Immutable
    data class MissingCount(
        val id: String,
        val count: Int,
    ) : ChapterList()

    @Immutable
    data class Item(
        val chapter: Chapter,
        val downloadState: Download.State,
        // TachiyomiAT
        val translationState: Translation.State = Translation.State.NOT_TRANSLATED,
        // TachiyomiAT: rich batch translation progress for the manga-screen
        // indicator and progress sheet. Null means no active batch.
        val translationProgress: TranslationProgressSnapshot? = null,
        /** Immediate request acknowledgement before queue/tracker creation. */
        val translationRequest: TranslationRequestState? = null,
        val downloadProgress: Int,
        val selected: Boolean = false,
    ) : ChapterList() {
        val id = chapter.id
        val isDownloaded = downloadState == Download.State.DOWNLOADED
    }
}

/**
 * TachiyomiAT: enqueues translation-driven chapter downloads and guarantees
 * the downloader actually runs. Stock auto-start only fires when the queue
 * was empty, so a stale or restored entry — including a retained ERROR
 * download from an earlier failed attempt — would leave freshly queued
 * chapters stuck in QUEUED until the user started each one by hand.
 * [DownloadManager.startDownloads] is idempotent, so the guarantee must not
 * depend on ERROR-state detection below; that re-fronting only preserves
 * explicit-retry ordering.
 */
internal fun enqueueTranslationDownloads(
    downloadManager: DownloadManager,
    manga: Manga,
    chapters: List<Chapter>,
) {
    if (chapters.isEmpty()) return
    downloadManager.downloadChapters(manga, chapters)
    // A failed download remains in the downloader queue with ERROR
    // status. Put it back at the front when this is an explicit
    // retry of a previously failed pending request.
    chapters.forEach { chapter ->
        if (downloadManager.getQueuedDownloadOrNull(chapter.id)?.status == Download.State.ERROR) {
            downloadManager.startDownloadNow(chapter.id)
        }
    }
    downloadManager.startDownloads()
}
