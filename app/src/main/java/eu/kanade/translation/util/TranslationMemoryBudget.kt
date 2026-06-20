package eu.kanade.translation.util

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
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

    fun chooseDecodeSampleSize(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        val pixels = width.toLong() * height.toLong()
        val rawBitmapBytes = pixels * 4L

        // TachiyomiAT: read the heap snapshot ONCE for the whole decision.
        // If memory appears low (which would trigger downscaling), proactively trigger
        // a Garbage Collection first. This reclaims memory from previously recycled
        // bitmaps and dead objects that haven't been swept by lazy GC yet, preventing
        // premature downscaling (blurry pages) on consecutive page loads.
        var available = snapshot().availableHeapBytes
        if (pixels > MAX_FULL_RES_DECODE_PIXELS || rawBitmapBytes > available * 25L / 100L) {
            System.gc()
            available = snapshot().availableHeapBytes
        }

        // TachiyomiAT: thresholds lowered from 40%/35% to 25%/20% of available
        // heap. The previous values were too aggressive on small-heaped devices
        // (a 512MB heap leaves ~200MB for "one page" at 40%), and crucially they
        // ignored that for DOWNLOADED chapters the reader decodes the SAME page
        // on the main thread at the same time — so the translator's 40% budget
        // plus the reader's decode frequently OOM'd together around page ~20,
        // making translation appear to silently stop. 25%/20% steps the sample
        // size up earlier, keeping peak memory low enough that the two concurrent
        // decodes coexist.
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

    fun canRunNeuralInpaint(pageWidth: Int, pageHeight: Int, cropWidth: Int, cropHeight: Int): Boolean {
        if (pageWidth <= 0 || pageHeight <= 0 || cropWidth <= 0 || cropHeight <= 0) return false
        val pagePixels = pageWidth.toLong() * pageHeight.toLong()
        val cropPixels = cropWidth.toLong() * cropHeight.toLong()
        val estimatedPeak = (pagePixels * 4L * 3L) + (cropPixels * 4L * NEURAL_INPAINT_PEAK_MULTIPLIER)
        return estimatedPeak <= singlePageBudgetBytes()
    }

    fun canStartOnnxRecognition(pageWidth: Int, pageHeight: Int): Boolean {
        if (pageWidth <= 0 || pageHeight <= 0) return false
        val snapshot = snapshot()
        val pageBytes = pageWidth.toLong() * pageHeight.toLong() * 4L
        val minimumHeadroom = max(96L * MIB, snapshot.maxHeapBytes / 4L)
        return snapshot.availableHeapBytes >= minimumHeadroom &&
            pageBytes <= snapshot.availableHeapBytes * 30L / 100L
    }

    fun isCriticalHeap(): Boolean {
        val snapshot = snapshot()
        return snapshot.availableHeapBytes < max(32L * MIB, snapshot.maxHeapBytes / 10L)
    }

    /**
     * TachiyomiAT: gate used before auto-translate enqueues prefetch pages. The
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
        val minimum = max(64L * MIB, snapshot.maxHeapBytes / 6L)
        return snapshot.availableHeapBytes >= minimum
    }

    fun logSnapshot(tag: String, width: Int? = null, height: Int? = null, extra: String = "") {
        // TachiyomiAT: this fires at multiple stages per page (decode,
        // analyze_start, before_inpaint, oom_recovery, ...). Building the message
        // string + log dispatch on every stage of every page is wasted work when
        // the user isn't debugging. Gate the whole call behind the opt-in
        // translation_diagnostics preference; ERROR-level logs elsewhere stay on.
        if (!diagnosticsEnabled) return
        val snapshot = snapshot()
        val dims = if (width != null && height != null) " page=${width}x$height" else ""
        logcat(LogPriority.INFO) {
            "[translation_mem] $tag$dims " +
                "heap=${snapshot.usedHeapBytes.toMiB()}MiB/${snapshot.maxHeapBytes.toMiB()}MiB " +
                "avail=${snapshot.availableHeapBytes.toMiB()}MiB budget=${singlePageBudgetBytes().toMiB()}MiB $extra"
        }
    }

    /**
     * TachiyomiAT: cached value of the translation_diagnostics preference. Read
     * lazily once and cached for the process lifetime; the pref rarely changes
     * mid-session and re-reading SharedPreferences on every hot-path log call
     * would defeat the purpose of gating. Falls back to false if Injekt isn't
     * ready (e.g. during very early init), keeping logging off by default.
     */
    private val diagnosticsEnabled: Boolean by lazy {
        try {
            Injekt.get<TranslationPreferences>().translationDiagnostics().get()
        } catch (e: Throwable) {
            false
        }
    }

    private fun singlePageBudgetBytes(): Long {
        val available = snapshot().availableHeapBytes
        return (available * 5L / 10L)
            .coerceAtLeast(MIN_SINGLE_PAGE_BUDGET_BYTES)
            .coerceAtMost(MAX_SINGLE_PAGE_BUDGET_BYTES)
    }

    private fun sourceLimitSampleSize(pixels: Long): Int {
        var sample = 1
        while (pixels / (sample.toLong() * sample.toLong()) > MAX_FULL_RES_DECODE_PIXELS) {
            sample *= 2
        }
        return sample
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

    private fun Long.toMiB(): Long = this / MIB
}
