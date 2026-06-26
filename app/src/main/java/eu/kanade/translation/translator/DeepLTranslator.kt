package eu.kanade.translation.translator

import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocr.TextRecognizerLanguage
import logcat.LogPriority
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat

/**
 * DeepL Standard translator.
 *
 * Sends the page set's text blocks to DeepL's v2 `translate` endpoint in a
 * single batched request (DeepL accepts repeated `text` params and returns the
 * translations in order). Endpoint + auth are resolved by [DeepLApi]; source/
 * target language codes are uppercased to DeepL's format (e.g. `zh` -> `ZH`).
 */
class DeepLTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    private val apiKey: String,
) : TextTranslator {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        if (apiKey.isBlank()) {
            throw IllegalArgumentException("DeepL API key is required")
        }

        // Flatten non-empty blocks across all pages. DeepL accepts repeated
        // `text` params and returns translations in the same order, so a single
        // request batch-translates the whole page set.
        val flatBlocks = ArrayList<TranslationBlock>()
        for ((_, page) in pages) {
            for (block in page.blocks) {
                if (block.text.isNotBlank()) flatBlocks.add(block)
            }
        }
        if (flatBlocks.isEmpty()) return

        val formBuilder = FormBody.Builder()
            .add("source_lang", fromLang.code.uppercase())
            .add("target_lang", toLang.code.uppercase())
        flatBlocks.forEach { formBuilder.add("text", it.text) }

        val request = Request.Builder()
            .url(DeepLApi.translateUrl(apiKey))
            .header("Authorization", DeepLApi.authHeader(apiKey))
            .post(formBuilder.build())
            .build()

        val response = okHttpClient.newCall(request).await()
        val body = response.body
            ?: throw IllegalStateException("Empty response body from DeepL API (code=${response.code})")
        val responseString = body.string()

        // TachiyomiAT: shape-check the response. A DeepL API error (bad key,
        // quota exhausted, unsupported lang, rate limit) returns a body with no
        // "translations" array (or plain text); reading it unconditionally would
        // throw an opaque JSONException. Log the code + a body snippet so the
        // real cause is diagnosable, matching GoogleTranslator's diagnostics.
        val translations = try {
            JSONObject(responseString).optJSONArray("translations")
        } catch (e: Exception) {
            val snippet = responseString.take(200)
            logcat(LogPriority.WARN, e) {
                "DeepLTranslator: parse failed code=${response.code} body=\"$snippet\""
            }
            throw IllegalStateException("DeepL returned an unparseable response (code=${response.code})", e)
        }
        if (translations == null || translations.length() != flatBlocks.size) {
            val snippet = responseString.take(200)
            logcat(LogPriority.WARN) {
                "DeepLTranslator: count mismatch expected=${flatBlocks.size} " +
                    "got=${translations?.length() ?: 0} code=${response.code} body=\"$snippet\""
            }
            throw IllegalStateException(
                "DeepL returned ${translations?.length() ?: 0} translations for ${flatBlocks.size} blocks (code=${response.code})",
            )
        }

        // TachiyomiAT: do NOT fall back to source text on a blank line. Leaving
        // block.translation empty lets the batch validation gate mark the block/
        // page PARTIAL/FAILED instead of silently passing OCR text off as a
        // successful translation (the source-mixed-into-output symptom).
        flatBlocks.forEachIndexed { index, block ->
            val translated = translations.optJSONObject(index)?.optString("text").orEmpty()
            if (translated.isNotBlank()) block.translation = translated
        }
    }

    override fun close() {
        // TachiyomiAT: release this translator's connection pool + dispatcher
        // threads. TranslationEngineBuilder rebuilds translators on every
        // language change; a no-op close() would leave each retired client's
        // pool (and its idle threads) alive for the process lifetime.
        okHttpClient.connectionPool.evictAll()
        okHttpClient.dispatcher.executorService.shutdown()
    }
}
