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

class DeepSeekTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    val apiKey: String,
    val modelName: String,
    val maxOutputToken: Int,
    val temp: Float,
) : ContextualTextTranslator {

    // TachiyomiAT: tightened from 180s → 60s. The batch path now wraps every
    // page in withTimeoutOrNull(120s), so an HTTP timeout within that period
    // gives the permit a chance to release timely rather than eating all 120s.
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        translateInternal(pages, rollingContext = "", outputTokenLimit = maxOutputToken)
    }

    override suspend fun translateContextual(chunk: TranslationContextChunk) {
        translateInternal(chunk.pages, chunk.rollingContext, chunk.maxOutputTokens)
    }

    private suspend fun translateInternal(
        pages: MutableMap<String, PageTranslation>,
        rollingContext: String,
        outputTokenLimit: Int,
    ) {
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
            val contextPrefix = if (rollingContext.isBlank()) {
                ""
            } else {
                "Previous concise context/glossary/recent pairs:\n$rollingContext\n\n"
            }

            val systemPrompt = """
                You are an expert manga/comic translator and localization specialist. Translate the following list of sequential text blocks from ${fromLang.label} to ${toLang.label}.

                CRITICAL GUIDELINES:
                1. READING ORDER: The sequential blocks are loosely ordered based on physical coordinates (Top-to-Bottom, then Left-to-Right or Right-to-Left depending on the format). However, complex comic panel layouts mean this numbering is just a nudge. Use your narrative judgment to connect dialogue logically across adjacent speech bubbles if the numbered sequence seems slightly out of order.
                2. HONORIFICS: Honorifics (-san, -kun, -chan, -sama, -senpai, etc.) are highly expressive of character relationships. Preserve them natively (e.g., 'Taro-kun') if the tone is character-driven/anime-style, or translate them to natural relational equivalents (like 'Mr.', 'Sir', or dropping them) if a more conventional western localization is appropriate for the dialogue.
                3. BUBBLE SIZE & CONCISENESS: Manga speech bubbles have very limited space. Keep your translations highly concise, punchy, and natural. Avoid wordy phrasing. The length of the translated text should roughly match the original block size.
                4. STYLE & TONE: Adapt register, slang, and dialect to fit character personalities. For sound effects (SFX) / onomatopoeia, provide standard comic-styled english/localized equivalents (e.g., 'Gasp', 'Thud', *rumble*).
                5. NO EXTRA TEXT: Your output MUST contain only the translations in the exact numbered format below, one block per line. Do not include explanations, notes, or preambles.
                6. OCR ARTIFACTS: The source text comes from OCR and may contain misread glyphs such as "N0", "N°", "Nº", "№", or "Ｎ０". These are NOT meaningful — they are scanner misreads of Japanese characters like の. Do NOT preserve or translate them literally. Simply omit them and translate the intended meaning naturally.
                7. SCRIPT FIDELITY: If the target language is English or any Latin-script language, do NOT output Japanese/Chinese/Korean characters. Localize sound-effect parentheses like (笑) to "lol", "(laugh)", or an equivalent in the target language.

                Format:
                [index] translation
            """.trimIndent()

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val jsonObject = buildJsonObject {
                put("model", if (modelName.isBlank()) "deepseek-chat" else modelName)
                put("temperature", temp)
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

            val parsedTranslations = NumberedLineResponseParser.parse(
                raw = rawOutput,
                expectedCount = flatBlocks.size,
                targetLang = toLang,
            )

            // TachiyomiAT: do NOT fall back to the source text when the model
            // returns a blank/missing line. Leaving `block.translation` empty
            // lets the batch validation gate (TranslationPipeline) mark this
            // block/page as PARTIAL/FAILED instead of silently passing OCR text
            // off as a successful translation — the root cause of pages that
            // mixed real translations with untouched source text. The renderer
            // no longer falls back to `block.text` either, so a blank
            // translation renders as nothing rather than the original text.
            flatBlocks.forEachIndexed { index, (block, _) ->
                val translated = parsedTranslations[index] ?: ""
                if (translated.isNotBlank()) {
                    block.translation = OcrArtifactSanitizer.sanitize(translated)
                }
            }
            TranslationBlockFilters.removeWatermarkBlocks(pages)
        } catch (e: Exception) {
            logcat { "DeepSeek Translation Error : ${e.stackTraceToString()}" }
            throw e
        }
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
