package eu.kanade.translation.engines.translator.providers
import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.engines.translator.GeminiInputAccountingContract
import eu.kanade.translation.engines.translator.InputAccountingContract
import eu.kanade.translation.engines.translator.ProviderFailure
import eu.kanade.translation.engines.translator.ProviderFailureException
import eu.kanade.translation.engines.translator.ProviderFailureKind
import eu.kanade.translation.engines.translator.ProviderFailureRetryability
import eu.kanade.translation.engines.translator.ProviderRequestGovernor
import eu.kanade.translation.engines.translator.ProviderRequestKey
import eu.kanade.translation.engines.translator.ProviderRequestMetadata
import eu.kanade.translation.engines.translator.SharedProviderRequestGovernor
import eu.kanade.translation.engines.translator.TextTranslatorLanguage
import eu.kanade.translation.engines.translator.contextual.ContextualRequestBuilder
import eu.kanade.translation.engines.translator.contextual.ContextualRequestProtocol
import eu.kanade.translation.engines.translator.contextual.ContextualResponseParser
import eu.kanade.translation.engines.translator.contextual.ContextualTranslationBatch
import eu.kanade.translation.engines.translator.contextual.TranslationContextChunk
import eu.kanade.translation.engines.translator.contextual.TranslationPrompts
import eu.kanade.translation.engines.translator.contextual.applyBatchToChunk
import eu.kanade.translation.engines.translator.currentProviderRequestPriority
import eu.kanade.translation.engines.translator.retry.classifyHttpFailure
import eu.kanade.translation.engines.translator.retry.classifyHttpFailureWithRetryAfterMillis
import eu.kanade.translation.engines.translator.retry.parseRetryAfterMillis
import eu.kanade.translation.engines.translator.retry.withTranslationRetry
import eu.kanade.translation.engines.vision.ocr.TextRecognizerLanguage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.util.ShortHash
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import logcat.logcat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.domain.translation.GeminiThinkingMode
import java.util.concurrent.TimeUnit

