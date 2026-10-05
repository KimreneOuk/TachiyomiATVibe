package eu.kanade.translation.engines.translator.contextual
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage

object ContextualRequestBuilder {
    private val stableBlockIdRegex = Regex("(?:p\\d+_)?b(\\d+)")

    data class Request(
        val idMap: Map<String, AnchoredTargetKey>,
        val orderedIds: List<String>,
        val locations: Map<String, TargetLocation>,
        val promptLines: List<String>,
        val protocol: ContextualRequestProtocol = ContextualRequestProtocol.LEGACY,
        val pageOrder: List<String> = emptyList(),
        val pageIndexes: Map<String, Int> = emptyMap(),
        val pagePromptLines: Map<String, List<String>> = emptyMap(),
        val correctionLine: String? = null,
    )

    /**
     * Builds the strict, versioned request used by chapter-batch AI translation.
     *
     * Page identity is supplied by [TranslationContextChunk.pageIndexes] from the accepted PLAN.
     * Batch requests fail closed when a page lacks a valid unique PLAN index; the chunk's map
     * iteration order must never become a replacement namespace.
     */
    fun build(
        chunk: TranslationContextChunk,
        fromLang: TextRecognizerLanguage,
        toLang: TextTranslatorLanguage,
    ): Request {
        val idMap = LinkedHashMap<String, AnchoredTargetKey>()
        val orderedIds = mutableListOf<String>()
        val locations = LinkedHashMap<String, TargetLocation>()
        val promptLines = mutableListOf<String>()
        val computedPageIndexes = naturalPageIndexes(chunk)
        val pageOrder = computedPageIndexes.keys.toList()
        val pageIndexes = LinkedHashMap<String, Int>().apply {
            pageOrder.forEach { pageKey -> put(pageKey, computedPageIndexes.getValue(pageKey)) }
        }
        val pagePromptLines = LinkedHashMap<String, List<String>>()
        var nextRequestId = 1

        // Page indexes are frozen from the validated PLAN. Live map iteration may have changed
        // after the plan was captured, so it must not define the model-visible namespace.
        for (pageKey in pageOrder) {
            val page = chunk.pages[pageKey] ?: continue
            val naturalPageIndex = pageIndexes.getValue(pageKey)
            val pageLines = mutableListOf<String>()
            val stableIndexes = stableBlockIndexes(page.blocks)
            // The batch executor reconstructs each page's blocks in the accepted PLAN order.
            // Stable IDs identify destinations, but sorting by them here would silently replace
            // that frozen order with geometry/persisted-ID order.
            for ((blockIndex, block) in page.blocks.withIndex()) {
                if (block.text.isBlank()) continue

                val stableBlockIndex = stableIndexes[blockIndex]
                    ?: error("Missing stable block index for $pageKey/$blockIndex")
                val id = nextRequestId.toString()
                nextRequestId += 1
                val target = AnchoredTargetKey(naturalPageIndex, stableBlockIndex)
                check(id !in idMap) {
                    "Duplicate request-local batch id $id for page $pageKey"
                }
                idMap[id] = target
                orderedIds += id
                locations[id] = TargetLocation(pageKey, blockIndex)
                val line = TranslationPrompts.idMappedSourceLine(id, block)
                promptLines += line
                pageLines += line
            }
            pagePromptLines[pageKey] = pageLines
        }

        return Request(
            idMap = idMap,
            orderedIds = orderedIds,
            locations = locations,
            promptLines = promptLines,
            protocol = ContextualRequestProtocol.BATCH_V1,
            pageOrder = pageOrder,
            pageIndexes = pageIndexes,
            pagePromptLines = pagePromptLines,
            correctionLine = TranslationPrompts.correctionLine(chunk.correctionHint, toLang),
        )
    }

