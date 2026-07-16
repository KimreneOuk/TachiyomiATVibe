package eu.kanade.translation.translator

import eu.kanade.translation.model.stableFingerprint

/**
 * TachiyomiAT: converts a [RevisionPlanner.RequestGroup] into the prompt lines
 * and structured precondition map consumed by every revision adapter, then
 * assembles a [ContextualTranslationBatch] from the parser's results.
 *
 * This mirrors [ContextualRequestBuilder] but operates on the already-planned
 * group rather than scanning a live chunk, so the adapter never re-derives which
 * blocks are eligible for revision (the planner owns that decision).
 *
 * Prompt format (Pass-2 / revision):
 *   `p<pageIdx>_b<blockSeq>|Source: <source> | Draft: <draft>`
 *
 * Output format expected from the provider:
 *   `p<pageIdx>_b<blockSeq>|Corrected Text`
 *   (no [OK]/[FLAG] tags -- the merge layer clears the flag on acceptance)
 */
object RevisionRequestBuilder {

    /**
     * Opaque request snapshot returned to the adapter so it can call
     * [toRevisionBatch] after parsing the raw response.
     */
    data class RevisionRequest(
        /** Ordered id -> target location map (reading order). */
        val idMap: Map<String, AnchoredTargetKey>,
        val orderedIds: List<String>,
        val locations: Map<String, TargetLocation>,
        val preconditions: Map<String, TargetPrecondition>,
        /** One prompt line per revision target, in reading order. */
        val promptLines: List<String>,
        /** Nearby non-target dialogue serialized as context prefix lines. */
        val contextLines: List<String>,
        val glossaryLines: List<String>,
        val maxOutputTokens: Int,
    )

    /**
     * Build a [RevisionRequest] from [group].
     *
     * The planner already sorted and bounded targets; this function assigns
     * request-local anchored ids (`p<pageIdx>_b<blockSeq>`) in the same
     * reading order, captures precondition snapshots, and serializes the
     * prompt lines. Nearby context and glossary are serialized separately so
     * each adapter can prepend them to the user message in the format its
     * API expects.
     */
    fun build(group: RevisionPlanner.RequestGroup): RevisionRequest {
        val idMap = LinkedHashMap<String, AnchoredTargetKey>()
        val orderedIds = mutableListOf<String>()
        val locations = LinkedHashMap<String, TargetLocation>()
        val preconditions = HashMap<String, TargetPrecondition>()
        val promptLines = mutableListOf<String>()

        // Assign anchored ids in the order targets appear in the group (reading
        // order). The page index within the group is the target's position among
        // distinct pages so the id is unique and stable within this request.
        val pageOrder = group.targets.map { it.pageKey }.distinct()

        for ((blockSeq, target) in group.targets.withIndex()) {
            val pageIdx = pageOrder.indexOf(target.pageKey)
            val id = AnchoredBlockId.format(pageIdx, blockSeq)
            val key = AnchoredTargetKey(pageIdx, blockSeq)
            idMap[id] = key
            orderedIds += id
            locations[id] = TargetLocation(target.pageKey, target.blockIndex)
            preconditions[id] = TargetPrecondition(
                draft = target.block.translation,
                fingerprint = target.block.stableFingerprint(),
                needsRevision = target.block.needsRevision,
                userEditedAt = target.block.userEditedAt,
            )
            promptLines += "$id|Source: ${target.block.text} | Draft: ${target.block.translation}"
        }

        // Nearby context: non-target source/draft pairs for coherence.
        val contextLines = group.nearbyContext.map { dl ->
            "Context|Source: ${dl.source} | Draft: ${dl.draft}"
        }

        // Glossary lines for provider user-message injection.
        val glossaryLines = group.chapterGlossary.map { (k, v) -> "$k -> $v" }

        return RevisionRequest(
            idMap = idMap,
            orderedIds = orderedIds,
            locations = locations,
            preconditions = preconditions,
            promptLines = promptLines,
            contextLines = contextLines,
            glossaryLines = glossaryLines,
            maxOutputTokens = group.maxOutputTokens,
        )
    }

    /**
     * Assemble a [ContextualTranslationBatch] from a [RevisionRequest] and its
     * parsed [results]. Always sets `isPass2 = true` so downstream merge layers
     * apply the strict K/C/U accounting path (never [OK]/[FLAG] tag logic).
     */
    fun toRevisionBatch(
        request: RevisionRequest,
        results: List<ContextualTranslationResult>,
    ): ContextualTranslationBatch {
        val ordered = LinkedHashMap<String, TargetLocation>()
        request.orderedIds.forEach { id ->
            request.locations[id]?.let { ordered[id] = it }
        }
        return ContextualTranslationBatch(
            idToBlockIndex = ordered.toMap(),
            results = results.toList(),
            preconditions = request.preconditions.toMap(),
            isPass2 = true,
        )
    }

    /**
     * Build the full user-message body for a revision request. Adapters call
     * this to get the combined string they post as the user turn.
     *
     * Prepends any glossary and nearby-context lines before the revision
     * targets so the model sees terminology and surrounding dialogue before
     * it processes the blocks it must correct.
     */
    fun buildUserMessage(request: RevisionRequest): String {
        val sb = StringBuilder()
        if (request.glossaryLines.isNotEmpty()) {
            sb.append("Established terms (reuse these exact renderings):\n")
            request.glossaryLines.forEach { sb.append(it).append('\n') }
            sb.append('\n')
        }
        if (request.contextLines.isNotEmpty()) {
            sb.append("Surrounding context (do not output these):\n")
            request.contextLines.forEach { sb.append(it).append('\n') }
            sb.append('\n')
        }
        request.promptLines.forEach { sb.append(it).append('\n') }
        return sb.toString().trimEnd()
    }
}
