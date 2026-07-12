package eu.kanade.translation.translator

import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.ocr.TextRecognizerLanguage
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
) : TextTranslator {
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
        val newCall = okHttpClient.newCall(build)
        val response = newCall.await()
        val body = response.body
            ?: run {
                logcat(LogPriority.WARN) {
                    "GoogleTranslator: empty response body for lang=$lang text=\"$text\" code=${response.code}"
                }
                return ""
            }
        val string = body.string()
        try {
            val jSONArray = JSONArray(string).getJSONArray(0).getJSONArray(0)
            return jSONArray.getString(0)
        } catch (e: Exception) {
            // Google's free endpoint returns 429/HTML (not JSON) on rate-limiting or bot-detection,
            // which this catch previously turned into "" with no visible error — blank pages with
            // no cause. Log the code + a body snippet so the real cause is diagnosable.
            val snippet = if (string.length > 200) string.substring(0, 200) else string
            logcat(LogPriority.WARN, e) {
                "GoogleTranslator: parse failed for lang=$lang text=\"$text\" " +
                    "code=${response.code} body=\"$snippet\""
            }
        }
        return ""
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
            j = RL(j + num.toLong(), "+-a^+6")
        }
        var rL = RL(j, "+-3^+b+-f") xor 3293161072L
        if (rL < 0) {
            rL = (rL and 2147483647L) + 2147483648L
        }
        val j2 = rL % 1000000L
        return "$j2.${406644L xor j2}"
    }

    private fun RL(j: Long, str: String): Long {
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

}
