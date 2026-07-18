package eu.kanade.translation.batch

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.isStageRunning
import eu.kanade.translation.scheduling.TranslationLifecyclePolicy

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

    internal fun decide(
        page: PageTranslation?,
        cleanedFileValid: Boolean = true,
        inpaintModeMatches: Boolean = true,
    ): Decision {
        // A RUNNING state at batch entry is a stranded/in-flight marker from a
        // previous attempt; the batch must rebuild that page rather than
        // treating it as a completed resume candidate.
        if (page?.isStageRunning == true && page.ocrStatus != StageStatus.READY) return Decision.FULL
        return when (TranslationLifecyclePolicy.nextStage(page, cleanedFileValid, inpaintModeMatches)) {
            TranslationLifecyclePolicy.NextStage.SKIP,
            TranslationLifecyclePolicy.NextStage.RENDER,
            -> Decision.SKIP_ALL
            TranslationLifecyclePolicy.NextStage.INPAINT -> Decision.INPAINT_ONLY
            TranslationLifecyclePolicy.NextStage.FULL -> Decision.FULL
        }
    }
}
