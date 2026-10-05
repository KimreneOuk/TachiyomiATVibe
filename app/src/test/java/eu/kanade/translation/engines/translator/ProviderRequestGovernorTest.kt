package eu.kanade.translation.engines.translator
import eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceClock
import eu.kanade.translation.diagnostics.TranslationTraceMode
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceSink
import eu.kanade.translation.engines.translator.retry.RetryAfterParser
import eu.kanade.translation.engines.translator.retry.classifyHttpFailure
import eu.kanade.translation.engines.translator.retry.withTranslationRetry
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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

    @Test
    fun `contextual operation records the provider translation span`() = runTest {
        val lines = captureTrace { clock ->
            val governor = ProviderRequestGovernor(
                policy = { ProviderQuotaPolicy(minimumSpacingMs = 0) },
                clock = clock,
            )
            governor.executeValue(metadata().copy(operation = "contextual")) {
                clock.advanceMs(31)
                "ok"
            }
        }

        val translateSpans = lines.filter {
            it.contains("event=stage_end ") && it.contains("stage=translate")
        }
        translateSpans.size shouldBe 1
        listOf(
            translateSpans.singleOrNull()?.contains("durationMs=31") == true,
            translateSpans.singleOrNull()?.contains("outcome=success") == true,
            lines.any { it.contains("stage=provider_governor_wait") },
        ) shouldBe listOf(true, true, true)
    }

    @Test
    fun `http telemetry correlates attempts and preserves actual usage provenance`() = runTest {
        val lines = captureTrace { clock ->
            val governor = ProviderRequestGovernor(
                policy = { ProviderQuotaPolicy(minimumSpacingMs = 0) },
                clock = clock,
            )
            governor.execute(
                metadata(input = 11, output = 32).copy(
                    operation = "contextual",
                    envelopeId = "known-envelope-secret",
                    attempt = 2,
                ),
            ) {
                clock.advanceMs(17)
                ProviderHttpResult(
                    value = "known-body-sentinel",
                    usage = ProviderUsage(inputTokens = 4, outputTokens = 3),
                )
            }
            governor.execute(
                metadata(input = 7, output = 19).copy(
                    operation = "contextual",
                    envelopeId = "unknown-envelope-secret",
                    attempt = 1,
                ),
            ) {
                ProviderHttpResult(value = "unknown-body-sentinel")
            }
            runCatching {
                governor.execute<String>(
                    metadata().copy(operation = "contextual", envelopeId = "failure-envelope", attempt = 1),
                ) {
                    throw IOException("raw failure sentinel")
                }
            }
            runCatching {
                governor.execute<String>(
                    metadata().copy(operation = "contextual", envelopeId = "cancel-envelope", attempt = 1),
                ) {
                    throw CancellationException("cancel sentinel")
                }
            }
        }

        val requests = lines.filter { it.contains("requestId=") && it.contains("operation=contextual") }
        val known = requests.singleOrNull { it.contains("attempt=2") }
        val unknown = requests.singleOrNull {
            it.contains("estimatedInputTokens=7") && it.contains("reservedOutputTokens=19")
        }
        val failure = requests.singleOrNull { it.contains("outcome=failure") }
        val cancelled = requests.singleOrNull { it.contains("outcome=cancelled") }
        val checks = listOf(
            known?.contains("requestId=none") == false,
            known?.contains("envelope=none") == false,
            known?.contains("durationMs=17") == true,
            known?.contains("outcome=success") == true,
            known?.contains("estimatedInputTokens=11") == true,
            known?.contains("reservedOutputTokens=32") == true,
            known?.contains("inputTokens=4") == true,
            known?.contains("outputTokens=3") == true,
            known?.contains("usageSource=provider") == true,
            unknown?.contains("inputTokens=none") == true,
            unknown?.contains("outputTokens=none") == true,
            unknown?.contains("usageSource=unknown") == true,
            failure?.contains("envelope=none") == false,
            cancelled?.contains("envelope=none") == false,
            lines.none {
                it.contains("known-body-sentinel") ||
                    it.contains("unknown-body-sentinel") ||
                    it.contains("raw failure sentinel") ||
                    it.contains("cancel sentinel") ||
                    it.contains("known-envelope-secret") ||
                    it.contains("unknown-envelope-secret")
            },
        )
        checks shouldBe List(checks.size) { true }
    }

    private class TraceClock(var nowMs: Long = 0L) : ProviderRequestClock, TranslationTraceClock {
        override fun nowEpochMs(): Long = nowMs
        override fun nowNanos(): Long = nowMs * 1_000_000L

        override suspend fun delay(millis: Long) {
            nowMs += millis
        }

        fun advanceMs(millis: Long) {
            nowMs += millis
        }
    }

    private suspend fun captureTrace(
        block: suspend (TraceClock) -> Unit,
    ): List<String> {
        val lines = mutableListOf<String>()
        val oldSink = TranslationPipelineDiagnostics.sink
        val oldGate = TranslationPipelineDiagnostics.detailedTracingEnabled
        TranslationPipelineDiagnostics.sink = TranslationTraceSink { _, line -> lines += line }
        TranslationPipelineDiagnostics.detailedTracingEnabled = true
        val clock = TraceClock()
        val schedule = TranslationPipelineDiagnostics.startSchedule(
            mode = TranslationTraceMode.BATCH,
            origin = TranslationTraceMode.BATCH,
            pages = 1,
            clock = clock,
        )
        val run = TranslationPipelineDiagnostics.startRun(
            schedule = schedule,
            pageRaw = "governor-page",
            pageIndex = 0,
            clock = clock,
        )
        try {
            withContext(TranslationTrace.elementFor(run)) {
                block(clock)
            }
        } finally {
            run.end(TranslationTraceOutcome.SUCCESS)
            schedule.end(TranslationTraceOutcome.SUCCESS)
            TranslationPipelineDiagnostics.sink = oldSink
            TranslationPipelineDiagnostics.detailedTracingEnabled = oldGate
        }
        return lines.toList()
    }
}
