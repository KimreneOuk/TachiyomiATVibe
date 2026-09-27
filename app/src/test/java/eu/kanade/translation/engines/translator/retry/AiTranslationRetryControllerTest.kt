package eu.kanade.translation.engines.translator.retry
import eu.kanade.translation.engines.translator.ProviderFailure
import eu.kanade.translation.engines.translator.ProviderFailureException
import eu.kanade.translation.engines.translator.ProviderFailureKind
import eu.kanade.translation.engines.translator.ProviderFailureRetryability
import eu.kanade.translation.engines.translator.TextTranslatorLanguage
import eu.kanade.translation.engines.translator.contextual.ContextualRequestBuilder
import eu.kanade.translation.engines.translator.contextual.ContextualRequestProtocol
import eu.kanade.translation.engines.translator.contextual.ContextualTranslationBatch
import eu.kanade.translation.engines.translator.contextual.ContextualTranslationResult
import eu.kanade.translation.engines.translator.contextual.TranslationContextChunk
import eu.kanade.translation.engines.translator.contextual.TranslationContextChunkPlanner
import eu.kanade.translation.engines.translator.providers.AiTranslator
import eu.kanade.translation.engines.vision.ocr.TextRecognizerLanguage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException

class AiTranslationRetryControllerTest {

    @Test
    fun `first pass returns complete detached translations`() = runTest {
        val translator = ScriptedTranslator { _, chunk -> response(chunk) }
        val page = PageTranslation(blocks = mutableListOf(block("first"), block("second", x = 20f)))
        val chunk = chunk(page)

        val outcome = translate(chunk, translator)

        val complete = outcome.shouldBeInstanceOf<AiChunkOutcome.Complete>()
        translator.calls shouldBe 1
        complete.completedPageKeys shouldBe setOf("page.jpg")
        complete.acceptedBlockIds.size shouldBe 2
        complete.blockTranslations.values.toSet() shouldBe setOf(
            "translated-p4_b0",
            "translated-p4_b1",
        )
        // The controller freezes and requests a detached snapshot. Neither
        // translation nor the stable ID assignment leaks into the live page.
        page.blocks.map { it.translation } shouldContainExactly listOf("", "")
        page.blocks.map { it.blockId } shouldContainExactly listOf(null, null)
    }

    @Test
    fun `transient failure gets one whole envelope retry with stable identity`() = runTest {
        val requests = mutableListOf<List<String>>()
        val contexts = mutableListOf<String>()
        val translator = ScriptedTranslator { attempt, chunk ->
            requests += requestIds(chunk)
            contexts += chunk.rollingContext
            if (attempt == 1) throw IOException("temporary network failure")
            response(chunk)
        }
        val outcome = translate(chunk(), translator)

        outcome.shouldBeInstanceOf<AiChunkOutcome.Complete>()
        translator.calls shouldBe 2
        requests[0] shouldBe requests[1]
        contexts shouldBe listOf("previous => context", "previous => context")
        outcome.envelopeId.isNotBlank() shouldBe true
        outcome.wholeEnvelopeRetries shouldBe 1
    }

    @Test
    fun `missing blocks use a bounded missing-only request and merge by stable ID`() = runTest {
        val requested = mutableListOf<List<String>>()
        val translator = ScriptedTranslator { attempt, current ->
            requested += requestIds(current)
            val ids = requestIds(current)
            if (attempt == 1) {
                response(current, omit = setOf(ids.last()))
            } else {
                response(current)
            }
        }

        val outcome = translate(chunk(), translator)

        val complete = outcome.shouldBeInstanceOf<AiChunkOutcome.Complete>()
        translator.calls shouldBe 2
        requested[0].size shouldBe 2
        requested[1] shouldBe listOf("p4_b1")
        complete.acceptedBlockIds shouldBe setOf("p4_b0", "p4_b1")
        complete.blockTranslations["p4_b0"] shouldBe "translated-p4_b0"
        complete.blockTranslations["p4_b1"] shouldBe "translated-p4_b1"
    }

