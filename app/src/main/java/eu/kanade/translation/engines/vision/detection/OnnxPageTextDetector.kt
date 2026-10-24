package eu.kanade.translation.engines.vision.detection
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import com.google.ai.edge.litert.Environment
import eu.kanade.translation.engines.runtime.litert.LiteRtVisionLayouts
import eu.kanade.translation.engines.runtime.litert.LiteRtVisionRunner
import eu.kanade.translation.engines.runtime.onnx.HardwareDiscoveryEngine
import eu.kanade.translation.engines.runtime.onnx.OnnxRuntimeProvider
import eu.kanade.translation.engines.vision.ocr.BoxGeometry
import eu.kanade.translation.model.Detection
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.pools.BitmapPool
import tachiyomi.domain.translation.pools.DirectBufferPool
import java.io.File
import java.nio.FloatBuffer

class OnnxPageTextDetector {

    private var session: OrtSession? = null
    private var liteRtRunner: LiteRtVisionRunner? = null
    private var liteRtEnvironment: Environment? = null

    /** Provider that actually serves this detector ("qnn_htp"/"nnapi"/"cpu"/"litert_*"), for honest perf logging. */
    var executionProviderLabel: String = "uninitialized"
        private set

    // pooled DIRECT buffer for the fixed 1x3x640x640 tensor (contract
    // #12). Heap-backed buffers caused a per-call native-copy leak; maxPoolSize=2
    // bounds resident memory to two ~4.8 MiB buffers regardless of chapter length.
    private val inputBufferPool = DirectBufferPool(
        bufferCapacityBytes = DETECTOR_INPUT_FLOATS * Float.SIZE_BYTES,
        maxPoolSize = 2,
    )

    // interleaved NHWC scratch for the LiteRT path; allocated only when the
    // LiteRT backend is initialized.
    private var liteRtInputFloats: FloatArray? = null

    private val classNames = mapOf(
        0 to "bubble",
        1 to "text_bubble",
        2 to "text_free",
    )

    fun initialize(
        modelFile: File,
        requestedHardwareRoute: HardwareDiscoveryEngine.HardwareRoute? = null,
    ) {
        logcat(LogPriority.INFO) {
            "Detector init: ${modelFile.absolutePath} (${modelFile.length()}B exists=${modelFile.exists()})"
        }
        val useAcc = if (requestedHardwareRoute != null) {
            requestedHardwareRoute != HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK
        } else {
            true
        }
        // detector-v4: the accelerator graph-compile can fail on unsupported ops
        // (NNAPI rejects the Split op). The fallback wrapper creates a strict
        // accelerator session first and retries the model on CPU on failure.
        session = OnnxRuntimeProvider.createSessionWithFallback(
            modelFile.absolutePath,
            useAccelerator = useAcc,
            requestedHardwareRoute = requestedHardwareRoute,
            providerSink = { executionProviderLabel = it },
        )
        logcat(LogPriority.INFO) {
            "Detector session created from ${modelFile.name} " +
                "provider=$executionProviderLabel inputs=${session?.inputNames} outputs=${session?.outputNames}"
        }
    }

    /**
     * Initializes the LiteRT backend (GPU attempt + CPU demotion). On total
     * failure, [fallbackToOnnx] runs instead of throwing so the caller keeps
     * its ONNX session; when null, the failure propagates.
     */
    fun initializeLiteRt(
        modelFile: File,
        preferGpu: Boolean = true,
        numThreads: Int = 4,
        fallbackToOnnx: (() -> Unit)? = null,
    ) {
        logcat(LogPriority.INFO) {
            "Detector LiteRT init: ${modelFile.name} (${modelFile.length()}B) preferGpu=$preferGpu"
        }
        val env = try {
            Environment.create()
        } catch (failure: Throwable) {
            runOnnxFallbackAfterLiteRtFailure(fallbackToOnnx, failure)
            return
        }
        try {
            val runner = LiteRtVisionRunner(
                modelFile = modelFile,
                inputCount = 2,
                outputCount = 3,
                preferGpu = preferGpu,
                numThreads = numThreads,
                onGpuFallback = { executionProviderLabel = "litert_xnnpack" },
                env = env,
            )
            liteRtRunner = runner
            liteRtEnvironment = env
            liteRtInputFloats = FloatArray(DETECTOR_INPUT_FLOATS)
            executionProviderLabel = runner.executionProviderLabel
            logcat(LogPriority.INFO) { "Detector LiteRT ready: provider=$executionProviderLabel" }
        } catch (failure: Throwable) {
            runCatching { env.close() }
            runOnnxFallbackAfterLiteRtFailure(fallbackToOnnx, failure)
        }
    }

