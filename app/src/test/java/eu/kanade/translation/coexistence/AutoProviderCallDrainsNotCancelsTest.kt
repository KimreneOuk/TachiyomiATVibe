package eu.kanade.translation.coexistence

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.engines.translator.TranslatorComputeClass
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.persistence.artifact.ArtifactSeed
import eu.kanade.translation.persistence.artifact.AtomicChapterDocuments
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactLayout
import eu.kanade.translation.persistence.artifact.FakeChapterDocumentIo
import eu.kanade.translation.persistence.artifact.loadArtifact
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.pipeline.batch.ChunkCompletionOutcome
import eu.kanade.translation.pipeline.execution.PreparedPage
import eu.kanade.translation.pipeline.execution.TranslationExecutor
import eu.kanade.translation.pipeline.execution.TranslationStageEvent
import eu.kanade.translation.pipeline.execution.TranslationStageListener
import eu.kanade.translation.pipeline.toPrecondition
import eu.kanade.translation.scheduling.AutoChapterIdentity
import eu.kanade.translation.scheduling.RollingAutoCoordinator
import eu.kanade.translation.scheduling.TranslationSession
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.pools.BitmapPool
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 *  Drain-not-cancel behavior for auto provider calls.
 *
 * When the reader leaves (auto window shutdown/cancel), an auto provider call
 * already in flight must DRAIN to completion inside a bounded grace window —
 * translate + commit run under NonCancellable — instead of being torn down
 * mid-call (which strands the page and, per  leaves an unresolved attempt
 * ledger entry). Grace expiry must cancel the call cleanly and leave the
 * entry unresolved.
 *
 * Before this behavior was implemented, `drainGraceMs` was not
 * configurable and the consumer cancels in-flight work, so the drain variant's
 * commit never lands and the grace variant cannot even be constructed. Every
 * failure names its defect — never a timeout: bounded waits convert to
 * assertions, and the grace bridge raises a named assertion when the seam is
 * missing.
 *
 * Fixture: the REAL [RollingAutoCoordinator] over a minimal executor fake, with
 * the REAL [ChapterTranslationStore] in artifact authority ( fresh-chapter
 * recipe) so the ledger sidecar is durable and observable. The scheduler-level
 * routing into this coordinator is covered by the existing scheduler tests;
 * the coordinator boundary is the unit that owns the §2.3 drain seam.
 */
class AutoProviderCallDrainsNotCancelsTest {

    companion object {
        private const val AWAIT_TIMEOUT_MS = 10_000L
        private const val LEDGER_FILE = "D6 Drain Chapter_artifacts/attempts/ledger.json"
    }

    // Shared "disk" across the two variants.
    private val io = FakeChapterDocumentIo()
    private var scope: CoroutineScope? = null

