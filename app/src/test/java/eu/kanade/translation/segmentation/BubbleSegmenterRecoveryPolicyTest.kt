package eu.kanade.translation.segmentation

import ai.onnxruntime.OrtException
import eu.kanade.translation.runtime.onnx.HardwareDiscoveryEngine
import eu.kanade.translation.runtime.onnx.ModelRoutingEngine
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * T922 plan §6.1: JVM policy tests for the bubble segmenter's one-shot
 * accelerated→CPU runtime recovery and its CPU-primary initialization.
 *
 * Uses the [OnnxBubbleSegmenter.SessionFactory] seam injected through the
 * constructor; no native ONNX runtime, no physical model, and no Android
 * graphics are involved. Fakes only ever throw [OrtException] (constructible
 * on the JVM) or non-OrtException contract errors, mirroring what the real
 * session surface propagates to the policy.
 */
class BubbleSegmenterRecoveryPolicyTest {

    private class FakeSession(
        val label: String,
    ) : OnnxBubbleSegmenter.SegmenterSessionHandle {
        var behavior: (FloatBuffer) -> Pair<FloatArray, FloatArray> = {
            FloatArray(PREDICTION_FLOATS) to FloatArray(PROTOTYPE_FLOATS)
        }
        val runCount = AtomicInteger()
        val closeCount = AtomicInteger()

        override val inputNames = setOf("images")
        override val outputNames = setOf("output0", "output1")

        override fun run(input: FloatBuffer): Pair<FloatArray, FloatArray> {
            runCount.incrementAndGet()
            return behavior(input)
        }

        override fun close() {
            closeCount.incrementAndGet()
        }
    }

    private class RecordingFactory : OnnxBubbleSegmenter.SessionFactory {
        data class Request(
            val modelPath: String,
            val useAccelerator: Boolean,
            val useXnnpack: Boolean,
        )

        val requests = mutableListOf<Request>()
        val pending = ArrayDeque<FakeSession>()

        override fun create(
            modelPath: String,
            useAccelerator: Boolean,
            useXnnpack: Boolean,
            providerSink: (String) -> Unit,
        ): OnnxBubbleSegmenter.SegmenterSessionHandle {
            requests.add(Request(modelPath, useAccelerator, useXnnpack))
            val session = pending.removeFirst()
            providerSink(session.label)
            return session
        }
    }

    @TempDir
    lateinit var tempDir: File

    private fun newSegmenter(factory: RecordingFactory): OnnxBubbleSegmenter {
        val segmenter = OnnxBubbleSegmenter(factory)
        segmenter.initialize(File(tempDir, "bubble_segmenter.onnx"))
        return segmenter
    }

    private fun inputBuffer(): FloatBuffer =
        FloatBuffer.allocate(3 * BubbleSegmentationDecoder.INPUT_SIZE * BubbleSegmentationDecoder.INPUT_SIZE)

    @Test
    fun `production initialize requests default cpu and never qnn or xnnpack`() {
        val factory = RecordingFactory()
        factory.pending.add(FakeSession(label = "cpu"))

        val segmenter = OnnxBubbleSegmenter(factory)
        segmenter.initialize(File(tempDir, "bubble_segmenter.onnx"))

        factory.requests.size shouldBe 1
        val request = factory.requests.single()
        request.useAccelerator shouldBe false
        request.useXnnpack shouldBe false
        segmenter.executionProviderLabel shouldBe "cpu"
    }

