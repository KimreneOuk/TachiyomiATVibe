package eu.kanade.translation.pipeline

import android.content.Context
import android.graphics.Bitmap
import coil3.imageLoader
import eu.kanade.translation.engines.vision.ocr.PageRecognitionEngine
import eu.kanade.translation.engines.vision.ocr.RoiPageRecognitionEngine
import eu.kanade.translation.util.TranslationMemoryBudget
import eu.kanade.translation.util.TranslationMemoryBudget.DecodeDecision
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.pools.BitmapPool

/**
 * Memory-budget governance helpers moved from `TranslationPipeline`
 * ( Phase 1). The pipeline's engine read is injected as a getter.
 */
internal object MemoryGovernance {

    fun forceReleaseNativeBuffers(recognitionEngine: () -> PageRecognitionEngine) {
        try {
            recognitionEngine().forceReleaseNativeBuffers()
        } catch (_: Exception) {}
    }

    fun preflightAnalyzeGate(
        recognitionEngine: () -> PageRecognitionEngine,
        bitmap: Bitmap,
        fileName: String,
    ) {
        if (recognitionEngine() !is RoiPageRecognitionEngine) return
        val decision = TranslationMemoryBudget.canStartAnalyze(bitmap.width, bitmap.height)
        if (decision is TranslationMemoryBudget.MemoryPreflightDecision.Defer) {
            TranslationMemoryBudget.logSnapshot(
                tag = "onnx_analyze_preflight_defer",
                width = bitmap.width,
                height = bitmap.height,
                extra = "file=$fileName reason=${decision.reason}",
            )
            throw LowMemoryRecognitionDeferredException(fileName, bitmap.width, bitmap.height, decision.reason)
        }
    }

    fun preflightInpaintGate(
        recognitionEngine: () -> PageRecognitionEngine,
        bitmap: Bitmap,
        fileName: String,
    ) {
        if (recognitionEngine() !is RoiPageRecognitionEngine) return
        val decision = TranslationMemoryBudget.canStartInpaint(bitmap.width, bitmap.height)
        if (decision is TranslationMemoryBudget.MemoryPreflightDecision.Defer) {
            TranslationMemoryBudget.logSnapshot(
                tag = "onnx_inpaint_preflight_defer",
                width = bitmap.width,
                height = bitmap.height,
                extra = "file=$fileName reason=${decision.reason}",
            )
            throw LowMemoryRecognitionDeferredException(fileName, bitmap.width, bitmap.height, decision.reason)
        }
    }

    fun reclaimTranslationMemory(
        context: Context,
        recognitionEngine: () -> PageRecognitionEngine,
        reason: String,
        trimImageCache: Boolean,
    ) {
        val before = TranslationMemoryBudget.snapshot()
        BitmapPool.releaseAll()
        try {
            recognitionEngine().forceReleaseNativeBuffers()
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "forceReleaseNativeBuffers threw during memory reclaim: $reason" }
        }
        if (trimImageCache) {
            try {
                context.imageLoader.memoryCache?.trimToSize(0)
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Coil memory-cache trim failed during memory reclaim: $reason" }
            }
        }
        System.gc()
        val after = TranslationMemoryBudget.snapshot()
        logcat(LogPriority.WARN) {
            "[translation_reclaim] reason=$reason trimImageCache=$trimImageCache " +
                "heapBefore=${before.usedHeapBytes.toMiB()}MiB/${before.maxHeapBytes.toMiB()}MiB " +
                "availBefore=${before.availableHeapBytes.toMiB()}MiB " +
                "heapAfter=${after.usedHeapBytes.toMiB()}MiB/${after.maxHeapBytes.toMiB()}MiB " +
                "availAfter=${after.availableHeapBytes.toMiB()}MiB"
        }
    }

    fun logDecodeDecision(
        fileName: String,
        width: Int,
        height: Int,
        beforeReclaim: DecodeDecision?,
        afterReclaim: DecodeDecision,
    ) {
        val before = beforeReclaim?.let {
            " before=${it.kind}/sample=${it.sampleSize}/avail=${it.snapshot.availableHeapBytes.toMiB()}MiB"
        } ?: ""
        logcat(LogPriority.INFO) {
            "[translation_decode_decision] file=$fileName page=${width}x$height " +
                "decision=${afterReclaim.kind} sample=${afterReclaim.sampleSize} " +
                "raw=${afterReclaim.rawBitmapBytes.toMiB()}MiB " +
                "sampled=${afterReclaim.sampledBitmapBytes.toMiB()}MiB " +
                "avail=${afterReclaim.snapshot.availableHeapBytes.toMiB()}MiB$before"
        }
    }

    private fun Long.toMiB(): Long = this / (1024L * 1024L)

    fun handleCriticalTranslationOom(
        forceReleaseNativeBuffers: () -> Unit,
        stage: String,
        oom: OutOfMemoryError,
    ) {
        BitmapPool.releaseAll()
        forceReleaseNativeBuffers()
        System.gc()
        TranslationMemoryBudget.logSnapshot(tag = "oom_recovery", extra = "stage=$stage message=${oom.message}")
    }
}