    /** Builds the request used for reader single-page translation. */
    fun buildLegacy(
        chunk: TranslationContextChunk,
        fromLang: TextRecognizerLanguage,
        toLang: TextTranslatorLanguage,
    ): Request {
        val idMap = LinkedHashMap<String, AnchoredTargetKey>()
        val orderedIds = mutableListOf<String>()
        val locations = HashMap<String, TargetLocation>()
        val promptLines = mutableListOf<String>()

        chunk.pages.entries.forEachIndexed { pageIndex, (pageKey, page) ->
            var blockSeq = 0
            page.blocks.forEachIndexed { blockIndex, block ->
                if (block.text.isBlank()) return@forEachIndexed
                val id = "b$blockSeq"
                val target = AnchoredTargetKey(pageIndex, blockSeq)
                idMap[id] = target
                orderedIds += id
                locations[id] = TargetLocation(pageKey, blockIndex)
                promptLines += TranslationPrompts.idMappedSourceLine(id, block)
                blockSeq += 1
            }
        }

        return Request(
            idMap = idMap,
            orderedIds = orderedIds,
            locations = locations,
            promptLines = promptLines,
            protocol = ContextualRequestProtocol.LEGACY,
            pageOrder = chunk.pages.keys.toList(),
            correctionLine = TranslationPrompts.correctionLine(chunk.correctionHint, toLang),
        )
    }

    fun buildFor(
        chunk: TranslationContextChunk,
        fromLang: TextRecognizerLanguage,
        toLang: TextTranslatorLanguage,
    ): Request = when (chunk.protocol) {
        ContextualRequestProtocol.BATCH_V1 -> build(chunk, fromLang, toLang)
        ContextualRequestProtocol.LEGACY -> buildLegacy(chunk, fromLang, toLang)
    }

    fun renderPrompt(
        request: Request,
        rollingContext: String,
    ): String {
        val requestBody = request.promptLines.joinToString("\n")
        val contextPrefix = TranslationPrompts.contextPrefix(rollingContext)
        val correction = request.correctionLine?.let { "CORRECTIVE INSTRUCTION:\n$it\n\n" }.orEmpty()
        return contextPrefix + correction + "SOURCE ITEMS:\n" + requestBody
    }

    fun toBatch(
        request: Request,
        results: List<ContextualTranslationResult>,
    ): ContextualTranslationBatch {
        val ordered = LinkedHashMap<String, TargetLocation>()
        request.orderedIds.forEach { id ->
            request.locations[id]?.let { ordered[id] = it }
        }
        return ContextualTranslationBatch(
            idToBlockIndex = ordered.toMap(),
            results = results.toList(),
            strictValidation = request.protocol == ContextualRequestProtocol.BATCH_V1,
            protocolVersion = if (request.protocol == ContextualRequestProtocol.BATCH_V1) {
                BatchTranslationProtocol.VERSION
            } else {
                null
            },
        )
    }

    private fun naturalPageIndexes(chunk: TranslationContextChunk): LinkedHashMap<String, Int> {
        val indexes = chunk.pages.keys.map { pageKey ->
            val index = requireNotNull(chunk.pageIndexes[pageKey]) {
                "Batch request requires a valid frozen PLAN index for page $pageKey"
            }
            require(index >= 0) {
                "Batch request requires a valid frozen PLAN index for page $pageKey"
            }
            pageKey to index
        }
        require(indexes.map { it.second }.distinct().size == indexes.size) {
            "Batch request requires unique frozen PLAN page indexes"
        }
        return LinkedHashMap<String, Int>().apply {
            indexes.sortedBy { it.second }.forEach { (pageKey, index) -> put(pageKey, index) }
        }
    }

    /**
     * Returns stable local block indexes. Persisted block IDs win; otherwise the index is derived
     * from OCR-region geometry rather than the current (possibly RTL/LTR-sorted) list order.
     */
    private fun stableBlockIndexes(
        blocks: List<eu.kanade.translation.model.TranslationBlock>,
    ): Map<Int, Int> {
        val sorted = blocks.withIndex().sortedWith(
            compareBy<IndexedValue<eu.kanade.translation.model.TranslationBlock>> {
                it.value.y
            }.thenBy { it.value.x }
                .thenBy { it.value.width }
                .thenBy { it.value.height }
                .thenBy { it.index },
        )
        val next = sorted.mapIndexed { stableIndex, indexed -> indexed.index to stableIndex }
            .toMap()
        val persisted = blocks.mapNotNull { block ->
            block.blockId
                ?.let(stableBlockIdRegex::matchEntire)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
        }
        if (persisted.size != blocks.count { it.blockId != null } || persisted.toSet().size != persisted.size) {
            return next
        }
        return blocks.withIndex().associate { indexed ->
            val parsed = indexed.value.blockId
                ?.let(stableBlockIdRegex::matchEntire)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            indexed.index to (parsed ?: next.getValue(indexed.index))
        }
    }
}
