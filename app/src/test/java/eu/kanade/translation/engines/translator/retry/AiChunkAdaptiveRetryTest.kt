package eu.kanade.translation.engines.translator.retry
import eu.kanade.translation.diagnostics.BatchTranslationDiagnostics
import eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics
import eu.kanade.translation.diagnostics.TranslationTraceMode
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceSink
import eu.kanade.translation.engines.translator.ProviderFailure
import eu.kanade.translation.engines.translator.ProviderFailureException
import eu.kanade.translation.engines.translator.ProviderFailureKind
import eu.kanade.translation.engines.translator.ProviderFailureRetryability
import eu.kanade.translation.engines.translator.contextual.ContextualRequestBuilder
import eu.kanade.translation.engines.translator.contextual.ContextualRequestProtocol
import eu.kanade.translation.engines.translator.contextual.ContextualResponseParser
import eu.kanade.translation.engines.translator.contextual.ContextualTranslationBatch
import eu.kanade.translation.engines.translator.contextual.ContextualTranslationResult
import eu.kanade.translation.engines.translator.contextual.STRUCTURAL_REFUSAL_DIAGNOSTIC
import eu.kanade.translation.engines.translator.contextual.TranslationContextChunk
import eu.kanade.translation.engines.translator.contextual.TranslationContextChunkPlanner
import eu.kanade.translation.engines.translator.providers.AiTranslator
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException

