package eu.kanade.translation.engines.inpainting

import eu.kanade.translation.engines.inpainting.aot.AotExecutionCoordinator
import eu.kanade.translation.engines.runtime.onnx.HardwareDiscoveryEngine
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class InpaintingHardwareOverrideTest {

    @Test
    fun `reader choices map to explicit ONNX routes`() {
        InpaintingHardwareOverride.CPU.toHardwareRoute() shouldBe HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK
        InpaintingHardwareOverride.GPU.toHardwareRoute() shouldBe HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_GPU
        InpaintingHardwareOverride.NPU.toHardwareRoute() shouldBe HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP
    }

    @Test
    fun `AOT backend maps NPU to HTP and GPU to QNN GPU without automatic route`() {
        InpaintingHardwareOverride.CPU.toAotBackend() shouldBe null
        InpaintingHardwareOverride.GPU.toAotBackend() shouldBe AotExecutionCoordinator.Backend.QNN_GPU
        InpaintingHardwareOverride.NPU.toAotBackend() shouldBe AotExecutionCoordinator.Backend.QNN_HTP
    }
}
