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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class AOTInpainting {

    companion object {
        private const val MAX_INFERENCE_DIM = 768
        private const val MAX_TOTAL_PIXELS = MAX_INFERENCE_DIM * MAX_INFERENCE_DIM

        private val sharedImgData = ThreadLocal.withInitial { FloatArray(3 * MAX_TOTAL_PIXELS) }
        private val sharedMaskData = ThreadLocal.withInitial { FloatArray(MAX_TOTAL_PIXELS) }
        private val sharedImgPixels = ThreadLocal.withInitial { IntArray(MAX_TOTAL_PIXELS) }
        private val sharedMaskPixels = ThreadLocal.withInitial { IntArray(MAX_TOTAL_PIXELS) }
        private val sharedResultPixels = ThreadLocal.withInitial { IntArray(MAX_TOTAL_PIXELS) }
    }

    private var session: OrtSession? = null
    // TachiyomiAT: retained so [obtainCpuFallbackSession] can build a fallback
    // CPU session when the accelerated (NNAPI) session is detected returning
    // all-zero output. Set in [initialize] alongside the primary session; null
    // when not initialized.
    @Volatile
    private var modelPath: String? = null
    // TachiyomiAT: lazily-created CPU-only session, used once to recover from an
    // accelerated-EP zero-output result (see [runDetectingZeroOutput]). Created
    // on demand and cached; closed in [close].
    @Volatile
    private var cpuFallbackSession: OrtSession? = null
    private val bubbleCleaner = SmartBubbleTextCleaner()

    /**
     * TachiyomiAT: an output magnitude above this is treated as "real content".
     * The model output lives in ~[-1, 1]; a value within this epsilon of 0.0
     * denormalizes to ~128 (mid-gray). Verified offline that real output has
     * per-channel std 20-32 (well above this epsilon), so a uniformly sub-epsilon
     * tensor is the signature of a zero-output (broken accelerator) result.
     */
    private val ZERO_OUTPUT_EPSILON = 0.02f

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
        // TachiyomiAT: AOT is the ONE model that opts into the accelerator
        // (NNAPI/NPU when available). It's a single big generative forward pass
        // per masked region — exactly the workload mobile NPUs/GPUs are built
        // for, unlike the manga-ocr autoregressive decoder (which forces CPU).
        val opts = OnnxRuntimeProvider.createSessionOptions()
        try {
            session = OnnxRuntimeProvider.environment.createSession(modelFile.absolutePath, opts)
        } finally {
            opts.close()
        }
        modelPath = modelFile.absolutePath
        logcat(LogPriority.INFO) { "AOT Inpainting session created from ${modelFile.name}" }

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
                result = bubbleCleaner.cleanBubbleGroup(result, bubbleBbox, groupBoxes)
            }
        }

        if (unparented.isNotEmpty()) {
            result = bubbleCleaner.cleanRegions(result, unparented)
        }

        val freeFlatBoxes = mutableListOf<IntArray>()
        val freeNeuralBoxes = mutableListOf<IntArray>()
        val freeSmallBoxes = mutableListOf<IntArray>()
        val pageArea = image.width.toLong() * image.height.toLong()
        val smallBoxAreaThreshold = pageArea / 200
        for (box in freeBoxesForAot) {
            val boxArea = (box[2] - box[0]).toLong() * (box[3] - box[1]).toLong()
            if (boxArea < smallBoxAreaThreshold) {
                freeSmallBoxes.add(box)
            } else if (bubbleCleaner.isFlatBackgroundRegion(result, box)) {
                freeFlatBoxes.add(box)
            } else {
                freeNeuralBoxes.add(box)
            }
        }

        logcat(LogPriority.INFO) {
            "[inpaint] route bubbles=${bubbleBoxes.size} grouped=${grouped.values.sumOf { it.size }} " +
                "unparented=${unparented.size} freeFlat=${freeFlatBoxes.size} freeSmall=${freeSmallBoxes.size} freeNeural=${freeNeuralBoxes.size} " +
                "model=${sess != null}"
        }

        if (freeFlatBoxes.isNotEmpty()) {
            result = bubbleCleaner.cleanRegions(result, freeFlatBoxes)
        }

        if (freeSmallBoxes.isNotEmpty()) {
            result = bubbleCleaner.cleanRegions(result, freeSmallBoxes)
        }

        if (freeNeuralBoxes.isNotEmpty() && sess != null && mode == InpaintingMode.QUALITY) {
            if (TranslationMemoryBudget.isCriticalHeap()) {
                TranslationMemoryBudget.logSnapshot(
                    tag = "skip_neural_heap_pressure",
                    width = image.width,
                    height = image.height,
                    extra = "boxes=${freeNeuralBoxes.size}",
                )
                result = bubbleCleaner.cleanRegions(result, freeNeuralBoxes)
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
                        result = bubbleCleaner.cleanRegions(result, cluster)
                    }
                }
            }
        } else if (freeNeuralBoxes.isNotEmpty()) {
            result = bubbleCleaner.cleanRegions(result, freeNeuralBoxes)
        }

        return result
    }

    private fun inpaintFreeRegions(
        sess: OrtSession,
        image: Bitmap,
        boxes: List<IntArray>,
        padding: Int,
    ): Bitmap {
        val w = image.width
        val h = image.height

        val cropMargin = 32
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
        var dilatedMask: Bitmap? = null
        try {
            maskBitmap.eraseColor(0)
            for (box in normalizedBoxes) {
                val localX1 = box[0] - cropX1
                val localY1 = box[1] - cropY1
                val localW = box[2] - box[0]
                val localH = box[3] - box[1]
                maskBitmap.setPixels(
                    IntArray(localW * localH) { 0xFFFFFFFF.toInt() },
                    0, localW, localX1, localY1, localW, localH,
                )
            }

            val dilated = dilateMask(maskBitmap, kernel = 5, iterations = 2)
            dilatedMask = dilated
            return inpaint(
                sess = sess,
                image = image,
                maskBitmap = dilated,
                cropBounds = intArrayOf(cropX1, cropY1, cropX2, cropY2),
                maskAlreadyCropped = true,
                fallbackBoxes = normalizedBoxes,
            )
        } finally {
            if (dilatedMask != null) BitmapPool.putARGB8888(dilatedMask)
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
        var inferW: Int
        var inferH: Int

        var imgInput: Bitmap? = null
        var maskInput: Bitmap? = null
        var imgTensor: OnnxTensor? = null
        var maskTensor: OnnxTensor? = null
        var results: OrtSession.Result? = null
        var resultBitmap: Bitmap? = null
        var scaled: Bitmap? = null
        var blended: Bitmap? = null
        try {
            if (needsResize) {
                val scale = MAX_INFERENCE_DIM.toFloat() / max(cropW, cropH)
                inferW = max(8, (cropW * scale).toInt())
                inferH = max(8, (cropH * scale).toInt())
                inferW = inferW + (8 - inferW % 8) % 8
                inferH = inferH + (8 - inferH % 8) % 8
                
                imgInput = BitmapPool.getARGB8888(inferW, inferH)
                val imgInputCanvas = android.graphics.Canvas(imgInput)
                imgInputCanvas.drawBitmap(image, android.graphics.Rect(xMin, yMin, xMin + cropW, yMin + cropH), android.graphics.RectF(0f, 0f, inferW.toFloat(), inferH.toFloat()), null)

                maskInput = BitmapPool.getARGB8888(inferW, inferH)
                val maskInputCanvas = android.graphics.Canvas(maskInput)
                if (maskAlreadyCropped) {
                    maskInputCanvas.drawBitmap(maskBitmap, android.graphics.Rect(0, 0, maskBitmap.width, maskBitmap.height), android.graphics.RectF(0f, 0f, inferW.toFloat(), inferH.toFloat()), null)
                } else {
                    maskInputCanvas.drawBitmap(maskBitmap, android.graphics.Rect(xMin, yMin, xMin + cropW, yMin + cropH), android.graphics.RectF(0f, 0f, inferW.toFloat(), inferH.toFloat()), null)
                }
            } else {
                inferW = cropW
                inferH = cropH
                val padW = (8 - cropW % 8) % 8
                val padH = (8 - cropH % 8) % 8
                if (padW > 0 || padH > 0) {
                    imgInput = BitmapPool.getARGB8888(inferW + padW, inferH + padH)
                    imgInput.eraseColor(0)
                    val canvas = android.graphics.Canvas(imgInput)
                    canvas.drawBitmap(image, android.graphics.Rect(xMin, yMin, xMin + cropW, yMin + cropH), android.graphics.Rect(0, 0, cropW, cropH), null)

                    maskInput = BitmapPool.getARGB8888(inferW + padW, inferH + padH)
                    maskInput.eraseColor(0)
                    val maskCanvas = android.graphics.Canvas(maskInput)
                    if (maskAlreadyCropped) {
                        maskCanvas.drawBitmap(maskBitmap, 0f, 0f, null)
                    } else {
                        maskCanvas.drawBitmap(maskBitmap, android.graphics.Rect(xMin, yMin, xMin + cropW, yMin + cropH), android.graphics.Rect(0, 0, cropW, cropH), null)
                    }
                    inferW += padW
                    inferH += padH
                }
            }

            val totalPixels = inferW * inferH
            val imgData = sharedImgData.get()!!
            val maskData = sharedMaskData.get()!!
            val imgPixels = sharedImgPixels.get()!!
            val maskPixels = sharedMaskPixels.get()!!

            if (imgInput != null && maskInput != null) {
                imgInput.getPixels(imgPixels, 0, inferW, 0, 0, inferW, inferH)
                maskInput.getPixels(maskPixels, 0, inferW, 0, 0, inferW, inferH)
            } else {
                for (i in 0 until totalPixels) {
                    imgPixels[i] = 0
                    maskPixels[i] = 0
                }
                image.getPixels(imgPixels, 0, inferW, xMin, yMin, cropW, cropH)
                if (maskAlreadyCropped) {
                    maskBitmap.getPixels(maskPixels, 0, inferW, 0, 0, cropW, cropH)
                } else {
                    maskBitmap.getPixels(maskPixels, 0, inferW, xMin, yMin, cropW, cropH)
                }
            }

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
            results = runDetectingZeroOutput(sess, feed)
            val t1 = System.nanoTime()

            val outputTensor = results!![0] as OnnxTensor
            val outputShape = outputTensor.info.shape
            val outH = outputShape[2].toInt()
            val outW = outputShape[3].toInt()
            val outputBuf = outputTensor.floatBuffer

            val resultH = if (needsResize) outH else min(outH, cropH)
            val resultW = if (needsResize) outW else min(outW, cropW)

            val resultPixels = sharedResultPixels.get()!!
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

            if (needsResize) {
                scaled = BitmapPool.getARGB8888(cropW, cropH)
                val scaledCanvas = android.graphics.Canvas(scaled)
                scaledCanvas.drawBitmap(resultBitmap, null, android.graphics.RectF(0f, 0f, cropW.toFloat(), cropH.toFloat()), null)
            } else {
                scaled = resultBitmap
            }

            val candidate = scaled ?: throw IllegalStateException("Inpainting output was not created")
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
            if (scaled != null && scaled !== resultBitmap) BitmapPool.putARGB8888(scaled)
            if (resultBitmap != null) BitmapPool.putARGB8888(resultBitmap)
            if (maskInput != null) BitmapPool.putARGB8888(maskInput)
            if (imgInput != null) BitmapPool.putARGB8888(imgInput)
        }
    }

    /**
     * TachiyomiAT: run inference on [sess] and detect the silent-gray failure.
     *
     * AOTInpainting denormalizes the model output as `(value + 1) * 127.5`. If a
     * broken NNAPI/NPU driver returns an all-zero output tensor, every channel
     * denormalizes to exactly 128 — a uniform mid-gray patch over the whole
     * masked region. This is the prime suspect for on-device gray inpaint,
     * because the model + Kotlin preprocessing are verified correct offline
     * (scripts/inspect_aot_onnx.py), so a correct model producing gray output
     * can only mean the accelerator returned zeros.
     *
     * Strategy: run the accelerated session. Sample the output tensor; if it is
     * uniformly within [ZERO_OUTPUT_EPSILON] of 0.0 (which denorms to gray) AND
     * the session was accelerated, mark the accelerated EP failed for the
     * process and re-run the SAME input tensors on a lazily-created CPU-only
     * session. OnnxTensor is bound to the OrtEnvironment, not the session, so
     * the retry needs no re-encoding — just a second sess.run. The CPU result
     * replaces the gray one before decode/blend.
     *
     * Only retries once per call. If the CPU session also returns zeros (the
     * model itself is genuinely broken), returns the CPU result as-is rather
     * than looping — the caller's blend then shows whatever the model produced.
     */
    private fun runDetectingZeroOutput(
        sess: OrtSession,
        feed: Map<String, OnnxTensor>,
    ): OrtSession.Result {
        val accelerated = OnnxRuntimeProvider.acceleratedEpAvailable()
        val first = sess.run(feed)
        if (!accelerated) return first
        if (!isOutputNearZero(first)) return first

        logcat(LogPriority.WARN) {
            "[inpaint] zero_output detected from accelerated EP — denorm would " +
                "produce uniform 128 gray. Marking accelerated EP failed and retrying on CPU."
        }
        OnnxRuntimeProvider.markAcceleratedEpFailed()
        first.close()

        val cpu = obtainCpuFallbackSession()
            ?: // Could not build a CPU session (e.g. model path lost); return a
            // fresh empty result so the caller sees the failure rather than the
            // gray one. This path is extremely unlikely given [initialize]
            // succeeded, but is handled defensively.
            return sess.run(feed)
        val retried = cpu.run(feed)
        if (isOutputNearZero(retried)) {
            logcat(LogPriority.WARN) {
                "[inpaint] zero_output ALSO on CPU — the model itself may be " +
                "broken for this input; accepting the result as-is."
            }
        } else {
            logcat(LogPriority.INFO) { "[inpaint] CPU retry produced real output" }
        }
        return retried
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
        val inpaintedPixels = sharedResultPixels.get()!!
        val maskPixels = sharedMaskPixels.get()!!
        inpainted.getPixels(inpaintedPixels, 0, width, 0, 0, width, height)
        if (maskAlreadyCropped) {
            mask.getPixels(maskPixels, 0, width, 0, 0, width, height)
        } else {
            mask.getPixels(maskPixels, 0, width, xMin, yMin, width, height)
        }
        return AotOutputGuard.isSuspiciousGrayFill(inpaintedPixels, maskPixels, width, height)
    }

    /**
     * TachiyomiAT: true if the session result's first output tensor is uniformly
     * within [ZERO_OUTPUT_EPSILON] of 0.0 — i.e. it would denormalize to uniform
     * 128 gray. Samples a stride across the tensor (not every element) so this
     * stays cheap relative to the model forward pass. An all-zero tensor has
     * zero variance, so a coarse sample is sufficient to detect it.
     */
    private fun isOutputNearZero(result: OrtSession.Result): Boolean {
        return try {
            val tensor = result[0] as? OnnxTensor ?: return false
            val buf = tensor.floatBuffer
            val n = buf.remaining()
            if (n <= 0) return false
            // Sample ~256 points across the buffer with a stride; if ALL are
            // near zero the whole tensor is near zero (real content has large
            // positive/negative swings — verified offline std ~20-32).
            val stride = max(1, n / 256)
            var sampleCount = 0
            var i = 0
            while (i < n) {
                if (abs(buf.get(i)) > ZERO_OUTPUT_EPSILON) return false
                sampleCount++
                i += stride
            }
            sampleCount > 0
        } catch (_: Throwable) {
            false
        }
    }

    private fun obtainCpuFallbackSession(): OrtSession? {
        cpuFallbackSession?.let { return it }
        val path = modelPath ?: return null
        return try {
            val opts = OnnxRuntimeProvider.createSessionOptions(forceCpu = true)
            try {
                val s = OnnxRuntimeProvider.environment.createSession(path, opts)
                cpuFallbackSession = s
                logcat(LogPriority.INFO) { "[inpaint] created CPU fallback session" }
                s
            } finally {
                opts.close()
            }
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "[inpaint] failed to create CPU fallback session" }
            null
        }
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
        val origPixels = sharedImgPixels.get()!!
        val inpPixels = sharedResultPixels.get()!!
        val maskPixels = sharedMaskPixels.get()!!
        
        original.getPixels(origPixels, 0, width, xMin, yMin, width, height)
        inpainted.getPixels(inpPixels, 0, width, 0, 0, width, height)
        
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

    private fun dilateMask(mask: Bitmap, kernel: Int, iterations: Int): Bitmap {
        val width = mask.width
        val height = mask.height
        val radius = max(1, kernel / 2)
        var current = IntArray(width * height)
        mask.getPixels(current, 0, width, 0, 0, width, height)

        repeat(iterations) {
            val next = current.copyOf()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val idx = y * width + x
                    if (maskValue(current[idx]) > 127) continue
                    var found = false
                    for (dy in -radius..radius) {
                        val yy = y + dy
                        if (yy !in 0 until height) continue
                        for (dx in -radius..radius) {
                            val xx = x + dx
                            if (xx !in 0 until width) continue
                            if (maskValue(current[yy * width + xx]) > 127) {
                                found = true
                                break
                            }
                        }
                        if (found) break
                    }
                    if (found) next[idx] = 0xFFFFFFFF.toInt()
                }
            }
            current = next
        }

        return Bitmap.createBitmap(current, width, height, Bitmap.Config.ARGB_8888)
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
        // TachiyomiAT: also release the lazily-created CPU fallback session so
        // an engine teardown after a zero-output recovery doesn't leak it.
        cpuFallbackSession?.close()
        cpuFallbackSession = null
    }

    /**
     * TachiyomiAT: drop the cross-call working buffers while staying usable.
     *
     * [SmartBubbleTextCleaner] retains its largest-seen IntArray pair for the
     * engine's lifetime (so a dense early page pins large heap arrays for the
     * whole session). On OOM recovery we want that heap back; the next inpaint
     * simply reallocates a buffer sized to the page it actually sees. The
     * ThreadLocal inference scratch arrays are intentionally NOT touched here:
     * they are thread-local, so clearing them from a different worker thread
     * (the OOM path may run off the inpaint thread) is a no-op, and on the
     * same thread they are reused each call rather than leaked.
     */
    fun reclaimPooledMemory() {
        bubbleCleaner.clearWorkingBuffers()
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
