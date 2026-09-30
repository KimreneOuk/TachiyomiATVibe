package eu.kanade.translation.pipeline.adaptive

import eu.kanade.translation.coexistence.TranslationCoexistenceHarness
import eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics
import eu.kanade.translation.diagnostics.TranslationStageSpan
import eu.kanade.translation.diagnostics.TranslationTraceClock
import eu.kanade.translation.diagnostics.TranslationTraceLane
import eu.kanade.translation.diagnostics.TranslationTraceMode
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceProvider
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

class AdaptiveKnobControllerTest {
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
            harness.pipeline.devicePagePermitGate.capacityLimit shouldBe AdaptiveKnobController.DEFAULT_FLOOR
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
    fun `floor samples exclude the cold start and revise a median window`() {
        val controller = AdaptiveKnobController(
            anchorWindowSize = 5,
            minimumAnchorSamples = 3,
        )

        repeat(3) { observe(controller, durationNanos = 1L) }
        controller.validAnchorSampleCount(DeviceActiveStage.DETECT) shouldBe 0
        controller.computedConcurrency shouldBe controller.floor

        listOf(20L, 50L, 100L).forEach { observe(controller, durationNanos = it) }
        controller.anchorFor(DeviceActiveStage.DETECT) shouldBe 50.0
        controller.validAnchorSampleCount(DeviceActiveStage.DETECT) shouldBe 3

        observe(controller, durationNanos = 10L)
        controller.anchorFor(DeviceActiveStage.DETECT) shouldBe 35.0
        observe(controller, durationNanos = 20L)
        controller.anchorFor(DeviceActiveStage.DETECT) shouldBe 20.0
        controller.computedConcurrency shouldBe 3
    }

    @Test
    fun `higher concurrency can back off but never revises the floor anchor`() {
        val controller = AdaptiveKnobController(
            anchorWindowSize = 3,
            minimumAnchorSamples = 3,
            coldStartProvisionalSamples = 0,
        )
        repeat(3) { observe(controller, durationNanos = 100L) }
        observe(controller, durationNanos = 100L)
        controller.computedConcurrency shouldBe 3
        val floorAnchor = controller.anchorFor(DeviceActiveStage.DETECT)
        val floorSampleCount = controller.validAnchorSampleCount(DeviceActiveStage.DETECT)

        controller.observe(
            observation(
                durationNanos = 200L,
                activeDevicePages = 2,
            ),
        )
        controller.computedConcurrency shouldBe 2
        controller.anchorFor(DeviceActiveStage.DETECT) shouldBe floorAnchor
        controller.validAnchorSampleCount(DeviceActiveStage.DETECT) shouldBe floorSampleCount

        controller.observe(
            observation(
                durationNanos = 1L,
                activeDevicePages = 2,
            ),
        )
        controller.computedConcurrency shouldBe 2
        controller.validAnchorSampleCount(DeviceActiveStage.DETECT) shouldBe floorSampleCount
    }

    @Test
    fun `thermal and memory pressure back off without contaminating anchors`() {
        val thermal = warmedController()
        val thermalAnchor = thermal.anchorFor(DeviceActiveStage.DETECT)
        thermal.observe(
            observation(
                durationNanos = 1_000L,
                thermalThrottleOnset = true,
            ),
        )
        thermal.computedConcurrency shouldBe 1
        thermal.anchorFor(DeviceActiveStage.DETECT) shouldBe thermalAnchor
        thermal.validAnchorSampleCount(DeviceActiveStage.DETECT) shouldBe 1

        val memory = warmedController()
        memory.observe(
            observation(
                durationNanos = 1_000L,
                memoryHeadroomAvailable = false,
            ),
        )
        memory.computedConcurrency shouldBe 1
        memory.validAnchorSampleCount(DeviceActiveStage.DETECT) shouldBe 1
    }

