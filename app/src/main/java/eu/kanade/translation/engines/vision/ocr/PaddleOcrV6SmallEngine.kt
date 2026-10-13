package eu.kanade.translation.engines.vision.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import eu.kanade.translation.engines.runtime.onnx.OnnxRuntimeProvider
import eu.kanade.translation.engines.runtime.onnx.PaddleOcrProviderOverride
import eu.kanade.translation.engines.runtime.onnx.PaddleOcrProviderResolution
import eu.kanade.translation.engines.runtime.onnx.PaddleOcrSessionFactory
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrDynamicPageBatchPolicy
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.pools.BitmapPool
import tachiyomi.domain.translation.pools.DirectBufferPool
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.FloatBuffer

data class PaddleOcrV6RecTiming(
    val preprocessMs: Double,
    val neuralForwardMs: Double,
    val ctcDecodeMs: Double,
    val totalMs: Double,
)

class PaddleOcrV6SmallEngine : RoiOcrEngine {

    // PP-OCRv6 rec is a CNN+CTC head trained on horizontal text
    // lines, so RoiPageRecognitionEngine rotates tall crops 90° for this engine
    // only. Native-vertical engines (ML Kit, MangaOcr) set this false.
    override val prefersHorizontalText: Boolean = true

    private var session: OrtSession? = null
    private var dictionary: List<String> = emptyList()
    private var inputName: String = "x"
    private var batchSession: PaddleOcrV6BatchSession? = null
    private var batchBufferPool: PaddleOcrV6BatchBufferPool? = null
    private var batchExecutor: PaddleOcrV6BatchExecutor? = null
    private var strictProviderMode: Boolean = false
    private var liteRtModelFiles: Map<Int, File> = emptyMap()
    private var liteRtPreferGpu: Boolean = true
    private var liteRtAllowXnnpackFallback: Boolean = true
    private var liteRtNumThreads: Int = DEFAULT_LITERT_CPU_THREADS
    private var liteRtFallbackInitializer: (() -> Unit)? = null
    private val liteRtLock = Any()
    private val liteRtRunners = linkedMapOf<Int, PaddleOcrV6LiteRtRunner>()
    private var liteRtNhwcScratch = FloatArray(0)
    private var liteRtEnvironment: Environment? = null

    @Volatile
    var lastLiteRtTiming: PaddleOcrV6RecTiming? = null
        private set


    /** Optional upper bound used to trigger a large-batch latency downgrade. */
    var batchLatencyBudgetMs: Double? = null

    /** Most recent batch execution facts; null until [recognizeBucketBatch] runs. */
    @Volatile
    var lastBatchTelemetry: PaddleOcrV6BatchTelemetry? = null
        private set

    /** Loaded dictionary line count; feeds the dynamic page batch ceiling math. */
    val dictionarySize: Int
        get() = dictionary.size

    /** Provider that actually serves this recognizer ("qnn_htp"/"nnapi"/"cpu"), for honest perf logging. */
    override var executionProviderLabel: String = "uninitialized"
        private set

    /** Provider requested for this recognizer; null keeps the legacy automatic route. */
    var requestedProviderLabel: String = "automatic"
        private set

    // pooled DIRECT buffer for the rec input. The rec width is bucketed
    // to fixed shapes (640 or 1600), so the pool is sized for MAX_RECOGNITION_WIDTH (1600);
    // each call exposes only [width x height x 3] floats via the buffer limit.
    // A heap-backed wrap() forces ORT to allocate a per-call native copy that leaks (ORT #16937).
    private val inputPixelPool = DirectBufferPool(
        3 * RECOGNITION_HEIGHT * MAX_RECOGNITION_WIDTH * 4,
        maxPoolSize = 2,
    )

