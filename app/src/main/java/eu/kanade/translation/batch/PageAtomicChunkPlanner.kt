package eu.kanade.translation.batch

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.translator.TranslationContextChunk
import eu.kanade.translation.translator.TranslationContextChunkPlanner

/**
 * Chooses the largest natural-order whole-page prefix that fits one contextual
 * provider envelope. A single oversized page is returned as an exceptional
 * logical chunk with all transport slices so callers can recover without
 * letting later pages overtake it.
 */
object PageAtomicChunkPlanner {

    data class Plan(
        val pageKeys: List<String>,
        val transportChunks: List<TranslationContextChunk>,
        val rejectedPages: Map<String, String>,
        val oversizedSinglePage: Boolean,
    )

    fun plan(
        pages: LinkedHashMap<String, PageTranslation>,
        requestedOutputTokens: Int,
        profile: TranslationContextChunkPlanner.Profile,
        maxBlocksPerRequest: Int,
        maxPagesPerRequest: Int,
        pageIndexes: Map<String, Int> = emptyMap(),
    ): Plan {
        if (pages.isEmpty()) {
            return Plan(emptyList(), emptyList(), emptyMap(), oversizedSinglePage = false)
        }

        val accepted = linkedMapOf<String, PageTranslation>()
        var acceptedResult = TranslationContextChunkPlanner.Result(emptyList(), emptyMap())

        for ((pageKey, page) in pages) {
            val candidate = LinkedHashMap(accepted)
            candidate[pageKey] = page
            val result = TranslationContextChunkPlanner.plan(
                pages = candidate,
                requestedOutputTokens = requestedOutputTokens,
                profile = profile,
                maxBlocksPerChunk = maxBlocksPerRequest,
                maxPagesPerChunk = maxPagesPerRequest,
                pageIndexes = pageIndexes,
            )
            val fitsOneRequest = result.chunks.size <= 1 && result.rejectedPages.isEmpty()
            if (fitsOneRequest) {
                accepted[pageKey] = page
                acceptedResult = result
                continue
            }

            if (accepted.isEmpty()) {
                return Plan(
                    pageKeys = listOf(pageKey),
                    transportChunks = result.chunks,
                    rejectedPages = result.rejectedPages,
                    oversizedSinglePage = true,
                )
            }
            break
        }

        return Plan(
            pageKeys = accepted.keys.toList(),
            transportChunks = acceptedResult.chunks,
            rejectedPages = acceptedResult.rejectedPages,
            oversizedSinglePage = false,
        )
    }
}
