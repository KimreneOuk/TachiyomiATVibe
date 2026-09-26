package eu.kanade.translation.scheduling

import eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics
import eu.kanade.translation.diagnostics.TranslationTraceSink
import eu.kanade.translation.engines.translator.TranslatorComputeClass
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.LeaseAcquisition
import eu.kanade.translation.persistence.chapter.PageWriteOrigin
import eu.kanade.translation.pipeline.batch.ChunkCompletionOutcome
import eu.kanade.translation.pipeline.execution.PreparedPage
import eu.kanade.translation.pipeline.execution.SinglePageOutcome
import eu.kanade.translation.pipeline.execution.TranslationExecutor
import eu.kanade.translation.pipeline.execution.TranslationStageListener
import eu.kanade.translation.util.TranslationMemoryBudget
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 *  Phase 3 (plan §6.3 cases 2, 5, 6 + amendment §10.2): correlated trace
 * wiring of [RollingAutoCoordinator] — one schedule per rolling session,
 * correlated page runs with distinct rids, measured prepared-queue waits,
 * exactly-one-terminal ownership under cancel/timeout/eviction, and the
 * diagnostics-off parity invariant (no scheduling behavior change).
 *
 * The executor fixture is the same ControllableExecutor pattern as
 * [RollingAutoCoordinatorTest] (copied — the original is private), extended
 * with a typed persistence-rejection fault so the eviction discriminator can
 * be exercised deterministically.
 */
class RollingAutoCoordinatorTraceTest {

    private val identity = AutoChapterIdentity(chapterId = 42L, sessionKey = "trace-session")

    private var testScope: CoroutineScope? = null

    // Captured trace lines for the current test; swapped in setUpTraceCapture.
    private val capturedLines = mutableListOf<String>()
    private var oldSink: TranslationTraceSink? = null
    private var oldGate: Boolean? = null
    private var oldIds: eu.kanade.translation.diagnostics.TranslationTraceIdGenerator? = null
    private var oldKeys: eu.kanade.translation.diagnostics.TranslationIdentityKeys? = null

    @AfterEach
    fun tearDown() {
        restoreCapture()
        testScope?.cancel()
        testScope = null
    }

    private fun setUpTraceCapture(detailed: Boolean) {
        oldSink = TranslationPipelineDiagnostics.sink
        oldGate = TranslationPipelineDiagnostics.detailedTracingEnabled
        oldIds = TranslationPipelineDiagnostics.idGenerator
        oldKeys = TranslationPipelineDiagnostics.identityKeys
        capturedLines.clear()
        TranslationPipelineDiagnostics.sink = TranslationTraceSink { _, line -> capturedLines.add(line) }
        TranslationPipelineDiagnostics.detailedTracingEnabled = detailed
        TranslationPipelineDiagnostics.idGenerator =
            eu.kanade.translation.diagnostics.TranslationTraceIdGenerator(processPrefix = "t922")
        TranslationPipelineDiagnostics.identityKeys = eu.kanade.translation.diagnostics.TranslationIdentityKeys(
            ByteArray(32) { 7 },
        )
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
        chapterId: Long = 42L,
        sessionKey: String = "trace-session",
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
        executor: TraceControllableExecutor,
        computeClass: TranslatorComputeClass,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        drainGraceMs: Long = RollingAutoCoordinator.PROVIDER_DRAIN_GRACE_MS,
    ): RollingAutoCoordinator {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        testScope = scope
        return RollingAutoCoordinator(
            executor = executor,
            computeClass = computeClass,
            memoryGate = { true },
            injectedScope = scope,
            drainGraceMs = drainGraceMs,
        )
    }

    private fun resolver(prefix: String = "p"): (Int) -> RollingAutoCoordinator.PageWorkItem? = { idx ->
        RollingAutoCoordinator.PageWorkItem("$prefix$idx", null)
    }