    fun initialize(
        modelFile: File,
        dictionaryFile: File,
        providerResolution: PaddleOcrProviderResolution? = null,
        strictProviderMode: Boolean = false,
        providerConfiguration: PaddleOcrProviderOverride? = null,
    ) {
        requestedProviderLabel = providerResolution?.requestedWireLabel ?: "automatic"
        this.strictProviderMode = strictProviderMode || providerConfiguration?.strictNoCpuFallback == true
        logcat(LogPriority.INFO) {
            "PaddleOCR v6 small init: model=${modelFile.absolutePath} (${modelFile.length()}B exists=${modelFile.exists()}), " +
                "dictionary=${dictionaryFile.absolutePath} (${dictionaryFile.length()}B exists=${dictionaryFile.exists()}), " +
                "requestedProvider=$requestedProviderLabel " +
                "resolvedRoute=${providerResolution?.resolvedRouteLabel ?: "automatic"} " +
                "probeFallback=${providerResolution?.fallbackReason ?: "none"}"
        }
        dictionary = BufferedReader(InputStreamReader(dictionaryFile.inputStream(), Charsets.UTF_8)).use { reader ->
            reader.lineSequence().map { it.trimEnd() }.toList()
        }
        fun createSessionWithOptFallback(optLevel: OrtSession.SessionOptions.OptLevel): OrtSession {
            val configure = { opts: OrtSession.SessionOptions ->
                opts.setOptimizationLevel(optLevel)
            }
            return when {
                providerConfiguration != null -> {
                    // Use the explicit provider override.
                    OnnxRuntimeProvider.createSessionForPaddleProvider(
                        modelPath = modelFile.absolutePath,
                        configuration = providerConfiguration,
                        configure = configure,
                        providerSink = { executionProviderLabel = it },
                    )
                }
                providerResolution != null -> {
                    // Paddle-v6 provider selector path.
                    PaddleOcrSessionFactory.createSession(
                        modelPath = modelFile.absolutePath,
                        resolution = providerResolution,
                        configure = configure,
                        providerSink = { executionProviderLabel = it },
                    )
                }
                else -> {
                    // Preserve the existing automatic/global route for callers
                    // outside the Paddle-v6 selector experiment.
                    OnnxRuntimeProvider.createSessionWithFallback(
                        modelPath = modelFile.absolutePath,
                        useAccelerator = true,
                        configure = configure,
                        providerSink = { executionProviderLabel = it },
                    )
                }
            }
        }
        try {
            val createdSession = try {
                createSessionWithOptFallback(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "PaddleOCR v6 small ALL_OPT failed, retrying with BASIC_OPT" }
                try {
                    createSessionWithOptFallback(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                } catch (e2: Exception) {
                    logcat(LogPriority.WARN, e2) { "PaddleOCR v6 small BASIC_OPT failed, retrying with NO_OPT" }
                    createSessionWithOptFallback(OrtSession.SessionOptions.OptLevel.NO_OPT)
                }
            }
            if (this.strictProviderMode && executionProviderLabel.isCpuLikeProvider()) {
                createdSession.close()
                throw IllegalStateException(
                    "Strict Paddle OCR provider mode rejected provider='$executionProviderLabel'; CPU fallback is disabled",
                )
            }
            session = createdSession
            inputName = createdSession.inputNames.firstOrNull() ?: "x"
            batchSession = PaddleOcrV6OrtBatchSession(
                session = createdSession,
                inputName = inputName,
                providerLabel = executionProviderLabel,
            )
            batchBufferPool = PaddleOcrV6BatchBufferPool(
                // Sized for the dynamic whole-page ceiling; input leases stay
                // lazily allocated at the actual batch size (see the pool).
                maxBatchSize = PaddleOcrDynamicPageBatchPolicy.HARD_MAX_BATCH,
                maxWidth = MAX_RECOGNITION_WIDTH,
                dictionarySize = dictionary.size,
            )
            batchExecutor = PaddleOcrV6BatchExecutor(
                session = batchSession!!,
                bufferPool = batchBufferPool!!,
                dictionary = dictionary,
                latencyBudgetMs = batchLatencyBudgetMs,
                strictProviderMode = strictProviderMode,
            )
            logcat(LogPriority.INFO) {
                "PaddleOCR v6 small loaded (dictionary=${dictionary.size}, " +
                    "requestedProvider=$requestedProviderLabel registeredProvider=$executionProviderLabel " +
                    "inputs=${createdSession.inputNames}, outputs=${createdSession.outputNames})"
            }
        } catch (e: Exception) {
            android.util.Log.e("PaddleOCR", "PaddleOCR v6 small session init failed", e)
            logcat(LogPriority.ERROR, e) { "PaddleOCR v6 small session init failed" }
            throw e
        }
    }


    /** Opt-in LiteRT recognizer setup for the static NHWC FlatBuffers. */
    fun initializeLiteRt(
        modelFilesByWidth: Map<Int, File>,
        dictionaryFile: File,
        preferGpu: Boolean = true,
        allowXnnpackFallback: Boolean = true,
        numThreads: Int = DEFAULT_LITERT_CPU_THREADS,
        fallbackInitializer: (() -> Unit)? = null,
    ) {
        val missingWidths = SUPPORTED_LITERT_WIDTHS - modelFilesByWidth.keys
        require(missingWidths.isEmpty()) {
            "LiteRT PaddleOCR missing required width buckets: $missingWidths"
        }
        modelFilesByWidth.forEach { (width, file) ->
            require(file.exists() && file.isFile) {
                "LiteRT PaddleOCR model for width $width does not exist: ${file.absolutePath}"
            }
        }
        require(dictionaryFile.exists() && dictionaryFile.isFile) {
            "LiteRT PaddleOCR dictionary file does not exist: ${dictionaryFile.absolutePath}"
        }

        closeBackends()

        liteRtModelFiles = modelFilesByWidth
        liteRtPreferGpu = preferGpu
        liteRtAllowXnnpackFallback = allowXnnpackFallback
        liteRtNumThreads = numThreads
        liteRtFallbackInitializer = fallbackInitializer
        requestedProviderLabel = if (preferGpu) "litert_gpu_delegate" else "litert_xnnpack"

        dictionary = BufferedReader(InputStreamReader(dictionaryFile.inputStream(), Charsets.UTF_8)).use { reader ->
            reader.lineSequence().map { it.trimEnd() }.toList()
        }

        liteRtEnvironment = Environment.create()
        executionProviderLabel = "litert_uninitialized"

        logcat(LogPriority.INFO) {
            "PaddleOCR v6 LiteRT configured: widths=$SUPPORTED_LITERT_WIDTHS_SORTED, " +
                "preferGpu=$preferGpu allowXnnpackFallback=$allowXnnpackFallback cpuThreads=$numThreads, " +
                "dictionary=${dictionary.size}"
        }
    }

    private fun recognizeWithConfLiteRt(crop: Bitmap): Pair<String, Float> {
        val start = System.nanoTime()
        val widthBucket = liteRtWidthBucket(crop)
        val inference = synchronized(liteRtLock) {
            val runner = liteRtRunners[widthBucket] ?: run {
                while (liteRtRunners.size >= MAX_RESIDENT_LITERT_RUNNERS) {
                    val oldestWidth = liteRtRunners.entries.first().key
                    liteRtRunners.remove(oldestWidth)?.close()
                }
                createLiteRtRunner(widthBucket).also { created ->
                    liteRtRunners[widthBucket] = created
                }
            }
            runner.recognize(
                dictionary = dictionary,
                writeInput = { input ->
                    val actualWidth = preprocess(
                        crop = crop,
                        out = input,
                        targetWidthBucket = widthBucket,
                        channelLast = true,
                    )
                    check(actualWidth == widthBucket) {
                        "LiteRT PaddleOCR input aligned to $actualWidth but model bucket is $widthBucket"
                    }
                },
            ).also { executionProviderLabel = runner.executionProviderLabel }
        }
        val totalMs = (System.nanoTime() - start) / 1_000_000.0
        lastLiteRtTiming = PaddleOcrV6RecTiming(
            preprocessMs = inference.preprocessMs,
            neuralForwardMs = inference.neuralForwardMs,
            ctcDecodeMs = inference.ctcDecodeMs,
            totalMs = totalMs,
        )
        if (isDiagnosticsEnabled()) {
            logcat(LogPriority.INFO) {
                "[paddle_ocr] totalMs=$totalMs " +
                    "preprocessMs=${inference.preprocessMs} neuralForwardMs=${inference.neuralForwardMs} " +
                    "ctcDecodeMs=${inference.ctcDecodeMs} " +
                    "requestedProvider=$requestedProviderLabel provider=$executionProviderLabel " +
                    "backend=litert crop=${crop.width}x${crop.height} input=${widthBucket}x$RECOGNITION_HEIGHT " +
                    "chars=${inference.text.length}"
            }
        }
        return inference.text to inference.confidence
    }

    private fun createLiteRtRunner(widthBucket: Int): PaddleOcrV6LiteRtRunner {
        val modelFile = checkNotNull(liteRtModelFiles[widthBucket]) {
            "No LiteRT PaddleOCR model configured for width $widthBucket"
        }
        val env = checkNotNull(liteRtEnvironment) {
            "LiteRT Environment is not initialized"
        }
        return PaddleOcrV6LiteRtRunner(
            modelFile = modelFile,
            expectedWidth = widthBucket,
            preferGpu = liteRtPreferGpu,
            allowXnnpackFallback = liteRtAllowXnnpackFallback,
            numThreads = liteRtNumThreads,
            onGpuFallback = { liteRtPreferGpu = false },
            env = env,
        )
    }

    private fun liteRtWidthBucket(crop: Bitmap): Int {
        val targetWidth = calculateTargetWidth(crop.width, crop.height)
        return SUPPORTED_LITERT_WIDTHS_SORTED.first { targetWidth <= it }
    }

    private fun closeBackends() {
        session?.close()
        session = null
        batchSession = null
        batchExecutor = null
        batchBufferPool?.clear()
        batchBufferPool = null
        synchronized(liteRtLock) {
            liteRtRunners.values.forEach(PaddleOcrV6LiteRtRunner::close)
            liteRtRunners.clear()
            liteRtNhwcScratch = FloatArray(0)
        }
        liteRtModelFiles = emptyMap()
        liteRtFallbackInitializer = null
        try {
            liteRtEnvironment?.close()
        } catch (_: Throwable) {
        }
        liteRtEnvironment = null
    }

    private fun closeLiteRtRunners() {
        synchronized(liteRtLock) {
            liteRtRunners.values.forEach(PaddleOcrV6LiteRtRunner::close)
            liteRtRunners.clear()
            liteRtNhwcScratch = FloatArray(0)
        }
    }

    override suspend fun recognize(crop: Bitmap): String = recognizeWithConf(crop).first

    override suspend fun recognizeWithConf(crop: Bitmap): Pair<String, Float> {
        if (liteRtModelFiles.isNotEmpty()) {
            try {
                return recognizeWithConfLiteRt(crop)
            } catch (liteRtError: Exception) {
                val fallbackInitializer = synchronized(liteRtLock) {
                    val fallback = liteRtFallbackInitializer
                    liteRtFallbackInitializer = null
                    liteRtModelFiles = emptyMap()
                    fallback
                } ?: throw liteRtError

                closeLiteRtRunners()
                logcat(LogPriority.WARN, liteRtError) {
                    "LiteRT PaddleOCR recognition failed; switching to the configured ONNX fallback"
                }
                try {
                    fallbackInitializer()
                } catch (fallbackError: Exception) {
                    fallbackError.addSuppressed(liteRtError)
                    throw fallbackError
                }
                return recognizeWithConf(crop)
            }
        }
        val localSession = session ?: throw IllegalStateException("PaddleOCR v6 small is not initialized")
        var pixelBuffer: FloatBuffer? = null
        var inputTensor: OnnxTensor? = null
        var result: OrtSession.Result? = null
        val start = System.nanoTime()
        try {
            // preprocess writes NCHW straight into the pooled direct
            // buffer; only the filled [width x height x 3] region is exposed via
            // the limit. See inputPixelPool for the leak rationale.
            pixelBuffer = inputPixelPool.acquire()
            pixelBuffer.clear()
            val width = preprocess(crop, pixelBuffer)
            pixelBuffer.limit(3 * RECOGNITION_HEIGHT * width)
            pixelBuffer.position(0)
            inputTensor = OnnxTensor.createTensor(
                OnnxRuntimeProvider.environment,
                pixelBuffer,
                longArrayOf(1, 3, RECOGNITION_HEIGHT.toLong(), width.toLong()),
            )
            val inputTensorValue = inputTensor!!
            result = localSession.run(mapOf(inputName to inputTensorValue))
            val output = result[0] as OnnxTensor
            val shape = output.info.shape
            val timeSteps = shape[1].toInt()
            val classCount = shape[2].toInt()
            val (indices, maxProbs) = PaddleCtcDecoder.argmaxWithProbs(output.floatBuffer, timeSteps, classCount)
            val (text, conf) = PaddleCtcDecoder.decodeWithConf(indices, maxProbs, dictionary)
            if (isDiagnosticsEnabled()) {
                logcat(LogPriority.INFO) {
                    "[paddle_ocr] total=${(System.nanoTime() - start) / 1_000_000.0}ms " +
                        "requestedProvider=$requestedProviderLabel provider=$executionProviderLabel " +
                        "crop=${crop.width}x${crop.height} input=${width}x$RECOGNITION_HEIGHT " +
                        "chars=${text.length}"
                }
            }
            return text to conf
        } finally {
            inputTensor?.close()
            result?.close()
            pixelBuffer?.let { inputPixelPool.release(it) }
        }
    }

    /**
     * Recognize a same-width bucket with one ORT call per microbatch.
     *
     * The return order is exactly [crops] order. A failed pooled B1 allocation
     * uses the pre-existing single-crop path, which remains the universal
     * fallback and uses the same model/preprocessing contract.
     */
    suspend fun recognizeBucketBatch(
        crops: List<Bitmap>,
        widthBucket: Int,
        maxBatch: Int,
    ): List<Pair<String, Float>> {
        if (liteRtModelFiles.isNotEmpty()) {
            require(widthBucket in SUPPORTED_LITERT_WIDTHS) {
                "LiteRT PaddleOCR does not support width bucket $widthBucket"
            }
            lastBatchTelemetry = null
            val indicesByWidth = crops.indices.groupBy { index -> liteRtWidthBucket(crops[index]) }
            val results = arrayOfNulls<Pair<String, Float>>(crops.size)
            for (bucket in SUPPORTED_LITERT_WIDTHS_SORTED) {
                for (index in indicesByWidth[bucket].orEmpty()) {
                    results[index] = recognizeWithConf(crops[index])
                }
            }
            return results.mapIndexed { index, result ->
                checkNotNull(result) { "LiteRT PaddleOCR omitted crop at index $index" }
            }
        }
        val localExecutor = batchExecutor
            ?: throw IllegalStateException("PaddleOCR v6 small is not initialized")
        return try {
            val execution = localExecutor.execute(
                crops = crops,
                widthBucket = widthBucket,
                maxBatch = maxBatch,
            ) { crop, destination, baseOffset, _ ->
                val actualWidth = preprocess(crop, destination, baseOffset, widthBucket)
                check(actualWidth == widthBucket) {
                    "Paddle OCR crop aligned to $actualWidth but batch bucket is $widthBucket"
                }
            }
            lastBatchTelemetry = execution.telemetry
            execution.results
        } catch (failure: PaddleOcrV6BatchExecutionException) {
            lastBatchTelemetry = failure.telemetry
            if (failure.telemetry.downgradeReason ==
                PaddleOcrV6BatchDowngradeReason.ALLOCATION_FAILURE.wireValue
            ) {
                // This intentionally calls the unchanged crop path rather than
                // creating a CPU session or changing provider routing.
                crops.map { recognizeWithConf(it) }
            } else {
                throw failure
            }
        }
    }

    override fun close() {
        closeBackends()
        session = null
        batchSession = null
        batchExecutor = null
        batchBufferPool?.clear()
        batchBufferPool = null
        dictionary = emptyList()
        inputPixelPool.clear()
        lastBatchTelemetry = null
    }

    override fun forceReleaseNativeBuffers() {
        inputPixelPool.clear()
        batchBufferPool?.clear()
        closeLiteRtRunners()
    }

    override fun reclaimPooledMemory() {
        batchBufferPool?.clear()
        closeLiteRtRunners()
    }

    private fun preprocess(
        crop: Bitmap,
        out: FloatBuffer,
        baseOffset: Int = 0,
        targetWidthBucket: Int? = null,
        channelLast: Boolean = false,
    ): Int {
        val safeWidth = crop.width.coerceAtLeast(1)
        val safeHeight = crop.height.coerceAtLeast(1)
        val targetWidth = calculateTargetWidth(safeWidth, safeHeight)

        val inputWidth = targetWidthBucket ?: targetWidth
        val drawWidth = if (targetWidthBucket != null) {
            targetWidth.coerceAtMost(targetWidthBucket)
        } else {
            targetWidth
        }

        var resized: Bitmap? = null
        var padded: Bitmap? = null
        try {
            resized = BitmapPool.getARGB8888(drawWidth, RECOGNITION_HEIGHT)
            Canvas(resized).drawBitmap(
                crop,
                null,
                RectF(0f, 0f, drawWidth.toFloat(), RECOGNITION_HEIGHT.toFloat()),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )

            val sourceBitmap = if (targetWidthBucket != null && targetWidthBucket > drawWidth) {
                padded = BitmapPool.getARGB8888(inputWidth, RECOGNITION_HEIGHT)
                padded.eraseColor(PAD_GRAY)
                Canvas(padded).drawBitmap(resized, 0f, 0f, null)
                padded
            } else {
                resized
            }

            val pixels = IntArray(inputWidth * RECOGNITION_HEIGHT)
            sourceBitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, RECOGNITION_HEIGHT)
            val planeSize = RECOGNITION_HEIGHT * inputWidth
            val totalElements = planeSize * 3
            val floats = FloatArray(totalElements)
            val gOffset = planeSize
            val bOffset = planeSize * 2
            for (i in 0 until planeSize) {
                val pixel = pixels[i]
                floats[i] = normalize(pixel shr 16 and 0xFF)
                floats[gOffset + i] = normalize(pixel shr 8 and 0xFF)
                floats[bOffset + i] = normalize(pixel and 0xFF)
            }
            val originalPosition = out.position()
            out.position(baseOffset)
            if (channelLast) {
                val totalNhwcElements = pixels.size * 3
                if (liteRtNhwcScratch.size < totalNhwcElements) {
                    liteRtNhwcScratch = FloatArray(totalNhwcElements)
                }
                val nhwcFloats = liteRtNhwcScratch
                for (index in pixels.indices) {
                    val pixel = pixels[index]
                    val base = index * 3
                    nhwcFloats[base] = normalize(pixel shr 16 and 0xFF)
                    nhwcFloats[base + 1] = normalize(pixel shr 8 and 0xFF)
                    nhwcFloats[base + 2] = normalize(pixel and 0xFF)
                }
                out.put(nhwcFloats, 0, totalNhwcElements)
            } else {
                out.put(floats, 0, totalElements)
            }
            out.position(originalPosition)
            return inputWidth
        } finally {
            if (padded != null) BitmapPool.putARGB8888(padded)
            if (resized != null) BitmapPool.putARGB8888(resized)
        }
    }

