package eu.kanade.translation.translator

import eu.kanade.translation.batch.BatchDiagnosticReason
import eu.kanade.translation.batch.BatchEnvelopeLifecycle
import eu.kanade.translation.batch.BatchTranslationDiagnostics
import eu.kanade.translation.util.ShortHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import logcat.LogPriority
import logcat.logcat
import kotlin.coroutines.coroutineContext

/**
 * Runs one contextual request and plans targeted retries for whatever the
 * provider got wrong. Page envelopes stay atomic: a provider or structural
 * failure never splits a page into isolated OCR regions. A failed envelope is
 * therefore recorded for the owning pages instead of being retried as smaller
 * block fragments.
 *
 * Returns the merged page-scoped context deltas (pageId -> raw delta text) of
 * every successful response in the retry tree; first writer wins per page so a
 * page's delta always comes from the response that carried its earliest
 * blocks.
 */
internal suspend fun translateAiChunkWithAdaptiveRetry(
    translator: ContextualTextTranslator,
    chunk: TranslationContextChunk,
    requestedOutputTokens: Int,
    profile: TranslationContextChunkPlanner.Profile,
    label: String,
    retryDepth: Int,
): Map<String, String> {
    coroutineContext.ensureActive()
    val mergedDeltas = linkedMapOf<String, String>()
    val safeLabel = ShortHash.hash(label).ifEmpty { "none" }
    val pageSetHash = ShortHash.hash(chunk.pages.keys.joinToString("\u0000"))
    try {
        logcat(tag = "TranslationBatchRetry", priority = LogPriority.INFO) {
            "event=stage2_request label=$safeLabel pass=$retryDepth " +
                "pages=${chunk.pages.size} blocks=${chunk.blockCount} " +
                "promptTokens=${chunk.estimatedPromptTokens} maxOutput=${chunk.maxOutputTokens} " +
                "pageSetHash=$pageSetHash"
        }
        val batch = translator.translateContextual(chunk)
        batch.structuralFailure?.let { failure ->
            BatchTranslationDiagnostics.envelopeLifecycle(
                phase = BatchEnvelopeLifecycle.FAILED,
                pageKeys = chunk.pages.keys,
                attempt = retryDepth,
                expectedItemCount = failure.expectedCount,
                receivedItemCount = failure.receivedCount,
                reason = BatchDiagnosticReason.TERMINAL_FAILURE,
            )
            logcat(tag = "TranslationBatchRetry", priority = LogPriority.WARN) {
                "event=stage2_structural_failure label=$safeLabel pass=$retryDepth ${failure.safeSummary()}"
            }
            return mergedDeltas
        }
        BatchTranslationDiagnostics.envelopeLifecycle(
            phase = BatchEnvelopeLifecycle.PARSED,
            pageKeys = chunk.pages.keys,
            attempt = retryDepth,
            expectedItemCount = batch.accounting.requested,
            receivedItemCount = batch.accounting.translated,
            reason = if (batch.accounting.missing == 0) BatchDiagnosticReason.SUCCESS else BatchDiagnosticReason.STAGE_FAILURE,
        )
        batch.contextDeltas.forEach { (pageId, delta) ->
            if (pageId !in mergedDeltas) mergedDeltas[pageId] = delta
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        logcat(tag = "TranslationBatchRetry", priority = LogPriority.WARN) {
            "event=stage2_failure reason=page_atomic_terminal label=$safeLabel pass=$retryDepth " +
                "pages=${chunk.pages.size} blocks=${chunk.blockCount} pageSetHash=$pageSetHash"
        }
        BatchTranslationDiagnostics.envelopeLifecycle(
            phase = BatchEnvelopeLifecycle.FAILED,
            pageKeys = chunk.pages.keys,
            attempt = retryDepth,
            expectedItemCount = chunk.blockCount,
            reason = BatchDiagnosticReason.TERMINAL_FAILURE,
        )
        return mergedDeltas
    }

    val missingPages = AiTranslationRetryPlanner.untranslatedPages(chunk)
    val missingBlocks = missingPages.values.sumOf { it.blocks.size }
    if (missingBlocks == 0) {
        BatchTranslationDiagnostics.envelopeLifecycle(
            phase = BatchEnvelopeLifecycle.SUCCEEDED,
            pageKeys = chunk.pages.keys,
            attempt = retryDepth,
            expectedItemCount = chunk.blockCount,
            receivedItemCount = chunk.blockCount,
            reason = BatchDiagnosticReason.SUCCESS,
        )
        return mergedDeltas
    }

    logcat(tag = "TranslationBatchRetry", priority = LogPriority.WARN) {
        "event=stage2_retry_plan reason=page_atomic_partial label=$safeLabel pass=$retryDepth " +
            "remainingBlocks=$missingBlocks"
    }
    return mergedDeltas
}
