package eu.kanade.translation.inpainting

import android.graphics.Bitmap
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.recordAttemptFailure
import eu.kanade.translation.util.TranslationMemoryBudget
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class PageInpaintingEngine(
    private val mode: InpaintingMode,
    private val inpainter: AOTInpainting = AOTInpainting(),
) {

    fun inpaint(bitmap: Bitmap, pageTranslation: PageTranslation): Bitmap? {
        if (pageTranslation.blocks.isEmpty()) {
            markReady(pageTranslation)
            return null
        }

        val input = PageInpaintingPlanner.build(pageTranslation)
        if (input.isEmpty) {
            markReady(pageTranslation)
            return null
        }

        return try {
            pageTranslation.inpaintStatus = StageStatus.RUNNING
            pageTranslation.updatedAt = System.currentTimeMillis()
            logcat(LogPriority.INFO) {
                "Page inpainting input: boxes=${input.boxes.size} extraDetector=${input.extraDetectorCount} " +
                    "labels=${input.labels.groupingBy { it }.eachCount()} mode=$mode neural=${inpainter.isInitialized()}"
            }
            TranslationMemoryBudget.logSnapshot(
                "before_inpaint",
                bitmap.width,
                bitmap.height,
                "boxes=${input.boxes.size}",
            )
            // TachiyomiAT: strict no-fallback. The old code silently downgraded
            // QUALITY → FAST when the neural inpainter wasn't initialized,
            // producing a visibly worse clean (median-fill instead of AOT
            // reconstruction) with no signal to the user that their chosen
            // QUALITY mode didn't actually run. Throwing lets the stage's
            // try/catch mark the page FAILED with this message, so the user
            // sees "QUALITY inpainting unavailable" and can switch to FAST or
            // fix the model load — instead of a silent quality regression.
            if (mode == InpaintingMode.QUALITY && !inpainter.isInitialized()) {
                throw IllegalStateException(
                    "QUALITY inpainting unavailable (neural model not loaded); " +
                        "set inpainting to FAST or load the AOT model",
                )
            }
            val effectiveMode = if (mode == InpaintingMode.QUALITY) {
                InpaintingMode.QUALITY
            } else {
                InpaintingMode.FAST
            }
            val cleaned = inpainter.inpaintRegions(
                image = bitmap,
                boxes = input.boxes,
                labels = input.labels,
                mode = effectiveMode,
            )
            markReady(pageTranslation)
            cleaned
        } catch (e: Exception) {
            pageTranslation.inpaintStatus = StageStatus.FAILED
            // TachiyomiAT: charge the attempt exactly once. This is the FIRST
            // terminal stage in the inpaint→render cascade, so it owns the
            // attemptCount increment; the downstream render-block path calls
            // recordAttemptFailure() too but it no-ops once attemptCharged is
            // set (idempotent within an attempt). Replaces the old bare
            // `retryCount++`, which double-counted with the render-block
            // increment and tripped exhaustion after one transient failure
            // (see PageTranslationState.recordAttemptFailure / MAX_STAGE_RETRIES).
            pageTranslation.recordAttemptFailure()
            pageTranslation.errorMessage = e.message
            pageTranslation.updatedAt = System.currentTimeMillis()
            logcat(LogPriority.WARN, e) { "Page inpainting failed" }
            null
        }
    }

    fun close() {
        inpainter.close()
    }

    private fun markReady(pageTranslation: PageTranslation) {
        pageTranslation.inpaintStatus = StageStatus.READY
        pageTranslation.errorMessage = null
        pageTranslation.updatedAt = System.currentTimeMillis()
    }
}