    internal fun calculateTargetWidth(cropWidth: Int, cropHeight: Int): Int {
        val safeWidth = cropWidth.coerceAtLeast(1)
        val safeHeight = cropHeight.coerceAtLeast(1)
        return Math.round(RECOGNITION_HEIGHT.toDouble() * safeWidth / safeHeight).toInt()
            .coerceIn(MIN_RECOGNITION_WIDTH, MAX_RECOGNITION_WIDTH)
    }

    internal fun normalize(value: Int): Float {
        return (value / 255.0f - 0.5f) / 0.5f
    }

    internal fun alignWidth(width: Int): Int {
        // Fixed-width bucketing: inputs <= 640 align to 640; inputs > 640 align to 1600.
        return if (width <= BUCKET_WIDTH_SMALL) {
            BUCKET_WIDTH_SMALL
        } else {
            MAX_RECOGNITION_WIDTH
        }
    }

    internal companion object {
        const val RECOGNITION_HEIGHT = 48
        const val MIN_RECOGNITION_WIDTH = 16

        // PP-OCR rec is trained on (3, 48, 320); width bucketing aligns inputs
        // <= 640 to 640 and > 640 to 1600 (MAX_RECOGNITION_WIDTH).
        const val MIN_TARGET_WIDTH = 640
        const val BUCKET_WIDTH_SMALL = 640
        const val MAX_RECOGNITION_WIDTH = 1600
        private val SUPPORTED_LITERT_WIDTHS_SORTED: List<Int> = listOf(160, 320, 480, 640, 1600)
        val SUPPORTED_LITERT_WIDTHS: Set<Int> = SUPPORTED_LITERT_WIDTHS_SORTED.toSet()
        private const val DEFAULT_LITERT_CPU_THREADS = 4
        private const val MAX_RESIDENT_LITERT_RUNNERS = 1

        // Gray that normalizes to 0.0 (the normalization mean) — used for the
        // right-side padding instead of white, matching the reference pipeline.
        const val PAD_GRAY = 0xFF808080.toInt()

        private fun isDiagnosticsEnabled(): Boolean = OcrDiagnostics.isEnabled()
    }

