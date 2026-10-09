package eu.kanade.translation.engines.vision.ocr.paddle.batch

import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.translation.engines.runtime.onnx.PaddleOcrProviderTarget
import tachiyomi.domain.translation.PaddleOcrExecutionProvider
import tachiyomi.domain.translation.PaddleOcrRecognitionBatch
import tachiyomi.domain.translation.TranslationPreferences

/** Build-variant bridge for the staged flag; the actual gate stays pure/testable. */
object PaddleOcrBatchActivationPolicy {

    fun currentFromPreferences(
        preferences: TranslationPreferences,
        requestedProvider: PaddleOcrExecutionProvider?,
    ): PaddleOcrBatchActivation {
        val preference = preferences.paddleOcrRecognitionBatch().get()
        return current(
            requestedBatchSize = when (preference) {
                PaddleOcrRecognitionBatch.B1 -> PaddleOcrBatchSize.B1
                PaddleOcrRecognitionBatch.B2 -> PaddleOcrBatchSize.B2
                PaddleOcrRecognitionBatch.B4 -> PaddleOcrBatchSize.B4
                PaddleOcrRecognitionBatch.DYNAMIC -> PaddleOcrBatchSize.B8
            },
            requestedProvider = requestedProvider?.toBatchProvider(),
            dynamicPageBatch = preference == PaddleOcrRecognitionBatch.DYNAMIC,
        )
    }

    fun current(
        requestedBatchSize: PaddleOcrBatchSize = PaddleOcrBatchSize.B1,
        profile: PaddleOcrDeviceProfile = PaddleOcrDeviceProfile.untested(),
        widthBucket: PaddleOcrWidthBucket = PaddleOcrWidthBucket.WIDTH_640,
        thermalSeverity: Int = 0,
        requestedProvider: PaddleOcrProviderTarget? = null,
        dynamicPageBatch: Boolean = false,
    ): PaddleOcrBatchActivation {
        val buildConfigBatch = when (BuildConfig.PADDLE_BATCHING_REQUESTED_BATCH) {
            8 -> PaddleOcrBatchSize.B8
            4 -> PaddleOcrBatchSize.B4
            2 -> PaddleOcrBatchSize.B2
            else -> PaddleOcrBatchSize.B1
        }
        // The staged cap exists because fixed B8 was never device-validated;
        // dynamic mode carries its own memory-derived ceiling, so it is exempt.
        val requestedBatch = if (BuildConfig.PADDLE_BATCHING_STAGED && !dynamicPageBatch) {
            requestedBatchSize.capAt(buildConfigBatch)
        } else {
            requestedBatchSize
        }
        return PaddleOcrDevicePolicy.resolveActivation(
            stagedEnabled = BuildConfig.PADDLE_BATCHING_STAGED,
            requestedBatchSize = requestedBatch,
            requestedProvider = requestedProvider,
            profile = profile,
            widthBucket = widthBucket,
            thermalSeverity = thermalSeverity,
            debugProvisionalOptIn = BuildConfig.DEBUG && requestedBatchSize != PaddleOcrBatchSize.B1,
        ).copy(dynamicPageBatch = dynamicPageBatch)
    }

    private fun PaddleOcrBatchSize.capAt(cap: PaddleOcrBatchSize): PaddleOcrBatchSize =
        if (value <= cap.value) this else cap

    private fun PaddleOcrExecutionProvider.toBatchProvider(): PaddleOcrProviderTarget = when (this) {
        PaddleOcrExecutionProvider.CPU -> PaddleOcrProviderTarget.CPU
        PaddleOcrExecutionProvider.QUALCOMM_QNN_GPU -> PaddleOcrProviderTarget.QNN_GPU
        PaddleOcrExecutionProvider.QUALCOMM_QNN_HTP -> PaddleOcrProviderTarget.QNN_HTP
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
