package eu.kanade.translation.diagnostics

import ai.onnxruntime.OrtException
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * JVM tests for the `translation_trace_v1` formatter, privacy sanitizer,
 * budgets, gate, and identity keys.
 *
 * No Robolectric: the sink is swapped for a capturing lambda, and the ID
 * generator / identity keys are pinned for deterministic exact-line
 * assertions.
 */
class TranslationPipelineDiagnosticsTest {

    private class FakeClock(var nowNanos: Long = 0L) : TranslationTraceClock {
        override fun nowNanos(): Long = nowNanos
        fun advanceMs(ms: Long) {
            nowNanos += ms * NANOS_PER_MS
        }
    }

    private val keys = TranslationIdentityKeys(FIXED_KEY)

    private fun withCapture(detailed: Boolean, block: () -> Unit): List<String> {
        val lines = mutableListOf<String>()
        val oldSink = TranslationPipelineDiagnostics.sink
        val oldGate = TranslationPipelineDiagnostics.detailedTracingEnabled
        val oldIds = TranslationPipelineDiagnostics.idGenerator
        val oldKeys = TranslationPipelineDiagnostics.identityKeys
        TranslationPipelineDiagnostics.sink = TranslationTraceSink { _, line -> lines.add(line) }
        TranslationPipelineDiagnostics.detailedTracingEnabled = detailed
        TranslationPipelineDiagnostics.idGenerator = TranslationTraceIdGenerator(processPrefix = TEST_PREFIX)
        TranslationPipelineDiagnostics.identityKeys = keys
        try {
            block()
        } finally {
            TranslationPipelineDiagnostics.sink = oldSink
            TranslationPipelineDiagnostics.detailedTracingEnabled = oldGate
            TranslationPipelineDiagnostics.idGenerator = oldIds
            TranslationPipelineDiagnostics.identityKeys = oldKeys
        }
        return lines
    }

    // ------------------------------------------------------------------
    // 1) Fixed key order + schema token for every event family
    // ------------------------------------------------------------------

