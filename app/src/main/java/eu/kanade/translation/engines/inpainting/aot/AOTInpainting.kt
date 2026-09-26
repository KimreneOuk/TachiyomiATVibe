package eu.kanade.translation.engines.inpainting.aot
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtProvider
import ai.onnxruntime.OrtSession
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Build
import eu.kanade.translation.engines.inpainting.InpaintingMode
import eu.kanade.translation.engines.inpainting.bubble.BubbleMaskBuilder
import eu.kanade.translation.engines.inpainting.bubble.SmartBubbleTextCleaner
import eu.kanade.translation.engines.inpainting.opencv.OpenCvInpaintEngine
import eu.kanade.translation.engines.runtime.onnx.DeviceCapability
import eu.kanade.translation.engines.runtime.onnx.HardwareDiscoveryEngine
import eu.kanade.translation.engines.runtime.onnx.ModelRoutingEngine
import eu.kanade.translation.engines.runtime.onnx.OnnxRuntimeProvider
import eu.kanade.translation.engines.runtime.onnx.QnnContextCacheManager
import eu.kanade.translation.util.TranslationMemoryBudget
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.pools.BitmapPool
import tachiyomi.domain.translation.pools.DirectBufferPool
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class AOTInpainting(
    val performanceMode: String? = "burst",
) {

    companion object {
        private const val MAX_INFERENCE_DIM = 768
        private const val MAX_TOTAL_PIXELS = MAX_INFERENCE_DIM * MAX_INFERENCE_DIM

        // PaddleOCR-v6 solid-box erase-mask tunables. Defaults match
        // the validated prototype; kept
        // internal (no settings surface).
        private const val PADDLE_CROP_PAD = 12
        private const val PADDLE_THRESH = 0.18f
        private const val PADDLE_BOX_THRESH = 0.34f
        private const val MASK_PAD = 8
        private const val REPORT_FREE_TEXT_PAD = 1
        private const val REPORT_FREE_TEXT_DILATE = 2
        private const val REPORT_AOT_CONTEXT = 512
        private const val REPORT_FREE_TEXT_FEATHER = 3
        private const val REPORT_BUBBLE_SMOOTH_PASSES = 12
        private const val REPORT_PUSH_PULL_CONTEXT = 64
        internal const val BUBBLE_SEG_MASK_EROSION = 5

        // distance-field feather ramp (px) blending the neural
        // output with the page. Replaces the earlier 2–6px box-blur cliff that
        // exposed the erase-box rectangle. See [BubbleMaskBuilder.featherAlphaField].
        private const val FEATHER_RAMP_PX = 12
    }

    private val scratchLock = Any()

    // pooled DIRECT buffers for the variable-shape inpaint tensors
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

    private var fixedSession: OrtSession? = null
    private var fixedNnapiSession: OrtSession? = null
    private var fixedQnnSession: OrtSession? = null
    private var dynamicSession: OrtSession? = null
    private val nnapiHealth = NnapiHealthMonitor()
    private val bubbleCleaner = SmartBubbleTextCleaner()
    var paddleDetector: eu.kanade.translation.engines.vision.ocr.PaddleOcrV6DetEngine? = null

    /**
     * Route tag of the most recently accepted neural candidate
     * (fixed_qnn_htp / fixed_nnapi / fixed / dynamic), for honest page-level
     * perf logging. Null before the first acceptance.
     */
    @Volatile
    var lastAcceptedRoute: String? = null
        private set

    fun initialize(fixedModelFile: File?, dynamicModelFile: File?) {
        fixedSession = initializeSession(fixedModelFile, AotModelContract.Kind.FIXED_512, "fixed")
        dynamicSession = initializeSession(dynamicModelFile, AotModelContract.Kind.DYNAMIC, "dynamic")
        if (HardwareDiscoveryEngine.resolveRoute() == HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP &&
            fixedModelFile != null &&
            fixedModelFile.exists()
        ) {
            fixedQnnSession = initializeQnnSession(fixedModelFile)
        }
        fixedNnapiSession = initializeStrictNnapiSession(fixedModelFile)
        logcat(LogPriority.INFO) {
            "[inpaint] init fixedXnnpack=${fixedSession != null} fixedQnn=${fixedQnnSession != null} fixedNnapi=${fixedNnapiSession != null} " +
                "dynamic=${dynamicSession != null} strictCpuFallbackDisabled=true ${DeviceCapability.describe()}"
        }
    }

    private fun initializeQnnSession(modelFile: File): OrtSession? {
        val modelId = ModelRoutingEngine.resolveModelId(modelFile.absolutePath)
        if (!ModelRoutingEngine.isSupported(modelId, HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP)) {
            logcat(LogPriority.INFO) { "[inpaint] route=qnn_htp model=$modelId is marked UNSUPPORTED; skipping" }
            return null
        }

        val app = try {
            Injekt.get<Application>()
        } catch (_: Throwable) {
            null
        }
        val htpOptions = OnnxRuntimeProvider.buildGenericHtpOptions(performanceMode = performanceMode)

        // 1. Warm load from precompiled context cache if valid (~300ms)
        if (app != null) {
            val validCachedModel = QnnContextCacheManager.getValidCachedModel(app, modelFile, htpOptions)
            if (validCachedModel != null) {
                var loadOpts: OrtSession.SessionOptions? = null
                try {
                    loadOpts = OnnxRuntimeProvider.createQnnHtpSessionOptions(
                        contextCacheFile = null,
                        strictCpuFallbackDisabled = true,
                        qnnOptions = htpOptions,
                    )
                    val session = OnnxRuntimeProvider.environment.createSession(validCachedModel.absolutePath, loadOpts)
                    AotModelContract.validate(AotModelContract.Kind.FIXED_512, readContract(session))
                    ModelRoutingEngine.markSupported(modelId, HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP)
                    logcat(LogPriority.INFO) {
                        "[inpaint] route=qnn_htp init=ok cached=true model=$modelId ${DeviceCapability.describe()}"
                    }
                    return session
                } catch (t: Throwable) {
                    logcat(LogPriority.WARN, t) {
                        "[inpaint] route=qnn_htp cached context load failed; invalidating cache and compiling fresh"
                    }
                    QnnContextCacheManager.invalidate(app, modelFile, htpOptions)
                } finally {
                    try {
                        loadOpts?.close()
                    } catch (_: Throwable) {}
                }
            }
        }

        // 2. Cold compilation and atomic staging
        val stagingCtxFile = if (app != null) {
            QnnContextCacheManager.prepareStaging(app, modelFile, htpOptions)
        } else {
            null
        }

        var opts: OrtSession.SessionOptions? = null
        var created: OrtSession? = null
        return try {
            opts = OnnxRuntimeProvider.createQnnHtpSessionOptions(
                contextCacheFile = stagingCtxFile,
                strictCpuFallbackDisabled = true,
                qnnOptions = htpOptions,
            )
            created = OnnxRuntimeProvider.environment.createSession(modelFile.absolutePath, opts)
            AotModelContract.validate(AotModelContract.Kind.FIXED_512, readContract(created))
            if (app != null) {
                QnnContextCacheManager.commitStaging(app, modelFile, htpOptions)
            }
            ModelRoutingEngine.markSupported(modelId, HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP)
            logcat(LogPriority.INFO) {
                "[inpaint] route=qnn_htp init=ok cached=false generated=true model=$modelId ${DeviceCapability.describe()}"
            }
            created
        } catch (error: Throwable) {
            try {
                created?.close()
            } catch (closeError: Throwable) {
                error.addSuppressed(closeError)
            }
            val canRetry = ModelRoutingEngine.recordFailure(modelId, HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP, error)
            logcat(LogPriority.ERROR, error) {
                "[inpaint] route=qnn_htp init=failed canRetry=$canRetry model=$modelId ${DeviceCapability.describe()}"
            }
            null
        } finally {
            try {
                opts?.close()
            } catch (error: Throwable) {
                logcat(LogPriority.WARN, error) { "[inpaint] route=qnn_htp optionsClose=failed" }
            }
        }
    }

    private fun initializeStrictNnapiSession(modelFile: File?): OrtSession? {
        val memory = TranslationMemoryBudget.nnapiMemorySnapshot()
        val providers = try {
            OrtEnvironment.getAvailableProviders()
        } catch (error: Throwable) {
            logcat(LogPriority.WARN, error) { "[inpaint] route=nnapi init=skipped reason=provider_query_failed ${DeviceCapability.describe()}" }
            return null
        }
        val decision = NnapiCapabilityGate.decide(
            NnapiCapabilityGate.Snapshot(
                sdk = Build.VERSION.SDK_INT,
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
                emulator = DeviceCapability.isProbablyEmulator,
                nnapiProviderCompiled = OrtProvider.NNAPI in providers,
                availableHeapBytes = memory.availableHeapBytes,
                systemHeadroomBytes = memory.systemHeadroomBytes,
                lowMemory = memory.lowMemory,
                healthy = nnapiHealth.isHealthy(),
            ),
        )
        if (!decision.eligible || modelFile == null || !modelFile.exists()) {
            logcat(LogPriority.INFO) {
                "[inpaint] route=xnnpack nnapiCandidate=skipped reason=${if (modelFile?.exists() != true) "model_missing" else decision.reason} " +
                    "strictCpuFallbackDisabled=true heapAvail=${memory.availableHeapBytes / (1024L * 1024L)}MiB " +
                    "sysHeadroom=${memory.systemHeadroomBytes?.div(1024L * 1024L)}MiB lowMemory=${memory.lowMemory} ${DeviceCapability.describe()}"
            }
            return null
        }

        var opts: OrtSession.SessionOptions? = null
        var created: OrtSession? = null
        return try {
            // No runCatching around strict config, addNnapi, or createSession: a
            // failed strict candidate must be visible and must never become CPU.
            opts = OnnxRuntimeProvider.createStrictNnapiSessionOptions()
            created = OnnxRuntimeProvider.environment.createSession(modelFile.absolutePath, opts)
            AotModelContract.validate(AotModelContract.Kind.FIXED_512, readContract(created))
            logcat(LogPriority.INFO) {
                "[inpaint] route=nnapi init=ok strictCpuFallbackDisabled=true model=${modelFile.name} ${DeviceCapability.describe()}"
            }
            created
        } catch (error: Throwable) {
            try {
                created?.close()
            } catch (closeError: Throwable) {
                error.addSuppressed(closeError)
            }
            nnapiHealth.disableForNativeException()
            logcat(LogPriority.ERROR, error) {
                "[inpaint] route=xnnpack nnapiCandidate=init_failed strictCpuFallbackDisabled=true health=disabled ${DeviceCapability.describe()}"
            }
            null
        } finally {
            try {
                opts?.close()
            } catch (error: Throwable) {
                logcat(LogPriority.WARN, error) { "[inpaint] route=nnapi optionsClose=failed" }
            }
        }
    }

    private fun initializeSession(
        modelFile: File?,
        kind: AotModelContract.Kind,
        route: String,
    ): OrtSession? {
        if (modelFile == null || !modelFile.exists()) {
            logcat(LogPriority.WARN) {
                "[inpaint] route=$route init=skipped reason=model_missing path=${modelFile?.absolutePath}"
            }
            return null
        }
        var xnnpackRegistered = false
        val opts = try {
            OnnxRuntimeProvider.createRequiredXnnpackSessionOptions { registered ->
                xnnpackRegistered = registered
            }
        } catch (error: Throwable) {
            logcat(LogPriority.ERROR, error) {
                "[inpaint] route=$route init=failed provider=XNNPACK required=true model=${modelFile.name}"
            }
            return null
        }
        var created: OrtSession? = null
        return try {
            created = OnnxRuntimeProvider.environment.createSession(modelFile.absolutePath, opts)
            val contract = readContract(created)
            AotModelContract.validate(kind, contract)
            logcat(LogPriority.INFO) {
                "[inpaint] route=$route init=ok provider=${if (xnnpackRegistered) "XNNPACK" else "CPU"} model=${modelFile.name} " +
                    "image=${contract.imageShape.contentToString()} mask=${contract.maskShape.contentToString()} " +
                    "output=${contract.outputShape.contentToString()}"
            }
            created
        } catch (error: Throwable) {
            try {
                created?.close()
            } catch (_: Throwable) {}
            logcat(LogPriority.ERROR, error) {
                "[inpaint] route=$route init=failed provider=${if (xnnpackRegistered) "XNNPACK" else "CPU"} model=${modelFile.name}"
            }
            null
        } finally {
            opts.close()
        }
    }

    private fun readContract(sess: OrtSession): AotModelContract.Contract {
        val inputs = sess.inputInfo
        val outputs = sess.outputInfo
        val image = inputs["image"]?.info as? ai.onnxruntime.TensorInfo
        val mask = inputs["mask"]?.info as? ai.onnxruntime.TensorInfo
        val output = outputs.values.singleOrNull()?.info as? ai.onnxruntime.TensorInfo
        return AotModelContract.Contract(
            inputNames = inputs.keys,
            outputCount = outputs.size,
            imageShape = image?.shape ?: longArrayOf(),
            maskShape = mask?.shape ?: longArrayOf(),
            outputShape = output?.shape ?: longArrayOf(),
        )
    }

    fun isInitialized(): Boolean = fixedSession != null || dynamicSession != null || fixedQnnSession != null

    private fun neuralSessionCount(): Int =
        (if (fixedSession != null) 1 else 0) +
            (if (fixedQnnSession != null) 1 else 0) +
            (if (fixedNnapiSession != null && nnapiHealth.isHealthy()) 1 else 0) +
            (if (dynamicSession != null) 1 else 0)

    fun inpaintRegions(
        image: Bitmap,
        boxes: List<IntArray>,
        labels: List<Int>? = null,
        mode: InpaintingMode = InpaintingMode.QUALITY,
        blocks: List<eu.kanade.translation.model.TranslationBlock>? = null,
    ): Bitmap {
        if (boxes.isEmpty()) return image.copy(Bitmap.Config.ARGB_8888, true)
        val fixedSess = fixedSession
        val dynamicSess = dynamicSession
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
        val rawFreeTextGroups: List<List<IntArray>> = if (paddleDetector != null && freeTextDetectorBoxes.isNotEmpty()) {
            val refined = refineFreeTextGroups(image, freeTextDetectorBoxes)
            paddleLinesTotal = refined.paddleLineCount
            paddleFallback = refined.fallbackCount
            refined.groups
        } else {
            freeTextDetectorBoxes.map { listOf(it.copyOf()) }
        }

        val freeTextGroups = AotBoxGeometry.clusterFreeTextGroups(rawFreeTextGroups, REPORT_AOT_CONTEXT)

        logcat(LogPriority.INFO) {
            "[inpaint] pipeline=investigation_report bubbleText=${bubbleTextBoxes.size} " +
                "freeDets=${freeTextDetectorBoxes.size} rawGroups=${rawFreeTextGroups.size} clusteredGroups=${freeTextGroups.size} " +
                "mode=$mode fixed=${fixedSess != null} dynamic=${dynamicSess != null}"
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
            if (mode == InpaintingMode.QUALITY && (fixedSess != null || dynamicSess != null)) {
                result = inpaintReportFreeTextNeural(fixedSess, dynamicSess, result, group)
            } else {
                logcat(LogPriority.INFO) {
                    "[inpaint] route=push_pull reason=${if (mode != InpaintingMode.QUALITY) "fast_mode" else "no_neural_session"} boxes=${group.size}"
                }
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

        // 1. Rasterize segmentation masks from blocks that have one and apply edge erosion
        val blocksWithSegMask = blocks?.filter { it.segmentationMask != null } ?: emptyList()
        if (blocksWithSegMask.isNotEmpty()) {
            val rawSegMask = ByteArray(w * h)
            val boundsList = ArrayList<List<Int>>(blocksWithSegMask.size)
            for (block in blocksWithSegMask) {
                val seg = block.segmentationMask ?: continue
                seg.rasterizeOnto(rawSegMask)
                boundsList.add(seg.bounds)
            }
            val erodedSegMask = erodeBinaryMask(rawSegMask, w, h, boundsList, BUBBLE_SEG_MASK_EROSION)
            for (i in mask.indices) {
                if (erodedSegMask[i] != 0.toByte()) {
                    mask[i] = 1
                }
            }
        }

        // 2. For all boxes that are NOT covered by an existing segmentation mask, build a dynamic pill mask
        val remainingBoxes = if (blocksWithSegMask.isEmpty()) {
            boxes
        } else {
            boxes.filter { box ->
                blocksWithSegMask.none { block ->
                    val seg = block.segmentationMask
                    seg != null && seg.overlapPixels(box[0], box[1], box[2], box[3]) > 0
                }
            }
        }

        if (remainingBoxes.isNotEmpty()) {
            val pillMask = BubbleMaskBuilder.buildDynamicPillMask(remainingBoxes, w, h, MASK_PAD)
            for (i in mask.indices) {
                if (pillMask[i] != 0.toByte()) {
                    mask[i] = 1
                }
            }
        }

        if (mask.none { it != 0.toByte() }) return image
        val pixels = IntArray(w * h)
        image.getPixels(pixels, 0, w, 0, 0, w, h)
        AotReportBubbleFill.fillAndBlend(pixels, mask, w, h, REPORT_BUBBLE_SMOOTH_PASSES, FEATHER_RAMP_PX)
        image.setPixels(pixels, 0, w, 0, 0, w, h)
        return image
    }

    /**
     * Morphological binary erosion for segmentation masks.
     * Erodes by [radius] px (default [BUBBLE_SEG_MASK_EROSION]) within component bounds
     * to protect hand-drawn stroke borders of speech bubbles from being erased.
     * If a small bubble completely vanishes from erosion, falls back to a milder
     * radius (radius / 2) or retains original pixels.
     */
    internal fun erodeBinaryMask(
        mask: ByteArray,
        width: Int,
        height: Int,
        boundsList: List<List<Int>>,
        radius: Int = BUBBLE_SEG_MASK_EROSION,
    ): ByteArray {
        if (radius <= 0) return mask
        val eroded = ByteArray(width * height)
        val r2 = radius * radius
        for (bounds in boundsList) {
            val minX = bounds[0].coerceIn(0, width)
            val minY = bounds[1].coerceIn(0, height)
            val maxX = bounds[2].coerceIn(minX, width)
            val maxY = bounds[3].coerceIn(minY, height)
            var erodedCount = 0
            for (y in minY until maxY) {
                val rowOffset = y * width
                for (x in minX until maxX) {
                    if (mask[rowOffset + x] == 0.toByte()) continue
                    var keep = true
                    for (dy in -radius..radius) {
                        val ny = y + dy
                        if (ny < 0 || ny >= height) {
                            keep = false
                            break
                        }
                        val nRowOffset = ny * width
                        for (dx in -radius..radius) {
                            if (dx * dx + dy * dy <= r2) {
                                val nx = x + dx
                                if (nx < 0 || nx >= width || mask[nRowOffset + nx] == 0.toByte()) {
                                    keep = false
                                    break
                                }
                            }
                        }
                        if (!keep) break
                    }
                    if (keep) {
                        eroded[rowOffset + x] = 1
                        erodedCount++
                    }
                }
            }
            if (erodedCount == 0) {
                val milderRadius = max(1, radius / 2)
                val m2 = milderRadius * milderRadius
                for (y in minY until maxY) {
                    val rowOffset = y * width
                    for (x in minX until maxX) {
                        if (mask[rowOffset + x] == 0.toByte()) continue
                        var keep = true
                        for (dy in -milderRadius..milderRadius) {
                            val ny = y + dy
                            if (ny < 0 || ny >= height) {
                                keep = false
                                break
                            }
                            val nRowOffset = ny * width
                            for (dx in -milderRadius..milderRadius) {
                                if (dx * dx + dy * dy <= m2) {
                                    val nx = x + dx
                                    if (nx < 0 || nx >= width || mask[nRowOffset + nx] == 0.toByte()) {
                                        keep = false
                                        break
                                    }
                                }
                            }
                            if (!keep) break
                        }
                        if (keep) {
                            eroded[rowOffset + x] = 1
                            erodedCount++
                        }
                    }
                }
                if (erodedCount == 0) {
                    for (y in minY until maxY) {
                        val rowOffset = y * width
                        for (x in minX until maxX) {
                            if (mask[rowOffset + x] != 0.toByte()) {
                                eroded[rowOffset + x] = 1
                            }
                        }
                    }
                }
            }
        }
        return eroded
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
        OpenCvInpaintEngine.inpaintPixels(work, mask, cropW, cropH)
        val alpha = BubbleMaskBuilder.featherAlphaField(mask, cropW, cropH, REPORT_FREE_TEXT_FEATHER)
        for (i in work.indices) {
            val a = alpha[i]
            if (a > 0.0f) work[i] = AotPixelOps.blendPixel(original[i], work[i], a)
        }
        image.setPixels(work, 0, cropW, bounds[0], bounds[1], cropW, cropH)
        return image
    }

    private fun inpaintReportFreeTextNeural(
        fixedSess: OrtSession?,
        dynamicSess: OrtSession?,
        image: Bitmap,
        boxes: List<IntArray>,
    ): Bitmap {
        val crop = AotBoxGeometry.centeredReportCrop(boxes, image.width, image.height, REPORT_AOT_CONTEXT)
            ?: return image
        val side = crop[2] - crop[0]
        if (side <= 0 || crop[3] - crop[1] != side) return image
        val memoryDecision = TranslationMemoryBudget.neuralInpaintDecision(
            pageWidth = image.width,
            pageHeight = image.height,
            cropWidth = side,
            cropHeight = side,
            sessionCount = neuralSessionCount(),
        )
        if (!memoryDecision.canRun) {
            TranslationMemoryBudget.logSnapshot(
                tag = "skip_report_aot",
                width = image.width,
                height = image.height,
                extra = "route=push_pull neuralMode=${memoryDecision.mode} " +
                    "sessions=${memoryDecision.sessionCount} " +
                    "nativeSystemReserve=${memoryDecision.nativeSystemReserveBytes / (1024L * 1024L)}MiB " +
                    "sysHeadroom=${memoryDecision.systemHeadroomBytes?.div(1024L * 1024L)}MiB " +
                    "reason=${memoryDecision.reason} crop=${side}x$side boxes=${boxes.size}",
            )
            return inpaintReportFreeTextFast(image, boxes)
        }
        TranslationMemoryBudget.logSnapshot(
            tag = "run_report_aot",
            width = image.width,
            height = image.height,
            extra = "neuralMode=${memoryDecision.mode} sessions=${memoryDecision.sessionCount} " +
                "nativeSystemReserve=${memoryDecision.nativeSystemReserveBytes / (1024L * 1024L)}MiB " +
                "sysHeadroom=${memoryDecision.systemHeadroomBytes?.div(1024L * 1024L)}MiB crop=${side}x$side",
        )
        val localBoxes = boxes.mapNotNull { AotBoxGeometry.localizeBox(it, crop[0], crop[1], side, side) }
        val maskBytes = BubbleMaskBuilder.buildFixedPillMask(localBoxes, side, side, REPORT_FREE_TEXT_PAD, REPORT_FREE_TEXT_DILATE)
        if (maskBytes.none { it != 0.toByte() }) return image
        val maskBitmap = BitmapPool.getALPHA8(side, side)
        try {
            setAlphaMaskPixels(maskBitmap, maskBytes, side, side)
            var fixedOom = false
            val result = AotFallbackCoordinator.run(
                primary = {
                    if (fixedSess == null) {
                        loggedFailure("fixed_xnnpack", "session_unavailable", crop, side, null)
                        AotFallbackCoordinator.CandidateResult.Failed(IllegalStateException("fixed XNNPACK session unavailable"))
                    } else {
                        tryStrictFixedCandidate(fixedSess, image, maskBitmap, crop, side)
                            .also { if (it is AotFallbackCoordinator.CandidateResult.Failed && it.error is OutOfMemoryError) fixedOom = true }
                    }
                },
                fallback = {
                    if (fixedOom) {
                        loggedFailure("dynamic", "skipped_after_fixed_oom", crop, side, null)
                        AotFallbackCoordinator.CandidateResult.Failed(IllegalStateException("dynamic skipped after fixed OOM"))
                    } else if (dynamicSess == null) {
                        loggedFailure("dynamic", "session_unavailable", crop, side, null)
                        AotFallbackCoordinator.CandidateResult.Failed(IllegalStateException("dynamic session unavailable"))
                    } else {
                        tryNeuralCandidate("dynamic", dynamicSess, image, maskBitmap, crop, side, fixedShape = false)
                    }
                },
            )
            return when (result) {
                is AotFallbackCoordinator.Result.Accepted -> {
                    val originalPixels = IntArray(side * side)
                    image.getPixels(originalPixels, 0, side, crop[0], crop[1], side, side)
                    val alphaField = BubbleMaskBuilder.featherAlphaField(
                        mask = maskBytes,
                        width = side,
                        height = side,
                        rampWidth = REPORT_FREE_TEXT_FEATHER,
                    )
                    for (i in originalPixels.indices) {
                        val a = alphaField[i]
                        if (a > 0.0f) {
                            originalPixels[i] = AotPixelOps.blendPixel(originalPixels[i], result.pixels[i], a)
                        }
                    }
                    image.setPixels(originalPixels, 0, side, crop[0], crop[1], side, side)
                    image
                }
                is AotFallbackCoordinator.Result.Exhausted -> {
                    if (fixedOom) {
                        loggedFailure("dynamic", "skipped_after_fixed_oom", crop, side, null)
                    }
                    logcat(LogPriority.WARN) {
                        "[inpaint] route=push_pull reason=neural_exhausted crop=${side}x$side " +
                            "offset=${crop[0]},${crop[1]} tensor=${side}x$side boxes=${boxes.size}"
                    }
                    if (fixedOom) {
                        BitmapPool.releaseAll()
                        System.gc()
                    }
                    inpaintReportFreeTextFast(image, boxes)
                }
            }
        } finally {
            BitmapPool.putALPHA8(maskBitmap)
        }
    }

    private fun tryStrictFixedCandidate(
        xnnpackSession: OrtSession,
        page: Bitmap,
        mask: Bitmap,
        crop: IntArray,
        side: Int,
    ): AotFallbackCoordinator.CandidateResult {
        val nnapiSession = fixedNnapiSession
        val memory = TranslationMemoryBudget.nnapiMemorySnapshot()
        val useNnapi = nnapiSession != null &&
            NnapiCapabilityGate.decide(
                NnapiCapabilityGate.Snapshot(
                    sdk = Build.VERSION.SDK_INT,
                    supportedAbis = Build.SUPPORTED_ABIS.toList(),
                    emulator = DeviceCapability.isProbablyEmulator,
                    nnapiProviderCompiled = true,
                    availableHeapBytes = memory.availableHeapBytes,
                    systemHeadroomBytes = memory.systemHeadroomBytes,
                    lowMemory = memory.lowMemory,
                    healthy = nnapiHealth.isHealthy(),
                ),
            ).eligible
        val prepared = try {
            prepareFixedInput(page, mask, crop, side)
        } catch (error: Throwable) {
            loggedFailure("fixed_prepare", "exception", crop, side, error)
            return AotFallbackCoordinator.CandidateResult.Failed(error)
        }
        val qnnSession = fixedQnnSession
        if (qnnSession != null) {
            try {
                val qnnResult = runPreparedFixedCandidate("fixed_qnn_htp", qnnSession, prepared, crop, side)
                when (qnnResult) {
                    is StrictNnapiFallback.Candidate.Accepted -> {
                        return AotFallbackCoordinator.CandidateResult.Accepted(qnnResult.value)
                    }
                    is StrictNnapiFallback.Candidate.Rejected -> {
                        logcat(LogPriority.WARN) {
                            "[inpaint] route=fixed_qnn_htp guard_rejected, falling back to CPU XNNPACK"
                        }
                        val cpuResult = runPreparedFixedCandidate("fixed_xnnpack", xnnpackSession, prepared, crop, side)
                        return when (cpuResult) {
                            is StrictNnapiFallback.Candidate.Accepted -> AotFallbackCoordinator.CandidateResult.Accepted(cpuResult.value)
                            is StrictNnapiFallback.Candidate.Rejected -> AotFallbackCoordinator.CandidateResult.Rejected(cpuResult.stats)
                            is StrictNnapiFallback.Candidate.Failed -> AotFallbackCoordinator.CandidateResult.Failed(cpuResult.error)
                        }
                    }
                    is StrictNnapiFallback.Candidate.Failed -> {
                        HardwareDiscoveryEngine.tripCircuitBreaker("qnn_execution_failed", qnnResult.error)
                        closeAndDetachQnn("execution_failed", qnnResult.error)
                        val cpuResult = runPreparedFixedCandidate("fixed_xnnpack", xnnpackSession, prepared, crop, side)
                        return when (cpuResult) {
                            is StrictNnapiFallback.Candidate.Accepted -> AotFallbackCoordinator.CandidateResult.Accepted(cpuResult.value)
                            is StrictNnapiFallback.Candidate.Rejected -> AotFallbackCoordinator.CandidateResult.Rejected(cpuResult.stats)
                            is StrictNnapiFallback.Candidate.Failed -> AotFallbackCoordinator.CandidateResult.Failed(cpuResult.error)
                        }
                    }
                }
            } catch (error: Throwable) {
                HardwareDiscoveryEngine.tripCircuitBreaker("qnn_execution_failed", error)
                closeAndDetachQnn("execution_exception", error)
                val cpuResult = runPreparedFixedCandidate("fixed_xnnpack", xnnpackSession, prepared, crop, side)
                return when (cpuResult) {
                    is StrictNnapiFallback.Candidate.Accepted -> AotFallbackCoordinator.CandidateResult.Accepted(cpuResult.value)
                    is StrictNnapiFallback.Candidate.Rejected -> AotFallbackCoordinator.CandidateResult.Rejected(cpuResult.stats)
                    is StrictNnapiFallback.Candidate.Failed -> AotFallbackCoordinator.CandidateResult.Failed(cpuResult.error)
                }
            } finally {
                prepared.close()
            }
        }
        val result = try {
            StrictNnapiFallback.run(
                useNnapi = useNnapi,
                nnapi = {
                    requireNotNull(nnapiSession)
                    runPreparedFixedCandidate("fixed_nnapi", nnapiSession, prepared, crop, side)
                },
                xnnpack = {
                    runPreparedFixedCandidate("fixed_xnnpack", xnnpackSession, prepared, crop, side)
                },
                onNnapiNativeFailure = {
                    nnapiHealth.disableForNativeException()
                    closeAndDetachNnapi("runtime_exception", it)
                },
                onNnapiOnlyMismatch = {
                    nnapiHealth.recordNnapiOnlyGuardMismatch()
                    if (!nnapiHealth.isHealthy()) closeAndDetachNnapi("guard_mismatch_rate")
                },
                onSharedRejection = { nnapiHealth.recordSharedRejection() },
                onNnapiAccepted = { nnapiHealth.recordNnapiAccepted() },
            )
        } finally {
            prepared.close()
        }
        val health = nnapiHealth.snapshot()
        logcat(LogPriority.INFO) {
            "[inpaint] strictRoute=${result.route} fallback=${result.route != StrictNnapiFallback.Route.NNAPI} " +
                "healthEnabled=${health.enabled} healthSamples=${health.samples} healthMismatches=${health.mismatches} " +
                "healthReason=${health.disableReason} ${DeviceCapability.describe()}"
        }
        return when (result.route) {
            StrictNnapiFallback.Route.NNAPI, StrictNnapiFallback.Route.XNNPACK ->
                AotFallbackCoordinator.CandidateResult.Accepted(requireNotNull(result.value))
            StrictNnapiFallback.Route.PUSH_PULL -> when (val baseline = result.xnnpack) {
                is StrictNnapiFallback.Candidate.Rejected -> AotFallbackCoordinator.CandidateResult.Rejected(baseline.stats)
                is StrictNnapiFallback.Candidate.Failed -> AotFallbackCoordinator.CandidateResult.Failed(baseline.error)
                else -> AotFallbackCoordinator.CandidateResult.Failed(IllegalStateException("fixed providers exhausted"))
            }
        }
    }

    private inner class PreparedFixedInput(
        val imageTensor: OnnxTensor,
        val maskTensor: OnnxTensor,
        val imageBuffer: FloatBuffer,
        val maskBuffer: FloatBuffer,
        val croppedMaskPixels: IntArray,
        val offset: Int,
        val grayscale: Boolean,
    ) : AutoCloseable {
        override fun close() {
            try {
                imageTensor.close()
            } finally {
                try {
                    maskTensor.close()
                } finally {
                    imgInputPool.release(imageBuffer)
                    maskInputPool.release(maskBuffer)
                }
            }
        }
    }

    /** Creates the immutable tensors once; both EP attempts consume these exact values. */
    private fun prepareFixedInput(
        page: Bitmap,
        mask: Bitmap,
        crop: IntArray,
        side: Int,
    ): PreparedFixedInput {
        require(side in 1..AotPadPath.SIZE)
        val sourcePixels = IntArray(side * side)
        page.getPixels(sourcePixels, 0, side, crop[0], crop[1], side, side)
        val maskPixels = IntArray(side * side)
        mask.getPixels(maskPixels, 0, side, 0, 0, side, side)
        val binaryMask = ByteArray(side * side)
        for (index in binaryMask.indices) {
            if (AotPixelOps.maskValue(maskPixels[index]) > 127) binaryMask[index] = 1
        }
        val background = PushPullGradient.localRingMedian(
            sourcePixels,
            side,
            side,
            binaryMask,
            PushPullGradient.DEFAULT_RING,
        )
        val offset = AotPadPath.centeredOffset(side)
        val paddedPixels = getImgPixels()
        java.util.Arrays.fill(paddedPixels, background)
        val paddedMask = getMaskPixels()
        java.util.Arrays.fill(paddedMask, 0)
        AotPadPath.padSquareInto(sourcePixels, side, background, paddedPixels)
        AotPadPath.padSquareInto(maskPixels, side, 0, paddedMask)

        var totalChroma = 0
        val sampleStride = max(1, sourcePixels.size / 400)
        var samples = 0
        for (index in sourcePixels.indices step sampleStride) {
            val pixel = sourcePixels[index]
            val red = pixel shr 16 and 0xFF
            val green = pixel shr 8 and 0xFF
            val blue = pixel and 0xFF
            totalChroma += max(red, max(green, blue)) - min(red, min(green, blue))
            samples++
        }

        val imageBuffer = imgInputPool.acquire()
        val maskBuffer = maskInputPool.acquire()
        var imageTensor: OnnxTensor? = null
        var maskTensor: OnnxTensor? = null
        try {
            imageBuffer.clear()
            maskBuffer.clear()
            val pixels = AotPadPath.SIZE * AotPadPath.SIZE
            imageBuffer.limit(3 * pixels)
            maskBuffer.limit(pixels)
            for (index in 0 until pixels) {
                val pixel = paddedPixels[index]
                maskBuffer.put(index, if (AotPixelOps.maskValue(paddedMask[index]) > 127) 1.0f else 0.0f)
                imageBuffer.put(index, AotPixelOps.encodeFixedImageChannel(pixel shr 16 and 0xFF))
                imageBuffer.put(pixels + index, AotPixelOps.encodeFixedImageChannel(pixel shr 8 and 0xFF))
                imageBuffer.put(2 * pixels + index, AotPixelOps.encodeFixedImageChannel(pixel and 0xFF))
            }
            val environment = OnnxRuntimeProvider.environment
            imageTensor = OnnxTensor.createTensor(environment, imageBuffer, longArrayOf(1, 3, 512, 512))
            maskTensor = OnnxTensor.createTensor(environment, maskBuffer, longArrayOf(1, 1, 512, 512))
            return PreparedFixedInput(
                imageTensor = imageTensor,
                maskTensor = maskTensor,
                imageBuffer = imageBuffer,
                maskBuffer = maskBuffer,
                croppedMaskPixels = maskPixels,
                offset = offset,
                grayscale = totalChroma / samples < 15,
            )
        } catch (error: Throwable) {
            try {
                imageTensor?.close()
            } catch (closeError: Throwable) {
                error.addSuppressed(closeError)
            }
            try {
                maskTensor?.close()
            } catch (closeError: Throwable) {
                error.addSuppressed(closeError)
            }
            imgInputPool.release(imageBuffer)
            maskInputPool.release(maskBuffer)
            throw error
        }
    }

    private fun runPreparedFixedCandidate(
        route: String,
        session: OrtSession,
        prepared: PreparedFixedInput,
        crop: IntArray,
        side: Int,
    ): StrictNnapiFallback.Candidate<IntArray> {
        val started = System.nanoTime()
        return try {
            session.run(mapOf("image" to prepared.imageTensor, "mask" to prepared.maskTensor)).use { result ->
                val output = result[0] as OnnxTensor
                val shape = output.info.shape
                require(shape.contentEquals(longArrayOf(1, 3, 512, 512))) {
                    "fixed AOT output shape changed: ${shape.contentToString()}"
                }
                val buffer = output.floatBuffer
                val candidate = IntArray(side * side)
                val channelSize = AotPadPath.SIZE * AotPadPath.SIZE
                for (y in 0 until side) {
                    for (x in 0 until side) {
                        val outputIndex = (prepared.offset + y) * AotPadPath.SIZE + prepared.offset + x
                        val red = AotPixelOps.decodeFixedChannel(buffer.get(outputIndex))
                        val green = AotPixelOps.decodeFixedChannel(buffer.get(channelSize + outputIndex))
                        val blue = AotPixelOps.decodeFixedChannel(buffer.get(2 * channelSize + outputIndex))
                        candidate[y * side + x] = if (prepared.grayscale) {
                            val luma = (0.299f * red + 0.587f * green + 0.114f * blue).roundToInt()
                            (0xFF shl 24) or (luma shl 16) or (luma shl 8) or luma
                        } else {
                            (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                        }
                    }
                }
                val stats = AotOutputGuard.inspect(candidate, prepared.croppedMaskPixels, side, side)
                if (AotOutputGuard.classify(stats)) {
                    loggedFailure(route, "guard_rejected", crop, side, null, started, stats)
                    StrictNnapiFallback.Candidate.Rejected(stats)
                } else {
                    lastAcceptedRoute = route
                    logcat(LogPriority.INFO) {
                        "[inpaint] route=$route accepted totalMs=${elapsedMs(started)} crop=${side}x$side " +
                            "tensor=512x512 offset=${prepared.offset},${prepared.offset} guard=pass"
                    }
                    StrictNnapiFallback.Candidate.Accepted(candidate)
                }
            }
        } catch (oom: OutOfMemoryError) {
            loggedFailure(route, "oom", crop, side, oom, started)
            StrictNnapiFallback.Candidate.Failed(oom)
        } catch (error: Throwable) {
            loggedFailure(route, "exception", crop, side, error, started)
            StrictNnapiFallback.Candidate.Failed(error)
        }
    }

    private fun closeAndDetachNnapi(reason: String, error: Throwable? = null) {
        val session = fixedNnapiSession ?: return
        fixedNnapiSession = null
        try {
            session.close()
        } catch (closeError: Throwable) {
            error?.addSuppressed(closeError)
            logcat(LogPriority.ERROR, closeError) { "[inpaint] route=nnapi close=failed reason=$reason" }
        }
        logcat(LogPriority.WARN, error) { "[inpaint] route=xnnpack nnapi=disabled reason=$reason" }
    }

    private fun closeAndDetachQnn(reason: String, error: Throwable? = null) {
        val session = fixedQnnSession ?: return
        fixedQnnSession = null
        try {
            session.close()
        } catch (closeError: Throwable) {
            error?.addSuppressed(closeError)
            logcat(LogPriority.ERROR, closeError) { "[inpaint] route=qnn_htp close=failed reason=$reason" }
        }
        logcat(LogPriority.WARN, error) { "[inpaint] route=xnnpack qnn_htp=disabled reason=$reason" }
    }

    private fun tryNeuralCandidate(
        route: String,
        sess: OrtSession,
        page: Bitmap,
        mask: Bitmap,
        crop: IntArray,
        side: Int,
        fixedShape: Boolean,
    ): AotFallbackCoordinator.CandidateResult {
        var working: Bitmap? = null
        val started = System.nanoTime()
        return try {
            val candidate = BitmapPool.getARGB8888(side, side)
            working = candidate
            Canvas(candidate).drawBitmap(
                page,
                android.graphics.Rect(crop[0], crop[1], crop[2], crop[3]),
                android.graphics.Rect(0, 0, side, side),
                null,
            )
            inpaint(
                sess = sess,
                image = candidate,
                maskBitmap = mask,
                cropBounds = intArrayOf(0, 0, side, side),
                maskAlreadyCropped = true,
                featherRampPx = 0,
                reportFallbackOnly = true,
                fixedShape = fixedShape,
                route = route,
            )
            val pixels = IntArray(side * side)
            candidate.getPixels(pixels, 0, side, 0, 0, side, side)
            lastAcceptedRoute = route
            logcat(LogPriority.INFO) {
                "[inpaint] route=$route accepted totalMs=${elapsedMs(started)} crop=${side}x$side " +
                    "tensor=${if (fixedShape) "512x512" else "${side}x$side"} offset=${crop[0]},${crop[1]} guard=pass"
            }
            AotFallbackCoordinator.CandidateResult.Accepted(pixels)
        } catch (oom: OutOfMemoryError) {
            loggedFailure(route, "oom", crop, side, oom, started)
            AotFallbackCoordinator.CandidateResult.Failed(oom)
        } catch (rejected: AotRejectedException) {
            loggedFailure(route, "guard_rejected", crop, side, rejected, started, rejected.stats)
            AotFallbackCoordinator.CandidateResult.Rejected(rejected.stats)
        } catch (error: Throwable) {
            loggedFailure(route, "exception", crop, side, error, started)
            AotFallbackCoordinator.CandidateResult.Failed(error)
        } finally {
            working?.let { BitmapPool.putARGB8888(it) }
        }
    }

    private class AotRejectedException(val stats: AotOutputGuard.GuardStats) : RuntimeException("AOT guard rejected output")

    private fun elapsedMs(started: Long): Double = (System.nanoTime() - started) / 1_000_000.0

    private fun loggedFailure(
        route: String,
        reason: String,
        crop: IntArray,
        side: Int,
        error: Throwable?,
        started: Long = System.nanoTime(),
        stats: AotOutputGuard.GuardStats? = null,
    ) {
        logcat(LogPriority.WARN, error) {
            "[inpaint] route=$route result=fallback reason=$reason totalMs=${elapsedMs(started)} crop=${side}x$side " +
                "tensor=${if (route.startsWith("fixed")) "512x512" else "${side}x$side"} offset=${crop[0]},${crop[1]} " +
                "guard=${stats?.let { "mean=${it.mean},variance=${it.variance},channelDelta=${it.channelDelta},masked=${it.maskedCount}" } ?: "n/a"}"
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
        fixedShape: Boolean = false,
        route: String = "dynamic",
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

        require(!fixedShape || (cropWidth == cropHeight && cropWidth in 1..AotPadPath.SIZE)) {
            "fixed AOT requires a square crop in 1..${AotPadPath.SIZE}, actual=${cropWidth}x$cropHeight"
        }
        val needsResize = !fixedShape && max(cropWidth, cropHeight) > MAX_INFERENCE_DIM
        val inferenceWidth: Int
        val inferenceHeight: Int
        if (fixedShape) {
            inferenceWidth = AotPadPath.SIZE
            inferenceHeight = AotPadPath.SIZE
        } else if (needsResize) {
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
        val fixedOffset = if (fixedShape) AotPadPath.centeredOffset(cropWidth) else 0

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
            val fixedBackground = if (fixedShape) {
                val cropMask = ByteArray(cropWidth * cropHeight)
                val localMaskPixels = IntArray(cropWidth * cropHeight)
                maskBitmap.getPixels(localMaskPixels, 0, cropWidth, 0, 0, cropWidth, cropHeight)
                for (i in cropMask.indices) {
                    if (AotPixelOps.maskValue(localMaskPixels[i]) > 127) cropMask[i] = 1
                }
                PushPullGradient.localRingMedian(cropPixelsOriginal, cropWidth, cropHeight, cropMask, PushPullGradient.DEFAULT_RING)
            } else {
                0
            }
            imgInput.eraseColor(fixedBackground)
            val imgInputCanvas = android.graphics.Canvas(imgInput)
            if (fixedShape) {
                imgInputCanvas.drawBitmap(
                    image,
                    android.graphics.Rect(xMin, yMin, xMin + cropWidth, yMin + cropHeight),
                    android.graphics.Rect(fixedOffset, fixedOffset, fixedOffset + cropWidth, fixedOffset + cropHeight),
                    null,
                )
            } else if (needsResize) {
                imgInputCanvas.drawBitmap(image, android.graphics.Rect(xMin, yMin, xMin + cropWidth, yMin + cropHeight), android.graphics.RectF(0f, 0f, inferenceWidth.toFloat(), inferenceHeight.toFloat()), null)
            } else {
                imgInputCanvas.drawBitmap(image, android.graphics.Rect(xMin, yMin, xMin + cropWidth, yMin + cropHeight), android.graphics.Rect(0, 0, cropWidth, cropHeight), null)
            }

            maskInput = BitmapPool.getARGB8888(inferenceWidth, inferenceHeight)
            maskInput.eraseColor(0)
            val maskInputCanvas = android.graphics.Canvas(maskInput)
            if (maskAlreadyCropped) {
                if (fixedShape) {
                    maskInputCanvas.drawBitmap(
                        maskBitmap,
                        android.graphics.Rect(0, 0, maskBitmap.width, maskBitmap.height),
                        android.graphics.Rect(fixedOffset, fixedOffset, fixedOffset + cropWidth, fixedOffset + cropHeight),
                        null,
                    )
                } else if (needsResize) {
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
            if (fixedShape) {
                scaledCanvas.drawBitmap(
                    resultBitmap,
                    android.graphics.Rect(fixedOffset, fixedOffset, fixedOffset + cropWidth, fixedOffset + cropHeight),
                    android.graphics.Rect(0, 0, cropWidth, cropHeight),
                    null,
                )
            } else if (needsResize) {
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
                // rejection covers near-BLACK too (a uniform black
                // block is the documented failure for oversized/long masks where
                // cropMargin→0 and the model collapses to ~0). Route to cleanRegions
                // so the page degrades visibly rather than showing a solid rectangle.
                logcat(LogPriority.WARN) {
                    "[inpaint] suspicious uniform output rejected: " +
                        "mean=${"%.1f".format(guardStats.mean)} " +
                        "variance=${"%.1f".format(guardStats.variance)} " +
                        "channelDelta=${"%.1f".format(guardStats.channelDelta)} " +
                        "masked=${guardStats.maskedCount} crop=${cropWidth}x$cropHeight " +
                        "— falling back to cleanRegions"
                }
                if (reportFallbackOnly) {
                    throw AotRejectedException(guardStats)
                }
                val boxesForFallback = fallbackBoxes.ifEmpty { listOf(intArrayOf(boxX1, boxY1, boxX2, boxY2)) }
                return bubbleCleaner.cleanRegions(image, boxesForFallback)
            }

            blended = featherBlend(image, candidate, maskBitmap, maskAlreadyCropped, xMin, yMin, cropWidth, cropHeight, featherRampPx)

            val canvas = android.graphics.Canvas(image)
            canvas.drawBitmap(blended ?: throw IllegalStateException("Inpainting blend was not created"), xMin.toFloat(), yMin.toFloat(), null)

            logcat(LogPriority.INFO) {
                "[inpaint] route=$route modelMs=${(t1 - t0) / 1_000_000.0} crop=${cropWidth}x$cropHeight " +
                    "tensor=${inferenceWidth}x$inferenceHeight offset=$fixedOffset,$fixedOffset guard=pass"
            }

            return image
        } finally {
            results?.close()
            imgTensor?.close()
            maskTensor?.close()
            imgBuffer?.let { imgInputPool.release(it) }
            maskBuffer?.let { maskInputPool.release(it) }
            if (blended != null) BitmapPool.putARGB8888(blended)
            // scaled is a fresh bitmap (not an alias of resultBitmap)
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
        // clamp the read region to the actual bitmap bounds as a
        // safety net (a delegate shape mismatch / OOM partial fill could produce
        // a smaller bitmap; the guard result on the visible region is still valid).
        val inpW = min(width, inpainted.width)
        val inpH = min(height, inpainted.height)
        val mskW = min(width, mask.width)
        val mskH = min(height, mask.height)
        val safeW = min(inpW, mskW)
        val safeH = min(inpH, mskH)

        // size buffers to the ACTUAL read region, not the pooled
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
        // size buffers to the ACTUAL crop dims, not the pooled
        // MAX_TOTAL_PIXELS — cropMargin can push the crop past the pool cap and
        // overflow (the page-24 inpaint crash). Transient alloc above the cap.
        val blendSize = width * height
        val origPixels = if (blendSize <= MAX_TOTAL_PIXELS) getImgPixels() else IntArray(blendSize)
        val inpPixels = if (blendSize <= MAX_TOTAL_PIXELS) getResultPixels() else IntArray(blendSize)
        val maskPixels = if (blendSize <= MAX_TOTAL_PIXELS) getMaskPixels() else IntArray(blendSize)

        original.getPixels(origPixels, 0, width, xMin, yMin, width, height)

        // clamp the inpainted bitmap read to its actual size.
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

        // distance-field feather. The old box-average alpha was a
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
        val fixed = fixedSession
        val nnapi = fixedNnapiSession
        val qnn = fixedQnnSession
        val dynamic = dynamicSession
        // Detach first so repeated/concurrent lifecycle teardown cannot close a
        // native handle twice. Each distinct session is still attempted when its
        // sibling close fails.
        fixedSession = null
        fixedNnapiSession = null
        fixedQnnSession = null
        dynamicSession = null
        AotSessionLifecycle.closeIndependently(fixed, dynamic, nnapi, qnn) { failure ->
            logcat(LogPriority.ERROR, failure.error) {
                "[inpaint] route=${failure.route} close=failed"
            }
        }
        clearScratch()
        imgInputPool.clear()
        maskInputPool.clear()
    }

    fun freeNativeSessions() {
        close()
    }

    /**
     * drop cross-call working buffers while staying usable.
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
