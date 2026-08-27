package eu.kanade.translation.translator

import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.util.ShortHash
import logcat.LogPriority
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import tachiyomi.core.common.util.system.logcat
import java.io.UnsupportedEncodingException
import java.net.URLEncoder

class GoogleTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    private val requestGovernor: ProviderRequestGovernor = SharedProviderRequestGovernor.instance,
) : BaseTranslator() {
    private val client1 = "gtx"
    private val client2 = "webapp"
    val okHttpClient = OkHttpClient()

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        // Pin sl=fromLang.code (not the old hardcoded "auto"). "auto" made Google guess the source
        // per request, a silent fallback that hid misconfigured OCR language settings and produced
        // inconsistent results across blocks. Pinning makes the configured language authoritative.
        pages.mapValues { (_, v) ->
            v.blocks.map { b ->
                b.translation = translateText(toLang.code, fromLang.code, b.text)
            }
        }
    }

    private suspend fun translateText(lang: String, sourceLang: String, text: String): String {
        if (text.isBlank()) return ""
        val access = getTranslateUrl(lang, sourceLang, text)
        val build: Request = Request.Builder().url(access).build()
        val metadata = ProviderRequestMetadata(
            key = ProviderRequestKey(backend = "google"),
            estimatedInputTokens = TranslationContextChunkPlanner.estimateTokens(text),
            operation = "translate",
            envelopeId = ShortHash.hash(text),
            priority = currentProviderRequestPriority(),
        )
        val response = withTranslationRetry(logTag = "google") {
            requestGovernor.executeValue(metadata) {
                okHttpClient.newCall(build).await().use { response ->
                    val raw = RawGoogleResponse(
                        code = response.code,
                        retryAfter = response.header("Retry-After"),
                        body = response.body?.string().orEmpty(),
                    )
                    if (raw.code !in 200..299) {
                        val failure = classifyHttpFailure(
                            backend = "google",
                            statusCode = raw.code,
                            retryAfterHeader = raw.retryAfter,
                            safeSummary = "Google Translate HTTP ${raw.code}",
                        )
                        throw ProviderFailureException(failure)
                    }
                    raw
                }
            }
        }
        val string = response.body
        if (string.isBlank()) {
            logcat(LogPriority.WARN) {
                "event=provider_response_empty backend=google reason=empty_body lang=$lang " +
                    "inputChars=${text.length} status=${response.code}"
            }
            return ""
        }
        try {
            val jSONArray = JSONArray(string).getJSONArray(0).getJSONArray(0)
            return jSONArray.getString(0)
        } catch (e: Exception) {
            // Google's free endpoint returns 429/HTML (not JSON) on rate-limiting or bot-detection,
            // which this catch previously turned into "" with no visible error — blank pages with
            // no cause. Log only status, counts, and a response fingerprint.
            logcat(LogPriority.WARN) {
                "event=provider_response_invalid backend=google reason=parse_failure lang=$lang " +
                    "inputChars=${text.length} status=${response.code} responseHash=${ShortHash.hash(string)}"
            }
            return ""
        }
    }

    private fun getTranslateUrl(lang: String, sourceLang: String, text: String): String {
        try {
            val client = client1
            val calculateToken = calculateToken(text)
            val encode: String = URLEncoder.encode(text, "utf-8")
            return "https://translate.google.com/translate_a/single?client=$client&sl=$sourceLang&tl=$lang&dt=at&dt=bd&dt=ex&dt=ld&dt=md&dt=qca&dt=rw&dt=rm&dt=ss&dt=t&otf=1&ssel=0&tsel=0&kc=1&tk=$calculateToken&q=$encode"
        } catch (unused: UnsupportedEncodingException) {
            val client2 = client1
            val calculateToken2 = calculateToken(text)
            return "https://translate.google.com/translate_a/single?client=$client2&sl=$sourceLang&tl=$lang&dt=at&dt=bd&dt=ex&dt=ld&dt=md&dt=qca&dt=rw&dt=rm&dt=ss&dt=t&otf=1&ssel=0&tsel=0&kc=1&tk=$calculateToken2&q=$text"
        }
    }

    private fun calculateToken(str: String): String {
        val list = mutableListOf<Int>()
        var i = 0

        while (i < str.length) {
            val charCodeAt = str.codePointAt(i)
            when {
                charCodeAt < 128 -> list.add(charCodeAt)
                charCodeAt < 2048 -> {
                    list.add((charCodeAt shr 6) or 192)
                    list.add((charCodeAt and 63) or 128)
                }
                charCodeAt in 55296..57343 && i + 1 < str.length -> {
                    val nextChar = str.codePointAt(i + 1)
                    if (nextChar in 56320..57343) {
                        val codePoint = ((charCodeAt and 1023) shl 10) + (nextChar and 1023) + 65536
                        list.add((codePoint shr 18) or 240)
                        list.add(((codePoint shr 12) and 63) or 128)
                        list.add(((codePoint shr 6) and 63) or 128)
                        list.add((codePoint and 63) or 128)
                        i++
                    }
                }
                else -> {
                    list.add((charCodeAt shr 12) or 224)
                    list.add(((charCodeAt shr 6) and 63) or 128)
                    list.add((charCodeAt and 63) or 128)
                }
            }
            i++
        }

        var j: Long = 406644
        for (num in list) {
            j = rl(j + num.toLong(), "+-a^+6")
        }
        var rL = rl(j, "+-3^+b+-f") xor 3293161072L
        if (rL < 0) {
            rL = (rL and 2147483647L) + 2147483648L
        }
        val j2 = rL % 1000000L
        return "$j2.${406644L xor j2}"
    }

    private fun rl(j: Long, str: String): Long {
        var result = j
        var i = 0
        while (i < str.length - 2) {
            val shift = if (str[i + 2] in 'a'..'z') str[i + 2].code - 'W'.code else str[i + 2].digitToInt()
            val shiftValue = if (str[i + 1] == '+') result ushr shift else result shl shift
            result = if (str[i] == '+') (result + shiftValue) and 4294967295L else result xor shiftValue
            i += 3
        }
        return result
    }

    override fun close() {
        okHttpClient.connectionPool.evictAll()
        okHttpClient.dispatcher.executorService.shutdown()
    }

    private data class RawGoogleResponse(
        val code: Int,
        val retryAfter: String?,
        val body: String,
    )
}
