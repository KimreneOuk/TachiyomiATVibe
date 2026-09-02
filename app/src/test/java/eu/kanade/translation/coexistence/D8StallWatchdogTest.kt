package eu.kanade.translation.coexistence

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.TimeoutCancellationException
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
    fun `parked native lane emits stall, rejects new tap, then clears and admits next tap`() = runBlocking {
        val h = TranslationCoexistenceHarness.create(
            pageKeys = listOf("p0", "p1"),
            stallThresholdMs = 100L,
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
            val retry = h.capturedManualJob("p1")
            withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) { retry.join() }
            h.barrier.arrivalsOf(CoexistenceBarrier.BarrierPoint.NATIVE_ACQUIRE, "p1") shouldBe 1
        } catch (e: NoSuchFieldException) {
            throw AssertionError(
                "T917 D8 RED defect: TranslationPipeline has no injectable native stall state seam",
                e,
            )
        }
    }

    @Test
    fun `native timeout reports typed failure and truthful timer while residual call rejects re tap`() = runBlocking {
        val h = TranslationCoexistenceHarness.create(
            pageKeys = listOf("p0"),
            nativeTimeoutMs = 100L,
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
        withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) { first.join() }

        val outcome = readManualOutcome(h, "${TranslationCoexistenceHarness.CHAPTER_ID}:p0")
        withClue("T917 D8 §2.2: native timeout cannot be reported as Completed") {
            if (outcome?.javaClass?.simpleName == "Completed" || outcome == null) {
                throw AssertionError(
                    "T917 D8 RED defect: native timeout produced $outcome instead of a typed failure",
                )
            }
        }
        val page = h.store.state.value.getValue("p0")
        withClue("T917 D8 §2.2: timeout writes a FAILED placeholder") {
            page.ocrStatus shouldBe "FAILED"
            page.activeError?.contains("100") shouldBe true
        }

        // NativeRunQuarantine retains the admission lock until the fake native
        // call really exits. A re-tap must therefore be an honest rejection,
        // not another silent Completed.
        h.tapManual("p0")
        val retry = h.capturedManualJob("p0")
        withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) { retry.join() }
        val retryOutcome = readManualOutcome(h, "${TranslationCoexistenceHarness.CHAPTER_ID}:p0")
        withClue("T917 D8 §2.2: residual native occupancy rejects a re-tap") {
            if (retryOutcome?.javaClass?.simpleName != "Rejected") {
                throw AssertionError("T917 D8 RED defect: residual re-tap produced $retryOutcome")
            }
            val reason = retryOutcome.javaClass.getMethod("getReason").invoke(retryOutcome)
            reason shouldBe "page already translating"
        }
        h.barrier.release(CoexistenceBarrier.BarrierPoint.NATIVE_ACQUIRE, "p0")
    }

    @Test
    fun `normal native call below threshold never emits stall`() = runBlocking {
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
