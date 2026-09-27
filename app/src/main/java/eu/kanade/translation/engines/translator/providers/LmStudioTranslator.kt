package eu.kanade.translation.engines.translator.providers
import eu.kanade.translation.engines.translator.InputAccountingContract
import eu.kanade.translation.engines.translator.LmStudioInputAccountingContract
import eu.kanade.translation.engines.translator.ProviderRequestGovernor
import eu.kanade.translation.engines.translator.SharedProviderRequestGovernor
import eu.kanade.translation.engines.translator.TextTranslatorLanguage
import eu.kanade.translation.engines.translator.contextual.ContextualRequestProtocol
import eu.kanade.translation.engines.translator.contextual.ContextualTranslationBatch
import eu.kanade.translation.engines.translator.contextual.TranslationContextChunk
import eu.kanade.translation.engines.translator.retry.withTranslationRetry
import eu.kanade.translation.engines.vision.ocr.TextRecognizerLanguage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.util.ShortHash
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import logcat.logcat

open class LmStudioTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    baseUrl: String,
    val modelName: String,
    val maxOutputToken: Int,
    val temperature: Float,
    requestGovernor: ProviderRequestGovernor = SharedProviderRequestGovernor.instance,
    customAccountingContract: InputAccountingContract? = null,
) : OpenAiCompatibleTranslator(requestGovernor, customAccountingContract) {

    override fun defaultAccountingContract(): InputAccountingContract =
        LmStudioInputAccountingContract(modelName)

    private val normalizedBaseUrl = AiModelFetcher.normalizeBaseUrl(baseUrl)

    override val providerBackend: String = "lm_studio"
    override val providerModel: String get() = modelName
    override val providerCredentialScope: String? get() = ShortHash.hash(normalizedBaseUrl).ifEmpty { null }

    //  structured-analysis endpoint (local server, no auth).
    override fun analysisEndpointUrl(): String = "$normalizedBaseUrl/chat/completions"

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        val linkedPages = LinkedHashMap(pages)
        val blockCount = linkedPages.values.sumOf { it.blocks.size }
        val chunk = TranslationContextChunk(
            pages = linkedPages,
            blockCount = blockCount,
            rollingContext = "",
            estimatedPromptTokens = 0,
            maxOutputTokens = maxOutputToken,
            protocol = ContextualRequestProtocol.LEGACY,
        )
        translateContextual(chunk)
    }

    override suspend fun translateContextualStructured(chunk: TranslationContextChunk): ContextualTranslationBatch {
        if (normalizedBaseUrl.isBlank()) {
            throw IllegalArgumentException("LM Studio base URL is required")
        }
        if (modelName.isBlank()) {
            throw IllegalArgumentException("LM Studio model is required")
        }
        return parseContextualCompletion(
            chunk = chunk,
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

            withTranslationRetry(logTag = "lm_studio") {
                postChatCompletion(
                    url = "$normalizedBaseUrl/chat/completions",
                    headers = emptyMap(),
                    payloadJson = jsonObject,
                    reservedOutputTokens = maxOutputToken,
                    operation = "prompt",
                    envelopeId = ShortHash.hash(prompt),
                )
            }
        } catch (e: Exception) {
            logcat { "event=provider_failure backend=lm_studio stage=prompt error=${e::class.java.simpleName}" }
            ""
        }
    }
}
