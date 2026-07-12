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
import logcat.LogPriority

class LmStudioTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    baseUrl: String,
    val modelName: String,
    val maxOutputToken: Int,
    val temperature: Float,
) : OpenAiCompatibleTranslator() {

    private val normalizedBaseUrl = AiModelFetcher.normalizeBaseUrl(baseUrl)

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
            val textBlocksStr = flatBlocks.mapIndexed { index, (block, _) ->
                TranslationPrompts.numberedSourceLine(index, block)
            }.joinToString("\n")
            val contextPrefix = TranslationPrompts.contextPrefix(rollingContext, glossary)

            val systemPrompt = TranslationPrompts.numberedSystemPrompt(fromLang, toLang)

            val mediaType = "application/json; charset=utf-8".toMediaType()
            logcat(LogPriority.INFO) {
                "LM Studio request: pages=${pages.size} blocks=${flatBlocks.size} " +
                    "promptTokens=${TranslationContextChunkPlanner.estimateTokens(contextPrefix + textBlocksStr)} " +
                    "chunkPromptTokens=${if (rollingContext.isBlank()) -1 else TranslationContextChunkPlanner.estimateTokens(rollingContext)} " +
                    "maxOutput=$outputTokenLimit"
            }
            val jsonObject = buildJsonObject {
                put("model", modelName)
                put("temperature", temperature)
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

            val rawOutput = postChatCompletion(
                url = "$normalizedBaseUrl/chat/completions",
                headers = emptyMap(),
                payloadJson = jsonObject,
            )

            val parsedTranslations = NumberedLineResponseParser.parse(
                raw = rawOutput,
                expectedCount = flatBlocks.size,
                targetLang = toLang,
            )
            // Log parse yield: a common failure mode is the model ignoring the [index] format AND
            // the positional fallback (e.g. returning one prose paragraph), so parse yields 0 entries.
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
            // Never fall back to source text on a blank/missing line: a blank translation lets the
            // validation gate mark the block/page PARTIAL/FAILED instead of rendering OCR as a translation.
            flatBlocks.forEachIndexed { index, (block, _) ->
                val translated = parsedTranslations[index]
                if (!translated.isNullOrBlank()) {
                    block.translation = OcrArtifactSanitizer.sanitize(translated)
                }
            }
            TranslationBlockFilters.removeWatermarkBlocks(pages)
        } catch (e: Exception) {
            logcat { "LM Studio Translation Error : ${e.stackTraceToString()}" }
            throw e
        }
    }

    override suspend fun promptText(prompt: String): String {
        return try {
            val jsonObject = buildJsonObject {
                put("model", modelName)
                put("temperature", temperature)
                put("max_tokens", maxOutputToken)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    }
                }
            }.toString()

            postChatCompletion(
                url = "$normalizedBaseUrl/chat/completions",
                headers = emptyMap(),
                payloadJson = jsonObject,
            )
        } catch (e: Exception) {
            logcat { "LM Studio promptText Error : ${e.stackTraceToString()}" }
            ""
        }
    }
}
