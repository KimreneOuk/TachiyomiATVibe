package eu.kanade.translation.pipeline

import eu.kanade.translation.diagnostics.TelemetryTrace
import eu.kanade.translation.engines.translator.GeminiInputAccountingContract
import eu.kanade.translation.engines.translator.OpenRouterInputAccountingContract
import eu.kanade.translation.engines.translator.ProviderHttpResult
import eu.kanade.translation.engines.translator.ProviderRequestGovernor
import eu.kanade.translation.engines.translator.contextual.ContextualRequestProtocol
import eu.kanade.translation.engines.translator.contextual.TranslationContextChunk
import eu.kanade.translation.engines.translator.providers.GeminiTranslator
import eu.kanade.translation.engines.translator.providers.OpenRouterTranslator
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.GeminiThinkingMode

class BatchTelemetryTest {

    private val capturedLines = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        capturedLines.clear()
        TelemetryTrace.setTestSink { capturedLines.add(it) }
        TelemetryTrace.enabled = true
    }

    @AfterEach
    fun tearDown() {
        TelemetryTrace.setTestSink(null)
    }

    private fun block(text: String) = TranslationBlock(
        blockId = "b1",
        text = text,
        translation = "",
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )

    private fun testChunk(): TranslationContextChunk {
        val page = PageTranslation(
            sourceFileName = "page_1.jpg",
            blocks = mutableListOf(block("Hello")),
        )
        return TranslationContextChunk(
            pages = linkedMapOf("page_1.jpg" to page),
            blockCount = 1,
            rollingContext = "",
            estimatedPromptTokens = 10,
            maxOutputTokens = 100,
            protocol = ContextualRequestProtocol.BATCH_V1,
            pageIndexes = mapOf("page_1.jpg" to 0),
        )
    }

    @Test
    fun `OpenAiCompatibleTranslator emits ai_envelope_transit decomposition`() = runTest {
        val openAiResponseBody = "data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"translations\\\":[{\\\"id\\\":\\\"b1\\\",\\\"translation\\\":\\\"Bonjour\\\"}]}\"}}]}\n\ndata: [DONE]\n\n"
        val responseClass = Class.forName(
            "eu.kanade.translation.engines.translator.providers.OpenAiCompatibleTranslator\$RawProviderResponse",
        )
        val constructor = responseClass.getDeclaredConstructor(
            Int::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
        )
        constructor.isAccessible = true
        val fakeResponse = constructor.newInstance(200, null, openAiResponseBody)

        val governor = mockk<ProviderRequestGovernor>()
        coEvery { governor.executeWithUsage<Any>(any(), any()) } returns ProviderHttpResult(fakeResponse)

        val translator = OpenRouterTranslator(
            fromLang = TextRecognizerLanguage.JAPANESE,
            toLang = TextTranslatorLanguage.ENGLISH,
            apiKey = "test-key",
            modelName = "anthropic/claude-3.5-sonnet",
            maxOutputToken = 100,
            temperature = 0.2f,
            requestGovernor = governor,
            customAccountingContract = OpenRouterInputAccountingContract("anthropic/claude-3.5-sonnet"),
        )

        translator.translateContextualStructured(testChunk())
        assertTransitEvent()
    }

    @Test
    fun `GeminiTranslator emits ai_envelope_transit decomposition`() = runTest {
        val geminiResponseBody = """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {
                    "text": "{\"translations\":[{\"id\":\"b1\",\"translation\":\"Bonjour\"}]}"
                  }
                ]
              }
            }
          ]
        }
        """.trimIndent()

        val responseClass = Class.forName(
            "eu.kanade.translation.engines.translator.providers.GeminiTranslator\$RawGeminiResponse",
        )
        val constructor = responseClass.getDeclaredConstructor(
            Int::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
        )
        constructor.isAccessible = true
        val fakeResponse = constructor.newInstance(200, null, geminiResponseBody)

        val governor = mockk<ProviderRequestGovernor>()
        coEvery { governor.executeWithUsage<Any>(any(), any()) } returns ProviderHttpResult(fakeResponse)

        val translator = GeminiTranslator(
            fromLang = TextRecognizerLanguage.JAPANESE,
            toLang = TextTranslatorLanguage.ENGLISH,
            apiKey = "test-gemini-key",
            modelName = "gemini-1.5-flash",
            maxOutputToken = 100,
            temp = 0.2f,
            thinkingMode = GeminiThinkingMode.DISABLED,
            requestGovernor = governor,
            customAccountingContract = GeminiInputAccountingContract("gemini-1.5-flash"),
        )

        translator.translateContextualStructured(testChunk())
        assertTransitEvent()
    }

    private fun assertTransitEvent() {
        val transitEvents = capturedLines.filter {
            it.contains("domain=batch") && it.contains("event=ai_envelope_transit")
        }
        transitEvents.size shouldBe 1
        val event = transitEvents.first()
        event shouldContain "domain=batch"
        event shouldContain "event=ai_envelope_transit"
        event shouldContain "envelopeId="
        event shouldContain "pageCount=1"
        event shouldContain "serializeMs="
        event shouldContain "httpTransitMs="
        event shouldContain "deserializeMs="
        event shouldContain "totalRpcMs="
    }
}
