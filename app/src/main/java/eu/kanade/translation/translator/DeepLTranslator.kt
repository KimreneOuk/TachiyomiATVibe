package eu.kanade.translation.translator

import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.util.ShortHash
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
    private val requestGovernor: ProviderRequestGovernor = SharedProviderRequestGovernor.instance,
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

        val metadata = ProviderRequestMetadata(
            key = ProviderRequestKey(
                backend = "deepl",
                credentialScope = ShortHash.hash(apiKey).ifEmpty { null },
            ),
            estimatedInputTokens = flatBlocks.sumOf { TranslationContextChunkPlanner.estimateTokens(it.text) },
            operation = "translate",
            envelopeId = ShortHash.hash(pages.keys.joinToString("|")),
            priority = currentProviderRequestPriority(),
        )
        val response = withTranslationRetry(
            logTag = "deepl",
            envelopePageKeys = pages.keys,
        ) {
            requestGovernor.executeValue(metadata) {
                okHttpClient.newCall(request).await().use { response ->
                    val raw = RawDeepLResponse(
                        code = response.code,
                        retryAfter = response.header("Retry-After"),
                        body = response.body?.string().orEmpty(),
                    )
                    if (raw.code !in 200..299) {
                        throw ProviderFailureException(
                            classifyHttpFailure(
                                backend = "deepl",
                                statusCode = raw.code,
                                retryAfterHeader = raw.retryAfter,
                                safeSummary = "DeepL HTTP ${raw.code}",
                            ),
                        )
                    }
                    raw
                }
            }
        }
        if (response.body.isBlank()) {
            throw ProviderFailureException(
                ProviderFailure(
                    kind = ProviderFailureKind.PROTOCOL,
                    retryability = ProviderFailureRetryability.TERMINAL,
                    statusCode = response.code,
                    safeSummary = "DeepL returned an empty response body",
                ),
            )
        }
        val responseString = response.body

        // Shape-check: an API error (bad key, quota, unsupported lang, rate limit) returns a
        // body with no "translations" array (or plain text); log status + a response fingerprint.
        val translations = try {
            JSONObject(responseString).optJSONArray("translations")
        } catch (e: Exception) {
            logcat(LogPriority.WARN) {
                "event=provider_response_invalid backend=deepl reason=parse_failure " +
                    "status=${response.code} responseHash=${ShortHash.hash(responseString)}"
            }
            throw ProviderFailureException(
                ProviderFailure(
                    kind = ProviderFailureKind.PROTOCOL,
                    retryability = ProviderFailureRetryability.TERMINAL,
                    statusCode = response.code,
                    safeSummary = "DeepL returned an unparseable response",
                ),
                e,
            )
        }
        if (translations == null || translations.length() != flatBlocks.size) {
            logcat(LogPriority.WARN) {
                "event=provider_response_invalid backend=deepl reason=count_mismatch " +
                    "expected=${flatBlocks.size} got=${translations?.length() ?: 0} " +
                    "status=${response.code} responseHash=${ShortHash.hash(responseString)}"
            }
            throw ProviderFailureException(
                ProviderFailure(
                    kind = ProviderFailureKind.PROTOCOL,
                    retryability = ProviderFailureRetryability.TERMINAL,
                    statusCode = response.code,
                    safeSummary = "DeepL returned an unexpected translation count",
                ),
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

    private data class RawDeepLResponse(
        val code: Int,
        val retryAfter: String?,
        val body: String,
    )
}
