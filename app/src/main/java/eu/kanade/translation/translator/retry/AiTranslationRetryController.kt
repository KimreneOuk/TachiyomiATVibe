package eu.kanade.translation.translator.retry
import eu.kanade.translation.translator.contextual.TranslationResponseFaithfulness
import eu.kanade.translation.translator.contextual.TranslationContextChunkPlanner
import eu.kanade.translation.translator.contextual.TranslationContextChunk
import eu.kanade.translation.translator.contextual.TargetLocation
import eu.kanade.translation.translator.SystemProviderRequestClock
import eu.kanade.translation.translator.contextual.StableBlockIds
import eu.kanade.translation.translator.ProviderRequestPausedException
import eu.kanade.translation.translator.ProviderRequestClock
import eu.kanade.translation.translator.ProviderFailureRetryability
import eu.kanade.translation.translator.ProviderFailureKind
import eu.kanade.translation.translator.ProviderFailure
import eu.kanade.translation.translator.providers.OcrArtifactSanitizer
import eu.kanade.translation.translator.contextual.ContextualTranslationResult
import eu.kanade.translation.translator.contextual.ContextualTranslationBatch
import eu.kanade.translation.translator.contextual.ContextualTextTranslator
import eu.kanade.translation.translator.contextual.ContextualResponseParser
import eu.kanade.translation.translator.contextual.ContextualRequestBuilder
import eu.kanade.translation.translator.contextual.BatchTranslationProtocol

import eu.kanade.translation.pipeline.batch.BatchDiagnosticReason
import eu.kanade.translation.pipeline.batch.BatchEnvelopeLifecycle
import eu.kanade.translation.pipeline.batch.BatchTranslationDiagnostics
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.util.ShortHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import logcat.LogPriority
import logcat.logcat
import kotlin.coroutines.coroutineContext

/**
 * Semantic retry limits for one contextual envelope.
 *
 * [maxTotalAttempts] is used only when the caller does not provide an
 * explicit [RequestRetryBudget]. The budget itself is the hard ceiling for
 * all transport attempts across the initial request, whole-envelope reissue,
 * targeted requests, and provider fallbacks.
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
 * never mutates the [TranslationContextChunk] supplied by the caller, and it
 * only produces a rolling-context delta for [Complete]. [Paused] retains a
 * non-authoritative partial candidate for a later pipeline phase; it must not
 * be fed into ordered rolling context.
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

    data class Complete(
        override val acceptedBlockIds: Set<String>,
        override val completedPageKeys: Set<String>,
        override val blockTranslations: Map<String, String>,
        val rollingContextDelta: String,
        override val envelopeId: String,
        override val attemptsUsed: Int,
        override val wholeEnvelopeRetries: Int,
        override val missingBlockRequests: Int,
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
    val identity: String,
) {
    fun chunkFor(stableIds: Collection<String>): TranslationContextChunk {
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
        )
    }
}

private data class OutgoingRequest(
    val chunk: TranslationContextChunk,
    val request: ContextualRequestBuilder.Request,
    val requestIdToStableId: Map<String, String>,
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
    val protocolIssue: Boolean,
    val refusal: ProviderFailure?,
)

/** Detached accepted-results accumulator. Every merge returns a new value. */
private data class AiTranslationAccumulator(
    val translations: LinkedHashMap<String, String> = linkedMapOf(),
    val duplicateCount: Int = 0,
    val conflictCount: Int = 0,
    val unknownCount: Int = 0,
    val malformedCount: Int = 0,
    val rejectedCount: Int = 0,
    val protocolIssue: Boolean = false,
) {
    fun merge(
        response: ResponseAnalysis,
        allowExistingFromWholeRetry: Boolean,
    ): AiTranslationAccumulator {
        val merged = LinkedHashMap(translations)
        var duplicates = duplicateCount + response.duplicateIds.size
        var conflicts = conflictCount + response.conflictIds.size
        // Per-merge state: the accumulated set is tainted only when THIS
        // response introduces a violation. An earlier attempt's violation
        // must not veto a later fully recovered set — whole/missing repair
        // exists precisely to recover from it. Conflicting repeats below
        // still poison the current merge, and a same-response violation
        // raises response.protocolIssue.
        var issue = response.protocolIssue
        response.accepted.forEach { (stableId, translation) ->
            val existing = merged[stableId]
            if (existing == null) {
                merged[stableId] = translation
            } else if (!allowExistingFromWholeRetry || existing != translation) {
                // A whole-envelope reissue is allowed to repeat an already
                // accepted value. It is never allowed to overwrite a value or
                // silently accept a conflicting duplicate.
                duplicates++
                if (existing != translation) conflicts++
                issue = true
            }
        }
        return copy(
            translations = merged,
            duplicateCount = duplicates,
            conflictCount = conflicts,
            unknownCount = unknownCount + response.unknownCount,
            malformedCount = malformedCount + response.malformedCount,
            rejectedCount = rejectedCount + response.rejectedCount,
            protocolIssue = issue,
        )
    }
}

