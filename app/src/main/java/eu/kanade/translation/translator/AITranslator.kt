package eu.kanade.translation.translator

import eu.kanade.translation.batch.RollingContextPacket
import eu.kanade.translation.model.PageTranslation

/**
 * Structured result of parsing an AI batch translation response.
 */
data class ParsedAiChunkResponse(
    val translations: Map<String, String> = emptyMap(),
    val updatedGlossary: Map<String, String> = emptyMap(),
    val microSummary: String = "",
) {
    fun toRollingContextPacket(): RollingContextPacket {
        return RollingContextPacket(
            glossary = updatedGlossary,
            microSummary = microSummary,
        )
    }
}

/**
 * Parser for AI translation responses containing translation blocks, updated glossary terms,
 * and scene micro-summaries.
 */
object AITranslatorResponseParser {
    private val glossaryTagRegex = Regex("""\[GLOSSARY\]([\s\S]*?)\[(?:END GLOSSARY|/GLOSSARY)\]""", RegexOption.IGNORE_CASE)
    private val summaryTagRegex = Regex("""\[(?:SUMMARY|MICRO_SUMMARY)\]([\s\S]*?)\[(?:END SUMMARY|END MICRO_SUMMARY|/SUMMARY|/MICRO_SUMMARY)\]""", RegexOption.IGNORE_CASE)
    private val summaryLineRegex = Regex("""^(?:SUMMARY|MICRO[-_]SUMMARY|SCENE SUMMARY|PREVIOUS SCENE SUMMARY)\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val glossaryHeaderRegex = Regex("""^(?:ESTABLISHED\s+)?GLOSSARY\s*:\s*$""", RegexOption.IGNORE_CASE)

    fun parse(rawResponse: String): ParsedAiChunkResponse {
        val sanitized = OcrArtifactSanitizer.stripThinkingTags(rawResponse)
        val glossaryMap = mutableMapOf<String, String>()
        var microSummary = ""

        // 1. Extract [GLOSSARY] block if present
        val glossaryMatch = glossaryTagRegex.find(sanitized)
        if (glossaryMatch != null) {
            val content = glossaryMatch.groupValues[1]
            parseGlossaryLines(content, glossaryMap)
        }

        // 2. Extract [SUMMARY] block if present
        val summaryMatch = summaryTagRegex.find(sanitized)
        if (summaryMatch != null) {
            microSummary = summaryMatch.groupValues[1].trim()
        }

        // Remove the tagged blocks from content before parsing lines
        var remainingText = sanitized
        if (glossaryMatch != null) {
            remainingText = remainingText.replace(glossaryMatch.value, "")
        }
        if (summaryMatch != null) {
            remainingText = remainingText.replace(summaryMatch.value, "")
        }

        // 3. Parse remaining lines for translation blocks and inline summary/glossary
        val translations = mutableMapOf<String, String>()
        var inGlossarySection = false

        for (line in remainingText.lines()) {
            val trimmed = line.trim(' ', '\t', '\r', '\n', '`', '*')
            if (trimmed.isBlank()) continue

            if (glossaryHeaderRegex.matches(trimmed)) {
                inGlossarySection = true
                continue
            }

            val sumLineMatch = summaryLineRegex.find(trimmed)
            if (sumLineMatch != null) {
                inGlossarySection = false
                if (microSummary.isBlank()) {
                    microSummary = sumLineMatch.groupValues[1].trim()
                }
                continue
            }

            val parsedLine = TranslationPrompts.parseLine(trimmed)
            if (parsedLine != null) {
                inGlossarySection = false
                if (parsedLine.id !in translations && parsedLine.text.isNotBlank()) {
                    translations[parsedLine.id] = parsedLine.text
                }
                continue
            }

            if (inGlossarySection) {
                parseSingleGlossaryLine(trimmed, glossaryMap)
            }
        }

        return ParsedAiChunkResponse(
            translations = translations,
            updatedGlossary = glossaryMap,
            microSummary = microSummary,
        )
    }

    private fun parseGlossaryLines(content: String, outMap: MutableMap<String, String>) {
        for (line in content.lines()) {
            val trimmed = line.trim(' ', '\t', '\r', '\n', '`', '*')
            if (trimmed.isBlank()) continue
            parseSingleGlossaryLine(trimmed, outMap)
        }
    }

    private fun parseSingleGlossaryLine(line: String, outMap: MutableMap<String, String>) {
        val clean = line.removePrefix("-").removePrefix("*").trim()
        if (clean.isBlank()) return
        val separator = when {
            clean.contains("=>") -> "=>"
            clean.contains(":") -> ":"
            clean.contains("=") -> "="
            clean.contains("|") -> "|"
            else -> return
        }
        val parts = clean.split(separator, limit = 2)
        if (parts.size == 2) {
            val term = parts[0].trim()
            val def = parts[1].trim()
            if (term.isNotBlank() && def.isNotBlank()) {
                outMap[term] = def
            }
        }
    }
}

/**
 * TachiyomiAT: Base abstraction for AI / LLM translators.
 *
 * Supports chunk batch translation with [RollingContextPacket] injection (glossary + scene micro-summary)
 * and structured parsing of translation blocks, updated glossary terms, and scene summaries.
 */
abstract class AITranslator : BaseTranslator(), ContextualTextTranslator {

    /**
     * Builds prompt payload with rolling context injected into the prompt.
     */
    open fun buildPromptWithRollingContext(
        request: ContextualRequestBuilder.Request,
        rollingContext: RollingContextPacket = RollingContextPacket(),
        extraGlossary: String = "",
    ): String {
        return ContextualRequestBuilder.renderPrompt(request, rollingContext, extraGlossary)
    }

    /**
     * Parses an AI response into structured translations, updated glossary entries,
     * and a micro-summary of the current scene.
     */
    open fun parseChunkResponse(rawResponse: String): ParsedAiChunkResponse {
        return AITranslatorResponseParser.parse(rawResponse)
    }

    /**
     * Translates a chunk of pages with rolling context propagation.
     * Extracts translations into the pages and returns the updated [RollingContextPacket].
     */
    open suspend fun translateChunkWithRollingContext(
        chunk: TranslationContextChunk,
        rollingContext: RollingContextPacket,
    ): Pair<ContextualTranslationBatch, RollingContextPacket> {
        val request = ContextualRequestBuilder.buildFor(chunk, fromLang, toLang)
        if (request.promptLines.isEmpty()) {
            return ContextualRequestBuilder.toBatch(request, emptyList()) to rollingContext
        }

        val prompt = buildPromptWithRollingContext(request, rollingContext, chunk.glossary)
        val rawResponse = promptText(prompt)
        val parsedChunk = if (request.protocol == ContextualRequestProtocol.BATCH_V1) {
            null
        } else {
            parseChunkResponse(rawResponse)
        }
        val batch = if (request.protocol == ContextualRequestProtocol.BATCH_V1) {
            ContextualResponseParser.parseBatch(rawResponse, request)
        } else {
            val results = request.orderedIds.map { id ->
                val text = parsedChunk?.translations?.get(id)
                val target = request.idMap[id]
                if (text != null && text.isNotBlank()) {
                    ContextualTranslationResult(
                        id = id,
                        targetKey = target,
                        text = text,
                        status = ContextualTranslationResult.Status.TRANSLATED,
                    )
                } else {
                    ContextualTranslationResult(
                        id = id,
                        targetKey = target,
                        text = "",
                        status = ContextualTranslationResult.Status.REJECTED,
                    )
                }
            }
            ContextualRequestBuilder.toBatch(request, results)
        }
        applyBatchToChunk(chunk, batch)

        val updatedContext = if (request.protocol == ContextualRequestProtocol.BATCH_V1) {
            // Context-delta interpretation belongs to the later context-quality phase. A
            // structurally invalid batch must never advance rolling state.
            rollingContext
        } else {
            val legacyParsed = requireNotNull(parsedChunk)
            RollingContextPacket(
                glossary = rollingContext.glossary + legacyParsed.updatedGlossary,
                microSummary = if (legacyParsed.microSummary.isNotBlank()) legacyParsed.microSummary else rollingContext.microSummary,
            )
        }

        return batch to updatedContext
    }

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        val linkedPages = LinkedHashMap(pages)
        val blockCount = linkedPages.values.sumOf { it.blocks.size }
        val chunk = TranslationContextChunk(
            pages = linkedPages,
            blockCount = blockCount,
            rollingContext = "",
            glossary = "",
            estimatedPromptTokens = 0,
            maxOutputTokens = 8192,
            protocol = ContextualRequestProtocol.LEGACY,
        )
        translateContextual(chunk)
    }

    override suspend fun translateContextual(chunk: TranslationContextChunk): ContextualTranslationBatch {
        val batch = translateContextualStructured(chunk)
        applyBatchToChunk(chunk, batch)
        return batch
    }

    companion object {
        fun parseChunkResponse(rawResponse: String): ParsedAiChunkResponse =
            AITranslatorResponseParser.parse(rawResponse)

        fun buildPromptWithRollingContext(
            request: ContextualRequestBuilder.Request,
            rollingContext: RollingContextPacket = RollingContextPacket(),
            extraGlossary: String = "",
        ): String {
            return ContextualRequestBuilder.renderPrompt(request, rollingContext, extraGlossary)
        }
    }
}
