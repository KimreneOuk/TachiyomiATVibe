package eu.kanade.translation.translator

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.ocr.TextRecognizerLanguage
import logcat.logcat
import org.json.JSONObject

class GeminiTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    private val apiKey: String,
    private val modelName: String,
    val maxOutputToken: Int,
    val temp: Float,
) : ContextualTextTranslator {

    private var model: GenerativeModel = GenerativeModel(
        modelName = modelName,
        apiKey = apiKey,
        generationConfig = generationConfig {
            topK = 30
            topP = 0.5f
            temperature = temp
            maxOutputTokens = maxOutputToken
            responseMimeType = "application/json"
        },
        safetySettings = listOf(
            SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
            SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE),
            SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.NONE),
            SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.NONE),
        ),
        systemInstruction = content {
            text(
                TranslationPrompts.jsonSystemPrompt(fromLang, toLang),
            )
        },
    )

    private fun createContextualModel(outputTokenLimit: Int): GenerativeModel = GenerativeModel(
        modelName = modelName,
        apiKey = apiKey,
        generationConfig = generationConfig {
            topK = 30
            topP = 0.5f
            temperature = temp
            maxOutputTokens = outputTokenLimit
            responseMimeType = "application/json"
        },
        safetySettings = listOf(
            SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
            SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE),
            SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.NONE),
            SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.NONE),
        ),
        systemInstruction = content {
            text(
                TranslationPrompts.jsonSystemPrompt(fromLang, toLang),
            )
        },
    )

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
        try {
            val data = pages.mapValues { (k, v) -> v.blocks.map { b -> TranslationPrompts.jsonSourceValue(b) } }
            val json = JSONObject(data)
            val prefix = TranslationPrompts.contextPrefix(rollingContext, glossary)
            val prompt = if (prefix.isEmpty()) json.toString() else prefix + "JSON $json"
            val hasContext = rollingContext.isNotBlank() || glossary.isNotBlank()
            val activeModel = if (!hasContext && outputTokenLimit == maxOutputToken) {
                model
            } else {
                createContextualModel(outputTokenLimit)
            }
            val response = activeModel.generateContent(prompt)
            // TachiyomiAT: response.text is null when the model refuses, is
            // safety-filtered, or errors internally. The old code did
            // JSONObject("${response.text}") which turned a null into the literal
            // string "null" and then threw a JSONException deep in org.json. Fail
            // early with a clear, typed message so the page is marked FAILED with
            // an actionable cause instead.
            val responseText = response.text
            if (responseText.isNullOrBlank()) {
                throw GeminiEmptyResponseException(
                    "Gemini returned an empty response (refused, safety-filtered, or error).",
                )
            }
            val resJson = JSONObject(responseText)
            for ((k, v) in pages) {
                // TachiyomiAT: log when the model returned a different number of
                // translations than blocks for this page. Previously a mismatch
                // silently fell back to the original (untranslated) text with no
                // signal, making it look like translation "just didn't work".
                val expected = v.blocks.size
                val actual = resJson.optJSONArray(k)?.length() ?: 0
                if (expected != actual) {
                    logcat {
                        "Gemini response length mismatch for '$k': expected=$expected actual=$actual " +
                            "(mismatched blocks stay blank, retried by pipeline PARTIAL recovery)"
                    }
                }
                // TachiyomiAT: do NOT fall back to `b.text` when the model returns
                // null/"NULL"/missing. Leaving translation blank lets the batch
                // validation gate mark the block/page PARTIAL/FAILED instead of
                // passing OCR text off as a translation (the mixed-source-text
                // bug). The renderer no longer falls back to `b.text` either.
                v.blocks.forEachIndexed { i, b ->
                    val res = resJson.optJSONArray(k)?.optString(i, "NULL")
                    if (res != null && res != "NULL" && res.isNotBlank()) {
                        b.translation = OcrArtifactSanitizer.sanitize(res)
                    }
                }
            }
            TranslationBlockFilters.removeWatermarkBlocks(pages)
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
 * TachiyomiAT: thrown when the Gemini model returns no usable text (refusal,
 * safety filter, or internal error). Distinct from a generic [Exception] so the
 * caller can report a clear cause to the user instead of a low-level JSON parse
 * error.
 */
class GeminiEmptyResponseException(message: String) : Exception(message)