    @Test
    fun `durability interference does not move the device knob`() {
        val controller = warmedController()
        val anchor = controller.anchorFor(DeviceActiveStage.DETECT)
        controller.observe(
            observation(
                durationNanos = 10_000L,
                durabilityInterference = true,
            ),
        )

        controller.computedConcurrency shouldBe 2
        controller.anchorFor(DeviceActiveStage.DETECT) shouldBe anchor
        controller.validAnchorSampleCount(DeviceActiveStage.DETECT) shouldBe 1
    }

    @Test
    fun `out of memory resets the computed knob to floor`() {
        val controller = warmedController()
        val adapter = AdaptiveKnobSignalAdapter(controller, hasMemoryHeadroom = { true }, thermalStatus = { 0 })
        val clock = FakeClock()
        val schedule = TranslationPipelineDiagnostics.startSchedule(TranslationTraceMode.MANUAL, clock = clock)
        val run = TranslationPipelineDiagnostics.startRun(schedule, pageRaw = "adaptive-oom", pageIndex = 0, clock = clock)
        controller.computedConcurrency shouldBe 2
        try {
            record(
                run = run,
                clock = clock,
                stage = TranslationTraceStage.DETECT,
                durationNanos = 100L,
                units = 1L,
                outcome = TranslationTraceOutcome.FAILURE,
                error = OutOfMemoryError("device allocation failed"),
            )

            controller.computedConcurrency shouldBe controller.floor
        } finally {
            run.end(TranslationTraceOutcome.SUCCESS)
            adapter.close()
        }
    }

    @Test
    fun `trace adapter ignores remote work and rejects a durability-contaminated sample`() {
        val controller = AdaptiveKnobController()
        val adapter = AdaptiveKnobSignalAdapter(controller, hasMemoryHeadroom = { true }, thermalStatus = { 0 })
        val clock = FakeClock()
        val schedule = TranslationPipelineDiagnostics.startSchedule(TranslationTraceMode.MANUAL, clock = clock)
        val run = TranslationPipelineDiagnostics.startRun(schedule, pageRaw = "adaptive-test", pageIndex = 0, clock = clock)
        try {
            repeat(3) { record(run, clock, TranslationTraceStage.DETECT, durationNanos = 100L, units = 1L) }
            controller.validAnchorSampleCount(DeviceActiveStage.DETECT) shouldBe 0

            val deviceSpan = run.beginStage(
                stage = TranslationTraceStage.DETECT,
                lane = TranslationTraceLane.NATIVE,
                normalizationUnits = 1L,
            )
            val storageSpan = run.beginStage(
                stage = TranslationTraceStage.JOURNAL_TERMINAL_LAG,
                lane = TranslationTraceLane.STORAGE,
            )
            clock.advance(100L)
            storageSpan.end(TranslationTraceOutcome.PERSISTENCE_REJECTED)
            deviceSpan.end(TranslationTraceOutcome.SUCCESS)
            controller.validAnchorSampleCount(DeviceActiveStage.DETECT) shouldBe 0

            repeat(5) { record(run, clock, TranslationTraceStage.DETECT, durationNanos = 100L, units = 1L) }
            val computedAfterDeviceSamples = controller.computedConcurrency
            record(
                run,
                clock,
                TranslationTraceStage.TRANSLATE,
                lane = TranslationTraceLane.PROVIDER,
                provider = TranslationTraceProvider.REMOTE,
                durationNanos = 100_000L,
                units = 1_000L,
                outcome = TranslationTraceOutcome.FAILURE,
                error = IllegalStateException("HTTP 429 Too Many Requests"),
            )
            controller.computedConcurrency shouldBe computedAfterDeviceSamples
            controller.validAnchorSampleCount(DeviceActiveStage.TRANSLATE) shouldBe 0

            record(
                run,
                clock,
                TranslationTraceStage.TRANSLATE,
                lane = TranslationTraceLane.PROVIDER,
                provider = TranslationTraceProvider.LOCAL,
                durationNanos = 100L,
                units = 5L,
            )
            controller.validAnchorSampleCount(DeviceActiveStage.TRANSLATE) shouldBe 1
        } finally {
            run.end(TranslationTraceOutcome.SUCCESS)
            adapter.close()
        }
    }

