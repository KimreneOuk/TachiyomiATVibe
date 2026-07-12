package eu.kanade.translation.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.Paint
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import eu.kanade.translation.runtime.onnx.OnnxRuntimeProvider
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.max
import tachiyomi.domain.translation.pools.BitmapPool
import tachiyomi.domain.translation.pools.DirectBufferPool
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MangaOcrEngine : RoiOcrEngine {

    private var encoderSession: OrtSession? = null
    private var decoderInitSession: OrtSession? = null
    private var decoderStepSession: OrtSession? = null
    private var vocab: List<String> = emptyList()
    private var loggedTensorShapes = false

    private val decoderThreadCount = maxOf(1, minOf(Runtime.getRuntime().availableProcessors() / 2, 2))
    private val kCachePool = DirectBufferPool(4 * 1 * 4 * MAX_LEN * 64 * 4, maxPoolSize = 2)
    private val vCachePool = DirectBufferPool(4 * 1 * 4 * MAX_LEN * 64 * 4, maxPoolSize = 2)
    // TachiyomiAT: pooled DIRECT buffer for the encoder input. FloatBuffer.wrap
    // is heap-backed, forcing ORT to allocate a native copy per call that leaks
    // across recognize() calls (ORT #16937); a direct buffer is used in place.
    private val inputPixelPool = DirectBufferPool(3 * 224 * 224 * 4, maxPoolSize = 2)

    fun initialize(
        encoderFile: File,
        decoderInitFile: File,
        decoderStepFile: File,
        vocabFile: File,
    ) {
        logcat(LogPriority.INFO) {
            "OCR init: encoder=${encoderFile.absolutePath} (${encoderFile.length()}B exists=${encoderFile.exists()}), " +
                "decoderInit=${decoderInitFile.absolutePath} (${decoderInitFile.length()}B exists=${decoderInitFile.exists()}), " +
                "decoderStep=${decoderStepFile.absolutePath} (${decoderStepFile.length()}B exists=${decoderStepFile.exists()}), " +
                "vocab=${vocabFile.absolutePath} (${vocabFile.length()}B exists=${vocabFile.exists()})"
        }
        // TachiyomiAT: manga-ocr runs an autoregressive decoder loop on many
        // tiny per-step graphlets. Keep it on the shared CPU-only ONNX runtime.
        val encoderOpts = OnnxRuntimeProvider.createSessionOptions(useAccelerator = false)
        try {
            encoderSession = OnnxRuntimeProvider.environment.createSession(encoderFile.absolutePath, encoderOpts)
            logcat(LogPriority.INFO) { "OCR init: encoder session created OK, inputs=${encoderSession?.inputNames}" }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "OCR init: encoder session FAILED for ${encoderFile.absolutePath}" }
            throw e
        } finally {
            encoderOpts.close()
        }

        val decoderOpts = OnnxRuntimeProvider.createSessionOptions() { opts ->
            opts.setIntraOpNumThreads(decoderThreadCount)
        }
        try {
            decoderInitSession = OnnxRuntimeProvider.environment.createSession(decoderInitFile.absolutePath, decoderOpts)
            logcat(LogPriority.INFO) { "OCR init: decoder_init session created OK" }
            decoderStepSession = OnnxRuntimeProvider.environment.createSession(decoderStepFile.absolutePath, decoderOpts)
            logcat(LogPriority.INFO) { "OCR init: decoder_step session created OK" }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "OCR init: decoder session FAILED" }
            throw e
        } finally {
            decoderOpts.close()
        }

        vocab = BufferedReader(InputStreamReader(vocabFile.inputStream(), Charsets.UTF_8)).use { reader ->
            reader.lineSequence().map { it.trimEnd() }.toList()
        }

        logcat(LogPriority.INFO) {
            "OCR engine loaded (vocab=${vocab.size} tokens, " +
                "encoderInputs=${encoderSession?.inputNames}, encoderOutputs=${encoderSession?.outputNames}, " +
                "decoderInitInputs=${decoderInitSession?.inputNames}, decoderInitOutputs=${decoderInitSession?.outputNames}, " +
                "decoderStepInputs=${decoderStepSession?.inputNames}, decoderStepOutputs=${decoderStepSession?.outputNames})"
        }
    }

    override suspend fun recognize(cropBitmap: Bitmap): String {
        val enc = encoderSession ?: throw IllegalStateException("OCR not initialized")
        val decInit = decoderInitSession ?: throw IllegalStateException("OCR not initialized")
        val decStep = decoderStepSession ?: throw IllegalStateException("OCR not initialized")

        val t0 = System.nanoTime()
        val pixels = preprocess(cropBitmap)

        if (pixels.isEmpty()) return ""

        val t1 = System.nanoTime()

        var inputTensor: OnnxTensor? = null
        var encResult: OrtSession.Result? = null
        var startIdsTensor: OnnxTensor? = null
        var initResult: OrtSession.Result? = null

        var selfKCacheTensor: OnnxTensor? = null
        var selfVCacheTensor: OnnxTensor? = null
        var stepInputIdsTensor: OnnxTensor? = null
        var stepPositionIdsTensor: OnnxTensor? = null
        var selfKCacheBuf: java.nio.FloatBuffer? = null
        var selfVCacheBuf: java.nio.FloatBuffer? = null
        // TachiyomiAT: declared nullable outside try and assigned inside, so the
        // pool acquire/release stays balanced even if a later line throws.
        var pixelBuffer: java.nio.FloatBuffer? = null

        try {
            // TachiyomiAT: pooled DIRECT buffer avoids the per-call native copy
            // (see inputPixelPool). The one-time 600 KiB memcpy is far cheaper
            // than the leak.
            pixelBuffer = inputPixelPool.acquire().apply {
                clear()
                writeNormalizedChw(pixels, this)
                limit(3 * 224 * 224)
                position(0)
            }
            inputTensor = OnnxTensor.createTensor(
                OnnxRuntimeProvider.environment,
                pixelBuffer,
                longArrayOf(1, 3, 224, 224),
            )
            val inputTensorValue = inputTensor!!
            encResult = enc.run(mapOf(enc.inputNames.iterator().next() to inputTensorValue))
            val encHiddenTensor = encResult[0] as OnnxTensor

            val t2 = System.nanoTime()

            val startIdsBuf = java.nio.ByteBuffer.allocateDirect(8)
                .order(java.nio.ByteOrder.nativeOrder())
                .asLongBuffer()
            startIdsBuf.put(0, START_TOKEN.toLong())
            startIdsTensor = OnnxTensor.createTensor(
                OnnxRuntimeProvider.environment,
                startIdsBuf,
                longArrayOf(1, 1),
            )

            val initInputs = mapOf(
                "encoder_hidden_states" to encHiddenTensor,
                "input_ids" to startIdsTensor!!,
            )
            initResult = decInit.run(initInputs)

            val crossK = initResult[3] as OnnxTensor
            val crossV = initResult[4] as OnnxTensor

            val t3 = System.nanoTime()

            val cacheSize = 4L * 1 * 4 * MAX_LEN * 64
            selfKCacheBuf = kCachePool.acquire()
            selfVCacheBuf = vCachePool.acquire()

            val selfKInit = initResult[1] as OnnxTensor
            val selfVInit = initResult[2] as OnnxTensor
            copyInitToCache(selfKInit, selfKCacheBuf!!)
            copyInitToCache(selfVInit, selfVCacheBuf!!)

            if (!loggedTensorShapes) {
                loggedTensorShapes = true
                logcat(LogPriority.INFO) {
                    "OCR tensor shapes: encHidden=${encHiddenTensor.info.shape.contentToString()}, " +
                        "selfKInit=${selfKInit.info.shape.contentToString()}, " +
                        "selfVInit=${selfVInit.info.shape.contentToString()}, " +
                        "crossK=${crossK.info.shape.contentToString()}, crossV=${crossV.info.shape.contentToString()}"
                }
            }

            selfKCacheTensor = OnnxTensor.createTensor(
                OnnxRuntimeProvider.environment,
                selfKCacheBuf,
                longArrayOf(4, 1, 4, MAX_LEN.toLong(), 64),
            )
            selfVCacheTensor = OnnxTensor.createTensor(
                OnnxRuntimeProvider.environment,
                selfVCacheBuf,
                longArrayOf(4, 1, 4, MAX_LEN.toLong(), 64),
            )

            val stepInputIdsBuf = ByteBuffer.allocateDirect(8)
                .order(ByteOrder.nativeOrder())
                .asLongBuffer()
            val stepPositionIdsBuf = ByteBuffer.allocateDirect(8)
                .order(ByteOrder.nativeOrder())
                .asLongBuffer()

            stepInputIdsTensor = OnnxTensor.createTensor(
                OnnxRuntimeProvider.environment,
                stepInputIdsBuf,
                longArrayOf(1, 1),
            )
            stepPositionIdsTensor = OnnxTensor.createTensor(
                OnnxRuntimeProvider.environment,
                stepPositionIdsBuf,
                longArrayOf(1, 1),
            )

            val tokenIds = mutableListOf<Int>()
            var pos = 1
            var currentInputId = START_TOKEN.toLong()
            var currentPositionId = pos.toLong()

            val stepInputs = mapOf(
                "encoder_hidden_states" to encHiddenTensor,
                "input_ids" to stepInputIdsTensor!!,
                "position_ids" to stepPositionIdsTensor!!,
                "self_k_cache" to selfKCacheTensor!!,
                "self_v_cache" to selfVCacheTensor!!,
                "cross_k_cache" to crossK,
                "cross_v_cache" to crossV,
            )

            for (stepIdx in 0 until MAX_GENERATION_LENGTH) {
                // TachiyomiAT: bound pos by the 128-entry position-embedding
                // table, NOT MAX_LEN (256, the KV-cache dim). pos==128 overflows
                // node_embedding_1 -> native Gather throws -> chapter ERROR.
                if (pos >= DECODER_POSITION_COUNT) break

                stepInputIdsBuf.put(0, currentInputId)
                stepPositionIdsBuf.put(0, currentPositionId)

                var stepResult: OrtSession.Result? = null
                try {
                    stepResult = decStep.run(stepInputs)
                    val logitsTensor = stepResult[0] as OnnxTensor
                    val logitsBuf = logitsTensor.floatBuffer
                    val vocabSize = logitsBuf.remaining()
                    var maxVal = Float.NEGATIVE_INFINITY
                    var maxIdx = 0
                    for (vi in 0 until vocabSize) {
                        val v = logitsBuf.get(vi)
                        if (v > maxVal) {
                            maxVal = v
                            maxIdx = vi
                        }
                    }

                    writeCacheAtPos(stepResult[1] as OnnxTensor, selfKCacheBuf, pos)
                    writeCacheAtPos(stepResult[2] as OnnxTensor, selfVCacheBuf, pos)

                    if (maxIdx == END_TOKEN) {
                        break
                    }

                    tokenIds.add(maxIdx)
                    pos++
                    currentInputId = maxIdx.toLong()
                    currentPositionId = pos.toLong()
                } finally {
                    stepResult?.close()
                }
            }

            val t4 = System.nanoTime()

            val text = buildString {
                for (tokenId in tokenIds) {
                    if (tokenId < vocab.size) {
                        append(vocab[tokenId])
                    }
                }
            }
            val result = postprocess(text)
            val t5 = System.nanoTime()

            // Gate the per-ROI (30+/page) timing log behind the opt-in pref.
            if (isDiagnosticsEnabled()) {
                logcat(LogPriority.INFO) {
                    "[ocr] total=${(t5 - t0) / 1_000_000.0}ms " +
                        "preprocess=${(t1 - t0) / 1_000_000.0}ms " +
                        "encoder=${(t2 - t1) / 1_000_000.0}ms " +
                        "decoder_init=${(t3 - t2) / 1_000_000.0}ms " +
                        "decoder_steps=${(t4 - t3) / 1_000_000.0}ms " +
                        "postprocess=${(t5 - t4) / 1_000_000.0}ms " +
                        "tokens=${tokenIds.size}"
                }
            }
            return result
        } finally {
            inputTensor?.close()
            startIdsTensor?.close()
            selfKCacheTensor?.close()
            selfVCacheTensor?.close()
            stepInputIdsTensor?.close()
            stepPositionIdsTensor?.close()
            encResult?.close()
            initResult?.close()
            selfKCacheBuf?.let { kCachePool.release(it) }
            selfVCacheBuf?.let { vCachePool.release(it) }
            pixelBuffer?.let { inputPixelPool.release(it) }
        }
    }

    override fun reclaimPooledMemory() {
        // Direct buffers are already returned to pools in recognize finally block.
    }

    override fun forceReleaseNativeBuffers() {
        kCachePool.clear()
        vCachePool.clear()
        inputPixelPool.clear()
    }

    override fun close() {
        encoderSession?.close()
        decoderInitSession?.close()
        decoderStepSession?.close()
        encoderSession = null
        decoderInitSession = null
        decoderStepSession = null
        kCachePool.clear()
        vCachePool.clear()
        inputPixelPool.clear()
    }

    private fun preprocess(cropBitmap: Bitmap): IntArray {
        val w = cropBitmap.width
        val h = cropBitmap.height

        if (max(w, h) == 0) return IntArray(0)

        var grayBitmap: Bitmap? = null
        var resized: Bitmap? = null
        var padded: Bitmap? = null
        try {
            grayBitmap = BitmapPool.getARGB8888(w, h)
            val grayCanvas = Canvas(grayBitmap)
            val grayPaint = Paint().apply {
                colorFilter = android.graphics.ColorMatrixColorFilter(
                    ColorMatrix().apply { setSaturation(0f) },
                )
            }
            grayCanvas.drawBitmap(cropBitmap, 0f, 0f, grayPaint)

            val ratio = 224.0f / max(w, h)
            val newW = (w * ratio).toInt().coerceAtLeast(1)
            val newH = (h * ratio).toInt().coerceAtLeast(1)

            resized = BitmapPool.getARGB8888(newW, newH)
            val resizedCanvas = Canvas(resized)
            resizedCanvas.drawBitmap(grayBitmap, null, android.graphics.RectF(0f, 0f, newW.toFloat(), newH.toFloat()), null)

            padded = BitmapPool.getARGB8888(224, 224)
            padded.eraseColor(Color.WHITE)
            val padCanvas = Canvas(padded)
            padCanvas.drawBitmap(resized, ((224 - newW) / 2).toFloat(), ((224 - newH) / 2).toFloat(), null)

            val pixels = IntArray(224 * 224)
            padded.getPixels(pixels, 0, 224, 0, 0, 224, 224)

            return pixels
        } finally {
            if (padded != null) BitmapPool.putARGB8888(padded)
            if (resized != null) BitmapPool.putARGB8888(resized)
            if (grayBitmap != null) BitmapPool.putARGB8888(grayBitmap)
        }
    }

    private fun copyInitToCache(initTensor: OnnxTensor, dstBuf: FloatBuffer) {
        val srcBuf = initTensor.floatBuffer
        val srcShape = initTensor.info.shape
        val sliceSize = (srcShape[3].toInt()) * (srcShape[4].toInt())

        for (i in 0 until 4) {
            for (j in 0 until 1) {
                for (k in 0 until 4) {
                    val srcOffset = ((i * 1 + j) * 4 + k) * sliceSize
                    val dstOffset = ((i * 1 + j) * 4 + k) * MAX_LEN * 64
                    for (s in 0 until sliceSize) {
                        dstBuf.put(dstOffset + s, srcBuf.get(srcOffset + s))
                    }
                }
            }
        }
    }

    private fun writeCacheAtPos(updatedTensor: OnnxTensor, dstBuf: FloatBuffer, pos: Int) {
        val srcBuf = updatedTensor.floatBuffer
        val totalElements = dstBuf.capacity()
        val cacheShape4 = MAX_LEN * 64

        for (i in 0 until 4) {
            for (j in 0 until 1) {
                for (k in 0 until 4) {
                    val srcOffset = ((i * 1 + j) * 4 + k) * 64
                    val dstOffset = ((i * 1 + j) * 4 + k) * cacheShape4 + pos * 64
                    if (dstOffset + 64 <= totalElements) {
                        for (s in 0 until 64) {
                            dstBuf.put(dstOffset + s, srcBuf.get(srcOffset + s))
                        }
                    }
                }
            }
        }
    }

    private fun postprocess(text: String): String {
        var result = text.replace("\\s".toRegex(), "")
        result = result.replace("\u2026", "...")
        result = result.replace("[・.]{2,}".toRegex()) { match ->
            ".".repeat(match.value.length)
        }
        result = result.replace("([A-Za-z])0([A-Za-z])".toRegex(), "$1o$2")
        result = result.replace("N[0\u00b0\u00ba\u02da\u2070]".toRegex(), "")
        result = result.replace("\u2116", "")
        return result
    }

    companion object {
        private const val MAX_GENERATION_LENGTH = 300
        private const val START_TOKEN = 2
        private const val END_TOKEN = 3
        private const val MAX_LEN = 256
        // TachiyomiAT: real decode ceiling. The gpt2 position-embedding Gather
        // (node_embedding_1) has 128 entries; pos==128 overflows it and crashes
        // the chapter. MAX_LEN (256) is only the KV-cache dim, not a safe bound.
        private const val DECODER_POSITION_COUNT = 128

        internal fun writeNormalizedChw(sourcePixels: IntArray, destination: FloatBuffer) {
            val channelSize = 224 * 224
            require(sourcePixels.size == channelSize) {
                "MangaOCR preprocessing expected $channelSize pixels, got ${sourcePixels.size}"
            }
            require(destination.capacity() >= 3 * channelSize) {
                "MangaOCR input buffer is too small: ${destination.capacity()}"
            }
            for (c in 0 until 3) {
                for (i in sourcePixels.indices) {
                    val pixel = sourcePixels[i]
                    val channelValue = when (c) {
                        0 -> (pixel shr 16 and 0xFF) / 255.0f
                        1 -> (pixel shr 8 and 0xFF) / 255.0f
                        else -> (pixel and 0xFF) / 255.0f
                    }
                    destination.put(c * channelSize + i, (channelValue - 0.5f) / 0.5f)
                }
            }
        }

        /**
         * TachiyomiAT: mirrors the translation_diagnostics preference. The per-ROI
         * [ocr] timing log in [recognize] fires once per text region (30+ on a
         * text-heavy page), so reading the preference through SharedPreferences on
         * every call would itself be hot-path overhead. These @Volatile flags are
         * initialized lazily once; the diagnostics pref is not toggled mid-read.
         */
        @Volatile
        private var diagnosticsInitialized = false
        @Volatile
        private var diagnosticsEnabled = false

        private fun isDiagnosticsEnabled(): Boolean {
            if (diagnosticsInitialized) return diagnosticsEnabled
            diagnosticsEnabled = try {
                Injekt.get<TranslationPreferences>().translationDiagnostics().get()
            } catch (e: Throwable) {
                false
            }
            diagnosticsInitialized = true
            return diagnosticsEnabled
        }
    }
}
