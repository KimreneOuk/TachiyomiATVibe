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
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.translation.TranslationManager
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
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
import kotlin.coroutines.cancellation.CancellationException

/**
 * T918 — batch retry affordance, state-reconciliation leg.
 *
 * RED defect named here: "cancelled batch strands chapter in translating state
 * with no restart affordance". When a batch is cancelled and its queue entry is
 * already gone (ChapterTranslator.kt CancellationException branch), no
 * statusFlow emission ever reaches the screen: queue membership IS the
 * acknowledgement, and a removed entry is dropped without a terminal
 * emission. The chapter item keeps its stale QUEUE/TRANSLATING/PAUSED state,
 * the indicator routes every tap to the progress drawer, and the drawer's
 * long-press CANCEL is a no-op — the user cannot restart the batch.
 *
 * The production fact the reconciliation keys on (per the task contract): the
 * observed batch snapshot becomes ABORTED-TERMINAL while
 * getQueuedTranslationOrNull(chapterId) == null. In that exact situation the
 * chapter item must land in a restartable state (NOT_TRANSLATED) — unless a
 * NEW batch for the chapter was already queued, which must never be clobbered.
 *
 * MangaScreenModel is exercised through the same fully mocked constructor
 * fixture as MangaScreenModelTranslationDrawerTest (real resumed
 * LifecycleRegistry). Sleep-free: every await is a bounded
 * `state.first { … }`, and the negative oracle is the sanctioned bounded
 * negative probe (NormalMangaIsolationTest precedent: TimeoutCancellationException
 * means the forbidden transition never fired).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MangaScreenModelCancelledBatchReconciliationTest {

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
        every { downloadManager.isChapterDownloaded(any(), any(), any(), any()) } returns true

        every { downloadCache.changes } returns MutableSharedFlow(extraBufferCapacity = 1)

        every { translationManager.queueState } returns translationQueueState
        every { translationManager.statusFlow() } returns translationStatusFlow
        every { translationManager.pendingTranslationRequests } returns pendingRequestsState
        every { translationManager.getQueuedTranslationOrNull(any()) } answers {
            // Mirror production truth: the queue lookup reads the live queue.
            translationQueueState.value.firstOrNull { it.chapter.id == firstArg<Long>() }
        }
        coEvery {
            translationManager.getChapterTranslationStatus(any(), any(), any(), any(), any())
        } returns Translation.State.NOT_TRANSLATED
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
        every { translationManager.observeBatchProgress(any()) } returns batchProgressFlow
        every { translationManager.hasPendingTranslationRequest(any()) } returns false

        every { getAvailableScanlators.subscribe(any()) } returns flowOf(emptySet())
        every { getExcludedScanlators.subscribe(any()) } returns flowOf(emptySet())
        every { getTracks.subscribe(any()) } returns flowOf(emptyList())

        every { libraryPreferences.swipeToEndAction() } returns swipeActionPref
        every { libraryPreferences.swipeToStartAction() } returns swipeActionPref
        every { libraryPreferences.autoUpdateMangaRestrictions() } returns updateRestrictionsPref
        every { trackPreferences.autoUpdateTrackOnMarkRead() } returns autoTrackPref
        every { readerPreferences.skipFiltered() } returns skipFilteredPref

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
        // Sleep-free boot wait: bounded state.first{} (no polling loop).
        runBlocking {
            withTimeout(AWAIT_TIMEOUT_MS) {
                model.state.first { it is MangaScreenModel.State.Success }
            }
            // T922 flake stabilization: the status emissions this class drives
            // ride a no-replay MutableSharedFlow, and the screen model
            // subscribes to it asynchronously (init launch on Dispatchers.IO).
            // An emission fired before that subscription is live is silently
            // dropped forever — observed as the item stuck at NOT_TRANSLATED
            // until the 10s await budget expired, only under full-suite load.
            // Hold setup until the status collector has actually subscribed.
            translationStatusFlow.subscriptionCount.first { it > 0 }
        }
    }

    @AfterAll
    fun tearDown() {
        if (::model.isInitialized) {
            model.screenModelScope.cancel()
        }
        evictCachedScreenModelScope()
        ArchTaskExecutor.getInstance().setDelegate(null)
        Dispatchers.resetMain()
        mainThreadSurrogate.close()
    }

    // -- tests --------------------------------------------------------------

    /**
     * THE defect: the queue entry is removed mid-run (production: user Stop,
     * notification stop, queue removal), the tracker aborts, the aborted
     * terminal snapshot arrives — and the item must land restartable
     * (NOT_TRANSLATED) instead of staying stuck in the live-looking state.
     */
    @Test
    @Order(1)
    fun `cancelled batch strands chapter in translating state with no restart affordance`() = runBlocking {
        // Mid-run production state: a live queue entry translating, observed by
        // the item through the manager status flow.
        val entry = Translation(mockk(relaxed = true), manga, chapter)
            .apply { status = Translation.State.TRANSLATING }
        translationQueueState.value = listOf(entry)
        translationStatusFlow.tryEmit(entry)
        awaitItem("item shows the live TRANSLATING state") {
            it.translationState == Translation.State.TRANSLATING
        }

        // The cancel: the queue entry is removed. Production truth — no
        // statusFlow emission reaches the screen for a removed entry, so the
        // item stays stale until the aborted snapshot arrives.
        translationQueueState.value = emptyList()

        // The ChapterTranslator CancellationException branch aborts the tracker;
        // the observed snapshot becomes terminal-aborted with no queue entry.
        batchProgressFlow.value = terminalAbortedSnapshot(donePages = 1)

        awaitItem(
            "T918 defect: the stranded chapter must land restartable (NOT_TRANSLATED) " +
                "once the aborted terminal snapshot is observed with no queue entry",
        ) {
            it.translationState == Translation.State.NOT_TRANSLATED
        }
    }

    /** A newly queued batch (the user restarted, or another entry exists) is never clobbered. */
    @Test
    @Order(2)
    fun `aborted snapshot does not clobber a newly queued batch for the same chapter`() = runBlocking {
        val newEntry = Translation(mockk(relaxed = true), manga, chapter)
            .apply { status = Translation.State.QUEUE }
        translationQueueState.value = listOf(newEntry)
        translationStatusFlow.tryEmit(newEntry)
        awaitItem("item shows the new batch's QUEUE state") {
            it.translationState == Translation.State.QUEUE
        }

        // A late aborted snapshot from the PREVIOUS run arrives while a new
        // batch is already queued: the guard must leave the live state alone.
        batchProgressFlow.value = terminalAbortedSnapshot(donePages = 99)

        // Sanctioned bounded negative probe: the forbidden transition is the
        // item leaving QUEUE; TimeoutCancellationException (a
        // CancellationException) means it never fired.
        val clobbered = try {
            withTimeout(NEGATIVE_PROBE_MS) {
                model.state.first { state ->
                    (state as? MangaScreenModel.State.Success)
                        ?.chapters
                        ?.firstOrNull { it.id == chapterId }
                        ?.let { it.translationState != Translation.State.QUEUE } == true
                }
            }
            true
        } catch (_: CancellationException) {
            false
        }
        assertEquals(false, clobbered, "the aborted reconciliation clobbered a newly queued batch")
    }

    // -- helpers ------------------------------------------------------------

    /** The real tracker-aborted terminal snapshot shape (BatchAborted reduce outcome). */
    private fun terminalAbortedSnapshot(donePages: Int) =
        TranslationProgressSnapshot.empty(chapterId, Translation.State.ERROR)
            .copy(
                aborted = true,
                abortedReason = "Batch cancelled",
                batchPhase = TranslationBatchPhase.FINISHED,
                donePages = donePages,
            )

    private fun successState(): MangaScreenModel.State.Success? =
        model.state.value as? MangaScreenModel.State.Success

    private suspend fun awaitItem(what: String, condition: (ChapterList.Item) -> Boolean) {
        try {
            withTimeout(AWAIT_TIMEOUT_MS) {
                model.state.first { state ->
                    (state as? MangaScreenModel.State.Success)
                        ?.chapters
                        ?.firstOrNull { it.id == chapterId }
                        ?.let(condition) == true
                }
            }
        } catch (timeout: CancellationException) {
            throw AssertionError(
                "Timed out after ${AWAIT_TIMEOUT_MS}ms waiting for: $what (state=${model.state.value})",
                timeout,
            )
        }
    }

    private fun evictCachedScreenModelScope() {
        try {
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

        /** Bound for every event-driven await (harness precedent). */
        const val AWAIT_TIMEOUT_MS = 10_000L

        /** Bound for NEGATIVE oracles ("X must NOT happen") — harness precedent. */
        const val NEGATIVE_PROBE_MS = 2_000L
    }
}