    @Test
    fun `durability span completing after run terminal clears its active signal`() {
        val adapter = AdaptiveKnobSignalAdapter(
            AdaptiveKnobController(),
            hasMemoryHeadroom = { true },
            thermalStatus = { 0 },
        )
        val clock = FakeClock()
        val schedule = TranslationPipelineDiagnostics.startSchedule(TranslationTraceMode.MANUAL, clock = clock)
        val run = TranslationPipelineDiagnostics.startRun(schedule, pageRaw = "late-durability", pageIndex = 0, clock = clock)
        try {
            val span = run.beginStage(
                stage = TranslationTraceStage.JOURNAL_CREDIT_WAIT,
                lane = TranslationTraceLane.STORAGE,
            )
            adapter.snapshot().activeDurabilityStalls shouldBe 1

            run.end(TranslationTraceOutcome.SUCCESS)
            span.end(TranslationTraceOutcome.PERSISTENCE_REJECTED)

            adapter.snapshot().activeDurabilityStalls shouldBe 0
        } finally {
            run.end(TranslationTraceOutcome.SUCCESS)
            adapter.close()
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
        val controller = AdaptiveKnobController()
        repeat(
            AdaptiveKnobController.DEFAULT_COLD_START_PROVISIONAL_SAMPLES +
                AdaptiveKnobController.DEFAULT_MINIMUM_ANCHOR_SAMPLES +
                AdaptiveKnobController.DEFAULT_CEILING -
                AdaptiveKnobController.DEFAULT_FLOOR -
                1,
        ) {
            controller.observe(
                DeviceStageObservation(
                    stage = DeviceActiveStage.DETECT,
                    durationNanos = 100L,
                    normalizationUnits = 1L,
                    activeDevicePages = 1,
                    successful = true,
                ),
            )
        }
        controller.computedConcurrency shouldBe 4
        controller.appliedConcurrency shouldBe 1

        val gate = DevicePagePermitGate(capacity = { controller.appliedConcurrency })
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

    private fun warmedController(): AdaptiveKnobController = AdaptiveKnobController(
        anchorWindowSize = 3,
        minimumAnchorSamples = 1,
        coldStartProvisionalSamples = 0,
    ).also { controller ->
        observe(controller, durationNanos = 100L)
    }

    private fun observe(
        controller: AdaptiveKnobController,
        durationNanos: Long,
    ) = controller.observe(observation(durationNanos = durationNanos))

    private fun observation(
        durationNanos: Long,
        activeDevicePages: Int = 1,
        thermalThrottleOnset: Boolean = false,
        durabilityInterference: Boolean = false,
        memoryHeadroomAvailable: Boolean = true,
    ) = DeviceStageObservation(
        stage = DeviceActiveStage.DETECT,
        durationNanos = durationNanos,
        normalizationUnits = 1L,
        activeDevicePages = activeDevicePages,
        successful = true,
        thermalThrottleOnset = thermalThrottleOnset,
        durabilityInterference = durabilityInterference,
        memoryHeadroomAvailable = memoryHeadroomAvailable,
    )

    private fun record(
        run: eu.kanade.translation.diagnostics.TranslationRunTrace,
        clock: FakeClock,
        stage: TranslationTraceStage,
        lane: TranslationTraceLane = TranslationTraceLane.NATIVE,
        provider: TranslationTraceProvider = TranslationTraceProvider.NONE,
        durationNanos: Long,
        units: Long,
        outcome: TranslationTraceOutcome = TranslationTraceOutcome.SUCCESS,
        error: Throwable? = null,
    ) {
        val span = run.beginStage(stage = stage, lane = lane, provider = provider, normalizationUnits = units)
        clock.advance(durationNanos)
        span.end(outcome, error = error)
    }

    private class FakeClock(var nowNanos: Long = 0L) : TranslationTraceClock {
        override fun nowNanos(): Long = nowNanos

        fun advance(nanos: Long) {
            nowNanos += nanos
        }
    }
}
