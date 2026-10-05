package eu.kanade.translation.engines.translator.providers

import eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceClock
import eu.kanade.translation.diagnostics.TranslationTraceMode
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceSink
import eu.kanade.translation.engines.translator.AccountingMode
import eu.kanade.translation.engines.translator.InputAccountingContract
import eu.kanade.translation.engines.translator.ProviderQuotaPolicy
import eu.kanade.translation.engines.translator.ProviderRequestClock
import eu.kanade.translation.engines.translator.ProviderRequestGovernor
import eu.kanade.translation.engines.translator.contextual.ContextualTranslationBatch
import eu.kanade.translation.engines.translator.contextual.TranslationContextChunk
import eu.kanade.translation.engines.translator.contextual.TranslationContextChunkPlanner
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

class OpenAiCompatibleTranslatorTelemetryTest {

    @Test
    fun `chat completion telemetry extracts provider usage without logging the body`() = runTest {
        val server = ServerSocket(0)
        val serverFailure = AtomicReference<Throwable?>()
        val responseText = "provider-body-sentinel"
        val response = """{"choices":[{"message":{"content":"$responseText"}}],"usage":{"prompt_tokens":4,"completion_tokens":3}}"""
        val serverThread = Thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                    while (reader.readLine()?.isNotEmpty() == true) Unit
                    val bytes = response.toByteArray(StandardCharsets.UTF_8)
                    val headers = (
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: ${bytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(StandardCharsets.US_ASCII)
                    socket.getOutputStream().apply {
                        write(headers)
                        write(bytes)
                        flush()
                    }
                }
            } catch (failure: Throwable) {
                serverFailure.set(failure)
            }
        }.apply {
            isDaemon = true
            start()
        }

        val traceLines = mutableListOf<String>()
        val oldSink = TranslationPipelineDiagnostics.sink
        val oldGate = TranslationPipelineDiagnostics.detailedTracingEnabled
        TranslationPipelineDiagnostics.sink = TranslationTraceSink { _, line -> traceLines += line }
        TranslationPipelineDiagnostics.detailedTracingEnabled = true
        val traceClock = TestTraceClock()
        val schedule = TranslationPipelineDiagnostics.startSchedule(
            mode = TranslationTraceMode.BATCH,
            clock = traceClock,
        )
        val run = TranslationPipelineDiagnostics.startRun(
            schedule = schedule,
            pageRaw = "provider-page-secret",
            pageIndex = 0,
            clock = traceClock,
        )
        try {
            val providerClock = object : ProviderRequestClock {
                override fun nowEpochMs(): Long = 0L
                override suspend fun delay(millis: Long) = Unit
            }
            val governor = ProviderRequestGovernor(
                policy = { ProviderQuotaPolicy(minimumSpacingMs = 0) },
                clock = providerClock,
            )
            val translator = TestTranslator(governor)
            val result = withContext(TranslationTrace.elementFor(run)) {
                translator.complete("http://127.0.0.1:${server.localPort}/chat/completions")
            }
            result shouldBe responseText
        } finally {
            run.end(TranslationTraceOutcome.SUCCESS)
            schedule.end(TranslationTraceOutcome.SUCCESS)
            TranslationPipelineDiagnostics.sink = oldSink
            TranslationPipelineDiagnostics.detailedTracingEnabled = oldGate
            server.close()
            serverThread.join(5_000)
        }

        val request = traceLines.singleOrNull {
            it.contains("requestId=") && it.contains("operation=contextual")
        }
        val duration = request?.split(" ")
            ?.firstOrNull { it.startsWith("durationMs=") }
            ?.removePrefix("durationMs=")
            ?.toLongOrNull()
        val checks = listOf(
            request?.contains("requestId=none") == false,
            request?.contains("envelope=none") == false,
            request?.contains("operation=contextual") == true,
            request?.contains("inputTokens=4") == true,
            request?.contains("outputTokens=3") == true,
            request?.contains("usageSource=provider") == true,
            request?.contains("outcome=success") == true,
            duration != null && duration >= 0L,
            traceLines.none {
                it.contains(responseText) ||
                    it.contains("provider-page-secret") ||
                    it.contains("envelope-secret-fixture")
            },
            serverFailure.get() == null,
        )
        checks shouldBe List(checks.size) { true }
    }

    private class TestTranslator(governor: ProviderRequestGovernor) : OpenAiCompatibleTranslator(
        requestGovernor = governor,
        customAccountingContract = object : InputAccountingContract {
            override val providerBackend: String = "openai-compatible-test"
            override val model: String = "fixture"
            override val isCertified: Boolean = true
            override val accountingMode: AccountingMode = AccountingMode.EXACT
            override fun countFinalTokens(payload: String): Int =
                TranslationContextChunkPlanner.estimateTokens(payload)
        },
    ) {
        override fun defaultAccountingContract(): InputAccountingContract = checkNotNull(inputAccountingContract)

        override val fromLang: TextRecognizerLanguage = TextRecognizerLanguage.JAPANESE
        override val toLang: TextTranslatorLanguage = TextTranslatorLanguage.ENGLISH

        override suspend fun translateContextualStructured(chunk: TranslationContextChunk): ContextualTranslationBatch =
            error("structured translation is not used by the HTTP telemetry fixture")

        override suspend fun promptText(prompt: String): String =
            error("prompt translation is not used by the HTTP telemetry fixture")

        suspend fun complete(url: String): String = postChatCompletion(
            url = url,
            headers = emptyMap(),
            payloadJson = """{"messages":[{"role":"user","content":"clean input"}]}""",
            reservedOutputTokens = 16,
            operation = "contextual",
            envelopeId = "envelope-secret-fixture",
        )
    }

    private class TestTraceClock(private var nowNanos: Long = 0L) : TranslationTraceClock {
        override fun nowNanos(): Long = nowNanos
    }
}
