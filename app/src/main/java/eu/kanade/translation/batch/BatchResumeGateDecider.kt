package eu.kanade.translation.batch

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.hasCurrentInpaintMask
import eu.kanade.translation.model.hasCurrentInpaintResult

/**
 * Batch resume gate, extracted from TranslationPipeline.translateBatch.
 *
 * Given a (possibly null) persisted [PageTranslation] for a page, decides how
 * much of the ONNX phase to skip on resume:
 *  - [Decision.FULL]          — run recognition + inpaint + render
 *  - [Decision.INPAINT_ONLY]  — OCR done and mask captured, but cleaned image
 *                               missing/stale — re-inpaint + render
 *  - [Decision.SKIP_ALL]      — OCR done, durable cleaned image present, mask
 *                               current — render only (or fully skip)
 *
 * Pure over the public PageTranslation model; JVM-testable without the heavy
 * pipeline object. The pipeline resolves the page once and calls [decide].
 */
internal object BatchResumeGateDecider {

    internal enum class Decision { SKIP_ALL, INPAINT_ONLY, FULL }

    internal fun decide(page: PageTranslation?): Decision {
        if (page == null) return Decision.FULL
        val ocrReady = page.ocrStatus == StageStatus.READY
        val hasMask = page.hasCurrentInpaintMask
        val durableCleaned = page.cleanedImageName != null &&
            page.inpaintStatus == StageStatus.READY &&
            page.hasCurrentInpaintResult
        return when {
            ocrReady && hasMask && durableCleaned -> Decision.SKIP_ALL
            ocrReady && hasMask -> Decision.INPAINT_ONLY
            else -> Decision.FULL
        }
    }
}
