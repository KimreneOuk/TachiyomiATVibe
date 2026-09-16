package eu.kanade.translation.translator

import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.translator.contextual.TranslationContextChunkPlanner
import eu.kanade.translation.translator.providers.DeepSeekTranslator
import eu.kanade.translation.translator.providers.GeminiTranslator
import eu.kanade.translation.translator.providers.LmStudioTranslator
import eu.kanade.translation.translator.providers.OpenRouterTranslator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.GeminiThinkingMode
import kotlin.math.ceil

/**
 * T933 Task 1.2a: Test suite for InputAccountingContract audit and transport-layer enforcement.
 *
 * Covers:
 * 1. Certified conservative bounds formulas for all 4 providers (LM Studio, DeepSeek, OpenRouter, Gemini).
 * 2. Uncertified contract behavior.
 * 3. Rejection of dispatches without contract (contract = null) at the transport layer before network calls.
 * 4. Rejection of dispatches with uncertified contracts (isCertified = false) at the transport layer.
 * 5. Per-provider disable mechanism (uncertified contract -> terminal dispatch refusal).
 * 6. Rejection of dispatches where finalInputTokens + reservedOutputTokens + 512 > 8192 before network calls.
 *    (Analysis raw path: an oversized OUTPUT reservation is first clamped to
 *    what fits the window; only an oversized INPUT — under the analysis
 *    output floor — is refused at the guard.)
 * 7. Verification that dispatches within 8k budget pass the transport gate and sync certified tokens to governor.
 */
class InputAccountingContractTest {

    private class AdmissionInterceptException(val event: ProviderAdmissionEvent) : RuntimeException("Intercepted at governor admission")

    private fun interceptingGovernor(): Pair<ProviderRequestGovernor, MutableList<ProviderAdmissionEvent>> {
        val events = mutableListOf<ProviderAdmissionEvent>()
        val governor = ProviderRequestGovernor(
            diagnostics = { event ->
                events += event
                throw AdmissionInterceptException(event)
            },
        )
        return governor to events
    }

    /**
     * ~[targetBaseTokens] honest base tokens of repeated CJK text: every
     * certified bound (x1.10..x1.40 + offset) pushes the final payload count
     * past the point where ANY analysis output reservation fits
     * (8192 - 512 - finalInput < 1024-token analysis floor).
     */
    private fun oversizedUserPrompt(targetBaseTokens: Int = 6_400): String {
        val unit = "魔剣アルステラは神社の地下に眠っている"
        val perUnit = TranslationContextChunkPlanner.estimateTokens(unit)
        return unit.repeat(targetBaseTokens / perUnit + 1)
    }

    // =========================================================================
    // 1. Certified Accounting Bounds Calculation Tests
    // =========================================================================

    @Test
    fun `LM Studio contract calculates certified conservative bound ceil(base x 1_40) + 64`() {
        val contract = LmStudioInputAccountingContract(model = "llama-3-8b")
        contract.providerBackend shouldBe "lm_studio"
        contract.model shouldBe "llama-3-8b"
        contract.isCertified shouldBe true
        contract.accountingMode shouldBe AccountingMode.CERTIFIED_BOUND

        val testPayloads = listOf(
            "",
            "Hello, world!",
            "ダンジョンに出会いを求めるのは間違っているだろうか",
            "This is a longer translation context payload with multiple sentences and dialogue lines.",
        )

        for (payload in testPayloads) {
            val base = TranslationContextChunkPlanner.estimateTokens(payload)
            val expected = ceil(base * 1.40).toInt() + 64
            contract.countFinalTokens(payload) shouldBe expected
        }
    }

    @Test
    fun `DeepSeek contract calculates certified conservative bound ceil(base x 1_15) + 32`() {
        val contract = DeepSeekInputAccountingContract(model = "deepseek-chat")
        contract.providerBackend shouldBe "deepseek"
        contract.model shouldBe "deepseek-chat"
        contract.isCertified shouldBe true
        contract.accountingMode shouldBe AccountingMode.CERTIFIED_BOUND

        val testPayloads = listOf(
            "",
            "Hello, world!",
            "魔剣アルステラは神社の地下に眠っている",
            "Complex structured prompt with JSON delimiters and system instructions.",
        )

        for (payload in testPayloads) {
            val base = TranslationContextChunkPlanner.estimateTokens(payload)
            val expected = ceil(base * 1.15).toInt() + 32
            contract.countFinalTokens(payload) shouldBe expected
        }
    }

