with open('app/src/main/java/eu/kanade/translation/translator/RevisionCommitter.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# Replace CommitOutcome
old_outcome = '''    data class CommitOutcome(
        val appliedCount: Int,
        val failedCount: Int,
        /** Page keys whose status was (re)derived and should be re-rendered. */
        val touchedPages: Set<String>,
    )'''
new_outcome = '''    data class CommitOutcome(
        val keptCount: Int,
        val correctedCount: Int,
        val unresolvedCount: Int,
        /** Page keys whose status was (re)derived and should be re-rendered. */
        val touchedPages: Set<String>,
    )'''
content = content.replace(old_outcome, new_outcome)

# Replace the inner commit loop
old_loop = '''        var appliedCount = 0
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
                description = "Pass-2 revision correction chapter=\",
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
                        "TachiyomiAT revision patch applied: chapter=\ " +
                            "pageKey=\ blockIndex=\ anchorId=\"
                    }
                }
                is ChapterTranslationStore.PatchResult.Rejected -> {
                    // A late concurrent edit between the merge snapshot and the patch:
                    // retain the draft + flag and account as failed.
                    failedCount++
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT revision patch rejected: chapter=\ " +
                            "pageKey=\ blockIndex=\ anchorId=\ " +
                            "reason=\"
                    }
                }
            }
        }'''

new_loop = '''        var keptCount = 0
        var correctedCount = 0
        var unresolvedCount = mergeResult.unresolvedCount
        val touchedPages = linkedSetOf<String>()

        fun doPatch(pageKey: String, blockIndex: Int, isKept: Boolean) {
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
                description = "Pass-2 revision correction chapter=\",
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
                        "TachiyomiAT revision patch applied (kept=\): chapter=\ " +
                            "pageKey=\ blockIndex=\ anchorId=\"
                    }
                }
                is ChapterTranslationStore.PatchResult.Rejected -> {
                    unresolvedCount++
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT revision patch rejected: chapter=\ " +
                            "pageKey=\ blockIndex=\ anchorId=\ " +
                            "reason=\"
                    }
                }
            }
        }

        for (outcome in mergeResult.kept) {
            doPatch(outcome.pageKey, outcome.blockIndex, isKept = true)
        }
        for (outcome in mergeResult.corrected) {
            doPatch(outcome.pageKey, outcome.blockIndex, isKept = false)
        }'''

content = content.replace(old_loop, new_loop)
content = content.replace('return CommitOutcome(appliedCount, failedCount, touchedPages)', 'return CommitOutcome(keptCount, correctedCount, unresolvedCount, touchedPages)')

with open('app/src/main/java/eu/kanade/translation/translator/RevisionCommitter.kt', 'w', encoding='utf-8') as f:
    f.write(content)