    @Test
    fun `every event family emits fixed key order with schema token`() {
        val chapterToken = keys.token('c', "chapter-7")
        val pageToken = keys.token('p', "001.jpg")
        val identity = "sid=${TEST_PREFIX}s1 rid=none mode=manual origin=manual chapter=$chapterToken"
        val runIdentity =
            "sid=${TEST_PREFIX}s1 rid=${TEST_PREFIX}r1 mode=manual origin=manual chapter=$chapterToken page=$pageToken"

        val lines = withCapture(detailed = true) {
            val clock = FakeClock()
            val schedule = TranslationPipelineDiagnostics.startSchedule(
                mode = TranslationTraceMode.MANUAL,
                origin = TranslationTraceMode.MANUAL,
                chapterRaw = "chapter-7",
                pages = 1,
                clock = clock,
            )
            val run = TranslationPipelineDiagnostics.startRun(
                schedule = schedule,
                pageRaw = "001.jpg",
                pageIndex = 0,
                plan = TranslationTracePlan.FRESH,
                clock = clock,
            )
            val span = run.beginStage(
                TranslationTraceStage.OCR,
                provider = TranslationTraceProvider.CPU,
                model = TranslationTraceModel.MANGA_OCR,
                items = 3,
            )
            clock.advanceMs(5)
            span.end()
            run.end(TranslationTraceOutcome.SUCCESS)
            schedule.end(TranslationTraceOutcome.SUCCESS)
            TranslationPipelineDiagnostics.routeChange(
                run = run,
                schedule = schedule,
                stage = TranslationTraceStage.SEGMENT,
                model = TranslationTraceModel.BUBBLE_SEGMENTER,
                from = TranslationTraceProvider.QNN_HTP,
                to = TranslationTraceProvider.CPU,
                reason = "runtime_failure",
                error = OrtException(OrtException.OrtErrorCode.ORT_ENGINE_ERROR, "QNN execute failed with error code 1100"),
                retry = 1,
            )
            schedule.reportState(
                TranslationScheduleState.ADMITTED,
                reason = "lease_granted",
                queueDepth = 0,
                nativeActive = 1,
                providerActive = 0,
            )
        }

        lines.shouldContainExactly(
            // schedule_start
            "schema=translation_trace_v1 event=schedule_start $identity page=none pageIndex=none" +
                " pages=1 wallMs=0 nativeBusyMs=0 providerBusyMs=0 renderBusyMs=0" +
                " overlapMs=0 unionActiveMs=0 concurrencySavingsMs=0 workMs=0 criticalPathMs=0" +
                " maxQueueMs=0 slowestPage=none bottleneck=none outcome=started",
            // run_start
            "schema=translation_trace_v1 event=run_start $runIdentity pageIndex=0" +
                " plan=fresh queuedMs=0 totalMs=0 stageSumMs=0 bottleneck=none bottleneckMs=0" +
                " retries=0 outcome=started errorType=none errorCode=none",
            // stage_start
            "schema=translation_trace_v1 event=stage_start $runIdentity pageIndex=0" +
                " lane=native stage=ocr queueMs=0 durationMs=0 totalMs=0" +
                " provider=cpu model=manga_ocr items=3 outcome=started lag=false" +
                " budgetMs=2000 errorType=none errorCode=none",
            // stage_end
            "schema=translation_trace_v1 event=stage_end $runIdentity pageIndex=0" +
                " lane=native stage=ocr queueMs=0 durationMs=5 totalMs=5" +
                " provider=cpu model=manga_ocr items=3 outcome=success lag=false" +
                " budgetMs=2000 errorType=none errorCode=none",
            // run_end
            "schema=translation_trace_v1 event=run_end $runIdentity pageIndex=0" +
                " plan=fresh queuedMs=0 totalMs=5 stageSumMs=5 bottleneck=ocr bottleneckMs=5" +
                " retries=0 outcome=success errorType=none errorCode=none",
            // schedule_end
            "schema=translation_trace_v1 event=schedule_end $identity page=none pageIndex=none" +
                " pages=1 wallMs=5 nativeBusyMs=0 providerBusyMs=0 renderBusyMs=0" +
                " overlapMs=0 unionActiveMs=0 concurrencySavingsMs=0 workMs=0 criticalPathMs=5" +
                " maxQueueMs=0 slowestPage=$pageToken bottleneck=none outcome=success",
            // route_change
            "schema=translation_trace_v1 event=route_change $runIdentity pageIndex=0" +
                " stage=segment model=bubble_segmenter from=qnn_htp to=cpu" +
                " reason=runtime_failure errorType=ort errorCode=1100 retry=1",
            // schedule_state
            "schema=translation_trace_v1 event=schedule_state $identity page=none pageIndex=none" +
                " state=admitted reason=lease_granted queueDepth=0 nativeActive=1 providerActive=0",
        )
        lines.forEach { line -> line.shouldStartWithSchema() }
    }

    // ------------------------------------------------------------------
    // 2) Hostile page/chapter/error strings never appear raw
    // ------------------------------------------------------------------

