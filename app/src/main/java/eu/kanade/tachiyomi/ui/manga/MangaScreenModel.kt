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
import eu.kanade.translation.model.ChapterRevisionEligibility
import eu.kanade.translation.model.RevisionConfirmState
import eu.kanade.translation.model.RevisionConfirmation
import eu.kanade.translation.model.RevisionReviewerOption
import eu.kanade.translation.model.RevisionScope
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationSettingsSummary
import eu.kanade.translation.model.defaultScope
import eu.kanade.translation.model.resolveEffectiveReviewerEngine
import eu.kanade.translation.model.snapshotTranslationSummary
import eu.kanade.translation.model.toConfirmState
import eu.kanade.translation.model.toResultState
import eu.kanade.translation.model.withReviewerPicked
import eu.kanade.translation.model.withScopeChanged
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.transformLatest
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
import tachiyomi.domain.translation.AiEngine
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
            ) { mangaAndChapters, _, _, _ -> mangaAndChapters }
                .flowWithLifecycle(lifecycle)
                .collectLatest { (manga, chapters) ->
                    updateSuccessState {
                        it.copy(
                            manga = manga,
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

    // TachiyomiAT: active per-chapter batch-progress collectors. Keyed by
    // chapterId so we start one collector when a chapter begins translating and
    // stop it when it leaves the translating state, feeding (done,total) into
    // the chapter list item for the determinate "12/40" indicator.
    private val translationProgressJobs = mutableMapOf<Long, kotlinx.coroutines.Job>()

    private fun observeTranslationProgress(chapterId: Long) {
        if (translationProgressJobs[chapterId]?.isActive == true) return
        translationProgressJobs[chapterId] = screenModelScope.launchIO {
            translationManager.observeBatchProgress(chapterId)
                // Sample nonterminal updates, but deliver terminal snapshots immediately.
                .transformLatest { progress ->
                    if (progress.batchPhase == eu.kanade.translation.model.TranslationBatchPhase.FINISHED) {
                        emit(progress)
                    } else {
                        delay(200)
                        emit(progress)
                    }
                }
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

    // TachiyomiAT
    private fun updateTranslationState(translation: Translation) {
        // Start/stop the per-chapter batch-progress collector so the "12/40"
        // indicator only tracks chapters actively translating, and stops (and
        // resets to no-fraction) once the chapter reaches a terminal state.
        val chapterId = translation.chapter.id
        when (translation.status) {
            Translation.State.QUEUE, Translation.State.TRANSLATING -> observeTranslationProgress(chapterId)
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

    private fun List<Chapter>.toChapterListItems(manga: Manga): List<ChapterList.Item> {
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
            var translationState = Translation.State.NOT_TRANSLATED
            if (downloadState == Download.State.DOWNLOADED) {
                translationState = translationManager.getChapterTranslationStatus(
                    chapter.id,
                    chapter.name,
                    chapter.scanlator,
                    manga.title,
                    manga.source,
                )
            }
            if (translationState == Translation.State.QUEUE || translationState == Translation.State.TRANSLATING) {
                chapter.id?.let(::observeTranslationProgress)
            }

            // TachiyomiAT CP7: snapshot manager-derived revision eligibility
            // for any chapter with translation state. The manager returns null
            // when there is no store, so NOT_TRANSLATED chapters stay null and
            // hide the REVIEW action. The snapshot is fetched asynchronously
            // and merged into existing state so it never blocks the list render.
            val chapterId = chapter.id
            if (translationState != Translation.State.NOT_TRANSLATED && chapterId != null) {
                observeRevisionEligibility(chapterId)
            }

            ChapterList.Item(
                chapter = chapter,
                downloadState = downloadState,
                downloadProgress = activeDownload?.progress ?: 0,
                selected = chapter.id in selectedChapterIds,
                // TachiyomiAT
                translationState = translationState,
            )
        }
    }

    /**
     * TachiyomiAT CP7: asynchronously snapshots manager-derived revision
     * eligibility for [chapterId] and merges it into the matching chapter item.
     * The snapshot is backend-owned (counts, reviewer options, language pair)
     * so the UI never computes them. Idempotent: re-snapshots are cheap and the
     * manager reads durable state.
     */
    private fun observeRevisionEligibility(chapterId: Long) {
        screenModelScope.launch {
            val eligibility = translationManager.snapshotRevisionEligibility(chapterId) ?: return@launch
            updateSuccessState { success ->
                val index = success.chapters.indexOfFirst { it.id == chapterId }
                if (index < 0) return@updateSuccessState success
                success.copy(
                    chapters = success.chapters.toMutableList().apply {
                        val existing = removeAt(index)
                        add(index, existing.copy(revisionEligibility = eligibility))
                    },
                )
            }
        }
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
    fun runChapterTranslationActions(
        item: ChapterList.Item,
        action: ChapterTranslationAction,
    ) {
        when (action) {
            ChapterTranslationAction.START -> {
                // TachiyomiAT: log the guard outcome instead of silently returning.
                // A silent return on a user action made the staged-batch path look
                // "dead" when the real cause was the chapter not being downloaded.
                if (item.downloadState != Download.State.DOWNLOADED) {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT translate START rejected: chapter ${item.chapter.name} " +
                            "not downloaded (state=${item.downloadState}); download it first."
                    }
                    return
                }
                // TachiyomiAT: gate batch translation behind a read-only settings
                // review popup so the user can verify source/target language,
                // engine/model, OCR model, and output tokens before the chapter is
                // processed. Suppressed via the "Don't show this again" checkbox
                // (translationConfirmPretranslate preference); the reader per-page
                // path is unaffected.
                if (translationPreferences.translationConfirmPretranslate().get()) {
                    showConfirmTranslationDialog(item)
                } else {
                    confirmChapterTranslation(item)
                }
            }

            ChapterTranslationAction.DETAILS -> {
                val chapterId = item.chapter.id ?: return
                observeTranslationProgress(chapterId)
                updateSuccessState { it.copy(dialog = Dialog.TranslationProgress(chapterId)) }
            }

            ChapterTranslationAction.CANCEL -> {
                val activeTranslation = translationManager.getQueuedTranslationOrNull(item.chapter.id) ?: return
                translationManager.cancelQueuedTranslation(activeTranslation)
                updateTranslationState(activeTranslation.apply { status = Translation.State.NOT_TRANSLATED })
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
                        translationManager.translateChapter(manga, item.chapter)
                    }
                }
            }

            ChapterTranslationAction.DELETE -> showChapterResetDialog(item)

            // TachiyomiAT CP7: open the shared revision confirmation dialog.
            // The manager runs preflight and returns a typed outcome; the UI
            // maps it to display state and never sends blocks/counts back.
            ChapterTranslationAction.REVIEW -> startRevisionPreflight(
                item = item,
                scope = defaultScope(item.revisionEligibility),
            )
        }
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
     */
    fun showConfirmTranslationDialog(item: ChapterList.Item) {
        val summary = translationPreferences.snapshotTranslationSummary()
        updateSuccessState { it.copy(dialog = Dialog.ConfirmTranslation(item, summary)) }
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
        logcat(LogPriority.INFO) {
            "TachiyomiAT translate START: chapter=${item.chapter.name} manga=${manga.title} " +
                "lastPageRead=${item.chapter.lastPageRead}"
        }
        when (val preflight = translationManager.translateChapterPreflight(manga, item.chapter)) {
            is ChapterQueuePreflight.NoConflict -> launchTranslateChapter(manga, item.chapter)
            is ChapterQueuePreflight.RunningConflict -> {
                updateSuccessState {
                    it.copy(dialog = Dialog.RunningTranslationConflict(item, preflight))
                }
            }
            is ChapterQueuePreflight.RevisionBlocked -> {
                // A standalone revision is active for this chapter; the user must
                // finish or cancel it before starting a batch. Log and no-op here
                // so the existing revision UI stays the focus.
                logcat(LogPriority.WARN) {
                    "TachiyomiAT translate START blocked: revision active for chapter=${item.chapter.id}"
                }
            }
        }
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
        screenModelScope.launchNonCancellable {
            translationManager.cancelRunningChapterForReplace(preflight.chapterId)
            translationManager.translateChapter(manga, item.chapter)
        }
    }

    private fun launchTranslateChapter(manga: Manga, chapter: Chapter) {
        screenModelScope.launchNonCancellable {
            translationManager.translateChapter(manga, chapter)
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

    // TachiyomiAT CP7: standalone revision actions. The view-model reads the
    // reviewer engine/model from the injected [TranslationPreferences] (the
    // manager does not expose the pref setter) and dispatches only scope +
    // reviewer + language intents to [TranslationManager]. Closing the screen
    // does not cancel the work; the manager owns the job.

    /**
     * The persisted reviewer engine for standalone revision. Drives the default
     * model selection via [TranslationPreferences.translationAiModel].
     */
    private fun revisionReviewerEngine(): AiEngine =
        translationPreferences.revisionReviewerEngine().get()

    /**
     * Runs preflight for [item] with the given [scope] and opens the shared
     * revision confirm dialog with the typed outcome. The confirm dialog shows
     * the rejection reason (with a settings-nav action when recoverable) or the
     * Ready confirmation (reviewer picker, scope, counts).
     */
    fun startRevisionPreflight(item: ChapterList.Item, scope: RevisionScope) {
        val chapterId = item.chapter.id ?: return
        successState?.manga ?: return
        // Open the dialog in Idle while preflight runs so the user sees the
        // scope/reviewer surface immediately.
        updateSuccessState { it.copy(dialog = Dialog.RevisionConfirm(item, RevisionConfirmState.Idle)) }
        screenModelScope.launch {
            // Resolve the reviewer engine: Auto follows the Pass-1 translation
            // engine (with a configured-provider fallback when Pass-1 is non-AI
            // or its provider lacks a credential); explicit honors the persisted
            // reviewer engine. Mirrors the reader path.
            val reviewerOptions = item.revisionEligibility?.reviewerOptions
                ?: translationManager.revisionReviewerOptions()
            val engine = resolveEffectiveReviewerEngine(
                auto = translationPreferences.revisionReviewerAuto().get(),
                pass1Category = translationPreferences.translationEngineCategory().get(),
                pass1AiEngine = translationPreferences.translationAiEngine().get(),
                configuredOptions = reviewerOptions,
                persistedEngine = revisionReviewerEngine(),
            )
            val model = translationPreferences.translationAiModel(engine).get()
            val outcome = translationManager.runRevisionPreflight(
                chapterId = chapterId,
                chapterName = item.chapter.name,
                scope = scope,
                reviewerEngine = engine,
                reviewerModel = model,
            )
            val state = outcome.toConfirmState(reviewerOptions, engine)
            updateSuccessState { it.copy(dialog = Dialog.RevisionConfirm(item, state)) }
            // Keep eligibility fresh for this chapter so the next REVIEW reflects
            // any post-preflight state.
            observeRevisionEligibility(chapterId)
        }
    }

    /**
     * Updates the in-memory confirm state for reviewer/scope edits. The reviewer
     * engine is persisted immediately via [TranslationPreferences]; the scope is
     * held in-memory until the next preflight. No backend calls here.
     */
    fun updateRevisionConfirmState(
        item: ChapterList.Item,
        transform: (RevisionConfirmState) -> RevisionConfirmState,
    ) {
        updateSuccessState { success ->
            val dialog = success.dialog as? Dialog.RevisionConfirm ?: return@updateSuccessState success
            if (dialog.item.id != item.id) return@updateSuccessState success
            success.copy(dialog = Dialog.RevisionConfirm(item, transform(dialog.state)))
        }
    }

    /**
     * Persists the picked reviewer engine and updates the confirm dialog's
     * selection. The model is read from the per-engine model preference by the
     * next preflight, matching the manager's identity check.
     */
    fun pickRevisionReviewer(item: ChapterList.Item, option: RevisionReviewerOption) {
        // An explicit pick disables Auto so the chosen provider is honored.
        translationPreferences.revisionReviewerAuto().set(false)
        translationPreferences.revisionReviewerEngine().set(option.engine)
        updateRevisionConfirmState(item) { state -> state.withReviewerPicked(option) }
    }

    /**
     * Updates the confirm dialog's scope and re-runs preflight so the displayed
     * counts match the new scope before the user confirms.
     */
    fun changeRevisionScope(item: ChapterList.Item, scope: RevisionScope) {
        // Reflect the new scope immediately, then re-run preflight so the
        // Ready confirmation carries the new scope's counts.
        updateRevisionConfirmState(item) { state -> state.withScopeChanged(scope) }
        startRevisionPreflight(item = item, scope = scope)
    }

    /**
     * Confirms and starts the revision. Sends only scope + reviewer + language
     * to the manager (the opaque token is held backend-side and re-validated at
     * [TranslationManager.startRevision]). Closing the screen does not cancel
     * the work.
     */
    fun confirmStartRevision(item: ChapterList.Item, confirmation: RevisionConfirmation) {
        val manga = successState?.manga ?: return
        val chapterId = item.chapter.id ?: return
        // UI guard mirrors the manager's active-job admission so a fast
        // double-tap cannot enqueue a second start.
        if (translationManager.isRevisionActive(chapterId)) {
            logcat(LogPriority.WARN) { "Revision already active for chapter $chapterId; ignoring duplicate confirm" }
            return
        }
        val flow = translationManager.startRevision(
            manga = manga,
            chapter = item.chapter,
            scope = confirmation.scope,
            reviewerEngine = confirmation.reviewerEngine,
            reviewerModel = confirmation.reviewerModel,
        ) ?: run {
            logcat(LogPriority.WARN) {
                "startRevision returned null for chapter $chapterId (active batch / revision / deleted)"
            }
            return
        }
        observeTranslationProgress(chapterId)
        updateSuccessState {
            it.copy(dialog = Dialog.TranslationProgress(chapterId))
        }
        // Reference the flow so it is collected; the manager's tracker updates
        // the snapshot the progress sheet renders.
        screenModelScope.launch { flow.collect { /* snapshot flows through queueState */ } }
    }

    /**
     * Cancels an active revision for [item]. The manager clears its own job;
     * closing the screen is not required (and never cancels the work).
     */
    fun cancelRevision(item: ChapterList.Item) {
        val chapterId = item.chapter.id ?: return
        translationManager.cancelRevision(chapterId)
    }

    /**
     * Opens the terminal result sheet for [item], loading the latest durable
     * report. The sheet renders Loading, then Missing (no run yet) or Loaded
     * (K/C/U totals + bounded accepted changes).
     */
    fun showRevisionResult(item: ChapterList.Item) {
        val chapterId = item.chapter.id ?: return
        updateSuccessState {
            it.copy(dialog = Dialog.RevisionResult(item, eu.kanade.translation.model.RevisionResultState.Loading))
        }
        screenModelScope.launch {
            val report = translationManager.getLatestRevisionReport(chapterId)
            val state = report?.toResultState()
                ?: eu.kanade.translation.model.RevisionResultState.Missing
            updateSuccessState { success ->
                val dialog = success.dialog as? Dialog.RevisionResult ?: return@updateSuccessState success
                success.copy(dialog = Dialog.RevisionResult(item, state))
            }
        }
    }

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
        ) : Dialog

        // TachiyomiAT bug 3: another chapter of the same source is actively
        // translating; the user must confirm cancelling it before this chapter's
        // batch can start. The conflict carries enough identity for the dialog
        // copy and for cancelRunningChapterForReplace.
        data class RunningTranslationConflict(
            val item: ChapterList.Item,
            val conflict: eu.kanade.translation.model.ChapterQueuePreflight.RunningConflict,
        ) : Dialog

        // TachiyomiAT CP7: standalone revision confirmation + terminal result.
        // The confirm dialog holds the pure confirm state (Idle/Ready/Rejected)
        // so reviewer/scope edits are unit-testable; the result dialog carries
        // a pure result state (Loading/Missing/Loaded) so the sheet renders one
        // run identically from manga and reader.
        data class RevisionConfirm(
            val item: ChapterList.Item,
            val state: RevisionConfirmState,
        ) : Dialog
        data class RevisionResult(
            val item: ChapterList.Item,
            val state: eu.kanade.translation.model.RevisionResultState,
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
        // TachiyomiAT CP7: manager-derived standalone-revision eligibility.
        // Null until the first snapshot completes (or when the chapter has no
        // translation store). Drives the REVIEW menu action; independent of
        // aggregate [translationState] and downloaded-image state.
        val revisionEligibility: ChapterRevisionEligibility? = null,
        val downloadProgress: Int,
        val selected: Boolean = false,
    ) : ChapterList() {
        val id = chapter.id
        val isDownloaded = downloadState == Download.State.DOWNLOADED
    }
}
