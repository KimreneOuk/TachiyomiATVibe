package eu.kanade.translation.engines.runtime.onnx

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.PaddleOcrExecutionProvider
import tachiyomi.domain.translation.TranslationPreferences

class PaddleOcrSessionFactoryTest {

    @BeforeEach
    @AfterEach
    fun resetHardwareDiscovery() {
        HardwareDiscoveryEngine.resetForTesting()
        PaddleOcrSessionFactory.resetForTesting()
    }

    @Test
    fun `provider choices map to stable UI and wire labels`() {
        PaddleOcrExecutionProvider.entries.map { it.wireLabel } shouldBe listOf(
            "cpu",
            "qnn_gpu",
            "qnn_htp",
        )
        PaddleOcrExecutionProvider.entries shouldBe listOf(
            PaddleOcrExecutionProvider.CPU,
            PaddleOcrExecutionProvider.QUALCOMM_QNN_GPU,
            PaddleOcrExecutionProvider.QUALCOMM_QNN_HTP,
        )
    }

    @Test
    fun `dedicated Paddle preference defaults to CPU`() {
        TranslationPreferences(InMemoryPreferenceStore())
            .paddleOcrExecutionProvider()
            .get() shouldBe PaddleOcrExecutionProvider.CPU
    }

    @Test
    fun `CPU is the default resolution and skips probing`() {
        var htpProbes = 0
        var gpuProbes = 0
        HardwareDiscoveryEngine.resetForTesting(
            customQnnProbe = {
                htpProbes++
                true
            },
            customGpuProbe = {
                gpuProbes++
                true
            },
        )

        val resolution = PaddleOcrSessionFactory.resolve(PaddleOcrExecutionProvider.CPU)

        resolution.requestedWireLabel shouldBe "cpu"
        resolution.resolvedRouteLabel shouldBe "cpu"
        resolution.fallbackReason shouldBe null
        htpProbes shouldBe 0
        gpuProbes shouldBe 0
    }

    @Test
    fun `successful GPU probe resolves the shared paired route`() {
        var gpuProbes = 0
        HardwareDiscoveryEngine.resetForTesting(
            customGpuProbe = {
                gpuProbes++
                true
            },
        )

        val resolution = PaddleOcrSessionFactory.resolve(
            PaddleOcrExecutionProvider.QUALCOMM_QNN_GPU,
        )

        resolution.requestedWireLabel shouldBe "qnn_gpu"
        resolution.resolvedRouteLabel shouldBe "qnn_gpu"
        resolution.fallbackReason shouldBe null
        gpuProbes shouldBe 1
    }

    @Test
    fun `failed HTP probe resolves explicitly to CPU with reason`() {
        var htpProbes = 0
        HardwareDiscoveryEngine.resetForTesting(
            customQnnProbe = {
                htpProbes++
                false
            },
        )

        val first = PaddleOcrSessionFactory.resolve(
            PaddleOcrExecutionProvider.QUALCOMM_QNN_HTP,
        )
        val second = PaddleOcrSessionFactory.resolve(
            PaddleOcrExecutionProvider.QUALCOMM_QNN_HTP,
        )

        first shouldBe second
        first.resolvedRouteLabel shouldBe "cpu"
        first.requestedWireLabel shouldBe "qnn_htp"
        first.fallbackReason shouldBe "qnn_htp_probe_failed"
        htpProbes shouldBe 1
    }
}
