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
        val appliedCount: Int,
        val failedCount: Int,
        /** Page keys whose status was (re)derived and should be re-rendered. */
        val touchedPages: Set<String>,
    )

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
        var appliedCount = 0
        var failedCount = 0
        val touchedPages = linkedSetOf<String>()

        groupTargets.forEachIndexed { targetIndex, target ->
            val pageKey = target.pageKey
            val blockIndex = target.blockIndex
            val mergedBlock = mergeLive[pageKey]?.blocks?.getOrNull(blockIndex)
            // The merger clears needsRevision ONLY for an accepted correction.
            val wasApplied = mergedBlock != null && !mergedBlock.needsRevision &&
                mergedBlock.translation != target.block.translation
            if (!wasApplied) {
                // Retained by the merge layer (reason already logged by RevisionMerger).
                failedCount++
                return@forEachIndexed
            }
            val anchorId = orderedAnchorIds.getOrNull(targetIndex) ?: ""
            val precond = batch?.preconditions?.get(anchorId)
            val snapshot = store.snapshot(pageKey)
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
                    appliedCount++
                    touchedPages += pageKey
                    logcat(LogPriority.INFO) {
                        "TachiyomiAT revision patch applied: chapter=$chapterName " +
                            "pageKey=$pageKey blockIndex=$blockIndex anchorId=$anchorId"
                    }
                }
                is ChapterTranslationStore.PatchResult.Rejected -> {
                    // A late concurrent edit between the merge snapshot and the patch:
                    // retain the draft + flag and account as failed.
                    failedCount++
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT revision patch rejected: chapter=$chapterName " +
                            "pageKey=$pageKey blockIndex=$blockIndex anchorId=$anchorId " +
                            "reason=${patchResult.reason}"
                    }
                }
            }
        }

        // Re-run TranslationBlockValidation for each touched page to derive its
        // READY/PARTIAL status from validation — NEVER force READY. A page with any
        // untranslated block stays PARTIAL.
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
        return CommitOutcome(appliedCount, failedCount, touchedPages)
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