open class GeminiTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    private val apiKey: String,
    private val modelName: String,
    val maxOutputToken: Int,
    val temp: Float,
    private val thinkingMode: GeminiThinkingMode = GeminiThinkingMode.DISABLED,
    private val requestGovernor: ProviderRequestGovernor = SharedProviderRequestGovernor.instance,
    val customAccountingContract: InputAccountingContract? = null,
) : AiTranslator() {

    open override val inputAccountingContract: InputAccountingContract?
        get() = customAccountingContract ?: GeminiInputAccountingContract(modelName)

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        val linkedPages = LinkedHashMap(pages)
        val blockCount = linkedPages.values.sumOf { it.blocks.size }
        translateContextual(
            TranslationContextChunk(
                pages = linkedPages,
                blockCount = blockCount,
                rollingContext = "",
                glossary = "",
                estimatedPromptTokens = 0,
                maxOutputTokens = maxOutputToken,
                protocol = ContextualRequestProtocol.LEGACY,
            ),
        )
    }

    override suspend fun translateContextual(chunk: TranslationContextChunk): ContextualTranslationBatch {
        val batch = translateContextualStructured(chunk)
        applyBatchToChunk(chunk, batch)
        return batch
    }

    override suspend fun translateContextualStructured(
        chunk: TranslationContextChunk,
    ): ContextualTranslationBatch {
        val request = ContextualRequestBuilder.buildFor(chunk, fromLang, toLang)
        if (request.promptLines.isEmpty()) return ContextualRequestBuilder.toBatch(request, emptyList())

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
        val responseText = withTranslationRetry(
            logTag = "gemini",
            envelopePageKeys = chunk.pages.keys,
        ) {
            generateContent(
                systemPrompt = systemPrompt,
                prompt = finalPrompt,
                maxOutputTokens = chunk.maxOutputTokens,
            )
        }
        return if (request.protocol == ContextualRequestProtocol.BATCH_V1) {
            ContextualResponseParser.parseBatch(responseText, request)
        } else {
            ContextualRequestBuilder.toBatch(
                request,
                ContextualResponseParser.parse(responseText.lineSequence().toList(), request.idMap),
            )
        }
    }

    // ------------------------------------------------------------------
    //  the typed structured-analysis transport (AiTranslator
    // hooks). generateContent already throws typed failures
    // (GeminiApiException : ProviderFailureException) and makes ONE raw
    // attempt — exactly the transport contract.
    // ------------------------------------------------------------------

    override val analysisBackendId: String = "gemini"

    override val analysisModelId: String get() = modelName

    override val analysisCredentialScope: String? get() = ShortHash.hash(apiKey).ifEmpty { null }

    override suspend fun postStructuredAnalysisRaw(
        systemPrompt: String,
        userPrompt: String,
        maxOutputTokens: Int,
    ): String = generateContent(
        systemPrompt = systemPrompt,
        prompt = userPrompt,
        maxOutputTokens = clampAnalysisOutputToPayload(systemPrompt, userPrompt, maxOutputTokens),
    )

    /**
     * Analysis-only output-budget clamp, counted on the EXACT payload the
     * dispatch guard will re-count (zero estimate drift by construction):
     * when the honest final-input count leaves less room than requested,
     * shrink the reservation to what fits under the 8k window instead of
     * letting the guard refuse the chunk. Below the analysis output floor
     * the reservation is returned unchanged — the guard's refusal (mapped
     * to a typed PAUSE upstream) remains the honest outcome for an
     * unfixably oversized input.
     */
    private fun clampAnalysisOutputToPayload(
        systemPrompt: String,
        prompt: String,
        requestedOutputTokens: Int,
    ): Int {
        val contract = inputAccountingContract
        if (contract == null || !contract.isCertified) return requestedOutputTokens
        val probe = GeminiRequestPayload.create(
            modelName = modelName,
            systemPrompt = systemPrompt,
            prompt = prompt,
            maxOutputTokens = requestedOutputTokens,
            temperature = temp,
            thinkingMode = thinkingMode,
        )
        val finalInputTokens = contract.countFinalTokens(probe.payload)
        val available = 8_192 - 512 - finalInputTokens
        if (available >= requestedOutputTokens || available < ANALYSIS_MIN_OUTPUT_TOKENS) {
            return requestedOutputTokens
        }
        logcat(LogPriority.WARN, tag = "GeminiTranslator") {
            "event=analysis_output_budget_clamped model=${ShortHash.hash(modelName)} " +
                "requested=$requestedOutputTokens clamped=$available finalInputTokens=$finalInputTokens"
        }
        return available
    }

    override suspend fun promptText(prompt: String): String =
        try {
            withTranslationRetry(logTag = "gemini") {
                generateContent(systemPrompt = null, prompt = prompt, maxOutputTokens = maxOutputToken)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logProviderFailure(stage = "prompt", error = e)
            ""
        }

    private suspend fun generateContent(
        systemPrompt: String?,
        prompt: String,
        maxOutputTokens: Int,
    ): String {
        val requestConfig = GeminiRequestPayload.create(
            modelName = modelName,
            systemPrompt = systemPrompt,
            prompt = prompt,
            maxOutputTokens = maxOutputTokens,
            temperature = temp,
            thinkingMode = thinkingMode,
        )
        return try {
            post(requestConfig.payload, maxOutputTokens)
        } catch (e: GeminiApiException) {
            if (e.statusCode != 400 || !requestConfig.hasThinkingConfig) throw e
            logcat(tag = "GeminiTranslator", priority = LogPriority.WARN) {
                "event=gemini_thinking_fallback model=${ShortHash.hash(modelName)} status=${e.statusCode}"
            }
            post(requestConfig.payloadWithoutThinking, maxOutputTokens)
        }
    }

    private suspend fun post(payload: String, reservedOutputTokens: Int): String {
        val contract = inputAccountingContract
        if (contract == null || !contract.isCertified) {
            throw ProviderFailureException(
                ProviderFailure(
                    kind = ProviderFailureKind.CONFIGURATION,
                    retryability = ProviderFailureRetryability.TERMINAL,
                    safeSummary = "dispatch refused: provider 'gemini' has no certified InputAccountingContract",
                ),
            )
        }
        val finalInputTokens = contract.countFinalTokens(payload)
        if (finalInputTokens + reservedOutputTokens + 512 > 8_192) {
            throw ProviderFailureException(
                ProviderFailure(
                    kind = ProviderFailureKind.CONFIGURATION,
                    retryability = ProviderFailureRetryability.TERMINAL,
                    safeSummary = "dispatch refused under 8k: final input ($finalInputTokens) + output ($reservedOutputTokens) + 512 > 8192 for gemini",
                ),
            )
        }
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent")
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val metadata = ProviderRequestMetadata(
            key = ProviderRequestKey(
                backend = "gemini",
                model = modelName,
                credentialScope = ShortHash.hash(apiKey).ifEmpty { null },
            ),
            estimatedInputTokens = finalInputTokens,
            reservedOutputTokens = reservedOutputTokens,
            operation = "generate_content",
            envelopeId = ShortHash.hash(payload),
            priority = currentProviderRequestPriority(),
        )
        val response = requestGovernor.executeValue(metadata) {
            client.newCall(request).await().use { response ->
                val raw = RawGeminiResponse(
                    code = response.code,
                    retryAfter = response.header("Retry-After"),
                    body = response.body?.string().orEmpty(),
                )
                if (raw.code !in 200..299) {
                    val retryAfterMillis = parseRetryAfterMillis(raw.retryAfter)
                    val error = raw.body.toGeminiErrorSummary()
                    val failure = classifyHttpFailure(
                        backend = "gemini",
                        statusCode = raw.code,
                        retryAfterHeader = raw.retryAfter,
                        providerCode = error.code?.toString(),
                        providerStatus = error.statusName,
                        safeSummary = "Gemini HTTP ${raw.code}",
                    )
                    logcat(tag = "GeminiTranslator", priority = LogPriority.WARN) {
                        "event=provider_http_failure backend=gemini status=${raw.code} " +
                            "retryAfterMs=${retryAfterMillis ?: 0} code=${error.code ?: 0} " +
                            "statusName=${error.statusName ?: "none"} responseHash=${ShortHash.hash(raw.body)}"
                    }
                    throw GeminiApiException(raw.code, retryAfterMillis, error.code, error.statusName, failure)
                }
                raw
            }
        }
        return response.body.extractGeminiText()
    }

    private fun logProviderFailure(stage: String, error: Exception) {
        val status = (error as? GeminiApiException)?.statusCode ?: 0
        val retryAfter = (error as? GeminiApiException)?.retryAfterMillis ?: 0
        logcat(tag = "GeminiTranslator", priority = LogPriority.WARN) {
            "event=provider_failure backend=gemini stage=$stage status=$status retryAfterMs=$retryAfter " +
                "error=${error::class.java.simpleName}"
        }
    }

    override fun close() {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Analysis JSON below this size cannot carry a usable record set. */
        const val ANALYSIS_MIN_OUTPUT_TOKENS = 1_024
    }

    private data class RawGeminiResponse(
        val code: Int,
        val retryAfter: String?,
        val body: String,
    )
}

