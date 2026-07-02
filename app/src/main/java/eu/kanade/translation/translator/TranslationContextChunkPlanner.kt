package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation

/**
 * Conservative AI-request chunking for pre-translation.
 *
 * The setting named "max output tokens" is treated as an upper bound only. A
 * chunk's effective output cap is reduced so estimated input + context + output
 * remains below [MAX_CONTEXT_TOKENS].
 *
 * The greedy flush, token estimation, and chunk construction live in
 * [StreamingChunkPlanner]; [plan] delegates to it so the batch and streaming
 * entry points can never diverge.
 */
object TranslationContextChunkPlanner {
    const val MAX_CONTEXT_TOKENS = 8_192
    const val SAFETY_MARGIN = 512
    const val MIN_OUTPUT_TOKENS = 256
    // Accounts for the unified system prompt + few-shot examples (TranslationPrompts).
    const val PROMPT_OVERHEAD_TOKENS = 1_100
    // Token budget for the combined rolling-context + chapter glossary injected
    // via TranslationPrompts.contextPrefix. Large enough for a small glossary
    // (~400) plus ~32 recent pairs; drops cleanly when exceeded.
    const val MAX_ROLLING_CONTEXT_TOKENS = 1_500
    // Pair-count cap on the rolling recent-pairs window (token-capped separately).
    const val MAX_ROLLING_PAIRS = 32

    enum class Profile {
        DEFAULT,
        LM_STUDIO,
    }

    data class Constraints(
        val maxContextTokens: Int,
        val safetyMargin: Int,
        val minOutputTokens: Int,
        val promptOverheadTokens: Int,
        val maxRollingContextTokens: Int,
        val maxBlocksPerChunk: Int,
        val maxPagesPerChunk: Int,
    )

    data class Result(
        val chunks: List<TranslationContextChunk>,
        val rejectedPages: Map<String, String>,
    )

    fun plan(
        pages: LinkedHashMap<String, PageTranslation>,
        requestedOutputTokens: Int,
        profile: Profile = Profile.DEFAULT,
        maxBlocksPerChunk: Int? = null,
        maxPagesPerChunk: Int? = null,
    ): Result {
        val planner = StreamingChunkPlanner(requestedOutputTokens, profile, maxBlocksPerChunk, maxPagesPerChunk)
        pages.forEach { (k, v) -> planner.accept(k, v) }
        val flush = planner.flushRemaining()
        val allChunks = planner.emittedChunks() + listOfNotNull(flush.finalChunk)
        return Result(chunks = allChunks, rejectedPages = flush.rejectedPages)
    }

    fun withRollingContext(
        chunk: TranslationContextChunk,
        rollingContext: String,
        requestedOutputTokens: Int,
        profile: Profile = Profile.DEFAULT,
        glossary: String = "",
    ): TranslationContextChunk {
        val constraints = constraintsFor(profile)
        val trimmedRolling = rollingContext.trim()
        val trimmedGlossary = glossary.trim()
        if (trimmedRolling.isEmpty() && trimmedGlossary.isEmpty()) {
            return chunk.withOutputCap(requestedOutputTokens, constraints)
        }
        val maxContextPrompt = constraints.maxContextTokens - constraints.safetyMargin - constraints.minOutputTokens
        val rollingTokens = estimateTokens(trimmedRolling)
        val glossaryTokens = estimateTokens(trimmedGlossary)

        fun overCap(roll: Int, glos: Int): Boolean {
            val context = roll + glos
            val candidate = chunk.estimatedPromptTokens + context
            return context > constraints.maxRollingContextTokens || candidate > maxContextPrompt
        }

        var useGlossary = trimmedGlossary
        var useRolling = trimmedRolling
        if (overCap(rollingTokens, glossaryTokens)) {
            // Drop the glossary first (recent pairs carry more speaker/pronoun
            // continuity); if the pairs alone still blow the cap, drop them too
            // (the original drop-all behaviour, so a runaway context can't shrink
            // the output cap toward the min floor).
            useGlossary = ""
            if (overCap(rollingTokens, 0)) {
                return chunk.copy(rollingContext = "", glossary = "")
                    .withOutputCap(requestedOutputTokens, constraints)
            }
        }
        val contextTokens = estimateTokens(useRolling) + estimateTokens(useGlossary)
        return chunk.copy(
            rollingContext = useRolling,
            glossary = useGlossary,
            estimatedPromptTokens = chunk.estimatedPromptTokens + contextTokens,
        ).withOutputCap(requestedOutputTokens, constraints)
    }

