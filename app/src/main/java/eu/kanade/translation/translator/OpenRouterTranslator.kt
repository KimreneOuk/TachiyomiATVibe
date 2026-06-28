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
) : ContextualTextTranslator {
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
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val jsonObject = buildJsonObject {
                put("model", modelName)
                putJsonObject("response_format") { put("type", "json_object") }
                put("top_p", 0.5f)
                put("top_k", 30)
                put("temperature", temp)
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
                val expected = v.blocks.size
                val actual = resJson.optJSONArray(k)?.length() ?: 0
                if (expected != actual) {
                    logcat {
                        "OpenRouter response length mismatch for '$k': expected=$expected actual=$actual " +
                            "(mismatched blocks stay blank, retried by pipeline PARTIAL recovery)"
                    }
                }
                // TachiyomiAT: do NOT fall back to `b.text` when the model returns
                // null/"NULL"/missing. See GeminiTranslator for the full rationale
                // (leaving translation blank lets validation mark the page
                // PARTIAL/FAILED instead of rendering source text as a translation).
                v.blocks.forEachIndexed { i, b ->
                    val res = resJson.optJSONArray(k)?.optString(i, "NULL")
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

    override fun close() {
        // TachiyomiAT: release this translator's connection pool + dispatcher
        // threads. TranslationEngineBuilder rebuilds translators on every language
        // change, and an empty close() left each retired client's pool (and its
        // idle threads) alive for the process lifetime, slowly leaking.
        okHttpClient.connectionPool.evictAll()
        okHttpClient.dispatcher.executorService.shutdown()
    }


}
