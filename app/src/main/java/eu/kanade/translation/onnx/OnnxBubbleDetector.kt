package eu.kanade.translation.onnx

import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.pools.BitmapPool
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class Detection(
    val bbox: IntArray,
    val label: Int,
    val score: Float,
    val className: String,
)

class OnnxBubbleDetector {

    private var session: OrtSession? = null

    private val classNames = mapOf(
        0 to "bubble",
        1 to "text_bubble",
        2 to "text_free",
    )

    fun initialize(modelFile: File) {
        val opts = OnnxRuntimeProvider.createSessionOptions()
        try {
            session = OnnxRuntimeProvider.environment.createSession(modelFile.absolutePath, opts)
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

    private fun preprocess(resized: Bitmap): OnnxTensor {
        val pixels = IntArray(640 * 640)
        resized.getPixels(pixels, 0, 640, 0, 0, 640, 640)

        val floatBuffer = FloatBuffer.allocate(1 * 3 * 640 * 640)
        for (c in 0 until 3) {
            for (y in 0 until 640) {
                for (x in 0 until 640) {
                    val pixel = pixels[y * 640 + x]
                    val channelValue = when (c) {
                        0 -> (pixel shr 16 and 0xFF) / 255.0f
                        1 -> (pixel shr 8 and 0xFF) / 255.0f
                        2 -> (pixel and 0xFF) / 255.0f
                        else -> 0f
                    }
                    floatBuffer.put(channelValue)
                }
            }
        }
        floatBuffer.rewind()
        return OnnxTensor.createTensor(
            OnnxRuntimeProvider.environment,
            floatBuffer,
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
