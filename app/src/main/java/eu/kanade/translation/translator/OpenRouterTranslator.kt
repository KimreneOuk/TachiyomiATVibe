package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.ocr.TextRecognizerLanguage
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import logcat.logcat

class OpenRouterTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    val apiKey: String,
    val modelName: String,
    val maxOutputToken: Int,
    val temperature: Float,
) : OpenAiCompatibleTranslator() {

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
            protocol = ContextualRequestProtocol.LEGACY,
        )
        translateContextual(chunk)
    }

    override suspend fun translateContextualStructured(chunk: TranslationContextChunk): ContextualTranslationBatch {
        return parseContextualCompletion(
            chunk = chunk,
            url = "https://openrouter.ai/api/v1/chat/completions",
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            logTag = "OpenRouterTranslator",
        ) { systemPrompt, finalPrompt ->
            buildJsonObject {
                put("model", modelName)
                put("top_p", 0.5f)
                put("top_k", 30)
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

    override suspend fun promptText(prompt: String): String {
        return try {
            val jsonObject = buildJsonObject {
                put("model", modelName)
                put("top_p", 0.5f)
                put("top_k", 30)
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
                url = "https://openrouter.ai/api/v1/chat/completions",
                headers = mapOf("Authorization" to "Bearer $apiKey"),
                payloadJson = jsonObject,
            )
        } catch (e: Exception) {
            logcat { "event=provider_failure backend=openrouter stage=prompt error=${e::class.java.simpleName}" }
            ""
        }
    }
}
