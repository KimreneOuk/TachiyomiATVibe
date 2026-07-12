package eu.kanade.translation.translator

import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.ocr.TextRecognizerLanguage
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import logcat.logcat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class OpenRouterTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    val apiKey: String,
    val modelName: String,
    val maxOutputToken: Int,
    val temperature: Float,
) : OpenAiCompatibleTranslator() {

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        translateInternal(pages, rollingContext = "", glossary = "", outputTokenLimit = maxOutputToken)
    }

    override suspend fun translateContextual(chunk: TranslationContextChunk) {
        translateInternal(chunk.pages, chunk.rollingContext, chunk.glossary, chunk.maxOutputTokens)
    }

    private suspend fun translateInternal(
        pages: MutableMap<String, PageTranslation>,
        rollingContext: String,
        glossary: String,
        outputTokenLimit: Int,
    ) {

        try {
            val data = pages.mapValues { (k, v) -> v.blocks.map { b -> TranslationPrompts.jsonSourceValue(b) } }
            val json = JSONObject(data)
            val contextPrefix = TranslationPrompts.contextPrefix(rollingContext, glossary)
            val jsonObject = buildJsonObject {
                put("model", modelName)
                putJsonObject("response_format") { put("type", "json_object") }
                put("top_p", 0.5f)
                put("top_k", 30)
                put("temperature", temperature)
                put("max_tokens", outputTokenLimit)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "system")
                        put("content", TranslationPrompts.jsonSystemPrompt(fromLang, toLang))
                    }
                    addJsonObject {
                        put("role", "user")
                        put("content", contextPrefix + "JSON $json")
                    }
                }

            }.toString()

            val rawOutput = postChatCompletion(
                url = "https://openrouter.ai/api/v1/chat/completions",
                headers = mapOf("Authorization" to "Bearer $apiKey"),
                payloadJson = jsonObject,
            )
            val responseJson = JSONObject(rawOutput)

            for ((k, v) in pages) {
                val expected = v.blocks.size
                val actual = responseJson.optJSONArray(k)?.length() ?: 0
                if (expected != actual) {
                    logcat {
                        "OpenRouter response length mismatch for '$k': expected=$expected actual=$actual " +
                            "(mismatched blocks stay blank, retried by pipeline PARTIAL recovery)"
                    }
                }
                // Never fall back to source text on null/missing lines: a blank translation
                // lets validation mark the page PARTIAL/FAILED instead of rendering OCR as a translation.
                v.blocks.forEachIndexed { i, b ->
                    val res = responseJson.optJSONArray(k)?.optString(i, "NULL")
                    if (res != null && res != "NULL" && res.isNotBlank()) {
                        b.translation = OcrArtifactSanitizer.sanitize(res)
                    }
                }
            }
            TranslationBlockFilters.removeWatermarkBlocks(pages)


        } catch (e: Exception) {
            logcat { "Image Translation Error : ${e.stackTraceToString()}" }
            throw e
        }
    }

    override suspend fun promptText(prompt: String): String {
        return try {
            val jsonObject = buildJsonObject {
                put("model", modelName)
                put("top_p", 0.5f)
                put("top_k", 30)
                put("temperature", temperature)
                put("max_tokens", maxOutputToken)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    }
                }
            }.toString()

            postChatCompletion(
                url = "https://openrouter.ai/api/v1/chat/completions",
                headers = mapOf("Authorization" to "Bearer $apiKey"),
                payloadJson = jsonObject,
            )
        } catch (e: Exception) {
            logcat { "OpenRouter promptText Error : ${e.stackTraceToString()}" }
            ""
        }
    }
}
