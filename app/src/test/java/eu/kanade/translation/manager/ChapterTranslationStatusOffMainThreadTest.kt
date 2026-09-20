package eu.kanade.translation.manager

import eu.kanade.tachiyomi.source.Source
import eu.kanade.translation.storage.ActiveChapterStoreRegistry
import eu.kanade.translation.pipeline.TranslationPipeline
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.model.Translation
import eu.kanade.translation.pipeline.batch.TranslationBatchTrackerRegistry
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 *  ANR regression guard: the chapter translation status chain
 * (`TranslationManager.getChapterTranslationStatus` →
 * `TranslationProgressProjection.getChapterTranslationStatus` →
 * `DurableChapterStatusResolver.persistedChapterStatus`) used
 * `runBlocking(Dispatchers.IO)` around the durable store resolution, parking
 * the calling thread for O(pages) SAF/FUSE reads (5-9s on a 68-page translated
 * chapter → reader-launch ANR). The chain is now suspend.
 *
 * These tests fail on the pre-fix code: a probe task submitted to the
 * single-threaded "main" surrogate while the durable lookup is in flight can
 * only run if the caller suspended instead of parking in runBlocking.
 * Deterministic: latch-synchronized, no sleeps in the assertion paths, and all
 * orchestration runs under structured concurrency so a failed probe cannot
 * leak uncaught exceptions into a later runTest.
 */
class ChapterTranslationStatusOffMainThreadTest {

    /** Stub whose slow leg blocks an IO worker (like a real SAF lookup) — never the caller. */
    private fun blockingProvider(lookupStarted: CountDownLatch, releaseLookup: CountDownLatch): TranslationProvider =
        mockk {
            every { findTranslationFile(any(), any(), any(), any()) } answers {
                lookupStarted.countDown()
                releaseLookup.await(10, TimeUnit.SECONDS)
                null
            }
            // Absent manga dir → absent document → null (transient) result.
            every { findMangaDir(any(), any()) } returns null
        }

    /**
     * Runs [call] on a single-thread "main" surrogate while a probe task is
     * submitted to the same thread while [waitStarted] has fired and
     * [release] has not. If the caller parked (runBlocking) instead of
     * suspending, the probe cannot run and the check fails. Returns [call]'s
     * value once the call has fully completed.
     */
    private fun <T> assertCallerStaysFree(
        mainExecutor: java.util.concurrent.ExecutorService,
        waitStarted: CountDownLatch,
        release: CountDownLatch,
        call: suspend () -> T,
    ): T = runBlocking {
        val result = CompletableDeferred<T>()
        val work = launch(mainExecutor.asCoroutineDispatcher()) { result.complete(call()) }
        check(waitStarted.await(10, TimeUnit.SECONDS)) { "slow leg never started" }

        // With the old runBlocking implementation the calling thread was
        // parked inside the query until the lookup finished, so this probe
        // could not run — that is exactly the  reader-launch ANR.
        val probeRan = CountDownLatch(1)
        mainExecutor.execute { probeRan.countDown() }
        check(probeRan.await(5, TimeUnit.SECONDS)) {
            "caller thread stayed parked inside the status query (runBlocking regression)"
        }

        release.countDown()
        val value = withTimeout(10_000) { result.await() }
        work.join()
        value
    }

    @Test
    fun `persistedChapterStatus keeps the caller thread free while the durable lookup runs`() {
        val mainExecutor = Executors.newSingleThreadExecutor() // stands in for Main
        val lookupStarted = CountDownLatch(1)
        val releaseLookup = CountDownLatch(1)

        val sourceManager = mockk<SourceManager>()
        every { sourceManager.get(any()) } returns mockk<Source>(relaxed = true)
        val cache = ConcurrentHashMap<DurableChapterKey, DurableStatus>()
        val resolver = DurableChapterStatusResolver(
            providerProvider = { blockingProvider(lookupStarted, releaseLookup) },
            sourceManagerProvider = { sourceManager },
            activeStoresProvider = { ActiveChapterStoreRegistry() },
            durableStatusCacheProvider = { cache },
        )

        try {
            assertCallerStaysFree(mainExecutor, lookupStarted, releaseLookup) {
                resolver.persistedChapterStatus(1L, "Chapter 1", null, "Manga", 77L)
            }
            // Transient (null) outcomes are still not cached — same semantics as before.
            cache.isEmpty() shouldBe true
        } finally {
            releaseLookup.countDown()
            mainExecutor.shutdownNow()
        }
    }

    @Test
    fun `durable leg runs off the caller thread and its state is passed through`() {
        val mainExecutor = Executors.newSingleThreadExecutor() // stands in for Main
        val durableStarted = CountDownLatch(1)
        val releaseDurable = CountDownLatch(1)
        // The durable leg suspends to an IO worker, modelling the real
        // DurableChapterStatusResolver.persistedChapterStatus behaviour.
        val projector = projector(
            persistedChapterStatus = { _, _, _, _, _ ->
                withContext(Dispatchers.IO) {
                    durableStarted.countDown()
                    releaseDurable.await(10, TimeUnit.SECONDS)
                    Translation.State.TRANSLATED
                }
            },
        )

        try {
            val state = assertCallerStaysFree(mainExecutor, durableStarted, releaseDurable) {
                projector.getChapterTranslationStatus(5L, "Chapter 1", null, "Manga", 77L)
            }
            state shouldBe Translation.State.TRANSLATED
        } finally {
            releaseDurable.countDown()
            mainExecutor.shutdownNow()
        }
    }

    @Test
    fun `queued translation still wins without consulting the durable resolver`() {
        var durableInvocations = 0
        val projector = projector(
            getQueuedTranslationOrNull = {
                Translation(source = mockk(relaxed = true), manga = mockk(relaxed = true), chapter = mockk(relaxed = true))
                    .apply { status = Translation.State.QUEUE }
            },
            persistedChapterStatus = { _, _, _, _, _ ->
                durableInvocations++
                Translation.State.TRANSLATED
            },
        )

        runBlocking {
            projector.getChapterTranslationStatus(5L, "Chapter 1", null, "Manga", 77L) shouldBe Translation.State.QUEUE
        }
        durableInvocations shouldBe 0
    }

    @Test
    fun `null durable outcome still falls back to NOT_TRANSLATED`() {
        val projector = projector(persistedChapterStatus = { _, _, _, _, _ -> null })

        runBlocking {
            projector.getChapterTranslationStatus(5L, "Chapter 1", null, "Manga", 77L) shouldBe
                Translation.State.NOT_TRANSLATED
        }
    }

    private fun projector(
        getQueuedTranslationOrNull: (Long) -> Translation? = { null },
        persistedChapterStatus: suspend (
            chapterId: Long?,
            chapterName: String,
            chapterScanlator: String?,
            mangaTitle: String,
            sourceId: Long,
        ) -> Translation.State?,
    ): TranslationProgressProjection = TranslationProgressProjection(
        activeStoresProvider = { ActiveChapterStoreRegistry() },
        batchTrackerRegistryProvider = { TranslationBatchTrackerRegistry() },
        queueStateProvider = { MutableStateFlow(emptyList()) },
        pendingTranslationRequestsProvider = { MutableStateFlow(emptyMap()) },
        pipelineProvider = { mockk<TranslationPipeline>(relaxed = true) },
        getQueuedTranslationOrNull = getQueuedTranslationOrNull,
        persistedChapterStatus = persistedChapterStatus,
        openOrCreateStoreSuspend = { _, _, _, _, _, _ -> null },
        observeActiveDisplayStore = { null },
    )
}
