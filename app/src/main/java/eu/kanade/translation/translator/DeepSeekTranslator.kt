package eu.kanade.translation.translator

import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocr.TextRecognizerLanguage
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import logcat.logcat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class DeepSeekTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    val apiKey: String,
    val modelName: String,
    val maxOutputToken: Int,
    val temp: Float,
) : ContextualTextTranslator {

    // TachiyomiAT: tightened from 180s → 60s. The batch path now wraps every
    // page in withTimeoutOrNull(120s), so an HTTP timeout within that period
    // gives the permit a chance to release timely rather than eating all 120s.
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
        if (apiKey.isBlank()) {
            throw IllegalArgumentException("DeepSeek API key is required")
        }

        // Flatten all text blocks from all pages to pass to DeepSeek in one single batch
        val flatBlocks = mutableListOf<Pair<TranslationBlock, String>>()
        for ((_, page) in pages) {
            for (block in page.blocks) {
                if (block.text.isNotBlank()) {
                    flatBlocks.add(Pair(block, block.text))
                }
            }
        }

        if (flatBlocks.isEmpty()) return

        try {
            val textBlocksStr = flatBlocks.mapIndexed { index, (block, _) ->
                TranslationPrompts.numberedSourceLine(index, block)
            }.joinToString("\n")
            val contextPrefix = TranslationPrompts.contextPrefix(rollingContext, glossary)

            val systemPrompt = TranslationPrompts.numberedSystemPrompt(fromLang, toLang)

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val jsonObject = buildJsonObject {
                put("model", if (modelName.isBlank()) "deepseek-chat" else modelName)
                put("temperature", temp)
                put("max_tokens", outputTokenLimit)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    }
                    addJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            contextPrefix +
                                "Translate these ${fromLang.label} text blocks to ${toLang.label}:\n\n$textBlocksStr",
                        )
                    }
                }
            }.toString()

            val body = jsonObject.toRequestBody(mediaType)
            val build: Request = Request.Builder()
                .url("https://api.deepseek.com/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            val response = okHttpClient.newCall(build).await()
            val rBody = response.body ?: throw IllegalStateException("Empty response body from DeepSeek API")
            val responseJson = JSONObject(rBody.string())
            // TachiyomiAT: shape-check the response. A DeepSeek API error (rate
            // limit, bad key, server error) returns JSON with no "choices" array;
            // the old code did getJSONArray("choices") unconditionally and
            // JSONException'd with an opaque message.
            val rawOutput = responseJson.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")
            if (rawOutput.isNullOrBlank()) {
                throw IllegalStateException(
                    "DeepSeek returned no content (choices missing or empty): " +
                        responseJson.optString("error", responseJson.toString()),
                )
            }

            val parsedTranslations = NumberedLineResponseParser.parse(
                raw = rawOutput,
                expectedCount = flatBlocks.size,
                targetLang = toLang,
            )

            // TachiyomiAT: do NOT fall back to the source text when the model
            // returns a blank/missing line. Leaving `block.translation` empty
            // lets the batch validation gate (TranslationPipeline) mark this
            // block/page as PARTIAL/FAILED instead of silently passing OCR text
            // off as a successful translation — the root cause of pages that
            // mixed real translations with untouched source text. The renderer
            // no longer falls back to `block.text` either, so a blank
            // translation renders as nothing rather than the original text.
            flatBlocks.forEachIndexed { index, (block, _) ->
                val translated = parsedTranslations[index] ?: ""
                if (translated.isNotBlank()) {
                    block.translation = OcrArtifactSanitizer.sanitize(translated)
                }
            }
            TranslationBlockFilters.removeWatermarkBlocks(pages)
        } catch (e: Exception) {
            logcat { "DeepSeek Translation Error : ${e.stackTraceToString()}" }
            throw e
        }
    }

    override suspend fun promptText(prompt: String): String {
        return try {
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val jsonObject = buildJsonObject {
                put("model", if (modelName.isBlank()) "deepseek-chat" else modelName)
                put("temperature", temp)
                put("max_tokens", maxOutputToken)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    }
                }
            }.toString()

            val body = jsonObject.toRequestBody(mediaType)
            val build: Request = Request.Builder()
                .url("https://api.deepseek.com/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            val response = okHttpClient.newCall(build).await()
            val rBody = response.body ?: return ""
            val responseJson = JSONObject(rBody.string())
            responseJson.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content") ?: ""
        } catch (e: Exception) {
            logcat { "DeepSeek promptText Error : ${e.stackTraceToString()}" }
            ""
        }
    }

    override fun close() {
        // TachiyomiAT: release this translator's connection pool + dispatcher
        // threads. TranslationEngineBuilder rebuilds translators on every language
        // change, and a no-op close() left each retired client's pool (and its
        // idle threads) alive for the process lifetime, slowly leaking. This
        // does NOT evict shared/coil clients — only this instance's own pool.
        okHttpClient.connectionPool.evictAll()
        okHttpClient.dispatcher.executorService.shutdown()
    }
}
