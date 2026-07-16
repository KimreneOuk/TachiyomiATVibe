with open('app/src/main/java/eu/kanade/translation/model/TranslationProgressSnapshot.kt', 'r', encoding='utf-8') as f:
    content = f.read()

old_prog = '''@Immutable
data class RevisionProgress(
    val totalBlocks: Int = 0,
    val completedBlocks: Int = 0,
    val failedBlocks: Int = 0,
    val skippedBlocks: Int = 0,
    val userEditedBlocks: Int = 0,
    val activePageKey: String? = null,
    val activeChunkBlocks: Int = 0,
) {
    val processedBlocks: Int get() = (completedBlocks + failedBlocks).coerceAtMost(totalBlocks)
    val fraction: Float get() = if (totalBlocks == 0) 0f else processedBlocks.toFloat() / totalBlocks
    val isActive: Boolean get() = totalBlocks > 0 && processedBlocks < totalBlocks
}'''

new_prog = '''@Immutable
data class RevisionProgress(
    val totalBlocks: Int = 0,
    val keptBlocks: Int = 0,
    val correctedBlocks: Int = 0,
    val unresolvedBlocks: Int = 0,
    val userEditedBlocks: Int = 0,
    val activePageKey: String? = null,
    val activeChunkBlocks: Int = 0,
) {
    val processedBlocks: Int get() = (keptBlocks + correctedBlocks + unresolvedBlocks).coerceAtMost(totalBlocks)
    val fraction: Float get() = if (totalBlocks == 0) 0f else processedBlocks.toFloat() / totalBlocks
    val isActive: Boolean get() = totalBlocks > 0 && processedBlocks < totalBlocks
}'''

content = content.replace(old_prog, new_prog)

with open('app/src/main/java/eu/kanade/translation/model/TranslationProgressSnapshot.kt', 'w', encoding='utf-8') as f:
    f.write(content)
