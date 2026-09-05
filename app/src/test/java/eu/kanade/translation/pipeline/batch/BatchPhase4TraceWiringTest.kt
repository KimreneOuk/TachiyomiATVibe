package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.diagnostics.TranslationIdentityKeys
import eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics
import eu.kanade.translation.diagnostics.TranslationTraceIdGenerator
import eu.kanade.translation.diagnostics.TranslationTraceLane
import eu.kanade.translation.diagnostics.TranslationTraceMode
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceProvider
import eu.kanade.translation.diagnostics.TranslationTraceSink
import eu.kanade.translation.diagnostics.TranslationTraceStage
import eu.kanade.translation.inpainting.InpaintingMode
import eu.kanade.translation.model.BatchExpectedFingerprints
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TranslatorComputeClass
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.translation.TranslationPreferences
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * T922 Phase 4 (plan §4.4 batch + §6.3 case 3, §9, §10 amendment checks):
 * batch trace wiring parity — one schedule per batch invocation, one
 * correlated run per page, per-page provider WAIT attribution (never envelope
 * multiplication), terminal schedule summaries with real lane overlap, and
 * teardown-exception exits that still emit exactly one schedule_end.
 */
class BatchPhase4TraceWiringTest {

    private val capturedLines = mutableListOf<String>()
    private var oldSink: TranslationTraceSink? = null
    private var oldGate: Boolean? = null
    private var oldIds: TranslationTraceIdGenerator? = null
    private var oldKeys: TranslationIdentityKeys? = null

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
        TranslationPipelineDiagnostics.idGenerator = TranslationTraceIdGenerator(processPrefix = "t922b")
        TranslationPipelineDiagnostics.identityKeys = TranslationIdentityKeys(ByteArray(32) { 11 })
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

    /** Parses a numeric field as millis; null becomes -1 so bounds fail loudly. */
    private fun ms(line: String, key: String): Long = field(line, key)?.toLong() ?: -1L

    private fun newSchedule(pages: Int) = TranslationPipelineDiagnostics.startSchedule(
        mode = TranslationTraceMode.BATCH,
        origin = TranslationTraceMode.BATCH,
        chapterRaw = "chapter",
        pages = pages,
    )

