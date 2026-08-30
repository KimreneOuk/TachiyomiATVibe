package eu.kanade.translation.translator
import eu.kanade.translation.translator.retry.withTranslationRetry
import eu.kanade.translation.translator.retry.classifyHttpFailure
import eu.kanade.translation.translator.retry.RetryAfterParser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException

class ProviderRequestGovernorTest {

    @Test
    fun `rolling request and token reservations pace a shared bucket`() = runTest {
        val clock = FakeClock()
        val governor = governor(clock) {
            it.copy(
                requestsPerMinute = 10,
                tokensPerMinute = 20,
                minimumSpacingMs = 100,
                maxForegroundWaitMs = 1_000,
                pollIntervalMs = 10,
            )
        }
        val metadata = metadata(input = 3, output = 2)

        governor.executeValue(metadata) { "batch" }
        governor.executeValue(metadata.copy(priority = AdmissionPriority.INTERACTIVE)) { "reader" }
        governor.executeValue(metadata) { "auto" }

        clock.now shouldBe 200L
        clock.delays.sum() shouldBe 200L
    }

    @Test
    fun `different provider keys do not consume one another quota`() = runTest {
        val clock = FakeClock()
        val governor = governor(clock) {
            it.copy(minimumSpacingMs = 100, maxForegroundWaitMs = 200, pollIntervalMs = 10)
        }
        governor.executeValue(metadata(key = ProviderRequestKey("gemini", "flash", "one"))) { Unit }
        governor.executeValue(metadata(key = ProviderRequestKey("openrouter", "flash", "one"))) { Unit }

        clock.now shouldBe 0L
        clock.delays shouldBe emptyList()
    }

    @Test
    fun `provider usage reconciles the reserved token cost`() = runTest {
        val clock = FakeClock()
        val events = mutableListOf<ProviderAdmissionEvent>()
        val governor = ProviderRequestGovernor(
            policy = { ProviderQuotaPolicy(minimumSpacingMs = 0) },
            clock = clock,
            diagnostics = ProviderRequestDiagnostics { events += it },
        )

        val result = governor.execute(metadata(input = 10, output = 10)) {
            ProviderHttpResult(
                value = "ok",
                usage = ProviderUsage(inputTokens = 2, outputTokens = 3),
            )
        }

        result shouldBe "ok"
        events.last().actualTokens shouldBe 5
    }

    @Test
    fun `retry after parser accepts fractional seconds and http dates`() {
        RetryAfterParser.parseMillis("1.5", nowEpochMs = 0) shouldBe 1_500L
        RetryAfterParser.parseMillis("-2", nowEpochMs = 0) shouldBe 0L
        RetryAfterParser.parseMillis("not-a-date", nowEpochMs = 0) shouldBe null
        RetryAfterParser.parseMillis("Thu, 01 Jan 1970 00:00:02 GMT", nowEpochMs = 0) shouldBe 2_000L
    }

    @Test
    fun `long cooldown is deferred instead of sleeping past foreground budget`() = runTest {
        val clock = FakeClock()
        val governor = governor(clock) {
            it.copy(maxForegroundWaitMs = 100, pollIntervalMs = 10)
        }
        val metadata = metadata()
        governor.recordFailure(
            metadata,
            classifyHttpFailure(
                backend = "gemini",
                statusCode = 429,
                retryAfterHeader = "3600",
                nowEpochMs = clock.nowEpochMs(),
            ),
        )

        val decision = governor.admit(metadata)
        decision::class shouldBe ProviderAdmissionDecision.Deferred::class
        clock.now shouldBe 0L
        clock.delays shouldBe emptyList()
    }

    @Test
    fun `transport retries reenter the governor for every attempt`() = runTest {
        val clock = FakeClock()
        val events = mutableListOf<ProviderAdmissionEvent>()
        val governor = ProviderRequestGovernor(
            policy = { ProviderQuotaPolicy(minimumSpacingMs = 25, pollIntervalMs = 5) },
            clock = clock,
            diagnostics = ProviderRequestDiagnostics { events += it },
        )
        var calls = 0
        val result = withTranslationRetry(
            maxAttempts = 3,
            baseDelayMs = 10,
            logTag = "gemini",
            clock = clock,
            requestMetadata = metadata(),
            governor = governor,
        ) {
            calls++
            if (calls < 3) throw IOException("temporary network drop")
            "ok"
        }

        result shouldBe "ok"
        calls shouldBe 3
        events.count { it.outcome == "admitted" } shouldBe 3
        clock.now shouldBe 50L
    }

    @Test
    fun `transport helper turns a long retry hint into a typed pause`() = runTest {
        val clock = FakeClock()
        val governor = governor(clock)
        var calls = 0

        val thrown = runCatching {
            withTranslationRetry(
                maxAttempts = 3,
                logTag = "gemini",
                clock = clock,
                requestMetadata = metadata(),
                governor = governor,
            ) {
                calls++
                throw ProviderFailureException(
                    classifyHttpFailure(
                        backend = "gemini",
                        statusCode = 429,
                        retryAfterHeader = "3600",
                        nowEpochMs = clock.nowEpochMs(),
                    ),
                )
            }
        }.exceptionOrNull()

        thrown.shouldBeInstanceOf<ProviderRequestPausedException>()
        calls shouldBe 1
        clock.delays shouldBe emptyList()
    }

    private fun governor(
        clock: FakeClock,
        policy: (ProviderQuotaPolicy) -> ProviderQuotaPolicy = { it },
    ) = ProviderRequestGovernor(
        policy = { policy(ProviderQuotaPolicy()) },
        clock = clock,
    )

    private fun metadata(
        key: ProviderRequestKey = ProviderRequestKey("gemini", "flash", "one"),
        input: Int = 1,
        output: Int = 1,
    ) = ProviderRequestMetadata(
        key = key,
        estimatedInputTokens = input,
        reservedOutputTokens = output,
        operation = "test",
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
