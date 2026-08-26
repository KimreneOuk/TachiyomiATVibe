package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.util.ShortHash
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import logcat.logcat

class DeepSeekTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    val apiKey: String,
    val modelName: String,
    val maxOutputToken: Int,
    val temperature: Float,
    requestGovernor: ProviderRequestGovernor = SharedProviderRequestGovernor.instance,
) : OpenAiCompatibleTranslator(requestGovernor) {

    override val providerBackend: String = "deepseek"
    override val providerModel: String get() = modelName.ifBlank { "deepseek-chat" }
    override val providerCredentialScope: String? get() = ShortHash.hash(apiKey).ifEmpty { null }

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
        if (apiKey.isBlank()) {
            throw IllegalArgumentException("DeepSeek API key is required")
        }
        return parseContextualCompletion(
            chunk = chunk,
            url = "https://api.deepseek.com/chat/completions",
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            logTag = "DeepSeekTranslator",
        ) { systemPrompt, finalPrompt ->
            buildJsonObject {
                put("model", if (modelName.isBlank()) "deepseek-chat" else modelName)
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
                put("model", if (modelName.isBlank()) "deepseek-chat" else modelName)
                put("temperature", temperature)
                put("max_tokens", maxOutputToken)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    }
                }
            }.toString()

            withTranslationRetry(logTag = "deepseek", envelopePageKeys = null) {
                postChatCompletion(
                    url = "https://api.deepseek.com/chat/completions",
                    headers = mapOf("Authorization" to "Bearer $apiKey"),
                    payloadJson = jsonObject,
                    reservedOutputTokens = maxOutputToken,
                    operation = "prompt",
                    envelopeId = ShortHash.hash(prompt),
                )
            }
        } catch (e: Exception) {
            logcat { "event=provider_failure backend=deepseek stage=prompt error=${e::class.java.simpleName}" }
            ""
        }
    }
}