    @AfterEach
    fun tearDown() {
        scope?.cancel()
        scope = null
        runCatching { BitmapPool.releaseAll() }
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** Production fresh-chapter recipe over the shared IO ( test precedent). */
    private fun freshStore(pageKeys: List<String>): ChapterTranslationStore {
        val artifactStore = ChapterArtifactEngine(
            AtomicChapterDocuments(io),
            ChapterArtifactLayout("D6 Drain Chapter"),
        )
        val manifest = artifactStore
            .loadArtifact(ArtifactSeed(migratedAtEpochMs = 1L))
            .manifest
        return ChapterTranslationStore(
            translationFile = null as com.hippo.unifile.UniFile?,
            fileCreator = null,
            initialPages = pageKeys.associateWith { key -> PageTranslation(sourceFileName = key) },
            artifactStore = artifactStore,
            initialArtifactManifest = manifest,
        )
    }

    private fun fakeSession(store: ChapterTranslationStore): TranslationSession {
        val manga = mockk<Manga>(relaxed = true)
        every { manga.id } returns 1L
        val chapter = mockk<Chapter>(relaxed = true)
        every { chapter.id } returns 1L
        val source = mockk<HttpSource>(relaxed = true)
        every { source.id } returns 1L
        return TranslationSession("drain-session", manga, chapter, source, store)
    }

    private fun resolver(): (Int) -> RollingAutoCoordinator.PageWorkItem? = { idx ->
        RollingAutoCoordinator.PageWorkItem("p$idx", null)
    }

    private fun newCoordinatorUnconfined(
        executor: TranslationExecutor,
        memoryGate: () -> Boolean = { true },
    ): RollingAutoCoordinator {
        val injected = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scope = injected
        return RollingAutoCoordinator(
            executor = executor,
            computeClass = TranslatorComputeClass.REMOTE_IO,
            memoryGate = memoryGate,
            injectedScope = injected,
        )
    }

    // ------------------------------------------------------------------
    // ledger-file observation ( schema mirror)
    // ------------------------------------------------------------------

    @kotlinx.serialization.Serializable
    private data class LedgerMirror(
        val entries: List<kotlinx.serialization.json.JsonObject> = emptyList(),
        val consecutiveUnresolved: Map<String, Int> = emptyMap(),
    )

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun readLedger(): LedgerMirror? =
        io.read(LEDGER_FILE)?.let { bytes -> json.decodeFromString<LedgerMirror>(bytes.decodeToString()) }

    // ------------------------------------------------------------------
    // §2.3 seam bridge — named failure, never a timeout.
    // ------------------------------------------------------------------

    /**
     * Builds a coordinator whose drain grace is injectable. At RED the
     * constructor has no grace parameter, which IS the defect under test, so
     * this raises an assertion naming the missing seam instead of failing to
     * compile against commit 4.
     */
    private fun newGraceBoundedCoordinator(
        executor: TranslationExecutor,
        graceMs: Long,
    ): RollingAutoCoordinator {
        val ctor = RollingAutoCoordinator::class.java.constructors
            .firstOrNull { it.parameterTypes.size == 7 }
            ?: throw AssertionError(
                "T917 D6 RED defect: drainGraceMs is not configurable — the §2.3 bounded " +
                    "drain-not-cancel seam is missing from RollingAutoCoordinator",
            )
        val injected = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scope = injected
        return ctor.newInstance(
            executor,
            TranslatorComputeClass.REMOTE_IO,
            { true },
            injected,
            emptyList<RollingAutoCoordinator>(),
            0L,
            graceMs,
        ) as RollingAutoCoordinator
    }

    /**
     * Pins the production grace bound.  Phase 4 ( §1.6) CONTRACT CHANGE
     * (recorded in the phase-4 implementation log): the bound moved from the
     * pinned 90 s to exactly the drained call chain's own legitimate budget —
     * [eu.kanade.translation.pipeline.TranslationPipeline.ONNX_PHASE_TIMEOUT_MS] +
     * [eu.kanade.translation.pipeline.TranslationPipeline.SINGLE_PAGE_TIMEOUT_MS] (ONNX 90 s +
     * HTTP/render 120 s, sequential = 210 s) — so a healthy long call is never
     * cut cancellation-class mid-chain.
     */
    @Test
    fun `drain grace companion bound matches the attach chain budget`() {
        val field = runCatching {
            RollingAutoCoordinator::class.java.getField("PROVIDER_DRAIN_GRACE_MS")
        }.getOrNull()
        if (field == null) {
            throw AssertionError(
                "T917 D6 RED defect: PROVIDER_DRAIN_GRACE_MS is missing — the §2.3 drain bound " +
                    "companion constant does not exist",
            )
        }
        (field.get(null) as Long) shouldBe (
            eu.kanade.translation.pipeline.TranslationPipeline.ONNX_PHASE_TIMEOUT_MS +
                eu.kanade.translation.pipeline.TranslationPipeline.SINGLE_PAGE_TIMEOUT_MS
            )
    }

    // ------------------------------------------------------------------
    // 1. THE drain: a cancelled window lets the in-flight call FINISH
    // ------------------------------------------------------------------

    @Test
    fun `cancelled auto window drains the in flight call and commits instead of cancelling`() = runBlocking<Unit> {
        val store = freshStore(listOf("p0"))
        val executor = DrainExecutor(store)
        val coordinator = newCoordinatorUnconfined(executor)

        coordinator.updateWindow(
            AutoChapterIdentity(chapterId = 1L, sessionKey = "drain"),
            visiblePageIndex = 0,
            configuredAheadTarget = 0,
            pageCount = 1,
            session = fakeSession(store),
            pageResolver = resolver(),
        )
        // The paid call is parked mid-flight (the  PROVIDER_START analogue).
        withTimeout(AWAIT_TIMEOUT_MS) { executor.awaitTranslateStarted("p0") }

        // Reader leaves: the real teardown path cancels the coordination job.
        coordinator.cancel()

        // §2.3: the call must DRAIN — releasing the gate lets translate + commit
        // run to completion under NonCancellable even though the window is gone.
        executor.releaseTranslate("p0")
        try {
            withTimeout(AWAIT_TIMEOUT_MS) { coordinator.awaitTermination() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError(
                "T917 D6 §2.3 defect: the translate consumer never finished after the drain " +
                    "release — the in-flight call was cancelled instead of drained",
                e,
            )
        }

        withClue(
            "T917 D6 §2.3 defect: the drained call must commit its translation-terminal state " +
                "even though the window is gone — RED strands the page because cancel() tears " +
                "the call down mid-flight",
        ) {
            val page = store.state.value.getValue("p0")
            page.translationStatus shouldBe StageStatus.READY
            page.ocrStatus shouldBe StageStatus.READY
            page.inpaintStatus shouldBe StageStatus.READY
        }
        withClue(
            "T917 D6 §2.3 defect: a drained COMPLETED call must consume its D9 attempt entry — " +
                "RED leaves it unresolved, so the next startup would count a false crash",
        ) {
            readLedger()?.entries.orEmpty() shouldBe emptyList()
        }
        withClue("T917 D6 §2.3: exactly one paid call — the drain must not re-translate") {
            executor.translateCallsFor("p0") shouldBe 1
            executor.cancelledCalls.get() shouldBe 0
        }
    }

    // ------------------------------------------------------------------
    // 2. grace expiry: a call that cannot finish cancels cleanly
    // ------------------------------------------------------------------

    @Test
    fun `grace expiry cancels the parked call and leaves the ledger entry unresolved`() = runBlocking<Unit> {
        val store = freshStore(listOf("p0"))
        val executor = DrainExecutor(store)
        val coordinator = newGraceBoundedCoordinator(executor, graceMs = 300L)

        coordinator.updateWindow(
            AutoChapterIdentity(chapterId = 1L, sessionKey = "drain-grace"),
            visiblePageIndex = 0,
            configuredAheadTarget = 0,
            pageCount = 1,
            session = fakeSession(store),
            pageResolver = resolver(),
        )
        withTimeout(AWAIT_TIMEOUT_MS) { executor.awaitTranslateStarted("p0") }

        // Reader leaves; the gate is NEVER released — the grace must expire.
        coordinator.cancel()

        // Event-driven: the expiry surfaces as a CancellationException inside
        // the parked call (inner timeout), not as a scheduler-level kill.
        withClue(
            "T917 D6 §2.3 defect: the parked call was never cancelled by the drain grace — " +
                "the §2.3 inner bound is not applied to the drained work",
        ) {
            withTimeout(AWAIT_TIMEOUT_MS) { executor.cancelledSignal.await() }
        }
        withTimeout(AWAIT_TIMEOUT_MS) { coordinator.awaitTermination() }

        withClue(
            "T917 D6 §2.3 defect: grace expiry must NOT commit — the page stays un-READY",
        ) {
            store.state.value.getValue("p0").renderStatus shouldBe StageStatus.PENDING
        }
        withClue(
            "T917 D6 §2.3 defect: grace expiry is cancellation-class — the D9 attempt entry " +
                "must stay unresolved (exactly one entry), not consumed and not doubled",
        ) {
            val ledger = readLedger()
            if (ledger == null) {
                throw AssertionError(
                    "T917 D6 §2.3 defect: the attempt ledger sidecar was never written",
                )
            }
            ledger.entries.size shouldBe 1
        }
        withClue("T917 D6 §2.3: the expiry must not re-issue the call") {
            executor.translateCallsFor("p0") shouldBe 1
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
        val cancelledSignal = CompletableDeferred<Unit>()

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

        override suspend fun prepareSinglePage(
            manga: Manga,
            chapter: Chapter,
            source: HttpSource,
            pageKey: String,
            streamFn: (() -> InputStream)?,
            force: Boolean,
            stageListener: TranslationStageListener?,
        ): PreparedPage = PreparedPage(
            pageKey = pageKey,
            chapterId = chapter.id,
            mangaId = manga.id,
            sourceId = source.id,
            cleanedImageName = "cleaned.jpg",
            generation = 0L,
            pageVersion = 0L,
            blockFingerprints = emptyList(),
            isTerminal = false,
        )

        override suspend fun translatePreparedPage(
            manga: Manga,
            chapter: Chapter,
            source: HttpSource,
            prepared: PreparedPage,
            stageListener: TranslationStageListener?,
        ): ChunkCompletionOutcome? {
            translateCalls.getOrPut(prepared.pageKey) { AtomicInteger(0) }.incrementAndGet()
            stageListener?.onStageEntered(prepared.pageKey, TranslationStageEvent.TRANSLATING)
            gate(translateStarted, prepared.pageKey).complete(Unit)
            try {
                gate(translateGate, prepared.pageKey).await()
            } catch (e: CancellationException) {
                cancelledCalls.incrementAndGet()
                cancelledSignal.complete(Unit)
                throw e
            }
            // The real pipeline's terminal commit (translation-terminal truth) —
            // the same guarded write the manual publish shim performs: a
            // manifest-free precondition so the dependency-fingerprint clause
            // cannot reject the fake's page-derived snapshot fingerprint.
            val result = store.updatePageGuarded(
                prepared.pageKey,
                store.snapshot(prepared.pageKey).toPrecondition().copy(dependencyFingerprint = null),
                "D6 drain commit",
            ) { current ->
                displayReady(prepared.pageKey, current)
            }
            check(result is ChapterTranslationStore.PatchResult.Accepted) {
                "D6 drain commit rejected: ${(result as? ChapterTranslationStore.PatchResult.Rejected)?.reason}"
            }
            return ChunkCompletionOutcome.Completed(setOf(prepared.pageKey))
        }

        override suspend fun translateSinglePage(
            manga: Manga,
            chapter: Chapter,
            source: HttpSource,
            pageKey: String,
            force: Boolean,
            stageListener: TranslationStageListener?,
            origin: eu.kanade.translation.persistence.chapter.PageWriteOrigin,
        ): eu.kanade.translation.pipeline.execution.SinglePageOutcome =
            eu.kanade.translation.pipeline.execution.SinglePageOutcome.Completed

        private fun displayReady(pageKey: String, current: PageTranslation?): PageTranslation =
            (current ?: PageTranslation(sourceFileName = pageKey)).apply {
                // The paid call's terminal commit is the translate+persist
                // stage, so the fake commits the same translation-terminal
                // shape the harness's manual publish shim does (harness note
                // §1.2.3): promoting renderStatus to READY additionally needs
                // a decodable cleaned base file, which a JVM fixture cannot
                // produce (documented fixture deviation,  harness notes).
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
