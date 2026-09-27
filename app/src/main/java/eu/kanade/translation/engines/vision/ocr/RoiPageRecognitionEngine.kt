package eu.kanade.translation.engines.vision.ocr

import android.content.Context
import android.graphics.Bitmap
import eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics
import eu.kanade.translation.diagnostics.TranslationStageSpan
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceMode
import eu.kanade.translation.diagnostics.TranslationTraceModel
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceStage
import eu.kanade.translation.engines.inpainting.InpaintingMode
import eu.kanade.translation.engines.inpainting.PageInpaintingEngine
import eu.kanade.translation.engines.inpainting.PageInpaintingPlanner
import eu.kanade.translation.engines.inpainting.aot.AOTInpainting
import eu.kanade.translation.engines.rendering.RenderColorEstimator
import eu.kanade.translation.engines.runtime.EngineMemoryBudget
import eu.kanade.translation.engines.runtime.onnx.OnnxModelStore
import eu.kanade.translation.engines.runtime.onnx.PaddleOcrProviderResolution
import eu.kanade.translation.engines.runtime.onnx.PaddleOcrProviderTestConfiguration
import eu.kanade.translation.engines.runtime.onnx.PaddleOcrSessionFactory
import eu.kanade.translation.engines.vision.detection.OnnxPageTextDetector
import eu.kanade.translation.engines.vision.detection.OnnxPanelDetector
import eu.kanade.translation.engines.vision.detection.PanelAssignment
import eu.kanade.translation.engines.vision.ocr.MangaOcrEngine
import eu.kanade.translation.engines.vision.ocr.MlKitRoiOcrEngine
import eu.kanade.translation.engines.vision.ocr.OcrTextFilter
import eu.kanade.translation.engines.vision.ocr.PaddleOcrV6DetEngine
import eu.kanade.translation.engines.vision.ocr.PaddleOcrV6SmallEngine
import eu.kanade.translation.engines.vision.ocr.RoiOcrEngine
import eu.kanade.translation.engines.vision.ocr.TextRecognizerLanguage
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrBatchActivationPolicy
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrP95Action
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrPageGeneration
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrRollingP95HysteresisDowngradePolicy
import eu.kanade.translation.engines.vision.segmentation.BubbleMaskRle
import eu.kanade.translation.engines.vision.segmentation.OnnxBubbleSegmenter
import eu.kanade.translation.engines.vision.webtoon.WebtoonSlidingDetector
import eu.kanade.translation.model.Detection
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.PaddleOcrExecutionProvider
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicLong

