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
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.domain.translation.pools.BitmapPool
import tachiyomi.domain.translation.pools.DirectBufferPool
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class PaddleOcrV6SmallEngine : RoiOcrEngine {

    // TachiyomiAT: PP-OCRv6's recognition model is a CNN+CTC head trained on
    // horizontal text lines (left-to-right). It cannot read vertical columns
    // directly, so RoiPageRecognitionEngine rotates tall (vertical) crops 90°
    // for THIS engine only — native-vertical engines (ML Kit, MangaOcr) opt out
    // via prefersHorizontalText=false and get the crop unrotated.
    override val prefersHorizontalText: Boolean = true

    private var session: OrtSession? = null
    private var dictionary: List<String> = emptyList()
    private var inputName: String = "x"

    // TachiyomiAT: pooled DIRECT buffer for the rec input tensor. The rec input
    // width varies per crop (up to MAX_RECOGNITION_WIDTH), so the pool is sized
    // for the maximum shape; each call exposes only [width x height x 3] floats
    // to the tensor via the buffer's limit. The previous
    // FloatBuffer.wrap(preprocessed.pixels) was heap-backed, forcing ONNX Runtime
    // to allocate an internal native copy on every recognize() call that
    // accumulated across the per-text-line rec calls (ORT issue #16937) — the
    // same leak class MangaOcrEngine.inputPixelPool fixes. A direct buffer is
    // consumed in place, so nothing leaks. recognize() is serialized under the
    // translator permit, so maxPoolSize = 2 (one live buffer) suffices.
    // Capacity = 3 * 48 * 1600 floats * 4 bytes ~= 0.9 MiB.
    private val inputPixelPool = DirectBufferPool(
        3 * RECOGNITION_HEIGHT * MAX_RECOGNITION_WIDTH * 4,
        maxPoolSize = 2,
    )

    fun initialize(modelFile: File, dictionaryFile: File) {
        logcat(LogPriority.INFO) {
            "PaddleOCR v6 small init: model=${modelFile.absolutePath} (${modelFile.length()}B exists=${modelFile.exists()}), " +
                "dictionary=${dictionaryFile.absolutePath} (${dictionaryFile.length()}B exists=${dictionaryFile.exists()})"
        }
        dictionary = BufferedReader(InputStreamReader(dictionaryFile.inputStream(), Charsets.UTF_8)).use { reader ->
            reader.lineSequence().map { it.trimEnd() }.toList()
        }
        val opts = OnnxRuntimeProvider.createSessionOptions()
        try {
            session = OnnxRuntimeProvider.environment.createSession(modelFile.absolutePath, opts)
            inputName = session?.inputNames?.firstOrNull() ?: "x"
            logcat(LogPriority.INFO) {
                "PaddleOCR v6 small loaded (dictionary=${dictionary.size}, inputs=${session?.inputNames}, outputs=${session?.outputNames})"
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "PaddleOCR v6 small session init failed" }
            throw e
        } finally {
            opts.close()
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
            // TachiyomiAT: preprocess writes the NCHW tensor straight into a
            // pooled DIRECT buffer; only [width x height x 3] floats are exposed
            // to the tensor via the buffer limit (the rec width varies per crop).
            // The previous FloatBuffer.wrap(preprocessed.pixels) was heap-backed,
            // forcing ORT to allocate a per-call native copy that leaked across
            // the per-text-line recognize() calls (ORT issue #16937).
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
                        "chars=${text.length} text=\"$text\""
                }
            }
            return text to conf
        } finally {
            inputTensor?.close()
            result?.close()
            pixelBuffer?.let { inputPixelPool.release(it) }
        }
    }

    override fun close() {
        session?.close()
        session = null
        dictionary = emptyList()
        inputPixelPool.clear()
    }

    override fun forceReleaseNativeBuffers() {
        inputPixelPool.clear()
    }

    private fun preprocess(crop: Bitmap, out: FloatBuffer): Int {
        val safeWidth = crop.width.coerceAtLeast(1)
        val safeHeight = crop.height.coerceAtLeast(1)
        // TachiyomiAT: match the reference PP-OCR pipeline (e.g. comic-translate's
        // ppocr module). Three corrections vs. the original implementation, all of
        // which improved horizontal manga text and are prerequisites for vertical
        // column splitting (handled by RoiPageRecognitionEngine):
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
            // NCHW, RGB plane order, written directly into the pooled direct
            // buffer (no intermediate FloatArray). The rec input width varies per
            // crop; recognizeWithConf exposes only the filled region via the
            // buffer's limit. Absolute puts leave the buffer position untouched.
            val planeSize = RECOGNITION_HEIGHT * inputWidth
            for (y in 0 until RECOGNITION_HEIGHT) {
                for (x in 0 until inputWidth) {
                    val pixel = pixels[y * inputWidth + x]
                    val offset = y * inputWidth + x
                    out.put(offset, normalize(pixel shr 16 and 0xFF)) // R
                    out.put(planeSize + offset, normalize(pixel shr 8 and 0xFF)) // G
                    out.put(planeSize * 2 + offset, normalize(pixel and 0xFF)) // B
                }
            }
            return inputWidth
        } finally {
            if (padded != null) BitmapPool.putARGB8888(padded)
            if (resized != null) BitmapPool.putARGB8888(resized)
        }
    }

    private fun normalize(value: Int): Float {
        return (value / 255.0f - 0.5f) / 0.5f
    }

    private fun alignWidth(width: Int): Int {
        // Match the reference PP-OCR pipeline: pad every crop to AT LEAST the
        // model's native recognition width (320), rounded up to WIDTH_ALIGNMENT.
        // This is the key fix for vertical/rotated text — the original aligned to
        // only 16-32px, starving the model of per-character resolution. Cap at
        // MAX_RECOGNITION_WIDTH.
        val floored = maxOf(width, MIN_TARGET_WIDTH)
        val aligned = ((floored + WIDTH_ALIGNMENT - 1) / WIDTH_ALIGNMENT) * WIDTH_ALIGNMENT
        return aligned.coerceAtMost(MAX_RECOGNITION_WIDTH)
    }

    private companion object {
        private const val RECOGNITION_HEIGHT = 48
        // TachiyomiAT: PP-OCR's recognition model is trained on a (3, 48, 320)
        // input shape. Every crop is padded up to at least this width so the
        // model gets the per-character resolution it expects — padding to only
        // 16-32px starved it and caused empty/garbage output on vertical text.
        private const val MIN_TARGET_WIDTH = 320
        private const val MAX_RECOGNITION_WIDTH = 1600
        private const val WIDTH_ALIGNMENT = 16
        // Gray that normalizes to 0.0 (the normalization mean) — used for the
        // right-side padding instead of white, matching the reference pipeline.
        private const val PAD_GRAY = 0xFF808080.toInt()

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
