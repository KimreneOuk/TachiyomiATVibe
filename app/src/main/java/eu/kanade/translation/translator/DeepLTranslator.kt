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
) : BaseTranslator() {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        if (apiKey.isBlank()) {
            throw IllegalArgumentException("DeepL API key is required")
        }

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

        // Shape-check: an API error (bad key, quota, unsupported lang, rate limit) returns a
        // body with no "translations" array (or plain text); log code + body snippet for diagnosis.
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

        // Never fall back to source text on a blank line: an empty translation lets the
        // validation gate mark the block/page PARTIAL/FAILED instead of passing OCR as a translation.
        flatBlocks.forEachIndexed { index, block ->
            val translated = translations.optJSONObject(index)?.optString("text").orEmpty()
            if (translated.isNotBlank()) block.translation = translated
        }
    }

    override fun close() {
        // Release this client's pool + dispatcher threads; translators are rebuilt on
        // every language change, so a no-op close() leaks each retired pool.
        okHttpClient.connectionPool.evictAll()
        okHttpClient.dispatcher.executorService.shutdown()
    }
}
