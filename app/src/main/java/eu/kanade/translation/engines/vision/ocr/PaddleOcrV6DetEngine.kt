package eu.kanade.translation.engines.vision.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
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
data class PaddleOcrV6DetTiming(
    val preprocessMs: Double,
    val neuralForwardMs: Double,
    val dbPostProcessMs: Double,
    val totalMs: Double,
)

class PaddleOcrV6DetEngine : Closeable {

    private var session: OrtSession? = null
    private var inputName: String = "x"
    private var outputName: String = "fetch_name_0"
    private var liteRtEnvironment: Environment? = null
    private var liteRtModel: CompiledModel? = null
    private var liteRtInputBuffer: TensorBuffer? = null
    private var liteRtOutputBuffer: TensorBuffer? = null
    private var liteRtInputFloats: FloatArray? = null
    private var liteRtModelFile: File? = null
    private var liteRtPreferGpu: Boolean = true
    private var liteRtAllowXnnpackFallback: Boolean = true
    private var liteRtNumThreads: Int = DEFAULT_LITERT_CPU_THREADS
    private var liteRtGpuActive: Boolean = false
    private val liteRtZeroRow = FloatArray(INPUT_ROW_ELEMENTS)

    private var lastPageBitmapRef: java.lang.ref.WeakReference<Bitmap>? = null
    private var lastPageThresh: Float = 0f
    private var lastPageBoxThresh: Float = 0f
    private var lastPageUnclip: Float = 0f
    private var lastPageLines: List<TextLine>? = null

    @Volatile
    var lastTiming: PaddleOcrV6DetTiming? = null
        private set

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

    /** Opt-in fixed-shape LiteRT backend. ONNX remains the default initializer. */
    @Synchronized
    fun initializeLiteRt(
        modelFile: File,
        preferGpu: Boolean = true,
        allowXnnpackFallback: Boolean = true,
        numThreads: Int = DEFAULT_LITERT_CPU_THREADS,
    ) {
        check(!closed) { "PaddleOCR v6 det is closed" }
        closeLiteRtBackend()
        liteRtModelFile = modelFile
        liteRtPreferGpu = preferGpu
        liteRtAllowXnnpackFallback = allowXnnpackFallback
        liteRtNumThreads = numThreads
        requestedProviderLabel = if (preferGpu) "litert_gpu_delegate" else "litert_xnnpack"
        liteRtEnvironment = Environment.create()
        try {
            if (preferGpu) {
                try {
                    initializeLiteRtGpu(modelFile)
                } catch (failure: Throwable) {
                    closeLiteRtModel()
                    if (!allowXnnpackFallback) throw failure
                    liteRtPreferGpu = false
                    logcat(LogPriority.WARN, failure) {
                        "PaddleOCR detector LiteRT GPU init failed; falling back to CPU"
                    }
                    initializeLiteRtCpu(modelFile)
                }
            } else {
                initializeLiteRtCpu(modelFile)
            }
            allocateLiteRtBuffers()
            logcat(LogPriority.INFO) {
                "PaddleOCR detector LiteRT loaded: model=${modelFile.absolutePath} " +
                    "provider=$executionProviderLabel input=[1,$MAX_DET_SIZE,$MAX_DET_SIZE,3]"
            }
        } catch (failure: Throwable) {
            closeLiteRtBackend()
            throw failure
        }
    }

