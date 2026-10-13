package eu.kanade.translation.engines.inpainting.litert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import eu.kanade.translation.engines.inpainting.InpaintBoxGeometry
import eu.kanade.translation.engines.inpainting.InpaintPadOps
import eu.kanade.translation.engines.inpainting.InpaintPixelOps
import eu.kanade.translation.engines.inpainting.PushPullGradient
import eu.kanade.translation.engines.inpainting.bubble.BubbleMaskBuilder
import eu.kanade.translation.model.TranslationBlock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Production Google AI Edge LiteRT inpainting engine executing the Manga LaMa
 * model through the accelerator-first CompiledModel Kotlin API.
 *
 * Employs tight stroke masking (fixed pill mask) and ring-median background
 * context padding to eliminate screentone phase distortion and moiré artifacts.
 */
class LiteRTMangaInpaintingEngine(
    private val context: Context,
    private val modelAssetPath: String = "models/inpainting/manga_lama_fused_fp16.tflite",
    private val enableExperimentalNpu: Boolean = false,
) : AutoCloseable {

    companion object {
        private const val MODEL_INPUT_SIZE = 512
        private const val FEATHER_RAMP_PX = 8
        private const val MASK_PAD = 2
        private const val MASK_DILATE = 2
        private const val CONTEXT_PADDING = 32
        private const val MAX_CLUSTER_BOX_SIZE = 448
        private const val MODEL_FP16_PATH = "models/inpainting/manga_lama_fused_fp16.tflite"
        private const val MODEL_INT8_PATH = "models/inpainting/manga_lama_fused_int8.tflite"
        private const val FALLBACK_FP16_GZ = "models/inpainting/manga_lama_fused_fp16.tflite.gz"
        private const val FALLBACK_INT8_GZ = "models/inpainting/manga_lama_fused_int8.tflite.gz"
        private const val FALLBACK_DYN_INT8 = "models/inpainting/manga_lama_fused_snapdragon_dyn_int8.tflite"
    }

    private var model: CompiledModel? = null
    private var environment: Environment? = null
    private var inputBuffers: List<TensorBuffer> = emptyList()
    private var outputBuffers: List<TensorBuffer> = emptyList()
    private var backendName: String = "UNINITIALIZED"

    // Reused input arrays feed the LiteRT TensorBuffers without allocating a new
    // pixel tensor for every patch. The dual-input model uses NHWC RGB + mask.
    private val imageInputValues = FloatArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * 3)
    private val maskInputValues = FloatArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
    // Compatibility path for older single-input RGBA model assets.
    private val singleInputValues = FloatArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * 4)
    private var isDualInput: Boolean = true

    // Pooled scratch arrays for 1:1 padding
    private val paddedSourcePixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
    private val paddedMaskPixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
    private val paddedOutputPixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)

    private val initLock = Any()
    private var isInitialized: Boolean = false

    fun isAvailable(): Boolean {
        synchronized(initLock) {
            ensureInitialized()
            return model != null
        }
    }

    fun getBackendName(): String = backendName

    private fun ensureInitialized() {
        synchronized(initLock) {
            if (isInitialized) return
            val startInit = System.currentTimeMillis()
            try {
                var modelFile = getOrExtractModelFile(context, modelAssetPath)
                if (modelFile == null || !modelFile.exists() || modelFile.length() == 0L) {
                    logcat(LogPriority.INFO) { "[LiteRTInpaint] Primary model $modelAssetPath missing; trying gz $FALLBACK_FP16_GZ" }
                    modelFile = getOrExtractModelFile(context, FALLBACK_FP16_GZ)
                }
                if (modelFile == null || !modelFile.exists() || modelFile.length() == 0L) {
                    logcat(LogPriority.INFO) { "[LiteRTInpaint] Plain FP16 model missing; trying dynamic INT8 $FALLBACK_DYN_INT8" }
                    modelFile = getOrExtractModelFile(context, FALLBACK_DYN_INT8)
                }

                if (modelFile == null || !modelFile.exists() || modelFile.length() == 0L) {
                    logcat(LogPriority.WARN) { "[LiteRTInpaint] No model file available; LiteRT inpainter unavailable" }
                    return
                }

                val libDir = context.applicationInfo.nativeLibraryDir
                val envOptions = mapOf(
                    Environment.Option.CompilerPluginLibraryDir to libDir,
                    Environment.Option.DispatchLibraryDir to libDir,
                )
                val env = try {
                    Environment.create(
                        context,
                        BuiltinNpuAcceleratorProvider(context),
                        envOptions,
                    )
                } catch (t: Throwable) {
                    logcat(LogPriority.WARN, t) { "[LiteRTInpaint] Environment with NpuAcceleratorProvider failed; falling back to default Environment" }
                    Environment.create(context)
                }
                environment = env
                val availableAccelerators = try {
                    env.getAvailableAccelerators()
                } catch (e: Throwable) {
                    logcat(LogPriority.WARN, e) { "[LiteRTInpaint] Failed to query available accelerators" }
                    emptySet()
                }
                logcat(LogPriority.INFO) {
                    "[LiteRTInpaint] Accelerators registered in LiteRT runtime: $availableAccelerators"
                }

                var compiled = false

                // 1. Primary accelerator: Prioritize GPU with FP16 model (Adreno 750 OpenCL 222ms rock-solid baseline)
                if (!enableExperimentalNpu && (Accelerator.GPU in availableAccelerators || availableAccelerators.isEmpty())) {
                    logcat(LogPriority.INFO) {
                        "[LiteRTInpaint] Prioritizing stable GPU accelerator (Adreno 750 OpenCL 222ms baseline)"
                    }
                    compiled = tryCreateCompiledModel(modelFile, Accelerator.GPU, env)
                }

                // 2. Experimental Qualcomm NPU path (HTP V75 INT8 JIT compilation)
                if (!compiled && Accelerator.NPU in availableAccelerators) {
                    logcat(LogPriority.INFO) {
                        "[LiteRTInpaint] NPU accelerator registered; attempting NPU compilation with INT8 model"
                    }
                    val npuModelFile = getOrExtractModelFile(context, MODEL_INT8_PATH)
                        ?: getOrExtractModelFile(context, FALLBACK_INT8_GZ)
                    if (npuModelFile != null) {
                        compiled = tryCreateCompiledModel(npuModelFile, Accelerator.NPU, env)
                    } else {
                        logcat(LogPriority.WARN) {
                            "[LiteRTInpaint] INT8 model file not found for NPU; skipping NPU to avoid unaccelerated FP16 emulation"
                        }
                    }
                } else if (!compiled) {
                    logcat(LogPriority.INFO) {
                        "[LiteRTInpaint] Accelerator.NPU not available in LiteRT runtime; targeting GPU"
                    }
                }

                // 3. Fallback to GPU if NPU was attempted first and failed
                if (!compiled) {
                    if (Accelerator.GPU in availableAccelerators || availableAccelerators.isEmpty()) {
                        logcat(LogPriority.INFO) { "[LiteRTInpaint] Falling back to GPU accelerator" }
                        compiled = tryCreateCompiledModel(modelFile, Accelerator.GPU, env)
                    } else {
                        logcat(LogPriority.WARN) { "[LiteRTInpaint] GPU accelerator not registered in LiteRT environment" }
                    }
                }

                // 3. Last-resort fallback to CPU
                if (!compiled) {
                    logcat(LogPriority.INFO) { "[LiteRTInpaint] Accelerators unavailable; falling back to LiteRT CPU" }
                    tryCreateCompiledModel(modelFile, Accelerator.CPU, env)
                }

                if (model == null) {
                    env.close()
                    environment = null
                    logcat(LogPriority.WARN) { "[LiteRTInpaint] All LiteRT CompiledModel initialization paths failed" }
                    return
                }

                val elapsed = System.currentTimeMillis() - startInit
                logcat(LogPriority.INFO) {
                    "[InpaintBenchmark] [LiteRTInpaint] Ready in ${elapsed}ms | Backend: $backendName | Inputs=${inputBuffers.size}"
                }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "[LiteRTInpaint] Initialization failed completely" }
                closeRuntime()
            } finally {
                isInitialized = true
            }
        }
    }

    /** Builds the official CompiledModel and its reusable TensorBuffers. */
    private fun tryCreateCompiledModel(
        modelFile: File,
        accelerator: Accelerator,
        env: Environment,
    ): Boolean {
        var createdModel: CompiledModel? = null
        var createdInputs: List<TensorBuffer> = emptyList()
        var createdOutputs: List<TensorBuffer> = emptyList()
        try {
            val options = CompiledModel.Options(accelerator)
            if (accelerator == Accelerator.NPU) {
                try {
                    val qcOptions = CompiledModel.QualcommOptions(
                        logLevel = CompiledModel.QualcommOptions.LogLevel.INFO,
                        htpPerformanceMode = CompiledModel.QualcommOptions.HtpPerformanceMode.BURST,
                        optimizationLevel = CompiledModel.QualcommOptions.OptimizationLevel.HTP_OPTIMIZE_FOR_INFERENCE,
                    )
                    options.qualcommOptions = qcOptions
                    logcat(LogPriority.INFO) { "[LiteRTInpaint] Configured QualcommOptions: HTP BURST / OPTIMIZE_FOR_INFERENCE" }
                } catch (t: Throwable) {
                    logcat(LogPriority.WARN, t) { "[LiteRTInpaint] Failed to set QualcommOptions" }
                }
            }

            createdModel = CompiledModel.create(
                modelFile.absolutePath,
                options,
                env,
            )
            createdInputs = createdModel.createInputBuffers()
            createdOutputs = createdModel.createOutputBuffers()
            require(createdInputs.size in 1..2) {
                "Expected one or two input tensors, got ${createdInputs.size}"
            }
            require(createdOutputs.isNotEmpty()) { "CompiledModel returned no output tensors" }
            if (createdInputs.size == 2) {
                requireInputTensor(
                    createdModel,
                    inputName = "input_image",
                    expectedShape = listOf(1, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, 3),
                )
                requireInputTensor(
                    createdModel,
                    inputName = "input_mask",
                    expectedShape = listOf(1, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, 1),
                )
            }

            model = createdModel
            inputBuffers = createdInputs
            outputBuffers = createdOutputs
            isDualInput = createdInputs.size == 2
            backendName = when (accelerator) {
                Accelerator.NPU -> "NPU (LiteRT CompiledModel)"
                Accelerator.GPU -> "GPU (LiteRT CompiledModel)"
                else -> "CPU (LiteRT CompiledModel)"
            }
            logcat(LogPriority.INFO) {
                "[LiteRTInpaint] $backendName initialized successfully on ${modelFile.name} with ${createdInputs.size} inputs"
            }
            return true
        } catch (e: Throwable) {
            createdInputs.forEach { runCatching { it.close() } }
            createdOutputs.forEach { runCatching { it.close() } }
            runCatching { createdModel?.close() }
            val acceleratorName = when (accelerator) {
                Accelerator.NPU -> "NPU"
                Accelerator.GPU -> "GPU"
                else -> "CPU"
            }
            logcat(LogPriority.WARN, e) { "[LiteRTInpaint] $acceleratorName CompiledModel initialization failed on ${modelFile.name}" }
            return false
        }
    }

    /** Fails model setup early if the dual-input signature no longer matches the packer. */
    private fun requireInputTensor(
        model: CompiledModel,
        inputName: String,
        expectedShape: List<Int>,
    ) {
        val signature = "serving_default"
        val tensorType = model.getInputTensorType(inputName, signature)
        require(tensorType.elementType == TensorType.ElementType.FLOAT) {
            "Expected float input '$inputName', got ${tensorType.elementType}"
        }
        val layout = requireNotNull(tensorType.layout) { "Input '$inputName' has no tensor layout" }
        require(layout.dimensions == expectedShape) {
            "Expected input '$inputName' shape $expectedShape, got ${layout.dimensions}"
        }
    }

    private fun getOrExtractModelFile(context: Context, assetPath: String): File? {
        val targetDir = File(context.filesDir, "models/inpainting")
        if (!targetDir.exists()) targetDir.mkdirs()
        val baseName = File(assetPath).name.removeSuffix(".gz")
        val targetFile = File(targetDir, baseName)
        val prefs = context.getSharedPreferences("litert_model_cache", Context.MODE_PRIVATE)

        val appUpdateTime = try {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        } catch (_: Throwable) {
            0L
        }
        val versionKey = "app_update_time_$baseName"
        val sizeKey = "extracted_size_$baseName"
        val cachedTime = prefs.getLong(versionKey, -1L)
        val cachedSize = prefs.getLong(sizeKey, -1L)

        if (targetFile.exists() && targetFile.length() > 30_000_000L && cachedTime == appUpdateTime &&
            (cachedSize <= 0L || targetFile.length() == cachedSize)
        ) {
            return targetFile
        }

        val candidates = listOf(
            assetPath,
            if (assetPath.endsWith(".gz")) assetPath.removeSuffix(".gz") else "$assetPath.gz",
        ).distinct()

        for (candidate in candidates) {
            val isGzip = candidate.endsWith(".gz")
            try {
                val tempFile = File(targetDir, "${targetFile.name}.tmp")
                context.assets.open(candidate).use { rawInput ->
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
                    "[LiteRTInpaint] Extracted asset $candidate to ${targetFile.absolutePath} (${targetFile.length()} bytes)"
                }
                return targetFile
            } catch (_: Exception) {
                // Try next candidate
            }
        }
        logcat(LogPriority.ERROR) { "[LiteRTInpaint] Failed to extract any asset candidate for $assetPath to storage" }
        return null
    }

    /**
     * Executes neural inpainting on the provided page bitmap across detected boxes and masks.
     * Uses 1:1 tight pill stroke masking and ring-median background padding to preserve original artwork sharpness.
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
        if (model == null) {
            logcat(LogPriority.WARN) { "[LiteRTInpaint] CompiledModel not available; returning unmodified bitmap" }
            return image
        }

        val result = image.copy(Bitmap.Config.ARGB_8888, true)
        fillPatchesInPlace(result, boxes)
        return result
    }

    /**
     * Neural fill for Paddle-refined free-text line boxes, mutating [image] in
     * place. Speech bubbles never route here — the dispatch keeps them on the
     * smooth diffusion path — so every box in [boxes] is free text.
     *
     * Returns the mutated [image], or null when the CompiledModel is unavailable
     * (the caller falls back to its classical free-text path).
     */
    fun inpaintFreeText(
        image: Bitmap,
        groupBoxes: List<IntArray>,
    ): Bitmap? {
        if (groupBoxes.isEmpty()) return image
        ensureInitialized()
        if (model == null) return null
        fillPatchesInPlace(image, groupBoxes)
        return image
    }

    /**
     * Neural fill for ONE speech-bubble segmentation-mask tile, invoked by the
     * [eu.kanade.translation.engines.inpainting.aot.NeuralInpaintingCoordinator]
     * in QUALITY mode. [localMask] is the (contour-eroded) supplied bubble
     * segmentation crop — a non-rectangular balloon interior, never a bounding
     * box or pill — and it is preserved in shape through the 1:1 pad, model
     * inference, centered crop, and feathered compositing; pixels outside the
     * mask keep their original values ([eu.kanade.translation.engines.inpainting.InpaintPixelOps.compositeInto]
     * blends with alpha 0 there).
     *
     * Returns the filled side×side candidate pixels WITHOUT compositing (the
     * caller owns the feathered blend), or null when the CompiledModel is
     * unavailable or the geometry does not match (the caller falls back to the
     * ONNX ladder, then the solid median blend).
     */
    @Synchronized
    fun inpaintMaskedSquare(
        image: Bitmap,
        localMask: ByteArray,
        crop: IntArray,
        side: Int,
    ): IntArray? {
        if (side <= 0 || side > MODEL_INPUT_SIZE) return null
        if (localMask.size != side * side) return null
        if (crop.size < 4 || crop[2] - crop[0] != side || crop[3] - crop[1] != side) return null
        if (crop[0] < 0 || crop[1] < 0) return null
        ensureInitialized()
        synchronized(initLock) {
            if (model == null) return null
        }
        val sourceSquare = IntArray(side * side)
        image.getPixels(sourceSquare, 0, side, crop[0], crop[1], side, side)
        val localMaskPixels = IntArray(side * side) { i ->
            if (localMask[i] != 0.toByte()) 0xFFFFFFFF.toInt() else 0
        }

        // 1:1 padding into the 512x512 tensor canvas. When side == 512 this is
        // a direct copy; the mask shape survives exactly (no resize, no warp).
        if (side == MODEL_INPUT_SIZE) {
            System.arraycopy(sourceSquare, 0, paddedSourcePixels, 0, side * side)
            System.arraycopy(localMaskPixels, 0, paddedMaskPixels, 0, side * side)
        } else {
            val background = PushPullGradient.localRingMedian(
                sourceSquare,
                side,
                side,
                localMask,
                PushPullGradient.DEFAULT_RING,
            )
            InpaintPadOps.padSquareInto(sourceSquare, side, background, paddedSourcePixels)
            InpaintPadOps.padSquareInto(localMaskPixels, side, 0, paddedMaskPixels)
        }

        val outputValues = runPaddedModel() ?: return null
        return decodeOutputSquare(outputValues, side)
    }

    /** Runs the model on the shared padded tensors; null when the model is closed. */
    @Synchronized
    private fun runPaddedModel(): FloatArray? {
        val activeModel = model ?: return null
        if (isDualInput) {
            // inputBuffers[0] is RGB [1, 512, 512, 3] NHWC; inputBuffers[1]
            // is the binary mask [1, 512, 512, 1] NHWC.
            var imageIndex = 0
            for (i in 0 until MODEL_INPUT_SIZE * MODEL_INPUT_SIZE) {
                val p = paddedSourcePixels[i]
                val isMask = if ((paddedMaskPixels[i] and 0xFF) > 127) 1.0f else 0.0f
                val keep = 1.0f - isMask

                imageInputValues[imageIndex++] = ((p shr 16) and 0xFF) / 255.0f * keep
                imageInputValues[imageIndex++] = ((p shr 8) and 0xFF) / 255.0f * keep
                imageInputValues[imageIndex++] = (p and 0xFF) / 255.0f * keep
                maskInputValues[i] = isMask
            }
            inputBuffers[0].writeFloat(imageInputValues)
            inputBuffers[1].writeFloat(maskInputValues)
        } else {
            // Legacy fallback: single 4-channel input [1, 512, 512, 4]
            var inputIndex = 0
            for (i in 0 until MODEL_INPUT_SIZE * MODEL_INPUT_SIZE) {
                val p = paddedSourcePixels[i]
                val isMask = if ((paddedMaskPixels[i] and 0xFF) > 127) 1.0f else 0.0f
                val keep = 1.0f - isMask

                singleInputValues[inputIndex++] = ((p shr 16) and 0xFF) / 255.0f * keep
                singleInputValues[inputIndex++] = ((p shr 8) and 0xFF) / 255.0f * keep
                singleInputValues[inputIndex++] = (p and 0xFF) / 255.0f * keep
                singleInputValues[inputIndex++] = isMask
            }
            inputBuffers[0].writeFloat(singleInputValues)
        }
        activeModel.run(inputBuffers, outputBuffers)
        return outputBuffers[0].readFloat()
    }

    /**
     * Decodes the raw NHWC output into sourceSide×sourceSide pixels. The
     * monochrome projection uses the PADDED source, so the decision is stable
     * regardless of the crop side; the centered side×side area is extracted
     * 1:1 (zero scaling blur) and copied so later patches cannot alias it.
     */
    private fun decodeOutputSquare(outputValues: FloatArray, sourceSide: Int): IntArray {
        val isMonochrome = isMonochromeCrop(paddedSourcePixels, MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
        for (i in 0 until MODEL_INPUT_SIZE * MODEL_INPUT_SIZE) {
            val outputIndex = i * 3
            val r = (outputValues[outputIndex].coerceIn(0.0f, 1.0f) * 255.0f).toInt()
            val g = (outputValues[outputIndex + 1].coerceIn(0.0f, 1.0f) * 255.0f).toInt()
            val b = (outputValues[outputIndex + 2].coerceIn(0.0f, 1.0f) * 255.0f).toInt()
            if (isMonochrome) {
                val lum = ((r * 299 + g * 587 + b * 114 + 500) / 1000).coerceIn(0, 255)
                paddedOutputPixels[i] = (0xFF shl 24) or (lum shl 16) or (lum shl 8) or lum
            } else {
                paddedOutputPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return if (sourceSide == MODEL_INPUT_SIZE) {
            paddedOutputPixels.copyOf()
        } else {
            val decoded = IntArray(sourceSide * sourceSide)
            InpaintPadOps.cropSquareInto(paddedOutputPixels, sourceSide, decoded)
            decoded
        }
    }

    /** Page dims cached per fill for the benchmark log. */
    @Synchronized
    private fun fillPatchesInPlace(
        image: Bitmap,
        boxes: List<IntArray>,
    ) {
        // close() uses the same monitor. Recheck after acquiring it so a caller
        // that passed its earlier availability check can safely bail out.
        if (model == null) {
            logcat(LogPriority.WARN) { "[LiteRTInpaint] Model closed before inference" }
            return
        }
        val pageStart = System.currentTimeMillis()
        var totalInferMs = 0L
        var patchCount = 0

        val pageW = image.width
        val pageH = image.height

        // 1. Cluster boxes into contextual evaluation patches (at most MODEL_INPUT_SIZE)
        val patches = clusterIntoPatches(boxes, pageW, pageH, MODEL_INPUT_SIZE, CONTEXT_PADDING)

        for (patch in patches) {
            val cropRect = patch.cropRect
            val side = cropRect.width()
            if (side <= 0 || cropRect.height() != side) continue

            // Read sub-rectangle directly from image
            val sourceSquare = IntArray(side * side)
            image.getPixels(sourceSquare, 0, side, cropRect.left, cropRect.top, side, side)

            // Localize text boxes to cropRect coordinates
            val localBoxes = patch.containedBoxes.mapNotNull { box ->
                InpaintBoxGeometry.localizeBox(box, cropRect.left, cropRect.top, side, side)
            }
            if (localBoxes.isEmpty()) continue

            // Build tight fixed pill mask (stroke masking: pad = 2, dilate = 2)
            val localMaskBytes = BubbleMaskBuilder.buildFixedPillMask(
                boxes = localBoxes,
                width = side,
                height = side,
                pad = MASK_PAD,
                dilateRadius = MASK_DILATE,
            )
            if (localMaskBytes.none { it != 0.toByte() }) continue

            val localMaskPixels = IntArray(side * side) { i ->
                if (localMaskBytes[i] != 0.toByte()) 0xFFFFFFFF.toInt() else 0
            }

            // Pad into 512x512 tensor canvas:
            // When side == MODEL_INPUT_SIZE, padSquareInto is a direct copy.
            // When side < MODEL_INPUT_SIZE, use local ring-median background padding
            // so screentones are not distorted by border replicate streaks.
            if (side == MODEL_INPUT_SIZE) {
                System.arraycopy(sourceSquare, 0, paddedSourcePixels, 0, side * side)
                System.arraycopy(localMaskPixels, 0, paddedMaskPixels, 0, side * side)
            } else {
                val background = PushPullGradient.localRingMedian(
                    sourceSquare,
                    side,
                    side,
                    localMaskBytes,
                    PushPullGradient.DEFAULT_RING,
                )
                InpaintPadOps.padSquareInto(sourceSquare, side, background, paddedSourcePixels)
                InpaintPadOps.padSquareInto(localMaskPixels, side, 0, paddedMaskPixels)
            }

            // 2. LiteRT CompiledModel inference into its preallocated TensorBuffers.
            val inferStart = System.currentTimeMillis()
            val outputValues = runPaddedModel()
            if (outputValues == null) {
                logcat(LogPriority.WARN) { "[LiteRTInpaint] Model closed before inference completed" }
                break
            }
            totalInferMs += System.currentTimeMillis() - inferStart
            patchCount++

            // 3. Decode the centered side x side area back (1:1 pixel scale, zero scaling blur!)
            val decodedSquare = decodeOutputSquare(outputValues, side)

            // 4. Feathered alpha compositing with smoothstep falloff preserving original artwork outside mask
            val alphaField = BubbleMaskBuilder.featherAlphaField(
                localMaskBytes,
                side,
                side,
                FEATHER_RAMP_PX,
            )
            val blendedSquare = IntArray(side * side)
            InpaintPixelOps.compositeInto(sourceSquare, decodedSquare, alphaField, blendedSquare)

            // 5. Write back to image bitmap
            image.setPixels(blendedSquare, 0, side, cropRect.left, cropRect.top, side, side)
        }

        val totalPageElapsed = System.currentTimeMillis() - pageStart
        logcat(LogPriority.INFO) {
            "[InpaintBenchmark] Mode=LAMA_LITERT_COMPILED | Backend=$backendName | " +
                "Patches=$patchCount | PureInferMs=${totalInferMs}ms | TotalPageMs=${totalPageElapsed}ms | " +
                "PageSize=${pageW}x$pageH"
        }
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

        // Decompose any tall or wide boxes exceeding MAX_CLUSTER_BOX_SIZE into overlapping tiles
        val inputBoxes = mutableListOf<IntArray>()
        for (box in boxes) {
            val bw = box[2] - box[0]
            val bh = box[3] - box[1]
            if (bw > MAX_CLUSTER_BOX_SIZE || bh > MAX_CLUSTER_BOX_SIZE) {
                inputBoxes.addAll(InpaintBoxGeometry.tileBounds(box[0], box[1], box[2], box[3], tile = MAX_CLUSTER_BOX_SIZE, overlap = 64))
            } else {
                inputBoxes.add(box)
            }
        }

        val maxClusterSpan = patchSize - (2 * pad)
        val patches = mutableListOf<InpaintPatch>()
        val unassigned = inputBoxes.toMutableList()

        while (unassigned.isNotEmpty()) {
            val seed = unassigned.removeAt(0)
            val patchBoxes = mutableListOf(seed)

            var minX = seed[0]
            var minY = seed[1]
            var maxX = seed[2]
            var maxY = seed[3]

            // Iteratively absorb all nearby boxes that fit within maxClusterSpan so they run in one pass
            var mergedAny = true
            while (mergedAny) {
                mergedAny = false
                val iter = unassigned.iterator()
                while (iter.hasNext()) {
                    val candidate = iter.next()
                    val nMinX = min(minX, candidate[0])
                    val nMinY = min(minY, candidate[1])
                    val nMaxX = max(maxX, candidate[2])
                    val nMaxY = max(maxY, candidate[3])

                    if ((nMaxX - nMinX) <= maxClusterSpan && (nMaxY - nMinY) <= maxClusterSpan) {
                        minX = nMinX
                        minY = nMinY
                        maxX = nMaxX
                        maxY = nMaxY
                        patchBoxes.add(candidate)
                        iter.remove()
                        mergedAny = true
                    }
                }
            }

            val side = min(patchSize, min(pageW, pageH))
            val cx = (minX + maxX) / 2
            val cy = (minY + maxY) / 2

            val left = (cx - side / 2).coerceIn(0, max(0, pageW - side))
            val top = (cy - side / 2).coerceIn(0, max(0, pageH - side))
            val right = min(pageW, left + side)
            val bottom = min(pageH, top + side)

            patches.add(InpaintPatch(Rect(left, top, right, bottom), patchBoxes))
        }

        return patches
    }

    private fun isMonochromeCrop(pixels: IntArray, count: Int): Boolean {
        if (count <= 0) return false
        val stride = max(1, count / 512)
        var totalDiff = 0L
        var samples = 0
        var i = 0
        while (i < count) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val diff = max(kotlin.math.abs(r - g), kotlin.math.abs(r - b))
            totalDiff += diff
            samples++
            i += stride
        }
        return if (samples > 0) (totalDiff.toDouble() / samples) < 3.0 else true
    }

    private fun closeRuntime() {
        inputBuffers.forEach { runCatching { it.close() } }
        outputBuffers.forEach { runCatching { it.close() } }
        inputBuffers = emptyList()
        outputBuffers = emptyList()
        runCatching { model?.close() }
        model = null
        runCatching { environment?.close() }
        environment = null
    }

    override fun close() {
        synchronized(initLock) {
            synchronized(this) {
                closeRuntime()
                isInitialized = false
            }
        }
    }
}