    @Test
    fun `malicious page chapter and error strings never appear raw in emitted lines`() {
        val hostileChapter = """Chapter 12 <img src=x onerror="steal()"> & DROP TABLE pages; --"""
        val hostilePage = "../../secret/page_001.jpg?token=abc&x=1"
        val hostileError = RuntimeException("password=hunter2 via https://evil.example/leak DROP FROM pages")

        val lines = withCapture(detailed = true) {
            val clock = FakeClock()
            val schedule = TranslationPipelineDiagnostics.startSchedule(
                TranslationTraceMode.BATCH,
                TranslationTraceMode.BATCH,
                chapterRaw = hostileChapter,
                pages = 8,
                clock = clock,
            )
            val run = TranslationPipelineDiagnostics.startRun(
                schedule = schedule,
                pageRaw = hostilePage,
                pageIndex = 0,
                clock = clock,
            )
            val span = run.beginStage(
                TranslationTraceStage.OCR,
                provider = TranslationTraceProvider.CPU,
                model = TranslationTraceModel.PADDLE_OCR,
            )
            span.end(TranslationTraceOutcome.FAILURE, error = hostileError)
            run.end(TranslationTraceOutcome.FAILURE, error = hostileError)
            schedule.end(TranslationTraceOutcome.FAILURE)
        }

        for (line in lines) {
            line.shouldNotContain("steal")
            line.shouldNotContain("DROP")
            line.shouldNotContain("<img")
            line.shouldNotContain("secret")
            line.shouldNotContain("token=abc")
            line.shouldNotContain("hunter2")
            line.shouldNotContain("evil.example")
            // key=value tokens: no raw separators can appear inside values
            line.split(' ').forEach { token ->
                val value = token.substringAfter('=', missingDelimiterValue = "=")
                if (token.contains('=')) value.shouldMatch(TOKEN_VALUE_PATTERN)
            }
        }
        val pageToken = keys.token('p', hostilePage)
        pageToken.shouldMatch("[cp][0-9a-f]{16}")
        // keys are keyed, not content-derived: token charset is hex only
        lines.first { it.contains("event=run_end") }.shouldContain("errorType=unknown errorCode=none")
    }

    // ------------------------------------------------------------------
    // 5) Budget table yields deterministic lag / budgetMs
    // ------------------------------------------------------------------

    @Test
    fun `budget table yields deterministic lag and budget values`() {
        TranslationTraceBudgets.budgetMsFor(TranslationTraceStage.LEASE_WAIT, TranslationTraceProvider.NONE) shouldBe 1_000L
        TranslationTraceBudgets.budgetMsFor(TranslationTraceStage.NATIVE_QUEUE, TranslationTraceProvider.NONE) shouldBe 1_000L
        TranslationTraceBudgets.budgetMsFor(TranslationTraceStage.PREPARED_QUEUE, TranslationTraceProvider.NONE) shouldBe 1_000L
        TranslationTraceBudgets.budgetMsFor(TranslationTraceStage.PROVIDER_GOVERNOR_WAIT, TranslationTraceProvider.NONE) shouldBe 1_000L
        TranslationTraceBudgets.budgetMsFor(TranslationTraceStage.SOURCE_DECODE, TranslationTraceProvider.CPU) shouldBe 750L
        TranslationTraceBudgets.budgetMsFor(TranslationTraceStage.DETECT, TranslationTraceProvider.CPU) shouldBe 750L
        TranslationTraceBudgets.budgetMsFor(TranslationTraceStage.SEGMENT, TranslationTraceProvider.CPU) shouldBe 750L
        TranslationTraceBudgets.budgetMsFor(TranslationTraceStage.OCR, TranslationTraceProvider.CPU) shouldBe 2_000L
        TranslationTraceBudgets.budgetMsFor(TranslationTraceStage.INPAINT, TranslationTraceProvider.QNN_HTP) shouldBe 1_000L
        TranslationTraceBudgets.budgetMsFor(TranslationTraceStage.INPAINT, TranslationTraceProvider.CPU) shouldBe 6_000L
        TranslationTraceBudgets.budgetMsFor(TranslationTraceStage.TRANSLATE, TranslationTraceProvider.REMOTE) shouldBe 8_000L
        TranslationTraceBudgets.budgetMsFor(TranslationTraceStage.TRANSLATE, TranslationTraceProvider.LOCAL) shouldBe 5_000L
        TranslationTraceBudgets.budgetMsFor(TranslationTraceStage.LAYOUT, TranslationTraceProvider.ANDROID_CANVAS) shouldBe 1_000L
        TranslationTraceBudgets.budgetMsFor(TranslationTraceStage.RENDER, TranslationTraceProvider.ANDROID_CANVAS) shouldBe 1_000L
        TranslationTraceBudgets.budgetMsFor(TranslationTraceStage.STORE_COMMIT, TranslationTraceProvider.NONE) shouldBe 1_000L
        TranslationTraceBudgets.budgetMsFor(TranslationTraceStage.STORE_FLUSH, TranslationTraceProvider.NONE) shouldBe 1_000L

        // lag=true iff durationMs > budgetMs or queueMs > queue budget.
        withCapture(detailed = true) {
            val clock = FakeClock()
            val schedule = TranslationPipelineDiagnostics.startSchedule(
                TranslationTraceMode.MANUAL,
                clock = clock,
            )
            val run = TranslationPipelineDiagnostics.startRun(schedule, clock = clock)

            val overBudget = run.beginStage(TranslationTraceStage.OCR, provider = TranslationTraceProvider.CPU)
            clock.advanceMs(2_001)
            overBudget.end()

            val withinBudget = run.beginStage(TranslationTraceStage.OCR, provider = TranslationTraceProvider.CPU)
            clock.advanceMs(2_000)
            withinBudget.end() // duration == budget -> not lagged

            val queued = run.beginStage(TranslationTraceStage.TRANSLATE, provider = TranslationTraceProvider.REMOTE)
            clock.advanceMs(1)
            queued.end(queueMs = 1_001) // queue wait dominates -> lagged

            val lease = run.beginStage(TranslationTraceStage.LEASE_WAIT)
            clock.advanceMs(1_000)
            lease.end() // queue stage duration == budget -> not lagged
        }.let { lines ->
            val stageEnds = lines.filter { it.contains("event=stage_end") }
            stageEnds[0].shouldContain("stage=ocr queueMs=0 durationMs=2001")
            stageEnds[0].shouldContain("lag=true budgetMs=2000")
            stageEnds[1].shouldContain("stage=ocr queueMs=0 durationMs=2000")
            stageEnds[1].shouldContain("lag=false budgetMs=2000")
            stageEnds[2].shouldContain("stage=translate queueMs=1001 durationMs=1")
            stageEnds[2].shouldContain("lag=true budgetMs=8000")
            stageEnds[3].shouldContain("stage=lease_wait queueMs=1000 durationMs=1000")
            stageEnds[3].shouldContain("outcome=success lag=false budgetMs=1000")
        }
    }

