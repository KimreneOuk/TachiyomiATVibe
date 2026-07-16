with open('app/src/main/java/eu/kanade/translation/translator/RevisionMerger.kt', 'r', encoding='utf-8') as f:
    content = f.read()

old_target_outcome = '''    sealed interface TargetOutcome {
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
    }'''

new_target_outcome = '''    sealed interface TargetOutcome {
        val pageKey: String
        val blockIndex: Int

        data class Kept(
            override val pageKey: String,
            override val blockIndex: Int,
        ) : TargetOutcome

        data class Corrected(
            override val pageKey: String,
            override val blockIndex: Int,
            val beforeDraft: String,
            val afterDraft: String,
        ) : TargetOutcome

        data class Unresolved(
            override val pageKey: String,
            override val blockIndex: Int,
            val reason: String,
        ) : TargetOutcome
    }

    data class MergeResult(
        val kept: List<TargetOutcome.Kept>,
        val corrected: List<TargetOutcome.Corrected>,
        val unresolved: List<TargetOutcome.Unresolved>,
        val pageStatuses: Map<String, String>,
        /** True when the request itself failed (exception/null); flags untouched. */
        val requestFailed: Boolean,
    ) {
        val keptCount: Int get() = kept.size
        val correctedCount: Int get() = corrected.size
        val unresolvedCount: Int get() = unresolved.size
        val appliedCount: Int get() = keptCount + correctedCount
    }'''

content = content.replace(old_target_outcome, new_target_outcome)

# Now replace usage inside merge
content = content.replace('val applied = mutableListOf<TargetOutcome.Applied>()', 'val kept = mutableListOf<TargetOutcome.Kept>()\n        val corrected = mutableListOf<TargetOutcome.Corrected>()')
content = content.replace('val retained = mutableListOf<TargetOutcome.Retained>()', 'val unresolved = mutableListOf<TargetOutcome.Unresolved>()')

# Inside loop
loop_old = '''            if (outcome is TargetOutcome.Applied) {
                applied += outcome
                touchedPages += outcome.pageKey
            } else {
                retained += outcome as TargetOutcome.Retained
            }'''
loop_new = '''            when (outcome) {
                is TargetOutcome.Kept -> { kept += outcome; touchedPages += outcome.pageKey }
                is TargetOutcome.Corrected -> { corrected += outcome; touchedPages += outcome.pageKey }
                is TargetOutcome.Unresolved -> unresolved += outcome
            }'''
content = content.replace(loop_old, loop_new)

# derivePageStatuses usage
content = content.replace('val pageStatuses = derivePageStatuses(livePages, touchedPages, applied)', 'val pageStatuses = derivePageStatuses(livePages, touchedPages, kept.map { it.pageKey } + corrected.map { it.pageKey })')

# MergeResult constructor
constructor_old = '''        return MergeResult(
            applied = applied,
            retained = retained,
            pageStatuses = pageStatuses,
            requestFailed = batch == null,
        )'''
constructor_new = '''        return MergeResult(
            kept = kept,
            corrected = corrected,
            unresolved = unresolved,
            pageStatuses = pageStatuses,
            requestFailed = batch == null,
        )'''
content = content.replace(constructor_old, constructor_new)

# Inside mergeTarget
mergeTarget_old = '''        // All preconditions match: apply the correction.
        liveBlock.translation = OcrArtifactSanitizer.sanitize(result.text)
        liveBlock.needsRevision = false
        return TargetOutcome.Applied(livePageKey, anchorIdToInt(anchorId))'''

mergeTarget_new = '''        // All preconditions match: apply the correction.
        val sanitizedText = OcrArtifactSanitizer.sanitize(result.text)
        val beforeDraft = liveBlock.translation
        val isKept = sanitizedText == beforeDraft
        if (isKept) {
            liveBlock.needsRevision = false
            return TargetOutcome.Kept(livePageKey, anchorIdToInt(anchorId))
        } else {
            liveBlock.translation = sanitizedText
            liveBlock.needsRevision = false
            return TargetOutcome.Corrected(livePageKey, anchorIdToInt(anchorId), beforeDraft, sanitizedText)
        }'''
content = content.replace(mergeTarget_old, mergeTarget_new)

# reject helper signature
content = content.replace(': TargetOutcome.Retained {', ': TargetOutcome.Unresolved {')
content = content.replace('return TargetOutcome.Retained(pageKey, anchorIdToInt(anchorId), reason)', 'return TargetOutcome.Unresolved(pageKey, anchorIdToInt(anchorId), reason)')

# derivePageStatuses signature
content = content.replace(
'''    private fun derivePageStatuses(
        livePages: Map<String, PageTranslation>,
        touchedPages: Set<String>,
        applied: List<TargetOutcome.Applied>,
    ): Map<String, String> {''',
'''    private fun derivePageStatuses(
        livePages: Map<String, PageTranslation>,
        touchedPages: Set<String>,
        appliedPagesList: List<String>,
    ): Map<String, String> {''')

content = content.replace('val appliedPages = applied.map { it.pageKey }.toSet()', 'val appliedPages = appliedPagesList.toSet()')

with open('app/src/main/java/eu/kanade/translation/translator/RevisionMerger.kt', 'w', encoding='utf-8') as f:
    f.write(content)
