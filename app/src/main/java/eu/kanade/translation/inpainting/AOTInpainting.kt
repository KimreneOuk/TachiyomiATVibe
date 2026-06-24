package eu.kanade.translation.inpainting

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import eu.kanade.translation.runtime.onnx.OnnxRuntimeProvider
import eu.kanade.translation.util.TranslationMemoryBudget
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.domain.translation.pools.BitmapPool
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class AOTInpainting {

    companion object {
        private const val MAX_INFERENCE_DIM = 512
        private const val MAX_TOTAL_PIXELS = MAX_INFERENCE_DIM * MAX_INFERENCE_DIM

        // TachiyomiAT: PaddleOCR-v6 → solid-box erase-mask tunables. These drive
        // the free-text erase mask directly from Paddle line boxes (the prototype
        // `mask_mode = paddle_boxes`), replacing the pixel-heuristic masks built
        // over detector-v4 rectangles. Defaults match the validated prototype
        // (`tools/inpaint-debug-viewer/server.py`): paddle_crop_pad=12,
        // paddle_thresh=0.18, paddle_box_thresh=0.34, mask_pad=8. Kept as internal
        // constants first (no UI/settings surface) per the task's "tunables stay
        // internal" requirement.
        private const val PADDLE_CROP_PAD = 12
        private const val PADDLE_THRESH = 0.18f
        private const val PADDLE_BOX_THRESH = 0.34f
        private const val MASK_PAD = 8
    }

    private val scratchLock = Any()

    private var sharedImgData: FloatArray? = null
    private var sharedMaskData: FloatArray? = null
    private var sharedImgPixels: IntArray? = null
    private var sharedMaskPixels: IntArray? = null
    private var sharedResultPixels: IntArray? = null

    private fun getImgData(): FloatArray {
        return sharedImgData ?: FloatArray(3 * MAX_TOTAL_PIXELS).also { sharedImgData = it }
    }
    private fun getMaskData(): FloatArray {
        return sharedMaskData ?: FloatArray(MAX_TOTAL_PIXELS).also { sharedMaskData = it }
    }
    private fun getImgPixels(): IntArray {
        return sharedImgPixels ?: IntArray(MAX_TOTAL_PIXELS).also { sharedImgPixels = it }
    }
    private fun getMaskPixels(): IntArray {
        return sharedMaskPixels ?: IntArray(MAX_TOTAL_PIXELS).also { sharedMaskPixels = it }
    }
    private fun getResultPixels(): IntArray {
        return sharedResultPixels ?: IntArray(MAX_TOTAL_PIXELS).also { sharedResultPixels = it }
    }

    fun clearScratch() {
        synchronized(scratchLock) {
            sharedImgData = null
            sharedMaskData = null
            sharedImgPixels = null
            sharedMaskPixels = null
            sharedResultPixels = null
        }
    }

    private var session: OrtSession? = null
    private val bubbleCleaner = SmartBubbleTextCleaner()
    var paddleDet: eu.kanade.translation.ocr.PaddleOcrV6DetEngine? = null
    var usePaddleMasking: Boolean = false

    // TachiyomiAT: cached value of the translation_diagnostics preference.
    // The graph-spec dump in [initialize] fires once per session creation and
    // the per-inference zero-output sample in [inpaint] fires per cluster, so
    // both read this lazily-once to avoid SharedPreferences reads on the hot
    // path. Mirrors the resolveDiagnostics() pattern in the OCR engines.
    @Volatile
    private var translationDiagnosticsEnabled: Boolean = false
    @Volatile
    private var diagnosticsResolved: Boolean = false
    private fun resolveDiagnostics(): Boolean {
        if (diagnosticsResolved) return translationDiagnosticsEnabled
        translationDiagnosticsEnabled = try {
            Injekt.get<TranslationPreferences>().translationDiagnostics().get()
        } catch (_: Throwable) {
            false
        }
        diagnosticsResolved = true
        return translationDiagnosticsEnabled
    }

    fun initialize(modelFile: File) {
        if (!modelFile.exists()) {
            logcat(LogPriority.WARN) { "Inpainting model not found at ${modelFile.absolutePath}, skipping" }
            return
        }
        // TachiyomiAT: AOT inpainting runs on the shared CPU-only ONNX runtime.
        // Future NPU support should use Qualcomm QNN/QAIRT with converted models.
        val opts = OnnxRuntimeProvider.createSessionOptions(forceCpu = true)
        try {
            session = OnnxRuntimeProvider.environment.createSession(modelFile.absolutePath, opts)
        } finally {
            opts.close()
        }
        logcat(LogPriority.INFO) { "AOT Inpainting CPU session created from ${modelFile.name}" }

        // TachiyomiAT: dump the model's tensor contract once at session creation
        // so the names the [inpaint] wiring assumes can be verified from logcat
        // without a desktop ONNX parse. Gated behind the opt-in
        // translation_diagnostics pref (off by default) to avoid spamming on
        // every chapter's first page. Verified offline to be: inputs
        // image(1,3,H,W) + mask(1,1,H,W), output inpainted(1,3,H,W), NCHW,
        // [-1,1] normalization. Full shapes/dtypes are available via
        // scripts/inspect_aot_onnx.py.
        val sess = session
        if (sess != null) {
            assertContract(sess)
            if (resolveDiagnostics()) {
                try {
                    logcat(LogPriority.INFO) {
                        "[inpaint] contract inputs=${sess.inputNames} outputs=${sess.outputNames}"
                    }
                } catch (e: Throwable) {
                    logcat(LogPriority.WARN) { "[inpaint] could not dump graph contract: ${e.message}" }
                }
            }
        }
    }

    /**
     * TachiyomiAT: verify the session's inputs match what [inpaint] feeds it
     * ("image" and "mask"). A mismatch previously surfaced only as an
     * [OrtException] from sess.run that the caller caught generically and
     * turned into a silent inpaintStatus=FAILED ("Inpainting unavailable"),
     * hiding the real cause. Asserting here turns a name mismatch into a clear,
     * readable error in the log so the cause isn't buried. Throws
     * [IllegalStateException] on mismatch — the model is unusable as wired.
     */
    private fun assertContract(sess: OrtSession) {
        val names = try {
            sess.inputNames
        } catch (e: Throwable) {
            logcat(LogPriority.WARN) { "[inpaint] could not read input names: ${e.message}" }
            return
        }
        require(names.contains("image")) {
            "[inpaint] model has no 'image' input; actual inputs=$names. " +
                "AOTInpainting feeds {\"image\",\"mask\"} — the wiring must be updated."
        }
        require(names.contains("mask")) {
            "[inpaint] model has no 'mask' input; actual inputs=$names. " +
                "AOTInpainting feeds {\"image\",\"mask\"} — the wiring must be updated."
        }
    }

    fun isInitialized(): Boolean = session != null

    fun inpaintRegions(
        image: Bitmap,
        boxes: List<IntArray>,
        labels: List<Int>? = null,
        padding: Int = 5,
        mode: InpaintingMode = InpaintingMode.QUALITY,
    ): Bitmap {
        // TachiyomiAT: return a mutable COPY on the empty-boxes path too.
        // Previously this returned the immutable input `image` by reference,
        // which aliased the caller's decoded bitmap — a downstream render()
        // could then mutate the caller's bitmap (or, after the caller recycled
        // it, throw). A copy keeps the contract uniform: the returned bitmap
        // is always a fresh mutable ARGB_8888 owned by the inpainter.
        if (boxes.isEmpty()) return image.copy(Bitmap.Config.ARGB_8888, true)
        val sess = session
        var result = image.copy(Bitmap.Config.ARGB_8888, true)

        val bubbleBoxes = mutableListOf<IntArray>()
        val textBoxes = mutableListOf<IntArray>()
        val textLabels = mutableListOf<Int>()

        if (labels != null && labels.size == boxes.size) {
            for (i in boxes.indices) {
                val lbl = labels[i]
                if (lbl == 0) {
                    bubbleBoxes.add(boxes[i])
                } else {
                    textBoxes.add(boxes[i])
                    textLabels.add(lbl)
                }
            }
        } else {
            for (box in boxes) {
                textBoxes.add(box)
                textLabels.add(2)
            }
        }

        val grouped = mutableMapOf<String, MutableList<IntArray>>()
        val unparented = mutableListOf<IntArray>()
        val freeBoxesForAot = mutableListOf<IntArray>()

        for (i in textBoxes.indices) {
            val box = textBoxes[i]
            val lbl = textLabels[i]
            val parent = findParentBubble(box, bubbleBoxes)
            if (parent != null) {
                val key = parent.toList().toString()
                grouped.getOrPut(key) { mutableListOf() }.add(box)
            } else {
                if (lbl == 2) {
                    freeBoxesForAot.add(box)
                } else {
                    unparented.add(box)
                }
            }
        }

        if (grouped.isNotEmpty()) {
            for ((_, groupBoxes) in grouped) {
                val bubbleBbox = findParentBubble(groupBoxes.first(), bubbleBoxes) ?: continue
                result = bubbleCleaner.cleanBubbleGroup(result, bubbleBbox, groupBoxes, paddleDet, usePaddleMasking)
            }
        }

        if (unparented.isNotEmpty()) {
            result = bubbleCleaner.cleanRegions(result, unparented)
        }

        // TachiyomiAT: refine the free-text erase boxes through PaddleOCR-v6 DET.
        // detector-v4 supplied the COARSE free-text regions above; now each one is
        // re-detected at line granularity by Paddle and the resulting line boxes
        // (back-projected to page coords) REPLACE the detector-v4 rectangles as
        // the erase target — the prototype `mask_mode = paddle_boxes`. When Paddle
        // returns 0 lines for a region, that region falls back to its detector-v4
        // box (counted in paddleFallback for diagnostics). Only free text is
        // refined here; parented bubbles keep cleanBubbleGroup (AOT stays free-
        // text-only). Null paddleDet keeps the legacy detector-v4 boxes verbatim.
        var paddleLinesTotal = 0
        var paddleFallback = 0
        val paddleRefined: List<IntArray>
        if (paddleDet != null && freeBoxesForAot.isNotEmpty()) {
            val refined = refineFreeTextBoxes(image, freeBoxesForAot)
            paddleLinesTotal = refined.paddleLineCount
            paddleFallback = refined.fallbackCount
            paddleRefined = refined.boxes
        } else {
            paddleRefined = freeBoxesForAot
        }
        val paddleActive = paddleDet != null && freeBoxesForAot.isNotEmpty()

        val freeFlatBoxes = mutableListOf<IntArray>()
        val freeNeuralBoxes = mutableListOf<IntArray>()
        val freeSmallBoxes = mutableListOf<IntArray>()
        val pageArea = image.width.toLong() * image.height.toLong()
        val smallBoxAreaThreshold = pageArea / 200
        // TachiyomiAT: route order changed so small boxes can reach the neural
        // path. Previously the small-box check ran FIRST and force-routed every
        // sub-threshold box to flat-fill regardless of QUALITY mode — so a user
        // in QUALITY mode expecting neural reconstruction on a small bubble
        // silently got the flat color-average fill, which destroys screentone
        // (averaging dots → flat gray). Now: (1) flat-background regions route
        // to flat-fill (fast and correct for genuinely flat backgrounds);
        // (2) small boxes route to flat-fill ONLY when memory is constrained;
        // (3) otherwise the box reaches the neural path for screentone-capable
        // reconstruction. The memory gate preserves the OOM-safety intent of
        // the original absolute-area bypass.
        for (box in paddleRefined) {
            val boxArea = (box[2] - box[0]).toLong() * (box[3] - box[1]).toLong()
            when {
                bubbleCleaner.isFlatBackgroundRegion(result, box) -> freeFlatBoxes.add(box)
                boxArea < smallBoxAreaThreshold &&
                    !TranslationMemoryBudget.canRunNeuralInpaint(
                        pageWidth = image.width,
                        pageHeight = image.height,
                        cropWidth = box[2] - box[0],
                        cropHeight = box[3] - box[1],
                    ) -> freeSmallBoxes.add(box)
                else -> freeNeuralBoxes.add(box)
            }
        }

        logcat(LogPriority.INFO) {
            "[inpaint] route bubbles=${bubbleBoxes.size} grouped=${grouped.values.sumOf { it.size }} " +
                "unparented=${unparented.size} freeFlat=${freeFlatBoxes.size} freeSmall=${freeSmallBoxes.size} freeNeural=${freeNeuralBoxes.size} " +
                "model=${sess != null}"
        }
        // TachiyomiAT: paddle_boxes diagnostics — surfaces the detector→Paddle
        // refinement so a missed column or a fallback storm is diagnosable from
        // logcat without the diagnostics pref. Mirrors the prototype's reported
        // counts (detectorText / paddleLines / fallback / finalMaskBoxes).
        if (paddleActive) {
            logcat(LogPriority.INFO) {
                "[inpaint] paddle_boxes detectorText=${freeBoxesForAot.size} paddleLines=$paddleLinesTotal " +
                    "fallback=$paddleFallback finalMaskBoxes=${paddleRefined.size} " +
                    "thresh=$PADDLE_THRESH boxThresh=$PADDLE_BOX_THRESH cropPad=$PADDLE_CROP_PAD maskPad=$MASK_PAD"
            }
        }

        // TachiyomiAT: when Paddle refined the free-text boxes, the flat/small
        // routes erase SOLID padded Paddle boxes (fillSolidBoxes) — not the
        // pixel-heuristic cleanRegions, which would re-derive a mask from the
        // box interior and re-introduce the sparse/over-processed artifact the
        // Paddle path exists to avoid. cleanRegions is retained for the null-
        // paddleDet legacy path (paddleActive == false).
        if (freeFlatBoxes.isNotEmpty()) {
            result = if (paddleActive) {
                bubbleCleaner.fillSolidBoxes(result, freeFlatBoxes, MASK_PAD)
            } else {
                bubbleCleaner.cleanRegions(result, freeFlatBoxes)
            }
        }

        if (freeSmallBoxes.isNotEmpty()) {
            result = if (paddleActive) {
                bubbleCleaner.fillSolidBoxes(result, freeSmallBoxes, MASK_PAD)
            } else {
                bubbleCleaner.cleanRegions(result, freeSmallBoxes)
            }
        }

        // TachiyomiAT: pick the non-neural cleaner for a box list. When Paddle
        // refined the boxes, erase SOLID padded boxes (fillSolidBoxes); otherwise
        // keep the legacy pixel-heuristic cleanRegions (null-paddleDet path).
        val cleanFree: (Bitmap, List<IntArray>) -> Bitmap = { src, boxes ->
            if (paddleActive) bubbleCleaner.fillSolidBoxes(src, boxes, MASK_PAD)
            else bubbleCleaner.cleanRegions(src, boxes)
        }

        if (freeNeuralBoxes.isNotEmpty() && sess != null && mode == InpaintingMode.QUALITY) {
            if (TranslationMemoryBudget.isCriticalHeap()) {
                TranslationMemoryBudget.logSnapshot(
                    tag = "skip_neural_heap_pressure",
                    width = image.width,
                    height = image.height,
                    extra = "boxes=${freeNeuralBoxes.size}",
                )
                result = cleanFree(result, freeNeuralBoxes)
            } else {
                val clusters = clusterNearbyBoxes(freeNeuralBoxes, clusterDistance = 100)
                logcat(LogPriority.INFO) {
                    "[inpaint] clustering ${freeNeuralBoxes.size} neural boxes into ${clusters.size} clusters"
                }
                for (cluster in clusters) {
                    try {
                        val next = inpaintFreeRegions(sess, result, cluster, padding)
                        if (next !== result) {
                            if (result !== image) result.recycle()
                            result = next
                        }
                    } catch (oom: OutOfMemoryError) {
                        BitmapPool.releaseAll()
                        System.gc()
                        logcat(LogPriority.WARN) {
                            "[inpaint] OOM on neural cluster (${cluster.size} boxes), falling back to smart clean"
                        }
                        result = cleanFree(result, cluster)
                    }
                }
            }
        } else if (freeNeuralBoxes.isNotEmpty()) {
            result = cleanFree(result, freeNeuralBoxes)
        }

        return result
    }

    /**
     * TachiyomiAT: refine the free-text erase boxes through PaddleOCR-v6 DET.
     *
     * For every detector-v4 free-text box, crop the page with [PADDLE_CROP_PAD]
     * of context, run Paddle DET at the inpaint thresholds ([PADDLE_THRESH] /
     * [PADDLE_BOX_THRESH]), and back-project the returned line boxes to PAGE
     * coords (crop origin + line bbox, clamped to the page). Those Paddle line
     * boxes REPLACE the detector-v4 rectangle as the erase target — the
     * prototype `mask_mode = paddle_boxes`. When Paddle returns 0 lines for a
     * region, that region falls back to its detector-v4 box (counted in
     * [RefinedFreeText.fallbackCount]) so a missed region is still erased.
     *
     * Boring and direct: Paddle box → pad (applied later in the mask builder).
     * The back-projection is plain integer arithmetic matching the prototype's
     * `clamp_box([x1 + lx1, y1 + ly1, x1 + lx2, y1 + ly2], width, height)`.
     *
     * Each crop is created + recycled in-place to bound heap (no cross-call
     * retention). Paddle det failures are logged and treated as "0 lines" →
     * fallback for that region (never abort the whole refine).
     */
    private fun refineFreeTextBoxes(
        image: Bitmap,
        detectorBoxes: List<IntArray>,
    ): RefinedFreeText {
        val w = image.width
        val h = image.height
        val paddleDet = this.paddleDet ?: return RefinedFreeText(detectorBoxes, 0, detectorBoxes.size)
        val refined = ArrayList<IntArray>(detectorBoxes.size)
        var paddleLineCount = 0
        var fallbackCount = 0
        for (det in detectorBoxes) {
            val cx1 = (det[0] - PADDLE_CROP_PAD).coerceIn(0, w)
            val cy1 = (det[1] - PADDLE_CROP_PAD).coerceIn(0, h)
            val cx2 = (det[2] + PADDLE_CROP_PAD).coerceIn(0, w)
            val cy2 = (det[3] + PADDLE_CROP_PAD).coerceIn(0, h)
            if (cx2 <= cx1 || cy2 <= cy1) {
                // Degenerate crop — fall back to the detector box verbatim.
                refined.add(det.copyOf())
                fallbackCount++
                continue
            }
            val crop = Bitmap.createBitmap(image, cx1, cy1, cx2 - cx1, cy2 - cy1)
            val lines = try {
                paddleDet.detectLines(crop, thresh = PADDLE_THRESH, boxThresh = PADDLE_BOX_THRESH)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) {
                    "[inpaint] paddle DET failed on free-text crop; falling back to detector box"
                }
                emptyList()
            } finally {
                crop.recycle()
            }
            if (lines.isEmpty()) {
                // Paddle found nothing for this region — conservative fallback
                // to the detector-v4 box so the region is still erased.
                refined.add(det.copyOf())
                fallbackCount++
                continue
            }
            for (line in lines) {
                val b = line.bbox
                if (b.size < 4) continue
                // Back-project: crop-local → page coords. Clamp to the page and
                // drop zero-area boxes (matches the prototype's clamp_box guard).
                val px1 = (cx1 + b[0]).coerceIn(0, w)
                val py1 = (cy1 + b[1]).coerceIn(0, h)
                val px2 = (cx1 + b[2]).coerceIn(0, w)
                val py2 = (cy1 + b[3]).coerceIn(0, h)
                if (px2 > px1 && py2 > py1) {
                    refined.add(intArrayOf(px1, py1, px2, py2))
                    paddleLineCount++
                }
            }
        }
        return RefinedFreeText(refined, paddleLineCount, fallbackCount)
    }

    /** Result of [refineFreeTextBoxes] — refined boxes + counts for diagnostics. */
    private data class RefinedFreeText(
        val boxes: List<IntArray>,
        val paddleLineCount: Int,
        val fallbackCount: Int,
    )

    private fun inpaintFreeRegions(
        sess: OrtSession,
        image: Bitmap,
        boxes: List<IntArray>,
        padding: Int,
    ): Bitmap = synchronized(scratchLock) {
        val w = image.width
        val h = image.height

        val normalizedBoxes = boxes.mapNotNull { box ->
            val x1 = max(0, box[0] - padding)
            val y1 = max(0, box[1] - padding)
            val x2 = min(w, box[2] + padding)
            val y2 = min(h, box[3] + padding)
            if (x2 <= x1 || y2 <= y1) null else intArrayOf(x1, y1, x2, y2)
        }
        if (normalizedBoxes.isEmpty()) return image

        val unionX1 = normalizedBoxes.minOf { it[0] }
        val unionY1 = normalizedBoxes.minOf { it[1] }
        val unionX2 = normalizedBoxes.maxOf { it[2] }
        val unionY2 = normalizedBoxes.maxOf { it[3] }

        // TachiyomiAT: proportional crop margin. The earlier fixed 32px margin
        // gave the generative model too little surrounding context for small
        // text boxes — a known artifact source (the model needs to see nearby
        // bubble borders, screentone, and line art to reconstruct naturally).
        // Scale the margin to the text-region size (~2.5× the longer side, per
        // production AOT-GAN manga-translation practice), clamped to [64, 256]:
        //  - floor 64 ensures even tiny SFX gets meaningful context,
        //  - ceiling 256 caps memory on the 6GB target (the crop feeds a fixed
        //    512×512 model input regardless, so a larger crop is only a larger
        //    transient IntArray; the ceiling keeps that bounded).
        val textW = unionX2 - unionX1
        val textH = unionY2 - unionY1
        val cropMargin = (max(textW, textH) * 2.5f).toInt().coerceIn(64, 256)

        val cropX1 = max(0, unionX1 - cropMargin)
        val cropY1 = max(0, unionY1 - cropMargin)
        val cropX2 = min(w, unionX2 + cropMargin + 1)
        val cropY2 = min(h, unionY2 + cropMargin + 1)
        val cropW = cropX2 - cropX1
        val cropH = cropY2 - cropY1

        if (!TranslationMemoryBudget.canRunNeuralInpaint(w, h, cropW, cropH)) {
            // TachiyomiAT: log the neural-inpaint downgrade UNCONDITIONALLY (not
            // just under translation_diagnostics). This is a user-visible quality
            // degradation — bubbles get flat-filled instead of neural-repainted,
            // which is exactly the "image gets blurry / worse" symptom. Hiding it
            // behind the diagnostics flag made the fallback invisible by default,
            // so the root cause (heap pressure) was never diagnosable. The full
            // heap snapshot still goes through the gated logSnapshot below.
            logcat(LogPriority.WARN) {
                "Neural inpaint SKIPPED on heap pressure: page=${w}x$h crop=${cropW}x$cropH " +
                    "boxes=${normalizedBoxes.size} — falling back to flat bubble fill (visible quality drop)"
            }
            TranslationMemoryBudget.logSnapshot(
                tag = "skip_neural_inpaint",
                width = w,
                height = h,
                extra = "crop=${cropW}x$cropH boxes=${normalizedBoxes.size}",
            )
            return bubbleCleaner.cleanRegions(image, normalizedBoxes)
        }

        val maskBitmap = BitmapPool.getALPHA8(cropW, cropH)
        try {
            maskBitmap.eraseColor(0)
            // TachiyomiAT: the neural (AOT) erase mask is now SOLID padded
            // PaddleOCR-v6 line boxes — the prototype `mask_mode = paddle_boxes`.
            // [boxes] arrive here already back-projected to page coords by
            // refineFreeTextBoxes (Paddle line boxes, or a detector-v4 fallback
            // box where Paddle found nothing). Localize them to the crop and
            // build the mask with BubbleMaskBuilder.buildRectMask: pad each box
            // by MASK_PAD and dilate with a disk SE. This is deliberately boring
            // and direct (Paddle box → pad → mask → inpaint); the prior bug was
            // caused by shrinking/over-processing masks after detection via the
            // pixel-heuristic buildTightTextRegionMask.
            val localBoxes = normalizedBoxes.map { box ->
                intArrayOf(box[0] - cropX1, box[1] - cropY1, box[2] - cropX1, box[3] - cropY1)
            }
            val textRegionMask = BubbleMaskBuilder.buildRectMask(
                boxes = localBoxes,
                width = cropW,
                height = cropH,
                pad = MASK_PAD,
                dilateRadius = 2,
            )
            // Convert the byte mask to the ARGB mask bitmap the inpaint() path
            // expects: 1 byte → opaque white, 0 byte → transparent.
            val maskPixels = IntArray(cropW * cropH)
            for (i in textRegionMask.indices) {
                if (textRegionMask[i] != 0.toByte()) maskPixels[i] = 0xFFFFFFFF.toInt()
            }
            maskBitmap.setPixels(maskPixels, 0, cropW, 0, 0, cropW, cropH)

            // TachiyomiAT: buildRectMask already dilates with a disk SE, so no
            // second dilation pass here (the prior dilateMask call would double-
            // grow the already-dilated mask).
            return inpaint(
                sess = sess,
                image = image,
                maskBitmap = maskBitmap,
                cropBounds = intArrayOf(cropX1, cropY1, cropX2, cropY2),
                maskAlreadyCropped = true,
                fallbackBoxes = normalizedBoxes,
            )
        } finally {
            BitmapPool.putALPHA8(maskBitmap)
        }
    }

    private fun inpaint(
        sess: OrtSession,
        image: Bitmap,
        maskBitmap: Bitmap,
        cropBounds: IntArray,
        maskAlreadyCropped: Boolean = false,
        fallbackBoxes: List<IntArray> = emptyList(),
    ): Bitmap {
        val cropMargin = 32
        val origW = image.width
        val origH = image.height

        val bx1 = cropBounds[0]
        val by1 = cropBounds[1]
        val bx2 = cropBounds[2]
        val by2 = cropBounds[3]

        val yMin = if (maskAlreadyCropped) by1 else max(0, by1 - cropMargin)
        val yMax = if (maskAlreadyCropped) by2 else min(origH, by2 + cropMargin + 1)
        val xMin = if (maskAlreadyCropped) bx1 else max(0, bx1 - cropMargin)
        val xMax = if (maskAlreadyCropped) bx2 else min(origW, bx2 + cropMargin + 1)

        val cropW = xMax - xMin
        val cropH = yMax - yMin

        val needsResize = max(cropW, cropH) > MAX_INFERENCE_DIM
        val inferW: Int
        val inferH: Int
        if (needsResize) {
            val scale = MAX_INFERENCE_DIM.toFloat() / max(cropW, cropH)
            val wScaled = max(8, (cropW * scale).toInt())
            val hScaled = max(8, (cropH * scale).toInt())
            inferW = wScaled + (8 - wScaled % 8) % 8
            inferH = hScaled + (8 - hScaled % 8) % 8
        } else {
            val padW = (8 - cropW % 8) % 8
            val padH = (8 - cropH % 8) % 8
            inferW = cropW + padW
            inferH = cropH + padH
        }

        var imgInput: Bitmap? = null
        var maskInput: Bitmap? = null
        var imgTensor: OnnxTensor? = null
        var maskTensor: OnnxTensor? = null
        var results: OrtSession.Result? = null
        var resultBitmap: Bitmap? = null
        var scaled: Bitmap? = null
        var blended: Bitmap? = null
        try {
            imgInput = BitmapPool.getARGB8888(inferW, inferH)
            imgInput.eraseColor(0)
            val imgInputCanvas = android.graphics.Canvas(imgInput)
            if (needsResize) {
                imgInputCanvas.drawBitmap(image, android.graphics.Rect(xMin, yMin, xMin + cropW, yMin + cropH), android.graphics.RectF(0f, 0f, inferW.toFloat(), inferH.toFloat()), null)
            } else {
                imgInputCanvas.drawBitmap(image, android.graphics.Rect(xMin, yMin, xMin + cropW, yMin + cropH), android.graphics.Rect(0, 0, cropW, cropH), null)
            }

            maskInput = BitmapPool.getARGB8888(inferW, inferH)
            maskInput.eraseColor(0)
            val maskInputCanvas = android.graphics.Canvas(maskInput)
            if (maskAlreadyCropped) {
                if (needsResize) {
                    maskInputCanvas.drawBitmap(maskBitmap, android.graphics.Rect(0, 0, maskBitmap.width, maskBitmap.height), android.graphics.RectF(0f, 0f, inferW.toFloat(), inferH.toFloat()), null)
                } else {
                    maskInputCanvas.drawBitmap(maskBitmap, android.graphics.Rect(0, 0, maskBitmap.width, maskBitmap.height), android.graphics.Rect(0, 0, cropW, cropH), null)
                }
            } else {
                if (needsResize) {
                    maskInputCanvas.drawBitmap(maskBitmap, android.graphics.Rect(xMin, yMin, xMin + cropW, yMin + cropH), android.graphics.RectF(0f, 0f, inferW.toFloat(), inferH.toFloat()), null)
                } else {
                    maskInputCanvas.drawBitmap(maskBitmap, android.graphics.Rect(xMin, yMin, xMin + cropW, yMin + cropH), android.graphics.Rect(0, 0, cropW, cropH), null)
                }
            }

            val totalPixels = inferW * inferH
            val imgData = getImgData()
            val maskData = getMaskData()
            val imgPixels = getImgPixels()
            val maskPixels = getMaskPixels()

            imgInput.getPixels(imgPixels, 0, inferW, 0, 0, inferW, inferH)
            maskInput.getPixels(maskPixels, 0, inferW, 0, 0, inferW, inferH)

            for (y in 0 until inferH) {
                for (x in 0 until inferW) {
                    val idx = y * inferW + x
                    val px = imgPixels[idx]
                    val r = (px shr 16 and 0xFF) / 127.5f - 1.0f
                    val g = (px shr 8 and 0xFF) / 127.5f - 1.0f
                    val b = (px and 0xFF) / 127.5f - 1.0f
                    val m = if (maskValue(maskPixels[idx]) > 127) 1.0f else 0.0f
                    maskData[idx] = m
                    imgData[0 * totalPixels + idx] = r * (1.0f - m)
                    imgData[1 * totalPixels + idx] = g * (1.0f - m)
                    imgData[2 * totalPixels + idx] = b * (1.0f - m)
                }
            }

            val imgBuffer = FloatBuffer.wrap(imgData, 0, 3 * totalPixels)
            val maskBuffer = FloatBuffer.wrap(maskData, 0, totalPixels)

            imgTensor = OnnxTensor.createTensor(
                OnnxRuntimeProvider.environment,
                imgBuffer,
                longArrayOf(1, 3, inferH.toLong(), inferW.toLong()),
            )
            maskTensor = OnnxTensor.createTensor(
                OnnxRuntimeProvider.environment,
                maskBuffer,
                longArrayOf(1, 1, inferH.toLong(), inferW.toLong()),
            )

            val t0 = System.nanoTime()
            val imgTensorValue = imgTensor!!
            val maskTensorValue = maskTensor!!
            val feed = mapOf("image" to imgTensorValue, "mask" to maskTensorValue)
            results = sess.run(feed)
            val t1 = System.nanoTime()

            val outputTensor = results!![0] as OnnxTensor
            val outputShape = outputTensor.info.shape
            val outH = outputShape[2].toInt()
            val outW = outputShape[3].toInt()
            val outputBuf = outputTensor.floatBuffer

            val resultH = outH
            val resultW = outW

            val resultPixels = getResultPixels()
            val outChannels = outH * outW
            for (y in 0 until resultH) {
                for (x in 0 until resultW) {
                    val idx = y * resultW + x
                    val srcIdx = y * outW + x
                    val r = ((outputBuf.get(0 * outChannels + srcIdx) + 1.0f) * 127.5f)
                        .roundToInt().coerceIn(0, 255)
                    val g = ((outputBuf.get(1 * outChannels + srcIdx) + 1.0f) * 127.5f)
                        .roundToInt().coerceIn(0, 255)
                    val b = ((outputBuf.get(2 * outChannels + srcIdx) + 1.0f) * 127.5f)
                        .roundToInt().coerceIn(0, 255)
                    resultPixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }

            resultBitmap = BitmapPool.getARGB8888(resultW, resultH)
            resultBitmap.setPixels(resultPixels, 0, resultW, 0, 0, resultW, resultH)

            scaled = BitmapPool.getARGB8888(cropW, cropH)
            val scaledCanvas = android.graphics.Canvas(scaled)
            if (needsResize) {
                scaledCanvas.drawBitmap(
                    resultBitmap,
                    null,
                    android.graphics.RectF(0f, 0f, cropW.toFloat(), cropH.toFloat()),
                    null,
                )
            } else {
                scaledCanvas.drawBitmap(
                    resultBitmap,
                    android.graphics.Rect(0, 0, cropW, cropH),
                    android.graphics.RectF(0f, 0f, cropW.toFloat(), cropH.toFloat()),
                    null,
                )
            }

            val candidate = scaled
            if (isSuspiciousGrayOutput(candidate, maskBitmap, maskAlreadyCropped, xMin, yMin, cropW, cropH)) {
                logcat(LogPriority.WARN) {
                    "[inpaint] suspicious uniform mid-gray output; falling back to smart cleaner"
                }
                val boxesForFallback = fallbackBoxes.ifEmpty { listOf(intArrayOf(bx1, by1, bx2, by2)) }
                return bubbleCleaner.cleanRegions(image, boxesForFallback)
            }

            blended = featherBlend(image, candidate, maskBitmap, maskAlreadyCropped, xMin, yMin, cropW, cropH)

            val canvas = android.graphics.Canvas(image)
            canvas.drawBitmap(blended ?: throw IllegalStateException("Inpainting blend was not created"), xMin.toFloat(), yMin.toFloat(), null)

            logcat(LogPriority.INFO) {
                "[inpaint] model=${(t1 - t0) / 1_000_000.0}ms crop=${cropW}x${cropH} infer=${inferW}x${inferH}"
            }

            return image
        } finally {
            results?.close()
            imgTensor?.close()
            maskTensor?.close()
            if (blended != null) BitmapPool.putARGB8888(blended)
            // TachiyomiAT: scaled is always a fresh bitmap (not an alias of
            // resultBitmap) after the unconditional normalization step above.
            if (scaled != null) BitmapPool.putARGB8888(scaled)
            if (resultBitmap != null) BitmapPool.putARGB8888(resultBitmap)
            if (maskInput != null) BitmapPool.putARGB8888(maskInput)
            if (imgInput != null) BitmapPool.putARGB8888(imgInput)
        }
    }

    private fun isSuspiciousGrayOutput(
        inpainted: Bitmap,
        mask: Bitmap,
        maskAlreadyCropped: Boolean,
        xMin: Int,
        yMin: Int,
        width: Int,
        height: Int,
    ): Boolean {
        // TachiyomiAT: clamp the read region to the actual bitmap bounds.
        // After the unconditional normalization in inpaint() the inpainted
        // bitmap IS cropW × cropH, but this clamp avoids a crash if any edge
        // case (delegate shape mismatch, OOM partial fill, etc.) produces a
        // smaller bitmap.  The guard result from a smaller region is still
        // meaningful — if the visible region is uniformly gray, the model
        // failed.
        val inpW = min(width, inpainted.width)
        val inpH = min(height, inpainted.height)
        val mskW = min(width, mask.width)
        val mskH = min(height, mask.height)
        val safeW = min(inpW, mskW)
        val safeH = min(inpH, mskH)

        // TachiyomiAT: size the read buffers to the ACTUAL read region, NOT the
        // pooled MAX_TOTAL_PIXELS (512×512). width/height here are the unbounded
        // CROP dimensions (cropMargin can push them well past 512), so reading
        // safeW*safeH into the 512² pooled buffer overflows →
        // ArrayIndexOutOfBoundsException in Bitmap.getPixels, which crashed
        // inpaint deterministically on large text regions (the page-24
        // "cannot reprocess" reproduction). The pooled buffers are an
        // optimization for the fixed 512² inference read; these post-model
        // guard reads are on the crop-sized bitmap, so they must be transient.
        val readSize = safeW * safeH
        val inpaintedPixels = if (readSize <= MAX_TOTAL_PIXELS) getResultPixels() else IntArray(readSize)
        val maskPixels = if (readSize <= MAX_TOTAL_PIXELS) getMaskPixels() else IntArray(readSize)
        inpainted.getPixels(inpaintedPixels, 0, safeW, 0, 0, safeW, safeH)
        if (maskAlreadyCropped) {
            mask.getPixels(maskPixels, 0, safeW, 0, 0, safeW, safeH)
        } else {
            mask.getPixels(maskPixels, 0, safeW, xMin, yMin, safeW, safeH)
        }
        return AotOutputGuard.isSuspiciousGrayFill(inpaintedPixels, maskPixels, safeW, safeH)
    }

    private fun featherBlend(
        original: Bitmap,
        inpainted: Bitmap,
        mask: Bitmap,
        maskAlreadyCropped: Boolean,
        xMin: Int,
        yMin: Int,
        width: Int,
        height: Int,
    ): Bitmap {
        val result = BitmapPool.getARGB8888(width, height)
        // TachiyomiAT: size the read buffers to the ACTUAL crop dimensions, NOT
        // the pooled MAX_TOTAL_PIXELS (512×512). width/height are the unbounded
        // CROP dims (cropMargin can push them past 512); reading them into the
        // 512² pooled buffer overflowed → ArrayIndexOutOfBoundsException (the
        // page-24 inpaint crash). The pooled buffers only fit the fixed 512²
        // inference read; these post-model blend reads are on the crop-sized
        // bitmap, so fall back to a transient allocation when the crop exceeds
        // the pool cap.
        val blendSize = width * height
        val origPixels = if (blendSize <= MAX_TOTAL_PIXELS) getImgPixels() else IntArray(blendSize)
        val inpPixels = if (blendSize <= MAX_TOTAL_PIXELS) getResultPixels() else IntArray(blendSize)
        val maskPixels = if (blendSize <= MAX_TOTAL_PIXELS) getMaskPixels() else IntArray(blendSize)

        original.getPixels(origPixels, 0, width, xMin, yMin, width, height)

        // TachiyomiAT: clamp the inpainted bitmap read to its actual size.
        // The unconditional normalization in inpaint() makes the inpainted
        // bitmap cropW × cropH, but this clamp is a last-resort safety net.
        val inpW = min(width, inpainted.width)
        val inpH = min(height, inpainted.height)
        inpainted.getPixels(inpPixels, 0, inpW, 0, 0, inpW, inpH)
        // Fill pixels beyond the clamped region with the original so the
        // blend loop below never reads uninitialised data.
        for (y in 0 until inpH) {
            for (x in inpW until width) {
                inpPixels[y * width + x] = origPixels[y * width + x]
            }
        }
        for (y in inpH until height) {
            for (x in 0 until width) {
                inpPixels[y * width + x] = origPixels[y * width + x]
            }
        }

        if (maskAlreadyCropped) {
            mask.getPixels(maskPixels, 0, width, 0, 0, width, height)
        } else {
            mask.getPixels(maskPixels, 0, width, xMin, yMin, width, height)
        }

        val featherR = max(2, min(6, min(width, height) / 8))
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val alpha = if (maskValue(maskPixels[idx]) > 127) {
                    1.0f
                } else {
                    var covered = 0
                    var total = 0
                    for (dy in -featherR..featherR) {
                        val yy = y + dy
                        if (yy !in 0 until height) continue
                        for (dx in -featherR..featherR) {
                            val xx = x + dx
                            if (xx !in 0 until width) continue
                            total++
                            if (maskValue(maskPixels[yy * width + xx]) > 127) covered++
                        }
                    }
                    if (total == 0) 0.0f else covered.toFloat() / total.toFloat()
                }
                origPixels[idx] = if (alpha <= 0.0f) origPixels[idx] else blendPixel(origPixels[idx], inpPixels[idx], alpha)
            }
        }
        result.setPixels(origPixels, 0, width, 0, 0, width, height)
        return result
    }

    private fun blendPixel(original: Int, inpainted: Int, alpha: Float): Int {
        val inv = 1.0f - alpha
        val r = ((original shr 16 and 0xFF) * inv + (inpainted shr 16 and 0xFF) * alpha).roundToInt().coerceIn(0, 255)
        val g = ((original shr 8 and 0xFF) * inv + (inpainted shr 8 and 0xFF) * alpha).roundToInt().coerceIn(0, 255)
        val b = ((original and 0xFF) * inv + (inpainted and 0xFF) * alpha).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun maskValue(pixel: Int): Int = max(pixel and 0xFF, pixel ushr 24)

    private fun findParentBubble(
        textBox: IntArray,
        bubbleBoxes: List<IntArray>,
    ): IntArray? {
        val cx = (textBox[0] + textBox[2]) / 2.0
        val cy = (textBox[1] + textBox[3]) / 2.0
        var best: IntArray? = null
        var bestArea = Float.MAX_VALUE
        for (bubble in bubbleBoxes) {
            if (cx >= bubble[0] && cx <= bubble[2] && cy >= bubble[1] && cy <= bubble[3]) {
                val area = (bubble[2] - bubble[0]) * (bubble[3] - bubble[1]).toFloat()
                if (area < bestArea) {
                    best = bubble
                    bestArea = area
                }
            }
        }
        return best
    }

    private fun clusterNearbyBoxes(boxes: List<IntArray>, clusterDistance: Int): List<List<IntArray>> {
        if (boxes.isEmpty()) return emptyList()
        if (boxes.size == 1) return listOf(boxes)

        val visited = BooleanArray(boxes.size)
        val clusters = mutableListOf<List<IntArray>>()

        fun centerDistance(a: IntArray, b: IntArray): Double {
            val cx1 = (a[0] + a[2]) / 2.0
            val cy1 = (a[1] + a[3]) / 2.0
            val cx2 = (b[0] + b[2]) / 2.0
            val cy2 = (b[1] + b[3]) / 2.0
            return kotlin.math.sqrt((cx1 - cx2) * (cx1 - cx2) + (cy1 - cy2) * (cy1 - cy2))
        }

        for (i in boxes.indices) {
            if (visited[i]) continue
            val cluster = mutableListOf<IntArray>()
            val queue = ArrayDeque<Int>()
            queue.add(i)
            visited[i] = true
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                cluster.add(boxes[current])
                for (j in boxes.indices) {
                    if (visited[j]) continue
                    if (centerDistance(boxes[current], boxes[j]) <= clusterDistance) {
                        visited[j] = true
                        queue.add(j)
                    }
                }
            }
            clusters.add(cluster)
        }
        return clusters
    }

    fun close() {
        bubbleCleaner.clearWorkingBuffers()
        session?.close()
        session = null
        clearScratch()
    }

    /**
     * TachiyomiAT: drop the cross-call working buffers while staying usable.
     *
     * [SmartBubbleTextCleaner] retains its largest-seen IntArray pair for the
     * engine's lifetime (so a dense early page pins large heap arrays for the
     * whole session). On OOM recovery we want that heap back; the next inpaint
     * simply reallocates a buffer sized to the page it actually sees.
     */
    fun reclaimPooledMemory() {
        bubbleCleaner.clearWorkingBuffers()
    }

    fun forceReleaseNativeBuffers() {
        bubbleCleaner.clearWorkingBuffers()
        clearScratch()
    }

    private fun adaptiveInferenceDim(cropW: Int, cropH: Int): Int {
        val maxSide = max(cropW, cropH)
        return when {
            maxSide <= 300 -> maxSide
            maxSide <= 500 -> 384
            maxSide <= 800 -> 512
            else -> 640
        }
    }
}
