package eu.kanade.translation.detection

import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import eu.kanade.translation.runtime.onnx.OnnxRuntimeProvider
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.pools.BitmapPool
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
                if (keep.any { isGeometricDuplicate(det.bbox, it.bbox) }) {
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

    private fun isGeometricDuplicate(a: IntArray, b: IntArray): Boolean {
        val iou = computeIou(a, b)
        if (iou > IOU_THRESHOLD) return true
        val minArea = min(bboxArea(a), bboxArea(b))
        if (minArea > 0 && intersectionArea(a, b).toFloat() / minArea.toFloat() > CONTAINMENT_THRESHOLD) return true

        val aw = max(1, a[2] - a[0])
        val ah = max(1, a[3] - a[1])
        val bw = max(1, b[2] - b[0])
        val bh = max(1, b[3] - b[1])
        val centerDx = kotlin.math.abs((a[0] + a[2]) - (b[0] + b[2])) / 2f
        val centerDy = kotlin.math.abs((a[1] + a[3]) - (b[1] + b[3])) / 2f
        return centerDx <= CENTER_THRESHOLD * min(aw, bw) &&
            centerDy <= CENTER_THRESHOLD * min(ah, bh) &&
            kotlin.math.abs(aw - bw).toFloat() <= SIZE_THRESHOLD * max(aw, bw) &&
            kotlin.math.abs(ah - bh).toFloat() <= SIZE_THRESHOLD * max(ah, bh)
    }

    private fun computeIou(a: IntArray, b: IntArray): Float {
        val ax1 = a[0]; val ay1 = a[1]; val ax2 = a[2]; val ay2 = a[3]
        val bx1 = b[0]; val by1 = b[1]; val bx2 = b[2]; val by2 = b[3]
        val ix1 = max(ax1, bx1)
        val iy1 = max(ay1, by1)
        val ix2 = min(ax2, bx2)
        val iy2 = min(ay2, by2)
        if (ix2 <= ix1 || iy2 <= iy1) return 0.0f
        val inter = (ix2 - ix1) * (iy2 - iy1)
        val aArea = max(0, ax2 - ax1) * max(0, ay2 - ay1)
        val bArea = max(0, bx2 - bx1) * max(0, by2 - by1)
        val union = aArea + bArea - inter
        return if (union > 0) inter.toFloat() / union.toFloat() else 0.0f
    }

    private fun intersectionArea(a: IntArray, b: IntArray): Int {
        val ix1 = max(a[0], b[0])
        val iy1 = max(a[1], b[1])
        val ix2 = min(a[2], b[2])
        val iy2 = min(a[3], b[3])
        if (ix2 <= ix1 || iy2 <= iy1) return 0
        return (ix2 - ix1) * (iy2 - iy1)
    }

    private fun bboxArea(box: IntArray): Int = max(0, box[2] - box[0]) * max(0, box[3] - box[1])

    companion object {
        private const val CONFIDENCE_THRESHOLD = 0.45f
        private const val IOU_THRESHOLD = 0.75f
        private const val CONTAINMENT_THRESHOLD = 0.88f
        private const val CENTER_THRESHOLD = 0.12f
        private const val SIZE_THRESHOLD = 0.18f
    }
}
