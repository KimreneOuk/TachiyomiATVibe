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

    data class Result(
        val chunks: List<TranslationContextChunk>,
        val rejectedPages: Map<String, String>,
    )

    fun plan(
        pages: LinkedHashMap<String, PageTranslation>,
        requestedOutputTokens: Int,
    ): Result {
        val outputUpperBound = requestedOutputTokens.coerceAtLeast(MIN_OUTPUT_TOKENS)
        val maxPromptTokens = MAX_CONTEXT_TOKENS - SAFETY_MARGIN - MIN_OUTPUT_TOKENS
        val chunks = mutableListOf<TranslationContextChunk>()
        val rejected = linkedMapOf<String, String>()
        var current = mutableListOf<BlockRef>()
        var currentTokens = PROMPT_OVERHEAD_TOKENS

        fun flush() {
            if (current.isEmpty()) return
            chunks += buildChunk(current, outputUpperBound)
            current = mutableListOf()
            currentTokens = PROMPT_OVERHEAD_TOKENS
        }

        pages.forEach pageLoop@ { (pageKey, page) ->
            val pageRefs = mutableListOf<Pair<BlockRef, Int>>()
            var rejectReason: String? = null
            page.blocks.forEachIndexed { blockIndex, block ->
                if (rejectReason != null) return@forEachIndexed
                if (block.text.isBlank()) return@forEachIndexed
                val ref = BlockRef(pageKey, blockIndex, block)
                val refTokens = estimateBlockTokens(ref)
                if (PROMPT_OVERHEAD_TOKENS + refTokens > maxPromptTokens) {
                    rejectReason = "Text block exceeds the 8k AI context budget"
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
                if (current.isNotEmpty() && currentTokens + refTokens > maxPromptTokens) {
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
    ): TranslationContextChunk {
        val trimmedContext = rollingContext.trim()
        if (trimmedContext.isEmpty()) {
            return chunk.withOutputCap(requestedOutputTokens)
        }
        val contextTokens = estimateTokens(trimmedContext)
        val candidateTokens = chunk.estimatedPromptTokens + contextTokens
        val maxContextPrompt = MAX_CONTEXT_TOKENS - SAFETY_MARGIN - MIN_OUTPUT_TOKENS
        if (contextTokens > MAX_ROLLING_CONTEXT_TOKENS || candidateTokens > maxContextPrompt) {
            return chunk.withOutputCap(requestedOutputTokens)
        }
        return chunk.copy(
            rollingContext = trimmedContext,
            estimatedPromptTokens = candidateTokens,
        ).withOutputCap(requestedOutputTokens)
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
    ): TranslationContextChunk {
        val grouped = linkedMapOf<String, PageTranslation>()
        refs.groupBy { it.pageKey }.forEach { (pageKey, blockRefs) ->
            grouped[pageKey] = PageTranslation(
                blocks = blockRefs.map { it.block }.toMutableList(),
            )
        }
        val promptTokens = PROMPT_OVERHEAD_TOKENS + refs.sumOf(::estimateBlockTokens)
        return TranslationContextChunk(
            pages = grouped,
            blockCount = refs.size,
            rollingContext = "",
            estimatedPromptTokens = promptTokens,
            maxOutputTokens = effectiveOutputCap(promptTokens, requestedOutputTokens),
        )
    }

    private fun TranslationContextChunk.withOutputCap(requestedOutputTokens: Int): TranslationContextChunk =
        copy(maxOutputTokens = effectiveOutputCap(estimatedPromptTokens, requestedOutputTokens))

    private fun effectiveOutputCap(promptTokens: Int, requestedOutputTokens: Int): Int {
        val available = MAX_CONTEXT_TOKENS - SAFETY_MARGIN - promptTokens
        return requestedOutputTokens
            .coerceAtLeast(MIN_OUTPUT_TOKENS)
            .coerceAtMost(available)
            .coerceAtLeast(MIN_OUTPUT_TOKENS)
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