internal data class GeminiPayload(
    val payload: String,
    val payloadWithoutThinking: String,
    val hasThinkingConfig: Boolean,
)

internal object GeminiRequestPayload {
    fun create(
        modelName: String,
        systemPrompt: String?,
        prompt: String,
        maxOutputTokens: Int,
        temperature: Float,
        thinkingMode: GeminiThinkingMode,
    ): GeminiPayload {
        val thinkingConfig = thinkingConfig(modelName, thinkingMode)
        fun payload(includeThinking: Boolean): String = Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("contents", buildJsonArray { add(content(prompt)) })
                put(
                    "generationConfig",
                    buildJsonObject {
                        put("temperature", JsonPrimitive(temperature))
                        put("topP", JsonPrimitive(0.5))
                        put("topK", JsonPrimitive(30))
                        put("maxOutputTokens", JsonPrimitive(maxOutputTokens))
                        thinkingConfig?.takeIf { includeThinking }?.let { put("thinkingConfig", it) }
                    },
                )
                put("safetySettings", safetySettings())
                systemPrompt?.let { put("systemInstruction", content(it)) }
            },
        )

        val hasThinkingConfig = thinkingConfig != null
        return GeminiPayload(
            payload = payload(includeThinking = true),
            payloadWithoutThinking = payload(includeThinking = false),
            hasThinkingConfig = hasThinkingConfig,
        )
    }

    private fun thinkingConfig(modelName: String, mode: GeminiThinkingMode): JsonObject? {
        val normalized = modelName.lowercase()
        return when {
            mode == GeminiThinkingMode.AUTO -> null
            normalized.startsWith("gemini-3") && mode == GeminiThinkingMode.LOW ->
                buildJsonObject { put("thinkingLevel", JsonPrimitive("low")) }
            normalized.startsWith("gemini-2.5-flash") && mode == GeminiThinkingMode.DISABLED ->
                buildJsonObject { put("thinkingBudget", JsonPrimitive(0)) }
            normalized.startsWith("gemini-2.5") && mode == GeminiThinkingMode.LOW ->
                buildJsonObject { put("thinkingBudget", JsonPrimitive(1_024)) }
            else -> null
        }
    }

    private fun content(text: String): JsonObject = buildJsonObject {
        put("parts", buildJsonArray { add(buildJsonObject { put("text", JsonPrimitive(text)) }) })
    }

    private fun safetySettings(): JsonArray = buildJsonArray {
        listOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT",
        ).forEach { category ->
            add(
                buildJsonObject {
                    put("category", JsonPrimitive(category))
                    put("threshold", JsonPrimitive("BLOCK_NONE"))
                },
            )
        }
    }
}

