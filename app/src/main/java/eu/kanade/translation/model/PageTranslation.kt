package eu.kanade.translation.model

import android.graphics.Bitmap
import eu.kanade.translation.detection.Detection
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class PageTranslation(
    var blocks: MutableList<TranslationBlock> = mutableListOf(),
    var imgWidth: Float = 0f,
    var imgHeight: Float = 0f,
    var cleanedImageName: String? = null,
    var renderedImageName: String? = null,
    var renderQuality: String = RenderQuality.UNKNOWN,
    var renderedWidth: Int = 0,
    var renderedHeight: Int = 0,
    var recognitionEngine: String? = null,
    var detectionCount: Int = 0,
    var ocrBlockCount: Int = 0,
    var decodeSampleSize: Int = 1,
    var originalImgWidth: Float = 0f,
    var originalImgHeight: Float = 0f,
    var ocrStatus: String = StageStatus.PENDING,
    var translationStatus: String = StageStatus.PENDING,
    var inpaintStatus: String = StageStatus.PENDING,
    var renderStatus: String = StageStatus.PENDING,
    var errorMessage: String? = null,
    var updatedAt: Long = 0L,
    var sourceFileName: String? = null,
    // TachiyomiAT: counts how many times a stage on this page has been retried
    // after a failure. Persisted (serialized) so it survives a chapter reopen —
    // see [StageStatus.MAX_STAGE_RETRIES] and the retry-exhaustion contract in
    // docs/TRANSLATION_MODULE.md. This is a raw, per-failure increment kept for
    // diagnostics/back-compat; do NOT use it directly to gate auto-scheduling —
    // [PageTranslation.hasExhaustedRetries] keys off [attemptCount] instead.
    // The reason it must not gate exhaustion: a single reader-path attempt can
    // touch multiple stages (OCR → inpaint → render) and an inpaint failure
    // naturally cascades into a render failure, so per-failure increments could
    // double-count within ONE attempt and trip exhaustion after a single
    // transient failure — permanently blacklisting the page (the
    // "cannot reprocess / retranslate" bug). [attemptCount] counts DISTINCT
    // attempts, which is the correct granularity for exhaustion.
    var retryCount: Int = 0,
    // Incremented when the rendered file's bytes are rewritten. The file name is
    // stable (<page>.rendered.png for new renders), so UI dedup must not key on
    // the name alone.
    var inpaintRevision: Int = 0,
    var renderRevision: Long = 0L,
    /**
     * TachiyomiAT: SERIALIZABLE inpaint mask captured at OCR time.
     *
     * This is the durable record of every region the inpainter must erase: the
     * bubble box + text box of every OCR'd block, the detector-only regions
     * that were NOT OCR'd (filtered by dedupe/suppression but still need
     * erasing), plus any watermark/hidden-text regions. It is computed ONCE at
     * the end of [PageRecognitionEngine.analyze] (see [PageInpaintingPlanner]),
     * so it is independent of:
     *   - translation-stage filtering (watermark blocks are removed AFTER
     *     translate; without this field the resumed inpaint would lose the
     *     boxes and leave watermark text visible), and
     *   - the transient [allTextDetections] list below, which is `@Transient`
     *     and so is lost on serialize/deserialize.
     *
     * Bumping [CURRENT_INPAINT_REVISION] invalidates pre-existing chapters whose
     * persisted mask predates this field (they re-inpaint from a fresh OCR pass).
     */
    var inpaintMaskBoxes: List<InpaintMaskBox> = emptyList(),
) {
    @Transient
    var cleanedBitmap: Bitmap? = null

    /**
     * TachiyomiAT: number of DISTINCT page translation attempts that have ended
     * in a terminal failure for this page. NOT serialized — it is an in-memory
     * counter that resets to its default (0) on store reopen / process restart,
     * so a page that failed in a prior process is not permanently treated as
     * exhausted. This is the correct granularity for retry-exhaustion: an
     * attempt is OCR → inpaint → translate → render for one page, and a single
     * transient failure (the dominant case on the 6 GB / 20-30% heap target per
     * AGENT.md, expected to recover on the next attempt per memory contracts
     * #4/#6/#10) must count as ONE attempt, not one per failed stage.
     *
     * [PageTranslation.hasExhaustedRetries] keys off this, NOT [retryCount].
     * [recordAttemptFailure] increments it exactly once per attempt (see its
     * doc for the "first terminal stage owns the count" rule). Reset to 0 by
     * [PageTranslation.prepareForcedRetry] and by the start-of-attempt gate in
     * [TranslationPipeline], so the manual re-translate button always re-admits
     * a failed page.
     *
     * Why @Transient and not persisted: persisting it would recreate the
     * original "one transient failure → permanently blacklisted" bug after a
     * reopen. The transient-heap-pressure failure model requires that a fresh
     * process — with its native pools and heap reclaimed — gets a fresh attempt
     * budget. Diagnostics still see raw per-failure counts via [retryCount].
     */
    @Transient
    var attemptCount: Int = 0

    /**
     * TachiyomiAT: per-attempt idempotency flag for [recordAttemptFailure].
     * Set true the first time an attempt's terminal failure charges
     * [attemptCount]; reset by [prepareForcedRetry] / [resetAttemptCharge] at
     * the start of a fresh attempt. NOT serialized. See
     * [recordAttemptFailure] for why the guard cannot use [isStageFailed]
     * (callers set the stage FAILED before calling the helper).
     */
    @Transient
    var attemptCharged: Boolean = false

    /**
     * TachiyomiAT: all text detections from the recognition stage, carried
     * per-page so they survive from analyze() into inpaint() without relying
     * on shared mutable state on the (singleton) recognition engine. This
     * removes a race where concurrent pages overwrote each other's detections.
     *
     * NOT serialized. The durable equivalent is [inpaintMaskBoxes] above; the
     * inpaint planner reads [inpaintMaskBoxes] when present (the resume path)
     * and falls back to recomputing from [allTextDetections] only on the live
     * (non-resumed) path where this field is still populated.
     */
    @Transient
    var allTextDetections: List<Detection> = emptyList()

    companion object {
        /**
         * Bumped 9 -> 10: the erase mask is now render-aware — a block whose OCR
         * text is blank no longer contributes its box, so unread regions keep
         * their original pixels instead of being erased to an empty void. Pre-10
         * chapters carry a mask computed by the old logic that erases blank-text
         * regions; force them through a fresh OCR + inpaint so the new mask
         * semantics take effect. Earlier: 8 -> 9 added [inpaintMaskBoxes] +
         * detector-only/watermark erase.
         */
        const val CURRENT_INPAINT_REVISION = 10
        val EMPTY = PageTranslation()
    }
}

