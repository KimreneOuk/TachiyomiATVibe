package eu.kanade.translation.engines.translator.contextual

import eu.kanade.translation.model.PageTranslation

object TranslationBlockFilters {
    private const val WATERMARK_TOKEN = "RTMTH"

    fun removeWatermarkBlocks(pages: MutableMap<String, PageTranslation>) {
        pages.values.forEach { page ->
            page.blocks = page.blocks
                .filterNot { block -> block.translation.contains(WATERMARK_TOKEN, ignoreCase = true) }
                .toMutableList()
        }
    }
}
