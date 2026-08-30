package eu.kanade.translation.translator

import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.util.ShortHash
import logcat.LogPriority
import logcat.logcat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

abstract class OpenAiCompatibleTranslator(
    protected val requestGovernor: ProviderRequestGovernor = SharedProviderRequestGovernor.instance,
) : AiTranslator() {

    protected open val providerBackend: String
        get() = this::class.java.simpleName.removeSuffix("Translator").lowercase(Locale.ROOT)

    protected open val providerModel: String?
        get() = null

    protected open val providerCredentialScope: String?
        get() = null

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
        estimatedInputTokens: Int = TranslationContextChunkPlanner.estimateTokens(payloadJson),
        reservedOutputTokens: Int = 0,
        operation: String = "chat_completion",
        envelopeId: String? = null,
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
        val metadata = ProviderRequestMetadata(
            key = ProviderRequestKey(
                backend = providerBackend,
                model = providerModel,
                credentialScope = providerCredentialScope,
            ),
            estimatedInputTokens = estimatedInputTokens,
            reservedOutputTokens = reservedOutputTokens,
            operation = operation,
            envelopeId = envelopeId,
            priority = currentProviderRequestPriority(),
        )
        val response = requestGovernor.executeValue(metadata) {
            okHttpClient.newCall(request).await().use { response ->
                val raw = RawProviderResponse(
                    code = response.code,
                    retryAfter = response.header("Retry-After"),
                    body = response.body?.string().orEmpty(),
                )
                if (raw.code !in 200..299) {
                    val failure = classifyHttpFailure(
                        backend = providerBackend,
                        statusCode = raw.code,
                        retryAfterHeader = raw.retryAfter,
                        safeSummary = "$providerBackend HTTP ${raw.code}",
                    )
                    throw OpenAiApiException(failure)
                }
                raw
            }
        }

        val responseString = response.body
        if (responseString.isBlank()) {
            throw ProviderFailureException(
                ProviderFailure(
                    kind = ProviderFailureKind.PROTOCOL,
                    retryability = ProviderFailureRetryability.TERMINAL,
                    statusCode = response.code,
                    safeSummary = "$providerBackend returned an empty response body",
                ),
            )
        }
        val responseJson = JSONObject(responseString)

        val choicesArray = responseJson.optJSONArray("choices")
        val rawOutput = choicesArray?.optJSONObject(0)?.optJSONObject("message")?.optString("content")

        if (rawOutput.isNullOrBlank()) {
            logcat(LogPriority.WARN) {
                "event=provider_response_empty reason=no_content status=${response.code} " +
                    "chars=${responseString.length} responseHash=${ShortHash.hash(responseString)} " +
                    "choices=${choicesArray?.length() ?: 0}"
            }
            throw ProviderFailureException(
                ProviderFailure(
                    kind = ProviderFailureKind.PROTOCOL,
                    retryability = ProviderFailureRetryability.TERMINAL,
                    statusCode = response.code,
                    safeSummary = "$providerBackend returned no completion content",
                ),
            )
        }

        return rawOutput
    }

    override suspend fun translateContextual(chunk: TranslationContextChunk): ContextualTranslationBatch {
        val batch = translateContextualStructured(chunk)
        applyBatchToChunk(chunk, batch)
        return batch
    }

    protected suspend fun parseContextualCompletion(
        chunk: TranslationContextChunk,
        url: String,
        headers: Map<String, String>,
        logTag: String,
        buildPayload: (systemPrompt: String, finalPrompt: String) -> String,
    ): ContextualTranslationBatch {
        val request = ContextualRequestBuilder.buildFor(chunk, fromLang, toLang)
        if (request.promptLines.isEmpty()) {
            return ContextualRequestBuilder.toBatch(request, emptyList())
        }
        val systemPrompt = TranslationPrompts.pass1SystemPrompt(
            fromLang,
            toLang,
            batchProtocol = request.protocol == ContextualRequestProtocol.BATCH_V1,
        )
        val finalPrompt = ContextualRequestBuilder.renderPrompt(
            request = request,
            rollingContext = chunk.rollingContext,
            extraGlossary = chunk.glossary,
        )
        val payloadJson = buildPayload(systemPrompt, finalPrompt)

        val rawOutput = withTranslationRetry(
            logTag = logTag,
            envelopePageKeys = chunk.pages.keys,
        ) {
            postChatCompletion(
                url = url,
                headers = headers,
                payloadJson = payloadJson,
                estimatedInputTokens = chunk.estimatedPromptTokens,
                reservedOutputTokens = chunk.maxOutputTokens,
                operation = "contextual",
                envelopeId = ShortHash.hash(chunk.pages.keys.joinToString("|")),
            )
        }
        return if (request.protocol == ContextualRequestProtocol.BATCH_V1) {
            ContextualResponseParser.parseBatch(rawOutput, request).also { batch ->
                if (batch.framingRecovered) {
                    logcat(LogPriority.WARN) {
                        "event=contextual_framing_recovered requested=${batch.requestedIds.size} " +
                            "warnings=${batch.validationErrors.size}"
                    }
                }
            }
        } else {
            val parsed = ContextualResponseParser.parse(rawOutput.lineSequence().toList(), request.idMap)
            ContextualRequestBuilder.toBatch(request, parsed)
        }
    }

    override fun close() {
        okHttpClient.connectionPool.evictAll()
        okHttpClient.dispatcher.executorService.shutdown()
    }

    private data class RawProviderResponse(
        val code: Int,
        val retryAfter: String?,
        val body: String,
    )
}

internal class OpenAiApiException(
    failure: ProviderFailure,
) : ProviderFailureException(failure)
