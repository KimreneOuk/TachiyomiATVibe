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
import java.util.regex.Pattern

class DeepSeekTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    val apiKey: String,
    val modelName: String,
    val maxOutputToken: Int,
    val temp: Float,
) : TextTranslator {

    // TachiyomiAT: tightened from 180s → 60s. The batch path now wraps every
    // page in withTimeoutOrNull(120s), so an HTTP timeout within that period
    // gives the permit a chance to release timely rather than eating all 120s.
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val ocrArtifactPattern = "(?:[N\\uff2e][\\u00ba\\u00b0\\u02da]|[N\\uff2e]\\u2070|\\u2116|\\uff2e\\uff10|N0)"
    private val ocrArtifactInlineRe = Regex("\\s+$ocrArtifactPattern(?=\\s|$)")
    private val ocrArtifactBeforePunctRe = Regex("\\s+$ocrArtifactPattern(?=[.,!?;:\\-])")
    private val ocrArtifactLeadingRe = Regex("^$ocrArtifactPattern\\s*")

    private fun sanitizeOcrArtifacts(text: String): String {
        var cleaned = ocrArtifactBeforePunctRe.replace(text, "")
        cleaned = ocrArtifactInlineRe.replace(cleaned, " ")
        cleaned = ocrArtifactLeadingRe.replace(cleaned, "")
        cleaned = Regex("\\s{2,}").replace(cleaned, " ").trim()
        return cleaned
    }

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        if (apiKey.isBlank()) {
            throw IllegalArgumentException("DeepSeek API key is required")
        }

        // Flatten all text blocks from all pages to pass to DeepSeek in one single batch
        val flatBlocks = mutableListOf<Pair<TranslationBlock, String>>()
        for ((_, page) in pages) {
            for (block in page.blocks) {
                if (block.text.isNotBlank()) {
                    flatBlocks.add(Pair(block, block.text))
                }
            }
        }

        if (flatBlocks.isEmpty()) return

        try {
            val textBlocksStr = flatBlocks.mapIndexed { index, (_, text) ->
                "[$index] $text"
            }.joinToString("\n")

            val systemPrompt = """
                You are an expert manga/comic translator and localization specialist. Translate the following list of sequential text blocks from ${fromLang.label} to ${toLang.label}.

                CRITICAL GUIDELINES:
                1. READING ORDER: Manga panels and bubbles fundamentally follow a Right-to-Left (RTL) and Top-to-Bottom (TTB) flow. Interpret the sequential blocks with this context in mind to maintain narrative coherence across adjacent speech bubbles.
                2. HONORIFICS: Honorifics (-san, -kun, -chan, -sama, -senpai, etc.) are highly expressive of character relationships. Preserve them natively (e.g., 'Taro-kun') if the tone is character-driven/anime-style, or translate them to natural relational equivalents (like 'Mr.', 'Sir', or dropping them) if a more conventional western localization is appropriate for the dialogue.
                3. BUBBLE SIZE & CONCISENESS: Manga speech bubbles have very limited space. Keep your translations highly concise, punchy, and natural. Avoid wordy phrasing. The length of the translated text should roughly match the original block size.
                4. STYLE & TONE: Adapt register, slang, and dialect to fit character personalities. For sound effects (SFX) / onomatopoeia, provide standard comic-styled english/localized equivalents (e.g., 'Gasp', 'Thud', *rumble*).
                5. NO EXTRA TEXT: Your output MUST contain only the translations in the exact numbered format below, one block per line. Do not include explanations, notes, or preambles.
                6. OCR ARTIFACTS: The source text comes from OCR and may contain misread glyphs such as "N0", "N°", "Nº", "№", or "Ｎ０". These are NOT meaningful — they are scanner misreads of Japanese characters like の. Do NOT preserve or translate them literally. Simply omit them and translate the intended meaning naturally.

                Format:
                [index] translation
            """.trimIndent()

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val jsonObject = buildJsonObject {
                put("model", if (modelName.isBlank()) "deepseek-chat" else modelName)
                put("temperature", temp)
                put("max_tokens", maxOutputToken)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    }
                    addJsonObject {
                        put("role", "user")
                        put("content", "Translate these ${fromLang.label} text blocks to ${toLang.label}:\n\n$textBlocksStr")
                    }
                }
            }.toString()

            val body = jsonObject.toRequestBody(mediaType)
            val build: Request = Request.Builder()
                .url("https://api.deepseek.com/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            val response = okHttpClient.newCall(build).await()
            val rBody = response.body ?: throw IllegalStateException("Empty response body from DeepSeek API")
            val responseJson = JSONObject(rBody.string())
            // TachiyomiAT: shape-check the response. A DeepSeek API error (rate
            // limit, bad key, server error) returns JSON with no "choices" array;
            // the old code did getJSONArray("choices") unconditionally and
            // JSONException'd with an opaque message.
            val rawOutput = responseJson.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")
            if (rawOutput.isNullOrBlank()) {
                throw IllegalStateException(
                    "DeepSeek returned no content (choices missing or empty): " +
                        responseJson.optString("error", responseJson.toString()),
                )
            }

            val parsedTranslations = parseResponse(rawOutput, flatBlocks.size)

            flatBlocks.forEachIndexed { index, (block, originalText) ->
                var translated = parsedTranslations[index] ?: ""
                if (translated.isBlank()) {
                    translated = originalText
                }
                block.translation = sanitizeOcrArtifacts(translated)
            }
            TranslationBlockFilters.removeWatermarkBlocks(pages)

        } catch (e: Exception) {
            logcat { "DeepSeek Translation Error : ${e.stackTraceToString()}" }
            throw e
        }
    }

    private fun parseResponse(raw: String, expectedCount: Int): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        val pattern = Pattern.compile("^\\[(\\d+)\\]\\s*(.+)$", Pattern.MULTILINE)
        val matcher = pattern.matcher(raw)
        while (matcher.find()) {
            val idx = matcher.group(1)!!.toInt()
            val text = matcher.group(2)!!.trim()
            result[idx] = text
        }

        if (result.isEmpty()) {
            val lines = raw.trim().split("\n")
            lines.forEachIndexed { i, line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && i < expectedCount) {
                    result[i] = trimmed
                }
            }
        }
        return result
    }

    override fun close() {
        // TachiyomiAT: release this translator's connection pool + dispatcher
        // threads. TranslationEngineBuilder rebuilds translators on every language
        // change, and a no-op close() left each retired client's pool (and its
        // idle threads) alive for the process lifetime, slowly leaking. This
        // does NOT evict shared/coil clients — only this instance's own pool.
        okHttpClient.connectionPool.evictAll()
        okHttpClient.dispatcher.executorService.shutdown()
    }
}
