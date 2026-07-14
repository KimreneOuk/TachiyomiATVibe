package eu.kanade.translation.scheduling

import eu.kanade.translation.model.PageLifecycle
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.hasExhaustedRetries
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isStageRunning
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.hasCurrentInpaintMask
import eu.kanade.translation.model.hasRecognizedTranslation
import eu.kanade.translation.model.isCleanedImageReady
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.lifecycle
import eu.kanade.translation.model.shouldSkipAutoScheduling

/**
 * TachiyomiAT: owns the auto-scheduling policy table.
 *
 * The underlying stage predicates (`shouldSkipAutoScheduling`, `lifecycle`,
 * `hasExhaustedRetries`, …) live as top-level extensions on
 * `PageTranslation` in [eu.kanade.translation.model.PageTranslationState] and
 * are widely imported across the reader, page loaders, and stores. Moving them
 * would churn many files for little gain, so they stay where they are.
 *
 * This object instead provides a single named surface that encodes the "should
 * this page be scheduled vs. skipped" decision in one place (the table described
 * by the refactor plan):
 *
 * ```
 * Done / Textless / Failed(exhausted)  -> skip
 * Running                              -> skip
 * Pending / Cancelled / NeedsRender    -> schedule
 * ```
 *
 * The scheduler calls [shouldSchedule] for admission, while the batch resume
 * gate calls [nextStage] for the minimum safe stage. Storage validation remains
 * outside this pure object and is passed in as a boolean. This keeps the
 * so the decision table is unit-testable independently — wiring it into the
 *
 * Keeping it as a stateless object (no Android, no coroutines) means the
 * decision table is unit-testable in isolation — see
 * `TranslationLifecyclePolicyTest`. Every branch delegates to the existing
 * extensions so behavior is byte-for-byte identical to the previous inline
 * `current.shouldSkipAutoScheduling` checks in `TranslationManager`.
 */
object TranslationLifecyclePolicy {

    enum class NextStage {
        SKIP,
        RENDER,
        INPAINT,
        FULL,
    }

    /**
     * Returns the [PageLifecycle] classification for [page]. Delegates to the
     * existing `PageTranslation.lifecycle` extension so the sealed-subtype
     * mapping stays in one place.
     */
    fun classify(page: PageTranslation?): PageLifecycle {
        return page?.lifecycle ?: PageLifecycle.Pending
    }

    /**
     * True when [page] may be scheduled for auto/single-page translation: it is
     * neither finished, textless, retry-exhausted, nor already running. Mirrors
     * the historical `!page.shouldSkipAutoScheduling` gate.
     *
     * A null page (never tracked) is always schedulable — it has produced no
     * result and is in no stage, so there is work to do.
     */
    fun shouldSchedule(page: PageTranslation?): Boolean {
        if (page == null) return true
        return !page.shouldSkipAutoScheduling
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
        if (page.isCleanedImageReady && cleanedFileValid && inpaintModeMatches &&
            (page.hasRecognizedTranslation ||
                (page.ocrStatus == StageStatus.READY && page.hasCurrentInpaintMask))
        ) {
            return NextStage.RENDER
        }
        if (page.ocrStatus == StageStatus.READY && page.hasCurrentInpaintMask) {
            return NextStage.INPAINT
        }
        return NextStage.FULL
    }

    /**
     * Convenience view over the individual predicates, exposed so the scheduler
     * can log the *reason* a page was skipped without recomputing it. Each flag
     * delegates to the matching extension.
     */
    fun reasons(page: PageTranslation?): SkipReasons {
        val p = page
        return SkipReasons(
            hasRenderedResult = p?.hasRenderedResult == true,
            isRunning = p?.isStageRunning == true,
            exhausted = p?.hasExhaustedRetries == true,
            textless = p?.isTextlessTerminal == true,
        )
    }

    data class SkipReasons(
        val hasRenderedResult: Boolean,
        val isRunning: Boolean,
        val exhausted: Boolean,
        val textless: Boolean,
    ) {
        /** A human-readable label for the first matching skip cause, or null. */
        fun firstReason(): String? = when {
            hasRenderedResult -> "rendered-result-exists"
            isRunning -> "already-running"
            exhausted -> "retries-exhausted"
            textless -> "textless"
            else -> null
        }
    }
}
