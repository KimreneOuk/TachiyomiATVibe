package eu.kanade.translation.translator.analysis

import eu.kanade.translation.translator.AdmissionPriority
import eu.kanade.translation.translator.BatchProviderSublimit
import eu.kanade.translation.translator.BatchRequestSublimitGate
import eu.kanade.translation.translator.ProviderFailure
import eu.kanade.translation.translator.ProviderFailureKind
import eu.kanade.translation.translator.ProviderFailureRetryability
import eu.kanade.translation.translator.ProviderRequestKey
import eu.kanade.translation.translator.ProviderRequestMetadata
import eu.kanade.translation.translator.SharedBatchRequestSublimitGate
import eu.kanade.translation.translator.SharedProviderRequestGovernor
import eu.kanade.translation.translator.contextual.TranslationResponseFaithfulness
import eu.kanade.translation.translator.analysis.AnalysisResponseValidator.AnalysisResponseOutcome
import eu.kanade.translation.translator.retry.RequestRetryBudget
import eu.kanade.translation.translator.retry.withTranslationRetry
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * T924 WP5 slice A (T924-AP-02/07/08): the typed structured-analysis client.
 *
 * `promptText` is EXPLICITLY FORBIDDEN as the analysis transport (T924-AP-01/08
 * — it swallows every failure into an empty string). Analysis goes through
 * this typed interface whose single operation returns the provider's raw text
 * and whose failures stay typed ([ProviderFailureException] subclasses flow
 * through the shared taxonomy unchanged).
 */
interface AnalysisTextTransport {
    /** Provider identity, e.g. `gemini` (AnalyzerProvenance.providerId). */
    val providerId: String

    /** Model identity, e.g. `gemini-2.5` (AnalyzerProvenance.modelId). */
    val modelId: String

    /** Opaque credential signature; never a raw credential (T924-FP-04). */
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
     * AMBIGUOUS_PROTOCOL after exactly one identical reissue (T924-AP-05): the
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
 * mechanism, T924-AP-08.3), admission through the nested 15-RPM Batch
 * sub-limit bucket and then the shared provider bucket (DR-D), semantic
 * policy = 0-1 identical reissues on PROTOCOL_MALFORMED, then pause.
 */
