package eu.kanade.translation.engines.vision.detection

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.google.ai.edge.litert.Environment
import eu.kanade.translation.engines.runtime.litert.LiteRtVisionLayouts
import eu.kanade.translation.engines.runtime.litert.LiteRtVisionRunner
import eu.kanade.translation.engines.runtime.onnx.HardwareDiscoveryEngine
import eu.kanade.translation.engines.runtime.onnx.OnnxRuntimeProvider
import eu.kanade.translation.engines.runtime.onnx.QnnVisionContextCache
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.pools.BitmapPool
import tachiyomi.domain.translation.pools.DirectBufferPool
import java.io.File
import java.nio.FloatBuffer

/**
 * YOLO26-nano manga panel detector.
 *
 * Loads `manga_panel_detector_int8.onnx` (exported from
 * `leoxs22/manga-panel-detector-yolo26n` for ONNX inference)
 * and returns the page's panel bounding boxes in original-image coordinates.
 *
 * The model is a YOLO detector with output `[1, N, 4+nc]` where each row is
 * `(cx, cy, w, h, conf0, conf1, ...)` in the 640x640 letterbox space. Unlike
 * [OnnxPageTextDetector] (RT-DETR), it does NOT take an
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
 * Two interchangeable backends: the ONNX session (NNAPI attempt + CPU retry
 * via createSessionWithFallback) and the LiteRT fp32 flatbuffer (GPU
 * delegate attempt + one-shot XNNPACK demotion) used when the store deployed
 * `panel_detector_fp32.tflite`. One cheap 640x640 pass per page; runs
 * alongside the text detector during recognition.
 */
class OnnxPanelDetector {

    private var session: OrtSession? = null
    private var liteRtRunner: LiteRtVisionRunner? = null
    private var liteRtEnvironment: Environment? = null

    /** Provider that actually serves this detector ("qnn_htp"/"nnapi"/"cpu"/"litert_*"), for honest perf logging. */
    var executionProviderLabel: String = "uninitialized"
        private set

    // pooled DIRECT buffer for the fixed 1x3x640x640 tensor. ORT
    // consumes it in place so it MUST outlive the tensor; maxPoolSize=2 bounds
    // native memory. Same contract as OnnxPageTextDetector.
    private val inputBufferPool = DirectBufferPool(
        bufferCapacityBytes = INPUT_FLOATS * Float.SIZE_BYTES,
        maxPoolSize = 2,
    )

    // interleaved NHWC scratch for the LiteRT path; allocated only when the
    // LiteRT backend is initialized.
    private var liteRtInputFloats: FloatArray? = null

