package eu.kanade.translation.engines.coloring

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import eu.kanade.translation.engines.runtime.onnx.OnnxModelStore
import eu.kanade.translation.engines.runtime.onnx.OnnxRuntimeProvider
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.nio.FloatBuffer
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Deterministic colorizer running the manga-colorization-v2 generator ONNX
 * graph on the CPU execution provider. The store prefers the int8-weights
 * artifact (dynamic quantization, PSNR 39+ dB vs fp32 on validation pages at
 * a quarter of the size) and falls back to the fp32-widened graph — the
 * published fp16 export itself targets GPU and miscolors on ORT's ARM64 CPU
 * fp16 path. The graph takes a 5-channel NCHW tensor — ch0 grayscale [0,1],
 * ch1-3 color hints, ch4 hint mask (hints stay zero: hint drawing is a later
 * feature) — with dynamic H/W multiples of 32, and emits RGB in [0,1]. Line
 * detail lost to the downscaled inference is re-imposed at page resolution
 * by [LineArtMerger].
 */
class OnnxMangaColorizationEngine(private val context: Context) : PageColorizationEngine {

    private val modelStore = OnnxModelStore(context)

    @Volatile
    private var session: OrtSession? = null

    /** Provenance of the loaded artifact, set during a successful init. */
    private var stamp: String? = null

    private val initLock = Any()

    override fun isAvailable(): Boolean {
        if (session != null) return true
        synchronized(initLock) {
            if (session != null) return true
            return try {
                initializeLocked()
                true
            } catch (failure: Throwable) {
                logcat(LogPriority.ERROR, failure) { "OnnxMangaColorization initialization failed" }
                false
            }
        }
    }

    override fun currentStamp(): String? = if (isAvailable()) stamp else null

    @Synchronized
    override fun colorize(source: Bitmap, seed: Long): Bitmap {
        // seed is unused: the generator is deterministic (no sampler).
        val activeSession = session ?: run {
            if (!isAvailable()) throw IllegalStateException("OnnxMangaColorization assets/runtime unavailable")
            checkNotNull(session) { "OnnxMangaColorization session missing after init" }
        }
        val startedAt = System.nanoTime()
        val (workWidth, workHeight) = workDimensions(source.width, source.height)
        val gray = grayscalePlane(source, workWidth, workHeight)

        val inputShape = longArrayOf(1L, CHANNELS.toLong(), workHeight.toLong(), workWidth.toLong())
        val input = FloatArray(CHANNELS * workWidth * workHeight)
        // Channel 0 carries the grayscale; hint channels (1-4) stay zero.
        System.arraycopy(gray, 0, input, 0, gray.size)
        val tensor = OnnxTensor.createTensor(OnnxRuntimeProvider.environment, FloatBuffer.wrap(input), inputShape)
        val results = try {
            activeSession.run(mapOf(activeSession.inputNames.first() to tensor))
        } finally {
            tensor.close()
        }
        val output = try {
            val outTensor = results[0] as OnnxTensor
            val buffer = outTensor.floatBuffer
            val values = FloatArray(buffer.capacity())
            buffer.position(0)
            buffer.get(values)
            values
        } finally {
            results.close()
        }

        val coloredWork = rgb01ToBitmap(output, workWidth, workHeight)
        val colored = mergeWithLineArt(source, coloredWork)
        logcat(LogPriority.INFO) {
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
            "OnnxMangaColorization page done in ${elapsedMs}ms " +
                "(cpu ${if (stamp?.contains("INT8") == true) "int8" else "fp32"}, work=${workWidth}x$workHeight)"
        }
        return colored
    }

    /**
     * Closes the session but stays re-initializable: closeEngines fires on
     * user stop while translation may restart in the same session.
     */
    @Synchronized
    override fun close() {
        session?.close()
        session = null
        stamp = null
    }

    private fun initializeLocked() {
        val startedAt = System.nanoTime()
        val modelFile = modelStore.ensureMangaColorization()
        val precision = if (modelFile.name.contains("int8")) "int8" else "fp32"
        val opts = OnnxRuntimeProvider.createSessionOptions(useAccelerator = false) {
            it.setIntraOpNumThreads(THREAD_COUNT)
        }
        try {
            session = OnnxRuntimeProvider.environment.createSession(modelFile.absolutePath, opts)
        } catch (failure: Exception) {
            session?.close()
            session = null
            throw failure
        } finally {
            opts.close()
        }
        stamp = if (precision == "int8") STAMP_INT8 else STAMP_FP32
        val built = session ?: throw IllegalStateException("OnnxMangaColorization session missing")
        logcat(LogPriority.INFO) {
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
            "OnnxMangaColorization ready in ${elapsedMs}ms (cpu $precision, threads=$THREAD_COUNT); " +
                "inputs=${built.inputNames} outputs=${built.outputNames}"
        }
    }

