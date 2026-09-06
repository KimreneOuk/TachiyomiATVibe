package eu.kanade.translation.translator.analysis

import eu.kanade.translation.artifact.StageFingerprints
import eu.kanade.translation.translator.BatchRequestSublimitGate
import eu.kanade.translation.translator.ProviderFailureException
import eu.kanade.translation.translator.ProviderFailureKind
import eu.kanade.translation.translator.ProviderFailureRetryability
import eu.kanade.translation.translator.ProviderRequestClock
import eu.kanade.translation.translator.ProviderRequestGovernor
import eu.kanade.translation.translator.ProviderQuotaPolicy
import eu.kanade.translation.translator.analysis.AnalysisResponseValidator.AnalysisResponseOutcome
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * T924 WP5 slice A: fake-analyzer coverage for the typed analysis client —
 * the V1..V9 hard-fail matrix (T924-AP-05, all response-fatal), the
 * malformed-response taxonomy (MISSING_ONLY / AMBIGUOUS_PROTOCOL /
 * TERMINAL_REFUSAL), the exactly-one-identical-reissue policy, the refusal
 * no-auto-retry rule, and the DR-A Option 1 MISSING_ONLY partial-commit
 * classification (provider-analysis contract §6).
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
                        AnalysisRequestBuilder.RequestBlock("p0_b0", blockText),
                    ),
                ),
                AnalysisRequestBuilder.RequestPage(
                    pageKey = "p1",
                    role = AnalysisRequestBuilder.ROLE_CONTEXT,
                    blocks = listOf(
                        AnalysisRequestBuilder.RequestBlock("p1_b0", "context page text"),
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

    private fun executor(transport: FakeTransport) = AnalysisChunkExecutor(
        transport = transport,
        sublimitGate = BatchRequestSublimitGate(NoDelayClock()),
        governor = ProviderRequestGovernor(
            policy = { ProviderQuotaPolicy(minimumSpacingMs = 0L, pollIntervalMs = 10L) },
            clock = NoDelayClock(),
        ),
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
    fun `malformed response gets exactly one identical reissue then typed PROTOCOL pause`() = runTest {
        val malformed = "{\"schemaVersion\":2,\"chunkId\":\"x\"}"
        val transport = FakeTransport(mutableListOf(malformed, malformed))
        val attempt = executor(transport).execute(request(), corePageKeys = setOf("p0"))
        attempt.shouldBeInstanceOf<AnalysisChunkAttempt.Malformed>()
        attempt.failure.kind shouldBe ProviderFailureKind.PROTOCOL
        // Malformed output is model-state: PAUSE, never TERMINAL.
        attempt.failure.retryability shouldBe ProviderFailureRetryability.PAUSE
        // The reissue is IDENTICAL (same wire document, gap-3 order intact).
        transport.calls.size shouldBe 2
        transport.calls[0] shouldBe transport.calls[1]
    }

    @Test
    fun `malformed then valid reissue commits the valid response`() = runTest {
        val transport = FakeTransport(
            mutableListOf(
                "garbage not json",
                validResponseJson().replace("\"COMEDY_X\", ", ""),
            ),
        )
        val attempt = executor(transport).execute(request(), corePageKeys = setOf("p0"))
        attempt.shouldBeInstanceOf<AnalysisChunkAttempt.Validated>()
        attempt.attemptsUsed shouldBe 2
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