    @Test
    fun `OpenRouter contract calculates certified conservative bound ceil(base x 1_35) + 64`() {
        val contract = OpenRouterInputAccountingContract(model = "anthropic/claude-3.5-sonnet")
        contract.providerBackend shouldBe "openrouter"
        contract.model shouldBe "anthropic/claude-3.5-sonnet"
        contract.isCertified shouldBe true
        contract.accountingMode shouldBe AccountingMode.CERTIFIED_BOUND

        val testPayloads = listOf(
            "",
            "Hello, world!",
            "俺の妹がこんなに可愛いわけがない",
            "Multi-model routing prompt covering conservative envelope across architectures.",
        )

        for (payload in testPayloads) {
            val base = TranslationContextChunkPlanner.estimateTokens(payload)
            val expected = ceil(base * 1.35).toInt() + 64
            contract.countFinalTokens(payload) shouldBe expected
        }
    }

    @Test
    fun `Gemini contract calculates certified conservative bound ceil(base x 1_10) + 32`() {
        val contract = GeminiInputAccountingContract(model = "gemini-1.5-flash")
        contract.providerBackend shouldBe "gemini"
        contract.model shouldBe "gemini-1.5-flash"
        contract.isCertified shouldBe true
        contract.accountingMode shouldBe AccountingMode.CERTIFIED_BOUND

        val testPayloads = listOf(
            "",
            "Hello, world!",
            "君の名は。新海誠監督のアニメーション映画作品",
            "Gemini REST API payload containing contents, parts, generationConfig, and safetySettings.",
        )

        for (payload in testPayloads) {
            val base = TranslationContextChunkPlanner.estimateTokens(payload)
            val expected = ceil(base * 1.10).toInt() + 32
            contract.countFinalTokens(payload) shouldBe expected
        }
    }

    @Test
    fun `Uncertified contract reports isCertified false`() {
        val contract = UncertifiedInputAccountingContract(providerBackend = "custom_provider", model = "untested")
        contract.providerBackend shouldBe "custom_provider"
        contract.model shouldBe "untested"
        contract.isCertified shouldBe false
        contract.accountingMode shouldBe AccountingMode.CERTIFIED_BOUND

        val payload = "Some arbitrary text payload"
        contract.countFinalTokens(payload) shouldBe TranslationContextChunkPlanner.estimateTokens(payload)
    }

    // =========================================================================
    // 2. Transport Rejection Without Contract (contract = null)
    // =========================================================================

    @Test
    fun `LM Studio dispatch without contract is rejected at transport layer`() = runTest {
        val translator = object : LmStudioTranslator(
            fromLang = TextRecognizerLanguage.JAPANESE,
            toLang = TextTranslatorLanguage.ENGLISH,
            baseUrl = "http://localhost:1234/v1",
            modelName = "llama-3-8b",
            maxOutputToken = 1024,
            temperature = 0.2f,
        ) {
            override val inputAccountingContract: InputAccountingContract? = null
        }

        val ex = shouldThrow<ProviderFailureException> {
            translator.postStructuredAnalysisRaw("system", "user", 256)
        }
        ex.failure.kind shouldBe ProviderFailureKind.CONFIGURATION
        ex.failure.retryability shouldBe ProviderFailureRetryability.TERMINAL
        ex.failure.safeSummary shouldContain "dispatch refused: provider 'lm_studio' has no certified InputAccountingContract"
    }

    @Test
    fun `DeepSeek dispatch without contract is rejected at transport layer`() = runTest {
        val translator = object : DeepSeekTranslator(
            fromLang = TextRecognizerLanguage.JAPANESE,
            toLang = TextTranslatorLanguage.ENGLISH,
            apiKey = "sk-test",
            modelName = "deepseek-chat",
            maxOutputToken = 1024,
            temperature = 0.2f,
        ) {
            override val inputAccountingContract: InputAccountingContract? = null
        }

        val ex = shouldThrow<ProviderFailureException> {
            translator.postStructuredAnalysisRaw("system", "user", 256)
        }
        ex.failure.kind shouldBe ProviderFailureKind.CONFIGURATION
        ex.failure.retryability shouldBe ProviderFailureRetryability.TERMINAL
        ex.failure.safeSummary shouldContain "dispatch refused: provider 'deepseek' has no certified InputAccountingContract"
    }

