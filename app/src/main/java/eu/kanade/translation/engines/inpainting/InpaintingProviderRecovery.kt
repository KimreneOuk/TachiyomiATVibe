package eu.kanade.translation.engines.inpainting

import ai.onnxruntime.OrtException
import eu.kanade.translation.engines.runtime.onnx.HardwareDiscoveryEngine

/** Runs one CPU retry after an accelerated inpainting provider fails during inference. */
internal inline fun <T> runWithInpaintingProviderRecovery(
    route: HardwareDiscoveryEngine.HardwareRoute,
    inference: () -> T,
    retryOnCpu: (OrtException) -> T,
    recordFailure: (OrtException) -> Unit,
    recordSuccess: () -> Unit,
): T {
    var acceleratedSucceeded = false
    val result = try {
        inference().also { acceleratedSucceeded = true }
    } catch (providerError: OrtException) {
        if (route == HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK) throw providerError

        recordFailure(providerError)
        try {
            retryOnCpu(providerError)
        } catch (cpuError: Throwable) {
            if (cpuError !== providerError) cpuError.addSuppressed(providerError)
            throw cpuError
        }
    }
    if (acceleratedSucceeded && route != HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK) recordSuccess()
    return result
}
