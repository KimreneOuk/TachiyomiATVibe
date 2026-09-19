package eu.kanade.translation.ocr.paddle.batch

import eu.kanade.translation.runtime.onnx.PaddleOcrProviderTarget

enum class PaddleOcrEvidence {
    CONFIRMED,
    LIKELY,
    UNTESTED,
    FAILED,
}

/** One provider/width/batch cell in the device validation matrix. */
data class PaddleOcrMatrixCombination(
    val provider: PaddleOcrProviderTarget,
    val batchSize: PaddleOcrBatchSize,
    val widthBucket: PaddleOcrWidthBucket,
)

/**
 * Device facts and post-run approvals kept outside the recognizer engine.
 * Empty approvals are intentional: an unknown or stale device profile cannot
 * silently activate B4/B8 in production.
 */
data class PaddleOcrDeviceProfile(
    val manufacturer: String,
    val model: String,
    val device: String,
    val socModel: String,
    val androidApi: Int,
    val primaryAbi: String,
    val totalRamBytes: Long,
    val evidenceByCombination: Map<PaddleOcrMatrixCombination, PaddleOcrEvidence> = emptyMap(),
) {

    fun evidenceFor(combination: PaddleOcrMatrixCombination): PaddleOcrEvidence =
        evidenceByCombination[combination] ?: PaddleOcrEvidence.UNTESTED

    fun isConfirmed(combination: PaddleOcrMatrixCombination): Boolean =
        evidenceFor(combination) == PaddleOcrEvidence.CONFIRMED

    fun confirmedCombinations(): Set<PaddleOcrMatrixCombination> =
        evidenceByCombination.filterValues { it == PaddleOcrEvidence.CONFIRMED }.keys

    companion object {
        fun untested(
            manufacturer: String = "unknown",
            model: String = "unknown",
            device: String = "unknown",
            socModel: String = "unknown",
            androidApi: Int = 0,
            primaryAbi: String = "unknown",
            totalRamBytes: Long = 0L,
        ): PaddleOcrDeviceProfile = PaddleOcrDeviceProfile(
            manufacturer = manufacturer,
            model = model,
            device = device,
            socModel = socModel,
            androidApi = androidApi,
            primaryAbi = primaryAbi,
            totalRamBytes = totalRamBytes,
        )
    }
}