class AnalysisChunkExecutor(
    private val transport: AnalysisTextTransport,
    private val sublimitGate: BatchRequestSublimitGate = SharedBatchRequestSublimitGate.instance,
    private val governor: eu.kanade.translation.translator.ProviderRequestGovernor =
        SharedProviderRequestGovernor.instance,
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
     * sub-limit, transport-retried through the shared governor bucket, then
     * validated. Malformed responses get EXACTLY ONE identical reissue
     * (T924-AP-05); refusals get none (typed terminal for the request).
     */
    suspend fun execute(
        request: AnalysisRequestBuilder.AnalysisChunkRequest,
        corePageKeys: Set<String>,
    ): AnalysisChunkAttempt {
        val contextPageKeys = request.orderedPageKeys.toSet() - corePageKeys
        val metadata = ProviderRequestMetadata(
            key = requestKey,
            estimatedInputTokens = estimateTokens(request.requestJson),
            reservedOutputTokens = 0,
            operation = "analysis_chunk",
            priority = AdmissionPriority.BACKGROUND,
        )
        val budget = RequestRetryBudget()
        var attemptsUsed = 0
        var malformedViolations: List<String>? = null

        // At most two CLASSIFIED attempts (original + one identical reissue);
        // each attempt's TRANSPORT may retry under withTranslationRetry.
        while (attemptsUsed < 2) {
            attemptsUsed++
            val rawText = try {
                sublimitGate.executeBatch(metadata) {
                    withTranslationRetry(
                        maxAttempts = 3,
                        baseDelayMs = 1_000L,
                        logTag = transport.providerId,
                        requestMetadata = metadata,
                        governor = governor,
                        retryBudget = budget,
                    ) {
                        transport.postStructuredAnalysis(request.requestJson, request.chunkId)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: eu.kanade.translation.translator.ProviderFailureException) {
                return when (e.failure.retryability) {
                    ProviderFailureRetryability.PAUSE,
                    ProviderFailureRetryability.RETRY_AFTER,
                    -> AnalysisChunkAttempt.TransportPaused(failure = e.failure)
                    else -> AnalysisChunkAttempt.TransportPaused(failure = e.failure)
                }
            } catch (e: eu.kanade.translation.translator.retry.RequestRetryBudgetExhaustedException) {
                return AnalysisChunkAttempt.TransportPaused(
                    failure = ProviderFailure(
                        kind = ProviderFailureKind.QUOTA_EXHAUSTED,
                        retryability = ProviderFailureRetryability.PAUSE,
                        safeSummary = "analysis retry budget exhausted",
                    ),
                )
            }

            // Chapter-only authority guard: dropped + logged, never persisted.
            when (val outcome = AnalysisResponseValidator.classify(rawText, request, corePageKeys, contextPageKeys)) {
                is AnalysisResponseOutcome.Refusal -> {
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
                is AnalysisResponseOutcome.Malformed -> {
                    if (malformedViolations == null) {
                        // T924-AP-05: exactly one identical reissue.
                        malformedViolations = outcome.violations
                        logcat(LogPriority.WARN) {
                            "TachiyomiAT t924 analysis chunk=${request.chunkId} malformed " +
                                "(one identical reissue allowed): ${outcome.violations.firstOrNull()}"
                        }
                        continue
                    }
                    return AnalysisChunkAttempt.Malformed(
                        violations = outcome.violations,
                        failure = ProviderFailure(
                            kind = ProviderFailureKind.PROTOCOL,
                            retryability = ProviderFailureRetryability.PAUSE,
                            safeSummary = "analysis chunk malformed after the allowed reissue",
                        ),
                    )
                }
                is AnalysisResponseOutcome.Validated -> {
                    if (outcome.droppedAuthorityKeys.isNotEmpty()) {
                        logcat(LogPriority.WARN) {
                            "TachiyomiAT t924 analysis chunk=${request.chunkId} dropped series-scoped " +
                                "response keys (chapter-only authority): ${outcome.droppedAuthorityKeys}"
                        }
                    }
                    return AnalysisChunkAttempt.Validated(
                        response = outcome.response,
                        coverage = outcome.coverage,
                        droppedAuthorityKeys = outcome.droppedAuthorityKeys,
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

    /** Coarse deterministic wire-size token estimate (governor input only). */
    private fun estimateTokens(requestJson: String): Int = (requestJson.length + 3) / 4
}

/** Test/production seam for the coordinator analysis phase. */
fun interface AnalysisChunkRunner {
    suspend fun executeChunk(
        chunk: eu.kanade.translation.translator.contextual.PlannedAnalysisChunk,
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
        val provenance: eu.kanade.translation.artifact.AnalyzerProvenance,
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

/** Builds the persisted [eu.kanade.translation.artifact.AnalyzerProvenance]. */
object AnalyzerProvenanceFactory {
    const val PROMPT_VERSION = 1
    const val ANALYSIS_SCHEMA_VERSION = AnalysisRequestBuilder.SCHEMA_VERSION

    fun from(transport: AnalysisTextTransport): eu.kanade.translation.artifact.AnalyzerProvenance =
        eu.kanade.translation.artifact.AnalyzerProvenance(
            providerId = transport.providerId,
            modelId = transport.modelId,
            promptVersion = PROMPT_VERSION,
            analysisSchemaVersion = ANALYSIS_SCHEMA_VERSION,
            credentialFingerprint = transport.credentialSignature,
        )
}

/** Maps wire evidence texts onto request pages in core-then-context order. */
fun AnalysisEvidenceTexts.toRequestPages(
    chunk: eu.kanade.translation.translator.contextual.PlannedAnalysisChunk,
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
