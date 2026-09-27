package eu.kanade.translation.pipeline.memory

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import eu.kanade.translation.engines.runtime.EngineMemoryBudget
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.max

object TranslationMemoryBudget {
    private const val MIB = 1024L * 1024L
    private const val GIB = 1024L * MIB

    enum class DeviceMemoryTier {
        BASELINE,
        HIGH,
        FLAGSHIP,
    }

    private const val MAX_FULL_RES_DECODE_PIXELS = 45_000_000L

    fun deviceMemoryTier(): DeviceMemoryTier {
        val app = try {
            Injekt.get<Application>()
        } catch (_: Throwable) {
            return DeviceMemoryTier.BASELINE
        }
        val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return DeviceMemoryTier.BASELINE
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val totalMem = memInfo.totalMem
        return when {
            totalMem >= 14L * GIB -> DeviceMemoryTier.FLAGSHIP
            totalMem >= 7L * GIB -> DeviceMemoryTier.HIGH
            else -> DeviceMemoryTier.BASELINE
        }
    }

    fun recommendedPrefetchCapacity(): Int = when (deviceMemoryTier()) {
        DeviceMemoryTier.FLAGSHIP -> 6
        DeviceMemoryTier.HIGH -> 4
        DeviceMemoryTier.BASELINE -> 2
    }

    fun heldBitmapByteCeiling(): Long = when (deviceMemoryTier()) {
        DeviceMemoryTier.FLAGSHIP -> 384L * MIB
        DeviceMemoryTier.HIGH -> 192L * MIB
        DeviceMemoryTier.BASELINE -> 48L * MIB
    }

    sealed interface MemoryPreflightDecision {
        object Proceed : MemoryPreflightDecision
        data class Defer(val reason: String) : MemoryPreflightDecision
    }

    data class Snapshot(
        val maxHeapBytes: Long,
        val usedHeapBytes: Long,
        val freeHeapBytes: Long,
        val availableHeapBytes: Long,
    )

    enum class DecodeDecisionKind {
        FULL,
        HEAP_CONSTRAINED,
        SOURCE_TOO_LARGE,
    }

