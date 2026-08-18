package eu.kanade.translation.segmentation

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

/** ONNX session owner for the AGPL-3.0 manga109 YOLO11 bubble segmenter. */
class OnnxBubbleSegmenter {
    private var session: OrtSession? = null

    /** Provider that actually serves this segmenter ("qnn_htp"/"nnapi"/"cpu"), for honest perf logging. */
    var executionProviderLabel: String = "uninitialized"
        private set

    // TachiyomiAT: pooled DIRECT buffer for the fixed 1x3x640x640 tensor; ORT
    // consumes it in place so it MUST outlive the tensor. maxPoolSize=2 bounds
    // resident native memory. Same contract as OnnxPageTextDetector.
    private val inputBufferPool = DirectBufferPool(
        bufferCapacityBytes = SEGMENTER_INPUT_FLOATS * Float.SIZE_BYTES,
        maxPoolSize = 2,
    )

    fun initialize(modelFile: File) {
        session = OnnxRuntimeProvider.createSessionWithFallback(
            modelFile.absolutePath,
            useAccelerator = true,
            providerSink = { executionProviderLabel = it },
        )
        val current = requireNotNull(session)
        require(current.inputNames == setOf("images")) {
            "Bubble segmenter input contract changed: ${current.inputNames}"
        }
        require(current.outputNames.size == 2) { "Bubble segmenter expected two outputs, got ${current.outputNames}" }
        logcat(LogPriority.INFO) {
            "Bubble segmenter initialized: inputs=${current.inputNames} outputs=${current.outputNames}"
        }
    }

    fun segment(bitmap: Bitmap): List<BubbleMaskRle> {
        val current = session ?: throw IllegalStateException("Bubble segmenter session is not initialized")
        val transform = BubbleSegmentationDecoder.letterboxFor(bitmap.width, bitmap.height)
        val input = BitmapPool.getARGB8888(
            BubbleSegmentationDecoder.INPUT_SIZE,
            BubbleSegmentationDecoder.INPUT_SIZE,
        )
        var tensor: OnnxTensor? = null
        var result: OrtSession.Result? = null
        var inputBuffer: FloatBuffer? = null
        try {
            Canvas(input).apply {
                drawColor(0xFF727272.toInt())
                drawBitmap(
                    bitmap,
                    null,
                    RectF(
                        transform.padX.toFloat(),
                        transform.padY.toFloat(),
                        transform.padX + bitmap.width * transform.ratio,
                        transform.padY + bitmap.height * transform.ratio,
                    ),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
            }
            // TachiyomiAT: ORT consumes the direct buffer in place (no native copy),
            // so it MUST outlive the tensor — keep referenced until the finally.
            inputBuffer = inputBufferPool.acquire()
            val buffer = inputBuffer
            buffer.clear()
            val pixels = IntArray(BubbleSegmentationDecoder.INPUT_SIZE * BubbleSegmentationDecoder.INPUT_SIZE)
            input.getPixels(
                pixels,
                0,
                BubbleSegmentationDecoder.INPUT_SIZE,
                0,
                0,
                BubbleSegmentationDecoder.INPUT_SIZE,
                BubbleSegmentationDecoder.INPUT_SIZE,
            )
            val plane = BubbleSegmentationDecoder.INPUT_SIZE * BubbleSegmentationDecoder.INPUT_SIZE
            for (i in pixels.indices) {
                buffer.put(i, (pixels[i] shr 16 and 0xFF) / 255f)
                buffer.put(plane + i, (pixels[i] shr 8 and 0xFF) / 255f)
                buffer.put(2 * plane + i, (pixels[i] and 0xFF) / 255f)
            }
            buffer.limit(3 * plane)
            buffer.position(0)
            tensor = OnnxTensor.createTensor(OnnxRuntimeProvider.environment, buffer, longArrayOf(1, 3, 640, 640))
            result = current.run(mapOf("images" to tensor))
            val prediction = result[0] as? OnnxTensor ?: error("Bubble segmenter output0 is not a tensor")
            val prototype = result[1] as? OnnxTensor ?: error("Bubble segmenter output1 is not a tensor")
            val pShape = prediction.info.shape
            val mShape = prototype.info.shape
            require(pShape.contentEquals(longArrayOf(1, 37, 8400))) {
                "Unsupported bubble output0 shape=${pShape.contentToString()}; expected [1,37,8400]"
            }
            require(mShape.contentEquals(longArrayOf(1, 32, 160, 160))) {
                "Unsupported bubble output1 shape=${mShape.contentToString()}; expected [1,32,160,160]"
            }
            val predictions = FloatArray(37 * 8400).also { prediction.floatBuffer.get(it) }
            val prototypes = FloatArray(32 * 160 * 160).also { prototype.floatBuffer.get(it) }
            return BubbleSegmentationDecoder.decodeRle(
                predictions,
                37,
                8400,
                prototypes,
                160,
                160,
                bitmap.width,
                bitmap.height,
            )
        } finally {
            result?.close()
            tensor?.close()
            inputBuffer?.let { inputBufferPool.release(it) }
            BitmapPool.putARGB8888(input)
        }
    }

    fun close() {
        session?.close()
        session = null
        inputBufferPool.clear()
    }

    // TachiyomiAT: frees the pooled direct buffer without tearing down the ONNX
    // session; wired into per-page OOM relief. Registered in
    // RoiPageRecognitionEngine's reclaim fan-outs like the sibling engines.
    fun reclaimPooledMemory() {
        inputBufferPool.clear()
    }

    fun forceReleaseNativeBuffers() {
        inputBufferPool.clear()
    }

    companion object {
        private const val SEGMENTER_INPUT_FLOATS = 3 * BubbleSegmentationDecoder.INPUT_SIZE * BubbleSegmentationDecoder.INPUT_SIZE
    }
}
