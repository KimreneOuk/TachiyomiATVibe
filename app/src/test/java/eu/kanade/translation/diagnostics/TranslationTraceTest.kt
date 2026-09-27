package eu.kanade.translation.diagnostics

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * JVM tests for the trace engine: clock clamping, coroutine identity
 * propagation, overlap math, repeated-stage accumulation, and idempotent
 * terminals.
 */
class TranslationTraceTest {

    private class FakeClock(var nowNanos: Long = 0L) : TranslationTraceClock {
        override fun nowNanos(): Long = nowNanos
        fun advanceMs(ms: Long) {
            nowNanos += ms * NANOS_PER_MS
        }
    }

    private val keys = TranslationIdentityKeys(TranslationPipelineDiagnosticsTest.FIXED_KEY)

    private fun withCapture(detailed: Boolean, block: () -> Unit): List<String> {
        val lines = mutableListOf<String>()
        val oldSink = TranslationPipelineDiagnostics.sink
        val oldGate = TranslationPipelineDiagnostics.detailedTracingEnabled
        val oldIds = TranslationPipelineDiagnostics.idGenerator
        val oldKeys = TranslationPipelineDiagnostics.identityKeys
        TranslationPipelineDiagnostics.sink = TranslationTraceSink { _, line -> lines.add(line) }
        TranslationPipelineDiagnostics.detailedTracingEnabled = detailed
        TranslationPipelineDiagnostics.idGenerator = TranslationTraceIdGenerator(processPrefix = "0000aa11-")
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
    // 3) Identity persists across dispatcher hops via TranslationTraceElement
    // ------------------------------------------------------------------

    @Test
    fun `identity persists across dispatcher hops via trace element`() {
        val lines = withCapture(detailed = true) {
            val schedule = TranslationPipelineDiagnostics.startSchedule(
                TranslationTraceMode.AUTO,
                chapterRaw = "chapter",
                clock = FakeClock(),
            )
            val run = TranslationPipelineDiagnostics.startRun(
                schedule = schedule,
                pageRaw = "005.jpg",
                pageIndex = 4,
                clock = FakeClock(),
            )
            runBlocking {
                val observed = CompletableDeferred<String>()
                launch(Dispatchers.Default + TranslationTrace.elementFor(run)) {
                    val current = TranslationTrace.currentRun()
                    observed.complete("${current?.identity?.sid}|${current?.identity?.rid}")
                    // Deep synchronous code emits with no run parameter at all.
                    TranslationTrace.beginStage(
                        TranslationTraceStage.OCR,
                        provider = TranslationTraceProvider.CPU,
                        model = TranslationTraceModel.MANGA_OCR,
                    ).close()
                }.join()
                observed.await() shouldBe "${run.identity.sid}|${run.identity.rid}"
            }
            run.end(TranslationTraceOutcome.SUCCESS)
            schedule.end(TranslationTraceOutcome.SUCCESS)
        }

        val sid = "0000aa11-s1"
        val rid = "0000aa11-r1"
        val stageEnd = lines.single { it.contains("event=stage_end") }
        stageEnd.shouldContain("sid=$sid rid=$rid")
        stageEnd.shouldContain("pageIndex=4")
        stageEnd.shouldContain("stage=ocr")
        // No run installed on a bare thread -> fail-open no-op span that
        // settles safely without any effect.
        val orphan = TranslationTrace.beginStage(TranslationTraceStage.OCR)
        orphan.end() shouldBe true
        orphan.isFinished shouldBe true
    }

    // ------------------------------------------------------------------
    // 4) Negative fake-clock deltas clamp to zero
    // ------------------------------------------------------------------

    @Test
    fun `negative clock deltas clamp to zero`() {
        val lines = withCapture(detailed = true) {
            val clock = FakeClock(nowNanos = 10 * NANOS_PER_MS)
            val schedule = TranslationPipelineDiagnostics.startSchedule(TranslationTraceMode.MANUAL, clock = clock)
            val run = TranslationPipelineDiagnostics.startRun(schedule, pageIndex = 0, clock = clock)

            val span = run.beginStage(TranslationTraceStage.SOURCE_DECODE)
            clock.nowNanos = 2 // clock went backwards
            span.end() // duration clamps to 0

            clock.nowNanos = 0
            run.end(TranslationTraceOutcome.SUCCESS) // totalMs clamps to 0
            schedule.end(TranslationTraceOutcome.SUCCESS)

            // Accumulator settle across a backwards jump is non-negative.
            val lane = schedule.enterLane(TranslationTraceLane.NATIVE)
            clock.nowNanos = 50
            lane.close()

            schedule.end(TranslationTraceOutcome.SUCCESS) // second close: no-op
        }

        lines.single { it.contains("event=stage_end") }.shouldContain("durationMs=0 totalMs=0")
        lines.single { it.contains("event=run_end") }.shouldContain("totalMs=0 stageSumMs=0")
        val scheduleEnd = lines.single { it.contains("event=schedule_end") }
        scheduleEnd.shouldContain("wallMs=0 nativeBusyMs=0")
    }

    // ------------------------------------------------------------------
    // 6) Run summary selects max stage as bottleneck; queue waits eligible
    // ------------------------------------------------------------------

    @Test
    fun `run summary selects the largest stage as bottleneck`() {
        val lines = withCapture(detailed = false) {
            val clock = FakeClock()
            val schedule = TranslationPipelineDiagnostics.startSchedule(TranslationTraceMode.MANUAL, clock = clock)
            val run = TranslationPipelineDiagnostics.startRun(schedule, pageIndex = 0, clock = clock)

            run.beginStage(TranslationTraceStage.DETECT).let {
                clock.advanceMs(100)
                it.end()
            }
            run.beginStage(TranslationTraceStage.OCR).let {
                clock.advanceMs(2_500)
                it.end()
            }
            run.beginStage(TranslationTraceStage.RENDER).let {
                clock.advanceMs(300)
                it.end()
            }
            run.end(TranslationTraceOutcome.SUCCESS)
        }

        lines.single { it.contains("event=run_end") }.shouldContain(
            "bottleneck=ocr bottleneckMs=2500",
        )
    }

    @Test
    fun `queue waits are eligible and can dominate the bottleneck`() {
        val lines = withCapture(detailed = false) {
            val clock = FakeClock()
            val schedule = TranslationPipelineDiagnostics.startSchedule(TranslationTraceMode.AUTO, clock = clock)
            val run = TranslationPipelineDiagnostics.startRun(schedule, pageIndex = 2, clock = clock)

            run.beginStage(TranslationTraceStage.LEASE_WAIT).let {
                clock.advanceMs(3_000)
                it.end()
            }
            run.beginStage(TranslationTraceStage.OCR).let {
                clock.advanceMs(100)
                it.end()
            }
            run.end(TranslationTraceOutcome.SUCCESS)
        }

        val runEnd = lines.single { it.contains("event=run_end") }
        runEnd.shouldContain("bottleneck=lease_wait bottleneckMs=3000")
        runEnd.shouldContain("queuedMs=3000")
        runEnd.shouldContain("stageSumMs=3100")
    }

    // ------------------------------------------------------------------
    // 7) Online overlap accumulator + repeated stage intervals sum
    // ------------------------------------------------------------------

    @Test
    fun `triple overlap produces correct busy union overlap and savings`() {
        val clock = FakeClock()
        val accumulator = TranslationLaneOverlapAccumulator(clock.nowNanos())

        accumulator.enter(TranslationTraceLane.NATIVE, clock.nowNanos())
        accumulator.enter(TranslationTraceLane.PROVIDER, clock.nowNanos())
        accumulator.enter(TranslationTraceLane.RENDER, clock.nowNanos())
        clock.advanceMs(1_000)
        val snapshot = accumulator.snapshot(clock.nowNanos())

        snapshot.nativeBusyMs shouldBe 1_000L
        snapshot.providerBusyMs shouldBe 1_000L
        snapshot.renderBusyMs shouldBe 1_000L
        snapshot.unionActiveMs shouldBe 1_000L
        snapshot.overlapMs shouldBe 1_000L
        snapshot.concurrencySavingsMs shouldBe 2_000L // (3-1) active lanes x 1s
        snapshot.workMs shouldBe 3_000L
    }

    @Test
    fun `overlapping and nested intervals settle correctly`() {
        val clock = FakeClock()
        val accumulator = TranslationLaneOverlapAccumulator(clock.nowNanos())

        // native: [0, 2000]; provider: [1000, 3000] -> 1000ms of 2-lane overlap.
        accumulator.enter(TranslationTraceLane.NATIVE, clock.nowNanos())
        clock.advanceMs(1_000)
        accumulator.enter(TranslationTraceLane.PROVIDER, clock.nowNanos())
        clock.advanceMs(1_000)
        accumulator.exit(TranslationTraceLane.NATIVE, clock.nowNanos())
        clock.advanceMs(1_000)
        accumulator.exit(TranslationTraceLane.PROVIDER, clock.nowNanos())

        accumulator.snapshot(clock.nowNanos()).let { snapshot ->
            snapshot.nativeBusyMs shouldBe 2_000L
            snapshot.providerBusyMs shouldBe 2_000L
            snapshot.unionActiveMs shouldBe 3_000L
            snapshot.overlapMs shouldBe 1_000L
            snapshot.concurrencySavingsMs shouldBe 1_000L
        }

        // Nested same-lane enters keep the lane busy for the union span only.
        accumulator.enter(TranslationTraceLane.RENDER, clock.nowNanos())
        clock.advanceMs(100)
        accumulator.enter(TranslationTraceLane.RENDER, clock.nowNanos())
        clock.advanceMs(200)
        accumulator.exit(TranslationTraceLane.RENDER, clock.nowNanos()) // inner exit: count 2 -> 1
        clock.advanceMs(100)
        accumulator.exit(TranslationTraceLane.RENDER, clock.nowNanos()) // outer exit: count 1 -> 0

        accumulator.snapshot(clock.nowNanos()).let { snapshot ->
            snapshot.renderBusyMs shouldBe 400L
            snapshot.unionActiveMs shouldBe 3_400L
        }
    }

    @Test
    fun `lane misuse is fail open and never throws`() {
        val clock = FakeClock()
        val accumulator = TranslationLaneOverlapAccumulator(clock.nowNanos())

        // Exit without enter: clamped, no exception, totals untouched.
        accumulator.exit(TranslationTraceLane.PROVIDER, clock.nowNanos())
        clock.advanceMs(500)
        accumulator.snapshot(clock.nowNanos()).providerBusyMs shouldBe 0L

        // Scheduler/storage lanes never participate in overlap accounting.
        accumulator.exit(TranslationTraceLane.SCHEDULER, clock.nowNanos())
        accumulator.enter(TranslationTraceLane.STORAGE, clock.nowNanos())
        clock.advanceMs(100)
        accumulator.snapshot(clock.nowNanos()).unionActiveMs shouldBe 0L
    }

    @Test
    fun `concurrent lane transitions stay internally consistent`() {
        val clock = FakeClock()
        val schedule = TranslationScheduleTrace(
            sid = "s",
            mode = TranslationTraceMode.AUTO,
            origin = TranslationTraceMode.AUTO,
            chapter = "c",
            pages = null,
            clock = clock,
            startNanos = 0,
        )
        val iterations = 2_000
        val nativeWorker = Thread {
            repeat(iterations) { schedule.enterLane(TranslationTraceLane.NATIVE).close() }
        }
        val providerWorker = Thread {
            repeat(iterations) { schedule.enterLane(TranslationTraceLane.PROVIDER).close() }
        }
        nativeWorker.start()
        providerWorker.start()
        nativeWorker.join()
        providerWorker.join()

        val lines = withCapture(detailed = false) {
            schedule.end(TranslationTraceOutcome.SUCCESS)
        }
        val scheduleEnd = lines.single { it.contains("event=schedule_end") }
        // Invariant: no lane can be busier than the wall clock it ran inside,
        // and balanced enter/exit pairs leave both lanes fully settled.
        val wallMs = scheduleEnd.substringAfter("wallMs=").substringBefore(' ').toLong()
        val nativeBusyMs = scheduleEnd.substringAfter("nativeBusyMs=").substringBefore(' ').toLong()
        val providerBusyMs = scheduleEnd.substringAfter("providerBusyMs=").substringBefore(' ').toLong()
        (nativeBusyMs >= 0) shouldBe true
        (providerBusyMs >= 0) shouldBe true
        (nativeBusyMs <= wallMs + 1) shouldBe true
        (providerBusyMs <= wallMs + 1) shouldBe true
    }

    @Test
    fun `repeated stage intervals sum instead of overwriting`() {
        val lines = withCapture(detailed = false) {
            val clock = FakeClock()
            val schedule = TranslationPipelineDiagnostics.startSchedule(TranslationTraceMode.MANUAL, clock = clock)
            val run = TranslationPipelineDiagnostics.startRun(schedule, pageIndex = 0, clock = clock)

            // First OCR attempt fails after 100ms, retry succeeds after 100ms.
            run.beginStage(TranslationTraceStage.OCR).let {
                clock.advanceMs(100)
                it.end(TranslationTraceOutcome.FAILURE)
            }
            run.beginStage(TranslationTraceStage.OCR).let {
                clock.advanceMs(100)
                it.end()
            }
            run.recordRetry()
            run.end(TranslationTraceOutcome.SUCCESS)
        }

        val runEnd = lines.single { it.contains("event=run_end") }
        runEnd.shouldContain("stageSumMs=200")
        runEnd.shouldContain("bottleneck=ocr bottleneckMs=200")
        runEnd.shouldContain("retries=1")
    }

    // ------------------------------------------------------------------
    // 8) Terminal idempotency and balanced lane tokens
    // ------------------------------------------------------------------

    @Test
    fun `terminal closes are idempotent and emit exactly one terminal event`() {
        val lines = withCapture(detailed = true) {
            val clock = FakeClock()
            val schedule = TranslationPipelineDiagnostics.startSchedule(TranslationTraceMode.MANUAL, clock = clock)
            val run = TranslationPipelineDiagnostics.startRun(schedule, pageIndex = 0, clock = clock)

            run.end(TranslationTraceOutcome.SUCCESS) shouldBe true
            run.end(TranslationTraceOutcome.FAILURE) shouldBe false // ignored

            schedule.end(TranslationTraceOutcome.SUCCESS) shouldBe true
            schedule.end(TranslationTraceOutcome.FAILURE) shouldBe false // ignored
            schedule.end(TranslationTraceOutcome.TIMEOUT) shouldBe false // ignored
        }

        lines.filter { it.contains("event=run_end") }.size shouldBe 1
        lines.filter { it.contains("event=schedule_end") }.size shouldBe 1
        lines.single { it.contains("event=run_end") }.shouldContain("outcome=success")
        lines.single { it.contains("event=schedule_end") }.shouldContain("outcome=success")
    }

    @Test
    fun `lane tokens exit exactly once and tolerate misuse`() {
        val lines = withCapture(detailed = true) {
            val clock = FakeClock()
            val schedule = TranslationPipelineDiagnostics.startSchedule(TranslationTraceMode.AUTO, clock = clock)

            val token = schedule.enterLane(TranslationTraceLane.NATIVE)
            clock.advanceMs(100)
            token.close()
            token.close() // double-exit: no-op
            token.close() // still a no-op

            // Exit of a never-entered lane through the schedule: fail-open.
            schedule.exitLane(TranslationTraceLane.RENDER)

            // A second lane remains accounted until the terminal snapshot.
            val provider = schedule.enterLane(TranslationTraceLane.PROVIDER)
            clock.advanceMs(50)
            provider.close()

            schedule.end(TranslationTraceOutcome.SUCCESS)
        }

        val scheduleEnd = lines.single { it.contains("event=schedule_end") }
        // NATIVE busy = 100ms exactly once; RENDER exit-without-enter adds nothing.
        scheduleEnd.shouldContain("nativeBusyMs=100")
        scheduleEnd.shouldContain("renderBusyMs=0")
        scheduleEnd.shouldContain("providerBusyMs=50")
    }

    @Test
    fun `schedule tracks slowest page and max queue wait online`() {
        val lines = withCapture(detailed = false) {
            val schedule = TranslationPipelineDiagnostics.startSchedule(
                TranslationTraceMode.BATCH,
                clock = FakeClock(),
            )

            val clockA = FakeClock()
            val pageA = TranslationPipelineDiagnostics.startRun(schedule, pageRaw = "a.jpg", pageIndex = 0, clock = clockA)
            pageA.beginStage(TranslationTraceStage.RENDER).let {
                clockA.advanceMs(120)
                it.end()
            }
            pageA.end(TranslationTraceOutcome.SUCCESS)

            val clockB = FakeClock()
            val pageB = TranslationPipelineDiagnostics.startRun(schedule, pageRaw = "b.jpg", pageIndex = 1, clock = clockB)
            pageB.beginStage(TranslationTraceStage.LEASE_WAIT).let {
                clockB.advanceMs(1_400)
                it.end()
            }
            pageB.end(TranslationTraceOutcome.SUCCESS)

            schedule.end(TranslationTraceOutcome.SUCCESS)
        }

        val scheduleEnd = lines.single { it.contains("event=schedule_end") }
        scheduleEnd.shouldContain("maxQueueMs=1400")
        // slowestPage tracks the opaque page token of the longest run.
        scheduleEnd.shouldContain("slowestPage=${keys.token('p', "b.jpg")}")
    }

    @Test
    fun `id generator emits stable process-prefixed opaque ids`() {
        val generator = TranslationTraceIdGenerator(processPrefix = "0000aa11-")
        generator.nextScheduleId() shouldBe "0000aa11-s1"
        generator.nextScheduleId() shouldBe "0000aa11-s2"
        generator.nextRunId() shouldBe "0000aa11-r1"
        generator.nextRunId() shouldBe "0000aa11-r2"
        generator.nextScheduleId() shouldBe "0000aa11-s3"

        // Random prefixes carry no content and contain no whitespace.
        repeat(16) {
            val prefix = TranslationTraceIdGenerator.newProcessPrefix()
            Regex("[0-9a-f]{8}-").matches(prefix) shouldBe true
        }
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
