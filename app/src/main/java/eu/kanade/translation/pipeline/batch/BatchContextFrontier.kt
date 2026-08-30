package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.translator.TranslationContextChunkPlanner

/**
 * Natural-order rolling-context state for a fragmented batch resume.
 *
 * Only a contiguous prefix of terminal predecessors can seed context. Pages
 * already complete after a missing predecessor stay available for reuse, but
 * they never move the context frontier backward or leak into an earlier page's
 * request. A non-textless terminal failure records a gap and blocks later AI
 * work until a subsequent run resolves that gap.
 */
class BatchContextFrontier(
    naturalPageIndexes: Map<String, Int>,
) {
    private val indexes = naturalPageIndexes.toMap()
    private val completed = linkedMapOf<Int, Pair<String, PageTranslation>>()
    private val failures = mutableSetOf<Int>()

    var rollingContext: String = ""
        private set

    var frontierIndex: Int = -1
        private set

    var gapIndex: Int? = null
        private set

    fun seed(
        pages: Map<String, PageTranslation>,
        eligible: (pageKey: String, page: PageTranslation) -> Boolean = { _, _ -> true },
        terminalFailure: (pageKey: String, page: PageTranslation) -> Boolean = { _, page ->
            page.translationStatus == StageStatus.FAILED
        },
    ) {
        for ((pageKey, _) in indexes.entries.sortedBy { it.value }) {
            val page = pages[pageKey] ?: break
            if (!eligible(pageKey, page)) break
            if (!isTerminal(page)) break
            record(pageKey, page, terminalFailure = terminalFailure(pageKey, page))
            if (gapIndex != null) break
        }
    }

    fun record(
        pageKey: String,
        page: PageTranslation,
        terminalFailure: Boolean = false,
    ) {
        val pageIndex = indexes[pageKey] ?: return
        // PARTIAL output is displayable but not a complete source=>target pair. Do
        // not let it advance the rolling context frontier or seed a later request.
        // A terminal failure still records a gap so later AI work remains fenced.
        if (!isContextReady(page)) {
            if (terminalFailure && !isTextless(page)) {
                failures += pageIndex
                if (gapIndex == null && pageIndex == frontierIndex + 1) {
                    gapIndex = pageIndex
                }
            }
            return
        }
        completed[pageIndex] = pageKey to page.detachedCopy()
        if (terminalFailure && !isTextless(page)) failures += pageIndex
        if (gapIndex != null) return

        while (true) {
            val nextIndex = frontierIndex + 1
            if (nextIndex in failures) {
                gapIndex = nextIndex
                return
            }
            val next = completed[nextIndex] ?: return
            rollingContext = TranslationContextChunkPlanner.updateRollingContext(
                rollingContext,
                mapOf(next.first to next.second),
            )
            completed.remove(nextIndex)
            frontierIndex = nextIndex
        }
    }

    fun blocksLaterAi(pageKey: String): Boolean {
        val gap = gapIndex ?: return false
        return (indexes[pageKey] ?: Int.MAX_VALUE) > gap
    }

    private fun isTerminal(page: PageTranslation): Boolean =
        page.ocrStatus in setOf(StageStatus.READY, StageStatus.TEXTLESS) &&
            (
                page.translationStatus == StageStatus.READY ||
                    page.translationStatus == StageStatus.SKIPPED ||
                    page.translationStatus == StageStatus.FAILED
                )

    private fun isTextless(page: PageTranslation): Boolean =
        page.ocrStatus == StageStatus.TEXTLESS ||
            page.isTextlessTerminal ||
            (page.blocks.none { it.text.isNotBlank() } && page.translationStatus == StageStatus.SKIPPED)

    private fun isContextReady(page: PageTranslation): Boolean =
        page.ocrStatus in setOf(StageStatus.READY, StageStatus.TEXTLESS) &&
            (page.translationStatus == StageStatus.READY || isTextless(page))
}
