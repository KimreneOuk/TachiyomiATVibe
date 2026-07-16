import os

def replace_in_file(filepath, old_str, new_str):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
    content = content.replace(old_str, new_str)
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)

base = 'app/src/main/java/eu/kanade/'

# TranslationBatchProgressTracker
tracker = base + 'translation/batch/TranslationBatchProgressTracker.kt'
replace_in_file(tracker, 'completedBlocks = ', 'correctedBlocks = ')
replace_in_file(tracker, 'failedBlocks = ', 'unresolvedBlocks = ')
replace_in_file(tracker, 'skippedBlocks = ', 'keptBlocks = ')

# TranslationPipeline
pipeline = base + 'translation/TranslationPipeline.kt'
replace_in_file(pipeline, 'appliedCount', 'correctedCount')
replace_in_file(pipeline, 'failedCount', 'unresolvedCount')
replace_in_file(pipeline, 'completedBlocks', 'correctedBlocks')
replace_in_file(pipeline, 'failedBlocks', 'unresolvedBlocks')
replace_in_file(pipeline, 'skippedBlocks', 'keptBlocks')

# UI files
ui1 = base + 'presentation/manga/components/TranslationProgressSheet.kt'
replace_in_file(ui1, 'completedBlocks', 'correctedBlocks')
replace_in_file(ui1, 'failedBlocks', 'unresolvedBlocks')
replace_in_file(ui1, 'skippedBlocks', 'keptBlocks')

ui2 = base + 'presentation/reader/TranslationSettingsSheet.kt'
replace_in_file(ui2, 'completedBlocks', 'correctedBlocks')
replace_in_file(ui2, 'failedBlocks', 'unresolvedBlocks')
replace_in_file(ui2, 'skippedBlocks', 'keptBlocks')

ui3 = base + 'presentation/reader/appbars/BottomReaderBar.kt'
replace_in_file(ui3, 'completedBlocks', 'correctedBlocks')
replace_in_file(ui3, 'failedBlocks', 'unresolvedBlocks')
replace_in_file(ui3, 'skippedBlocks', 'keptBlocks')