    @Test
    fun `OpenRouter dispatch without contract is rejected at transport layer`() = runTest {
        val translator = object : OpenRouterTranslator(
            fromLang = TextRecognizerLanguage.JAPANESE,
            toLang = TextTranslatorLanguage.ENGLISH,
            apiKey = "sk-or-test",
            modelName = "anthropic/claude-3.5-sonnet",
            maxOutputToken = 1024,
            temperature = 0.2f,
        ) {
            override val inputAccountingContract: InputAccountingContract? = null
        }

        val ex = shouldThrow<ProviderFailureException> {
            translator.postStructuredAnalysisRaw("system", "user", 256)
        }
        ex.failure.kind shouldBe ProviderFailureKind.CONFIGURATION
        ex.failure.retryability shouldBe ProviderFailureRetryability.TERMINAL
        ex.failure.safeSummary shouldContain "dispatch refused: provider 'openrouter' has no certified InputAccountingContract"
    }

    @Test
    fun `Gemini dispatch without contract is rejected at transport layer`() = runTest {
        val translator = object : GeminiTranslator(
            fromLang = TextRecognizerLanguage.JAPANESE,
            toLang = TextTranslatorLanguage.ENGLISH,
            apiKey = "test-gemini-key",
            modelName = "gemini-1.5-flash",
            maxOutputToken = 1024,
            temp = 0.2f,
            thinkingMode = GeminiThinkingMode.DISABLED,
        ) {
            override val inputAccountingContract: InputAccountingContract? = null
        }

        val ex = shouldThrow<ProviderFailureException> {
            translator.postStructuredAnalysisRaw("system", "user", 256)
        }
        ex.failure.kind shouldBe ProviderFailureKind.CONFIGURATION
        ex.failure.retryability shouldBe ProviderFailureRetryability.TERMINAL
        ex.failure.safeSummary shouldContain "dispatch refused: provider 'gemini' has no certified InputAccountingContract"
    }

    // =========================================================================
    // 3. Transport Rejection With Uncertified Contract / Per-Provider Disable
    // =========================================================================

    @Test
    fun `LM Studio dispatch with uncertified contract is refused at transport layer`() = runTest {
        val uncertifiedContract = UncertifiedInputAccountingContract("lm_studio", "unverified-model")
        val translator = LmStudioTranslator(
            fromLang = TextRecognizerLanguage.JAPANESE,
            toLang = TextTranslatorLanguage.ENGLISH,
            baseUrl = "http://localhost:1234/v1",
            modelName = "unverified-model",
            maxOutputToken = 1024,
            temperature = 0.2f,
            customAccountingContract = uncertifiedContract,
        )

        val ex = shouldThrow<ProviderFailureException> {
            translator.postStructuredAnalysisRaw("system", "user", 256)
        }
        ex.failure.kind shouldBe ProviderFailureKind.CONFIGURATION
        ex.failure.retryability shouldBe ProviderFailureRetryability.TERMINAL
        ex.failure.safeSummary shouldContain "dispatch refused: provider 'lm_studio' has no certified InputAccountingContract"
    }

    @Test
    fun `DeepSeek dispatch with uncertified contract is refused at transport layer`() = runTest {
        val disabledContract = DeepSeekInputAccountingContract("deepseek-chat", isCertified = false)
        val translator = DeepSeekTranslator(
            fromLang = TextRecognizerLanguage.JAPANESE,
            toLang = TextTranslatorLanguage.ENGLISH,
            apiKey = "sk-test",
            modelName = "deepseek-chat",
            maxOutputToken = 1024,
            temperature = 0.2f,
            customAccountingContract = disabledContract,
        )

        val ex = shouldThrow<ProviderFailureException> {
            translator.postStructuredAnalysisRaw("system", "user", 256)
        }
        ex.failure.kind shouldBe ProviderFailureKind.CONFIGURATION
        ex.failure.retryability shouldBe ProviderFailureRetryability.TERMINAL
        ex.failure.safeSummary shouldContain "dispatch refused: provider 'deepseek' has no certified InputAccountingContract"
    }