/**
 * Runs one contextual request and bounded semantic retries.
 *
 * [retryBudget] is shared by every provider transport attempt in this
 * envelope. Concrete translators inherit it through [withRequestRetryBudget],
 * and the Phase 1 governor consumes it only after admission. A translator
 * double that does not expose a governor boundary is charged once per retry
 * attempt, keeping tests and legacy adapters bounded without charging real
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
): AiChunkOutcome {
    coroutineContext.ensureActive()
    // The arguments are retained in the direct-call API for compatibility;
    // the caller has already applied the output-cap/profile to [chunk].

    val budget = retryBudget ?: RequestRetryBudget(retryPolicy.maxTotalAttempts)
    val envelope = freezeEnvelope(chunk)
    if (envelope.requestableIds.isEmpty()) {
        return completeOutcome(
            envelope = envelope,
            accumulator = AiTranslationAccumulator(),
            budget = budget,
            wholeRetries = 0,
            missingRequests = 0,
        )
    }

    val safeLabel = ShortHash.hash(label).ifEmpty { "none" }
    var accumulator = AiTranslationAccumulator()
    var wholeRetries = 0
    var missingRequests = 0
    var requestKind = RequestKind.WHOLE
    var requestedIds = envelope.requestableIds

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
                buildOutgoingRequest(envelope, requestedIds, translator)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = protocolFailure(
                    envelope = envelope,
                    expectedCount = requestedIds.size,
                    receivedCount = 0,
                    missingCount = requestedIds.size,
                    duplicateCount = 0,
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
                requestStructured(
                    translator = translator,
                    outgoing = outgoing,
                    budget = budget,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = classifyFailure(e, clock, envelope.identity)
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
                val shouldRetry = !budget.isExhausted &&
                    when (requestKind) {
                        RequestKind.WHOLE -> wholeRetries < retryPolicy.maxWholeEnvelopeRetries
                        RequestKind.MISSING -> missingRequests < retryPolicy.maxMissingBlockRequests
                    }
                if (shouldRetry) {
                    when (requestKind) {
                        RequestKind.WHOLE -> wholeRetries++
                        RequestKind.MISSING -> missingRequests++
                    }
                    BatchTranslationDiagnostics.envelopeLifecycle(
                        phase = BatchEnvelopeLifecycle.RETRY,
                        pageKeys = envelope.pageOrder,
                        attempt = budget.attemptsUsed,
                        expectedItemCount = requestedIds.size,
                        receivedItemCount = null,
                        reason = BatchDiagnosticReason.TRANSIENT_FAILURE,
                    )
                    logcat(tag = "TranslationBatchRetry", priority = LogPriority.WARN) {
                        "event=semantic_retry label=$safeLabel envelope=${envelope.identity} " +
                            "kind=${requestKind.name.lowercase()} attempts=${budget.attemptsUsed} " +
                            "wholeRetries=$wholeRetries missingRequests=$missingRequests"
                    }
                    // Keep the exact same stable IDs and frozen context on
                    // a whole-envelope reissue or missing-only retry.
                    continue
                }
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
            BatchTranslationDiagnostics.envelopeLifecycle(
                phase = BatchEnvelopeLifecycle.PARSED,
                pageKeys = envelope.pageOrder,
                attempt = budget.attemptsUsed,
                expectedItemCount = outgoing.stableIds.size,
                receivedItemCount = analysis.accepted.size,
                reason = if (analysis.missingIds.isEmpty() && !analysis.protocolIssue) {
                    BatchDiagnosticReason.SUCCESS
                } else {
                    BatchDiagnosticReason.STAGE_FAILURE
                },
            )

            analysis.refusal?.let { failure ->
                val merged = accumulator.merge(analysis, requestKind == RequestKind.WHOLE)
                return terminalOutcome(
                    envelope = envelope,
                    accumulator = merged,
                    missingIds = remainingIds(envelope, merged),
                    failure = failure,
                    budget = budget,
                    wholeRetries = wholeRetries,
                    missingRequests = missingRequests,
                )
            }

            accumulator = accumulator.merge(
                response = analysis,
                allowExistingFromWholeRetry = requestKind == RequestKind.WHOLE,
            )
            val missingIds = remainingIds(envelope, accumulator)

            if (missingIds.isEmpty() && !accumulator.protocolIssue) {
                return completeOutcome(
                    envelope = envelope,
                    accumulator = accumulator,
                    budget = budget,
                    wholeRetries = wholeRetries,
                    missingRequests = missingRequests,
                )
            }

            // A malformed/unknown/duplicate whole response gets one
            // bounded whole-envelope recovery before targeted work. This
            // keeps the first accepted value fenced while giving a model a
            // chance to repair its envelope framing.
            if (accumulator.protocolIssue &&
                requestKind == RequestKind.WHOLE &&
                wholeRetries < retryPolicy.maxWholeEnvelopeRetries &&
                !budget.isExhausted
            ) {
                wholeRetries++
                requestedIds = envelope.requestableIds
                requestKind = RequestKind.WHOLE
                BatchTranslationDiagnostics.envelopeLifecycle(
                    phase = BatchEnvelopeLifecycle.RETRY,
                    pageKeys = envelope.pageOrder,
                    attempt = budget.attemptsUsed,
                    expectedItemCount = requestedIds.size,
                    receivedItemCount = analysis.accepted.size,
                    reason = BatchDiagnosticReason.STAGE_FAILURE,
                )
                continue
            }

            if (missingIds.isNotEmpty() &&
                missingRequests < retryPolicy.maxMissingBlockRequests &&
                !budget.isExhausted
            ) {
                missingRequests++
                requestedIds = missingIds.toList()
                requestKind = RequestKind.MISSING
                continue
            }

            if (accumulator.protocolIssue && missingIds.isEmpty()) {
                val failure = protocolFailure(
                    envelope = envelope,
                    expectedCount = envelope.requestableIds.size,
                    receivedCount = accumulator.translations.size,
                    missingCount = 0,
                    duplicateCount = accumulator.duplicateCount,
                    unknownCount = accumulator.unknownCount,
                    malformedCount = accumulator.malformedCount,
                    rejectedCount = accumulator.rejectedCount,
                    retryability = ProviderFailureRetryability.TERMINAL,
                    attempt = budget.attemptsUsed,
                )
                return terminalOutcome(
                    envelope = envelope,
                    accumulator = accumulator,
                    missingIds = missingIds,
                    failure = failure,
                    budget = budget,
                    wholeRetries = wholeRetries,
                    missingRequests = missingRequests,
                )
            }

            val failure = protocolFailure(
                envelope = envelope,
                expectedCount = envelope.requestableIds.size,
                receivedCount = accumulator.translations.size,
                missingCount = missingIds.size,
                duplicateCount = accumulator.duplicateCount,
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

    return try {
        withRequestRetryBudget(budget) { runEnvelope() }
    } catch (e: CancellationException) {
        throw e
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

private fun freezeEnvelope(chunk: TranslationContextChunk): FrozenAiEnvelope {
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
            val hasUsableExisting = block.translation.isNotBlank() &&
                block.translation.trim() != block.text.trim()
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
        ),
        pages = pages,
        pageOrder = pages.keys.toList(),
        blocksById = blocksById,
        pageIndexes = pageIndexes,
        requestableIds = requestable,
        identity = ShortHash.hash(identityInput).ifEmpty { "none" },
    )
}

private fun buildOutgoingRequest(
    envelope: FrozenAiEnvelope,
    stableIds: Collection<String>,
    translator: ContextualTextTranslator,
): OutgoingRequest {
    val chunk = envelope.chunkFor(stableIds)
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
    return OutgoingRequest(
        chunk = chunk,
        request = request,
        requestIdToStableId = requestIdToStableId,
        locationToStableId = locationToStableId,
        stableIds = stableIds.toSet(),
    )
}

private fun analyzeResponse(
    batch: ContextualTranslationBatch,
    outgoing: OutgoingRequest,
    envelope: FrozenAiEnvelope,
): ResponseAnalysis {
    val accepted = linkedMapOf<String, String>()
    val returned = linkedSetOf<String>()
    val duplicates = linkedSetOf<String>()
    val conflicts = linkedSetOf<String>()
    var unknownCount = 0
    var rejectedCount = 0
    var refusal: ProviderFailure? = null

    batch.results.forEach { result ->
        val stableId = resolveStableId(result, outgoing)
        if (stableId == null || stableId !in outgoing.stableIds || result.targetKey == null) {
            unknownCount++
            return@forEach
        }
        if (!returned.add(stableId)) {
            duplicates += stableId
            val sanitized = OcrArtifactSanitizer.sanitize(result.text)
            accepted[stableId]?.let { first ->
                if (first != sanitized) conflicts += stableId
            }
            return@forEach
        }

        val frozen = envelope.blocksById[stableId]
        if (frozen == null || frozen.userEditedAt != null) {
            // User edits are a hard write fence. A response for a fenced block
            // is ignored rather than allowed to turn into a replacement.
            rejectedCount++
            return@forEach
        }
        val sanitized = OcrArtifactSanitizer.sanitize(result.text)
        if (result.status != ContextualTranslationResult.Status.TRANSLATED || sanitized.isBlank()) {
            rejectedCount++
            return@forEach
        }
        if (sanitized.trim() == frozen.sourceText.trim()) {
            // Echoed source is not a translation; leave it in the missing set
            // so a targeted request can repair it.
            rejectedCount++
            return@forEach
        }
        if (TranslationResponseFaithfulness.isStructuralRefusal(sanitized)) {
            refusal = ProviderFailure(
                kind = ProviderFailureKind.REFUSAL,
                retryability = ProviderFailureRetryability.TERMINAL,
                safeSummary = "Provider refused one or more contextual translations",
                requestId = envelope.identity,
            )
            return@forEach
        }
        accepted[stableId] = sanitized
    }

    val missingValidationErrors = batch.validationErrors.count { error ->
        error.startsWith("Missing translation for '")
    }
    val malformedCount = (batch.validationErrors.size - missingValidationErrors).coerceAtLeast(0)
    val protocolVersionIssue = batch.strictValidation &&
        batch.protocolVersion != BatchTranslationProtocol.VERSION
    val protocolIssue = protocolVersionIssue ||
        duplicates.isNotEmpty() ||
        conflicts.isNotEmpty() ||
        unknownCount > 0 ||
        malformedCount > 0 ||
        batch.results.any { result ->
            result.status == ContextualTranslationResult.Status.REJECTED &&
                result.id.isNotBlank() &&
                resolveStableId(result, outgoing) != null &&
                result.text.isNotBlank() &&
                result.id !in batch.duplicateIds
        }
    val missing = outgoing.stableIds - accepted.keys
    return ResponseAnalysis(
        accepted = accepted,
        missingIds = missing,
        duplicateIds = duplicates,
        conflictIds = conflicts,
        unknownCount = unknownCount,
        malformedCount = malformedCount,
        rejectedCount = rejectedCount,
        protocolIssue = protocolIssue,
        refusal = refusal,
    )
}

private fun resolveStableId(
    result: ContextualTranslationResult,
    outgoing: OutgoingRequest,
): String? {
    val normalized = ContextualResponseParser.normalizeBatchId(result.id)
    return outgoing.requestIdToStableId[normalized]
        ?: outgoing.requestIdToStableId[result.id]
        ?: result.targetKey?.let { target ->
            outgoing.requestIdToStableId[BatchTranslationProtocol.blockId(target.pageIndex, target.blockIndex)]
        }
        ?: outgoing.locationToStableId.entries.firstOrNull { (location, _) ->
            result.id == location.toString()
        }?.value
}

private fun resolveStableId(
    rawId: String,
    outgoing: OutgoingRequest,
): String? = outgoing.requestIdToStableId[ContextualResponseParser.normalizeBatchId(rawId)]
    ?: outgoing.requestIdToStableId[rawId]

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
    val contextPages = linkedMapOf<String, PageTranslation>()
    completedPages.forEach { pageKey ->
        val source = envelope.pages[pageKey] ?: return@forEach
        val detached = source.detachedCopy()
        detached.blocks.forEach { block ->
            val id = block.blockId?.let(ContextualResponseParser::normalizeBatchId)
            id?.let { stableId ->
                accumulator.translations[stableId]?.let { block.translation = it }
            }
        }
        contextPages[pageKey] = detached
    }
    val rollingDelta = TranslationContextChunkPlanner.updateRollingContext("", contextPages)
    return AiChunkOutcome.Complete(
        acceptedBlockIds = accumulator.translations.keys.toSet(),
        completedPageKeys = completedPages,
        blockTranslations = accumulator.translations.toMap(),
        rollingContextDelta = rollingDelta,
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
                (
                    block.originalTranslation.isNotBlank() &&
                        block.originalTranslation.trim() != block.sourceText.trim()
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
        "unknown=$unknownCount malformed=$malformedCount rejected=$rejectedCount)",
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

/**
 * Explicit compatibility adapter for the pre-Phase-2 live pipeline.
 *
 * The controller is side-effect-free; this helper is the only Phase-2 bridge
 * that applies accepted detached results to live page objects. It preserves
 * user edits and assigns IDs on the live snapshot only when the caller has not
 * already assigned them. Phase 3 owns whether a provisional outcome may be
 * durably published.
 */
