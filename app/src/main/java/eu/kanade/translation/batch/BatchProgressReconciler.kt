package eu.kanade.translation.batch

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isStageCancelled
import eu.kanade.translation.model.isStageFailed
import eu.kanade.translation.model.isStageRunning
import eu.kanade.translation.model.isTextlessTerminal
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

data class ReconciliationResult(
    val chapterStatus: Translation.State,
    val strandedPages: Map<String, String>,
    val unexpectedPageKeys: Set<String>,
    val doneCount: Int,
    val failedCount: Int,
    val partialCount: Int,
)

object BatchProgressReconciler {

    fun reconcile(
        pageMap: Map<String, PageTranslation>,
        orderedKeys: List<String>,
        activeGeneration: Long,
    ): ReconciliationResult {
        val expectedKeys = orderedKeys.distinct()
        val unexpectedPageKeys = pageMap.keys - expectedKeys.toSet()
        unexpectedPageKeys.forEach { pageKey ->
            logcat(LogPriority.WARN) {
                "TachiyomiAT batch reconciliation unexpected page: pageKey=$pageKey reason=not in authoritative ordered keys"
            }
        }
        if (expectedKeys.isEmpty()) {
            return ReconciliationResult(
                chapterStatus = Translation.State.ERROR,
                strandedPages = emptyMap(),
                unexpectedPageKeys = unexpectedPageKeys,
                doneCount = 0,
                failedCount = 0,
                partialCount = 0,
            )
        }

        val strandedPages = linkedMapOf<String, String>()
        var doneCount = 0
        var failedCount = 0
        var partialCount = 0

        for (pageKey in expectedKeys) {
            val page = pageMap[pageKey]
            if (page == null) {
                strandedPages[pageKey] = "Translation incomplete — expected page is missing"
                failedCount++
                continue
            }
            if (page.runGeneration != activeGeneration && !page.hasRenderedResult && !page.isTextlessTerminal) {
                // Not owned by the active generation and not successfully terminal.
                // It was skipped by the planner because it wasn't valid, but never reached by the producer.
                strandedPages[pageKey] =
                    "Translation incomplete — expected page was stranded by a prior run and not reached"
                failedCount++
                continue
            }
            when {
                page.isStageFailed -> failedCount++
                page.hasRenderedResult || page.isTextlessTerminal -> doneCount++
                page.translationStatus == eu.kanade.translation.model.StageStatus.PARTIAL -> {
                    partialCount++
                    doneCount++
                }
                page.isStageCancelled || page.isStageRunning || page.isNonTerminalWithoutOutput() -> {
                    strandedPages[pageKey] = "Translation incomplete — page was left cancelled or non-terminal"
                    failedCount++
                }
                else -> {
                    strandedPages[pageKey] = "Translation incomplete — expected page has no readable terminal output"
                    failedCount++
                }
            }
        }

        val chapterStatus = when {
            failedCount > 0 -> Translation.State.ERROR
            partialCount > 0 -> Translation.State.READY_WITH_WARNINGS
            else -> Translation.State.TRANSLATED
        }

        return ReconciliationResult(
            chapterStatus = chapterStatus,
            strandedPages = strandedPages,
            unexpectedPageKeys = unexpectedPageKeys,
            doneCount = doneCount,
            failedCount = failedCount,
            partialCount = partialCount,
        )
    }

    private fun PageTranslation.isNonTerminalWithoutOutput(): Boolean {
        return !isStageFailed &&
            !hasRenderedResult &&
            !isTextlessTerminal &&
            (
                ocrStatus == eu.kanade.translation.model.StageStatus.RUNNING ||
                    ocrStatus == eu.kanade.translation.model.StageStatus.PENDING
                )
    }
}
