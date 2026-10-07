package eu.kanade.translation.engines.vision.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import eu.kanade.translation.engines.runtime.onnx.OnnxRuntimeProvider
import java.io.Closeable
import java.nio.FloatBuffer

/** Minimal session seam used by the batch executor and deterministic JVM fakes. */
internal interface PaddleOcrV6BatchSession {
    val providerLabel: String

    fun run(input: FloatBuffer, shape: LongArray): PaddleOcrV6BatchOutput
}

internal interface PaddleOcrV6BatchOutput : Closeable {
    val shape: LongArray

    /**
     * Returns a view of `B x T x C` logits valid until [close]. The ORT adapter
     * exposes its extractor buffer directly so CTC decode does not make a
     * second full-batch copy.
     */
    fun logits(): FloatBuffer
}

/** ORT adapter; the engine remains the owner of the underlying [OrtSession]. */
internal class PaddleOcrV6OrtBatchSession(
    private val session: OrtSession,
    private val inputName: String,
    override val providerLabel: String,
) : PaddleOcrV6BatchSession {

    override fun run(input: FloatBuffer, shape: LongArray): PaddleOcrV6BatchOutput {
        val inputTensor = OnnxTensor.createTensor(
            OnnxRuntimeProvider.environment,
            input,
            shape,
        )
        val result = try {
            session.run(mapOf(inputName to inputTensor))
        } catch (error: Throwable) {
            inputTensor.close()
            throw error
        }
        return try {
            val output = result[0] as? OnnxTensor
                ?: error("Paddle OCR output is not an OnnxTensor")
            PaddleOcrV6OrtBatchOutput(result, inputTensor, output)
        } catch (error: Throwable) {
            try {
                result.close()
            } finally {
                inputTensor.close()
            }
            throw error
        }
    }
}

private class PaddleOcrV6OrtBatchOutput(
    private val result: OrtSession.Result,
    private val inputTensor: OnnxTensor,
    private val outputTensor: OnnxTensor,
) : PaddleOcrV6BatchOutput {

    override val shape: LongArray = outputTensor.info.shape.copyOf()

    override fun logits(): FloatBuffer {
        val expected = shape.fold(1L) { acc, dimension -> acc * dimension }
        require(expected <= Int.MAX_VALUE) { "Paddle OCR output is too large: $expected floats" }
        return outputTensor.floatBuffer.duplicate().apply {
            position(0)
            limit(expected.toInt())
        }
    }

    override fun close() {
        try {
            result.close()
        } finally {
            inputTensor.close()
        }
    }
}
