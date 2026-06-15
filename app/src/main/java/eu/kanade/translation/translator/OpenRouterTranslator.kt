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
    val temp: Float,
) : TextTranslator {
    // TachiyomiAT: explicit 60s timeouts instead of OkHttpClient's default 10s,
    // which is too short for batch AI calls. Consistent with DeepSeek's 60s.
    // The batch path wraps every page in withTimeoutOrNull(120s), so if an HTTP
    // call hangs, the translator permit is released within that window.
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {

        try {
            val data = pages.mapValues { (k, v) -> v.blocks.map { b -> b.text } }
            val json = JSONObject(data)
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val jsonObject = buildJsonObject {
                put("model", modelName)
                putJsonObject("response_format") { put("type", "json_object") }
                put("top_p", 0.5f)
                put("top_k", 30)
                put("temperature", temp)
                put("max_tokens", maxOutputToken)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "system")
                        put(
                            "content",
                            "## System Prompt for Manhwa/Manga/Manhua Translation\n" +
                                "\n" +
                                "You are a highly skilled AI tasked with translating text from scanned images of comics (manhwa, manga, manhua) while preserving the original structure and removing any watermarks or site links. \n" +
                                "\n" +
                                "**Here's how you should operate:**\n" +
                                "\n" +
                                "1. **Input:** You'll receive a JSON object where keys are image filenames (e.g., \"001.jpg\") and values are lists of text strings extracted from those images.\n" +
                                "\n" +
                                "2. **Translation:** Translate all text strings to the target language `${toLang.label}`. Ensure the translation is natural and fluent, adapting idioms and expressions to fit the target language's cultural context.\n" +
                                "\n" +
                                "3. **Watermark/Site Link Removal:** Replace any watermarks or site links (e.g., \"colamanga.com\") with the placeholder \"RTMTH\".\n" +
                                "\n" +
                                "4. **Structure Preservation:** Maintain the exact same structure as the input JSON. The output JSON should have the same number of keys (image filenames) and the same number of text strings within each list.\n" +
                                "\n" +
                                "**Example:**\n" +
                                "\n" +
                                "**Input:**\n" +
                                "\n" +
                                "```json\n" +
                                "{\"001.jpg\":[\"chinese1\",\"chinese2\"],\"002.jpg\":[\"chinese2\",\"colamanga.com\"]}\n" +
                                "```\n" +
                                "\n" +
                                "**Output (for `${toLang.label}` = English):**\n" +
                                "\n" +
                                "```json\n" +
                                "{\"001.jpg\":[\"eng1\",\"eng2\"],\"002.jpg\":[\"eng2\",\"RTMTH\"]}\n" +
                                "```\n" +
                                "\n" +
                                "**Key Points:**\n" +
                                "\n" +
                                "* Prioritize accurate and natural-sounding translations.\n" +
                                "* Be meticulous in removing all watermarks and site links.\n" +
                                "* Ensure the output JSON structure perfectly mirrors the input structure.",
                        )
                    }
                    addJsonObject {
                        put("role", "user")
                        put("content", "JSON $json")
                    }
                }

            }.toString()
            val body = jsonObject.toRequestBody(mediaType)
            val access = "https://openrouter.ai/api/v1/chat/completions"
            val build: Request =
                Request.Builder().url(access).header(
                    "Authorization",
                    "Bearer $apiKey",
                ).header("Content-Type", "application/json").post(body).build()
            val response = okHttpClient.newCall(build).await()
            // TachiyomiAT: null/shape-check the response. An error from the API
            // (rate limit, bad key, server error) returns a JSON object with no
            // "choices" array; the old code dereferenced rBody.string() and
            // getJSONArray("choices") unconditionally and NPE/JSONException'd.
            val rBody = response.body
                ?: throw IllegalStateException("Empty response body from OpenRouter API")
            val json2 = JSONObject(rBody.string())
            val content = json2.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")
            if (content.isNullOrBlank()) {
                throw IllegalStateException(
                    "OpenRouter returned no content (choices missing or empty): " +
                        json2.optString("error", json2.toString()),
                )
            }
            val resJson = JSONObject(content)

            for ((k, v) in pages) {
                v.blocks.forEachIndexed { i, b ->
                    run {
                        val res = resJson.optJSONArray(k)?.optString(i, "NULL")
                        b.translation = if (res == null || res == "NULL") b.text else res
                    }
                }
                v.blocks =
                    v.blocks.filterNot { it.translation.contains("RTMTH") }.toMutableList()
            }


        } catch (e: Exception) {
            logcat { "Image Translation Error : ${e.stackTraceToString()}" }
            throw e
        }
    }

    override fun close() {
    }


}
