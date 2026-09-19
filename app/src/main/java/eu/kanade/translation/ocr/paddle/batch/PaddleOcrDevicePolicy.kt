package eu.kanade.translation.ocr.paddle.batch

import eu.kanade.translation.runtime.onnx.PaddleOcrProviderTarget

data class PaddleOcrThermalPolicy(
    val maxAllowedSeverity: Int = 2,
    val recoverySeverity: Int = 1,
) {
    init {
        require(maxAllowedSeverity >= 0)
        require(recoverySeverity >= 0)
        require(recoverySeverity <= maxAllowedSeverity)
    }

    fun blocksAcceleration(thermalSeverity: Int): Boolean = thermalSeverity > maxAllowedSeverity
}

data class PaddleOcrBatchActivation(
    val activeBatchSize: PaddleOcrBatchSize,
    val providerTarget: PaddleOcrProviderTarget?,
    val forceCpuB1EmergencyFallback: Boolean,
    val reason: String,
)

/**
 * Device/profile gate for staged Paddle batching. The recognizer engine does
 * not own this policy or thermal state; it receives only the resulting batch
 * size/provider choice at its integration boundary.
 */
object PaddleOcrDevicePolicy {

    fun resolveActivation(
        stagedEnabled: Boolean,
        requestedBatchSize: PaddleOcrBatchSize,
        requestedProvider: PaddleOcrProviderTarget?,
        profile: PaddleOcrDeviceProfile,
        widthBucket: PaddleOcrWidthBucket = PaddleOcrWidthBucket.WIDTH_640,
        thermalSeverity: Int = 0,
        thermalPolicy: PaddleOcrThermalPolicy = PaddleOcrThermalPolicy(),
        debugProvisionalOptIn: Boolean = false,
    ): PaddleOcrBatchActivation {
        if (requestedBatchSize == PaddleOcrBatchSize.B1) {
            return PaddleOcrBatchActivation(
                activeBatchSize = PaddleOcrBatchSize.B1,
                providerTarget = requestedProvider,
                forceCpuB1EmergencyFallback = false,
                reason = "b1_default",
            )
        }
        if (!stagedEnabled) {
            return emergency("staged_flag_off")
        }
        if (requestedProvider == null) {
            return emergency("provider_not_selected")
        }
        val combination = PaddleOcrMatrixCombination(requestedProvider, requestedBatchSize, widthBucket)
        if (!profile.isConfirmed(combination)) {
            if (debugProvisionalOptIn) {
                if (thermalPolicy.blocksAcceleration(thermalSeverity)) {
                    return emergency("thermal_guard")
                }
                return PaddleOcrBatchActivation(
                    activeBatchSize = requestedBatchSize,
                    providerTarget = requestedProvider,
                    forceCpuB1EmergencyFallback = false,
                    reason = "debug_provisional_optin",
                )
            }
            return emergency("combination_not_confirmed")
        }
        if (thermalPolicy.blocksAcceleration(thermalSeverity)) {
            return emergency("thermal_guard")
        }
        return PaddleOcrBatchActivation(
            activeBatchSize = requestedBatchSize,
            providerTarget = requestedProvider,
            forceCpuB1EmergencyFallback = false,
            reason = "confirmed",
        )
    }

    private fun emergency(reason: String): PaddleOcrBatchActivation = PaddleOcrBatchActivation(
        activeBatchSize = PaddleOcrBatchSize.B1,
        providerTarget = PaddleOcrProviderTarget.CPU,
        forceCpuB1EmergencyFallback = true,
        reason = reason,
    )
}
