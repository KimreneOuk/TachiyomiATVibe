package eu.kanade.translation.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
        // TachiyomiAT: scale the width PROPORTIONALLY to the fixed 48px recognition
        // height, preserving the crop's aspect ratio. PaddleOCR's rec model is
        // aspect-ratio sensitive: feeding a tall/narrow manga bubble stretched into
        // a square produced garbage ("??", "") for every non-wide crop. The previous
        // `.coerceIn(MIN_RECOGNITION_WIDTH, MAX)` here clamped narrow text UP to 48px
        // wide, destroying the aspect ratio — e.g. a 383x719 bubble (0.53:1) became
        // 48x48 (1:1). Only the upper bound is correct (the model input is capped);
        // the lower bound is enforced by padding (below), NOT by distorting the image.
        val scaledWidth = ceil(safeWidth * (RECOGNITION_HEIGHT.toFloat() / safeHeight)).toInt()
            .coerceAtMost(MAX_RECOGNITION_WIDTH)
            .coerceAtLeast(1)
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
            padded.eraseColor(Color.WHITE)
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
        // Round the proportional width UP to the WIDTH_ALIGNMENT boundary so the
        // padded bitmap can hold the (aspect-correct) resized crop plus white
        // padding on the right. Cap at MAX_RECOGNITION_WIDTH. The minimum width
        // is the alignment unit itself (16) — the existing white padding
        // (padded.eraseColor(WHITE)) fills the remainder, so narrow text keeps its
        // aspect ratio instead of being stretched into a square.
        val aligned = ((width + WIDTH_ALIGNMENT - 1) / WIDTH_ALIGNMENT) * WIDTH_ALIGNMENT
        return aligned.coerceIn(WIDTH_ALIGNMENT, MAX_RECOGNITION_WIDTH)
    }

    private data class PreprocessedInput(
        val pixels: FloatArray,
        val width: Int,
    )

    private companion object {
        private const val RECOGNITION_HEIGHT = 48
        private const val MAX_RECOGNITION_WIDTH = 960
        private const val WIDTH_ALIGNMENT = 16

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
