package eu.kanade.translation.engines.runtime

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Debug
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.max

/** Heap and native-memory facts used by OCR and inpainting engines. */
object EngineMemoryBudget {
    private const val MIB = 1024L * 1024L
    private const val MIN_SINGLE_PAGE_BUDGET_BYTES = 96L * MIB
    private const val MAX_SINGLE_PAGE_BUDGET_BYTES = 384L * MIB
    private const val NEURAL_INPAINT_PEAK_MULTIPLIER = 10L

    // ORT model weights and execution arenas live in native/system memory, not the
    // managed heap. Keep these reserves separate from bitmap/tensor heap estimates.
    // AOT currently keeps the fixed and dynamic graphs alive independently.
    internal const val AOT_NATIVE_RESERVE_PER_SESSION_BYTES = 96L * MIB
    internal const val AOT_INFERENCE_SYSTEM_RESERVE_BYTES = 64L * MIB

    data class HeapSnapshot(
        val maxHeapBytes: Long,
        val usedHeapBytes: Long,
        val freeHeapBytes: Long,
        val availableHeapBytes: Long,
    )

    fun heapSnapshot(): HeapSnapshot {
        val runtime = Runtime.getRuntime()
        val maxHeap = runtime.maxMemory()
        val totalHeap = runtime.totalMemory()
        val freeHeap = runtime.freeMemory()
        val usedHeap = totalHeap - freeHeap
        return HeapSnapshot(
            maxHeapBytes = maxHeap,
            usedHeapBytes = usedHeap,
            freeHeapBytes = freeHeap,
            availableHeapBytes = max(0L, maxHeap - usedHeap),
        )
    }

    data class NeuralInpaintDecision(
        val canRun: Boolean,
        val mode: String,
        val sessionCount: Int,
        val heapRequiredBytes: Long,
        val heapAvailableBytes: Long,
        val nativeSystemReserveBytes: Long,
        val systemHeadroomBytes: Long?,
        val reason: String?,
    )

    fun neuralInpaintDecision(
        pageWidth: Int,
        pageHeight: Int,
        cropWidth: Int,
        cropHeight: Int,
        sessionCount: Int,
        snapshot: HeapSnapshot = heapSnapshot(),
        systemHeadroomBytes: Long? = systemHeadroomBytes(),
    ): NeuralInpaintDecision {
        val boundedSessionCount = sessionCount.coerceAtLeast(0)
        val mode = when (boundedSessionCount) {
            0 -> "classical_fallback"
            1 -> "single_session"
            else -> "dual_session"
        }
        val nativeReserve = neuralNativeSystemReserveBytes(boundedSessionCount)
        if (pageWidth <= 0 || pageHeight <= 0 || cropWidth <= 0 || cropHeight <= 0 || boundedSessionCount == 0) {
            return NeuralInpaintDecision(
                canRun = false,
                mode = mode,
                sessionCount = boundedSessionCount,
                heapRequiredBytes = 0L,
                heapAvailableBytes = snapshot.availableHeapBytes,
                nativeSystemReserveBytes = nativeReserve,
                systemHeadroomBytes = systemHeadroomBytes,
                reason = if (boundedSessionCount == 0) "no_neural_session" else "invalid_dimensions",
            )
        }

        val pagePixels = pageWidth.toLong() * pageHeight.toLong()
        val cropPixels = cropWidth.toLong() * cropHeight.toLong()
        val estimatedHeapPeak = (pagePixels * 4L * 3L) +
            (cropPixels * 4L * NEURAL_INPAINT_PEAK_MULTIPLIER)
        val heapBudget = singlePageBudgetBytes(snapshot.availableHeapBytes)
        val heapFits = estimatedHeapPeak <= heapBudget
        val systemFits = systemHeadroomBytes == null || systemHeadroomBytes >= nativeReserve
        val reason = when {
            !heapFits -> "heap_budget"
            !systemFits -> "native_system_reserve"
            else -> null
        }
        return NeuralInpaintDecision(
            canRun = reason == null,
            mode = mode,
            sessionCount = boundedSessionCount,
            heapRequiredBytes = estimatedHeapPeak,
            heapAvailableBytes = snapshot.availableHeapBytes,
            nativeSystemReserveBytes = nativeReserve,
            systemHeadroomBytes = systemHeadroomBytes,
            reason = reason,
        )
    }

