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
        val results = mutableListOf<ContextualTranslationResult>()
        val errors = mutableListOf<String>()
        val seenIds = HashSet<String>()
        val seenPages = HashSet<String>()
        val seenDeltas = HashSet<String>()
        val contextDeltas = linkedMapOf<String, String>()
        // Every page present in the request has exactly one required PAGE section, including a
        // textless/empty page. Block cardinality is still derived solely from orderedIds, so an
        // empty page is valid when represented by an empty section and cannot create unknown IDs.
        val expectedPageIds = request.pageOrder.mapNotNull { pageKey ->
            request.pageIndexes[pageKey]?.let(BatchTranslationProtocol::pageId)
        }.toSet().ifEmpty {
            request.orderedIds
                .mapNotNull { it.substringBefore("_b", missingDelimiterValue = "").takeIf(String::isNotBlank) }
                .toSet()
        }

        var headerSeen = false
        var footerSeen = false
        var currentPage: String? = null
        var currentDelta: String? = null
        val deltaLines = mutableListOf<String>()

        fun rejected(
            id: String,
            text: String = "",
            target: AnchoredTargetKey? = null,
        ) {
            results += ContextualTranslationResult(
                id = id,
                targetKey = target,
                text = text,
                status = ContextualTranslationResult.Status.REJECTED,
            )
        }

        fun parsePageToken(line: String, marker: String): String? {
            val prefix = "$marker "
            if (!line.startsWith(prefix)) return null
            val token = line.removePrefix(prefix)
            if (token.isBlank() || token.any(Char::isWhitespace)) {
                errors += "Invalid $marker marker: '$line'"
                return token.ifBlank { null }
            }
            return token
        }

        fun parseTranslationLine(line: String, pageId: String) {
            if (line.firstOrNull()?.isWhitespace() == true) {
                errors += "Normalized translation line at page $pageId"
            }
            val separator = line.indexOf('|')
            if (separator <= 0) {
                errors += "Malformed translation line at page $pageId"
                rejected(line.trim().take(64))
                return
            }
            val id = line.substring(0, separator)
            // IDs remain byte-exact; provider-added whitespace at the end of the target is
            // harmless and is removed only from the target tail.
            val text = line.substring(separator + 1).trimEnd()
            val target = request.idMap[id]
            val canonical = canonicalBatchIdRegex.matches(id)
            if (!canonical) {
                errors += "Normalized or malformed id '$id'"
            }
            if (target == null) {
                errors += "Unknown id '$id'"
                rejected(id, text)
                return
            }
            val expectedPage = id.substringBefore("_b")
            if (expectedPage != pageId) {
                errors += "Id '$id' is outside page section $pageId"
            }
            if (!seenIds.add(id)) {
                errors += "Duplicate id '$id'"
                rejected(id, text, target)
                return
            }
            if (text.isBlank()) {
                errors += "Blank required output for '$id'"
                rejected(id, text, target)
                return
            }
            if (canonical) {
                results += ContextualTranslationResult(
                    id = id,
                    targetKey = target,
                    text = text,
                    status = ContextualTranslationResult.Status.TRANSLATED,
                )
            } else {
                rejected(id, text, target)
            }
        }

        rawResponse.lineSequence().forEachIndexed { lineNumber, rawLine ->
            val line = rawLine.removeSuffix("\r")
            if (line.isBlank()) return@forEachIndexed
            if (footerSeen) {
                errors += "Extra content after response footer at line ${lineNumber + 1}"
                return@forEachIndexed
            }

            if (!headerSeen) {
                if (line == BatchTranslationProtocol.RESPONSE_HEADER) {
                    headerSeen = true
                } else {
                    errors += "Missing batch response header"
                }
                return@forEachIndexed
            }

            if (currentPage != null) {
                val endPage = parsePageToken(line, BatchTranslationProtocol.PAGE_END)
                if (endPage != null || line.startsWith("${BatchTranslationProtocol.PAGE_END} ")) {
                    if (endPage != currentPage) {
                        errors += "Mismatched page section end '$line'"
                    }
                    currentPage = null
                } else {
                    if (line.startsWith("BEGIN_") || line.startsWith("END_")) {
                        errors += "Nested or extra section inside page $currentPage"
                        rejected(line.take(64))
                    } else {
                        parseTranslationLine(line, currentPage!!)
                    }
                }
                return@forEachIndexed
            }

            if (currentDelta != null) {
                val endDelta = parsePageToken(line, BatchTranslationProtocol.CONTEXT_DELTA_END)
                if (endDelta != null || line.startsWith("${BatchTranslationProtocol.CONTEXT_DELTA_END} ")) {
                    if (endDelta != currentDelta) {
                        errors += "Mismatched context delta end '$line'"
                    }
                    contextDeltas[currentDelta!!] = deltaLines.joinToString("\n")
                    currentDelta = null
                    deltaLines.clear()
                } else {
                    if (line.startsWith("BEGIN_") || line.startsWith("END_")) {
                        errors += "Nested or extra section inside context delta $currentDelta"
                    } else {
                        deltaLines += line
                    }
                }
                return@forEachIndexed
            }

            when {
                line == BatchTranslationProtocol.RESPONSE_END -> footerSeen = true
                BatchTranslationProtocol.isPageStart(line) -> {
                    val pageId = parsePageToken(line, BatchTranslationProtocol.PAGE_START)
                    if (pageId == null) return@forEachIndexed
                    if (!seenPages.add(pageId)) errors += "Duplicate page section '$pageId'"
                    if (pageId !in expectedPageIds) errors += "Unknown page section '$pageId'"
                    currentPage = pageId
                }
                BatchTranslationProtocol.isContextDeltaStart(line) -> {
                    val pageId = parsePageToken(line, BatchTranslationProtocol.CONTEXT_DELTA_START)
                    if (pageId == null) return@forEachIndexed
                    if (!seenDeltas.add(pageId)) errors += "Duplicate context delta '$pageId'"
                    if (pageId !in expectedPageIds) errors += "Unknown context delta page '$pageId'"
                    currentDelta = pageId
                    deltaLines.clear()
                }
                line.startsWith("BEGIN_") || line.startsWith("END_") -> {
                    errors += "Unknown response section '$line'"
                }
                else -> errors += "Unexpected response content at line ${lineNumber + 1}"
            }
        }

        if (!headerSeen) errors += "Missing batch response header"
        if (!footerSeen) errors += "Missing batch response footer"
        if (currentPage != null) errors += "Unclosed page section '$currentPage'"
        if (currentDelta != null) errors += "Unclosed context delta '$currentDelta'"
        val missingPages = expectedPageIds - seenPages
        missingPages.forEach { errors += "Missing page section '$it'" }

        return ContextualTranslationBatch(
            idToBlockIndex = request.locations,
            results = results,
            strictValidation = true,
            protocolVersion = BatchTranslationProtocol.VERSION,
            contextDeltas = contextDeltas,
            validationErrors = errors.distinct(),
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
