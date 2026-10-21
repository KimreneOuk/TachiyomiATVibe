package eu.kanade.translation.engines.vision.ocr

import ai.onnxruntime.OrtException
import eu.kanade.translation.diagnostics.TelemetryTrace
import eu.kanade.translation.engines.runtime.onnx.HardwareDiscoveryEngine
import eu.kanade.translation.engines.runtime.onnx.OnnxRuntimeProvider
import eu.kanade.translation.engines.runtime.onnx.PaddleOcrProviderResolution
import eu.kanade.translation.engines.runtime.onnx.PaddleOcrSessionFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.PaddleOcrExecutionProvider

/**
 * Verifies ONNX Runtime hardware execution provider session creation auditing.
 */
class HardwareSessionAuditTest {

    private val capturedLines = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        capturedLines.clear()
        TelemetryTrace.setTestSink { capturedLines.add(it) }
        TelemetryTrace.enabled = true
    }

    @AfterEach
    fun tearDown() {
        TelemetryTrace.setTestSink(null)
    }

    @Test
    fun `session_create_attempt logs correct model, requestedProvider, and isProbe false for normal models`() {
        OnnxRuntimeProvider.openSessionWithHonestLabel<String, String>(
            route = HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK,
            canUseAccelerator = false,
            useXnnpack = false,
            buildRequested = {
                OnnxRuntimeProvider.ProviderOptionsBuild("cpu-options", OnnxRuntimeProvider.RegisteredExecutionProvider.CPU)
            },
            buildCpu = {
                OnnxRuntimeProvider.ProviderOptionsBuild("cpu-options", OnnxRuntimeProvider.RegisteredExecutionProvider.CPU)
            },
            open = { "session" },
            closeOptions = {},
            sink = {},
            recordModelFailure = {},
            model = "manga_rec_v0.2_fp16.onnx",
            isProbe = false,
        )

        val attemptEvent = capturedLines.find { it.contains("event=session_create_attempt") }
        assertNotNull(attemptEvent, "session_create_attempt must be logged")
        assertTrue(attemptEvent!!.contains("domain=hardware"))
        assertTrue(attemptEvent.contains("model=manga_rec_v0.2_fp16.onnx"))
        assertTrue(attemptEvent.contains("requestedProvider=cpu"))
        assertTrue(attemptEvent.contains("isProbe=false"))
    }

    @Test
    fun `session_create_attempt logs isProbe true for probe session`() {
        OnnxRuntimeProvider.openSessionWithHonestLabel<String, String>(
            route = HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP,
            canUseAccelerator = true,
            useXnnpack = false,
            buildRequested = {
                OnnxRuntimeProvider.ProviderOptionsBuild("htp-options", OnnxRuntimeProvider.RegisteredExecutionProvider.QNN_HTP)
            },
            buildCpu = {
                OnnxRuntimeProvider.ProviderOptionsBuild("cpu-options", OnnxRuntimeProvider.RegisteredExecutionProvider.CPU)
            },
            open = { "session" },
            closeOptions = {},
            sink = {},
            recordModelFailure = {},
            model = "probe.onnx",
            isProbe = true,
        )

        val attemptEvent = capturedLines.find { it.contains("event=session_create_attempt") }
        assertNotNull(attemptEvent)
        assertTrue(attemptEvent!!.contains("domain=hardware"))
        assertTrue(attemptEvent.contains("model=probe.onnx"))
        assertTrue(attemptEvent.contains("requestedProvider=qnn_htp"))
        assertTrue(attemptEvent.contains("isProbe=true"))
    }

    @Test
    fun `session_create_success logs actualProvider and durationMs on successful creation`() {
        OnnxRuntimeProvider.openSessionWithHonestLabel<String, String>(
            route = HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_GPU,
            canUseAccelerator = true,
            useXnnpack = false,
            buildRequested = {
                OnnxRuntimeProvider.ProviderOptionsBuild("gpu-options", OnnxRuntimeProvider.RegisteredExecutionProvider.QNN_GPU)
            },
            buildCpu = {
                OnnxRuntimeProvider.ProviderOptionsBuild("cpu-options", OnnxRuntimeProvider.RegisteredExecutionProvider.CPU)
            },
            open = { "session" },
            closeOptions = {},
            sink = {},
            recordModelFailure = {},
            model = "manga_ocr.onnx",
            isProbe = false,
        )

        val successEvent = capturedLines.find { it.contains("event=session_create_success") }
        assertNotNull(successEvent, "session_create_success must be logged")
        assertTrue(successEvent!!.contains("domain=hardware"))
        assertTrue(successEvent.contains("model=manga_ocr.onnx"))
        assertTrue(successEvent.contains("actualProvider=qnn_gpu"))
        assertTrue(successEvent.contains("durationMs="))
    }

    @Test
    fun `session_create_failure logs errorType, sanitized errorReason, and fallbackProvider=cpu when accelerator throws`() {
        val ortError = OrtException(OrtException.OrtErrorCode.ORT_FAIL, "Dynamic shapes not supported by QNN EP\nLine 2 info")

        OnnxRuntimeProvider.openSessionWithHonestLabel<String, String>(
            route = HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_GPU,
            canUseAccelerator = true,
            useXnnpack = false,
            buildRequested = {
                OnnxRuntimeProvider.ProviderOptionsBuild("gpu-options", OnnxRuntimeProvider.RegisteredExecutionProvider.QNN_GPU)
            },
            buildCpu = {
                OnnxRuntimeProvider.ProviderOptionsBuild("cpu-options", OnnxRuntimeProvider.RegisteredExecutionProvider.CPU)
            },
            open = { opts ->
                if (opts == "gpu-options") throw ortError
                "cpu-session"
            },
            closeOptions = {},
            sink = {},
            recordModelFailure = {},
            model = "manga_rec_v0.2_fp16.onnx",
            isProbe = false,
        )

        val failEvent = capturedLines.find { it.contains("event=session_create_failure") }
        assertNotNull(failEvent, "session_create_failure must be logged on accelerator exception")
        assertTrue(failEvent!!.contains("domain=hardware"))
        assertTrue(failEvent.contains("model=manga_rec_v0.2_fp16.onnx"))
        assertTrue(failEvent.contains("requestedProvider=qnn_gpu"))
        assertTrue(failEvent.contains("errorType=OrtException"))
        assertTrue(failEvent.contains("errorReason=") && failEvent.contains("Dynamic shapes not supported by QNN EP Line 2 info"))
        assertTrue(failEvent.contains("fallbackProvider=cpu"))

        // Also check that CPU retry was attempted and succeeded
        val attempts = capturedLines.filter { it.contains("event=session_create_attempt") }
        assertTrue(attempts.size >= 2, "Expected accelerator attempt and CPU retry attempt")
        assertTrue(attempts[0].contains("requestedProvider=qnn_gpu"))
        assertTrue(attempts[1].contains("requestedProvider=cpu"))

        val successes = capturedLines.filter { it.contains("event=session_create_success") }
        assertTrue(successes.size == 1, "Only CPU retry should succeed")
        assertTrue(successes[0].contains("actualProvider=cpu"))
    }

    @Test
    fun `PaddleOcrSessionFactory end-to-end session creation logs attempt, failure, and fallback`() {
        val resolution = PaddleOcrProviderResolution(
            requested = PaddleOcrExecutionProvider.QUALCOMM_QNN_GPU,
            route = HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_GPU,
        )

        PaddleOcrSessionFactory.createSessionWithHonestLabel<String, String>(
            modelPath = "manga_rec_v0.2_fp16.onnx",
            resolution = resolution,
            buildRequested = { canUseAccelerator ->
                OnnxRuntimeProvider.ProviderOptionsBuild("gpu-options", OnnxRuntimeProvider.RegisteredExecutionProvider.QNN_GPU)
            },
            buildCpu = {
                OnnxRuntimeProvider.ProviderOptionsBuild("cpu-options", OnnxRuntimeProvider.RegisteredExecutionProvider.CPU)
            },
            open = { opts ->
                if (opts == "gpu-options") {
                    throw OrtException(OrtException.OrtErrorCode.ORT_FAIL, "QNN EP rejected dynamic shapes")
                }
                "cpu-session"
            },
            closeOptions = {},
            providerSink = {},
            recordModelFailure = {},
        )

        val failEvent = capturedLines.find { it.contains("event=session_create_failure") }
        assertNotNull(failEvent)
        assertTrue(failEvent!!.contains("model=manga_rec_v0.2_fp16.onnx"))
        assertTrue(failEvent.contains("requestedProvider=qnn_gpu"))
        assertTrue(failEvent.contains("errorType=OrtException"))
        assertTrue(failEvent.contains("fallbackProvider=cpu"))

        val successEvent = capturedLines.find { it.contains("event=session_create_success") }
        assertNotNull(successEvent)
        assertTrue(successEvent!!.contains("actualProvider=cpu"))
    }
}
