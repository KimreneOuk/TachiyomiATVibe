with open('app/src/main/java/eu/kanade/translation/translator/RevisionCommitter.kt', 'r', encoding='utf-8') as f:
    content = f.read()

old_outcome = '''    data class CommitOutcome(
        val keptCount: Int,
        val correctedCount: Int,
        val unresolvedCount: Int,
        /** Page keys whose status was (re)derived and should be re-rendered. */
        val touchedPages: Set<String>,
    )'''

new_outcome = '''    data class CommitOutcome(
        val keptCount: Int,
        val correctedCount: Int,
        val unresolvedCount: Int,
        /** Page keys whose status was (re)derived and should be re-rendered. */
        val touchedPages: Set<String>,
    ) {
        // Compatibility for TranslationPipeline
        val appliedCount: Int get() = keptCount + correctedCount
        val failedCount: Int get() = unresolvedCount
    }'''

content = content.replace(old_outcome, new_outcome)

with open('app/src/main/java/eu/kanade/translation/translator/RevisionCommitter.kt', 'w', encoding='utf-8') as f:
    f.write(content)
