package eu.kanade.translation.engines.translator.retry
import eu.kanade.translation.diagnostics.BatchDiagnosticReason
import eu.kanade.translation.diagnostics.BatchEnvelopeLifecycle
import eu.kanade.translation.diagnostics.BatchTranslationDiagnostics
import eu.kanade.translation.engines.translator.ProviderFailure
import eu.kanade.translation.engines.translator.ProviderFailureKind
import eu.kanade.translation.engines.translator.ProviderFailureRetryability
import eu.kanade.translation.engines.translator.ProviderRequestClock
import eu.kanade.translation.engines.translator.ProviderRequestPausedException
import eu.kanade.translation.engines.translator.SystemProviderRequestClock
import eu.kanade.translation.engines.translator.TranslationOutputSemantics
import eu.kanade.translation.engines.translator.contextual.AnchoredTargetKey
import eu.kanade.translation.engines.translator.contextual.BatchTranslationProtocol
import eu.kanade.translation.engines.translator.contextual.ContextualRequestBuilder
import eu.kanade.translation.engines.translator.contextual.ContextualResponseParser
import eu.kanade.translation.engines.translator.contextual.ContextualTextTranslator
import eu.kanade.translation.engines.translator.contextual.ContextualTranslationBatch
import eu.kanade.translation.engines.translator.contextual.ContextualTranslationResult
import eu.kanade.translation.engines.translator.contextual.STRUCTURAL_REFUSAL_DIAGNOSTIC
import eu.kanade.translation.engines.translator.contextual.StableBlockIds
import eu.kanade.translation.engines.translator.contextual.TargetLocation
import eu.kanade.translation.engines.translator.contextual.TranslationContextChunk
import eu.kanade.translation.engines.translator.contextual.TranslationContextChunkPlanner
import eu.kanade.translation.engines.translator.contextual.TranslationCorrectionHint
import eu.kanade.translation.engines.translator.contextual.TranslationResponseFaithfulness
import eu.kanade.translation.engines.translator.providers.GeminiEmptyResponseException
import eu.kanade.translation.engines.translator.providers.OcrArtifactSanitizer
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.util.ShortHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import logcat.LogPriority
import logcat.logcat
import java.util.Locale
import kotlin.coroutines.coroutineContext

private val conflictingDuplicateIdDiagnostic = Regex("""^Conflicting duplicate id '([^']+)'$""")

/**
 * Caps identical Gemini empty-response reissues for a stable payload. The count rides an
 * existing durable failure record; a recordless pause does not create one just for the counter,
 * so each recordless pause cycle can allow one additional reissue.
 */
internal const val MAX_EMPTY_GEMINI_RESPONSE_REISSUES = 2

/**
 * Request budget settings for one contextual envelope.
 *
 * The semantic retry limit properties remain for source compatibility but do
 * not enable additional model requests. An envelope may make one initial
 * request and at most one unresolved-only semantic follow-up; transport retries
 * remain governed by [RequestRetryBudget].
 */
data class AiTranslationRetryPolicy(
    val maxWholeEnvelopeRetries: Int = 1,
    val maxMissingBlockRequests: Int = 2,
    val maxTotalAttempts: Int = RequestRetryBudget.DEFAULT_MAX_ATTEMPTS,
) {
    init {
        require(maxWholeEnvelopeRetries >= 0) { "maxWholeEnvelopeRetries must be >= 0" }
        require(maxMissingBlockRequests >= 0) { "maxMissingBlockRequests must be >= 0" }
        require(maxTotalAttempts > 0) { "maxTotalAttempts must be > 0" }
    }
}

/**
 * Typed result of one AI semantic envelope.
 *
 * The controller returns detached block translations keyed by stable IDs. It
 * never mutates the [TranslationContextChunk] supplied by the caller.
 * [Paused] retains a non-authoritative partial candidate for a later pipeline
 * phase; committed page snapshots remain the only source of rolling history.
 */
sealed interface AiChunkOutcome {
    val acceptedBlockIds: Set<String>
    val completedPageKeys: Set<String>
    val blockTranslations: Map<String, String>
    val missingBlockIds: Set<String>
    val envelopeId: String
    val attemptsUsed: Int
    val wholeEnvelopeRetries: Int
    val missingBlockRequests: Int
    val emptyResponseReissues: Int
    val emptyResponseCapExhausted: Boolean

    data class Complete(
        override val acceptedBlockIds: Set<String>,
        override val completedPageKeys: Set<String>,
        override val blockTranslations: Map<String, String>,
        override val envelopeId: String,
        override val attemptsUsed: Int,
        override val wholeEnvelopeRetries: Int,
        override val missingBlockRequests: Int,
        override val emptyResponseReissues: Int = 0,
        override val emptyResponseCapExhausted: Boolean = false,
    ) : AiChunkOutcome {
        override val missingBlockIds: Set<String> = emptySet()
    }

    data class Paused(
        override val acceptedBlockIds: Set<String>,
        override val completedPageKeys: Set<String>,
        override val blockTranslations: Map<String, String>,
        override val missingBlockIds: Set<String>,
        val failure: ProviderFailure,
        val nextEligibleRetryAtEpochMs: Long?,
        val partialCandidate: Boolean,
        override val envelopeId: String,
        override val attemptsUsed: Int,
        override val wholeEnvelopeRetries: Int,
        override val missingBlockRequests: Int,
        override val emptyResponseReissues: Int = 0,
        override val emptyResponseCapExhausted: Boolean = false,
    ) : AiChunkOutcome

