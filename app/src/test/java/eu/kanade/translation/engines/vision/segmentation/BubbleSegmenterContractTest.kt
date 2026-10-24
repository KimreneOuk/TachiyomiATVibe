package eu.kanade.translation.engines.vision.segmentation

import eu.kanade.translation.engines.runtime.onnx.HardwareDiscoveryEngine
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.FloatBuffer

/**
 * JVM tests for the segmenter's tensor-contract validation. The check is
 * size-based (one input, two outputs) so both the ORT naming and the LiteRT
 * flatbuffer naming pass; the tensor counts stay mandatory.
 */
class BubbleSegmenterContractTest {

    private class NamedSession(
        override val inputNames: Set<String>,
        override val outputNames: Set<String>,
    ) : OnnxBubbleSegmenter.SegmenterSessionHandle {
        override fun run(input: FloatBuffer): Pair<FloatArray, FloatArray> {
            return FloatArray(37 * 8400) to FloatArray(32 * 160 * 160)
        }

        override fun close() {}
    }

    private class FixedFactory(
        private val session: OnnxBubbleSegmenter.SegmenterSessionHandle,
    ) : OnnxBubbleSegmenter.SessionFactory {
        override fun create(
            modelPath: String,
            useAccelerator: Boolean,
            useXnnpack: Boolean,
            providerSink: (String) -> Unit,
            requestedHardwareRoute: HardwareDiscoveryEngine.HardwareRoute?,
        ): OnnxBubbleSegmenter.SegmenterSessionHandle {
            providerSink("test")
            return session
        }
    }

    @Test
    fun acceptsLiteRtFlatbufferTensorNaming() {
        val segmenter = OnnxBubbleSegmenter(
            FixedFactory(NamedSession(setOf("litert:images"), setOf("litert:output0", "litert:output1"))),
        )
        segmenter.initialize(File("unused.tflite"))
        segmenter.close()
    }

    @Test
    fun rejectsWrongInputCount() {
        val segmenter = OnnxBubbleSegmenter(
            FixedFactory(NamedSession(setOf("a", "b"), setOf("o0", "o1"))),
        )
        assertThrows<IllegalArgumentException> { segmenter.initialize(File("unused.onnx")) }
        segmenter.close()
    }

    @Test
    fun rejectsWrongOutputCount() {
        val segmenter = OnnxBubbleSegmenter(
            FixedFactory(NamedSession(setOf("images"), setOf("only"))),
        )
        assertThrows<IllegalArgumentException> { segmenter.initialize(File("unused.onnx")) }
        segmenter.close()
    }
}
