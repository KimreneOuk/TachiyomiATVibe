package eu.kanade.translation.benchmark

import android.content.Context
import ai.onnxruntime.OrtEnvironment
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.translation.ocr.PaddleOcrV6SmallEngine
import eu.kanade.translation.runtime.onnx.HardwareDiscoveryEngine
import eu.kanade.translation.runtime.onnx.OnnxModelStore
import eu.kanade.translation.runtime.onnx.OnnxRuntimeProvider
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.ceil

class PaddleBenchmarkRunner(private val context: Context) {

    fun run(config: PaddleBenchmarkConfig): PaddleBenchmarkResult {
        val startedAtEpochMs = System.currentTimeMillis()
        val startedAtNanos = System.nanoTime()
        val deviceStart = BenchmarkDeviceMetadata.collectStart(context)
        val powerManager = context.getSystemService(android.os.PowerManager::class.java)
            ?: error("PowerManager is unavailable")
        val pssSampler = PssSampler(powerManager)
        val stageTimer = BenchmarkStageTimer()
        val corpusLoader = BenchmarkCorpusLoader(context)
        val downloadedDiscovery = if (config.includeDownloadedCorpus) {
            BenchmarkDownloadedCorpusLoader(context).discover(config.pageLimit)
        } else {
            BenchmarkDownloadedCorpusLoader.Discovery(emptyList(), "disabled")
        }
        val supplementalDiscovery = corpusLoader.discover(config)
        val allPages = downloadedDiscovery.pages + supplementalDiscovery.pages
        val selectedPages = if (config.pageLimit > 0) {
            allPages.take(config.pageLimit)
        } else {
            allPages
        }
        val engine = PaddleOcrV6SmallEngine()
        var modelPreparationMs = 0.0
        var sessionCreationMs = 0.0
        var pssSummary: PssSummary? = null
        var modelFiles: Map<String, String> = emptyMap()
        val samples = mutableListOf<SampleTiming>()
        var providerLabel = "uninitialized"
        var pagesProcessed = 0
        var coldSessionConsumed = false
        val paritySamples = mutableListOf<PaddleB1ParitySample>()

        pssSampler.start()
        try {
            val prepared = stageTimer.measureTimed("model_preparation") {
                val store = OnnxModelStore(context)
                val rec = store.ensurePaddleOcrV6Small()
                val det = store.ensurePaddleOcrV6Det()
                PreparedModels(rec.recognitionModel, rec.dictionary, det.detectionModel)
            }
            modelPreparationMs = prepared.durationMs
            val paths = prepared.value
            modelFiles = mapOf(
                "paddle_v6_recognition_onnx" to BenchmarkFileHasher.sha256(paths.recognitionModel),
                "paddle_v6_dictionary" to BenchmarkFileHasher.sha256(paths.dictionary),
                "paddle_v6_det_onnx" to BenchmarkFileHasher.sha256(paths.detectionModel),
            )
            sessionCreationMs = stageTimer.measureTimed("session_creation") {
                engine.initialize(paths.recognitionModel, paths.dictionary)
            }.durationMs
            providerLabel = engine.executionProviderLabel

            for (page in selectedPages) {
                corpusLoader.forEachSample(page) { sample ->
                    if (config.sampleLimit > 0 && samples.size >= config.sampleLimit) return@forEachSample
                    val firstRunIsColdSession = !coldSessionConsumed
                    val widthBucket = widthBucketFor(engine, sample.bitmap)
                    val shape = listOf(1L, 3L, PaddleOcrV6SmallEngine.RECOGNITION_HEIGHT.toLong(), widthBucket.toLong())
                    val cold = measureInference(
                        stageTimer = stageTimer,
                        stage = if (firstRunIsColdSession) "cold_inference" else "first_inference",
                    ) {
                        runBlocking { engine.recognizeWithConf(sample.bitmap) }
                    }
                    coldSessionConsumed = true
                    val warm1 = measureInference(stageTimer, "warm_inference_1") {
                        runBlocking { engine.recognizeWithConf(sample.bitmap) }
                    }
                    val warm2 = measureInference(stageTimer, "warm_inference_2") {
                        runBlocking { engine.recognizeWithConf(sample.bitmap) }
                    }
                    if (config.parityMode) {
                        val batch = runBlocking {
                            engine.recognizeBucketBatch(
                                crops = listOf(sample.bitmap),
                                widthBucket = widthBucket,
                                maxBatch = 1,
                            ).single()
                        }
                        val reference = warm2.value
                        val exactText = reference.first == batch.first
                        val exactConfidence = reference.second.toRawBits() == batch.second.toRawBits()
                        paritySamples += PaddleB1ParitySample(
                            sampleId = sample.id,
                            pageId = sample.pageId,
                            regionId = sample.id,
                            leafId = sample.id,
                            stage = "recognizer_b1",
                            widthBucket = widthBucket,
                            inputShape = shape,
                            referenceText = reference.first,
                            batchText = batch.first,
                            referenceConfidence = reference.second,
                            batchConfidence = batch.second,
                            referenceConfidenceBits = reference.second.toRawBits(),
                            batchConfidenceBits = batch.second.toRawBits(),
                            exactText = exactText,
                            exactConfidence = exactConfidence,
                        )
                        check(engine.lastBatchTelemetry?.downgradeReason == null) {
                            "B1 parity batch fallback page=${sample.pageId} region=${sample.id} " +
                                "leaf=${sample.id} stage=recognizer_b1 " +
                                "reason=${engine.lastBatchTelemetry?.downgradeReason}"
                        }
                    }
                    samples += SampleTiming(
                        sampleId = sample.id,
                        pageId = sample.pageId,
                        widthBucket = widthBucket,
                        inputShape = shape,
                        measuredBatchSize = 1,
                        batched = false,
                        coldSession = firstRunIsColdSession,
                        coldInferenceMs = cold.durationMs,
                        warmRun1Ms = warm1.durationMs,
                        warmRun2Ms = warm2.durationMs,
                        confidence = warm2.value.second,
                        text = warm2.value.first,
                    )
                }
                pagesProcessed++
                if (config.sampleLimit > 0 && samples.size >= config.sampleLimit) break
            }
        } finally {
            engine.close()
            pssSummary = pssSampler.stop()
        }

        val finalPss = pssSummary ?: error("PSS sampler did not produce a summary")
        val device = BenchmarkDeviceMetadata.finish(deviceStart, context)
        val provider = ProviderMetadata(
            actualRegisteredProvider = providerLabel,
            requestedRoute = HardwareDiscoveryEngine.activeRoute.name,
            runtimeVersion = OnnxRuntimeProvider.environment.getVersion(),
            availableProviders = OrtEnvironment.getAvailableProviders()
                .map { it.getName() }
                .sorted(),
            providerLibraryDigests = BenchmarkProviderLibraryCollector.collect(context),
            measuredBatchSize = 1,
            batched = config.parityMode,
        )
        val parity = if (config.parityMode) {
            PaddleB1ParityResult(
                mode = "recognizeWithConf_vs_recognizeBucketBatch_B1",
                passed = paritySamples.isNotEmpty() && paritySamples.all {
                    it.exactText && it.exactConfidence
                },
                detectorConfiguration = "crop-only benchmark; detector-present/absent parity is covered by JVM fixtures",
                comparedSamples = paritySamples.size,
                samples = paritySamples.toList(),
            )
        } else {
            null
        }
        if (parity != null && !parity.passed) {
            val mismatches = parity.samples.filterNot { it.exactText && it.exactConfidence }.joinToString("; ") {
                "page=${it.pageId} region=${it.regionId} leaf=${it.leafId} stage=${it.stage} " +
                    "text=${it.referenceText}/${it.batchText} " +
                    "confidenceBits=${it.referenceConfidenceBits}/${it.batchConfidenceBits}"
            }
            error("B1 parity mismatch; B4/B8 and accelerator promotion remain blocked: $mismatches")
        }
        val bucketSummaries = listOf(640, 1600).map { bucket ->
            summarizeBucket(samples.filter { it.widthBucket == bucket }, bucket)
        }
        return PaddleBenchmarkResult(
            schemaVersion = if (config.parityMode) 2 else 1,
            benchmarkName = if (config.parityMode) {
                "paddle_ocr_v6_b1_parity"
            } else {
                "paddle_ocr_v6_small_current_main_b1"
            },
            commit = BuildConfig.COMMIT_SHA,
            startedAtEpochMs = startedAtEpochMs,
            durationMs = elapsedMs(startedAtNanos),
            config = config,
            device = device,
            models = ModelMetadata(modelFiles),
            provider = provider,
            sessionCreationMs = sessionCreationMs,
            modelPreparationMs = modelPreparationMs,
            stages = stageTimer.snapshot(),
            samples = samples,
            buckets = bucketSummaries,
            pss = finalPss,
            pagesAvailable = allPages.size,
            pagesProcessed = pagesProcessed,
            downloadedCorpusStatus = downloadedDiscovery.status,
            externalCorpusStatus = supplementalDiscovery.externalStatus,
            parity = parity,
        )
    }