    // ------------------------------------------------------------------
    // 8) Outcomes are a bounded enum; terminal lines carry exact tokens
    // ------------------------------------------------------------------

    @Test
    fun `terminal outcomes enumerate the bounded token set`() {
        val outcomes = listOf(
            TranslationTraceOutcome.SUCCESS,
            TranslationTraceOutcome.FAILURE,
            TranslationTraceOutcome.PAUSE,
            TranslationTraceOutcome.TIMEOUT,
            TranslationTraceOutcome.CANCELLED,
            TranslationTraceOutcome.CANCELLED_BEFORE_DISPATCH,
            TranslationTraceOutcome.CANCELLED_DURING_SEND,
            TranslationTraceOutcome.EVICTED,
            TranslationTraceOutcome.STALE_HANDOFF,
            TranslationTraceOutcome.COORDINATOR_REPLACED,
            TranslationTraceOutcome.ATTACHED,
            TranslationTraceOutcome.SKIP,
            TranslationTraceOutcome.RESUME,
            TranslationTraceOutcome.PERSISTENCE_REJECTED,
            TranslationTraceOutcome.TEARDOWN_EXCEPTION,
        )

        val lines = withCapture(detailed = false) {
            val schedule = TranslationPipelineDiagnostics.startSchedule(
                TranslationTraceMode.AUTO,
                clock = FakeClock(),
            )
            outcomes.forEach { outcome ->
                val run = TranslationPipelineDiagnostics.startRun(schedule, pageIndex = 0, clock = FakeClock())
                run.end(outcome)
            }
            // schedule itself closes with a non-success terminal too
            schedule.end(TranslationTraceOutcome.TEARDOWN_EXCEPTION)
        }

        val runEnds = lines.filter { it.contains("event=run_end") }
        runEnds.size shouldBe outcomes.size
        outcomes.forEachIndexed { index, outcome ->
            runEnds[index].shouldContain("outcome=${outcome.token}")
        }
        val scheduleEnd = lines.single { it.contains("event=schedule_end") }
        scheduleEnd.shouldContain("outcome=teardown_exception")
        // Gate off: only terminals were emitted (15 run_end + 1 schedule_end).
        lines.size shouldBe runEnds.size + 1
    }

