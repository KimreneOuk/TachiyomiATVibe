package eu.kanade.translation.engines.translator.contextual
import eu.kanade.translation.engines.translator.TextTranslator
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageTranslationView

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
    // This is a provider-safety ceiling, not a page/block batching limit.
    // Complete pages are greedily packed until their calculated prompt and
    // response reserve reaches this ceiling.
    const val MAX_CONTEXT_TOKENS = 8_192
    const val SAFETY_MARGIN = 512
    const val MIN_OUTPUT_TOKENS = 256

    // Accounts for the unified system prompt + few-shot examples (TranslationPrompts),
    // including batch semantic-role, mature-content, and delta guidance.
    const val PROMPT_OVERHEAD_TOKENS = 1_400

    // Deterministic reserve for the v1 response envelope. It covers the response header/footer,
    // page and delta delimiters, canonical IDs, separators, and bounded provider whitespace.
    const val BATCH_RESPONSE_FIXED_OVERHEAD_TOKENS = 32
    const val BATCH_RESPONSE_PAGE_OVERHEAD_TOKENS = 24
    const val BATCH_RESPONSE_BLOCK_OVERHEAD_TOKENS = 8
    const val BATCH_RESPONSE_WHITESPACE_OVERHEAD_TOKENS = 2

    // Token budget for the rolling-history section.
    const val MAX_ROLLING_CONTEXT_TOKENS = 1_500

    // Pair-count cap on the rolling recent-pairs window.
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
    )

    data class Result(
        val chunks: List<TranslationContextChunk>,
        val rejectedPages: Map<String, String>,
    )

    fun plan(
        pages: LinkedHashMap<String, PageTranslation>,
        requestedOutputTokens: Int,
        profile: Profile = Profile.DEFAULT,
        pageIndexes: Map<String, Int> = emptyMap(),
        sourceLanguageCode: String? = null,
        targetLanguageCode: String? = null,
    ): Result {
        val planner = StreamingChunkPlanner(
            requestedOutputTokens = requestedOutputTokens,
            profile = profile,
            naturalPageIndexes = pageIndexes,
            sourceLanguageCode = sourceLanguageCode,
            targetLanguageCode = targetLanguageCode,
        )
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
    ): TranslationContextChunk {
        val constraints = constraintsFor(profile)
        val historyLines = rollingContext.lineSequence().map(String::trim).filter(String::isNotEmpty).toMutableList()
        if (historyLines.isEmpty()) {
            return chunk.withOutputCap(requestedOutputTokens, constraints)
        }
        val maxContextPrompt = maxPromptTokensFor(chunk, constraints)
        fun currentRolling(): String = historyLines.joinToString("\n")
        fun overCap(): Boolean {
            val history = currentRolling()
            val contextTokens = estimateTokens(history)
            val candidate = chunk.estimatedPromptTokens + contextTokens
            return contextTokens > constraints.maxRollingContextTokens || candidate > maxContextPrompt
        }

        // History is a sequence of complete pairs. Evict the oldest complete
        // lines until the section and current request fit; never trim source
        // content or lower the output floor to preserve history.
        while (historyLines.isNotEmpty() && overCap()) {
            historyLines.removeAt(0)
        }
        val useRolling = currentRolling()
        if (useRolling.isEmpty()) {
            return chunk.copy(rollingContext = "")
                .withOutputCap(requestedOutputTokens, constraints)
        }
        val contextTokens = estimateTokens(useRolling)
        return chunk.copy(
            rollingContext = useRolling,
            estimatedPromptTokens = chunk.estimatedPromptTokens + contextTokens,
        ).withOutputCap(requestedOutputTokens, constraints)
    }

    fun updateRollingContext(previous: String, translatedPages: Map<String, PageTranslationView>): String {
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
                protocol = protocol,
                blockCount = blockCount,
                pageCount = pages.size,
            ),
        )

    private fun maxPromptTokensFor(
        chunk: TranslationContextChunk,
        constraints: Constraints,
    ): Int {
        val responseReserve = if (chunk.protocol == ContextualRequestProtocol.BATCH_V1) {
            batchResponseOverheadTokens(chunk.blockCount, chunk.pages.size)
        } else {
            0
        }
        return constraints.maxContextTokens - constraints.safetyMargin -
            constraints.minOutputTokens - responseReserve
    }

    fun batchResponseOverheadTokens(blockCount: Int, pageCount: Int): Int {
        val boundedBlocks = blockCount.coerceAtLeast(0)
        val boundedPages = pageCount.coerceAtLeast(0)
        return BATCH_RESPONSE_FIXED_OVERHEAD_TOKENS +
            BATCH_RESPONSE_WHITESPACE_OVERHEAD_TOKENS +
            boundedPages * BATCH_RESPONSE_PAGE_OVERHEAD_TOKENS +
            boundedBlocks * BATCH_RESPONSE_BLOCK_OVERHEAD_TOKENS
    }

    fun constraintsFor(profile: Profile): Constraints = when (profile) {
        Profile.DEFAULT -> Constraints(
            maxContextTokens = MAX_CONTEXT_TOKENS,
            safetyMargin = SAFETY_MARGIN,
            minOutputTokens = MIN_OUTPUT_TOKENS,
            promptOverheadTokens = PROMPT_OVERHEAD_TOKENS,
            maxRollingContextTokens = MAX_ROLLING_CONTEXT_TOKENS,
        )
        Profile.LM_STUDIO -> Constraints(
            maxContextTokens = 8_192,
            safetyMargin = SAFETY_MARGIN,
            minOutputTokens = MIN_OUTPUT_TOKENS,
            promptOverheadTokens = PROMPT_OVERHEAD_TOKENS,
            maxRollingContextTokens = 512,
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

enum class ContextualRequestProtocol {
    /** Strict, versioned envelope used only by chapter-batch AI translation. */
    BATCH_V1,

    /** Existing reader/single-page prompt and response behavior. */
    LEGACY,
}

data class TranslationContextChunk(
    val pages: LinkedHashMap<String, PageTranslation>,
    val blockCount: Int,
    val rollingContext: String,
    val estimatedPromptTokens: Int,
    val maxOutputTokens: Int,
    val protocol: ContextualRequestProtocol = ContextualRequestProtocol.LEGACY,
    val pageIndexes: Map<String, Int> = emptyMap(),
    /** In-memory-only hint for the one unresolved-only semantic repair request. */
    val correctionHint: TranslationCorrectionHint? = null,
)

data class TranslationCorrectionHint(
    val sourceEcho: Boolean = false,
    val wrongTargetLanguage: Boolean = false,
) {
    init {
        require(sourceEcho || wrongTargetLanguage) { "Correction hint requires classified evidence" }
    }
}

interface ContextualTextTranslator : TextTranslator {
    /** Applies the returned batch exactly once and exposes it to the live retry controller. */
    suspend fun translateContextual(chunk: TranslationContextChunk): ContextualTranslationBatch

    /**
     * Mandatory structured-result contract. Every contextual provider returns
     * per-id results keyed by request-local IDs without mutating request blocks.
     */
    suspend fun translateContextualStructured(
        chunk: TranslationContextChunk,
    ): ContextualTranslationBatch

    /** Prompts the underlying model directly for summarization. */
    suspend fun promptText(prompt: String): String
}