    private fun widthBucketFor(engine: PaddleOcrV6SmallEngine, bitmap: android.graphics.Bitmap): Int {
        val safeHeight = bitmap.height.coerceAtLeast(1)
        val scaledWidth = ceil(bitmap.width * (PaddleOcrV6SmallEngine.RECOGNITION_HEIGHT.toFloat() / safeHeight))
            .toInt()
            .coerceIn(1, PaddleOcrV6SmallEngine.MAX_RECOGNITION_WIDTH)
        return engine.alignWidth(scaledWidth)
    }

    private fun <T> measureInference(
        stageTimer: BenchmarkStageTimer,
        stage: String,
        block: () -> T,
    ): Measured<T> {
        val measured = stageTimer.measureTimed(stage, block)
        return Measured(measured.value, measured.durationMs)
    }

    private fun summarizeBucket(values: List<SampleTiming>, bucket: Int): BucketSummary {
        val warm1 = values.map { it.warmRun1Ms }
        val warm2 = values.map { it.warmRun2Ms }
        val cold = values.map { it.coldInferenceMs }
        val all = warm1 + warm2
        return BucketSummary(
            widthBucket = bucket,
            sampleCount = values.size,
            measuredBatchSizes = values.map { it.measuredBatchSize }.distinct().sorted(),
            batchedValues = values.map { it.batched }.distinct(),
            coldInferenceP50Ms = percentileOrNull(cold, 0.50),
            warmRun1P50Ms = percentileOrNull(warm1, 0.50),
            warmRun1P95Ms = percentileOrNull(warm1, 0.95),
            warmRun2P50Ms = percentileOrNull(warm2, 0.50),
            warmRun2P95Ms = percentileOrNull(warm2, 0.95),
            minMs = all.minOrNull(),
            maxMs = all.maxOrNull(),
        )
    }

    private fun percentileOrNull(values: List<Double>, percentile: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * percentile).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun elapsedMs(startedNanos: Long): Double = (System.nanoTime() - startedNanos) / 1_000_000.0

    private data class PreparedModels(
        val recognitionModel: File,
        val dictionary: File,
        val detectionModel: File,
    )

    private data class Measured<T>(
        val value: T,
        val durationMs: Double,
    )
}
