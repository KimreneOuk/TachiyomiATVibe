package eu.kanade.translation.engines.inpainting

import ai.onnxruntime.OrtException
import eu.kanade.translation.engines.runtime.onnx.HardwareDiscoveryEngine
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class InpaintingProviderRecoveryTest {

    @Test
    fun `accelerator inference failure records route failure and retries once on CPU`() {
        var failures = 0
        var successes = 0
        var cpuRetries = 0

        val result = runWithInpaintingProviderRecovery(
            route = HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP,
            inference = { throw qnnFailure() },
            retryOnCpu = {
                cpuRetries++
                "cpu result"
            },
            recordFailure = { failures++ },
            recordSuccess = { successes++ },
        )

        result shouldBe "cpu result"
        cpuRetries shouldBe 1
        failures shouldBe 1
        successes shouldBe 0
    }

    @Test
    fun `successful accelerator inference records support without CPU retry`() {
        var failures = 0
        var successes = 0
        var cpuRetries = 0

        val result = runWithInpaintingProviderRecovery(
            route = HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_GPU,
            inference = { "gpu result" },
            retryOnCpu = {
                cpuRetries++
                "cpu result"
            },
            recordFailure = { failures++ },
            recordSuccess = { successes++ },
        )

        result shouldBe "gpu result"
        cpuRetries shouldBe 0
        failures shouldBe 0
        successes shouldBe 1
    }

    @Test
    fun `CPU inference failures do not trigger a second retry`() {
        var failures = 0
        var successes = 0
        var cpuRetries = 0

        shouldThrow<OrtException> {
            runWithInpaintingProviderRecovery(
                route = HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK,
                inference = { throw ortFailure("CPU inference failed") },
                retryOnCpu = {
                    cpuRetries++
                    "cpu result"
                },
                recordFailure = { failures++ },
                recordSuccess = { successes++ },
            )
        }

        cpuRetries shouldBe 0
        failures shouldBe 0
        successes shouldBe 0
    }

    @Test
    fun `CPU retry failure retains the accelerator failure as suppressed`() {
        val acceleratorError = qnnFailure()
        val cpuError = ortFailure("CPU retry failed")

        val thrown = shouldThrow<OrtException> {
            runWithInpaintingProviderRecovery(
                route = HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP,
                inference = { throw acceleratorError },
                retryOnCpu = { throw cpuError },
                recordFailure = {},
                recordSuccess = {},
            )
        }

        thrown shouldBe cpuError
        thrown.suppressed.toList() shouldBe listOf(acceleratorError)
    }

    private fun qnnFailure() = OrtException(OrtException.OrtErrorCode.ORT_ENGINE_ERROR, "QNN execute failed")

    private fun ortFailure(message: String) = OrtException(OrtException.OrtErrorCode.ORT_FAIL, message)
}