    private fun runOnnxFallbackAfterLiteRtFailure(fallbackToOnnx: (() -> Unit)?, failure: Throwable) {
        if (fallbackToOnnx == null) throw failure
        logcat(LogPriority.WARN, failure) { "Detector LiteRT init failed; using ONNX session" }
        fallbackToOnnx.invoke()
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        val runner = liteRtRunner
        return if (runner != null) detectLiteRt(runner, bitmap) else detectOnnx(bitmap)
    }

    private fun detectOnnx(bitmap: Bitmap): List<Detection> {
        val localSession = session ?: throw IllegalStateException("Detector not initialized")

        val t0 = System.nanoTime()
        val resized = BitmapPool.getARGB8888(640, 640)
        val canvas = android.graphics.Canvas(resized)
        val paint = android.graphics.Paint().apply { isFilterBitmap = true }
        canvas.drawBitmap(bitmap, null, android.graphics.RectF(0f, 0f, 640f, 640f), paint)
        var inputTensor: OnnxTensor? = null
        var sizesTensor: OnnxTensor? = null
        var results: OrtSession.Result? = null
        var inputBuffer: FloatBuffer? = null

        try {
            // ORT consumes the direct buffer in place (no native copy),
            // so it MUST outlive the tensor — keep referenced until the finally (#12).
            inputBuffer = inputBufferPool.acquire()
            inputTensor = preprocess(resized, inputBuffer)
            sizesTensor = createOriginalSizes(bitmap)
            val inputTensorValue = inputTensor!!
            val sizesTensorValue = sizesTensor!!
            val t1 = System.nanoTime()

            results = localSession.run(
                mapOf(
                    "images" to inputTensorValue,
                    "orig_target_sizes" to sizesTensorValue,
                ),
            )
            val t2 = System.nanoTime()

            val labelsArray = (results[0].value as Array<LongArray>)[0]
            val boxesArray = (results[1].value as Array<Array<FloatArray>>)[0]
            val scoresArray = (results[2].value as Array<FloatArray>)[0]

            val labelInts = labelsArray.map { it.toInt() }.toIntArray()
            val rawByLabel = labelInts.toList().groupingBy { it }.eachCount()
            val detections = postprocess(labelInts, boxesArray, scoresArray)
            val filteredByLabel = detections.groupingBy { it.label }.eachCount()
            val deduplicated = deduplicateLabels(detections)
            val dedupedByLabel = deduplicated.groupingBy { it.label }.eachCount()
            val t3 = System.nanoTime()

            logcat(LogPriority.INFO) {
                "[detection_perf] model=detector provider=$executionProviderLabel total=${(t3 - t0) / 1_000_000.0}ms " +
                    "preprocess=${(t1 - t0) / 1_000_000.0}ms " +
                    "inference=${(t2 - t1) / 1_000_000.0}ms " +
                    "postprocess=${(t3 - t2) / 1_000_000.0}ms " +
                    "rawLabels=$rawByLabel filteredLabels=$filteredByLabel dedupedLabels=$dedupedByLabel " +
                    "found=${deduplicated.size}"
            }
            return deduplicated
        } finally {
            results?.close()
            inputTensor?.close()
            sizesTensor?.close()
            inputBuffer?.let { inputBufferPool.release(it) }
            BitmapPool.putARGB8888(resized)
        }
    }

