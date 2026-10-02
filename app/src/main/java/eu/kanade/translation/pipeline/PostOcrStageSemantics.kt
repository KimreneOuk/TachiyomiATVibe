package eu.kanade.translation.pipeline

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus

internal fun finalizePostOcrStage(
    page: PageTranslation,
    inpaintAlreadyRan: Boolean,
) {
    if (!inpaintAlreadyRan) {
        page.inpaintStatus = StageStatus.PENDING
    }
    if (page.ocrStatus != StageStatus.READY || page.blocks.any { it.text.isNotBlank() }) return

    page.translationStatus = StageStatus.SKIPPED
    page.renderStatus = StageStatus.SKIPPED
    if (page.inpaintMaskBoxes.isEmpty()) {
        page.inpaintStatus = StageStatus.SKIPPED
        page.cleanedBitmap?.let { bitmap -> runCatching { bitmap.recycle() } }
        page.cleanedBitmap = null
        page.cleanedImageName = null
        page.cleanedImageContentHash = null
    }
}
