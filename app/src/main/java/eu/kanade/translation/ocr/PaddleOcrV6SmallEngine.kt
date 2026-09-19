package eu.kanade.translation.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import eu.kanade.translation.runtime.onnx.OnnxRuntimeProvider
import eu.kanade.translation.runtime.onnx.PaddleOcrProviderTestConfiguration
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.pools.BitmapPool
import tachiyomi.domain.translation.pools.DirectBufferPool
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.FloatBuffer
import kotlin.math.ceil

class PaddleOcrV6SmallEngine : RoiOcrEngine {

    // TachiyomiAT: PP-OCRv6 rec is a CNN+CTC head trained on horizontal text
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

    /** Optional upper bound used to trigger a large-batch latency downgrade. */
    var batchLatencyBudgetMs: Double? = null

    /** Most recent batch execution facts; null until [recognizeBucketBatch] runs. */
    @Volatile
    var lastBatchTelemetry: PaddleOcrV6BatchTelemetry? = null
        private set

    /** Provider that actually serves this recognizer ("qnn_htp"/"nnapi"/"cpu"), for honest perf logging. */
    override var executionProviderLabel: String = "uninitialized"
        private set

    // TachiyomiAT: pooled DIRECT buffer for the rec input. The rec width is bucketed
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
        strictProviderMode: Boolean = false,
        providerConfiguration: PaddleOcrProviderTestConfiguration? = null,
    ) {
        this.strictProviderMode = strictProviderMode || providerConfiguration?.strictNoCpuFallback == true
        logcat(LogPriority.INFO) {
            "PaddleOCR v6 small init: model=${modelFile.absolutePath} (${modelFile.length()}B exists=${modelFile.exists()}), " +
                "dictionary=${dictionaryFile.absolutePath} (${dictionaryFile.length()}B exists=${dictionaryFile.exists()})"
        }
        dictionary = BufferedReader(InputStreamReader(dictionaryFile.inputStream(), Charsets.UTF_8)).use { reader ->
            reader.lineSequence().map { it.trimEnd() }.toList()
        }
        try {
            val createdSession = if (providerConfiguration == null) {
                OnnxRuntimeProvider.createSessionWithFallback(
                    modelFile.absolutePath,
                    useAccelerator = true,
                    providerSink = { executionProviderLabel = it },
                )
            } else {
                OnnxRuntimeProvider.createSessionForPaddleProvider(
                    modelPath = modelFile.absolutePath,
                    configuration = providerConfiguration,
                    providerSink = { executionProviderLabel = it },
                )
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
                maxBatchSize = MAX_BATCH_SIZE,
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
                "PaddleOCR v6 small loaded (dictionary=${dictionary.size}, inputs=${createdSession.inputNames}, outputs=${createdSession.outputNames})"
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "PaddleOCR v6 small session init failed" }
            throw e
        }
    }

    override suspend fun recognize(crop: Bitmap): String = recognizeWithConf(crop).first

    override suspend fun recognizeWithConf(crop: Bitmap): Pair<String, Float> {
        val localSession = session ?: throw IllegalStateException("PaddleOCR v6 small is not initialized")
        var pixelBuffer: FloatBuffer? = null
        var inputTensor: OnnxTensor? = null
        var result: OrtSession.Result? = null
        val start = System.nanoTime()
        try {
            // TachiyomiAT: preprocess writes NCHW straight into the pooled direct
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
        val localExecutor = batchExecutor
            ?: throw IllegalStateException("PaddleOCR v6 small is not initialized")
        return try {
            val execution = localExecutor.execute(
                crops = crops,
                widthBucket = widthBucket,
                maxBatch = maxBatch,
            ) { crop, destination, baseOffset, _ ->
                val actualWidth = preprocess(crop, destination, baseOffset)
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
        session?.close()
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
    }

    override fun reclaimPooledMemory() {
        batchBufferPool?.clear()
    }

    private fun preprocess(crop: Bitmap, out: FloatBuffer, baseOffset: Int = 0): Int {
        val safeWidth = crop.width.coerceAtLeast(1)
        val safeHeight = crop.height.coerceAtLeast(1)
        // TachiyomiAT: match the reference PP-OCR pipeline (comic-translate's
        // ppocr module). Correcting the width/alignment here is a prerequisite
        // for vertical-column splitting (handled by RoiPageRecognitionEngine).
        val scaledWidth = ceil(safeWidth * (RECOGNITION_HEIGHT.toFloat() / safeHeight)).toInt()
            .coerceIn(1, MAX_RECOGNITION_WIDTH)
        val inputWidth = alignWidth(scaledWidth)

        var resized: Bitmap? = null
        var padded: Bitmap? = null
        try {
            resized = BitmapPool.getARGB8888(scaledWidth, RECOGNITION_HEIGHT)
            Canvas(resized).drawBitmap(
                crop,
                null,
                RectF(0f, 0f, scaledWidth.toFloat(), RECOGNITION_HEIGHT.toFloat()),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )

            padded = BitmapPool.getARGB8888(inputWidth, RECOGNITION_HEIGHT)
            // Pad with mid-gray (128) so that after normalize() the pad region is
            // 0.0 — the normalization mean — matching the reference pipeline's
            // np.zeros padding. White (255) normalized to 1.0 skewed recognition.
            padded.eraseColor(PAD_GRAY)
            Canvas(padded).drawBitmap(resized, 0f, 0f, null)

            val pixels = IntArray(inputWidth * RECOGNITION_HEIGHT)
            padded.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, RECOGNITION_HEIGHT)
            // NCHW RGB, written via absolute puts into the direct buffer (no
            // intermediate FloatArray; leaves the buffer position untouched).
            val planeSize = RECOGNITION_HEIGHT * inputWidth
            for (y in 0 until RECOGNITION_HEIGHT) {
                for (x in 0 until inputWidth) {
                    val pixel = pixels[y * inputWidth + x]
                    val offset = y * inputWidth + x
                    out.put(baseOffset + offset, normalize(pixel shr 16 and 0xFF)) // R
                    out.put(baseOffset + planeSize + offset, normalize(pixel shr 8 and 0xFF)) // G
                    out.put(baseOffset + planeSize * 2 + offset, normalize(pixel and 0xFF)) // B
                }
            }
            return inputWidth
        } finally {
            if (padded != null) BitmapPool.putARGB8888(padded)
            if (resized != null) BitmapPool.putARGB8888(resized)
        }
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

        // PP-OCR rec is trained on (3, 48, 320); width bucketing aligns inputs
        // <= 640 to 640 and > 640 to 1600 (MAX_RECOGNITION_WIDTH).
        const val MIN_TARGET_WIDTH = 640
        const val BUCKET_WIDTH_SMALL = 640
        const val MAX_RECOGNITION_WIDTH = 1600
        const val MAX_BATCH_SIZE = 8

        // Gray that normalizes to 0.0 (the normalization mean) — used for the
        // right-side padding instead of white, matching the reference pipeline.
        const val PAD_GRAY = 0xFF808080.toInt()

        private fun isDiagnosticsEnabled(): Boolean = OcrDiagnostics.isEnabled()
    }

    private fun String.isCpuLikeProvider(): Boolean =
        equals("cpu", ignoreCase = true) || equals("uninitialized", ignoreCase = true) || isBlank()
}
