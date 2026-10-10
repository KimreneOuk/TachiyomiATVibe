package eu.kanade.translation.engines.inpainting

import eu.kanade.translation.engines.inpainting.aot.AotExecutionCoordinator
import eu.kanade.translation.engines.runtime.onnx.HardwareDiscoveryEngine
import tachiyomi.domain.translation.NeuralInpaintModel

/** Reader-session-only execution provider selection for neural inpainting. */
enum class InpaintingHardwareOverride {
    CPU,
    GPU,
    NPU,
}

internal fun InpaintingHardwareOverride.toHardwareRoute(): HardwareDiscoveryEngine.HardwareRoute = when (this) {
    InpaintingHardwareOverride.CPU -> HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK
    InpaintingHardwareOverride.GPU -> HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_GPU
    InpaintingHardwareOverride.NPU -> HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP
}

internal data class InpaintingHardwareRouteResolution(
    val route: HardwareDiscoveryEngine.HardwareRoute,
    val blockedReason: String? = null,
)

internal fun resolveInpaintingHardwareRoute(
    neuralModel: NeuralInpaintModel,
    hardwareOverride: InpaintingHardwareOverride,
): InpaintingHardwareRouteResolution {
    val acceleratorBlocked = neuralModel != NeuralInpaintModel.AOT_GAN && hardwareOverride != InpaintingHardwareOverride.CPU
    return InpaintingHardwareRouteResolution(
        route = if (acceleratorBlocked) {
            HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK
        } else {
            hardwareOverride.toHardwareRoute()
        },
        blockedReason = if (acceleratorBlocked) "lama_qnn_compile_unsafe" else null,
    )
}

internal fun InpaintingHardwareOverride.toAotBackend(): AotExecutionCoordinator.Backend? = when (this) {
    InpaintingHardwareOverride.CPU -> null
    InpaintingHardwareOverride.GPU -> AotExecutionCoordinator.Backend.QNN_GPU
    InpaintingHardwareOverride.NPU -> AotExecutionCoordinator.Backend.QNN_HTP
}
