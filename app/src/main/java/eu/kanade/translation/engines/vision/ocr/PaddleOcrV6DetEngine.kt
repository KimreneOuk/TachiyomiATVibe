package eu.kanade.translation.engines.vision.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import eu.kanade.translation.engines.runtime.onnx.OnnxRuntimeProvider
import eu.kanade.translation.engines.runtime.onnx.PaddleOcrProviderOverride
import eu.kanade.translation.engines.runtime.onnx.PaddleOcrProviderResolution
import eu.kanade.translation.engines.runtime.onnx.PaddleOcrSessionFactory
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.pools.BitmapPool
import tachiyomi.domain.translation.pools.DirectBufferPool
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * PP-OCRv6_manga v0.2 FP16 **detection** ONNX engine.
 *
 * Runs the DB (Differentiable Binarization) text-line detector on a bitmap
 * and returns the text-line boxes in crop pixel coordinates.
 *
 * Implements the reference PP-OCRv6_manga pipeline:
 *  - PreProcess: Dynamic resizing to longest side 960 (rounded to multiples of 32, min 32),
 *    area-based resizing for aspect ratios > 2:1 (pixel budget of 3:4 page at 960),
 *    ImageNet mean/std normalize (RGB), NCHW.
 *  - Model: manga_det_v0.2_fp16.onnx
 *  - PostProcess: DbPostProcess (thresh=0.15, box_thresh=0.25, unclip_ratio=1.4).
 */
class PaddleOcrV6DetEngine : Closeable {

    private var session: OrtSession? = null
    private var inputName: String = "x"
    private var outputName: String = "fetch_name_0"

    /** Provider that actually serves this detector ("qnn_htp"/"nnapi"/"cpu"), for honest perf logging. */
    var executionProviderLabel: String = "uninitialized"
        private set

    /** Provider requested for this detector; null keeps the legacy automatic route. */
    var requestedProviderLabel: String = "automatic"
        private set

    @Volatile
    private var closed: Boolean = false

    // Pooled DIRECT buffer for the det input. Sized for max dimension MAX_DET_SIZE x MAX_DET_SIZE.
    private val inputPixelPool = DirectBufferPool(3 * MAX_DET_SIZE * MAX_DET_SIZE * 4, maxPoolSize = 2)

    fun initialize(
        modelFile: File,
        providerResolution: PaddleOcrProviderResolution? = null,
        providerConfiguration: PaddleOcrProviderOverride? = null,
    ) {
        val providerSelection = selectPaddleOcrDetProvider(providerConfiguration, providerResolution)
        requestedProviderLabel = when (providerSelection) {
            is PaddleOcrDetProviderSelection.Explicit -> providerSelection.configuration.expectedProviderLabel
            is PaddleOcrDetProviderSelection.Resolved -> providerSelection.resolution.requestedWireLabel
            PaddleOcrDetProviderSelection.Automatic -> "automatic"
        }
        logcat(LogPriority.INFO) {
            "PaddleOCR v6 det init: model=${modelFile.absolutePath} " +
                "(${modelFile.length()}B exists=${modelFile.exists()}) " +
                "requestedProvider=$requestedProviderLabel " +
                "resolvedRoute=${providerResolution?.resolvedRouteLabel ?: "automatic"} " +
                "probeFallback=${providerResolution?.fallbackReason ?: "none"}"
        }
        fun createSessionWithOptFallback(optLevel: OrtSession.SessionOptions.OptLevel): OrtSession {
            val configure = { opts: OrtSession.SessionOptions ->
                opts.setOptimizationLevel(optLevel)
            }
            return when (val selection = providerSelection) {
                is PaddleOcrDetProviderSelection.Explicit ->
                    OnnxRuntimeProvider.createSessionForPaddleProvider(
                        modelPath = modelFile.absolutePath,
                        configuration = selection.configuration,
                        configure = configure,
                        providerSink = { executionProviderLabel = it },
                    )
                is PaddleOcrDetProviderSelection.Resolved ->
                    PaddleOcrSessionFactory.createSession(
                        modelPath = modelFile.absolutePath,
                        resolution = selection.resolution,
                        configure = configure,
                        providerSink = { executionProviderLabel = it },
                    )
                PaddleOcrDetProviderSelection.Automatic ->
                    OnnxRuntimeProvider.createSessionWithFallback(
                        modelPath = modelFile.absolutePath,
                        useAccelerator = true,
                        configure = configure,
                        providerSink = { executionProviderLabel = it },
                    )
            }
        }
        try {
            session = try {
                createSessionWithOptFallback(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "PaddleOCR v6 det BASIC_OPT failed, retrying with NO_OPT" }
                createSessionWithOptFallback(OrtSession.SessionOptions.OptLevel.NO_OPT)
            }
            inputName = session?.inputNames?.firstOrNull() ?: "x"
            outputName = session?.outputNames?.firstOrNull() ?: "fetch_name_0"
            logcat(LogPriority.INFO) {
                "PaddleOCR v6 det loaded (requestedProvider=$requestedProviderLabel " +
                    "registeredProvider=$executionProviderLabel inputs=${session?.inputNames}, " +
                    "outputs=${session?.outputNames})"
            }
        } catch (e: Exception) {
            android.util.Log.e("PaddleOCR", "PaddleOCR v6 det session init failed", e)
            logcat(LogPriority.ERROR, e) { "PaddleOCR v6 det session init failed" }
            throw e
        }
    }

