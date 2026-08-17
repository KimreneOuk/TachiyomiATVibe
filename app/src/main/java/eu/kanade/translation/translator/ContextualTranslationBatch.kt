package eu.kanade.translation.translator

/**
 * TachiyomiAT: immutable, structured result of a single contextual translator request.
 * Replaces in-place [TranslationBlock] mutation so caller can log/account every rejected,
 * missing, or duplicate id.
 */
data class ContextualTranslationBatch(
    /**
     * Ordered map from request-local id to the target's location. Order is the
     * order targets were added to the request (reading order within the chunk).
     */
    val idToBlockIndex: Map<String, TargetLocation>,
    val results: List<ContextualTranslationResult>,
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
