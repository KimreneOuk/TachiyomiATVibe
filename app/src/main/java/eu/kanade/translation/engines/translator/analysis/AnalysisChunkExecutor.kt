package eu.kanade.translation.engines.translator.analysis

import eu.kanade.translation.engines.translator.AdmissionPriority
import eu.kanade.translation.engines.translator.BatchProviderSublimit
import eu.kanade.translation.engines.translator.BatchRequestSublimitGate
import eu.kanade.translation.engines.translator.ProviderFailure
import eu.kanade.translation.engines.translator.ProviderFailureKind
import eu.kanade.translation.engines.translator.ProviderFailureRetryability
import eu.kanade.translation.engines.translator.ProviderRequestKey
import eu.kanade.translation.engines.translator.ProviderRequestMetadata
import eu.kanade.translation.engines.translator.SharedBatchRequestSublimitGate
import eu.kanade.translation.engines.translator.contextual.TranslationResponseFaithfulness
import eu.kanade.translation.engines.translator.providers.OcrArtifactSanitizer
import eu.kanade.translation.engines.translator.retry.RequestRetryBudget
import eu.kanade.translation.engines.translator.retry.withTranslationRetry
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Typed structured-analysis client.
 *
 * The `promptText` path swallows failures into an empty string, so analysis
 * uses this typed interface. It returns raw provider text and preserves typed
 * failures through the shared taxonomy.
 */
interface AnalysisTextTransport {
    /** Provider identity, e.g. `gemini` (AnalyzerProvenance.providerId). */
    val providerId: String

    /** Model identity, e.g. `gemini-2.5` (AnalyzerProvenance.modelId). */
    val modelId: String

    /** Opaque credential signature; never a raw credential. */
    val credentialSignature: String?

    /**
     * Posts ONE structured-analysis request document and returns the model's
     * raw text output. Transport failures MUST be typed
     * [ProviderFailureException]s (the provider transports already do this);
     * anything else is classified by the shared failure classifier.
     */
    suspend fun postStructuredAnalysis(requestJson: String, chunkId: String): String
}

/** Typed result of executing ONE analysis chunk (attempt policy applied). */
sealed interface AnalysisChunkAttempt {

    /** The response validated; [coverage] carries the DR-A MISSING_ONLY split. */
    data class Validated(
        val response: AnalysisResponseValidator.ValidatedAnalysisResponse,
        val coverage: AnalysisCoverage,
        val droppedAuthorityKeys: List<String>,
        val attemptsUsed: Int,
    ) : AnalysisChunkAttempt

    /**
     * AMBIGUOUS_PROTOCOL after exactly one identical reissue: the
     * run pauses at this chunk with a typed PROTOCOL failure — model-state,
     * not user-state, so never TERMINAL, never silently skipped.
     */
    data class Malformed(
        val violations: List<String>,
        val failure: ProviderFailure,
    ) : AnalysisChunkAttempt

    /** TERMINAL_REFUSAL: typed, no auto-retry, nothing retained (§3.2). */
    data class Refused(
        val marker: String,
        val failure: ProviderFailure,
    ) : AnalysisChunkAttempt

    /** Transport-level pause (quota/rate-limit/admission defer). */
    data class TransportPaused(
        val failure: ProviderFailure,
    ) : AnalysisChunkAttempt
}

/**
 * Executes the per-chunk attempt policy: transport retries under
 * `withTranslationRetry` (3 transport attempts inside the shared root budget
 * mechanism,.3), admission through the nested 15-RPM Batch
 * sub-limit bucket and then exactly ONE shared-provider-bucket admission
 * made by the transport itself (DR-D; mirrors the translation envelope,
 * ProfileEnvelopeExecutor), semantic policy = 0-1 identical reissues on
 * PROTOCOL_MALFORMED, then pause.
 *
 * The executor deliberately holds NO [eu.kanade.translation.engines.translator.ProviderRequestGovernor]:
 * the real transports (`OpenAiCompatibleTranslator`/`GeminiTranslator`)
 * already admit every HTTP attempt through the shared bucket. Passing
 * request metadata to the retry driver as well made a SECOND nested
 * admission on the same key, which `maxInFlight = 1` could never grant —
 * every analysis chunk stalled to its foreground-wait deadline and paused.
 */
