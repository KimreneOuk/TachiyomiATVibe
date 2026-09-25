package eu.kanade.translation.translator.analysis

import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.translator.ProviderFailure
import eu.kanade.translation.translator.ProviderFailureException
import eu.kanade.translation.translator.ProviderFailureKind
import eu.kanade.translation.translator.ProviderFailureRetryability
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.contextual.ContextualTranslationBatch
import eu.kanade.translation.translator.contextual.TranslationContextChunk
import eu.kanade.translation.translator.providers.AiTranslator
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

/**
 *  wave-7c: the engine-backed analysis transport adapter. Pins the
 * identity triple sourced from the engine's own hooks (one spelling for
 * provenance AND Batch admission keys — wave-6 F-W6-4), the prompt framing
 * ( system + user above the raw JSON envelope), the typed-failure
 * guarantee ( a raw IO failure becomes NETWORK/PAUSE, typed
 * failures pass through, cancellation is never swallowed), and the
 * construction fence for engines without a raw completion.
 */
class AnalysisEngineTransportTest {

    private class FakeEngine(
        override val analysisBackendId: String?,
        override val analysisModelId: String?,
        override val analysisCredentialScope: String?,
        private val onRaw: suspend (systemPrompt: String, userPrompt: String, maxOutputTokens: Int) -> String,
        private val contract: eu.kanade.translation.translator.InputAccountingContract? = null,
    ) : AiTranslator() {
        override val fromLang = TextRecognizerLanguage.JAPANESE
        override val toLang = TextTranslatorLanguage.ENGLISH
        override val inputAccountingContract get() = contract

        override suspend fun translateContextualStructured(
            chunk: TranslationContextChunk,
        ): ContextualTranslationBatch = error("not used by the transport")

        override suspend fun promptText(prompt: String): String = error("not used by the transport")

        override suspend fun postStructuredAnalysisRaw(
            systemPrompt: String,
            userPrompt: String,
            maxOutputTokens: Int,
        ): String = onRaw(systemPrompt, userPrompt, maxOutputTokens)
    }

    @Test
    fun `identity triple comes from the engine hooks`() {
        val engine = FakeEngine(
            analysisBackendId = "lm_studio",
            analysisModelId = "qwen3-8b",
            analysisCredentialScope = "0123456789abcdef",
            onRaw = { _, _, _ -> "{}" },
        )
        val transport = AnalysisEngineTransport(engine)

        // F-W6-4: the governor's `lm_studio` spelling rides through untouched,
        // so analyzer provenance and Batch admission keys share ONE identity.
        transport.providerId shouldBe "lm_studio"
        transport.modelId shouldBe "qwen3-8b"
        // 04: the signature is the opaque one-way value, never a raw key.
        transport.credentialSignature shouldBe "0123456789abcdef"
    }

    @Test
    fun `framing wraps the request json in the analysis prompt with the AP-03 budget`() = runTest {
        val requestJson = """{"envelope":{"protocol":"tachiyomiat-analysis"}}"""
        var captured: Triple<String, String, Int>? = null
        val engine = FakeEngine(
            analysisBackendId = "gemini",
            analysisModelId = "gemini-2.5",
            analysisCredentialScope = null,
            onRaw = { system, user, max ->
                captured = Triple(system, user, max)
                "RAW OUTPUT"
            },
        )
        val transport = AnalysisEngineTransport(engine)

        val result = transport.postStructuredAnalysis(requestJson, chunkId = "chunk-0-abcdef12")

        result shouldBe "RAW OUTPUT"
        val (system, user, max) = captured!!
        system shouldBe AnalysisEngineTransport.ANALYSIS_SYSTEM_PROMPT
        user shouldBe AnalysisEngineTransport.ANALYSIS_USER_INSTRUCTIONS + "\n\n" + requestJson
        max shouldBe AnalysisEngineTransport.ANALYSIS_MAX_OUTPUT_TOKENS
        AnalysisEngineTransport.ANALYSIS_MAX_OUTPUT_TOKENS shouldBe 512
    }

    @Test
    fun `an engine without a raw completion is rejected at construction`() {
        val engine = FakeEngine(
            analysisBackendId = null,
            analysisModelId = null,
            analysisCredentialScope = null,
            onRaw = { _, _, _ -> error("never reached") },
        )
        val error = assertThrows<IllegalArgumentException> {
            AnalysisEngineTransport(engine)
        }
        error.message!! shouldBe "engine has no analysis transport"
    }

