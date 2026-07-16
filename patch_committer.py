import re

with open('app/src/main/java/eu/kanade/translation/translator/RevisionCommitter.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# I will just write the correct commit method body using regex.

new_commit = '''    ): CommitOutcome {
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
    }'''

content = re.sub(r'\): CommitOutcome \{.*?\n    }', new_commit, content, flags=re.DOTALL)

with open('app/src/main/java/eu/kanade/translation/translator/RevisionCommitter.kt', 'w', encoding='utf-8') as f:
    f.write(content)
