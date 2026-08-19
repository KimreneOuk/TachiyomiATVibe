package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.isTranslationDisplayReady

data class ReaderTranslationOverlayBinding(
    val blocks: List<TranslationBlock>,
    val pageWidth: Int,
    val pageHeight: Int,
)

/**
 * Selects the overlay independently from image loading. The cleaned bitmap and
 * translated text are separate reader layers, so every image decode must be
 * able to reconstruct this binding without waiting for another store emission.
 */
fun selectReaderTranslationOverlayBinding(
    showTranslatedImage: Boolean,
    translation: PageTranslation?,
): ReaderTranslationOverlayBinding {
    if (translation == null) {
        return ReaderTranslationOverlayBinding(emptyList(), 0, 0)
    }
    val width = when {
        translation.imgWidth > 0f -> translation.imgWidth.toInt()
        translation.originalImgWidth > 0f -> translation.originalImgWidth.toInt()
        else -> 0
    }
    val height = when {
        translation.imgHeight > 0f -> translation.imgHeight.toInt()
        translation.originalImgHeight > 0f -> translation.originalImgHeight.toInt()
        else -> 0
    }
    if (showTranslatedImage && translation.isTranslationDisplayReady) {
        return ReaderTranslationOverlayBinding(
            blocks = translation.blocks,
            pageWidth = width,
            pageHeight = height,
        )
    }
    return ReaderTranslationOverlayBinding(emptyList(), 0, 0)
}
