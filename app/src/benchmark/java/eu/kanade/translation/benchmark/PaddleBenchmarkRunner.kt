package eu.kanade.translation.benchmark

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.graphics.Bitmap
import android.os.Debug
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.translation.ocr.PaddleOcrV6BatchTelemetry
import eu.kanade.translation.ocr.PaddleOcrV6SmallEngine
import eu.kanade.translation.ocr.paddle.batch.PaddleOcrRollingP95Config
import eu.kanade.translation.ocr.paddle.batch.PaddleOcrRollingP95HysteresisDowngradePolicy
import eu.kanade.translation.ocr.paddle.batch.PaddleOcrWidthBucket
import eu.kanade.translation.runtime.onnx.HardwareDiscoveryEngine
import eu.kanade.translation.runtime.onnx.ModelRoutingEngine
import eu.kanade.translation.runtime.onnx.OnnxModelStore
import eu.kanade.translation.runtime.onnx.OnnxRuntimeProvider
import eu.kanade.translation.runtime.onnx.PaddleOcrProviderTarget
import eu.kanade.translation.runtime.onnx.PaddleOcrProviderTestConfiguration
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
        var matrix: PaddleBenchmarkMatrixResult? = null

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
            if (config.matrixMode) {
                val matrixRun = runMatrix(
                    config = config,
                    prepared = paths,
                    pages = selectedPages,
                    corpusLoader = corpusLoader,
                    engine = engine,
                    powerManager = powerManager,
                    stageTimer = stageTimer,
                )
                matrix = matrixRun.result
                sessionCreationMs = matrixRun.sessionCreationMs
                providerLabel = "per_cell"
                pagesProcessed = selectedPages.size
            } else {
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
            }
        } finally {
            engine.close()
            pssSummary = pssSampler.stop()
        }

        val finalPss = pssSummary ?: error("PSS sampler did not produce a summary")
        val device = BenchmarkDeviceMetadata.finish(deviceStart, context)
        val provider = ProviderMetadata(
            actualRegisteredProvider = if (matrix == null) providerLabel else "per_cell",
            requestedRoute = HardwareDiscoveryEngine.activeRoute.name,
            runtimeVersion = OnnxRuntimeProvider.environment.getVersion(),
            availableProviders = OrtEnvironment.getAvailableProviders()
                .map { it.getName() }
                .sorted(),
            providerLibraryDigests = BenchmarkProviderLibraryCollector.collect(context),
            measuredBatchSize = if (matrix == null) 1 else 0,
            batched = config.parityMode || matrix != null,
        )
        val parity = if (config.parityMode) {
            PaddleB1ParityResult(
                mode = "recognizeWithConf_vs_recognizeBucketBatch_B1",
                passed = paritySamples.isNotEmpty() &&
                    paritySamples.all {
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
            schemaVersion = when {
                matrix != null -> 3
                config.parityMode -> 2
                else -> 1
            },
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
            matrix = matrix,
        )
    }

    private fun runMatrix(
        config: PaddleBenchmarkConfig,
        prepared: PreparedModels,
        pages: List<BenchmarkPage>,
        corpusLoader: BenchmarkCorpusLoader,
        engine: PaddleOcrV6SmallEngine,
        powerManager: android.os.PowerManager,
        stageTimer: BenchmarkStageTimer,
    ): MatrixRun {
        val representatives = collectRepresentatives(corpusLoader, pages, engine)
        val cells = ArrayList<PaddleBenchmarkMatrixCellResult>(PaddleBenchmarkMatrix.specs.size)
        var sessionCreationMs = 0.0
        for (spec in PaddleBenchmarkMatrix.specs) {
            val representative = representatives[spec.widthBucket]
            if (representative == null) {
                cells += untestedCell(spec, "no representative crop for width bucket")
                continue
            }
            val providerConfiguration = PaddleOcrProviderTestConfiguration(spec.provider)
            val startedPss = currentPssKb()
            val thermalStart = ThermalStatus.describe(powerManager)
            val timings = ArrayList<Double>(config.matrixIterations)
            val actualBatchSizes = ArrayList<Int>()
            val downgradeReasons = ArrayList<String>()
            val rollingActions = ArrayList<String>()
            var actualProvider = "uninitialized"
            var lastTelemetry: PaddleOcrV6BatchTelemetry? = null
            var peakInputBytes = 0L
            var peakOutputBytes = 0L
            var provenanceAfterInference = false
            var noCpuFallbackObserved = false
            var errorMessage: String? = null
            var evidence = "FAILED"
            try {
                sessionCreationMs += stageTimer.measureTimed(
                    "matrix_session_${spec.provider.name.lowercase()}_${spec.batchSize.value}_${spec.widthBucket.paddedWidth}",
                ) {
                    engine.initialize(
                        modelFile = prepared.recognitionModel,
                        dictionaryFile = prepared.dictionary,
                        strictProviderMode = spec.provider != PaddleOcrProviderTarget.CPU,
                        providerConfiguration = providerConfiguration,
                    )
                }.durationMs
                actualProvider = engine.executionProviderLabel
                val p95Policy = PaddleOcrRollingP95HysteresisDowngradePolicy(
                    initialBatchSize = spec.batchSize,
                    config = PaddleOcrRollingP95Config(
                        windowSize = config.matrixIterations.coerceAtLeast(3),
                    ),
                )
                repeat(config.matrixIterations) { iteration ->
                    val crops = List(spec.batchSize.value) { representative }
                    val measured = stageTimer.measureTimed(
                        "matrix_inference_${spec.provider.name.lowercase()}_${spec.batchSize.value}_${spec.widthBucket.paddedWidth}",
                    ) {
                        runBlocking {
                            engine.recognizeBucketBatch(
                                crops = crops,
                                widthBucket = spec.widthBucket.paddedWidth,
                                maxBatch = spec.batchSize.value,
                            )
                        }
                    }
                    timings += measured.durationMs
                    lastTelemetry = engine.lastBatchTelemetry
                    lastTelemetry?.let { telemetry ->
                        peakInputBytes = maxOf(peakInputBytes, telemetry.peakInputBytes)
                        peakOutputBytes = maxOf(peakOutputBytes, telemetry.peakOutputBytes)
                    }
                    lastTelemetry?.actualBatchSizes?.let(actualBatchSizes::addAll)
                    lastTelemetry?.downgradeReasons?.let(downgradeReasons::addAll)
                    val decision = p95Policy.record(measured.durationMs)
                    rollingActions += "${decision.action}:${decision.activeBatchSize.value}:${decision.reason}"
                    if (spec.provider != PaddleOcrProviderTarget.CPU &&
                        (lastTelemetry?.downgradeReason != null || actualProvider.isCpuLikeProvider())
                    ) {
                        error(
                            "strict cell downgraded: provider=$actualProvider " +
                                "reason=${lastTelemetry?.downgradeReason} iteration=$iteration",
                        )
                    }
                }
                noCpuFallbackObserved = spec.provider == PaddleOcrProviderTarget.CPU ||
                    (!actualProvider.isCpuLikeProvider() && downgradeReasons.isEmpty())
                if (spec.provider != PaddleOcrProviderTarget.CPU) {
                    ModelRoutingEngine.recordSuccessfulInference(
                        prepared.recognitionModel.absolutePath,
                        spec.provider.route,
                    )
                    provenanceAfterInference = true
                }
                evidence = if (spec.provider == PaddleOcrProviderTarget.CPU ||
                    (provenanceAfterInference && noCpuFallbackObserved)
                ) {
                    "CONFIRMED"
                } else {
                    "FAILED"
                }
            } catch (failure: Throwable) {
                errorMessage = "${failure::class.java.simpleName}: ${failure.message}"
            } finally {
                engine.close()
            }
            val thermalEnd = ThermalStatus.describe(powerManager)
            cells += PaddleBenchmarkMatrixCellResult(
                provider = spec.provider,
                requestedBatchSize = spec.batchSize,
                widthBucket = spec.widthBucket,
                evidence = evidence,
                actualRegisteredProvider = actualProvider,
                strictNoCpuFallback = providerConfiguration.strictNoCpuFallback,
                provenanceAfterInference = provenanceAfterInference,
                noCpuFallbackObserved = noCpuFallbackObserved,
                downgradeReason = lastTelemetry?.downgradeReason,
                downgradeReasons = downgradeReasons.distinct(),
                measuredBatchSizes = actualBatchSizes,
                peakInputBytes = peakInputBytes.takeIf { timings.isNotEmpty() },
                peakOutputBytes = peakOutputBytes.takeIf { timings.isNotEmpty() },
                p50Ms = percentileOrNull(timings, 0.50),
                p95Ms = percentileOrNull(timings, 0.95),
                pssDeltaKb = if (timings.isEmpty()) null else currentPssKb() - startedPss,
                thermalStatusAtStart = thermalStart,
                thermalStatusAtEnd = thermalEnd,
                rollingP95Actions = rollingActions,
                error = errorMessage,
            )
        }
        representatives.values.forEach { if (!it.isRecycled) it.recycle() }
        return MatrixRun(
            result = PaddleBenchmarkMatrixResult(
                cells = cells,
                deviceProfileEvidence = when {
                    cells.all { it.evidence == "CONFIRMED" } -> "CONFIRMED"
                    cells.any { it.evidence == "FAILED" } -> "FAILED"
                    else -> "UNTESTED"
                },
            ),
            sessionCreationMs = sessionCreationMs,
        )
    }

    private fun collectRepresentatives(
        corpusLoader: BenchmarkCorpusLoader,
        pages: List<BenchmarkPage>,
        engine: PaddleOcrV6SmallEngine,
    ): Map<PaddleOcrWidthBucket, Bitmap> {
        val representatives = linkedMapOf<PaddleOcrWidthBucket, Bitmap>()
        for (page in pages) {
            corpusLoader.forEachSample(page) { sample ->
                val bucket = PaddleOcrWidthBucket.forScaledWidth(widthBucketFor(engine, sample.bitmap))
                if (bucket !in representatives) {
                    representatives[bucket] = sample.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                }
            }
            if (representatives.size == PaddleOcrWidthBucket.entries.size) break
        }
        return representatives
    }

    private fun untestedCell(
        spec: PaddleBenchmarkMatrixCellSpec,
        reason: String,
    ): PaddleBenchmarkMatrixCellResult = PaddleBenchmarkMatrixCellResult(
        provider = spec.provider,
        requestedBatchSize = spec.batchSize,
        widthBucket = spec.widthBucket,
        evidence = "UNTESTED",
        actualRegisteredProvider = "unavailable",
        strictNoCpuFallback = spec.provider != PaddleOcrProviderTarget.CPU,
        provenanceAfterInference = false,
        noCpuFallbackObserved = false,
        downgradeReason = null,
        downgradeReasons = emptyList(),
        measuredBatchSizes = emptyList(),
        peakInputBytes = null,
        peakOutputBytes = null,
        p50Ms = null,
        p95Ms = null,
        pssDeltaKb = null,
        thermalStatusAtStart = "UNTESTED",
        thermalStatusAtEnd = "UNTESTED",
        rollingP95Actions = emptyList(),
        error = reason,
    )

    private fun currentPssKb(): Int {
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        return memoryInfo.totalPss
    }

    private fun String.isCpuLikeProvider(): Boolean =
        equals("cpu", ignoreCase = true) || equals("xnnpack", ignoreCase = true) || isBlank()

    private data class MatrixRun(
        val result: PaddleBenchmarkMatrixResult,
        val sessionCreationMs: Double,
    )

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