    @Test
    fun `OpenRouter dispatch with uncertified contract is refused at transport layer`() = runTest {
        val uncertifiedContract = UncertifiedInputAccountingContract("openrouter", "meta-llama/llama-3")
        val translator = OpenRouterTranslator(
            fromLang = TextRecognizerLanguage.JAPANESE,
            toLang = TextTranslatorLanguage.ENGLISH,
            apiKey = "sk-or-test",
            modelName = "meta-llama/llama-3",
            maxOutputToken = 1024,
            temperature = 0.2f,
            customAccountingContract = uncertifiedContract,
        )

        val ex = shouldThrow<ProviderFailureException> {
            translator.postStructuredAnalysisRaw("system", "user", 256)
        }
        ex.failure.kind shouldBe ProviderFailureKind.CONFIGURATION
        ex.failure.retryability shouldBe ProviderFailureRetryability.TERMINAL
        ex.failure.safeSummary shouldContain "dispatch refused: provider 'openrouter' has no certified InputAccountingContract"
    }

    @Test
    fun `Gemini dispatch with uncertified contract is refused at transport layer`() = runTest {
        val disabledContract = GeminiInputAccountingContract("gemini-1.5-flash", isCertified = false)
        val translator = GeminiTranslator(
            fromLang = TextRecognizerLanguage.JAPANESE,
            toLang = TextTranslatorLanguage.ENGLISH,
            apiKey = "test-gemini-key",
            modelName = "gemini-1.5-flash",
            maxOutputToken = 1024,
            temp = 0.2f,
            customAccountingContract = disabledContract,
        )

        val ex = shouldThrow<ProviderFailureException> {
            translator.postStructuredAnalysisRaw("system", "user", 256)
        }
        ex.failure.kind shouldBe ProviderFailureKind.CONFIGURATION
        ex.failure.retryability shouldBe ProviderFailureRetryability.TERMINAL
        ex.failure.safeSummary shouldContain "dispatch refused: provider 'gemini' has no certified InputAccountingContract"
    }

    // =========================================================================
    // 4. Transport Rejection Under 8k Ceiling (finalInput + reservedOutput + 512 > 8192)
    // =========================================================================

    @Test
    fun `LM Studio dispatch exceeding 8k budget is rejected at transport layer`() = runTest {
        val (governor, _) = interceptingGovernor()
        val translator = LmStudioTranslator(
            fromLang = TextRecognizerLanguage.JAPANESE,
            toLang = TextTranslatorLanguage.ENGLISH,
            baseUrl = "http://localhost:1234/v1",
            modelName = "llama-3-8b",
            maxOutputToken = 8000,
            temperature = 0.2f,
            requestGovernor = governor,
        )

        // The INPUT is oversized: no honest output reservation fits under the
        // analysis floor, the clamp stands down, and the guard refuses.
        val ex = shouldThrow<ProviderFailureException> {
            translator.postStructuredAnalysisRaw("system prompt", oversizedUserPrompt(), maxOutputTokens = 7800)
        }
        ex.failure.kind shouldBe ProviderFailureKind.CONFIGURATION
        ex.failure.retryability shouldBe ProviderFailureRetryability.TERMINAL
        ex.failure.safeSummary shouldContain "dispatch refused under 8k"
        ex.failure.safeSummary shouldContain "lm_studio"
    }

    @Test
    fun `DeepSeek dispatch exceeding 8k budget is rejected at transport layer`() = runTest {
        val (governor, _) = interceptingGovernor()
        val translator = DeepSeekTranslator(
            fromLang = TextRecognizerLanguage.JAPANESE,
            toLang = TextTranslatorLanguage.ENGLISH,
            apiKey = "sk-test",
            modelName = "deepseek-chat",
            maxOutputToken = 8000,
            temperature = 0.2f,
            requestGovernor = governor,
        )

        val ex = shouldThrow<ProviderFailureException> {
            translator.postStructuredAnalysisRaw("system prompt", oversizedUserPrompt(), maxOutputTokens = 7800)
        }
        ex.failure.kind shouldBe ProviderFailureKind.CONFIGURATION
        ex.failure.retryability shouldBe ProviderFailureRetryability.TERMINAL
        ex.failure.safeSummary shouldContain "dispatch refused under 8k"
        ex.failure.safeSummary shouldContain "deepseek"
    }

