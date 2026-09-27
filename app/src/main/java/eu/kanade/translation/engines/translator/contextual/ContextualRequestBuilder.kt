package eu.kanade.translation.engines.translator.contextual
import eu.kanade.translation.engines.translator.TextTranslatorLanguage

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
    )

    /**
     * Builds the strict, versioned request used by chapter-batch AI translation.
     *
     * Page identity is supplied by [TranslationContextChunk.pageIndexes] when the caller has
     * natural chapter order available. The fallback is only for pure/unit callers that construct
     * a chunk without chapter metadata; the live batch path always supplies the map.
     */
    fun build(
        chunk: TranslationContextChunk,
        fromLang: eu.kanade.translation.engines.vision.ocr.TextRecognizerLanguage,
        toLang: TextTranslatorLanguage,
    ): Request {
        val idMap = LinkedHashMap<String, AnchoredTargetKey>()
        val orderedIds = mutableListOf<String>()
        val locations = HashMap<String, TargetLocation>()
        val promptLines = mutableListOf<String>()
        val pageOrder = chunk.pages.keys.toList()
        val pageIndexes = naturalPageIndexes(chunk)
        val pagePromptLines = LinkedHashMap<String, List<String>>()

        for ((pageOrdinal, entry) in chunk.pages.entries.withIndex()) {
            val (pageKey, page) = entry
            val naturalPageIndex = pageIndexes[pageKey] ?: pageOrdinal
            val pageLines = mutableListOf<String>()
            val stableIndexes = stableBlockIndexes(page.blocks)
            for ((blockIndex, block) in page.blocks.withIndex()) {
                if (block.text.isBlank()) continue

                val stableBlockIndex = stableIndexes[blockIndex]
                    ?: error("Missing stable block index for $pageKey/$blockIndex")
                val id = BatchTranslationProtocol.blockId(naturalPageIndex, stableBlockIndex)
                val target = AnchoredTargetKey(naturalPageIndex, stableBlockIndex)
                check(id !in idMap) {
                    "Duplicate stable batch block id $id for page $pageKey"
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
        )
    }

    /** Builds the request used for reader single-page translation. */
    fun buildLegacy(
        chunk: TranslationContextChunk,
        fromLang: eu.kanade.translation.engines.vision.ocr.TextRecognizerLanguage,
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
        )
    }

    fun buildFor(
        chunk: TranslationContextChunk,
        fromLang: eu.kanade.translation.engines.vision.ocr.TextRecognizerLanguage,
        toLang: TextTranslatorLanguage,
    ): Request = when (chunk.protocol) {
        ContextualRequestProtocol.BATCH_V1 -> build(chunk, fromLang, toLang)
        ContextualRequestProtocol.LEGACY -> buildLegacy(chunk, fromLang, toLang)
    }

    fun renderPrompt(
        request: Request,
        rollingContext: String,
        extraGlossary: String = "",
    ): String {
        val contextPrefix = TranslationPrompts.contextPrefix(rollingContext, extraGlossary)
        val requestBody = request.promptLines.joinToString("\n")
        return if (contextPrefix.isEmpty()) requestBody else contextPrefix + requestBody
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
        val used = mutableSetOf<Int>()
        var nextFallback = chunk.pageIndexes.values.maxOrNull()
            ?.let { if (it == Int.MAX_VALUE) 0 else it + 1 }
            ?: 0
        return LinkedHashMap<String, Int>().apply {
            chunk.pages.keys.forEach { pageKey ->
                val supplied = chunk.pageIndexes[pageKey]
                val index = if (supplied != null && supplied >= 0 && used.add(supplied)) {
                    supplied
                } else {
                    while (nextFallback in used) {
                        nextFallback = if (nextFallback == Int.MAX_VALUE) 0 else nextFallback + 1
                    }
                    val fallback = nextFallback
                    used += fallback
                    nextFallback = if (nextFallback == Int.MAX_VALUE) 0 else nextFallback + 1
                    fallback
                }
                put(pageKey, index)
            }
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
