package eu.kanade.translation.translator.providers
import eu.kanade.translation.translator.currentProviderRequestPriority
import eu.kanade.translation.translator.ProviderFailureException
import eu.kanade.translation.translator.retry.classifyHttpFailure
import eu.kanade.translation.translator.SharedProviderRequestGovernor
import eu.kanade.translation.translator.ProviderRequestMetadata
import eu.kanade.translation.translator.ProviderRequestKey
import eu.kanade.translation.translator.ProviderRequestGovernor
import eu.kanade.translation.translator.ProviderFailureRetryability
import eu.kanade.translation.translator.ProviderFailureKind
import eu.kanade.translation.translator.ProviderFailure

import eu.kanade.translation.util.ShortHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import logcat.logcat
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.domain.translation.AiEngine
import java.util.concurrent.TimeUnit

/**
 * Fetches the list of available model ids directly from each AI provider.
 *
 * Per the refactor plan:
 * - Do not fetch if the provider key is missing (the caller is responsible
 *   for surfacing the missing-key message).
 * - Treat authentication failures (401/403) as invalid-key errors.
 * - On any failure the caller keeps the currently selected model.
 * - The returned ids are unfiltered; local search/highlighting is handled UI-side.
 */
object AiModelFetcher {

    /** Shared with text translation so model discovery cannot burst around quotas. */
    internal var requestGovernor: ProviderRequestGovernor = SharedProviderRequestGovernor.instance

    /** Outcome of a fetch attempt. The UI never throws from this. */
    sealed class Result {
        data class Success(val models: List<String>) : Result()
        data object InvalidKey : Result()
        data object NoModels : Result()
        data class Error(val message: String) : Result()
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetch(engine: AiEngine, apiKey: String, baseUrl: String = ""): Result = withContext(Dispatchers.IO) {
        // Guard at the boundary so the UI shows a missing-key message without issuing a request.
        if (engine == AiEngine.LMSTUDIO) {
            if (baseUrl.isBlank()) return@withContext Result.Error("Base URL is required")
        } else if (apiKey.isBlank()) {
            return@withContext Result.Error("API key is required")
        }

        try {
            val models = when (engine) {
                AiEngine.GEMINI -> fetchGemini(apiKey)
                AiEngine.OPENROUTER -> fetchOpenRouter(apiKey)
                AiEngine.DEEPSEEK -> fetchDeepSeek(apiKey)
                AiEngine.LMSTUDIO -> fetchLmStudio(baseUrl)
            }
            if (models.isEmpty()) Result.NoModels else Result.Success(models)
        } catch (e: InvalidKeyException) {
            Result.InvalidKey
        } catch (e: ProviderFailureException) {
            // Provider failures already carry a redacted, provider-neutral summary.
            // Keep the UI contract safe if a transport is deferred or rejected.
            Result.Error(e.failure.safeSummary)
        } catch (e: Exception) {
            if (engine == AiEngine.LMSTUDIO) {
                logcat(LogPriority.ERROR) {
                    "event=model_fetch_failure backend=lm_studio baseHash=${ShortHash.hash(normalizeBaseUrl(baseUrl))} " +
                        "error=${e::class.java.simpleName}"
                }
            }
            // Exception messages can embed URLs, response bodies, or other
            // user/provider data. Diagnostics retain only the exception type;
            // the UI receives a stable safe summary.
            Result.Error("Model list request failed")
        }
    }

    private suspend fun fetchGemini(apiKey: String): List<String> {
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
            .get()
            .build()

        val metadata = modelListMetadata("gemini", apiKey, request.url.toString())
        val response = requestGovernor.executeValue(metadata) {
            client.newCall(request).execute().use { response ->
                RawModelResponse(response.code, response.header("Retry-After"), response.body?.string().orEmpty()).also {
                    validateAuth(it)
                }
            }
        }
        if (response.body.isBlank()) return emptyList()
        return parseGeminiModels(Json.parseToJsonElement(response.body).jsonObject)
    }

    private suspend fun fetchOpenRouter(apiKey: String): List<String> {
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()

        val metadata = modelListMetadata("openrouter", apiKey, request.url.toString())
        val response = requestGovernor.executeValue(metadata) {
            client.newCall(request).execute().use { response ->
                RawModelResponse(response.code, response.header("Retry-After"), response.body?.string().orEmpty()).also {
                    validateAuth(it)
                }
            }
        }
        if (response.body.isBlank()) return emptyList()
        return parseOpenAiModels(Json.parseToJsonElement(response.body).jsonObject)
    }

