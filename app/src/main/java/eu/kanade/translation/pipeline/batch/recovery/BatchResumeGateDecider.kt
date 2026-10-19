package eu.kanade.translation.pipeline.batch.recovery

import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.hasCurrentInpaintMask
import eu.kanade.translation.model.hasExhaustedRetries
import eu.kanade.translation.model.isCleanedImageReady
import eu.kanade.translation.model.isStageRunning
import eu.kanade.translation.model.isTextlessTerminal

/**
 * Selects the earliest batch stage that can safely reuse a page's existing
 * translation data. The caller supplies file validity and current inpainting
 * mode because this policy does not perform storage or preference reads.
 */
internal object BatchResumePolicy {
    enum class NextStage {
        SKIP,
        TRANSLATE,
        INPAINT,
        FULL,
    }

    /**
     * Chooses the minimum safe stage to resume. Physical file validation is
     * supplied by the caller because this pure policy must not perform storage
     * I/O; an invalid/missing file should be passed as false.
     *
     * [inpaintModeMatches] is likewise caller-supplied: true when the page's
     * [PageTranslationView.inpaintingModeUsed] matches the current preference (or is
     * null/legacy). A FAST->QUALITY switch passes false so a stale FAST cleaned
     * image is re-inpainted under the new mode instead of being served as-is.
     */
    fun nextStage(
        page: PageTranslationView?,
        cleanedFileValid: Boolean,
        inpaintModeMatches: Boolean = true,
    ): NextStage {
        if (page == null) return NextStage.FULL
        val hasCommittedTranslation =
            page.translationStatus in setOf(StageStatus.READY, StageStatus.PARTIAL) &&
                page.blocks.any { it.translation.isNotBlank() }
        val cleanImageReady = page.isCleanedImageReady || page.originalImageFallback
        if (hasCommittedTranslation && cleanImageReady && cleanedFileValid && inpaintModeMatches) {
            return NextStage.SKIP
        }
        if (page.hasExhaustedRetries || page.isTextlessTerminal) {
            return NextStage.SKIP
        }
        if (cleanImageReady &&
            cleanedFileValid &&
            inpaintModeMatches &&
            page.ocrStatus == StageStatus.READY &&
            page.hasCurrentInpaintMask
        ) {
            return NextStage.TRANSLATE
        }
        if (page.ocrStatus == StageStatus.READY && page.hasCurrentInpaintMask) {
            return NextStage.INPAINT
        }
        return NextStage.FULL
    }
}

/**
 * Selects which batch stages can be reused from the current page snapshot.
 *
 * Given a (possibly null) persisted [PageTranslation] for a page, decides how
 * much of the ONNX phase to skip on resume:
 *  - [Decision.FULL]          — run recognition + inpaint
 *  - [Decision.INPAINT_ONLY]  — OCR done and mask captured, but cleaned image
 *                               missing/stale — re-inpaint
 *  - [Decision.SKIP_ALL]      — OCR and cleaned-image artifacts are current,
 *                               so the producer stages can be skipped
 *
 * Pure over the public PageTranslation model; JVM-testable without the heavy
 * pipeline object. The pipeline resolves the page once and calls [decide].
 */
internal object BatchResumeGateDecider {

    internal enum class Decision { SKIP_ALL, INPAINT_ONLY, FULL }

    internal fun decide(
        page: PageTranslationView?,
        cleanedFileValid: Boolean = true,
        inpaintModeMatches: Boolean = true,
    ): Decision {
        // A RUNNING state at batch entry is a stranded/in-flight marker from a
        // previous attempt; the batch must rebuild that page rather than
        // treating it as a completed resume candidate.
        if (page?.isStageRunning == true && page.ocrStatus != StageStatus.READY) return Decision.FULL
        return when (BatchResumePolicy.nextStage(page, cleanedFileValid, inpaintModeMatches)) {
            BatchResumePolicy.NextStage.SKIP,
            BatchResumePolicy.NextStage.TRANSLATE,
            -> Decision.SKIP_ALL
            BatchResumePolicy.NextStage.INPAINT -> Decision.INPAINT_ONLY
            BatchResumePolicy.NextStage.FULL -> Decision.FULL
        }
    }
}
