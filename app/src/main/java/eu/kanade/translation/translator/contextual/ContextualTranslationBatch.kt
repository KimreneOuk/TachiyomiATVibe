package eu.kanade.translation.translator.contextual

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
    /** True for the Phase 1 batch envelope; legacy reader responses remain permissive. */
    val strictValidation: Boolean = false,
    /** Protocol version carried by a strict response, or null for the legacy path. */
    val protocolVersion: Int? = null,
    /** Page-scoped opaque deltas reserved for the context-quality phase. */
    val contextDeltas: Map<String, String> = emptyMap(),
    /** Structural/cardinality errors found before any result may be promoted. */
    val validationErrors: List<String> = emptyList(),
    /** Exact requested IDs were recovered from a response with malformed or omitted framing. */
    val framingRecovered: Boolean = false,
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
            .filter { it.targetKey == null || it.id !in requestedIds }
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

    /**
     * A strict batch response is promotable only when every requested source block has exactly
     * one non-blank target and no unknown/extra/malformed protocol content was observed.
     */
    val isStructurallyValid: Boolean
        get() = !strictValidation ||
            (
                protocolVersion == BatchTranslationProtocol.VERSION &&
                    (validationErrors.isEmpty() || framingRecovered) &&
                    duplicateIds.isEmpty() &&
                    missingIds.isEmpty() &&
                    unknownIds.isEmpty() &&
                    rejected.isEmpty() &&
                    accepted.size == requestedIds.size
                )

    /**
     * Typed, privacy-safe failure information for the live retry controller. The parser keeps
     * detailed diagnostics for focused tests and local debugging, but callers must use this
     * summary when logging or reporting a malformed provider envelope.
     */
    val structuralFailure: ContextualStructuralFailure?
        get() = if (isStructurallyValid) {
            null
        } else {
            ContextualStructuralFailure(
                reasonCounts = buildReasonCounts(),
                expectedCount = requestedIds.size,
                receivedCount = results.size,
                missingCount = missingIds.size,
                duplicateCount = duplicateIds.size,
                unknownCount = unknownIds.size,
                rejectedCount = rejected.size,
            )
        }

    private fun buildReasonCounts(): Map<ContextualStructuralFailureCode, Int> {
        val counts = linkedMapOf<ContextualStructuralFailureCode, Int>()
        fun add(code: ContextualStructuralFailureCode, count: Int = 1) {
            if (count > 0) counts[code] = (counts[code] ?: 0) + count
        }

        validationErrors.forEach { error ->
            add(
                when {
                    error.startsWith("Missing batch response header") -> ContextualStructuralFailureCode.HEADER
                    error.startsWith("Missing batch response footer") -> ContextualStructuralFailureCode.FOOTER
                    error.contains("page section") -> ContextualStructuralFailureCode.PAGE_SECTION
                    error.contains("context delta") -> ContextualStructuralFailureCode.CONTEXT_DELTA_SECTION
                    error.contains("translation line") || error.contains("required output") ->
                        ContextualStructuralFailureCode.TRANSLATION_LINE
                    error.contains("id '") -> ContextualStructuralFailureCode.IDENTIFIER
                    else -> ContextualStructuralFailureCode.CONTENT
                },
            )
        }
        if (protocolVersion != null && protocolVersion != BatchTranslationProtocol.VERSION) {
            add(ContextualStructuralFailureCode.PROTOCOL_VERSION)
        } else if (strictValidation && protocolVersion == null) {
            add(ContextualStructuralFailureCode.PROTOCOL_VERSION)
        }
        add(ContextualStructuralFailureCode.CARDINALITY, missingIds.size)
        add(ContextualStructuralFailureCode.CARDINALITY, duplicateIds.size)
        add(ContextualStructuralFailureCode.CARDINALITY, unknownIds.size)
        add(ContextualStructuralFailureCode.CARDINALITY, rejected.size)
        if (strictValidation && accepted.size != requestedIds.size) {
            add(ContextualStructuralFailureCode.CARDINALITY)
        }
        return counts
    }

    companion object {
        val EMPTY = ContextualTranslationBatch(
            idToBlockIndex = emptyMap(),
            results = emptyList(),
        )
    }
}

enum class ContextualStructuralFailureCode {
    PROTOCOL_VERSION,
    HEADER,
    FOOTER,
    PAGE_SECTION,
    CONTEXT_DELTA_SECTION,
    IDENTIFIER,
    TRANSLATION_LINE,
    CONTENT,
    CARDINALITY,
}

/** A bounded structural-failure summary that contains no source or target text. */
data class ContextualStructuralFailure(
    val reasonCounts: Map<ContextualStructuralFailureCode, Int>,
    val expectedCount: Int,
    val receivedCount: Int,
    val missingCount: Int,
    val duplicateCount: Int,
    val unknownCount: Int,
    val rejectedCount: Int,
) {
    /** Safe for logs and user-facing failure reasons; never includes raw provider output. */
    fun safeSummary(): String {
        val reasons = reasonCounts.entries
            .sortedBy { it.key.name }
            .joinToString(",") { "${it.key.name.lowercase()}=${it.value}" }
        return "protocol structural failure (reasons=$reasons expected=$expectedCount " +
            "received=$receivedCount missing=$missingCount duplicate=$duplicateCount " +
            "unknown=$unknownCount rejected=$rejectedCount)"
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
