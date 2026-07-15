package eu.kanade.translation.translator

/**
 * TachiyomiAT: request-local anchored block IDs used by contextual translators.
 *
 * The Pass-1 prompt uses per-chunk indices `b0..bN` because the chunk's blocks
 * are unrelated to any persisted block id. Pass 2 (revision) needs stable,
 * page-scoped ids so the planner can map a correction back to its target block
 * without relying on a persisted block-id that never existed. The form is
 * `p<pageIndex>_b<blockIndex>`, e.g. `p0_b3` = the 4th target block on the 1st
 * page of the request. This is purely request-local; nothing is persisted.
 */
object AnchoredBlockId {

    /** Regex matching the canonical anchored id `p<digits>_b<digits>`. */
    val pattern: Regex = Regex("""p(\d+)_b(\d+)""")

    /** True when [raw] matches the canonical anchored id form. */
    fun isAnchored(raw: String): Boolean = pattern.matches(raw.trim())

    /** Build an anchored id from its parts. */
    fun format(pageIndex: Int, blockIndex: Int): String = "p${pageIndex}_b${blockIndex}"
}

/**
 * Result of resolving a single model output line against the request's id map.
 *
 * A line whose id is unknown, whose payload is blank, or which is malformed is
 * returned as a [Status.Rejected] so the caller can log it and account for it
 * without losing the rest of the batch. Only an [Status.Translated] result may
 * overwrite an existing draft.
 */
data class ContextualTranslationResult(
    /** Original (request-local) id from the model line, e.g. `p0_b3` or `b0`. */
    val id: String,
    /** Resolved target key, or null when the id is unknown/malformed. */
    val targetKey: AnchoredTargetKey?,
    val text: String,
    val status: Status,
    /** Explicit revision tag parsed from `[OK]` / `[FLAG]`; null = absent. */
    val qualityTag: QualityTag? = null,
) {
    enum class Status {
        /** Valid, non-blank translation. May carry an explicit [qualityTag]. */
        TRANSLATED,
        /** Unknown id, blank, malformed, or duplicate — draft must be retained. */
        REJECTED,
    }

    enum class QualityTag { OK, FLAG }
}

/**
 * Identifies a target block within a contextual request: the page's position in
 * the request and the block's position among the request's eligible blocks.
 * Stable for the lifetime of a single provider request.
 */
data class AnchoredTargetKey(
    val pageIndex: Int,
    val blockIndex: Int,
)

/**
 * TachiyomiAT: parses a contextual translator's raw response into structured,
 * per-id [ContextualTranslationResult]s WITHOUT mutating any block.
 *
 * Rules enforced here (Checkpoint 2 §4):
 *  - Only unique, valid, non-blank ids become [Status.TRANSLATED].
 *  - A duplicate id is rejected as ambiguous for that id (the first occurrence
 *    wins; the second is dropped) so a single id can never silently overwrite a
 *    draft via two competing translations.
 *  - Unknown / malformed / blank payload ids are rejected, never applied.
 *  - A valid non-blank line without `[OK]`/`[FLAG]` is accepted but auto-flagged
 *    for revision (the caller records `needsRevision = true`).
 *  - Pass 2 (revision) lines never carry a tag; the corrected text is accepted
 *    and the draft flag cleared by the merge layer.
 *
 * Pure and side-effect-free so it is unit-testable without a translator.
 */
object ContextualResponseParser {

