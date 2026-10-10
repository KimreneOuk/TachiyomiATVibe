package eu.kanade.translation.engines.inpainting

import android.graphics.Bitmap
import eu.kanade.translation.engines.inpainting.aot.AOTInpainting
import eu.kanade.translation.engines.inpainting.litert.LiteRTMangaInpaintingEngine
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
    private val litertInpainter: LiteRTMangaInpaintingEngine? = null,
    private val qualityFallbackPref: () -> Boolean = {
        Injekt.get<TranslationPreferences>().translationInpaintQualityFallback().get()
    },
    private val neuralModel: NeuralInpaintModel = NeuralInpaintModel.LAMA_LITERT_GPU,
) {
    private val neuralModelDisplayName = when (neuralModel) {
        NeuralInpaintModel.LAMA_LITERT_GPU -> "Manga LaMa (LiteRT GPU)"
        NeuralInpaintModel.LAMA_MANGA -> "LaMa Manga"
        NeuralInpaintModel.LAMA_MANGA_FP16 -> "LaMa Manga FP16"
        NeuralInpaintModel.LAMA_512_INT8 -> "LaMa 512 INT8"
        NeuralInpaintModel.LAMA_512_FP16 -> "LaMa 512 FP16"
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
            val neuralAvailable = if (neuralModel == NeuralInpaintModel.LAMA_LITERT_GPU) {
                litertInpainter?.isAvailable() == true
            } else {
                inpainter.isInitialized()
            }
            logcat(LogPriority.INFO) {
                "Page inpainting input: boxes=${input.boxes.size} extraDetector=${input.extraDetectorCount} " +
                    "labels=${input.labels.groupingBy {
                        it
                    }.eachCount()} mode=$mode neuralModel=$neuralModel neuralAvailable=$neuralAvailable"
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

            val cleaned = if (neuralModel == NeuralInpaintModel.LAMA_LITERT_GPU && mode != InpaintingMode.FAST) {
                val lama = litertInpainter
                if (lama != null && lama.isAvailable()) {
                    lama.inpaintRegions(
                        image = bitmap,
                        boxes = input.boxes,
                        labels = input.labels,
                        blocks = pageTranslation.blocks,
                    )
                } else {
                    val dispatch = mode.resolveDispatch(neuralAvailable)
                    inpainter.inpaintRegions(
                        image = bitmap,
                        boxes = input.boxes,
                        labels = input.labels,
                        dispatch = dispatch,
                        blocks = pageTranslation.blocks,
                    )
                }
            } else {
                val dispatch = mode.resolveDispatch(neuralAvailable)
                inpainter.inpaintRegions(
                    image = bitmap,
                    boxes = input.boxes,
                    labels = input.labels,
                    dispatch = dispatch,
                    blocks = pageTranslation.blocks,
                )
            }
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
        litertInpainter?.close()
    }

    private fun markReady(pageTranslation: PageTranslation, degraded: Boolean = inpainter.lastRunDegraded) {
        pageTranslation.inpaintStatus = StageStatus.READY
        pageTranslation.errorMessage = null
        pageTranslation.inpaintingModeUsed = mode.stampName(
            neuralModel = neuralModel,
            neuralAvailable = if (neuralModel == NeuralInpaintModel.LAMA_LITERT_GPU) litertInpainter?.isAvailable() == true else inpainter.isInitialized(),
            degraded = degraded,
        )
        pageTranslation.updatedAt = System.currentTimeMillis()
    }
}
