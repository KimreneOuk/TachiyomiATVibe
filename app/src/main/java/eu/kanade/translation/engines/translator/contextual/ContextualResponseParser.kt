package eu.kanade.translation.engines.translator.contextual
import eu.kanade.translation.engines.translator.providers.OcrArtifactSanitizer
import eu.kanade.translation.util.ShortHash
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/** Safe parser diagnostic preserving a response-level refusal after candidate text is discarded. */
internal const val STRUCTURAL_REFUSAL_DIAGNOSTIC = "Structural refusal detected"

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
    /**
     * Request-local batch row grammar (Task C item 7 and §131 of ai-output-repair-feedback):
     *
     * | Part | Accepted forms |
     * | --- | --- |
     * | ID | Exact requested numeric ID; outer whitespace and leading zeroes are normalized |
     * | Separator | `|`, `:`, `：`, `>`, `→`, `;`, `-`, or whitespace |
     * | Bullet wrapper | `-`, `*`, or `•` before the explicit ID |
     * | Numbered wrapper | `N.` or `N)` only when a second explicit request ID follows |
     *
     * A period is not a separator: `1. translated text` is ambiguous with an ordinal and is
     * rejected rather than assigned to item 1. This deliberately narrows the legacy durable-ID
     * salvage regex, whose `[^|\\w]` separator accepted arbitrary punctuation. It implements
     * the spec's finite wrapper/deviation allowance and the "Top three risks" ambiguity fence
     * (risk 1), while leaving an unresolved item recoverable by a bounded follow-up.
     * The strict batch parser accepts numeric IDs only; the separate reader-line parser retains
     * its legacy bN association.
     */

    fun normalizeBatchId(rawId: String): String {
        val trimmed = rawId.trim().lowercase()
        val numeric = Regex("""0*(\d+)""").matchEntire(trimmed)
        if (numeric != null) {
            val value = numeric.groupValues[1].toLongOrNull()
            if (value != null) return value.toString()
        }
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
     * Parses a complete response into per-requested-item candidates. A malformed row or an
     * unrelated extra ID does not erase a different item's uniquely recovered translation.
     */
    fun parseBatch(
        rawResponse: String,
        request: ContextualRequestBuilder.Request,
    ): ContextualTranslationBatch {
        val sanitizedResponse = OcrArtifactSanitizer.stripThinkingTags(rawResponse)
        val errors = mutableListOf<String>()
        val refusalDetected = TranslationResponseFaithfulness.isStructuralRefusal(
            OcrArtifactSanitizer.sanitize(sanitizedResponse),
        )
        if (refusalDetected) {
            // Keep only a fixed marker; never retain refusal prose after parsing.
            errors += STRUCTURAL_REFUSAL_DIAGNOSTIC
        }
        val requestIds = request.orderedIds.map(::normalizeBatchId).distinct()
        val targetsById = request.idMap.entries.associate { (id, target) ->
            normalizeBatchId(id) to target
        }
        val requestedIds = requestIds.toHashSet()
        val candidatesById = linkedMapOf<String, MutableList<String>>()
        val salvagedIds = linkedSetOf<String>()
        val unknownResults = mutableListOf<ContextualTranslationResult>()
        val contextDeltas = linkedMapOf<String, String>()
        val envelopeId = ShortHash.hash(request.orderedIds.joinToString(","))
        var parseAmbiguityCount = 0

        sanitizedResponse.lineSequence().forEachIndexed { lineNumber, rawLine ->
            val line = rawLine.removeSuffix("\r").trim()
            if (line.isBlank() || line.startsWith("\u0060\u0060\u0060")) return@forEachIndexed

            val isEnvelopeMarker = line.startsWith("TACHIYOMI_") ||
                line.startsWith("BEGIN_") ||
                line.startsWith("END_") ||
                line.startsWith("Response schema")
            if (isEnvelopeMarker) return@forEachIndexed

            val candidate = parseBatchCandidate(line)
            if (candidate == null) {
                errors += "Malformed line at line " + (lineNumber + 1)
                if (ambiguousOrdinalLineRegex.matches(line)) parseAmbiguityCount++
                return@forEachIndexed
            }

            val id = normalizeBatchId(candidate.rawId)
            if (candidate.separator != "|") {
                logcat(LogPriority.WARN) {
                    "event=batch_separator_salvage envelope=" + envelopeId + " block=" + id +
                        " separator=U+" + candidate.separator.codePointAt(0).toString(16).uppercase().padStart(4, '0') +
                        " targetLength=" + candidate.text.length
                }
            }
            if (id !in requestedIds || id !in targetsById) {
                errors += "Unknown id '" + candidate.rawId + "'"
                unknownResults += ContextualTranslationResult(
                    id = id,
                    targetKey = null,
                    text = candidate.text,
                    status = ContextualTranslationResult.Status.REJECTED,
                )
                return@forEachIndexed
            }

            candidatesById.getOrPut(id, ::mutableListOf) += candidate.text
            if (candidate.salvaged || candidate.rawId != id) salvagedIds += id
        }

        val results = mutableListOf<ContextualTranslationResult>()
        requestIds.forEach { id ->
            val target = targetsById[id] ?: return@forEach
            val candidates = candidatesById[id]
            if (candidates.isNullOrEmpty()) {
                errors += "Missing translation for '" + id + "'"
                return@forEach
            }

            val uniqueTargetsByNormalizedText = linkedMapOf<String, String>()
            candidates.forEach { candidateText ->
                val target = candidateText.trim()
                val normalized = normalizeCandidateTarget(target)
                if (normalized.isNotEmpty() && normalized !in uniqueTargetsByNormalizedText) {
                    uniqueTargetsByNormalizedText[normalized] = target
                }
            }
            val distinctTargets = uniqueTargetsByNormalizedText.values.toList()
            when {
                distinctTargets.size == 1 -> {
                    results += ContextualTranslationResult(
                        id = id,
                        targetKey = target,
                        text = distinctTargets.single(),
                        status = ContextualTranslationResult.Status.TRANSLATED,
                    )
                }

                distinctTargets.size > 1 -> {
                    errors += "Conflicting duplicate id '" + id + "'"
                    // Keep an explicit duplicate marker without retaining conflicting output.
                    repeat(2) {
                        results += ContextualTranslationResult(
                            id = id,
                            targetKey = target,
                            text = "",
                            status = ContextualTranslationResult.Status.REJECTED,
                        )
                    }
                }

                else -> {
                    errors += "Blank required output for '" + id + "'"
                    results += ContextualTranslationResult(
                        id = id,
                        targetKey = target,
                        text = "",
                        status = ContextualTranslationResult.Status.REJECTED,
                    )
                }
            }
        }
        results += unknownResults

        val acceptedIds = results.asSequence()
            .filter { it.status == ContextualTranslationResult.Status.TRANSLATED && it.targetKey != null }
            .map { it.id }
            .toSet()
        val contentValid = errors.isEmpty() &&
            requestIds.all { it in acceptedIds } &&
            results.none { it.status == ContextualTranslationResult.Status.REJECTED }

        return ContextualTranslationBatch(
            idToBlockIndex = request.locations,
            results = results,
            strictValidation = true,
            protocolVersion = BatchTranslationProtocol.VERSION,
            contextDeltas = contextDeltas,
            validationErrors = errors.distinct(),
            framingRecovered = contentValid,
            salvagedIds = salvagedIds,
            parseAmbiguityCount = parseAmbiguityCount,
        )
    }

    private val batchCandidateLineRegex = Regex(
        """^\s*((?:[-*•]|\d+[.)])\s+)?(\d+)\s*(\||:|：|>|→|;|-|\s)\s*(.*?)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val ambiguousOrdinalLineRegex = Regex("""^\s*\d+[.)]\s+\S.*$""")

    private data class ParsedBatchCandidate(
        val rawId: String,
        val separator: String,
        val text: String,
        val salvaged: Boolean,
    )

    private fun normalizeCandidateTarget(target: String): String = java.text.Normalizer.normalize(
        target.replace("\r\n", "\n").replace('\r', '\n').trim(),
        java.text.Normalizer.Form.NFC,
    )

    private fun parseBatchCandidate(line: String): ParsedBatchCandidate? {
        val match = batchCandidateLineRegex.matchEntire(line) ?: return null
        return ParsedBatchCandidate(
            rawId = match.groupValues[2],
            separator = match.groupValues[3],
            text = match.groupValues[4].trim(),
            salvaged = match.groupValues[1].isNotEmpty() || match.groupValues[3] != "|",
        )
    }
}

fun applyBatchToChunk(
    chunk: TranslationContextChunk,
    batch: ContextualTranslationBatch,
) {
    val responseRefused = batch.validationErrors.any { it == STRUCTURAL_REFUSAL_DIAGNOSTIC } ||
        batch.results.any { result ->
            TranslationResponseFaithfulness.isStructuralRefusal(
                OcrArtifactSanitizer.sanitize(result.text),
            )
        }
    val accepted = if (responseRefused) {
        emptyList()
    } else {
        batch.accepted
    }
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
