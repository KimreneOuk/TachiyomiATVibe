package eu.kanade.translation.detection

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import eu.kanade.translation.recognition.BoxGeometry
import eu.kanade.translation.runtime.onnx.OnnxRuntimeProvider
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.pools.BitmapPool
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

class OnnxPageTextDetector {

    private var session: OrtSession? = null

    private val classNames = mapOf(
        0 to "bubble",
        1 to "text_bubble",
        2 to "text_free",
    )

    fun initialize(modelFile: File) {
        logcat(LogPriority.INFO) {
            "Detector init: ${modelFile.absolutePath} (${modelFile.length()}B exists=${modelFile.exists()})"
        }
        // TachiyomiAT: detector stays on CPU. It's one cheap 640x640 pass per
        // page and runs in the same init sequence as the manga-ocr sessions;
        // keeping it off the accelerator avoids any chance an NNAPI partitioning
        // hiccup destabilizes the OCR session init that follows. The AOT
        // inpainting model is the only model that opts into the accelerator
        // (single big generative pass — its ideal workload).
        val opts = OnnxRuntimeProvider.createSessionOptions(forceCpu = true)
        try {
            session = OnnxRuntimeProvider.environment.createSession(modelFile.absolutePath, opts)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Detector init: session FAILED for ${modelFile.absolutePath}" }
            throw e
        } finally {
            opts.close()
        }
        logcat(LogPriority.INFO) {
            "Detector session created from ${modelFile.name} " +
                "inputs=${session?.inputNames} outputs=${session?.outputNames}"
        }
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        val sess = session ?: throw IllegalStateException("Detector not initialized")

        val t0 = System.nanoTime()
        val resized = BitmapPool.getARGB8888(640, 640)
        val canvas = android.graphics.Canvas(resized)
        val paint = android.graphics.Paint().apply { isFilterBitmap = true }
        canvas.drawBitmap(bitmap, null, android.graphics.RectF(0f, 0f, 640f, 640f), paint)
        var inputTensor: OnnxTensor? = null
        var sizesTensor: OnnxTensor? = null
        var results: OrtSession.Result? = null

        try {
            inputTensor = preprocess(resized)
            sizesTensor = createOrigSizes(bitmap)
            val inputTensorValue = inputTensor!!
            val sizesTensorValue = sizesTensor!!
            val t1 = System.nanoTime()

            results = sess.run(
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
            BitmapPool.putARGB8888(resized)
        }
    }

    fun close() {
        session?.close()
        session = null
    }

    // TachiyomiAT: rewritten from a triple-nested per-channel loop
    // (for c in 0..2 { for y { for x { ... } } }) to a single-pass scan through
    // the pixel array. The old code traversed every pixel three times (once per
    // channel); this version reads each pixel once, extracts R/G/B, and writes
    // to contiguous FloatBuffer regions via bulk-put where possible, reducing
    // the per-page pixel-array traversal from ~3.6M to ~1.2M.
    private fun preprocess(resized: Bitmap): OnnxTensor {
        val pixels = IntArray(640 * 640)
        resized.getPixels(pixels, 0, 640, 0, 0, 640, 640)

        val total = 640 * 640
        // Layout: [R_0...R_n, G_0...G_n, B_0...B_n] — contiguous float arrays
        // so ONNX can read them directly without interleaving.
        val rChannel = FloatArray(total)
        val gChannel = FloatArray(total)
        val bChannel = FloatArray(total)
        for (i in 0 until total) {
            val pixel = pixels[i]
            rChannel[i] = (pixel shr 16 and 0xFF) / 255.0f
            gChannel[i] = (pixel shr 8 and 0xFF) / 255.0f
            bChannel[i] = (pixel and 0xFF) / 255.0f
        }
        val floatBuf = FloatBuffer.allocate(3 * total)
        floatBuf.put(rChannel)
        floatBuf.put(gChannel)
        floatBuf.put(bChannel)
        floatBuf.rewind()
        return OnnxTensor.createTensor(
            OnnxRuntimeProvider.environment,
            floatBuf,
            longArrayOf(1, 3, 640, 640),
        )
    }

    private fun createOrigSizes(bitmap: Bitmap): OnnxTensor {
        val origSizes = longArrayOf(bitmap.width.toLong(), bitmap.height.toLong())
        return OnnxTensor.createTensor(
            OnnxRuntimeProvider.environment,
            LongBuffer.wrap(origSizes),
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
            val scr = scores[i]
            if (scr.isNaN()) continue
            if (scr < CONFIDENCE_THRESHOLD) continue
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
                    score = (Math.round(scr * 10000.0) / 10000.0).toFloat(),
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
