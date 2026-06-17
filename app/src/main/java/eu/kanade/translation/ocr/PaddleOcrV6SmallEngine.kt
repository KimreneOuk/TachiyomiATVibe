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

    private var session: OrtSession? = null
    private var dictionary: List<String> = emptyList()
    private var inputName: String = "x"

    fun initialize(modelFile: File, dictionaryFile: File) {
        logcat(LogPriority.INFO) {
            "PaddleOCR v6 small init: model=${modelFile.absolutePath} (${modelFile.length()}B exists=${modelFile.exists()}), " +
                "dictionary=${dictionaryFile.absolutePath} (${dictionaryFile.length()}B exists=${dictionaryFile.exists()})"
        }
        dictionary = BufferedReader(InputStreamReader(dictionaryFile.inputStream(), Charsets.UTF_8)).use { reader ->
            reader.lineSequence().map { it.trimEnd() }.toList()
        }
        val opts = OnnxRuntimeProvider.createSessionOptions(forceCpu = true)
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

    override suspend fun recognize(crop: Bitmap): String {
        val localSession = session ?: throw IllegalStateException("PaddleOCR v6 small is not initialized")
        val preprocessed = preprocess(crop)
        var inputTensor: OnnxTensor? = null
        var result: OrtSession.Result? = null
        val start = System.nanoTime()
        try {
            inputTensor = OnnxTensor.createTensor(
                OnnxRuntimeProvider.environment,
                FloatBuffer.wrap(preprocessed.pixels),
                longArrayOf(1, 3, RECOGNITION_HEIGHT.toLong(), preprocessed.width.toLong()),
            )
            val inputTensorValue = inputTensor!!
            result = localSession.run(mapOf(inputName to inputTensorValue))
            val output = result[0] as OnnxTensor
            val shape = output.info.shape
            val timeSteps = shape[1].toInt()
            val classCount = shape[2].toInt()
            val indices = PaddleCtcDecoder.argmaxIndices(output.floatBuffer, timeSteps, classCount)
            val text = PaddleCtcDecoder.decode(indices, dictionary)
            if (isDiagnosticsEnabled()) {
                logcat(LogPriority.INFO) {
                    "[paddle_ocr] total=${(System.nanoTime() - start) / 1_000_000.0}ms " +
                        "crop=${crop.width}x${crop.height} input=${preprocessed.width}x$RECOGNITION_HEIGHT " +
                        "chars=${text.length} text=\"$text\""
                }
            }
            return text
        } finally {
            inputTensor?.close()
            result?.close()
        }
    }

    override fun close() {
        session?.close()
        session = null
        dictionary = emptyList()
    }

    private fun preprocess(crop: Bitmap): PreprocessedInput {
        val safeWidth = crop.width.coerceAtLeast(1)
        val safeHeight = crop.height.coerceAtLeast(1)
        // TachiyomiAT: match the reference PP-OCR pipeline (e.g. comic-translate's
        // ppocr module). Three corrections vs. the original implementation, all of
        // which are needed for vertical manga text to be recognized:
        //
        // 1. PAD TO A MINIMUM WIDTH OF 320 (the model's training shape), not to a
        //    tiny 16/32px alignment boundary. The reference uses rec_img_shape
        //    (3, 48, 320) and pads every crop up to at least 320 wide. Padding to
        //    only 16-32px starved the model of pixels per character, which is why
        //    every narrow/rotated crop returned "" or single junk chars.
        //
        // 2. PAD WITH THE NORMALIZATION MEAN (gray 128 -> normalized 0.0), NOT
        //    white. The reference fills the pad region with np.zeros AFTER
        //    normalizing (i.e. the mean). White (255 -> normalized 1.0) biases
        //    the CTC decoder.
        //
        // 3. Scale the resized width PROPORTIONALLY to the fixed 48px height
        //    (aspect-ratio preserving) and let padding fill the rest — the
        //    original `.coerceIn(48, MAX)` destroyed aspect ratio.
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
            val result = FloatArray(3 * RECOGNITION_HEIGHT * inputWidth)
            val planeSize = RECOGNITION_HEIGHT * inputWidth
            for (y in 0 until RECOGNITION_HEIGHT) {
                for (x in 0 until inputWidth) {
                    val pixel = pixels[y * inputWidth + x]
                    val offset = y * inputWidth + x
                    result[offset] = normalize(pixel shr 16 and 0xFF)
                    result[planeSize + offset] = normalize(pixel shr 8 and 0xFF)
                    result[planeSize * 2 + offset] = normalize(pixel and 0xFF)
                }
            }
            return PreprocessedInput(result, inputWidth)
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

    private data class PreprocessedInput(
        val pixels: FloatArray,
        val width: Int,
    )

    private companion object {
        private const val RECOGNITION_HEIGHT = 48
        // TachiyomiAT: PP-OCR's recognition model is trained on a (3, 48, 320)
        // input shape. Every crop is padded up to at least this width so the
        // model gets the per-character resolution it expects — padding to only
        // 16-32px starved it and caused empty/garbage output on vertical text.
        private const val MIN_TARGET_WIDTH = 320
        private const val MAX_RECOGNITION_WIDTH = 960
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