    @Test
    fun `clean targeted repair completes even after an earlier protocol violation`() = runTest {
        // Device reproduction: attempt 1 carried a protocol issue,
        // the whole repair and the targeted missing repair then returned
        // clean, fully covering the envelope. The accumulator must judge
        // the CURRENT merge, not latch the earlier violation — otherwise a
        // recovered response is discarded as ambiguous protocol.
        val translator = ScriptedTranslator { attempt, current ->
            when (attempt) {
                1 -> {
                    // Truncated whole response: one block missing AND a
                    // stray unknown id carrying the protocol issue.
                    val request = ContextualRequestBuilder.build(
                        current,
                        TextRecognizerLanguage.JAPANESE,
                        TextTranslatorLanguage.ENGLISH,
                    )
                    val junk = ContextualTranslationResult(
                        id = "unknown-extra",
                        targetKey = null,
                        text = "unrequested",
                        status = ContextualTranslationResult.Status.TRANSLATED,
                    )
                    ContextualRequestBuilder.toBatch(
                        request,
                        request.orderedIds.filterNot { it == requestIds(current).last() }
                            .map { id ->
                                ContextualTranslationResult(
                                    id = id,
                                    targetKey = request.idMap[id],
                                    text = "translated-$id",
                                    status = ContextualTranslationResult.Status.TRANSLATED,
                                )
                            } + junk,
                    )
                }
                2 -> response(current, omit = setOf(requestIds(current).last()))
                else -> response(current)
            }
        }

        val outcome = translate(chunk(), translator)

        val complete = outcome.shouldBeInstanceOf<AiChunkOutcome.Complete>()
        translator.calls shouldBe 3
        complete.wholeEnvelopeRetries shouldBe 1
        complete.acceptedBlockIds shouldBe setOf("p4_b0", "p4_b1")
    }

    @Test
    fun `missing budget exhaustion returns paused partial outcome`() = runTest {
        val translator = ScriptedTranslator { _, current ->
            response(current, omit = requestIds(current).toSet())
        }
        val outcome = translate(
            chunk(),
            translator,
            retryBudget = RequestRetryBudget(maxAttempts = 3),
            retryPolicy = AiTranslationRetryPolicy(
                maxWholeEnvelopeRetries = 0,
                maxMissingBlockRequests = 2,
                maxTotalAttempts = 3,
            ),
        )

        val paused = outcome.shouldBeInstanceOf<AiChunkOutcome.Paused>()
        translator.calls shouldBe 3
        paused.missingBlockIds shouldBe setOf("p4_b0", "p4_b1")
        paused.failure.kind shouldBe ProviderFailureKind.PROTOCOL
        paused.failure.retryability shouldBe ProviderFailureRetryability.PAUSE
        paused.partialCandidate shouldBe false
        paused.rollingContextUnavailable()
    }

    @Test
    fun `nested transport and semantic retries stay inside one hard request budget`() = runTest {
        var transportCalls = 0
        val translator = ScriptedTranslator { _, _ ->
            withTranslationRetry(
                maxAttempts = 3,
                baseDelayMs = 0,
                maxRetryDelayMs = 0,
                logTag = "fake",
            ) {
                transportCalls++
                throw IOException("503 temporary")
            }
        }

        val outcome = translate(
            chunk(),
            translator,
            retryBudget = RequestRetryBudget(maxAttempts = 5),
            retryPolicy = AiTranslationRetryPolicy(
                maxWholeEnvelopeRetries = 2,
                maxMissingBlockRequests = 2,
                maxTotalAttempts = 5,
            ),
        )

        outcome.shouldBeInstanceOf<AiChunkOutcome.Paused>()
        transportCalls shouldBe 5
        translator.calls shouldBe 2
    }

    @Test
    fun `duplicate and conflicting IDs return terminal protocol outcome without overwrite`() = runTest {
        val translator = ScriptedTranslator { _, current ->
            val request = ContextualRequestBuilder.build(current, TextRecognizerLanguage.JAPANESE, TextTranslatorLanguage.ENGLISH)
            val firstId = request.orderedIds.first()
            val first = ContextualTranslationResult(
                id = firstId,
                targetKey = request.idMap[firstId],
                text = "first-value",
                status = ContextualTranslationResult.Status.TRANSLATED,
            )
            val duplicate = first.copy(text = "conflicting-value")
            val secondId = request.orderedIds[1]
            val second = ContextualTranslationResult(
                id = secondId,
                targetKey = request.idMap[secondId],
                text = "second-value",
                status = ContextualTranslationResult.Status.TRANSLATED,
            )
            ContextualRequestBuilder.toBatch(request, listOf(first, duplicate, second))
        }

        val outcome = translate(
            chunk(),
            translator,
            retryPolicy = AiTranslationRetryPolicy(maxWholeEnvelopeRetries = 0),
        )

        val terminal = outcome.shouldBeInstanceOf<AiChunkOutcome.Terminal>()
        translator.calls shouldBe 1
        terminal.failure.kind shouldBe ProviderFailureKind.PROTOCOL
        terminal.blockTranslations["p4_b0"] shouldBe "first-value"
        terminal.blockTranslations["p4_b1"] shouldBe "second-value"
        terminal.failure.safeSummary.contains("duplicate") shouldBe true
    }

