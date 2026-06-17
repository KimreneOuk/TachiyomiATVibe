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
     apiKey: String,
     modelName: String,
    val maxOutputToken: Int,
    val temp: Float,
) : TextTranslator {

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
                "## System Prompt for Manhwa/Manga/Manhua Translation\n" +
                    "\n" +
                    "You are a highly skilled AI tasked with translating text from scanned images of comics (manhwa, manga, manhua) while preserving the original structure and removing any watermarks or site links. \n" +
                    "\n" +
                    "**Here's how you should operate:**\n" +
                    "\n" +
                    "1. **Input:** You'll receive a JSON object where keys are image filenames (e.g., \"001.jpg\") and values are lists of text strings extracted from those images.\n" +
                    "\n" +
                    "2. **Translation:** Translate all text strings to the target language `${toLang.label}`. Ensure the translation is natural and fluent, adapting idioms and expressions to fit the target language's cultural context.\n" +
                    "\n" +
                    "3. **Watermark/Site Link Removal:** Replace any watermarks or site links (e.g., \"colamanga.com\") with the placeholder \"RTMTH\".\n" +
                    "\n" +
                    "4. **Structure Preservation:** Maintain the exact same structure as the input JSON. The output JSON should have the same number of keys (image filenames) and the same number of text strings within each list.\n" +
                    "\n" +
                    "**Example:**\n" +
                    "\n" +
                    "**Input:**\n" +
                    "\n" +
                    "```json\n" +
                    "{\"001.jpg\":[\"chinese1\",\"chinese2\"],\"002.jpg\":[\"chinese2\",\"colamanga.com\"]}\n" +
                    "```\n" +
                    "\n" +
                    "**Output (for `${toLang.label}` = English):**\n" +
                    "\n" +
                    "```json\n" +
                    "{\"001.jpg\":[\"eng1\",\"eng2\"],\"002.jpg\":[\"eng2\",\"RTMTH\"]}\n" +
                    "```\n" +
                    "\n" +
                    "**Key Points:**\n" +
                    "\n" +
                    "* Prioritize accurate and natural-sounding translations.\n" +
                    "* Be meticulous in removing all watermarks and site links.\n" +
                    "* Ensure the output JSON structure perfectly mirrors the input structure.\n" +
                    "Return {[key:string]:Array<String>}",

                )
        },
    )

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        try {
            val data = pages.mapValues { (k, v) -> v.blocks.map { b -> b.text } }
            val json = JSONObject(data)
            val response = model.generateContent(json.toString())
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
                            "(mismatched blocks keep their original text)"
                    }
                }
                v.blocks.forEachIndexed { i, b ->
                    run {
                        val res = resJson.optJSONArray(k)?.optString(i, "NULL")
                        b.translation = if (res == null || res == "NULL") b.text else res
                    }
                }
            }
            TranslationBlockFilters.removeWatermarkBlocks(pages)
        } catch (e: Exception) {
            logcat { "Image Translation Error : ${e.stackTraceToString()}" }
            throw e
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
