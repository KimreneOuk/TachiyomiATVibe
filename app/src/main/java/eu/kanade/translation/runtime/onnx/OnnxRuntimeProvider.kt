package eu.kanade.translation.runtime.onnx

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

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
     * the EP cannot handle. This wrapper catches that, trips the latching circuit
     * breaker if an accelerator was attempted, and falls back to plain CPU
     * so a single incompatible op never bricks the whole engine.
     */
    fun createSessionWithFallback(
        modelPath: String,
        useAccelerator: Boolean = false,
        useXnnpack: Boolean = false,
        disableIntraOpSpinning: Boolean = false,
        contextCacheDir: File? = null,
    ): OrtSession {
        val opts = createSessionOptions(
            useAccelerator = useAccelerator,
            useXnnpack = useXnnpack,
            disableIntraOpSpinning = disableIntraOpSpinning,
            contextCacheDir = contextCacheDir,
        )
        return try {
            environment.createSession(modelPath, opts)
        } catch (error: Throwable) {
            try {
                opts.close()
            } catch (_: Throwable) {}
            val routeName = when {
                useAccelerator -> HardwareDiscoveryEngine.activeRoute.name
                useXnnpack -> "XNNPACK"
                else -> "CPU"
            }
            logcat(LogPriority.ERROR, error) {
                "Session creation with $routeName failed (graph compile); tripping circuit breaker and retrying on CPU only"
            }
            if (useAccelerator) {
                HardwareDiscoveryEngine.tripCircuitBreaker("graph_compile_failed_$routeName", error)
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
            try {
                opts.close()
            } catch (_: Throwable) {}
        }
    }

    /**
     * Dedicated QNN HTP session options for Qualcomm Snapdragon NPUs.
     * Configures HTP execution provider, burst performance mode, graph optimization mode,
     * and on-disk context binary caching if [contextCacheDir] is provided.
     */
    fun createQnnHtpSessionOptions(
        contextCacheDir: File? = null,
        configure: (OrtSession.SessionOptions) -> Unit = {},
    ): OrtSession.SessionOptions {
        logcat(LogPriority.INFO) { "ONNX session options using Qualcomm QNN HTP NPU provider (contextCacheDir=$contextCacheDir)" }
        return OrtSession.SessionOptions().apply {
            val cpuCores = Runtime.getRuntime().availableProcessors()
            val threads = (cpuCores / 2).coerceIn(2, 4)
            setInterOpNumThreads(threads)
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            runCatching { setCPUArenaAllocator(false) }
                .onFailure { e ->
                    logcat(LogPriority.WARN, e) { "setCPUArenaAllocator(false) rejected; arena will stay on" }
                }
            runCatching { setMemoryPatternOptimization(false) }
                .onFailure { e ->
                    logcat(LogPriority.WARN, e) { "setMemoryPatternOptimization(false) rejected; mem-pattern will stay on" }
                }

            val qnnOptions = mutableMapOf<String, String>()
            qnnOptions["backend_type"] = "HTP"
            qnnOptions["htp_performance_mode"] = "burst"
            qnnOptions["htp_graph_finalization_optimization_mode"] = "3"
            if (contextCacheDir != null) {
                if (!contextCacheDir.exists()) {
                    contextCacheDir.mkdirs()
                }
                qnnOptions["qnn_context_cache_enable"] = "1"
                qnnOptions["qnn_context_cache_path"] = contextCacheDir.absolutePath
            }
            try {
                addQnn(qnnOptions)
                logcat(LogPriority.INFO) { "Successfully added QNN HTP EP with options: $qnnOptions" }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Failed to add QNN HTP EP, falling back to CPU!" }
                HardwareDiscoveryEngine.tripCircuitBreaker("qnn_htp_registration_failed", e)
            }
            configure(this)
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
            try {
                options.close()
            } catch (closeError: Throwable) {
                error.addSuppressed(closeError)
            }
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
        contextCacheDir: File? = null,
        configure: (OrtSession.SessionOptions) -> Unit = {},
    ): OrtSession.SessionOptions {
        val route = when {
            useAccelerator -> HardwareDiscoveryEngine.resolveRoute()
            useXnnpack -> HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK
            else -> null
        }

        when (route) {
            HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP ->
                logcat(LogPriority.INFO) { "ONNX session options using Qualcomm QNN HTP NPU provider" }
            HardwareDiscoveryEngine.HardwareRoute.NNAPI ->
                logcat(LogPriority.INFO) { "ONNX session options using Hardware Accelerator (NNAPI)" }
            HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK ->
                logcat(LogPriority.INFO) { "ONNX session options using XNNPACK CPU provider" }
            null ->
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
            when (route) {
                HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP -> {
                    runCatching {
                        val qnnOptions = mutableMapOf(
                            "backend_type" to "HTP",
                            "htp_performance_mode" to "burst",
                            "htp_graph_finalization_optimization_mode" to "3",
                        )
                        if (contextCacheDir != null) {
                            if (!contextCacheDir.exists()) contextCacheDir.mkdirs()
                            qnnOptions["qnn_context_cache_enable"] = "1"
                            qnnOptions["qnn_context_cache_path"] = contextCacheDir.absolutePath
                        }
                        addQnn(qnnOptions)
                        logcat(LogPriority.INFO) { "Successfully added QNN HTP EP" }
                    }.onFailure { e ->
                        logcat(LogPriority.ERROR, e) { "Failed to add QNN HTP EP, falling back to CPU!" }
                        HardwareDiscoveryEngine.tripCircuitBreaker("qnn_htp_add_failed", e)
                    }
                }
                HardwareDiscoveryEngine.HardwareRoute.NNAPI -> {
                    runCatching {
                        addNnapi()
                        logcat(LogPriority.INFO) { "Successfully added NNAPI EP" }
                    }.onFailure { e ->
                        logcat(LogPriority.ERROR, e) { "Failed to add NNAPI EP, falling back to CPU!" }
                        HardwareDiscoveryEngine.tripCircuitBreaker("nnapi_add_failed", e)
                    }
                }
                HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK -> {
                    runCatching {
                        addXnnpack(java.util.HashMap<String, String>())
                        logcat(LogPriority.INFO) { "Successfully added XNNPACK EP" }
                    }.onFailure { e ->
                        logcat(LogPriority.ERROR, e) { "Failed to add XNNPACK EP, falling back to CPU!" }
                    }
                }
                null -> {}
            }
            configure(this)
        }
    }
}
