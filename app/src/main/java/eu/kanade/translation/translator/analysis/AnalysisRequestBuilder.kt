package eu.kanade.translation.translator.analysis

import eu.kanade.translation.translator.contextual.PlannedAnalysisChunk

/**
 * T924 WP5 slice A (T924-AP-03): builds the versioned analysis chunk request
 * document. Provider-independent — one JSON document in the model's text
 * channel, identical for every backend (T924-AP-01.1).
 *
 * WAVE-2 REVIEW F4 / GAP-3 (BINDING): the `pages` array is emitted in
 * CORE-THEN-CONTEXT order — the contributing set order of
 * [PlannedAnalysisChunk.contributingPageKeys], which the S4 planner defines
 * and the persisted [eu.kanade.translation.artifact.AnalysisChunkResult]
 * fingerprints hash (`naturalOrderProven=true`). The request payload order
 * MUST reproduce that order exactly; it is pinned end-to-end by
 * `AnalysisRequestOrderTest`.
 *
 * Blocks inside a page keep the page's persisted OCR order (stable block ids
 * `p<N>_b<M>`); the request text is the persisted OCR text exactly as sent —
 * the request is the sole authority for what response evidence may reference
 * (T924-AP-05).
 */
object AnalysisRequestBuilder {

    /** Wire protocol identity (T924-AP-01.2 / T924-AP-03). */
    const val PROTOCOL = "tachiyomiat-analysis"

    /** The only accepted wire schema version in the first release (T924-AP-01.2). */
    const val SCHEMA_VERSION = 1

    /** Request kind discriminator: whole-page extraction chunks (T924-AP-03). */
    const val REQUEST_KIND_CHUNK = "CHUNK"

    /** Page roles: CORE pages owe primary records; CONTEXT pages are citation-only. */
    const val ROLE_CORE = "CORE"
    const val ROLE_CONTEXT = "CONTEXT"

    /** One request-page entry: wire page key, role, ordered block id/text pairs. */
    data class RequestPage(
        val pageKey: String,
        val role: String,
        val blocks: List<RequestBlock>,
    )

    data class RequestBlock(
        val blockId: String,
        val text: String,
        /**
         * T924-AP-05 V8 echo basis: `e:` + the first 16 hex chars of
         * [eu.kanade.translation.artifact.StageFingerprints.sourceExcerptHash]
         * over this block's source text, computed at request-build time. An
         * LLM cannot compute SHA-256 — the contract's anchor-integrity rule is
         * realizable only as an ECHO: the model copies the block's hash into
         * its evidence anchors and the validator recomputes locally, so an
         * invented/paraphrased anchor (a hash that does not match the cited
         * block) stays response-fatal (wave-7c).
         */
        val excerptHash: String,
    )

    /**
     * The built chunk request: the exact wire document plus the resolution
     * universe the response validator enforces.
     */
    data class AnalysisChunkRequest(
        val chunkId: String,
        /** The exact JSON document sent to the provider. */
        val requestJson: String,
        /** Contributing wire page keys in payload order (core-then-context). */
        val orderedPageKeys: List<String>,
        /** Wire page key -> ordered block ids (the evidence universe). */
        val blockIdsByPage: Map<String, List<String>>,
        /** Wire block id -> persisted source text (the V8 recompute input). */
        val textByBlockId: Map<String, String>,
    )

    /**
     * Builds the chunk request. `wirePages` MUST be ordered core-then-context;
     * the builder emits them verbatim and fails fast on a CORE page appearing
     * after a CONTEXT page (order violation = planner/builder contract bug,
     * never silently reordered).
     */
    fun buildChunkRequest(
        chunk: PlannedAnalysisChunk,
        pages: List<RequestPage>,
        identity: AnalysisRunIdentity,
        existingCanonChapterFacts: List<String> = emptyList(),
    ): AnalysisChunkRequest {
        require(pages.isNotEmpty()) { "chunk ${chunk.chunkId}: request needs at least one page" }
        var seenContext = false
        pages.forEach { page ->
            if (page.role == ROLE_CONTEXT) {
                seenContext = true
            } else {
                check(!seenContext) {
                    "chunk ${chunk.chunkId}: CORE page ${page.pageKey} appears after a CONTEXT page — " +
                        "contributing-set order must be core-then-context (wave-2 F4/gap-3)"
                }
            }
        }
        val document = buildString {
            append("{")
            // ---- envelope (T924-AP-03) ----
            append("\"envelope\":{")
            append("\"protocol\":\"$PROTOCOL\",")
            append("\"schemaVersion\":$SCHEMA_VERSION,")
            append("\"requestKind\":\"$REQUEST_KIND_CHUNK\",")
            append("\"chunkId\":\"${jsonEscape(chunk.chunkId)}\",")
            append("\"run\":{")
            append("\"mangaKeyHash\":\"${jsonEscape(identity.mangaKeyHash)}\",")
            append("\"chapterKeyHash\":\"${jsonEscape(identity.chapterKeyHash)}\",")
            append("\"runId\":\"${jsonEscape(identity.runId)}\"")
            append("},")
            append("\"languages\":{")
            append("\"source\":\"${jsonEscape(identity.sourceLanguage)}\",")
            append("\"target\":\"${jsonEscape(identity.targetLanguage)}\"")
            append("},")
            append("\"policy\":{")
            append("\"analysisPolicyFingerprint\":\"${jsonEscape(identity.analysisPolicyFingerprint)}\",")
            append("\"ocrCorpusFingerprint\":\"${jsonEscape(identity.ocrCorpusFingerprint)}\"")
            append("},")
            append("\"outputBudget\":{\"maxOutputTokens\":${identity.maxOutputTokens.coerceAtLeast(1)}}")
            append("},")
            // ---- pages: CONTRIBUTING ORDER (core-then-context, F4/gap-3) ----
            append("\"pages\":[")
            pages.forEachIndexed { pageIndex, page ->
                if (pageIndex > 0) append(",")
                append("{\"pageKey\":\"${jsonEscape(page.pageKey)}\",\"role\":\"${page.role}\",\"blocks\":[")
                page.blocks.forEachIndexed { blockIndex, block ->
                    if (blockIndex > 0) append(",")
                    append("{\"blockId\":\"${jsonEscape(block.blockId)}\",\"text\":\"${jsonEscape(block.text)}\",")
                    // T924-AP-05 V8 echo basis (wave-7c): the model copies
                    // this hash into its evidence anchors; the validator
                    // recomputes it locally from the same text.
                    append("\"excerptHash\":\"${jsonEscape(block.excerptHash)}\"}")
                }
                append("]}")
            }
            append("],")
            // ---- existingCanon: never model-invented facts (T924-AP-03) ----
            append("\"existingCanon\":{")
            append("\"userAuthority\":null,")
            append("\"seriesAuthority\":null,")
            append("\"chapterFacts\":[")
            existingCanonChapterFacts.forEachIndexed { index, fact ->
                if (index > 0) append(",")
                append("\"${jsonEscape(fact)}\"")
            }
            append("]")
            append("}")
            append("}")
        }
        return AnalysisChunkRequest(
            chunkId = chunk.chunkId,
            requestJson = document,
            orderedPageKeys = pages.map { it.pageKey },
            blockIdsByPage = pages.associate { it.pageKey to it.blocks.map { block -> block.blockId } },
            textByBlockId = pages.flatMap { page ->
                page.blocks.map { block -> block.blockId to block.text }
            }.toMap(),
        )
    }

    /** Minimal JSON string escaping (controls, quote, backslash). */
    private fun jsonEscape(value: String): String = buildString {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
				'\u000C' -> append("\\u000C")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
    }
}
