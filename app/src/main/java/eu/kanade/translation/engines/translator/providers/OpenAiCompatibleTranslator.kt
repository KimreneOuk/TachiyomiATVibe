package eu.kanade.translation.engines.translator.providers
import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.diagnostics.TelemetryTrace
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceProvider
import eu.kanade.translation.engines.translator.InputAccountingContract
import eu.kanade.translation.engines.translator.ProviderFailure
import eu.kanade.translation.engines.translator.ProviderFailureException
import eu.kanade.translation.engines.translator.ProviderFailureKind
import eu.kanade.translation.engines.translator.ProviderFailureRetryability
import eu.kanade.translation.engines.translator.ProviderHttpResult
import eu.kanade.translation.engines.translator.ProviderRequestGovernor
import eu.kanade.translation.engines.translator.ProviderRequestKey
import eu.kanade.translation.engines.translator.ProviderRequestMetadata
import eu.kanade.translation.engines.translator.ProviderUsage
import eu.kanade.translation.engines.translator.SharedProviderRequestGovernor
import eu.kanade.translation.engines.translator.contextual.ContextualRequestBuilder
import eu.kanade.translation.engines.translator.contextual.ContextualRequestProtocol
import eu.kanade.translation.engines.translator.contextual.ContextualResponseParser
import eu.kanade.translation.engines.translator.contextual.ContextualTranslationBatch
import eu.kanade.translation.engines.translator.contextual.TranslationContextChunk
import eu.kanade.translation.engines.translator.contextual.TranslationContextChunkPlanner
import eu.kanade.translation.engines.translator.contextual.TranslationPrompts
import eu.kanade.translation.engines.translator.contextual.applyBatchToChunk
import eu.kanade.translation.engines.translator.currentProviderRequestPriority
import eu.kanade.translation.engines.translator.retry.classifyHttpFailure
import eu.kanade.translation.engines.translator.retry.withTranslationRetry
import eu.kanade.translation.util.ShortHash
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import logcat.LogPriority
import logcat.logcat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.concurrent.TimeUnit

