package eu.kanade.translation.runtime.onnx

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

object OnnxRuntimeProvider {

    val environment: OrtEnvironment by lazy {
        logcat { "Creating ONNX Runtime environment" }
        val environment = OrtEnvironment.getEnvironment()
        runCatching {
            logcat(LogPriority.INFO) {
                "[onnx_runtime] compiledProviders=${OrtEnvironment.getAvailableProviders().joinToString(",") { it.getName() }}"
            }
        }.onFailure { error ->
            logcat(LogPriority.WARN, error) { "[onnx_runtime] provider query failed" }
        }
        environment
    }

    /**
     *  Phase 5 (plan §3.4): typed execution-provider registration result.
     * Reported by [createSessionOptionsWithRegistration] from what actually
     * registered on the built options — never from
     * [HardwareDiscoveryEngine.activeRoute], which is device preference only.
     * The wire label (`providerSink` value) is the lowercase [wireLabel].
     */
    enum class RegisteredExecutionProvider { QNN_HTP, QNN_GPU, NNAPI, XNNPACK, CPU }

    /** Lowercase wire label used by `providerSink` and per-engine route logging. */
    val RegisteredExecutionProvider.wireLabel: String
        get() = when (this) {
            RegisteredExecutionProvider.QNN_HTP -> "qnn_htp"
            RegisteredExecutionProvider.QNN_GPU -> "qnn_gpu"
            RegisteredExecutionProvider.NNAPI -> "nnapi"
            RegisteredExecutionProvider.XNNPACK -> "xnnpack"
            RegisteredExecutionProvider.CPU -> "cpu"
        }

    /**
     * Session options together with the execution provider that actually
     * registered on them. [registered] is a registration fact only — it is
     * NOT execution proof (plan amendment §10.8); provenance requires
     * [ModelRoutingEngine.recordSuccessfulInference] after a real run.
     */
    class SessionOptionsWithRegistration(
        val options: OrtSession.SessionOptions,
        val registered: RegisteredExecutionProvider,
    )

    /**
     *  Phase 5 (plan §3.4): a STRICT accelerator execution provider failed
     * to register while building session options. The options are already
     * closed by the builder when this is thrown; [OnnxRuntimeProvider.createSessionWithFallback]
     * owns the CPU retry so a default-CPU session can never masquerade as the
     * requested accelerator.
     */
    class AcceleratorRegistrationException(
        val route: HardwareDiscoveryEngine.HardwareRoute,
        cause: Throwable,
    ) : RuntimeException("Execution provider registration failed for route $route", cause)


    /**
     * Generic production QNN HTP provider options.
     * By default, only backend_type="htp" is specified, allowing ORT and QNN runtime
     * to automatically query the device's DSP capabilities and negotiate optimal settings
     * without hardcoding SoC or HTP architecture assumptions.
     *
     * Model-specific profiles or benchmark overrides can pass explicit options
     * (e.g. performanceMode, socModel, htpArch) when empirically justified.
     */
    internal fun buildGenericHtpOptions(
        performanceMode: String? = null,
        socModel: String? = null,
        htpArch: String? = null,
        extraOptions: Map<String, String> = emptyMap(),
    ): Map<String, String> = buildMap {
        put("backend_type", "htp")
        performanceMode?.let { put("htp_performance_mode", it) }
        socModel?.let { put("soc_model", it) }
        htpArch?.let { put("htp_arch", it) }
        putAll(extraOptions)
    }

    internal fun buildQnnProviderOptions(
        socModel: String? = null,
        htpArch: String? = null,
    ): Map<String, String> = buildGenericHtpOptions(socModel = socModel, htpArch = htpArch)

    /**
     *  Phase 5 (plan §3.4): typed options build used by the generic
     * orchestration core; [OnnxRuntimeProvider.SessionOptionsWithRegistration]
     * is its native-typed form.
     */
    internal class ProviderOptionsBuild<O>(
        val options: O,
        val registered: RegisteredExecutionProvider,
    )