class RoiPageRecognitionEngine(
    private val context: Context,
    private val language: TextRecognizerLanguage,
    private val ocrModel: OcrModel,
    private val inpaintingMode: InpaintingMode = InpaintingMode.QUALITY,
) : PageRecognitionEngine {

    private val modelStore = OnnxModelStore(context)
    private var detector: OnnxPageTextDetector? = null

    /**
     * optional YOLO26-nano manga panel detector. Best-effort
     * init (mirrors paddleDet): when the asset is missing or fails to load,
     * this stays null and panel assignment is skipped — translation proceeds
     * panel-less exactly as before. Never blocks OCR/inpaint.
     */
    private var panelDetector: OnnxPanelDetector? = null

    /**
     * optional YOLO11-seg manga bubble segmenter.
     * Drives the precise Interior Median Solid Fill and Symmetrical Growth.
     */
    private var bubbleSegmenter: OnnxBubbleSegmenter? = null
    private var roiOcrEngine: RoiOcrEngine? = null
    private var paddlePageOcrCoordinator: PaddlePageOcrCoordinator? = null
    private var paddleBatchGovernor: PaddleOcrRollingP95HysteresisDowngradePolicy? = null
    private var paddleBatchGovernorReason = "not_paddle"
    private val paddlePageGenerationCounter = AtomicLong(0L)

    /**
     * PP-OCRv6 small **det** engine, used to split a vertical-text
     * ROI into individual text lines for the PaddleOCR rec path. Only built when
     * the selected OCR model is [OcrModel.PADDLEOCR_V6_SMALL] (the only
     * horizontal-line rec engine). Stays null otherwise; the rec path checks for
     * null and falls back to the ink-gap heuristic. See
     * `docs/superpowers/specs/2026-06-23-paddleocr-v6-det-onnx-integration-design.md`.
     */
    private var paddleDet: PaddleOcrV6DetEngine? = null
    private var inpainting: AOTInpainting? = null
    private var pageInpainter: PageInpaintingEngine? = null

    @Volatile
    private var initialized = false
    private var initFailed = false
    private val initMutex = Mutex()

    /**
     * serializes native ONNX inference (analyze/inpaint) against
     * close(). The batch path can call close() before acquiring the translator
     * permit, so this is the primary defense. analyze()/inpaint() hold it across
     * each native OrtSession.run(); close() tryLocks it before freeing sessions.
     * If held at close time, close() logs, skips native free, and leaves [closed]
     * as the backstop — degrades to leak-instead-of-SIGSEGV. close() is non-suspend
     * (main thread), so it uses tryLock rather than runBlocking.
     */
    private val nativeGuard = Mutex()

    // cooperative close flag. close() can race an in-flight
    // analyze()/inpaint() and free native ONNX sessions mid-call past a
    // suspension point (SIGSEGV, not catchable). analyze()/inpaint() poll this
    // before each ONNX call and bail cleanly; [nativeGuard] closes the window.
    @Volatile
    private var closed = false

    // cached translation_diagnostics pref. Resolved lazily (not at
    // construction) to avoid an Injekt cycle during init; cached after first read.
    @Volatile
    private var translationDiagnosticsEnabled: Boolean = false

    @Volatile
    private var diagnosticsResolved: Boolean = false
    private fun resolveDiagnostics(): Boolean {
        if (diagnosticsResolved) return translationDiagnosticsEnabled
        val prefs = try {
            Injekt.get<tachiyomi.domain.translation.TranslationPreferences>()
        } catch (_: Throwable) {
            null
        }
        translationDiagnosticsEnabled = prefs?.translationDiagnostics()?.get() ?: false
        diagnosticsResolved = true
        return translationDiagnosticsEnabled
    }

    // cached reading-order pref (AUTO derives RTL from source
    // language). Resolved lazily to avoid an Injekt cycle during init.
    @Volatile
    private var readingOrderRtl: Boolean = true

    @Volatile
    private var readingOrderResolved: Boolean = false
    private fun resolveReadingOrderRtl(): Boolean {
        if (readingOrderResolved) return readingOrderRtl
        val prefs = try {
            Injekt.get<tachiyomi.domain.translation.TranslationPreferences>()
        } catch (_: Throwable) {
            null
        }
        val pref = prefs?.translationReadingOrder()?.get()
            ?: tachiyomi.domain.translation.TranslationReadingOrder.AUTO
        readingOrderRtl = when (pref) {
            tachiyomi.domain.translation.TranslationReadingOrder.AUTO ->
                language == TextRecognizerLanguage.JAPANESE
            tachiyomi.domain.translation.TranslationReadingOrder.RTL_MANGA -> true
            tachiyomi.domain.translation.TranslationReadingOrder.LTR_COMIC -> false
        }
        readingOrderResolved = true
        return readingOrderRtl
    }

    val isAvailable: Boolean
        get() = !initFailed && (initialized || modelStore.modelsAvailable() || modelStore.assetsAvailable())

    /**
     * Warms up engine sessions outside the timed recognition region.
     */
    suspend fun warmUp() {
        if (isAvailable && !initialized) {
            initialize()
        }
    }

    private suspend fun initialize() {
        initMutex.withLock {
            if (initialized) return
            if (initFailed) {
                throw IllegalStateException("ONNX recognition engine failed to initialize previously")
            }
            val startTime = System.nanoTime()
            try {
                val paths = modelStore.ensureModels()
                // Resolve the temporary Paddle-only choice once and share the
                // result between recognizer and line-detector sessions. This
                // keeps the paired models on the same provider and makes a
                // probe/CPU fallback visible instead of changing midway.
                val paddleProvider = if (ocrModel == OcrModel.PADDLEOCR_V6_SMALL) {
                    resolvePaddleOcrProvider()
                } else {
                    // The free-text mask-only detector used by non-Paddle OCR
                    // keeps its existing automatic/global route. The temporary
                    // selector is scoped to the v6 recognizer + line detector.
                    null
                }
                logcat(LogPriority.INFO) { "ONNX init: starting detector initialization" }
                detector = OnnxPageTextDetector().also { it.initialize(paths.detectorModel) }
                logcat(LogPriority.INFO) { "ONNX init: detector OK, starting OCR initialization (language=$language, model=$ocrModel)" }
                // panel detector is optional context; best-effort
                // init mirrors paddleDet (missing asset -> null, never blocks OCR).
                paths.panelDetectorModel?.let { panelModelFile ->
                    try {
                        panelDetector = OnnxPanelDetector().also { it.initialize(panelModelFile) }
                        logcat(LogPriority.INFO) { "ONNX init: panel detector OK" }
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN, e) {
                            "ONNX init: panel detector failed; panel-aware translation context disabled"
                        }
                    }
                }
                paths.bubbleSegmenterModel?.let { bubbleModelFile ->
                    try {
                        bubbleSegmenter = OnnxBubbleSegmenter().also { it.initialize(bubbleModelFile) }
                        logcat(LogPriority.INFO) { "ONNX init: bubble segmenter OK" }
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN, e) {
                            "ONNX init: bubble segmenter failed; falling back to heuristic masking"
                        }
                    }
                }
                roiOcrEngine = when (ocrModel) {
                    OcrModel.MANGAOCR -> MangaOcrEngine().also {
                        it.initialize(paths.ocrEncoder, paths.ocrDecoderInit, paths.ocrDecoderStep, paths.ocrVocab)
                    }
                    OcrModel.PADDLEOCR_V6_SMALL -> PaddleOcrV6SmallEngine().also {
                        val paddlePaths = modelStore.ensurePaddleOcrV6Small()
                        val activation = PaddleOcrBatchActivationPolicy.currentFromPreferences(
                            preferences = Injekt.get<TranslationPreferences>(),
                            requestedProvider = paddleProvider?.requested,
                        )
                        paddleBatchGovernor = PaddleOcrRollingP95HysteresisDowngradePolicy(
                            initialBatchSize = activation.activeBatchSize,
                            maximumBatchSize = activation.activeBatchSize,
                        )
                        paddleBatchGovernorReason = activation.reason
                        val providerConfiguration = if (activation.forceCpuB1EmergencyFallback) {
                            PaddleOcrProviderTestConfiguration.cpuB1EmergencyFallback()
                        } else {
                            null
                        }
                        it.initialize(
                            paddlePaths.recognitionModel,
                            paddlePaths.dictionary,
                            providerResolution = paddleProvider,
                            providerConfiguration = providerConfiguration,
                        )
                        // PaddleOCR rec reads horizontal lines; vertical
                        // columns must be split first. The det model replaces the
                        // ink-gap heuristic for that split (best-effort; falls back
                        // to the heuristic if the det asset is missing).
                        if (modelStore.paddleOcrV6DetAvailable() || modelStore.paddleOcrV6DetAssetsAvailable()) {
                            try {
                                val detPaths = modelStore.ensurePaddleOcrV6Det()
                                paddleDet = PaddleOcrV6DetEngine().also {
                                    it.initialize(
                                        detPaths.detectionModel,
                                        providerResolution = paddleProvider,
                                    )
                                }
                                logcat(LogPriority.INFO) { "ONNX init: PaddleOCR det OK (replaces ink-gap heuristic)" }
                            } catch (e: Exception) {
                                logcat(LogPriority.WARN, e) {
                                    "ONNX init: PaddleOCR det failed; falling back to ink-gap heuristic"
                                }
                            }
                        } else {
                            logcat(LogPriority.WARN) {
                                "ONNX init: PaddleOCR det asset missing; using ink-gap heuristic"
                            }
                        }
                        // The coordinator owns page-scoped leaf mapping and the
                        // explicit recognizer-call serialization seam.
                        paddlePageOcrCoordinator = PaddlePageOcrCoordinator(
                            engine = it,
                            validatedBatchSize = activation.activeBatchSize,
                        )
                    }
                    OcrModel.MLKIT -> MlKitRoiOcrEngine(language)
                }
                // load PaddleOCR-v6 det for the INPAINTER (free-text
                // erase mask) whenever the asset is available, independent of OCR
                // model. Previously gated behind a removed experimental flag, so
                // the default MANGAOCR path never got Paddle-driven masking. The
                // PADDLEOCR_V6_SMALL branch above already built it for rec.
                if (ocrModel != OcrModel.PADDLEOCR_V6_SMALL &&
                    paddleDet == null &&
                    (modelStore.paddleOcrV6DetAvailable() || modelStore.paddleOcrV6DetAssetsAvailable())
                ) {
                    try {
                        val detPaths = modelStore.ensurePaddleOcrV6Det()
                        paddleDet = PaddleOcrV6DetEngine().also {
                            it.initialize(detPaths.detectionModel)
                        }
                        logcat(LogPriority.INFO) {
                            "ONNX init: PaddleOCR det OK (free-text erase mask)"
                        }
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN, e) {
                            "ONNX init: PaddleOCR det failed; free-text erase falls back to detector-v4 boxes"
                        }
                    }
                } else if (ocrModel != OcrModel.PADDLEOCR_V6_SMALL &&
                    !(modelStore.paddleOcrV6DetAvailable() || modelStore.paddleOcrV6DetAssetsAvailable())
                ) {
                    logcat(LogPriority.WARN) {
                        "ONNX init: PaddleOCR det asset missing; free-text erase falls back to detector-v4 boxes"
                    }
                }
                logcat(LogPriority.INFO) { "ONNX init: OCR OK (backend=${roiOcrEngine!!::class.simpleName}), preparing inpainting (mode=$inpaintingMode)" }
                val localInpainting = AOTInpainting()
                localInpainting.paddleDetector = paddleDet
                if (inpaintingMode == InpaintingMode.QUALITY) {
                    localInpainting.initialize(
                        fixedModelFile = paths.inpaint512Model,
                        dynamicModelFile = paths.inpaintModel,
                    )
                } else {
                    logcat(LogPriority.INFO) { "ONNX init: FAST inpainting mode; skipping AOT session initialization" }
                }
                inpainting = localInpainting
                pageInpainter = PageInpaintingEngine(inpaintingMode, localInpainting)
                initialized = true
                val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
                logcat(LogPriority.INFO) { "RoiPageRecognitionEngine initialized in ${elapsedMs}ms" }
            } catch (e: Exception) {
                initFailed = true
                logcat(LogPriority.ERROR, e) { "RoiPageRecognitionEngine init failed, marking unavailable" }
                throw e
            }
        }
    }

    private fun resolvePaddleOcrProvider(): PaddleOcrProviderResolution {
        val requested = try {
            Injekt.get<TranslationPreferences>()
                .paddleOcrExecutionProvider()
                .get()
        } catch (_: Throwable) {
            PaddleOcrExecutionProvider.CPU
        }
        return PaddleOcrSessionFactory.resolve(requested)
    }

    private fun recordPaddleBatchLatencies(
        engine: PaddleOcrV6SmallEngine,
        completedCoordinator: PaddlePageOcrCoordinator,
    ) {
        val governor = paddleBatchGovernor ?: return
        completedCoordinator.lastBatchTrace.forEach { trace ->
            val previous = governor.activeBatchSize
            val decision = governor.record(trace.batchLatencyMs)
            if (decision.action != PaddleOcrP95Action.HOLD) {
                paddleBatchGovernorReason = decision.reason
                logcat(LogPriority.INFO) {
                    "[paddle_batch_governor] action=${decision.action.name} " +
                        "from=${previous.value} to=${decision.activeBatchSize.value} " +
                        "p95Ms=${decision.rollingP95Ms ?: "n/a"} cap=${governor.maximumBatchSize.value} " +
                        "reason=${decision.reason}"
                }
            }
        }
        if (governor.activeBatchSize != completedCoordinator.validatedBatchSize) {
            paddlePageOcrCoordinator = PaddlePageOcrCoordinator(
                engine = engine,
                validatedBatchSize = governor.activeBatchSize,
            )
        }
    }

    override suspend fun analyze(bitmap: Bitmap): PageTranslation {
        if (!initialized) initialize()
        // bail before any ONNX call if the engine was closed
        // cooperatively (stop()/language-change raced this call). See [closed].
        if (closed) throw IllegalStateException("ONNX recognition engine closed before analyze")
        // Capture engine refs into locals: close() can run concurrently (it does
        // NOT hold translatorPermit) and nulls these fields mid-analyze. Throwing
        // a catchable IllegalStateException surfaces a retryable failure instead
        // of an NPE crash.
        val localDetector = detector
            ?: throw IllegalStateException("ONNX detector closed mid-analyze")
        val localOcrEngine = roiOcrEngine
            ?: throw IllegalStateException("ONNX OCR engine closed mid-analyze")
        var localPaddlePageCoordinator = paddlePageOcrCoordinator
        val startTime = System.nanoTime()
        EngineMemoryBudget.logSnapshot("analyze_start", bitmap.width, bitmap.height)
        // hold nativeGuard across detect + the per-ROI OCR loop so
        // close() cannot free a native session out from under an in-flight
        // OrtSession.run(). Each native pass (detect + every recognize()) must
        // be inside this critical section.
        var detectMs = 0L
        var segmentMs = 0L
        var ocrMs = 0L
        // Correlated engine stages. The run arrives through the
        // installed TranslationTrace element; outside a traced coroutine every
        // span is a fail-open NO_OP. openRecognitionSpan tracks whichever
        // engine stage is currently open so the outer catch below can settle
        // it with a typed failure (never leaks an unclosed stage_start).
        var openRecognitionSpan: TranslationStageSpan? = null
        val analyzed = try {
            nativeGuard.withLock {
                localPaddlePageCoordinator = paddlePageOcrCoordinator
                val pagePaddlePageCoordinator = localPaddlePageCoordinator
                if (closed) throw IllegalStateException("ONNX recognition engine closed before detect")
                val detectSpan = TranslationTrace.beginStage(
                    TranslationTraceStage.DETECT,
                    provider = TranslationPipelineDiagnostics.providerFromLabel(localDetector.executionProviderLabel),
                    model = TranslationTraceModel.PAGE_DETECTOR,
                )
                openRecognitionSpan = detectSpan
                val detectStart = System.nanoTime()
                val detections = try {
                    WebtoonSlidingDetector.detectSliding(bitmap) { localDetector.detect(it) }
                } catch (t: Throwable) {
                    detectSpan.end(TranslationTraceOutcome.FAILURE, error = t)
                    openRecognitionSpan = null
                    throw t
                }
                detectSpan.end(
                    TranslationTraceOutcome.SUCCESS,
                    items = detections.size,
                    registeredProvider = TranslationPipelineDiagnostics.providerFromLabel(localDetector.executionProviderLabel),
                )
                openRecognitionSpan = null
                detectMs = (System.nanoTime() - detectStart) / 1_000_000

                val segmentStart = System.nanoTime()
                val segmenter = bubbleSegmenter
                val bubbleMasks = if (segmenter != null) {
                    val segmentSpan = TranslationTrace.beginStage(
                        TranslationTraceStage.SEGMENT,
                        provider = TranslationPipelineDiagnostics.providerFromLabel(segmenter.executionProviderLabel),
                        model = TranslationTraceModel.BUBBLE_SEGMENTER,
                    )
                    openRecognitionSpan = segmentSpan
                    try {
                        WebtoonSlidingDetector.segmentSliding(bitmap) { segmenter.segment(it) }
                    } catch (t: Throwable) {
                        segmentSpan.end(TranslationTraceOutcome.FAILURE, error = t)
                        openRecognitionSpan = null
                        throw t
                    }.also { masks ->
                        segmentSpan.end(
                            TranslationTraceOutcome.SUCCESS,
                            items = masks.size,
                            registeredProvider = TranslationPipelineDiagnostics.providerFromLabel(segmenter.executionProviderLabel),
                        )
                        openRecognitionSpan = null
                    }
                } else {
                    emptyList()
                }
                segmentMs = (System.nanoTime() - segmentStart) / 1_000_000
                val bubbles = detections.filter { it.label == 0 }
                val textDetections = detections.filter { it.label == 1 || it.label == 2 }
                val lockedPageTranslation = PageTranslation(
                    imgWidth = bitmap.width.toFloat(),
                    imgHeight = bitmap.height.toFloat(),
                    recognitionEngine = "onnx",
                    detectionCount = detections.size,
                    ocrStatus = StageStatus.RUNNING,
                    updatedAt = System.currentTimeMillis(),
                )
                val geometricallyDeduped = dedupeTextDetections(textDetections, bubbles)
                val filteredDetections = suppressCrossLabelDuplicates(geometricallyDeduped, bubbles)
                // stash on the per-page translation so concurrent pages
                // can't overwrite each other's data before inpaint() reads it back.
                lockedPageTranslation.allTextDetections =
                    filteredDetections + (textDetections.filter { it !in filteredDetections && it !in geometricallyDeduped })
                logcat(LogPriority.INFO) {
                    "ONNX recognition detections: bubbles=${bubbles.size} text=${textDetections.size} " +
                        "geometricText=${geometricallyDeduped.size} filteredText=${filteredDetections.size}"
                }
                val lockedRecognizedBlocks = mutableListOf<RecognizedBlock>()
                val engine = localOcrEngine
                val isWebtoonMode = WebtoonSlidingDetector.isTallImage(bitmap.width, bitmap.height) ||
                    language == TextRecognizerLanguage.KOREAN ||
                    !resolveReadingOrderRtl()
                val isVerticalLanguage = (
                    language == TextRecognizerLanguage.JAPANESE ||
                        language == TextRecognizerLanguage.CHINESE
                    ) &&
                    !isWebtoonMode

                val ocrStart = System.nanoTime()
                val ocrSpan = TranslationTrace.beginStage(
                    TranslationTraceStage.OCR,
                    provider = TranslationPipelineDiagnostics.providerFromLabel(localOcrEngine.executionProviderLabel),
                    model = when (localOcrEngine) {
                        is MangaOcrEngine -> TranslationTraceModel.MANGA_OCR
                        is PaddleOcrV6SmallEngine -> TranslationTraceModel.PADDLE_OCR
                        else -> TranslationTraceModel.NONE
                    },
                )
                openRecognitionSpan = ocrSpan
                val paddleRegionResults = if (
                    engine is PaddleOcrV6SmallEngine && pagePaddlePageCoordinator != null
                ) {
                    val traceIdentity = TranslationTrace.currentRun()?.identity
                    val pageId = traceIdentity?.page?.takeUnless { it.isBlank() || it == "none" }
                        ?: "roi-page"
                    val pageGeneration = PaddleOcrPageGeneration(
                        pageId = pageId,
                        generation = paddlePageGenerationCounter.incrementAndGet(),
                    )
                    val mode = when (traceIdentity?.mode) {
                        TranslationTraceMode.AUTO -> PaddlePageOcrMode.AUTO
                        TranslationTraceMode.BATCH -> PaddlePageOcrMode.CHAPTER
                        TranslationTraceMode.MANUAL,
                        null,
                        -> PaddlePageOcrMode.MANUAL
                    }
                    pagePaddlePageCoordinator.recognizePage(
                        pageGeneration = pageGeneration,
                        bitmap = bitmap,
                        detections = filteredDetections,
                        paddleDet = paddleDet,
                        language = language,
                        isVerticalLanguage = isVerticalLanguage,
                        mode = mode,
                        isClosed = { closed },
                    ).also { results ->
                        val waitMs = pagePaddlePageCoordinator.lastBatchTrace.sumOf {
                            it.queueWaitMs + it.admissionWaitMs
                        }
                        logcat(LogPriority.INFO) {
                            "Paddle page generation=${pageGeneration.generation} mode=${mode.name.lowercase()} " +
                                "leaves=${pagePaddlePageCoordinator.lastResolvedLeafCount} " +
                                "batches=${pagePaddlePageCoordinator.lastBatchTrace.size} waitMs=$waitMs"
                        }
                        recordPaddleBatchLatencies(engine, pagePaddlePageCoordinator)
                    }
                } else {
                    null
                }
                if (!engine.prefersHorizontalText) {
                    if (closed) throw IllegalStateException("ONNX recognition engine closed before batch OCR")
                    val crops = filteredDetections.map { detection ->
                        cropBitmap(bitmap, detection.bbox[0], detection.bbox[1], detection.bbox[2], detection.bbox[3])
                    }
                    val batchResults = try {
                        engine.recognizeBatchWithConf(crops)
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "Failed batch OCR recognition" }
                        emptyList()
                    } finally {
                        crops.forEach { it.recycle() }
                    }

                    for (i in filteredDetections.indices) {
                        val detection = filteredDetections[i]
                        val bbox = detection.bbox
                        val rawText = batchResults.getOrNull(i)?.first ?: ""
                        val text = if (OcrTextFilter.isUsable(rawText, language)) rawText else ""

                        if (resolveDiagnostics()) {
                            logcat(LogPriority.INFO) {
                                "[ocr_block] box=[${bbox[0].toInt()},${bbox[1].toInt()},${bbox[2].toInt()},${bbox[3].toInt()}] " +
                                    "size=${(bbox[2] - bbox[0]).toInt()}x${(bbox[3] - bbox[1]).toInt()} rotated=no " +
                                    "chars=${text.length}"
                            }
                        }
                        val boxWidth = (bbox[2] - bbox[0]).toFloat()
                        val boxHeight = (bbox[3] - bbox[1]).toFloat()
                        val centerX = (bbox[0] + bbox[2]) / 2.0
                        val centerY = (bbox[1] + bbox[3]) / 2.0
                        val rawParent = selectParentBubble(detection, bbox, bubbles, centerX, centerY)
                        val parentBbox = rawParent?.let { rp ->
                            val siblings = bubbles.filter { it !== rp }.map { it.bbox }
                            trimParentBbox(rp.bbox, bbox, siblings)
                        } ?: rawParent?.bbox
                        val renderColors = RenderColorEstimator.estimate(
                            bitmap,
                            bbox[0],
                            bbox[1],
                            bbox[2],
                            bbox[3],
                            parentBbox,
                        )
                        val direction = if (isVerticalLanguage && boxHeight > boxWidth * 1.2f) "TTB" else "LTR"
                        lockedRecognizedBlocks.add(
                            RecognizedBlock(
                                detection = detection,
                                block = TranslationBlock(
                                    text = text,
                                    width = boxWidth,
                                    height = boxHeight,
                                    x = bbox[0].toFloat(),
                                    y = bbox[1].toFloat(),
                                    symWidth = boxWidth * 0.1f,
                                    symHeight = boxHeight * 0.1f,
                                    angle = 0f,
                                    label = detection.label,
                                    score = detection.score,
                                    parentX = parentBbox?.get(0)?.toFloat() ?: 0f,
                                    parentY = parentBbox?.get(1)?.toFloat() ?: 0f,
                                    parentWidth = parentBbox?.let { (it[2] - it[0]).toFloat() } ?: 0f,
                                    parentHeight = parentBbox?.let { (it[3] - it[1]).toFloat() } ?: 0f,
                                    textColor = renderColors.first,
                                    strokeColor = renderColors.second,
                                    strokeWidth = renderColors.third,
                                    direction = direction,
                                    segmentationMask = bubbleMasks.firstOrNull { rle ->
                                        rle.overlapPixels(centerX.toInt(), centerY.toInt(), centerX.toInt() + 1, centerY.toInt() + 1) > 0
                                    },
                                ),
                            ),
                        )
                    }
                } else {
                    for ((detectionIndex, detection) in filteredDetections.withIndex()) {
                        // The Paddle adapter has already completed every final
                        // line/glyph leaf for this page. Other horizontal engines
                        // stay on the original sequential crop path below.
                        if (paddleRegionResults != null) {
                            if (closed) throw IllegalStateException("ONNX recognition engine closed during OCR mapping")
                            val result = paddleRegionResults.getOrNull(detectionIndex)
                                ?: error("Paddle page result missing for region=$detectionIndex")
                            val text = result.text
                            val rotatedForOcr = result.rotatedForOcr
                            if (resolveDiagnostics()) {
                                logcat(LogPriority.INFO) {
                                    "[ocr_block] box=[${detection.bbox[0].toInt()},${detection.bbox[1].toInt()},${detection.bbox[2].toInt()},${detection.bbox[3].toInt()}] " +
                                        "size=${(detection.bbox[2] - detection.bbox[0]).toInt()}x${(detection.bbox[3] - detection.bbox[1]).toInt()} " +
                                        "rotated=${if (rotatedForOcr) "90ccw" else "no"} chars=${text.length}"
                                }
                            }
                            appendRecognizedBlock(
                                bitmap = bitmap,
                                detection = detection,
                                text = text,
                                isVerticalLanguage = isVerticalLanguage,
                                bubbles = bubbles,
                                bubbleMasks = bubbleMasks,
                                lockedRecognizedBlocks = lockedRecognizedBlocks,
                            )
                            continue
                        }

                        // cooperative close — bail out of the per-ROI OCR
                        // loop if close() ran between iterations, before the native call.
                        if (closed) throw IllegalStateException("ONNX recognition engine closed during OCR loop")
                        // horizontal-line engines (PaddleOCR) need context
                        // padding to avoid edge-effect failures; native-vertical engines
                        // get tight unbounded crops.
                        val pad = 12
                        val unpaddedCrop = cropBitmap(bitmap, detection.bbox[0], detection.bbox[1], detection.bbox[2], detection.bbox[3])
                        val crop = cropBitmap(bitmap, detection.bbox[0] - pad, detection.bbox[1] - pad, detection.bbox[2] + pad, detection.bbox[3] + pad)
                        val bbox = detection.bbox
                        val boxWidthPre = (bbox[2] - bbox[0]).toFloat()
                        val boxHeightPre = (bbox[3] - bbox[1]).toFloat()
                        val tallVertical = isVerticalLanguage && boxHeightPre > boxWidthPre * 1.5f
                        val paddleMultiLine = paddleDet != null
                        val paddleVerticalHeuristic = isVerticalLanguage &&
                            paddleDet == null &&
                            boxHeightPre > boxWidthPre * 1.5f
                        var rotatedForOcr = false
                        val text = try {
                            when {
                                paddleMultiLine -> {
                                    rotatedForOcr = tallVertical
                                    var rawText = recognizeMultiLine(engine, crop, paddleDet, tallVertical)
                                    if (rawText.isEmpty() && isVerticalLanguage && boxHeightPre > boxWidthPre) {
                                        rotatedForOcr = true
                                        val fallbackText = recognizeMultiLine(engine, unpaddedCrop, null, verticalFallback = true)
                                        if (fallbackText.isNotEmpty()) rawText = fallbackText
                                    }
                                    rawText
                                }
                                paddleVerticalHeuristic -> {
                                    rotatedForOcr = true
                                    recognizeMultiLine(engine, unpaddedCrop, paddleDet, verticalFallback = true)
                                }
                                else -> {
                                    val rawText = engine.recognizeWithConf(crop).first
                                    if (OcrTextFilter.isUsable(rawText, language)) rawText else ""
                                }
                            }
                        } catch (e: Exception) {
                            logcat(LogPriority.ERROR, e) { "Failed to recognize text in box" }
                            ""
                        } finally {
                            if (crop !== unpaddedCrop) {
                                crop.recycle()
                            }
                            unpaddedCrop.recycle()
                        }
                        // per-block OCR diagnostics, gated by the opt-in
                        // translation_diagnostics pref so recognition quality is inspectable.
                        if (resolveDiagnostics()) {
                            logcat(LogPriority.INFO) {
                                "[ocr_block] box=[${bbox[0].toInt()},${bbox[1].toInt()},${bbox[2].toInt()},${bbox[3].toInt()}] " +
                                    "size=${(bbox[2] - bbox[0]).toInt()}x${(bbox[3] - bbox[1]).toInt()} " +
                                    "rotated=${if (rotatedForOcr) "90ccw" else "no"} chars=${text.length}"
                            }
                        }
                        appendRecognizedBlock(
                            bitmap = bitmap,
                            detection = detection,
                            text = text,
                            isVerticalLanguage = isVerticalLanguage,
                            bubbles = bubbles,
                            bubbleMasks = bubbleMasks,
                            lockedRecognizedBlocks = lockedRecognizedBlocks,
                        )
                    }
                }
                ocrMs = (System.nanoTime() - ocrStart) / 1_000_000
                ocrSpan.end(
                    TranslationTraceOutcome.SUCCESS,
                    items = lockedRecognizedBlocks.size,
                    registeredProvider = TranslationPipelineDiagnostics.providerFromLabel(localOcrEngine.executionProviderLabel),
                )
                openRecognitionSpan = null
                RecognizedAnalyzeResult(lockedPageTranslation, lockedRecognizedBlocks)
            }
        } catch (t: Throwable) {
            // Settle whichever engine stage was still open (detect/segment/ocr)
            // so a mid-recognition throw never leaves a stage_start unclosed.
            openRecognitionSpan?.end(TranslationTraceOutcome.FAILURE, error = t)
            openRecognitionSpan = null
            throw t
        } finally {
            if (closed && initialized) {
                if (nativeGuard.tryLock()) {
                    try {
                        freeNativeSessions()
                    } finally {
                        nativeGuard.unlock()
                    }
                }
            }
        }
        val pageTranslation = analyzed.pageTranslation
        val recognizedBlocks = analyzed.recognizedBlocks
        val finalRecognizedBlocks = removePostOcrDuplicateBlocks(recognizedBlocks)
        // second dedupe pass. removePostOcrDuplicateBlocks only
        // collapses identical-text overlaps; this collapses remaining geometric
        // overlaps (cross-label, differing-text) so two overlapping boxes never
        // reach the renderer.
        val dedupedBlocks = OcrBlockDeduper.dedupeGeometricOverlaps(
            finalRecognizedBlocks.map { it.block },
        )
        val droppedByGeoDedupe = finalRecognizedBlocks.size - dedupedBlocks.size
        if (droppedByGeoDedupe > 0) {
            logcat(LogPriority.INFO) {
                "Geometric block dedupe dropped $droppedByGeoDedupe overlapping block(s) after OCR"
            }
        }
        pageTranslation.blocks.addAll(dedupedBlocks)
        pageTranslation.ocrBlockCount = pageTranslation.blocks.size
        pageTranslation.ocrStatus = StageStatus.READY
        pageTranslation.updatedAt = System.currentTimeMillis()
        // assign OCR blocks to panels now that the block list is
        // frozen. See assignPanels() for the conservative no-fallback policy.
        assignPanels(bitmap, pageTranslation)
        // capture the durable inpaint mask at OCR time, before
        // translation/watermark filtering removes blocks and before the page is
        // persisted (allTextDetections is @Transient). Persisting it means a
        // resumed batch still erases detector-only + watermark regions, not just
        // the surviving OCR blocks. See PageInpaintingPlanner + contract #14.
        pageTranslation.inpaintMaskBoxes = PageInpaintingPlanner.computeMask(pageTranslation)
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        val paddleBatchSize = localPaddlePageCoordinator?.validatedBatchSize?.value ?: 1
        val paddleBatched = localPaddlePageCoordinator != null && paddleBatchSize > 1
        val paddleGovernorReason = if (localPaddlePageCoordinator != null) {
            paddleBatchGovernorReason
        } else {
            "not_paddle"
        }
        logcat(LogPriority.INFO) {
            // Per-engine provider labels report the providers actually used;
            // a device-wide route can differ from an individual engine's route.
            "[translation_perf] " +
                "providers(detector=${detector?.executionProviderLabel ?: "n/a"}, " +
                "segmenter=${bubbleSegmenter?.executionProviderLabel ?: "n/a"}, " +
                "ocr=${localOcrEngine.executionProviderLabel}) " +
                "stage=recognition total=${elapsedMs}ms detector=${detectMs}ms segmenter=${segmentMs}ms " +
                "ocr=${ocrMs}ms (blocks=${pageTranslation.blocks.size} batched=$paddleBatched " +
                "batchSize=$paddleBatchSize governorReason=$paddleGovernorReason)"
        }
        logcat(LogPriority.INFO) {
            "RoiPageRecognitionEngine analyzed ${pageTranslation.blocks.size} blocks " +
                "emptyOcr=${pageTranslation.blocks.count { it.text.isBlank() }} in ${elapsedMs}ms"
        }
        return pageTranslation
    }

    override suspend fun inpaint(bitmap: Bitmap, pageTranslation: PageTranslation): Bitmap? {
        if (!initialized) initialize()
        if (closed) {
            pageTranslation.inpaintStatus = StageStatus.FAILED
            pageTranslation.errorMessage = "ONNX recognition engine closed before inpaint"
            pageTranslation.updatedAt = System.currentTimeMillis()
            return null
        }
        // Hold nativeGuard across inpaint so close() cannot free the AOT
        // inpainter's native session mid-run. Re-check [closed] inside the
        // lock. PageInpaintingEngine owns box and mask computation.
        // The correlated inpaint stage reports AOT provenance from the
        // inpainter's execution-proven route (lastAcceptedRoute) after
        // inference as `provenProvider`; this adapter has no registration
        // result to report.
        val inpaintSpan = TranslationTrace.beginStage(
            TranslationTraceStage.INPAINT,
            model = if (inpainting != null) TranslationTraceModel.AOT_GAN else TranslationTraceModel.NONE,
        )
        val inpaintStart = System.nanoTime()
        val result = try {
            nativeGuard.withLock {
                if (closed) {
                    pageTranslation.inpaintStatus = StageStatus.FAILED
                    pageTranslation.errorMessage = "ONNX recognition engine closed before inpaint"
                    pageTranslation.updatedAt = System.currentTimeMillis()
                    null
                } else {
                    (pageInpainter ?: PageInpaintingEngine(inpaintingMode, inpainting ?: AOTInpainting()))
                        .inpaint(bitmap, pageTranslation)
                }
            }
        } catch (t: Throwable) {
            inpaintSpan.end(TranslationTraceOutcome.FAILURE, error = t)
            throw t
        } finally {
            if (closed && initialized) {
                if (nativeGuard.tryLock()) {
                    try {
                        freeNativeSessions()
                    } finally {
                        nativeGuard.unlock()
                    }
                }
            }
        }
        val inpaintMs = (System.nanoTime() - inpaintStart) / 1_000_000
        inpaintSpan.end(
            if (result == null) TranslationTraceOutcome.FAILURE else TranslationTraceOutcome.SUCCESS,
            provenProvider = TranslationPipelineDiagnostics.providerFromLabel(inpainting?.lastAcceptedRoute),
        )
        logcat(LogPriority.INFO) {
            // inpaintRoute reports the provider that actually handled inference.
            "[translation_perf] " +
                "stage=inpainting total=${inpaintMs}ms mode=$inpaintingMode maskBoxes=${pageTranslation.inpaintMaskBoxes.size} " +
                "inpaintRoute=${inpainting?.lastAcceptedRoute ?: "n/a"}"
        }
        return result
    }

    private fun appendRecognizedBlock(
        bitmap: Bitmap,
        detection: Detection,
        text: String,
        isVerticalLanguage: Boolean,
        bubbles: List<Detection>,
        bubbleMasks: List<BubbleMaskRle>,
        lockedRecognizedBlocks: MutableList<RecognizedBlock>,
    ) {
        val bbox = detection.bbox
        val boxWidth = (bbox[2] - bbox[0]).toFloat()
        val boxHeight = (bbox[3] - bbox[1]).toFloat()
        val centerX = (bbox[0] + bbox[2]) / 2.0
        val centerY = (bbox[1] + bbox[3]) / 2.0
        val rawParent = selectParentBubble(detection, bbox, bubbles, centerX, centerY)
        val parentBbox = rawParent?.let { rp ->
            val siblings = bubbles.filter { it !== rp }.map { it.bbox }
            trimParentBbox(rp.bbox, bbox, siblings)
        } ?: rawParent?.bbox
        val renderColors = RenderColorEstimator.estimate(
            bitmap,
            bbox[0],
            bbox[1],
            bbox[2],
            bbox[3],
            parentBbox,
        )
        val direction = if (isVerticalLanguage && boxHeight > boxWidth * 1.2f) "TTB" else "LTR"
        lockedRecognizedBlocks.add(
            RecognizedBlock(
                detection = detection,
                block = TranslationBlock(
                    text = text,
                    width = boxWidth,
                    height = boxHeight,
                    x = bbox[0].toFloat(),
                    y = bbox[1].toFloat(),
                    symWidth = boxWidth * 0.1f,
                    symHeight = boxHeight * 0.1f,
                    angle = 0f,
                    label = detection.label,
                    score = detection.score,
                    parentX = parentBbox?.get(0)?.toFloat() ?: 0f,
                    parentY = parentBbox?.get(1)?.toFloat() ?: 0f,
                    parentWidth = parentBbox?.let { (it[2] - it[0]).toFloat() } ?: 0f,
                    parentHeight = parentBbox?.let { (it[3] - it[1]).toFloat() } ?: 0f,
                    textColor = renderColors.first,
                    strokeColor = renderColors.second,
                    strokeWidth = renderColors.third,
                    direction = direction,
                    segmentationMask = bubbleMasks.firstOrNull { rle ->
                        rle.overlapPixels(centerX.toInt(), centerY.toInt(), centerX.toInt() + 1, centerY.toInt() + 1) > 0
                    },
                ),
            ),
        )
    }

    /**
     * run panel detection on the page bitmap and assign each final
     * OCR block to a panel via [PanelAssignment]. Mutates the block list in
     * place: sets [TranslationBlock.panelIndex], [TranslationBlock.panelAssignment],
     * [TranslationBlock.panelContainment]. No-op when the panel detector is not
     * loaded (panel-less translation, the prior behaviour).
     *
     * Conservative policy enforced here, NOT silent fallback:
     *  - owned blocks get a real panelIndex (reading-order).
     *  - spanning / free_floating / orphan / invalid blocks keep panelIndex=null
     *    and are surfaced to the translator as page-level context. They are
     *    NEVER attached to the nearest panel — that would poison speaker and
     *    pronoun inference, the exact failure mode panel context exists to fix.
     *  - every non-OWNED block is logged so a flaky panel model is visible.
     *
     * Panel boxes are sorted into reading order (XY-cut, RTL-aware for manga)
     * BEFORE assignment so panelIndex is the reading-order index the translator
     * will see in the prompt, not the raw detector order.
     */
    private fun assignPanels(bitmap: Bitmap, pageTranslation: PageTranslation) {
        val pd = panelDetector ?: return
        if (pageTranslation.blocks.isEmpty()) return
        // derive stable bubble indices from parent geometry BEFORE
        // panel assignment (see assignBubbleIndices). Must run first so the
        // bubbleIndex is present on each block before panel fields are set.
        assignBubbleIndices(pageTranslation)
        val isWebtoonMode = WebtoonSlidingDetector.isTallImage(bitmap.width, bitmap.height) ||
            language == TextRecognizerLanguage.KOREAN ||
            !resolveReadingOrderRtl()
        if (isWebtoonMode) {
            logcat(LogPriority.INFO) { "Panel detection bypassed for webtoon / LTR reading order" }
            return
        }
        val panels: List<FloatArray> = try {
            pd.detect(bitmap)
        } catch (e: Exception) {
            // Detection threw (closed session, native error). Fail loudly but do
            // NOT crash OCR/translation — blocks keep panelAssignment="none" and
            // the prompt layer treats missing panel context as panel-less.
            logcat(LogPriority.ERROR, e) {
                "Panel detection failed; page will be translated without panel context"
            }
            return
        }
        if (panels.isEmpty()) {
            // Text present but 0 panels: the broken/full-bleed page case. Mark
            // every block ORPHAN so the translator sees explicit page-level context.
            pageTranslation.blocks.forEach { block ->
                val res = PanelAssignment.noPanels(boxValid = PanelAssignment.isValidBox(block.x, block.y, block.x + block.width, block.y + block.height))
                applyAssignment(pageTranslation, block, res)
            }
            logcat(LogPriority.INFO) {
                "Panel detection: 0 panels for page with ${pageTranslation.blocks.size} block(s) — marked orphan (broken/full-bleed page)"
            }
            return
        }

        val orderedPanels = ReadingOrderSorter.readingOrderPanels(panels, resolveReadingOrderRtl())
        val counts = HashMap<String, Int>()
        for (block in pageTranslation.blocks) {
            val res = PanelAssignment.assign(
                block.x,
                block.y,
                block.x + block.width,
                block.y + block.height,
                orderedPanels,
            )
            applyAssignment(pageTranslation, block, res)
            counts.merge(res.category.asString(), 1) { a, b -> a + b }
        }
        // Surface non-owned categories so a degraded panel model is visible
        // without enabling full diagnostics.
        val owned = counts["owned"] ?: 0
        val nonOwned = pageTranslation.blocks.size - owned
        if (nonOwned > 0) {
            logcat(LogPriority.INFO) {
                "Panel assignment: ${pageTranslation.blocks.size} blocks, ${orderedPanels.size} panels — " +
                    "owned=$owned, non-owned=$nonOwned ($counts)"
            }
        }
    }

    /**
     * assign a stable per-page bubble index to each block based on
     * its parent-bubble geometry. Blocks inside the same speech bubble share the
     * same parentX/Y/Width/Height (set by the recognition path); they get the
     * same index so the translator can treat them as one utterance. Free-text
     * blocks (no parent bubble, parentWidth/Height == 0) keep index null.
     *
     * Indices are assigned in top-left reading order of the distinct bubbles so
     * they're stable across re-runs on the same page state. Mutates blocks in
     * place via copy()+replace (data class val fields).
     */
    private fun assignBubbleIndices(pageTranslation: PageTranslation) {
        // Collect distinct bubble geometries (rounded to int to treat near-
        // identical floats as the same bubble).
        data class BubbleKey(val x: Int, val y: Int, val w: Int, val h: Int)
        val distinctBubbles = LinkedHashMap<BubbleKey, Int>()
        for (block in pageTranslation.blocks) {
            if (block.parentWidth <= 0f || block.parentHeight <= 0f) continue
            val key = BubbleKey(
                block.parentX.toInt(),
                block.parentY.toInt(),
                block.parentWidth.toInt(),
                block.parentHeight.toInt(),
            )
            if (key !in distinctBubbles) distinctBubbles[key] = 0
        }
        if (distinctBubbles.isEmpty()) return
        // Sort by (y, x) for a stable grouping ID. RTL ordering is irrelevant —
        // the prompt layer never orders by bubbleIndex, only groups by it.
        val sortedKeys = distinctBubbles.keys.sortedWith(
            compareBy<BubbleKey>({ it.y }, { it.x }),
        )
        val keyToIndex = HashMap<BubbleKey, Int>(sortedKeys.size)
        sortedKeys.forEachIndexed { i, k -> keyToIndex[k] = i }

        // Apply: replace each block with a copy carrying its bubbleIndex.
        for (i in pageTranslation.blocks.indices) {
            val block = pageTranslation.blocks[i]
            if (block.parentWidth <= 0f || block.parentHeight <= 0f) continue
            val key = BubbleKey(
                block.parentX.toInt(),
                block.parentY.toInt(),
                block.parentWidth.toInt(),
                block.parentHeight.toInt(),
            )
            val bidx = keyToIndex[key]
            if (bidx != null && bidx != block.bubbleIndex) {
                pageTranslation.blocks[i] = block.copy(bubbleIndex = bidx)
            }
        }
    }

    private fun applyAssignment(
        pageTranslation: PageTranslation,
        block: TranslationBlock,
        res: PanelAssignment.Result,
    ) {
        // panelIndex is only set for OWNED; other categories keep null so the
        // translator renders page-level context instead of a confidently wrong panel.
        val idx = pageTranslation.blocks.indexOfFirst { it === block }
        if (idx < 0) {
            logcat(LogPriority.WARN) {
                "Panel assignment: could not locate block for category=${res.category.asString()}; assignment dropped"
            }
            return
        }
        pageTranslation.blocks[idx] = block.copy(
            panelIndex = res.panelIndex,
            panelAssignment = res.category.asString(),
            panelContainment = res.bestContainment,
            bubbleIndex = block.bubbleIndex,
        )
    }

    private fun freeNativeSessions() {
        try {
            detector?.close()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error closing detector" }
        } finally {
            detector = null
        }
        try {
            roiOcrEngine?.close()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error closing roiOcrEngine" }
        } finally {
            roiOcrEngine = null
            paddlePageOcrCoordinator = null
            paddleBatchGovernor = null
            paddleBatchGovernorReason = "not_paddle"
        }
        try {
            paddleDet?.close()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error closing paddleDet" }
        } finally {
            paddleDet = null
        }
        try {
            inpainting?.close()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error closing inpainting" }
        } finally {
            inpainting = null
        }
        try {
            panelDetector?.close()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error closing panelDetector" }
        } finally {
            panelDetector = null
        }
        try {
            bubbleSegmenter?.close()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error closing bubbleSegmenter" }
        } finally {
            bubbleSegmenter = null
        }
        pageInpainter = null
        initialized = false
    }

    override fun close() {
        // set the cooperative close flag FIRST so an in-flight
        // analyze()/inpaint() polling [closed] between ONNX calls bails cleanly
        // before touching a session freed below (avoids a native crash).
        closed = true
        // tryLock (non-suspend, never blocks the main thread). Every
        // close() call site is guarded by the translator permit, so the lock
        // should be free; tryLock is defense-in-depth. If it fails, defer the
        // session free to the lock holder (leak-instead-of-SIGSEGV).
        val nativeDrained = nativeGuard.tryLock()
        if (!nativeDrained) {
            logcat(LogPriority.WARN) {
                "RoiPageRecognitionEngine.close: nativeGuard held (unexpected — permit guard " +
                    "should prevent an in-flight native run at close time); deferring native " +
                    "session free to the lock holder to avoid use-after-free or leak."
            }
            return
        }
        try {
            freeNativeSessions()
        } finally {
            nativeGuard.unlock()
        }
    }

    override fun reclaimPooledMemory() {
        // free off-heap pooled state held by sub-engines WITHOUT
        // tearing them down. Called by OOM recovery so native pressure from one
        // page doesn't carry into the next. Guarded so partial init is a no-op.
        try {
            detector?.reclaimPooledMemory()
        } catch (_: Exception) {}
        try {
            roiOcrEngine?.reclaimPooledMemory()
        } catch (_: Exception) {}
        try {
            paddleDet?.reclaimPooledMemory()
        } catch (_: Exception) {}
        try {
            inpainting?.reclaimPooledMemory()
        } catch (_: Exception) {}
        try {
            panelDetector?.reclaimPooledMemory()
        } catch (_: Exception) {}
        try {
            bubbleSegmenter?.reclaimPooledMemory()
        } catch (_: Exception) {}
    }

    override fun forceReleaseNativeBuffers() {
        // Memory-pressure callbacks may race an in-flight native run. Never
        // drain child pools while the worker owns nativeGuard: skip this call
        // (leak-instead-of-SIGSEGV, mirroring close()). The worker's own
        // finally releases its pools after the guarded native call completes,
        // and the next memory-pressure callback will retry.
        //
        // The skip-vs-drain decision forwards to TranslationSafetyPrimitives so
        // the P0-1 invariant (no drain while the native lock is held) is
        // unit-testable without an ONNX engine — see drainChildBuffersGuarded.
        val lockAcquired = nativeGuard.tryLock()
        if (!lockAcquired) {
            logcat(LogPriority.WARN) {
                "RoiPageRecognitionEngine.forceReleaseNativeBuffers: nativeGuard held; " +
                    "skipping pooled-buffer release to avoid native use-after-free"
            }
            return
        }
        try {
            val engines = listOfNotNull(
                detector?.let { TranslationSafetyPrimitives.ForceReleasable { it.forceReleaseNativeBuffers() } },
                roiOcrEngine?.let { TranslationSafetyPrimitives.ForceReleasable { it.forceReleaseNativeBuffers() } },
                paddleDet?.let { TranslationSafetyPrimitives.ForceReleasable { it.forceReleaseNativeBuffers() } },
                inpainting?.let { TranslationSafetyPrimitives.ForceReleasable { it.forceReleaseNativeBuffers() } },
                panelDetector?.let { TranslationSafetyPrimitives.ForceReleasable { it.forceReleaseNativeBuffers() } },
                bubbleSegmenter?.let { TranslationSafetyPrimitives.ForceReleasable { it.forceReleaseNativeBuffers() } },
            )
            TranslationSafetyPrimitives.drainChildBuffersGuarded(lockHeld = false, engines = engines)
        } finally {
            nativeGuard.unlock()
        }
    }

    private fun suppressCrossLabelDuplicates(
        textDetections: List<Detection>,
        bubbles: List<Detection>,
    ): List<Detection> =
        OcrBlockDeduplication.suppressCrossLabelDuplicates(textDetections, bubbles)

    private fun dedupeTextDetections(
        textDetections: List<Detection>,
        bubbles: List<Detection>,
    ): List<Detection> =
        OcrBlockDeduplication.dedupeTextDetections(textDetections, bubbles)

    private fun removePostOcrDuplicateBlocks(blocks: List<RecognizedBlock>): List<RecognizedBlock> =
        OcrBlockDeduplication.removePostOcrDuplicateBlocks(blocks)

    private fun trimParentBbox(
        parent: IntArray,
        textBbox: IntArray,
        siblingBubbles: List<IntArray>,
    ): IntArray =
        OcrBlockDeduplication.trimParentBbox(parent, textBbox, siblingBubbles)

    private fun selectParentBubble(
        detection: Detection,
        textBbox: IntArray,
        bubbles: List<Detection>,
        centerX: Double,
        centerY: Double,
    ): Detection? =
        OcrBlockDeduplication.selectParentBubble(detection, textBbox, bubbles, centerX, centerY)

    private fun cropBitmap(source: Bitmap, x1: Int, y1: Int, x2: Int, y2: Int): Bitmap =
        VerticalLineOcr.cropBitmap(source, x1, y1, x2, y2)

    private suspend fun recognizeMultiLine(
        engine: RoiOcrEngine,
        crop: Bitmap,
        paddleDet: PaddleOcrV6DetEngine?,
        verticalFallback: Boolean,
    ): String =
        VerticalLineOcr.recognizeMultiLine(engine, crop, paddleDet, verticalFallback, language) { closed }
}
