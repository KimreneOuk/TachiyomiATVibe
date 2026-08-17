package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.isTier1DisplayReady
import eu.kanade.translation.model.shouldShowTranslationOverlay

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
    if (showTranslatedImage && translation.shouldShowTranslationOverlay) {
        return ReaderTranslationOverlayBinding(
            blocks = translation.blocks,
            pageWidth = translation.imgWidth.toInt(),
            pageHeight = translation.imgHeight.toInt(),
        )
    }
    if (!showTranslatedImage && translation.isTier1DisplayReady) {
        return ReaderTranslationOverlayBinding(
            blocks = translation.blocks,
            pageWidth = translation.imgWidth.toInt(),
            pageHeight = translation.imgHeight.toInt(),
        )
    }
    return ReaderTranslationOverlayBinding(emptyList(), 0, 0)
}
