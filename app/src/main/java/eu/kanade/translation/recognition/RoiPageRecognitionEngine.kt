package eu.kanade.translation.recognition

import android.content.Context
import android.graphics.Bitmap
import eu.kanade.translation.detection.Detection
import eu.kanade.translation.detection.OnnxPageTextDetector
import eu.kanade.translation.detection.OnnxPanelDetector
import eu.kanade.translation.detection.PanelAssignment
import eu.kanade.translation.segmentation.OnnxBubbleSegmenter
import eu.kanade.translation.segmentation.BubbleMaskRle
import eu.kanade.translation.inpainting.AOTInpainting
import eu.kanade.translation.inpainting.InpaintingMode
import eu.kanade.translation.inpainting.PageInpaintingEngine
import eu.kanade.translation.inpainting.PageInpaintingPlanner
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageTranslationHelper
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocr.DbPostProcess
import eu.kanade.translation.ocr.MangaOcrEngine
import eu.kanade.translation.ocr.MlKitRoiOcrEngine
import eu.kanade.translation.ocr.OcrTextFilter
import eu.kanade.translation.ocr.PaddleOcrV6DetEngine
import eu.kanade.translation.ocr.PaddleOcrV6SmallEngine
import eu.kanade.translation.ocr.RoiOcrEngine
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.rendering.RenderColorEstimator
import eu.kanade.translation.runtime.onnx.OnnxModelStore
import eu.kanade.translation.util.TranslationMemoryBudget
import eu.kanade.translation.util.TranslationSafetyPrimitives
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.OcrModel
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class RoiPageRecognitionEngine(
    private val context: Context,
    private val language: TextRecognizerLanguage,
    private val ocrModel: OcrModel,
    private val inpaintingMode: InpaintingMode = InpaintingMode.QUALITY,
) : PageRecognitionEngine {

    private val modelStore = OnnxModelStore(context)
    private var detector: OnnxPageTextDetector? = null
    /**
     * TachiyomiAT: optional YOLO26-nano manga panel detector. Best-effort
     * init (mirrors paddleDet): when the asset is missing or fails to load,
     * this stays null and panel assignment is skipped — translation proceeds
     * panel-less exactly as before. Never blocks OCR/inpaint.
     */
    private var panelDetector: OnnxPanelDetector? = null
    /**
     * TachiyomiAT: optional YOLO11-seg manga bubble segmenter.
     * Drives the precise Interior Median Solid Fill and Symmetrical Growth.
     */
    private var bubbleSegmenter: OnnxBubbleSegmenter? = null
    private var roiOcrEngine: RoiOcrEngine? = null
    /**
     * TachiyomiAT: PP-OCRv6 small **det** engine, used to split a vertical-text
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
     * TachiyomiAT: serializes native ONNX inference (analyze/inpaint) against
     * close(). The batch path can call close() before acquiring the translator
     * permit, so this is the primary defense. analyze()/inpaint() hold it across
     * each native OrtSession.run(); close() tryLocks it before freeing sessions.
     * If held at close time, close() logs, skips native free, and leaves [closed]
     * as the backstop — degrades to leak-instead-of-SIGSEGV. close() is non-suspend
     * (main thread), so it uses tryLock rather than runBlocking.
     */
    private val nativeGuard = Mutex()
    // TachiyomiAT: cooperative close flag. close() can race an in-flight
    // analyze()/inpaint() and free native ONNX sessions mid-call past a
    // suspension point (SIGSEGV, not catchable). analyze()/inpaint() poll this
    // before each ONNX call and bail cleanly; [nativeGuard] closes the window.
    @Volatile
    private var closed = false

    // TachiyomiAT: cached translation_diagnostics pref. Resolved lazily (not at
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

    // TachiyomiAT: cached reading-order pref (AUTO derives RTL from source
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

    private suspend fun initialize() {
        initMutex.withLock {
            if (initialized) return
            if (initFailed) {
                throw IllegalStateException("ONNX recognition engine failed to initialize previously")
            }
            val startTime = System.nanoTime()
            try {
                val paths = modelStore.ensureModels()
                logcat(LogPriority.INFO) { "ONNX init: starting detector initialization" }
                detector = OnnxPageTextDetector().also { it.initialize(paths.detectorModel) }
                logcat(LogPriority.INFO) { "ONNX init: detector OK, starting OCR initialization (language=$language, model=$ocrModel)" }
                // TachiyomiAT: panel detector is optional context; best-effort
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
                        it.initialize(paddlePaths.recognitionModel, paddlePaths.dictionary)
                        // TachiyomiAT: PaddleOCR rec reads horizontal lines; vertical
                        // columns must be split first. The det model replaces the
                        // ink-gap heuristic for that split (best-effort; falls back
                        // to the heuristic if the det asset is missing).
                        if (modelStore.paddleOcrV6DetAvailable() || modelStore.paddleOcrV6DetAssetsAvailable()) {
                            try {
                                val detPaths = modelStore.ensurePaddleOcrV6Det()
                                paddleDet = PaddleOcrV6DetEngine().also { it.initialize(detPaths.detectionModel) }
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
                    }
                    OcrModel.MLKIT -> MlKitRoiOcrEngine(language)
                }
                // TachiyomiAT: load PaddleOCR-v6 det for the INPAINTER (free-text
                // erase mask) whenever the asset is available, independent of OCR
                // model. Previously gated behind a removed experimental flag, so
                // the default MANGAOCR path never got Paddle-driven masking. The
                // PADDLEOCR_V6_SMALL branch above already built it for rec.
                if (ocrModel != OcrModel.PADDLEOCR_V6_SMALL && paddleDet == null &&
                    (modelStore.paddleOcrV6DetAvailable() || modelStore.paddleOcrV6DetAssetsAvailable())
                ) {
                    try {
                        val detPaths = modelStore.ensurePaddleOcrV6Det()
                        paddleDet = PaddleOcrV6DetEngine().also { it.initialize(detPaths.detectionModel) }
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

    override suspend fun analyze(bitmap: Bitmap): PageTranslation {
        if (!initialized) initialize()
        // TachiyomiAT: bail before any ONNX call if the engine was closed
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
        val startTime = System.nanoTime()
        TranslationMemoryBudget.logSnapshot("analyze_start", bitmap.width, bitmap.height)
        // TachiyomiAT: hold nativeGuard across detect + the per-ROI OCR loop so
        // close() cannot free a native session out from under an in-flight
        // OrtSession.run(). Each native pass (detect + every recognize()) must
        // be inside this critical section.
        val analyzed = try {
            nativeGuard.withLock {
            if (closed) throw IllegalStateException("ONNX recognition engine closed before detect")
            val detections = localDetector.detect(bitmap)
            val bubbleMasksRaw = bubbleSegmenter?.segment(bitmap) ?: emptyList()
            val bubbleMasks = bubbleMasksRaw.map { eu.kanade.translation.segmentation.BubbleMaskRle.encode(it) }
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
            // TachiyomiAT: stash on the per-page translation so concurrent pages
            // can't overwrite each other's data before inpaint() reads it back.
            lockedPageTranslation.allTextDetections =
                filteredDetections + (textDetections.filter { it !in filteredDetections && it !in geometricallyDeduped })
            logcat(LogPriority.INFO) {
                "ONNX recognition detections: bubbles=${bubbles.size} text=${textDetections.size} " +
                    "geometricText=${geometricallyDeduped.size} filteredText=${filteredDetections.size}"
            }
            val lockedRecognizedBlocks = mutableListOf<RecognizedBlock>()
            val engine = localOcrEngine
            for (detection in filteredDetections) {
                // TachiyomiAT: cooperative close — bail out of the per-ROI OCR
                // loop if close() ran between iterations, before the native call.
                if (closed) throw IllegalStateException("ONNX recognition engine closed during OCR loop")
                // TachiyomiAT: horizontal-line engines (PaddleOCR) need context
                // padding to avoid edge-effect failures; native-vertical engines
                // get tight unbounded crops.
                val pad = if (engine.prefersHorizontalText) 12 else 0
                val unpaddedCrop = cropBitmap(bitmap, detection.bbox[0], detection.bbox[1], detection.bbox[2], detection.bbox[3])
                val crop = if (pad > 0) cropBitmap(bitmap, detection.bbox[0] - pad, detection.bbox[1] - pad, detection.bbox[2] + pad, detection.bbox[3] + pad) else unpaddedCrop
            // TachiyomiAT: PaddleOCR's rec head is a CNN+CTC trained on HORIZONTAL
            // text lines; a tall vertical crop yields garbage. Two-part fix:
            //  1. COLUMN SPLITTING: split by ink-gap, OCR each column separately,
            //     concatenate right-to-left (whole-box 1/6 vs per-column 6/6).
            //  2. CCW ROTATION per column; CW yields upside-down text (0/6 vs 6/6).
            // OCR-only; rendering stays vertical via direction="TTB" below.
            // Gated on prefersHorizontalText — MangaOcr/ML Kit read vertical natively.
            val bbox = detection.bbox
            val boxWidthPre = (bbox[2] - bbox[0]).toFloat()
            val boxHeightPre = (bbox[3] - bbox[1]).toFloat()
            val isVerticalLanguage = language == TextRecognizerLanguage.JAPANESE ||
                language == TextRecognizerLanguage.CHINESE ||
                language == TextRecognizerLanguage.KOREAN
            val tallVertical = isVerticalLanguage && boxHeightPre > boxWidthPre * 1.5f
            // TachiyomiAT: PaddleOCR rec reads a single horizontal strip, so any
            // multi-line bubble must be split by the det model first. Run the
            // det-based split on EVERY Paddle bubble — recognizeDetColumns
            // classifies each line as horizontal or vertical and joins correctly,
            // so English multi-line paragraphs read properly (previously gated to
            // tall CJK boxes only and collapsed English into one garbled line).
            // MangaOcr/ML Kit read vertical natively and stay on the single-read path.
            val paddleMultiLine = engine.prefersHorizontalText && paddleDet != null
            // CJK vertical bubble on a Paddle engine WITHOUT the det asset: a
            // horizontal read of vertical text is reliably wrong, so use the
            // ink-gap heuristic column split (preserves prior behavior).
            val paddleVerticalHeuristic = isVerticalLanguage && engine.prefersHorizontalText &&
                paddleDet == null && boxHeightPre > boxWidthPre * 1.5f
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
            // TachiyomiAT: per-block OCR diagnostics, gated by the opt-in
            // translation_diagnostics pref so recognition quality is inspectable.
            if (resolveDiagnostics()) {
                logcat(LogPriority.INFO) {
                    "[ocr_block] box=[${bbox[0].toInt()},${bbox[1].toInt()},${bbox[2].toInt()},${bbox[3].toInt()}] " +
                        "size=${(bbox[2] - bbox[0]).toInt()}x${(bbox[3] - bbox[1]).toInt()} " +
                        "rotated=${if (rotatedForOcr) "90ccw" else "no"} text=\"$text\""
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
            // TachiyomiAT: text color sampled against the ORIGINAL bitmap as a
            // first pass; ChapterTranslator recomputes AFTER inpainting (against
            // the cleaned bitmap). Kept as a fallback if inpainting is skipped.
            val renderColors = RenderColorEstimator.estimate(
                bitmap,
                bbox[0], bbox[1], bbox[2], bbox[3],
                parentBbox,
            )
            // TachiyomiAT: render direction. The renderer only goes vertical when
            // direction=="TTB"; without this, Japanese vertical text was rendered
            // horizontally even though MangaOcr read it correctly. Height-to-width
            // ratio heuristic, gated on CJK languages so Latin text is unaffected.
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
                    }
                ),
                ),
            )
            }
            RecognizedAnalyzeResult(lockedPageTranslation, lockedRecognizedBlocks)
        }
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
        // TachiyomiAT: second dedupe pass. removePostOcrDuplicateBlocks only
        // collapses identical-text overlaps; this collapses remaining geometric
        // overlaps (cross-label, differing-text) so two overlapping boxes never
        // reach the renderer.
        val dedupedBlocks = PageTranslationHelper.dedupeGeometricOverlaps(
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
        // TachiyomiAT: assign OCR blocks to panels now that the block list is
        // frozen. See assignPanels() for the conservative no-fallback policy.
        assignPanels(bitmap, pageTranslation)
        // TachiyomiAT: capture the durable inpaint mask at OCR time, before
        // translation/watermark filtering removes blocks and before the page is
        // persisted (allTextDetections is @Transient). Persisting it means a
        // resumed batch still erases detector-only + watermark regions, not just
        // the surviving OCR blocks. See PageInpaintingPlanner + contract #14.
        pageTranslation.inpaintMaskBoxes = PageInpaintingPlanner.computeMask(pageTranslation)
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
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
        // TachiyomiAT: hold nativeGuard across the inpaint pass so close() cannot
        // free the AOT inpainter's native session mid-run. Mirrors analyze().
        // Re-check [closed] inside the lock. Box/mask computation is delegated to
        // PageInpaintingEngine (do not duplicate here — an earlier copy was
        // unreachable and masked the real planner path).
        return try {
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
    }

    private data class RecognizedBlock(
        val detection: Detection,
        val block: TranslationBlock,
    )

    /**
     * TachiyomiAT: carrier for analyze()'s native critical-section output. The
     * detect + OCR loop runs under [nativeGuard] and returns this so the
     * post-lock dedupe/assembly (removePostOcrDuplicateBlocks, blocks.addAll)
     * runs outside the native lock — it is pure Kotlin and need not block close().
     */
    private data class RecognizedAnalyzeResult(
        val pageTranslation: PageTranslation,
        val recognizedBlocks: MutableList<RecognizedBlock>,
    )

    /**
     * TachiyomiAT: run panel detection on the page bitmap and assign each final
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
        // TachiyomiAT: derive stable bubble indices from parent geometry BEFORE
        // panel assignment (see assignBubbleIndices). Must run first so the
        // bubbleIndex is present on each block before panel fields are set.
        assignBubbleIndices(pageTranslation)
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
                block.x, block.y, block.x + block.width, block.y + block.height,
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
     * TachiyomiAT: assign a stable per-page bubble index to each block based on
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
        try { detector?.close() } catch (e: Exception) { logcat(LogPriority.ERROR, e) { "Error closing detector" } } finally { detector = null }
        try { roiOcrEngine?.close() } catch (e: Exception) { logcat(LogPriority.ERROR, e) { "Error closing roiOcrEngine" } } finally { roiOcrEngine = null }
        try { paddleDet?.close() } catch (e: Exception) { logcat(LogPriority.ERROR, e) { "Error closing paddleDet" } } finally { paddleDet = null }
        try { inpainting?.close() } catch (e: Exception) { logcat(LogPriority.ERROR, e) { "Error closing inpainting" } } finally { inpainting = null }
        try { panelDetector?.close() } catch (e: Exception) { logcat(LogPriority.ERROR, e) { "Error closing panelDetector" } } finally { panelDetector = null }
        try { bubbleSegmenter?.close() } catch (e: Exception) { logcat(LogPriority.ERROR, e) { "Error closing bubbleSegmenter" } } finally { bubbleSegmenter = null }
        pageInpainter = null
        initialized = false
    }

    override fun close() {
        // TachiyomiAT: set the cooperative close flag FIRST so an in-flight
        // analyze()/inpaint() polling [closed] between ONNX calls bails cleanly
        // before touching a session freed below (avoids a native crash).
        closed = true
        // TachiyomiAT: tryLock (non-suspend, never blocks the main thread). Every
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
        // TachiyomiAT: free off-heap pooled state held by sub-engines WITHOUT
        // tearing them down. Called by OOM recovery so native pressure from one
        // page doesn't carry into the next. Guarded so partial init is a no-op.
        try { detector?.reclaimPooledMemory() } catch (_: Exception) {}
        try { roiOcrEngine?.reclaimPooledMemory() } catch (_: Exception) {}
        try { paddleDet?.reclaimPooledMemory() } catch (_: Exception) {}
        try { inpainting?.reclaimPooledMemory() } catch (_: Exception) {}
        try { panelDetector?.reclaimPooledMemory() } catch (_: Exception) {}
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
            )
            TranslationSafetyPrimitives.drainChildBuffersGuarded(lockHeld = false, engines = engines)
        } finally {
            nativeGuard.unlock()
        }
    }

    private fun suppressCrossLabelDuplicates(
        textDetections: List<Detection>,
        bubbles: List<Detection>,
    ): List<Detection> {
        if (textDetections.size < 2) return textDetections
        val parentMap = mutableMapOf<Int, Detection?>()
        for (det in textDetections) {
            val cx = (det.bbox[0] + det.bbox[2]) / 2.0
            val cy = (det.bbox[1] + det.bbox[3]) / 2.0
            val parent = bubbles
                .filter { b -> cx >= b.bbox[0] && cx <= b.bbox[2] && cy >= b.bbox[1] && cy <= b.bbox[3] }
                .minByOrNull { (it.bbox[2] - it.bbox[0]) * (it.bbox[3] - it.bbox[1]) }
            parentMap[System.identityHashCode(det)] = parent
        }
        val keep = textDetections.toMutableList()
        val toRemove = mutableSetOf<Detection>()
        for (i in keep.indices) {
            if (keep[i] in toRemove) continue
            val parentI = parentMap[System.identityHashCode(keep[i])]
            if (parentI == null) continue
            for (j in i + 1 until keep.size) {
                if (keep[j] in toRemove) continue
                val parentJ = parentMap[System.identityHashCode(keep[j])]
                if (parentJ !== parentI) continue
                if (keep[i].label == keep[j].label) continue
                val iou = BoxGeometry.iou(keep[i].bbox, keep[j].bbox)
                if (iou > 0.3f) {
                    val victim = if (keep[i].score < keep[j].score) keep[i] else keep[j]
                    toRemove.add(victim)
                }
            }
        }
        if (toRemove.isNotEmpty()) {
            keep.removeAll(toRemove)
            logcat(LogPriority.INFO) {
                "Cross-label suppression removed ${toRemove.size} overlapping detections"
            }
        }
        return keep
    }

    private fun dedupeTextDetections(
        textDetections: List<Detection>,
        bubbles: List<Detection>,
    ): List<Detection> {
        if (textDetections.size < 2) return textDetections

        val parentMap = textDetections.associateWith { findParentBubble(it, bubbles) }
        val removed = mutableSetOf<Detection>()
        for (label in setOf(1, 2)) {
            val candidates = textDetections
                .filter { it.label == label }
                .sortedWith(compareByDescending<Detection> { parentContainmentScore(it, parentMap[it]) }.thenByDescending { it.score })
            val kept = mutableListOf<Detection>()
            for (det in candidates) {
                if (kept.any { isTextBoxDuplicate(det.bbox, it.bbox) }) {
                    removed.add(det)
                } else {
                    kept.add(det)
                }
            }
        }

        if (removed.isEmpty()) return textDetections
        logcat(LogPriority.INFO) { "Geometric OCR dedupe removed ${removed.size} same-label text boxes before OCR" }
        return textDetections.filter { it !in removed }
    }

    private fun removePostOcrDuplicateBlocks(blocks: List<RecognizedBlock>): List<RecognizedBlock> {
        if (blocks.size < 2) return blocks
        val keep = blocks.toMutableList()
        val removed = mutableSetOf<RecognizedBlock>()
        for (i in keep.indices) {
            if (keep[i] in removed) continue
            val textI = normalizeOcrText(keep[i].block.text)
            if (textI.isBlank()) continue
            for (j in i + 1 until keep.size) {
                if (keep[j] in removed) continue
                if (textI != normalizeOcrText(keep[j].block.text)) continue
                if (!isTextBoxDuplicate(keep[i].detection.bbox, keep[j].detection.bbox)) continue
                val victim = if (keep[i].detection.score < keep[j].detection.score) keep[i] else keep[j]
                removed.add(victim)
            }
        }
        if (removed.isEmpty()) return blocks
        keep.removeAll(removed)
        logcat(LogPriority.INFO) { "Post-OCR duplicate removal dropped ${removed.size} repeated text blocks" }
        return keep
    }

    private fun findParentBubble(det: Detection, bubbles: List<Detection>): Detection? {
        val cx = (det.bbox[0] + det.bbox[2]) / 2.0
        val cy = (det.bbox[1] + det.bbox[3]) / 2.0
        return bubbles
            .filter { b -> cx >= b.bbox[0] && cx <= b.bbox[2] && cy >= b.bbox[1] && cy <= b.bbox[3] }
            .minByOrNull { (it.bbox[2] - it.bbox[0]) * (it.bbox[3] - it.bbox[1]) }
    }

    private fun parentContainmentScore(det: Detection, parent: Detection?): Float {
        val parentBox = parent?.bbox ?: return 0f
        val area = BoxGeometry.bboxArea(det.bbox)
        if (area <= 0) return 0f
        return BoxGeometry.intersectionArea(det.bbox, parentBox).toFloat() / area.toFloat()
    }

    private fun normalizeOcrText(text: String): String = text
        .lowercase()
        .filterNot { it.isWhitespace() || it.isISOControl() }

    private fun isTextBoxDuplicate(a: IntArray, b: IntArray): Boolean =
        BoxGeometry.isGeometricDuplicate(a, b, BoxGeometry.TEXT_DEDUP_THRESHOLDS)

    private fun cropBitmap(source: Bitmap, x1: Int, y1: Int, x2: Int, y2: Int): Bitmap {
        val clampedX1 = x1.coerceIn(0, source.width)
        val clampedY1 = y1.coerceIn(0, source.height)
        val clampedX2 = x2.coerceIn(clampedX1, source.width)
        val clampedY2 = y2.coerceIn(clampedY1, source.height)
        if (clampedX2 <= clampedX1 || clampedY2 <= clampedY1) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        return Bitmap.createBitmap(source, clampedX1, clampedY1, clampedX2 - clampedX1, clampedY2 - clampedY1)
    }

    /**
     * TachiyomiAT: recognize a (possibly multi-line) bubble by first running the
     * PaddleOCR det model over the whole crop to recover individual text lines,
     * then OCR-ing each line with the correct orientation via [recognizeDetColumns].
     * Handles both stacked horizontal lines (English/Korean paragraphs) and
     * vertical columns (CJK), so it replaces the old vertical-only split.
     *
     * [verticalFallback] selects the empty/failed-det fallback: a tall CJK bubble
     * uses the ink-gap column heuristic; anything else (e.g. an English bubble the
     * det model missed) falls back to a single horizontal rec read instead of
     * being force-rotated.
     */
    private suspend fun recognizeMultiLine(
        engine: RoiOcrEngine,
        crop: Bitmap,
        paddleDet: PaddleOcrV6DetEngine?,
        verticalFallback: Boolean,
    ): String {
        if (paddleDet != null) {
            try {
                // Use the standard DB BOX_THRESH (0.45f) for the rec path; 0.34 admitted noise.
                val lines = paddleDet.detectLines(crop, thresh = 0.2f, boxThresh = DbPostProcess.Defaults.BOX_THRESH)
                if (lines.isNotEmpty()) {
                    return recognizeDetColumns(engine, crop, lines)
                }
                // Det returned nothing -> fall through to the fallback. A true
                // negative on a real text region is preferable to empty output.
                logcat(LogPriority.INFO) {
                    "[paddle_det] returned 0 lines; falling back to " +
                        if (verticalFallback) "ink-gap heuristic" else "single read"
                }
            } catch (e: Exception) {
                // Never let a det-model failure abort OCR — degrade to the fallback
                // for this ROI and log loudly (AGENT.md: never suppress errors).
                logcat(LogPriority.WARN, e) {
                    "[paddle_det] failed; falling back to " +
                        if (verticalFallback) "ink-gap heuristic" else "single read" + " for this ROI"
                }
            }
        }
        return if (verticalFallback) recognizeHeuristicColumns(engine, crop) else recognizeSingleLine(engine, crop)
    }

    private suspend fun recognizeSingleLine(engine: RoiOcrEngine, crop: Bitmap): String {
        val (text, conf) = engine.recognizeWithConf(crop)
        // PaddleOCR's CTC decoder reports a meaningful confidence; MangaOcr returns
        // default 1.0 (no real score). The conf < 1f guard keeps MangaOcr's default
        // from being filtered here.
        if (conf < OCR_MIN_CONFIDENCE && conf < 1f) return ""
        return if (text.isNotEmpty() && OcrTextFilter.isUsable(text, language)) text else ""
    }

    /**
     * Recognize using PP-OCRv6 det-detected text lines. Each [TextLine.bbox] is in
     * crop pixel coords; sort in manga reading order (vertical: right-to-left by
     * x-center; horizontal: top-to-bottom), crop, rotate CCW if vertical, OCR.
     */
    private suspend fun recognizeDetColumns(
        engine: RoiOcrEngine,
        crop: Bitmap,
        lines: List<eu.kanade.translation.ocr.TextLine>,
    ): String {
        // Classify + order. Vertical columns sort right-to-left (manga); horizontal
        // lines sort top-to-bottom. Vertical first, then horizontal, mirroring the
        // validated Python prototype (det_rec_any.py).
        data class Item(val bbox: IntArray, val vertical: Boolean, val sortKey: Int)
        val items = lines.mapNotNull { l ->
            val b = l.bbox
            if (b.size < 4 || b[2] <= b[0] || b[3] <= b[1]) return@mapNotNull null
            val w = b[2] - b[0]
            val h = b[3] - b[1]
            // TachiyomiAT: det boxes are real text lines (not raw pixel runs),
            // so the floor is far smaller than the ink-gap heuristic's 12px.
            // Back-projected det lines in small ROIs are legitimately 6-10px;
            // 12px dropped them all and fell through to a whole-crop read that
            // squashed a tall bubble into garbage. 4px only rejects fragments.
            if (w < MIN_DET_LINE_PX || h < MIN_DET_LINE_PX) return@mapNotNull null
            if (w > MAX_COLUMN_WIDTH_PX || h > MAX_COLUMN_HEIGHT_PX) return@mapNotNull null
            val vertical = h > w * 1.5f
            // Vertical: sort by x-center DESC (right-to-left). Horizontal: by y ASC.
            val key = if (vertical) -(b[0] + b[2]) / 2 else (b[1] + b[3]) / 2
            Item(b, vertical, key)
        }.sortedBy { it.sortKey }

        if (items.isEmpty()) {
            val rotated = rotateCcw(crop)
            return try {
                val part = engine.recognizeWithConf(rotated).first
                if (part.isNotEmpty() && OcrTextFilter.isUsable(part, language)) part else ""
            } finally {
                rotated.recycle()
            }
        }

        val verticalCjk = language == TextRecognizerLanguage.JAPANESE ||
            language == TextRecognizerLanguage.CHINESE ||
            language == TextRecognizerLanguage.KOREAN
        val parts = ArrayList<String>(items.size)
        for (it in items) {
            if (closed) throw IllegalStateException("ONNX recognition engine closed during OCR loop")
            val b = it.bbox
            val columnCrop = cropBitmap(crop, b[0], b[1], b[2], b[3])
            try {
                // CJK vertical columns are split into individual glyphs before rec
                // (the rec CTC head misreads a rotated whole column); other vertical
                // boxes are rotated whole. Horizontal boxes are read as-is.
                val part = when {
                    it.vertical && verticalCjk && engine.prefersHorizontalText -> recognizeVerticalColumnPerChar(engine, columnCrop)
                    it.vertical && engine.prefersHorizontalText -> {
                        val rotated = rotateCcw(columnCrop)
                        try { recognizeSingleLine(engine, rotated) } finally { rotated.recycle() }
                    }
                    else -> recognizeSingleLine(engine, columnCrop)
                }
                if (part.isNotEmpty()) parts.add(part)
            } finally {
                columnCrop.recycle()
            }
        }
        return parts.joinToString(language.joinSeparator())
    }

    /**
     * Original ink-gap-heuristic column splitter + per-column OCR. Kept as the
     * fallback path when the det model is unavailable or returns no lines.
     */
    private suspend fun recognizeHeuristicColumns(
        engine: RoiOcrEngine,
        crop: Bitmap,
    ): String {
        val columns = detectVerticalColumns(crop)
        if (columns.size <= 1) {
            val rotated = rotateCcw(crop)
            return try {
                val part = engine.recognizeWithConf(rotated).first
                if (part.isNotEmpty() && OcrTextFilter.isUsable(part, language)) part else ""
            } finally {
                rotated.recycle()
            }
        }
        val verticalCjk = language == TextRecognizerLanguage.JAPANESE ||
            language == TextRecognizerLanguage.CHINESE ||
            language == TextRecognizerLanguage.KOREAN
        val parts = ArrayList<String>(columns.size)
        // Manga vertical text reads right-to-left. [detectVerticalColumns] returns
        // columns in left-to-right pixel order, so iterate them in reverse.
        for (i in columns.indices.reversed()) {
            val (x0, x1) = columns[i]
            if (x1 - x0 < MIN_COLUMN_WIDTH_PX) continue
            val columnCrop = Bitmap.createBitmap(crop, x0, 0, x1 - x0, crop.height)
            try {
                val part = if (verticalCjk && engine.prefersHorizontalText) {
                    recognizeVerticalColumnPerChar(engine, columnCrop)
                } else if (engine.prefersHorizontalText) {
                    val rotated = rotateCcw(columnCrop)
                    try { recognizeSingleLine(engine, rotated) } finally { rotated.recycle() }
                } else {
                    recognizeSingleLine(engine, columnCrop)
                }
                if (part.isNotEmpty()) parts.add(part)
            } finally {
                columnCrop.recycle()
            }
        }
        return parts.joinToString(language.joinSeparator())
    }

    private fun rotateCcw(bitmap: Bitmap): Bitmap {
        val matrix = android.graphics.Matrix()
        matrix.postRotate(-90f)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * TachiyomiAT: split a single vertical text column into individual glyph
     * cells by row ink-gap analysis (the transpose of [detectVerticalColumns]),
     * then recognize each glyph on its own after a 90° CCW rotation. The rec
     * CTC head is trained on horizontal lines and misreads a rotated whole
     * multi-glyph column; recognizing one glyph at a time is the maintainer-
     * validated workaround for vertical CJK (see docs/ocr-engine-notes.md).
     * Returns glyphs in top-to-bottom reading order joined with the language
     * separator. A single-glyph (or un-splittable) column degrades to one
     * rotated whole-column read, so this never does worse than the whole path.
     */
    private suspend fun recognizeVerticalColumnPerChar(
        engine: RoiOcrEngine,
        columnCrop: Bitmap,
    ): String {
        val rows = detectVerticalGlyphRows(columnCrop)
        if (rows.size <= 1) {
            val rotated = rotateCcw(columnCrop)
            return try {
                recognizeSingleLine(engine, rotated)
            } finally {
                rotated.recycle()
            }
        }
        val parts = ArrayList<String>(rows.size)
        for ((y0, y1) in rows) {
            if (closed) throw IllegalStateException("ONNX recognition engine closed during OCR loop")
            if (y1 - y0 < MIN_COLUMN_WIDTH_PX) continue
            val glyphCrop = Bitmap.createBitmap(columnCrop, 0, y0, columnCrop.width, y1 - y0)
            try {
                val rotated = rotateCcw(glyphCrop)
                try {
                    val part = recognizeSingleLine(engine, rotated)
                    if (part.isNotEmpty()) parts.add(part)
                } finally {
                    rotated.recycle()
                }
            } finally {
                glyphCrop.recycle()
            }
        }
        return parts.joinToString(language.joinSeparator())
    }

    /**
     * TachiyomiAT: detect horizontal glyph bands within a single vertical column
     * by row ink-gap analysis. Returns glyph y-ranges [(y0,y1), ...] in
     * top-to-bottom order. Mirror of [detectVerticalColumns] transposed to the
     * row axis (ink fraction per row, ink-runs merged across narrow gaps). The
     * same thresholds are reused since they are generic ink-band size floors.
     */
    private fun detectVerticalGlyphRows(crop: Bitmap): List<Pair<Int, Int>> {
        val w = crop.width
        val h = crop.height
        if (w < 2 || h < 2) return listOf(0 to h)
        val pixels = IntArray(w * h)
        crop.getPixels(pixels, 0, w, 0, 0, w, h)
        val inkFraction = FloatArray(h)
        for (y in 0 until h) {
            var dark = 0
            for (x in 0 until w) {
                val px = pixels[y * w + x]
                val lum = (0.299f * ((px shr 16) and 0xFF) +
                    0.587f * ((px shr 8) and 0xFF) +
                    0.114f * (px and 0xFF)).toInt()
                if (lum < INK_LUMINANCE_THRESHOLD) dark++
            }
            inkFraction[y] = dark.toFloat() / w.toFloat()
        }
        val rows = ArrayList<Pair<Int, Int>>()
        var inRun = false
        var runStart = 0
        var gapSinceInk = 0
        for (y in 0 until h) {
            val hasInk = inkFraction[y] >= COLUMN_GAP_INK_FRACTION
            if (hasInk) {
                if (!inRun) {
                    runStart = y
                    inRun = true
                }
                gapSinceInk = 0
            } else if (inRun) {
                gapSinceInk++
                if (gapSinceInk >= MIN_COLUMN_GAP_PX) {
                    val end = y - gapSinceInk
                    if (end - runStart >= MIN_COLUMN_WIDTH_PX) {
                        rows.add(runStart to end)
                    }
                    inRun = false
                    gapSinceInk = 0
                }
            }
        }
        if (inRun) {
            val end = if (gapSinceInk > 0) h - gapSinceInk else h
            if (end - runStart >= MIN_COLUMN_WIDTH_PX) {
                rows.add(runStart to end)
            }
        }
        return rows
    }

    /**
     * TachiyomiAT: detect vertical text columns in a TALL crop by ink-gap analysis.
     * Returns column x-ranges [(x0,x1), ...] in left-to-right pixel order.
     *
     * Algorithm: a column is a horizontal x-band containing ink (dark text strokes);
     * a wide-enough ink-free x-band separates columns. Within-column inter-character
     * gaps are NARROW (typical glyph spacing) and are merged so a single column of
     * several stacked glyphs is not split apart; only wider inter-COLUMN gaps split.
     */
    private fun detectVerticalColumns(crop: Bitmap): List<Pair<Int, Int>> {
        val w = crop.width
        val h = crop.height
        if (w < 2 || h < 2) return listOf(0 to w)
        val pixels = IntArray(w * h)
        crop.getPixels(pixels, 0, w, 0, 0, w, h)
        val inkFraction = FloatArray(w)
        for (x in 0 until w) {
            var dark = 0
            for (y in 0 until h) {
                val px = pixels[y * w + x]
                val lum = (0.299f * ((px shr 16) and 0xFF) +
                    0.587f * ((px shr 8) and 0xFF) +
                    0.114f * (px and 0xFF)).toInt()
                if (lum < INK_LUMINANCE_THRESHOLD) dark++
            }
            inkFraction[x] = dark.toFloat() / h.toFloat()
        }
        val columns = ArrayList<Pair<Int, Int>>()
        var inRun = false
        var runStart = 0
        var gapSinceInk = 0
        for (x in 0 until w) {
            val hasInk = inkFraction[x] >= COLUMN_GAP_INK_FRACTION
            if (hasInk) {
                if (!inRun) {
                    runStart = x
                    inRun = true
                }
                gapSinceInk = 0
            } else if (inRun) {
                gapSinceInk++
                if (gapSinceInk >= MIN_COLUMN_GAP_PX) {
                    val end = x - gapSinceInk
                    if (end - runStart >= MIN_COLUMN_WIDTH_PX) {
                        columns.add(runStart to end)
                    }
                    inRun = false
                    gapSinceInk = 0
                }
            }
        }
        if (inRun) {
            val end = if (gapSinceInk > 0) w - gapSinceInk else w
            if (end - runStart >= MIN_COLUMN_WIDTH_PX) {
                columns.add(runStart to end)
            }
        }
        return columns
    }

    private fun trimParentBbox(
        parent: IntArray,
        textBbox: IntArray,
        siblingBubbles: List<IntArray>,
    ): IntArray {
        var px1 = parent[0]
        var py1 = parent[1]
        var px2 = parent[2]
        var py2 = parent[3]
        val tx1 = textBbox[0]
        val ty1 = textBbox[1]
        val tx2 = textBbox[2]
        val ty2 = textBbox[3]

        for (sib in siblingBubbles) {
            val sx1 = sib[0]
            val sy1 = sib[1]
            val sx2 = sib[2]
            val sy2 = sib[3]
            if (sib.contentEquals(parent)) continue

            val sibArea = max(0, sx2 - sx1) * max(0, sy2 - sy1)
            if (sibArea == 0) continue
            val inter = BoxGeometry.intersectionArea(intArrayOf(px1, py1, px2, py2), sib)
            if (inter < 0.45f * sibArea) continue

            val scx = (sx1 + sx2) / 2.0
            val scy = (sy1 + sy2) / 2.0
            val tcx = (tx1 + tx2) / 2.0
            val tcy = (ty1 + ty2) / 2.0

            val overlapX = min(px2, sx2) - max(px1, sx1)
            val overlapY = min(py2, sy2) - max(py1, sy1)
            if (overlapX <= 0 || overlapY <= 0) continue

            if (overlapX < overlapY) {
                if (scx < tcx) {
                    val newX1 = sx2 + 2
                    val pad = max(4, (0.05 * (px2 - newX1)).toInt())
                    if (newX1 + pad <= tx1) px1 = newX1
                } else if (scx > tcx) {
                    val newX2 = sx1 - 2
                    val pad = max(4, (0.05 * (newX2 - px1)).toInt())
                    if (newX2 - pad >= tx2) px2 = newX2
                }
            } else {
                if (scy < tcy) {
                    val newY1 = sy2 + 2
                    val pad = max(4, (0.05 * (py2 - newY1)).toInt())
                    if (newY1 + pad <= ty1) py1 = newY1
                } else if (scy > tcy) {
                    val newY2 = sy1 - 2
                    val pad = max(4, (0.05 * (newY2 - py1)).toInt())
                    if (newY2 - pad >= ty2) py2 = newY2
                }
            }
        }

        if (px1 >= px2 || py1 >= py2) return parent
        if (px1 > tx1 + 1 || py1 > ty1 + 1 || px2 < tx2 - 1 || py2 < ty2 - 1) return parent

        return intArrayOf(px1, py1, px2, py2)
    }

    private fun selectParentBubble(
        detection: Detection,
        textBbox: IntArray,
        bubbles: List<Detection>,
        centerX: Double,
        centerY: Double,
    ): Detection? {
        if (detection.label != 1 && detection.label != 2) return null
        val containing = bubbles
            .filter { b ->
                centerX >= b.bbox[0] && centerX <= b.bbox[2] &&
                    centerY >= b.bbox[1] && centerY <= b.bbox[3]
            }
            .minByOrNull {
                (it.bbox[2] - it.bbox[0]) * (it.bbox[3] - it.bbox[1])
            }
        if (containing != null) return containing

        val textArea = max(1, textBbox[2] - textBbox[0]) * max(1, textBbox[3] - textBbox[1])
        return bubbles
            .mapNotNull { bubble ->
                val overlap = BoxGeometry.intersectionArea(textBbox, bubble.bbox)
                if (overlap >= textArea * MIN_PARENT_TEXT_OVERLAP_FRACTION) bubble to overlap else null
            }
            .maxWithOrNull(compareBy<Pair<Detection, Int>> { it.second }.thenBy { it.first.score })
            ?.first
    }

    private companion object {
        // TachiyomiAT: ink-gap column-split thresholds (tuned against the Python
        // repro: whole-box 1/6 vs per-column 6/6 on multi-column cases).
        // INK_LUMINANCE_THRESHOLD: pixels darker than this count as text ink.
        // COLUMN_GAP_INK_FRACTION: x-column below this ink fraction is a gap candidate.
        // MIN_COLUMN_GAP_PX: only an ink-free run this wide splits columns; narrower
        //   inter-character gaps are merged.
        // MIN_COLUMN_WIDTH_PX: ink-runs narrower than this are discarded as noise.
        private const val INK_LUMINANCE_THRESHOLD = 110
        private const val COLUMN_GAP_INK_FRACTION = 0.02f
        private const val MIN_COLUMN_GAP_PX = 10
        private const val MIN_COLUMN_WIDTH_PX = 12
        // TachiyomiAT: min det-line dimension (see recognizeDetColumns). Distinct
        // from MIN_COLUMN_WIDTH_PX: det already filtered noise, so 4px only rejects
        // fragments while 12px wrongly dropped legitimate small ROI text lines.
        private const val MIN_DET_LINE_PX = 4
        private const val MIN_PARENT_TEXT_OVERLAP_FRACTION = 0.20f
        // TachiyomiAT: det-line size guard for [recognizeDetColumns]. Rejects
        // sub-glyph noise (det sometimes emits tiny fragments) and false merges
        // (huge regions spanning multiple bubbles). Sized in crop pixel coords.
        private const val MAX_COLUMN_WIDTH_PX = 400
        private const val MAX_COLUMN_HEIGHT_PX = 800
        // TachiyomiAT: minimum confidence for PaddleOCR CTC output. PaddleOCR
        // reports meaningful confidence values; reads below this threshold are
        // likely garbage and discarded before translation. MangaOcr returns
        // default 1.0 (no real score) and is exempt via the conf < 1f guard.
        private const val OCR_MIN_CONFIDENCE = 0.5f
        // TachiyomiAT: text-color constants moved to RenderColorEstimator (and
        // fixed there — the legacy INVERTED_TEXT_COLORS was a copy-paste of the
        // default constant, both returning dark-gray text 0xFF1A1A1A, which made
        // dark-inpainted bubbles illegible).
    }
}
