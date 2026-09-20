package eu.kanade.translation.coexistence

import eu.kanade.translation.pipeline.PageWriteOrigin
import eu.kanade.translation.model.StageStatus
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * T917 Phase 4 D8 — graph coverage for the native occupancy watchdog and
 * honest single-page terminal outcomes. The harness uses real scheduler,
 * pipeline, quarantine, leases, and store; only its documented Android/IO
 * seams are faked.
 */
class D8StallWatchdogTest {
    private var harness: TranslationCoexistenceHarness? = null

    @AfterEach
    fun tearDown() {
        harness?.let {
            runCatching { it.removeGraphicsShims() }
            runCatching { it.close() }
        }
        harness = null
    }

    @Test
    fun `parked native lane emits stall, rejects new tap, then clears and admits next tap`() = runBlocking<Unit> {
        val h = TranslationCoexistenceHarness.create(
            pageKeys = listOf("p0", "p1"),
            stallThresholdMs = 100L,
            // Empty-start store (harness note): a pre-registered PENDING page
            // resume-skips the native phase before the decode seam and the
            // manual tap would never reach the parked stove.
            preRegisterInStore = false,
        )
        harness = h
        h.installGraphicsShims()
        h.registerReaderStream(TranslationCoexistenceHarness.CHAPTER_ID, "p0")
        h.registerReaderStream(TranslationCoexistenceHarness.CHAPTER_ID, "p1")
        h.barrier.arm(CoexistenceBarrier.BarrierPoint.NATIVE_ACQUIRE, "p0")
        try {
            h.tapManual("p0")
            val p0 = h.capturedManualJob("p0")
            h.barrier.awaitArrivalWithin(
                CoexistenceBarrier.BarrierPoint.NATIVE_ACQUIRE,
                "p0",
                TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS,
            )
            val stall = awaitStall(h)
            withClue("T917 D8 §2.2: stalled state names the still-occupied page") {
                stall.javaClass.getMethod("getPageKey").invoke(stall) shouldBe "p0"
            }

            // A different page must be refused before lease/native admission
            // while the stove is stalled. Its outcome is recorded by the real
            // scheduler and cannot silently disappear.
            h.tapManual("p1")
            val p1 = h.capturedManualJob("p1")
            withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) { p1.join() }
            val outcome = readManualOutcome(h, "${TranslationCoexistenceHarness.CHAPTER_ID}:p1")
            withClue("T917 D8 §2.2: a tap during native stall is typed Stalled") {
                if (outcome?.javaClass?.simpleName != "Stalled") {
                    throw AssertionError(
                        "T917 D8 RED defect: stall tap produced $outcome instead of SinglePageOutcome.Stalled",
                    )
                }
            }
            h.barrier.arrivalsOf(CoexistenceBarrier.BarrierPoint.NATIVE_ACQUIRE, "p1") shouldBe 0

            h.barrier.release(CoexistenceBarrier.BarrierPoint.NATIVE_ACQUIRE, "p0")
            withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) { p0.join() }
            withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
                readNativeStall(h).first { it == null }
            }

            // Once the real invocation exits, the next tap proceeds normally.
            h.tapManual("p1")
            // Event-driven oracle: the decode seam logs the arrival when the
            // retry's native phase really reaches the stove (capturedManualJob
            // would return the stale first-tap registration here), then the
            // store reaches the rendered terminal state.
            h.barrier.awaitArrivalWithin(
                CoexistenceBarrier.BarrierPoint.NATIVE_ACQUIRE,
                "p1",
                TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS,
            )
            withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
                h.store.state.first { it["p1"]?.renderStatus == StageStatus.READY }
            }
            h.barrier.arrivalsOf(CoexistenceBarrier.BarrierPoint.NATIVE_ACQUIRE, "p1") shouldBe 1
        } catch (e: NoSuchFieldException) {
            throw AssertionError(
                "T917 D8 RED defect: TranslationPipeline has no injectable native stall state seam",
                e,
            )
        }
    }

    @Test
    fun `native timeout reports typed failure and truthful timer while residual call rejects re tap`() = runBlocking<Unit> {
        val h = TranslationCoexistenceHarness.create(
            pageKeys = listOf("p0"),
            nativeTimeoutMs = 100L,
            preRegisterInStore = false,
        )
        harness = h
        h.installGraphicsShims()
        h.registerReaderStream(TranslationCoexistenceHarness.CHAPTER_ID, "p0")
        h.barrier.arm(CoexistenceBarrier.BarrierPoint.NATIVE_ACQUIRE, "p0")
        h.tapManual("p0")
        val first = h.capturedManualJob("p0")
        h.barrier.awaitArrivalWithin(
            CoexistenceBarrier.BarrierPoint.NATIVE_ACQUIRE,
            "p0",
            TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS,
        )

        // The quarantine timeout writes the honest FAILED placeholder with the
        // truthful timer WHILE the residual native invocation is still parked
        // inside the stove: the onTimeout callback runs before the quarantine's
        // late-exit wait, so this store observation pins the residual window.
        withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
            h.store.state.first { it["p0"]?.ocrStatus == "FAILED" }
        }
        val page = h.store.state.value.getValue("p0")
        withClue("T917 D8 §2.2 + Phase 5 D12: timeout writes a FAILED placeholder naming the ACTUAL timer (duration omitted)") {
            page.activeError?.contains("ONNX/native result timer expired") shouldBe true
        }

        // A same-page request during the residual window is typed Rejected
        // BEFORE queueing behind the parked stove. Driven through the real
        // pipeline boundary directly: the scheduler's anti-blink dedup would
        // silently swallow a second translatePage for an already-active job,
        // and the D8 typed-rejection contract lives at pipeline admission.
        val rejected = withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
            CoroutineScope(Dispatchers.IO).async {
                h.pipeline.translateSinglePage(
                    h.manga,
                    h.chapterFor(TranslationCoexistenceHarness.CHAPTER_ID),
                    h.source,
                    "p0",
                    force = false,
                    stageListener = null,
                    origin = PageWriteOrigin.MANUAL,
                )
            }.await()
        }
        withClue("T917 D8 §2.2: residual same-page request is typed Rejected") {
            rejected.javaClass.simpleName shouldBe "Rejected"
            rejected.javaClass.getMethod("getReason").invoke(rejected) shouldBe "page already translating"
        }

        // Native work is never killed: only the real exit (barrier release)
        // unwinds the quarantined job, and its terminal outcome is the typed
        // failure — never a silent Completed.
        h.barrier.release(CoexistenceBarrier.BarrierPoint.NATIVE_ACQUIRE, "p0")
        withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) { first.join() }

        val outcome = readManualOutcome(h, "${TranslationCoexistenceHarness.CHAPTER_ID}:p0")
        withClue("T917 D8 §2.2: native timeout cannot be reported as Completed") {
            if (outcome?.javaClass?.simpleName != "Failed") {
                throw AssertionError(
                    "T917 D8 RED defect: native timeout produced $outcome instead of SinglePageOutcome.Failed",
                )
            }
        }
    }

    @Test
    fun `normal native call below threshold never emits stall`() = runBlocking<Unit> {
        val h = TranslationCoexistenceHarness.create(
            pageKeys = listOf("p0"),
            stallThresholdMs = 500L,
        )
        harness = h
        h.installGraphicsShims()
        h.registerReaderStream(TranslationCoexistenceHarness.CHAPTER_ID, "p0")
        h.tapManual("p0")
        val job = h.capturedManualJob("p0")
        withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) { job.join() }
        try {
            withTimeout(TranslationCoexistenceHarness.NEGATIVE_PROBE_MS) {
                readNativeStall(h).first { it != null }
            }
            throw AssertionError("T917 D8 RED defect: sub-threshold native work emitted nativeStall")
        } catch (_: TimeoutCancellationException) {
            // Expected negative oracle.
        }
    }

    private suspend fun awaitStall(h: TranslationCoexistenceHarness): Any =
        withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
            readNativeStall(h).first { it != null }!!
        }

    private fun readNativeStall(h: TranslationCoexistenceHarness): kotlinx.coroutines.flow.StateFlow<Any?> {
        var cls: Class<*>? = h.pipeline.javaClass
        while (cls != null) {
            try {
                val field = cls.getDeclaredField("nativeStall")
                field.isAccessible = true
                return field.get(h.pipeline) as kotlinx.coroutines.flow.StateFlow<Any?>
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw AssertionError("T917 D8 RED defect: TranslationPipeline.nativeStall is missing")
    }

    private fun readManualOutcome(h: TranslationCoexistenceHarness, key: String): Any? {
        var cls: Class<*>? = h.scheduler.javaClass
        while (cls != null) {
            try {
                val field = cls.getDeclaredField("manualOutcomes")
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                return (field.get(h.scheduler) as Map<String, Any>)[key]
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw AssertionError("T917 D8 RED defect: scheduler manualOutcomes seam is missing")
    }
}
