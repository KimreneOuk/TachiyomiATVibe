package eu.kanade.translation.util

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlin.math.max

object TranslationMemoryBudget {
    private const val MIB = 1024L * 1024L

    // Keep one translation page well below the app heap cap. Android heap is much smaller than physical RAM.
    private const val MAX_SINGLE_PAGE_BUDGET_BYTES = 384L * MIB
    private const val MIN_SINGLE_PAGE_BUDGET_BYTES = 96L * MIB
    private const val MAX_FULL_RES_DECODE_PIXELS = 45_000_000L
    private const val NEURAL_INPAINT_PEAK_MULTIPLIER = 10L

    data class Snapshot(
        val maxHeapBytes: Long,
        val usedHeapBytes: Long,
        val freeHeapBytes: Long,
        val availableHeapBytes: Long,
    )

    fun snapshot(): Snapshot {
        val runtime = Runtime.getRuntime()
        val maxHeap = runtime.maxMemory()
        val totalHeap = runtime.totalMemory()
        val freeHeap = runtime.freeMemory()
        val usedHeap = totalHeap - freeHeap
        return Snapshot(
            maxHeapBytes = maxHeap,
            usedHeapBytes = usedHeap,
            freeHeapBytes = freeHeap,
            availableHeapBytes = max(0L, maxHeap - usedHeap),
        )
    }

    fun chooseDecodeSampleSize(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        val pixels = width.toLong() * height.toLong()
        val rawBitmapBytes = pixels * 4L

        if (pixels <= MAX_FULL_RES_DECODE_PIXELS && rawBitmapBytes <= snapshot().availableHeapBytes * 40L / 100L) {
            return 1
        }

        var sample = 1
        while (true) {
            val sampledPixels = pixels / (sample.toLong() * sample.toLong())
            val sampledBytes = sampledPixels * 4L
            val fitsHugePageLimit = sampledPixels <= MAX_FULL_RES_DECODE_PIXELS
            val fitsHeap = sampledBytes <= snapshot().availableHeapBytes * 35L / 100L
            if (fitsHugePageLimit && fitsHeap) break
            sample *= 2
        }
        return sample
    }

    fun canRunNeuralInpaint(pageWidth: Int, pageHeight: Int, cropWidth: Int, cropHeight: Int): Boolean {
        if (pageWidth <= 0 || pageHeight <= 0 || cropWidth <= 0 || cropHeight <= 0) return false
        val pagePixels = pageWidth.toLong() * pageHeight.toLong()
        val cropPixels = cropWidth.toLong() * cropHeight.toLong()
        val estimatedPeak = (pagePixels * 4L * 3L) + (cropPixels * 4L * NEURAL_INPAINT_PEAK_MULTIPLIER)
        return estimatedPeak <= singlePageBudgetBytes()
    }

    fun logSnapshot(tag: String, width: Int? = null, height: Int? = null, extra: String = "") {
        val snapshot = snapshot()
        val dims = if (width != null && height != null) " page=${width}x$height" else ""
        logcat(LogPriority.INFO) {
            "[translation_mem] $tag$dims " +
                "heap=${snapshot.usedHeapBytes.toMiB()}MiB/${snapshot.maxHeapBytes.toMiB()}MiB " +
                "avail=${snapshot.availableHeapBytes.toMiB()}MiB budget=${singlePageBudgetBytes().toMiB()}MiB $extra"
        }
    }

    private fun singlePageBudgetBytes(): Long {
        val available = snapshot().availableHeapBytes
        return (available * 5L / 10L)
            .coerceAtLeast(MIN_SINGLE_PAGE_BUDGET_BYTES)
            .coerceAtMost(MAX_SINGLE_PAGE_BUDGET_BYTES)
    }

    private fun Long.toMiB(): Long = this / MIB
}