    // ------------------------------------------------------------------
    // 9) Gating: detailed suppressed, terminals/lag emitted; state coalesced
    // ------------------------------------------------------------------

    @Test
    fun `gate off suppresses detailed events but keeps terminals lag and failure stage ends`() {
        val lines = withCapture(detailed = false) {
            val clock = FakeClock()
            val schedule = TranslationPipelineDiagnostics.startSchedule(
                TranslationTraceMode.AUTO,
                chapterRaw = "c",
                clock = clock,
            )
            schedule.reportState(TranslationScheduleState.QUEUED, "window_pending", 0, 0, 0) // suppressed: gate off
            val run = TranslationPipelineDiagnostics.startRun(schedule, pageIndex = 1, clock = clock)
            val ok = run.beginStage(TranslationTraceStage.SEGMENT, provider = TranslationTraceProvider.CPU)
            ok.end() // success, non-lagged -> suppressed by gate

            val slow = run.beginStage(TranslationTraceStage.OCR, provider = TranslationTraceProvider.CPU)
            clock.advanceMs(2_001)
            slow.end() // lagged -> must survive the gate

            val failed = run.beginStage(TranslationTraceStage.TRANSLATE, provider = TranslationTraceProvider.REMOTE)
            failed.end(TranslationTraceOutcome.FAILURE, error = IOException("io detail")) // failure -> survives

            run.end(TranslationTraceOutcome.SUCCESS) // terminal -> survives
            schedule.end(TranslationTraceOutcome.SUCCESS) // terminal -> survives
        }

        lines.map { it.substringAfter("event=").substringBefore(' ') }.shouldContainExactly(
            "stage_end", // lagged OCR
            "stage_end", // failed translate
            "run_end",
            "schedule_end",
        )
        val events = lines.joinToString("\n")
        events.shouldNotContain("event=schedule_start")
        events.shouldNotContain("event=run_start")
        events.shouldNotContain("event=stage_start")
        events.shouldNotContain("stage=segment")
        events.shouldContain("stage=ocr queueMs=0 durationMs=2001")
        events.shouldContain("outcome=success lag=true budgetMs=2000")
        events.shouldContain("stage=translate queueMs=0 durationMs=0")
        events.shouldContain("outcome=failure lag=false budgetMs=8000 errorType=io")
        events.shouldNotContain("io detail")
    }

    @Test
    fun `repeated identical schedule states are coalesced within a schedule only`() {
        val lines = withCapture(detailed = true) {
            val scheduleA = TranslationPipelineDiagnostics.startSchedule(TranslationTraceMode.AUTO, clock = FakeClock())
            val scheduleB = TranslationPipelineDiagnostics.startSchedule(TranslationTraceMode.AUTO, clock = FakeClock())

            scheduleA.reportState(TranslationScheduleState.QUEUED, "window_pending", 2, 0, 0)
            scheduleA.reportState(TranslationScheduleState.QUEUED, "window_pending", 3, 0, 0) // same state+reason -> suppressed
            scheduleA.reportState(TranslationScheduleState.DEFERRED, "source_unavailable", 2, 0, 0) // new state -> emitted
            scheduleA.reportState(TranslationScheduleState.DEFERRED, "source_unavailable", 1, 0, 0) // coalesced
            scheduleA.reportState(TranslationScheduleState.DEFERRED, "memory_pressure", 1, 0, 0) // new reason -> emitted

            scheduleB.reportState(TranslationScheduleState.QUEUED, "window_pending", 1, 0, 0) // other schedule -> emitted
        }

        val states = lines.filter { it.contains("event=schedule_state") }
        states.size shouldBe 4
        states.filter { it.contains("sid=${TEST_PREFIX}s1") }.size shouldBe 3
        states.last().shouldContain("sid=${TEST_PREFIX}s2")
        states.last().shouldContain("state=queued reason=window_pending queueDepth=1")
    }

    // ------------------------------------------------------------------
    // 10) Same page name across simulated launches is uncorrelatable
    // ------------------------------------------------------------------

