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
                // T924 field fix (Chapter 21): this branch is only reachable
                // when the page is NOT display-ready — a partial candidate with
                // no rendered result shows nothing readable, so it is
                // unresolved work (ERROR, retryable), never a usable warning.
                // Counting it done+partial produced "Ready (Warnings)"
                // chapters with unrendered pages and no Retry affordance.
                page.translationStatus == eu.kanade.translation.model.StageStatus.PARTIAL -> {
                    partialCount++
                    strandedPages[pageKey] =
                        "Translation incomplete — partial result was never rendered"
                    failedCount++
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

    /**
     * T924 LI-1: the completion projection for a FLAGGED-lane (FF-01 ON)
     * COMPLETED outcome. The flagged [ChapterProfileBatchCoordinator] commits
     * translations WITHOUT an in-pass render — a healthy page ends
     * translation-terminal (READY/PARTIAL, committed bundle in the artifact
     * manifest) with `renderStatus == PENDING` and no cleaned image, so the
     * legacy [reconcile] done-predicate (`hasRenderedResult`) counts every
     * translatable page stranded and reports the chapter ERROR. At a flagged
     * COMPLETED outcome the coordinator has already resolved every expected
     * page to a terminal state per `t924PageTerminalAtFinalize` (unresolved
     * pages make the chapter a retryable ERROR BEFORE finalize), so the
     * COMPLETE record itself is the completion authority:
     *
     *  - every expected page counts done — including pages MISSING from
     *    [pageMap] entirely (the run's COMPLETE record covers them; the
     *    durable per-page evidence lives in the manifest, consumed by the
     *    run-record-aware `StoreStatusProjector.artifactStatus`);
     *  - pages with `translationStatus == PARTIAL` count into the partial
     *    bucket (chapter READY_WITH_WARNINGS) instead of done;
     *  - no stranded pages and no failures are ever manufactured here.
     *
     * NEVER use this for legacy (FF-01 OFF) outcomes — the legacy schedule
     * renders in-pass and keeps the stricter done-predicate. [activeGeneration]
     * is accepted for signature parity with [reconcile] and is deliberately
     * unused: a COMPLETED run is generation-agnostic by definition.
     */
    fun reconcileFlaggedCompleted(
        pageMap: Map<String, PageTranslation>,
        orderedKeys: List<String>,
        activeGeneration: Long,
    ): ReconciliationResult {
        val expectedKeys = orderedKeys.distinct()
        val unexpectedPageKeys = pageMap.keys - expectedKeys.toSet()
        var doneCount = 0
        var partialCount = 0
        for (pageKey in expectedKeys) {
            if (pageMap[pageKey]?.translationStatus == eu.kanade.translation.model.StageStatus.PARTIAL) {
                partialCount++
            } else {
                doneCount++
            }
        }
        return ReconciliationResult(
            chapterStatus = if (partialCount > 0) {
                Translation.State.READY_WITH_WARNINGS
            } else {
                Translation.State.TRANSLATED
            },
            strandedPages = emptyMap(),
            unexpectedPageKeys = unexpectedPageKeys,
            doneCount = doneCount,
            failedCount = 0,
            partialCount = partialCount,
            terminalCount = 0,
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
                // T924 field fix (Chapter 21): a persistence-rejected run
                // stopped with unresolved work (tail pages cancelled). It must
                // surface as a retryable ERROR — the old in-memory
                // READY_WITH_WARNINGS rendered as a completed chapter with no
                // Retry affordance. [nonDurableFailure] stays true so callers
                // still skip tail reconciliation and never synthesize
                // durable failures the store does not own.
                persistenceRejected -> Translation.State.ERROR
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
