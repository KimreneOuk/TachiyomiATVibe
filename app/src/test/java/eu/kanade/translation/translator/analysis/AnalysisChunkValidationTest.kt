package eu.kanade.translation.translator.analysis

import eu.kanade.translation.artifact.StageFingerprints
import eu.kanade.translation.translator.BatchRequestSublimitGate
import eu.kanade.translation.translator.ProviderFailureException
import eu.kanade.translation.translator.ProviderFailureKind
import eu.kanade.translation.translator.ProviderFailureRetryability
import eu.kanade.translation.translator.ProviderQuotaPolicy
import eu.kanade.translation.translator.ProviderRequestClock
import eu.kanade.translation.translator.ProviderRequestGovernor
import eu.kanade.translation.translator.analysis.AnalysisResponseValidator.AnalysisResponseOutcome
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 *  WP5 slice A: fake-analyzer coverage for the typed analysis client —
 * the V1..V9 hard-fail matrix ( all response-fatal), the
 * response taxonomy (MISSING_ONLY / AMBIGUOUS_PROTOCOL / TERMINAL_REFUSAL),
 * the summary-mode rule (Director redesign: any non-refusal body commits as
 * a free-form summary on the FIRST attempt — no reissue loop), and the
 * refusal no-auto-retry rule.
 */
class AnalysisChunkValidationTest {

    private val blockText = "the demon sword sleeps beneath the shrine"

    /** Wire evidence universe: one CORE page `p0`, one CONTEXT page `p1`. */
    private fun request(): AnalysisRequestBuilder.AnalysisChunkRequest {
        val base = AnalysisRequestBuilder.buildChunkRequest(
            chunk = FakeChunks.chunk(),
            pages = listOf(
                AnalysisRequestBuilder.RequestPage(
                    pageKey = "p0",
                    role = AnalysisRequestBuilder.ROLE_CORE,
                    blocks = listOf(
                        AnalysisRequestBuilder.RequestBlock(
                            "p0_b0",
                            blockText,
                        ),
                    ),
                ),
                AnalysisRequestBuilder.RequestPage(
                    pageKey = "p1",
                    role = AnalysisRequestBuilder.ROLE_CONTEXT,
                    blocks = listOf(
                        AnalysisRequestBuilder.RequestBlock(
                            "p1_b0",
                            "context page text",
                        ),
                    ),
                ),
            ),
            identity = FakeChunks.identity(),
        )
        return base
    }

    private fun excerptHash16(text: String = blockText): String =
        StageFingerprints.sourceExcerptHash(text).take(16)

    private fun evidenceBlock(
        pageKey: String = "p0",
        blockId: String = "p0_b0",
        hash: String = "e:${excerptHash16()}",
    ): String = """{"pageKey":"$pageKey","blockId":"$blockId","excerptHash":"$hash","strength":"EXPLICIT"}"""

    /** A minimal response that passes every rule. */
    private fun validResponseJson(): String = """
        {
          "schemaVersion": 1,
          "chunkId": "${FakeChunks.CHUNK_ID}",
          "terms": [{
            "termId": "t001",
            "sourceForm": "魔剣",
            "canonicalTarget": "demon sword",
            "aliases": ["魔剣アルステラ"],
            "kind": "TERM",
            "applicability": "CANONICAL_CHAPTER_WIDE",
            "evidence": [ ${evidenceBlock()} ]
          }],
          "entities": [{
            "entityId": "e001",
            "sourceNames": ["レイナ"],
            "canonicalSourceName": "レイナ・アルステラ",
            "proposedTargetName": "Reina Alstella",
            "titles": ["剣姫"],
            "gender": { "value": "FEMALE", "strength": "EXPLICIT",
                        "evidence": [ ${evidenceBlock()} ] },
            "relationships": [ { "type": "RIVAL_OF", "targetEntityId": "e002",
                                 "evidence": [ ${evidenceBlock()} ] } ]
          }, {
            "entityId": "e002",
            "sourceNames": ["カイル"],
            "canonicalSourceName": "カイル",
            "proposedTargetName": "Kail"
          }],
          "scenes": [{
            "sceneId": "s001",
            "range": { "fromPage": "p0", "fromBlock": "p0_b0",
                       "toPage": "p1", "toBlock": "p1_b0" },
            "participants": ["e001"],
            "tone": ["COMEDY_X", "SERIOUS"],
            "contentTags": ["VIOLENT"],
            "register": "CASUAL",
            "narrative": "quiet shrine scene"
          }],
          "narrative": { "summary": "chapter-so-far synopsis" },
          "unresolvedQuestions": [ { "id": "u001", "question": "who?",
                                     "evidence": [ ${evidenceBlock()} ] } ],
          "candidateEquivalences": [ { "id": "c001", "sourceForms": ["レイナ"],
                                       "hypothesis": "SAME_ENTITY",
                                       "confidence": "MEDIUM",
                                       "evidence": [ ${evidenceBlock()} ] } ]
        }
    """.trimIndent()

