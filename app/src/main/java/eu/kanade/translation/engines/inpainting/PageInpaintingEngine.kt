package eu.kanade.translation.engines.inpainting
import android.graphics.Bitmap
import eu.kanade.translation.engines.inpainting.aot.AOTInpainting
import eu.kanade.translation.engines.runtime.EngineMemoryBudget
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.recordAttemptFailure
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class PageInpaintingEngine(
    private val mode: InpaintingMode,
    private val inpainter: AOTInpainting = AOTInpainting(),
) {
    @Volatile
    private var qualityFallbackEnabled: Boolean = false

    @Volatile
    private var qualityFallbackResolved: Boolean = false
    private fun resolveQualityFallback(): Boolean {
        if (qualityFallbackResolved) return qualityFallbackEnabled
        qualityFallbackEnabled = try {
            Injekt.get<TranslationPreferences>().translationInpaintQualityFallback().get()
        } catch (_: Throwable) {
            false
        }
        qualityFallbackResolved = true
        return qualityFallbackEnabled
    }

    fun inpaint(bitmap: Bitmap, pageTranslation: PageTranslation): Bitmap? {
        // Persisted masks and detector-only regions can outlive OCR blocks.
        // Only an empty erase plan makes inpainting a no-op.
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
                    "labels=${input.labels.groupingBy {
                        it
                    }.eachCount()} mode=$mode neural=${inpainter.isInitialized()}"
            }
            EngineMemoryBudget.logSnapshot(
                "before_inpaint",
                bitmap.width,
                bitmap.height,
                "boxes=${input.boxes.size}",
            )
            // Strict no-fallback by default: QUALITY throws when the neural model
            // is unloaded (was silently downgraded to median-fill FAST). User must
            // opt into QUALITY→FAST fallback via translation_inpaint_quality_fallback.
            if (mode == InpaintingMode.QUALITY && !inpainter.isInitialized()) {
                if (resolveQualityFallback()) {
                    logcat(LogPriority.WARN) {
                        "QUALITY inpainting: neural AOT model not loaded; QUALITY→FAST fallback enabled by user setting"
                    }
                } else {
                    throw IllegalStateException(
                        "QUALITY inpainting unavailable (neural model not loaded); " +
                            "set inpainting to FAST, load the AOT model, or enable the QUALITY→FAST fallback in Translation settings",
                    )
                }
            }
            val effectiveMode = if (mode == InpaintingMode.QUALITY && inpainter.isInitialized()) {
                InpaintingMode.QUALITY
            } else {
                InpaintingMode.FAST
            }
            val cleaned = inpainter.inpaintRegions(
                image = bitmap,
                boxes = input.boxes,
                labels = input.labels,
                mode = effectiveMode,
                blocks = pageTranslation.blocks,
            )
            markReady(pageTranslation)
            cleaned
        } catch (e: Exception) {
            pageTranslation.inpaintStatus = StageStatus.FAILED
            // First terminal stage in the inpaint→render cascade owns the attempt
            // charge; downstream render path no-ops via attemptCharged (idempotent).
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