    fun updateRollingContext(previous: String, translatedPages: Map<String, PageTranslation>): String {
        val pairs = translatedPages.values
            .flatMap { page ->
                page.blocks.mapNotNull { block ->
                    val translation = block.translation.trim()
                    val text = block.text.trim()
                    if (text.isBlank() || translation.isBlank() || translation == text) {
                        null
                    } else {
                        // TachiyomiAT: tag the source side for in-bubble blocks so the
                        // rolling context carries speaker (conversation) continuity
                        // across chunk boundaries, not just the translation text.
                        val tag = if (block.parentWidth > 0f && block.parentHeight > 0f) {
                            "[${TranslationPrompts.SPEECH_TAG}] "
                        } else {
                            ""
                        }
                        "$tag$text => $translation"
                    }
                }
            }
        if (pairs.isEmpty()) return previous
        val lines = (previous.lineSequence().filter { it.isNotBlank() } + pairs.asSequence())
            .toList()
            .takeLast(MAX_ROLLING_PAIRS)
        return lines.joinToString("\n")
    }

    private val encodingRegistry = com.knuddels.jtokkit.Encodings.newDefaultEncodingRegistry()
    private val encoding = encodingRegistry.getEncoding(com.knuddels.jtokkit.api.EncodingType.CL100K_BASE)

    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 1
        return encoding.countTokens(text).coerceAtLeast(1)
    }

    private fun TranslationContextChunk.withOutputCap(
        requestedOutputTokens: Int,
        constraints: Constraints,
    ): TranslationContextChunk =
        copy(maxOutputTokens = StreamingChunkPlanner.effectiveOutputCap(estimatedPromptTokens, requestedOutputTokens, constraints))

    fun constraintsFor(profile: Profile): Constraints = when (profile) {
        Profile.DEFAULT -> Constraints(
            maxContextTokens = MAX_CONTEXT_TOKENS,
            safetyMargin = SAFETY_MARGIN,
            minOutputTokens = MIN_OUTPUT_TOKENS,
            promptOverheadTokens = PROMPT_OVERHEAD_TOKENS,
            maxRollingContextTokens = MAX_ROLLING_CONTEXT_TOKENS,
            maxBlocksPerChunk = Int.MAX_VALUE,
            maxPagesPerChunk = Int.MAX_VALUE,
        )
        Profile.LM_STUDIO -> Constraints(
            maxContextTokens = 10_000,
            safetyMargin = SAFETY_MARGIN,
            minOutputTokens = MIN_OUTPUT_TOKENS,
            promptOverheadTokens = PROMPT_OVERHEAD_TOKENS,
            maxRollingContextTokens = 768,
            maxBlocksPerChunk = 32,
            maxPagesPerChunk = 4,
        )
    }

    private fun Char.isCjk(): Boolean {
        val block = Character.UnicodeBlock.of(this)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
            block == Character.UnicodeBlock.HANGUL_JAMO ||
            block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
    }
}

data class TranslationContextChunk(
    val pages: LinkedHashMap<String, PageTranslation>,
    val blockCount: Int,
    val rollingContext: String,
    val glossary: String = "",
    val estimatedPromptTokens: Int,
    val maxOutputTokens: Int,
)

interface ContextualTextTranslator : TextTranslator {
    suspend fun translateContextual(chunk: TranslationContextChunk)
    
    /** Prompts the underlying model directly (used for glossary generation and summarization). */
    suspend fun promptText(prompt: String): String
}