    /**
     * Case 2: provider translation of page A overlaps native preparation of
     * page B; one schedule, two correlated runs with distinct rids, measured
     * prepared/native queue stages, and exactly one success terminal each.
     */
    @Test
    @Timeout(60)
    fun `rolling auto overlap emits one schedule with correlated runs and queue stages`() = runBlocking<Unit> {
        setUpTraceCapture(detailed = true)
        val executor = TraceControllableExecutor(autoComplete = false)
        val store = newStore(listOf("p0" to blank(), "p1" to blank()))
        val coordinator = newCoordinator(executor, TranslatorComputeClass.REMOTE_IO)
        val session = fakeSession(store)
        coordinator.updateWindow(identity, 0, 1, 2, session, resolver())

        executor.awaitAndCompletePrepare(0)
        executor.awaitTranslateStarted(0)
        executor.awaitPrepareStarted(1)
        executor.maxConcurrent.get() shouldBe 2

        // The consumer is a single lane: page 1's translate can only start
        // after page 0's translate completes (work1 is buffered meanwhile).
        executor.completeTranslate(0)
        executor.awaitAndCompletePrepare(1)
        executor.awaitTranslateStarted(1)
        executor.completeTranslate(1)

        coordinator.shutdown()
        coordinator.awaitTermination()

        lines("schedule_start").size shouldBe 1
        lines("schedule_end").size shouldBe 1
        field(lines("schedule_end").single(), "outcome") shouldBe "success"
        field(lines("schedule_end").single(), "mode") shouldBe "auto"

        val runStarts = lines("run_start")
        runStarts.size shouldBe 2
        runStarts.map { field(it, "rid") }.toSet().size shouldBe 2
        runStarts.map { field(it, "sid") }.toSet().size shouldBe 1
        field(runStarts[0], "mode") shouldBe "auto"
        field(runStarts[0], "pageIndex") shouldBe "0"
        field(runStarts[1], "pageIndex") shouldBe "1"

        val runEnds = lines("run_end")
        runEnds.size shouldBe 2
        runEnds.forEach { end ->
            field(end, "outcome") shouldBe "success"
            // Exactly one terminal per started run.
            runStarts.count { field(it, "rid") == field(end, "rid") } shouldBe 1
            // A correlated wall total is present.
            field(end, "totalMs")!!.toLong() shouldBe (field(end, "totalMs")!!.toLong().coerceAtLeast(0L))
        }

        // Queue stages measured and correlated to a run. (native_queue is a
        // TranslationPipeline-level stage and is not exercised by this
        // coordinator fixture — the fake executor replaces the pipeline.)
        val preparedQueueEnds = lines("stage_end").filter { field(it, "stage") == "prepared_queue" }
        preparedQueueEnds.size shouldBe 2
        preparedQueueEnds.forEach { line ->
            field(line, "rid")?.startsWith("t922r") shouldBe true
            field(line, "queueMs")!!.toLong() shouldBe (field(line, "queueMs")!!.toLong().coerceAtLeast(0L))
        }
    }

    /**
     * Amendment §10.2 cancel coverage: runs buffered in the channel (and one
     * parked mid-send) all close exactly once as cancelled when the window is
     * cancelled; the drained in-flight call still reports its real outcome.
     */
    @Test
    @Timeout(60)
    fun `cancel closes buffered runs cancelled and drained run with real outcome`() = runBlocking<Unit> {
        setUpTraceCapture(detailed = true)
        val capacity = TranslationMemoryBudget.recommendedPrefetchCapacity()
        val pageCount = capacity + 2
        val executor = TraceControllableExecutor(autoComplete = false)
        val pages = (0 until pageCount).map { "p$it" to blank() }
        val store = newStore(pages)
        val coordinator = newCoordinator(executor, TranslatorComputeClass.REMOTE_IO)
        val session = fakeSession(store)
        // Desired set = visible + ahead prefix: open the window over every
        // page so the reconcile loop prepares and sends all of them.
        coordinator.updateWindow(identity, 0, pageCount - 1, pageCount, session, resolver())

        // Page 0 parks the consumer inside translate; pages 1..N fill the
        // channel (the last send parks mid-handoff).
        executor.awaitAndCompletePrepare(0)
        executor.awaitTranslateStarted(0)
        for (i in 1 until pageCount) {
            executor.awaitAndCompletePrepare(i)
        }
        executor.prepareCount.get() shouldBe pageCount

        coordinator.cancel()
        // The drained call finishes after the window is gone: drain-not-cancel.
        executor.completeTranslate(0)
        coordinator.awaitTermination()

        val runStarts = lines("run_start")
        val runEnds = lines("run_end")
        if (runEnds.size != runStarts.size) {
            throw AssertionError(
                "start/end mismatch\n" + capturedLines.joinToString("\n"),
            )
        }
        // Every started run has exactly one terminal.
        runEnds.size shouldBe runStarts.size
        runStarts.forEach { start ->
            runEnds.count { field(it, "rid") == field(start, "rid") } shouldBe 1
        }
        // The drained run reports its real success; every other run closed as
        // cancelled (buffered, swept) or cancelled_during_send (parked handoff).
        runEnds.count { field(it, "outcome") == "success" } shouldBe 1
        val cancelFamily = runEnds.count {
            field(it, "outcome") == "cancelled" || field(it, "outcome") == "cancelled_during_send"
        }
        cancelFamily shouldBe runEnds.size - 1
        // The schedule closed exactly once as cancelled.
        lines("schedule_end").size shouldBe 1
        field(lines("schedule_end").single(), "outcome") shouldBe "cancelled"
    }

