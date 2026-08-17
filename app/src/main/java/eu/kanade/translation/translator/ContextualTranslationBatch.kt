package eu.kanade.translation.translator

/**
 * TachiyomiAT: immutable, structured result of a single contextual translator
 * request (Pass 1 OR Pass 2). Replaces in-place [TranslationBlock] mutation so
 * the merge layer can re-check preconditions before applying any correction and
 * can log/account every rejected, missing, or duplicate id.
 *
 * A batch carries:
 *  - [idToBlockIndex]: maps each request-local id (`b0` Pass 1, `p0_b3` Pass 2)
 *    to the (pageKey, blockIndex) it refers to, in the order targets were added.
 *  - [results]: parsed per-id results, exactly one per model output line.
 *  - [preconditions]: snapshot of each target's draft/fingerprint/flags captured
 *    at request time, so a late response can detect a concurrent edit.
 */
data class ContextualTranslationBatch(
    /**
     * Ordered map from request-local id to the target's location. Order is the
     * order targets were added to the request (reading order within the chunk).
     */
    val idToBlockIndex: Map<String, TargetLocation>,
    val results: List<ContextualTranslationResult>,
    val preconditions: Map<String, TargetPrecondition>,
    val isPass2: Boolean,
) {
    init {
        require(idToBlockIndex.keys.all { it.isNotBlank() }) { "Contextual target ids must not be blank" }
        require(idToBlockIndex.keys.toList().distinct().size == idToBlockIndex.size) {
            "Contextual target ids must be unique"
        }
    }

    /** IDs the provider was required to return for this request. */
    val requestedIds: List<String>
        get() = idToBlockIndex.keys.toList()

    /** IDs returned more than once, including the accepted first occurrence. */
    val duplicateIds: List<String>
        get() = results.groupingBy { it.id }.eachCount()
            .filterValues { it > 1 }
            .keys
            .toList()

    /** Requested IDs omitted from the provider response. */
    val missingIds: List<String>
        get() = requestedIds.filterNot { id -> results.any { it.id == id } }

    /** Returned IDs which were not part of this request. */
    val unknownIds: List<String>
        get() = results.asSequence()
            .filter { it.targetKey == null }
            .map { it.id }
            .distinct()
            .toList()

    /** A stable accounting snapshot for logs and progress consumers. */
    val accounting: ContextualTranslationAccounting
        get() = ContextualTranslationAccounting(
            requested = requestedIds.size,
            translated = accepted.size,
            rejected = rejected.size,
            missing = missingIds.size,
            duplicates = duplicateIds.size,
            unknown = unknownIds.size,
        )

    /** Accepted (TRANSLATED) results only, in reading order. */
    val accepted: List<ContextualTranslationResult>
        get() = results.filter { it.status == ContextualTranslationResult.Status.TRANSLATED }

    /** Rejected (unknown/blank/malformed/duplicate) results only. */
    val rejected: List<ContextualTranslationResult>
        get() = results.filter { it.status == ContextualTranslationResult.Status.REJECTED }

    companion object {
        val EMPTY = ContextualTranslationBatch(
            idToBlockIndex = emptyMap(),
            results = emptyList(),
            preconditions = emptyMap(),
            isPass2 = false,
        )
    }
}

data class ContextualTranslationAccounting(
    val requested: Int,
    val translated: Int,
    val rejected: Int,
    val missing: Int,
    val duplicates: Int,
    val unknown: Int,
)

/** Where a target lives: page key + block index within that page's blocks list. */
data class TargetLocation(
    val pageKey: String,
    val blockIndex: Int,
)

/**
 * Snapshot of a target block captured at request time. The merge layer compares
 * these against the live block to reject stale/duplicate/edited results. All
 * fields must match for a correction to apply.
 */
data class TargetPrecondition(
    val draft: String,
    val fingerprint: String,
    val needsRevision: Boolean,
    val userEditedAt: Long?,
)