    private suspend fun fetchDeepSeek(apiKey: String): List<String> {
        val request = Request.Builder()
            .url("https://api.deepseek.com/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()

        val metadata = modelListMetadata("deepseek", apiKey, request.url.toString())
        val response = requestGovernor.executeValue(metadata) {
            client.newCall(request).execute().use { response ->
                RawModelResponse(response.code, response.header("Retry-After"), response.body?.string().orEmpty()).also {
                    validateAuth(it)
                }
            }
        }
        if (response.body.isBlank()) return emptyList()
        // DeepSeek exposes the list under "data" like OpenAI/OpenRouter.
        return parseOpenAiModels(Json.parseToJsonElement(response.body).jsonObject)
    }

    private suspend fun fetchLmStudio(baseUrl: String): List<String> {
        val url = "${normalizeBaseUrl(baseUrl)}/models"
        logcat(LogPriority.INFO) {
            "event=model_fetch_start backend=lm_studio baseHash=${ShortHash.hash(normalizeBaseUrl(baseUrl))}"
        }
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val metadata = ProviderRequestMetadata(
            key = ProviderRequestKey(
                backend = "lm_studio",
                credentialScope = ShortHash.hash(normalizeBaseUrl(baseUrl)).ifEmpty { null },
            ),
            operation = "model_list",
            envelopeId = ShortHash.hash(request.url.toString()),
            priority = currentProviderRequestPriority(),
        )
        val response = requestGovernor.executeValue(metadata) {
            client.newCall(request).execute().use { response ->
                RawModelResponse(response.code, response.header("Retry-After"), response.body?.string().orEmpty()).also {
                    validateAuth(it)
                }
            }
        }
        if (response.body.isBlank()) return emptyList()
        return parseOpenAiModels(Json.parseToJsonElement(response.body).jsonObject)
    }

    fun normalizeBaseUrl(baseUrl: String): String =
        baseUrl.trim().trimEnd('/')

    fun parseOpenAiModels(json: JsonObject): List<String> {
        val arr = json["data"]?.jsonArray ?: return emptyList()
        return arr.mapNotNull { entry ->
            entry.jsonObject.stringOrNull("id")?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * Parse the Gemini `/models` response. Only models that advertise
     * `generateContent` in `supportedGenerationMethods` are surfaced — this
     * filters out embedding/vision-only entries that cannot translate text. The
     * provider returns ids like `models/gemini-1.5-pro`; the prefix is stripped.
     */
    fun parseGeminiModels(json: JsonObject): List<String> {
        val arr = json["models"]?.jsonArray ?: return emptyList()
        val result = mutableListOf<String>()
        for (entry in arr) {
            val model = entry.jsonObject
            val methods = model["supportedGenerationMethods"]?.jsonArray ?: continue
            val supportsGenerate = methods.any { it.contentOrNull() == "generateContent" }
            if (!supportsGenerate) continue
            val id = model.stringOrNull("name")?.removePrefix("models/")?.trim()
            if (!id.isNullOrEmpty()) result.add(id)
        }
        return result
    }

    /**
     * Null-safe string read from a [JsonObject]. Returns null when the key is
     * absent OR its value is JSON null — matching `org.json`'s `optString`
     * semantics (which the original code relied on) rather than throwing on a
     * `JsonNull` value like `?.jsonPrimitive?.content` would.
     */
    private fun JsonObject.stringOrNull(key: String): String? {
        val element = this[key] ?: return null
        return element.contentOrNull()
    }

    /**
     * Null-safe string read from a single [JsonElement]. Returns null for
     * `JsonNull` (or non-primitive) elements instead of throwing.
     */
    private fun JsonElement.contentOrNull(): String? {
        if (this is JsonNull) return null
        return jsonPrimitive.content
    }

    private suspend fun modelListMetadata(backend: String, apiKey: String, url: String): ProviderRequestMetadata =
        ProviderRequestMetadata(
            key = ProviderRequestKey(
                backend = backend,
                credentialScope = ShortHash.hash(apiKey).ifEmpty { null },
            ),
            operation = "model_list",
            envelopeId = ShortHash.hash(url),
            priority = currentProviderRequestPriority(),
        )

    private fun validateAuth(response: RawModelResponse) {
        if (response.code == 401 || response.code == 403) {
            throw InvalidKeyException("Invalid or expired API key")
        }
        if (response.code !in 200..299) {
            throw ProviderFailureException(
                classifyHttpFailure(
                    backend = "model_list",
                    statusCode = response.code,
                    retryAfterHeader = response.retryAfter,
                    safeSummary = "Model list request failed with HTTP ${response.code}",
                ),
            )
        }
    }

    private data class RawModelResponse(
        val code: Int,
        val retryAfter: String?,
        val body: String,
    )

    private class InvalidKeyException(message: String) : ProviderFailureException(
        ProviderFailure(
            kind = ProviderFailureKind.AUTHENTICATION,
            retryability = ProviderFailureRetryability.TERMINAL,
            statusCode = 401,
            safeSummary = message,
        ),
    )
}
