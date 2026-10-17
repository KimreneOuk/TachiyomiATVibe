package eu.kanade.translation.pipeline.execution

import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isTextlessTerminal

/**
 * Failed pages remain retryable. Rendered or genuinely textless pages are terminal;
 * masked textless pages also require completed inpainting.
 */
internal fun isPreparedPageTerminal(page: PageTranslationView): Boolean {
    val failed = page.ocrStatus == StageStatus.FAILED ||
        page.inpaintStatus == StageStatus.FAILED ||
        page.translationStatus == StageStatus.FAILED ||
        page.renderStatus == StageStatus.FAILED
    if (failed) return false
    return page.hasRenderedResult ||
        (
            page.isTextlessTerminal &&
                (page.inpaintMaskBoxes.isEmpty() || page.inpaintStatus == StageStatus.READY)
            )
}