    @Test
    fun `OpenRouter dispatch exceeding 8k budget is rejected at transport layer`() = runTest {
        val (governor, _) = interceptingGovernor()
        val translator = OpenRouterTranslator(
            fromLang = TextRecognizerLanguage.JAPANESE,
            toLang = TextTranslatorLanguage.ENGLISH,
            apiKey = "sk-or-test",
            modelName = "anthropic/claude-3.5-sonnet",
            maxOutputToken = 8000,
            temperature = 0.2f,
            requestGovernor = governor,
        )

        val ex = shouldThrow<ProviderFailureException> {
            translator.postStructuredAnalysisRaw("system prompt", oversizedUserPrompt(), maxOutputTokens = 7800)
        }
        ex.failure.kind shouldBe ProviderFailureKind.CONFIGURATION
        ex.failure.retryability shouldBe ProviderFailureRetryability.TERMINAL
        ex.failure.safeSummary shouldContain "dispatch refused under 8k"
        ex.failure.safeSummary shouldContain "openrouter"
    }

    @Test
    fun `Gemini dispatch exceeding 8k budget is rejected at transport layer`() = runTest {
        val (governor, _) = interceptingGovernor()
        val translator = GeminiTranslator(
            fromLang = TextRecognizerLanguage.JAPANESE,
            toLang = TextTranslatorLanguage.ENGLISH,
            apiKey = "test-gemini-key",
            modelName = "gemini-1.5-flash",
            maxOutputToken = 8000,
            temp = 0.2f,
            requestGovernor = governor,
        )

        val ex = shouldThrow<ProviderFailureException> {
            translator.postStructuredAnalysisRaw("system prompt", oversizedUserPrompt(), maxOutputTokens = 7800)
        }
        ex.failure.kind shouldBe ProviderFailureKind.CONFIGURATION
        ex.failure.retryability shouldBe ProviderFailureRetryability.TERMINAL
        ex.failure.safeSummary shouldContain "dispatch refused under 8k"
        ex.failure.safeSummary shouldContain "gemini"
    }

    @Test
    fun `Gemini analysis output reservation is clamped to the 8k window instead of refused`() = runTest {
        val (governor, _) = interceptingGovernor()
        val translator = GeminiTranslator(
            fromLang = TextRecognizerLanguage.JAPANESE,
            toLang = TextTranslatorLanguage.ENGLISH,
            apiKey = "test-gemini-key",
            modelName = "gemini-1.5-flash",
            maxOutputToken = 8000,
            temp = 0.2f,
            requestGovernor = governor,
        )

        // A small input with an oversized requested output no longer refuses:
        // the reservation is clamped to what fits and the dispatch proceeds.
        val ex = shouldThrow<AdmissionInterceptException> {
            translator.postStructuredAnalysisRaw("system prompt", "user prompt", maxOutputTokens = 7800)
        }
        ex.event.outcome shouldBe "admitted"
        // finalInputTokens + reservedOutput stays inside the dispatch window.
        (ex.event.estimatedTokens <= 8_192 - 512) shouldBe true
    }

    // =========================================================================
    // 5. Dispatches Within 8k Budget Pass Transport Gate & Sync Final Tokens
    // =========================================================================