    private fun classifyAgainstValid(response: String) =
        AnalysisResponseValidator.classify(
            rawText = response,
            request = request(),
            corePageKeys = setOf("p0"),
            contextPageKeys = setOf("p1"),
        )

    // ------------------------------------------------------------------
    // success + taxonomy
    // ------------------------------------------------------------------

    @Test
    fun `valid response classifies COMPLETE with resolved evidence`() {
        val outcome = classifyAgainstValid(validResponseJson().replace("\"COMEDY_X\", ", ""))
        outcome.shouldBeInstanceOf<AnalysisResponseOutcome.Validated>()
        outcome.coverage.kind shouldBe AnalysisCoverageKind.COMPLETE
        outcome.response.terms.single().canonicalTarget shouldBe "demon sword"
        outcome.response.entities.size shouldBe 2
        // The forward relationship edge (e001 -> e002) resolved.
        outcome.response.entities[0].relationships.single().targetEntityId shouldBe "e002"
        outcome.response.scenes.single().participants shouldContainExactly listOf("e001")
        outcome.response.narrativeSummary shouldBe "chapter-so-far synopsis"
        // Evidence anchors collected once per distinct reference use.
        outcome.response.evidenceRefs.isNotEmpty() shouldBe true
        outcome.droppedAuthorityKeys shouldBe emptyList()
    }

    @Test
    fun `clean but empty response is MISSING_ONLY (DR-A Option 1 input)`() {
        val outcome = classifyAgainstValid(
            """{"schemaVersion":1,"chunkId":"${FakeChunks.CHUNK_ID}"}""",
        )
        outcome.shouldBeInstanceOf<AnalysisResponseOutcome.Validated>()
        outcome.coverage.kind shouldBe AnalysisCoverageKind.MISSING_ONLY
        outcome.coverage.reasons.first() shouldContain "no extraction records"
        outcome.response.terms.isEmpty() shouldBe true
    }

    @Test
    fun `series-scoped authority keys are dropped and reported, never persisted`() {
        val response = """{"schemaVersion":1,"chunkId":"${FakeChunks.CHUNK_ID}",
            "seriesAuthority": {"entities": ["x"]},
            "user_canon": {"entries": ["y"]}}"""
        val outcome = classifyAgainstValid(response)
        outcome.shouldBeInstanceOf<AnalysisResponseOutcome.Validated>()
        outcome.droppedAuthorityKeys.shouldContainExactly("seriesAuthority", "user_canon")
    }

    // ------------------------------------------------------------------
    // V1..V9 matrix — every violation is response-fatal (AMBIGUOUS_PROTOCOL)
    // ------------------------------------------------------------------

    private fun assertMalformed(response: String, expectedFragment: String) {
        val outcome = classifyAgainstValid(response)
        outcome.shouldBeInstanceOf<AnalysisResponseOutcome.Malformed>()
        outcome.violations.joinToString("|") shouldContain expectedFragment
    }

    @Test
    fun `V1 unknown evidence page reference is fatal`() = assertMalformed(
        validResponseJson().replace(evidenceBlock(), evidenceBlock(pageKey = "p9")),
        "V1",
    )

