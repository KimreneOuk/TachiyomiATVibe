package eu.kanade.translation.translator

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 *  DR-C/DR-D Stage 5: the nested Batch sub-limit bucket. ONE credential
 * wide 15-RPM allowance under the shared provider bucket (bucket 1 is NOT the
 * sub-limit — its interactive reserve, starvation guard and cooldowns are
 * untouched); pacing comes from the rolling window, never a forced sleep.
 */
class ProviderGovernorBatchSublimitTest {

    @Test
    fun `batch admits fifteen per rolling window and defers the sixteenth without sleeping`() = runTest {
        val clock = FakeClock()
        val gate = BatchRequestSublimitGate(clock)
        val metadata = metadata()

        repeat(BatchProviderSublimit.BATCH_REQUESTS_PER_MINUTE) {
            gate.executeBatch(metadata) { "ok" }
        }

        // Window pacing only: not one forced sleep on the admission path.
        clock.delays.shouldBeEmpty()

        val deferred = runCatching { gate.executeBatch(metadata) { "ok" } }
            .exceptionOrNull().shouldBeInstanceOf<ProviderRequestPausedException>()
        deferred.reason shouldContain "Batch sub-limit deferred"
        deferred.nextEligibleRetryAtEpochMs shouldBe 60_000L

        // Still no sleeps: the deferral is immediate, never a spin.
        clock.delays.shouldBeEmpty()

        // Sliding the window frees the allowance: burst tolerance, not a timer.
        clock.now = 60_000L
        gate.executeBatch(metadata) { "recovered" } shouldBe "recovered"
        clock.delays.shouldBeEmpty()
    }

    @Test
    fun `sublimit bucket is credential wide and model agnostic`() = runTest {
        val clock = FakeClock()
        val gate = BatchRequestSublimitGate(clock)

        // DR-D key derivation: the model dimension is dropped, backend +
        // credential scope are kept — one pool per credential, never two 15s.
        val key = ProviderRequestKey("gemini", "gemini-2.5-flash", "cred-1")
        BatchProviderSublimit.batchSublimitKey(key) shouldBe
            ProviderRequestKey("gemini", null, "cred-1")

        val flash = metadata(ProviderRequestKey("gemini", "flash", "cred-1"))
        val pro = metadata(ProviderRequestKey("gemini", "pro", "cred-1"))

        repeat(8) { gate.executeBatch(flash) { "ok" } }
        repeat(7) { gate.executeBatch(pro) { "ok" } }

        // Mixed models share the same 15: the 16th request on ANY model defers.
        runCatching { gate.executeBatch(flash) { "ok" } }
            .exceptionOrNull().shouldBeInstanceOf<ProviderRequestPausedException>()

        // A different credential is a DIFFERENT bucket.
        gate.executeBatch(metadata(ProviderRequestKey("gemini", "flash", "cred-2"))) { "other" } shouldBe "other"
    }

    @Test
    fun `interactive requests never enter the batch sublimit bucket`() = runTest {
        val clock = FakeClock()
        val gate = BatchRequestSublimitGate(clock)
        val shared = ProviderRequestGovernor(
            policy = { ProviderRequestGovernor.defaultPolicy(it) },
            clock = clock,
        )

        // Saturate the Batch sub-limit window completely.
        val batchMetadata = metadata()
        repeat(BatchProviderSublimit.BATCH_REQUESTS_PER_MINUTE) {
            gate.executeBatch(batchMetadata) { "ok" }
        }

        // The reader's INTERACTIVE request rides bucket 1 with its own policy:
        // full headroom, zero waits — the saturated bucket-2 window cannot
        // touch it (structural skip, not a polite favor).
        val reader = shared.executeValue(
            metadata(ProviderRequestKey("gemini", "flash", "cred-1"))
                .copy(priority = AdmissionPriority.INTERACTIVE, operation = "reader"),
        ) { "page" }
        reader shouldBe "page"
        clock.delays.shouldBeEmpty()
    }

    @Test
    fun `sublimit permit is released when the inner provider bucket defers`() = runTest {
        val clock = FakeClock()
        val gate = BatchRequestSublimitGate(clock)
        val metadata = metadata()

        // DR-D all-or-nothing: bucket 2 granted, then the inner (bucket 1)
        // work pauses — the sub-limit permit must be given back BEFORE the
        // pause propagates, or maxInFlight=1 would wedge all Batch traffic.
        val first = runCatching {
            gate.executeBatch(metadata) {
                throw ProviderRequestPausedException(
                    key = metadata.key,
                    nextEligibleRetryAtEpochMs = 5_000L,
                    reason = "provider bucket deferred",
                )
            }
        }.exceptionOrNull().shouldBeInstanceOf<ProviderRequestPausedException>()
        first.reason shouldBe "provider bucket deferred"

        // The gate still admits: no permit leaked.
        gate.executeBatch(metadata) { "retried" } shouldBe "retried"
    }

    private fun metadata(
        key: ProviderRequestKey = ProviderRequestKey("gemini", "flash", "cred-1"),
    ) = ProviderRequestMetadata(
        key = key,
        estimatedInputTokens = 100,
        reservedOutputTokens = 50,
        operation = "analysis_chunk",
    )

    private class FakeClock(
        var now: Long = 0L,
    ) : ProviderRequestClock {
        val delays = mutableListOf<Long>()

        override fun nowEpochMs(): Long = now

        override suspend fun delay(millis: Long) {
            delays += millis
            now += millis
        }
    }
}