abstract class OpenAiCompatibleTranslator(
    protected val requestGovernor: ProviderRequestGovernor = SharedProviderRequestGovernor.instance,
    open val customAccountingContract: InputAccountingContract? = null,
) : AiTranslator() {

    open override val inputAccountingContract: InputAccountingContract?
        get() = customAccountingContract ?: defaultAccountingContract()

    protected abstract fun defaultAccountingContract(): InputAccountingContract

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

    // ------------------------------------------------------------------
    //  the typed structured-analysis transport (AiTranslator
    // hooks). The OpenAI-compatible family shares ONE completion shape —
    // only the endpoint URL and auth headers differ per backend, so the
    // subclass supplies those two and the base builds the payload.
    // ------------------------------------------------------------------

    override val analysisBackendId: String get() = providerBackend

    override val analysisModelId: String? get() = providerModel

    override val analysisCredentialScope: String? get() = providerCredentialScope

    /** The chat-completions endpoint for structured analysis, or null when unsupported. */
    protected open fun analysisEndpointUrl(): String? = null

    /** Auth headers for [analysisEndpointUrl] (no Content-Type — postChatCompletion enforces it). */
    protected open fun analysisHeaders(): Map<String, String> = emptyMap()

    override suspend fun postStructuredAnalysisRaw(
        systemPrompt: String,
        userPrompt: String,
        maxOutputTokens: Int,
    ): String {
        val url = analysisEndpointUrl()
            ?: throw ProviderFailureException(
                ProviderFailure(
                    kind = ProviderFailureKind.CONFIGURATION,
                    retryability = ProviderFailureRetryability.TERMINAL,
                    safeSummary = "backend has no analysis endpoint",
                ),
            )
        val clampedOutputTokens = clampAnalysisOutputToPayload(systemPrompt, userPrompt, maxOutputTokens)
        val payload = buildJsonObject {
            put("model", analysisModelId.orEmpty())
            // Structured extraction: low temperature, honest max_tokens.
            put("temperature", 0.2)
            put("max_tokens", clampedOutputTokens)
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
        }.toString()
        return postChatCompletion(
            url = url,
            headers = analysisHeaders(),
            payloadJson = payload,
            reservedOutputTokens = clampedOutputTokens,
            operation = "analysis_completion",
        )
    }

    /**
     * Analysis-only output-budget clamp, counted on the EXACT payload the
     * dispatch guard will re-count (zero estimate drift): when the honest
     * final-input count leaves less room than requested, shrink the
     * reservation to what fits under the 8k window instead of letting the
     * guard refuse the chunk. Below the analysis output floor the
     * reservation is returned unchanged — the guard's refusal remains the
     * honest outcome for an unfixably oversized input.
     */
    private fun clampAnalysisOutputToPayload(
        systemPrompt: String,
        userPrompt: String,
        requestedOutputTokens: Int,
    ): Int {
        val contract = inputAccountingContract
        if (contract == null || !contract.isCertified) return requestedOutputTokens
        val probe = buildJsonObject {
            put("model", analysisModelId.orEmpty())
            put("temperature", 0.2)
            put("max_tokens", requestedOutputTokens)
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
        }.toString()
        val finalInputTokens = contract.countFinalTokens(probe)
        val available = 8_192 - 512 - finalInputTokens
        if (available >= requestedOutputTokens || available < ANALYSIS_MIN_OUTPUT_TOKENS) {
            return requestedOutputTokens
        }
        logcat(LogPriority.WARN) {
            "TachiyomiAT analysis output budget clamped: backend=$providerBackend " +
                "requested=$requestedOutputTokens clamped=$available finalInputTokens=$finalInputTokens"
        }
        return available
    }

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
        val contract = inputAccountingContract
        if (contract == null || !contract.isCertified) {
            throw ProviderFailureException(
                ProviderFailure(
                    kind = ProviderFailureKind.CONFIGURATION,
                    retryability = ProviderFailureRetryability.TERMINAL,
                    safeSummary = "dispatch refused: provider '$providerBackend' has no certified InputAccountingContract",
                ),
            )
        }
        val finalInputTokens = contract.countFinalTokens(payloadJson)
        if (finalInputTokens + reservedOutputTokens + 512 > 8_192) {
            throw ProviderFailureException(
                ProviderFailure(
                    kind = ProviderFailureKind.CONFIGURATION,
                    retryability = ProviderFailureRetryability.TERMINAL,
                    safeSummary = "dispatch refused under 8k: final input ($finalInputTokens) + output ($reservedOutputTokens) + 512 > 8192 for $providerBackend",
                ),
            )
        }

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
            estimatedInputTokens = finalInputTokens,
            reservedOutputTokens = reservedOutputTokens,
            operation = operation,
            envelopeId = envelopeId,
            priority = currentProviderRequestPriority(),
            traceRuns = TranslationTrace.currentRuns(),
            traceProvider = if (providerBackend.startsWith("lmstudio", ignoreCase = true)) {
                TranslationTraceProvider.LOCAL
            } else {
                TranslationTraceProvider.REMOTE
            },
        )
        val result = requestGovernor.executeWithUsage(metadata) {
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
                ProviderHttpResult(
                    value = raw,
                    usage = raw.body.openAiProviderUsage(),
                )
            }
        }
        val response = result.value

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

        val rawOutput = if (responseString.trimStart().startsWith("data:") || responseString.contains("\ndata:")) {
            parseSseResponse(responseString)
        } else {
            Json.parseToJsonElement(responseString).jsonObject["choices"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("message")
                ?.jsonObject
                ?.get("content")
                ?.jsonPrimitive
                ?.contentOrNull
        }

        if (rawOutput.isNullOrBlank()) {
            logcat(LogPriority.WARN) {
                "event=provider_response_empty reason=no_content status=${response.code} " +
                    "chars=${responseString.length} responseHash=${ShortHash.hash(responseString)}"
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

    companion object {
        /** Analysis JSON below this size cannot carry a usable record set. */
        private const val ANALYSIS_MIN_OUTPUT_TOKENS = 1_024

        /**
         * Parses an SSE response body (`data:` lines).
         * Extracts delta.content, text, or message.content from streaming chunks until [DONE].
         */
        fun parseSseResponse(body: String): String {
            val builder = StringBuilder()
            for (rawLine in body.lineSequence()) {
                val line = rawLine.trim()
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isBlank()) continue
                try {
                    val root = Json.parseToJsonElement(data).jsonObject
                    val choices = root["choices"]?.jsonArray ?: continue
                    val firstChoice = choices.firstOrNull()?.jsonObject ?: continue
                    val deltaObj = firstChoice["delta"]?.jsonObject
                    val content = deltaObj?.get("content")?.jsonPrimitive?.content
                        ?: firstChoice["text"]?.jsonPrimitive?.content
                        ?: firstChoice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content
                    if (!content.isNullOrEmpty()) {
                        builder.append(content)
                    }
                } catch (_: Exception) {
                    // Ignore malformed intermediate frames
                }
            }
            return builder.toString()
        }
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
        val envelopeId = ShortHash.hash(chunk.pages.keys.joinToString("|"))
        val serStartNs = System.nanoTime()
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
        )
        val payloadJson = buildPayload(systemPrompt, finalPrompt)
        val serEndNs = System.nanoTime()
        val serializeMs = (serEndNs - serStartNs) / 1_000_000.0

        val httpStartNs = System.nanoTime()
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
                envelopeId = envelopeId,
            )
        }
        val httpEndNs = System.nanoTime()
        val httpTransitMs = (httpEndNs - httpStartNs) / 1_000_000.0

        val deserStartNs = System.nanoTime()
        val batch = if (request.protocol == ContextualRequestProtocol.BATCH_V1) {
            ContextualResponseParser.parseBatch(rawOutput, request).also { b ->
                if (b.framingRecovered) {
                    logcat(LogPriority.WARN) {
                        "event=contextual_framing_recovered requested=${b.requestedIds.size} " +
                            "warnings=${b.validationErrors.size}"
                    }
                }
            }
        } else {
            val parsed = ContextualResponseParser.parse(rawOutput.lineSequence().toList(), request.idMap)
            ContextualRequestBuilder.toBatch(request, parsed)
        }
        val deserEndNs = System.nanoTime()
        val deserializeMs = (deserEndNs - deserStartNs) / 1_000_000.0
        val totalRpcMs = serializeMs + httpTransitMs + deserializeMs

        TelemetryTrace.log(
            "batch",
            "ai_envelope_transit",
            "envelopeId" to envelopeId,
            "pageCount" to chunk.pages.size,
            "serializeMs" to String.format(Locale.US, "%.1f", serializeMs),
            "httpTransitMs" to String.format(Locale.US, "%.1f", httpTransitMs),
            "deserializeMs" to String.format(Locale.US, "%.1f", deserializeMs),
            "totalRpcMs" to String.format(Locale.US, "%.1f", totalRpcMs),
        )

        return batch
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

private fun String.openAiProviderUsage(): ProviderUsage? = runCatching {
    val usage = Json.parseToJsonElement(this).jsonObject["usage"]?.jsonObject
        ?: return@runCatching null
    val inputTokens = usage["prompt_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.takeIf { it >= 0 }
    val outputTokens = usage["completion_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.takeIf { it >= 0 }
    if (inputTokens == null && outputTokens == null) {
        null
    } else {
        ProviderUsage(inputTokens = inputTokens, outputTokens = outputTokens)
    }
}.getOrNull()

internal class OpenAiApiException(
    failure: ProviderFailure,
) : ProviderFailureException(failure)