    @Test
    fun `a raw IO failure is typed NETWORK with PAUSE retryability`() = runTest {
        val io = IOException("connection reset")
        val engine = FakeEngine(
            analysisBackendId = "deepseek",
            analysisModelId = "deepseek-chat",
            analysisCredentialScope = "fedcba9876543210",
            onRaw = { _, _, _ -> throw io },
        )
        val error = assertThrows<ProviderFailureException> {
            AnalysisEngineTransport(engine).postStructuredAnalysis("{}", "chunk-0")
        }
        error.failure.kind shouldBe ProviderFailureKind.NETWORK
        error.failure.retryability shouldBe ProviderFailureRetryability.PAUSE
        error.failure.safeSummary shouldBe "analysis transport network failure"
        error.cause shouldBe io
    }

    @Test
    fun `typed provider failures pass through unchanged`() = runTest {
        val typed = ProviderFailureException(
            ProviderFailure(
                kind = ProviderFailureKind.QUOTA_EXHAUSTED,
                retryability = ProviderFailureRetryability.RETRY_AFTER,
                safeSummary = "quota window exhausted",
            ),
        )
        val engine = FakeEngine(
            analysisBackendId = "openrouter",
            analysisModelId = "free/model",
            analysisCredentialScope = null,
            onRaw = { _, _, _ -> throw typed },
        )
        val error = assertThrows<ProviderFailureException> {
            AnalysisEngineTransport(engine).postStructuredAnalysis("{}", "chunk-0")
        }
        error.failure.kind shouldBe ProviderFailureKind.QUOTA_EXHAUSTED
        error.failure.retryability shouldBe ProviderFailureRetryability.RETRY_AFTER
        error shouldBe typed
    }

    @Test
    fun `cancellation is never swallowed into a typed failure`() = runTest {
        val engine = FakeEngine(
            analysisBackendId = "gemini",
            analysisModelId = "gemini-2.5",
            analysisCredentialScope = null,
            onRaw = { _, _, _ -> throw CancellationException("run aborted") },
        )
        val error = assertThrows<CancellationException> {
            AnalysisEngineTransport(engine).postStructuredAnalysis("{}", "chunk-0")
        }
        error.message shouldBe "run aborted"
    }

    /** Deterministic certified contract: every payload counts as [tokensPerPayload]. */
    private class FixedTokenContract(
        private val tokensPerPayload: Int,
    ) : eu.kanade.translation.translator.InputAccountingContract {
        override val providerBackend = "fake"
        override val model: String? = null
        override val isCertified = true
        override val accountingMode = eu.kanade.translation.translator.AccountingMode.CERTIFIED_BOUND
        override fun countFinalTokens(payload: String): Int = tokensPerPayload
    }

    @Test
    fun `certified contract clamps the output budget when honest input count leaves less room`() = runTest {
        // 8_192 - 512 - 128 - 7_100 = 452 available (< 512 default, >= 256 floor).
        var capturedMax: Int? = null
        val engine = FakeEngine(
            analysisBackendId = "gemini",
            analysisModelId = "gemini-2.5",
            analysisCredentialScope = null,
            onRaw = { _, _, max ->
                capturedMax = max
                "{}"
            },
            contract = FixedTokenContract(tokensPerPayload = 7_100),
        )
        val transport = AnalysisEngineTransport(engine)

        transport.postStructuredAnalysis("""{"pages":[]}""", "chunk-0-abcdef12")

        capturedMax shouldBe 452
    }

    @Test
    fun `input that cannot host the minimum analysis output pauses with a typed failure`() = runTest {
        // 8_192 - 512 - 128 - 7_500 = 52 available (< 256 floor).
        val engine = FakeEngine(
            analysisBackendId = "gemini",
            analysisModelId = "gemini-2.5",
            analysisCredentialScope = null,
            onRaw = { _, _, _ -> error("never dispatched") },
            contract = FixedTokenContract(tokensPerPayload = 7_500),
        )
        val error = assertThrows<ProviderFailureException> {
            AnalysisEngineTransport(engine).postStructuredAnalysis("""{"pages":[]}""", "chunk-0-abcdef12")
        }
        error.failure.kind shouldBe ProviderFailureKind.CONFIGURATION
        error.failure.retryability shouldBe ProviderFailureRetryability.PAUSE
        error.failure.safeSummary shouldBe "analysis request token-oversized: " +
            "input (7500) leaves under 256 output tokens under 8k"
    }
}
