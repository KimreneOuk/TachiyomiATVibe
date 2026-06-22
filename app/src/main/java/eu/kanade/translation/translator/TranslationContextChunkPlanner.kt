package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock

/**
 * Conservative AI-request chunking for pre-translation.
 *
 * The setting named "max output tokens" is treated as an upper bound only. A
 * chunk's effective output cap is reduced so estimated input + context + output
 * remains below [MAX_CONTEXT_TOKENS].
 */
object TranslationContextChunkPlanner {
    const val MAX_CONTEXT_TOKENS = 8_192
    const val SAFETY_MARGIN = 512
    const val MIN_OUTPUT_TOKENS = 256
    const val PROMPT_OVERHEAD_TOKENS = 900
    const val MAX_ROLLING_CONTEXT_TOKENS = 768

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
        val constraints = constraintsFor(profile).let {
            it.copy(
                maxBlocksPerChunk = maxBlocksPerChunk ?: it.maxBlocksPerChunk,
                maxPagesPerChunk = maxPagesPerChunk ?: it.maxPagesPerChunk,
            )
        }
        val outputUpperBound = requestedOutputTokens.coerceAtLeast(constraints.minOutputTokens)
        val maxPromptTokens = constraints.maxContextTokens - constraints.safetyMargin - constraints.minOutputTokens
        val chunks = mutableListOf<TranslationContextChunk>()
        val rejected = linkedMapOf<String, String>()
        var current = mutableListOf<BlockRef>()
        var currentTokens = constraints.promptOverheadTokens

        fun flush() {
            if (current.isEmpty()) return
            chunks += buildChunk(current, outputUpperBound, constraints)
            current = mutableListOf()
            currentTokens = constraints.promptOverheadTokens
        }

        pages.forEach pageLoop@ { (pageKey, page) ->
            val pageRefs = mutableListOf<Pair<BlockRef, Int>>()
            var rejectReason: String? = null
            page.blocks.forEachIndexed { blockIndex, block ->
                if (rejectReason != null) return@forEachIndexed
                if (block.text.isBlank()) return@forEachIndexed
                // TachiyomiAT: resume — skip blocks that already carry a real
                // translation. On a resumed AI batch, blocks translated in a
                // prior run would otherwise be re-sent to the LLM, wasting
                // tokens + HTTP round-trips per finished block. A "real"
                // translation is non-blank AND not source-equal (trim compare
                // guards against adapters that echo the source verbatim or
                // wrap it in stray whitespace). Mirrors the predicate in
                // AiTranslationRetryPlanner.untranslatedBlocks.
                if (block.translation.isNotBlank() && block.translation.trim() != block.text.trim()) {
                    return@forEachIndexed
                }
                val ref = BlockRef(pageKey, blockIndex, block)
                val refTokens = estimateBlockTokens(ref)
                if (constraints.promptOverheadTokens + refTokens > maxPromptTokens) {
                    rejectReason = "Text block exceeds the ${constraints.maxContextTokens / 1024}k AI context budget"
                    return@forEachIndexed
                }
                pageRefs += ref to refTokens
            }
            rejectReason?.let { reason ->
                flush()
                rejected[pageKey] = reason
                return@pageLoop
            }
            pageRefs.forEach { (ref, refTokens) ->
                val prospectivePageCount = current.mapTo(linkedSetOf()) { it.pageKey }
                    .also { it += ref.pageKey }
                    .size
                if (current.isNotEmpty() &&
                    (
                        currentTokens + refTokens > maxPromptTokens ||
                            current.size >= constraints.maxBlocksPerChunk ||
                            prospectivePageCount > constraints.maxPagesPerChunk
                        )
                ) {
                    flush()
                }
                current += ref
                currentTokens += refTokens
            }
        }
        flush()

