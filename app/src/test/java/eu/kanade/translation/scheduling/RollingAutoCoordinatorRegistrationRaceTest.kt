package eu.kanade.translation.scheduling

import eu.kanade.translation.diagnostics.TranslationIdentityKeys
import eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics
import eu.kanade.translation.diagnostics.TranslationTraceIdGenerator
import eu.kanade.translation.diagnostics.TranslationTraceSink
import eu.kanade.translation.engines.translator.TranslatorComputeClass
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.orchestration.TranslationSession
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.pipeline.batch.ChunkCompletionOutcome
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 *  Phase 4 regression for Phase 3 review finding F1: the page-run trace
 * joins the terminal-sweep registry and the generation liveness re-check
 * ATOMICALLY under the lifecycle lock. The test forces the exact race window
 * deterministically: the trace sink fires synchronously inside
 * [TranslationPipelineDiagnostics.startRun], and the hook performs a chapter
 * identity change (which runs the cancel sweep under the lifecycle lock) at
 * that moment — BETWEEN startRun and the registration. The run must then be
 * closed locally (exactly one cancelled terminal) instead of leaking in the
 * sweep registry unclosed.
 */
class RollingAutoCoordinatorRegistrationRaceTest {

    private val identity = AutoChapterIdentity(chapterId = 42L, sessionKey = "gen1")

    private var testScope: CoroutineScope? = null

    private val capturedLines = mutableListOf<String>()
    private var oldSink: TranslationTraceSink? = null
    private var oldGate: Boolean? = null
    private var oldIds: TranslationTraceIdGenerator? = null
    private var oldKeys: TranslationIdentityKeys? = null

    @AfterEach
    fun tearDown() {
        restoreCapture()
        testScope?.cancel()
        testScope = null
    }

    private fun setUpTraceCapture() {
        oldSink = TranslationPipelineDiagnostics.sink
        oldGate = TranslationPipelineDiagnostics.detailedTracingEnabled
        oldIds = TranslationPipelineDiagnostics.idGenerator
        oldKeys = TranslationPipelineDiagnostics.identityKeys
        capturedLines.clear()
        TranslationPipelineDiagnostics.sink = TranslationTraceSink { _, line -> capturedLines.add(line) }
        TranslationPipelineDiagnostics.detailedTracingEnabled = true
        TranslationPipelineDiagnostics.idGenerator = TranslationTraceIdGenerator(processPrefix = "t922f")
        TranslationPipelineDiagnostics.identityKeys = TranslationIdentityKeys(ByteArray(32) { 13 })
    }

    private fun restoreCapture() {
        oldSink?.let { TranslationPipelineDiagnostics.sink = it }
        oldGate?.let { TranslationPipelineDiagnostics.detailedTracingEnabled = it }
        oldIds?.let { TranslationPipelineDiagnostics.idGenerator = it }
        oldKeys?.let { TranslationPipelineDiagnostics.identityKeys = it }
        oldSink = null
        oldGate = null
        oldIds = null
        oldKeys = null
    }

    private fun lines(event: String): List<String> = capturedLines.filter { it.contains("event=$event ") }

    private fun field(line: String, key: String): String? =
        line.split(" ").firstOrNull { it.startsWith("$key=") }?.removePrefix("$key=")

