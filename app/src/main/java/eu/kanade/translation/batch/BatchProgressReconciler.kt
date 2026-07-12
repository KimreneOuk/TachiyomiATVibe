package eu.kanade.translation.batch

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.isStageFailed
import eu.kanade.translation.model.isStageRunning
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.hasRenderedResult

data class ReconciliationResult(
    val chapterStatus: Translation.State,
    val strandedPages: Map<String, String>,
    val doneCount: Int,
    val failedCount: Int,
    val partialCount: Int,
)

object BatchProgressReconciler {

    fun reconcile(
        pageMap: Map<String, PageTranslation>,
        orderedKeys: List<String>,
    ): ReconciliationResult {
        if (pageMap.isEmpty()) {
            return ReconciliationResult(
                chapterStatus = Translation.State.ERROR,
                strandedPages = emptyMap(),
                doneCount = 0, failedCount = 0, partialCount = 0,
            )
        }

        val strandedPages = mutableMapOf<String, String>()
        var doneCount = 0
        var failedCount = 0
        var partialCount = 0

        for ((pageKey, page) in pageMap) {
            if (page.hasRenderedResult || page.isTextlessTerminal) {
                doneCount++
            } else if (page.isStageFailed) {
                failedCount++
            } else if (page.translationStatus == eu.kanade.translation.model.StageStatus.PARTIAL && page.renderStatus == eu.kanade.translation.model.StageStatus.READY) {
                partialCount++
            } else if (page.isStageRunning || page.isNonTerminalWithoutOutput()) {
                strandedPages[pageKey] = "Translation incomplete — page was left in a non-terminal state"
                failedCount++
            } else {
                doneCount++
            }
        }

        val hasFailedOrStranded = failedCount > 0 || strandedPages.isNotEmpty()
        val chapterStatus = if (hasFailedOrStranded) {
            Translation.State.ERROR
        } else {
            Translation.State.TRANSLATED
        }

        return ReconciliationResult(
            chapterStatus = chapterStatus,
            strandedPages = strandedPages,
            doneCount = doneCount,
            failedCount = failedCount,
            partialCount = partialCount,
        )
    }

    private fun PageTranslation.isNonTerminalWithoutOutput(): Boolean {
        return !isStageFailed &&
            !hasRenderedResult &&
            !isTextlessTerminal &&
            (ocrStatus == eu.kanade.translation.model.StageStatus.RUNNING ||
                ocrStatus == eu.kanade.translation.model.StageStatus.PENDING)
    }
}
