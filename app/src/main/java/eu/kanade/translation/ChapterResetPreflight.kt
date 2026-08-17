package eu.kanade.translation

import eu.kanade.translation.model.PageTranslation

/**
 * Durable translation data that a chapter reset would affect. Counts are derived
 * from the store snapshot so callers do not infer artifacts from UI state.
 */
data class ChapterResetPreflight(
    val ocrPages: Int,
    val inpaintPages: Int,
    val translatedBlocks: Int,
    val manualEditBlocks: Int,
) {
    val hasTranslationData: Boolean
        get() = ocrPages > 0 || inpaintPages > 0 || translatedBlocks > 0
}

fun chapterResetPreflight(pages: Collection<PageTranslation>): ChapterResetPreflight =
    ChapterResetPreflight(
        ocrPages = pages.count { it.blocks.isNotEmpty() || it.inpaintMaskBoxes.isNotEmpty() },
        inpaintPages = pages.count { it.cleanedImageName != null },
        translatedBlocks = pages.sumOf { page -> page.blocks.count { it.translation.isNotBlank() } },
        manualEditBlocks = pages.sumOf { page -> page.blocks.count { it.userEditedAt != null } },
    )
