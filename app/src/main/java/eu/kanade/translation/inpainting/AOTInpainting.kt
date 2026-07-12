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
import tachiyomi.domain.translation.pools.DirectBufferPool
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class AOTInpainting {

    companion object {
        private const val MAX_INFERENCE_DIM = 768
        private const val MAX_TOTAL_PIXELS = MAX_INFERENCE_DIM * MAX_INFERENCE_DIM

        // TachiyomiAT: PaddleOCR-v6 solid-box erase-mask tunables. Defaults match
        // the validated prototype (tools/inpaint-debug-viewer/server.py); kept
        // internal (no settings surface).
        private const val PADDLE_CROP_PAD = 12
        private const val PADDLE_THRESH = 0.18f
        private const val PADDLE_BOX_THRESH = 0.34f
        private const val MASK_PAD = 8
        private const val REPORT_FREE_TEXT_PAD = 16
        private const val REPORT_FREE_TEXT_DILATE = 8
        private const val REPORT_AOT_CONTEXT = 512
        private const val REPORT_FREE_TEXT_FEATHER = 3
        private const val REPORT_BUBBLE_SMOOTH_PASSES = 12
        private const val REPORT_PUSH_PULL_CONTEXT = 64

        // TachiyomiAT: distance-field feather ramp (px) blending the neural
        // output with the page. Replaces the earlier 2–6px box-blur cliff that
        // exposed the erase-box rectangle. See [BubbleMaskBuilder.featherAlphaField].
        private const val FEATHER_RAMP_PX = 12
    }

    private val scratchLock = Any()

    // TachiyomiAT: pooled DIRECT buffers for the variable-shape inpaint tensors
    // (memory: maxPoolSize=2 bounds resident native memory; the active infer
    // region is exposed via the buffer limit).
    private val imgInputPool = DirectBufferPool(
        bufferCapacityBytes = 3 * MAX_TOTAL_PIXELS * Float.SIZE_BYTES,
        maxPoolSize = 2,
    )
    private val maskInputPool = DirectBufferPool(
        bufferCapacityBytes = MAX_TOTAL_PIXELS * Float.SIZE_BYTES,
        maxPoolSize = 2,
    )

    private var sharedImgPixels: IntArray? = null
    private var sharedMaskPixels: IntArray? = null
    private var sharedResultPixels: IntArray? = null

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
            sharedImgPixels = null
            sharedMaskPixels = null
            sharedResultPixels = null
        }
    }

    private var session: OrtSession? = null
    private val bubbleCleaner = SmartBubbleTextCleaner()
    var paddleDetector: eu.kanade.translation.ocr.PaddleOcrV6DetEngine? = null

    // TachiyomiAT: resolved-once cache of translation_diagnostics to avoid
    // SharedPreferences reads on the hot path (mirrors the OCR engines).
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
        val opts = OnnxRuntimeProvider.createSessionOptions(
            useAccelerator = false,
            useXnnpack = true,
            disableIntraOpSpinning = true,
        )
        try {
            session = OnnxRuntimeProvider.environment.createSession(modelFile.absolutePath, opts)
        } finally {
            opts.close()
        }
        logcat(LogPriority.INFO) { "AOT Inpainting CPU session created from ${modelFile.name}" }

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
     * TachiyomiAT: assert the session inputs match what [inpaint] feeds it
     * ("image", "mask"). A name mismatch otherwise surfaces only as a generic
     * [OrtException] that the caller turns into a silent FAILED, hiding the
     * cause; asserting here gives a readable error. Throws on mismatch.
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
        mode: InpaintingMode = InpaintingMode.QUALITY,
        blocks: List<eu.kanade.translation.model.TranslationBlock>? = null,
    ): Bitmap {
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

        val bubbleTextBoxes = mutableListOf<IntArray>()
        val freeTextDetectorBoxes = mutableListOf<IntArray>()

        for (i in textBoxes.indices) {
            val box = textBoxes[i]
            val lbl = textLabels[i]
            val parent = AotBoxGeometry.findParentBubble(box, bubbleBoxes)
            if (parent != null || AotBoxGeometry.overlapsAnyBubble(box, bubbleBoxes)) {
                bubbleTextBoxes.add(box)
            } else if (lbl == 2) {
                freeTextDetectorBoxes.add(box)
            } else {
                bubbleTextBoxes.add(box)
            }
        }

        var paddleLinesTotal = 0
        var paddleFallback = 0
        val freeTextGroups: List<List<IntArray>> = if (paddleDetector != null && freeTextDetectorBoxes.isNotEmpty()) {
            val refined = refineFreeTextGroups(image, freeTextDetectorBoxes)
            paddleLinesTotal = refined.paddleLineCount
            paddleFallback = refined.fallbackCount
            refined.groups
        } else {
            freeTextDetectorBoxes.map { listOf(it.copyOf()) }
        }

        logcat(LogPriority.INFO) {
            "[inpaint] pipeline=investigation_report bubbleText=${bubbleTextBoxes.size} " +
                "freeDets=${freeTextDetectorBoxes.size} freeGroups=${freeTextGroups.size} " +
                "mode=$mode model=${sess != null}"
        }
        if (paddleDetector != null && freeTextDetectorBoxes.isNotEmpty()) {
            logcat(LogPriority.INFO) {
                "[inpaint] report_paddle detectorText=${freeTextDetectorBoxes.size} paddleLines=$paddleLinesTotal " +
                    "fallback=$paddleFallback linePad=$REPORT_FREE_TEXT_PAD lineDilate=$REPORT_FREE_TEXT_DILATE " +
                    "aotContext=$REPORT_AOT_CONTEXT thresh=$PADDLE_THRESH boxThresh=$PADDLE_BOX_THRESH cropPad=$PADDLE_CROP_PAD"
            }
        }

        result = inpaintReportBubbles(result, bubbleTextBoxes, blocks)

        for (group in freeTextGroups) {
            if (group.isEmpty()) continue
            if (mode == InpaintingMode.QUALITY && sess != null) {
                try {
                    result = inpaintReportFreeTextAot512(sess, result, group)
                } catch (oom: OutOfMemoryError) {
                    BitmapPool.releaseAll()
                    System.gc()
                    logcat(LogPriority.WARN) { "[inpaint] report AOT OOM; falling back to grouped push-pull" }
                    result = inpaintReportFreeTextFast(result, group)
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "[inpaint] report AOT failed; falling back to grouped push-pull" }
                    result = inpaintReportFreeTextFast(result, group)
                }
            } else {
                result = inpaintReportFreeTextFast(result, group)
            }
        }

        return result
    }

    private data class RefinedFreeTextGroups(
        val groups: List<List<IntArray>>,
        val paddleLineCount: Int,
        val fallbackCount: Int,
    )

    private fun refineFreeTextGroups(
        image: Bitmap,
        detectorBoxes: List<IntArray>,
    ): RefinedFreeTextGroups {
        val w = image.width
        val h = image.height
        val paddleDetector = this.paddleDetector ?: return RefinedFreeTextGroups(detectorBoxes.map { listOf(it.copyOf()) }, 0, detectorBoxes.size)
        val groups = ArrayList<List<IntArray>>(detectorBoxes.size)
        var paddleLineCount = 0
        var fallbackCount = 0
        for (det in detectorBoxes) {
            val cx1 = (det[0] - PADDLE_CROP_PAD).coerceIn(0, w)
            val cy1 = (det[1] - PADDLE_CROP_PAD).coerceIn(0, h)
            val cx2 = (det[2] + PADDLE_CROP_PAD).coerceIn(0, w)
            val cy2 = (det[3] + PADDLE_CROP_PAD).coerceIn(0, h)
            if (cx2 <= cx1 || cy2 <= cy1) {
                groups.add(listOf(det.copyOf()))
                fallbackCount++
                continue
            }
            val crop = Bitmap.createBitmap(image, cx1, cy1, cx2 - cx1, cy2 - cy1)
            val lines = try {
                paddleDetector.detectLines(crop, thresh = PADDLE_THRESH, boxThresh = PADDLE_BOX_THRESH)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "[inpaint] report Paddle DET failed; using detector free-text box" }
                emptyList()
            } finally {
                crop.recycle()
            }
            if (lines.isEmpty()) {
                groups.add(listOf(det.copyOf()))
                fallbackCount++
                continue
            }
            val group = ArrayList<IntArray>(lines.size)
            for (line in lines) {
                val b = line.bbox
                if (b.size < 4) continue
                val px1 = (cx1 + b[0]).coerceIn(0, w)
                val py1 = (cy1 + b[1]).coerceIn(0, h)
                val px2 = (cx1 + b[2]).coerceIn(0, w)
                val py2 = (cy1 + b[3]).coerceIn(0, h)
                if (px2 > px1 && py2 > py1) {
                    group.add(intArrayOf(px1, py1, px2, py2))
                    paddleLineCount++
                }
            }
            if (group.isEmpty()) {
                groups.add(listOf(det.copyOf()))
                fallbackCount++
            } else {
                groups.add(group)
            }
        }
        return RefinedFreeTextGroups(groups, paddleLineCount, fallbackCount)
    }

    private fun inpaintReportBubbles(image: Bitmap, boxes: List<IntArray>, blocks: List<eu.kanade.translation.model.TranslationBlock>?): Bitmap {
        if (boxes.isEmpty() && (blocks == null || blocks.none { it.segmentationMask != null })) return image
        val w = image.width
        val h = image.height
        val mask = ByteArray(w * h)
        var hasMask = false
        if (blocks != null) {
            for (block in blocks) {
                block.segmentationMask?.rasterizeOnto(mask)
                if (block.segmentationMask != null) {
                    hasMask = true
                }
            }
        }
        if (!hasMask) {
            val pillMask = BubbleMaskBuilder.buildDynamicPillMask(boxes, w, h, MASK_PAD)
            System.arraycopy(pillMask, 0, mask, 0, mask.size)
        }
        if (mask.none { it != 0.toByte() }) return image
        val pixels = IntArray(w * h)
        image.getPixels(pixels, 0, w, 0, 0, w, h)
        AotReportBubbleFill.reportBubbleFill(pixels, mask, w, h, REPORT_BUBBLE_SMOOTH_PASSES)
        val alpha = BubbleMaskBuilder.featherAlphaField(mask, w, h, FEATHER_RAMP_PX)
        val original = IntArray(w * h)
        image.getPixels(original, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val a = alpha[i]
            if (a > 0.0f) pixels[i] = AotPixelOps.blendPixel(original[i], pixels[i], a)
        }
        image.setPixels(pixels, 0, w, 0, 0, w, h)
        return image
    }


    private fun inpaintReportFreeTextFast(image: Bitmap, boxes: List<IntArray>): Bitmap {
        val bounds = AotBoxGeometry.paddedUnionBounds(boxes, image.width, image.height, REPORT_PUSH_PULL_CONTEXT) ?: return image
        val cropW = bounds[2] - bounds[0]
        val cropH = bounds[3] - bounds[1]
        if (cropW <= 0 || cropH <= 0) return image
        val localBoxes = boxes.mapNotNull { AotBoxGeometry.localizeBox(it, bounds[0], bounds[1], cropW, cropH) }
        val mask = BubbleMaskBuilder.buildFixedPillMask(localBoxes, cropW, cropH, REPORT_FREE_TEXT_PAD, REPORT_FREE_TEXT_DILATE)
        if (mask.none { it != 0.toByte() }) return image
        val original = IntArray(cropW * cropH)
        image.getPixels(original, 0, cropW, bounds[0], bounds[1], cropW, cropH)
        val work = original.copyOf()
        val bg = PushPullGradient.localRingMedian(work, cropW, cropH, mask, PushPullGradient.DEFAULT_RING)
        PushPullGradient.pushPullFill(work, cropW, cropH, mask, bg)
        val alpha = BubbleMaskBuilder.featherAlphaField(mask, cropW, cropH, REPORT_FREE_TEXT_FEATHER)
        for (i in work.indices) {
            val a = alpha[i]
            if (a > 0.0f) work[i] = AotPixelOps.blendPixel(original[i], work[i], a)
        }
        image.setPixels(work, 0, cropW, bounds[0], bounds[1], cropW, cropH)
        return image
    }

    private fun inpaintReportFreeTextAot512(sess: OrtSession, image: Bitmap, boxes: List<IntArray>): Bitmap {
        val crop = AotBoxGeometry.centeredReportCrop(boxes, image.width, image.height, REPORT_AOT_CONTEXT) ?: return image
        val cropWidth = crop[2] - crop[0]
        val cropHeight = crop[3] - crop[1]
        if (cropWidth != cropHeight || cropWidth <= 0) return image
        if (!TranslationMemoryBudget.canRunNeuralInpaint(image.width, image.height, cropWidth, cropHeight)) {
            TranslationMemoryBudget.logSnapshot(
                tag = "skip_report_aot512",
                width = image.width,
                height = image.height,
                extra = "crop=${cropWidth}x$cropHeight boxes=${boxes.size}",
            )
            return inpaintReportFreeTextFast(image, boxes)
        }
        val localBoxes = boxes.mapNotNull { AotBoxGeometry.localizeBox(it, crop[0], crop[1], cropWidth, cropHeight) }
        val maskBytes = BubbleMaskBuilder.buildFixedPillMask(localBoxes, cropWidth, cropHeight, REPORT_FREE_TEXT_PAD, REPORT_FREE_TEXT_DILATE)
        if (maskBytes.none { it != 0.toByte() }) return image
        val maskBitmap = BitmapPool.getALPHA8(cropWidth, cropHeight)
        try {
            setAlphaMaskPixels(maskBitmap, maskBytes, cropWidth, cropHeight)
            return inpaint(
                sess = sess,
                image = image,
                maskBitmap = maskBitmap,
                cropBounds = crop,
                maskAlreadyCropped = true,
                fallbackBoxes = boxes,
                featherRampPx = 0,
                reportFallbackOnly = true,
            )
        } finally {
            BitmapPool.putALPHA8(maskBitmap)
        }
    }

    private fun setAlphaMaskPixels(maskBitmap: Bitmap, maskBytes: ByteArray, width: Int, height: Int) {
        val maskPixels = IntArray(width * height)
        for (i in maskBytes.indices) {
            if (maskBytes[i] != 0.toByte()) maskPixels[i] = 0xFFFFFFFF.toInt()
        }
        maskBitmap.setPixels(maskPixels, 0, width, 0, 0, width, height)
    }

    private fun inpaint(
        sess: OrtSession,
        image: Bitmap,
        maskBitmap: Bitmap,
        cropBounds: IntArray,
        maskAlreadyCropped: Boolean = false,
        fallbackBoxes: List<IntArray> = emptyList(),
        featherRampPx: Int = FEATHER_RAMP_PX,
        reportFallbackOnly: Boolean = false,
    ): Bitmap {
        val cropMargin = 32
        val originalWidth = image.width
        val originalHeight = image.height

        val boxX1 = cropBounds[0]
        val boxY1 = cropBounds[1]
        val boxX2 = cropBounds[2]
        val boxY2 = cropBounds[3]

        val yMin = if (maskAlreadyCropped) boxY1 else max(0, boxY1 - cropMargin)
        val yMax = if (maskAlreadyCropped) boxY2 else min(originalHeight, boxY2 + cropMargin + 1)
        val xMin = if (maskAlreadyCropped) boxX1 else max(0, boxX1 - cropMargin)
        val xMax = if (maskAlreadyCropped) boxX2 else min(originalWidth, boxX2 + cropMargin + 1)

        val cropWidth = xMax - xMin
        val cropHeight = yMax - yMin

        val cropPixelsOriginal = IntArray(cropWidth * cropHeight)
        image.getPixels(cropPixelsOriginal, 0, cropWidth, xMin, yMin, cropWidth, cropHeight)

        var totalChroma = 0
        val sampleStride = max(1, cropPixelsOriginal.size / 400)
        for (i in cropPixelsOriginal.indices step sampleStride) {
            val px = cropPixelsOriginal[i]
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            totalChroma += (maxC - minC)
        }
        val avgChroma = totalChroma / (cropPixelsOriginal.size / sampleStride)
        val isGrayscale = avgChroma < 15

        val needsResize = max(cropWidth, cropHeight) > MAX_INFERENCE_DIM
        val inferenceWidth: Int
        val inferenceHeight: Int
        if (needsResize) {
            val scale = MAX_INFERENCE_DIM.toFloat() / max(cropWidth, cropHeight)
            val wScaled = max(8, (cropWidth * scale).toInt())
            val hScaled = max(8, (cropHeight * scale).toInt())
            inferenceWidth = wScaled + (8 - wScaled % 8) % 8
            inferenceHeight = hScaled + (8 - hScaled % 8) % 8
        } else {
            val padW = (8 - cropWidth % 8) % 8
            val padH = (8 - cropHeight % 8) % 8
            inferenceWidth = cropWidth + padW
            inferenceHeight = cropHeight + padH
        }

        var imgInput: Bitmap? = null
        var maskInput: Bitmap? = null
        var imgTensor: OnnxTensor? = null
        var maskTensor: OnnxTensor? = null
        var imgBuffer: FloatBuffer? = null
        var maskBuffer: FloatBuffer? = null
        var results: OrtSession.Result? = null
        var resultBitmap: Bitmap? = null
        var scaled: Bitmap? = null
        var blended: Bitmap? = null
        try {
            imgInput = BitmapPool.getARGB8888(inferenceWidth, inferenceHeight)
            imgInput.eraseColor(0)
            val imgInputCanvas = android.graphics.Canvas(imgInput)
            if (needsResize) {
                imgInputCanvas.drawBitmap(image, android.graphics.Rect(xMin, yMin, xMin + cropWidth, yMin + cropHeight), android.graphics.RectF(0f, 0f, inferenceWidth.toFloat(), inferenceHeight.toFloat()), null)
            } else {
                imgInputCanvas.drawBitmap(image, android.graphics.Rect(xMin, yMin, xMin + cropWidth, yMin + cropHeight), android.graphics.Rect(0, 0, cropWidth, cropHeight), null)
            }

            maskInput = BitmapPool.getARGB8888(inferenceWidth, inferenceHeight)
            maskInput.eraseColor(0)
            val maskInputCanvas = android.graphics.Canvas(maskInput)
            if (maskAlreadyCropped) {
                if (needsResize) {
                    maskInputCanvas.drawBitmap(maskBitmap, android.graphics.Rect(0, 0, maskBitmap.width, maskBitmap.height), android.graphics.RectF(0f, 0f, inferenceWidth.toFloat(), inferenceHeight.toFloat()), null)
                } else {
                    maskInputCanvas.drawBitmap(maskBitmap, android.graphics.Rect(0, 0, maskBitmap.width, maskBitmap.height), android.graphics.Rect(0, 0, cropWidth, cropHeight), null)
                }
            } else {
                if (needsResize) {
                    maskInputCanvas.drawBitmap(maskBitmap, android.graphics.Rect(xMin, yMin, xMin + cropWidth, yMin + cropHeight), android.graphics.RectF(0f, 0f, inferenceWidth.toFloat(), inferenceHeight.toFloat()), null)
                } else {
                    maskInputCanvas.drawBitmap(maskBitmap, android.graphics.Rect(xMin, yMin, xMin + cropWidth, yMin + cropHeight), android.graphics.Rect(0, 0, cropWidth, cropHeight), null)
                }
            }

            // Reuse the bounded scratch arrays already used by postprocess.
            // getPixels overwrites every element, so a prior page cannot bleed
            // into this tensor even when the inference dimensions shrink.
            val imgPixels = getImgPixels()
            imgInput.getPixels(imgPixels, 0, inferenceWidth, 0, 0, inferenceWidth, inferenceHeight)
            val maskPixels = getMaskPixels()
            maskInput.getPixels(maskPixels, 0, inferenceWidth, 0, 0, inferenceWidth, inferenceHeight)

            imgBuffer = imgInputPool.acquire()
            maskBuffer = maskInputPool.acquire()

            imgBuffer.clear()
            maskBuffer.clear()

            imgBuffer.limit(3 * inferenceWidth * inferenceHeight)
            maskBuffer.limit(1 * inferenceWidth * inferenceHeight)

            val channelSize = inferenceWidth * inferenceHeight
            for (i in 0 until channelSize) {
                val px = imgPixels[i]
                var r = (px shr 16 and 0xFF) / 127.5f - 1.0f
                var g = (px shr 8 and 0xFF) / 127.5f - 1.0f
                var b = (px and 0xFF) / 127.5f - 1.0f

                val maskPixel = maskPixels[i]
                val maskVal = if (AotPixelOps.maskValue(maskPixel) > 127) 1.0f else 0.0f
                maskBuffer.put(0 * channelSize + i, maskVal)
                
                r *= (1.0f - maskVal)
                g *= (1.0f - maskVal)
                b *= (1.0f - maskVal)

                imgBuffer.put(0 * channelSize + i, r)
                imgBuffer.put(1 * channelSize + i, g)
                imgBuffer.put(2 * channelSize + i, b)
            }

            val env = OnnxRuntimeProvider.environment
            imgTensor = OnnxTensor.createTensor(env, imgBuffer, longArrayOf(1, 3, inferenceHeight.toLong(), inferenceWidth.toLong()))
            maskTensor = OnnxTensor.createTensor(env, maskBuffer, longArrayOf(1, 1, inferenceHeight.toLong(), inferenceWidth.toLong()))

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

            val resultPixels = IntArray(resultW * resultH)
            val outChannels = outH * outW
            for (y in 0 until resultH) {
                for (x in 0 until resultW) {
                    val idx = y * resultW + x
                    val srcIdx = y * outW + x
                    val rChannel = ((outputBuf.get(0 * outChannels + srcIdx) + 1.0f) * 127.5f)
                        .roundToInt().coerceIn(0, 255)
                    val gChannel = ((outputBuf.get(1 * outChannels + srcIdx) + 1.0f) * 127.5f)
                        .roundToInt().coerceIn(0, 255)
                    val bChannel = ((outputBuf.get(2 * outChannels + srcIdx) + 1.0f) * 127.5f)
                        .roundToInt().coerceIn(0, 255)
                    if (isGrayscale) {
                        val l = (0.299f * rChannel + 0.587f * gChannel + 0.114f * bChannel).roundToInt()
                        resultPixels[idx] = (0xFF shl 24) or (l shl 16) or (l shl 8) or l
                    } else {
                        resultPixels[idx] = (0xFF shl 24) or (rChannel shl 16) or (gChannel shl 8) or bChannel
                    }
                }
            }

            resultBitmap = BitmapPool.getARGB8888(resultW, resultH)
            resultBitmap.setPixels(resultPixels, 0, resultW, 0, 0, resultW, resultH)

            scaled = BitmapPool.getARGB8888(cropWidth, cropHeight)
            val scaledCanvas = android.graphics.Canvas(scaled)
            if (needsResize) {
                scaledCanvas.drawBitmap(
                    resultBitmap,
                    null,
                    android.graphics.RectF(0f, 0f, cropWidth.toFloat(), cropHeight.toFloat()),
                    null,
                )
            } else {
                scaledCanvas.drawBitmap(
                    resultBitmap,
                    android.graphics.Rect(0, 0, cropWidth, cropHeight),
                    android.graphics.RectF(0f, 0f, cropWidth.toFloat(), cropHeight.toFloat()),
                    null,
                )
            }

            val candidate = scaled
            val guardStats = isSuspiciousUniformOutput(candidate, maskBitmap, maskAlreadyCropped, xMin, yMin, cropWidth, cropHeight)
            if (guardStats != null) {
                // TachiyomiAT: rejection covers near-BLACK too (a uniform black
                // block is the documented failure for oversized/long masks where
                // cropMargin→0 and the model collapses to ~0). Route to cleanRegions
                // so the page degrades visibly rather than showing a solid rectangle.
                logcat(LogPriority.WARN) {
                    "[inpaint] suspicious uniform output rejected: " +
                        "mean=${"%.1f".format(guardStats.mean)} " +
                        "variance=${"%.1f".format(guardStats.variance)} " +
                        "channelDelta=${"%.1f".format(guardStats.channelDelta)} " +
                        "masked=${guardStats.maskedCount} crop=${cropWidth}x${cropHeight} " +
                        "— falling back to cleanRegions"
                }
                if (reportFallbackOnly) {
                    throw IllegalStateException("Report AOT output rejected by guard")
                }
                val boxesForFallback = fallbackBoxes.ifEmpty { listOf(intArrayOf(boxX1, boxY1, boxX2, boxY2)) }
                return bubbleCleaner.cleanRegions(image, boxesForFallback)
            }

            blended = featherBlend(image, candidate, maskBitmap, maskAlreadyCropped, xMin, yMin, cropWidth, cropHeight, featherRampPx)

            val canvas = android.graphics.Canvas(image)
            canvas.drawBitmap(blended ?: throw IllegalStateException("Inpainting blend was not created"), xMin.toFloat(), yMin.toFloat(), null)

            logcat(LogPriority.INFO) {
                "[inpaint] model=${(t1 - t0) / 1_000_000.0}ms crop=${cropWidth}x${cropHeight} infer=${inferenceWidth}x${inferenceHeight}"
            }

            return image
        } finally {
            results?.close()
            imgTensor?.close()
            maskTensor?.close()
            imgBuffer?.let { imgInputPool.release(it) }
            maskBuffer?.let { maskInputPool.release(it) }
            if (blended != null) BitmapPool.putARGB8888(blended)
            // TachiyomiAT: scaled is a fresh bitmap (not an alias of resultBitmap)
            // after the normalization step above, so it is released separately.
            if (scaled != null) BitmapPool.putARGB8888(scaled)
            if (resultBitmap != null) BitmapPool.putARGB8888(resultBitmap)
            if (maskInput != null) BitmapPool.putARGB8888(maskInput)
            if (imgInput != null) BitmapPool.putARGB8888(imgInput)
        }
    }

    /**
     * Returns masked-region stats when the AOT candidate is a suspicious uniform
     * fill (near-black / mid-gray / near-white), else null. Renamed from
     * isSuspiciousGrayOutput since the guard now covers three failure modes.
     */
    private fun isSuspiciousUniformOutput(
        inpainted: Bitmap,
        mask: Bitmap,
        maskAlreadyCropped: Boolean,
        xMin: Int,
        yMin: Int,
        width: Int,
        height: Int,
    ): AotOutputGuard.GuardStats? {
        // TachiyomiAT: clamp the read region to the actual bitmap bounds as a
        // safety net (a delegate shape mismatch / OOM partial fill could produce
        // a smaller bitmap; the guard result on the visible region is still valid).
        val inpW = min(width, inpainted.width)
        val inpH = min(height, inpainted.height)
        val mskW = min(width, mask.width)
        val mskH = min(height, mask.height)
        val safeW = min(inpW, mskW)
        val safeH = min(inpH, mskH)

        // TachiyomiAT: size buffers to the ACTUAL read region, not the pooled
        // MAX_TOTAL_PIXELS. width/height are unbounded CROP dims (cropMargin can
        // exceed MAX_INFERENCE_DIM); reading them into the pooled buffer overflows
        // → ArrayIndexOutOfBoundsException that crashed inpaint on large text
        // regions. Fall back to a transient allocation when the crop exceeds the pool cap.
        val readSize = safeW * safeH
        val inpaintedPixels = if (readSize <= MAX_TOTAL_PIXELS) getResultPixels() else IntArray(readSize)
        val maskPixels = if (readSize <= MAX_TOTAL_PIXELS) getMaskPixels() else IntArray(readSize)
        inpainted.getPixels(inpaintedPixels, 0, safeW, 0, 0, safeW, safeH)
        if (maskAlreadyCropped) {
            mask.getPixels(maskPixels, 0, safeW, 0, 0, safeW, safeH)
        } else {
            mask.getPixels(maskPixels, 0, safeW, xMin, yMin, safeW, safeH)
        }
        val stats = AotOutputGuard.inspect(inpaintedPixels, maskPixels, safeW, safeH)
        return if (AotOutputGuard.classify(stats)) stats else null
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
        rampWidth: Int = FEATHER_RAMP_PX,
    ): Bitmap {
        val result = BitmapPool.getARGB8888(width, height)
        // TachiyomiAT: size buffers to the ACTUAL crop dims, not the pooled
        // MAX_TOTAL_PIXELS — cropMargin can push the crop past the pool cap and
        // overflow (the page-24 inpaint crash). Transient alloc above the cap.
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

        // TachiyomiAT: distance-field feather. The old box-average alpha was a
        // thin cliff that exposed the box rectangle; the chamfer transform gives
        // a smooth monotonic alpha ramp over FEATHER_RAMP_PX from the mask edge.
        val maskBytes = ByteArray(blendSize)
        for (i in 0 until blendSize) {
            if (AotPixelOps.maskValue(maskPixels[i]) > 127) maskBytes[i] = 1
        }
        val alphaField = if (rampWidth <= 0) {
            FloatArray(blendSize) { idx -> if (maskBytes[idx] != 0.toByte()) 1.0f else 0.0f }
        } else {
            BubbleMaskBuilder.featherAlphaField(
                mask = maskBytes,
                width = width,
                height = height,
                rampWidth = rampWidth,
            )
        }
        for (idx in 0 until blendSize) {
            val alpha = alphaField[idx]
            if (alpha > 0.0f) {
                origPixels[idx] = AotPixelOps.blendPixel(origPixels[idx], inpPixels[idx], alpha)
            }
        }
        result.setPixels(origPixels, 0, width, 0, 0, width, height)
        return result
    }

    fun close() {
        bubbleCleaner.clearWorkingBuffers()
        session?.close()
        session = null
        clearScratch()
        imgInputPool.clear()
        maskInputPool.clear()
    }

    /**
     * TachiyomiAT: drop cross-call working buffers while staying usable.
     * [SmartBubbleTextCleaner] retains its largest-seen IntArray pair for the
     * session, pinning large heap arrays after a dense early page; on OOM
     * recovery the next inpaint reallocates a buffer sized to the page it sees.
     */
    fun reclaimPooledMemory() {
        bubbleCleaner.clearWorkingBuffers()
        imgInputPool.clear()
        maskInputPool.clear()
    }

    fun forceReleaseNativeBuffers() {
        bubbleCleaner.clearWorkingBuffers()
        clearScratch()
        imgInputPool.clear()
        maskInputPool.clear()
    }
}
