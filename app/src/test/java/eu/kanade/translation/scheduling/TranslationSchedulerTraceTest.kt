package eu.kanade.translation.scheduling

import eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics
import eu.kanade.translation.diagnostics.TranslationTraceIdGenerator
import eu.kanade.translation.diagnostics.TranslationTraceSink
import eu.kanade.translation.LeaseAcquisition
import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.pipeline.batch.ChunkCompletionOutcome
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * T922 Phase 3 (plan §6.3 case 1 + amendment §10.2): correlated trace wiring
 * of the MANUAL scheduler path — exactly one schedule + one run per
 * [TranslationScheduler.translatePage] intent, the measured lease_wait
 * scheduler queue, and exactly-one-terminal ownership for success, mid-flight
 * cancellation, and cancel-before-dispatch (scope close racing the launch).
 */
class TranslationSchedulerTraceTest {

    private val capturedLines = mutableListOf<String>()
    private var oldSink: TranslationTraceSink? = null
    private var oldGate: Boolean? = null
    private var oldIds: TranslationTraceIdGenerator? = null
    private var oldKeys: eu.kanade.translation.diagnostics.TranslationIdentityKeys? = null

    @AfterEach
    fun tearDown() {
        restoreCapture()
    }

    private fun setUpTraceCapture(detailed: Boolean) {
        oldSink = TranslationPipelineDiagnostics.sink
        oldGate = TranslationPipelineDiagnostics.detailedTracingEnabled
        oldIds = TranslationPipelineDiagnostics.idGenerator
        oldKeys = TranslationPipelineDiagnostics.identityKeys
        capturedLines.clear()
        TranslationPipelineDiagnostics.sink = TranslationTraceSink { _, line -> capturedLines.add(line) }
        TranslationPipelineDiagnostics.detailedTracingEnabled = detailed
        TranslationPipelineDiagnostics.idGenerator = TranslationTraceIdGenerator(processPrefix = "t922m")
        TranslationPipelineDiagnostics.identityKeys = eu.kanade.translation.diagnostics.TranslationIdentityKeys(
            ByteArray(32) { 9 },
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

    private fun mockSession(chapterId: Long = 7L): Triple<
        tachiyomi.domain.manga.model.Manga,
        tachiyomi.domain.chapter.model.Chapter,
        eu.kanade.tachiyomi.source.online.HttpSource,
        > {
        val manga = mockk<tachiyomi.domain.manga.model.Manga>(relaxed = true)
        every { manga.id } returns chapterId
        val chapter = mockk<tachiyomi.domain.chapter.model.Chapter>(relaxed = true)
        every { chapter.id } returns chapterId
        val source = mockk<eu.kanade.tachiyomi.source.online.HttpSource>(relaxed = true)
        every { source.id } returns chapterId
        return Triple(manga, chapter, source)
    }

    private suspend fun awaitCondition(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(10)
        }
    }

    /**
     * Case 1: a manual intent produces exactly one schedule and one correlated
     * run, with the lease_wait scheduler queue measured and a success terminal.
     */
    @Test
    @Timeout(60)
    fun `manual translate emits one schedule and one run with lease wait and success terminal`() = runBlocking<Unit> {
        setUpTraceCapture(detailed = true)
        val executor = ManualFakeExecutor()
        val scheduler = TranslationScheduler(executor, { null })
        try {
            val (manga, chapter, source) = mockSession()
            scheduler.translatePage(manga, chapter, source, "p0")
            executor.awaitTranslateStarted()
            executor.releaseTranslate.complete(Unit)
            awaitCondition { scheduler.manualOutcomeFor(7L, "p0") != null }
        } finally {
            scheduler.close()
        }

        val scheduleStarts = lines("schedule_start")
        scheduleStarts.size shouldBe 1
        field(scheduleStarts.single(), "mode") shouldBe "manual"

        val runStarts = lines("run_start")
        runStarts.size shouldBe 1
        field(runStarts.single(), "sid") shouldBe field(scheduleStarts.single(), "sid")

        // The request→coroutine-start scheduler queue was measured.
        lines("stage_start").count { field(it, "stage") == "lease_wait" } shouldBe 1
        val leaseWaitEnds = lines("stage_end").filter { field(it, "stage") == "lease_wait" }
        leaseWaitEnds.size shouldBe 1
        field(leaseWaitEnds.single(), "rid") shouldBe field(runStarts.single(), "rid")

        val runEnds = lines("run_end")
        runEnds.size shouldBe 1
        field(runEnds.single(), "outcome") shouldBe "success"
        field(runEnds.single(), "sid") shouldBe field(scheduleStarts.single(), "sid")
        field(runEnds.single(), "totalMs")!!.toLong() shouldBe (field(runEnds.single(), "totalMs")!!.toLong().coerceAtLeast(0L))

        lines("schedule_end").size shouldBe 1
        field(lines("schedule_end").single(), "outcome") shouldBe "success"
    }

    /**
     * Mid-flight cancellation: the terminal is cancelled, emitted exactly once
     * by the invoke-on-completion owner outside the coroutine.
     */
    @Test
    @Timeout(60)
    fun `cancelled manual intent closes exactly once as cancelled`() = runBlocking<Unit> {
        setUpTraceCapture(detailed = true)
        val executor = ManualFakeExecutor(throwCancellation = true)
        val scheduler = TranslationScheduler(executor, { null })
        try {
            val (manga, chapter, source) = mockSession()
            scheduler.translatePage(manga, chapter, source, "p0")
            executor.awaitTranslateStarted()
            awaitCondition { lines("run_end").isNotEmpty() }
        } finally {
            scheduler.close()
        }

        val runEnds = lines("run_end")
        runEnds.size shouldBe 1
        field(runEnds.single(), "outcome") shouldBe "cancelled"
        lines("run_start").size shouldBe 1
        lines("schedule_end").size shouldBe 1
        field(lines("schedule_end").single(), "outcome") shouldBe "cancelled"
    }

    /**
     * Cancel-before-dispatch coverage: closing the scheduler immediately after
     * the request still yields exactly one terminal (the invoke-on-completion
     * handler runs even when the coroutine body never starts).
     */
    @Test
    @Timeout(60)
    fun `cancel before dispatch still closes the run exactly once`() = runBlocking<Unit> {
        setUpTraceCapture(detailed = true)
        val executor = ManualFakeExecutor()
        val scheduler = TranslationScheduler(executor, { null })
        val (manga, chapter, source) = mockSession()
        scheduler.translatePage(manga, chapter, source, "p0")
        scheduler.close()
        awaitCondition { lines("run_end").isNotEmpty() }

        val runEnds = lines("run_end")
        runEnds.size shouldBe 1
        field(runEnds.single(), "outcome") shouldBe "cancelled"
        lines("run_start").size shouldBe 1
        lines("schedule_end").size shouldBe 1
        field(lines("schedule_end").single(), "outcome") shouldBe "cancelled"
    }
}

/** Minimal manual-path executor: parks the single-page call on a gate. */
private class ManualFakeExecutor(
    private val throwCancellation: Boolean = false,
) : TranslationExecutor {

    private val translateStartedDeferred = CompletableDeferred<Unit>()
    val releaseTranslate = CompletableDeferred<Unit>()
    val translateCount = AtomicInteger(0)

    suspend fun awaitTranslateStarted() {
        translateStartedDeferred.await()
    }

    override suspend fun translateSinglePage(
        manga: tachiyomi.domain.manga.model.Manga,
        chapter: tachiyomi.domain.chapter.model.Chapter,
        source: eu.kanade.tachiyomi.source.online.HttpSource,
        pageKey: String,
        force: Boolean,
        stageListener: TranslationStageListener?,
        origin: PageWriteOrigin,
    ): SinglePageOutcome {
        translateCount.incrementAndGet()
        translateStartedDeferred.complete(Unit)
        if (throwCancellation) {
            throw CancellationException("cancelled by test")
        }
        releaseTranslate.await()
        return SinglePageOutcome.Completed
    }

    override suspend fun prepareSinglePage(
        manga: tachiyomi.domain.manga.model.Manga,
        chapter: tachiyomi.domain.chapter.model.Chapter,
        source: eu.kanade.tachiyomi.source.online.HttpSource,
        pageKey: String,
        streamFn: (() -> InputStream)?,
        force: Boolean,
        stageListener: TranslationStageListener?,
    ): PreparedPage? = null

    override suspend fun translatePreparedPage(
        manga: tachiyomi.domain.manga.model.Manga,
        chapter: tachiyomi.domain.chapter.model.Chapter,
        source: eu.kanade.tachiyomi.source.online.HttpSource,
        prepared: PreparedPage,
        stageListener: TranslationStageListener?,
    ): ChunkCompletionOutcome? = null

    override suspend fun translateSinglePageFromStream(
        manga: tachiyomi.domain.manga.model.Manga,
        chapter: tachiyomi.domain.chapter.model.Chapter,
        source: eu.kanade.tachiyomi.source.online.HttpSource,
        pageKey: String,
        streamFn: () -> InputStream,
        force: Boolean,
        stageListener: TranslationStageListener?,
    ) {}
}
