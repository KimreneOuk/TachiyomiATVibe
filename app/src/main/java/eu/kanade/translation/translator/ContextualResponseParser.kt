package eu.kanade.translation.translator

data class ContextualTranslationResult(
    val id: String,
    val targetKey: AnchoredTargetKey?,
    val text: String,
    val status: Status,
) {
    enum class Status {
        TRANSLATED,
        REJECTED,
    }
}

data class AnchoredTargetKey(
    val pageIndex: Int,
    val blockIndex: Int,
)

object ContextualResponseParser {

    fun parse(
        rawLines: List<String>,
        idMap: Map<String, AnchoredTargetKey>,
    ): List<ContextualTranslationResult> {
        val results = mutableListOf<ContextualTranslationResult>()
        val seenIds = HashSet<String>()
        for (rawLine in rawLines) {
            val parsed = TranslationPrompts.parseLine(rawLine)
            if (parsed == null) {
                results += ContextualTranslationResult(
                    id = rawLine.trim().take(64),
                    targetKey = null,
                    text = "",
                    status = ContextualTranslationResult.Status.REJECTED,
                )
                continue
            }
            val id = parsed.id
            val target = idMap[id]
            if (target == null) {
                results += ContextualTranslationResult(
                    id = id,
                    targetKey = null,
                    text = parsed.text,
                    status = ContextualTranslationResult.Status.REJECTED,
                )
                continue
            }
            if (!seenIds.add(id)) {
                results += ContextualTranslationResult(
                    id = id,
                    targetKey = target,
                    text = parsed.text,
                    status = ContextualTranslationResult.Status.REJECTED,
                )
                continue
            }
            if (parsed.text.isBlank()) {
                results += ContextualTranslationResult(
                    id = id,
                    targetKey = target,
                    text = parsed.text,
                    status = ContextualTranslationResult.Status.REJECTED,
                )
                continue
            }
            results += ContextualTranslationResult(
                id = id,
                targetKey = target,
                text = parsed.text,
                status = ContextualTranslationResult.Status.TRANSLATED,
            )
        }
        return results
    }
}

fun applyBatchToChunk(
    chunk: TranslationContextChunk,
    batch: ContextualTranslationBatch,
) {
    val accepted = batch.accepted
    for (result in accepted) {
        val location = batch.idToBlockIndex[result.id] ?: continue
        val page = chunk.pages[location.pageKey] ?: continue
        val block = page.blocks.getOrNull(location.blockIndex) ?: continue
        if (block.userEditedAt != null) continue
        block.translation = OcrArtifactSanitizer.sanitize(result.text)
    }
    TranslationBlockFilters.removeWatermarkBlocks(chunk.pages)
}
