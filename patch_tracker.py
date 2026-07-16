with open('app/src/main/java/eu/kanade/translation/batch/TranslationBatchProgressTracker.kt', 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace('skippedBlocks = event.skippedBlocks', 'keptBlocks = event.skippedBlocks')
content = content.replace('copy(completedBlocks = completedBlocks', 'copy(correctedBlocks = correctedBlocks')
content = content.replace('failedBlocks = failedBlocks', 'unresolvedBlocks = unresolvedBlocks')

with open('app/src/main/java/eu/kanade/translation/batch/TranslationBatchProgressTracker.kt', 'w', encoding='utf-8') as f:
    f.write(content)
