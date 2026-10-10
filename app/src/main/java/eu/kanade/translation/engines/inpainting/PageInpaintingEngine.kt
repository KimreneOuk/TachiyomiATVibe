package eu.kanade.translation.engines.inpainting
import android.graphics.Bitmap
import eu.kanade.translation.engines.inpainting.aot.AOTInpainting
import eu.kanade.translation.engines.runtime.EngineMemoryBudget
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.recordAttemptFailure
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class PageInpaintingEngine(
    private val mode: InpaintingMode,
    private val inpainter: AOTInpainting = AOTInpainting(),
    private val qualityFallbackPref: () -> Boolean = {
        Injekt.get<TranslationPreferences>().translationInpaintQualityFallback().get()
    },
    private val neuralModel: NeuralInpaintModel = NeuralInpaintModel.AOT_GAN,
) {
    private val neuralModelDisplayName = when (neuralModel) {
        NeuralInpaintModel.LAMA_MANGA -> "LaMa Manga"
        NeuralInpaintModel.LAMA_MANGA_FP16 -> "LaMa Manga FP16"
        NeuralInpaintModel.AOT_GAN -> "AOT-GAN"
    }

    fun inpaint(bitmap: Bitmap, pageTranslation: PageTranslation): Bitmap? {
        // Persisted masks and detector-only regions can outlive OCR blocks.
        // Only an empty erase plan makes inpainting a no-op.
        val input = PageInpaintingPlanner.build(pageTranslation)
        if (input.isEmpty) {
            // Do not inherit lastRunDegraded from a previous page using the
            // same inpainter instance when this page needs no backend work.
            markReady(pageTranslation, degraded = false)
            return null
        }

        return try {
            pageTranslation.inpaintStatus = StageStatus.RUNNING
            pageTranslation.updatedAt = System.currentTimeMillis()
            val neuralAvailable = inpainter.isInitialized()
            logcat(LogPriority.INFO) {
                "Page inpainting input: boxes=${input.boxes.size} extraDetector=${input.extraDetectorCount} " +
                    "labels=${input.labels.groupingBy {
                        it
                    }.eachCount()} mode=$mode neural=$neuralAvailable"
            }
            EngineMemoryBudget.logSnapshot(
                "before_inpaint",
                bitmap.width,
                bitmap.height,
                "boxes=${input.boxes.size}",
            )
            // Neural modes fail without sessions unless the preference allows
            // classical fallback. Read the setting live for every page.
            if (mode.resolveDispatch(neuralAvailable = true).usesNeural && !neuralAvailable) {
                val fallbackAllowed = try {
                    qualityFallbackPref()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "Could not read the neural inpainting fallback preference; keeping it disabled" }
                    false
                }
                if (fallbackAllowed) {
                    logcat(LogPriority.WARN) {
                        "$mode inpainting: neural $neuralModelDisplayName model not loaded; classical fallback enabled by user setting"
                    }
                } else {
                    throw IllegalStateException(
                        "$mode inpainting unavailable (neural model not loaded); " +
                            "set inpainting to FAST, load the $neuralModelDisplayName model, or enable the neural fallback in Translation settings",
                    )
                }
            }
            val dispatch = mode.resolveDispatch(neuralAvailable)
            val cleaned = inpainter.inpaintRegions(
                image = bitmap,
                boxes = input.boxes,
                labels = input.labels,
                dispatch = dispatch,
                blocks = pageTranslation.blocks,
            )
            markReady(pageTranslation)
            cleaned
        } catch (e: Exception) {
            if (e is CancellationException) throw e
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

    private fun markReady(pageTranslation: PageTranslation, degraded: Boolean = inpainter.lastRunDegraded) {
        pageTranslation.inpaintStatus = StageStatus.READY
        pageTranslation.errorMessage = null
        pageTranslation.inpaintingModeUsed = mode.stampName(
            neuralModel = neuralModel,
            neuralAvailable = inpainter.isInitialized(),
            degraded = degraded,
        )
        pageTranslation.updatedAt = System.currentTimeMillis()
    }
}
