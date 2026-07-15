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
) : ContextualTextTranslator {

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        val linkedPages = LinkedHashMap(pages)
        val blockCount = linkedPages.values.sumOf { it.blocks.size }
        val chunk = TranslationContextChunk(
            pages = linkedPages,
            blockCount = blockCount,
            rollingContext = "",
            glossary = "",
            estimatedPromptTokens = 0,
            maxOutputTokens = maxOutputToken
        )
        translateContextual(chunk, isPass2 = false)
    }

    override suspend fun translateContextual(chunk: TranslationContextChunk, isPass2: Boolean) {
        // Delegate to the structured path then apply accepted results in place so
        // legacy callers that read block.translation directly keep working.
        val batch = translateContextualStructured(chunk, isPass2)
        applyBatchToChunk(chunk, batch, isPass2)
    }

    override suspend fun translateContextualStructured(
        chunk: TranslationContextChunk,
        isPass2: Boolean,
    ): ContextualTranslationBatch {
        val request = ContextualRequestBuilder.build(chunk, isPass2, fromLang, toLang)
        if (request.promptLines.isEmpty()) {
            return ContextualRequestBuilder.toBatch(request, emptyList(), isPass2)
        }

        try {
            val systemPrompt = if (isPass2) {
                TranslationPrompts.pass2SystemPrompt(fromLang, toLang)
            } else {
                TranslationPrompts.pass1SystemPrompt(fromLang, toLang)
            }

            val contextPrefix = TranslationPrompts.contextPrefix(chunk.rollingContext, chunk.glossary)
            val promptBody = request.promptLines.joinToString("\n")
            val finalPrompt = if (contextPrefix.isEmpty()) promptBody else contextPrefix + promptBody

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
                }
            )

            val response = activeModel.generateContent(finalPrompt)
            val responseText = response.text
            if (responseText.isNullOrBlank()) {
                throw GeminiEmptyResponseException(
                    "Gemini returned an empty response (refused, safety-filtered, or error).",
                )
            }

            val lines = responseText.split("\n")
            val parsed = ContextualResponseParser.parse(lines, request.idMap, isPass2)
            return ContextualRequestBuilder.toBatch(request, parsed, isPass2)
        } catch (e: Exception) {
            logcat { "Image Translation Error : ${e.stackTraceToString()}" }
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
            logcat { "Gemini promptText Error : ${e.stackTraceToString()}" }
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