/**
 * TachiyomiAT: a single serializable inpaint erase region.
 *
 * Mirrors exactly what the inpainter consumes: an axis-aligned pixel box
 * `[x1, y1, x2, y2]` plus the label the AOT model expects (0 = bubble
 * background, 1 = OCR'd text, 2 = detector-only text). Serialized as part of
 * [PageTranslation.inpaintMaskBoxes] so the mask survives process death /
 * store reopen / batch resume.
 *
 * Deliberately a separate type from [Detection]: a Detection carries score +
 * className which the inpainter does not need, and keeping the persisted shape
 * minimal shrinks the on-disk translation file.
 */
@Serializable
data class InpaintMaskBox(
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
    val label: Int,
) {
    /** Compact IntArray view for the inpainter's `boxes: List<IntArray>` API. */
    fun toIntArray(): IntArray = intArrayOf(x1, y1, x2, y2)
}

object StageStatus {
    const val PENDING = "PENDING"
    const val RUNNING = "RUNNING"
    const val READY = "READY"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
    /**
     * TachiyomiAT: the translate stage produced SOME valid translations AND
     * some missing/rejected ones, but NOT zero. Distinct from READY (all
     * blocks translated) and FAILED (none translated). A PARTIAL page is
     * still RENDERED — valid blocks are drawn, the missing regions stay
     * blank on the cleaned image — so a single bad block no longer skips the
     * whole page. Set by [TranslationBlockValidation.applyTo]; the render
     * gates in [TranslationPipeline] admit READY and PARTIAL alike.
     */
    const val PARTIAL = "PARTIAL"

    /**
     * TachiyomiAT: maximum number of DISTINCT page-translation attempts
     * auto-translate will make for a page before treating it as exhausted
     * ([PageTranslation.hasExhaustedRetries]). Counts attempts, not per-stage
     * failures: a single reader-path attempt that cascades OCR→inpaint→render
     * and fails at inpaint counts as ONE attempt (see
     * [PageTranslation.attemptCount]). Bounds the retry loop so a genuinely
     * broken page (corrupt image, persistent OOM) can't spin forever holding
     * the singleton translator permit, while still recovering the dominant case
     * — a transient heap-pressure failure that recovers on the next attempt
     * after native pools are reclaimed (memory contracts #4/#6/#10).
     *
     * The manual per-page translate button is NOT bound by this: it routes
     * through [TranslationPipeline.translateSinglePage] with `force = true`,
     * which calls [PageTranslation.prepareForcedRetry] to reset the attempt
     * counter and re-admit the page. So a user can always force another attempt
     * on a page auto-translate has given up on.
     *
     * NOTE: this is NOT serialized through [PageTranslation.attemptCount]
     * (which is `@Transient`), so exhaustion does NOT survive a process
     * restart. That is intentional — see the attemptCount field doc.
     */
    const val MAX_STAGE_RETRIES = 2
}

object RenderQuality {
    const val UNKNOWN = "UNKNOWN"
    const val FULL = "FULL"
    const val SIZE_LIMITED = "SIZE_LIMITED"
}

@Serializable
data class TranslationBlock(
    var text: String,
    var translation: String = "",
    var width: Float,
    var height: Float,
    var x: Float,
    var y: Float,
    var symHeight: Float,
    var symWidth: Float,
    val angle: Float,
    val label: Int = 1,
    val score: Float = 1f,
    val parentX: Float = 0f,
    val parentY: Float = 0f,
    val parentWidth: Float = 0f,
    val parentHeight: Float = 0f,
    // TachiyomiAT: textColor is re-derived after inpainting against the cleaned
    // bitmap (RenderColorEstimator.recomputeFor) so "dark inpaint → light text"
    // holds. strokeColor is re-derived by PageTextRenderer (luma-inverse of the
    // text) and strokeWidth is owned by TextLayoutPlanner (font-derived); those
    // two fields are retained on the model for serialization backward-compat
    // but are NO LONGER read at render time. `var` so they can be re-derived;
    // serialization is field-name based, so this is backward compatible.
    var textColor: Long = 0xFF000000,
    var strokeColor: Long = 0xFFFFFFFF,
    var strokeWidth: Float = 0f,
    val direction: String = "LTR",
)