    /**
     * §6.3 case 3: two pages share ONE batch schedule and receive DISTINCT
     * correlated runs; each page contributes exactly one translate stage_end
     * (the per-page provider WAIT — not an envelope copy), one inpaint, one
     * render_join; the terminal schedule summary shows real native/provider
     * overlap with concurrency savings > 0 because the lanes actually ran
     * concurrently (real sleeps on a real dispatcher).
     */
    @Test
    @Timeout(120)
    fun `batch parity one schedule distinct runs per-page stages and lane overlap`() = runBlocking<Unit> {
        setUpTraceCapture(detailed = true)
        val schedule = newSchedule(pages = 2)
        val native = object : NativeLaneWorker {
            override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef {
                return OcrReadyPageRef(pageKey, pageIndex, 0L, emptyList())
            }

            override suspend fun runInpaintStage(pageKey: String) {
                Thread.sleep(100)
            }
        }
        val translator = object : TranslatorLaneWorker {
            override suspend fun translate(ref: OcrReadyPageRef) = Unit

            override suspend fun translateOutcome(ref: OcrReadyPageRef): ChunkCompletionOutcome {
                Thread.sleep(150)
                return ChunkCompletionOutcome.Completed(setOf(ref.pageKey))
            }
        }
        val render = object : RenderJoinWorker {
            override fun onNativeBranchDone(pageKey: String) {}
            override fun onTranslationBranchDone(pageKey: String) {}
            override suspend fun awaitAndRender(pageKey: String) = Unit
        }
        val coordinator = SequentialBatchCoordinator(
            nativeWorker = native,
            translatorWorker = translator,
            renderJoin = render,
            scheduleTrace = schedule,
        )

        val outcome = withContext(Dispatchers.Default) {
            coordinator.runPass1(listOf("p0" to 0, "p1" to 1), TranslatorComputeClass.REMOTE_IO)
        }
        outcome.status shouldBe BatchPass1Status.COMPLETED
        schedule.end(TranslationTraceOutcome.SUCCESS)

        // Exactly one schedule, one terminal, with real overlap.
        val scheduleStarts = lines("schedule_start")
        scheduleStarts.size shouldBe 1
        field(scheduleStarts.single(), "mode") shouldBe "batch"
        val scheduleEnds = lines("schedule_end")
        scheduleEnds.size shouldBe 1
        val scheduleEnd = scheduleEnds.single()
        field(scheduleEnd, "outcome") shouldBe "success"
        field(scheduleEnd, "sid") shouldBe field(scheduleStarts.single(), "sid")
        (ms(scheduleEnd, "overlapMs") >= 1) shouldBe true
        (ms(scheduleEnd, "concurrencySavingsMs") >= 1) shouldBe true
        (ms(scheduleEnd, "wallMs") >= ms(scheduleEnd, "overlapMs")) shouldBe true

        // Distinct correlated page runs under the shared schedule.
        val runStarts = lines("run_start")
        runStarts.size shouldBe 2
        runStarts.map { field(it, "rid") }.toSet().size shouldBe 2
        runStarts.forEach { field(it, "sid") shouldBe field(scheduleStarts.single(), "sid") }
        val runEnds = lines("run_end")
        runEnds.size shouldBe 2
        runEnds.forEach { end ->
            field(end, "outcome") shouldBe "success"
            runStarts.count { field(it, "rid") == field(end, "rid") } shouldBe 1
        }

        // Per-page provider WAIT attribution: one translate stage_end per page
        // run, and NO schedule-scoped (rid=none) translate event on this path —
        // the envelope duration belongs to the lane worker, never to the pages.
        val translateEnds = lines("stage_end").filter { field(it, "stage") == "translate" }
        translateEnds.size shouldBe 2
        translateEnds.forEach { end ->
            (field(end, "rid") == "none") shouldBe false
            (ms(end, "queueMs") >= 0) shouldBe true
        }
        // Native and join stages are measured and correlated per page too.
        lines("stage_end").filter { field(it, "stage") == "inpaint" }.size shouldBe 2
        lines("stage_end").filter { field(it, "stage") == "render_join" }.size shouldBe 2
        lines("stage_end").filter { field(it, "stage") == "render" }.size shouldBe 2
    }