    /**
     *  Phase 5 (plan §3.4): pure, ORT-free core of the
     * [createSessionWithFallback] main path, generic over the concrete
     * option/session types so JVM provenance tests can drive it with inert
     * tokens (ai.onnxruntime classes refuse to initialize off-device — their
     * static initializers load the native library — so the native-typed
     * surface itself is not JVM-drivable).
     *
     * Contract executed here verbatim by [createSessionWithFallback]:
     * - a strict accelerator registration failure thrown by [buildRequested]
     *   is device-level: the CPU retry happens here, the model routing state
     *   is untouched (the builder already logged + tripped the circuit
     *   breaker), and the sink label is the CPU registration result;
     * - the sink receives the TYPED registration result of the options that
     *   actually opened — never derived from the device [route] — exactly
     *   once, and ONLY after [open] returned successfully;
     * - an [open] failure records the model failure (accelerator attempts
     *   only, via [recordModelFailure]) and retries exactly once on CPU, even
     *   when the failed attempt was already CPU (pre-existing behavior).
     */
    internal fun <O, S> openSessionWithHonestLabel(
        route: HardwareDiscoveryEngine.HardwareRoute,
        canUseAccelerator: Boolean,
        useXnnpack: Boolean,
        buildRequested: () -> ProviderOptionsBuild<O>,
        buildCpu: () -> ProviderOptionsBuild<O>,
        open: (O) -> S,
        closeOptions: (O) -> Unit,
        sink: (String) -> Unit,
        recordModelFailure: (Throwable) -> Unit,
    ): S {
        val requested = try {
            buildRequested()
        } catch (registrationError: AcceleratorRegistrationException) {
            // A STRICT accelerator registration failure is device-level, not
            // model-level. This provider owns the CPU retry: the session is
            // created on the default CPU EP and labelled cpu — a default-CPU
            // session must never be labelled qnn_htp/qnn_gpu/nnapi/xnnpack.
            logcat(LogPriority.WARN, registrationError) {
                "Strict accelerator registration failed for route ${registrationError.route}; " +
                    "creating this session on default CPU"
            }
            val cpu = buildCpu()
            return try {
                open(cpu.options).also { sink(cpu.registered.wireLabel) }
            } finally {
                closeQuietly(cpu.options, closeOptions)
            }
        }

        // Describes the ATTEMPT in failure logs only — never the sink label.
        val routeName = when {
            canUseAccelerator -> route.name
            useXnnpack -> "XNNPACK"
            else -> "CPU"
        }
        return try {
            open(requested.options).also { sink(requested.registered.wireLabel) }
        } catch (error: Throwable) {
            closeQuietly(requested.options, closeOptions)
            if (canUseAccelerator) {
                recordModelFailure(error)
            }
            logcat(LogPriority.WARN, error) {
                "Session creation with $routeName failed (model likely not fully partitionable on this EP); " +
                    "retrying this model on CPU only (device health is probe-gated; circuit breaker not tripped)"
            }
            val cpu = buildCpu()
            try {
                open(cpu.options).also { sink(cpu.registered.wireLabel) }
            } finally {
                closeQuietly(cpu.options, closeOptions)
            }
        } finally {
            closeQuietly(requested.options, closeOptions)
        }
    }

