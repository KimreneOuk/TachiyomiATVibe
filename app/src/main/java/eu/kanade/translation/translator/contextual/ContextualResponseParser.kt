package eu.kanade.translation.translator.contextual
import eu.kanade.translation.translator.providers.OcrArtifactSanitizer
import eu.kanade.translation.util.ShortHash
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

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
    private val canonicalBatchIdRegex = Regex("p\\d+_b\\d+")

    /**
     * T934 separator-salvage rule (see
     * `Plan/active/2026-09-16_T934_resume-rebuild-and-parallelism/team/diagnosis-omitted-blocks.md`):
     * the deployed 7B model deterministically corrupts the `ID|text` frame on
     * short emphatic lines concentrated on the envelope's LAST page, substituting
     * another glyph for the required `|` separator (`>` for `|` observed 7/7 on
     * block p12_b6 in the desktop replay). The block id and the translation text
     * stay correct — only the separator glyph is wrong — so the strict parser
     * drops them as malformed, the retry ladder re-asks, the model re-corrupts
     * them, and the attempt budget exhausts. A line anchored by an EXACT,
     * REQUESTED block id followed by a single NON-ALPHANUMERIC separator that is
     * not `|` (e.g. `>`, `:`, `：`, `;`, `-`, `→`, or a whitespace run) and a
     * NON-EMPTY remainder is therefore salvaged as that block's translation.
     * Guard rails keep every other parse stage strict: hallucinated or
     * digit-extended ids (`p12_b67` when `p12_b6` was requested — the greedy
     * digit run keeps the id from being read as a prefix), blank remainders, and
     * duplicate handling all behave exactly as before.
     */
    private val corruptedSeparatorLineRegex = Regex("""^(p\d+_b\d+)([^|\w])(.*)$""", RegexOption.IGNORE_CASE)

    fun normalizeBatchId(rawId: String): String {
        val trimmed = rawId.trim().lowercase()
        val match = Regex("""p0*(\d+)_b0*(\d+)""").matchEntire(trimmed)
        if (match != null) {
            val (p, b) = match.destructured
            return "p${p}_b$b"
        }
        val blockOnly = Regex("""b0*(\d+)""").matchEntire(trimmed)
        if (blockOnly != null) {
            val (b) = blockOnly.destructured
            return "b$b"
        }
        return trimmed
    }

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
            val id = normalizeBatchId(parsed.id)
            val target = idMap[id] ?: idMap[parsed.id]
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
     * Parses a complete Phase 1 batch response with resilient ID normalization.
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
        val requestedIds = request.idMap.keys.mapTo(HashSet(), ::normalizeBatchId)
        val envelopeId = ShortHash.hash(request.orderedIds.joinToString(","))

        sanitizedResponse.lineSequence().forEachIndexed { lineNumber, rawLine ->
            val line = rawLine.removeSuffix("\r").trim()
            if (line.isBlank() || line.startsWith("```") || line == "```") return@forEachIndexed

            val separator = line.indexOf('|')
            val rawId: String
            val text: String
            if (separator <= 0) {
                // Ignore envelope markers or comment lines gracefully
                val isEnvelopeMarker = line.startsWith("TACHIYOMI_") ||
                    line.startsWith("BEGIN_") ||
                    line.startsWith("END_") ||
                    line.startsWith("Response schema")
                val salvaged = if (isEnvelopeMarker) {
                    null
                } else {
                    salvageCorruptedSeparator(line, requestedIds)
                }
                if (salvaged == null) {
                    if (!isEnvelopeMarker) {
                        errors += "Malformed line at line ${lineNumber + 1}: '$line'"
                    }
                    return@forEachIndexed
                }
                logcat(LogPriority.WARN) {
                    "event=batch_separator_salvage envelope=$envelopeId block=${salvaged.id} " +
                        "separator=U+${salvaged.separatorCodePoint.toString(16).uppercase().padStart(4, '0')} " +
                        "targetLength=${salvaged.text.length}"
                }
                rawId = salvaged.rawId
                text = salvaged.text
            } else {
                rawId = line.substring(0, separator).trim()
                text = line.substring(separator + 1).trim()
            }
            val id = normalizeBatchId(rawId)
            val target = request.idMap[id] ?: request.idMap[rawId]

            if (target == null) {
                errors += "Unknown id '$rawId'"
                results += ContextualTranslationResult(
                    id = id,
                    targetKey = null,
                    text = text,
                    status = ContextualTranslationResult.Status.REJECTED,
                )
                return@forEachIndexed
            }

            if (!seenIds.add(id)) {
                errors += "Duplicate id '$rawId'"
                results += ContextualTranslationResult(
                    id = id,
                    targetKey = target,
                    text = text,
                    status = ContextualTranslationResult.Status.REJECTED,
                )
                return@forEachIndexed
            }

            if (text.isBlank()) {
                errors += "Blank required output for '$rawId'"
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

        val missingIds = request.orderedIds.map(::normalizeBatchId).toSet() - seenIds
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

    /**
     * T934: salvage candidate for a no-`|` line. The greedy digit run in
     * [corruptedSeparatorLineRegex] rejects digit-extended ids (`p12_b67` stays
     * `p12_b67`, never a corrupted `p12_b6`), the separator must be a single
     * non-word, non-`|` character, and only ids the envelope actually requested
     * are eligible (echo prevention). Blank remainders stay malformed.
     */
    private fun salvageCorruptedSeparator(line: String, requestedIds: Set<String>): SalvagedSeparatorLine? {
        val match = corruptedSeparatorLineRegex.find(line) ?: return null
        val rawId = match.groupValues[1]
        val separator = match.groupValues[2]
        val text = match.groupValues[3].trim()
        if (text.isEmpty()) return null
        val id = normalizeBatchId(rawId)
        if (id !in requestedIds) return null
        return SalvagedSeparatorLine(rawId, id, separator.codePointAt(0), text)
    }

    private data class SalvagedSeparatorLine(
        val rawId: String,
        val id: String,
        val separatorCodePoint: Int,
        val text: String,
    )
}

fun applyBatchToChunk(
    chunk: TranslationContextChunk,
    batch: ContextualTranslationBatch,
) {
    val accepted = batch.accepted
    for (result in accepted) {
        val location = batch.idToBlockIndex[result.id]
            ?: batch.idToBlockIndex[ContextualResponseParser.normalizeBatchId(result.id)]
            ?: continue
        val page = chunk.pages[location.pageKey] ?: continue
        val block = page.blocks.getOrNull(location.blockIndex) ?: continue
        if (block.userEditedAt != null) continue
        block.translation = OcrArtifactSanitizer.sanitize(result.text)
    }
    TranslationBlockFilters.removeWatermarkBlocks(chunk.pages)
}
