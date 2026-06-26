package eu.kanade.translation.recognition

import android.content.Context
import android.graphics.Bitmap
import eu.kanade.translation.detection.Detection
import eu.kanade.translation.detection.OnnxPageTextDetector
import eu.kanade.translation.inpainting.AOTInpainting
import eu.kanade.translation.inpainting.InpaintingMode
import eu.kanade.translation.inpainting.PageInpaintingEngine
import eu.kanade.translation.inpainting.PageInpaintingPlanner
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageTranslationHelper
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocr.MangaOcrEngine
import eu.kanade.translation.ocr.MlKitRoiOcrEngine
import eu.kanade.translation.ocr.PaddleOcrV6DetEngine
import eu.kanade.translation.ocr.PaddleOcrV6SmallEngine
import eu.kanade.translation.ocr.RoiOcrEngine
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.rendering.RenderColorEstimator
import eu.kanade.translation.runtime.onnx.OnnxModelStore
import eu.kanade.translation.util.TranslationMemoryBudget
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
     * close(). Every close() call site is already guarded by the translator
     * permit (closeEngines tryAcquires it; the rebuild path holds it), so in
     * practice a native run and a close never overlap. This mutex is the
     * defense-in-depth belt-and-suspenders: analyze()/inpaint() hold it across
     * each native OrtSession.run(), and close() acquires it before freeing the
     * sessions. If the permit guard were ever bypassed, this guarantees close()
     * cannot free a session an in-flight run is using (the SIGSEGV acknowledged
     * in the [closed] comment below). close() is non-suspend and called from the
     * main thread, so it uses a BOUNDED tryLock on a background dispatcher
     * rather than runBlocking — it never ANRs; on the (permit-guarded,
     * unreachable-in-practice) timeout it logs and proceeds, leaving the
     * [closed] flag + permit guard as the backstop.
     */
    private val nativeGuard = Mutex()
    // TachiyomiAT: cooperative close flag. closeEngines() in ChapterTranslator
    // runs WITHOUT the translator permit (called from stop() on the main thread),
    // so it can race an in-flight analyze()/inpaint(). The locals-capture pattern
    // below guards the KOTLIN dereference (no NPE), but the underlying ONNX
    // NATIVE sessions can still be freed by close() while analyze() is mid-call
    // past a suspension point — a potential SIGSEGV rather than the catchable
    // IllegalStateException. analyze()/inpaint() poll this flag before each ONNX
    // invocation and bail cleanly (throwing) so the page is marked retryable
    // instead of crashing the process. The [nativeGuard] mutex above closes the
    // remaining window by serializing native runs against close().
    @Volatile
    private var closed = false

    // TachiyomiAT: cached value of the translation_diagnostics pref. Read once at
    // first use (not at construction, to avoid an Injekt cycle during init) so the
    // opt-in per-block OCR logging can be inspected without rebuilds.
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
                roiOcrEngine = when (ocrModel) {
                    OcrModel.MANGAOCR -> MangaOcrEngine().also {
                        it.initialize(paths.ocrEncoder, paths.ocrDecoderInit, paths.ocrDecoderStep, paths.ocrVocab)
                    }
                    OcrModel.PADDLEOCR_V6_SMALL -> PaddleOcrV6SmallEngine().also {
                        val paddlePaths = modelStore.ensurePaddleOcrV6Small()
                        it.initialize(paddlePaths.recognitionModel, paddlePaths.dictionary)
                        // TachiyomiAT: the PaddleOCR rec head reads horizontal
                        // lines; vertical manga columns must be split + rotated
                        // first. The det model replaces the ink-gap heuristic for
                        // that split. Build it best-effort: if the det asset is
                        // missing or fails to load, rec falls back to the
                        // heuristic (never blocks OCR).
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
                val usePaddleMasking = Injekt.get<tachiyomi.domain.translation.TranslationPreferences>().translationExperimentalPaddleMasking().get()
                // TachiyomiAT: load the PaddleOCR-v6 det engine for the INPAINTER
                // whenever the det asset is available — independent of OCR model and
                // of the experimental-paddle-masking pref. The det engine now drives
                // the free-text erase mask directly (Paddle line boxes → solid mask;
                // see AOTInpainting.refineFreeTextBoxes), which must run by default,
                // not behind the experimental gate. Previously it only loaded under
                // `ocrModel != PADDLEOCR_V6_SMALL && usePaddleMasking`, so the
                // default Japanese path (MANGAOCR) — the common case — never got
                // Paddle-driven masking. The PADDLEOCR_V6_SMALL branch above already
                // built it for the rec path; this block is the no-op-if-already-loaded
                // loader for every other model. usePaddleMasking still gates ONLY the
                // parented-bubble path (cleanBubbleGroup), passed to the inpainter below.
                if (ocrModel != OcrModel.PADDLEOCR_V6_SMALL && paddleDet == null &&
                    (modelStore.paddleOcrV6DetAvailable() || modelStore.paddleOcrV6DetAssetsAvailable())
                ) {
                    try {
                        val detPaths = modelStore.ensurePaddleOcrV6Det()
                        paddleDet = PaddleOcrV6DetEngine().also { it.initialize(detPaths.detectionModel) }
                        logcat(LogPriority.INFO) {
                            "ONNX init: PaddleOCR det OK (free-text erase mask" +
                                if (usePaddleMasking) " + experimental bubble masking)" else ")"
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
                localInpainting.paddleDet = paddleDet
                localInpainting.usePaddleMasking = usePaddleMasking
                if (inpaintingMode == InpaintingMode.QUALITY) {
                    paths.inpaintModel?.let { model ->
                        localInpainting.initialize(model)
                    }
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
        // Capture this engine references into locals up front. close() can run
        // concurrently (closeEngines does NOT hold translatorPermit) and nulls
        // these fields; the previous detector!!/roiOcrEngine!! dereferences were
        // an NPE crash if close() raced mid-analyze. Throwing a catchable
        // IllegalStateException here lets processSinglePage surface a retryable
        // ONNX failure instead of crashing the app.
        val localDetector = detector
            ?: throw IllegalStateException("ONNX detector closed mid-analyze")
        val localOcrEngine = roiOcrEngine
            ?: throw IllegalStateException("ONNX OCR engine closed mid-analyze")
        val startTime = System.nanoTime()
        TranslationMemoryBudget.logSnapshot("analyze_start", bitmap.width, bitmap.height)
        // TachiyomiAT: hold nativeGuard across detect + the per-ROI OCR loop so
        // close() cannot free a native session out from under an in-flight
        // OrtSession.run(). See [nativeGuard]. The whole detect+OCR region is one
        // critical section: detect is one native pass and each recognize() is a
        // native pass, and close() must wait for whichever is in flight.
        val analyzed = nativeGuard.withLock {
            if (closed) throw IllegalStateException("ONNX recognition engine closed before detect")
            val detections = localDetector.detect(bitmap)
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
            // TachiyomiAT: stash on the per-page translation instead of a shared
            // engine field so concurrent pages can't overwrite each other's data
            // before inpaint() reads it back.
            lockedPageTranslation.allTextDetections =
                filteredDetections + (textDetections.filter { it !in filteredDetections && it !in geometricallyDeduped })
            logcat(LogPriority.INFO) {
                "ONNX recognition detections: bubbles=${bubbles.size} text=${textDetections.size} " +
                    "geometricText=${geometricallyDeduped.size} filteredText=${filteredDetections.size}"
            }
            val lockedRecognizedBlocks = mutableListOf<RecognizedBlock>()
            // localOcrEngine was null-checked at the top of analyze(); reuse it
            // instead of re-dereferencing the nullable field (which close() may
            // have nulled by now).
            val engine = localOcrEngine
            for (detection in filteredDetections) {
                // TachiyomiAT: cooperative close — bail out of the per-ROI OCR loop
                // if close() ran between iterations, before invoking the (native)
                // OCR engine. Throwing keeps the page degradable instead of SIGSEGV.
                if (closed) throw IllegalStateException("ONNX recognition engine closed during OCR loop")
                val bbox = detection.bbox
                val crop = cropBitmap(bitmap, bbox[0], bbox[1], bbox[2], bbox[3])
            // TachiyomiAT: PaddleOCR's rec model reads HORIZONTAL text lines (it is a
            // CNN+CTC trained on left-to-right lines — confirmed against the official
            // PaddleOCR text-recognition docs, which run a separate orientation
            // classifier BEFORE rec). Japanese/Chinese manga text is usually laid out
            // VERTICALLY in tall, narrow columns (top-to-bottom, right-to-left).
            // Feeding a vertical crop directly produced empty/garbage output for every
            // tall bubble — only wide (horizontal) crops were readable. MangaOcr
            // tolerates vertical crops because it resizes to a fixed 224x224 square and
            // handles orientation internally, but the CTC model cannot.
            //
            // Fix (two parts, both required for horizontal-line engines like PaddleOCR):
            //
            // 1. COLUMN SPLITTING. A manga speech bubble's text detector emits ONE box
            //    for the whole bubble, but the bubble usually contains MULTIPLE vertical
            //    text columns side-by-side. The CTC model can only read a SINGLE
            //    horizontal line, so feeding a whole multi-column bubble (after rotation)
            //    interleaved the columns and produced garbage / single junk chars — e.g.
            //    real device output was "开" / "天者" from tall boxes that held 4-5 chars.
            //    Fix: detect each text column by ink-gap analysis on the original crop,
            //    OCR each column SEPARATELY, and concatenate in manga reading order
            //    (right-to-left). Single-column bubbles degrade gracefully to the
            //    one-column path. Verified in a controlled reproduction against this
            //    exact ONNX model: whole-box decoded 1/6 multi-column cases; per-column
            //    splitting decoded 6/6.
            //
            // 2. CCW ROTATION per column. Each detected vertical column is rotated 90°
            //    COUNTER-clockwise (postRotate(-90f)) so its top-to-bottom glyphs become
            //    a left-to-right line. CLOCKWISE produces upside-down text the CTC head
            //    cannot read (CW decoded 0/6 vertical strings; CCW decoded 6/6).
            //
            // Both transformations are OCR-only: the renderer lays text out vertically
            // via the existing direction="TTB" logic below, independent of this.
            //
            // Gate on engine.prefersHorizontalText so this applies ONLY to horizontal-
            // line engines (PaddleOCR). ML Kit's CJK recognizers and MangaOcr read
            // vertical text NATIVELY (prefersHorizontalText=false) and get the crop as-is.
            val boxWidthPre = crop.width.toFloat()
            val boxHeightPre = crop.height.toFloat()
            val isVerticalLanguage = language == TextRecognizerLanguage.JAPANESE ||
                language == TextRecognizerLanguage.CHINESE ||
                language == TextRecognizerLanguage.KOREAN
            val splitVerticalColumns = isVerticalLanguage &&
                engine.prefersHorizontalText &&
                boxHeightPre > boxWidthPre * 1.5f
            var rotatedForOcr = false
            val text = try {
                if (splitVerticalColumns) {
                    // TachiyomiAT: capture the det engine into a local — close()
                    // can null the field mid-analyze. Pass it down so the split
                    // path uses the learned det model when available, and falls
                    // back to the ink-gap heuristic otherwise.
                    rotatedForOcr = true
                    recognizeVerticalColumns(engine, crop, paddleDet)
                } else {
                    val (rawText, rawConf) = engine.recognizeWithConf(crop)
                    // TachiyomiAT: a tall CJK box (h > w) is vertical text, but a
                    // horizontal read can yield a confident-but-WRONG short decode
                    // (a large vertical bubble collapsing to "1") that the
                    // confidence gate alone misses. Retry via the column-split +
                    // rotate path when the horizontal read is low-confidence OR
                    // suspiciously short, and keep whichever produced more text.
                    val shortDecode = rawText.count { !it.isWhitespace() } <= SHORT_DECODE_CHARS
                    if (isVerticalLanguage && boxHeightPre > boxWidthPre &&
                        (rawConf < PADDLE_REC_CONFIDENCE || shortDecode)
                    ) {
                        rotatedForOcr = true
                        val vertical = recognizeVerticalColumns(engine, crop, paddleDet)
                        if (vertical.length > rawText.length) vertical
                        else if (rawConf >= PADDLE_REC_CONFIDENCE) rawText else ""
                    } else if (rawConf >= PADDLE_REC_CONFIDENCE) {
                        rawText
                    } else {
                        ""
                    }
                }
            } finally {
                crop.recycle()
            }
            // Mirrors the rotation the per-column path applies internally; also
            // set when the tall-CJK horizontal-failure fallback retries vertical.
            // TachiyomiAT: diagnostics — log each detected box and its OCR output so
            // recognition quality can be inspected from logcat. Gated by the opt-in
            // translation_diagnostics pref (same gate as the per-engine timing logs).
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
            val rawParent = if (detection.label == 1 || detection.label == 2) {
                bubbles
                    .filter { b ->
                        centerX >= b.bbox[0] && centerX <= b.bbox[2] &&
                            centerY >= b.bbox[1] && centerY <= b.bbox[3]
                    }
                    .minByOrNull {
                        (it.bbox[2] - it.bbox[0]) * (it.bbox[3] - it.bbox[1])
                    }
            } else null
            val parentBbox = rawParent?.let { rp ->
                val siblings = bubbles.filter { it !== rp }.map { it.bbox }
                trimParentBbox(rp.bbox, bbox, siblings)
            } ?: rawParent?.bbox
            // TachiyomiAT: text color is sampled against the ORIGINAL bitmap here
            // as a FIRST PASS. ChapterTranslator calls RenderColorEstimator.recomputeFor()
            // again AFTER inpainting (against the cleaned bitmap) so the final
            // rendered color matches the post-inpaint background. The recognition-
            // time pass is still useful as a fallback if inpainting is skipped.
            val renderColors = RenderColorEstimator.estimate(
                bitmap,
                bbox[0], bbox[1], bbox[2], bbox[3],
                parentBbox,
            )
            // TachiyomiAT: choose the render text direction. Vertical (TTB) manga
            // text is laid out top-to-bottom in columns, so its bounding box is
            // taller than wide. The renderer (PageTextRenderer) only goes vertical
            // when direction == "TTB"; without this, Japanese vertical text was
            // always rendered horizontally even though MangaOcr read it correctly.
            // Use a height-to-width ratio heuristic, gated on the CJK languages
            // that actually use vertical layout, so horizontal Latin text is
            // unaffected. isVerticalLanguage is computed once above (shared with the
            // OCR-rotation decision) — reused here.
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
                ),
                ),
            )
            }
            // End of native critical section. Return both the page translation
            // (carrying detections + dedupe state) and the recognized blocks so
            // the post-lock dedupe/assembly below runs outside nativeGuard.
            RecognizedAnalyzeResult(lockedPageTranslation, lockedRecognizedBlocks)
        }
        val pageTranslation = analyzed.pageTranslation
        val recognizedBlocks = analyzed.recognizedBlocks
        val finalRecognizedBlocks = removePostOcrDuplicateBlocks(recognizedBlocks)
        // TachiyomiAT: apply the geometric dedupe to the final block list.
        // removePostOcrDuplicateBlocks only collapses identical-text overlaps;
        // this second pass collapses any remaining geometric overlaps (cross-
        // label, no-parent, differing-text) so two overlapping boxes never
        // reach the renderer and get drawn on top of each other. See
        // PageTranslationHelper.dedupeGeometricOverlaps for the full rationale.
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
        // TachiyomiAT: capture the durable inpaint mask NOW, at OCR time, from
        // the full recognition result (OCR blocks + detector-only detections).
        // This must happen before translation/watermark filtering removes blocks
        // and before the page is persisted (allTextDetections is @Transient).
        // Persisting this mask means a resumed batch (after interrupt/OOM/reopen)
        // still erases detector-only + watermark regions instead of just the
        // surviving OCR blocks. See PageInpaintingPlanner + contract #14.
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
        // TachiyomiAT: hold nativeGuard across the (neural) inpaint pass so
        // close() cannot free the AOT inpainter's native session mid-run. Mirrors
        // the analyze() critical section. Re-check [closed] inside the lock in
        // case close() ran between the top-of-method check and acquiring it.
        //
        // The actual box/label computation is delegated to PageInpaintingEngine
        // → PageInpaintingPlanner, which prefers the persisted inpaintMaskBoxes
        // (captured at OCR time) and falls back to recomputing from the live
        // blocks + allTextDetections. The textless-page (empty mask) and
        // null-inpainter handling lives inside PageInpaintingEngine.inpaint; do
        // NOT duplicate it here — an earlier copy of that logic was unreachable
        // (this method returned inside the withLock above) and silently masked
        // the real planner path, which is exactly how the resume-mask bug hid.
        return nativeGuard.withLock {
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

    override fun close() {
        // TachiyomiAT: set the cooperative close flag FIRST, before freeing the
        // native sessions. An in-flight analyze()/inpaint() that polls [closed]
        // between ONNX calls will see this and bail cleanly (throwing) instead of
        // touching a session freed on the line below — a potential native crash.
        closed = true
        // TachiyomiAT: serialize close() against in-flight native OrtSession.run().
        // analyze()/inpaint() hold [nativeGuard] across each native pass. tryLock
        // is non-suspend and returns IMMEDIATELY (never blocks the main thread
        // close() is called from), which is correct here because every close()
        // call site is already guarded by the translator permit — so when close()
        // runs, no translate (hence no native run) is in flight and the lock is
        // free. tryLock is the defense-in-depth assertion of that invariant: if it
        // ever fails (permit guard bypassed), we do NOT free the native sessions
        // out from under a running inference — we log and leave them for the
        // permit guard / a subsequent close to handle, so close() degrades safely
        // instead of SIGSEGV-ing. The [closed] flag set above still makes any
        // in-flight run bail at its next checkpoint.
        val nativeDrained = nativeGuard.tryLock()
        if (!nativeDrained) {
            logcat(LogPriority.WARN) {
                "RoiPageRecognitionEngine.close: nativeGuard held (unexpected — permit guard " +
                    "should prevent an in-flight native run at close time); skipping native " +
                    "session free to avoid use-after-free. Engine will be rebuilt on next use."
            }
            // Leave detector/roiOcrEngine/inpainting references in place; the
            // [closed] flag + enginesClosed rebuild gate ensure a fresh engine is
            // built on the next translate. The leaked sessions are bounded (one
            // engine lifetime) and preferable to a native crash.
            initialized = false
            return
        }
        try {
            detector?.close()
            roiOcrEngine?.close()
            paddleDet?.close()
            inpainting?.close()
            detector = null
            roiOcrEngine = null
            paddleDet = null
            inpainting = null
            pageInpainter = null
            initialized = false
        } finally {
            // Release the nativeGuard acquired above so a rebuilt engine's
            // analyze()/inpaint() can proceed. (Mutex.unlock is non-suspend.)
            nativeGuard.unlock()
        }
    }

    override fun reclaimPooledMemory() {
        // TachiyomiAT: free off-heap pooled state held by the sub-engines WITHOUT
        // tearing them down. Called by TranslationPipeline's OOM recovery so the
        // native pressure that caused an OOM on one page doesn't carry into the
        // next. The OCR engine's direct KV-cache buffers are the main resident
        // (MangaOcr); the inpainter's working buffers are heap arrays already
        // covered by GC, but reclaiming via the same hook keeps the contract
        // uniform. Guarded so a partial init (null sub-engines) is a no-op.
        try { roiOcrEngine?.reclaimPooledMemory() } catch (_: Exception) {}
        try { paddleDet?.reclaimPooledMemory() } catch (_: Exception) {}
        try { inpainting?.reclaimPooledMemory() } catch (_: Exception) {}
    }

    override fun forceReleaseNativeBuffers() {
        try { roiOcrEngine?.forceReleaseNativeBuffers() } catch (_: Exception) {}
        try { paddleDet?.forceReleaseNativeBuffers() } catch (_: Exception) {}
        try { inpainting?.forceReleaseNativeBuffers() } catch (_: Exception) {}
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
     * TachiyomiAT: OCR a tall (vertical-text) manga bubble by splitting it into
     * individual text columns first, then recognizing each column as a separate
     * horizontal line. See the comment at the call site for why this is needed
     * (a whole multi-column bubble, even after rotation, garbles in the CTC head).
     *
     * Two column-detection strategies, in priority order:
     *   1. **PP-OCRv6 det model** ([paddleDet]) — a learned DB text-line detector
     *      that finds each vertical column precisely. Preferred when available.
     *   2. **Ink-gap heuristic** ([detectVerticalColumns]) — the original fallback.
     *      Used when the det engine is null/uninitialized or returns no lines.
     *
     * Either way, each column is then rotated 90° CCW and OCR'd as one horizontal
     * line, concatenated in manga reading order (right-to-left).
     *
     * A single-column bubble (or one where no split is found) degrades to rotating
     * and recognizing the whole crop as one line, so this never does worse than the
     * old whole-box path.
     */
    private suspend fun recognizeVerticalColumns(
        engine: RoiOcrEngine,
        crop: Bitmap,
        paddleDet: PaddleOcrV6DetEngine?,
    ): String {
        // Try the learned det model first. Det runs on the whole crop in one pass.
        if (paddleDet != null) {
            try {
                val lines = paddleDet.detectLines(crop, boxThresh = 0.34f)
                if (lines.isNotEmpty()) {
                    return recognizeDetColumns(engine, crop, lines)
                }
                // Det returned nothing -> fall through to the heuristic. A true
                // negative on a real text region is preferable to empty output.
                logcat(LogPriority.INFO) {
                    "[paddle_det] returned 0 lines; falling back to ink-gap heuristic"
                }
            } catch (e: Exception) {
                // Never let a det-model failure abort OCR — degrade to the heuristic
                // for this ROI and log loudly (AGENT.md: never suppress errors).
                logcat(LogPriority.WARN, e) {
                    "[paddle_det] failed; falling back to ink-gap heuristic for this ROI"
                }
            }
        }
        return recognizeHeuristicColumns(engine, crop)
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
            if (w < MIN_COLUMN_WIDTH_PX || h < MIN_COLUMN_WIDTH_PX) return@mapNotNull null
            if (w > MAX_COLUMN_WIDTH_PX || h > MAX_COLUMN_HEIGHT_PX) return@mapNotNull null
            val vertical = h > w * 1.5f
            // Vertical: sort by x-center DESC (right-to-left). Horizontal: by y ASC.
            val key = if (vertical) -(b[0] + b[2]) / 2 else (b[1] + b[3]) / 2
            Item(b, vertical, key)
        }.sortedBy { it.sortKey }

        if (items.isEmpty()) {
            val rotated = rotateCcw(crop)
            return try {
                val (part, conf) = engine.recognizeWithConf(rotated)
                if (part.isNotEmpty() && conf >= PADDLE_REC_CONFIDENCE) part else ""
            } finally {
                rotated.recycle()
            }
        }

        val parts = ArrayList<String>(items.size)
        for (it in items) {
            if (closed) throw IllegalStateException("ONNX recognition engine closed during OCR loop")
            val b = it.bbox
            val columnCrop = cropBitmap(crop, b[0], b[1], b[2], b[3])
            try {
                val feed = if (it.vertical) rotateCcw(columnCrop) else columnCrop
                val feedOwned = if (it.vertical) feed else null
                try {
                    val (part, conf) = engine.recognizeWithConf(feed)
                    if (part.isNotEmpty() && conf >= PADDLE_REC_CONFIDENCE) parts.add(part)
                } finally {
                    feedOwned?.recycle()
                }
            } finally {
                columnCrop.recycle()
            }
        }
        return parts.joinToString("")
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
                val (part, conf) = engine.recognizeWithConf(rotated)
                if (part.isNotEmpty() && conf >= PADDLE_REC_CONFIDENCE) part else ""
            } finally {
                rotated.recycle()
            }
        }
        val parts = ArrayList<String>(columns.size)
        // Manga vertical text reads right-to-left. [detectVerticalColumns] returns
        // columns in left-to-right pixel order, so iterate them in reverse.
        for (i in columns.indices.reversed()) {
            val (x0, x1) = columns[i]
            if (x1 - x0 < MIN_COLUMN_WIDTH_PX) continue
            val columnCrop = Bitmap.createBitmap(crop, x0, 0, x1 - x0, crop.height)
            val rotated = rotateCcw(columnCrop)
            columnCrop.recycle()
            try {
                val (part, conf) = engine.recognizeWithConf(rotated)
                if (part.isNotEmpty() && conf >= PADDLE_REC_CONFIDENCE) parts.add(part)
            } finally {
                rotated.recycle()
            }
        }
        return parts.joinToString("")
    }

    private fun rotateCcw(bitmap: Bitmap): Bitmap {
        val matrix = android.graphics.Matrix()
        matrix.postRotate(-90f)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
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
        // ink fraction per x-column: count dark pixels / column height.
        val inkFraction = FloatArray(w)
        for (x in 0 until w) {
            var dark = 0
            for (y in 0 until h) {
                val px = pixels[y * w + x]
                // luminance approximation; text strokes are dark.
                val lum = (0.299f * ((px shr 16) and 0xFF) +
                    0.587f * ((px shr 8) and 0xFF) +
                    0.114f * (px and 0xFF)).toInt()
                if (lum < INK_LUMINANCE_THRESHOLD) dark++
            }
            inkFraction[x] = dark.toFloat() / h.toFloat()
        }
        // Walk x finding ink-runs, merging across narrow gaps.
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

    private companion object {
        // TachiyomiAT: vertical-column detection thresholds for splitting a
        // multi-column manga bubble before OCR. Tuned against the verified Python
        // reproduction (whole-box 1/6 vs per-column 6/6 on multi-column cases).
        // INK_LUMINANCE_THRESHOLD: pixels darker than this count as text ink.
        // COLUMN_GAP_INK_FRACTION: an x-column whose ink fraction is below this is
        //   treated as a candidate gap.
        // MIN_COLUMN_GAP_PX: only an ink-free run at least this wide splits columns;
        //   narrower runs (inter-character spacing within a column) are merged.
        // MIN_COLUMN_WIDTH_PX: ink-runs narrower than this are discarded as noise.
        private const val INK_LUMINANCE_THRESHOLD = 110
        private const val COLUMN_GAP_INK_FRACTION = 0.02f
        private const val MIN_COLUMN_GAP_PX = 10
        private const val MIN_COLUMN_WIDTH_PX = 12
        // TachiyomiAT: det-line size guard for [recognizeDetColumns]. Rejects
        // sub-glyph noise (det sometimes emits tiny fragments) and false merges
        // (huge regions spanning multiple bubbles). Sized in crop pixel coords.
        private const val MAX_COLUMN_WIDTH_PX = 400
        private const val MAX_COLUMN_HEIGHT_PX = 800
        // TachiyomiAT: keep recognition text at/above this mean-max-prob; below it
        // the unit is blanked but left UN-ERASED by the render-aware inpaint mask
        // (PageInpaintingPlanner), so an unread region keeps its original pixels
        // instead of becoming an empty void. Lowered 0.5 -> 0.25 so real-but-
        // imperfect PaddleOCR text is translated rather than dropped.
        private const val PADDLE_REC_CONFIDENCE = 0.25f
        // TachiyomiAT: a tall CJK box read horizontally can produce a confident-
        // but-WRONG short decode (a large vertical bubble collapsing to "1").
        // Such a short horizontal result triggers a vertical (rotated) retry so
        // the real text is recovered. Tunable; 2 catches 1-2-char garbage while
        // rarely firing on legitimate short lines in tall boxes.
        private const val SHORT_DECODE_CHARS = 2
        // TachiyomiAT: text-color constants moved to RenderColorEstimator (and
        // fixed there — the legacy INVERTED_TEXT_COLORS was a copy-paste of the
        // default constant, both returning dark-gray text 0xFF1A1A1A, which made
        // dark-inpainted bubbles illegible).
    }
}