    /**
     * §9 envelope double-count regression: an envelope shared by N pages is
     * measured ONCE at schedule scope (rid=none, envelope token present), while
     * each page carries its own short WAIT stage whose duration is independent
     * of the envelope duration. Also covers the facade identity fallback
     * (active schedule) and the bounded errorType mapping.
     */
    @Test
    @Timeout(60)
    fun `envelope duration counted once at schedule scope independent of page waits`() = runBlocking<Unit> {
        setUpTraceCapture(detailed = true)
        val schedule = newSchedule(pages = 2)
        val token = BatchTranslationDiagnostics.traceEnvelopeToken(listOf("p0", "p1"))
        BatchTranslationDiagnostics.noteActiveSchedule(schedule)
        try {
            val r0 = TranslationPipelineDiagnostics.startRun(schedule, "p0", 0)
            val r1 = TranslationPipelineDiagnostics.startRun(schedule, "p1", 1)

            // ONE schedule-scoped envelope span for the shared provider request.
            val envelopeSpan = schedule.beginStage(
                TranslationTraceStage.TRANSLATE,
                lane = TranslationTraceLane.PROVIDER,
                provider = TranslationTraceProvider.REMOTE,
                items = 4,
            )
            Thread.sleep(80)
            envelopeSpan.end(outcome = TranslationTraceOutcome.SUCCESS, envelope = token)
            // Idempotent (CAS): a second end must not emit a second event.
            envelopeSpan.end(outcome = TranslationTraceOutcome.SUCCESS, envelope = token)

            // Per-page WAIT spans: short, independent of the envelope duration.
            val pageSpans = listOf(r0, r1).map { run ->
                run.beginStage(
                    TranslationTraceStage.TRANSLATE,
                    lane = TranslationTraceLane.PROVIDER,
                    provider = TranslationTraceProvider.REMOTE,
                    items = 2,
                )
            }
            pageSpans.forEach { it.end(outcome = TranslationTraceOutcome.SUCCESS, queueMs = 7L) }

            // Facade with no currentRun: identity resolves to the ACTIVE
            // schedule and the error class maps to a bounded errorType.
            BatchTranslationDiagnostics.failure(
                stage = BatchDiagnosticStage.TRANSLATION,
                pageKey = "p1",
                errorClass = "SocketTimeoutException",
                retryCount = 1,
            )
            // Facade timing with the same fallback: SUCCESS stage_end, counted
            // once, never multiplied per page.
            BatchTranslationDiagnostics.timing(
                stage = BatchDiagnosticStage.TRANSLATION,
                pageKey = "p0",
                durationMs = 5,
                itemCount = 2,
                success = true,
            )

            r0.end(TranslationTraceOutcome.SUCCESS)
            r1.end(TranslationTraceOutcome.SUCCESS)
        } finally {
            BatchTranslationDiagnostics.noteActiveSchedule(null)
            schedule.end(TranslationTraceOutcome.SUCCESS)
        }

        val scheduleSids = lines("schedule_start").map { field(it, "sid") }.toSet()
        scheduleSids.size shouldBe 1
        val sid = scheduleSids.single()

        val translateEnds = lines("stage_end").filter { field(it, "stage") == "translate" }
        // The envelope is counted ONCE at schedule scope (rid=none) and carries
        // the correlation token. (The facade failure/timing calls below also
        // emit rid=none translate facts — none of them carries an envelope
        // token, and none repeats the envelope duration.)
        val envelopeEnds = translateEnds.filter { field(it, "envelope") == token }
        envelopeEnds.size shouldBe 1
        field(envelopeEnds.single(), "rid") shouldBe "none"
        (ms(envelopeEnds.single(), "durationMs") >= 50) shouldBe true
        translateEnds.filter { field(it, "rid") == "none" && field(it, "envelope") != null }.size shouldBe 1
        // N per-page WAIT events remain independent: short durations, own sid,
        // and the page's queueMs attributed separately.
        val pageEnds = translateEnds.filter { field(it, "rid") != "none" }
        pageEnds.size shouldBe 2
        pageEnds.forEach { end ->
            field(end, "sid") shouldBe sid
            (ms(end, "durationMs") <= 50) shouldBe true
            ms(end, "queueMs") shouldBe 7L
        }

        // Exactly two run terminals; facade events did not open/close runs.
        lines("run_end").size shouldBe 2

        // Bounded error mapping: socket timeout -> errorType=http, attributed
        // to the schedule identity, emitted once.
        val failureEnds = lines("stage_end").filter { field(it, "errorType") == "http" }
        failureEnds.size shouldBe 1
        field(failureEnds.single(), "sid") shouldBe sid
        field(failureEnds.single(), "outcome") shouldBe "failure"

        lines("schedule_end").size shouldBe 1
    }

    /**
     * Teardown-exception amendment: when the batch teardown callback throws,
     * the schedule STILL emits exactly one schedule_end (outcome=
     * teardown_exception) and the exception propagates unchanged.
     */
    @Test
    @Timeout(60)
    fun `teardown exception still emits exactly one schedule_end as teardown_exception`() = runBlocking<Unit> {
        setUpTraceCapture(detailed = true)
        val thrown = runBatchWithTeardown(teardownThrows = true)

        thrown.shouldBeInstanceOf<IllegalStateException>()
        val scheduleEnds = lines("schedule_end")
        scheduleEnds.size shouldBe 1
        field(scheduleEnds.single(), "outcome") shouldBe "teardown_exception"
        // The engine_setup span settled before the teardown unwound.
        lines("stage_end").filter { field(it, "stage") == "engine_setup" }.size shouldBe 1
    }

