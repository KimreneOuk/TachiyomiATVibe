package eu.kanade.translation.translator

import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.stableFingerprint

/**
 * TachiyomiAT: shared helper that builds the request-local id map and target
 * preconditions for a contextual translation request, so every provider can
 * mirror the same structured-result pattern without duplicating the eligibility
 * + snapshot logic.
 *
 * Pass 1 ids are `b<index>` (per-chunk indices, as the prompt examples show).
 * Pass 2 ids are anchored `p<pageIndex>_b<blockIndex>` so a correction can be
 * mapped back to its target block across the request's pages.
 *
 * Eligibility:
 *  - Pass 1: every non-blank source block.
 *  - Pass 2: non-blank source blocks with `needsRevision == true` and
 *    `userEditedAt == null` (a user edit wins; the block is skipped).
 */
object ContextualRequestBuilder {

    data class Request(
        val idMap: Map<String, AnchoredTargetKey>,
        /** Ordered id list in the same order blocks are added (prompt line order). */
        val orderedIds: List<String>,
        val locations: Map<String, TargetLocation>,
        val preconditions: Map<String, TargetPrecondition>,
        val promptLines: List<String>,
    )

    fun build(
        chunk: TranslationContextChunk,
        isPass2: Boolean,
        fromLang: eu.kanade.translation.ocr.TextRecognizerLanguage,
        toLang: TextTranslatorLanguage,
    ): Request {
        val idMap = LinkedHashMap<String, AnchoredTargetKey>()
        val orderedIds = mutableListOf<String>()
        val locations = HashMap<String, TargetLocation>()
        val preconditions = HashMap<String, TargetPrecondition>()
        val promptLines = mutableListOf<String>()

        val orderedPages = chunk.pages.entries.toList()
        for ((pageIndex, entry) in orderedPages.withIndex()) {
            val (pageKey, page) = entry
            var blockSeq = 0
            for ((blockIndex, block) in page.blocks.withIndex()) {
                if (block.text.isBlank()) continue
                val eligible = if (isPass2) {
                    block.needsRevision && block.userEditedAt == null
                } else {
                    true
                }
                if (!eligible) continue

                val id = if (isPass2) {
                    AnchoredBlockId.format(pageIndex, blockSeq)
                } else {
                    "b$blockSeq"
                }
                val target = AnchoredTargetKey(pageIndex, blockSeq)
                idMap[id] = target
                orderedIds += id
                locations[id] = TargetLocation(pageKey, blockIndex)
                preconditions[id] = TargetPrecondition(
                    draft = block.translation,
                    fingerprint = block.stableFingerprint(),
                    needsRevision = block.needsRevision,
                    userEditedAt = block.userEditedAt,
                )
                promptLines += if (isPass2) {
                    "$id|Source: ${block.text} | Draft: ${block.translation}"
                } else {
                    TranslationPrompts.idMappedSourceLine(id, block)
                }
                blockSeq += 1
            }
        }

        return Request(
            idMap = idMap,
            orderedIds = orderedIds,
            locations = locations,
            preconditions = preconditions,
            promptLines = promptLines,
        )
    }

    /**
     * Resolve [ContextualResponseParser] results into a [ContextualTranslationBatch],
     * carrying the precondition snapshots so the merge layer can detect staleness.
     * [ContextualTranslationBatch.idToBlockIndex] is the id -> [TargetLocation]
     * map (preserving reading order), enabling correction -> block resolution.
     */
    fun toBatch(
        request: Request,
        results: List<ContextualTranslationResult>,
        isPass2: Boolean,
    ): ContextualTranslationBatch {
        val ordered = LinkedHashMap<String, TargetLocation>()
        request.orderedIds.forEach { id ->
            request.locations[id]?.let { ordered[id] = it }
        }
        return ContextualTranslationBatch(
            idToBlockIndex = ordered.toMap(),
            results = results.toList(),
            preconditions = request.preconditions.toMap(),
            isPass2 = isPass2,
        )
    }
}
