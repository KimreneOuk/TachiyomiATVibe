package eu.kanade.translation.diagnostics

import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.translation.engines.runtime.onnx.OnnxModelStore
import eu.kanade.translation.engines.vision.ocr.PaddleOcrV6DetEngine
import eu.kanade.translation.engines.vision.ocr.PaddleOcrV6RecTiming
import eu.kanade.translation.engines.vision.ocr.PaddleOcrV6SmallEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class PaddleOcrLiteRtOnDeviceTest {

    @Test
    fun benchmarkPackagedLiteRtRecognizerBucketsAndDetector() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val launchIntent = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)) {
            "Target app has no launch activity for the on-device OCR benchmark"
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.startActivitySync(launchIntent)

        val modelStore = OnnxModelStore(context)
        val paths = modelStore.ensurePaddleOcrV6LiteRt()
        val recEngine = PaddleOcrV6SmallEngine()
        val detEngine = PaddleOcrV6DetEngine()
        val providerMismatches = mutableListOf<String>()

        try {
            recEngine.initializeLiteRt(
                paths.recognitionModelsByWidth,
                paths.dictionary,
                preferGpu = true,
                allowXnnpackFallback = true,
                numThreads = 4,
            )

            RECOGNITION_BUCKETS.forEach { bucket ->
                val bitmap = syntheticTextBitmap(bucket, RECOGNITION_HEIGHT)
                try {
                    repeat(WARMUP_RUNS) {
                        val (text, confidence) = recEngine.recognizeWithConf(bitmap)
                        assertNotNull("Recognizer must return a text result for bucket $bucket", text)
                        assertTrue("Confidence must be finite for bucket $bucket", confidence.isFinite())
                    }
                    recordProviderMismatch(
                        providerMismatches,
                        "recognizer bucket $bucket",
                        recEngine.executionProviderLabel,
                    )

                    val samples = ArrayList<PaddleOcrV6RecTiming>(MEASURED_RUNS)
                    repeat(MEASURED_RUNS) {
                        val (text, confidence) = recEngine.recognizeWithConf(bitmap)
                        assertNotNull("Recognizer must return a text result for bucket $bucket", text)
                        assertTrue("Confidence must be finite for bucket $bucket", confidence.isFinite())
                        samples += checkNotNull(recEngine.lastLiteRtTiming) {
                            "LiteRT timing was not recorded for bucket $bucket"
                        }
                    }

                    val provider = recEngine.executionProviderLabel
                    recordProviderMismatch(providerMismatches, "recognizer bucket $bucket", provider)
                    emit(
                        "PADDLE_REC_BENCH: bucket=$bucket, forwardMs=${mean(samples.map { it.neuralForwardMs })}, " +
                            "totalMs=${mean(samples.map { it.totalMs })}, " +
                            "minForwardMs=${minimum(samples.map { it.neuralForwardMs })}, " +
                            "minTotalMs=${minimum(samples.map { it.totalMs })}, provider=$provider",
                    )
                } finally {
                    bitmap.recycle()
                }
            }

            detEngine.initializeLiteRt(
                paths.detectorModel,
                preferGpu = true,
                allowXnnpackFallback = true,
                numThreads = 4,
            )
            val detectorBitmap = syntheticTextBitmap(DETECTOR_SIZE, DETECTOR_SIZE)
            try {
                repeat(WARMUP_RUNS) {
                    assertNotNull("Detector must complete warm-up", detEngine.detectLines(detectorBitmap))
                }

                val samples = ArrayList<Pair<Double, Double>>(MEASURED_RUNS)
                repeat(MEASURED_RUNS) {
                    val lines = detEngine.detectLines(detectorBitmap)
                    assertNotNull("Detector must return a line list", lines)
                    val timing = checkNotNull(detEngine.lastTiming) { "Detector timing was not recorded" }
                    samples += timing.neuralForwardMs to timing.totalMs
                }
                recordProviderMismatch(
                    providerMismatches,
                    "detector",
                    detEngine.executionProviderLabel,
                )
                emit(
                    "PADDLE_DET_BENCH: forwardMs=${mean(samples.map { it.first })}, " +
                        "totalMs=${mean(samples.map { it.second })}, " +
                        "minForwardMs=${minimum(samples.map { it.first })}, " +
                        "minTotalMs=${minimum(samples.map { it.second })}, " +
                        "provider=${detEngine.executionProviderLabel}",
                )
            } finally {
                detectorBitmap.recycle()
            }
            assertTrue(
                "Expected litert_gpu_delegate for all models; ${providerMismatches.joinToString()}",
                providerMismatches.isEmpty(),
            )
        } finally {
            recEngine.close()
            detEngine.close()
        }
    }

    private fun syntheticTextBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height) { WHITE }
        val lineCount = if (height == RECOGNITION_HEIGHT) 2 else 8
        val glyphHeight = if (height == RECOGNITION_HEIGHT) 14 else 26
        val topMargin = if (height == RECOGNITION_HEIGHT) 5 else 70
        val lineGap = if (height == RECOGNITION_HEIGHT) 19 else 92
        val sideMargin = if (width < DETECTOR_SIZE) 8 else 80

        repeat(lineCount) { line ->
            val top = topMargin + line * lineGap
            var left = sideMargin + (line % 2) * 7
            var glyphIndex = 0
            while (left < width - sideMargin) {
                val glyphWidth = 5 + (glyphIndex * 7 + line * 3) % if (width < DETECTOR_SIZE) 8 else 15
                val right = (left + glyphWidth).coerceAtMost(width - sideMargin)
                val bottom = (top + glyphHeight - (glyphIndex % 3)).coerceAtMost(height)
                for (y in top.coerceAtLeast(0) until bottom) {
                    for (x in left.coerceAtLeast(0) until right) {
                        pixels[y * width + x] = INK
                    }
                }
                left = right + 4 + glyphIndex % 5
                glyphIndex++
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun emit(message: String) {
        println(message)
        Log.i(TAG, message)
    }

    private fun recordProviderMismatch(mismatches: MutableList<String>, label: String, provider: String) {
        if (provider != "litert_gpu_delegate") {
            mismatches += "$label used $provider"
        }
    }

    private fun mean(values: List<Double>): String =
        String.format(Locale.US, "%.3f", values.average())

    private fun minimum(values: List<Double>): String =
        String.format(Locale.US, "%.3f", values.minOrNull() ?: 0.0)

    private companion object {
        const val TAG = "PaddleOcrLiteRtBench"
        const val RECOGNITION_HEIGHT = 48
        const val DETECTOR_SIZE = 960
        const val WARMUP_RUNS = 3
        const val MEASURED_RUNS = 5
        val RECOGNITION_BUCKETS = listOf(160, 320, 480, 640, 1600)
        const val WHITE = 0xFFFFFFFF.toInt()
        const val INK = 0xFF202020.toInt()
    }
}
