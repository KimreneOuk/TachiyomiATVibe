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

    // Pair-count cap on the rolling recent-pairs window.
    const val MAX_ROLLING_PAIRS = 32

    /**
     * TachiyomiAT: cap on past source->target pairs the Analytical-Mode sliding window
     * retains for speaker/voice continuity, so a long chapter's history stays bounded
     * instead of growing unboundedly into the prompt token budget.
     */
    const val MAX_PAST_TRANSLATION_PAIRS = 32

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
                        "$text => $translation"
                    }
                }
            }
        if (pairs.isEmpty()) return previous
        val lines = (previous.lineSequence().filter { it.isNotBlank() } + pairs.asSequence())
            .toList()
            .takeLast(MAX_ROLLING_PAIRS)
        return lines.joinToString("\n")
    }

    /**
     * TachiyomiAT: build a FUTURE-CONTEXT string from OCR'd-but-untranslated pages.
     * Analytical Mode injects this so the model sees upcoming source text when
     * translating the current chunk, preserving narrative/POV continuity. Returns
     * "" when no page contributes non-blank text so callers can skip it cheaply.
     */
    fun buildFutureContext(upcoming: Collection<PageTranslation>): String {
        if (upcoming.isEmpty()) return ""
        val lines = upcoming.flatMap { page ->
            page.blocks.mapNotNull { block ->
                val text = block.text.trim()
                if (text.isBlank()) {
                    null
                } else {
                    "$text"
                }
            }
        }
        if (lines.isEmpty()) return ""
        return lines.joinToString("\n")
    }

    /**
     * TachiyomiAT: build a PAST-TRANSLATIONS string (source => target pairs) from freshly
     * translated pages. The Analytical-Mode sliding window accumulates these across chunks
     * for speaker/voice continuity. Returns "" when nothing is translated yet.
     */
    fun buildPastTranslations(translatedPages: Map<String, PageTranslation>): String {
        val pairs = translatedPages.values.flatMap { page ->
            page.blocks.mapNotNull { block ->
                val translation = block.translation.trim()
                val text = block.text.trim()
                if (text.isBlank() || translation.isBlank() || translation == text) {
                    null
                } else {
                    "$text => $translation"
                }
            }
        }
        if (pairs.isEmpty()) return ""
        return pairs.joinToString("\n")
    }

    /**
     * TachiyomiAT: fold the Analytical-Mode SLIDING CONTEXT (past translated pairs +
     * future source text) into a chunk's rolling-context buffer, since translators read
     * only [TranslationContextChunk.rollingContext] via [TranslationPrompts.contextPrefix].
     *
     * Labelled sections let the model distinguish "already translated" (authoritative
     * continuity) from "coming up next" (forward-looking). Token-safety mirrors
     * [withRollingContext]: if the merged context overflows the rolling budget, the
     * past/future sections are dropped so a runaway context can't squeeze the output
     * cap toward the min floor, and the output cap is recomputed.
     */
    fun withSlidingContext(
        chunk: TranslationContextChunk,
        pastTranslations: String,
        futureContext: String,
        requestedOutputTokens: Int,
        profile: Profile = Profile.DEFAULT,
    ): TranslationContextChunk {
        val constraints = constraintsFor(profile)
        val trimmedPast = pastTranslations.trim()
        val trimmedFuture = futureContext.trim()
        if (trimmedPast.isEmpty() && trimmedFuture.isEmpty()) {
            return chunk.withOutputCap(requestedOutputTokens, constraints)
        }

        val sb = StringBuilder()
        val base = chunk.rollingContext.trim()
        if (base.isNotEmpty()) {
            sb.append(chunk.rollingContext)
        }
        if (trimmedPast.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n---\n")
            sb.append("Past translations (already translated; reuse voice & terms):\n")
            sb.append(trimmedPast)
        }
        if (trimmedFuture.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n---\n")
            sb.append("Upcoming source text (for forward context only; do NOT translate now):\n")
            sb.append(trimmedFuture)
        }
        val merged = sb.toString()

        // Token-safety: if the merged context blows the rolling budget, fall back to
        // the chunk's original rolling context (drop sliding sections) and re-derive
        // the output cap — preserves continuity while keeping the prompt in budget.
        val mergedTokens = estimateTokens(merged)
        val maxContextPrompt = constraints.maxContextTokens - constraints.safetyMargin - constraints.minOutputTokens
        val overBudget = mergedTokens > constraints.maxRollingContextTokens ||
            chunk.estimatedPromptTokens + mergedTokens - estimateTokens(chunk.rollingContext) > maxContextPrompt
        if (overBudget) {
            return chunk.withOutputCap(requestedOutputTokens, constraints)
        }

        val newEstimatedPrompt = chunk.estimatedPromptTokens + mergedTokens - estimateTokens(chunk.rollingContext)
        return chunk.copy(
            rollingContext = merged,
            estimatedPromptTokens = newEstimatedPrompt,
        ).withOutputCap(requestedOutputTokens, constraints)
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
        copy(
            maxOutputTokens = StreamingChunkPlanner.effectiveOutputCap(
                estimatedPromptTokens,
                requestedOutputTokens,
                constraints,
            ),
        )

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

    /**
     * Mandatory structured-result contract. Every contextual provider returns
     * per-id results keyed by request-local IDs without mutating request blocks.
     */
    suspend fun translateContextualStructured(
        chunk: TranslationContextChunk,
    ): ContextualTranslationBatch

    /** Prompts the underlying model directly (used for glossary generation and summarization). */
    suspend fun promptText(prompt: String): String
}