    @Test
    fun `malformed unknown ID is terminal when all requested values are present`() = runTest {
        val translator = ScriptedTranslator { _, current ->
            val request = ContextualRequestBuilder.build(
                current,
                TextRecognizerLanguage.JAPANESE,
                TextTranslatorLanguage.ENGLISH,
            )
            val valid = request.orderedIds.map { id ->
                ContextualTranslationResult(
                    id = id,
                    targetKey = request.idMap[id],
                    text = "translated-$id",
                    status = ContextualTranslationResult.Status.TRANSLATED,
                )
            }
            val malformed = ContextualTranslationResult(
                id = "not-a-block-id",
                targetKey = null,
                text = "unexpected",
                status = ContextualTranslationResult.Status.TRANSLATED,
            )
            ContextualRequestBuilder.toBatch(request, valid + malformed)
        }

        val outcome = translate(
            chunk(),
            translator,
            retryPolicy = AiTranslationRetryPolicy(maxWholeEnvelopeRetries = 0),
        )

        val terminal = outcome.shouldBeInstanceOf<AiChunkOutcome.Terminal>()
        translator.calls shouldBe 1
        terminal.failure.kind shouldBe ProviderFailureKind.PROTOCOL
        terminal.failure.safeSummary.contains("unknown=1") shouldBe true
    }

    @Test
    fun `provider refusal is terminal and never retried`() = runTest {
        val translator = ScriptedTranslator { _, current ->
            val request = ContextualRequestBuilder.build(current, TextRecognizerLanguage.JAPANESE, TextTranslatorLanguage.ENGLISH)
            val result = ContextualTranslationResult(
                id = request.orderedIds.first(),
                targetKey = request.idMap[request.orderedIds.first()],
                text = "I can't assist with that request.",
                status = ContextualTranslationResult.Status.TRANSLATED,
            )
            ContextualRequestBuilder.toBatch(request, listOf(result))
        }

        val outcome = translate(chunk(), translator)

        val terminal = outcome.shouldBeInstanceOf<AiChunkOutcome.Terminal>()
        translator.calls shouldBe 1
        terminal.failure.kind shouldBe ProviderFailureKind.REFUSAL
        terminal.failure.retryability shouldBe ProviderFailureRetryability.TERMINAL
    }

    @Test
    fun `terminal provider failure is returned without semantic retry`() = runTest {
        val translator = ScriptedTranslator { _, _ ->
            throw ProviderFailureException(
                ProviderFailure(
                    kind = ProviderFailureKind.AUTHENTICATION,
                    retryability = ProviderFailureRetryability.TERMINAL,
                    safeSummary = "provider authentication failed",
                ),
            )
        }

        val outcome = translate(chunk(), translator)

        val terminal = outcome.shouldBeInstanceOf<AiChunkOutcome.Terminal>()
        translator.calls shouldBe 1
        terminal.failure.kind shouldBe ProviderFailureKind.AUTHENTICATION
        terminal.failure.retryability shouldBe ProviderFailureRetryability.TERMINAL
    }

    @Test
    fun `user edit fence excludes edited blocks and preserves their value`() = runTest {
        val edited = block("manual source").apply {
            translation = "manual rendering"
            userEditedAt = 42L
        }
        val translator = ScriptedTranslator { _, current -> response(current) }
        val page = PageTranslation(blocks = mutableListOf(edited, block("provider source", x = 20f)))

        val outcome = translate(chunk(page), translator)

        val complete = outcome.shouldBeInstanceOf<AiChunkOutcome.Complete>()
        requestIds(translator.requests.single()) shouldBe listOf("p4_b1")
        complete.blockTranslations.keys shouldBe setOf("p4_b1")
        page.blocks.first().translation shouldBe "manual rendering"
    }

