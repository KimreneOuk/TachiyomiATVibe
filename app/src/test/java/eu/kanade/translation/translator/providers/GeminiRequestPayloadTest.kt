package eu.kanade.translation.translator.providers

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.GeminiThinkingMode

class GeminiRequestPayloadTest {

    @Test
    fun `Gemini 25 Flash disables thinking by default`() {
        val request = GeminiRequestPayload.create(
            modelName = "gemini-2.5-flash",
            systemPrompt = "system",
            prompt = "prompt",
            maxOutputTokens = 512,
            temperature = 0.3f,
            thinkingMode = GeminiThinkingMode.DISABLED,
        )

        val config = Json.parseToJsonElement(request.payload).jsonObject["generationConfig"]!!.jsonObject
        request.hasThinkingConfig shouldBe true
        config["thinkingConfig"]!!.jsonObject["thinkingBudget"]!!.jsonPrimitive.content shouldBe "0"
    }

    @Test
    fun `unsupported thinking mode omits the field instead of sending an invalid request`() {
        val request = GeminiRequestPayload.create(
            modelName = "gemini-2.5-pro",
            systemPrompt = null,
            prompt = "prompt",
            maxOutputTokens = 512,
            temperature = 0.3f,
            thinkingMode = GeminiThinkingMode.DISABLED,
        )

        request.hasThinkingConfig shouldBe false
        Json.parseToJsonElement(request.payload).jsonObject["generationConfig"]!!.jsonObject
            .containsKey("thinkingConfig") shouldBe false
    }

    @Test
    fun `Gemini response ignores thought parts and returns visible text`() {
        val response = """{"candidates":[{"content":{"parts":[{"thought":true,"text":"hidden"},{"text":"p0000_b0000|Hello"}]}}]}"""

        response.extractGeminiText() shouldBe "p0000_b0000|Hello"
    }
}
