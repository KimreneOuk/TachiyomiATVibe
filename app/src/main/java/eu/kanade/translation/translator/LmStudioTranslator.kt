package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.ocr.TextRecognizerLanguage
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import logcat.logcat

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
        val linkedPages = LinkedHashMap(pages)
        val blockCount = linkedPages.values.sumOf { it.blocks.size }
        val chunk = TranslationContextChunk(
            pages = linkedPages,
            blockCount = blockCount,
            rollingContext = "",
            glossary = "",
            estimatedPromptTokens = 0,
            maxOutputTokens = maxOutputToken,
        )
        translateContextual(chunk, isPass2 = false)
    }

    override suspend fun translateContextualStructured(chunk: TranslationContextChunk, isPass2: Boolean): ContextualTranslationBatch {
        if (normalizedBaseUrl.isBlank()) {
            throw IllegalArgumentException("LM Studio base URL is required")
        }
        if (modelName.isBlank()) {
            throw IllegalArgumentException("LM Studio model is required")
        }
        return parseContextualCompletion(
            chunk = chunk,
            isPass2 = isPass2,
            url = "$normalizedBaseUrl/chat/completions",
            headers = emptyMap(),
            logTag = "LmStudioTranslator",
        ) { systemPrompt, finalPrompt ->
            buildJsonObject {
                put("model", modelName)
                put("temperature", temperature)
                put("max_tokens", chunk.maxOutputTokens)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    }
                    addJsonObject {
                        put("role", "user")
                        put("content", finalPrompt)
                    }
                }
            }.toString()
        }
    }

    /**
     * Pass-2 (revision) adapter. Wires through [parseRevisionCompletion] with
     * LM Studio-specific local endpoint / no auth / payload. Returns a strict
     * [ContextualTranslationBatch] (isPass2=true) for K/C/U accounting.
     */
    suspend fun translateRevision(group: RevisionPlanner.RequestGroup): ContextualTranslationBatch {
        if (normalizedBaseUrl.isBlank()) {
            throw IllegalArgumentException("LM Studio base URL is required")
        }
        if (modelName.isBlank()) {
            throw IllegalArgumentException("LM Studio model is required")
        }
        return parseRevisionCompletion(
            group = group,
            url = "$normalizedBaseUrl/chat/completions",
            headers = emptyMap(),
            logTag = "LmStudioTranslator",
        ) { systemPrompt, userMessage ->
            buildJsonObject {
                put("model", modelName)
                put("temperature", temperature)
                put("max_tokens", group.maxOutputTokens)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    }
                    addJsonObject {
                        put("role", "user")
                        put("content", userMessage)
                    }
                }
            }.toString()
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