    private fun String.isCpuLikeProvider(): Boolean =
        equals("cpu", ignoreCase = true) || equals("uninitialized", ignoreCase = true) || isBlank()
}

private data class PaddleOcrV6LiteRtRecognition(
    val text: String,
    val confidence: Float,
    val preprocessMs: Double,
    val neuralForwardMs: Double,
    val ctcDecodeMs: Double,
)

private class PaddleOcrV6LiteRtRunner(
    private val modelFile: File,
    private val expectedWidth: Int,
    private val preferGpu: Boolean,
    private val allowXnnpackFallback: Boolean,
    private val numThreads: Int,
    private val onGpuFallback: () -> Unit,
    private val env: Environment,
) : AutoCloseable {

    private var model: CompiledModel? = null
    private var inputBuffer: TensorBuffer? = null
    private var outputBuffer: TensorBuffer? = null
    private var gpuActive: Boolean = false
    private val inputFloats: FloatArray = FloatArray(1 * PaddleOcrV6SmallEngine.RECOGNITION_HEIGHT * expectedWidth * 3)
    private val timeSteps: Int = expectedWidth / 8
    private val classCount: Int = 18710

    var executionProviderLabel: String = "litert_uninitialized"
        private set

    init {
        try {
            if (preferGpu) {
                try {
                    initializeGpu()
                } catch (failure: Throwable) {
                    closeModel()
                    if (!allowXnnpackFallback) throw failure
                    onGpuFallback()
                    logcat(LogPriority.WARN, failure) {
                        "PaddleOCR LiteRT GPU init failed for width=$expectedWidth; falling back to CPU"
                    }
                    initializeCpu()
                }
            } else {
                initializeCpu()
            }
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    @Synchronized
    fun recognize(
        dictionary: List<String>,
        writeInput: (FloatBuffer) -> Unit,
    ): PaddleOcrV6LiteRtRecognition {
        var preprocessNanos = 0L
        var neuralForwardNanos = 0L
        val wrapBuf = FloatBuffer.wrap(inputFloats)
        val preprocessStart = System.nanoTime()
        writeInput(wrapBuf)
        preprocessNanos += System.nanoTime() - preprocessStart

        var inBuf = checkNotNull(inputBuffer) { "Input buffer is closed" }
        var outBuf = checkNotNull(outputBuffer) { "Output buffer is closed" }
        var activeModel = checkNotNull(model) { "Model is closed" }

        inBuf.writeFloat(inputFloats)
        try {
            val forwardStart = System.nanoTime()
            try {
                activeModel.run(listOf(inBuf), listOf(outBuf))
            } finally {
                neuralForwardNanos += System.nanoTime() - forwardStart
            }
        } catch (failure: Throwable) {
            if (!gpuActive || !allowXnnpackFallback) throw failure
            logcat(LogPriority.WARN, failure) {
                "PaddleOCR LiteRT GPU invoke failed for width=$expectedWidth; retrying with CPU"
            }
            onGpuFallback()
            closeModel()
            initializeCpu()
            inBuf = checkNotNull(inputBuffer)
            outBuf = checkNotNull(outputBuffer)
            activeModel = checkNotNull(model)
            inBuf.writeFloat(inputFloats)
            val forwardStart = System.nanoTime()
            try {
                activeModel.run(listOf(inBuf), listOf(outBuf))
            } finally {
                neuralForwardNanos += System.nanoTime() - forwardStart
            }
        }

        val decodeStart = System.nanoTime()
        val outputFloats = outBuf.readFloat()
        val (indices, probabilities) = PaddleCtcDecoder.argmaxWithProbs(
            FloatBuffer.wrap(outputFloats),
            timeSteps,
            classCount,
        )
        val (text, confidence) = PaddleCtcDecoder.decodeWithConf(indices, probabilities, dictionary)
        val ctcDecodeNanos = System.nanoTime() - decodeStart
        return PaddleOcrV6LiteRtRecognition(
            text = text,
            confidence = confidence,
            preprocessMs = preprocessNanos / 1_000_000.0,
            neuralForwardMs = neuralForwardNanos / 1_000_000.0,
            ctcDecodeMs = ctcDecodeNanos / 1_000_000.0,
        )
    }

    private fun initializeGpu() {
        val options = CompiledModel.Options(Accelerator.GPU)
        val created = CompiledModel.create(modelFile.absolutePath, options, env)
        val inBuf = created.createInputBuffers()[0]
        val outBuf = created.createOutputBuffers()[0]
        model = created
        inputBuffer = inBuf
        outputBuffer = outBuf
        gpuActive = true
        executionProviderLabel = "litert_gpu_delegate"
    }

    private fun initializeCpu() {
        val options = CompiledModel.Options(Accelerator.CPU)
        val created = CompiledModel.create(modelFile.absolutePath, options, env)
        val inBuf = created.createInputBuffers()[0]
        val outBuf = created.createOutputBuffers()[0]
        model = created
        inputBuffer = inBuf
        outputBuffer = outBuf
        gpuActive = false
        executionProviderLabel = "litert_xnnpack"
    }

    private fun closeModel() {
        try {
            inputBuffer?.close()
        } catch (_: Throwable) {
        }
        inputBuffer = null
        try {
            outputBuffer?.close()
        } catch (_: Throwable) {
        }
        outputBuffer = null
        try {
            model?.close()
        } catch (_: Throwable) {
        }
        model = null
        gpuActive = false
    }

    override fun close() {
        closeModel()
    }
}
