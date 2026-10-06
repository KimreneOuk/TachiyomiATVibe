package eu.kanade.translation.benchmark

import eu.kanade.translation.engines.runtime.onnx.PaddleOcrProviderTarget
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrBatchSize
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrWidthBucket

enum class PaddleBenchmarkWorkflowMode(val wireLabel: String) {
    AUTO_READER_FOLLOW("auto_reader_follow"),
    BATCH("batch"),
}

data class PaddleBenchmarkMatrixCellSpec(
    val workflowMode: PaddleBenchmarkWorkflowMode,
    val provider: PaddleOcrProviderTarget,
    val batchSize: PaddleOcrBatchSize,
    val widthBucket: PaddleOcrWidthBucket,
)

data class PaddleBenchmarkMatrixCellResult(
    val engine: String,
    val workflowMode: PaddleBenchmarkWorkflowMode,
    val provider: PaddleOcrProviderTarget,
    val requestedBatchSize: PaddleOcrBatchSize?,
    val widthBucket: PaddleOcrWidthBucket?,
    val expectedRegisteredProvider: String,
    val evidence: String,
    val actualRegisteredProvider: String,
    val sessionCreationMs: Double?,
    val pageSamples: List<PaddleBenchmarkMatrixPageSample>,
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

data class PaddleBenchmarkMatrixPageSample(
    val pageId: String,
    val inputWidth: Int,
    val inputHeight: Int,
    val measuredBatchSize: Int,
    val coldSession: Boolean,
    val inferenceMs: Double,
    val outputCount: Int,
)

data class PaddleBenchmarkMatrixResult(
    val cells: List<PaddleBenchmarkMatrixCellResult>,
    val deviceProfileEvidence: String,
)

object PaddleBenchmarkMatrix {
    val specs: List<PaddleBenchmarkMatrixCellSpec> = PaddleBenchmarkWorkflowMode.entries.flatMap { mode ->
        val batchSizes = when (mode) {
            PaddleBenchmarkWorkflowMode.AUTO_READER_FOLLOW -> listOf(PaddleOcrBatchSize.B1)
            PaddleBenchmarkWorkflowMode.BATCH -> PaddleOcrBatchSize.entries
        }
        batchSizes.flatMap { batchSize ->
            PaddleOcrWidthBucket.entries.flatMap { widthBucket ->
                PaddleOcrProviderTarget.entries.map { provider ->
                    PaddleBenchmarkMatrixCellSpec(mode, provider, batchSize, widthBucket)
                }
            }
        }
    }
}