    data class Terminal(
        override val acceptedBlockIds: Set<String>,
        override val completedPageKeys: Set<String>,
        override val blockTranslations: Map<String, String>,
        override val missingBlockIds: Set<String>,
        val failure: ProviderFailure,
        override val envelopeId: String,
        override val attemptsUsed: Int,
        override val wholeEnvelopeRetries: Int,
        override val missingBlockRequests: Int,
        override val emptyResponseReissues: Int = 0,
        override val emptyResponseCapExhausted: Boolean = false,
    ) : AiChunkOutcome
}

/** Immutable OCR/source snapshot used for every request in one retry tree. */
private data class FrozenAiBlock(
    val stableId: String,
    val pageKey: String,
    val sourceText: String,
    val originalTranslation: String,
    val userEditedAt: Long?,
)

private data class FrozenAiEnvelope(
    val sourceChunk: TranslationContextChunk,
    val pages: LinkedHashMap<String, PageTranslation>,
    val pageOrder: List<String>,
    val blocksById: LinkedHashMap<String, FrozenAiBlock>,
    val pageIndexes: LinkedHashMap<String, Int>,
    val requestableIds: List<String>,
    val sourceLanguageCode: String,
    val targetLanguageCode: String,
    val identity: String,
) {
    fun chunkFor(
        stableIds: Collection<String>,
        correctionHint: TranslationCorrectionHint? = null,
    ): TranslationContextChunk {
        val requested = stableIds.toSet()
        val grouped = linkedMapOf<String, PageTranslation>()
        var count = 0
        pageOrder.forEach { pageKey ->
            val page = pages[pageKey] ?: return@forEach
            val blocks = page.blocks.filter { block ->
                val id = block.blockId?.let(ContextualResponseParser::normalizeBatchId)
                id != null && id in requested
            }.map { block -> block.detachedCopy() }.toMutableList()
            if (blocks.isNotEmpty()) {
                grouped[pageKey] = page.detachedCopy().apply { this.blocks = blocks }
                count += blocks.count { it.text.isNotBlank() }
            }
        }
        return sourceChunk.copy(
            pages = grouped,
            blockCount = count,
            // Keep the original estimate/cap conservative for targeted
            // requests. Context and glossary are intentionally byte-for-byte
            // identical across the retry tree.
            pageIndexes = pageIndexes.filterKeys { it in grouped },
            correctionHint = correctionHint,
        )
    }
}

private data class OutgoingRequest(
    val chunk: TranslationContextChunk,
    val request: ContextualRequestBuilder.Request,
    val requestIdToStableId: Map<String, String>,
    val targetKeyToStableId: Map<AnchoredTargetKey, String>,
    val locationToStableId: Map<TargetLocation, String>,
    val stableIds: Set<String>,
)

private data class ResponseAnalysis(
    val accepted: LinkedHashMap<String, String>,
    val missingIds: Set<String>,
    val duplicateIds: Set<String>,
    val conflictIds: Set<String>,
    val unknownCount: Int,
    val malformedCount: Int,
    val rejectedCount: Int,
    val refusal: ProviderFailure?,
    val itemReasons: Map<String, ResponseItemReason> = emptyMap(),
    val salvagedIds: Set<String> = emptySet(),
)

private enum class ResponseItemReason {
    MISSING,
    BLANK,
    SOURCE_ECHO,
    WRONG_TARGET,
    CONFLICT,
    FORMAT,
}

private data class AcceptedCandidate(
    val target: String,
    val normalizedTarget: String,
)

/** Detached accepted-results accumulator. Every merge returns a new value. */
private data class AiTranslationAccumulator(
    val translations: LinkedHashMap<String, String> = linkedMapOf(),
    val duplicateCount: Int = 0,
    val conflictCount: Int = 0,
    val unknownCount: Int = 0,
    val malformedCount: Int = 0,
    val rejectedCount: Int = 0,
) {
    /** Keep the first accepted value; later responses can never replace it. */
    fun merge(response: ResponseAnalysis): AiTranslationAccumulator {
        val merged = LinkedHashMap(translations)
        var duplicates = duplicateCount + response.duplicateIds.size
        var conflicts = conflictCount + response.conflictIds.size
        response.accepted.forEach { (stableId, translation) ->
            val existing = merged[stableId]
            if (existing == null) {
                merged[stableId] = translation
            } else {
                duplicates++
                if (existing != translation) conflicts++
            }
        }
        return copy(
            translations = merged,
            duplicateCount = duplicates,
            conflictCount = conflicts,
            unknownCount = unknownCount + response.unknownCount,
            malformedCount = malformedCount + response.malformedCount,
            rejectedCount = rejectedCount + response.rejectedCount,
        )
    }
}

/**
 * Runs one contextual request and at most one unresolved-only semantic follow-up.
 *
 * [retryBudget] is shared by every provider transport attempt in this
 * envelope. Concrete translators inherit it through [withRequestRetryBudget],
 * and the provider request governor consumes it only after admission. A translator
 * double that does not expose a governor boundary is charged once per logical
 * request, keeping tests and legacy adapters bounded without charging real
 * HTTP twice.
 */
