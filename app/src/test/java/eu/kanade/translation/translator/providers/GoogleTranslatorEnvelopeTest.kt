package eu.kanade.translation.translator.providers

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.translator.ProviderAdmissionDecision
import eu.kanade.translation.translator.ProviderAdmissionEvent
import eu.kanade.translation.translator.ProviderQuotaPolicy
import eu.kanade.translation.translator.ProviderRequestGovernor
import eu.kanade.translation.translator.ProviderRequestKey
import eu.kanade.translation.translator.ProviderRequestMetadata
import eu.kanade.translation.translator.ProviderRequestPausedException
import eu.kanade.translation.translator.TextTranslatorLanguage
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test

class GoogleTranslatorEnvelopeTest {

    @Test
    fun `valid span envelope reconstructs blocks and charges one request`() = runTest {
        val requests = mutableListOf<Request>()
        val events = mutableListOf<ProviderAdmissionEvent>()
        val governor = governor(events)
        val client = fakeClient(requests) { request ->
            request.url.host shouldBe "translate.googleapis.com"
            """[[["<span data-id=\"b0\">one</span><span data-id=\"b1\">two</span>"]]]"""
        }
        val translator = GoogleTranslator(
            fromLang = TextRecognizerLanguage.JAPANESE,
            toLang = TextTranslatorLanguage.ENGLISH,
            requestGovernor = governor,
            okHttpClient = client,
        )
        val page = PageTranslation(blocks = mutableListOf(block("一"), block("二")))

        try {
            translator.translate(mutableMapOf("page" to page))
        } finally {
            translator.close()
        }

        page.blocks.map { it.translation } shouldContainExactly listOf("one", "two")
        requests.size shouldBe 1
        requests.single().url.host shouldBe "translate.googleapis.com"
        requests.single().url.queryParameterNames shouldBe setOf("client", "sl", "tl", "dt", "q")
        requests.single().url.queryParameter("dt") shouldBe "t"
        requests.single().url.queryParameter("q")!!.let {
            it shouldBe "<span data-id=\"b0\">一</span><span data-id=\"b1\">二</span>"
        }
        events.count { it.outcome == "admitted" } shouldBe 1
    }

    @Test
    fun `envelope anomaly falls back to serial per-block requests without envelope retry`() = runTest {
        val requests = mutableListOf<Request>()
        var envelopeCalls = 0
        val client = fakeClient(requests) { request ->
            if (request.url.host == "translate.googleapis.com") {
                envelopeCalls++
                """[[["<span data-id=\"b0\">one</span>"]]]"""
            } else {
                when (request.url.queryParameter("q")) {
                    "一" -> """[[["one"]]]"""
                    "二" -> """[[["two"]]]"""
                    else -> error("unexpected fallback query ${request.url.queryParameter("q")}")
                }
            }
        }
        val translator = GoogleTranslator(
            fromLang = TextRecognizerLanguage.JAPANESE,
            toLang = TextTranslatorLanguage.ENGLISH,
            requestGovernor = governor(),
            okHttpClient = client,
        )
        val page = PageTranslation(blocks = mutableListOf(block("一"), block("二")))

        try {
            translator.translate(mutableMapOf("page" to page))
        } finally {
            translator.close()
        }

        page.blocks.map { it.translation } shouldContainExactly listOf("one", "two")
        envelopeCalls shouldBe 1
        requests.map { it.url.host } shouldContainExactly listOf(
            "translate.googleapis.com",
            "translate.google.com",
            "translate.google.com",
        )
    }

