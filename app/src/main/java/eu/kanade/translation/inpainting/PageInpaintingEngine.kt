package eu.kanade.translation.inpainting

import android.graphics.Bitmap
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
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
            )
            markReady(pageTranslation)
            cleaned
        } catch (e: Exception) {
            pageTranslation.inpaintStatus = StageStatus.FAILED
            pageTranslation.retryCount++
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