@Suppress("UNUSED_PARAMETER")
internal suspend fun translateAiChunkWithAdaptiveRetry(
    translator: ContextualTextTranslator,
    chunk: TranslationContextChunk,
    requestedOutputTokens: Int,
    profile: TranslationContextChunkPlanner.Profile,
    label: String,
    retryDepth: Int = 0,
    retryBudget: RequestRetryBudget? = null,
    retryPolicy: AiTranslationRetryPolicy = AiTranslationRetryPolicy(),
    clock: ProviderRequestClock = SystemProviderRequestClock,
    emptyResponseReissuesAlreadyUsed: Int = 0,
): AiChunkOutcome {
    coroutineContext.ensureActive()
    // The arguments are retained in the direct-call API for compatibility;
    // the caller has already applied the output-cap/profile to [chunk].

    val budget = retryBudget ?: RequestRetryBudget(retryPolicy.maxTotalAttempts)
    val envelope = freezeEnvelope(chunk, translator.fromLang.code, translator.toLang.code)
    if (envelope.requestableIds.isEmpty()) {
        BatchTranslationDiagnostics.envelopeMetrics(
            "phase" to "first_pass",
            "requested" to "0",
            "accepted" to "0",
            "resolvedPct" to "na",
            "exact" to "0",
            "salvaged" to "0",
            "unresolved" to "0",
        )
        return completeOutcome(
            envelope = envelope,
            accumulator = AiTranslationAccumulator(),
            budget = budget,
            wholeRetries = 0,
            missingRequests = 0,
        )
    }

    if (emptyResponseReissuesAlreadyUsed >= MAX_EMPTY_GEMINI_RESPONSE_REISSUES) {
        val accumulator = AiTranslationAccumulator()
        val failure = ProviderFailure(
            kind = ProviderFailureKind.PROTOCOL,
            retryability = ProviderFailureRetryability.TERMINAL,
            safeSummary = "Gemini empty-response reissue cap exhausted " +
                "($emptyResponseReissuesAlreadyUsed/$MAX_EMPTY_GEMINI_RESPONSE_REISSUES)",
            requestId = envelope.identity,
        )
        return terminalOutcome(
            envelope = envelope,
            accumulator = accumulator,
            missingIds = remainingIds(envelope, accumulator),
            failure = failure,
            budget = budget,
            wholeRetries = 0,
            missingRequests = 0,
        ).copy(
            emptyResponseReissues = emptyResponseReissuesAlreadyUsed,
            emptyResponseCapExhausted = true,
        )
    }

    val safeLabel = ShortHash.hash(label).ifEmpty { "none" }
    var accumulator = AiTranslationAccumulator()
    var wholeRetries = 0
    var missingRequests = 0
    var requestKind = RequestKind.WHOLE
    var requestedIds = envelope.requestableIds
    var correctionHint: TranslationCorrectionHint? = null
    var emptyResponseReissues = emptyResponseReissuesAlreadyUsed
    var emptyResponseCapExhausted = false

    suspend fun runEnvelope(): AiChunkOutcome {
        while (true) {
            coroutineContext.ensureActive()
            if (budget.isExhausted) {
                return pausedOutcome(
                    envelope = envelope,
                    accumulator = accumulator,
                    missingIds = remainingIds(envelope, accumulator),
                    failure = budgetFailure(budget),
                    budget = budget,
                    wholeRetries = wholeRetries,
                    missingRequests = missingRequests,
                )
            }

            val outgoing = try {
                buildOutgoingRequest(
                    envelope = envelope,
                    stableIds = requestedIds,
                    translator = translator,
                    correctionHint = if (requestKind == RequestKind.MISSING) correctionHint else null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = protocolFailure(
                    envelope = envelope,
                    expectedCount = requestedIds.size,
                    receivedCount = 0,
                    missingCount = requestedIds.size,
                    duplicateCount = 0,
                    conflictCount = 0,
                    unknownCount = 0,
                    malformedCount = 1,
                    rejectedCount = 0,
                    retryability = ProviderFailureRetryability.TERMINAL,
                    attempt = budget.attemptsUsed,
                )
                return terminalOutcome(
                    envelope = envelope,
                    accumulator = accumulator,
                    missingIds = remainingIds(envelope, accumulator),
                    failure = failure,
                    budget = budget,
                    wholeRetries = wholeRetries,
                    missingRequests = missingRequests,
                )
            }

            logRequest(
                safeLabel = safeLabel,
                envelope = envelope,
                outgoing = outgoing,
                semanticAttempt = budget.attemptsUsed + 1,
                retryDepth = retryDepth,
                requestKind = requestKind,
            )
            val batch = try {
                var response: ContextualTranslationBatch? = null
                var emptyResponseReissuedForDispatch = false
                while (response == null) {
                    try {
                        response = requestStructured(
                            translator = translator,
                            outgoing = outgoing,
                            budget = budget,
                        )
                    } catch (e: GeminiEmptyResponseException) {
                        val isProtocolEmptyResponse =
                            classifyFailure(e, clock, envelope.identity).kind == ProviderFailureKind.PROTOCOL
                        if (
                            isProtocolEmptyResponse &&
                            !emptyResponseReissuedForDispatch &&
                            emptyResponseReissues < MAX_EMPTY_GEMINI_RESPONSE_REISSUES &&
                            !budget.isExhausted
                        ) {
                            emptyResponseReissues++
                            emptyResponseReissuedForDispatch = true
                            logcat(tag = "TranslationRetry", priority = LogPriority.WARN) {
                                "backend=ai_semantic translation_failure reason=empty_response_reissue " +
                                    "attempt=${budget.attemptsUsed} reissues=$emptyResponseReissues " +
                                    "envelope=${envelope.identity}"
                            }
                        } else {
                            emptyResponseCapExhausted =
                                emptyResponseReissues >= MAX_EMPTY_GEMINI_RESPONSE_REISSUES
                            throw e
                        }
                    }
                }
                checkNotNull(response)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val classified = classifyFailure(e, clock, envelope.identity)
                val failure = if (emptyResponseCapExhausted && e is GeminiEmptyResponseException) {
                    classified.copy(
                        safeSummary = "Gemini empty-response reissue cap exhausted " +
                            "($emptyResponseReissues/$MAX_EMPTY_GEMINI_RESPONSE_REISSUES)",
                    )
                } else {
                    classified
                }
                if (failure.retryability == ProviderFailureRetryability.TERMINAL) {
                    return terminalOutcome(
                        envelope = envelope,
                        accumulator = accumulator,
                        missingIds = remainingIds(envelope, accumulator),
                        failure = failure,
                        budget = budget,
                        wholeRetries = wholeRetries,
                        missingRequests = missingRequests,
                    )
                }
                if (failure.retryability == ProviderFailureRetryability.PAUSE) {
                    return pausedOutcome(
                        envelope = envelope,
                        accumulator = accumulator,
                        missingIds = remainingIds(envelope, accumulator),
                        failure = failure,
                        budget = budget,
                        wholeRetries = wholeRetries,
                        missingRequests = missingRequests,
                    )
                }
                // Transport retry exhaustion is not permission to regenerate
                // the same semantic envelope. The provider wrapper already
                // spent the admitted transport budget for this invocation.
                return pausedOutcome(
                    envelope = envelope,
                    accumulator = accumulator,
                    missingIds = remainingIds(envelope, accumulator),
                    failure = if (budget.isExhausted) budgetFailure(budget) else failure,
                    budget = budget,
                    wholeRetries = wholeRetries,
                    missingRequests = missingRequests,
                )
            }

            val analysis = analyzeResponse(
                batch = batch,
                outgoing = outgoing,
                envelope = envelope,
            )
            if (requestKind == RequestKind.WHOLE) {
                emitFirstPassMetrics(
                    requested = outgoing.stableIds.size,
                    analysis = analysis,
                    batch = batch,
                )
            }
            BatchTranslationDiagnostics.envelopeLifecycle(
                phase = BatchEnvelopeLifecycle.PARSED,
                pageKeys = envelope.pageOrder,
                attempt = budget.attemptsUsed,
                expectedItemCount = outgoing.stableIds.size,
                receivedItemCount = outgoing.stableIds.size - analysis.missingIds.size,
                reason = if (analysis.missingIds.isEmpty()) {
                    BatchDiagnosticReason.SUCCESS
                } else {
                    BatchDiagnosticReason.STAGE_FAILURE
                },
            )

            // A refusal invalidates this entire response. Prior accepted values
            // from an earlier logical request remain fenced, but nothing from
            // the refusing response enters the accumulator.
            analysis.refusal?.let { failure ->
                return terminalOutcome(
                    envelope = envelope,
                    accumulator = accumulator,
                    missingIds = remainingIds(envelope, accumulator),
                    failure = failure,
                    budget = budget,
                    wholeRetries = wholeRetries,
                    missingRequests = missingRequests,
                )
            }

            accumulator = accumulator.merge(analysis)
            val missingIds = remainingIds(envelope, accumulator)
            if (missingIds.isEmpty()) {
                return completeOutcome(
                    envelope = envelope,
                    accumulator = accumulator,
                    budget = budget,
                    wholeRetries = wholeRetries,
                    missingRequests = missingRequests,
                )
            }

            val unresolvedInFrozenOrder = envelope.requestableIds.filter { it in missingIds }
            if (missingRequests == 0 && !budget.isExhausted && unresolvedInFrozenOrder.isNotEmpty()) {
                // Consume the one semantic follow-up slot. Rebuilding the chunk
                // from these durable IDs gives ContextualRequestBuilder a fresh
                // 1..M namespace, while preserving frozen plan order and clean
                // OCR source text.
                missingRequests = 1
                requestedIds = unresolvedInFrozenOrder
                val unresolvedReasons = analysis.itemReasons.filterKeys { it in missingIds }.values
                val hasEcho = ResponseItemReason.SOURCE_ECHO in unresolvedReasons
                val hasWrongTarget = ResponseItemReason.WRONG_TARGET in unresolvedReasons
                correctionHint = if (hasEcho || hasWrongTarget) {
                    TranslationCorrectionHint(sourceEcho = hasEcho, wrongTargetLanguage = hasWrongTarget)
                } else {
                    null
                }
                BatchTranslationDiagnostics.envelopeMetrics(
                    "phase" to "semantic_follow_up",
                    "followUpUsed" to "1",
                    "correctionLineUsed" to if (correctionHint == null) "0" else "1",
                )
                requestKind = RequestKind.MISSING
                continue
            }

            val failure = protocolFailure(
                envelope = envelope,
                expectedCount = envelope.requestableIds.size,
                receivedCount = accumulator.translations.size,
                missingCount = missingIds.size,
                duplicateCount = accumulator.duplicateCount,
                conflictCount = accumulator.conflictCount,
                unknownCount = accumulator.unknownCount,
                malformedCount = accumulator.malformedCount,
                rejectedCount = accumulator.rejectedCount,
                retryability = ProviderFailureRetryability.PAUSE,
                attempt = budget.attemptsUsed,
            )
            return pausedOutcome(
                envelope = envelope,
                accumulator = accumulator,
                missingIds = missingIds,
                failure = failure,
                budget = budget,
                wholeRetries = wholeRetries,
                missingRequests = missingRequests,
            )
        }
    }

    val outcome = try {
        withRequestRetryBudget(budget) { runEnvelope() }
    } catch (e: CancellationException) {
        throw e
    }
    return when (outcome) {
        is AiChunkOutcome.Complete -> outcome.copy(
            emptyResponseReissues = emptyResponseReissues,
            emptyResponseCapExhausted = emptyResponseCapExhausted,
        )
        is AiChunkOutcome.Paused -> outcome.copy(
            emptyResponseReissues = emptyResponseReissues,
            emptyResponseCapExhausted = emptyResponseCapExhausted,
        )
        is AiChunkOutcome.Terminal -> outcome.copy(
            emptyResponseReissues = emptyResponseReissues,
            emptyResponseCapExhausted = emptyResponseCapExhausted,
        )
    }
}

private enum class RequestKind {
    WHOLE,
    MISSING,
}

private suspend fun requestStructured(
    translator: ContextualTextTranslator,
    outgoing: OutgoingRequest,
    budget: RequestRetryBudget,
): ContextualTranslationBatch {
    val before = budget.attemptsUsed
    var failed = false
    var admissionDeferred = false
    return try {
        withRequestRetryBudget(budget) {
            translator.translateContextualStructured(outgoing.chunk)
        }
    } catch (e: Throwable) {
        failed = true
        admissionDeferred = e is ProviderRequestPausedException ||
            e is RequestRetryBudgetExhaustedException
        throw e
    } finally {
        // Real providers consume at the governor boundary. Test doubles and
        // legacy implementations which do not expose that boundary still
        // represent one logical request and must consume one budget unit.
        if (budget.attemptsUsed == before && !budget.isExhausted && !admissionDeferred) {
            if (failed) {
                budget.tryConsumeAttempt()
            } else {
                budget.consumeAttemptOrThrow()
            }
        }
    }
}

private fun freezeEnvelope(
    chunk: TranslationContextChunk,
    sourceLanguageCode: String,
    targetLanguageCode: String,
): FrozenAiEnvelope {
    val pageIndexes = normalizedPageIndexes(chunk)
    val pages = linkedMapOf<String, PageTranslation>()
    val blocksById = linkedMapOf<String, FrozenAiBlock>()
    val requestable = mutableListOf<String>()

    chunk.pages.entries.forEach { (pageKey, originalPage) ->
        val page = originalPage.detachedCopy()
        val pageIndex = pageIndexes.getValue(pageKey)
        // Assignment happens only on the detached snapshot. The live OCR
        // objects therefore cannot be renamed or mutated while the provider
        // response remains provisional.
        StableBlockIds.assign(page, pageIndex)
        pages[pageKey] = page
        page.blocks.forEach blockLoop@{ block ->
            if (block.text.isBlank()) return@blockLoop
            val stableId = block.blockId
                ?.let(ContextualResponseParser::normalizeBatchId)
                ?: return@blockLoop
            val frozen = FrozenAiBlock(
                stableId = stableId,
                pageKey = pageKey,
                sourceText = block.text,
                originalTranslation = block.translation,
                userEditedAt = block.userEditedAt,
            )
            // StableBlockIds.assign guarantees uniqueness inside a page. The
            // normalized page-index map guarantees uniqueness across pages.
            if (blocksById.put(stableId, frozen) != null) {
                error("Duplicate stable AI block id $stableId")
            }
            val hasUsableExisting = TranslationOutputSemantics.isResolved(
                source = block.text,
                output = block.translation,
                sourceLanguageCode = sourceLanguageCode,
                targetLanguageCode = targetLanguageCode,
            )
            if (block.userEditedAt == null && !hasUsableExisting) {
                requestable += stableId
            }
        }
    }

    val identityInput = buildString {
        pageIndexes.entries.sortedBy { it.value }.forEach { (pageKey, index) ->
            appendIdentityField(pageKey)
            appendIdentityField(index)
            blocksById.values
                .asSequence()
                .filter { it.pageKey == pageKey }
                .sortedBy { it.stableId }
                .forEach { block ->
                    appendIdentityField(block.stableId)
                    appendIdentityField(block.sourceText)
                }
        }
    }
    return FrozenAiEnvelope(
        sourceChunk = chunk.copy(
            pages = LinkedHashMap(pages),
            pageIndexes = LinkedHashMap(pageIndexes),
            correctionHint = null,
        ),
        pages = pages,
        pageOrder = pages.keys.toList(),
        blocksById = blocksById,
        pageIndexes = pageIndexes,
        requestableIds = requestable,
        sourceLanguageCode = sourceLanguageCode,
        targetLanguageCode = targetLanguageCode,
        identity = ShortHash.hash(identityInput).ifEmpty { "none" },
    )
}

private fun buildOutgoingRequest(
    envelope: FrozenAiEnvelope,
    stableIds: Collection<String>,
    translator: ContextualTextTranslator,
    correctionHint: TranslationCorrectionHint? = null,
): OutgoingRequest {
    val chunk = envelope.chunkFor(stableIds, correctionHint)
    val request = ContextualRequestBuilder.buildFor(chunk, translator.fromLang, translator.toLang)
    val requestIdToStableId = linkedMapOf<String, String>()
    val locationToStableId = linkedMapOf<TargetLocation, String>()
    request.locations.forEach { (requestId, location) ->
        val block = chunk.pages[location.pageKey]?.blocks?.getOrNull(location.blockIndex)
            ?: return@forEach
        val stableId = block.blockId
            ?.let(ContextualResponseParser::normalizeBatchId)
            ?: return@forEach
        requestIdToStableId[requestId] = stableId
        requestIdToStableId[ContextualResponseParser.normalizeBatchId(requestId)] = stableId
        locationToStableId[location] = stableId
    }
    val targetKeyToStableId = linkedMapOf<AnchoredTargetKey, String>()
    request.idMap.forEach { (requestId, targetKey) ->
        val stableId = requestIdToStableId[requestId] ?: return@forEach
        targetKeyToStableId[targetKey] = stableId
    }
    return OutgoingRequest(
        chunk = chunk,
        request = request,
        requestIdToStableId = requestIdToStableId,
        targetKeyToStableId = targetKeyToStableId,
        locationToStableId = locationToStableId,
        stableIds = stableIds.toSet(),
    )
}

private fun analyzeResponse(
    batch: ContextualTranslationBatch,
    outgoing: OutgoingRequest,
    envelope: FrozenAiEnvelope,
): ResponseAnalysis {
    val malformedCount = batch.validationErrors.count { error ->
        !error.startsWith("Missing translation for '")
    } + if (batch.strictValidation && batch.protocolVersion != BatchTranslationProtocol.VERSION) 1 else 0

    val refusalPresent = batch.validationErrors.any { it == STRUCTURAL_REFUSAL_DIAGNOSTIC } ||
        batch.results.any { result ->
            TranslationResponseFaithfulness.isStructuralRefusal(
                OcrArtifactSanitizer.sanitize(result.text),
            )
        }
    if (refusalPresent) {
        return ResponseAnalysis(
            accepted = linkedMapOf(),
            missingIds = outgoing.stableIds,
            duplicateIds = emptySet(),
            conflictIds = emptySet(),
            unknownCount = 0,
            malformedCount = malformedCount,
            rejectedCount = batch.results.size,
            refusal = ProviderFailure(
                kind = ProviderFailureKind.REFUSAL,
                retryability = ProviderFailureRetryability.TERMINAL,
                safeSummary = "Provider refused one or more contextual translations",
                requestId = envelope.identity,
            ),
            itemReasons = outgoing.stableIds.associateWith { ResponseItemReason.MISSING },
        )
    }

    val candidatesByStableId = linkedMapOf<String, MutableList<AcceptedCandidate>>()
    val observedByStableId = linkedMapOf<String, Int>()
    val itemReasons = outgoing.stableIds.associateWithTo(linkedMapOf()) { ResponseItemReason.MISSING }
    val salvagedStableIds = batch.salvagedIds.mapNotNullTo(linkedSetOf()) { requestId ->
        outgoing.requestIdToStableId[ContextualResponseParser.normalizeBatchId(requestId)]
    }
    var unknownCount = 0
    var rejectedCount = 0

    batch.results.forEach { result ->
        val stableId = resolveStableId(result, outgoing)
        if (stableId == null || stableId !in outgoing.stableIds) {
            unknownCount++
            return@forEach
        }
        observedByStableId[stableId] = (observedByStableId[stableId] ?: 0) + 1

        val frozen = envelope.blocksById[stableId]
        if (frozen == null || frozen.userEditedAt != null) {
            // User edits are a hard write fence.
            rejectedCount++
            return@forEach
        }
        val sanitized = OcrArtifactSanitizer.sanitize(result.text)
        val semanticReason = TranslationOutputSemantics.unresolvedReason(
            source = frozen.sourceText,
            output = sanitized,
            sourceLanguageCode = envelope.sourceLanguageCode,
            targetLanguageCode = envelope.targetLanguageCode,
        )
        if (semanticReason != null) {
            itemReasons[stableId] = when (semanticReason) {
                TranslationOutputSemantics.UnresolvedReason.BLANK -> ResponseItemReason.BLANK
                TranslationOutputSemantics.UnresolvedReason.SOURCE_ECHO -> ResponseItemReason.SOURCE_ECHO
                TranslationOutputSemantics.UnresolvedReason.WRONG_TARGET_LANGUAGE -> ResponseItemReason.WRONG_TARGET
            }
            rejectedCount++
            return@forEach
        }
        if (result.status != ContextualTranslationResult.Status.TRANSLATED || sanitized.isBlank()) {
            itemReasons[stableId] = if (sanitized.isBlank()) ResponseItemReason.BLANK else ResponseItemReason.FORMAT
            rejectedCount++
            return@forEach
        }

        candidatesByStableId.getOrPut(stableId) { mutableListOf() } += AcceptedCandidate(
            target = sanitized,
            normalizedTarget = normalizeCandidateTarget(sanitized),
        )
    }

    val accepted = linkedMapOf<String, String>()
    val conflicts = linkedSetOf<String>()
    candidatesByStableId.forEach { (stableId, candidates) ->
        val uniqueTargets = candidates.distinctBy(AcceptedCandidate::normalizedTarget)
        when (uniqueTargets.size) {
            1 -> {
                accepted[stableId] = uniqueTargets.single().target
                itemReasons.remove(stableId)
            }
            in 2..Int.MAX_VALUE -> {
                conflicts += stableId
                itemReasons[stableId] = ResponseItemReason.CONFLICT
            }
        }
    }
    batch.validationErrors.forEach { error ->
        val parserConflictId = conflictingDuplicateIdDiagnostic.matchEntire(error)?.groupValues?.get(1)
            ?: return@forEach
        val stableId = outgoing.requestIdToStableId[
            ContextualResponseParser.normalizeBatchId(parserConflictId),
        ] ?: return@forEach
        if (stableId in outgoing.stableIds) {
            conflicts += stableId
            itemReasons[stableId] = ResponseItemReason.CONFLICT
        }
    }
    val duplicates = observedByStableId.filterValues { it > 1 }.keys
    val missing = outgoing.stableIds - accepted.keys
    return ResponseAnalysis(
        accepted = accepted,
        missingIds = missing,
        duplicateIds = duplicates,
        conflictIds = conflicts,
        unknownCount = unknownCount,
        malformedCount = malformedCount,
        rejectedCount = rejectedCount,
        refusal = null,
        itemReasons = itemReasons.filterKeys { it in missing },
        salvagedIds = salvagedStableIds.intersect(accepted.keys),
    )
}

private fun emitFirstPassMetrics(
    requested: Int,
    analysis: ResponseAnalysis,
    batch: ContextualTranslationBatch,
) {
    val accepted = analysis.accepted.size
    val salvaged = analysis.salvagedIds.size
    val resolvedPct = if (requested == 0) {
        "na"
    } else {
        "%.1f".format(Locale.ROOT, accepted * 100.0 / requested)
    }
    val reasonOrder = listOf(
        ResponseItemReason.BLANK,
        ResponseItemReason.CONFLICT,
        ResponseItemReason.SOURCE_ECHO,
        ResponseItemReason.MISSING,
        ResponseItemReason.WRONG_TARGET,
        ResponseItemReason.FORMAT,
    )
    val reasons = reasonOrder.filter { reason -> analysis.itemReasons.values.any { it == reason } }
        .joinToString(",") { it.token }
    BatchTranslationDiagnostics.envelopeMetrics(
        "phase" to "first_pass",
        "requested" to requested.toString(),
        "accepted" to accepted.toString(),
        "resolvedPct" to resolvedPct,
        "exact" to (accepted - salvaged).coerceAtLeast(0).toString(),
        "salvaged" to salvaged.toString(),
        "unresolved" to analysis.missingIds.size.toString(),
        "unresolvedReasons" to reasons.ifEmpty { "none" },
        "conflictCount" to analysis.conflictIds.size.toString(),
        "malformedCount" to analysis.malformedCount.toString(),
        "parseAmbiguity" to batch.parseAmbiguityCount.toString(),
    )
}

private val ResponseItemReason.token: String
    get() = when (this) {
        ResponseItemReason.MISSING -> "missing"
        ResponseItemReason.BLANK -> "blank"
        ResponseItemReason.SOURCE_ECHO -> "echo"
        ResponseItemReason.WRONG_TARGET -> "wrong_target"
        ResponseItemReason.CONFLICT -> "conflict"
        ResponseItemReason.FORMAT -> "format"
    }

private fun normalizeCandidateTarget(target: String): String = java.text.Normalizer.normalize(
    target.replace("\r\n", "\n").replace('\r', '\n').trim(),
    java.text.Normalizer.Form.NFC,
)

private fun resolveStableId(
    result: ContextualTranslationResult,
    outgoing: OutgoingRequest,
): String? {
    val normalized = ContextualResponseParser.normalizeBatchId(result.id)
    val idStableId = outgoing.requestIdToStableId[normalized]
        ?: outgoing.requestIdToStableId[result.id]
        ?: outgoing.locationToStableId.entries.firstOrNull { (location, _) ->
            outgoing.request.protocol == eu.kanade.translation.engines.translator.contextual.ContextualRequestProtocol.LEGACY &&
                result.id == location.toString()
        }?.value
        ?: return null
    val targetKey = result.targetKey ?: return idStableId
    val keyStableId = outgoing.targetKeyToStableId[targetKey] ?: return null
    return idStableId.takeIf { it == keyStableId }
}

private fun remainingIds(
    envelope: FrozenAiEnvelope,
    accumulator: AiTranslationAccumulator,
): Set<String> = envelope.requestableIds.filterNot(accumulator.translations::containsKey).toSet()

private fun completeOutcome(
    envelope: FrozenAiEnvelope,
    accumulator: AiTranslationAccumulator,
    budget: RequestRetryBudget,
    wholeRetries: Int,
    missingRequests: Int,
): AiChunkOutcome.Complete {
    val completedPages = completedNaturalPrefix(envelope, accumulator.translations)
    return AiChunkOutcome.Complete(
        acceptedBlockIds = accumulator.translations.keys.toSet(),
        completedPageKeys = completedPages,
        blockTranslations = accumulator.translations.toMap(),
        envelopeId = envelope.identity,
        attemptsUsed = budget.attemptsUsed,
        wholeEnvelopeRetries = wholeRetries,
        missingBlockRequests = missingRequests,
    )
}

private fun pausedOutcome(
    envelope: FrozenAiEnvelope,
    accumulator: AiTranslationAccumulator,
    missingIds: Set<String>,
    failure: ProviderFailure,
    budget: RequestRetryBudget,
    wholeRetries: Int,
    missingRequests: Int,
): AiChunkOutcome.Paused {
    val normalizedFailure = failure.copy(
        retryability = ProviderFailureRetryability.PAUSE,
        requestId = failure.requestId ?: envelope.identity,
        attempt = failure.attempt ?: budget.attemptsUsed,
    )
    return AiChunkOutcome.Paused(
        acceptedBlockIds = accumulator.translations.keys.toSet(),
        completedPageKeys = completedNaturalPrefix(envelope, accumulator.translations),
        blockTranslations = accumulator.translations.toMap(),
        missingBlockIds = missingIds,
        failure = normalizedFailure,
        nextEligibleRetryAtEpochMs = normalizedFailure.retryAfterAtEpochMs,
        partialCandidate = accumulator.translations.isNotEmpty(),
        envelopeId = envelope.identity,
        attemptsUsed = budget.attemptsUsed,
        wholeEnvelopeRetries = wholeRetries,
        missingBlockRequests = missingRequests,
    )
}

private fun terminalOutcome(
    envelope: FrozenAiEnvelope,
    accumulator: AiTranslationAccumulator,
    missingIds: Set<String>,
    failure: ProviderFailure,
    budget: RequestRetryBudget,
    wholeRetries: Int,
    missingRequests: Int,
): AiChunkOutcome.Terminal = AiChunkOutcome.Terminal(
    acceptedBlockIds = accumulator.translations.keys.toSet(),
    completedPageKeys = completedNaturalPrefix(envelope, accumulator.translations),
    blockTranslations = accumulator.translations.toMap(),
    missingBlockIds = missingIds,
    failure = failure.copy(
        retryability = ProviderFailureRetryability.TERMINAL,
        requestId = failure.requestId ?: envelope.identity,
        attempt = failure.attempt ?: budget.attemptsUsed,
    ),
    envelopeId = envelope.identity,
    attemptsUsed = budget.attemptsUsed,
    wholeEnvelopeRetries = wholeRetries,
    missingBlockRequests = missingRequests,
)

private fun completedNaturalPrefix(
    envelope: FrozenAiEnvelope,
    translations: Map<String, String>,
): Set<String> {
    val completeIds = envelope.blocksById.values
        .filter { block ->
            block.userEditedAt != null ||
                TranslationOutputSemantics.isResolved(
                    source = block.sourceText,
                    output = block.originalTranslation,
                    sourceLanguageCode = envelope.sourceLanguageCode,
                    targetLanguageCode = envelope.targetLanguageCode,
                ) ||
                block.stableId in translations
        }
        .mapTo(hashSetOf()) { it.stableId }
    val completed = linkedSetOf<String>()
    envelope.pageIndexes.entries.sortedBy { it.value }.forEach { (pageKey, _) ->
        val pageIds = envelope.blocksById.values
            .filter { it.pageKey == pageKey }
            .map { it.stableId }
        if (pageIds.all(completeIds::contains)) {
            completed += pageKey
        } else {
            return completed
        }
    }
    return completed
}

private fun protocolFailure(
    envelope: FrozenAiEnvelope,
    expectedCount: Int,
    receivedCount: Int,
    missingCount: Int,
    duplicateCount: Int,
    conflictCount: Int,
    unknownCount: Int,
    malformedCount: Int,
    rejectedCount: Int,
    retryability: ProviderFailureRetryability,
    attempt: Int,
): ProviderFailure = ProviderFailure(
    kind = ProviderFailureKind.PROTOCOL,
    retryability = retryability,
    safeSummary = "Contextual response protocol failure (expected=$expectedCount " +
        "received=$receivedCount missing=$missingCount duplicate=$duplicateCount " +
        "conflict=$conflictCount unknown=$unknownCount malformed=$malformedCount rejected=$rejectedCount)",
    requestId = envelope.identity,
    attempt = attempt,
)

private fun budgetFailure(budget: RequestRetryBudget): ProviderFailure = ProviderFailure(
    kind = ProviderFailureKind.NETWORK,
    retryability = ProviderFailureRetryability.PAUSE,
    safeSummary = "Semantic envelope request budget exhausted",
    attempt = budget.attemptsUsed,
)

private fun classifyFailure(
    error: Throwable,
    clock: ProviderRequestClock,
    envelopeId: String,
): ProviderFailure = classifyProviderFailure(
    error = error,
    backend = "ai_semantic",
    nowEpochMs = clock.nowEpochMs(),
).copy(requestId = envelopeId)

private fun logRequest(
    safeLabel: String,
    envelope: FrozenAiEnvelope,
    outgoing: OutgoingRequest,
    semanticAttempt: Int,
    retryDepth: Int,
    requestKind: RequestKind,
) {
    BatchTranslationDiagnostics.envelopeLifecycle(
        phase = BatchEnvelopeLifecycle.PROVIDER_REQUEST,
        pageKeys = envelope.pageOrder,
        attempt = semanticAttempt,
        expectedItemCount = outgoing.stableIds.size,
        receivedItemCount = null,
        reason = null,
    )
    logcat(tag = "TranslationBatchRetry", priority = LogPriority.INFO) {
        "event=stage2_request label=$safeLabel envelope=${envelope.identity} " +
            "kind=${requestKind.name.lowercase()} pass=$retryDepth " +
            "pages=${outgoing.chunk.pages.size} blocks=${outgoing.stableIds.size} " +
            "promptTokens=${outgoing.chunk.estimatedPromptTokens} " +
            "maxOutput=${outgoing.chunk.maxOutputTokens}"
    }
}

private fun normalizedPageIndexes(chunk: TranslationContextChunk): LinkedHashMap<String, Int> {
    val used = mutableSetOf<Int>()
    var nextFallback = chunk.pageIndexes.values.maxOrNull()
        ?.let { if (it == Int.MAX_VALUE) 0 else it + 1 }
        ?: 0
    return linkedMapOf<String, Int>().apply {
        chunk.pages.keys.forEach { pageKey ->
            val supplied = chunk.pageIndexes[pageKey]
            val index = if (supplied != null && supplied >= 0 && used.add(supplied)) {
                supplied
            } else {
                while (nextFallback in used) {
                    nextFallback = if (nextFallback == Int.MAX_VALUE) 0 else nextFallback + 1
                }
                val fallback = nextFallback
                used += fallback
                nextFallback = if (nextFallback == Int.MAX_VALUE) 0 else nextFallback + 1
                fallback
            }
            put(pageKey, index)
        }
    }
}

private fun StringBuilder.appendIdentityField(value: Any?) {
    val text = value?.toString() ?: "<null>"
    append(text.length).append(':').append(text).append('|')
}
