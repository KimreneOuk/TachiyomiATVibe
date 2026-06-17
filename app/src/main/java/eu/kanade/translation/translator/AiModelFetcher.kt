package eu.kanade.translation.translator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.logcat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
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
        // Guard at the boundary so the UI can show a missing-key message
        // without ever issuing a request.
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
        } catch (e: Exception) {
            if (engine == AiEngine.LMSTUDIO) {
                logcat(LogPriority.ERROR) {
                    "LM Studio model fetch failed for '${normalizeBaseUrl(baseUrl)}/models': ${e.stackTraceToString()}"
                }
            }
            Result.Error("${e::class.java.simpleName}: ${e.message ?: "Network error"}")
        }
    }

    private fun fetchGemini(apiKey: String): List<String> {
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            validateAuth(response.code)
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return emptyList()
            val json = JSONObject(body)
            val arr = json.optJSONArray("models") ?: return emptyList()
            val result = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val model = arr.optJSONObject(i) ?: continue
                // Only surface models that can generate content; this filters
                // out embedding-only entries that can't translate text.
                val methods = model.optJSONArray("supportedGenerationMethods") ?: continue
                val supportsGenerate = (0 until methods.length()).any { idx ->
                    methods.optString(idx) == "generateContent"
                }
                if (!supportsGenerate) continue
                val rawName = model.optString("name")
                // Provider returns "models/gemini-1.5-pro"; strip the prefix.
                val id = rawName.removePrefix("models/").trim()
                if (id.isNotEmpty()) result.add(id)
            }
            return result
        }
    }

    private fun fetchOpenRouter(apiKey: String): List<String> {
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            validateAuth(response.code)
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return emptyList()
            val json = JSONObject(body)
            return parseOpenAiModels(json)
        }
    }

    private fun fetchDeepSeek(apiKey: String): List<String> {
        val request = Request.Builder()
            .url("https://api.deepseek.com/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            validateAuth(response.code)
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return emptyList()
            val json = JSONObject(body)
            // DeepSeek exposes the list under "data" like OpenAI/OpenRouter.
            return parseOpenAiModels(json)
        }
    }

    private fun fetchLmStudio(baseUrl: String): List<String> {
        val url = "${normalizeBaseUrl(baseUrl)}/models"
        logcat(LogPriority.INFO) { "LM Studio fetching models from '$url'" }
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            validateAuth(response.code)
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return emptyList()
            return parseOpenAiModels(JSONObject(body))
        }
    }

    fun normalizeBaseUrl(baseUrl: String): String =
        baseUrl.trim().trimEnd('/')

    fun parseOpenAiModels(json: JSONObject): List<String> {
        val arr = json.optJSONArray("data") ?: return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val id = arr.optJSONObject(i)?.optString("id")?.trim().orEmpty()
            if (id.isNotEmpty()) result.add(id)
        }
        return result
    }

    private fun validateAuth(code: Int) {
        if (code == 401 || code == 403) throw InvalidKeyException("Invalid or expired API key")
        if (code !in 200..299) throw RuntimeException("HTTP $code")
    }

    private class InvalidKeyException(message: String) : Exception(message)
}
