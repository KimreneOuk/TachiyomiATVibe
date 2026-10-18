package eu.kanade.translation.persistence.chapter

import eu.kanade.translation.model.PageTranslationView

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

fun chapterResetPreflight(pages: Collection<PageTranslationView>): ChapterResetPreflight =
    ChapterResetPreflight(
        ocrPages = pages.count { it.blocks.isNotEmpty() || it.inpaintMaskBoxes.isNotEmpty() },
        inpaintPages = pages.count { it.cleanedImageName != null },
        translatedBlocks = pages.sumOf { page -> page.blocks.count { it.translation.isNotBlank() } },
        manualEditBlocks = pages.sumOf { page -> page.blocks.count { it.userEditedAt != null } },
    )

/**
 * P3B typed reset receipt. A reset is COMPLETE only when every destructive
 * publication behind it durably committed with verified same-state backups.
 * Any unverified backup, rejected publication, or failed authority removal
 * surfaces as typed incomplete — the caller must not present the chapter as
 * cleanly reset (and must not adopt orphan bytes) when [failures] is
 * non-empty.
 */
data class ChapterResetReceipt(
    val failures: List<String>,
) {
    val completed: Boolean get() = failures.isEmpty()

    companion object {
        val COMPLETE = ChapterResetReceipt(failures = emptyList())

        fun incomplete(failures: List<String>): ChapterResetReceipt =
            ChapterResetReceipt(failures = failures.filter { it.isNotBlank() }.distinct())
    }
}
