package eu.kanade.translation.translator

data class ContextualTranslationResult(
    val id: String,
    val targetKey: AnchoredTargetKey?,
    val text: String,
    val status: Status,
) {
    enum class Status {
        TRANSLATED,
        REJECTED,
    }
}

data class AnchoredTargetKey(
    val pageIndex: Int,
    val blockIndex: Int,
)

object ContextualResponseParser {
    private val canonicalBatchIdRegex = Regex("p\\d{4,}_b\\d{4,}")

    /** Strict batch overload; retained beside the legacy line parser for reader compatibility. */
    fun parse(
        rawResponse: String,
        request: ContextualRequestBuilder.Request,
    ): ContextualTranslationBatch = parseBatch(rawResponse, request)

    fun parse(
        rawLines: List<String>,
        idMap: Map<String, AnchoredTargetKey>,
    ): List<ContextualTranslationResult> {
        val results = mutableListOf<ContextualTranslationResult>()
        val seenIds = HashSet<String>()
        for (rawLine in rawLines) {
            val parsed = TranslationPrompts.parseLine(rawLine)
            if (parsed == null) {
                results += ContextualTranslationResult(
                    id = rawLine.trim().take(64),
                    targetKey = null,
                    text = "",
                    status = ContextualTranslationResult.Status.REJECTED,
                )
                continue
            }
            val id = parsed.id
            val target = idMap[id]
            if (target == null) {
                results += ContextualTranslationResult(
                    id = id,
                    targetKey = null,
                    text = parsed.text,
                    status = ContextualTranslationResult.Status.REJECTED,
                )
                continue
            }
            if (!seenIds.add(id)) {
                results += ContextualTranslationResult(
                    id = id,
                    targetKey = target,
                    text = parsed.text,
                    status = ContextualTranslationResult.Status.REJECTED,
                )
                continue
            }
            if (parsed.text.isBlank()) {
                results += ContextualTranslationResult(
                    id = id,
                    targetKey = target,
                    text = parsed.text,
                    status = ContextualTranslationResult.Status.REJECTED,
                )
                continue
            }
            results += ContextualTranslationResult(
                id = id,
                targetKey = target,
                text = parsed.text,
                status = ContextualTranslationResult.Status.TRANSLATED,
            )
        }
        return results
    }

    /**
     * Parses a complete Phase 1 batch response. The parser is intentionally fail-closed: a
     * missing, duplicate, unknown, normalized, malformed, or blank required output makes the
     * returned batch non-promotable. Callers may retain the diagnostics for retry/telemetry, but
     * [applyBatchToChunk] will not mutate a page from an invalid response.
     */
    fun parseBatch(
        rawResponse: String,
        request: ContextualRequestBuilder.Request,
    ): ContextualTranslationBatch {
        val sanitizedResponse = OcrArtifactSanitizer.stripThinkingTags(rawResponse)
        val results = mutableListOf<ContextualTranslationResult>()
        val errors = mutableListOf<String>()
        val seenIds = HashSet<String>()
        val contextDeltas = linkedMapOf<String, String>()

        sanitizedResponse.lineSequence().forEachIndexed { lineNumber, rawLine ->
            val line = rawLine.removeSuffix("\r").trim()
            if (line.isBlank() || line.startsWith("```") || line == "```") return@forEachIndexed

            val separator = line.indexOf('|')
            if (separator <= 0) {
                // Ignore envelope markers or comment lines gracefully
                if (!line.startsWith("TACHIYOMI_") && !line.startsWith("BEGIN_") && !line.startsWith("END_") && !line.startsWith("Response schema")) {
                    errors += "Malformed line at line ${lineNumber + 1}: '$line'"
                }
                return@forEachIndexed
            }

            val id = line.substring(0, separator).trim()
            val text = line.substring(separator + 1).trim()
            val target = request.idMap[id]

            if (target == null) {
                errors += "Unknown id '$id'"
                results += ContextualTranslationResult(
                    id = id,
                    targetKey = null,
                    text = text,
                    status = ContextualTranslationResult.Status.REJECTED,
                )
                return@forEachIndexed
            }

            if (!seenIds.add(id)) {
                errors += "Duplicate id '$id'"
                results += ContextualTranslationResult(
                    id = id,
                    targetKey = target,
                    text = text,
                    status = ContextualTranslationResult.Status.REJECTED,
                )
                return@forEachIndexed
            }

            if (text.isBlank()) {
                errors += "Blank required output for '$id'"
                results += ContextualTranslationResult(
                    id = id,
                    targetKey = target,
                    text = text,
                    status = ContextualTranslationResult.Status.REJECTED,
                )
                return@forEachIndexed
            }

            results += ContextualTranslationResult(
                id = id,
                targetKey = target,
                text = text,
                status = ContextualTranslationResult.Status.TRANSLATED,
            )
        }

        val missingIds = request.orderedIds.toSet() - seenIds
        missingIds.forEach { errors += "Missing translation for '$it'" }

        val contentValid = missingIds.isEmpty() &&
            errors.isEmpty() &&
            results.all { it.status == ContextualTranslationResult.Status.TRANSLATED }

        return ContextualTranslationBatch(
            idToBlockIndex = request.locations,
            results = results,
            strictValidation = true,
            protocolVersion = BatchTranslationProtocol.VERSION,
            contextDeltas = contextDeltas,
            validationErrors = errors.distinct(),
            framingRecovered = contentValid,
        )
    }
}

fun applyBatchToChunk(
    chunk: TranslationContextChunk,
    batch: ContextualTranslationBatch,
) {
    if (!batch.isStructurallyValid) return
    val accepted = batch.accepted
    for (result in accepted) {
        val location = batch.idToBlockIndex[result.id] ?: continue
        val page = chunk.pages[location.pageKey] ?: continue
        val block = page.blocks.getOrNull(location.blockIndex) ?: continue
        if (block.userEditedAt != null) continue
        block.translation = OcrArtifactSanitizer.sanitize(result.text)
    }
    TranslationBlockFilters.removeWatermarkBlocks(chunk.pages)
}