    /** Control: a clean teardown exits with the typed setup-failure terminal. */
    @Test
    @Timeout(60)
    fun `clean teardown emits one schedule_end with failure outcome`() = runBlocking<Unit> {
        setUpTraceCapture(detailed = true)
        val thrown = runBatchWithTeardown(teardownThrows = false)

        thrown shouldBe null
        val scheduleEnds = lines("schedule_end")
        scheduleEnds.size shouldBe 1
        field(scheduleEnds.single(), "outcome") shouldBe "failure"
        lines("stage_end").filter { field(it, "stage") == "engine_setup" }.size shouldBe 1
    }

    /**
     * Drives [BatchChapterTranslator.translateBatch] with a native lane whose
     * admission always fails (engine-setup null path) and the requested
     * teardown callback behavior. This exercises the outer schedule lifecycle:
     * created before engine setup, terminal on EVERY exit, closure even when
     * the teardown region itself throws.
     */
    private fun runBatchWithTeardown(teardownThrows: Boolean): Throwable? {
        val store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
        )
        val manga = mockk<tachiyomi.domain.manga.model.Manga>(relaxed = true)
        every { manga.id } returns 42L
        val chapter = mockk<tachiyomi.domain.chapter.model.Chapter>(relaxed = true)
        every { chapter.id } returns 42L
        every { chapter.name } returns "chapter"
        val source = mockk<eu.kanade.tachiyomi.source.online.HttpSource>(relaxed = true)
        every { source.id } returns 42L

        val prefs = mockk<TranslationPreferences>(relaxed = true)
        every { prefs.translateFromLanguage() } returns stringPref("ENGLISH")
        every { prefs.translateToLanguage() } returns stringPref("ENGLISH")

        val failingLane = object : NativeLaneRunner {
            override suspend fun <T> run(
                timeoutMs: Long,
                chapterId: Long?,
                chapterName: String,
                pageKey: String,
                onTimeout: suspend () -> Unit,
                block: suspend () -> T,
            ): T? = null
        }

        val translator = BatchChapterTranslator(
            provider = mockk(relaxed = true),
            translationPreferences = prefs,
            nativeLane = failingLane,
            engineRebuildMutex = Mutex(),
            ensureEnginesBuiltFor = { _, _ -> },
            recognitionEngineFn = { error("not reached") },
            textTranslatorFn = { error("not reached") },
            computeSourceFingerprintFn = { null },
            batchExpectedFingerprintsFn = { _, _ -> BatchExpectedFingerprints() },
            inpaintingModeFromPref = { InpaintingMode.FAST },
            releaseBatchPageLease = { _, _ -> },
            persistPageWithOomRecovery = { _, _, _, _ -> error("not reached") },
            loadPersistedCleanedBitmap = { _, _, _, _ -> null },
            deleteRetiredCleanedFile = { _, _, _, _, _ -> },
            markPageTimedOut = { _, _, _, _ -> },
            analyzePage = { _, _, _, _, _ -> error("not reached") },
            decodePageBitmapForTranslation = { _, _ -> null },
            preflightInpaintGate = { _, _ -> },
            inpaintPage = { _, _, _, _, _ -> error("not reached") },
            retryInpaintDownscaled = { _, _, _, _, _, _, _ -> error("not reached") },
            persistCleanedBitmap = { _, _, _, _, _, _, _, _, _, _ -> null },
            updatePageFromCurrentSnapshotFn = { _, _, _, _ -> error("not reached") },
            onBatchClosedFn = {
                if (teardownThrows) {
                    { _, _, _, _ -> throw IllegalStateException("teardown boom") }
                } else {
                    null
                }
            },
        )

        val streams: List<Pair<String, () -> InputStream>> = listOf(
            "p0" to { ByteArrayInputStream(ByteArray(4)) },
            "p1" to { ByteArrayInputStream(ByteArray(4)) },
        )
        var thrown: Throwable? = null
        try {
            runBlocking {
                translator.translateBatch(manga, chapter, source, store, streams)
            }
        } catch (t: Throwable) {
            thrown = t
        }
        return thrown
    }

    private fun stringPref(value: String): Preference<String> = mockk {
        every { get() } returns value
    }
}
