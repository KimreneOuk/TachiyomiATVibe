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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** ONNX session owner for the AGPL-3.0 manga109 YOLO11 bubble segmenter. */
class OnnxBubbleSegmenter {
    private var session: OrtSession? = null

    fun initialize(modelFile: File) {
        session = OnnxRuntimeProvider.createSessionWithFallback(
            modelFile.absolutePath,
            useAccelerator = true,
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

    fun segment(bitmap: Bitmap): List<BubbleSegmentationDecoder.Mask> {
        val current = session ?: throw IllegalStateException("Bubble segmenter session is not initialized")
        val transform = BubbleSegmentationDecoder.letterboxFor(bitmap.width, bitmap.height)
        val input = Bitmap.createBitmap(
            BubbleSegmentationDecoder.INPUT_SIZE,
            BubbleSegmentationDecoder.INPUT_SIZE,
            Bitmap.Config.ARGB_8888,
        )
        var tensor: OnnxTensor? = null
        var result: OrtSession.Result? = null
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
            val buffer = ByteBuffer.allocateDirect(
                3 * BubbleSegmentationDecoder.INPUT_SIZE * BubbleSegmentationDecoder.INPUT_SIZE * Float.SIZE_BYTES,
            )
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
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
            buffer.rewind()
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
            return BubbleSegmentationDecoder.decode(
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
            input.recycle()
        }
    }

    fun close() {
        session?.close()
        session = null
    }
}
