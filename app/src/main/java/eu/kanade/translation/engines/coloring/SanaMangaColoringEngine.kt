package eu.kanade.translation.engines.coloring

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * On-device manga colorization (sana-manga-coloring-v2, Sana 600M DiT + DC-AE)
 * on the shared LiteRT CompiledModel runtime, following the LaMa ladder:
 * GPU-first fp16 for every graph, an optional experimental Qualcomm NPU pass
 * for the int8 DiT, one-shot per-graph CPU demotion on a failed or degenerate
 * pass, and no CPU fallback at init — an unsupported GPU leaves the engine
 * unavailable since colorization has no classical fallback.
 *
 * Graphs are static 512x768 (B=2: CFG doubles the rows in-graph), the look
 * token (a learned 256-f vector; the NULL look is a zero vector) lives
 * outside the DiT graph, and the Euler loop, CFG combine, seeding, and
 * line-art merge all run in Kotlin. Inputs bind by validated signature names
 * from the converter-emitted graph_signatures.json contract — resolution
 * fails closed.
 */
class SanaMangaColoringEngine(
    private val context: Context,
    private val enableExperimentalNpu: Boolean = false,
) : PageColorizationEngine {

    private companion object {
        const val INPUT_W = 512
        const val INPUT_H = 768
        const val LATENT_C = 32
        const val LOOK_DIM = 256
        const val LINE_INK = 1.0f
        const val CFG_ROWS = 2
        const val SAMPLE_STEPS = 20
        const val CFG_SCALE = 2.0f
        const val MERGE_SIGMA = 3.0f
        const val LETTERBOX_LUMINANCE = 1f // paper white padding

        const val DIT_FP16_ASSET = "models/coloring/sana_color_dit_fp16.tflite"
        const val DIT_INT8_ASSET = "models/coloring/sana_color_dit_int8.tflite"
        const val VAE_ENC_FP16_ASSET = "models/coloring/sana_color_vae_enc_fp16.tflite"
        const val VAE_DEC_FP16_ASSET = "models/coloring/sana_color_vae_dec_fp16.tflite"
        const val SIGNATURES_ASSET = "models/coloring/graph_signatures.json"
        const val LOOK_NORMAL_ASSET = "models/coloring/color_look_normal.f32.bin"

        const val MODEL_CACHE_DIR = "models/coloring"
        const val MODEL_CACHE_PREFS = "litert_model_cache"
        const val KEY_APP_UPDATE_TIME = "app_update_time_"
        const val KEY_EXTRACTED_SIZE = "extracted_size_"
        const val SIGNATURE = "serving_default"

        val BUNDLED_ASSETS = listOf(DIT_FP16_ASSET, VAE_ENC_FP16_ASSET, VAE_DEC_FP16_ASSET, SIGNATURES_ASSET, LOOK_NORMAL_ASSET)

        /** Cheap UI-thread probe: every bundled artifact present (or already extracted). */
        fun isModelAssetPresent(context: Context): Boolean {
            val prefs = context.getSharedPreferences(MODEL_CACHE_PREFS, Context.MODE_PRIVATE)
            val appUpdateTime = try {
                context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            } catch (_: Throwable) {
                0L
            }
            return BUNDLED_ASSETS.all { assetPath ->
                val baseName = File(assetPath).name
                val extracted = File(File(context.filesDir, MODEL_CACHE_DIR), baseName)
                val cacheValid = extracted.exists() &&
                    extracted.length() > 1_000_000L &&
                    prefs.getLong(KEY_APP_UPDATE_TIME + baseName, -1L) == appUpdateTime
                cacheValid || try {
                    context.assets.open(assetPath).close()
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }
    }

    @Serializable
    private data class TensorSpec(val name: String, val dims: List<Int>)

    @Serializable
    private data class GraphSpec(val inputs: List<TensorSpec>, val outputs: List<TensorSpec>)

    @Serializable
    private data class SignatureContract(val dit: GraphSpec, val vae_enc: GraphSpec, val vae_dec: GraphSpec)

    /** One live CompiledModel with its name-bound buffers; owner of its runtime file. */
    private class GraphSession(
        val model: CompiledModel,
        val accelerator: Accelerator,
        val file: File,
        val spec: GraphSpec,
        val inputBuffers: Map<String, TensorBuffer>,
        val outputBuffers: Map<String, TensorBuffer>,
    ) {
        val backendName: String = when (accelerator) {
            Accelerator.NPU -> "NPU"
            Accelerator.GPU -> "GPU"
            else -> "CPU"
        }
    }

    private var environment: Environment? = null
    private var dit: GraphSession? = null
    private var vaeEnc: GraphSession? = null
    private var vaeDec: GraphSession? = null
    private var contract: SignatureContract? = null
    private var lookNormal: FloatArray? = null

    private val initLock = Any()
    private var initialized = false

    override fun isAvailable(): Boolean {
        synchronized(initLock) {
            ensureInitialized()
            return dit != null && vaeEnc != null && vaeDec != null
        }
    }

    /** Stamp describing the live backend+artifact mix, for coloring provenance. */
    override fun currentStamp(): String? {
        val ditSession = dit ?: return null
        return "SANA_COLOR_${ditSession.backendName}_${if (ditSession.file.name.contains("int8")) "INT8" else "FP16"}"
    }

    /**
     * Colorizes a B&W manga page. Returns the merged, line-art-reimposed
     * result at the source dimensions; throws on runtime failure (the caller
     * owns the never-fail-the-page contract) — never when merely unavailable,
     * which [isAvailable] answers first.
     */
    @Synchronized
    override fun colorize(source: Bitmap, seed: Long): Bitmap {
        val ditSession = dit ?: error("[SanaColor] engine unavailable")
        val encSession = vaeEnc ?: error("[SanaColor] VAE encoder unavailable")
        val decSession = vaeDec ?: error("[SanaColor] VAE decoder unavailable")
        val look = lookNormal ?: error("[SanaColor] look vector unavailable")
        val startedAt = System.currentTimeMillis()

        val bwPixels01 = FloatArray(3 * INPUT_W * INPUT_H)
        preprocess(source, bwPixels01)

        val encInput = FloatArray(CFG_ROWS * bwPixels01.size)
        System.arraycopy(bwPixels01, 0, encInput, 0, bwPixels01.size)
        System.arraycopy(bwPixels01, 0, encInput, bwPixels01.size, bwPixels01.size)
        val zBw = runGraph(encSession, encInput)

        val latentSize = CFG_ROWS * LATENT_C * (INPUT_W / 32) * (INPUT_H / 32)
        val z = seededLatents(latentSize, seed)
        // v2 look conditioning: row 0 = the learned "normal" look vector, row 1
        // = the NULL look (all-zero) which is CFG's unconditional side.
        val tokens = FloatArray(CFG_ROWS * look.size).also {
            System.arraycopy(look, 0, it, 0, look.size)
        }
        val bwCfg = FloatArray(CFG_ROWS * bwPixels01.size).also {
            System.arraycopy(bwPixels01, 0, it, 0, bwPixels01.size)
            System.arraycopy(bwPixels01, 0, it, bwPixels01.size, bwPixels01.size)
        }

        val scheduler = FlowMatchEulerScheduler.forColoring()
        val velocity = FloatArray(latentSize)
        var encodeMs = 0L
        var decodeMs = 0L
        scheduler.timesteps.forEachIndexed { stepIndex, t ->
            val stepStartedAt = System.currentTimeMillis()
            val stepResult = runDitStep(ditSession, z, zBw, bwCfg, t, tokens)
            encodeMs += System.currentTimeMillis() - stepStartedAt
            // CFG: v = v_uncond + scale * (v_cond - v_uncond); cond is row 0.
            val rowSize = latentSize / CFG_ROWS
            for (i in 0 until rowSize) {
                val cond = stepResult[i]
                val uncond = stepResult[rowSize + i]
                velocity[i] = uncond + CFG_SCALE * (cond - uncond)
            }
            val next = scheduler.step(velocity, z, stepIndex)
            System.arraycopy(next, 0, z, 0, next.size)
        }

        val decInput = FloatArray(latentSize).also { System.arraycopy(z, 0, it, 0, latentSize) }
        val decoded = runGraph(decSession, decInput)
        decodeMs = System.currentTimeMillis() - startedAt - encodeMs

        val colored01 = nchwToRgb01(decoded, row = 0)
        val bw01 = nchwToRgb01(encInput, row = 0)
        val merged = LineArtMerger.merge(colored01, bw01, INPUT_W, INPUT_H, MERGE_SIGMA, LINE_INK)
        val result = rgb01ToBitmapCropped(merged, source.width, source.height)

        logcat(LogPriority.INFO) {
            "[SanaColor] page colored in ${System.currentTimeMillis() - startedAt}ms " +
                "(dit ${ditSession.backendName} ${encodeMs}ms, vae ${decodeMs}ms, stamp=${currentStamp()})"
        }
        return result
    }

    /** One DiT call with both CFG rows; demotes once to CPU on failure or degenerate output. */
    private fun runDitStep(
        session: GraphSession,
        z: FloatArray,
        zBw: FloatArray,
        bwCfg: FloatArray,
        t: Float,
        tokens: FloatArray,
    ): FloatArray {
        val timestep = FloatArray(CFG_ROWS) { t }
        val inputs = mapOf(
            "z_t" to z,
            "z_bw" to zBw,
            "bw" to bwCfg,
            "t" to timestep,
            "tokens" to tokens,
        )
        val output = FloatArray(z.size)
        try {
            runGraphInto(session, inputs, output)
        } catch (failure: Throwable) {
            val recovered = recoverDit(session, failure) ?: throw failure
            return runDitStepBuffers(recovered, inputs, output)
        }
        if (isDegenerateOutput(output)) {
            val degraded = IllegalStateException("[SanaColor] DiT ${session.backendName} returned an all-zero velocity")
            val recovered = recoverDit(session, degraded) ?: throw degraded
            return runDitStepBuffers(recovered, inputs, output)
        }
        return output
    }

    private fun runDitStepBuffers(session: GraphSession, inputs: Map<String, FloatArray>, output: FloatArray): FloatArray {
        runGraphInto(session, inputs, output)
        return output
    }

    /** Single failure recovery: rebuild this graph on CPU (int8 DiT preferred) and rerun. */
    private fun recoverDit(failed: GraphSession, failure: Throwable): GraphSession? {
        if (failed.accelerator == Accelerator.CPU) return null
        val env = environment ?: return null
        val spec = contract?.dit ?: return null
        logcat(LogPriority.WARN, failure) { "[SanaColor] DiT ${failed.backendName} pass failed; demoting to CPU" }
        val cpuFile = extractedFileFor(DIT_INT8_ASSET) ?: extractedFileFor(DIT_FP16_ASSET) ?: return null
        val recovered = tryCreateSession(cpuFile, Accelerator.CPU, spec, env) ?: return null
        releaseSession(failed)
        dit = recovered
        return recovered
    }

    /** Runs a B=2 single-input graph; the input buffer is row-duplicated by the caller. */
    private fun runGraph(session: GraphSession, input: FloatArray): FloatArray {
        val inputSpec = session.spec.inputs.single()
        val output = FloatArray(specFloatCount(session.spec.outputs.single()))
        runGraphInto(session, mapOf(inputSpec.name to input), output)
        return output
    }

    private fun runGraphInto(session: GraphSession, inputs: Map<String, FloatArray>, output: FloatArray) {
        for ((name, values) in inputs) {
            val buffer = session.inputBuffers[name] ?: error("[SanaColor] unbound input '$name'")
            buffer.writeFloat(values)
        }
        val outputSpec = session.spec.outputs.single()
        val outBuffer = session.outputBuffers[outputSpec.name] ?: error("[SanaColor] unbound output '${outputSpec.name}'")
        session.model.run(session.inputBuffers, mapOf(outputSpec.name to outBuffer), SIGNATURE)
        val read = outBuffer.readFloat()
        check(read.size == output.size) { "[SanaColor] output float count ${read.size} vs expected ${output.size}" }
        System.arraycopy(read, 0, output, 0, output.size)
    }

    private fun specFloatCount(spec: TensorSpec): Int = spec.dims.fold(1) { acc, dim -> acc * dim }

    private fun isDegenerateOutput(output: FloatArray): Boolean {
        for (value in output) if (value != 0f) return false
        return true
    }

    private fun seededLatents(floatCount: Int, seed: Long): FloatArray {
        val random = java.util.Random(seed)
        val latents = FloatArray(floatCount)
        var index = 0
        while (index < floatCount) {
            if (index + 1 < floatCount) {
                val u1 = max(random.nextDouble(), Double.MIN_VALUE)
                val u2 = random.nextDouble()
                val radius = sqrt(-2.0 * ln(u1))
                latents[index++] = (radius * kotlin.math.cos(2.0 * Math.PI * u2)).toFloat()
                latents[index++] = (radius * kotlin.math.sin(2.0 * Math.PI * u2)).toFloat()
            } else {
                val u1 = max(random.nextDouble(), Double.MIN_VALUE)
                val u2 = random.nextDouble()
                latents[index++] = (sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)).toFloat()
            }
        }
        return latents
    }

    /** Grayscale -> RGB, antialiased letterbox into the 512x768 canvas, [0,1] RGB rows. */
    private fun preprocess(source: Bitmap, out: FloatArray) {
        val scale = min(INPUT_W.toFloat() / source.width, INPUT_H.toFloat() / source.height)
        val drawW = (source.width * scale).toInt().coerceIn(1, INPUT_W)
        val drawH = (source.height * scale).toInt().coerceIn(1, INPUT_H)
        val offsetX = (INPUT_W - drawW) / 2
        val offsetY = (INPUT_H - drawH) / 2

        val scaled = Bitmap.createScaledBitmap(source, drawW, drawH, true)
        val scaledPixels = IntArray(drawW * drawH)
        scaled.getPixels(scaledPixels, 0, drawW, 0, 0, drawW, drawH)
        if (scaled !== source) scaled.recycle()

        for (y in 0 until INPUT_H) {
            val insideY = y in offsetY until offsetY + drawH
            val scaledY = if (insideY) y - offsetY else -1
            for (x in 0 until INPUT_W) {
                val insideX = x in offsetX until offsetX + drawW
                val luminance = if (insideY && insideX) {
                    val argb = scaledPixels[scaledY * drawW + (x - offsetX)]
                    val r = (argb shr 16) and 0xFF
                    val g = (argb shr 8) and 0xFF
                    val b = argb and 0xFF
                    (r * 299 + g * 587 + b * 114) / 1000f / 255f
                } else {
                    LETTERBOX_LUMINANCE
                }
                val index = (y * INPUT_W + x) * 3
                out[index] = luminance
                out[index + 1] = luminance
                out[index + 2] = luminance
            }
        }
    }

    /** Row [row] of an NCHW (rows,3,H,W) buffer as an RGB float copy. */
    private fun nchwToRgb01(buffer: FloatArray, row: Int): FloatArray {
        val plane = buffer.size / CFG_ROWS
        val rowBase = row * plane
        val pixels = INPUT_W * INPUT_H
        val rgb = FloatArray(pixels * 3)
        for (channel in 0 until 3) {
            System.arraycopy(buffer, rowBase + channel * pixels, rgb, channel * pixels, pixels)
        }
        return rgb
    }

    /** RGB [0,1] rows (plane-major) -> ARGB bitmap cropped from the letterbox and scaled to the page. */
    private fun rgb01ToBitmapCropped(rgb: FloatArray, targetWidth: Int, targetHeight: Int): Bitmap {
        val canvas = Bitmap.createBitmap(INPUT_W, INPUT_H, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(INPUT_W * INPUT_H)
        for (i in pixels.indices) {
            val r = (rgb[i * 3] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val g = (rgb[i * 3 + 1] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val b = (rgb[i * 3 + 2] * 255f + 0.5f).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        canvas.setPixels(pixels, 0, INPUT_W, 0, 0, INPUT_W, INPUT_H)

        val scale = min(INPUT_W.toFloat() / targetWidth, INPUT_H.toFloat() / targetHeight)
        val drawW = (targetWidth * scale).toInt().coerceIn(1, INPUT_W)
        val drawH = (targetHeight * scale).toInt().coerceIn(1, INPUT_H)
        val offsetX = (INPUT_W - drawW) / 2
        val offsetY = (INPUT_H - drawH) / 2
        val cropped = Bitmap.createBitmap(canvas, offsetX, offsetY, drawW, drawH)
        if (cropped !== canvas) canvas.recycle()
        return if (drawW == targetWidth && drawH == targetHeight) {
            cropped
        } else {
            val scaled = Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
            if (scaled !== cropped) cropped.recycle()
            scaled
        }
    }

    private fun ensureInitialized() {
        synchronized(initLock) {
            if (initialized) return
            val startedAt = System.currentTimeMillis()
            try {
                if (!isModelAssetPresent(context)) {
                    logcat(LogPriority.WARN) { "[SanaColor] coloring model assets absent; engine unavailable" }
                    return
                }
                contract = openAsset(SIGNATURES_ASSET).decodeToString().let {
                    Json { ignoreUnknownKeys = true }.decodeFromString(SignatureContract.serializer(), it)
                }
                lookNormal = openAsset(LOOK_NORMAL_ASSET).let { bytes ->
                    val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    FloatArray(bytes.size / 4) { buffer.float }
                }.also { check(it.size == LOOK_DIM) { "look vector is ${it.size} floats, expected $LOOK_DIM" } }

                val libDir = context.applicationInfo.nativeLibraryDir
                val envOptions = mapOf(
                    Environment.Option.CompilerPluginLibraryDir to libDir,
                    Environment.Option.DispatchLibraryDir to libDir,
                )
                val env = try {
                    Environment.create(context, BuiltinNpuAcceleratorProvider(context), envOptions)
                } catch (t: Throwable) {
                    logcat(LogPriority.WARN, t) { "[SanaColor] NPU-capable Environment failed; using default Environment" }
                    Environment.create(context)
                }
                environment = env
                val available = try {
                    env.getAvailableAccelerators()
                } catch (t: Throwable) {
                    logcat(LogPriority.WARN, t) { "[SanaColor] accelerator query failed" }
                    emptySet<Accelerator>()
                }
                val gpuAllowed = Accelerator.GPU in available || available.isEmpty()

                // VAE graphs are conv-heavy and fp16: GPU or nothing.
                vaeEnc = if (gpuAllowed) tryCreateSession(extractedFileFor(VAE_ENC_FP16_ASSET)!!, Accelerator.GPU, contract!!.vae_enc, env) else null
                vaeDec = if (gpuAllowed) tryCreateSession(extractedFileFor(VAE_DEC_FP16_ASSET)!!, Accelerator.GPU, contract!!.vae_dec, env) else null

                // DiT: GPU fp16 first, then the experimental int8 NPU pass, then a GPU retry.
                val fp16File = extractedFileFor(DIT_FP16_ASSET)!!
                var ditSession = if (gpuAllowed) tryCreateSession(fp16File, Accelerator.GPU, contract!!.dit, env) else null
                val npuAttempted = ditSession == null && enableExperimentalNpu && Accelerator.NPU in available
                if (npuAttempted) {
                    logcat(LogPriority.INFO) { "[SanaColor] experimental NPU opt-in; compiling int8 DiT on NPU" }
                    ditSession = extractedFileFor(DIT_INT8_ASSET)?.let { tryCreateSession(it, Accelerator.NPU, contract!!.dit, env) }
                    if (ditSession == null && gpuAllowed) {
                        logcat(LogPriority.INFO) { "[SanaColor] final GPU retry after failed NPU attempt" }
                        ditSession = tryCreateSession(fp16File, Accelerator.GPU, contract!!.dit, env)
                    }
                }
                dit = ditSession

                if (dit == null || vaeEnc == null || vaeDec == null) {
                    closeRuntime()
                    logcat(LogPriority.WARN) { "[SanaColor] session ladder incomplete; engine unavailable" }
                    return
                }
                logcat(LogPriority.INFO) {
                    "[SanaColor] ready in ${System.currentTimeMillis() - startedAt}ms " +
                        "(dit=${dit?.backendName}, enc=${vaeEnc?.backendName}, dec=${vaeDec?.backendName})"
                }
            } catch (t: Throwable) {
                logcat(LogPriority.ERROR, t) { "[SanaColor] initialization failed" }
                closeRuntime()
            } finally {
                initialized = true
            }
        }
    }

    private fun tryCreateSession(file: File, accelerator: Accelerator, spec: GraphSpec, env: Environment): GraphSession? {
        var model: CompiledModel? = null
        val inputBuffers = HashMap<String, TensorBuffer>()
        val outputBuffers = HashMap<String, TensorBuffer>()
        try {
            val options = CompiledModel.Options(accelerator)
            if (accelerator == Accelerator.NPU) {
                options.qualcommOptions = CompiledModel.QualcommOptions(
                    logLevel = CompiledModel.QualcommOptions.LogLevel.INFO,
                    htpPerformanceMode = CompiledModel.QualcommOptions.HtpPerformanceMode.BURST,
                    optimizationLevel = CompiledModel.QualcommOptions.OptimizationLevel.HTP_OPTIMIZE_FOR_INFERENCE,
                )
            }
            model = CompiledModel.create(file.absolutePath, options, env)
            for (tensor in spec.inputs) {
                validateTensor(model, tensor, isInput = true, file.name)
                inputBuffers[tensor.name] = model.createInputBuffer(tensor.name, SIGNATURE)
            }
            for (tensor in spec.outputs) {
                validateTensor(model, tensor, isInput = false, file.name)
                outputBuffers[tensor.name] = model.createOutputBuffer(tensor.name, SIGNATURE)
            }
            val session = GraphSession(model, accelerator, file, spec, inputBuffers, outputBuffers)
            logcat(LogPriority.INFO) {
                "[SanaColor] ${session.backendName} session on ${file.name}: " +
                    "in=${spec.inputs.joinToString { it.name }} out=${spec.outputs.joinToString { it.name }}"
            }
            return session
        } catch (t: Throwable) {
            inputBuffers.values.forEach { runCatching { it.close() } }
            outputBuffers.values.forEach { runCatching { it.close() } }
            runCatching { model?.close() }
            logcat(LogPriority.WARN, t) { "[SanaColor] $accelerator session failed on ${file.name}" }
            return null
        }
    }

    /** Fail-closed: float element type and exact dims, both from the converter contract. */
    private fun validateTensor(model: CompiledModel, tensor: TensorSpec, isInput: Boolean, fileName: String) {
        val type = if (isInput) model.getInputTensorType(tensor.name, SIGNATURE) else model.getOutputTensorType(tensor.name, SIGNATURE)
        check(type.elementType == TensorType.ElementType.FLOAT) {
            "[SanaColor] $fileName tensor '${tensor.name}' is not FLOAT"
        }
        val dims = type.layout?.dimensions.orEmpty()
        check(dims == tensor.dims) {
            "[SanaColor] $fileName tensor '${tensor.name}' dims $dims disagree with contract ${tensor.dims}"
        }
    }

    private fun releaseSession(session: GraphSession) {
        session.inputBuffers.values.forEach { runCatching { it.close() } }
        session.outputBuffers.values.forEach { runCatching { it.close() } }
        runCatching { session.model.close() }
    }

    private fun closeRuntime() {
        dit?.let(::releaseSession)
        vaeEnc?.let(::releaseSession)
        vaeDec?.let(::releaseSession)
        dit = null
        vaeEnc = null
        vaeDec = null
        runCatching { environment?.close() }
        environment = null
        contract = null
        lookNormal = null
    }

    /**
     * Releases the LiteRT runtime but stays attached and re-initializable:
     * closeEngines fires on user stop / translation disable while translation
     * can restart in the same session (mirrors the engine-lane rebuild flow).
     */
    override fun close() {
        synchronized(initLock) {
            closeRuntime()
            initialized = false
        }
    }

    private fun openAsset(assetPath: String): ByteArray = context.assets.open(assetPath).use { it.readBytes() }

    /** Extraction + version-stamped cache, keyed by app update time, mirroring the LaMa ladder. */
    private fun extractedFileFor(assetPath: String): File? {
        val targetDir = File(context.filesDir, MODEL_CACHE_DIR)
        if (!targetDir.exists()) targetDir.mkdirs()
        val targetFile = File(targetDir, File(assetPath).name)
        val prefs = context.getSharedPreferences(MODEL_CACHE_PREFS, Context.MODE_PRIVATE)
        val appUpdateTime = try {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        } catch (_: Throwable) {
            0L
        }
        val cachedTime = prefs.getLong(KEY_APP_UPDATE_TIME + targetFile.name, -1L)
        val cachedSize = prefs.getLong(KEY_EXTRACTED_SIZE + targetFile.name, -1L)
        val cacheValid = targetFile.exists() &&
            targetFile.length() > 1_000_000L &&
            cachedTime == appUpdateTime &&
            (cachedSize <= 0L || targetFile.length() == cachedSize)
        if (cacheValid) return targetFile

        try {
            val tempFile = File(targetDir, "${targetFile.name}.tmp")
            context.assets.open(assetPath).use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            prefs.edit()
                .putLong(KEY_APP_UPDATE_TIME + targetFile.name, appUpdateTime)
                .putLong(KEY_EXTRACTED_SIZE + targetFile.name, targetFile.length())
                .apply()
            return targetFile
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "[SanaColor] failed to extract $assetPath" }
            return null
        }
    }
}
