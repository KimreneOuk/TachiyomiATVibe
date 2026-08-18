package eu.kanade.translation.detection

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import eu.kanade.translation.runtime.onnx.OnnxRuntimeProvider
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.pools.BitmapPool
import tachiyomi.domain.translation.pools.DirectBufferPool
import java.io.File
import java.nio.FloatBuffer

/**
 * TachiyomiAT: YOLO26-nano manga panel detector.
 *
 * Loads `manga_panel_detector_int8.onnx` (exported from
 * `leoxs22/manga-panel-detector-yolo26n` via tools/export_panel_detector_onnx.py)
 * and returns the page's panel bounding boxes in original-image coordinates.
 *
 * The model is a YOLO detector with output `[1, N, 4+nc]` (decoded xyxy + per-class
 * confidence). Unlike [OnnxPageTextDetector] (RT-DETR), it does NOT take an
 * `orig_target_sizes` input, so:
 *  - input must be LETTERBOXED to 640x640 (preserve aspect, pad with 114 grey),
 *    not stretched. A stretch resize silently shifts every box off-target.
 *  - output boxes are in the 640x640 padded space; this class maps them back to
 *    the original image using the inverse letterbox transform.
 *  - NMS is run on-device (per-class greedy IoU), since the export bakes in the
 *    decoded tensor but not the final NMS.
 *
 * Only class 0 (panel) is returned; class 1 (text) is dropped because the
 * production RT-DETR text detector is the source of truth for text/bubble boxes.
 *
 * Attempts NNAPI with a CPU retry via createSessionWithFallback, matching the
 * other vision-side ONNX engines. One cheap 640x640 pass
 * per page; runs alongside the text detector during recognition.
 */
class OnnxPanelDetector {

    private var session: OrtSession? = null

    /** Provider that actually serves this detector ("qnn_htp"/"nnapi"/"cpu"), for honest perf logging. */
    var executionProviderLabel: String = "uninitialized"
        private set

    // TachiyomiAT: pooled DIRECT buffer for the fixed 1x3x640x640 tensor. ORT
    // consumes it in place so it MUST outlive the tensor; maxPoolSize=2 bounds
    // native memory. Same contract as OnnxPageTextDetector.
    private val inputBufferPool = DirectBufferPool(
        bufferCapacityBytes = INPUT_FLOATS * Float.SIZE_BYTES,
        maxPoolSize = 2,
    )

    fun initialize(modelFile: File) {
        logcat(LogPriority.INFO) {
            "PanelDetector init: ${modelFile.absolutePath} " +
                "(${modelFile.length()}B exists=${modelFile.exists()})"
        }
        session = OnnxRuntimeProvider.createSessionWithFallback(
            modelFile.absolutePath,
            useAccelerator = true,
            providerSink = { executionProviderLabel = it },
        )
        logcat(LogPriority.INFO) {
            "PanelDetector session created from ${modelFile.name} " +
                "inputs=${session?.inputNames} outputs=${session?.outputNames}"
        }
    }

    fun isInitialized(): Boolean = session != null

