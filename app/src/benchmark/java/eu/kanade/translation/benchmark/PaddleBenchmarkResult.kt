package eu.kanade.translation.benchmark

data class PaddleBenchmarkConfig(
    val corpusRoot: String?,
    val pageLimit: Int,
    val sampleLimit: Int,
    val includeFixtures: Boolean,
    val includeDownloadedCorpus: Boolean = true,
    val includeExternalCorpus: Boolean,
    val outputDirectory: java.io.File,
    val parityMode: Boolean = false,
)

data class BenchmarkPage(
    val id: String,
    val fixture: FixtureSpec? = null,
    val imageFile: java.io.File? = null,
    val cropBoxes: List<CropBox> = emptyList(),
    val imageStreamFactory: (() -> java.io.InputStream)? = null,
    val source: String = "external",
)

data class FixtureSpec(
    val id: String,
    val width: Int,
    val height: Int,
    val background: Int,
    val text: String,
)

data class CropBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

data class BenchmarkSample(
    val id: String,
    val pageId: String,
    val bitmap: android.graphics.Bitmap,
)

data class StageTiming(
    val stage: String,
    val durationMs: Double,
)

data class SampleTiming(
    val sampleId: String,
    val pageId: String,
    val widthBucket: Int,
    val inputShape: List<Long>,
    val measuredBatchSize: Int,
    val batched: Boolean,
    val coldSession: Boolean,
    val coldInferenceMs: Double,
    val warmRun1Ms: Double,
    val warmRun2Ms: Double,
    val confidence: Float,
    val text: String,
)

data class PaddleB1ParitySample(
    val sampleId: String,
    val pageId: String,
    val regionId: String,
    val leafId: String,
    val stage: String,
    val widthBucket: Int,
    val inputShape: List<Long>,
    val referenceText: String,
    val batchText: String,
    val referenceConfidence: Float,
    val batchConfidence: Float,
    val referenceConfidenceBits: Int,
    val batchConfidenceBits: Int,
    val exactText: Boolean,
    val exactConfidence: Boolean,
)

data class PaddleB1ParityResult(
    val mode: String,
    val passed: Boolean,
    val detectorConfiguration: String,
    val comparedSamples: Int,
    val samples: List<PaddleB1ParitySample>,
)

data class BucketSummary(
    val widthBucket: Int,
    val sampleCount: Int,
    val measuredBatchSizes: List<Int>,
    val batchedValues: List<Boolean>,
    val coldInferenceP50Ms: Double?,
    val warmRun1P50Ms: Double?,
    val warmRun1P95Ms: Double?,
    val warmRun2P50Ms: Double?,
    val warmRun2P95Ms: Double?,
    val minMs: Double?,
    val maxMs: Double?,
)

data class DeviceMetadata(
    val manufacturer: String,
    val model: String,
    val device: String,
    val socManufacturer: String,
    val socModel: String,
    val androidApi: Int,
    val release: String,
    val primaryAbi: String,
    val supportedAbis: List<String>,
    val totalRamBytes: Long,
    val memoryClassMb: Int,
    val largeMemoryClassMb: Int,
    val packageName: String,
    val packageVersionName: String,
    val packageVersionCode: Long,
    val thermalStatusAtStart: String,
    val thermalStatusAtEnd: String,
    val thermalStatusMax: String,
)

data class ProviderMetadata(
    val actualRegisteredProvider: String,
    val requestedRoute: String,
    val runtimeVersion: String,
    val availableProviders: List<String>,
    val providerLibraryDigests: Map<String, String>,
    val measuredBatchSize: Int,
    val batched: Boolean,
)

data class ModelMetadata(
    val files: Map<String, String>,
)

data class PssSummary(
    val intervalMs: Long,
    val sampleCount: Int,
    val peakPssKb: Int,
    val peakJavaHeapBytes: Long,
    val firstPssKb: Int?,
    val lastPssKb: Int?,
    val thermalStatusAtPeak: String,
    val samples: List<PssSample>,
)

data class PssSample(
    val elapsedMs: Long,
    val pssKb: Int,
    val javaHeapBytes: Long,
    val thermalStatus: String,
)

data class PaddleBenchmarkResult(
    val schemaVersion: Int,
    val benchmarkName: String,
    val commit: String,
    val startedAtEpochMs: Long,
    val durationMs: Double,
    val config: PaddleBenchmarkConfig,
    val device: DeviceMetadata,
    val models: ModelMetadata,
    val provider: ProviderMetadata,
    val sessionCreationMs: Double,
    val modelPreparationMs: Double,
    val stages: List<StageTiming>,
    val samples: List<SampleTiming>,
    val buckets: List<BucketSummary>,
    val pss: PssSummary,
    val pagesAvailable: Int,
    val pagesProcessed: Int,
    val downloadedCorpusStatus: String,
    val externalCorpusStatus: String,
    val parity: PaddleB1ParityResult? = null,
)