    /**
     * Case 5 (trace half): a MANUAL owner stealing the page lease makes the
     * auto run's persistence rejection discriminate as evicted.
     */
    @Test
    @Timeout(60)
    fun `manual lease theft discriminates evicted terminal`() = runBlocking<Unit> {
        setUpTraceCapture(detailed = true)
        val executor = TraceControllableExecutor(autoComplete = false)
        executor.persistenceRejectedFor.add("p0")
        val store = newStore(listOf("p0" to blank()))
        val coordinator = newCoordinator(executor, TranslatorComputeClass.REMOTE_IO)
        val session = fakeSession(store)
        coordinator.updateWindow(identity, 0, 1, 1, session, resolver())

        executor.awaitAndCompletePrepare(0)
        executor.awaitTranslateStarted(0)
        // MANUAL steals the in-flight AUTO lease ( token replacement).
        val acquisition = store.tryAcquirePageStageLease("p0", PageStage.Translation, PageWriteOrigin.MANUAL)
        acquisition.shouldBeInstanceOf<LeaseAcquisition.Granted>()

        executor.completeTranslate(0)
        coordinator.shutdown()
        coordinator.awaitTermination()
        store.releasePageStageLease("p0", PageWriteOrigin.MANUAL)

        val runEnds = lines("run_end")
        runEnds.size shouldBe 1
        field(runEnds.single(), "outcome") shouldBe "evicted"
    }

    /**
     * Drain-grace expiry: the run closes as timeout and rethrows exactly as
     * before (control flow unchanged); the schedule still closes once.
     */
    @Test
    @Timeout(60)
    fun `drain grace expiry closes the run as timeout`() = runBlocking<Unit> {
        setUpTraceCapture(detailed = true)
        val executor = TraceControllableExecutor(autoComplete = false)
        val store = newStore(listOf("p0" to blank()))
        val coordinator = newCoordinator(
            executor,
            TranslatorComputeClass.REMOTE_IO,
            drainGraceMs = 200L,
        )
        val session = fakeSession(store)
        coordinator.updateWindow(identity, 0, 1, 1, session, resolver())

        executor.awaitAndCompletePrepare(0)
        executor.awaitTranslateStarted(0)

        coordinator.cancel()
        // withTimeout(200) fires inside the NonCancellable drain block; the
        // consumer then exits with the typed timeout terminal.
        withTimeout(10_000) { coordinator.awaitTermination() }
        // The parked call must not leak its gate.
        executor.completeTranslate(0)

        val runEnds = lines("run_end")
        runEnds.size shouldBe 1
        field(runEnds.single(), "outcome") shouldBe "timeout"
        lines("schedule_end").size shouldBe 1
    }

    /**
     * Case 6 parity: with the detailed gate off, scheduling behavior and
     * executor invocation counts are IDENTICAL to the enabled run; only
     * detailed events are suppressed while terminal summaries still emit.
     */
    @Test
    @Timeout(60)
    fun `diagnostics off does not change scheduling behavior`() = runBlocking<Unit> {
        val invocationCounts = mutableListOf<Triple<Int, Int, Int>>()
        for (detailed in listOf(false, true)) {
            setUpTraceCapture(detailed = detailed)
            val executor = TraceControllableExecutor(autoComplete = false)
            val store = newStore(listOf("p0" to blank(), "p1" to blank()))
            val coordinator = newCoordinator(executor, TranslatorComputeClass.REMOTE_IO)
            val session = fakeSession(store, sessionKey = "parity-$detailed")
            coordinator.updateWindow(identity.copy(sessionKey = "parity-$detailed"), 0, 1, 2, session, resolver())

            executor.awaitAndCompletePrepare(0)
            executor.awaitTranslateStarted(0)
            executor.completeTranslate(0)
            executor.awaitAndCompletePrepare(1)
            executor.awaitTranslateStarted(1)
            executor.completeTranslate(1)
            coordinator.shutdown()
            coordinator.awaitTermination()

            invocationCounts.add(Triple(executor.prepareCount.get(), executor.translateCount.get(), executor.maxConcurrent.get()))

            if (!detailed) {
                // Detailed families suppressed...
                lines("schedule_start").size shouldBe 0
                lines("run_start").size shouldBe 0
                lines("stage_start").size shouldBe 0
                lines("schedule_state").size shouldBe 0
                lines("stage_end").filter { field(it, "outcome") == "success" }.size shouldBe 0
                // ...while terminal summaries still emit.
                lines("run_end").size shouldBe 2
                lines("schedule_end").size shouldBe 1
            }
        }
        // Identical executor behavior with tracing on vs off.
        invocationCounts[0] shouldBe invocationCounts[1]
    }
}

