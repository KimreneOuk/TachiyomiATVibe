package eu.kanade.translation.ocr.paddle.batch

import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.translation.runtime.onnx.PaddleOcrProviderTarget

/** Build-variant bridge for the staged flag; the actual gate stays pure/testable. */
object PaddleOcrBatchActivationPolicy {

    fun current(
        profile: PaddleOcrDeviceProfile = PaddleOcrDeviceProfile.untested(),
        widthBucket: PaddleOcrWidthBucket = PaddleOcrWidthBucket.WIDTH_640,
        thermalSeverity: Int = 0,
        requestedProvider: PaddleOcrProviderTarget? = null,
    ): PaddleOcrBatchActivation {
        val requestedBatch = when (BuildConfig.PADDLE_BATCHING_REQUESTED_BATCH) {
            8 -> PaddleOcrBatchSize.B8
            4 -> PaddleOcrBatchSize.B4
            2 -> PaddleOcrBatchSize.B2
            else -> PaddleOcrBatchSize.B1
        }
        return PaddleOcrDevicePolicy.resolveActivation(
            stagedEnabled = BuildConfig.PADDLE_BATCHING_STAGED,
            requestedBatchSize = requestedBatch,
            requestedProvider = requestedProvider,
            profile = profile,
            widthBucket = widthBucket,
            thermalSeverity = thermalSeverity,
        )
    }

    /**
     * The only safe activation when a staged cell fails its device gate.
     * Callers pass this configuration to the provider seam; the recognizer
     * itself does not choose or hide a provider fallback.
     */
    fun emergencyCpuB1(): PaddleOcrBatchActivation = PaddleOcrBatchActivation(
        activeBatchSize = PaddleOcrBatchSize.B1,
        providerTarget = PaddleOcrProviderTarget.CPU,
        forceCpuB1EmergencyFallback = true,
        reason = "explicit_cpu_b1_emergency_fallback",
    )
}
