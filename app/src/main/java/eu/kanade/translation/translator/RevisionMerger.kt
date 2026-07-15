package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.stableFingerprint
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * TachiyomiAT: strict Pass-2 merge. Applies structured revision corrections to
 * the live chapter state ONLY when every precondition captured at request time
 * still holds, then re-runs [TranslationBlockValidation] to derive the page's
 * terminal status. Never forces a page READY.
 *
 * Invariants (Checkpoint 2 §6):
 *  - Only TRANSLATED, non-blank, unique results may apply.
 *  - Precondition re-check: run generation, page version, block fingerprint,
 *    draft, needsRevision, userEditedAt must ALL match the request snapshot.
 *  - Missing / blank / malformed / duplicate / stale / edited / rejected
 *    corrections retain the draft + flag and are logged and accounted.
 *  - Only an applied correction increments `completed` and clears the flag.
 *  - A page whose post-merge validation is PARTIAL stays PARTIAL; the page is
 *    NEVER forced to READY.
 *  - A request failure (exception / null batch) leaves every flag intact and
 *    accounts all targets as failed without mutating state.
 */
object RevisionMerger {

    /** Per-target outcome of a merge attempt. */
    sealed interface TargetOutcome {
        data class Applied(val pageKey: String, val blockIndex: Int) : TargetOutcome
        data class Retained(val pageKey: String, val blockIndex: Int, val reason: String) : TargetOutcome
    }

    data class MergeResult(
        val applied: List<TargetOutcome.Applied>,
        val retained: List<TargetOutcome.Retained>,
        val pageStatuses: Map<String, String>,
        /** True when the request itself failed (exception/null); flags untouched. */
        val requestFailed: Boolean,
    ) {
        val appliedCount: Int get() = applied.size
        val retainedCount: Int get() = retained.size
    }

    /**
     * Merge a single request's structured batch into the live pages.
     *
     * @param livePages current chapter state (pageKey -> page). NOT mutated by
     *   this function; the caller applies the returned status via the store.
     * @param batch the structured batch returned by the provider. May be null
     *   when the provider request failed — every target is then accounted as
     *   failed and flags are left intact.
     * @param requestTargets the targets this request was planned for (from
     *   [RevisionPlanner.RequestGroup.targets]), in reading order. Used to
     *   account missing/failed targets even when the batch omits them.
     * @param chapterId / pageKey context for the no-silent-failure logging.
     */
    fun merge(
        livePages: Map<String, PageTranslation>,
        batch: ContextualTranslationBatch?,
        requestTargets: List<RevisionPlanner.Target>,
        chapterId: Long?,
        chapterName: String,
    ): MergeResult {
        val applied = mutableListOf<TargetOutcome.Applied>()
        val retained = mutableListOf<TargetOutcome.Retained>()
        val touchedPages = mutableSetOf<String>()

        // Map id -> target location for resolving results.
        val resultsById = batch?.results?.associateBy { it.id } ?: emptyMap()

        for (target in requestTargets) {
            val page = livePages[target.pageKey]
            val liveBlock = page?.blocks?.getOrNull(target.blockIndex)
            val anchorId = resolveAnchorId(batch, target, requestTargets)
            val result = resultsById[anchorId]
            val outcome = mergeTarget(
                livePageKey = target.pageKey,
                liveBlock = liveBlock,
                expectedPrecondition = batch?.preconditions?.get(anchorId),
                result = result,
                anchorId = anchorId,
                chapterId = chapterId,
                chapterName = chapterName,
            )
            if (outcome is TargetOutcome.Applied) {
                applied += outcome
                touchedPages += outcome.pageKey
            } else {
                retained += outcome as TargetOutcome.Retained
            }
        }

        val pageStatuses = derivePageStatuses(livePages, touchedPages, applied)
        return MergeResult(
            applied = applied,
            retained = retained,
            pageStatuses = pageStatuses,
            requestFailed = batch == null,
        )
    }

    /**
     * Resolve the request-local anchored id for [target]. The batch's id map
     * was built in reading order, so the Nth target maps to the Nth anchored id.
     */
    private fun resolveAnchorId(
        batch: ContextualTranslationBatch?,
        target: RevisionPlanner.Target,
        requestTargets: List<RevisionPlanner.Target>,
    ): String {
        if (batch == null) return ""
        val orderedIds = batch.idToBlockIndex.keys.toList()
        val targetIndex = requestTargets.indexOfFirst {
            it.pageKey == target.pageKey && it.blockIndex == target.blockIndex
        }
        if (targetIndex < 0 || targetIndex >= orderedIds.size) return ""
        return orderedIds[targetIndex]
    }