        return Result(chunks = chunks, rejectedPages = rejected)
    }

    fun withRollingContext(
        chunk: TranslationContextChunk,
        rollingContext: String,
        requestedOutputTokens: Int,
        profile: Profile = Profile.DEFAULT,
    ): TranslationContextChunk {
        val constraints = constraintsFor(profile)
        val trimmedContext = rollingContext.trim()
        if (trimmedContext.isEmpty()) {
            return chunk.withOutputCap(requestedOutputTokens, constraints)
        }
        val contextTokens = estimateTokens(trimmedContext)
        val candidateTokens = chunk.estimatedPromptTokens + contextTokens
        val maxContextPrompt = constraints.maxContextTokens - constraints.safetyMargin - constraints.minOutputTokens
        if (contextTokens > constraints.maxRollingContextTokens || candidateTokens > maxContextPrompt) {
            return chunk.withOutputCap(requestedOutputTokens, constraints)
        }
        return chunk.copy(
            rollingContext = trimmedContext,
            estimatedPromptTokens = candidateTokens,
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
            .takeLast(12)
        return lines.joinToString("\n")
    }

    fun estimateTokens(text: String): Int {
        var tokens = 0
        var latinRun = 0
        fun flushLatin() {
            if (latinRun > 0) {
                tokens += (latinRun + 3) / 4
                latinRun = 0
            }
        }
        text.forEach { ch ->
            when {
                ch.isCjk() -> {
                    flushLatin()
                    tokens += 1
                }
                ch.isWhitespace() -> {
                    flushLatin()
                }
                ch.isLetterOrDigit() -> {
                    latinRun += 1
                }
                else -> {
                    flushLatin()
                    tokens += 1
                }
            }
        }
        flushLatin()
        return tokens.coerceAtLeast(1)
    }

    private fun buildChunk(
        refs: List<BlockRef>,
        requestedOutputTokens: Int,
        constraints: Constraints,
    ): TranslationContextChunk {
        val grouped = linkedMapOf<String, PageTranslation>()
        refs.groupBy { it.pageKey }.forEach { (pageKey, blockRefs) ->
            grouped[pageKey] = PageTranslation(
                blocks = blockRefs.map { it.block }.toMutableList(),
            )
        }
        val promptTokens = constraints.promptOverheadTokens + refs.sumOf(::estimateBlockTokens)
        return TranslationContextChunk(
            pages = grouped,
            blockCount = refs.size,
            rollingContext = "",
            estimatedPromptTokens = promptTokens,
            maxOutputTokens = effectiveOutputCap(promptTokens, requestedOutputTokens, constraints),
        )
    }

    private fun TranslationContextChunk.withOutputCap(
        requestedOutputTokens: Int,
        constraints: Constraints,
    ): TranslationContextChunk =
        copy(maxOutputTokens = effectiveOutputCap(estimatedPromptTokens, requestedOutputTokens, constraints))

    private fun effectiveOutputCap(
        promptTokens: Int,
        requestedOutputTokens: Int,
        constraints: Constraints,
    ): Int {
        val available = constraints.maxContextTokens - constraints.safetyMargin - promptTokens
        return requestedOutputTokens
            .coerceAtLeast(constraints.minOutputTokens)
            .coerceAtMost(available)
            .coerceAtLeast(constraints.minOutputTokens)
    }

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
            maxContextTokens = 4_096,
            safetyMargin = SAFETY_MARGIN,
            minOutputTokens = MIN_OUTPUT_TOKENS,
            promptOverheadTokens = PROMPT_OVERHEAD_TOKENS,
            maxRollingContextTokens = 384,
            maxBlocksPerChunk = 32,
            maxPagesPerChunk = 4,
        )
    }

    private fun estimateBlockTokens(ref: BlockRef): Int {
        val keyOverhead = estimateTokens(ref.pageKey) + 8
        return keyOverhead + estimateTokens(ref.block.text)
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

    private data class BlockRef(
        val pageKey: String,
        val blockIndex: Int,
        val block: TranslationBlock,
    )
}

data class TranslationContextChunk(
    val pages: LinkedHashMap<String, PageTranslation>,
    val blockCount: Int,
    val rollingContext: String,
    val estimatedPromptTokens: Int,
    val maxOutputTokens: Int,
)

interface ContextualTextTranslator : TextTranslator {
    suspend fun translateContextual(chunk: TranslationContextChunk)
}