    @Test
    fun duplicateAndReorderedEnvelopeIdsFallBackToSerialPerBlockRequests() = runTest {
        listOf(
            """[[["<span data-id=\"b0\">one</span><span data-id=\"b0\">two</span>"]]]""",
            """[[["<span data-id=\"b1\">two</span><span data-id=\"b0\">one</span>"]]]""",
        ).forEach { invalidEnvelope ->
            val requests = mutableListOf<Request>()
            var envelopeCalls = 0
            val client = fakeClient(requests) { request ->
                if (request.url.host == "translate.googleapis.com") {
                    envelopeCalls++
                    invalidEnvelope
                } else {
                    when (request.url.queryParameter("q")) {
                        "一" -> """[[["one"]]]"""
                        "二" -> """[[["two"]]]"""
                        else -> error("unexpected fallback query")
                    }
                }
            }
            val translator = GoogleTranslator(
                fromLang = TextRecognizerLanguage.JAPANESE,
                toLang = TextTranslatorLanguage.ENGLISH,
                requestGovernor = governor(),
                okHttpClient = client,
            )
            val page = PageTranslation(blocks = mutableListOf(block("一"), block("二")))

            try {
                translator.translate(mutableMapOf("page" to page))
            } finally {
                translator.close()
            }

            page.blocks.map { it.translation } shouldContainExactly listOf("one", "two")
            envelopeCalls shouldBe 1
            requests.map { it.url.host } shouldContainExactly listOf(
                "translate.googleapis.com",
                "translate.google.com",
                "translate.google.com",
            )
        }
    }

    @Test
    fun `missing duplicate and reordered ids are rejected`() {
        val expected = listOf("b0", "b1")

        GoogleTranslationEnvelope.parse(
            "<span data-id=\"b0\">one</span><span data-id=\"b1\">two</span>",
            expected,
        ) shouldBe listOf("one", "two")

        GoogleTranslationEnvelope.parse(
            "<span data-id=\"b0\">one</span>",
            expected,
        ) shouldBe null
        GoogleTranslationEnvelope.parse(
            "<span data-id=\"b0\">one</span><span data-id=\"b0\">two</span>",
            expected,
        ) shouldBe null
        GoogleTranslationEnvelope.parse(
            "<span data-id=\"b1\">two</span><span data-id=\"b0\">one</span>",
            expected,
        ) shouldBe null
    }

    @Test
    fun `chunking keeps every envelope at or below the five thousand character cap`() {
        val plan = GoogleTranslationEnvelope.plan(
            listOf("a".repeat(3_000), "b".repeat(3_000), "c"),
        )

        plan.shouldBeInstanceOf<List<GoogleTranslationEnvelope.Chunk>>()
        plan!!.size shouldBe 2
        plan.forEach { chunk ->
            val chars = chunk.source.codePointCount(0, chunk.source.length)
            if (chars > GoogleTranslationEnvelope.MAX_SOURCE_CHARS) {
                throw AssertionError("Envelope exceeded cap: $chars")
            }
        }
        plan.flatMap { it.blockIndices } shouldContainExactly listOf(0, 1, 2)
    }

    @Test
    fun `challenge html trips the governor breaker and is never parsed`() = runTest {
        val governor = governor()
        val client = fakeClient(mutableListOf()) {
            "<!doctype html><html><body>captcha challenge</body></html>"
        }
        val translator = GoogleTranslator(
            fromLang = TextRecognizerLanguage.JAPANESE,
            toLang = TextTranslatorLanguage.ENGLISH,
            requestGovernor = governor,
            okHttpClient = client,
        )
        val page = PageTranslation(blocks = mutableListOf(block("一")))

        try {
            runCatching { translator.translate(mutableMapOf("page" to page)) }
                .exceptionOrNull()
                .shouldBeInstanceOf<ProviderRequestPausedException>()

            governor.admit(
                ProviderRequestMetadata(
                    key = ProviderRequestKey("google"),
                    estimatedInputTokens = 1,
                ),
            ).shouldBeInstanceOf<ProviderAdmissionDecision.Deferred>()
            page.blocks.single().translation shouldBe ""
        } finally {
            translator.close()
        }
    }

    private fun governor(events: MutableList<ProviderAdmissionEvent> = mutableListOf()) =
        ProviderRequestGovernor(
            policy = {
                ProviderQuotaPolicy(
                    minimumSpacingMs = 0L,
                    maxForegroundWaitMs = 1_000L,
                    pollIntervalMs = 1L,
                )
            },
            diagnostics = { events += it },
        )

    private fun fakeClient(
        requests: MutableList<Request>,
        body: (Request) -> String,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            requests += request
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body(request).toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private fun block(text: String) = TranslationBlock(
        text = text,
        width = 1f,
        height = 1f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )
}