    /**
     * Detect text lines in [crop]. Returns boxes in **crop pixel coords**
     * (already back-projected from the model's resized map space).
     */
    fun detectLines(
        crop: Bitmap,
        thresh: Float = DbPostProcess.Defaults.THRESH,
        boxThresh: Float = DbPostProcess.Defaults.BOX_THRESH,
    ): List<TextLine> {
        val localSession = session ?: throw IllegalStateException("PaddleOCR v6 det not initialized")
        val w = crop.width
        val h = crop.height
        if (w <= 0 || h <= 0) return emptyList()

        val t0 = System.nanoTime()
        var pixelBuffer: FloatBuffer? = null
        var inputTensor: OnnxTensor? = null
        var result: OrtSession.Result? = null
        try {
            pixelBuffer = inputPixelPool.acquire()
            pixelBuffer.clear()
            val pre = preprocess(crop, pixelBuffer)
            pixelBuffer.flip()
            inputTensor = OnnxTensor.createTensor(
                OnnxRuntimeProvider.environment,
                pixelBuffer,
                longArrayOf(1, 3, pre.resizedH.toLong(), pre.resizedW.toLong()),
            )
            result = localSession.run(mapOf(inputName to inputTensor))
            val out = result[0] as OnnxTensor
            val shape = out.info.shape
            val mapWidth = shape[shape.size - 1].toInt().coerceAtLeast(1)
            val mapHeight = shape[shape.size - 2].toInt().coerceAtLeast(1)
            val probabilityArray = FloatArray(mapWidth * mapHeight)
            out.floatBuffer.get(probabilityArray)
            val t1 = System.nanoTime()

            val mapToCropX = if (pre.cropToMapX > 0f) 1f / pre.cropToMapX else 0f
            val mapToCropY = if (pre.cropToMapY > 0f) 1f / pre.cropToMapY else 0f
            val mapLines = DbPostProcess.detectLines(
                probabilityMap = probabilityArray,
                width = mapWidth,
                height = mapHeight,
                threshold = thresh,
                boxThreshold = boxThresh,
                unclipRatio = DbPostProcess.Defaults.UNCLIP_RATIO,
            )
            val cropLines = ArrayList<TextLine>(mapLines.size)
            for (ml in mapLines) {
                val cb = DbPostProcess.backProject(
                    bbox = ml.bbox,
                    scaleX = mapToCropX,
                    scaleY = mapToCropY,
                    cropWidth = w,
                    cropHeight = h,
                )
                if (cb[2] > cb[0] && cb[3] > cb[1]) {
                    cropLines.add(TextLine(bbox = cb, meanScore = ml.meanScore))
                }
            }
            if (isDiagnosticsEnabled()) {
                val t2 = System.nanoTime()
                logcat(LogPriority.INFO) {
                    "[paddle_det] total=${(t2 - t0) / 1_000_000.0}ms " +
                        "requestedProvider=$requestedProviderLabel provider=$executionProviderLabel " +
                        "infer=${(t1 - t0) / 1_000_000.0}ms " +
                        "crop=${w}x$h map=${mapWidth}x$mapHeight " +
                        "lines=${cropLines.size}"
                }
            }
            return cropLines
        } finally {
            result?.close()
            inputTensor?.close()
            pixelBuffer?.let { inputPixelPool.release(it) }
        }
    }

    private var scratchPixels: IntArray? = null
    private var scratchPlane: FloatArray? = null