    private fun detectLinesLiteRt(
        crop: Bitmap,
        thresh: Float,
        boxThresh: Float,
        unclipRatio: Float,
    ): List<TextLine> {
        val w = crop.width
        val h = crop.height
        if (w <= 0 || h <= 0) return emptyList()
        val totalStart = System.nanoTime()
        if (liteRtInputFloats == null) allocateLiteRtBuffers()
        val inputFloats = checkNotNull(liteRtInputFloats) { "LiteRT detector float input is not initialized" }
        var inBuf = checkNotNull(liteRtInputBuffer) { "LiteRT input buffer is closed" }
        var outBuf = checkNotNull(liteRtOutputBuffer) { "LiteRT output buffer is closed" }
        var activeModel = checkNotNull(liteRtModel) { "LiteRT model is closed" }

        val preprocessStart = System.nanoTime()
        val letterbox = preprocessLiteRt(crop, inputFloats)
        val preprocessMs = (System.nanoTime() - preprocessStart) / 1_000_000.0

        var neuralForwardNanos = 0L
        var recoveryNanos = 0L

        inBuf.writeFloat(inputFloats)
        try {
            val forwardStart = System.nanoTime()
            try {
                activeModel.run(listOf(inBuf), listOf(outBuf))
            } finally {
                neuralForwardNanos += System.nanoTime() - forwardStart
            }
        } catch (failure: Throwable) {
            if (!liteRtGpuActive || !liteRtAllowXnnpackFallback) throw failure
            val recoveryStart = System.nanoTime()
            logcat(LogPriority.WARN, failure) {
                "PaddleOCR detector LiteRT GPU invoke failed; retrying with CPU"
            }
            liteRtPreferGpu = false
            closeLiteRtModel()
            initializeLiteRtCpu(checkNotNull(liteRtModelFile))
            inBuf = checkNotNull(liteRtInputBuffer)
            outBuf = checkNotNull(liteRtOutputBuffer)
            activeModel = checkNotNull(liteRtModel)
            inBuf.writeFloat(inputFloats)
            val forwardStart = System.nanoTime()
            try {
                activeModel.run(listOf(inBuf), listOf(outBuf))
            } finally {
                val retryForwardNanos = System.nanoTime() - forwardStart
                neuralForwardNanos += retryForwardNanos
                recoveryNanos = (System.nanoTime() - recoveryStart - retryForwardNanos).coerceAtLeast(0L)
            }
        }

        val postprocessStart = System.nanoTime()
        val probabilityArray = outBuf.readFloat()
        val mapLines = DbPostProcess.detectLines(
            probabilityMap = probabilityArray,
            width = MAX_DET_SIZE,
            height = MAX_DET_SIZE,
            threshold = thresh,
            boxThreshold = boxThresh,
            unclipRatio = unclipRatio,
        )
        val cropLines = ArrayList<TextLine>(mapLines.size)
        mapLines.forEach { line ->
            val mapped = backProjectLetterboxed(line.bbox, letterbox, w, h)
            if (mapped[2] > mapped[0] && mapped[3] > mapped[1]) {
                cropLines += TextLine(mapped, line.meanScore)
            }
        }
        val dbPostProcessMs = (System.nanoTime() - postprocessStart) / 1_000_000.0
        val totalMs = (System.nanoTime() - totalStart) / 1_000_000.0
        val neuralForwardMs = neuralForwardNanos / 1_000_000.0
        lastTiming = PaddleOcrV6DetTiming(
            preprocessMs = preprocessMs,
            neuralForwardMs = neuralForwardMs,
            dbPostProcessMs = dbPostProcessMs,
            totalMs = totalMs,
        )
        if (isDiagnosticsEnabled()) {
            logcat(LogPriority.INFO) {
                "[paddle_det] total=${totalMs}ms preprocess=${preprocessMs}ms " +
                    "neuralForward=${neuralForwardMs}ms " +
                    "requestedProvider=$requestedProviderLabel provider=$executionProviderLabel " +
                    "crop=${w}x$h map=${MAX_DET_SIZE}x$MAX_DET_SIZE lines=${cropLines.size}"
            }
        }
        return cropLines
    }

