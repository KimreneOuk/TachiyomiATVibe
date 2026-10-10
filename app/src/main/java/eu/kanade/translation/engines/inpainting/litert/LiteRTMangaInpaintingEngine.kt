package eu.kanade.translation.engines.inpainting.litert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import eu.kanade.translation.engines.inpainting.aot.AotPixelOps
import eu.kanade.translation.engines.inpainting.bubble.BubbleMaskBuilder
import eu.kanade.translation.model.TranslationBlock
import logcat.LogPriority
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * Production Google LiteRT (TensorFlow Lite) inpainting engine executing
 * the optimized Manga LaMa FP16 neural model with full GPU acceleration on
 * Qualcomm Snapdragon (Adreno OpenCL) and MediaTek Dimensity (Mali Vulkan).
 *
 * Exposes full benchmarking metrics and graceful fallback to multi-threaded XNNPACK.
 */
class LiteRTMangaInpaintingEngine(
    private val context: Context,
    private val modelAssetPath: String = "models/inpainting/manga_lama_fused_fp16.tflite",
) : AutoCloseable {

    companion object {
        private const val MODEL_INPUT_SIZE = 512
        private const val FEATHER_RAMP_PX = 12
        private const val CONTEXT_PADDING = 32
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
            val modelFile = getOrExtractModelFile(context, modelAssetPath)
            if (modelFile == null || !modelFile.exists() || modelFile.length() == 0L) {
                logcat(LogPriority.WARN) { "[LiteRTInpaint] Model file not available for $modelAssetPath; LiteRT inpainter unavailable" }
                isInitialized = true
                return
            }

            val compatList = CompatibilityList()
            var createdInterpreter: Interpreter? = null

            // 1. Attempt Hardware GPU delegate if supported by hardware
            if (compatList.isDelegateSupportedOnThisDevice) {
                var delegate: GpuDelegate? = null
                try {
                    val gpuOptions = compatList.bestOptionsForThisDevice
                    delegate = GpuDelegate(gpuOptions)
                    val gpuInterpOptions = Interpreter.Options().apply {
                        addDelegate(delegate)
                    }
                    createdInterpreter = Interpreter(modelFile, gpuInterpOptions)
                    gpuDelegate = delegate
                    isGpuAccelerated = true
                    backendName = "GPU (Adreno/Mali OpenCL/Vulkan)"
                    logcat(LogPriority.INFO) { "[LiteRTInpaint] Hardware GPU delegate initialized successfully" }
                } catch (e: Throwable) {
                    logcat(LogPriority.WARN, e) { "[LiteRTInpaint] GPU delegate init failed; falling back to CPU XNNPACK" }
                    try {
                        delegate?.close()
                    } catch (_: Throwable) {}
                    gpuDelegate = null
                    createdInterpreter = null
                }
            } else {
                logcat(LogPriority.INFO) { "[LiteRTInpaint] Device reports GPU delegate unsupported; using CPU XNNPACK" }
            }

            // 2. Guaranteed fallback to multi-threaded CPU XNNPACK
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
        val targetDir = File(context.filesDir, "models/inpainting")
        if (!targetDir.exists()) targetDir.mkdirs()
        val targetFile = File(targetDir, File(assetPath).name)

        if (targetFile.exists() && targetFile.length() > 0L) {
            return targetFile
        }

        return try {
            val tempFile = File(targetDir, "${targetFile.name}.tmp")
            context.assets.open(assetPath).use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
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
        val canvas = Canvas(result)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        val pageW = image.width
        val pageH = image.height

        // 1. Cluster boxes into 512x512 contextual evaluation patches
        val patches = clusterIntoPatches(boxes, pageW, pageH, MODEL_INPUT_SIZE, CONTEXT_PADDING)

        for (patch in patches) {
            val patchStart = System.currentTimeMillis()
            val cropRect = patch.cropRect

            // Extract context bitmap from original image
            val cropW = cropRect.width()
            val cropH = cropRect.height()
            if (cropW <= 0 || cropH <= 0) continue

            val contextBmp = Bitmap.createBitmap(result, cropRect.left, cropRect.top, cropW, cropH)
            val scaledContext = if (cropW != MODEL_INPUT_SIZE || cropH != MODEL_INPUT_SIZE) {
                Bitmap.createScaledBitmap(contextBmp, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, true)
            } else {
                contextBmp
            }

            // Build binary mask for this patch
            val maskBmp = Bitmap.createBitmap(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, Bitmap.Config.ARGB_8888)
            val maskCanvas = Canvas(maskBmp)
            val maskPaint = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }

            val scaleX = MODEL_INPUT_SIZE.toFloat() / cropW
            val scaleY = MODEL_INPUT_SIZE.toFloat() / cropH

            for (box in patch.containedBoxes) {
                val relL = (box[0] - cropRect.left) * scaleX
                val relT = (box[1] - cropRect.top) * scaleY
                val relR = (box[2] - cropRect.left) * scaleX
                val relB = (box[3] - cropRect.top) * scaleY
                maskCanvas.drawRect(relL, relT, relR, relB, maskPaint)
            }

            // Pack into NHWC tensor buffer [1, 512, 512, 4]
            inputBuffer.rewind()
            inputFloatBuffer.rewind()

            val srcPixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
            val maskPixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
            scaledContext.getPixels(srcPixels, 0, MODEL_INPUT_SIZE, 0, 0, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)
            maskBmp.getPixels(maskPixels, 0, MODEL_INPUT_SIZE, 0, 0, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)

            for (i in 0 until MODEL_INPUT_SIZE * MODEL_INPUT_SIZE) {
                val p = srcPixels[i]
                val m = (maskPixels[i] and 0xFF) / 255.0f
                val maskVal = if (m > 0.5f) 1.0f else 0.0f
                val keep = 1.0f - maskVal

                val r = ((p shr 16) and 0xFF) / 255.0f * keep
                val g = ((p shr 8) and 0xFF) / 255.0f * keep
                val b = (p and 0xFF) / 255.0f * keep

                inputFloatBuffer.put(r)
                inputFloatBuffer.put(g)
                inputFloatBuffer.put(b)
                inputFloatBuffer.put(maskVal)
            }

            // Explicitly rewind direct ByteBuffers for native JNI
            inputBuffer.rewind()
            inputBuffer.position(0)
            outputBuffer.rewind()
            outputBuffer.position(0)

            // 2. Pure neural inference
            val inferStart = System.currentTimeMillis()
            logcat(LogPriority.INFO) {
                "[LiteRTInpaint] Running neural inference for patch ${patchCount + 1}/${patches.size} ($cropW x $cropH) on $backendName..."
            }

            synchronized(this) {
                interp.run(inputBuffer, outputBuffer)
            }
            val inferElapsed = System.currentTimeMillis() - inferStart
            totalInferMs += inferElapsed
            patchCount++
            logcat(LogPriority.INFO) {
                "[LiteRTInpaint] Patch $patchCount completed in ${inferElapsed}ms"
            }

            // 3. Unpack output tensor [1, 512, 512, 3] NHWC into bitmap
            outputFloatBuffer.rewind()
            val outPixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
            for (i in 0 until MODEL_INPUT_SIZE * MODEL_INPUT_SIZE) {
                val r = (outputFloatBuffer.get().coerceIn(0.0f, 1.0f) * 255.0f).toInt()
                val g = (outputFloatBuffer.get().coerceIn(0.0f, 1.0f) * 255.0f).toInt()
                val b = (outputFloatBuffer.get().coerceIn(0.0f, 1.0f) * 255.0f).toInt()
                outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }

            // 4. Distance-field feathered alpha compositing:
            // Preserves original manga artwork 100% outside mask, seamlessly blends inside mask
            val byteMask = ByteArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
            for (i in 0 until MODEL_INPUT_SIZE * MODEL_INPUT_SIZE) {
                if ((maskPixels[i] and 0xFF) > 127) byteMask[i] = 1
            }
            val alphaField = BubbleMaskBuilder.featherAlphaField(
                byteMask,
                MODEL_INPUT_SIZE,
                MODEL_INPUT_SIZE,
                FEATHER_RAMP_PX,
            )
            val blendedPixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
            AotPixelOps.compositeInto(srcPixels, outPixels, alphaField, blendedPixels)

            val inpaintedBmp = Bitmap.createBitmap(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, Bitmap.Config.ARGB_8888)
            inpaintedBmp.setPixels(blendedPixels, 0, MODEL_INPUT_SIZE, 0, 0, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)

            val scaledInpainted = if (cropW != MODEL_INPUT_SIZE || cropH != MODEL_INPUT_SIZE) {
                Bitmap.createScaledBitmap(inpaintedBmp, cropW, cropH, true).also { inpaintedBmp.recycle() }
            } else {
                inpaintedBmp
            }

            canvas.drawBitmap(scaledInpainted, cropRect.left.toFloat(), cropRect.top.toFloat(), paint)

            // Cleanup patch bitmaps
            if (scaledContext !== contextBmp) scaledContext.recycle()
            contextBmp.recycle()
            maskBmp.recycle()
            scaledInpainted.recycle()
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

            // Expand to square patchSize x patchSize centered on content
            var boxW = maxX - minX
            var boxH = maxY - minY
            val cx = (minX + maxX) / 2
            val cy = (minY + maxY) / 2

            var left = cx - patchSize / 2
            var top = cy - patchSize / 2
            var right = left + patchSize
            var bottom = top + patchSize

            // Clamp bounds to page dimensions
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
            right = right.coerceIn(0, pageW)
            bottom = bottom.coerceIn(0, pageH)

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