    private fun newStore(pages: List<Pair<String, PageTranslation>> = emptyList()): ChapterTranslationStore =
        ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = pages.associate { it.first to it.second },
        )

    private fun blank() = PageTranslation(sourceFileName = "")

    private fun fakeSession(
        store: ChapterTranslationStore,
        chapterId: Long,
        sessionKey: String,
    ): TranslationSession {
        val manga = mockk<tachiyomi.domain.manga.model.Manga>(relaxed = true)
        every { manga.id } returns chapterId
        val chapter = mockk<tachiyomi.domain.chapter.model.Chapter>(relaxed = true)
        every { chapter.id } returns chapterId
        val source = mockk<eu.kanade.tachiyomi.source.online.HttpSource>(relaxed = true)
        every { source.id } returns chapterId
        return TranslationSession(sessionKey, manga, chapter, source, store)
    }

    private fun newCoordinator(
        executor: TranslationExecutor,
        computeClass: TranslatorComputeClass = TranslatorComputeClass.REMOTE_IO,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): RollingAutoCoordinator {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        testScope = scope
        return RollingAutoCoordinator(
            executor = executor,
            computeClass = computeClass,
            memoryGate = { true },
            injectedScope = scope,
        )
    }

    /**
     * F1 regression: an identity change landing inside startRun (between the
     * run's creation and its sweep-registry registration) must not leak the
     * run — exactly one cancelled terminal is emitted for it.
     */
    @Test
    @Timeout(60)
    fun `identity change inside startRun still closes the admitted run exactly once`() = runBlocking<Unit> {
        setUpTraceCapture()
        val identityChangeFired = AtomicBoolean(false)
        lateinit var coordinator: RollingAutoCoordinator
        val store = newStore(listOf("p0" to blank()))

        // The sink hook: when the FIRST page run starts, synchronously replace
        // the window identity. updateWindow runs the cancel sweep under the
        // lifecycle lock while startRun has NOT yet registered the run — the
        // exact F1 interleaving, made deterministic.
        TranslationPipelineDiagnostics.sink = TranslationTraceSink { _, line ->
            synchronized(capturedLines) { capturedLines.add(line) }
            if (line.contains("event=run_start ") && identityChangeFired.compareAndSet(false, true)) {
                val session2 = fakeSession(store, chapterId = 43L, sessionKey = "gen2")
                coordinator.updateWindow(
                    identity.copy(chapterId = 43L, sessionKey = "gen2"),
                    visiblePageIndex = 0,
                    configuredAheadTarget = 0,
                    pageCount = 1,
                    session = session2,
                    pageResolver = { null },
                )
            }
        }

        val executor = ParkingExecutor()
        coordinator = newCoordinator(executor)
        val session1 = fakeSession(store, chapterId = 42L, sessionKey = "gen1")
        coordinator.updateWindow(identity, 0, 0, 1, session1, { idx ->
            RollingAutoCoordinator.PageWorkItem("p$idx", null)
        })

        withTimeout(20_000) {
            coordinator.cancel()
            coordinator.awaitTermination()
        }

        // The page run started exactly once and was closed exactly once.
        val runStarts = lines("run_start")
        runStarts.size shouldBe 1
        val rid = field(runStarts.single(), "rid")!!
        val gen1Ends = lines("run_end").filter { field(it, "rid") == rid }
        gen1Ends.size shouldBe 1
        field(gen1Ends.single(), "outcome") shouldBe "cancelled"
        // The replaced (gen1) schedule closed via the identity-change sweep;
        // the replacement (gen2) schedule closed via the final cancel.
        val scheduleEnds = lines("schedule_end")
        scheduleEnds.size shouldBe 2
        scheduleEnds.any { field(it, "outcome") == "coordinator_replaced" } shouldBe true
        // gen2 carried no failed runs, so its cancel sweep reports the honest
        // success terminal (sweepTracesLocked lifecycle heuristic).
        scheduleEnds.any { field(it, "outcome") == "success" } shouldBe true
    }
}

/**
 * Executor whose native work never starts: the F1 path returns between run
 * admission and the prepare call, so a parking prepare proves the pass never
 * reached the lane.
 */
private class ParkingExecutor : TranslationExecutor {
    private val parked = CompletableDeferred<Unit>()

    override suspend fun prepareSinglePage(
        manga: tachiyomi.domain.manga.model.Manga,
        chapter: tachiyomi.domain.chapter.model.Chapter,
        source: eu.kanade.tachiyomi.source.online.HttpSource,
        pageKey: String,
        streamFn: (() -> InputStream)?,
        force: Boolean,
        stageListener: TranslationStageListener?,
    ): PreparedPage? {
        parked.await()
        return null
    }

    override suspend fun translatePreparedPage(
        manga: tachiyomi.domain.manga.model.Manga,
        chapter: tachiyomi.domain.chapter.model.Chapter,
        source: eu.kanade.tachiyomi.source.online.HttpSource,
        prepared: PreparedPage,
        stageListener: TranslationStageListener?,
    ): ChunkCompletionOutcome? = null

    override suspend fun translateSinglePage(
        manga: tachiyomi.domain.manga.model.Manga,
        chapter: tachiyomi.domain.chapter.model.Chapter,
        source: eu.kanade.tachiyomi.source.online.HttpSource,
        pageKey: String,
        force: Boolean,
        stageListener: TranslationStageListener?,
        origin: eu.kanade.translation.pipeline.PageWriteOrigin,
    ): SinglePageOutcome = SinglePageOutcome.Completed
}