internal fun applyAiChunkOutcomeToPages(
    outcome: AiChunkOutcome,
    pages: Map<String, PageTranslation>,
    pageIndexes: Map<String, Int> = emptyMap(),
    pageKeys: Set<String>? = null,
) {
    if (outcome.blockTranslations.isEmpty()) return
    val indexes = normalizedAdapterPageIndexes(pages.keys, pageIndexes)
    val targetPages = if (pageKeys == null) pages else pages.filterKeys { it in pageKeys }
    targetPages.forEach { (pageKey, page) ->
        StableBlockIds.assign(page, indexes.getValue(pageKey))
    }
    val blocksById = targetPages.values
        .asSequence()
        .flatMap { it.blocks.asSequence() }
        .mapNotNull { block ->
            block.blockId
                ?.let(ContextualResponseParser::normalizeBatchId)
                ?.let { it to block }
        }
        .toMap()
    outcome.blockTranslations.forEach { (rawId, translation) ->
        val block = blocksById[ContextualResponseParser.normalizeBatchId(rawId)] ?: return@forEach
        if (block.userEditedAt == null) block.translation = translation
    }
}

private fun normalizedAdapterPageIndexes(
    pageKeys: Set<String>,
    supplied: Map<String, Int>,
): LinkedHashMap<String, Int> {
    val used = mutableSetOf<Int>()
    var next = supplied.values.maxOrNull()?.let { if (it == Int.MAX_VALUE) 0 else it + 1 } ?: 0
    return linkedMapOf<String, Int>().apply {
        pageKeys.forEach { pageKey ->
            val candidate = supplied[pageKey]
            val index = if (candidate != null && candidate >= 0 && used.add(candidate)) {
                candidate
            } else {
                while (next in used) next = if (next == Int.MAX_VALUE) 0 else next + 1
                used += next
                next.also { next = if (next == Int.MAX_VALUE) 0 else next + 1 }
            }
            put(pageKey, index)
        }
    }
}