internal class GeminiApiException(
    val statusCode: Int,
    val retryAfterMillis: Long?,
    val providerCode: Int?,
    val providerStatus: String?,
    failure: ProviderFailure? = null,
) : ProviderFailureException(
    failure ?: classifyHttpFailureWithRetryAfterMillis(
        backend = "gemini",
        statusCode = statusCode,
        retryAfterMillis = retryAfterMillis,
        providerCode = providerCode?.toString(),
        providerStatus = providerStatus,
    ),
)

private data class GeminiErrorSummary(val code: Int?, val statusName: String?)

private fun String.toGeminiErrorSummary(): GeminiErrorSummary = runCatching {
    val error = Json.parseToJsonElement(this).jsonObject["error"]?.jsonObject
        ?: return@runCatching GeminiErrorSummary(null, null)
    GeminiErrorSummary(
        code = error["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        statusName = error["status"]?.jsonPrimitive?.contentOrNull,
    )
}.getOrDefault(GeminiErrorSummary(null, null))

internal fun String.extractGeminiText(): String {
    val parts = Json.parseToJsonElement(this).jsonObject["candidates"]
        ?.jsonArray
        ?.firstOrNull()
        ?.jsonObject
        ?.get("content")
        ?.jsonObject
        ?.get("parts")
        ?.jsonArray
        ?: throw GeminiEmptyResponseException("Gemini returned no response candidate")
    val text = buildString {
        parts.forEach { part ->
            val partObject = part.jsonObject
            if (partObject["thought"]?.jsonPrimitive?.booleanOrNull != true) {
                append(partObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
            }
        }
    }.trim()
    if (text.isBlank()) throw GeminiEmptyResponseException("Gemini returned no usable text")
    return text
}

class GeminiEmptyResponseException(message: String) : Exception(message)
