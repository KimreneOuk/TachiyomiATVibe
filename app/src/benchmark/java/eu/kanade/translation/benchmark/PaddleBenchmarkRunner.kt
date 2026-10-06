package eu.kanade.translation.benchmark

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.graphics.Bitmap
import android.os.Debug
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.translation.engines.runtime.onnx.HardwareDiscoveryEngine
import eu.kanade.translation.engines.runtime.onnx.ModelRoutingEngine
import eu.kanade.translation.engines.runtime.onnx.OnnxModelStore
import eu.kanade.translation.engines.runtime.onnx.OnnxRuntimeProvider
import eu.kanade.translation.engines.runtime.onnx.PaddleOcrProviderOverride
import eu.kanade.translation.engines.vision.ocr.PaddleOcrV6BatchTelemetry
import eu.kanade.translation.engines.vision.ocr.PaddleOcrV6DetEngine
import eu.kanade.translation.engines.vision.ocr.PaddleOcrV6SmallEngine
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrRollingP95Config
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrRollingP95HysteresisDowngradePolicy
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrWidthBucket
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
                matrix != null -> 4
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
        val cells = ArrayList<PaddleBenchmarkMatrixCellResult>(PaddleBenchmarkMatrix.specs.size + 2)
        var sessionCreationMs = 0.0
        for (spec in PaddleBenchmarkMatrix.specs) {
            val representative = representatives[spec.widthBucket]
            if (representative == null) {
                cells += untestedCell(spec, "no representative crop for width bucket")
                continue
            }
            val providerConfiguration = PaddleOcrProviderOverride(spec.provider)
            val expectedRegisteredProvider = spec.provider.expectedRegisteredProviderLabel
            val startedPss = currentPssKb()
            val thermalStart = ThermalStatus.describe(powerManager)
            val timings = ArrayList<Double>(config.matrixIterations)
            val pageSamples = ArrayList<PaddleBenchmarkMatrixPageSample>(config.matrixIterations)
            val actualBatchSizes = ArrayList<Int>()
            val downgradeReasons = ArrayList<String>()
            val rollingActions = ArrayList<String>()
            var actualProvider = "uninitialized"
            var cellSessionCreationMs: Double? = null
            var lastTelemetry: PaddleOcrV6BatchTelemetry? = null
            var peakInputBytes = 0L
            var peakOutputBytes = 0L
            var provenanceAfterInference = false
            var noCpuFallbackObserved = false
            var errorMessage: String? = null
            var evidence = "FAILED"
            try {
                val sessionCreation = stageTimer.measureTimed(
                    "matrix_session_${spec.workflowMode.wireLabel}_${spec.provider.name.lowercase()}_" +
                        "${spec.batchSize.value}_${spec.widthBucket.paddedWidth}",
                ) {
                    engine.initialize(
                        modelFile = prepared.recognitionModel,
                        dictionaryFile = prepared.dictionary,
                        strictProviderMode = spec.provider.isAccelerator,
                        providerConfiguration = providerConfiguration,
                    )
                }
                cellSessionCreationMs = sessionCreation.durationMs
                sessionCreationMs += sessionCreation.durationMs
                actualProvider = engine.executionProviderLabel
                check(actualProvider.equals(expectedRegisteredProvider, ignoreCase = true)) {
                    "registered provider mismatch: requested=${spec.provider} expected=$expectedRegisteredProvider " +
                        "actual=$actualProvider"
                }
                val p95Policy = PaddleOcrRollingP95HysteresisDowngradePolicy(
                    initialBatchSize = spec.batchSize,
                    config = PaddleOcrRollingP95Config(
                        windowSize = config.matrixIterations.coerceAtLeast(3),
                    ),
                )
                repeat(config.matrixIterations) { iteration ->
                    val crops = List(spec.batchSize.value) { representative.bitmap }
                    val measured = stageTimer.measureTimed(
                        "matrix_inference_${spec.workflowMode.wireLabel}_${spec.provider.name.lowercase()}_" +
                            "${spec.batchSize.value}_${spec.widthBucket.paddedWidth}",
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
                    pageSamples += PaddleBenchmarkMatrixPageSample(
                        pageId = representative.pageId,
                        inputWidth = representative.bitmap.width,
                        inputHeight = representative.bitmap.height,
                        measuredBatchSize = crops.size,
                        coldSession = iteration == 0,
                        inferenceMs = measured.durationMs,
                        outputCount = measured.value.size,
                    )
                    lastTelemetry = engine.lastBatchTelemetry
                    lastTelemetry?.let { telemetry ->
                        peakInputBytes = maxOf(peakInputBytes, telemetry.peakInputBytes)
                        peakOutputBytes = maxOf(peakOutputBytes, telemetry.peakOutputBytes)
                    }
                    lastTelemetry?.actualBatchSizes?.let(actualBatchSizes::addAll)
                    lastTelemetry?.downgradeReasons?.let(downgradeReasons::addAll)
                    val decision = p95Policy.record(measured.durationMs)
                    rollingActions += "${decision.action}:${decision.activeBatchSize.value}:${decision.reason}"
                    if (spec.provider.isAccelerator &&
                        (lastTelemetry?.downgradeReason != null || actualProvider.isCpuLikeProvider())
                    ) {
                        error(
                            "strict cell downgraded: provider=$actualProvider " +
                                "reason=${lastTelemetry?.downgradeReason} iteration=$iteration",
                        )
                    }
                }
                noCpuFallbackObserved = spec.provider.isCpu ||
                    (!actualProvider.isCpuLikeProvider() && downgradeReasons.isEmpty())
                if (spec.provider.isAccelerator) {
                    ModelRoutingEngine.recordSuccessfulInference(
                        prepared.recognitionModel.absolutePath,
                        spec.provider.route,
                    )
                    provenanceAfterInference = true
                }
                evidence = if (spec.provider.isCpu ||
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
                engine = "recognizer",
                workflowMode = spec.workflowMode,
                provider = spec.provider,
                requestedBatchSize = spec.batchSize,
                widthBucket = spec.widthBucket,
                expectedRegisteredProvider = expectedRegisteredProvider,
                evidence = evidence,
                actualRegisteredProvider = actualProvider,
                sessionCreationMs = cellSessionCreationMs,
                pageSamples = pageSamples,
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
        cells += runCpuDetectorCells(
            prepared = prepared,
            pages = pages,
            corpusLoader = corpusLoader,
            powerManager = powerManager,
            stageTimer = stageTimer,
        )
        representatives.values.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        return MatrixRun(
            result = PaddleBenchmarkMatrixResult(
                cells = cells,
                deviceProfileEvidence = when {
                    cells.any { it.evidence == "FAILED" } -> "FAILED"
                    cells.isNotEmpty() && cells.all { it.evidence == "CONFIRMED" } -> "CONFIRMED"
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
    ): Map<PaddleOcrWidthBucket, RepresentativeCrop> {
        val representatives = linkedMapOf<PaddleOcrWidthBucket, RepresentativeCrop>()
        for (page in pages) {
            corpusLoader.forEachSample(page) { sample ->
                val bucket = PaddleOcrWidthBucket.forScaledWidth(widthBucketFor(engine, sample.bitmap))
                if (bucket !in representatives) {
                    representatives[bucket] = RepresentativeCrop(
                        pageId = sample.pageId,
                        bitmap = sample.bitmap.copy(Bitmap.Config.ARGB_8888, false),
                    )
                }
            }
            if (representatives.size == PaddleOcrWidthBucket.entries.size) break
        }
        return representatives
    }

    private fun runCpuDetectorCells(
        prepared: PreparedModels,
        pages: List<BenchmarkPage>,
        corpusLoader: BenchmarkCorpusLoader,
        powerManager: android.os.PowerManager,
        stageTimer: BenchmarkStageTimer,
    ): List<PaddleBenchmarkMatrixCellResult> =
        PaddleOcrProviderOverride.matrixTargets.filter { it.isCpu }.flatMap { target ->
            PaddleBenchmarkWorkflowMode.entries.map { workflowMode ->
                val providerConfiguration = PaddleOcrProviderOverride(target)
                val expectedRegisteredProvider = target.expectedRegisteredProviderLabel
                val detector = PaddleOcrV6DetEngine()
                val pageSamples = ArrayList<PaddleBenchmarkMatrixPageSample>(pages.size)
                val timings = ArrayList<Double>(pages.size)
                val startedPss = currentPssKb()
                val thermalStart = ThermalStatus.describe(powerManager)
                var actualProvider = "uninitialized"
                var sessionCreationMs: Double? = null
                var errorMessage: String? = null
                var evidence = "FAILED"
                try {
                    sessionCreationMs = stageTimer.measureTimed(
                        "matrix_detector_session_${workflowMode.wireLabel}_${target.name.lowercase()}",
                    ) {
                        detector.initialize(
                            modelFile = prepared.detectionModel,
                            providerConfiguration = providerConfiguration,
                        )
                    }.durationMs
                    actualProvider = detector.executionProviderLabel
                    check(actualProvider.equals(expectedRegisteredProvider, ignoreCase = true)) {
                        "registered provider mismatch: requested=$target expected=$expectedRegisteredProvider actual=$actualProvider"
                    }
                    pages.forEach { page ->
                        corpusLoader.forEachPageBitmap(page) { bitmap ->
                            val measured = stageTimer.measureTimed(
                                "matrix_detector_inference_${workflowMode.wireLabel}_${target.name.lowercase()}",
                            ) {
                                detector.detectLines(bitmap)
                            }
                            timings += measured.durationMs
                            pageSamples += PaddleBenchmarkMatrixPageSample(
                                pageId = page.id,
                                inputWidth = bitmap.width,
                                inputHeight = bitmap.height,
                                measuredBatchSize = 1,
                                coldSession = pageSamples.isEmpty(),
                                inferenceMs = measured.durationMs,
                                outputCount = measured.value.size,
                            )
                        }
                    }
                    evidence = if (pageSamples.isNotEmpty()) "CONFIRMED" else "UNTESTED"
                    if (pageSamples.isEmpty()) errorMessage = "no decodable full-page corpus inputs"
                } catch (failure: Throwable) {
                    errorMessage = "${failure::class.java.simpleName}: ${failure.message}"
                    evidence = "FAILED"
                } finally {
                    detector.close()
                }
                val thermalEnd = ThermalStatus.describe(powerManager)
                PaddleBenchmarkMatrixCellResult(
                    engine = "detector",
                    workflowMode = workflowMode,
                    provider = target,
                    requestedBatchSize = null,
                    widthBucket = null,
                    expectedRegisteredProvider = expectedRegisteredProvider,
                    evidence = evidence,
                    actualRegisteredProvider = actualProvider,
                    sessionCreationMs = sessionCreationMs,
                    pageSamples = pageSamples,
                    strictNoCpuFallback = providerConfiguration.strictNoCpuFallback,
                    provenanceAfterInference = false,
                    noCpuFallbackObserved = evidence == "CONFIRMED",
                    downgradeReason = null,
                    downgradeReasons = emptyList(),
                    measuredBatchSizes = if (pageSamples.isEmpty()) emptyList() else listOf(1),
                    peakInputBytes = null,
                    peakOutputBytes = null,
                    p50Ms = percentileOrNull(timings, 0.50),
                    p95Ms = percentileOrNull(timings, 0.95),
                    pssDeltaKb = if (timings.isEmpty()) null else currentPssKb() - startedPss,
                    thermalStatusAtStart = thermalStart,
                    thermalStatusAtEnd = thermalEnd,
                    rollingP95Actions = emptyList(),
                    error = errorMessage,
                )
            }
        }

    private fun untestedCell(
        spec: PaddleBenchmarkMatrixCellSpec,
        reason: String,
    ): PaddleBenchmarkMatrixCellResult = PaddleBenchmarkMatrixCellResult(
        engine = "recognizer",
        workflowMode = spec.workflowMode,
        provider = spec.provider,
        requestedBatchSize = spec.batchSize,
        widthBucket = spec.widthBucket,
        expectedRegisteredProvider = spec.provider.expectedRegisteredProviderLabel,
        evidence = "UNTESTED",
        actualRegisteredProvider = "unavailable",
        sessionCreationMs = null,
        pageSamples = emptyList(),
        strictNoCpuFallback = spec.provider.isAccelerator,
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

    private data class RepresentativeCrop(
        val pageId: String,
        val bitmap: Bitmap,
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
