package eu.kanade.translation.scheduling

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.hasCurrentInpaintMask
import eu.kanade.translation.model.hasExhaustedRetries
import eu.kanade.translation.model.hasRecognizedTranslation
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isCleanedImageReady
import eu.kanade.translation.model.isTextlessTerminal

/**
 * Selects the earliest batch stage that can safely reuse a page's existing
 * translation data. The caller supplies file validity and current inpainting
 * mode because this policy does not perform storage or preference reads.
 */
object TranslationLifecyclePolicy {
    enum class NextStage {
        SKIP,
        RENDER,
        INPAINT,
        FULL,
    }

    /**
     * Chooses the minimum safe stage to resume. Physical file validation is
     * supplied by the caller because this pure policy must not perform storage
     * I/O; an invalid/missing file should be passed as false.
     *
     * [inpaintModeMatches] is likewise caller-supplied: true when the page's
     * [PageTranslation.inpaintingModeUsed] matches the current preference (or is
     * null/legacy). A FAST->QUALITY switch passes false so a stale FAST cleaned
     * image is re-inpainted under the new mode instead of being served as-is.
     */
    fun nextStage(
        page: PageTranslation?,
        cleanedFileValid: Boolean,
        inpaintModeMatches: Boolean = true,
    ): NextStage {
        if (page == null) return NextStage.FULL
        if (page.hasRenderedResult && cleanedFileValid && inpaintModeMatches) return NextStage.SKIP
        if (page.hasExhaustedRetries || page.isTextlessTerminal) {
            return NextStage.SKIP
        }
        if (page.isCleanedImageReady &&
            cleanedFileValid &&
            inpaintModeMatches &&
            (
                page.hasRecognizedTranslation ||
                    (page.ocrStatus == StageStatus.READY && page.hasCurrentInpaintMask)
                )
        ) {
            return NextStage.RENDER
        }
        if (page.ocrStatus == StageStatus.READY && page.hasCurrentInpaintMask) {
            return NextStage.INPAINT
        }
        return NextStage.FULL
    }
}
