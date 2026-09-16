package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.chapter.interactor.GetAvailableScanlators
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.SetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.presentation.manga.components.ChapterTranslationAction
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.translation.TranslationManager
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.fullType
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T911 slice 1: confirmation must select the batch progress drawer in the same
 * UI transaction that acknowledges the request, and keyed snapshot retention
 * must keep the last live/terminal snapshot across full chapter-list rebuilds
 * and collector cancellation.
 *
 * MangaScreenModel is exercised through a fully mocked constructor fixture with
 * a real (resumed) LifecycleRegistry so the screen's lifecycle-gated collectors
 * actually run. The fixture boots once for the class and the tests run in a
 * fixed order against that single screen model: the constructor bootstrap is
 * expensive and re-bootstrapping a fresh model per test method proved
 * order-flaky on the JVM.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MangaScreenModelTranslationDrawerTest {

    // A real single-threaded main executor (as in MigratorTest); the JVM has no
    // real Android main thread.
    private val mainThreadSurrogate = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private val mangaId = 1L
    private val chapterId = 5L

    private val manga: Manga = Manga.create().copy(
        id = mangaId,
        title = "Manga",
        source = 1L,
        favorite = true,
        initialized = true,
    )
    private val chapter: Chapter = Chapter.create().copy(
        id = chapterId,
        mangaId = mangaId,
        name = "Chapter 5",
    )

    private val context = mockk<Context>(relaxed = true)
    private val lifecycleOwner = mockk<LifecycleOwner>(relaxed = true)
    private val lifecycleRegistry = LifecycleRegistry(lifecycleOwner)

    private val getMangaAndChapters = mockk<GetMangaWithChapters>(relaxed = true)
    private val downloadManager = mockk<DownloadManager>(relaxed = true)
    private val translationManager = mockk<TranslationManager>(relaxed = true)
    private val downloadCache = mockk<DownloadCache>(relaxed = true)
    private val trackerManager = mockk<TrackerManager>(relaxed = true)
    private val sourceManager = mockk<SourceManager>(relaxed = true)
    private val getAvailableScanlators = mockk<GetAvailableScanlators>(relaxed = true)
    private val getExcludedScanlators = mockk<GetExcludedScanlators>(relaxed = true)
    private val getTracks = mockk<GetTracks>(relaxed = true)

    // Relaxed mocks return plain Object for generic getters, which breaks the
    // typed enum/collection properties read in the constructor — stub them.
    private val swipeActionPref = mockk<Preference<LibraryPreferences.ChapterSwipeAction>> {
        every { get() } returns LibraryPreferences.ChapterSwipeAction.Disabled
        every { changes() } returns emptyFlow()
    }
    private val autoTrackPref = mockk<Preference<AutoTrackState>> {
        every { get() } returns AutoTrackState.NEVER
        every { changes() } returns emptyFlow()
    }
    private val skipFilteredPref = mockk<Preference<Boolean>> {
        every { get() } returns false
        every { changes() } returns emptyFlow()
    }
    private val updateRestrictionsPref = mockk<Preference<Set<String>>> {
        every { get() } returns emptySet()
        every { changes() } returns emptyFlow()
    }
    private val libraryPreferences = mockk<LibraryPreferences>(relaxed = true)
    private val trackPreferences = mockk<TrackPreferences>(relaxed = true)
    private val readerPreferences = mockk<ReaderPreferences>(relaxed = true)

    private val downloadQueueState = MutableStateFlow(emptyList<Download>())
    private val translationQueueState = MutableStateFlow(emptyList<Translation>())
    private val translationStatusFlow = MutableSharedFlow<Translation>(extraBufferCapacity = 16)
    private val pendingRequestsState = MutableStateFlow(emptyMap<Long, TranslationRequestState>())
    private val batchProgressFlow = MutableStateFlow(TranslationProgressSnapshot.empty(chapterId))

    private lateinit var model: MangaScreenModel

    @BeforeAll
    fun setUp() {
        Dispatchers.setMain(mainThreadSurrogate)
        // LifecycleRegistry enforces main-thread handling through ArchTaskExecutor,
        // which needs android.os.Looper (not mocked in JVM tests). Install a direct
        // executor delegate so lifecycle events stay synchronous and Looper-free.
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
        })
        if (injektPrepared.compareAndSet(false, true)) {
            Injekt.addSingleton(fullType<SourceManager>(), sourceManager)
        }
        every { sourceManager.getOrStub(any()) } returns mockk<Source>(relaxed = true)
        every { lifecycleOwner.lifecycle } returns lifecycleRegistry
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        coEvery { getMangaAndChapters.subscribe(any(), any()) } returns flowOf(manga to listOf(chapter))
        coEvery { getMangaAndChapters.awaitManga(any()) } returns manga
        coEvery { getMangaAndChapters.awaitChapters(any(), any()) } returns listOf(chapter)

        every { downloadManager.queueState } returns downloadQueueState
        every { downloadManager.statusFlow() } returns emptyFlow<Download>()
        every { downloadManager.progressFlow() } returns emptyFlow<Download>()
        every { downloadManager.getQueuedDownloadOrNull(any()) } returns null
        every { downloadManager.isChapterDownloaded(any(), any(), any(), any()) } returns false

        every { downloadCache.changes } returns MutableSharedFlow(extraBufferCapacity = 1)

        every { translationManager.queueState } returns translationQueueState
        every { translationManager.statusFlow() } returns translationStatusFlow
        every { translationManager.pendingTranslationRequests } returns pendingRequestsState
        every { translationManager.getQueuedTranslationOrNull(any()) } returns null
        // T912 ANR fix: getChapterTranslationStatus is now suspend.
        coEvery {
            translationManager.getChapterTranslationStatus(any(), any(), any(), any(), any())
        } returns Translation.State.NOT_TRANSLATED
        every { translationManager.observeBatchProgress(any()) } returns batchProgressFlow
        coEvery {
            translationManager.openOrCreateActiveChapterTranslationStoreSuspend(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } returns null
        every { translationManager.hasPendingTranslationRequest(any()) } returns false

        every { getAvailableScanlators.subscribe(any()) } returns flowOf(emptySet())
        every { getExcludedScanlators.subscribe(any()) } returns flowOf(emptySet())
        every { getTracks.subscribe(any()) } returns flowOf(emptyList())

        every { libraryPreferences.swipeToEndAction() } returns swipeActionPref
        every { libraryPreferences.swipeToStartAction() } returns swipeActionPref
        every { libraryPreferences.autoUpdateMangaRestrictions() } returns updateRestrictionsPref
        every { trackPreferences.autoUpdateTrackOnMarkRead() } returns autoTrackPref
        every { readerPreferences.skipFiltered() } returns skipFilteredPref

        // T911 slice 2 test-infra note (same as MangaScreenModelMultiSelectBatchTest):
        // voyager caches `screenModelScope` in a JVM-global ScreenModelStore under a
        // shared key for unregistered models, so a scope cancelled by a fixture that
        // ran earlier in this JVM (full-suite ordering) would silently kill this
        // fixture's collectors and boot would stall in State.Loading. Evict the
        // cached scope before boot so this fixture gets a live scope. This
        // full-suite-only flake was confirmed reproducible at HEAD 45c8f03 with no
        // local changes.
        evictCachedScreenModelScope()

        model = MangaScreenModel(
            context = context,
            lifecycle = lifecycleRegistry,
            mangaId = mangaId,
            isFromSource = false,
            libraryPreferences = libraryPreferences,
            trackPreferences = trackPreferences,
            readerPreferences = readerPreferences,
            trackerManager = trackerManager,
            trackChapter = mockk(relaxed = true),
            downloadManager = downloadManager,
            translationManager = translationManager,
            translationPreferences = mockk(relaxed = true),
            downloadCache = downloadCache,
            getMangaAndChapters = getMangaAndChapters,
            getDuplicateLibraryManga = mockk(relaxed = true),
            getAvailableScanlators = getAvailableScanlators,
            getExcludedScanlators = getExcludedScanlators,
            setExcludedScanlators = mockk(relaxed = true),
            setMangaChapterFlags = mockk(relaxed = true),
            setMangaDefaultChapterFlags = mockk(relaxed = true),
            setReadStatus = mockk(relaxed = true),
            updateChapter = mockk(relaxed = true),
            updateManga = mockk(relaxed = true),
            syncChaptersWithSource = mockk(relaxed = true),
            getCategories = mockk(relaxed = true),
            getTracks = getTracks,
            addTracks = mockk(relaxed = true),
            setMangaCategories = mockk(relaxed = true),
            mangaRepository = mockk(relaxed = true),
            filterChaptersForDownload = mockk(relaxed = true),
        )
        awaitUntil("screen state becomes Success") { successState() != null }
    }

    @AfterAll
    fun tearDown() {
        if (::model.isInitialized) {
            // Quiesce BEFORE dismantling the Looper-free main-thread fakes:
            // the observeTranslationProgress collectors unwind on the REAL
            // Dispatchers.IO, and their flowWithLifecycle/repeatOnLifecycle
            // teardown calls LifecycleRegistry.removeObserver — a
            // main-thread-enforced call. If that unwind lands after
            // setDelegate(null) below, the check falls back to android.os.Looper
            // (not mocked on the JVM) and the RuntimeException surfaces as an
            // uncaught exception in whichever test class runs next. Destroying
            // the lifecycle and JOINING the cancelled scope while the delegate
            // is still installed pins the unwind inside this teardown.
            // T934: the join is authoritative (no runCatching) — a >30s unwind fails THIS class with the real cause rather than leaking into the next fixture.
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            model.screenModelScope.cancel()
            runBlocking {
                withTimeout(30_000) {
                    model.screenModelScope.coroutineContext[Job]?.join()
                }
            }
        }
        // Do not leave the cancelled scope in the shared cache for the next
        // screen-model fixture in this JVM.
        evictCachedScreenModelScope()
        ArchTaskExecutor.getInstance().setDelegate(null)
        Dispatchers.resetMain()
        mainThreadSurrogate.close()
    }

    // -- tests --------------------------------------------------------------

    @Test
    @Order(1)
    fun `confirmChapterTranslation selects the translation progress drawer for the chapter`() {
        model.confirmChapterTranslation(itemFor())

        awaitUntil("progress drawer dialog selected") {
            successState()?.dialog is MangaScreenModel.Dialog.TranslationProgress
        }
        assertEquals(
            MangaScreenModel.Dialog.TranslationProgress(chapterId),
            successState()?.dialog,
        )
    }

    @Test
    @Order(2)
    fun `DETAILS still selects the translation progress drawer`() {
        model.runChapterTranslationActions(itemFor(), ChapterTranslationAction.DETAILS)

        awaitUntil("progress drawer dialog selected") {
            successState()?.dialog is MangaScreenModel.Dialog.TranslationProgress
        }
    }

    @Test
    @Order(3)
    fun `live snapshot survives a full chapter-list rebuild without a new canonical emission`() {
        // The keyed collector is already running (opened by confirm/details).
        val live = TranslationProgressSnapshot.empty(chapterId, Translation.State.TRANSLATING)
            .copy(donePages = 12, totalPages = 40, totalStages = 160)

        batchProgressFlow.value = live
        awaitUntil("collector copied the live snapshot into the item") {
            itemFor().translationProgress == live
        }

        // A full base-list rebuild without a new canonical emission: the item is
        // reconstructed with translationProgress = null and must read the
        // snapshot back from the screen-model registry.
        val rebuilt = rebuildChapters()

        assertEquals(live, rebuilt.single().translationProgress)
    }

    @Test
    @Order(4)
    fun `terminal snapshot survives collector cancellation and a later rebuild`() = runBlocking {
        val terminal = TranslationProgressSnapshot.empty(chapterId, Translation.State.TRANSLATED)
            .copy(
                donePages = 40,
                totalPages = 40,
                totalStages = 160,
                batchPhase = TranslationBatchPhase.FINISHED,
            )

        batchProgressFlow.value = terminal
        awaitUntil("collector copied the terminal snapshot into the item") {
            itemFor().translationProgress == terminal
        }

        // Terminal translation status cancels the per-chapter collector.
        translationStatusFlow.tryEmit(
            Translation(source = mockk(relaxed = true), manga = manga, chapter = chapter)
                .apply { status = Translation.State.TRANSLATED },
        )
        awaitUntil("collector stopped at terminal status") {
            itemFor().translationState == Translation.State.TRANSLATED
        }

        // A later canonical emission must not reach the cancelled collector.
        val afterCancel = terminal.copy(donePages = 999)
        batchProgressFlow.value = afterCancel
        delay(200)
        assertNotEquals(afterCancel, itemFor().translationProgress)

        // And the rebuild after cancellation must retain the terminal snapshot.
        val rebuilt = rebuildChapters()
        assertEquals(terminal, rebuilt.single().translationProgress)
    }

    @Test
    @Order(5)
    fun `waiting request acknowledgement reaches the chapter item and keeps the drawer state`() {
        // Simulate the acknowledgement the coordinator publishes on confirm.
        pendingRequestsState.value = mapOf(
            chapterId to TranslationRequestState(chapterId, TranslationRequestPhase.WAITING_FOR_DOWNLOAD),
        )

        awaitUntil("item carries the pending request") {
            itemFor().translationRequest?.phase == TranslationRequestPhase.WAITING_FOR_DOWNLOAD
        }

        model.runChapterTranslationActions(itemFor(), ChapterTranslationAction.DETAILS)
        awaitUntil("progress drawer dialog selected") {
            successState()?.dialog is MangaScreenModel.Dialog.TranslationProgress
        }
    }

    // -- helpers ------------------------------------------------------------

    private fun successState(): MangaScreenModel.State.Success? =
        model.state.value as? MangaScreenModel.State.Success

    private fun itemFor(id: Long = chapterId): ChapterList.Item =
        successState()?.chapters?.firstOrNull { it.id == id }
            ?: error("chapter item $id not present in screen state")

    /** Simulates a full base-list rebuild without any new canonical snapshot emission. */
    // T912 ANR fix: toChapterListItems is now suspend (it queries the durable
    // translation status per downloaded chapter).
    private fun rebuildChapters(): List<ChapterList.Item> = runBlocking {
        with(model) {
            listOf(chapter).toChapterListItems(this@MangaScreenModelTranslationDrawerTest.manga)
        }
    }

    private fun awaitUntil(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError(
                    "Timed out after ${timeoutMs}ms waiting for: $what (state=${model.state.value})",
                )
            }
            Thread.sleep(10)
        }
    }

    private fun evictCachedScreenModelScope() {
        try {
            // `dependencies` is Kotlin-internal in voyager; reach it reflectively.
            // Standalone (non-screen) models share ONE cache key derived from
            // "ScreenModelCoroutineScope", so a scope cancelled by a
            // previously-run fixture in this JVM would silently kill this
            // fixture's collectors.
            val store = cafe.adriel.voyager.core.model.ScreenModelStore
            val getDependencies = store.javaClass.methods.first { it.name == "getDependencies" }
            val dependencies = getDependencies.invoke(store) as? MutableMap<Any?, Any?> ?: return
            dependencies.keys.removeAll { key -> key is String && "ScreenModelCoroutineScope" in key }
        } catch (_: Exception) {
            // Best effort: only matters when another screen-model fixture ran
            // earlier in this JVM.
        }
    }

    private companion object {
        val injektPrepared = AtomicBoolean(false)
    }
}
