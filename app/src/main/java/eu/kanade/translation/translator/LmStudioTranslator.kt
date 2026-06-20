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
import java.util.concurrent.TimeUnit

class LmStudioTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    baseUrl: String,
    val modelName: String,
    val maxOutputToken: Int,
    val temp: Float,
) : TextTranslator {

    private val normalizedBaseUrl = AiModelFetcher.normalizeBaseUrl(baseUrl)

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        if (normalizedBaseUrl.isBlank()) {
            throw IllegalArgumentException("LM Studio base URL is required")
        }
        if (modelName.isBlank()) {
            throw IllegalArgumentException("LM Studio model is required")
        }

        val flatBlocks = mutableListOf<Pair<TranslationBlock, String>>()
        for ((_, page) in pages) {
            for (block in page.blocks) {
                if (block.text.isNotBlank()) {
                    flatBlocks.add(block to block.text)
                }
            }
        }

        if (flatBlocks.isEmpty()) return

        try {
            val textBlocksStr = flatBlocks.mapIndexed { index, (_, text) ->
                "[$index] $text"
            }.joinToString("\n")

            val systemPrompt = """
                You are an expert manga/comic translator and localization specialist. Translate the following list of sequential text blocks from ${fromLang.label} to ${toLang.label}.

                CRITICAL GUIDELINES:
                1. READING ORDER: Manga panels and bubbles fundamentally follow a Right-to-Left (RTL) and Top-to-Bottom (TTB) flow. Interpret sequential blocks with this context in mind.
                2. BUBBLE SIZE & CONCISENESS: Manga speech bubbles have very limited space. Keep translations concise, natural, and close to the original length.
                3. STYLE & TONE: Adapt register, slang, dialect, and sound effects to fit the character and scene.
                4. WATERMARKS: Replace watermark or site-link text with RTMTH.
                5. NO EXTRA TEXT: Output only the translations in the exact numbered format below, one block per line. Do not include explanations, notes, or preambles.

                Format:
                [index] translation
            """.trimIndent()

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val jsonObject = buildJsonObject {
                put("model", modelName)
                put("temperature", temp)
                put("max_tokens", maxOutputToken)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    }
                    addJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            "Translate these ${fromLang.label} text blocks to ${toLang.label}:\n\n$textBlocksStr",
                        )
                    }
                }
            }.toString()

            val body = jsonObject.toRequestBody(mediaType)
            val request = Request.Builder()
                .url("$normalizedBaseUrl/chat/completions")
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            val response = okHttpClient.newCall(request).await()
            val responseBody = response.body
                ?: throw IllegalStateException("Empty response body from LM Studio API")
            val responseJson = JSONObject(responseBody.string())
            val rawOutput = responseJson.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")
            if (rawOutput.isNullOrBlank()) {
                throw IllegalStateException(
                    "LM Studio returned no content (choices missing or empty): " +
                        responseJson.optString("error", responseJson.toString()),
                )
            }

            val parsedTranslations = NumberedLineResponseParser.parse(rawOutput, flatBlocks.size)
            flatBlocks.forEachIndexed { index, (block, originalText) ->
                val translated = parsedTranslations[index].takeUnless { it.isNullOrBlank() } ?: originalText
                block.translation = translated
            }
            TranslationBlockFilters.removeWatermarkBlocks(pages)
        } catch (e: Exception) {
            logcat { "LM Studio Translation Error : ${e.stackTraceToString()}" }
            throw e
        }
    }

    override fun close() {
        okHttpClient.connectionPool.evictAll()
        okHttpClient.dispatcher.executorService.shutdown()
    }
}
