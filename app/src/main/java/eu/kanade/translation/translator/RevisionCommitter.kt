package eu.kanade.translation.translator

import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.stableFingerprint
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * TachiyomiAT: commits a [RevisionMerger.MergeResult] to the live
 * [ChapterTranslationStore] through the store's atomic patch API, then re-runs
 * [TranslationBlockValidation] to derive each touched page's READY/PARTIAL
 * status. Extracted from [eu.kanade.translation.TranslationPipeline] so the
 * strict-commit + re-validate behavior (Checkpoint 2 §6) is unit-testable with a
 * real in-memory store and the structured [ContextualTranslationBatch].
 *
 * Invariants enforced here:
 *  - ONLY a correction the merge layer cleared the revision flag for is offered
 *    to the store (so missing/blank/malformed/duplicate/stale/edited corrections
 *    retain the draft + flag).
 *  - Each correction commits via [ChapterTranslationStore.patchBlock] carrying
 *    the generation / page-version / block-fingerprint / draft / needsRevision /
 *    userEditedAt preconditions captured at request time. A stale/edited/cleared
 *    block is rejected by the store AND logged, retaining its draft + flag.
 *  - After accepted patches, [TranslationBlockValidation] is re-run for each
 *    touched page to derive READY/PARTIAL. A page with any untranslated block
 *    stays PARTIAL — a page is NEVER forced READY.
 *  - Every rejected patch and every retained target is logged with chapter/page/
 *    reason (no silent fallback).
 */
object RevisionCommitter {

    data class CommitOutcome(
        val keptCount: Int,
        val correctedCount: Int,
        val unresolvedCount: Int,
        /** Page keys whose status was (re)derived and should be re-rendered. */
        val touchedPages: Set<String>,
    ) {
        // Compatibility for TranslationPipeline
        val appliedCount: Int get() = keptCount + correctedCount
        val failedCount: Int get() = unresolvedCount
    }

    /**
     * Commit [mergeResult] (produced by [RevisionMerger.merge] over [mergeLive])
     * to [store]. [groupTargets] is the request group's targets in reading order;
     * [orderedAnchorIds] is the batch's anchored-id list in the same order, used to
     * recover each target's request-time precondition snapshot.
     *
     * @param chapterName / chapterId context for the no-silent-failure logging.
     */
    suspend fun commit(
        store: ChapterTranslationStore,
        mergeLive: Map<String, PageTranslation>,
        groupTargets: List<RevisionPlanner.Target>,
        batch: ContextualTranslationBatch?,
        orderedAnchorIds: List<String>,
        mergeResult: RevisionMerger.MergeResult,
        chapterId: Long?,
        chapterName: String,
    ): CommitOutcome {
        var keptCount = 0
        var correctedCount = 0
        var unresolvedCount = mergeResult.unresolvedCount
        val touchedPages = linkedSetOf<String>()

        suspend fun doPatch(pageKey: String, blockIndex: Int, isKept: Boolean) {
            val targetIndex = groupTargets.indexOfFirst { it.pageKey == pageKey && it.blockIndex == blockIndex }
            if (targetIndex < 0) return
            val target = groupTargets[targetIndex]
            val anchorId = orderedAnchorIds.getOrNull(targetIndex) ?: ""
            val precond = batch?.preconditions?.get(anchorId)
            val snapshot = store.snapshot(pageKey)
            val mergedBlock = mergeLive[pageKey]?.blocks?.getOrNull(blockIndex)
            val patchResult = store.patchBlock(
                pageKey = pageKey,
                blockIndex = blockIndex,
                expected = ChapterTranslationStore.PatchPrecondition(
                    generation = snapshot.generation,
                    pageVersion = snapshot.pageVersion,
                ),
                expectedBlockFingerprint = target.block.stableFingerprint(),
                expectedTranslation = precond?.draft ?: target.block.translation,
                expectedUserEditedAt = precond?.userEditedAt,
                description = "Pass-2 revision correction chapter=$chapterName",
            ) { block ->
                val correctedDraft = mergedBlock?.translation ?: block.translation
                block.copy(
                    translation = OcrArtifactSanitizer.sanitize(correctedDraft),
                    needsRevision = false,
                )
            }
            when (patchResult) {
                is ChapterTranslationStore.PatchResult.Accepted -> {
                    if (isKept) keptCount++ else correctedCount++
                    touchedPages += pageKey
                    logcat(LogPriority.INFO) {
                        "TachiyomiAT revision patch applied (kept=$isKept): chapter=$chapterName " +
                            "pageKey=$pageKey blockIndex=$blockIndex anchorId=$anchorId"
                    }
                }
                is ChapterTranslationStore.PatchResult.Rejected -> {
                    unresolvedCount++
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT revision patch rejected: chapter=$chapterName " +
                            "pageKey=$pageKey blockIndex=$blockIndex anchorId=$anchorId " +
                            "reason=${patchResult.reason}"
                    }
                }
            }
        }

        for (outcome in mergeResult.kept) {
            doPatch(outcome.pageKey, outcome.blockIndex, isKept = true)
        }
        for (outcome in mergeResult.corrected) {
            doPatch(outcome.pageKey, outcome.blockIndex, isKept = false)
        }

        touchedPages.forEach { pageKey ->
            val current = store.state.value[pageKey] ?: return@forEach
            val validatedStatus = TranslationBlockValidation.applyTo(current)
            store.updatePage(pageKey) { existing ->
                (existing ?: current).apply {
                    translationStatus = validatedStatus
                    errorMessage = null
                    updatedAt = System.currentTimeMillis()
                }
            }
        }
        return CommitOutcome(keptCount, correctedCount, unresolvedCount, touchedPages)
    }

    /**
     * Helper: derive the ordered anchored-id list from a batch mirroring
     * [RevisionMerger.resolveAnchorId] (Nth target -> Nth anchored id).
     */
    fun orderedAnchorIds(batch: ContextualTranslationBatch?): List<String> =
        batch?.idToBlockIndex?.keys?.toList() ?: emptyList()

    /**
     * Helper: build a detached live snapshot map for the merge layer from [store]
     * for the given page keys.
     */
    fun mergeLiveSnapshot(
        store: ChapterTranslationStore,
        pageKeys: Collection<String>,
    ): LinkedHashMap<String, PageTranslation> {
        val live = store.state.value
        val out = LinkedHashMap<String, PageTranslation>()
        pageKeys.forEach { pk -> live[pk]?.let { out[pk] = it.detachedCopy() } }
        return out
    }
}
