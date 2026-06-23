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
import logcat.LogPriority

class LmStudioTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    baseUrl: String,
    val modelName: String,
    val maxOutputToken: Int,
    val temp: Float,
) : ContextualTextTranslator {

    private val normalizedBaseUrl = AiModelFetcher.normalizeBaseUrl(baseUrl)

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        translateInternal(pages, rollingContext = "", outputTokenLimit = maxOutputToken)
    }

    override suspend fun translateContextual(chunk: TranslationContextChunk) {
        translateInternal(chunk.pages, chunk.rollingContext, chunk.maxOutputTokens)
    }

    private suspend fun translateInternal(
        pages: MutableMap<String, PageTranslation>,
        rollingContext: String,
        outputTokenLimit: Int,
    ) {
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
            val contextPrefix = if (rollingContext.isBlank()) {
                ""
            } else {
                "Previous concise context/glossary/recent pairs:\n$rollingContext\n\n"
            }

            val systemPrompt = """
                You are an expert manga/comic translator and localization specialist. Translate the following list of sequential text blocks from ${fromLang.label} to ${toLang.label}.

                CRITICAL GUIDELINES:
                1. READING ORDER: Manga panels and bubbles fundamentally follow a Right-to-Left (RTL) and Top-to-Bottom (TTB) flow. Interpret sequential blocks with this context in mind.
                2. BUBBLE SIZE & CONCISENESS: Manga speech bubbles have very limited space. Keep translations concise, natural, and close to the original length.
                3. STYLE & TONE: Adapt register, slang, dialect, and sound effects to fit the character and scene.
                4. WATERMARKS: Replace watermark or site-link text with RTMTH.
                5. NO EXTRA TEXT: Output only the translations in the exact numbered format below, one block per line. Do not include explanations, notes, or preambles.
                6. SCRIPT FIDELITY: If the target language is English or any Latin-script language, do NOT output Japanese/Chinese/Korean characters. Localize sound-effect parentheses like (笑) to "lol", "(laugh)", or an equivalent in the target language.

                Format:
                [index] translation
            """.trimIndent()

            val mediaType = "application/json; charset=utf-8".toMediaType()
            logcat(LogPriority.INFO) {
                "LM Studio request: pages=${pages.size} blocks=${flatBlocks.size} " +
                    "promptTokens=${TranslationContextChunkPlanner.estimateTokens(contextPrefix + textBlocksStr)} " +
                    "chunkPromptTokens=${if (rollingContext.isBlank()) -1 else TranslationContextChunkPlanner.estimateTokens(rollingContext)} " +
                    "maxOutput=$outputTokenLimit"
            }
            val jsonObject = buildJsonObject {
                put("model", modelName)
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
            val request = Request.Builder()
                .url("$normalizedBaseUrl/chat/completions")
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            val response = okHttpClient.newCall(request).await()
            val responseBody = response.body
                ?: throw IllegalStateException("Empty response body from LM Studio API")
            val responseStr = responseBody.string()
            val responseJson = JSONObject(responseStr)
            // TachiyomiAT: surface the raw LM Studio response so a blank/partial
            // translation is diagnosable. The HTTP code, the presence of choices,
            // the content length, and a snippet of the content are logged
            // unconditionally — this path was previously a black box (no logs on
            // success), which hid the root cause of whole-chapter blank output.
            val choicesArr = responseJson.optJSONArray("choices")
            val rawOutput = choicesArr?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")
            logcat(LogPriority.INFO) {
                "LM Studio response: http=${response.code} hasChoices=${choicesArr != null} " +
                    "choicesLen=${choicesArr?.length() ?: -1} contentLen=${rawOutput?.length ?: -1} " +
                    "finishReason=${choicesArr?.optJSONObject(0)?.optString("finish_reason")}"
            }
            if (rawOutput.isNullOrBlank()) {
                val snippet = if (responseStr.length > 300) responseStr.substring(0, 300) else responseStr
                logcat(LogPriority.WARN) {
                    "LM Studio returned no usable content. Raw response snippet: $snippet"
                }
                throw IllegalStateException(
                    "LM Studio returned no content (choices missing or empty): " +
                        responseJson.optString("error", responseJson.toString()),
                )
            }

            val parsedTranslations = NumberedLineResponseParser.parse(
                raw = rawOutput,
                expectedCount = flatBlocks.size,
                targetLang = toLang,
            )
            // TachiyomiAT: log the parse yield. A common failure mode is the
            // model ignoring the [index] text format AND the positional fallback
            // (e.g. it returns a single prose paragraph) — parse then yields 0
            // entries and every block stays blank. Surface that here.
            val parsedCount = parsedTranslations.count { (_, v) -> v.isNotBlank() }
            logcat(LogPriority.INFO) {
                "LM Studio parse: requested=${flatBlocks.size} parsed=$parsedCount " +
                    "contentFirstLine=${rawOutput.lineSequence().firstOrNull()?.take(80)}"
            }
            if (parsedCount < flatBlocks.size) {
                val missingCount = flatBlocks.size - parsedCount
                logcat(LogPriority.WARN) {
                    "LM Studio response parsed $parsedCount/${flatBlocks.size} translations; " +
                        "remainingMissing=$missingCount. The batch pipeline will retry missing blocks " +
                        "with smaller requests when possible."
                }
            }
            // TachiyomiAT: do NOT fall back to the source text when the model
            // returns a blank/missing line. See DeepSeekTranslator for the full
            // rationale (leaving translation blank lets the batch validation
            // gate mark the block/page PARTIAL/FAILED instead of rendering OCR
            // text as a translation).
            flatBlocks.forEachIndexed { index, (block, _) ->
                val translated = parsedTranslations[index]
                if (!translated.isNullOrBlank()) {
                    block.translation = translated
                }
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