class AiChunkAdaptiveRetryTest {

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
            "translated-1",
            "translated-2",
        )
        // The controller freezes and requests a detached snapshot. Neither
        // translation nor the stable ID assignment leaks into the live page.
        page.blocks.map { it.translation } shouldContainExactly listOf("", "")
        page.blocks.map { it.blockId } shouldContainExactly listOf(null, null)
    }

    @Test
    fun `transport retries keep request identity without blind semantic regeneration`() = runTest {
        val transportAttempts = mutableListOf<Pair<List<String>, String>>()
        val translator = ScriptedTranslator { _, chunk ->
            withTranslationRetry(
                maxAttempts = 3,
                baseDelayMs = 0,
                maxRetryDelayMs = 0,
                logTag = "fake",
            ) {
                transportAttempts += requestIds(chunk) to chunk.rollingContext
                if (transportAttempts.size == 1) throw IOException("temporary network failure")
                response(chunk)
            }
        }
        val outcome = translate(chunk(), translator)

        outcome.shouldBeInstanceOf<AiChunkOutcome.Complete>()
        translator.calls shouldBe 1
        transportAttempts shouldBe listOf(
            listOf("1", "2") to "previous => context",
            listOf("1", "2") to "previous => context",
        )
        outcome.envelopeId.isNotBlank() shouldBe true
        outcome.wholeEnvelopeRetries shouldBe 0
    }

    @Test
    fun `one semantic follow-up re-enumerates unresolved durable IDs only`() = runTest {
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
        requested[0] shouldBe listOf("1", "2")
        // The follow-up is a fresh request-local namespace over only the
        // unresolved durable block; its wire id starts at one again.
        requested[1] shouldBe listOf("1")
        complete.acceptedBlockIds shouldBe setOf("p4_b0", "p4_b1")
        complete.blockTranslations["p4_b0"] shouldBe "translated-1"
        complete.blockTranslations["p4_b1"] shouldBe "translated-1"
    }

    @Test
    fun `scriptless equality output is accepted without semantic follow-up`() = runTest {
        val page = PageTranslation(
            blocks = mutableListOf(block("OK!"), block("Alice", x = 20f)),
        )
        val translator = ScriptedTranslator { _, current ->
            val request = ContextualRequestBuilder.build(
                current,
                TextRecognizerLanguage.JAPANESE,
                TextTranslatorLanguage.ENGLISH,
            )
            val result = request.orderedIds.map { id ->
                val location = request.locations.getValue(id)
                ContextualTranslationResult(
                    id = id,
                    targetKey = request.idMap[id],
                    text = current.pages.getValue(location.pageKey).blocks[location.blockIndex].text,
                    status = ContextualTranslationResult.Status.TRANSLATED,
                )
            }
            ContextualRequestBuilder.toBatch(request, result)
        }

        val outcome = translate(chunk(page), translator)

        outcome.shouldBeInstanceOf<AiChunkOutcome.Complete>()
        translator.calls shouldBe 1
        outcome.acceptedBlockIds shouldBe setOf("p4_b0", "p4_b1")
    }

    @Test
    fun `same-language and script-ambiguous equality outputs are accepted without semantic follow-up`() = runTest {
        val cases = listOf(
            Triple(TextRecognizerLanguage.JAPANESE, TextTranslatorLanguage.JAPANESE, "同じ"),
            Triple(TextRecognizerLanguage.JAPANESE, TextTranslatorLanguage.CHINESESIM, "漢字"),
            Triple(TextRecognizerLanguage.SPANISH, TextTranslatorLanguage.FRENCH, "Alice"),
        )
        cases.forEach { (fromLanguage, toLanguage, sourceText) ->
            val page = PageTranslation(blocks = mutableListOf(block(sourceText)))
            val translator = ScriptedTranslator(fromLanguage, toLanguage) { _, current ->
                val request = ContextualRequestBuilder.build(current, fromLanguage, toLanguage)
                ContextualRequestBuilder.toBatch(
                    request,
                    request.orderedIds.map { id ->
                        val location = request.locations.getValue(id)
                        ContextualTranslationResult(
                            id = id,
                            targetKey = request.idMap[id],
                            text = current.pages.getValue(location.pageKey).blocks[location.blockIndex].text,
                            status = ContextualTranslationResult.Status.TRANSLATED,
                        )
                    },
                )
            }

            val outcome = translate(chunk(page), translator)

            outcome.shouldBeInstanceOf<AiChunkOutcome.Complete>()
            translator.calls shouldBe 1
            outcome.acceptedBlockIds shouldBe setOf("p4_b0")
        }
    }

    @Test
    fun `preexisting scriptless equality is not re-requested and completes the natural prefix`() = runTest {
        val page = PageTranslation(
            blocks = mutableListOf(
                block("OK!").apply { translation = "OK!" },
                block("Alice", x = 20f).apply { translation = "Alice" },
            ),
        )
        val translator = ScriptedTranslator { _, current -> response(current) }

        val outcome = translate(chunk(page), translator)

        translator.calls shouldBe 0
        outcome.shouldBeInstanceOf<AiChunkOutcome.Complete>().completedPageKeys shouldBe setOf("page.jpg")
    }

    @Test
    fun `positive incompatible target script gets one clean source follow-up`() = runTest {
        val seen = mutableListOf<TranslationContextChunk>()
        val page = PageTranslation(blocks = mutableListOf(block("待て！")))
        val translator = ScriptedTranslator { attempt, current ->
            seen += current
            val request = ContextualRequestBuilder.build(
                current,
                TextRecognizerLanguage.JAPANESE,
                TextTranslatorLanguage.ENGLISH,
            )
            val text = if (attempt == 1) "안녕" else "Wait!"
            ContextualRequestBuilder.toBatch(
                request,
                request.orderedIds.map { id ->
                    ContextualTranslationResult(
                        id = id,
                        targetKey = request.idMap[id],
                        text = text,
                        status = ContextualTranslationResult.Status.TRANSLATED,
                    )
                },
            )
        }

        val outcome = translate(chunk(page), translator)

        outcome.shouldBeInstanceOf<AiChunkOutcome.Complete>()
        translator.calls shouldBe 2
        seen[1].pages.values.flatMap { it.blocks }.map { it.text } shouldBe listOf("待て！")
        seen[1].rollingContext.contains("안녕") shouldBe false
    }

    @Test
    fun `malformed prose and unknown extras do not veto unique siblings and one follow-up`() = runTest {
        val requested = mutableListOf<List<String>>()
        val translator = ScriptedTranslator { attempt, current ->
            val request = ContextualRequestBuilder.build(
                current,
                TextRecognizerLanguage.JAPANESE,
                TextTranslatorLanguage.ENGLISH,
            )
            requested += request.orderedIds
            if (attempt == 1) {
                val acceptedId = request.orderedIds.first()
                val accepted = ContextualTranslationResult(
                    id = acceptedId,
                    targetKey = request.idMap[acceptedId],
                    text = "first-pass-accepted",
                    status = ContextualTranslationResult.Status.TRANSLATED,
                )
                val unknown = ContextualTranslationResult(
                    id = "unknown-extra",
                    targetKey = null,
                    text = "unrequested",
                    status = ContextualTranslationResult.Status.TRANSLATED,
                )
                ContextualRequestBuilder.toBatch(request, listOf(accepted, unknown)).copy(
                    validationErrors = listOf("Malformed line at line 1"),
                )
            } else {
                response(current)
            }
        }

        val outcome = translate(chunk(), translator)

        val complete = outcome.shouldBeInstanceOf<AiChunkOutcome.Complete>()
        translator.calls shouldBe 2
        requested shouldContainExactly listOf(listOf("1", "2"), listOf("1"))
        complete.wholeEnvelopeRetries shouldBe 0
        complete.missingBlockRequests shouldBe 1
        complete.acceptedBlockIds shouldBe setOf("p4_b0", "p4_b1")
        complete.blockTranslations["p4_b0"] shouldBe "first-pass-accepted"
        complete.blockTranslations["p4_b1"] shouldBe "translated-1"
    }

    @Test
    fun `one unresolved semantic follow-up pauses without a second missing request`() = runTest {
        val translator = ScriptedTranslator { _, current ->
            response(current, omit = requestIds(current).toSet())
        }
        val outcome = translate(
            chunk(),
            translator,
            retryBudget = RequestRetryBudget(maxAttempts = 8),
        )

        val paused = outcome.shouldBeInstanceOf<AiChunkOutcome.Paused>()
        translator.calls shouldBe 2
        paused.missingBlockIds shouldBe setOf("p4_b0", "p4_b1")
        paused.failure.kind shouldBe ProviderFailureKind.PROTOCOL
        paused.failure.retryability shouldBe ProviderFailureRetryability.PAUSE
        paused.partialCandidate shouldBe false
        paused.rollingContextUnavailable()
    }

    @Test
    fun `transport exhaustion does not trigger another semantic request`() = runTest {
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
        transportCalls shouldBe 3
        translator.calls shouldBe 1
    }

    @Test
    fun `identical duplicate is accepted and conflicting item alone gets the follow-up`() = runTest {
        val requested = mutableListOf<List<String>>()
        val translator = ScriptedTranslator { attempt, current ->
            val request = ContextualRequestBuilder.build(current, TextRecognizerLanguage.JAPANESE, TextTranslatorLanguage.ENGLISH)
            requested += request.orderedIds
            if (attempt > 1) return@ScriptedTranslator response(current)
            val firstId = request.orderedIds.first()
            val first = ContextualTranslationResult(
                id = firstId,
                targetKey = request.idMap[firstId],
                text = "caf\u00e9",
                status = ContextualTranslationResult.Status.TRANSLATED,
            )
            // Canonically equivalent Unicode is one normalized candidate, and
            // the first accepted content remains the value that is frozen.
            val identicalDuplicate = first.copy(text = "cafe\u0301")
            val secondId = request.orderedIds[1]
            val secondA = ContextualTranslationResult(
                id = secondId,
                targetKey = request.idMap[secondId],
                text = "candidate-a",
                status = ContextualTranslationResult.Status.TRANSLATED,
            )
            val secondB = secondA.copy(
                text = "candidate-b",
            )
            ContextualRequestBuilder.toBatch(request, listOf(first, identicalDuplicate, secondA, secondB))
        }

        val outcome = translate(chunk(), translator)

        val complete = outcome.shouldBeInstanceOf<AiChunkOutcome.Complete>()
        translator.calls shouldBe 2
        requested shouldContainExactly listOf(listOf("1", "2"), listOf("1"))
        complete.missingBlockRequests shouldBe 1
        complete.blockTranslations["p4_b0"] shouldBe "caf\u00e9"
        complete.blockTranslations["p4_b1"] shouldBe "translated-1"

        val unresolvedConflict = ScriptedTranslator { _, current ->
            val request = ContextualRequestBuilder.build(
                current,
                TextRecognizerLanguage.JAPANESE,
                TextTranslatorLanguage.ENGLISH,
            )
            val firstId = request.orderedIds.first()
            val responseText = if (request.orderedIds.size == 2) {
                "1|conflicting a\n1|conflicting b\n2|unique sibling"
            } else {
                "$firstId|conflicting a\n$firstId|conflicting b"
            }
            ContextualResponseParser.parse(responseText, request)
        }
        val unresolvedOutcome = translate(chunk(), unresolvedConflict)
            .shouldBeInstanceOf<AiChunkOutcome.Paused>()
        unresolvedConflict.calls shouldBe 2
        unresolvedOutcome.blockTranslations.keys shouldBe setOf("p4_b1")
        unresolvedOutcome.missingBlockIds shouldBe setOf("p4_b0")
        unresolvedOutcome.failure.safeSummary.contains("conflict=2") shouldBe true
    }

    @Test
    fun `malformed unknown ID does not veto uniquely accepted requested values`() = runTest {
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
            ContextualRequestBuilder.toBatch(request, valid + malformed).copy(
                validationErrors = listOf("Malformed line at line 4"),
            )
        }

        val outcome = translate(chunk(), translator)

        val complete = outcome.shouldBeInstanceOf<AiChunkOutcome.Complete>()
        translator.calls shouldBe 1
        complete.blockTranslations.keys shouldBe setOf("p4_b0", "p4_b1")
        complete.missingBlockRequests shouldBe 0
        complete.wholeEnvelopeRetries shouldBe 0
    }

    @Test
    fun `provider refusal discards every candidate from that response`() = runTest {
        val responseTexts = listOf(
            "I can't assist with that request.\n1|valid-looking sibling",
            "1|valid-looking sibling\n1|I can't assist with that request.\n2|another sibling",
        )
        val outcomes = mutableListOf<AiChunkOutcome>()
        val parsedBatchesByResponse = mutableListOf<List<ContextualTranslationBatch>>()
        val callsByResponse = mutableListOf<Int>()
        responseTexts.forEach { rawResponse ->
            val parsed = mutableListOf<ContextualTranslationBatch>()
            val translator = ScriptedTranslator { _, current ->
                val request = ContextualRequestBuilder.build(
                    current,
                    TextRecognizerLanguage.JAPANESE,
                    TextTranslatorLanguage.ENGLISH,
                )
                ContextualResponseParser.parse(rawResponse, request).also { parsed += it }
            }

            outcomes += translate(chunk(), translator)
            parsedBatchesByResponse.add(parsed.toList())
            callsByResponse += translator.calls
        }

        outcomes.map { it is AiChunkOutcome.Terminal } shouldContainExactly listOf(true, true)
        outcomes.map { (it as? AiChunkOutcome.Terminal)?.failure?.kind } shouldContainExactly listOf(
            ProviderFailureKind.REFUSAL,
            ProviderFailureKind.REFUSAL,
        )
        outcomes.map { (it as? AiChunkOutcome.Terminal)?.failure?.retryability } shouldContainExactly listOf(
            ProviderFailureRetryability.TERMINAL,
            ProviderFailureRetryability.TERMINAL,
        )
        outcomes.map { it.blockTranslations } shouldContainExactly listOf(emptyMap(), emptyMap())
        outcomes.map { it.acceptedBlockIds } shouldContainExactly listOf(emptySet(), emptySet())
        callsByResponse shouldContainExactly listOf(1, 1)
        parsedBatchesByResponse.map { it.size } shouldContainExactly listOf(1, 1)
        parsedBatchesByResponse.flatten().map { it.validationErrors.contains(STRUCTURAL_REFUSAL_DIAGNOSTIC) } shouldContainExactly listOf(
            true,
            true,
        )
        parsedBatchesByResponse.flatten().map { batch ->
            batch.validationErrors.none { "can't assist" in it }
        } shouldContainExactly listOf(true, true)
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
        requestIds(translator.requests.single()) shouldBe listOf("1")
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
    fun `transport exhaustion pauses without semantic request regeneration`() = runTest {
        val translator = ScriptedTranslator { _, _ ->
            throw IOException("transport retry budget exhausted")
        }
        val outcome = translate(chunk(), translator)

        val paused = outcome.shouldBeInstanceOf<AiChunkOutcome.Paused>()
        translator.calls shouldBe 1
        translator.requests.map(::requestIds) shouldContainExactly listOf(listOf("1", "2"))
        paused.wholeEnvelopeRetries shouldBe 0
        paused.missingBlockIds shouldBe setOf("p4_b0", "p4_b1")
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
        private val fromLanguage: TextRecognizerLanguage = TextRecognizerLanguage.JAPANESE,
        private val toLanguage: TextTranslatorLanguage = TextTranslatorLanguage.ENGLISH,
        private val responder: suspend (Int, TranslationContextChunk) -> ContextualTranslationBatch,
    ) : AiTranslator() {
        val requests = mutableListOf<TranslationContextChunk>()
        val calls: Int get() = requests.size

        override val fromLang: TextRecognizerLanguage = fromLanguage
        override val toLang: TextTranslatorLanguage = toLanguage

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
        // A paused outcome carries no rolling-context state; each request
        // rebuilds that section from durable committed predecessor pages.
        missingBlockIds.isNotEmpty() shouldBe true
    }

    @Test
    fun `echo follow-up renders the pinned source-language correction`() = runTest {
        val observed = renderRun(listOf("待て！")) { attempt, request, _ ->
            mappedBatch(request, mapOf(request.orderedIds.single() to if (attempt == 1) "待て！" else "Wait!"))
        }

        observed.outcome.shouldBeInstanceOf<AiChunkOutcome.Complete>()
        observed.prompts.size shouldBe 2
        observed.prompts[1].lineSequence().count {
            it == "Translate source-language text into English; do not copy it unchanged."
        } shouldBe 1
        observed.prompts[1].contains("SOURCE ITEMS:\n1|待て！") shouldBe true
        observed.prompts[1].contains("failed response") shouldBe false
    }

    @Test
    fun `positive target-script mismatch follow-up renders the pinned target correction`() = runTest {
        val observed = renderRun(
            sourceTexts = listOf("待て！"),
            fromLanguage = TextRecognizerLanguage.JAPANESE,
            toLanguage = TextTranslatorLanguage.FRENCH,
        ) { attempt, request, _ ->
            mappedBatch(request, mapOf(request.orderedIds.single() to if (attempt == 1) "안녕" else "Attends!"))
        }

        observed.outcome.shouldBeInstanceOf<AiChunkOutcome.Complete>()
        observed.prompts.size shouldBe 2
        observed.prompts[1].lineSequence().count { it == "Use French for the translations." } shouldBe 1
        observed.prompts[1].contains("SOURCE ITEMS:\n1|待て！") shouldBe true
        observed.prompts[1].contains("안녕") shouldBe false
    }

    @Test
    fun `combined approved reasons render one exact correction line`() = runTest {
        val observed = renderRun(listOf("待て！", "もういい", "猫")) { attempt, request, _ ->
            val ids = request.orderedIds
            val outputs = if (attempt == 1) {
                mapOf(ids[0] to "待て！", ids[1] to "안녕", ids[2] to "Cat")
            } else {
                mapOf(ids[0] to "Wait!", ids[1] to "That's enough.")
            }
            mappedBatch(request, outputs)
        }

        observed.outcome.shouldBeInstanceOf<AiChunkOutcome.Complete>()
        observed.prompts.size shouldBe 2
        val combined = "Translate source-language text into English; do not copy it unchanged. " +
            "Use English for the translations."
        observed.prompts[1].lineSequence().count { it == combined } shouldBe 1
        observed.prompts[1].contains("3|猫") shouldBe false
        observed.prompts[1].contains("안녕") shouldBe false
    }

    @Test
    fun `non-approved reasons never render a correction with an echo sentinel`() = runTest {
        val observations = mutableListOf<Pair<String, RenderedRun>>()

        observations += "echo-sentinel" to renderRun(listOf("待て！")) { attempt, request, _ ->
            mappedBatch(request, mapOf(request.orderedIds.single() to if (attempt == 1) "待て！" else "Wait!"))
        }
        observations += "missing" to renderRun(listOf("one")) { attempt, request, _ ->
            if (attempt == 1) {
                ContextualRequestBuilder.toBatch(request, emptyList())
            } else {
                mappedBatch(
                    request,
                    request.orderedIds.associateWith { "translated" },
                )
            }
        }
        observations += "blank" to renderRun(listOf("one")) { attempt, request, _ ->
            if (attempt == 1) {
                ContextualResponseParser.parseBatch("1|   ", request)
            } else {
                mappedBatch(request, request.orderedIds.associateWith { "translated" })
            }
        }
        observations += "conflict" to renderRun(listOf("one")) { attempt, request, _ ->
            if (attempt == 1) {
                ContextualResponseParser.parseBatch("1|candidate a\n1|candidate b", request)
            } else {
                mappedBatch(request, request.orderedIds.associateWith { "translated" })
            }
        }
        observations += "malformed-numeric" to renderRun(listOf("one")) { attempt, request, _ ->
            if (attempt == 1) {
                ContextualResponseParser.parseBatch("1. ambiguous ordinal", request)
            } else {
                mappedBatch(request, request.orderedIds.associateWith { "translated" })
            }
        }
        observations += "prose-parse" to renderRun(listOf("one")) { attempt, request, _ ->
            if (attempt == 1) {
                ContextualResponseParser.parseBatch("unanchored prose", request)
            } else {
                mappedBatch(request, request.orderedIds.associateWith { "translated" })
            }
        }
        observations += "transport" to renderRun(listOf("one")) { _, _, _ ->
            withTranslationRetry(
                maxAttempts = 1,
                baseDelayMs = 0,
                maxRetryDelayMs = 0,
                logTag = "fake",
            ) {
                throw IOException("transport sentinel")
            }
        }
        observations += "refusal" to renderRun(listOf("one")) { _, request, _ ->
            ContextualResponseParser.parseBatch(
                "I can't assist with that request.\n1|valid-looking sibling",
                request,
            )
        }
        observations += "scriptless-equality" to renderRun(listOf("OK!")) { _, request, current ->
            val source = current.pages.getValue("page.jpg").blocks.single().text
            mappedBatch(request, mapOf(request.orderedIds.single() to source))
        }

        val echo = observations.first { it.first == "echo-sentinel" }.second
        val negativeControls = observations.filterNot { it.first == "echo-sentinel" }
        val allChecks = mutableListOf<Boolean>()
        allChecks += echo.prompts.size == 2
        allChecks += echo.prompts.getOrNull(1)?.lineSequence()?.any {
            it == "Translate source-language text into English; do not copy it unchanged."
        } == true
        negativeControls.forEach { (name, run) ->
            val expectedCalls = if (name in setOf("transport", "refusal", "scriptless-equality")) 1 else 2
            allChecks += run.prompts.size == expectedCalls
            allChecks += run.prompts.none { prompt ->
                prompt.lineSequence().any { line ->
                    line == "Translate source-language text into English; do not copy it unchanged." ||
                        line == "Use French for the translations."
                }
            }
        }
        allChecks shouldContainExactly List(allChecks.size) { true }
    }

    @Test
    fun `follow-up correction is one-shot and does not leak to a later envelope`() = runTest {
        val repeated = renderRun(listOf("待て！")) { _, request, _ ->
            mappedBatch(request, mapOf(request.orderedIds.single() to "待て！"))
        }
        val paused = repeated.outcome.shouldBeInstanceOf<AiChunkOutcome.Paused>()
        repeated.prompts.size shouldBe 2
        repeated.prompts[1].lineSequence().count {
            it == "Translate source-language text into English; do not copy it unchanged."
        } shouldBe 1
        repeated.prompts[1].contains("SOURCE ITEMS:\n1|待て！") shouldBe true
        repeated.chunks[1].rollingContext shouldBe "previous => context"
        repeated.chunks[1].pages.values.flatMap { it.blocks }.map { it.blockId } shouldBe listOf("p4_b0")
        paused.missingBlockIds shouldBe setOf("p4_b0")

        val laterPrompts = mutableListOf<String>()
        val laterTranslator = ScriptedTranslator { _, current ->
            val request = ContextualRequestBuilder.buildFor(
                current,
                TextRecognizerLanguage.JAPANESE,
                TextTranslatorLanguage.ENGLISH,
            )
            laterPrompts += ContextualRequestBuilder.renderPrompt(request, current.rollingContext)
            mappedBatch(request, request.orderedIds.associateWith { "Wait!" })
        }
        val laterOutcome = translate(repeated.chunks[1], laterTranslator)

        laterOutcome.shouldBeInstanceOf<AiChunkOutcome.Complete>()
        laterPrompts.size shouldBe 1
        laterPrompts.single().contains("Translate source-language text into English; do not copy it unchanged.") shouldBe false
    }

    @Test
    fun `first pass telemetry reports resolved percentage and exact versus salvaged items`() = runTest {
        val page = PageTranslation(
            blocks = mutableListOf(
                block("first"),
                block("second", x = 20f),
                block("third", x = 40f),
                block("fourth", x = 60f),
            ),
        )
        val translator = ScriptedTranslator { attempt, current ->
            val request = ContextualRequestBuilder.buildFor(
                current,
                TextRecognizerLanguage.JAPANESE,
                TextTranslatorLanguage.ENGLISH,
            )
            if (attempt == 1) {
                ContextualResponseParser.parseBatch("1|exact\n2:salvaged", request)
            } else {
                response(current)
            }
        }
        lateinit var result: AiChunkOutcome
        val firstTrace = captureBatchTrace {
            result = translate(chunk(page), translator)
        }

        val emptyTranslator = ScriptedTranslator { _, current -> response(current) }
        lateinit var emptyResult: AiChunkOutcome
        val emptyTrace = captureBatchTrace {
            emptyResult = translate(
                chunk(PageTranslation(blocks = mutableListOf())),
                emptyTranslator,
            )
        }
        val firstPass = firstTrace.filter { it.contains("phase=first_pass") }
        val emptyPass = emptyTrace.filter { it.contains("phase=first_pass") }
        val checks = listOf(
            result is AiChunkOutcome.Complete,
            translator.calls == 2,
            firstPass.size == 1,
            firstPass.singleOrNull()?.contains(
                "requested=4 accepted=2 resolvedPct=50.0 exact=1 salvaged=1 unresolved=2",
            ) == true,
            emptyResult is AiChunkOutcome.Complete,
            emptyTranslator.calls == 0,
            emptyPass.size == 1,
            emptyPass.singleOrNull()?.contains(
                "phase=first_pass requested=0 accepted=0 resolvedPct=na",
            ) == true,
        )
        checks shouldContainExactly List(checks.size) { true }
    }

    @Test
    fun `unresolved telemetry reports reason classes and overlapping conflict diagnostics`() = runTest {
        val page = PageTranslation(
            blocks = mutableListOf(
                block("blank"),
                block("conflict", x = 20f),
                block("待て！", x = 40f),
                block("missing", x = 60f),
                block("target", x = 80f),
            ),
        )
        val translator = ScriptedTranslator { attempt, current ->
            val request = ContextualRequestBuilder.buildFor(
                current,
                TextRecognizerLanguage.JAPANESE,
                TextTranslatorLanguage.ENGLISH,
            )
            if (attempt == 1) {
                ContextualResponseParser.parseBatch(
                    "1|   \n2|candidate a\n2|candidate b\n3|待て！\n" +
                        "5|안녕\n1. ambiguous ordinal",
                    request,
                )
            } else {
                response(current)
            }
        }
        lateinit var result: AiChunkOutcome
        val trace = captureBatchTrace {
            result = translate(chunk(page), translator)
        }
        val firstPass = trace.filter { it.contains("phase=first_pass") }
        val checks = listOf(
            result is AiChunkOutcome.Complete,
            translator.calls == 2,
            firstPass.size == 1,
            firstPass.singleOrNull()?.contains(
                "unresolvedReasons=blank,conflict,echo,missing,wrong_target",
            ) == true,
            firstPass.singleOrNull()?.contains("conflictCount=1 malformedCount=3") == true,
            firstPass.singleOrNull()?.contains("parseAmbiguity=1") == true,
            trace.any { it.contains("followUpUsed=1") },
            trace.any { it.contains("correctionLineUsed=1") },
            trace.any { it.contains("phase=semantic_follow_up") },
            trace.none { it.contains("candidate a") || it.contains("candidate b") || it.contains("안녕") },
        )
        checks shouldContainExactly List(checks.size) { true }
    }

    private data class RenderedRun(
        val prompts: List<String>,
        val chunks: List<TranslationContextChunk>,
        val outcome: AiChunkOutcome,
    )

    private suspend fun renderRun(
        sourceTexts: List<String>,
        fromLanguage: TextRecognizerLanguage = TextRecognizerLanguage.JAPANESE,
        toLanguage: TextTranslatorLanguage = TextTranslatorLanguage.ENGLISH,
        responder: suspend (Int, ContextualRequestBuilder.Request, TranslationContextChunk) -> ContextualTranslationBatch,
    ): RenderedRun {
        val prompts = mutableListOf<String>()
        val translator = ScriptedTranslator(fromLanguage, toLanguage) { attempt, current ->
            val request = ContextualRequestBuilder.buildFor(current, fromLanguage, toLanguage)
            prompts += ContextualRequestBuilder.renderPrompt(request, current.rollingContext)
            responder(attempt, request, current)
        }
        val page = PageTranslation(
            blocks = sourceTexts.mapIndexed { index, text -> block(text, x = index * 20f) }.toMutableList(),
        )
        val outcome = translate(chunk(page), translator)
        return RenderedRun(prompts.toList(), translator.requests.toList(), outcome)
    }

    private fun mappedBatch(
        request: ContextualRequestBuilder.Request,
        translations: Map<String, String>,
    ): ContextualTranslationBatch = ContextualRequestBuilder.toBatch(
        request,
        request.orderedIds.map { id ->
            ContextualTranslationResult(
                id = id,
                targetKey = request.idMap[id],
                text = translations.getValue(id),
                status = ContextualTranslationResult.Status.TRANSLATED,
            )
        },
    )

    private suspend fun captureBatchTrace(block: suspend () -> Unit): List<String> {
        val captured = mutableListOf<String>()
        val oldSink = TranslationPipelineDiagnostics.sink
        val oldGate = TranslationPipelineDiagnostics.detailedTracingEnabled
        TranslationPipelineDiagnostics.sink = TranslationTraceSink { _, line -> captured += line }
        TranslationPipelineDiagnostics.detailedTracingEnabled = true
        val schedule = TranslationPipelineDiagnostics.startSchedule(
            mode = TranslationTraceMode.BATCH,
            origin = TranslationTraceMode.BATCH,
            pages = 1,
        )
        BatchTranslationDiagnostics.noteActiveSchedule(schedule)
        try {
            block()
        } finally {
            BatchTranslationDiagnostics.noteActiveSchedule(null)
            schedule.end(TranslationTraceOutcome.SUCCESS)
            TranslationPipelineDiagnostics.sink = oldSink
            TranslationPipelineDiagnostics.detailedTracingEnabled = oldGate
        }
        return captured.toList()
    }
}
