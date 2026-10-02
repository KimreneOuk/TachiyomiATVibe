package eu.kanade.translation.engines.translator.providers
import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.engines.translator.ProviderFailure
import eu.kanade.translation.engines.translator.ProviderFailureException
import eu.kanade.translation.engines.translator.ProviderFailureKind
import eu.kanade.translation.engines.translator.ProviderFailureRetryability
import eu.kanade.translation.engines.translator.ProviderRequestGovernor
import eu.kanade.translation.engines.translator.ProviderRequestKey
import eu.kanade.translation.engines.translator.ProviderRequestMetadata
import eu.kanade.translation.engines.translator.SharedProviderRequestGovernor
import eu.kanade.translation.engines.translator.contextual.TranslationContextChunkPlanner
import eu.kanade.translation.engines.translator.currentProviderRequestPriority
import eu.kanade.translation.engines.translator.retry.classifyHttpFailure
import eu.kanade.translation.engines.translator.retry.withTranslationRetry
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.util.ShortHash
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import logcat.LogPriority
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import tachiyomi.core.common.util.system.logcat
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.util.Locale

class GoogleTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    private val requestGovernor: ProviderRequestGovernor = SharedProviderRequestGovernor.instance,
    val okHttpClient: OkHttpClient = OkHttpClient(),
) : BaseTranslator() {
    private val client1 = "gtx"

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        // Pin sl=fromLang.code (not the old hardcoded "auto"). "auto" made Google guess the source
        // per request, a silent fallback that hid misconfigured OCR language settings and produced
        // inconsistent results across blocks. Pinning makes the configured language authoritative.
        pages.forEach { (_, page) ->
            translatePage(page)
        }
    }

    private suspend fun translatePage(page: PageTranslation) {
        if (page.blocks.isEmpty()) return

        val chunks = GoogleTranslationEnvelope.plan(page.blocks.map { it.text })
        if (chunks != null) {
            val translations = mutableMapOf<Int, String>()
            var envelopeValid = true
            for (chunk in chunks) {
                val expectedIds = chunk.blockIndices.map { GoogleTranslationEnvelope.idFor(it) }
                val response = requestEnvelope(
                    lang = toLang.code,
                    sourceLang = fromLang.code,
                    source = chunk.source,
                )
                val parsed = parseEnvelopeResponse(response.body, expectedIds)
                if (parsed == null) {
                    envelopeValid = false
                    break
                }
                chunk.blockIndices.zip(parsed).forEach { (index, translation) ->
                    translations[index] = translation
                }
            }
            if (envelopeValid) {
                page.blocks.forEachIndexed { index, block ->
                    block.translation = translations[index].orEmpty()
                }
                return
            }
        }

        // A malformed envelope is a protocol anomaly, not a reason to guess or
        // discard the page. Retry the existing serial per-block path for the
        // whole page; no envelope request is retried after its response fails
        // strict reconstruction.
        page.blocks.forEach { block ->
            block.translation = translateText(toLang.code, fromLang.code, block.text)
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
        val response = request(build, metadata)
        return parseSingleResponse(response.body, lang, text, response.code)
    }

    private suspend fun requestEnvelope(
        lang: String,
        sourceLang: String,
        source: String,
    ): RawGoogleResponse {
        val build = Request.Builder()
            .url(getEnvelopeUrl(lang, sourceLang, source))
            .build()
        val metadata = ProviderRequestMetadata(
            key = ProviderRequestKey(backend = "google"),
            estimatedInputTokens = TranslationContextChunkPlanner.estimateTokens(source),
            operation = "translate",
            envelopeId = ShortHash.hash(source),
            priority = currentProviderRequestPriority(),
        )
        return request(build, metadata)
    }

    private suspend fun request(
        build: Request,
        metadata: ProviderRequestMetadata,
    ): RawGoogleResponse = withTranslationRetry(logTag = "google") {
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
                if (isHtmlChallengeBody(raw.body)) {
                    // A successful HTTP status does not make a CAPTCHA page a
                    // translation. Charge it as a quota failure so the shared
                    // governor opens its cooldown breaker for this endpoint.
                    throw ProviderFailureException(
                        ProviderFailure(
                            kind = ProviderFailureKind.QUOTA_EXHAUSTED,
                            retryability = ProviderFailureRetryability.PAUSE,
                            safeSummary = "Google Translate challenge response",
                        ),
                    )
                }
                raw
            }
        }
    }

    private fun parseSingleResponse(
        string: String,
        lang: String,
        text: String,
        statusCode: Int,
    ): String {
        if (string.isBlank()) {
            logcat(LogPriority.WARN) {
                "event=provider_response_empty backend=google reason=empty_body lang=$lang " +
                    "inputChars=${text.length} status=$statusCode"
            }
            return ""
        }
        return extractTranslationText(string).orEmpty().also {
            if (it.isEmpty()) {
                logcat(LogPriority.WARN) {
                    "event=provider_response_invalid backend=google reason=parse_failure lang=$lang " +
                        "inputChars=${text.length} status=$statusCode responseHash=${ShortHash.hash(string)}"
                }
            }
        }
    }

    private fun parseEnvelopeResponse(body: String, expectedIds: List<String>): List<String>? {
        val translated = extractTranslationText(body) ?: return null
        return GoogleTranslationEnvelope.parse(translated, expectedIds)
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

    private fun getEnvelopeUrl(lang: String, sourceLang: String, source: String): String =
        "https://translate.googleapis.com/translate_a/single?client=$client1" +
            "&sl=$sourceLang&tl=$lang&dt=t&q=${URLEncoder.encode(source, "utf-8")}"

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

    private fun extractTranslationText(body: String): String? {
        return runCatching {
            val root = Json.parseToJsonElement(body) as? JsonArray
                ?: return@runCatching null
            val segments = root.firstOrNull() as? JsonArray
                ?: return@runCatching null
            buildString {
                segments.forEach { element ->
                    val segment = element as? JsonArray ?: return@forEach
                    val value = segment.firstOrNull() as? JsonPrimitive ?: return@forEach
                    if (value.isString) append(value.content)
                }
            }
        }.getOrNull()
    }

    private fun isHtmlChallengeBody(body: String): Boolean {
        val normalized = body.trimStart().lowercase(Locale.ROOT)
        return normalized.startsWith("<!doctype html") ||
            normalized.startsWith("<html") ||
            (normalized.contains("captcha") && normalized.contains('<'))
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

/** Strict, no-concurrency envelope planner/parser for the free Google endpoint. */
internal object GoogleTranslationEnvelope {
    const val MAX_SOURCE_CHARS = 5_000

    internal data class Chunk(
        val blockIndices: List<Int>,
        val source: String,
    )

    fun idFor(index: Int): String = "b$index"

    fun plan(blocks: List<String>): List<Chunk>? {
        if (blocks.isEmpty()) return emptyList()
        val chunks = mutableListOf<Chunk>()
        val indices = mutableListOf<Int>()
        val source = StringBuilder()
        blocks.forEachIndexed { index, text ->
            val wrapped = span(idFor(index), text)
            val wrappedChars = wrapped.codePointCount(0, wrapped.length)
            // An individual block that cannot fit the envelope must use the
            // proven per-block path instead of violating the hard cap.
            if (wrappedChars > MAX_SOURCE_CHARS) return null
            val currentChars = source.codePointCount(0, source.length)
            if (indices.isNotEmpty() && currentChars + wrappedChars > MAX_SOURCE_CHARS) {
                chunks += Chunk(indices.toList(), source.toString())
                indices.clear()
                source.setLength(0)
            }
            indices += index
            source.append(wrapped)
        }
        if (indices.isNotEmpty()) chunks += Chunk(indices.toList(), source.toString())
        return chunks
    }

    fun parse(rawHtml: String, expectedIds: List<String>): List<String>? {
        val spans = Jsoup.parseBodyFragment(rawHtml).select("span[data-id]")
        if (spans.size != expectedIds.size) return null
        val ids = spans.map { it.attr("data-id") }
        if (ids != expectedIds || ids.toSet().size != ids.size) return null
        return spans.map { it.text() }
    }

    private fun span(id: String, text: String): String =
        "<span data-id=\"$id\">${escape(text)}</span>"

    private fun escape(text: String): String = buildString(text.length) {
        text.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(char)
            }
        }
    }
}
