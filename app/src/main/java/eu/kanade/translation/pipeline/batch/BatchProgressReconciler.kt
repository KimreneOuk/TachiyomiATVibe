package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isStageCancelled
import eu.kanade.translation.model.isStageFailed
import eu.kanade.translation.model.isStageRunning
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.util.ShortHash
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

data class ReconciliationResult(
    val chapterStatus: Translation.State,
    val strandedPages: Map<String, String>,
    val unexpectedPageKeys: Set<String>,
    val doneCount: Int,
    val failedCount: Int,
    val partialCount: Int,
    val paused: Boolean = false,
    val anchorPageKey: String? = null,
    val pendingCount: Int = 0,
    val retryableCount: Int = 0,
    val terminalCount: Int = failedCount,
    val nextEligibleRetryAtEpochMs: Long? = null,
    val pauseReason: String? = null,
    /** True when the result is an in-memory-only stop after publication rejection. */
    val nonDurableFailure: Boolean = false,
)

object BatchProgressReconciler {

    fun reconcile(
        pageMap: Map<String, PageTranslation>,
        orderedKeys: List<String>,
        activeGeneration: Long,
        pauseOutcome: BatchPass1Outcome? = null,
    ): ReconciliationResult {
        val expectedKeys = orderedKeys.distinct()
        val unexpectedPageKeys = pageMap.keys - expectedKeys.toSet()
        unexpectedPageKeys.forEach { pageKey ->
            logcat(LogPriority.WARN) {
                "event=batch_reconciliation reason=unexpected_page pageHash=${ShortHash.hash(pageKey)}"
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
                paused = false,
            )
        }

        pauseOutcome?.takeIf {
            it.status == BatchPass1Status.PAUSED ||
                it.status == BatchPass1Status.FAILED ||
                it.status == BatchPass1Status.PERSISTENCE_REJECTED
        }?.let { pausedOutcome ->
            return reconcilePaused(pageMap, expectedKeys, pausedOutcome, unexpectedPageKeys)
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
            terminalCount = failedCount,
        )
    }

    private fun reconcilePaused(
        pageMap: Map<String, PageTranslation>,
        expectedKeys: List<String>,
        outcome: BatchPass1Outcome,
        unexpectedPageKeys: Set<String>,
    ): ReconciliationResult {
        val retryable = outcome.retryablePageKeys.ifEmpty {
            outcome.anchorPageKey?.let(::setOf).orEmpty()
        }
        val anchorIndex = outcome.anchorPageKey?.let(expectedKeys::indexOf)?.takeIf { it >= 0 }
        var doneCount = 0
        var failedCount = 0
        var partialCount = 0
        var pendingCount = 0
        var terminalCount = 0
        val persistenceRejected = outcome.status == BatchPass1Status.PERSISTENCE_REJECTED

        expectedKeys.forEachIndexed { index, pageKey ->
            val page = pageMap[pageKey]
            when {
                pageKey in retryable -> {
                    if (persistenceRejected) {
                        // The rejected publication gives us no durable truth for
                        // this anchor. Keep it pending in the in-memory report;
                        // never infer completion or a retryable durable failure.
                        pendingCount++
                    } else if (page?.translationStatus == eu.kanade.translation.model.StageStatus.PARTIAL) {
                        partialCount++
                    }
                }
                anchorIndex != null && index > anchorIndex -> {
                    // A pause owns only its first unresolved anchor. Tail pages
                    // remain pending; pre-existing terminal failures are still
                    // reported, but no missing page is synthesized as failed.
                    when {
                        page == null -> pendingCount++
                        page.isStageFailed -> {
                            failedCount++
                            terminalCount++
                        }
                        page.hasRenderedResult || page.isTextlessTerminal -> doneCount++
                        else -> pendingCount++
                    }
                }
                page == null -> pendingCount++
                page.isStageFailed -> {
                    failedCount++
                    terminalCount++
                }
                page.hasRenderedResult || page.isTextlessTerminal -> doneCount++
                page.translationStatus == eu.kanade.translation.model.StageStatus.PARTIAL -> {
                    partialCount++
                }
                else -> pendingCount++
            }
        }

        return ReconciliationResult(
            chapterStatus = when {
                persistenceRejected -> Translation.State.READY_WITH_WARNINGS
                outcome.status == BatchPass1Status.FAILED || terminalCount > 0 -> Translation.State.ERROR
                else -> Translation.State.PAUSED
            },
            strandedPages = emptyMap(),
            unexpectedPageKeys = unexpectedPageKeys,
            doneCount = doneCount,
            failedCount = failedCount,
            partialCount = partialCount,
            paused = outcome.status == BatchPass1Status.PAUSED && terminalCount == 0,
            anchorPageKey = outcome.anchorPageKey,
            pendingCount = pendingCount,
            retryableCount = if (persistenceRejected) 0 else retryable.size,
            terminalCount = terminalCount,
            nextEligibleRetryAtEpochMs = outcome.nextEligibleRetryAtEpochMs,
            pauseReason = outcome.reason,
            nonDurableFailure = persistenceRejected,
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