    @Test
    fun `V1 unknown relationship target is fatal`() = assertMalformed(
        validResponseJson()
            .replace("\"COMEDY_X\", ", "")
            .replace("\"targetEntityId\": \"e002\"", "\"targetEntityId\": \"e777\""),
        "V1",
    )

    @Test
    fun `V2 duplicate entity id is fatal`() {
        val duplicatedId = validResponseJson()
            .replace("\"COMEDY_X\", ", "")
            .replace("\"entityId\": \"e002\"", "\"entityId\": \"e001\"")
        assertMalformed(duplicatedId, "V2 duplicate entityId e001")
    }

    @Test
    fun `V3 missing required field (entity canonical name) is fatal`() = assertMalformed(
        validResponseJson()
            .replace("\"COMEDY_X\", ", "")
            .replace("\"canonicalSourceName\": \"レイナ・アルステラ\",", ""),
        "V3",
    )

    @Test
    fun `V4 overlong field is fatal`() {
        val overlong = validResponseJson()
            .replace("\"COMEDY_X\", ", "")
            .replace("\"魔剣\"", "\"" + "a".repeat(200) + "\"")
        assertMalformed(overlong, "V4 terms[0].sourceForm exceeds 120 chars")
    }

    @Test
    fun `V5 invalid enum values are fatal`() {
        assertMalformed(
            validResponseJson(), // contains tone COMEDY_X on purpose
            "V5 scenes[0].tone invalid: COMEDY_X",
        )
        assertMalformed(
            validResponseJson()
                .replace("\"COMEDY_X\", ", "")
                .replace("\"value\": \"FEMALE\"", "\"value\": \"FLUID\""),
            "V5 entities[0].gender.value invalid: FLUID",
        )
    }

    @Test
    fun `V6 non-JSON and truncated bodies are fatal`() {
        // Deliberately NOT refusal prose — that classifies as TERMINAL_REFUSAL.
        assertMalformed("this response body is definitely not json at all", "V6")
        assertMalformed("{\"schemaVersion\":1,\"chunkId\":", "V6")
        assertMalformed("[]", "V6")
    }

    @Test
    fun `V7 wrong schemaVersion is fatal and never forward-interpreted`() = assertMalformed(
        validResponseJson().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
        "V7 schemaVersion must be 1, got 2",
    )

    @Test
    fun `V8 invented evidence hash is fatal`() = assertMalformed(
        validResponseJson()
            .replace("\"COMEDY_X\", ", "")
            .replace(excerptHash16(), "0123456789abcdef"),
        "V8",
    )

    @Test
    fun `V8 paraphrased hash format (not 16 hex) is fatal`() = assertMalformed(
        validResponseJson()
            .replace("\"COMEDY_X\", ", "")
            .replace("excerptHash\":\"e:${excerptHash16()}", "excerptHash\":\"ZZZ"),
        "V8",
    )

    @Test
    fun `V8 bare 16-hex hash without the e- prefix is fatal (wave-4 F-W4-4)`() = assertMalformed(
        validResponseJson()
            .replace("\"COMEDY_X\", ", "")
            .replace("excerptHash\":\"e:${excerptHash16()}", "excerptHash\":\"${excerptHash16()}"),
        "V8",
    )

    @Test
    fun `V3 missing term kind is fatal, not silently defaulted (wave-4 F-W4-4)`() = assertMalformed(
        validResponseJson()
            .replace("\"COMEDY_X\", ", "")
            .replace("\"kind\": \"TERM\",", ""),
        "V3 terms[0].kind required",
    )

    @Test
    fun `V4 overlong entity sourceNames or titles item is fatal (wave-5 F-W5-1)`() = assertMalformed(
        validResponseJson()
            .replace("\"COMEDIC_X\", ", "")
            .replace("\"sourceNames\": [\"レイナ\"]", "\"sourceNames\": [\"${"レ".repeat(200)}\"]"),
        "V4 entities[0].sourceNames item",
    )

    @Test
    fun `V9 record anchored only on CONTEXT pages is fatal`() = assertMalformed(
        validResponseJson()
            .replace("\"COMEDY_X\", ", "")
            .replace(evidenceBlock(), evidenceBlock(pageKey = "p1", blockId = "p1_b0", hash = "e:${excerptHash16("context page text")}")),
        "V9",
    )