    private fun <O> closeQuietly(options: O, close: (O) -> Unit) {
        try {
            close(options)
        } catch (_: Throwable) {}
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
     * ("qnn_htp", "qnn_gpu", "nnapi", "xnnpack", or "cpu") for honest
     * per-engine route logging.  Phase 5 (plan §3.4): the label is the
     * TYPED registration result of the options actually used — it NEVER
     * derives from [HardwareDiscoveryEngine.activeRoute] alone — and it is
     * emitted only after `createSession` succeeds. A strict accelerator
     * registration failure is owned here: the retry session is created on the
     * default CPU EP and labelled `cpu`, never the requested accelerator.
     * Session creation proves SESSION_CREATED, not execution (plan §3.3):
     * routing support is marked only by
     * [ModelRoutingEngine.recordSuccessfulInference] after a real run.
     */
    fun createSessionWithFallback(
        modelPath: String,
        useAccelerator: Boolean = false,
        useXnnpack: Boolean = false,
        disableIntraOpSpinning: Boolean = false,
        contextCacheFile: File? = null,
        configure: (OrtSession.SessionOptions) -> Unit = {},
        providerSink: (String) -> Unit = {},
    ): OrtSession {
        val modelName = ModelRoutingEngine.resolveModelId(modelPath)
        // Resolve the user's selected route before consulting model-level
        // compatibility state. Reading activeRoute first leaves the first
        // normal OCR session gated against the CPU default even when the
        // persisted accelerator preference has not yet been probed.
        val route = if (useAccelerator) {
            HardwareDiscoveryEngine.resolveRoute()
        } else {
            HardwareDiscoveryEngine.activeRoute
        }
        //  Phase 5 (plan §3.3): the attempt gate is the coherent
        // accelerator gate — TEMPORARY_FAILURE still owns its one documented
        // recreation attempt (bounded by the SSR retry counter).
        val canUseAccelerator = useAccelerator &&
            route != HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK &&
            ModelRoutingEngine.isAcceleratorAttemptAllowed(modelName, route)

        if (useAccelerator && !canUseAccelerator) {
            logcat(LogPriority.INFO) {
                "[model_routing] Model '$modelName' is known UNSUPPORTED on route $route; bypassing accelerator to CPU"
            }
            val cpuOpts = createSessionOptions(
                useAccelerator = false,
                useXnnpack = false,
                disableIntraOpSpinning = disableIntraOpSpinning,
                configure = configure,
            )
            return try {
                environment.createSession(modelPath, cpuOpts).also { providerSink("cpu") }
            } finally {
                cpuOpts.close()
            }
        }

        return openSessionWithHonestLabel(
            route = route,
            canUseAccelerator = canUseAccelerator,
            useXnnpack = useXnnpack,
            buildRequested = {
                createSessionOptionsWithRegistration(
                    useAccelerator = canUseAccelerator,
                    useXnnpack = useXnnpack,
                    disableIntraOpSpinning = disableIntraOpSpinning,
                    contextCacheFile = contextCacheFile,
                    routeOverride = route.takeIf { canUseAccelerator },
                    configure = configure,
                ).let { ProviderOptionsBuild(it.options, it.registered) }
            },
            buildCpu = {
                createSessionOptionsWithRegistration(
                    useAccelerator = false,
                    useXnnpack = false,
                    disableIntraOpSpinning = disableIntraOpSpinning,
                    configure = configure,
                ).let { ProviderOptionsBuild(it.options, it.registered) }
            },
            open = { opts -> environment.createSession(modelPath, opts) },
            closeOptions = { opts -> opts.close() },
            sink = providerSink,
            recordModelFailure = { error -> ModelRoutingEngine.recordFailure(modelName, route, error) },
        )
    }

    /**
     * Opens a session for one explicit Paddle provider-matrix cell.
     *
     * Unlike [createSessionWithFallback], this method never recreates an
     * accelerator failure on CPU. QNN/NNAPI options already carry
     * `session.disable_cpu_ep_fallback=1`; registration and session creation
     * errors propagate to the cell so a failed provider cannot be reported as
     * a successful accelerator measurement. The CPU cell is intentionally the
     * only cell that requests XNNPACK/default CPU behavior.
     */
    fun createSessionForPaddleProvider(
        modelPath: String,
        configuration: PaddleOcrProviderTestConfiguration,
        providerSink: (String) -> Unit = {},
    ): OrtSession {
        val optionsWithRegistration = createSessionOptionsWithRegistration(
            useAccelerator = configuration.target.isAccelerator,
            useXnnpack = configuration.target == PaddleOcrProviderTarget.CPU,
            routeOverride = configuration.route,
        )
        val options = optionsWithRegistration.options
        val registeredLabel = optionsWithRegistration.registered.wireLabel
        if (configuration.target.isAccelerator && registeredLabel.isCpuLikeProvider()) {
            options.close()
            throw IllegalStateException(
                "Strict Paddle provider cell ${configuration.target} registered CPU provider '$registeredLabel'",
            )
        }
        return try {
            environment.createSession(modelPath, options).also {
                // Registration is recorded here for diagnostics. The matrix
                // runner records execution provenance only after real inference.
                providerSink(registeredLabel)
            }
        } finally {
            options.close()
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
        qnnOptions: Map<String, String> = buildGenericHtpOptions(),
        configure: (OrtSession.SessionOptions) -> Unit = {},
    ): OrtSession.SessionOptions {
        logcat(LogPriority.INFO) { "ONNX session options using Qualcomm QNN HTP NPU provider (contextCacheFile=$contextCacheFile, options=$qnnOptions)" }
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

    /**
     *  Phase 5 (plan §3.4): [createSessionOptions] plus a TYPED
     * registration result describing what actually registered on the built
     * options. Behavior is otherwise byte-identical to the pre-Phase-5
     * builder — same logs, same option code — except:
     * - a STRICT accelerator registration failure (QNN HTP / QNN GPU /
     *   NNAPI) now PROPAGATES as [AcceleratorRegistrationException] (options
     *   closed first) instead of being swallowed into a default-CPU session
     *   that would masquerade as the accelerator — the caller
     *   ([createSessionWithFallback]) owns the CPU retry;
     * - an XNNPACK registration failure explicitly resolves to
     *   [RegisteredExecutionProvider.CPU] (the default CPU EP stays in use)
     *   instead of only being logged.
     */
    fun createSessionOptionsWithRegistration(
        useAccelerator: Boolean = false,
        useXnnpack: Boolean = false,
        disableIntraOpSpinning: Boolean = false,
        contextCacheFile: File? = null,
        routeOverride: HardwareDiscoveryEngine.HardwareRoute? = null,
        tripCircuitBreakerOnRegistrationFailure: Boolean = true,
        configure: (OrtSession.SessionOptions) -> Unit = {},
    ): SessionOptionsWithRegistration {
        val route = routeOverride ?: when {
            useAccelerator -> HardwareDiscoveryEngine.resolveRoute()
            useXnnpack -> HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK
            else -> null
        }

        when (route) {
            HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP ->
                logcat(LogPriority.INFO) { "ONNX session options using Qualcomm QNN HTP NPU provider (strict)" }
            HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_GPU ->
                logcat(LogPriority.INFO) { "ONNX session options using Qualcomm QNN Adreno GPU provider (strict)" }
            HardwareDiscoveryEngine.HardwareRoute.NNAPI ->
                logcat(LogPriority.INFO) { "ONNX session options using Hardware Accelerator (NNAPI, strict)" }
            HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK ->
                logcat(LogPriority.INFO) { "ONNX session options using XNNPACK CPU provider" }
            null ->
                logcat(LogPriority.INFO) { "ONNX session options using CPU execution provider" }
        }

        //  Phase 5 (plan §3.4): strict registration failures propagate to
        // the session creator; XNNPACK failures resolve to CPU.
        var strictRegistrationFailure: Pair<HardwareDiscoveryEngine.HardwareRoute, Throwable>? = null
        var xnnpackRegistrationFailed = false

        val options = OrtSession.SessionOptions().apply {
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
                        if (tripCircuitBreakerOnRegistrationFailure) {
                            HardwareDiscoveryEngine.tripCircuitBreaker("qnn_htp_add_failed", e)
                        }
                        strictRegistrationFailure = HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP to e
                    }
                }
                HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_GPU -> {
                    runCatching {
                        addQnn(mapOf("backend_type" to "gpu"))
                        addConfigEntry("session.disable_cpu_ep_fallback", "1")
                        logcat(LogPriority.INFO) { "Successfully added QNN GPU EP (strict)" }
                    }.onFailure { e ->
                        logcat(LogPriority.ERROR, e) { "Failed to add QNN GPU EP, falling back to CPU!" }
                        if (tripCircuitBreakerOnRegistrationFailure) {
                            HardwareDiscoveryEngine.tripCircuitBreaker("qnn_gpu_add_failed", e)
                        }
                        strictRegistrationFailure = HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_GPU to e
                    }
                }
                HardwareDiscoveryEngine.HardwareRoute.NNAPI -> {
                    runCatching {
                        addConfigEntry("session.disable_cpu_ep_fallback", "1")
                        addNnapi()
                        logcat(LogPriority.INFO) { "Successfully added NNAPI EP (strict)" }
                    }.onFailure { e ->
                        logcat(LogPriority.ERROR, e) { "Failed to add NNAPI EP, falling back to CPU!" }
                        if (tripCircuitBreakerOnRegistrationFailure) {
                            HardwareDiscoveryEngine.tripCircuitBreaker("nnapi_add_failed", e)
                        }
                        strictRegistrationFailure = HardwareDiscoveryEngine.HardwareRoute.NNAPI to e
                    }
                }
                HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK -> {
                    runCatching {
                        addXnnpack(java.util.HashMap<String, String>())
                        logcat(LogPriority.INFO) { "Successfully added XNNPACK EP" }
                    }.onFailure { e ->
                        //  Phase 5 (plan §3.4): the failure no longer stops
                        // at logging — the options keep the default CPU EP and
                        // the reported registration resolves to CPU so the
                        // session is labelled honestly.
                        xnnpackRegistrationFailed = true
                        logcat(LogPriority.ERROR, e) { "Failed to add XNNPACK EP!" }
                    }
                }
                null -> {}
            }
            configure(this)
        }

        strictRegistrationFailure?.let { (failedRoute, error) ->
            try {
                options.close()
            } catch (closeError: Throwable) {
                error.addSuppressed(closeError)
            }
            throw AcceleratorRegistrationException(failedRoute, error)
        }

        val registered = when (route) {
            HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP -> RegisteredExecutionProvider.QNN_HTP
            HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_GPU -> RegisteredExecutionProvider.QNN_GPU
            HardwareDiscoveryEngine.HardwareRoute.NNAPI -> RegisteredExecutionProvider.NNAPI
            HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK ->
                if (xnnpackRegistrationFailed) {
                    RegisteredExecutionProvider.CPU
                } else {
                    RegisteredExecutionProvider.XNNPACK
                }
            null -> RegisteredExecutionProvider.CPU
        }
        return SessionOptionsWithRegistration(options, registered)
    }

    fun createSessionOptions(
        useAccelerator: Boolean = false,
        useXnnpack: Boolean = false,
        disableIntraOpSpinning: Boolean = false,
        contextCacheFile: File? = null,
        routeOverride: HardwareDiscoveryEngine.HardwareRoute? = null,
        tripCircuitBreakerOnRegistrationFailure: Boolean = true,
        configure: (OrtSession.SessionOptions) -> Unit = {},
    ): OrtSession.SessionOptions =
        createSessionOptionsWithRegistration(
            useAccelerator = useAccelerator,
            useXnnpack = useXnnpack,
            disableIntraOpSpinning = disableIntraOpSpinning,
            contextCacheFile = contextCacheFile,
            configure = configure,
            routeOverride = routeOverride,
            tripCircuitBreakerOnRegistrationFailure = tripCircuitBreakerOnRegistrationFailure,
        ).options

    private fun String.isCpuLikeProvider(): Boolean =
        equals("cpu", ignoreCase = true) || equals("uninitialized", ignoreCase = true) || isBlank()
}
