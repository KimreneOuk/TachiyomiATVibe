package eu.kanade.translation.translator

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

/**
 * Guards the JSON model-list parsers in [AiModelFetcher]. These decide which
 * model ids appear in the settings UI, and an empty/wrong list makes a
 * configured engine look broken. Previously only `normalizeBaseUrl` was tested;
 * the parsing logic was uncovered.
 */
class AiModelFetcherParseTest {

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `parseOpenAiModels extracts the id of every data entry`() {
        AiModelFetcher.parseOpenAiModels(
            json("""{"data":[{"id":"gpt-4o"},{"id":"gpt-4o-mini"},{"id":"o3-mini"}]}"""),
        ) shouldContainExactly listOf("gpt-4o", "gpt-4o-mini", "o3-mini")
    }

    @Test
    fun `parseOpenAiModels trims ids and skips empty ones`() {
        AiModelFetcher.parseOpenAiModels(
            json("""{"data":[{"id":"  deepseek-chat  "},{"id":""},{"id":"  "}]}"""),
        ) shouldContainExactly listOf("deepseek-chat")
    }

    @Test
    fun `parseOpenAiModels returns empty when the data array is missing`() {
        AiModelFetcher.parseOpenAiModels(json("""{"error":"bad key"}""")) shouldBe emptyList()
    }

    @Test
    fun `parseOpenAiModels skips entries with a JSON-null id instead of throwing`() {
        // Regression guard: the original org.json optString treated {"id": null}
        // as "" (skip). The kotlinx migration must not throw on a JsonNull value.
        AiModelFetcher.parseOpenAiModels(
            json("""{"data":[{"id":"keep"},{"id":null},{"id":"also"}]}"""),
        ) shouldContainExactly listOf("keep", "also")
    }

    @Test
    fun `parseGeminiModels strips the models prefix from each name`() {
        AiModelFetcher.parseGeminiModels(
            json(
                """{"models":[{"name":"models/gemini-1.5-pro","supportedGenerationMethods":["generateContent"]},""" +
                    """{"name":"models/gemini-1.5-flash","supportedGenerationMethods":["generateContent"]}]}""",
            ),
        ) shouldContainExactly listOf("gemini-1.5-pro", "gemini-1.5-flash")
    }

    @Test
    fun `parseGeminiModels filters out entries that cannot generate content`() {
        // Embedding-only models must not appear in the translator model list.
        AiModelFetcher.parseGeminiModels(
            json(
                """{"models":[{"name":"models/gemini-1.5-pro","supportedGenerationMethods":["generateContent"]},""" +
                    """{"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]}]}""",
            ),
        ) shouldContainExactly listOf("gemini-1.5-pro")
    }

    @Test
    fun `parseGeminiModels skips entries missing supportedGenerationMethods`() {
        AiModelFetcher.parseGeminiModels(
            json(
                """{"models":[{"name":"models/gemini-1.5-pro","supportedGenerationMethods":["generateContent"]},""" +
                    """{"name":"models/unknown"}]}""",
            ),
        ) shouldContainExactly listOf("gemini-1.5-pro")
    }

    @Test
    fun `parseGeminiModels returns empty when the models array is missing`() {
        AiModelFetcher.parseGeminiModels(json("""{"error":"denied"}""")) shouldBe emptyList()
    }

    @Test
    fun `normalizeBaseUrl trims whitespace and trailing slashes`() {
        AiModelFetcher.normalizeBaseUrl("  http://host:1234/v1///  ") shouldBe "http://host:1234/v1"
    }
}
