package eu.kanade.translation.ocr

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
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * TachiyomiAT: PP-OCRv6 small **detection** ONNX engine.
 *
 * Runs the DB (Differentiable Binarization) text-line detector on a single
 * bitmap crop and returns the text-line boxes in **crop pixel coords**. The det
 * model is invoked inside [eu.kanade.translation.recognition.RoiPageRecognitionEngine]
 * on each Stage-1 detected ROI crop, replacing the ink-gap vertical-column
 * heuristic (`detectVerticalColumns`) for the PaddleOCR rec path.
 *
 * Single responsibility: **Bitmap → List<TextLine>**. All DB postprocess math
 * (threshold → connected components → raw axis-aligned bbox) lives in the pure,
 * unit-tested [DbPostProcess] helper — this class owns only the ONNX I/O glue
 * and the preprocess (resize/normalize) + back-projection that are
 * ONNX/Android-specific. The reference `unclip` expansion is intentionally NOT
 * applied: it collapses glyphs vertically in the rec head's 48px resize, so the
 * raw bbox is emitted directly.
 *
 * The preprocess/postprocess contract mirrors `inference.yml` of
 * `PaddlePaddle/PP-OCRv6_small_det_onnx`:
 *  - PreProcess: BGR, resize longer side to 736 keeping aspect ratio, pad to
 *    736x736, ImageNet mean/std normalize, CHW.
 *  - PostProcess: DBPostProcess (thresh=0.2, box_thresh=0.45, no unclip).
 *
 * Output shape (validated on-device via the Python prototype):
 * `[1, 1, 736, 736]` — a single probability (saliency) map. The Kotlin engine
 * reads the 2D map at index [0][0] after squeezing.
 *
 * Design: `docs/superpowers/specs/2026-06-23-paddleocr-v6-det-onnx-integration-design.md`.
 */
class PaddleOcrV6DetEngine : Closeable {

    private var session: OrtSession? = null
    private var inputName: String = "x"
    @Volatile
    private var closed: Boolean = false

    // TachiyomiAT: pooled DIRECT buffer for the det input tensor
    // (1 x 3 x TARGET x TARGET floats). FloatBuffer.wrap(...) (the previous
    // approach) is heap-backed, which forces ONNX Runtime to allocate an
    // internal NATIVE copy on every run() that lingers on the session's
    // internal heap and accumulates across the per-ROI det calls (ORT issue
    // #16937). A direct buffer is consumed in place with no native copy, so
    // nothing leaks. Mirrors MangaOcrEngine.inputPixelPool. det() is serialized
    // under the recognizer permit, so maxPoolSize = 2 (one live buffer) is
    // sufficient. Capacity = 3 * 736 * 736 floats * 4 bytes ~= 6.2 MiB.
    private val inputPixelPool = DirectBufferPool(3 * TARGET * TARGET * 4, maxPoolSize = 2)

    fun initialize(modelFile: File) {
        logcat(LogPriority.INFO) {
            "PaddleOCR v6 det init: model=${modelFile.absolutePath} " +
                "(${modelFile.length()}B exists=${modelFile.exists()})"
        }
        val opts = OnnxRuntimeProvider.createSessionOptions(forceCpu = true)
        try {
            session = OnnxRuntimeProvider.environment.createSession(modelFile.absolutePath, opts)
            inputName = session?.inputNames?.firstOrNull() ?: "x"
            logcat(LogPriority.INFO) {
                "PaddleOCR v6 det loaded (inputs=${session?.inputNames}, outputs=${session?.outputNames})"
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "PaddleOCR v6 det session init failed" }
            throw e
        } finally {
            opts.close()
        }
    }

