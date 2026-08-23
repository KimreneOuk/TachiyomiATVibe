package eu.kanade.translation.translator

/**
 * Versioned transport markers for chapter-batch AI requests and responses.
 *
 * The protocol is deliberately line-oriented. It works with providers that do not support a
 * formal response schema while still giving the response parser unambiguous section boundaries.
 * Context-delta sections are carried as opaque page-scoped data for the later context phase; this
 * phase does not interpret or persist their contents.
 */
object BatchTranslationProtocol {
    const val VERSION = 1

    const val REQUEST_HEADER = "TACHIYOMI_AT_BATCH_REQUEST v$VERSION"
    const val SOURCE_START = "BEGIN_SOURCE_DATA"
    const val SOURCE_END = "END_SOURCE_DATA"

    const val RESPONSE_HEADER = "TACHIYOMI_AT_BATCH_RESPONSE v$VERSION"
    const val RESPONSE_END = "END_TACHIYOMI_AT_BATCH_RESPONSE"
    const val PAGE_START = "BEGIN_PAGE"
    const val PAGE_END = "END_PAGE"
    const val CONTEXT_DELTA_START = "BEGIN_CONTEXT_DELTA"
    const val CONTEXT_DELTA_END = "END_CONTEXT_DELTA"

    fun pageId(naturalPageIndex: Int): String =
        "p${naturalPageIndex.toString().padStart(4, '0')}"

    fun blockId(naturalPageIndex: Int, stableBlockIndex: Int): String =
        "${pageId(naturalPageIndex)}_b${stableBlockIndex.toString().padStart(4, '0')}"

    /**
     * Renders the source side of a request. Source is fenced as inert data and every page has its
     * own section so a future response delta can be associated with exactly one natural page.
     */
    fun renderRequest(request: ContextualRequestBuilder.Request): String {
        require(request.protocol == ContextualRequestProtocol.BATCH_V1) {
            "Batch protocol rendering requires a batch request"
        }
        return request.promptLines.joinToString("\n")
    }

    /** True when [line] is a canonical page section marker. */
    fun isPageStart(line: String): Boolean = line.startsWith("$PAGE_START ")

    fun isPageEnd(line: String): Boolean = line.startsWith("$PAGE_END ")

    fun isContextDeltaStart(line: String): Boolean = line.startsWith("$CONTEXT_DELTA_START ")

    fun isContextDeltaEnd(line: String): Boolean = line.startsWith("$CONTEXT_DELTA_END ")
}