    @Test
    fun `identical page names in different process launches produce uncorrelatable tokens`() {
        // Two simulated launches: each gets a fresh random key.
        val launchOne = TranslationIdentityKeys()
        val launchTwo = TranslationIdentityKeys()

        val launchOneToken = launchOne.token('p', "page_001.jpg")
        val launchOneTokenAgain = launchOne.token('p', "page_001.jpg")
        val launchTwoToken = launchTwo.token('p', "page_001.jpg")

        // Within a launch: stable, so events can be correlated per process.
        launchOneToken shouldBe launchOneTokenAgain
        // Across launches: uncorrelatable.
        (launchOneToken == launchTwoToken) shouldBe false

        // Absent input collapses to the bounded placeholder.
        launchOne.token('p', null) shouldBe "none"
        launchOne.token('p', "") shouldBe "none"

        // The forbidden deterministic hash would produce the same digest for
        // both launches; the keyed digest must never do that. Also verify the
        // existing ShortHash is NOT what our tokens are derived from.
        val shortHash = eu.kanade.translation.util.ShortHash.hash("page_001.jpg")
        (launchOneToken.contains(shortHash)) shouldBe false
    }

    // ------------------------------------------------------------------
    // The reason field is pinned to a bounded token vocabulary
    // ------------------------------------------------------------------

    @Test
    fun `hostile and unknown reasons collapse to invalid on state and route events`() {
        val hostile = "leak msg; drop table"
        val lines = withCapture(detailed = true) {
            val clock = FakeClock()
            val schedule = TranslationPipelineDiagnostics.startSchedule(
                TranslationTraceMode.AUTO,
                clock = clock,
            )
            val run = TranslationPipelineDiagnostics.startRun(schedule, pageIndex = 0, clock = clock)

            schedule.reportState(TranslationScheduleState.DEFERRED, hostile, 0, 0, 0)
            TranslationPipelineDiagnostics.routeChange(
                run = run,
                schedule = schedule,
                stage = TranslationTraceStage.SEGMENT,
                model = TranslationTraceModel.BUBBLE_SEGMENTER,
                from = TranslationTraceProvider.QNN_HTP,
                to = TranslationTraceProvider.CPU,
                reason = hostile,
                retry = 1,
            )
            // Charset-safe but not in the vocabulary must ALSO collapse:
            // the vocabulary is a closed set, not merely a charset.
            schedule.reportState(TranslationScheduleState.DEFERRED, "totally_unknown_reason_xyz", 0, 0, 0)
            // Null/empty collapse too.
            schedule.reportState(TranslationScheduleState.DEFERRED, "", 0, 0, 0)
            // Every vocabulary token passes through unchanged.
            TranslationTraceReason.entries.forEach { reason ->
                schedule.reportState(TranslationScheduleState.DEFERRED, reason.token, 0, 0, 0)
            }
            run.end(TranslationTraceOutcome.SUCCESS)
            schedule.end(TranslationTraceOutcome.SUCCESS)
        }

        val stateLines = lines.filter { it.contains("event=schedule_state") }
        val routeLines = lines.filter { it.contains("event=route_change") }
        routeLines.single().shouldContain("reason=invalid")
        stateLines[0].shouldContain("reason=invalid") // hostile
        stateLines[1].shouldContain("reason=invalid") // charset-safe unknown
        stateLines[2].shouldContain("reason=invalid") // empty
        // One clean line per vocabulary token, each carrying its own token.
        val vocabularyLines = stateLines.drop(3)
        vocabularyLines.size shouldBe TranslationTraceReason.entries.size
        TranslationTraceReason.entries.forEachIndexed { index, reason ->
            vocabularyLines[index].shouldContain("reason=${reason.token}")
        }
        for (line in lines) {
            line.shouldNotContain("leak msg")
            line.shouldNotContain("drop table")
            line.shouldNotContain("totally_unknown_reason_xyz")
        }
    }