    @Test
    fun `LM Studio dispatch within 8k budget passes transport gate and charges certified final tokens`() = runTest {
        val (governor, events) = interceptingGovernor()
        val translator = LmStudioTranslator(
            fromLang = TextRecognizerLanguage.JAPANESE,
            toLang = TextTranslatorLanguage.ENGLISH,
            baseUrl = "http://localhost:1234/v1",
            modelName = "llama-3-8b",
            maxOutputToken = 1024,
            temperature = 0.2f,
            requestGovernor = governor,
        )

        val systemPrompt = "Translate Japanese to English"
        val userPrompt = "こんにちは、世界"
        val reservedOutput = 256

        val ex = shouldThrow<AdmissionInterceptException> {
            translator.postStructuredAnalysisRaw(systemPrompt, userPrompt, maxOutputTokens = reservedOutput)
        }

        events.size shouldBe 1
        val event = ex.event
        event.outcome shouldBe "admitted"

        // Compute expected certified bound on payload
        val contract = translator.inputAccountingContract!!
        // The structured analysis JSON payload was passed through contract.countFinalTokens
        // metadata.estimatedTokens == finalInputTokens + reservedOutput
        val finalInput = event.estimatedTokens - reservedOutput
        finalInput shouldBe contract.countFinalTokens(
            buildJsonObject {
                put("model", "llama-3-8b")
                put("temperature", 0.2)
                put("max_tokens", reservedOutput)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    }
                    addJsonObject {
                        put("role", "user")
                        put("content", userPrompt)
                    }
                }
            }.toString(),
        )
    }

    @Test
    fun `DeepSeek dispatch within 8k budget passes transport gate and charges certified final tokens`() = runTest {
        val (governor, events) = interceptingGovernor()
        val translator = DeepSeekTranslator(
            fromLang = TextRecognizerLanguage.JAPANESE,
            toLang = TextTranslatorLanguage.ENGLISH,
            apiKey = "sk-test",
            modelName = "deepseek-chat",
            maxOutputToken = 1024,
            temperature = 0.2f,
            requestGovernor = governor,
        )

        val systemPrompt = "Translate accurately"
        val userPrompt = "魔法使いの嫁"
        val reservedOutput = 256

        val ex = shouldThrow<AdmissionInterceptException> {
            translator.postStructuredAnalysisRaw(systemPrompt, userPrompt, maxOutputTokens = reservedOutput)
        }

        events.size shouldBe 1
        val event = ex.event
        event.outcome shouldBe "admitted"

        val contract = translator.inputAccountingContract!!
        val finalInput = event.estimatedTokens - reservedOutput
        finalInput shouldBe contract.countFinalTokens(
            buildJsonObject {
                put("model", "deepseek-chat")
                put("temperature", 0.2)
                put("max_tokens", reservedOutput)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    }
                    addJsonObject {
                        put("role", "user")
                        put("content", userPrompt)
                    }
                }
            }.toString(),
        )
    }

    @Test
    fun `OpenRouter dispatch within 8k budget passes transport gate and charges certified final tokens`() = runTest {
        val (governor, events) = interceptingGovernor()
        val translator = OpenRouterTranslator(
            fromLang = TextRecognizerLanguage.JAPANESE,
            toLang = TextTranslatorLanguage.ENGLISH,
            apiKey = "sk-or-test",
            modelName = "anthropic/claude-3.5-sonnet",
            maxOutputToken = 1024,
            temperature = 0.2f,
            requestGovernor = governor,
        )

        val systemPrompt = "Translate accurately"
        val userPrompt = "進撃の巨人"
        val reservedOutput = 256

        val ex = shouldThrow<AdmissionInterceptException> {
            translator.postStructuredAnalysisRaw(systemPrompt, userPrompt, maxOutputTokens = reservedOutput)
        }

        events.size shouldBe 1
        val event = ex.event
        event.outcome shouldBe "admitted"

        val contract = translator.inputAccountingContract!!
        val finalInput = event.estimatedTokens - reservedOutput
        finalInput shouldBe contract.countFinalTokens(
            buildJsonObject {
                put("model", "anthropic/claude-3.5-sonnet")
                put("temperature", 0.2)
                put("max_tokens", reservedOutput)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    }
                    addJsonObject {
                        put("role", "user")
                        put("content", userPrompt)
                    }
                }
            }.toString(),
        )
    }

    @Test
    fun `Gemini dispatch within 8k budget passes transport gate and charges certified final tokens`() = runTest {
        val (governor, events) = interceptingGovernor()
        val translator = GeminiTranslator(
            fromLang = TextRecognizerLanguage.JAPANESE,
            toLang = TextTranslatorLanguage.ENGLISH,
            apiKey = "test-gemini-key",
            modelName = "gemini-1.5-flash",
            maxOutputToken = 1024,
            temp = 0.2f,
            requestGovernor = governor,
        )

        val systemPrompt = "Translate accurately"
        val userPrompt = "鬼滅の刃"
        val reservedOutput = 256

        val ex = shouldThrow<AdmissionInterceptException> {
            translator.postStructuredAnalysisRaw(systemPrompt, userPrompt, maxOutputTokens = reservedOutput)
        }

        events.size shouldBe 1
        val event = ex.event
        event.outcome shouldBe "admitted"

        val contract = translator.inputAccountingContract!!
        val finalInput = event.estimatedTokens - reservedOutput
        // GeminiTranslator post was called with generated Gemini payload
        // Verify finalInput matches contract calculation for that payload
        finalInput shouldBe contract.countFinalTokens(
            eu.kanade.translation.translator.providers.GeminiRequestPayload.create(
                modelName = "gemini-1.5-flash",
                systemPrompt = systemPrompt,
                prompt = userPrompt,
                maxOutputTokens = reservedOutput,
                temperature = 0.2f,
                thinkingMode = GeminiThinkingMode.DISABLED,
            ).payload,
        )
    }
}