    /**
     * Run panel detection on [bitmap]. Returns panel boxes
     * `[x1, y1, x2, y2]` (class 0 only) in original-image coordinates, after
     * confidence threshold + per-class NMS. Empty list when no panels clear
     * the threshold (broken / full-bleed page) — the caller MUST treat that as
     * a real signal (orphan page), not a fallback-to-stretch case.
     */
    fun detect(bitmap: Bitmap): List<FloatArray> {
        val localSession = session ?: throw IllegalStateException("PanelDetector not initialized")

        val t0 = System.nanoTime()
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height

        // Letterbox: scale + center-pad to 640x640.
        val ratio = minOf(IMG_SIZE.toFloat() / originalWidth, IMG_SIZE.toFloat() / originalHeight)
        val newWidth = (originalWidth * ratio).toInt()
        val newHeight = (originalHeight * ratio).toInt()
        val padWidth = (IMG_SIZE - newWidth) / 2
        val padHeight = (IMG_SIZE - newHeight) / 2

        val resized = BitmapPool.getARGB8888(IMG_SIZE, IMG_SIZE)
        var inputTensor: OnnxTensor? = null
        var results: OrtSession.Result? = null
        var inputBuffer: FloatBuffer? = null

        try {
            // Pre-fill the pooled ARGB_8888 canvas with the YOLO pad colour before
            // drawing the scaled source into its centre.
            val canvas = Canvas(resized)
            canvas.drawColor(PAD_COLOR)
            val paint = Paint().apply { isFilterBitmap = true }
            canvas.drawBitmap(
                bitmap,
                null,
                RectF(
                    padWidth.toFloat(),
                    padHeight.toFloat(),
                    (padWidth + newWidth).toFloat(),
                    (padHeight + newHeight).toFloat(),
                ),
                paint,
            )

            inputBuffer = inputBufferPool.acquire()
            inputTensor = preprocess(resized, inputBuffer)
            val inputTensorValue = inputTensor
            val t1 = System.nanoTime()

            val inputName = localSession.inputNames.first()
            results = localSession.run(mapOf(inputName to inputTensorValue))
            val t2 = System.nanoTime()

            // Output [1, N, 4+nc]; ORT surfaces 3-D float tensors as
            // Array<Array<FloatArray>> (batch x N x row), so index [0] for the batch.
            val batched = results[0].value as Array<Array<FloatArray>>
            require(batched.isNotEmpty()) { "PanelDetector: empty batch dim" }
            val raw = batched[0]
            val n = raw.size
            val nc = if (n > 0) raw[0].size - 4 else 0
            if (nc < 1) {
                logcat(LogPriority.WARN) { "PanelDetector: unexpected output shape nc=$nc; returning no panels" }
                return emptyList()
            }

            // Row layout [x1, y1, x2, y2, cls0_conf, cls1_conf, ...]; keep class-0
            // (panel) candidates above threshold.
            val candidates = ArrayList<Box>(n)
            for (i in 0 until n) {
                val row = raw[i]
                val conf = row[4]
                if (conf.isNaN() || conf < CONF_THRESHOLD) continue
                if (!PanelAssignment.isValidBox(row[0], row[1], row[2], row[3])) continue
                candidates.add(Box(row[0], row[1], row[2], row[3], conf))
            }

            val kept = nms(candidates, IOU_THRESHOLD)
            val panels = ArrayList<FloatArray>(kept.size)
            for (b in kept) {
                val ox1 = ((b.x1 - padWidth) / ratio).coerceIn(0f, originalWidth.toFloat())
                val oy1 = ((b.y1 - padHeight) / ratio).coerceIn(0f, originalHeight.toFloat())
                val ox2 = ((b.x2 - padWidth) / ratio).coerceIn(0f, originalWidth.toFloat())
                val oy2 = ((b.y2 - padHeight) / ratio).coerceIn(0f, originalHeight.toFloat())
                if (!PanelAssignment.isValidBox(ox1, oy1, ox2, oy2)) continue
                panels.add(floatArrayOf(ox1, oy1, ox2, oy2))
            }
            val t3 = System.nanoTime()
            logcat(LogPriority.INFO) {
                "[panel-detection] total=${(t3 - t0) / 1_000_000.0}ms " +
                    "preprocess=${(t1 - t0) / 1_000_000.0}ms " +
                    "inference=${(t2 - t1) / 1_000_000.0}ms " +
                    "postprocess=${(t3 - t2) / 1_000_000.0}ms " +
                    "raw=${candidates.size} kept=${panels.size} " +
                    "img=${originalWidth}x$originalHeight ratio=$ratio"
            }
            return panels
        } finally {
            results?.close()
            inputTensor?.close()
            inputBuffer?.let { inputBufferPool.release(it) }
            BitmapPool.putARGB8888(resized)
        }
    }

    fun close() {
        session?.close()
        session = null
        inputBufferPool.clear()
    }

    fun reclaimPooledMemory() {
        inputBufferPool.clear()
    }

    fun forceReleaseNativeBuffers() {
        inputBufferPool.clear()
    }

    // Letterboxed NCHW [1,3,640,640], channel-first (R/G/B into three regions),
    // into a pooled DIRECT buffer — same layout as OnnxPageTextDetector.preprocess.
    private fun preprocess(resized: Bitmap, floatBuf: FloatBuffer): OnnxTensor {
        val pixels = IntArray(IMG_SIZE * IMG_SIZE)
        resized.getPixels(pixels, 0, IMG_SIZE, 0, 0, IMG_SIZE, IMG_SIZE)

        val total = IMG_SIZE * IMG_SIZE
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
            longArrayOf(1, 3, IMG_SIZE.toLong(), IMG_SIZE.toLong()),
        )
    }

    /** Greedy NMS by IoU, dropping lower-confidence overlaps. */
    private fun nms(boxes: List<Box>, iouThreshold: Float): List<Box> {
        if (boxes.isEmpty()) return emptyList()
        val sorted = boxes.sortedByDescending { it.conf }
        val keep = ArrayList<Box>(sorted.size)
        for (cand in sorted) {
            var suppressed = false
            for (k in keep) {
                if (iou(cand, k) > iouThreshold) {
                    suppressed = true
                    break
                }
            }
            if (!suppressed) keep.add(cand)
        }
        return keep
    }

    private fun iou(a: Box, b: Box): Float {
        val ix1 = maxOf(a.x1, b.x1)
        val iy1 = maxOf(a.y1, b.y1)
        val ix2 = minOf(a.x2, b.x2)
        val iy2 = minOf(a.y2, b.y2)
        val iw = ix2 - ix1
        val ih = iy2 - iy1
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        val union = a.area() + b.area() - inter
        return if (union > 0f) inter / union else 0f
    }

    private data class Box(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val conf: Float,
    ) {
        fun area(): Float {
            val w = x2 - x1
            val h = y2 - y1
            return if (w > 0f && h > 0f) w * h else 0f
        }
    }

    private companion object {
        const val IMG_SIZE = 640
        const val INPUT_FLOATS = 3 * IMG_SIZE * IMG_SIZE

        // Confidence threshold for class-0 (panel). Matches the tools/ eval conf
        // (0.5) at which the model was validated across the Okiraku chapter.
        const val CONF_THRESHOLD = 0.5f

        // Standard YOLO NMS IoU. Lower = more aggressive dedupe of overlapping
        // panels (typical manga gutters leave >0.45 IoU between true panels).
        const val IOU_THRESHOLD = 0.45f

        // Ultralytics' default letterbox pad colour (grey 114).
        const val PAD_COLOR = 0xFF727272.toInt()
    }
}