    // ------------------------------------------------------------------
    // executor attempt policy
    // ------------------------------------------------------------------

    private class FakeTransport(
        private val responses: MutableList<String>,
    ) : AnalysisTextTransport {
        override val providerId: String = "fake"
        override val modelId: String = "fake-model"
        override val credentialSignature: String? = "cred-signature"
        val calls = mutableListOf<String>()

        override suspend fun postStructuredAnalysis(requestJson: String, chunkId: String): String {
            calls += requestJson
            return responses.removeFirstOrNull() ?: error("no scripted response")
        }
    }

    /** Deterministic, never-waiting clock: no real sleeps in these tests. */
    private class NoDelayClock : ProviderRequestClock {
        override fun nowEpochMs(): Long = 1_000L
        override suspend fun delay(millis: Long) {}
    }

    private fun executor(transport: AnalysisTextTransport) = AnalysisChunkExecutor(
        transport = transport,
        sublimitGate = BatchRequestSublimitGate(NoDelayClock()),
    )

    @Test
    fun `successful chunk returns validated content with provenance`() = runTest {
        val transport = FakeTransport(mutableListOf(validResponseJson().replace("\"COMEDY_X\", ", "")))
        val attempt = executor(transport).execute(request(), corePageKeys = setOf("p0"))
        attempt.shouldBeInstanceOf<AnalysisChunkAttempt.Validated>()
        attempt.coverage.kind shouldBe AnalysisCoverageKind.COMPLETE
        attempt.attemptsUsed shouldBe 1
        transport.calls.size shouldBe 1
    }

    @Test
    fun `any non-refusal free-form body is a valid summary without reissue`() = runTest {
        // Summary mode (Director decision): a body the strict contract once
        // called malformed is just free-form text — validated on the first
        // attempt, never reissued.
        val body = "{\"schemaVersion\":2,\"chunkId\":\"x\"}"
        val transport = FakeTransport(mutableListOf(body))
        val attempt = executor(transport).execute(request(), corePageKeys = setOf("p0"))
        attempt.shouldBeInstanceOf<AnalysisChunkAttempt.Validated>()
        attempt.response.narrativeSummary shouldBe body
        attempt.attemptsUsed shouldBe 1
        transport.calls.size shouldBe 1
    }

    @Test
    fun `blank and nothing answers are valid empty summaries`() = runTest {
        // Summary mode (Director decision): an explicit "nothing" or a blank
        // body means "no characters or places in this chunk" — a COMPLETE
        // empty summary committed on the first attempt, never a reissue.
        for (body in listOf("nothing", "  NOTHING  ", "   ")) {
            val transport = FakeTransport(mutableListOf(body))
            val attempt = executor(transport).execute(request(), corePageKeys = setOf("p0"))
            attempt.shouldBeInstanceOf<AnalysisChunkAttempt.Validated>()
            attempt.response.narrativeSummary.shouldBeNull()
            attempt.attemptsUsed shouldBe 1
            transport.calls.size shouldBe 1
        }
    }

    @Test
    fun `terminal refusal is typed and never auto-retried`() = runTest {
        val transport = FakeTransport(
            mutableListOf("I'm sorry, but I can't assist with that request"),
        )
        val attempt = executor(transport).execute(request(), corePageKeys = setOf("p0"))
        attempt.shouldBeInstanceOf<AnalysisChunkAttempt.Refused>()
        attempt.failure.kind shouldBe ProviderFailureKind.REFUSAL
        attempt.failure.retryability shouldBe ProviderFailureRetryability.TERMINAL
        transport.calls.size shouldBe 1
    }

