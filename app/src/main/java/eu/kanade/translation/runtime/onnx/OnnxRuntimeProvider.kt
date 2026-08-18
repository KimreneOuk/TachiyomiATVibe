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
     * QNN HTP provider options shared by every QNN session (probe, detectors,
     * inpainting). ORT 1.27 parses these strictly:
     * - soc_model/htp_arch are integer strings (std::stoi) — string names throw;
     * - htp_arch only accepts 0/68/69/73/75/81;
     * - context caching is NOT a provider option — it is enabled via the
     *   "ep.context_enable"/"ep.context_file_path" session config entries.
     */
    internal fun buildQnnProviderOptions(
        socModel: String? = DeviceCapability.qnnSocModel,
        htpArch: String? = DeviceCapability.qnnHtpArch,
    ): Map<String, String> = buildMap {
        put("backend_type", "htp")
        put("htp_performance_mode", "burst")
        put("htp_graph_finalization_optimization_mode", "3")
        socModel?.let { put("soc_model", it) }
        htpArch?.let { put("htp_arch", it) }
    }

    /**
     * Create an ORT session, retrying on CPU if the requested execution provider
     * fails during session creation. With the strict CPU-fallback-disabled
     * accelerator options a graph the EP cannot fully take throws at creation
     * (instead of silently degrading to CPU), so this retry path means
     * "this model is not partitionable on the latched EP" — the model alone
     * drops to CPU. Device health is gated by the [HardwareDiscoveryEngine]
     * probe, so a per-model failure does not trip the circuit breaker;
     * runtime execution failures on accelerator sessions still do.
     *
     * [providerSink] receives the provider that actually served the session
     * ("qnn_htp", "nnapi", or "cpu") for honest per-engine route logging.
     */
    fun createSessionWithFallback(
        modelPath: String,
        useAccelerator: Boolean = false,
        useXnnpack: Boolean = false,
        disableIntraOpSpinning: Boolean = false,
        contextCacheFile: File? = null,
        providerSink: (String) -> Unit = {},
    ): OrtSession {
        val opts = createSessionOptions(
            useAccelerator = useAccelerator,
            useXnnpack = useXnnpack,
            disableIntraOpSpinning = disableIntraOpSpinning,
            contextCacheFile = contextCacheFile,
        )
        val routeName = when {
            useAccelerator -> HardwareDiscoveryEngine.activeRoute.name
            useXnnpack -> "XNNPACK"
            else -> "CPU"
        }
        return try {
            environment.createSession(modelPath, opts).also {
                providerSink(
                    when {
                        !useAccelerator && !useXnnpack -> "cpu"
                        HardwareDiscoveryEngine.activeRoute ==
                            HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP -> "qnn_htp"
                        HardwareDiscoveryEngine.activeRoute == HardwareDiscoveryEngine.HardwareRoute.NNAPI -> "nnapi"
                        else -> "cpu"
                    },
                )
            }
        } catch (error: Throwable) {
            try {
                opts.close()
            } catch (_: Throwable) {}
            logcat(LogPriority.WARN, error) {
                "Session creation with $routeName failed (model likely not fully partitionable on this EP); " +
                    "retrying this model on CPU only (device health is probe-gated; circuit breaker not tripped)"
            }
            val cpuOpts = createSessionOptions(
                useAccelerator = false,
                useXnnpack = false,
                disableIntraOpSpinning = disableIntraOpSpinning,
            )
            try {
                environment.createSession(modelPath, cpuOpts).also { providerSink("cpu") }
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
     * Dedicated strict QNN HTP session options. CPU fallback is disabled so a
     * broken HTP backend or an unpartitionable graph throws at session
     * creation instead of silently degrading to the CPU EP — with these
     * options, a created session truly executes on the NPU. When
     * [contextCacheFile] is provided, ORT persists the compiled HTP context
     * binary there and reloads it on later runs, so the multi-second graph
     * finalization is paid once per install.
     *
     * A failed QNN registration propagates to the caller (options are closed
     * first) so a CPU session can never be created here and masquerade as QNN;
     * callers decide their own fallback and circuit-breaker policy.
     */
    fun createQnnHtpSessionOptions(
        contextCacheFile: File? = null,
        strictCpuFallbackDisabled: Boolean = true,
        configure: (OrtSession.SessionOptions) -> Unit = {},
    ): OrtSession.SessionOptions {
        logcat(LogPriority.INFO) { "ONNX session options using Qualcomm QNN HTP NPU provider (contextCacheFile=$contextCacheFile)" }
        val options = OrtSession.SessionOptions()
        try {
            options.apply {
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

                val qnnOptions = buildQnnProviderOptions()
                addQnn(qnnOptions)
                if (strictCpuFallbackDisabled) {
                    addConfigEntry("session.disable_cpu_ep_fallback", "1")
                }
                if (contextCacheFile != null) {
                    contextCacheFile.parentFile?.mkdirs()
                    addConfigEntry("ep.context_enable", "1")
                    addConfigEntry("ep.context_file_path", contextCacheFile.absolutePath)
                }
                logcat(LogPriority.INFO) {
                    "Successfully added QNN HTP EP with options: $qnnOptions " +
                        "strictCpuFallbackDisabled=$strictCpuFallbackDisabled contextCacheFile=$contextCacheFile"
                }
                configure(this)
            }
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

    /**
     * Dedicated options for the fixed AOT baseline. XNNPACK is preferred but NOT
     * required: the onnxruntime-android-qnn artifact does not compile XNNPACK,
     * so a fatal requirement would break all AOT inpainting on that build. A
     * registration failure logs loudly and falls back to the default CPU EP;
     * [registrationSink] reports whether XNNPACK actually registered so callers
     * can label their route honestly.
     */
    fun createRequiredXnnpackSessionOptions(
        registrationSink: (Boolean) -> Unit = {},
    ): OrtSession.SessionOptions =
        configureOwnedAotOptions { options ->
            try {
                options.addXnnpack(java.util.HashMap<String, String>())
                registrationSink(true)
                logcat(LogPriority.INFO) { "ONNX fixed AOT options provider=XNNPACK registered=true" }
            } catch (error: Throwable) {
                registrationSink(false)
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
        contextCacheFile: File? = null,
        configure: (OrtSession.SessionOptions) -> Unit = {},
    ): OrtSession.SessionOptions {
        val route = when {
            useAccelerator -> HardwareDiscoveryEngine.resolveRoute()
            useXnnpack -> HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK
            else -> null
        }

        when (route) {
            HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP ->
                logcat(LogPriority.INFO) { "ONNX session options using Qualcomm QNN HTP NPU provider (strict)" }
            HardwareDiscoveryEngine.HardwareRoute.NNAPI ->
                logcat(LogPriority.INFO) { "ONNX session options using Hardware Accelerator (NNAPI, strict)" }
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
                        addQnn(buildQnnProviderOptions())
                        // A QNN device failure at session creation is non-fatal in
                        // ORT and silently assigns all nodes to the CPU EP; strict
                        // mode makes it throw so this route label stays truthful.
                        addConfigEntry("session.disable_cpu_ep_fallback", "1")
                        if (contextCacheFile != null) {
                            contextCacheFile.parentFile?.mkdirs()
                            addConfigEntry("ep.context_enable", "1")
                            addConfigEntry("ep.context_file_path", contextCacheFile.absolutePath)
                        }
                        logcat(LogPriority.INFO) { "Successfully added QNN HTP EP (strict)" }
                    }.onFailure { e ->
                        logcat(LogPriority.ERROR, e) { "Failed to add QNN HTP EP, falling back to CPU!" }
                        HardwareDiscoveryEngine.tripCircuitBreaker("qnn_htp_add_failed", e)
                    }
                }
                HardwareDiscoveryEngine.HardwareRoute.NNAPI -> {
                    runCatching {
                        addConfigEntry("session.disable_cpu_ep_fallback", "1")
                        addNnapi()
                        logcat(LogPriority.INFO) { "Successfully added NNAPI EP (strict)" }
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
                        logcat(LogPriority.ERROR, e) { "Failed to add XNNPACK EP!" }
                    }
                }
                null -> {}
            }
            configure(this)
        }
    }
}
