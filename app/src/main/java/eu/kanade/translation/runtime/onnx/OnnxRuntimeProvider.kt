package eu.kanade.translation.runtime.onnx

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

object OnnxRuntimeProvider {

    val environment: OrtEnvironment by lazy {
        logcat { "Creating ONNX Runtime environment" }
        OrtEnvironment.getEnvironment()
    }

    /**
     * Create an ORT session, retrying on CPU if the requested execution provider
     * fails during graph compilation (e.g. NNAPI rejects a Split op in the model).
     * EP registration failures are caught inside [createSessionOptions], but graph
     * compilation happens in createSession itself and can throw ORT_FAIL for ops
     * the EP cannot handle. This wrapper catches that and falls back to plain CPU
     * so a single incompatible op never bricks the whole engine.
     */
    fun createSessionWithFallback(
        modelPath: String,
        useAccelerator: Boolean = false,
        useXnnpack: Boolean = false,
        disableIntraOpSpinning: Boolean = false,
    ): OrtSession {
        val opts = createSessionOptions(useAccelerator, useXnnpack, disableIntraOpSpinning)
        return try {
            environment.createSession(modelPath, opts)
        } catch (error: Throwable) {
            try { opts.close() } catch (_: Throwable) {}
            val ep = when {
                useAccelerator -> "NNAPI"
                useXnnpack -> "XNNPACK"
                else -> "CPU"
            }
            logcat(LogPriority.ERROR, error) {
                "Session creation with $ep failed (graph compile); retrying on CPU only"
            }
            val cpuOpts = createSessionOptions(
                useAccelerator = false,
                useXnnpack = false,
                disableIntraOpSpinning = disableIntraOpSpinning,
            )
            try {
                environment.createSession(modelPath, cpuOpts)
            } finally {
                cpuOpts.close()
            }
        } finally {
            try { opts.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Dedicated options for the fixed AOT baseline. XNNPACK is preferred but NOT
     * required: the onnxruntime-android-qnn artifact does not compile XNNPACK,
     * so a fatal requirement would break all AOT inpainting on that build. A
     * registration failure logs loudly and falls back to the default CPU EP.
     */
    fun createRequiredXnnpackSessionOptions(): OrtSession.SessionOptions =
        configureOwnedAotOptions { options ->
            try {
                options.addXnnpack(java.util.HashMap<String, String>())
                logcat(LogPriority.INFO) { "ONNX fixed AOT options provider=XNNPACK registered=true" }
            } catch (error: Throwable) {
                logcat(LogPriority.WARN, error) {
                    "ONNX fixed AOT options provider=XNNPACK registered=false fallback=CPU (XNNPACK not in this build)"
                }
            }
        }

    /**
     * Dedicated strict NNAPI options for fixed AOT only. CPU fallback is disabled,
     * and both config/EP failures deliberately propagate to the session creator.
     */
    fun createStrictNnapiSessionOptions(): OrtSession.SessionOptions =
        configureOwnedAotOptions { options ->
            options.addConfigEntry("session.disable_cpu_ep_fallback", "1")
            options.addNnapi()
            logcat(LogPriority.INFO) {
                "ONNX fixed AOT options provider=NNAPI strictCpuFallbackDisabled=true"
            }
        }

    private inline fun configureOwnedAotOptions(
        configure: (OrtSession.SessionOptions) -> Unit,
    ): OrtSession.SessionOptions {
        val options = createBaseAotOptions()
        try {
            configure(options)
            return options
        } catch (error: Throwable) {
            try { options.close() } catch (closeError: Throwable) { error.addSuppressed(closeError) }
            throw error
        }
    }

    private fun createBaseAotOptions(): OrtSession.SessionOptions =
        OrtSession.SessionOptions().apply {
            val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
            setInterOpNumThreads(threads)
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
            addConfigEntry("session.intra_op.allow_spinning", "0")
        }

    fun createSessionOptions(
        useAccelerator: Boolean = false,
        useXnnpack: Boolean = false,
        disableIntraOpSpinning: Boolean = false,
        configure: (OrtSession.SessionOptions) -> Unit = {},
    ): OrtSession.SessionOptions {
        if (useAccelerator) {
            logcat(LogPriority.INFO) { "ONNX session options using Hardware Accelerator (NNAPI)" }
        } else if (useXnnpack) {
            logcat(LogPriority.INFO) { "ONNX session options using XNNPACK CPU provider" }
        } else {
            logcat(LogPriority.INFO) { "ONNX session options using CPU execution provider" }
        }
        return OrtSession.SessionOptions().apply {
            val cpuCores = Runtime.getRuntime().availableProcessors()
            val threads = (cpuCores / 2).coerceIn(2, 4)
            setInterOpNumThreads(threads)
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            runCatching { setCPUArenaAllocator(false) }
                .onFailure { e ->
                    logcat(LogPriority.WARN, e) {
                        "setCPUArenaAllocator(false) rejected; arena will stay on (higher native footprint)"
                    }
                }
            runCatching { setMemoryPatternOptimization(false) }
                .onFailure { e ->
                    logcat(LogPriority.WARN, e) {
                        "setMemoryPatternOptimization(false) rejected; mem-pattern will stay on"
                    }
                }
            if (disableIntraOpSpinning) {
                runCatching {
                    // Must be set before the execution provider is registered.
                    addConfigEntry("session.intra_op.allow_spinning", "0")
                }.onFailure { e ->
                    logcat(LogPriority.WARN, e) {
                        "Could not disable ORT intra-op spinning for this session"
                    }
                }
            }
            if (useAccelerator) {
                runCatching {
                    addNnapi()
                    logcat(LogPriority.INFO) { "Successfully added NNAPI EP" }
                }.onFailure { e ->
                    logcat(LogPriority.ERROR, e) { "Failed to add NNAPI EP, falling back to CPU!" }
                }
            } else if (useXnnpack) {
                runCatching {
                    addXnnpack(java.util.HashMap<String, String>())
                    logcat(LogPriority.INFO) { "Successfully added XNNPACK EP" }
                }.onFailure { e ->
                    logcat(LogPriority.ERROR, e) { "Failed to add XNNPACK EP, falling back to CPU!" }
                }
            }
            configure(this)
        }
    }
}