    @Test
    fun `transport typed failures surface as transport pause`() = runTest {
        val failing = object : AnalysisTextTransport {
            override val providerId = "fake"
            override val modelId = "fake-model"
            override val credentialSignature: String? = null
            override suspend fun postStructuredAnalysis(requestJson: String, chunkId: String): String =
                throw ProviderFailureException(
                    eu.kanade.translation.translator.ProviderFailure(
                        kind = ProviderFailureKind.RATE_LIMIT,
                        retryability = ProviderFailureRetryability.RETRY_AFTER,
                        retryAfterMillis = 1_000L,
                        safeSummary = "429 slow down",
                    ),
                )
        }
        val attempt = AnalysisChunkExecutor(failing).execute(request(), corePageKeys = setOf("p0"))
        attempt.shouldBeInstanceOf<AnalysisChunkAttempt.TransportPaused>()
        attempt.failure.kind shouldBe ProviderFailureKind.RATE_LIMIT
    }

    // ------------------------------------------------------------------
    // Wave-7c review F-1 regression: the transport makes the ONLY shared
    // provider-bucket admission.
    // ------------------------------------------------------------------

    /**
     * Self-admits through a real [ProviderRequestGovernor] exactly like the
     * production engines (`OpenAiCompatibleTranslator`/`GeminiTranslator`
     * wrap their HTTP call in `requestGovernor.executeValue`). The old
     * executor ALSO admitted at the retry driver (`requestMetadata`), and
     * with `maxInFlight = 1` that outer admission held the bucket the nested
     * one needed — every chunk stalled to its foreground-wait deadline and
     * came back `TransportPaused`. With the fix, the single admission is
     * granted and the chunk validates.
     */
    private class SelfAdmittingTransport(
        private val governor: ProviderRequestGovernor,
        private val responseJson: () -> String,
    ) : AnalysisTextTransport {
        override val providerId: String = "fake"
        override val modelId: String = "fake-model"
        override val credentialSignature: String? = "cred-signature"

        override suspend fun postStructuredAnalysis(requestJson: String, chunkId: String): String =
            governor.executeValue(
                eu.kanade.translation.translator.ProviderRequestMetadata(
                    key = eu.kanade.translation.translator.ProviderRequestKey(
                        backend = providerId,
                        model = modelId,
                        credentialScope = credentialSignature,
                    ),
                    operation = "analysis_chunk",
                ),
            ) {
                responseJson()
            }
    }

    @Test
    fun `self-admitting transport with a maxInFlight=1 bucket still completes the chunk (F-1)`() = runTest {
        val governor = ProviderRequestGovernor(
            policy = {
                ProviderQuotaPolicy(
                    maxInFlight = 1,
                    minimumSpacingMs = 0L,
                    pollIntervalMs = 10L,
                    maxForegroundWaitMs = 300L,
                )
            },
        )
        val attempt = executor(
            SelfAdmittingTransport(governor) { validResponseJson().replace("\"COMEDY_X\", ", "") },
        ).execute(request(), corePageKeys = setOf("p0"))
        attempt.shouldBeInstanceOf<AnalysisChunkAttempt.Validated>()
        attempt.coverage.kind shouldBe AnalysisCoverageKind.COMPLETE
    }
}

/** Shared fake planning inputs for the analysis tests. */
object FakeChunks {
    const val CHUNK_ID = "chunk-0-abcdef12"

    fun chunk() = eu.kanade.translation.translator.contextual.PlannedAnalysisChunk(
        chunkOrdinal = 0,
        chunkId = CHUNK_ID,
        corePageKeys = listOf("0001.jpg"),
        contextOverlapPageKeys = listOf("0002.jpg"),
        contributingCorpusFingerprint = "a".repeat(64),
        coreBlockCount = 1,
        contributingBlockCount = 2,
        estimatedInputTokens = 20,
        contributingBlockIds = mapOf(
            "0001.jpg" to listOf("p0_b0"),
            "0002.jpg" to listOf("p1_b0"),
        ),
    )

    fun identity() = AnalysisRunIdentity(
        runId = "run-1-abcdef12",
        mangaKeyHash = AnalysisRunIdentity.SCOPE_ABSENT,
        chapterKeyHash = "sha256:" + "b".repeat(64),
        sourceLanguage = "ja",
        targetLanguage = "en",
        analysisPolicyFingerprint = "c".repeat(64),
        ocrCorpusFingerprint = "d".repeat(64),
        maxOutputTokens = 8192,
    )
}