class AnalysisChunkExecutor(
    private val transport: AnalysisTextTransport,
    private val sublimitGate: BatchRequestSublimitGate = SharedBatchRequestSublimitGate.instance,
) {

    private val requestKey: ProviderRequestKey = ProviderRequestKey(
        backend = transport.providerId,
        model = transport.modelId,
        credentialScope = transport.credentialSignature,
    )

    /** Adapter exposing this executor's transport as a coordinator runner. */
    fun runner(): AnalysisChunkRunner = AnalysisChunkRunner { chunk, identity, evidence ->
        val request = AnalysisRequestBuilder.buildChunkRequest(
            chunk = chunk,
            pages = evidence.toRequestPages(chunk),
            identity = identity,
        )
        val corePageKeys = chunk.corePageKeys.mapNotNull { storageKey ->
            evidence.wirePageKeyByStorageKey[storageKey] ?: storageKey
        }.toSet()
        when (val attempt = execute(request, corePageKeys = corePageKeys)) {
            is AnalysisChunkAttempt.Validated -> AnalysisChunkRunOutcome.Completed(
                response = attempt.response,
                coverage = attempt.coverage,
                droppedAuthorityKeys = attempt.droppedAuthorityKeys,
                provenance = AnalyzerProvenanceFactory.from(transport),
            )
            is AnalysisChunkAttempt.Malformed -> AnalysisChunkRunOutcome.Paused(
                failure = attempt.failure,
                reason = "analysis chunk ${chunk.chunkId} malformed after reissue: " +
                    attempt.violations.take(3).joinToString("; "),
            )
            is AnalysisChunkAttempt.Refused -> AnalysisChunkRunOutcome.Refused(
                failure = attempt.failure,
                reason = "analysis chunk ${chunk.chunkId} refused: ${attempt.marker}",
            )
            is AnalysisChunkAttempt.TransportPaused -> AnalysisChunkRunOutcome.Paused(
                failure = attempt.failure,
                reason = "analysis chunk ${chunk.chunkId} transport paused: ${attempt.failure.safeSummary}",
            )
        }
    }

    /**
     * ONE attempt sequence for one chunk: admitted through the Batch
     * sub-limit, then transport-retried — the transport makes the ONE
     * shared-provider-bucket admission per HTTP attempt — and validated.
     * Malformed responses get EXACTLY ONE identical reissue
     *; refusals get none (typed terminal for the request).
     */
    suspend fun execute(
        request: AnalysisRequestBuilder.AnalysisChunkRequest,
        corePageKeys: Set<String>,
    ): AnalysisChunkAttempt {
        val metadata = ProviderRequestMetadata(
            key = requestKey,
            estimatedInputTokens = estimateTokens(request.requestJson),
            reservedOutputTokens = 0,
            operation = "analysis_chunk",
            priority = AdmissionPriority.BACKGROUND,
        )
        val budget = RequestRetryBudget()
        var attemptsUsed = 0

        // At most two CLASSIFIED attempts (original + one identical reissue);
        // each attempt's TRANSPORT may retry under withTranslationRetry.
        while (attemptsUsed < 2) {
            attemptsUsed++
            val rawText = try {
                sublimitGate.executeBatch(metadata) {
                    // No requestMetadata here: the transport self-admits
                    // through the shared provider bucket exactly once
                    // A driver-level admission would nest a second one the
                    // maxInFlight=1 bucket never
                    // grants).
                    withTranslationRetry(
                        maxAttempts = 3,
                        baseDelayMs = 1_000L,
                        logTag = transport.providerId,
                        retryBudget = budget,
                    ) {
                        transport.postStructuredAnalysis(request.requestJson, request.chunkId)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: eu.kanade.translation.engines.translator.ProviderFailureException) {
                return when (e.failure.retryability) {
                    ProviderFailureRetryability.PAUSE,
                    ProviderFailureRetryability.RETRY_AFTER,
                    -> AnalysisChunkAttempt.TransportPaused(failure = e.failure)
                    else -> AnalysisChunkAttempt.TransportPaused(failure = e.failure)
                }
            } catch (e: eu.kanade.translation.engines.translator.retry.RequestRetryBudgetExhaustedException) {
                return AnalysisChunkAttempt.TransportPaused(
                    failure = ProviderFailure(
                        kind = ProviderFailureKind.QUOTA_EXHAUSTED,
                        retryability = ProviderFailureRetryability.PAUSE,
                        safeSummary = "analysis retry budget exhausted",
                    ),
                )
            }

            // Chapter-only authority guard is implicit in summary mode: the
            // free-form answer carries no structured authority keys.
            val outcome = classifySummary(rawText)
            when (outcome) {
                is SummaryOutcome.Refused -> {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT t924 analysis chunk=${request.chunkId} refused (terminal, no auto-retry)"
                    }
                    return AnalysisChunkAttempt.Refused(
                        marker = outcome.marker,
                        failure = ProviderFailure(
                            kind = ProviderFailureKind.REFUSAL,
                            retryability = ProviderFailureRetryability.TERMINAL,
                            safeSummary = "analysis chunk refused by the provider",
                        ),
                    )
                }
                is SummaryOutcome.Summary -> {
                    return AnalysisChunkAttempt.Validated(
                        response = AnalysisResponseValidator.ValidatedAnalysisResponse(
                            chunkId = null,
                            terms = emptyList(),
                            entities = emptyList(),
                            scenes = emptyList(),
                            narrativeSummary = outcome.text,
                            conflictNotes = emptyList(),
                            evidenceRefs = emptyList(),
                        ),
                        coverage = AnalysisCoverage(
                            kind = AnalysisCoverageKind.COMPLETE,
                            reasons = emptyList(),
                        ),
                        droppedAuthorityKeys = emptyList(),
                        attemptsUsed = attemptsUsed,
                    )
                }
            }
        }
        // Unreachable: the loop returns on every branch.
        return AnalysisChunkAttempt.Malformed(
            violations = listOf("V6 attempt loop exhausted without a classification"),
            failure = ProviderFailure(
                kind = ProviderFailureKind.PROTOCOL,
                retryability = ProviderFailureRetryability.PAUSE,
                safeSummary = "analysis attempt loop exhausted",
            ),
        )
    }

    /**
     * Summary-mode classification: free-form text in,
     * bounded summary out. The strict  validator (shape, id
     * patterns, verbatim hash echo) failed against real providers twice and
     * paused every run — a free-form answer cannot be structurally
     * malformed, so the only terminal outcomes are an explicit refusal and
     * transport failures. A blank or "nothing" answer is a VALID empty
     * summary (the chunk commits; the page set owes no records).
     */
    private sealed interface SummaryOutcome {
        data class Refused(val marker: String) : SummaryOutcome
        data class Summary(val text: String?) : SummaryOutcome
    }

    private fun classifySummary(rawText: String): SummaryOutcome {
        val stripped = OcrArtifactSanitizer.stripThinkingTags(rawText)
        if (TranslationResponseFaithfulness.isStructuralRefusal(stripped)) {
            return SummaryOutcome.Refused("structural refusal marker in response body")
        }
        val body = stripped.trim()
            .removePrefix("```").removePrefix("json").removePrefix("JSON")
            .substringBeforeLast("```")
            .trim()
        if (body.isBlank() || body.equals("nothing", ignoreCase = true)) {
            return SummaryOutcome.Summary(null)
        }
        return SummaryOutcome.Summary(body.take(AnalysisEngineTransport.MAX_SUMMARY_CHARS))
    }

    /** Coarse deterministic wire-size token estimate (governor input only). */
    private fun estimateTokens(requestJson: String): Int = (requestJson.length + 3) / 4
}

/** Test/production seam for the coordinator analysis phase. */
fun interface AnalysisChunkRunner {
    suspend fun executeChunk(
        chunk: eu.kanade.translation.engines.translator.contextual.PlannedAnalysisChunk,
        identity: AnalysisRunIdentity,
        evidence: AnalysisEvidenceTexts,
    ): AnalysisChunkRunOutcome
}

/** Typed outcome the coordinator persists / reacts to (slice-A contract). */
sealed interface AnalysisChunkRunOutcome {
    data class Completed(
        val response: AnalysisResponseValidator.ValidatedAnalysisResponse,
        val coverage: AnalysisCoverage,
        val droppedAuthorityKeys: List<String>,
        val provenance: eu.kanade.translation.persistence.artifact.AnalyzerProvenance,
    ) : AnalysisChunkRunOutcome

    data class Paused(
        val failure: ProviderFailure,
        val reason: String,
    ) : AnalysisChunkRunOutcome

    data class Refused(
        val failure: ProviderFailure,
        val reason: String,
    ) : AnalysisChunkRunOutcome
}

/** Builds the persisted [eu.kanade.translation.persistence.artifact.AnalyzerProvenance]. */
object AnalyzerProvenanceFactory {

    /** Free-form chunk summaries; the persisted analysis schema is versioned separately. */
    const val PROMPT_VERSION = 2
    const val ANALYSIS_SCHEMA_VERSION = AnalysisRequestBuilder.SCHEMA_VERSION

    fun from(transport: AnalysisTextTransport): eu.kanade.translation.persistence.artifact.AnalyzerProvenance =
        eu.kanade.translation.persistence.artifact.AnalyzerProvenance(
            providerId = transport.providerId,
            modelId = transport.modelId,
            promptVersion = PROMPT_VERSION,
            analysisSchemaVersion = ANALYSIS_SCHEMA_VERSION,
            credentialFingerprint = transport.credentialSignature,
        )
}

/** Maps wire evidence texts onto request pages in core-then-context order. */
fun AnalysisEvidenceTexts.toRequestPages(
    chunk: eu.kanade.translation.engines.translator.contextual.PlannedAnalysisChunk,
): List<AnalysisRequestBuilder.RequestPage> {
    val coreSet = chunk.corePageKeys.toSet()
    return chunk.contributingPageKeys.map { storageKey ->
        val pageKey = wirePageKeyByStorageKey[storageKey] ?: storageKey
        val blockIds = blockIdsByPage[pageKey].orEmpty()
        AnalysisRequestBuilder.RequestPage(
            pageKey = pageKey,
            role = if (storageKey in coreSet) {
                AnalysisRequestBuilder.ROLE_CORE
            } else {
                AnalysisRequestBuilder.ROLE_CONTEXT
            },
            blocks = blockIds.map { blockId ->
                AnalysisRequestBuilder.RequestBlock(
                    blockId = blockId,
                    text = textByBlockId[blockId].orEmpty(),
                )
            },
        )
    }
}

/** Refusal detector re-export for tests that assert the taxonomy split. */
fun isStructuralAnalysisRefusal(text: String): Boolean =
    TranslationResponseFaithfulness.isStructuralRefusal(text)

/** Exposed for tests/diagnostics: the sublimit key derivation. */
fun batchSublimitKeyFor(key: ProviderRequestKey): ProviderRequestKey =
    BatchProviderSublimit.batchSublimitKey(key)