    @Test
    fun `accelerated ort exception code 1100 swaps to cpu retries once and returns cpu result`() {
        ModelRoutingEngine.reset()
        val factory = RecordingFactory()
        val acceleratorError =
            OrtException(OrtException.OrtErrorCode.ORT_ENGINE_ERROR, "QNN execute failed with error code 1100")
        val accelerated = FakeSession(label = "qnn_htp").apply { behavior = { throw acceleratorError } }
        val cpu = FakeSession(label = "cpu")
        factory.pending.add(accelerated)
        factory.pending.add(cpu)

        val segmenter = newSegmenter(factory)
        segmenter.executionProviderLabel shouldBe "qnn_htp"

        val (predictions, prototypes) = segmenter.runInferenceWithRecovery(accelerated, inputBuffer())

        predictions.size shouldBe PREDICTION_FLOATS
        prototypes.size shouldBe PROTOTYPE_FLOATS
        // Failed session closed, CPU session created, retry exactly once.
        accelerated.runCount.get() shouldBe 1
        accelerated.closeCount.get() shouldBe 1
        cpu.runCount.get() shouldBe 1
        cpu.closeCount.get() shouldBe 0
        factory.requests.size shouldBe 2
        factory.requests[1].useAccelerator shouldBe false
        factory.requests[1].useXnnpack shouldBe false
        segmenter.executionProviderLabel shouldBe "cpu"
        // T922 Phase 5 (plan §3.3): QNN graph execute error 1100 is now
        // classified as a hard accelerated-route EXECUTION failure BEFORE the
        // SSR heuristic — even though OrtException's ORT_ENGINE_ERROR enum
        // name leaks "ENGINE_ERROR" into the message text. The model is
        // demoted (UNSUPPORTED) for that route and this page retried on CPU.
        ModelRoutingEngine.getStatus(
            ModelRoutingEngine.resolveModelId(File(tempDir, "bubble_segmenter.onnx").absolutePath),
            HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP,
        ) shouldBe ModelRoutingEngine.Status.UNSUPPORTED
    }

    @Test
    fun `successful accelerated run records execution proof exactly once`() {
        ModelRoutingEngine.reset()
        val factory = RecordingFactory()
        val accelerated = FakeSession(label = "qnn_htp")
        factory.pending.add(accelerated)

        val segmenter = newSegmenter(factory)
        val modelId = ModelRoutingEngine.resolveModelId(File(tempDir, "bubble_segmenter.onnx").absolutePath)

        mockkObject(ModelRoutingEngine)
        try {
            every { ModelRoutingEngine.resolveModelId(any()) } answers { callOriginal() }
            every { ModelRoutingEngine.getStatus(any(), any()) } answers { callOriginal() }
            every { ModelRoutingEngine.recordSuccessfulInference(any(), any()) } answers { callOriginal() }
            every { ModelRoutingEngine.recordFailure(any(), any(), any()) } answers { callOriginal() }
            every { ModelRoutingEngine.isAcceleratorAttemptAllowed(any(), any()) } answers { callOriginal() }

            segmenter.runInferenceWithRecovery(accelerated, inputBuffer())

            // One successful accelerated run → exactly one execution proof,
            // against the model id and the label's actual route.
            verify(exactly = 1) {
                ModelRoutingEngine.recordSuccessfulInference(modelId, HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP)
            }
            verify(exactly = 0) { ModelRoutingEngine.recordFailure(any(), any(), any()) }
            // SUPPORTED now requires create AND execute (plan §3.3).
            ModelRoutingEngine.getStatus(modelId, HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP) shouldBe
                ModelRoutingEngine.Status.SUPPORTED
        } finally {
            unmockkObject(ModelRoutingEngine)
            ModelRoutingEngine.reset()
        }
    }

    @Test
    fun `cpu primary successful run never marks accelerator support`() {
        ModelRoutingEngine.reset()
        val factory = RecordingFactory()
        val cpu = FakeSession(label = "cpu")
        factory.pending.add(cpu)

        val segmenter = newSegmenter(factory)
        val modelId = ModelRoutingEngine.resolveModelId(File(tempDir, "bubble_segmenter.onnx").absolutePath)

        mockkObject(ModelRoutingEngine)
        try {
            every { ModelRoutingEngine.resolveModelId(any()) } answers { callOriginal() }
            every { ModelRoutingEngine.getStatus(any(), any()) } answers { callOriginal() }
            every { ModelRoutingEngine.recordSuccessfulInference(any(), any()) } answers { callOriginal() }
            every { ModelRoutingEngine.recordFailure(any(), any(), any()) } answers { callOriginal() }
            every { ModelRoutingEngine.isAcceleratorAttemptAllowed(any(), any()) } answers { callOriginal() }

            segmenter.runInferenceWithRecovery(cpu, inputBuffer())

            // CPU is the terminal route: no accelerator support, no failure
            // recorded, routing state stays UNKNOWN.
            verify(exactly = 0) { ModelRoutingEngine.recordSuccessfulInference(any(), any()) }
            verify(exactly = 0) { ModelRoutingEngine.recordFailure(any(), any(), any()) }
            ModelRoutingEngine.getStatus(modelId, HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP) shouldBe
                ModelRoutingEngine.Status.UNKNOWN
        } finally {
            unmockkObject(ModelRoutingEngine)
            ModelRoutingEngine.reset()
        }
    }