    // LiteRT fp32 export: NHWC images [1,640,640,3] + int64 orig_target_sizes
    // [1,2]; outputs in subgraph order labels INT32 [1,300], boxes FLOAT
    // [1,300,4], scores FLOAT [1,300] (index order pinned by the sha256 stamp).
    private fun detectLiteRt(runner: LiteRtVisionRunner, bitmap: Bitmap): List<Detection> {
        val t0 = System.nanoTime()
        val resized = BitmapPool.getARGB8888(640, 640)
        try {
            val canvas = android.graphics.Canvas(resized)
            val paint = android.graphics.Paint().apply { isFilterBitmap = true }
            canvas.drawBitmap(bitmap, null, android.graphics.RectF(0f, 0f, 640f, 640f), paint)
            val pixels = IntArray(640 * 640)
            resized.getPixels(pixels, 0, 640, 0, 0, 640, 640)
            val inputFloats = requireNotNull(liteRtInputFloats) { "LiteRT detector input scratch is not allocated" }
            val t1 = System.nanoTime()

            // Reads stay inside the retry closure: a GPU demotion swaps every
            // buffer, so the outputs must come from the backend that ran.
            var labels: IntArray? = null
            var boxes: FloatArray? = null
            var scores: FloatArray? = null
            runner.runWithCpuRetry {
                for (i in pixels.indices) {
                    LiteRtVisionLayouts.writeNhwcPixel(pixels[i], inputFloats, i)
                }
                runner.writeInputFloats(0, inputFloats)
                runner.writeInputLongs(1, longArrayOf(bitmap.width.toLong(), bitmap.height.toLong()))
                runner.runInference()
                labels = runner.readOutputInts(0)
                boxes = runner.readOutputFloats(1)
                scores = runner.readOutputFloats(2)
            }
            val t2 = System.nanoTime()

            val labelInts = requireNotNull(labels)
            val boxRows = detectorBoxRows(requireNotNull(boxes), DETECTOR_QUERY_COUNT)
            val scoreArray = requireNotNull(scores)
            if (labelInts.size != DETECTOR_QUERY_COUNT || scoreArray.size != DETECTOR_QUERY_COUNT) {
                logcat(LogPriority.WARN) {
                    "Detector LiteRT: unexpected output sizes labels=${labelInts.size} scores=${scoreArray.size}; returning no detections"
                }
                return emptyList()
            }
            val rawByLabel = labelInts.toList().groupingBy { it }.eachCount()
            val detections = postprocess(labelInts, boxRows, scoreArray)
            val filteredByLabel = detections.groupingBy { it.label }.eachCount()
            val deduplicated = deduplicateLabels(detections)
            val dedupedByLabel = deduplicated.groupingBy { it.label }.eachCount()
            val t3 = System.nanoTime()

            logcat(LogPriority.INFO) {
                "[detection_perf] model=detector provider=${runner.executionProviderLabel} total=${(t3 - t0) / 1_000_000.0}ms " +
                    "preprocess=${(t1 - t0) / 1_000_000.0}ms " +
                    "inference=${(t2 - t1) / 1_000_000.0}ms " +
                    "postprocess=${(t3 - t2) / 1_000_000.0}ms " +
                    "rawLabels=$rawByLabel filteredLabels=$filteredByLabel dedupedLabels=$dedupedByLabel " +
                    "found=${deduplicated.size}"
            }
            return deduplicated
        } finally {
            BitmapPool.putARGB8888(resized)
        }
    }

    fun close() {
        liteRtRunner?.close()
        liteRtRunner = null
        liteRtEnvironment?.close()
        liteRtEnvironment = null
        liteRtInputFloats = null
        session?.close()
        session = null
        inputBufferPool.clear()
    }

    // frees the pooled direct buffer without tearing down the ONNX
    // session; wired into per-page OOM relief (contracts #4/#10).
    fun reclaimPooledMemory() {
        inputBufferPool.clear()
    }

    fun forceReleaseNativeBuffers() {
        inputBufferPool.clear()
    }

