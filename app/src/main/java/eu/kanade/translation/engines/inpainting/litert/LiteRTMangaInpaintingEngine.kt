package eu.kanade.translation.engines.inpainting.litert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import eu.kanade.translation.engines.inpainting.aot.AotPadPath
import eu.kanade.translation.engines.inpainting.aot.AotPixelOps
import eu.kanade.translation.engines.inpainting.bubble.BubbleMaskBuilder
import eu.kanade.translation.model.TranslationBlock
import logcat.LogPriority
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Production Google LiteRT (TensorFlow Lite) inpainting engine executing
 * the Snapdragon-optimized Manga LaMa INT8/FP16 neural model with full
 * GPU acceleration on Qualcomm Adreno (OpenCL) and NPU acceleration (Hexagon).
 *
 * Employs 1:1 replicate border padding and tight contour mask compositing
 * to eliminate resampling blur and moiré artifacts on manga screentones.
 */
class LiteRTMangaInpaintingEngine(
    private val context: Context,
    private val modelAssetPath: String = "models/inpainting/manga_lama_fused_fp16.tflite.gz",
) : AutoCloseable {

    companion object {
        private const val MODEL_INPUT_SIZE = 512
        private const val FEATHER_RAMP_PX = 12
        private const val CONTEXT_PADDING = 32
        private const val FALLBACK_FP16_PLAIN = "models/inpainting/manga_lama_fused_fp16.tflite"
        private const val FALLBACK_DYN_INT8 = "models/inpainting/manga_lama_fused_snapdragon_dyn_int8.tflite"
    }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var isGpuAccelerated: Boolean = false
    private var backendName: String = "UNINITIALIZED"

    // Reusable direct native buffers to eliminate per-inference heap allocation
    private val inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(1 * MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * 4 * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
    private val outputBuffer: ByteBuffer = ByteBuffer.allocateDirect(1 * MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * 3 * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())

    private val inputFloatBuffer: FloatBuffer = inputBuffer.asFloatBuffer()
    private val outputFloatBuffer: FloatBuffer = outputBuffer.asFloatBuffer()

    // Pooled scratch arrays for 1:1 replicate padding
    private val paddedSourcePixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
    private val paddedMaskPixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
    private val paddedOutputPixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)

    private val initLock = Any()
    private var isInitialized: Boolean = false

    fun isAvailable(): Boolean {
        synchronized(initLock) {
            ensureInitialized()
            return interpreter != null
        }
    }

    fun getBackendName(): String = backendName

    private fun ensureInitialized() {
        if (isInitialized) return
        val startInit = System.currentTimeMillis()
        try {
            var modelFile = getOrExtractModelFile(context, modelAssetPath)
            if (modelFile == null || !modelFile.exists() || modelFile.length() == 0L) {
                logcat(LogPriority.INFO) { "[LiteRTInpaint] Primary model $modelAssetPath missing; trying plain FP16 $FALLBACK_FP16_PLAIN" }
                modelFile = getOrExtractModelFile(context, FALLBACK_FP16_PLAIN)
            }
            if (modelFile == null || !modelFile.exists() || modelFile.length() == 0L) {
                logcat(LogPriority.INFO) { "[LiteRTInpaint] Plain FP16 model missing; trying dynamic INT8 $FALLBACK_DYN_INT8" }
                modelFile = getOrExtractModelFile(context, FALLBACK_DYN_INT8)
            }

            if (modelFile == null || !modelFile.exists() || modelFile.length() == 0L) {
                logcat(LogPriority.WARN) { "[LiteRTInpaint] No model file available; LiteRT inpainter unavailable" }
                isInitialized = true
                return
            }

            val compatList = CompatibilityList()
            var createdInterpreter: Interpreter? = null
            var delegate: GpuDelegate? = null

            // 1. Attempt Hardware GPU delegate (Adreno OpenCL / Mali Vulkan)
            try {
                val gpuOptions = try {
                    compatList.bestOptionsForThisDevice
                } catch (_: Throwable) {
                    GpuDelegate.Options()
                }
                gpuOptions.setPrecisionLossAllowed(true)
                val gpu = GpuDelegate(gpuOptions)
                delegate = gpu
                val gpuInterpOptions = Interpreter.Options().apply {
                    addDelegate(gpu)
                }
                createdInterpreter = Interpreter(modelFile, gpuInterpOptions)
                gpuDelegate = gpu
                isGpuAccelerated = true
                backendName = "GPU (Adreno OpenCL)"
                logcat(LogPriority.INFO) { "[LiteRTInpaint] Hardware GPU delegate initialized successfully on ${modelFile.name}" }
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "[LiteRTInpaint] GPU delegate init failed; attempting NNAPI (Hexagon NPU)" }
                try {
                    delegate?.close()
                } catch (_: Throwable) {}
                gpuDelegate = null
                createdInterpreter = null
            }

            // 2. Attempt Hardware NNAPI delegate (Hexagon NPU on Qualcomm)
            if (createdInterpreter == null) {
                try {
                    val nnapiOptions = Interpreter.Options().apply {
                        setUseNNAPI(true)
                    }
                    createdInterpreter = Interpreter(modelFile, nnapiOptions)
                    isGpuAccelerated = true
                    backendName = "NPU (NNAPI / Hexagon)"
                    logcat(LogPriority.INFO) { "[LiteRTInpaint] Hardware NNAPI/NPU delegate initialized successfully" }
                } catch (e: Throwable) {
                    logcat(LogPriority.WARN, e) { "[LiteRTInpaint] NNAPI init failed; falling back to CPU XNNPACK" }
                    createdInterpreter = null
                }
            }

            // 3. Guaranteed fallback to multi-threaded CPU XNNPACK
            if (createdInterpreter == null) {
                val cpuOptions = Interpreter.Options().apply {
                    setNumThreads(4)
                    setUseXNNPACK(true)
                }
                createdInterpreter = Interpreter(modelFile, cpuOptions)
                isGpuAccelerated = false
                backendName = "CPU (XNNPACK 4T)"
                logcat(LogPriority.INFO) { "[LiteRTInpaint] CPU XNNPACK interpreter initialized successfully" }
            }

            interpreter = createdInterpreter
            val elapsed = System.currentTimeMillis() - startInit
            logcat(LogPriority.INFO) {
                "[InpaintBenchmark] [LiteRTInpaint] Ready in ${elapsed}ms | Backend: $backendName | Model: ${modelFile.name}"
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "[LiteRTInpaint] Initialization failed completely" }
        } finally {
            isInitialized = true
        }
    }

    private fun getOrExtractModelFile(context: Context, assetPath: String): File? {
        val isGzip = assetPath.endsWith(".gz")
        val effectiveName = if (isGzip) File(assetPath.removeSuffix(".gz")).name else File(assetPath).name
        val targetDir = File(context.filesDir, "models/inpainting")
        if (!targetDir.exists()) targetDir.mkdirs()
        val targetFile = File(targetDir, effectiveName)
        val prefs = context.getSharedPreferences("litert_model_cache", Context.MODE_PRIVATE)

        val appUpdateTime = try {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        } catch (_: Throwable) {
            0L
        }
        val versionKey = "app_update_time_$effectiveName"
        val sizeKey = "extracted_size_$effectiveName"
        val cachedTime = prefs.getLong(versionKey, -1L)
        val cachedSize = prefs.getLong(sizeKey, -1L)

        if (targetFile.exists() && targetFile.length() > 50_000_000L && cachedTime == appUpdateTime &&
            (cachedSize <= 0L || targetFile.length() == cachedSize)
        ) {
            return targetFile
        }

        return try {
            val tempFile = File(targetDir, "${targetFile.name}.tmp")
            context.assets.open(assetPath).use { rawInput ->
                val input = if (isGzip) java.util.zip.GZIPInputStream(rawInput) else rawInput
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            prefs.edit()
                .putLong(versionKey, appUpdateTime)
                .putLong(sizeKey, targetFile.length())
                .apply()
            logcat(LogPriority.INFO) {
                "[LiteRTInpaint] Extracted asset $assetPath to ${targetFile.absolutePath} (${targetFile.length()} bytes)"
            }
            targetFile
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "[LiteRTInpaint] Failed to extract asset $assetPath to storage" }
            null
        }
    }

    /**
     * Executes neural inpainting on the provided page bitmap across detected boxes and masks.
     * Uses 1:1 replicate padding and tight pill contour masks to preserve original artwork sharpness.
     */
    fun inpaintRegions(
        image: Bitmap,
        boxes: List<IntArray>,
        labels: List<Int>? = null,
        blocks: List<TranslationBlock>? = null,
    ): Bitmap {
        if (boxes.isEmpty() && (blocks == null || blocks.none { it.segmentationMask != null })) {
            return image
        }

        ensureInitialized()
        val interp = interpreter ?: run {
            logcat(LogPriority.WARN) { "[LiteRTInpaint] Interpreter not available; returning unmodified bitmap" }
            return image
        }

        val pageStart = System.currentTimeMillis()
        var totalInferMs = 0L
        var patchCount = 0

        val result = image.copy(Bitmap.Config.ARGB_8888, true)
        val pageW = image.width
        val pageH = image.height

        // 1. Cluster boxes into contextual evaluation patches (at most MODEL_INPUT_SIZE)
        val patches = clusterIntoPatches(boxes, pageW, pageH, MODEL_INPUT_SIZE, CONTEXT_PADDING)

        for (patch in patches) {
            val cropRect = patch.cropRect
            val cropW = cropRect.width()
            val cropH = cropRect.height()
            if (cropW <= 0 || cropH <= 0) continue

            val side = min(MODEL_INPUT_SIZE, max(cropW, cropH))
            if (side <= 0) continue

            // Read sub-rectangle from result bitmap
            val subPixels = IntArray(cropW * cropH)
            result.getPixels(subPixels, 0, cropW, cropRect.left, cropRect.top, cropW, cropH)

            // Center cropW x cropH into a square side x side with replicate border clamp
            val sourceSquare = IntArray(side * side)
            val offX = (side - cropW) / 2
            val offY = (side - cropH) / 2
            for (y in 0 until side) {
                val clampedY = (y - offY).coerceIn(0, cropH - 1)
                val srcRow = clampedY * cropW
                val dstRow = y * side
                for (x in 0 until side) {
                    val clampedX = (x - offX).coerceIn(0, cropW - 1)
                    sourceSquare[dstRow + x] = subPixels[srcRow + clampedX]
                }
            }

            // Build tight local mask (pill mask around text boxes with pad 4, dilate 2)
            val localBoxes = patch.containedBoxes.map { box ->
                intArrayOf(
                    (box[0] - cropRect.left + offX).coerceIn(0, side),
                    (box[1] - cropRect.top + offY).coerceIn(0, side),
                    (box[2] - cropRect.left + offX).coerceIn(0, side),
                    (box[3] - cropRect.top + offY).coerceIn(0, side),
                )
            }
            val localMaskBytes = BubbleMaskBuilder.buildDynamicPillMask(
                boxes = localBoxes,
                width = side,
                height = side,
                pad = 4,
            )

            val localMaskPixels = IntArray(side * side) { i ->
                if (localMaskBytes[i] != 0.toByte()) 0xFFFFFFFF.toInt() else 0
            }

            // Pad 1:1 into 512x512 tensor canvas via replicate border padding
            AotPadPath.padSquareReplicateInto(sourceSquare, side, paddedSourcePixels)
            AotPadPath.padSquareInto(localMaskPixels, side, 0, paddedMaskPixels)

            // Pack into FloatBuffer input [1, 512, 512, 4] NHWC
            inputBuffer.rewind()
            inputFloatBuffer.rewind()

            for (i in 0 until MODEL_INPUT_SIZE * MODEL_INPUT_SIZE) {
                val p = paddedSourcePixels[i]
                val isMask = if ((paddedMaskPixels[i] and 0xFF) > 127) 1.0f else 0.0f
                val keep = 1.0f - isMask

                val r = ((p shr 16) and 0xFF) / 255.0f * keep
                val g = ((p shr 8) and 0xFF) / 255.0f * keep
                val b = (p and 0xFF) / 255.0f * keep

                inputFloatBuffer.put(r)
                inputFloatBuffer.put(g)
                inputFloatBuffer.put(b)
                inputFloatBuffer.put(isMask)
            }

            inputBuffer.rewind()
            inputBuffer.position(0)
            outputBuffer.rewind()
            outputBuffer.position(0)

            // 2. Pure neural inference
            val inferStart = System.currentTimeMillis()
            synchronized(this) {
                interp.run(inputBuffer, outputBuffer)
            }
            val inferElapsed = System.currentTimeMillis() - inferStart
            totalInferMs += inferElapsed
            patchCount++

            // 3. Unpack output tensor [1, 512, 512, 3] NHWC
            outputFloatBuffer.rewind()
            for (i in 0 until MODEL_INPUT_SIZE * MODEL_INPUT_SIZE) {
                val r = (outputFloatBuffer.get().coerceIn(0.0f, 1.0f) * 255.0f).toInt()
                val g = (outputFloatBuffer.get().coerceIn(0.0f, 1.0f) * 255.0f).toInt()
                val b = (outputFloatBuffer.get().coerceIn(0.0f, 1.0f) * 255.0f).toInt()
                paddedOutputPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }

            // Extract the centered side x side area back (1:1 pixel scale, zero scaling blur!)
            val decodedSquare = IntArray(side * side)
            AotPadPath.cropSquareInto(paddedOutputPixels, side, decodedSquare)

            // 4. Feathered alpha compositing preserving original artwork outside mask
            val alphaField = BubbleMaskBuilder.featherAlphaField(
                localMaskBytes,
                side,
                side,
                FEATHER_RAMP_PX,
            )
            val blendedSquare = IntArray(side * side)
            AotPixelOps.compositeInto(sourceSquare, decodedSquare, alphaField, blendedSquare)

            // 5. Write unpadded cropW x cropH pixels back to result bitmap
            val finalCropPixels = IntArray(cropW * cropH)
            for (y in 0 until cropH) {
                System.arraycopy(
                    blendedSquare,
                    (y + offY) * side + offX,
                    finalCropPixels,
                    y * cropW,
                    cropW,
                )
            }
            result.setPixels(finalCropPixels, 0, cropW, cropRect.left, cropRect.top, cropW, cropH)
        }

        val totalPageElapsed = System.currentTimeMillis() - pageStart
        logcat(LogPriority.INFO) {
            "[InpaintBenchmark] Mode=LAMA_LITERT_GPU | Backend=$backendName | " +
                "Patches=$patchCount | PureInferMs=${totalInferMs}ms | TotalPageMs=${totalPageElapsed}ms | " +
                "PageSize=${pageW}x$pageH"
        }

        return result
    }

    private data class InpaintPatch(
        val cropRect: Rect,
        val containedBoxes: List<IntArray>,
    )

    private fun clusterIntoPatches(
        boxes: List<IntArray>,
        pageW: Int,
        pageH: Int,
        patchSize: Int,
        pad: Int,
    ): List<InpaintPatch> {
        if (boxes.isEmpty()) return emptyList()

        val patches = mutableListOf<InpaintPatch>()
        val unassigned = boxes.toMutableList()

        while (unassigned.isNotEmpty()) {
            val seed = unassigned.removeAt(0)
            val patchBoxes = mutableListOf(seed)

            var minX = seed[0] - pad
            var minY = seed[1] - pad
            var maxX = seed[2] + pad
            var maxY = seed[3] + pad

            // Absorb nearby boxes that fit within patchSize
            val iter = unassigned.iterator()
            while (iter.hasNext()) {
                val candidate = iter.next()
                val nMinX = min(minX, candidate[0] - pad)
                val nMinY = min(minY, candidate[1] - pad)
                val nMaxX = max(maxX, candidate[2] + pad)
                val nMaxY = max(maxY, candidate[3] + pad)

                if ((nMaxX - nMinX) <= patchSize && (nMaxY - nMinY) <= patchSize) {
                    minX = nMinX
                    minY = nMinY
                    maxX = nMaxX
                    maxY = nMaxY
                    patchBoxes.add(candidate)
                    iter.remove()
                }
            }

            var boxW = maxX - minX
            var boxH = maxY - minY
            val side = min(patchSize, max(boxW, boxH))
            val cx = (minX + maxX) / 2
            val cy = (minY + maxY) / 2

            var left = cx - side / 2
            var top = cy - side / 2
            var right = left + side
            var bottom = top + side

            // Shift bounds if hitting page boundaries
            if (left < 0) {
                right += -left
                left = 0
            }
            if (top < 0) {
                bottom += -top
                top = 0
            }
            if (right > pageW) {
                left -= (right - pageW)
                right = pageW
            }
            if (bottom > pageH) {
                top -= (bottom - pageH)
                bottom = pageH
            }

            left = left.coerceIn(0, pageW)
            top = top.coerceIn(0, pageH)
            right = right.coerceIn(left, pageW)
            bottom = bottom.coerceIn(top, pageH)

            patches.add(InpaintPatch(Rect(left, top, right, bottom), patchBoxes))
        }

        return patches
    }

    override fun close() {
        synchronized(initLock) {
            interpreter?.close()
            interpreter = null
            gpuDelegate?.close()
            gpuDelegate = null
            isInitialized = false
        }
    }
}