    private fun getScratchPixels(size: Int): IntArray {
        val existing = scratchPixels
        if (existing != null && existing.size >= size) return existing
        return IntArray(size).also { scratchPixels = it }
    }

    private fun getScratchPlane(size: Int): FloatArray {
        val existing = scratchPlane
        if (existing != null && existing.size >= size) return existing
        return FloatArray(size).also { scratchPlane = it }
    }

    override fun close() {
        closed = true
        session?.close()
        session = null
        inputPixelPool.clear()
        scratchPixels = null
        scratchPlane = null
    }

    /**
     * Reclaims pooled and scratch memory.
     */
    fun reclaimPooledMemory() {
        scratchPixels = null
        scratchPlane = null
    }

    fun forceReleaseNativeBuffers() {
        inputPixelPool.clear()
        scratchPixels = null
        scratchPlane = null
    }

    /**
     * Preprocess the crop dynamically to `[1, 3, resizedH, resizedW]` NCHW float tensor,
     * written directly into [out] (a pooled direct buffer).
     */
    private fun preprocess(crop: Bitmap, out: FloatBuffer): Preprocessed {
        val w = crop.width
        val h = crop.height
        val (resizedW, resizedH) = calculateDetDimensions(w, h)

        var resized: Bitmap? = null
        try {
            resized = BitmapPool.getARGB8888(resizedW, resizedH)
            Canvas(resized).drawBitmap(
                crop,
                null,
                RectF(0f, 0f, resizedW.toFloat(), resizedH.toFloat()),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )

            val total = resizedW * resizedH
            val pixels = getScratchPixels(total)
            val plane = getScratchPlane(total)
            resized.getPixels(pixels, 0, resizedW, 0, 0, resizedW, resizedH)

            // NCHW RGB plane order. Vectorized bulk puts into direct buffer.
            for (i in 0 until total) plane[i] = normalizeR(pixels[i] shr 16 and 0xFF)
            out.put(plane, 0, total)
            for (i in 0 until total) plane[i] = normalizeG(pixels[i] shr 8 and 0xFF)
            out.put(plane, 0, total)
            for (i in 0 until total) plane[i] = normalizeB(pixels[i] and 0xFF)
            out.put(plane, 0, total)
            return Preprocessed(
                cropToMapX = resizedW.toFloat() / w.toFloat(),
                cropToMapY = resizedH.toFloat() / h.toFloat(),
                resizedW = resizedW,
                resizedH = resizedH,
            )
        } finally {
            if (resized != null) BitmapPool.putARGB8888(resized)
        }
    }

    private fun normalizeB(v: Int) = (v / 255f - MEAN_B) / STD_B
    private fun normalizeG(v: Int) = (v / 255f - MEAN_G) / STD_G
    private fun normalizeR(v: Int) = (v / 255f - MEAN_R) / STD_R

    private data class Preprocessed(
        val cropToMapX: Float,
        val cropToMapY: Float,
        val resizedW: Int,
        val resizedH: Int,
    )

    companion object {
        const val MAX_DET_SIZE = 960

        // ImageNet normalize (RGB) from PP-OCRv6_manga v0.2
        private const val MEAN_R = 0.485f
        private const val MEAN_G = 0.456f
        private const val MEAN_B = 0.406f
        private const val STD_R = 0.229f
        private const val STD_G = 0.224f
        private const val STD_B = 0.225f

        private fun isDiagnosticsEnabled(): Boolean = OcrDiagnostics.isEnabled()

        /**
         * Calculates detection input dimensions matching PP-OCRv6_manga reference:
         * - Longest side scaled to 960, rounded to multiples of 32 (min 32).
         * - For long strips (aspect ratio > 2:1), uses pixel budget of 3:4 page at 960.
         */
        fun calculateDetDimensions(w: Int, h: Int): Pair<Int, Int> {
            val maxDim = max(h, w).toDouble()
            val minDim = min(h, w).toDouble()
            val ratio = if (minDim > 0.0 && maxDim / minDim > 2.0) {
                min(1.0, sqrt(0.75 * MAX_DET_SIZE.toDouble() * MAX_DET_SIZE.toDouble() / (h.toDouble() * w.toDouble())))
            } else {
                min(1.0, MAX_DET_SIZE.toDouble() / max(1.0, maxDim))
            }
            val rh = max(32, (round(h.toDouble() * ratio / 32.0) * 32.0).toInt())
            val rw = max(32, (round(w.toDouble() * ratio / 32.0) * 32.0).toInt())
            return Pair(rw, rh)
        }
    }
}
