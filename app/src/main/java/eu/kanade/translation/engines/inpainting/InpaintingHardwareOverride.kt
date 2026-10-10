package eu.kanade.translation.engines.inpainting

import eu.kanade.translation.engines.inpainting.aot.AotExecutionCoordinator
import eu.kanade.translation.engines.runtime.onnx.HardwareDiscoveryEngine

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

internal fun InpaintingHardwareOverride.toAotBackend(): AotExecutionCoordinator.Backend? = when (this) {
    InpaintingHardwareOverride.CPU -> null
    InpaintingHardwareOverride.GPU -> AotExecutionCoordinator.Backend.QNN_GPU
    InpaintingHardwareOverride.NPU -> AotExecutionCoordinator.Backend.QNN_HTP
}