    private fun mergeTarget(
        livePageKey: String,
        liveBlock: TranslationBlock?,
        expectedPrecondition: TargetPrecondition?,
        result: ContextualTranslationResult?,
        anchorId: String,
        chapterId: Long?,
        chapterName: String,
    ): TargetOutcome {
        // Missing live block: page/block vanished (cancellation, delete). Retain.
        if (liveBlock == null) {
            return reject(chapterId, chapterName, livePageKey, anchorId, "live block missing")
        }
        // No result for this id (missing from response): retain draft + flag.
        if (result == null) {
            return reject(chapterId, chapterName, livePageKey, anchorId, "result missing for id")
        }
        // Rejected by the parser (unknown/blank/malformed/duplicate): retain.
        if (result.status != ContextualTranslationResult.Status.TRANSLATED) {
            return reject(chapterId, chapterName, livePageKey, anchorId, "result rejected: ${result.id}")
        }
        // Blank payload after parsing guard: retain.
        if (result.text.isBlank()) {
            return reject(chapterId, chapterName, livePageKey, anchorId, "blank correction")
        }
        // Precondition snapshot absent: cannot verify staleness -> retain.
        if (expectedPrecondition == null) {
            return reject(chapterId, chapterName, livePageKey, anchorId, "precondition snapshot missing")
        }
        // Strict precondition re-check against the LIVE block.
        val liveFingerprint = liveBlock.stableFingerprint()
        when {
            liveBlock.userEditedAt != expectedPrecondition.userEditedAt ->
                return reject(chapterId, chapterName, livePageKey, anchorId, "userEditedAt changed (edit wins)")
            liveBlock.translation != expectedPrecondition.draft ->
                return reject(chapterId, chapterName, livePageKey, anchorId, "draft changed (stale)")
            liveFingerprint != expectedPrecondition.fingerprint ->
                return reject(chapterId, chapterName, livePageKey, anchorId, "fingerprint changed (stale)")
            !liveBlock.needsRevision ->
                return reject(chapterId, chapterName, livePageKey, anchorId, "flag already cleared")
        }
        // All preconditions match: apply the correction.
        liveBlock.translation = OcrArtifactSanitizer.sanitize(result.text)
        liveBlock.needsRevision = false
        return TargetOutcome.Applied(livePageKey, anchorIdToInt(anchorId))
    }

    private fun anchorIdToInt(anchorId: String): Int {
        // Block index within the page; used for compatibility with store APIs.
        // Anchored ids are pX_bY; extract Y.
        val match = AnchoredBlockId.pattern.matchEntire(anchorId)
        return match?.groupValues?.get(2)?.toIntOrNull() ?: 0
    }

    private fun reject(
        chapterId: Long?,
        chapterName: String,
        pageKey: String,
        anchorId: String,
        reason: String,
    ): TargetOutcome.Retained {
        logcat(LogPriority.WARN) {
            "TachiyomiAT revision merge retained draft: chapterId=$chapterId chapter=$chapterName " +
                "pageKey=$pageKey id=$anchorId reason=$reason"
        }
        return TargetOutcome.Retained(pageKey, anchorIdToInt(anchorId), reason)
    }

    /**
     * Re-run [TranslationBlockValidation] for each touched page to derive its
     * READY/PARTIAL/FAILED status. NEVER forces READY: a page with any
     * untranslated block stays PARTIAL (or FAILED if nothing translated).
     * Untouched flagged pages keep their existing status + flag.
     */
    private fun derivePageStatuses(
        livePages: Map<String, PageTranslation>,
        touchedPages: Set<String>,
        applied: List<TargetOutcome.Applied>,
    ): Map<String, String> {
        val statuses = LinkedHashMap<String, String>()
        // Only re-validate pages that actually received an applied correction;
        // untouched flagged pages keep their existing status + flag.
        val appliedPages = applied.map { it.pageKey }.toSet()
        for (pageKey in touchedPages intersect appliedPages) {
            val page = livePages[pageKey] ?: continue
            statuses[pageKey] = TranslationBlockValidation.applyTo(page)
        }
        return statuses
    }
}
