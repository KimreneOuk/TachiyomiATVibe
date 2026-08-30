package eu.kanade.translation.detection
import eu.kanade.translation.model.Detection

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import eu.kanade.translation.recognition.BoxGeometry
import eu.kanade.translation.runtime.onnx.OnnxRuntimeProvider
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.pools.BitmapPool
import tachiyomi.domain.translation.pools.DirectBufferPool
import java.io.File
import java.nio.FloatBuffer

class OnnxPageTextDetector {

    private var session: OrtSession? = null

    /** Provider that actually serves this detector ("qnn_htp"/"nnapi"/"cpu"), for honest perf logging. */
    var executionProviderLabel: String = "uninitialized"
        private set

    // TachiyomiAT: pooled DIRECT buffer for the fixed 1x3x640x640 tensor (contract
    // #12). Heap-backed buffers caused a per-call native-copy leak; maxPoolSize=2
    // bounds resident memory to two ~4.8 MiB buffers regardless of chapter length.
    private val inputBufferPool = DirectBufferPool(
        bufferCapacityBytes = DETECTOR_INPUT_FLOATS * Float.SIZE_BYTES,
        maxPoolSize = 2,
    )

    private val classNames = mapOf(
        0 to "bubble",
        1 to "text_bubble",
        2 to "text_free",
    )

    fun initialize(modelFile: File) {
        logcat(LogPriority.INFO) {
            "Detector init: ${modelFile.absolutePath} (${modelFile.length()}B exists=${modelFile.exists()})"
        }
        // detector-v4: the accelerator graph-compile can fail on unsupported ops
        // (NNAPI rejects the Split op). The fallback wrapper creates a strict
        // accelerator session first and retries the model on CPU on failure.
        session = OnnxRuntimeProvider.createSessionWithFallback(
            modelFile.absolutePath,
            useAccelerator = true,
            providerSink = { executionProviderLabel = it },
        )
        logcat(LogPriority.INFO) {
            "Detector session created from ${modelFile.name} " +
                "inputs=${session?.inputNames} outputs=${session?.outputNames}"
        }
    }

    fun detect(bitmap: Bitmap): List<Detection> {
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
            // TachiyomiAT: ORT consumes the direct buffer in place (no native copy),
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

            val rawByLabel = labelsArray.map { it.toInt() }.groupingBy { it }.eachCount()
            val detections = postprocess(labelsArray, boxesArray, scoresArray)
            val filteredByLabel = detections.groupingBy { it.label }.eachCount()
            val deduplicated = deduplicateLabels(detections)
            val dedupedByLabel = deduplicated.groupingBy { it.label }.eachCount()
            val t3 = System.nanoTime()

            logcat(LogPriority.INFO) {
                "[detection] total=${(t3 - t0) / 1_000_000.0}ms " +
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

    fun close() {
        session?.close()
        session = null
        inputBufferPool.clear()
    }

    // TachiyomiAT: frees the pooled direct buffer without tearing down the ONNX
    // session; wired into per-page OOM relief (contracts #4/#10).
    fun reclaimPooledMemory() {
        inputBufferPool.clear()
    }

    fun forceReleaseNativeBuffers() {
        inputBufferPool.clear()
    }

    // TachiyomiAT: NCHW [1,3,640,640] written channel-first into a pooled DIRECT
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
        labels: LongArray,
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
                    label = labels[i].toInt(),
                    score = (Math.round(scoreValue * 10000.0) / 10000.0).toFloat(),
                    className = classNames[labels[i].toInt()] ?: "class_${labels[i]}",
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
