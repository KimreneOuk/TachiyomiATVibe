package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.chapter.interactor.GetAvailableScanlators
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.presentation.manga.components.ChapterTranslationAction
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.translation.model.ChapterQueuePreflight
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.model.TranslationSettingsSummary
import eu.kanade.translation.model.snapshotTranslationSummary
import eu.kanade.translation.orchestration.TranslationManager
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.fullType
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 *  slice 2 (R6): the bottom-bar batch action must reach the model's LIST
 * API end-to-end — ONE acknowledgement for all N selected chapters, one
 * confirmation dialog representing all of them, one mixed
 * downloaded/undownloaded partition (list translate + list enqueue), and the
 * drawer opening for the primary (first) chapter. The probe mutations are the
 * generation-fenced variants (R7); a refusal drops the candidate instead of
 * recreating a cancelled request.
 *
 * Same fixture approach as MangaScreenModelTranslationDrawerTest: fully mocked
 * constructor, real resumed LifecycleRegistry, single-threaded main executor.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MangaScreenModelMultiSelectBatchTest {

    private val mainThreadSurrogate = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private val mangaId = 1L

    private val manga: Manga = Manga.create().copy(
        id = mangaId,
        title = "Manga",
        source = 1L,
        favorite = true,
        initialized = true,
    )

    private fun chapterFixture(id: Long) = Chapter.create().copy(
        id = id,
        mangaId = mangaId,
        name = "Chapter $id",
    )

    private val chapters = listOf(5L, 6L, 7L).map(::chapterFixture)

    private val context = mockk<Context>(relaxed = true)
    private val lifecycleOwner = mockk<LifecycleOwner>(relaxed = true)
    private val lifecycleRegistry = LifecycleRegistry(lifecycleOwner)

    private val getMangaAndChapters = mockk<GetMangaWithChapters>(relaxed = true)
    private val downloadManager = mockk<DownloadManager>(relaxed = true)
    private val translationManager = mockk<TranslationManager>(relaxed = true)
    private val downloadCache = mockk<DownloadCache>(relaxed = true)
    private val trackerManager = mockk<TrackerManager>(relaxed = true)
    private val sourceManager = mockk<SourceManager>(relaxed = true)
    private val translationPreferences = mockk<TranslationPreferences>(relaxed = true)

    private val confirmPref = mockk<Preference<Boolean>> {
        every { get() } returns false
    }
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

    private lateinit var model: MangaScreenModel
    private var initialCollectorJobs: Set<Job> = emptySet()

    @BeforeAll
    fun setUp() {
        Dispatchers.setMain(mainThreadSurrogate)
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

        coEvery { getMangaAndChapters.subscribe(any(), any()) } returns flowOf(manga to chapters)
        coEvery { getMangaAndChapters.awaitManga(any()) } returns manga
        coEvery { getMangaAndChapters.awaitChapters(any(), any()) } returns chapters

        every { downloadManager.queueState } returns downloadQueueState
        every { downloadManager.statusFlow() } returns emptyFlow<Download>()
        every { downloadManager.progressFlow() } returns emptyFlow<Download>()
        every { downloadManager.getQueuedDownloadOrNull(any()) } returns null

        every { downloadCache.changes } returns MutableSharedFlow(extraBufferCapacity = 1)

        every { translationManager.queueState } returns translationQueueState
        every { translationManager.statusFlow() } returns translationStatusFlow
        every { translationManager.pendingTranslationRequests } returns pendingRequestsState
        every { translationManager.getQueuedTranslationOrNull(any()) } returns null
        //  ANR fix: getChapterTranslationStatus is now suspend.
        coEvery {
            translationManager.getChapterTranslationStatus(any(), any(), any(), any(), any())
        } returns Translation.State.NOT_TRANSLATED
        every { translationManager.observeBatchProgress(any()) } returns MutableStateFlow(
            eu.kanade.translation.model.TranslationProgressSnapshot.empty(5L),
        )
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

        every { getAvailableScanlators.subscribe(any()) } returns flowOf(emptySet())
        every { getExcludedScanlators.subscribe(any()) } returns flowOf(emptySet())
        every { getTracks.subscribe(any()) } returns flowOf(emptyList())

        every { libraryPreferences.swipeToEndAction() } returns swipeActionPref
        every { libraryPreferences.swipeToStartAction() } returns swipeActionPref
        every { libraryPreferences.autoUpdateMangaRestrictions() } returns updateRestrictionsPref
        every { trackPreferences.autoUpdateTrackOnMarkRead() } returns autoTrackPref
        every { readerPreferences.skipFiltered() } returns skipFilteredPref

        //  slice 2 test-infra note: voyager caches `screenModelScope` in a
        // JVM-global ScreenModelStore under a shared key for unregistered
        // models, so a scope cancelled by a previously-run fixture in this JVM
        // (e.g. MangaScreenModelTranslationDrawerTest) would silently kill this
        // fixture's collectors. Evict the cached scope before and after the
        // model boots so every fixture gets a live scope.
        evictCachedScreenModelScope()

        every { translationPreferences.translationConfirmPretranslate() } returns confirmPref
        // snapshotTranslationSummary is a top-level extension that reads real
        // preference values; static-mock it so the dialog test runs on the JVM.
        mockkStatic("eu.kanade.translation.model.TranslationSettingsSummaryKt")
        every { translationPreferences.snapshotTranslationSummary() } returns
            mockk<TranslationSettingsSummary>(relaxed = true)

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
            translationPreferences = translationPreferences,
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
        initialCollectorJobs = model.screenModelScope.coroutineContext[Job]?.children?.toSet().orEmpty()
    }

    /**
     * The model fixture is shared by the whole class; recorded calls would
     * otherwise leak between tests (JUnit order is not test-declaration order).
     * Only the call log is cleared — stubs from the fixture stay in place.
     */
    @BeforeEach
    fun clearRecordedCalls() {
        settleProbe()
        clearMocks(
            downloadManager,
            translationManager,
            answers = false,
            recordedCalls = true,
            childMocks = false,
        )
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
            //  the join is authoritative (no runCatching) — a >30s unwind fails THIS class with the real cause rather than leaking into the next fixture.
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            model.screenModelScope.cancel()
            runBlocking {
                withTimeout(30_000) {
                    model.screenModelScope.coroutineContext[Job]?.join()
                }
            }
        }
        evictCachedScreenModelScope()
        ArchTaskExecutor.getInstance().setDelegate(null)
        Dispatchers.resetMain()
        mainThreadSurrogate.close()
    }

    // -- tests --------------------------------------------------------------

    @Test
    fun `three selected chapters produce ONE confirmation containing all three`() {
        every { confirmPref.get() } returns true

        model.runChapterTranslationActions(items(5L, 6L, 7L), ChapterTranslationAction.START)

        val dialog = successState()?.dialog as? MangaScreenModel.Dialog.ConfirmTranslation
        assertTrue(dialog != null, "expected the confirm dialog to be selected")
        dialog!!.group.map { it.chapter.id } shouldContainExactly listOf(5L, 6L, 7L)

        // The dialog's Translate button proceeds with the whole group.
        model.confirmChapterTranslation(dialog.item)

        val acknowledged = slot<List<Chapter>>()
        verify(exactly = 1) {
            translationManager.acknowledgeTranslationRequests(capture(acknowledged))
        }
        acknowledged.captured.map { it.id } shouldContainExactly listOf(5L, 6L, 7L)
    }

    @Test
    fun `confirming a mixed selection partitions into list translate and list enqueue`() {
        every { confirmPref.get() } returns false
        every { translationManager.pendingRequestGeneration(any()) } returns 1L
        every {
            translationManager.queueTranslationAfterDownloadIfCurrent(eq(manga), any(), any())
        } returns true
        every { translationManager.isTranslationRequestCurrent(any(), any()) } returns true
        every { translationManager.markTranslationRequestPreparingIfCurrent(any(), any()) } returns true
        every {
            translationManager.translateChapterPreflight(any(), any())
        } returns ChapterQueuePreflight.NoConflict
        // Chapter 5 is on disk; 6 and 7 are not.
        every {
            downloadManager.isChapterDownloaded(eq("Chapter 5"), any(), any(), any(), any())
        } returns true

        //  verify-too-early hardening: the partition work (fenced admission
        // for downloaded chapters, bridge enqueue + startDownloads for the
        // rest) runs async after the drawer appears. Record the calls so the
        // assertions below await completion instead of racing the handler.
        val singleAdmitted = AtomicBoolean(false)
        val bridgeEnqueued = AtomicBoolean(false)
        val downloadsStarted = AtomicBoolean(false)
        every {
            translationManager.translateChapter(eq(manga), eq(chapterFixture(5L)), eq(1L))
        } answers {
            singleAdmitted.set(true)
        }
        every { downloadManager.downloadChapters(eq(manga), any(), any()) } answers { bridgeEnqueued.set(true) }
        every { downloadManager.startDownloads() } answers { downloadsStarted.set(true) }

        model.runChapterTranslationActions(items(5L, 6L, 7L), ChapterTranslationAction.START)
        awaitUntil("drawer selected for the primary chapter") {
            successState()?.dialog is MangaScreenModel.Dialog.TranslationProgress
        }
        awaitUntil("fenced admission for the downloaded chapter") { singleAdmitted.get() }
        awaitUntil("undownloaded chapters enqueued via the bridge") { bridgeEnqueued.get() }
        awaitUntil("downloads started") { downloadsStarted.get() }

        // ONE acknowledgement for the whole group.
        val acknowledged = slot<List<Chapter>>()
        verify(exactly = 1) {
            translationManager.acknowledgeTranslationRequests(capture(acknowledged))
        }
        acknowledged.captured.map { it.id } shouldContainExactly listOf(5L, 6L, 7L)

        // Undownloaded chapters are enqueued together via the  bridge.
        val enqueued = slot<List<Chapter>>()
        verify(exactly = 1) { downloadManager.downloadChapters(eq(manga), capture(enqueued), any()) }
        enqueued.captured.map { it.id } shouldContainExactly listOf(6L, 7L)
        verify(exactly = 1) { downloadManager.startDownloads() }

        // The downloaded chapter goes through the fenced single admission path.
        verify(exactly = 1) {
            translationManager.translateChapter(eq(manga), eq(chapterFixture(5L)), eq(1L))
        }

        // The drawer opened for the primary (first) chapter.
        successState()?.dialog shouldBe MangaScreenModel.Dialog.TranslationProgress(5L)
    }

    @Test
    fun `multiple downloaded chapters are admitted through the list API in one call`() {
        every { confirmPref.get() } returns false
        every { translationManager.pendingRequestGeneration(any()) } returns 1L
        every {
            translationManager.queueTranslationAfterDownloadIfCurrent(eq(manga), any(), any())
        } returns true
        every {
            downloadManager.isChapterDownloaded(any(), any(), any(), any(), any())
        } returns true
        val admitted = slot<List<Chapter>>()
        every {
            translationManager.translateChaptersIfCurrent(eq(manga), capture(admitted), any())
        } returns true

        model.runChapterTranslationActions(items(5L, 6L, 7L), ChapterTranslationAction.START)
        awaitUntil("fenced list admission reached") { admitted.isCaptured }
        settleProbe()

        admitted.captured.map { it.id } shouldContainExactly listOf(5L, 6L, 7L)
        // No download enqueue: everything was already on disk.
        verify(exactly = 0) { downloadManager.downloadChapters(any(), any(), any()) }
        verify(exactly = 0) { downloadManager.startDownloads() }
    }

    @Test
    fun `a cancelled request between check and use prevents the download enqueue`() {
        every { confirmPref.get() } returns false
        every { translationManager.pendingRequestGeneration(any()) } returns 1L
        // The generation fence refuses every candidate (the user cancelled first).
        every {
            translationManager.queueTranslationAfterDownloadIfCurrent(eq(manga), any(), any())
        } returns false

        model.runChapterTranslationActions(items(6L, 7L), ChapterTranslationAction.START)
        settleProbe()

        verify(exactly = 0) { downloadManager.downloadChapters(any(), any(), any()) }
        verify(exactly = 0) { downloadManager.startDownloads() }
        verify(exactly = 0) { translationManager.translateChaptersIfCurrent(any(), any(), any()) }
        verify(exactly = 0) { translationManager.translateChapter(any(), any(), any()) }
    }

    // -- helpers ------------------------------------------------------------

    /**
     * Quiesces background coroutines launched on [MangaScreenModel.screenModelScope]
     * and flushes the main thread surrogate dispatcher.
     */
    private fun settleProbe() {
        val rootJob = model.screenModelScope.coroutineContext[Job] ?: return
        val progressJobSet = try {
            val field = MangaScreenModel::class.java.getDeclaredField("translationProgressJobs")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (field.get(model) as? Map<*, Job>)?.values?.toSet().orEmpty()
        } catch (_: Throwable) {
            emptySet()
        }
        val transientJobs = rootJob.children.filter { job ->
            job.isActive && job !in initialCollectorJobs && job !in progressJobSet
        }.toList()
        if (transientJobs.isNotEmpty()) {
            runBlocking {
                withTimeout(5_000) {
                    transientJobs.joinAll()
                }
            }
        }
        runBlocking(mainThreadSurrogate) { /* flush pending dispatches */ }
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

    private val getAvailableScanlators: GetAvailableScanlators = mockk(relaxed = true)
    private val getExcludedScanlators: GetExcludedScanlators = mockk(relaxed = true)
    private val getTracks: GetTracks = mockk(relaxed = true)

    private fun items(vararg ids: Long): List<ChapterList.Item> =
        ids.map { id -> item(chapterFixture(id)) }

    private fun item(chapter: Chapter) = ChapterList.Item(
        chapter = chapter,
        downloadState = Download.State.NOT_DOWNLOADED,
        translationState = Translation.State.NOT_TRANSLATED,
        downloadProgress = 0,
    )

    private fun successState(): MangaScreenModel.State.Success? =
        model.state.value as? MangaScreenModel.State.Success

    private fun awaitUntil(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Timed out after ${timeoutMs}ms waiting for: $what")
            }
            Thread.sleep(5)
        }
    }

    private companion object {
        val injektPrepared = AtomicBoolean(false)
    }
}