    fun initialize(
        modelFile: File,
        requestedHardwareRoute: HardwareDiscoveryEngine.HardwareRoute? = null,
    ) {
        logcat(LogPriority.INFO) {
            "PanelDetector init: ${modelFile.absolutePath} " +
                "(${modelFile.length()}B exists=${modelFile.exists()})"
        }
        val useAcc = if (requestedHardwareRoute != null) {
            requestedHardwareRoute != HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK
        } else {
            true
        }
        val contextPlan = QnnVisionContextCache.plan(modelFile, useAcc, requestedHardwareRoute)
        session = OnnxRuntimeProvider.createSessionWithFallback(
            modelFile.absolutePath,
            useAccelerator = useAcc,
            requestedHardwareRoute = requestedHardwareRoute,
            contextCacheFile = contextPlan?.contextFile,
            providerSink = { executionProviderLabel = it },
        )
        contextPlan?.onSessionCreated(executionProviderLabel)
        logcat(LogPriority.INFO) {
            "PanelDetector session created from ${modelFile.name} " +
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
            "PanelDetector LiteRT init: ${modelFile.name} (${modelFile.length()}B) preferGpu=$preferGpu"
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
                inputCount = 1,
                outputCount = 1,
                preferGpu = preferGpu,
                numThreads = numThreads,
                onGpuFallback = { executionProviderLabel = "litert_xnnpack" },
                env = env,
            )
            liteRtRunner = runner
            liteRtEnvironment = env
            liteRtInputFloats = FloatArray(INPUT_FLOATS)
            executionProviderLabel = runner.executionProviderLabel
            logcat(LogPriority.INFO) { "PanelDetector LiteRT ready: provider=$executionProviderLabel" }
        } catch (failure: Throwable) {
            runCatching { env.close() }
            runOnnxFallbackAfterLiteRtFailure(fallbackToOnnx, failure)
        }
    }

    private fun runOnnxFallbackAfterLiteRtFailure(fallbackToOnnx: (() -> Unit)?, failure: Throwable) {
        if (fallbackToOnnx == null) throw failure
        logcat(LogPriority.WARN, failure) { "PanelDetector LiteRT init failed; using ONNX session" }
        fallbackToOnnx.invoke()
    }

    fun isInitialized(): Boolean = session != null || liteRtRunner != null

    /**
     * Run panel detection on [bitmap]. Returns panel boxes
     * `[x1, y1, x2, y2]` (class 0 only) in original-image coordinates, after
     * confidence threshold + per-class NMS. Empty list when no panels clear
     * the threshold (broken / full-bleed page) — the caller MUST treat that as
     * a real signal (orphan page), not a fallback-to-stretch case.
     */
    fun detect(bitmap: Bitmap): List<FloatArray> {
        val runner = liteRtRunner
        return if (runner != null) detectLiteRt(runner, bitmap) else detectOnnx(bitmap)
    }

    private fun detectOnnx(bitmap: Bitmap): List<FloatArray> {
        val localSession = session ?: throw IllegalStateException("PanelDetector not initialized")

        val t0 = System.nanoTime()
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        val letterbox = letterbox(bitmap)
        var inputTensor: OnnxTensor? = null
        var results: OrtSession.Result? = null
        var inputBuffer: FloatBuffer? = null

        try {
            inputBuffer = inputBufferPool.acquire()
            inputTensor = preprocess(letterbox.bitmap, inputBuffer)
            val t1 = System.nanoTime()

            val inputName = localSession.inputNames.first()
            results = localSession.run(mapOf(inputName to inputTensor))
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

            // Row layout (cx, cy, w, h, conf0, conf1, ...); keep class-0
            // (panel) candidates above threshold.
            val candidates = ArrayList<Box>(n)
            for (i in 0 until n) {
                val decoded = decodePanelYoloRow(raw[i], CONF_THRESHOLD) ?: continue
                candidates.add(Box(decoded[0], decoded[1], decoded[2], decoded[3], decoded[4]))
            }

            val panels = rescaleToOriginal(nms(candidates, IOU_THRESHOLD), letterbox, originalWidth, originalHeight)
            val t3 = System.nanoTime()
            logcat(LogPriority.INFO) {
                "[panel_perf] model=panel_detector provider=$executionProviderLabel total=${(t3 - t0) / 1_000_000.0}ms " +
                    "preprocess=${(t1 - t0) / 1_000_000.0}ms " +
                    "inference=${(t2 - t1) / 1_000_000.0}ms " +
                    "postprocess=${(t3 - t2) / 1_000_000.0}ms " +
                    "raw=${candidates.size} kept=${panels.size} " +
                    "img=${originalWidth}x$originalHeight ratio=${letterbox.ratio}"
            }
            return panels
        } finally {
            results?.close()
            inputTensor?.close()
            inputBuffer?.let { inputBufferPool.release(it) }
            BitmapPool.putARGB8888(letterbox.bitmap)
        }
    }

    private fun detectLiteRt(runner: LiteRtVisionRunner, bitmap: Bitmap): List<FloatArray> {
        val t0 = System.nanoTime()
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        val letterbox = letterbox(bitmap)
        try {
            val pixels = IntArray(IMG_SIZE * IMG_SIZE)
            letterbox.bitmap.getPixels(pixels, 0, IMG_SIZE, 0, 0, IMG_SIZE, IMG_SIZE)
            val inputFloats = requireNotNull(liteRtInputFloats) { "LiteRT panel input scratch is not allocated" }
            val t1 = System.nanoTime()

            // Reads stay inside the retry closure: a GPU demotion swaps every
            // buffer, so the output must come from the backend that ran.
            var raw: FloatArray? = null
            runner.runWithCpuRetry {
                for (i in pixels.indices) {
                    LiteRtVisionLayouts.writeNhwcPixel(pixels[i], inputFloats, i)
                }
                runner.writeInputFloats(0, inputFloats)
                runner.runInference()
                raw = runner.readOutputFloats(0)
            }
            val output = requireNotNull(raw)
            if (output.size < PANEL_ANCHORS * 5) {
                logcat(LogPriority.WARN) {
                    "PanelDetector LiteRT: unexpected output size=${output.size}; returning no panels"
                }
                return emptyList()
            }
            val t2 = System.nanoTime()

            val candidates = ArrayList<Box>(64)
            for (a in 0 until PANEL_ANCHORS) {
                val decoded = decodePanelYoloRow(panelChannelFirstRow(output, a, PANEL_ANCHORS), CONF_THRESHOLD) ?: continue
                candidates.add(Box(decoded[0], decoded[1], decoded[2], decoded[3], decoded[4]))
            }

            val panels = rescaleToOriginal(nms(candidates, IOU_THRESHOLD), letterbox, originalWidth, originalHeight)
            val t3 = System.nanoTime()
            logcat(LogPriority.INFO) {
                "[panel_perf] model=panel_detector provider=${runner.executionProviderLabel} total=${(t3 - t0) / 1_000_000.0}ms " +
                    "preprocess=${(t1 - t0) / 1_000_000.0}ms " +
                    "inference=${(t2 - t1) / 1_000_000.0}ms " +
                    "postprocess=${(t3 - t2) / 1_000_000.0}ms " +
                    "raw=${candidates.size} kept=${panels.size} " +
                    "img=${originalWidth}x$originalHeight ratio=${letterbox.ratio}"
            }
            return panels
        } finally {
            BitmapPool.putARGB8888(letterbox.bitmap)
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

    fun reclaimPooledMemory() {
        inputBufferPool.clear()
    }

    fun forceReleaseNativeBuffers() {
        inputBufferPool.clear()
    }

    /** Pooled 640x640 letterboxed canvas plus its inverse-transform parameters. */
    private class Letterbox(
        val bitmap: Bitmap,
        val ratio: Float,
        val padWidth: Int,
        val padHeight: Int,
    )

    // Letterbox: scale + center-pad to 640x640 onto a pooled bitmap prefilled
    // with the YOLO pad colour (grey 114).
    private fun letterbox(bitmap: Bitmap): Letterbox {
        val ratio = minOf(IMG_SIZE.toFloat() / bitmap.width, IMG_SIZE.toFloat() / bitmap.height)
        val newWidth = (bitmap.width * ratio).toInt()
        val newHeight = (bitmap.height * ratio).toInt()
        val padWidth = (IMG_SIZE - newWidth) / 2
        val padHeight = (IMG_SIZE - newHeight) / 2
        val resized = BitmapPool.getARGB8888(IMG_SIZE, IMG_SIZE)
        Canvas(resized).apply {
            drawColor(PAD_COLOR)
            drawBitmap(
                bitmap,
                null,
                RectF(
                    padWidth.toFloat(),
                    padHeight.toFloat(),
                    (padWidth + newWidth).toFloat(),
                    (padHeight + newHeight).toFloat(),
                ),
                Paint().apply { isFilterBitmap = true },
            )
        }
        return Letterbox(resized, ratio, padWidth, padHeight)
    }

    // Inverse letterbox into original-image coordinates, clamped and validated.
    private fun rescaleToOriginal(kept: List<Box>, letterbox: Letterbox, originalWidth: Int, originalHeight: Int): List<FloatArray> {
        val panels = ArrayList<FloatArray>(kept.size)
        for (b in kept) {
            val ox1 = ((b.x1 - letterbox.padWidth) / letterbox.ratio).coerceIn(0f, originalWidth.toFloat())
            val oy1 = ((b.y1 - letterbox.padHeight) / letterbox.ratio).coerceIn(0f, originalHeight.toFloat())
            val ox2 = ((b.x2 - letterbox.padWidth) / letterbox.ratio).coerceIn(0f, originalWidth.toFloat())
            val oy2 = ((b.y2 - letterbox.padHeight) / letterbox.ratio).coerceIn(0f, originalHeight.toFloat())
            if (!PanelAssignment.isValidBox(ox1, oy1, ox2, oy2)) continue
            panels.add(floatArrayOf(ox1, oy1, ox2, oy2))
        }
        return panels
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

        // YOLO26 anchor grid: [1, 6, 8400] in the LiteRT fp32 export.
        const val PANEL_ANCHORS = 8400

        // Confidence threshold for class-0 (panel), set to 0.5 after validation
        // across the Okiraku chapter.
        const val CONF_THRESHOLD = 0.5f

        // Standard YOLO NMS IoU. Lower = more aggressive dedupe of overlapping
        // panels (typical manga gutters leave >0.45 IoU between true panels).
        const val IOU_THRESHOLD = 0.45f

        // Ultralytics' default letterbox pad colour (grey 114).
        const val PAD_COLOR = 0xFF727272.toInt()
    }
}

/**
 * Decode one YOLO output row `(cx, cy, w, h, conf, ...)` into
 * `[x1, y1, x2, y2, conf]` letterbox-space corners, or null when the row is
 * below [confThreshold] or not a valid box. Pure; unit-tested.
 */
internal fun decodePanelYoloRow(row: FloatArray, confThreshold: Float): FloatArray? {
    if (row.size < 5) return null
    val conf = row[4]
    if (conf.isNaN() || conf < confThreshold) return null
    val x1 = row[0] - row[2] / 2f
    val y1 = row[1] - row[3] / 2f
    val x2 = row[0] + row[2] / 2f
    val y2 = row[1] + row[3] / 2f
    if (!PanelAssignment.isValidBox(x1, y1, x2, y2)) return null
    return floatArrayOf(x1, y1, x2, y2, conf)
}

/**
 * Gathers one anchor's `(cx, cy, w, h, conf0)` from the channels-first
 * `[1, 6, anchors]` LiteRT output (class-1 channel dropped). Pure; unit-tested.
 */
internal fun panelChannelFirstRow(output: FloatArray, anchor: Int, anchors: Int): FloatArray = floatArrayOf(
    output[anchor],
    output[anchors + anchor],
    output[2 * anchors + anchor],
    output[3 * anchors + anchor],
    output[4 * anchors + anchor],
)
