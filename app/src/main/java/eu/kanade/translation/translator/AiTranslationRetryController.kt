package eu.kanade.translation.translator

import eu.kanade.translation.util.ShortHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import logcat.LogPriority
import logcat.logcat
import kotlin.coroutines.coroutineContext

/**
 * Runs one contextual request and only plans missing-block retries after a promotable batch has
 * been returned. A strict envelope failure is deliberately typed and propagated before the
 * missing-block planner can observe blank blocks.
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
    allowFailureSplit: Boolean,
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
            throw ContextualStructuralFailureException(failure)
        }
        batch.contextDeltas.forEach { (pageId, delta) ->
            if (pageId !in mergedDeltas) mergedDeltas[pageId] = delta
        }
    } catch (e: ContextualStructuralFailureException) {
        // This is a protocol failure, not missing content. Let the live pipeline mark the
        // affected pages failed; never recurse into missing-block splitting.
        throw e
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        if (!allowFailureSplit || chunk.blockCount <= 1) {
            if (allowFailureSplit || retryDepth > 0) {
                logcat(tag = "TranslationBatchRetry", priority = LogPriority.WARN) {
                    "event=stage2_failure reason=terminal label=$safeLabel pass=$retryDepth " +
                        "pages=${chunk.pages.size} blocks=${chunk.blockCount} pageSetHash=$pageSetHash"
                }
                return mergedDeltas
            }
            throw e
        }
        val split = AiTranslationRetryPlanner.planFailureSplit(
            chunk = chunk,
            requestedOutputTokens = requestedOutputTokens,
            profile = profile,
        )
        if (split.chunks.isEmpty()) {
            logcat(tag = "TranslationBatchRetry", priority = LogPriority.WARN) {
                "event=stage2_retry_plan reason=no_retry_chunks label=$safeLabel pass=$retryDepth"
            }
            return mergedDeltas
        }
        logcat(tag = "TranslationBatchRetry", priority = LogPriority.WARN) {
            "event=stage2_retry_plan reason=failure_split label=$safeLabel pass=$retryDepth " +
                "blocks=${chunk.blockCount} retryChunks=${split.chunks.size}"
        }
        split.chunks.forEachIndexed { index, retryChunk ->
            translateAiChunkWithAdaptiveRetry(
                translator = translator,
                chunk = retryChunk,
                requestedOutputTokens = requestedOutputTokens,
                profile = profile,
                allowFailureSplit = allowFailureSplit,
                label = "$label.${index + 1}",
                retryDepth = retryDepth + 1,
            ).forEach { (pageId, delta) ->
                if (pageId !in mergedDeltas) mergedDeltas[pageId] = delta
            }
        }
        return mergedDeltas
    }

    val missingPages = AiTranslationRetryPlanner.untranslatedPages(chunk)
    val missingBlocks = missingPages.values.sumOf { it.blocks.size }
    if (missingBlocks == 0) return mergedDeltas

    if (chunk.blockCount <= 1) {
        logcat(tag = "TranslationBatchRetry", priority = LogPriority.WARN) {
            "event=stage2_retry_plan reason=terminal_partial label=$safeLabel pass=$retryDepth " +
                "remainingBlocks=$missingBlocks"
        }
        return mergedDeltas
    }

    val missingPlan = AiTranslationRetryPlanner.planMissingRetry(
        chunk = chunk,
        requestedOutputTokens = requestedOutputTokens,
        profile = profile,
    )
    if (missingPlan.chunks.isEmpty()) return mergedDeltas
    logcat(tag = "TranslationBatchRetry", priority = LogPriority.WARN) {
        "event=stage2_retry_plan reason=missing_blocks label=$safeLabel pass=$retryDepth " +
            "remainingBlocks=$missingBlocks retryChunks=${missingPlan.chunks.size}"
    }
    missingPlan.chunks.forEachIndexed { index, retryChunk ->
        translateAiChunkWithAdaptiveRetry(
            translator = translator,
            chunk = retryChunk,
            requestedOutputTokens = requestedOutputTokens,
            profile = profile,
            allowFailureSplit = allowFailureSplit,
            label = "$label.missing${index + 1}",
            retryDepth = retryDepth + 1,
        ).forEach { (pageId, delta) ->
            if (pageId !in mergedDeltas) mergedDeltas[pageId] = delta
        }
    }
    return mergedDeltas
}
