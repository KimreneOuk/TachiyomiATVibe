package eu.kanade.translation.coexistence

import eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics
import eu.kanade.translation.diagnostics.TranslationStageSpan
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceMode
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceStage
import eu.kanade.translation.diagnostics.TranslationTraceStageObserver
import eu.kanade.translation.diagnostics.TranslationTraceStageObserverRegistry
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.persistence.chapter.PageWriteOrigin
import eu.kanade.translation.pipeline.adaptive.DevicePagePermitGate
import eu.kanade.translation.pipeline.adaptive.DevicePermitPriority
import eu.kanade.translation.pipeline.execution.SinglePageOutcome
import eu.kanade.translation.pipeline.execution.TranslationStageEvent
import eu.kanade.translation.pipeline.execution.TranslationStageListener
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

/** Contract test for the device gate's existing post-provider render boundary. */
class RenderPermitReacquisitionTest {

    @Test
    fun singlePageWaitsForDevicePermitAgainBeforeRendering() = runBlocking<Unit> {
        val harness = TranslationCoexistenceHarness.create(
            pageKeys = listOf("p0"),
            preRegisterInStore = false,
        )
        try {
            harness.installGraphicsShims()
            harness.registerReaderStream(harness.CHAPTER_ID, "p0")
            val chapter = harness.chapterFor(harness.CHAPTER_ID)
            val gate = harness.pipeline.devicePagePermitGate
            val traceSchedule = TranslationPipelineDiagnostics.startSchedule(
                mode = TranslationTraceMode.MANUAL,
                chapterRaw = "chapter",
                pages = 1,
            )
            val traceRun = TranslationPipelineDiagnostics.startRun(
                schedule = traceSchedule,
                pageRaw = "p0",
                pageIndex = 0,
            )
            val admitted = CompletableDeferred<Pair<Boolean, Int>>()
            val permitWaitStarted = CompletableDeferred<Int>()
            val permitWaitCompleted = AtomicBoolean(false)
            val actualRenderEntry = CompletableDeferred<Triple<Int, Int, Boolean>>()
            val stageObserver = TranslationTraceStageObserverRegistry.register(
                object : TranslationTraceStageObserver {
                    override fun onStageStarted(span: TranslationStageSpan) {
                        when (span.stage) {
                            TranslationTraceStage.RENDER_PERMIT_WAIT -> permitWaitStarted.complete(gate.activeCount)
                            TranslationTraceStage.RENDER ->
                                actualRenderEntry.complete(
                                    Triple(gate.activeCount, span.items, permitWaitCompleted.get()),
                                )
                            else -> Unit
                        }
                    }

                    override fun onStageCompleted(
                        span: TranslationStageSpan,
                        durationNanos: Long,
                        outcome: TranslationTraceOutcome,
                        error: Throwable?,
                        normalizationUnits: Long,
                    ) {
                        if (span.stage == TranslationTraceStage.RENDER_PERMIT_WAIT) {
                            permitWaitCompleted.set(true)
                        }
                    }
                },
            )
            var heldPermit: DevicePagePermitGate.Permit? = null
            val blockerReleased = AtomicBoolean(false)
            val result = CompletableDeferred<SinglePageOutcome>()
            harness.barrier.arm(CoexistenceBarrier.BarrierPoint.PROVIDER_START, "p0")
            harness.barrier.arm(CoexistenceBarrier.BarrierPoint.PROVIDER_END, "p0")

            val job = launch(Dispatchers.IO + TranslationTrace.elementFor(traceRun)) {
                try {
                    result.complete(
                        harness.pipeline.translateSinglePage(
                            manga = harness.manga,
                            chapter = chapter,
                            source = harness.source,
                            pageKey = "p0",
                            force = false,
                            stageListener = TranslationStageListener { _, stage ->
                                if (stage == TranslationStageEvent.RENDERING) {
                                    admitted.complete(blockerReleased.get() to gate.activeCount)
                                }
                            },
                            origin = PageWriteOrigin.MANUAL,
                        ),
                    )
                } catch (error: Throwable) {
                    result.completeExceptionally(error)
                }
            }

            try {
                harness.barrier.awaitArrivalWithin(
                    CoexistenceBarrier.BarrierPoint.PROVIDER_START,
                    "p0",
                    TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS,
                )
                heldPermit = requireNotNull(gate.acquire(DevicePermitPriority.BACKGROUND))
                harness.barrier.release(CoexistenceBarrier.BarrierPoint.PROVIDER_START, "p0")
                harness.barrier.awaitArrivalWithin(
                    CoexistenceBarrier.BarrierPoint.PROVIDER_END,
                    "p0",
                    TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS,
                )
                harness.barrier.release(CoexistenceBarrier.BarrierPoint.PROVIDER_END, "p0")
                withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
                    harness.store.state.first { it["p0"]?.renderStatus == StageStatus.RUNNING }
                }
                // The live store publication and admission callback complete while
                // the blocker is still the only device-permit owner.
                val admissionState = withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
                    admitted.await()
                }
                admissionState shouldBe (false to 1)
                val permitWaitOwnerCount = withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
                    permitWaitStarted.await()
                }
                permitWaitOwnerCount shouldBe 1
                gate.activeCount shouldBe 1
                val renderBeforeRelease = withTimeoutOrNull(TranslationCoexistenceHarness.NEGATIVE_PROBE_MS) {
                    actualRenderEntry.await()
                }
                renderBeforeRelease shouldBe null

                blockerReleased.set(true)
                heldPermit?.release()
                val renderEntry = withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
                    actualRenderEntry.await()
                }
                // The zero-block RENDER span starts around the estimator only
                // after the pipeline has reacquired the device permit.
                renderEntry shouldBe Triple(1, 0, true)
                withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) { job.join() }
                gate.activeCount shouldBe 0
                (result.await() is SinglePageOutcome.Completed) shouldBe true
            } finally {
                blockerReleased.set(true)
                heldPermit?.release()
                harness.barrier.release(CoexistenceBarrier.BarrierPoint.PROVIDER_START, "p0")
                harness.barrier.release(CoexistenceBarrier.BarrierPoint.PROVIDER_END, "p0")
                job.cancelAndJoin()
                stageObserver.close()
                traceRun.end(TranslationTraceOutcome.CANCELLED)
                traceSchedule.end(TranslationTraceOutcome.CANCELLED)
            }
        } finally {
            harness.close()
        }
    }
}
