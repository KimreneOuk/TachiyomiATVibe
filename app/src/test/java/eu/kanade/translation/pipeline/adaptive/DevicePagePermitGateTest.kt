package eu.kanade.translation.pipeline.adaptive

import eu.kanade.translation.coexistence.TranslationCoexistenceHarness
import eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics
import eu.kanade.translation.diagnostics.TranslationStageSpan
import eu.kanade.translation.diagnostics.TranslationTraceClock
import eu.kanade.translation.diagnostics.TranslationTraceLane
import eu.kanade.translation.diagnostics.TranslationTraceMode
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceStage
import eu.kanade.translation.diagnostics.TranslationTraceStageObserver
import eu.kanade.translation.diagnostics.TranslationTraceStageObserverRegistry
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test

class DevicePagePermitGateTest {
    @Test
    fun `pipeline native attachment immediately acquires an idle floor permit`() = runTest {
        val harness = TranslationCoexistenceHarness.create(pageKeys = listOf("p0"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var result: String? = null
        var worker: Job? = null
        try {
            val running = launch(start = CoroutineStart.UNDISPATCHED) {
                result = harness.pipeline.withNativeLane(
                    timeoutMs = 10_000L,
                    chapterId = harness.CHAPTER_ID,
                    chapterName = "adaptive-floor",
                    pageKey = "p0",
                    onTimeout = {},
                ) {
                    entered.complete(Unit)
                    release.await()
                    "admitted"
                }
            }
            worker = running
            withTimeout(10_000L) { entered.await() }
            harness.pipeline.devicePagePermitGate.capacityLimit shouldBe DevicePagePermitGate.DEFAULT_CAPACITY
            harness.pipeline.devicePagePermitGate.activeCount shouldBe 1
            harness.pipeline.devicePagePermitGate.queuedCount shouldBe 0
            release.complete(Unit)
            running.join()
            harness.pipeline.devicePagePermitGate.activeCount shouldBe 0
            result shouldBe "admitted"
        } finally {
            release.complete(Unit)
            worker?.cancelAndJoin()
            harness.close()
        }
    }

    @Test
    fun `source bounds update normalization without resetting trace duration`() {
        var completion: Pair<Long, Long>? = null
        val registration = TranslationTraceStageObserverRegistry.register(
            object : TranslationTraceStageObserver {
                override fun onStageStarted(span: TranslationStageSpan) = Unit

                override fun onStageCompleted(
                    span: TranslationStageSpan,
                    durationNanos: Long,
                    outcome: TranslationTraceOutcome,
                    error: Throwable?,
                    normalizationUnits: Long,
                ) {
                    if (span.stage == TranslationTraceStage.SOURCE_DECODE) {
                        completion = durationNanos to normalizationUnits
                    }
                }
            },
        )
        val clock = FakeClock(nowNanos = 1_000L)
        val schedule = TranslationPipelineDiagnostics.startSchedule(TranslationTraceMode.MANUAL, clock = clock)
        val run = TranslationPipelineDiagnostics.startRun(schedule, pageRaw = "predecode-bounds", pageIndex = 0, clock = clock)
        try {
            val span = run.beginStage(stage = TranslationTraceStage.SOURCE_DECODE, lane = TranslationTraceLane.NATIVE)
            clock.advance(25L)
            // E3 span boundaries are a measurement contract; changes require an E4/E13 re-baseline decision.
            // Bind pre-decode bounds without resetting that original duration boundary.
            span.setNormalizationUnits(DeviceStageNormalization.sourcePixels(width = 100, height = 50))
            run.sourcePixels shouldBe 5_000L
            clock.advance(75L)
            span.end(TranslationTraceOutcome.SUCCESS)

            completion shouldBe (100L to 5_000L)
        } finally {
            run.end(TranslationTraceOutcome.SUCCESS)
            registration.close()
        }
    }

    @Test
    fun `foreground permit jumps queued background work`() = runTest {
        val gate = DevicePagePermitGate()
        val initialPermit = gate.acquire(DevicePermitPriority.BACKGROUND)!!
        val order = mutableListOf<String>()
        val backgroundEntered = CompletableDeferred<Unit>()
        val foregroundEntered = CompletableDeferred<Unit>()
        val holdForeground = CompletableDeferred<Unit>()

        val background = launch {
            gate.withPermit(DevicePermitPriority.BACKGROUND) {
                order += "background"
                backgroundEntered.complete(Unit)
            }
        }
        runCurrent()
        val foreground = launch {
            gate.withPermit(DevicePermitPriority.FOREGROUND) {
                order += "foreground"
                foregroundEntered.complete(Unit)
                holdForeground.await()
            }
        }
        runCurrent()
        gate.queuedCount shouldBe 2

        initialPermit.release()
        foregroundEntered.await()
        order shouldBe listOf("foreground")
        holdForeground.complete(Unit)
        foreground.join()
        backgroundEntered.await()
        background.join()
        order shouldBe listOf("foreground", "background")
    }

    @Test
    fun `foreground wait cap expires without leaking its queued waiter`() = runTest {
        val gate = DevicePagePermitGate(foregroundWaitCapMs = 10L)
        val backgroundPermit = gate.acquire(DevicePermitPriority.BACKGROUND)!!
        val foreground = async { gate.acquire(DevicePermitPriority.FOREGROUND) }
        runCurrent()
        gate.queuedCount shouldBe 1

        advanceTimeBy(10L)
        runCurrent()
        foreground.await() shouldBe null
        gate.queuedCount shouldBe 0
        gate.activeCount shouldBe 1
        backgroundPermit.release()
        gate.activeCount shouldBe 0
    }

    @Test
    fun `cancelled waiter is removed and permit release remains usable`() = runTest {
        val gate = DevicePagePermitGate()
        val initialPermit = gate.acquire(DevicePermitPriority.BACKGROUND)!!
        val cancelled = launch { gate.acquire(DevicePermitPriority.BACKGROUND) }
        runCurrent()
        gate.queuedCount shouldBe 1

        cancelled.cancelAndJoin()
        gate.queuedCount shouldBe 0
        initialPermit.release()
        val next = gate.acquire(DevicePermitPriority.BACKGROUND)!!
        next.release()
        gate.activeCount shouldBe 0
    }

    @Test
    fun `remote work releases and later reacquires a device permit`() = runTest {
        val gate = DevicePagePermitGate()
        val devicePermit = gate.acquire(DevicePermitPriority.BACKGROUND)!!
        val remoteWorkComplete = CompletableDeferred<Unit>()
        val reacquired = async {
            remoteWorkComplete.await()
            gate.acquire(DevicePermitPriority.BACKGROUND)!!
        }

        devicePermit.release()
        runCurrent()
        gate.activeCount shouldBe 0
        val foregroundPermit = gate.acquire(DevicePermitPriority.FOREGROUND)!!
        remoteWorkComplete.complete(Unit)
        runCurrent()
        gate.queuedCount shouldBe 1

        foregroundPermit.release()
        val renderPermit = reacquired.await()
        gate.activeCount shouldBe 1
        renderPermit.release()
        gate.activeCount shouldBe 0
    }

    @Test
    fun `background-only traffic keeps fifo order at pinned floor`() = runTest {
        val gate = DevicePagePermitGate()
        val firstPermit = gate.acquire(DevicePermitPriority.BACKGROUND)!!
        val order = mutableListOf<Int>()
        val firstQueued = async {
            gate.withPermit(DevicePermitPriority.BACKGROUND) {
                order += 1
                yield()
            }
        }
        runCurrent()
        val secondQueued = async {
            gate.withPermit(DevicePermitPriority.BACKGROUND) {
                order += 2
                yield()
            }
        }
        runCurrent()
        firstPermit.release()
        firstQueued.await()
        secondQueued.await()

        order shouldBe listOf(1, 2)
        gate.activeCount shouldBe 0
    }

    private class FakeClock(var nowNanos: Long = 0L) : TranslationTraceClock {
        override fun nowNanos(): Long = nowNanos

        fun advance(nanos: Long) {
            nowNanos += nanos
        }
    }
}
