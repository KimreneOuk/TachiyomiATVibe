package eu.kanade.translation.translator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import logcat.LogPriority
import logcat.logcat
import kotlin.coroutines.coroutineContext

/**
 * Runs one contextual request and only plans missing-block retries after a promotable batch has
 * been returned. A strict envelope failure is deliberately typed and propagated before the
 * missing-block planner can observe blank blocks.
 */
internal suspend fun translateAiChunkWithAdaptiveRetry(
    translator: ContextualTextTranslator,
    chunk: TranslationContextChunk,
    requestedOutputTokens: Int,
    profile: TranslationContextChunkPlanner.Profile,
    allowFailureSplit: Boolean,
    label: String,
    retryDepth: Int,
) {
    coroutineContext.ensureActive()
    try {
        logcat(tag = "TranslationBatchRetry", priority = LogPriority.INFO) {
            "TachiyomiAT batch stage2-AI request $label pass=$retryDepth: " +
                "pages=${chunk.pages.size} blocks=${chunk.blockCount} " +
                "promptTokens=${chunk.estimatedPromptTokens} maxOutput=${chunk.maxOutputTokens}"
        }
        val batch = translator.translateContextual(chunk)
        batch.structuralFailure?.let { failure ->
            throw ContextualStructuralFailureException(failure)
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
                    "TachiyomiAT batch stage2-AI terminal chunk failure $label pass=$retryDepth: " +
                        "pages=${chunk.pages.keys} blocks=${chunk.blockCount}"
                }
                return
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
                "TachiyomiAT batch stage2-AI failed and produced no retry chunks $label pass=$retryDepth"
            }
            return
        }
        logcat(tag = "TranslationBatchRetry", priority = LogPriority.WARN) {
            "TachiyomiAT batch stage2-AI splitting failed chunk $label pass=$retryDepth: " +
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
            )
        }
        return
    }

    val missingPages = AiTranslationRetryPlanner.untranslatedPages(chunk)
    val missingBlocks = missingPages.values.sumOf { it.blocks.size }
    if (missingBlocks == 0) return

    if (chunk.blockCount <= 1) {
        logcat(tag = "TranslationBatchRetry", priority = LogPriority.WARN) {
            "TachiyomiAT batch stage2-AI terminal partial $label pass=$retryDepth: " +
                "remainingBlocks=$missingBlocks"
        }
        return
    }

    val missingPlan = AiTranslationRetryPlanner.planMissingRetry(
        chunk = chunk,
        requestedOutputTokens = requestedOutputTokens,
        profile = profile,
    )
    if (missingPlan.chunks.isEmpty()) return
    logcat(tag = "TranslationBatchRetry", priority = LogPriority.WARN) {
        "TachiyomiAT batch stage2-AI partial $label pass=$retryDepth: " +
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
        )
    }
}