    @Test
    fun `cancellation propagates and does not publish a provisional result`() = runTest {
        val translator = ScriptedTranslator { _, _ -> throw CancellationException("cancelled") }
        val page = PageTranslation(blocks = mutableListOf(block("source")))

        var thrown: CancellationException? = null
        try {
            translate(chunk(page), translator)
        } catch (e: CancellationException) {
            thrown = e
        }

        thrown?.message shouldBe "cancelled"
        page.blocks.single().translation shouldBe ""
        page.blocks.single().blockId shouldBe null
    }

    @Test
    fun `stable request identity and IDs survive whole retry`() = runTest {
        val translator = ScriptedTranslator { attempt, current ->
            if (attempt == 1) throw IOException("retry")
            response(current)
        }
        val outcome = translate(chunk(), translator)

        val ids = translator.requests.map(::requestIds)
        ids[0] shouldBe ids[1]
        ids[0] shouldBe listOf("p4_b0", "p4_b1")
        outcome.envelopeId.isNotBlank() shouldBe true
    }

    private suspend fun translate(
        chunk: TranslationContextChunk,
        translator: ScriptedTranslator,
        retryBudget: RequestRetryBudget? = null,
        retryPolicy: AiTranslationRetryPolicy = AiTranslationRetryPolicy(),
    ): AiChunkOutcome = translateAiChunkWithAdaptiveRetry(
        translator = translator,
        chunk = chunk,
        requestedOutputTokens = 512,
        profile = TranslationContextChunkPlanner.Profile.DEFAULT,
        label = "controller-test",
        retryBudget = retryBudget,
        retryPolicy = retryPolicy,
    )

    private fun chunk(
        page: PageTranslation = PageTranslation(
            blocks = mutableListOf(block("first"), block("second", x = 20f)),
        ),
    ) = TranslationContextChunk(
        pages = linkedMapOf("page.jpg" to page),
        blockCount = page.blocks.count { it.text.isNotBlank() },
        rollingContext = "previous => context",
        estimatedPromptTokens = 1_500,
        maxOutputTokens = 512,
        protocol = ContextualRequestProtocol.BATCH_V1,
        pageIndexes = mapOf("page.jpg" to 4),
    )

    private fun response(
        chunk: TranslationContextChunk,
        omit: Set<String> = emptySet(),
    ): ContextualTranslationBatch {
        val request = ContextualRequestBuilder.build(
            chunk,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )
        val results = request.orderedIds
            .filterNot(omit::contains)
            .map { id ->
                ContextualTranslationResult(
                    id = id,
                    targetKey = request.idMap[id],
                    text = "translated-$id",
                    status = ContextualTranslationResult.Status.TRANSLATED,
                )
            }
        return ContextualRequestBuilder.toBatch(request, results)
    }

    private fun requestIds(chunk: TranslationContextChunk): List<String> =
        ContextualRequestBuilder.build(
            chunk,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        ).orderedIds

    private class ScriptedTranslator(
        private val responder: suspend (Int, TranslationContextChunk) -> ContextualTranslationBatch,
    ) : AiTranslator() {
        val requests = mutableListOf<TranslationContextChunk>()
        val calls: Int get() = requests.size

        override val fromLang: TextRecognizerLanguage = TextRecognizerLanguage.JAPANESE
        override val toLang: TextTranslatorLanguage = TextTranslatorLanguage.ENGLISH

        override suspend fun translateContextualStructured(
            chunk: TranslationContextChunk,
        ): ContextualTranslationBatch {
            requests += chunk
            return responder(calls, chunk)
        }

        override suspend fun promptText(prompt: String): String = ""
    }

    private fun block(text: String, x: Float = 0f): TranslationBlock = TranslationBlock(
        text = text,
        width = 10f,
        height = 10f,
        x = x,
        y = 0f,
        symHeight = 10f,
        symWidth = 10f,
        angle = 0f,
    )

    private fun AiChunkOutcome.Paused.rollingContextUnavailable() {
        // A paused outcome intentionally has no rollingContextDelta. This
        // assertion keeps the test explicit without adding a nullable field
        // to the public result contract.
        missingBlockIds.isNotEmpty() shouldBe true
    }
}
