package eu.kanade.translation.translator

object ContextualRequestBuilder {

    data class Request(
        val idMap: Map<String, AnchoredTargetKey>,
        val orderedIds: List<String>,
        val locations: Map<String, TargetLocation>,
        val promptLines: List<String>,
    )

    fun build(
        chunk: TranslationContextChunk,
        fromLang: eu.kanade.translation.ocr.TextRecognizerLanguage,
        toLang: TextTranslatorLanguage,
    ): Request {
        val idMap = LinkedHashMap<String, AnchoredTargetKey>()
        val orderedIds = mutableListOf<String>()
        val locations = HashMap<String, TargetLocation>()
        val promptLines = mutableListOf<String>()

        val orderedPages = chunk.pages.entries.toList()
        for ((pageIndex, entry) in orderedPages.withIndex()) {
            val (pageKey, page) = entry
            var blockSeq = 0
            for ((blockIndex, block) in page.blocks.withIndex()) {
                if (block.text.isBlank()) continue

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
        )
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
        )
    }
}