    @Test
    fun `cpu primary ort exception propagates without session recreation`() {
        val factory = RecordingFactory()
        val cpuError = OrtException(OrtException.OrtErrorCode.ORT_FAIL, "cpu inference failed")
        val cpu = FakeSession(label = "cpu").apply { behavior = { throw cpuError } }
        factory.pending.add(cpu)

        val segmenter = newSegmenter(factory)

        val thrown = assertThrows<OrtException> {
            segmenter.runInferenceWithRecovery(cpu, inputBuffer())
        }

        thrown shouldBe cpuError
        cpu.runCount.get() shouldBe 1
        cpu.closeCount.get() shouldBe 0
        factory.requests.size shouldBe 1
        segmenter.executionProviderLabel shouldBe "cpu"
    }

    @Test
    fun `accelerator failure followed by cpu failure performs exactly two runs and propagates cpu failure`() {
        val factory = RecordingFactory()
        val acceleratorError =
            OrtException(OrtException.OrtErrorCode.ORT_ENGINE_ERROR, "QNN execute failed with error code 1100")
        val cpuError = OrtException(OrtException.OrtErrorCode.ORT_FAIL, "cpu retry failed")
        val accelerated = FakeSession(label = "qnn_htp").apply { behavior = { throw acceleratorError } }
        val cpu = FakeSession(label = "cpu").apply { behavior = { throw cpuError } }
        factory.pending.add(accelerated)
        factory.pending.add(cpu)

        val segmenter = newSegmenter(factory)

        val thrown = assertThrows<OrtException> {
            segmenter.runInferenceWithRecovery(accelerated, inputBuffer())
        }

        // The CPU failure is propagated with the first accelerator failure
        // suppressed, and no third run is ever attempted.
        thrown shouldBe cpuError
        thrown.suppressed.contains(acceleratorError) shouldBe true
        accelerated.runCount.get() shouldBe 1
        cpu.runCount.get() shouldBe 1
        factory.requests.size shouldBe 2
        accelerated.closeCount.get() shouldBe 1
    }

    @Test
    fun `decoder output shape errors do not trigger provider fallback`() {
        val factory = RecordingFactory()
        val shapeError =
            IllegalArgumentException("Unsupported bubble output0 shape=[1,1,1]; expected [1,37,8400]")
        val accelerated = FakeSession(label = "qnn_htp").apply { behavior = { throw shapeError } }
        factory.pending.add(accelerated)

        val segmenter = newSegmenter(factory)

        val thrown = assertThrows<IllegalArgumentException> {
            segmenter.runInferenceWithRecovery(accelerated, inputBuffer())
        }

        thrown shouldBe shapeError
        factory.requests.size shouldBe 1
        accelerated.runCount.get() shouldBe 1
        accelerated.closeCount.get() shouldBe 0
        segmenter.executionProviderLabel shouldBe "qnn_htp"
    }

    @Test
    fun `close is idempotent after a swap and releases each session exactly once`() {
        val factory = RecordingFactory()
        val acceleratorError =
            OrtException(OrtException.OrtErrorCode.ORT_ENGINE_ERROR, "QNN execute failed with error code 1100")
        val accelerated = FakeSession(label = "qnn_htp").apply { behavior = { throw acceleratorError } }
        val cpu = FakeSession(label = "cpu")
        factory.pending.add(accelerated)
        factory.pending.add(cpu)

        val segmenter = newSegmenter(factory)
        segmenter.runInferenceWithRecovery(accelerated, inputBuffer())

        segmenter.close()
        segmenter.close()
        // Pooled-buffer relief entry points stay safe after teardown.
        segmenter.reclaimPooledMemory()
        segmenter.forceReleaseNativeBuffers()

        // The swapped-out failed session was closed exactly once during the
        // swap; the CPU session exactly once by the first close(); the second
        // close() closed nothing.
        accelerated.closeCount.get() shouldBe 1
        cpu.closeCount.get() shouldBe 1
    }

    private companion object {
        const val PREDICTION_FLOATS = 37 * 8400
        const val PROTOTYPE_FLOATS = 32 * 160 * 160
    }
}