    /**
     * Detect text lines in [crop]. Returns boxes in **crop pixel coords**
     * (already back-projected from the model's 736x736 map space). Safe to call
     * concurrently with [close] only under the caller's native guard — see
     * [RoiPageRecognitionEngine].
     *
     * [thresh] / [boxThresh] are the DB postprocess thresholds forwarded to
     * [DbPostProcess.detectLines]. They default to [DbPostProcess.Defaults]
     * (matching `PP-OCRv6_small_det_onnx` `inference.yml`), which is the right
     * setting for the **OCR-rec** path. The **inpaint-mask** path passes the
     * prototype-validated lower thresholds (0.18 / 0.34) — see
     * `tools/inpaint-debug-viewer/server.py` — so Paddle finds the same text
     * lines for erasing that it finds for recognition.
     */
    fun detectLines(
        crop: Bitmap,
        thresh: Float = DbPostProcess.Defaults.THRESH,
        boxThresh: Float = DbPostProcess.Defaults.BOX_THRESH,
    ): List<TextLine> {
        val localSession = session ?: throw IllegalStateException("PaddleOCR v6 det not initialized")
        val w = crop.width
        val h = crop.height
        if (w <= 0 || h <= 0) return emptyList()

        val t0 = System.nanoTime()
        var pixelBuffer: FloatBuffer? = null
        var inputTensor: OnnxTensor? = null
        var result: OrtSession.Result? = null
        try {
            // TachiyomiAT: preprocess writes the NCHW tensor straight into a
            // pooled DIRECT buffer. The previous FloatBuffer.wrap(tensor) was
            // heap-backed, forcing ORT to allocate a per-call native copy that
            // leaked across the per-ROI det calls (ORT issue #16937).
            pixelBuffer = inputPixelPool.acquire()
            pixelBuffer.clear()
            val pre = preprocess(crop, pixelBuffer)
            pixelBuffer.flip()
            inputTensor = OnnxTensor.createTensor(
                OnnxRuntimeProvider.environment,
                pixelBuffer,
                longArrayOf(1, 3, TARGET.toLong(), TARGET.toLong()),
            )
            result = localSession.run(mapOf(inputName to inputTensor))
            // Output is [1, 1, TARGET, TARGET]; read as a flat TARGET*TARGET prob map.
            val out = result[0] as OnnxTensor
            val shape = out.info.shape
            val mapW = shape[shape.size - 1].toInt().coerceAtLeast(1)
            val mapH = shape[shape.size - 2].toInt().coerceAtLeast(1)
            val prob = FloatArray(mapW * mapH)
            out.floatBuffer.get(prob)
            val t1 = System.nanoTime()
            // Postprocess: only the [:resizedH, :resizedW] region holds real text
            // (the rest is zero-pad). Crop the active sub-map before DB postprocess
            // so padded zeros don't generate spurious low-score components.
            val active = activeRegion(prob, mapW, mapH, pre.resizedW, pre.resizedH)
            // Back-project scale: map-space -> crop-space = 1 / (crop->map scale).
            val mapToCropX = if (pre.cropToMapX > 0f) 1f / pre.cropToMapX else 0f
            val mapToCropY = if (pre.cropToMapY > 0f) 1f / pre.cropToMapY else 0f
            val mapLines = DbPostProcess.detectLines(
                probMap = active.pixels,
                width = active.width,
                height = active.height,
                thresh = thresh,
                boxThresh = boxThresh,
            )
            // Back-project each map-space bbox to crop pixel coords.
            val cropLines = ArrayList<TextLine>(mapLines.size)
            for (ml in mapLines) {
                val cb = DbPostProcess.backProject(
                    bbox = ml.bbox,
                    scaleX = mapToCropX,
                    scaleY = mapToCropY,
                    cropW = w,
                    cropH = h,
                )
                if (cb[2] > cb[0] && cb[3] > cb[1]) {
                    cropLines.add(TextLine(bbox = cb, meanScore = ml.meanScore))
                }
            }
            if (isDiagnosticsEnabled()) {
                val t2 = System.nanoTime()
                logcat(LogPriority.INFO) {
                    "[paddle_det] total=${(t2 - t0) / 1_000_000.0}ms " +
                        "infer=${(t1 - t0) / 1_000_000.0}ms " +
                        "crop=${w}x${h} map=${mapW}x${mapH} active=${active.width}x${active.height} " +
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

    override fun close() {
        closed = true
        session?.close()
        session = null
        inputPixelPool.clear()
    }

    /**
     * No persistent off-heap state to reclaim by default (the input buffer is
     * returned to [inputPixelPool] in the detectLines finally block). Implementing
     * the contract anyway so [RoiPageRecognitionEngine] can forward uniformly to
     * all sub-engines.
     */
    fun reclaimPooledMemory() {}

    fun forceReleaseNativeBuffers() {
        inputPixelPool.clear()
    }

    /**
     * Preprocess the crop to a [TARGET]x[TARGET] NCHW float tensor, written
     * directly into [out] (a pooled direct buffer). Returns the crop->map scale
     * factors and the resized (pre-pad) dimensions so [detectLines] can
     * back-project and crop the active map region.
     */
    private fun preprocess(crop: Bitmap, out: FloatBuffer): Preprocessed {
        val w = crop.width
        val h = crop.height
        val scale = TARGET.toFloat() / max(w, h).toFloat()
        val resizedW = (w * scale).roundToInt().coerceIn(1, TARGET)
        val resizedH = (h * scale).roundToInt().coerceIn(1, TARGET)

        var resized: Bitmap? = null
        var padded: Bitmap? = null
        try {
            resized = BitmapPool.getARGB8888(resizedW, resizedH)
            Canvas(resized).drawBitmap(
                crop,
                null,
                RectF(0f, 0f, resizedW.toFloat(), resizedH.toFloat()),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            padded = BitmapPool.getARGB8888(TARGET, TARGET)
            // Pad with black (0) — matches PaddleOCR DetResizeForTest. The pad
            // region is excluded from DB postprocess via [activeRegion], so the
            // pad color itself does not affect detection.
            padded.eraseColor(0xFF000000.toInt())
            Canvas(padded).drawBitmap(resized, 0f, 0f, null)

            val pixels = IntArray(TARGET * TARGET)
            padded.getPixels(pixels, 0, TARGET, 0, 0, TARGET, TARGET)
            // NCHW, RGB plane order (matches the exported det model's training
            // pipeline). Written directly into the pooled direct buffer — no
            // intermediate FloatArray allocations. One pixel scan per plane.
            val total = TARGET * TARGET
            for (i in 0 until total) out.put(normalizeR(pixels[i] shr 16 and 0xFF))
            for (i in 0 until total) out.put(normalizeG(pixels[i] shr 8 and 0xFF))
            for (i in 0 until total) out.put(normalizeB(pixels[i] and 0xFF))
            return Preprocessed(
                cropToMapX = resizedW.toFloat() / w.toFloat(),
                cropToMapY = resizedH.toFloat() / h.toFloat(),
                resizedW = resizedW,
                resizedH = resizedH,
            )
        } finally {
            if (padded != null) BitmapPool.putARGB8888(padded)
            if (resized != null) BitmapPool.putARGB8888(resized)
        }
    }

    /**
     * ImageNet normalize per channel — same constants as PP-OCRv6 det
     * `inference.yml` NormalizeImage (mean/std), applied in BGR plane order.
     */
    private fun normalizeB(v: Int) = (v / 255f - MEAN_B) / STD_B
    private fun normalizeG(v: Int) = (v / 255f - MEAN_G) / STD_G
    private fun normalizeR(v: Int) = (v / 255f - MEAN_R) / STD_R

    /**
     * Extract the active (pre-pad) region from the flat prob map. The padded
     * border is zero by construction; restricting DB postprocess to the active
     * sub-map avoids spurious low-score components at the pad boundary.
     */
    private fun activeRegion(
        prob: FloatArray,
        mapW: Int,
        mapH: Int,
        resizedW: Int,
        resizedH: Int,
    ): ActiveRegion {
        val aw = min(resizedW, mapW)
        val ah = min(resizedH, mapH)
        if (aw == mapW && ah == mapH) {
            return ActiveRegion(prob, aw, ah)
        }
        val out = FloatArray(aw * ah)
        for (y in 0 until ah) {
            val srcRow = y * mapW
            val dstRow = y * aw
            System.arraycopy(prob, srcRow, out, dstRow, aw)
        }
        return ActiveRegion(out, aw, ah)
    }

    private data class Preprocessed(
        val cropToMapX: Float,
        val cropToMapY: Float,
        val resizedW: Int,
        val resizedH: Int,
    )

    private data class ActiveRegion(val pixels: FloatArray, val width: Int, val height: Int)

    private companion object {
        // Det model operates on a TARGET x TARGET map (validated via the Python
        // prototype: output shape [1,1,736,736] for a 736 input).
        private const val TARGET = 736

        // ImageNet normalization (BGR) — from PP-OCRv6 det inference.yml.
        private const val MEAN_B = 0.406f
        private const val MEAN_G = 0.456f
        private const val MEAN_R = 0.485f
        private const val STD_B = 0.225f
        private const val STD_G = 0.224f
        private const val STD_R = 0.229f

        @Volatile
        private var diagnosticsInitialized = false
        @Volatile
        private var diagnosticsEnabled = false

        private fun isDiagnosticsEnabled(): Boolean {
            if (diagnosticsInitialized) return diagnosticsEnabled
            diagnosticsEnabled = try {
                Injekt.get<TranslationPreferences>().translationDiagnostics().get()
            } catch (_: Throwable) {
                false
            }
            diagnosticsInitialized = true
            return diagnosticsEnabled
        }
    }
}
