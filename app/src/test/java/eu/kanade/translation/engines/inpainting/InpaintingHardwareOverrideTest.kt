package eu.kanade.translation.engines.inpainting

import eu.kanade.translation.engines.inpainting.aot.AotExecutionCoordinator
import eu.kanade.translation.engines.runtime.onnx.HardwareDiscoveryEngine
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.NeuralInpaintModel

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

    @Test
    fun `LaMa models block accelerator routes while AOT keeps the selected route`() {
        val lamaModels = listOf(
            NeuralInpaintModel.LAMA_MANGA,
            NeuralInpaintModel.LAMA_MANGA_FP16,
            NeuralInpaintModel.LAMA_512_INT8,
            NeuralInpaintModel.LAMA_512_FP16,
        )

        lamaModels.forEach { model ->
            listOf(InpaintingHardwareOverride.GPU, InpaintingHardwareOverride.NPU).forEach { override ->
                val resolution = resolveInpaintingHardwareRoute(model, override)
                resolution.route shouldBe HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK
                resolution.blockedReason shouldBe "lama_qnn_compile_unsafe"
            }
        }

        val aotNpu = resolveInpaintingHardwareRoute(NeuralInpaintModel.AOT_GAN, InpaintingHardwareOverride.NPU)
        aotNpu.route shouldBe HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP
        aotNpu.blockedReason shouldBe null
        InpaintingHardwareOverride.NPU.toAotBackend() shouldBe AotExecutionCoordinator.Backend.QNN_HTP
    }
}
