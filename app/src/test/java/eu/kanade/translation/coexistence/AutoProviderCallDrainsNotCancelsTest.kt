package eu.kanade.translation.coexistence

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.engines.translator.AdmissionPriority
import eu.kanade.translation.engines.translator.TranslatorComputeClass
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.SinglePageOutcome
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.persistence.artifact.ArtifactSeed
import eu.kanade.translation.persistence.artifact.AtomicChapterDocuments
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactLayout
import eu.kanade.translation.persistence.artifact.FakeChapterDocumentIo
import eu.kanade.translation.persistence.artifact.loadArtifact
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.PageWriteOrigin
import eu.kanade.translation.pipeline.TranslationPipeline
import eu.kanade.translation.pipeline.execution.TranslationExecutor
import eu.kanade.translation.pipeline.execution.TranslationStageListener
import eu.kanade.translation.pipeline.toPrecondition
import eu.kanade.translation.scheduling.OperationPaidCallLifecycle
import eu.kanade.translation.scheduling.ReaderDrainResult
import eu.kanade.translation.scheduling.ReaderPageWork
import eu.kanade.translation.scheduling.ReaderSessionToken
import eu.kanade.translation.scheduling.ReaderTranslationQueue
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drain-not-cancel behavior for reader provider calls (Phase 4 queue contract).
 *
 * When the reader session is cancelled while an automatic provider call is in
 * flight, the call must DRAIN to physical completion — translate + durable
 * commit run to the end under the queue's paid-call retention — instead of
 * being torn down mid-call. The bounded drain grace is pinned by
 * [OperationPaidCallLifecycle.PROVIDER_DRAIN_GRACE_MS]; its expiry wiring is
 * covered queue-side by `ReaderTranslationQueueTest` ("paid drain expiry
 * cancels the exact worker but waits for physical exit").
 *
 * Fixture: the REAL [ReaderTranslationQueue] automatic-demand path over a
 * minimal executor fake and the REAL [ChapterTranslationStore]. Re-seamed in
 * Phase 4 S4 from the retired RollingAutoCoordinator onto the queue; the fake
 * enters the paid call through the production lifecycle element exactly as the
 * provider governor does.
 */
class AutoProviderCallDrainsNotCancelsTest {

    companion object {
        private const val AWAIT_TIMEOUT_MS = 60_000L
    }

    private val testScheduler = TestCoroutineScheduler()
    private val workerDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
    private val scope = CoroutineScope(workerDispatcher + SupervisorJob())
    private val manga = mockk<Manga>(relaxed = true)
    private val chapter = mockk<Chapter>(relaxed = true)
    private val source = mockk<HttpSource>(relaxed = true)

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** Production fresh-chapter recipe over fake artifact IO. */
    private fun freshStore(pageKeys: List<String>): ChapterTranslationStore {
        val artifactStore = ChapterArtifactEngine(
            AtomicChapterDocuments(FakeChapterDocumentIo()),
            ChapterArtifactLayout("D6 Drain Chapter"),
        )
        val manifest = artifactStore
            .loadArtifact(ArtifactSeed(createdAtEpochMs = 1L))
            .manifest
        return ChapterTranslationStore(
            artifactParentResolver = null,
            initialPages = pageKeys.associateWith { key -> PageTranslation(sourceFileName = key) },
            artifactStore = artifactStore,
            initialArtifactManifest = manifest,
        )
    }

    private fun newQueue(
        executor: TranslationExecutor,
        store: ChapterTranslationStore,
    ): ReaderTranslationQueue = ReaderTranslationQueue(
        executor = executor,
        scope = scope,
        workerDispatcher = workerDispatcher,
        storeResolver = { store },
        nowEpochMs = { testScheduler.currentTime },
        cancellationWaitMs = 500L,
    )

    // ------------------------------------------------------------------
    // §2.3 seam bridge — named failure, never a timeout.
    // ------------------------------------------------------------------

    /**
     * Pins the production grace bound to the drained call chain's legitimate
     * budget (ONNX 90 s + HTTP/render 120 s, sequential = 210 s) so a healthy
     * long call is never cut cancellation-class mid-chain.
     */
    @Test
    fun `drain grace companion bound matches the attach chain budget`() {
        OperationPaidCallLifecycle.PROVIDER_DRAIN_GRACE_MS shouldBe (
            TranslationPipeline.ONNX_PHASE_TIMEOUT_MS +
                TranslationPipeline.SINGLE_PAGE_TIMEOUT_MS
            )
    }

    // ------------------------------------------------------------------
    // THE drain: a cancelled session lets the in-flight call FINISH
    // ------------------------------------------------------------------

    @Test
    fun `cancelled session drains the in flight automatic call and commits instead of cancelling`() = runTest(workerDispatcher) {
        val store = freshStore(listOf("p0"))
        val executor = DrainExecutor(store)
        val queue = newQueue(executor, store)
        val token = ReaderSessionToken("drain-session", 1L)
        queue.bindSession(token, manga, chapter, source, TranslatorComputeClass.REMOTE_IO)
        try {
            queue.updateViewport(token, 0, 1, true) { index ->
                ReaderPageWork("p$index", index) { ByteArrayInputStream(byteArrayOf(1)) }
            }
            testScheduler.runCurrent()
            // The paid call is parked mid-flight (the PROVIDER_START analogue).
            withTimeout(AWAIT_TIMEOUT_MS) { executor.awaitTranslateStarted("p0") }

            // Reader leaves: the real teardown path cancels the queue session.
            val drain = queue.cancelSession(token)

            // §2.3: the call must DRAIN — the session's bounded control wait
            // ends in a pending native drain, never in a mid-call teardown.
            drain.shouldBeInstanceOf<ReaderDrainResult.PendingNativeDrain>()

            // Releasing the gate lets translate + commit run to completion
            // even though the session is gone.
            executor.releaseTranslate("p0")
            withTimeout(AWAIT_TIMEOUT_MS) { drain.completion.await() }

            withClue(
                "T917 D6 §2.3 defect: the drained call must commit its translation-terminal state " +
                    "even though the session is gone — RED strands the page because cancellation " +
                    "tears the call down mid-flight",
            ) {
                val page = store.state.value.getValue("p0")
                page.translationStatus shouldBe StageStatus.READY
                page.ocrStatus shouldBe StageStatus.READY
                page.inpaintStatus shouldBe StageStatus.READY
            }
            withClue("T917 D6 §2.3: exactly one paid call — the drain must not re-translate") {
                executor.translateCallsFor("p0") shouldBe 1
                executor.cancelledCalls.get() shouldBe 0
            }
        } finally {
            executor.releaseTranslate("p0")
            testScheduler.advanceUntilIdle()
        }
    }

    // ------------------------------------------------------------------
    // minimal executor fake: parks the paid call on a test-held gate and
    // commits display-ready truth through the REAL store when allowed to run.
    // ------------------------------------------------------------------

    private class DrainExecutor(
        private val store: ChapterTranslationStore,
    ) : TranslationExecutor {

        val translateCalls = ConcurrentHashMap<String, AtomicInteger>()
        val cancelledCalls = AtomicInteger(0)

        private val translateStarted = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val translateGate = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        private fun gate(map: ConcurrentHashMap<String, CompletableDeferred<Unit>>, key: String) =
            map.getOrPut(key) { CompletableDeferred<Unit>() }

        fun translateCallsFor(pageKey: String): Int = translateCalls[pageKey]?.get() ?: 0

        suspend fun awaitTranslateStarted(pageKey: String) {
            gate(translateStarted, pageKey).await()
        }

        fun releaseTranslate(pageKey: String) {
            gate(translateGate, pageKey).complete(Unit)
        }

        override suspend fun translateSinglePage(
            manga: Manga,
            chapter: Chapter,
            source: HttpSource,
            pageKey: String,
            force: Boolean,
            stageListener: TranslationStageListener?,
            origin: PageWriteOrigin,
            readerStreamFn: (() -> InputStream)?,
            readerPriority: (() -> AdmissionPriority)?,
        ): SinglePageOutcome {
            translateCalls.getOrPut(pageKey) { AtomicInteger(0) }.incrementAndGet()
            // The provider governor enters the paid call after the grant,
            // before the budget charge — the fake mirrors that entry point.
            currentCoroutineContext()[OperationPaidCallLifecycle]?.tryEnterPaidCall()
            gate(translateStarted, pageKey).complete(Unit)
            try {
                gate(translateGate, pageKey).await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelledCalls.incrementAndGet()
                throw e
            }
            // The real pipeline's terminal commit (translation-terminal truth) —
            // the same guarded write the manual publish shim performs: a
            // manifest-free precondition so the dependency-fingerprint clause
            // cannot reject the fake's page-derived snapshot fingerprint.
            val result = store.updatePageGuarded(
                pageKey,
                store.snapshot(pageKey).toPrecondition().copy(dependencyFingerprint = null),
                "D6 drain commit",
            ) { current ->
                displayReady(pageKey, current)
            }
            check(result is ChapterTranslationStore.PatchResult.Accepted) {
                "D6 drain commit rejected: ${(result as? ChapterTranslationStore.PatchResult.Rejected)?.reason}"
            }
            return SinglePageOutcome.Completed
        }

        private fun displayReady(pageKey: String, current: PageTranslation?): PageTranslation =
            (current ?: PageTranslation(sourceFileName = pageKey)).apply {
                // The paid call's terminal commit is the translate+persist
                // stage, so the fake commits the same translation-terminal
                // shape used by the manual publish shim. Promoting renderStatus
                // to READY additionally needs a decodable cleaned base file,
                // which a JVM fixture cannot produce.
                ocrStatus = StageStatus.READY
                translationStatus = StageStatus.READY
                inpaintStatus = StageStatus.READY
                cleanedImageName = "$pageKey.cleaned.png"
                inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
                inpaintingModeUsed = "FAST"
                errorMessage = null
                blocks = mutableListOf(
                    TranslationBlock(
                        text = "源",
                        translation = "drained",
                        width = 10f,
                        height = 10f,
                        x = 0f,
                        y = 0f,
                        symHeight = 1f,
                        symWidth = 1f,
                        angle = 0f,
                    ),
                )
            }
    }
}
