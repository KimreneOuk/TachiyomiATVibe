package eu.kanade.translation.translator

import eu.kanade.tachiyomi.network.await
import logcat.LogPriority
import logcat.logcat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

abstract class OpenAiCompatibleTranslator : AITranslator() {

    protected val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Posts a chat completion request to an OpenAI-compatible API.
     * Returns the content string from choices[0].message.content.
     */
    protected suspend fun postChatCompletion(
        url: String,
        headers: Map<String, String>,
        payloadJson: String,
    ): String {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = payloadJson.toRequestBody(mediaType)

        val requestBuilder = Request.Builder()
            .url(url)
            .post(body)

        for ((key, value) in headers) {
            requestBuilder.header(key, value)
        }
        // Always enforce Content-Type
        requestBuilder.header("Content-Type", "application/json")

        val request = requestBuilder.build()
        val response = okHttpClient.newCall(request).await()

        val responseBody = response.body ?: throw IllegalStateException("Empty response body from $url")
        val responseString = responseBody.string()
        val responseJson = JSONObject(responseString)

        val choicesArray = responseJson.optJSONArray("choices")
        val rawOutput = choicesArray?.optJSONObject(0)?.optJSONObject("message")?.optString("content")

        if (rawOutput.isNullOrBlank()) {
            val snippet = if (responseString.length > 300) responseString.substring(0, 300) else responseString
            logcat(LogPriority.WARN) {
                "API returned no usable content. Raw response snippet: $snippet"
            }
            throw IllegalStateException(
                "API returned no content (choices missing or empty): " +
                    responseJson.optString("error", responseJson.toString()),
            )
        }

        return rawOutput
    }

    override suspend fun translateContextual(chunk: TranslationContextChunk) {
        applyBatchToChunk(chunk, translateContextualStructured(chunk))
    }

    protected suspend fun parseContextualCompletion(
        chunk: TranslationContextChunk,
        url: String,
        headers: Map<String, String>,
        logTag: String,
        buildPayload: (systemPrompt: String, finalPrompt: String) -> String,
    ): ContextualTranslationBatch {
        val request = ContextualRequestBuilder.build(chunk, fromLang, toLang)
        if (request.promptLines.isEmpty()) {
            return ContextualRequestBuilder.toBatch(request, emptyList())
        }
        val systemPrompt = TranslationPrompts.pass1SystemPrompt(fromLang, toLang)
        val contextPrefix = TranslationPrompts.contextPrefix(chunk.rollingContext, chunk.glossary)
        val promptBody = request.promptLines.joinToString("\n")
        val finalPrompt = if (contextPrefix.isEmpty()) promptBody else contextPrefix + promptBody
        val payloadJson = buildPayload(systemPrompt, finalPrompt)

        val rawOutput = withTranslationRetry(logTag = logTag) {
            postChatCompletion(url, headers, payloadJson)
        }
        val parsed = ContextualResponseParser.parse(rawOutput.lineSequence().toList(), request.idMap)
        return ContextualRequestBuilder.toBatch(request, parsed)
    }

    override fun close() {
        okHttpClient.connectionPool.evictAll()
        okHttpClient.dispatcher.executorService.shutdown()
    }
}
