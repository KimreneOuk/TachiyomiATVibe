import re

with open('app/src/main/java/eu/kanade/translation/translator/RevisionPlanner.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# Add import
content = content.replace('import eu.kanade.translation.model.TranslationBlock', 'import eu.kanade.translation.model.TranslationBlock\nimport eu.kanade.translation.model.RevisionScope')

# plan signature
content = content.replace(
'''    fun plan(
        orderedPages: LinkedHashMap<String, PageTranslation>,
        chapterGlossary: Map<String, String>,
        requestedOutputTokens: Int,
        maxPromptTokens: Int = defaultMaxPromptTokens(),
        maxNearbyContextLines: Int = DEFAULT_NEARBY_CONTEXT_LINES,
    ): Plan {''',
'''    fun plan(
        orderedPages: LinkedHashMap<String, PageTranslation>,
        chapterGlossary: Map<String, String>,
        requestedOutputTokens: Int,
        maxPromptTokens: Int = defaultMaxPromptTokens(),
        maxNearbyContextLines: Int = DEFAULT_NEARBY_CONTEXT_LINES,
        scope: RevisionScope = RevisionScope.FLAGGED,
    ): Plan {''')

# collectTargets call
content = content.replace('val targets = collectTargets(orderedPages)', 'val targets = collectTargets(orderedPages, scope)')

# collectTargets signature
content = content.replace(
'''    private fun collectTargets(orderedPages: LinkedHashMap<String, PageTranslation>): List<Target> {''',
'''    private fun collectTargets(orderedPages: LinkedHashMap<String, PageTranslation>, scope: RevisionScope): List<Target> {''')

# isRevisionTarget call
content = content.replace('if (!isRevisionTarget(block)) continue', 'if (!isRevisionTarget(block, scope)) continue')

# isRevisionTarget body
content = content.replace(
'''    private fun isRevisionTarget(block: TranslationBlock): Boolean =
        block.needsRevision && block.userEditedAt == null && block.text.isNotBlank()''',
'''    private fun isRevisionTarget(block: TranslationBlock, scope: RevisionScope): Boolean =
        block.userEditedAt == null && block.text.isNotBlank() && block.translation.isNotBlank() &&
            (scope == RevisionScope.ALL_TRANSLATED || block.needsRevision)''')

with open('app/src/main/java/eu/kanade/translation/translator/RevisionPlanner.kt', 'w', encoding='utf-8') as f:
    f.write(content)