    /**
     * @param rawLines the model's output lines (split on newline by the caller)
     * @param idMap maps each request-local id (e.g. `b0` or `p0_b3`) to its
     *   target key. Unknown ids are rejected. For Pass 2 the caller builds an
     *   anchored id map; for Pass 1 a plain `b<index>` map.
     * @param isPass2 when true, the `[OK]`/`[FLAG]` tags are NOT expected and a
     *   tagless valid line clears the revision flag; when false, a tagless valid
     *   line is auto-flagged for revision.
     */
    fun parse(
        rawLines: List<String>,
        idMap: Map<String, AnchoredTargetKey>,
        isPass2: Boolean,
    ): List<ContextualTranslationResult> {
        val results = mutableListOf<ContextualTranslationResult>()
        val seenIds = HashSet<String>()
        for (rawLine in rawLines) {
            val parsed = TranslationPrompts.parseLine(rawLine)
            if (parsed == null) {
                // Malformed line: record a rejection so it can be logged/accounted.
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
                // Unknown id (not part of this request): reject, never apply.
                results += ContextualTranslationResult(
                    id = id,
                    targetKey = null,
                    text = parsed.text,
                    status = ContextualTranslationResult.Status.REJECTED,
                )
                continue
            }
            // Duplicate id: the first translation wins; a second is ambiguous and
            // must not overwrite the draft. Reject the duplicate explicitly.
            if (!seenIds.add(id)) {
                results += ContextualTranslationResult(
                    id = id,
                    targetKey = target,
                    text = parsed.text,
                    status = ContextualTranslationResult.Status.REJECTED,
                )
                continue
            }
            // Blank payload: a blank translation cannot overwrite a draft.
            if (parsed.text.isBlank()) {
                results += ContextualTranslationResult(
                    id = id,
                    targetKey = target,
                    text = parsed.text,
                    status = ContextualTranslationResult.Status.REJECTED,
                )
                continue
            }
            // Resolve the quality tag. Pass 2 lines are tagless; an explicit tag
            // is tolerated but ignored (the merge clears the flag on accept).
            val tag: ContextualTranslationResult.QualityTag? = when {
                parsed.needsRevision == true -> ContextualTranslationResult.QualityTag.FLAG
                parsed.needsRevision == false -> ContextualTranslationResult.QualityTag.OK
                else -> null
            }
            results += ContextualTranslationResult(
                id = id,
                targetKey = target,
                text = parsed.text,
                status = ContextualTranslationResult.Status.TRANSLATED,
                qualityTag = tag,
            )
        }
        return results
    }

    /**
     * True when a TRANSLATED result should mark the block as needing revision.
     *
     * Pass 1: an explicit `[FLAG]` flags it; a tagless valid line is auto-flagged
     * (the model gave no confidence signal). An explicit `[OK]` clears it.
     * Pass 2: the corrected draft is authoritative, so the flag is always cleared.
     */
    fun shouldFlagForRevision(
        result: ContextualTranslationResult,
        isPass2: Boolean,
    ): Boolean {
        if (result.status != ContextualTranslationResult.Status.TRANSLATED) return true
        if (isPass2) return false
        return when (result.qualityTag) {
            ContextualTranslationResult.QualityTag.OK -> false
            ContextualTranslationResult.QualityTag.FLAG -> true
            null -> true // tagless valid -> auto-flag for revision
        }
    }
}

/**
 * TachiyomiAT: legacy in-place applier used by providers whose
 * `translateContextual` mutates blocks directly (backward compatibility with the
 * Pass-1 streaming path that reads `block.translation` after the call). The
 * strict Pass-2 merge layer does NOT use this; it applies via [RevisionMerger]
 * with full precondition re-checks.
 *
 * Applies only TRANSLATED results whose target block is still at its expected
 * location. Sanitizes the text, sets `needsRevision` per
 * [ContextualResponseParser.shouldFlagForRevision], and never overwrites a
 * user-edited block. Watermark blocks are filtered afterwards.
 */
fun applyBatchToChunk(
    chunk: TranslationContextChunk,
    batch: ContextualTranslationBatch,
    isPass2: Boolean,
) {
    val accepted = batch.accepted
    for (result in accepted) {
        val location = batch.idToBlockIndex[result.id] ?: continue
        val page = chunk.pages[location.pageKey] ?: continue
        val block = page.blocks.getOrNull(location.blockIndex) ?: continue
        if (block.userEditedAt != null) continue
        block.translation = OcrArtifactSanitizer.sanitize(result.text)
        block.needsRevision = ContextualResponseParser.shouldFlagForRevision(result, isPass2)
    }
    TranslationBlockFilters.removeWatermarkBlocks(chunk.pages)
}