    // NCHW [1,3,640,640] written channel-first into a pooled DIRECT
    // buffer (contract #12). Every position is overwritten each call, so the
    // pool's non-zeroed acquire is safe (contract #1).
    private fun preprocess(resized: Bitmap, floatBuf: FloatBuffer): OnnxTensor {
        val pixels = IntArray(640 * 640)
        resized.getPixels(pixels, 0, 640, 0, 0, 640, 640)

        val total = 640 * 640
        floatBuf.clear()
        for (i in 0 until total) {
            val pixel = pixels[i]
            floatBuf.put(i, (pixel shr 16 and 0xFF) / 255.0f)
            floatBuf.put(total + i, (pixel shr 8 and 0xFF) / 255.0f)
            floatBuf.put(2 * total + i, (pixel and 0xFF) / 255.0f)
        }
        floatBuf.limit(3 * total)
        floatBuf.position(0)
        return OnnxTensor.createTensor(
            OnnxRuntimeProvider.environment,
            floatBuf,
            longArrayOf(1, 3, 640, 640),
        )
    }

    private fun createOriginalSizes(bitmap: Bitmap): OnnxTensor {
        val originalSizes = java.nio.ByteBuffer.allocateDirect(16)
            .order(java.nio.ByteOrder.nativeOrder())
            .asLongBuffer()
        originalSizes.put(0, bitmap.width.toLong())
        originalSizes.put(1, bitmap.height.toLong())
        return OnnxTensor.createTensor(
            OnnxRuntimeProvider.environment,
            originalSizes,
            longArrayOf(1, 2),
        )
    }

    private fun postprocess(
        labels: IntArray,
        boxes: Array<FloatArray>,
        scores: FloatArray,
    ): List<Detection> {
        val detections = mutableListOf<Detection>()
        for (i in labels.indices) {
            val scoreValue = scores[i]
            if (scoreValue.isNaN()) continue
            if (scoreValue < CONFIDENCE_THRESHOLD) continue
            val box = boxes[i]
            detections.add(
                Detection(
                    bbox = intArrayOf(
                        box[0].toInt(),
                        box[1].toInt(),
                        box[2].toInt(),
                        box[3].toInt(),
                    ),
                    label = labels[i],
                    score = (Math.round(scoreValue * 10000.0) / 10000.0).toFloat(),
                    className = classNames[labels[i]] ?: "class_${labels[i]}",
                ),
            )
        }
        return detections
    }

    private fun deduplicateLabels(
        detections: List<Detection>,
        labels: Set<Int> = setOf(1, 2),
    ): List<Detection> {
        if (detections.size < 2) return detections

        val removed = mutableSetOf<Detection>()
        for (label in labels) {
            val indexed = detections
                .mapIndexedNotNull { idx, det -> if (det.label == label) idx to det else null }
                .sortedByDescending { it.second.score }
            val keep = mutableListOf<Detection>()
            for ((_, det) in indexed) {
                if (keep.any { BoxGeometry.isGeometricDuplicate(det.bbox, it.bbox, DEDUP_THRESHOLDS) }) {
                    removed.add(det)
                } else {
                    keep.add(det)
                }
            }
        }

        if (removed.isEmpty()) return detections
        logcat(LogPriority.INFO) { "Detector geometric dedupe removed ${removed.size} same-label text detections" }
        return detections.filter { it !in removed }
    }

    companion object {
        private const val CONFIDENCE_THRESHOLD = 0.45f

        private const val DETECTOR_INPUT_FLOATS = 3 * 640 * 640

        // RT-DETR fixed query count: labels/scores [1,300], boxes [1,300,4].
        private const val DETECTOR_QUERY_COUNT = 300

        /**
         * Tuned thresholds for the detector-stage geometric dedupe. The
         * algorithm lives in [BoxGeometry]; only the constants are stage-specific.
         */
        private val DEDUP_THRESHOLDS = BoxGeometry.DedupThresholds(
            iou = 0.75f,
            containment = 0.88f,
            center = 0.12f,
            size = 0.18f,
        )
    }
}

/**
 * Splits the flat `[1, queries, 4]` XYXY box tensor into per-query rows.
 * Pure; unit-tested.
 */
internal fun detectorBoxRows(flat: FloatArray, queries: Int): Array<FloatArray> = Array(queries) { i ->
    floatArrayOf(flat[4 * i], flat[4 * i + 1], flat[4 * i + 2], flat[4 * i + 3])
}