    private fun preprocessLiteRt(crop: Bitmap, out: FloatArray): LiteRtLetterbox {
        val scale = min(MAX_DET_SIZE.toFloat() / crop.width, MAX_DET_SIZE.toFloat() / crop.height)
        val resizedW = (crop.width * scale).toInt().coerceIn(1, MAX_DET_SIZE)
        val resizedH = (crop.height * scale).toInt().coerceIn(1, MAX_DET_SIZE)
        val offsetX = (MAX_DET_SIZE - resizedW) / 2
        val offsetY = (MAX_DET_SIZE - resizedH) / 2
        var resized: Bitmap? = null
        try {
            resized = BitmapPool.getARGB8888(resizedW, resizedH)
            Canvas(resized).drawBitmap(
                crop,
                null,
                RectF(0f, 0f, resizedW.toFloat(), resizedH.toFloat()),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            val pixels = getScratchPixels(resizedW * resizedH)
            resized.getPixels(pixels, 0, resizedW, 0, 0, resizedW, resizedH)

            val leftElements = offsetX * CHANNELS
            val rightElements = (MAX_DET_SIZE - offsetX - resizedW) * CHANNELS
            val fullRowElements = INPUT_ROW_ELEMENTS
            var outIndex = 0
            for (canvasY in 0 until MAX_DET_SIZE) {
                val contentY = canvasY - offsetY
                if (contentY !in 0 until resizedH) {
                    System.arraycopy(liteRtZeroRow, 0, out, outIndex, fullRowElements)
                    outIndex += fullRowElements
                    continue
                }

                if (leftElements > 0) {
                    System.arraycopy(liteRtZeroRow, 0, out, outIndex, leftElements)
                    outIndex += leftElements
                }
                var sourceIndex = contentY * resizedW
                for (x in 0 until resizedW) {
                    val pixel = pixels[sourceIndex++]
                    out[outIndex++] = normalizeR(pixel shr 16 and 0xFF)
                    out[outIndex++] = normalizeG(pixel shr 8 and 0xFF)
                    out[outIndex++] = normalizeB(pixel and 0xFF)
                }
                if (rightElements > 0) {
                    System.arraycopy(liteRtZeroRow, 0, out, outIndex, rightElements)
                    outIndex += rightElements
                }
            }
            return LiteRtLetterbox(resizedW, resizedH, offsetX, offsetY)
        } finally {
            if (resized != null) BitmapPool.putARGB8888(resized)
        }
    }

    private fun backProjectLetterboxed(
        bbox: IntArray,
        letterbox: LiteRtLetterbox,
        cropWidth: Int,
        cropHeight: Int,
    ): IntArray {
        val scaleX = cropWidth.toFloat() / letterbox.resizedWidth
        val scaleY = cropHeight.toFloat() / letterbox.resizedHeight
        val x1 = ((bbox[0] - letterbox.offsetX) * scaleX).toInt().coerceIn(0, cropWidth - 1)
        val y1 = ((bbox[1] - letterbox.offsetY) * scaleY).toInt().coerceIn(0, cropHeight - 1)
        val x2 = ((bbox[2] - letterbox.offsetX) * scaleX).toInt().coerceIn(0, cropWidth - 1)
        val y2 = ((bbox[3] - letterbox.offsetY) * scaleY).toInt().coerceIn(0, cropHeight - 1)
        return intArrayOf(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
    }

    private fun initializeLiteRtGpu(modelFile: File) {
        val env = checkNotNull(liteRtEnvironment) { "LiteRT Environment is not initialized" }
        val options = CompiledModel.Options(Accelerator.GPU)
        val created = CompiledModel.create(modelFile.absolutePath, options, env)
        liteRtInputBuffer = created.createInputBuffers()[0]
        liteRtOutputBuffer = created.createOutputBuffers()[0]
        liteRtModel = created
        liteRtGpuActive = true
        liteRtModelFile = modelFile
        executionProviderLabel = "litert_gpu_delegate"
    }

    private fun initializeLiteRtCpu(modelFile: File) {
        val env = checkNotNull(liteRtEnvironment) { "LiteRT Environment is not initialized" }
        val options = CompiledModel.Options(Accelerator.CPU).apply {
            cpuOptions = CompiledModel.CpuOptions(numThreads = liteRtNumThreads)
        }
        val created = CompiledModel.create(modelFile.absolutePath, options, env)
        liteRtInputBuffer = created.createInputBuffers()[0]
        liteRtOutputBuffer = created.createOutputBuffers()[0]
        liteRtModel = created
        liteRtGpuActive = false
        liteRtModelFile = modelFile
        executionProviderLabel = "litert_xnnpack"
    }

    private fun allocateLiteRtBuffers() {
        if (liteRtInputFloats == null) {
            liteRtInputFloats = FloatArray(1 * MAX_DET_SIZE * MAX_DET_SIZE * CHANNELS)
        }
    }

    private fun closeLiteRtModel() {
        try {
            liteRtInputBuffer?.close()
        } catch (_: Throwable) {
        }
        liteRtInputBuffer = null
        try {
            liteRtOutputBuffer?.close()
        } catch (_: Throwable) {
        }
        liteRtOutputBuffer = null
        try {
            liteRtModel?.close()
        } catch (_: Throwable) {
        }
        liteRtModel = null
        liteRtGpuActive = false
    }

    private fun closeLiteRtBackend() {
        closeLiteRtModel()
        liteRtInputFloats = null
        liteRtModelFile = null
        try {
            liteRtEnvironment?.close()
        } catch (_: Throwable) {
        }
        liteRtEnvironment = null
    }

    private data class LiteRtLetterbox(
        val resizedWidth: Int,
        val resizedHeight: Int,
        val offsetX: Int,
        val offsetY: Int,
    )

    fun detectLines(
        crop: Bitmap,
        thresh: Float = DbPostProcess.Defaults.THRESH,
        boxThresh: Float = DbPostProcess.Defaults.BOX_THRESH,
        unclipRatio: Float = DbPostProcess.Defaults.UNCLIP_RATIO,
    ): List<TextLine> {
        val cached = synchronized(this) {
            if (lastPageBitmapRef?.get() === crop &&
                lastPageThresh == thresh &&
                lastPageBoxThresh == boxThresh &&
                lastPageUnclip == unclipRatio
            ) {
                lastPageLines
            } else {
                null
            }
        }
        if (cached != null) return cached

        val lines = if (liteRtModel != null) {
            detectLinesLiteRt(crop, thresh, boxThresh, unclipRatio)
        } else {
            detectLinesOnnx(crop, thresh, boxThresh, unclipRatio)
        }

        synchronized(this) {
            lastPageBitmapRef = java.lang.ref.WeakReference(crop)
            lastPageThresh = thresh
            lastPageBoxThresh = boxThresh
            lastPageUnclip = unclipRatio
            lastPageLines = lines
        }
        return lines
    }

    private fun detectLinesOnnx(
        crop: Bitmap,
        thresh: Float = DbPostProcess.Defaults.THRESH,
        boxThresh: Float = DbPostProcess.Defaults.BOX_THRESH,
        unclipRatio: Float = DbPostProcess.Defaults.UNCLIP_RATIO,
    ): List<TextLine> {
        if (liteRtModel != null) {
            return detectLinesLiteRt(crop, thresh, boxThresh, unclipRatio)
        }
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
                unclipRatio = unclipRatio,
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
        closeLiteRtBackend()
        session = null
        inputPixelPool.clear()
        scratchPixels = null
        scratchPlane = null
        synchronized(this) {
            lastPageBitmapRef = null
            lastPageLines = null
        }
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
        private const val CHANNELS = 3
        private const val INPUT_ROW_ELEMENTS = MAX_DET_SIZE * CHANNELS
        private const val DEFAULT_LITERT_CPU_THREADS = 4

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