/**
 * ControllableExecutor extended with a typed persistence-rejection fault and
 * a stale-translate hook, for the trace terminal-ownership tests.
 */
private class TraceControllableExecutor(
    val autoComplete: Boolean,
) : TranslationExecutor {
    val prepareCount = AtomicInteger(0)
    val translateCount = AtomicInteger(0)
    val currentConcurrent = AtomicInteger(0)
    val maxConcurrent = AtomicInteger(0)
    val persistenceRejectedFor = ConcurrentHashMap.newKeySet<String>()

    private fun incConcurrent() {
        val v = currentConcurrent.incrementAndGet()
        maxConcurrent.set(maxOf(maxConcurrent.get(), v))
    }

    private fun decConcurrent() {
        currentConcurrent.decrementAndGet()
    }

    private val prepareStarted = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
    private val prepareGate = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
    private val translateStarted = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
    private val translateGate = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()

    private fun gate(map: ConcurrentHashMap<Int, CompletableDeferred<Unit>>, key: Int) =
        map.getOrPut(key) { CompletableDeferred() }

    override suspend fun prepareSinglePage(
        manga: tachiyomi.domain.manga.model.Manga,
        chapter: tachiyomi.domain.chapter.model.Chapter,
        source: eu.kanade.tachiyomi.source.online.HttpSource,
        pageKey: String,
        streamFn: (() -> InputStream)?,
        force: Boolean,
        stageListener: TranslationStageListener?,
    ): PreparedPage? {
        val callNum = prepareCount.getAndIncrement()
        gate(prepareStarted, callNum).complete(Unit)
        incConcurrent()
        try {
            if (!autoComplete) gate(prepareGate, callNum).await()
        } finally {
            decConcurrent()
        }
        return PreparedPage(
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
    }

    override suspend fun translatePreparedPage(
        manga: tachiyomi.domain.manga.model.Manga,
        chapter: tachiyomi.domain.chapter.model.Chapter,
        source: eu.kanade.tachiyomi.source.online.HttpSource,
        prepared: PreparedPage,
        stageListener: TranslationStageListener?,
    ): ChunkCompletionOutcome? {
        val callNum = translateCount.getAndIncrement()
        gate(translateStarted, callNum).complete(Unit)
        incConcurrent()
        try {
            if (!autoComplete) gate(translateGate, callNum).await()
        } finally {
            decConcurrent()
        }
        if (prepared.pageKey in persistenceRejectedFor) {
            return ChunkCompletionOutcome.PersistenceRejected(
                anchorPageKey = prepared.pageKey,
                stage = eu.kanade.translation.diagnostics.BatchDiagnosticStage.TRANSLATION,
                reason = "lease rejected by manual owner",
            )
        }
        return ChunkCompletionOutcome.Completed(setOf(prepared.pageKey))
    }

    override suspend fun translateSinglePage(
        manga: tachiyomi.domain.manga.model.Manga,
        chapter: tachiyomi.domain.chapter.model.Chapter,
        source: eu.kanade.tachiyomi.source.online.HttpSource,
        pageKey: String,
        force: Boolean,
        stageListener: TranslationStageListener?,
        origin: PageWriteOrigin,
    ): SinglePageOutcome = SinglePageOutcome.Completed

    suspend fun awaitAndCompletePrepare(callIndex: Int) {
        gate(prepareStarted, callIndex).await()
        gate(prepareGate, callIndex).complete(Unit)
    }

    suspend fun awaitPrepareStarted(callIndex: Int) {
        gate(prepareStarted, callIndex).await()
    }

    suspend fun awaitTranslateStarted(callIndex: Int) {
        gate(translateStarted, callIndex).await()
    }

    fun completeTranslate(callIndex: Int) {
        gate(translateGate, callIndex).complete(Unit)
    }
}