    @Test
    fun `hostile errorType override collapses to invalid`() {
        val lines = withCapture(detailed = true) {
            val clock = FakeClock()
            val schedule = TranslationPipelineDiagnostics.startSchedule(
                TranslationTraceMode.MANUAL,
                clock = clock,
            )
            val run = TranslationPipelineDiagnostics.startRun(schedule, pageIndex = 0, clock = clock)
            val span = run.beginStage(TranslationTraceStage.TRANSLATE, provider = TranslationTraceProvider.REMOTE)
            span.end(
                TranslationTraceOutcome.FAILURE,
                errorType = "upstream 500; DROP TABLE users",
                errorCode = 500L,
                registeredProvider = TranslationTraceProvider.REMOTE,
            )
            // Bounded override tokens pass through and provenance fields append.
            val span2 = run.beginStage(TranslationTraceStage.SEGMENT, provider = TranslationTraceProvider.CPU)
            span2.end(
                TranslationTraceOutcome.SUCCESS,
                registeredProvider = TranslationTraceProvider.QNN_HTP,
                provenProvider = TranslationTraceProvider.CPU,
            )
            run.end(TranslationTraceOutcome.FAILURE, errorType = "http")
            schedule.end(TranslationTraceOutcome.FAILURE)
        }

        val stageEnds = lines.filter { it.contains("event=stage_end") }
        stageEnds[0].shouldContain("errorType=invalid errorCode=500")
        stageEnds[0].shouldContain("registeredProvider=remote")
        stageEnds[1].shouldContain("registeredProvider=qnn_htp provenProvider=cpu")
        val runEnd = lines.single { it.contains("event=run_end") }
        runEnd.shouldContain("errorType=http")
        for (line in lines) {
            line.shouldNotContain("DROP TABLE")
        }
    }

    @Test
    fun `provider label mapping is bounded and fail open`() {
        TranslationPipelineDiagnostics.providerFromLabel("cpu") shouldBe TranslationTraceProvider.CPU
        TranslationPipelineDiagnostics.providerFromLabel("xnnpack") shouldBe TranslationTraceProvider.XNNPACK
        TranslationPipelineDiagnostics.providerFromLabel("qnn_htp") shouldBe TranslationTraceProvider.QNN_HTP
        TranslationPipelineDiagnostics.providerFromLabel("fixed_qnn_htp") shouldBe TranslationTraceProvider.QNN_HTP
        TranslationPipelineDiagnostics.providerFromLabel("qnn_gpu") shouldBe TranslationTraceProvider.QNN_GPU
        TranslationPipelineDiagnostics.providerFromLabel("nnapi") shouldBe TranslationTraceProvider.NNAPI
        TranslationPipelineDiagnostics.providerFromLabel("fixed_nnapi") shouldBe TranslationTraceProvider.NNAPI
        TranslationPipelineDiagnostics.providerFromLabel("remote") shouldBe TranslationTraceProvider.REMOTE
        TranslationPipelineDiagnostics.providerFromLabel("local") shouldBe TranslationTraceProvider.LOCAL
        TranslationPipelineDiagnostics.providerFromLabel("uninitialized") shouldBe TranslationTraceProvider.NONE
        TranslationPipelineDiagnostics.providerFromLabel("n/a") shouldBe TranslationTraceProvider.NONE
        TranslationPipelineDiagnostics.providerFromLabel(null) shouldBe TranslationTraceProvider.NONE
        TranslationPipelineDiagnostics.providerFromLabel("") shouldBe TranslationTraceProvider.NONE
        TranslationPipelineDiagnostics.providerFromLabel("../../etc/passwd") shouldBe TranslationTraceProvider.NONE
    }

    private fun String.shouldStartWithSchema() {
        shouldContain(TranslationPipelineDiagnostics.SCHEMA_KEY)
        split(' ').first() shouldBe TranslationPipelineDiagnostics.SCHEMA_KEY
    }

    companion object {
        const val TEST_PREFIX = "0000aa11-"
        val FIXED_KEY = ByteArray(32) { (it + 1).toByte() }
        const val NANOS_PER_MS = 1_000_000L
        const val TOKEN_VALUE_PATTERN = """[A-Za-z0-9_.\-]+|none"""
    }
}
