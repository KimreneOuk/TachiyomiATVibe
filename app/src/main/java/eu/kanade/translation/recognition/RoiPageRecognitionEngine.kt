package eu.kanade.translation.recognition

import android.content.Context
import android.graphics.Bitmap
import eu.kanade.translation.detection.Detection
import eu.kanade.translation.detection.OnnxPageTextDetector
import eu.kanade.translation.inpainting.AOTInpainting
import eu.kanade.translation.inpainting.InpaintingMode
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocr.MangaOcrEngine
import eu.kanade.translation.rendering.RenderColorEstimator
import eu.kanade.translation.ocr.MlKitRoiOcrEngine
import eu.kanade.translation.ocr.RoiOcrEngine
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.runtime.onnx.OnnxModelStore
import eu.kanade.translation.util.TranslationMemoryBudget
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class RoiPageRecognitionEngine(
    private val context: Context,
    private val language: TextRecognizerLanguage,
    private val inpaintingMode: InpaintingMode = InpaintingMode.QUALITY,
) : PageRecognitionEngine {

    private val modelStore = OnnxModelStore(context)
    private var detector: OnnxPageTextDetector? = null
    private var roiOcrEngine: RoiOcrEngine? = null
    private var inpainting: AOTInpainting? = null
    @Volatile
    private var initialized = false
    private var initFailed = false
    private val initMutex = Mutex()
    // TachiyomiAT: cooperative close flag. closeEngines() in ChapterTranslator
    // runs WITHOUT the translator permit (called from stop() on the main thread),
    // so it can race an in-flight analyze()/inpaint(). The locals-capture pattern
    // below guards the KOTLIN dereference (no NPE), but the underlying ONNX
    // NATIVE sessions can still be freed by close() while analyze() is mid-call
    // past a suspension point — a potential SIGSEGV rather than the catchable
    // IllegalStateException. analyze()/inpaint() poll this flag before each ONNX
    // invocation and bail cleanly (throwing) so the page degrades to the ML Kit
    // fallback instead of crashing the process.
    @Volatile
    private var closed = false

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
                logcat(LogPriority.INFO) { "ONNX init: detector OK, starting OCR initialization (language=$language)" }
                roiOcrEngine = when (language) {
                    TextRecognizerLanguage.JAPANESE -> MangaOcrEngine().also {
                        it.initialize(paths.ocrEncoder, paths.ocrDecoderInit, paths.ocrDecoderStep, paths.ocrVocab)
                    }
                    else -> MlKitRoiOcrEngine(language)
                }
                logcat(LogPriority.INFO) { "ONNX init: OCR OK (backend=${roiOcrEngine!!::class.simpleName}), starting inpainting initialization" }
                paths.inpaintModel?.let { model ->
                    inpainting = AOTInpainting().also { it.initialize(model) }
                }
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
        // IllegalStateException here lets processSinglePage's fallback degrade
        // to ML Kit instead of crashing the app.
        val localDetector = detector
            ?: throw IllegalStateException("ONNX detector closed mid-analyze")
        val localOcrEngine = roiOcrEngine
            ?: throw IllegalStateException("ONNX OCR engine closed mid-analyze")
        val startTime = System.nanoTime()
        TranslationMemoryBudget.logSnapshot("analyze_start", bitmap.width, bitmap.height)
        // TachiyomiAT: re-check the closed flag right before the first native
        // (detect) call; close() may have run between the top-of-method check
        // and here (e.g. after initialize() completed).
        if (closed) throw IllegalStateException("ONNX recognition engine closed before detect")
        val detections = localDetector.detect(bitmap)
        val bubbles = detections.filter { it.label == 0 }
        val textDetections = detections.filter { it.label == 1 || it.label == 2 }
        val pageTranslation = PageTranslation(
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
        pageTranslation.allTextDetections =
            filteredDetections + (textDetections.filter { it !in filteredDetections && it !in geometricallyDeduped })
        logcat(LogPriority.INFO) {
            "ONNX recognition detections: bubbles=${bubbles.size} text=${textDetections.size} " +
                "geometricText=${geometricallyDeduped.size} filteredText=${filteredDetections.size}"
        }
        val recognizedBlocks = mutableListOf<RecognizedBlock>()
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
            val text = engine.recognize(crop)
            crop.recycle()
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
            // unaffected.
            val isVerticalLanguage = language == TextRecognizerLanguage.JAPANESE ||
                language == TextRecognizerLanguage.CHINESE ||
                language == TextRecognizerLanguage.KOREAN
            val direction = if (isVerticalLanguage && boxHeight > boxWidth * 1.2f) "TTB" else "LTR"
            recognizedBlocks.add(
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
        val finalRecognizedBlocks = removePostOcrDuplicateBlocks(recognizedBlocks)
        pageTranslation.blocks.addAll(finalRecognizedBlocks.map { it.block })
        pageTranslation.ocrBlockCount = pageTranslation.blocks.size
        pageTranslation.ocrStatus = StageStatus.READY
        pageTranslation.updatedAt = System.currentTimeMillis()
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        logcat(LogPriority.INFO) {
            "RoiPageRecognitionEngine analyzed ${pageTranslation.blocks.size} blocks " +
                "emptyOcr=${pageTranslation.blocks.count { it.text.isBlank() }} in ${elapsedMs}ms"
        }
        return pageTranslation
    }

    override suspend fun inpaint(bitmap: Bitmap, pageTranslation: PageTranslation): Bitmap? {
        if (!initialized) initialize()
        val inpainter = inpainting
        // TachiyomiAT: a page whose OCR found ZERO text blocks is a SUCCESSFUL
        // recognition (there is genuinely nothing to translate — a splash page,
        // an art-only spread). Previously this fell through to the generic
        // FAILED branch below and set inpaintStatus=FAILED + retryCount++, which
        // made the page look like a *failing* page to auto-translate's dedup
        // gate (ReaderViewModel.handleAutoTranslation). The gate re-enqueued it
        // on every page navigation until retryCount saturated at MAX_STAGE_RETRIES,
        // re-running detection+OCR each time — the reported "auto-translate keeps
        // reprocessing the same image" bug. A textless page needs no inpaint and
        // no retry: mark READY and bail cleanly so the dedup treats it as done.
        if (pageTranslation.blocks.isEmpty()) {
            pageTranslation.inpaintStatus = StageStatus.READY
            pageTranslation.errorMessage = null
            pageTranslation.updatedAt = System.currentTimeMillis()
            return null
        }
        if (inpainter != null && inpainter.isInitialized()) {
            try {
                pageTranslation.inpaintStatus = StageStatus.RUNNING
                pageTranslation.updatedAt = System.currentTimeMillis()
                val bubbleBoxes = pageTranslation.blocks
                    .filter { it.parentWidth > 0f && it.parentHeight > 0f }
                    .map { block ->
                        intArrayOf(
                            block.parentX.toInt(),
                            block.parentY.toInt(),
                            (block.parentX + block.parentWidth).toInt(),
                            (block.parentY + block.parentHeight).toInt(),
                        )
                    }
                    .distinctBy { it.toList() }
                val textBoxes = pageTranslation.blocks.map { block ->
                    intArrayOf(
                        block.x.toInt(),
                        block.y.toInt(),
                        (block.x + block.width).toInt(),
                        (block.y + block.height).toInt(),
                    )
                }
                val allBoxes = bubbleBoxes + textBoxes
                val allLabels = List(bubbleBoxes.size) { 0 } + pageTranslation.blocks.map { it.label }

                val ocrBlockBoxes = pageTranslation.blocks.map { block ->
                    intArrayOf(
                        block.x.toInt(),
                        block.y.toInt(),
                        (block.x + block.width).toInt(),
                        (block.y + block.height).toInt(),
                    )
                }.toSet()
                val extraDetectorBoxes = pageTranslation.allTextDetections
                    .map { it.bbox }
                    .filter { detBox ->
                        val expanded = intArrayOf(
                            max(0, detBox[0] - 3),
                            max(0, detBox[1] - 3),
                            detBox[2] + 3,
                            detBox[3] + 3,
                        )
                        ocrBlockBoxes.none { ocrBox ->
                            computeIou(expanded, ocrBox) > 0.4f
                        }
                    }
                    .distinctBy { it.toList() }
                val combinedBoxes = allBoxes + extraDetectorBoxes
                val combinedLabels = allLabels + extraDetectorBoxes.map { 2 }

                logcat(LogPriority.INFO) {
                    "ONNX inpainting input: boxes=${combinedBoxes.size} extraDetector=${extraDetectorBoxes.size} labels=${combinedLabels.groupingBy { it }.eachCount()}"
                }
                TranslationMemoryBudget.logSnapshot("before_inpaint", bitmap.width, bitmap.height, "boxes=${combinedBoxes.size}")
                // TachiyomiAT: cooperative close — bail before the native inpaint
                // call if close() ran while building the input boxes. See [closed].
                if (closed) {
                    pageTranslation.inpaintStatus = StageStatus.FAILED
                    return null
                }
                val cleaned = inpainter.inpaintRegions(bitmap, combinedBoxes, combinedLabels, mode = inpaintingMode)
                pageTranslation.inpaintStatus = StageStatus.READY
                pageTranslation.updatedAt = System.currentTimeMillis()
                return cleaned
            } catch (e: Exception) {
                pageTranslation.inpaintStatus = StageStatus.FAILED
                pageTranslation.retryCount++
                pageTranslation.errorMessage = e.message
                pageTranslation.updatedAt = System.currentTimeMillis()
                logcat(LogPriority.WARN, e) { "Inpainting failed, continuing without cleaned bitmap" }
            }
        }
        pageTranslation.inpaintStatus = StageStatus.FAILED
        pageTranslation.retryCount++
        // TachiyomiAT: record WHY inpaint was skipped. This branch is now ONLY
        // reached when there ARE blocks but the inpainter itself is
        // null/uninitialized (the empty-blocks case is handled above as a clean
        // READY). Previously it also caught empty-blocks pages and marked them
        // FAILED, which fed the auto-translate reprocess loop. The message is
        // kept diagnostic so a "blank error" in the store is never produced.
        pageTranslation.errorMessage = when {
            inpainter == null -> "Inpainting model not available"
            !inpainter.isInitialized() -> "Inpainting engine not initialized"
            else -> "Inpainting skipped"
        }
        pageTranslation.updatedAt = System.currentTimeMillis()
        return null
    }

    private data class RecognizedBlock(
        val detection: Detection,
        val block: TranslationBlock,
    )

    override fun close() {
        // TachiyomiAT: set the cooperative close flag FIRST, before freeing the
        // native sessions. An in-flight analyze()/inpaint() that polls [closed]
        // between ONNX calls will see this and bail cleanly (throwing) instead of
        // touching a session freed on the line below — a potential native crash.
        closed = true
        detector?.close()
        roiOcrEngine?.close()
        inpainting?.close()
        detector = null
        roiOcrEngine = null
        inpainting = null
        initialized = false
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
                val iou = computeIou(keep[i].bbox, keep[j].bbox)
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
        val area = bboxArea(det.bbox)
        if (area <= 0) return 0f
        return intersectionArea(det.bbox, parentBox).toFloat() / area.toFloat()
    }

    private fun normalizeOcrText(text: String): String = text
        .lowercase()
        .filterNot { it.isWhitespace() || it.isISOControl() }

    private fun isTextBoxDuplicate(a: IntArray, b: IntArray): Boolean {
        if (computeIou(a, b) > TEXT_IOU_DUPLICATE_THRESHOLD) return true
        val minArea = min(bboxArea(a), bboxArea(b))
        if (minArea > 0 && intersectionArea(a, b).toFloat() / minArea.toFloat() > TEXT_CONTAINMENT_DUPLICATE_THRESHOLD) {
            return true
        }

        val aw = max(1, a[2] - a[0])
        val ah = max(1, a[3] - a[1])
        val bw = max(1, b[2] - b[0])
        val bh = max(1, b[3] - b[1])
        val centerDx = abs((a[0] + a[2]) - (b[0] + b[2])) / 2f
        val centerDy = abs((a[1] + a[3]) - (b[1] + b[3])) / 2f
        return centerDx <= TEXT_CENTER_DUPLICATE_THRESHOLD * min(aw, bw) &&
            centerDy <= TEXT_CENTER_DUPLICATE_THRESHOLD * min(ah, bh) &&
            abs(aw - bw).toFloat() <= TEXT_SIZE_DUPLICATE_THRESHOLD * max(aw, bw) &&
            abs(ah - bh).toFloat() <= TEXT_SIZE_DUPLICATE_THRESHOLD * max(ah, bh)
    }

    private fun computeIou(a: IntArray, b: IntArray): Float {
        val ix1 = max(a[0], b[0])
        val iy1 = max(a[1], b[1])
        val ix2 = min(a[2], b[2])
        val iy2 = min(a[3], b[3])
        if (ix2 <= ix1 || iy2 <= iy1) return 0.0f
        val inter = (ix2 - ix1) * (iy2 - iy1)
        val aArea = max(0, a[2] - a[0]) * max(0, a[3] - a[1])
        val bArea = max(0, b[2] - b[0]) * max(0, b[3] - b[1])
        val union = aArea + bArea - inter
        return if (union > 0) inter.toFloat() / union.toFloat() else 0.0f
    }

    private fun cropBitmap(source: Bitmap, x1: Int, y1: Int, x2: Int, y2: Int): Bitmap {
        val clampedX1 = x1.coerceIn(0, source.width)
        val clampedY1 = y1.coerceIn(0, source.height)
        val clampedX2 = x2.coerceIn(clampedX1, source.width)
        val clampedY2 = y2.coerceIn(clampedY1, source.height)
        if (clampedX2 <= clampedX1 || clampedY2 <= clampedY1) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        return Bitmap.createBitmap(source, clampedX1, clampedY1, clampedX2 - clampedX1, clampedY2 - clampedY1)
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
            val inter = intersectionArea(intArrayOf(px1, py1, px2, py2), sib)
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

    private fun intersectionArea(a: IntArray, b: IntArray): Int {
        val ix1 = max(a[0], b[0])
        val iy1 = max(a[1], b[1])
        val ix2 = min(a[2], b[2])
        val iy2 = min(a[3], b[3])
        if (ix2 <= ix1 || iy2 <= iy1) return 0
        return (ix2 - ix1) * (iy2 - iy1)
    }

    private fun bboxArea(box: IntArray): Int = max(0, box[2] - box[0]) * max(0, box[3] - box[1])

    private companion object {
        private const val TEXT_IOU_DUPLICATE_THRESHOLD = 0.62f
        private const val TEXT_CONTAINMENT_DUPLICATE_THRESHOLD = 0.86f
        private const val TEXT_CENTER_DUPLICATE_THRESHOLD = 0.12f
        private const val TEXT_SIZE_DUPLICATE_THRESHOLD = 0.20f
        // TachiyomiAT: text-color constants moved to RenderColorEstimator (and
        // fixed there — the legacy INVERTED_TEXT_COLORS was a copy-paste of the
        // default constant, both returning dark-gray text 0xFF1A1A1A, which made
        // dark-inpainted bubbles illegible).
    }
}