    internal fun neuralNativeSystemReserveBytes(sessionCount: Int): Long {
        return sessionCount.coerceAtLeast(0).toLong() * AOT_NATIVE_RESERVE_PER_SESSION_BYTES +
            if (sessionCount > 0) AOT_INFERENCE_SYSTEM_RESERVE_BYTES else 0L
    }

    data class NnapiMemorySnapshot(
        val availableHeapBytes: Long,
        val systemHeadroomBytes: Long?,
        val lowMemory: Boolean,
    )

    fun nnapiMemorySnapshot(): NnapiMemorySnapshot {
        val heap = heapSnapshot().availableHeapBytes
        val app = try {
            Injekt.get<Application>()
        } catch (_: Throwable) {
            return NnapiMemorySnapshot(heap, null, false)
        }
        val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return NnapiMemorySnapshot(heap, null, false)
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return NnapiMemorySnapshot(
            availableHeapBytes = heap,
            systemHeadroomBytes = max(0L, memInfo.availMem - memInfo.threshold),
            lowMemory = memInfo.lowMemory,
        )
    }

    fun systemHeadroomBytes(): Long? {
        val app = try {
            Injekt.get<Application>()
        } catch (_: Throwable) {
            return null
        }
        val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return if (memInfo.lowMemory) 0L else max(0L, memInfo.availMem - memInfo.threshold)
    }

    fun logSnapshot(tag: String, width: Int? = null, height: Int? = null, extra: String = "") {
        if (!diagnosticsEnabled) return
        val snapshot = heapSnapshot()
        val nativeHeap = Debug.getNativeHeapAllocatedSize()
        val gcCount = try {
            Debug.getRuntimeStat("art.gc.gc-count")
        } catch (_: Throwable) {
            null
        }
        val gcTime = try {
            Debug.getRuntimeStat("art.gc.gc-time")
        } catch (_: Throwable) {
            null
        }

        val app = Injekt.get<Application>()
        val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        var lowMem = false
        var sysAvail = 0L
        if (activityManager != null) {
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            lowMem = memInfo.lowMemory
            sysAvail = memInfo.availMem
        }

        val dims = if (width != null && height != null) " page=${width}x$height" else ""
        logcat(LogPriority.INFO) {
            "[translation_mem] $tag$dims " +
                "heap=${snapshot.usedHeapBytes.toMiB()}MiB/${snapshot.maxHeapBytes.toMiB()}MiB " +
                "avail=${snapshot.availableHeapBytes.toMiB()}MiB " +
                "nativeAlloc=${nativeHeap.toMiB()}MiB " +
                "sysAvail=${sysAvail.toMiB()}MiB (lowMemory=$lowMem) " +
                "gcCount=$gcCount gcTime=${gcTime}ms " +
                "budget=${singlePageBudgetBytes().toMiB()}MiB $extra"
        }
    }

    /**
     * Cached once per process so the diagnostic hot path does not reread preferences.
     * If preferences are unavailable during early initialization, logging remains off.
     */
    private val diagnosticsEnabled: Boolean by lazy {
        try {
            Injekt.get<TranslationPreferences>().translationDiagnostics().get()
        } catch (_: Throwable) {
            false
        }
    }

    private fun singlePageBudgetBytes(availableHeapBytes: Long = heapSnapshot().availableHeapBytes): Long {
        return (availableHeapBytes * 5L / 10L)
            .coerceAtLeast(MIN_SINGLE_PAGE_BUDGET_BYTES)
            .coerceAtMost(MAX_SINGLE_PAGE_BUDGET_BYTES)
    }

    private fun Long.toMiB(): Long = this / MIB
}
