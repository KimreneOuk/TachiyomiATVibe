package eu.kanade.translation.engines.runtime.onnx

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ModelRoutingEngineTest {

    @BeforeEach
    fun setup() {
        ModelRoutingEngine.reset()
    }

    @Test
    fun `initial status is UNKNOWN and isSupported returns true for probe attempt`() {
        val route = HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP
        val model = "aot-512.onnx"

        ModelRoutingEngine.getStatus(model, route) shouldBe ModelRoutingEngine.Status.UNKNOWN
        ModelRoutingEngine.isSupported(model, route) shouldBe true
    }

    @Test
    fun `marking supported transitions status to SUPPORTED`() {
        val route = HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP
        val model = "aot-512.onnx"

        ModelRoutingEngine.markSupported(model, route)
        ModelRoutingEngine.getStatus(model, route) shouldBe ModelRoutingEngine.Status.SUPPORTED
        ModelRoutingEngine.isSupported(model, route) shouldBe true
    }

    @Test
    fun `graph mismatch error immediately marks model UNSUPPORTED and rejects further attempts`() {
        val route = HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP
        val model = "detector.onnx"

        val error = RuntimeException("This session contains graph nodes assigned to default CPU EP, fallback disabled")
        val canRetry = ModelRoutingEngine.recordFailure(model, route, error)

        canRetry shouldBe false
        ModelRoutingEngine.getStatus(model, route) shouldBe ModelRoutingEngine.Status.UNSUPPORTED
        ModelRoutingEngine.isSupported(model, route) shouldBe false
    }

    @Test
    fun `fastrpc ssr failure permits one retry then demotes to unsupported on second consecutive failure`() {
        val route = HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP
        val model = "fragile_model.onnx"

        val ssrError = RuntimeException("FastRPC Subsystem Restart ENGINE_ERROR: DSP subsystem crashed")

        // First attempt: should permit retry
        val canRetryFirst = ModelRoutingEngine.recordFailure(model, route, ssrError)
        canRetryFirst shouldBe true
        ModelRoutingEngine.getStatus(model, route) shouldBe ModelRoutingEngine.Status.TEMPORARY_FAILURE

        // Second attempt: should NOT permit retry, demote to UNSUPPORTED
        val canRetrySecond = ModelRoutingEngine.recordFailure(model, route, ssrError)
        canRetrySecond shouldBe false
        ModelRoutingEngine.getStatus(model, route) shouldBe ModelRoutingEngine.Status.UNSUPPORTED
        ModelRoutingEngine.isSupported(model, route) shouldBe false
    }

    @Test
    fun `generic HTP options are minimal without hardcoded soc or forced burst mode`() {
        val options = OnnxRuntimeProvider.buildGenericHtpOptions()
        options["backend_type"] shouldBe "htp"
        options.containsKey("htp_performance_mode") shouldBe false
        options.containsKey("soc_model") shouldBe false
        options.containsKey("htp_arch") shouldBe false
        options.containsKey("htp_graph_finalization_optimization_mode") shouldBe false
    }

    @Test
    fun `resolveModelId resolves distinct IDs for det and rec inference onnx`() {
        val detPath = "/data/user/0/eu.kanade.tachiyomi.at.debug/files/models/paddle-v6-small/det/inference.onnx"
        val recPath = "/data/user/0/eu.kanade.tachiyomi.at.debug/files/models/paddle-v6-small/inference.onnx"
        val aotPath = "/data/user/0/eu.kanade.tachiyomi.at.debug/files/models/inpainting/aot-512.onnx"

        val detId = ModelRoutingEngine.resolveModelId(detPath)
        val recId = ModelRoutingEngine.resolveModelId(recPath)
        val aotId = ModelRoutingEngine.resolveModelId(aotPath)

        detId shouldBe "paddle-v6-small/det/inference.onnx"
        recId shouldBe "paddle-v6-small/inference.onnx"
        aotId shouldBe "inpainting/aot-512.onnx"

        (detId != recId) shouldBe true
    }

    @Test
    fun `marking det UNSUPPORTED does not collide or mark rec UNSUPPORTED`() {
        val route = HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP
        val detPath = "/data/user/0/eu.kanade.tachiyomi.at.debug/files/models/paddle-v6-small/det/inference.onnx"
        val recPath = "/data/user/0/eu.kanade.tachiyomi.at.debug/files/models/paddle-v6-small/inference.onnx"

        ModelRoutingEngine.markUnsupported(detPath, route, "dynamic shapes")

        ModelRoutingEngine.isSupported(detPath, route) shouldBe false
        ModelRoutingEngine.getStatus(detPath, route) shouldBe ModelRoutingEngine.Status.UNSUPPORTED

        // Paddle recognition must remain unaffected!
        ModelRoutingEngine.isSupported(recPath, route) shouldBe true
        ModelRoutingEngine.getStatus(recPath, route) shouldBe ModelRoutingEngine.Status.UNKNOWN
    }

    // ---- SUPPORTED requires execution, coherent
    // temporary-retry gate, QNN graph execute 1100 classification. ----

    @Test
    fun `recordSuccessfulInference transitions status to SUPPORTED and clears the ssr retry counter`() {
        val route = HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP
        val model = "bubble_segmenter.onnx"

        // A prior SSR failure consumed the single recreation attempt.
        ModelRoutingEngine.recordFailure(
            model,
            route,
            ai.onnxruntime.OrtException(
                ai.onnxruntime.OrtException.OrtErrorCode.ORT_FAIL,
                "FastRPC remote error: subsystem restart",
            ),
        )
        ModelRoutingEngine.getStatus(model, route) shouldBe ModelRoutingEngine.Status.TEMPORARY_FAILURE

        // One successful executed inference proves the route again and clears
        // the retry counter.
        ModelRoutingEngine.recordSuccessfulInference(model, route)

        ModelRoutingEngine.getStatus(model, route) shouldBe ModelRoutingEngine.Status.SUPPORTED
        ModelRoutingEngine.isAcceleratorAttemptAllowed(model, route) shouldBe true
    }

    @Test
    fun `temporary failure remains eligible for exactly one recreation attempt via the attempt gate`() {
        val route = HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP
        val model = "fragile_model.onnx"
        val ssrError = RuntimeException("FastRPC Subsystem Restart ENGINE_ERROR: DSP subsystem crashed")

        // First failure: TEMPORARY_FAILURE, and the documented recreation
        // attempt is now actually allowed by the gate (previously the attempt
        // gate excluded TEMPORARY_FAILURE, making the promise unreachable).
        ModelRoutingEngine.recordFailure(model, route, ssrError) shouldBe true
        ModelRoutingEngine.getStatus(model, route) shouldBe ModelRoutingEngine.Status.TEMPORARY_FAILURE
        ModelRoutingEngine.isAcceleratorAttemptAllowed(model, route) shouldBe true

        // Second failure consumes the recreation attempt: demoted, no more
        // accelerator attempts.
        ModelRoutingEngine.recordFailure(model, route, ssrError) shouldBe false
        ModelRoutingEngine.getStatus(model, route) shouldBe ModelRoutingEngine.Status.UNSUPPORTED
        ModelRoutingEngine.isAcceleratorAttemptAllowed(model, route) shouldBe false
    }

    @Test
    fun `attempt gate allows unknown and supported and rejects unsupported`() {
        val route = HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP
        val model = "never_tried.onnx"

        ModelRoutingEngine.isAcceleratorAttemptAllowed(model, route) shouldBe true

        ModelRoutingEngine.markSupported(model, route)
        ModelRoutingEngine.isAcceleratorAttemptAllowed(model, route) shouldBe true

        ModelRoutingEngine.markUnsupported(model, route, "graph mismatch")
        ModelRoutingEngine.isAcceleratorAttemptAllowed(model, route) shouldBe false
    }

    @Test
    fun `isSupported keeps strict proven-or-unattempted semantics excluding temporary failure`() {
        val route = HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP
        val model = "aot-512.onnx"
        val ssrError = RuntimeException("FastRPC remote error during execute")

        ModelRoutingEngine.recordFailure(model, route, ssrError) shouldBe true
        ModelRoutingEngine.getStatus(model, route) shouldBe ModelRoutingEngine.Status.TEMPORARY_FAILURE

        // isSupported stays conservative (AOT-GAN's recovery policy depends on
        // it); the attempt gate is the only place TEMPORARY_FAILURE is allowed.
        ModelRoutingEngine.isSupported(model, route) shouldBe false
        ModelRoutingEngine.isAcceleratorAttemptAllowed(model, route) shouldBe true

        // Execution proof is what turns it SUPPORTED again.
        ModelRoutingEngine.recordSuccessfulInference(model, route)
        ModelRoutingEngine.isSupported(model, route) shouldBe true
    }

    @Test
    fun `qnn graph execute 1100 classifies as execution failure even without engine error text`() {
        val route = HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP
        val model = "bubble_segmenter.onnx"

        // The real device message: ORT_FAIL, no "ENGINE_ERROR" token anywhere.
        val error = ai.onnxruntime.OrtException(
            ai.onnxruntime.OrtException.OrtErrorCode.ORT_FAIL,
            "Non-zero status code returned while running QNNExecutionProvider node. " +
                "Status Message: QNN graph execute error. Error code: 1100",
        )

        val canRetry = ModelRoutingEngine.recordFailure(model, route, error)

        canRetry shouldBe false
        ModelRoutingEngine.getStatus(model, route) shouldBe ModelRoutingEngine.Status.UNSUPPORTED
        ModelRoutingEngine.isAcceleratorAttemptAllowed(model, route) shouldBe false
    }

    @Test
    fun `qnn graph execute 1100 classifies as execution failure even when text contains engine error`() {
        val route = HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP
        val model = "bubble_segmenter.onnx"

        // An OrtException built from ORT_ENGINE_ERROR leaks "ENGINE_ERROR" into
        // the message; the 1100 code must still win over the SSR heuristic.
        val error = ai.onnxruntime.OrtException(
            ai.onnxruntime.OrtException.OrtErrorCode.ORT_ENGINE_ERROR,
            "QNN graph execute error. Error code: 1100",
        )

        val canRetry = ModelRoutingEngine.recordFailure(model, route, error)

        canRetry shouldBe false
        ModelRoutingEngine.getStatus(model, route) shouldBe ModelRoutingEngine.Status.UNSUPPORTED
    }

    @Test
    fun `error code 1100 pattern on a non-ort exception does not hijack ssr classification`() {
        val route = HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP
        val model = "generic_model.onnx"

        // Only OrtException carries the QNN 1100 classification; arbitrary
        // throwables keep the existing heuristics.
        val plain = RuntimeException("internal buffer id 1100 exhausted")
        val canRetry = ModelRoutingEngine.recordFailure(model, route, plain)

        canRetry shouldBe false
        ModelRoutingEngine.getStatus(model, route) shouldBe ModelRoutingEngine.Status.UNSUPPORTED

        // ...while a FastRPC OrtException is still classified as temporary.
        val ssr = ai.onnxruntime.OrtException(
            ai.onnxruntime.OrtException.OrtErrorCode.ORT_FAIL,
            "FastRPC remote error: subsystem restart",
        )
        ModelRoutingEngine.recordFailure(model, route, ssr) shouldBe true
        ModelRoutingEngine.getStatus(model, route) shouldBe ModelRoutingEngine.Status.TEMPORARY_FAILURE
    }
}