    /** Target inference size: short side 512, long side capped, dims /32. */
    private fun workDimensions(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        val shortSide = min(sourceWidth, sourceHeight).coerceAtLeast(1)
        var scale = WORK_SHORT_SIDE.toFloat() / shortSide
        val longSide = maxOf(sourceWidth, sourceHeight)
        if (longSide * scale > WORK_LONG_SIDE_MAX) {
            scale = WORK_LONG_SIDE_MAX.toFloat() / longSide
        }
        val width = ((sourceWidth * scale).toInt() / 32) * 32
        val height = ((sourceHeight * scale).toInt() / 32) * 32
        return Pair(width.coerceAtLeast(32), height.coerceAtLeast(32))
    }

    /** Scales the page to work dims and extracts Rec.601 luminance in [0,1]. */
    private fun grayscalePlane(source: Bitmap, width: Int, height: Int): FloatArray {
        val pixels = IntArray(width * height)
        Bitmap.createScaledBitmap(source, width, height, true).getPixels(pixels, 0, width, 0, 0, width, height)
        val plane = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val argb = pixels[i]
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            plane[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }
        return plane
    }

    /** Pack NCHW [0,1] planes into a work-dims ARGB bitmap. */
    private fun rgb01ToBitmap(rgb: FloatArray, width: Int, height: Int): Bitmap {
        val pixelCount = width * height
        val pixels = IntArray(pixelCount)
        for (p in 0 until pixelCount) {
            val r = (rgb[p].coerceIn(0f, 1f) * 255f).roundToInt()
            val g = (rgb[pixelCount + p].coerceIn(0f, 1f) * 255f).roundToInt()
            val b = (rgb[2 * pixelCount + p].coerceIn(0f, 1f) * 255f).roundToInt()
            pixels[p] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { it.setPixels(pixels, 0, width, 0, 0, width, height) }
    }

    /** Pack interleaved RGB (3 floats per pixel, LineArtMerger output) into ARGB. */
    private fun interleavedRgbToBitmap(rgb: FloatArray, width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height)
        for (p in pixels.indices) {
            val r = (rgb[p * 3].coerceIn(0f, 1f) * 255f).roundToInt()
            val g = (rgb[p * 3 + 1].coerceIn(0f, 1f) * 255f).roundToInt()
            val b = (rgb[p * 3 + 2].coerceIn(0f, 1f) * 255f).roundToInt()
            pixels[p] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { it.setPixels(pixels, 0, width, 0, 0, width, height) }
    }

    /**
     * Upscales the colored work bitmap to page dims and re-imposes the B/W
     * page's line lightness. Very large strips merge at a capped resolution
     * to bound the transient Lab buffers, then the merged result is scaled
     * back up.
     */
    private fun mergeWithLineArt(source: Bitmap, coloredWork: Bitmap): Bitmap {
        val mergeWidth: Int
        val mergeHeight: Int
        val bwBitmap: Bitmap
        if (source.width.toLong() * source.height <= MAX_MERGE_PIXELS) {
            mergeWidth = source.width
            mergeHeight = source.height
            bwBitmap = source
        } else {
            val shrink = sqrt(MAX_MERGE_PIXELS.toDouble() / (source.width.toLong() * source.height))
            mergeWidth = ((source.width * shrink).toInt() / 32) * 32
            mergeHeight = ((source.height * shrink).toInt() / 32) * 32
            bwBitmap = Bitmap.createScaledBitmap(source, mergeWidth, mergeHeight, true)
        }
        val colored = Bitmap.createScaledBitmap(coloredWork, mergeWidth, mergeHeight, true)
        val bwRgb = bitmapToRgb01(bwBitmap)
        val coloredRgb = bitmapToRgb01(colored)
        val merged = LineArtMerger.merge(coloredRgb, bwRgb, mergeWidth, mergeHeight, MERGE_SIGMA, MERGE_INK)
        // The merger returns interleaved RGB (3 floats per pixel), not the
        // model's planar NCHW layout — decoding it planar scrambles channels.
        val mergedBitmap = interleavedRgbToBitmap(merged, mergeWidth, mergeHeight)
        if (mergeWidth == source.width && mergeHeight == source.height) {
            return mergedBitmap
        }
        return Bitmap.createScaledBitmap(mergedBitmap, source.width, source.height, true)
    }

    private fun bitmapToRgb01(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val rgb = FloatArray(pixels.size * 3)
        for (i in pixels.indices) {
            val argb = pixels[i]
            rgb[i * 3] = ((argb shr 16) and 0xFF) / 255f
            rgb[i * 3 + 1] = ((argb shr 8) and 0xFF) / 255f
            rgb[i * 3 + 2] = (argb and 0xFF) / 255f
        }
        return rgb
    }

    private companion object {
        const val STAMP_FP32 = "ONNX_MC_V2_CPU_FP32_R2"
        const val STAMP_INT8 = "ONNX_MC_V2_CPU_INT8_R1"
        const val CHANNELS = 5
        const val WORK_SHORT_SIDE = 512
        const val WORK_LONG_SIDE_MAX = 1536
        const val MERGE_SIGMA = 3.0f
        const val MERGE_INK = 1.0f

        /** Caps the merge stage's full-page float buffers (a ~5.8 MP page otherwise). */
        const val MAX_MERGE_PIXELS = 1_500_000L
        val THREAD_COUNT = minOf(4, maxOf(1, Runtime.getRuntime().availableProcessors()))
    }
}
