package eu.kanade.translation.translator

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.ocr.TextRecognizerLanguage
import logcat.logcat

class GeminiTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    private val apiKey: String,
    private val modelName: String,
    val maxOutputToken: Int,
    val temp: Float,
) : AITranslator() {

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

    override suspend fun translateContextual(chunk: TranslationContextChunk): ContextualTranslationBatch {
        val batch = translateContextualStructured(chunk)
        applyBatchToChunk(chunk, batch)
        return batch
    }

    override suspend fun translateContextualStructured(
        chunk: TranslationContextChunk,
    ): ContextualTranslationBatch {
        val request = ContextualRequestBuilder.buildFor(chunk, fromLang, toLang)
        if (request.promptLines.isEmpty()) {
            return ContextualRequestBuilder.toBatch(request, emptyList())
        }

        try {
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

            val activeModel = GenerativeModel(
                modelName = modelName,
                apiKey = apiKey,
                generationConfig = generationConfig {
                    topK = 30
                    topP = 0.5f
                    temperature = temp
                    maxOutputTokens = chunk.maxOutputTokens
                },
                safetySettings = listOf(
                    SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
                    SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE),
                    SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.NONE),
                    SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.NONE),
                ),
                systemInstruction = content {
                    text(systemPrompt)
                },
            )

            val response = activeModel.generateContent(finalPrompt)
            val responseText = response.text
            if (responseText.isNullOrBlank()) {
                throw GeminiEmptyResponseException(
                    "Gemini returned an empty response (refused, safety-filtered, or error).",
                )
            }

            return if (request.protocol == ContextualRequestProtocol.BATCH_V1) {
                ContextualResponseParser.parseBatch(responseText, request)
            } else {
                val parsed = ContextualResponseParser.parse(responseText.split("\n"), request.idMap)
                ContextualRequestBuilder.toBatch(request, parsed)
            }
        } catch (e: Exception) {
            logcat { "event=provider_failure backend=gemini stage=contextual error=${e::class.java.simpleName}" }
            throw e
        }
    }

    private val textModel: GenerativeModel = GenerativeModel(
        modelName = modelName,
        apiKey = apiKey,
        generationConfig = generationConfig {
            topK = 30
            topP = 0.5f
            temperature = temp
            maxOutputTokens = maxOutputToken
        },
        safetySettings = listOf(
            SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
            SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE),
            SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.NONE),
            SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.NONE),
        ),
    )

    override suspend fun promptText(prompt: String): String {
        return try {
            val response = textModel.generateContent(prompt)
            response.text ?: ""
        } catch (e: Exception) {
            logcat { "event=provider_failure backend=gemini stage=prompt error=${e::class.java.simpleName}" }
            ""
        }
    }

    override fun close() {
    }
}

/**
 * TachiyomiAT: thrown when Gemini returns no usable text (refusal, safety filter, or internal error).
 * Distinct from a generic [Exception] so the caller can report a clear cause instead of a low-level JSON error.
 */
class GeminiEmptyResponseException(message: String) : Exception(message)