    data class DecodeDecision(
        val kind: DecodeDecisionKind,
        val sampleSize: Int,
        val rawBitmapBytes: Long,
        val sampledBitmapBytes: Long,
        val sourcePixels: Long,
        val sampledPixels: Long,
        val snapshot: Snapshot,
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

    fun chooseDecodeSampleSize(
        sourceBytesSize: Long = 0L,
        width: Int,
        height: Int,
    ): Int {
        if (width <= 0 || height <= 0) return 1
        val pixels = width.toLong() * height.toLong()
        val rawBitmapBytes = pixels * 4L
        val available = snapshot().availableHeapBytes

        if (pixels <= MAX_FULL_RES_DECODE_PIXELS && rawBitmapBytes <= available * 25L / 100L) {
            return 1
        }

        var sample = 1
        while (true) {
            val sampledPixels = pixels / (sample.toLong() * sample.toLong())
            val sampledBytes = sampledPixels * 4L
            val fitsHugePageLimit = sampledPixels <= MAX_FULL_RES_DECODE_PIXELS
            val fitsHeap = sampledBytes <= available * 20L / 100L
            if (fitsHugePageLimit && fitsHeap) break
            sample *= 2
        }
        return sample
    }

    fun chooseDecodeDecision(
        width: Int,
        height: Int,
        sourceBytesSize: Long = 0L,
        snapshot: Snapshot = snapshot(),
    ): DecodeDecision {
        if (width <= 0 || height <= 0) {
            return DecodeDecision(
                kind = DecodeDecisionKind.FULL,
                sampleSize = 1,
                rawBitmapBytes = 0L,
                sampledBitmapBytes = 0L,
                sourcePixels = 0L,
                sampledPixels = 0L,
                snapshot = snapshot,
            )
        }
        val pixels = width.toLong() * height.toLong()
        val rawBitmapBytes = pixels * 4L
        val available = snapshot.availableHeapBytes

        if (pixels <= MAX_FULL_RES_DECODE_PIXELS) {
            return if (rawBitmapBytes <= available * 25L / 100L) {
                DecodeDecision(
                    kind = DecodeDecisionKind.FULL,
                    sampleSize = 1,
                    rawBitmapBytes = rawBitmapBytes,
                    sampledBitmapBytes = rawBitmapBytes,
                    sourcePixels = pixels,
                    sampledPixels = pixels,
                    snapshot = snapshot,
                )
            } else {
                val diagnosticSample = heapDiagnosticSampleSize(pixels, available)
                val sampledPixels = pixels / (diagnosticSample.toLong() * diagnosticSample.toLong())
                DecodeDecision(
                    kind = DecodeDecisionKind.HEAP_CONSTRAINED,
                    sampleSize = diagnosticSample,
                    rawBitmapBytes = rawBitmapBytes,
                    sampledBitmapBytes = sampledPixels * 4L,
                    sourcePixels = pixels,
                    sampledPixels = sampledPixels,
                    snapshot = snapshot,
                )
            }
        }

        val sourceSample = sourceLimitSampleSize(pixels)
        val sampledPixels = pixels / (sourceSample.toLong() * sourceSample.toLong())
        val sampledBytes = sampledPixels * 4L
        return if (sampledBytes <= available * 20L / 100L) {
            DecodeDecision(
                kind = DecodeDecisionKind.SOURCE_TOO_LARGE,
                sampleSize = sourceSample,
                rawBitmapBytes = rawBitmapBytes,
                sampledBitmapBytes = sampledBytes,
                sourcePixels = pixels,
                sampledPixels = sampledPixels,
                snapshot = snapshot,
            )
        } else {
            DecodeDecision(
                kind = DecodeDecisionKind.HEAP_CONSTRAINED,
                sampleSize = sourceSample,
                rawBitmapBytes = rawBitmapBytes,
                sampledBitmapBytes = sampledBytes,
                sourcePixels = pixels,
                sampledPixels = sampledPixels,
                snapshot = snapshot,
            )
        }
    }

    private fun heapDiagnosticSampleSize(pixels: Long, available: Long): Int {
        var sample = 1
        while (true) {
            val sampledPixels = pixels / (sample.toLong() * sample.toLong())
            val sampledBytes = sampledPixels * 4L
            if (sampledBytes <= available * 20L / 100L) return sample
            sample *= 2
        }
    }

    private fun sourceLimitSampleSize(pixels: Long): Int {
        var sample = 1
        while (pixels / (sample.toLong() * sample.toLong()) > MAX_FULL_RES_DECODE_PIXELS) {
            sample *= 2
        }
        return sample
    }

    fun canStartDecode(sourceBytesSize: Long, width: Int, height: Int): MemoryPreflightDecision {
        val snapshot = snapshot()
        val targetBitmapBytes = width.toLong() * height.toLong() * 4L
        val requiredHeap = sourceBytesSize + targetBitmapBytes
        val margin = max(32L * MIB, snapshot.maxHeapBytes / 10L)

        if (snapshot.availableHeapBytes < requiredHeap + margin) {
            return MemoryPreflightDecision.Defer(
                "Tight JVM heap for Decode: required=${(requiredHeap + margin).toMiB()}MiB, available=${snapshot.availableHeapBytes.toMiB()}MiB",
            )
        }

        val app = Injekt.get<Application>()
        val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (activityManager != null) {
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            if (memInfo.lowMemory) {
                return MemoryPreflightDecision.Defer("System-wide low memory indicator active before Decode")
            }
            val sysHeadroom = memInfo.availMem - memInfo.threshold
            if (sysHeadroom < 50L * MIB) {
                return MemoryPreflightDecision.Defer(
                    "Tight system memory for Decode: headroom=${sysHeadroom.toMiB()}MiB",
                )
            }
        }
        return MemoryPreflightDecision.Proceed
    }

    fun canStartAnalyze(width: Int, height: Int): MemoryPreflightDecision {
        val snapshot = snapshot()
        val decodedBitmapBytes = width.toLong() * height.toLong() * 4L
        val estimatedOcrHeapOverhead = 16L * MIB
        val margin = max(32L * MIB, snapshot.maxHeapBytes / 10L)
        val requiredHeap = decodedBitmapBytes + estimatedOcrHeapOverhead

        if (snapshot.availableHeapBytes < requiredHeap + margin) {
            return MemoryPreflightDecision.Defer(
                "Tight JVM heap for Analyze: required=${(requiredHeap + margin).toMiB()}MiB, available=${snapshot.availableHeapBytes.toMiB()}MiB",
            )
        }

        val app = Injekt.get<Application>()
        val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (activityManager != null) {
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            if (memInfo.lowMemory) {
                return MemoryPreflightDecision.Defer("System-wide low memory indicator active before Analyze")
            }
            val sysHeadroom = memInfo.availMem - memInfo.threshold
            val requiredSysMem = 128L * MIB // For ONNX detector & OCR sessions native buffers
            if (sysHeadroom < requiredSysMem) {
                return MemoryPreflightDecision.Defer(
                    "Tight system memory for Analyze: headroom=${sysHeadroom.toMiB()}MiB, required=${requiredSysMem.toMiB()}MiB",
                )
            }
        }
        return MemoryPreflightDecision.Proceed
    }

    fun canStartInpaint(width: Int, height: Int): MemoryPreflightDecision {
        val snapshot = snapshot()
        val decodedBitmapBytes = width.toLong() * height.toLong() * 4L
        val estimatedInpaintHeap = decodedBitmapBytes + 20L * MIB
        val margin = max(32L * MIB, snapshot.maxHeapBytes / 10L)

        if (snapshot.availableHeapBytes < estimatedInpaintHeap + margin) {
            return MemoryPreflightDecision.Defer(
                "Tight JVM heap for Inpaint: required=${(estimatedInpaintHeap + margin).toMiB()}MiB, available=${snapshot.availableHeapBytes.toMiB()}MiB",
            )
        }

        // Do not reject the whole inpaint stage on system/native pressure: the
        // Push-Pull path is heap-only and remains the safe fallback. The neural
        // branch applies its one/two-session native reserve immediately before ORT.
        return MemoryPreflightDecision.Proceed
    }

    fun isCriticalHeap(): Boolean {
        val snapshot = snapshot()
        return snapshot.availableHeapBytes < max(32L * MIB, snapshot.maxHeapBytes / 10L)
    }

    /**
     * gate used before auto-translate enqueues prefetch pages. The
     * translator's per-page decode runs concurrently with the reader's own decode
     * of the currently-viewed page (especially for downloaded chapters, where
     * both read the same local file with no network latency to space them out).
     * If the heap is already tight, launching a prefetch now risks an OOM on the
     * MAIN reader thread — which crashes/kicks the user out of the reader instead
     * of being caught as a translation-side FAILED. Skip enqueueing until there's
     * headroom; auto-translate will retry on the next page change.
     */
    fun hasHeadroomForPrefetch(): Boolean {
        val snapshot = snapshot()
        val sysHeadroom = EngineMemoryBudget.systemHeadroomBytes()
        if (sysHeadroom != null && sysHeadroom < 100L * MIB) return false
        val minimum = if (sysHeadroom != null && sysHeadroom > 400L * MIB) {
            max(32L * MIB, snapshot.maxHeapBytes / 10L)
        } else {
            max(64L * MIB, snapshot.maxHeapBytes / 6L)
        }
        return snapshot.availableHeapBytes >= minimum
    }

    private fun Long.toMiB(): Long = this / MIB
}
