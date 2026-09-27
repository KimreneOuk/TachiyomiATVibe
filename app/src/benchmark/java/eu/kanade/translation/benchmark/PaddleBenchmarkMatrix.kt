package eu.kanade.translation.benchmark

import eu.kanade.translation.engines.runtime.onnx.PaddleOcrProviderTarget
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrBatchSize
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrWidthBucket

data class PaddleBenchmarkMatrixCellSpec(
    val provider: PaddleOcrProviderTarget,
    val batchSize: PaddleOcrBatchSize,
    val widthBucket: PaddleOcrWidthBucket,
)

data class PaddleBenchmarkMatrixCellResult(
    val provider: PaddleOcrProviderTarget,
    val requestedBatchSize: PaddleOcrBatchSize,
    val widthBucket: PaddleOcrWidthBucket,
    val evidence: String,
    val actualRegisteredProvider: String,
    val strictNoCpuFallback: Boolean,
    val provenanceAfterInference: Boolean,
    val noCpuFallbackObserved: Boolean,
    val downgradeReason: String?,
    val downgradeReasons: List<String>,
    val measuredBatchSizes: List<Int>,
    val peakInputBytes: Long?,
    val peakOutputBytes: Long?,
    val p50Ms: Double?,
    val p95Ms: Double?,
    val pssDeltaKb: Int?,
    val thermalStatusAtStart: String,
    val thermalStatusAtEnd: String,
    val rollingP95Actions: List<String>,
    val error: String? = null,
)

data class PaddleBenchmarkMatrixResult(
    val cells: List<PaddleBenchmarkMatrixCellResult>,
    val deviceProfileEvidence: String,
)

object PaddleBenchmarkMatrix {
    val specs: List<PaddleBenchmarkMatrixCellSpec> = PaddleOcrBatchSize.entries.flatMap { batchSize ->
        PaddleOcrWidthBucket.entries.flatMap { widthBucket ->
            PaddleOcrProviderTarget.entries.map { provider ->
                PaddleBenchmarkMatrixCellSpec(provider, batchSize, widthBucket)
            }
        }
    }
}
