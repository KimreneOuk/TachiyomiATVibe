package eu.kanade.translation.runtime.onnx

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object OnnxRuntimeProvider {

    val environment: OrtEnvironment by lazy {
        logcat { "Creating ONNX Runtime environment" }
        OrtEnvironment.getEnvironment()
    }

    /**
     * TachiyomiAT: the EP strategy resolved from the translation_onnx_ep /
     * translation_experimental_qnn preferences, computed once and cached for
     * the process. Read here (rather than at each session-creation site) so the
     * preference is consulted exactly once and so the experimental QNN flag
     * overrides cleanly. The cache is @Volatile so a future "reset and re-read"
     * path stays correct without a lock; in practice the pref is not toggled
     * mid-session.
     */
    @Volatile
    private var strategyInitialized: Boolean = false

    @Volatile
    private var cachedStrategy: DeviceCapability.EpStrategy = DeviceCapability.EpStrategy.CPU

    /** Resolve and cache the configured EP strategy, honoring the experimental QNN flag. */
    fun resolveStrategy(): DeviceCapability.EpStrategy {
        if (strategyInitialized) return cachedStrategy
        cachedStrategy = try {
            val prefs = Injekt.get<TranslationPreferences>()
            when (prefs.translationExperimentalQnn().get()) {
                true -> {
                    if (DeviceCapability.supportsQnnCandidate()) {
                        DeviceCapability.EpStrategy.QNN
                    } else {
                        DeviceCapability.defaultStrategy().also {
                            logcat(LogPriority.WARN) {
                                "Experimental QNN requested but device is not a QNN candidate " +
                                    "(${DeviceCapability.describe()}); using default strategy"
                            }
                        }
                    }
                }
                false -> when (prefs.translationOnnxEp().get().uppercase()) {
                    "NNAPI" -> DeviceCapability.EpStrategy.NNAPI
                    "CPU" -> DeviceCapability.EpStrategy.CPU
                    else -> DeviceCapability.defaultStrategy() // "AUTO" and any unknown
                }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.WARN) { "Could not read ONNX EP preference; defaulting to CPU: ${e.message}" }
            DeviceCapability.EpStrategy.CPU
        }
        strategyInitialized = true
        logcat(LogPriority.INFO) {
            "ONNX EP strategy resolved: $cachedStrategy | ${DeviceCapability.describe()}"
        }
        return cachedStrategy
    }

    /**
     * TachiyomiAT: runtime latch set to true the first time a non-CPU execution
     * provider (NNAPI/QNN) fails to initialize a session. Once set, all
     * subsequent [createSessionOptions] calls fall back to CPU-only options — a
     * device whose NNAPI driver is broken will fail repeatedly otherwise, and a
     * SIGSEGV from the driver mid-inference is unrecoverable. The latch is
     * process-scoped (not persisted) because a driver that works on one boot
     * may not on the next; re-evaluating per process is the safe granularity.
     */
    @Volatile
    private var acceleratedEpDisabled: Boolean = false

    /** Record that an accelerated EP failed, forcing subsequent sessions to CPU. */
    fun markAcceleratedEpFailed() {
        if (!acceleratedEpDisabled) {
            acceleratedEpDisabled = true
            logcat(LogPriority.WARN) {
                "Accelerated EP failed; subsequent ONNX sessions will use CPU only for this process"
            }
        }
    }

    /** Whether accelerated EPs are still eligible (not disabled by a prior failure). */
    fun acceleratedEpAvailable(): Boolean = !acceleratedEpDisabled

    /**
     * Build ONNX session options for the given [strategy].
     *
     * Thread count stays hardcoded at 2: increasing beyond 2 caused SIGSEGV on
     * certain devices/SoCs in earlier builds, and the accelerated EPs (when
     * active) offload the heavy work to the accelerator anyway so CPU thread
     * count matters less. The 2-thread setting is intentionally conservative.
     *
     * EP registration is wrapped defensively: if NNAPI registration throws
     * (broken vendor driver, unsupported model operators), we log, call
     * [markAcceleratedEpFailed], and return plain CPU options so the caller
     * still gets a working session. Callers should NOT additionally wrap the
     * returned options in try/catch — the EP failure is handled here.
     *
     * The [strategy] is honored as a hint; if accelerated EPs have been
     * disabled by a prior failure, CPU is used regardless.
     *
     * TachiyomiAT (memory): the CPU memory arena and memory-pattern optimizer
     * are DISABLED here. With them ON (the ORT default), every session
     * pre-allocates an arena sized to its largest-seen tensor workspace and
     * HOLDS it for the session lifetime — the ORT maintainers document this
     * as the single largest ORT-side native-memory consumer on Android
     * (microsoft/onnxruntime#11627). This app creates up to SIX concurrent
     * sessions (detector + MangaOcr encoder/decoder_init/decoder_step + AOT
     * inpainting + its lazily-created CPU fallback), so six arenas compound
     * into hundreds of MB of resident native heap that never gets released
     * back to the system even after every page's inference completes. That
     * resident native footprint is invisible to the Java GC but DOES count
     * against the device's physical RAM, which trips the
     * `ActivityManager.lowMemory` / `availMem < threshold` gates in
     * `TranslationMemoryBudget.canStartAnalyze`/`canStartInpaint` — so a
     * chapter that should have heap headroom gets deferred as "low memory"
     * purely because the ORT arenas are squatting on the device's RAM. The
     * 200-page pre-translation OOM investigation (June 2026) traced the
     * runaway native pressure back here.
     *
     * Turning both OFF trades a modest per-inference CPU cost (the arena
     * also serves as a free-list, so without it each inference goes through
     * malloc/free) for a dramatically lower resident footprint — the correct
     * trade-off for the 6GB / 20-30% heap target device class. If a future
     * high-RAM device class needs the arena for speed, gate this on
     * DeviceCapability rather than re-enabling globally. Verified via the
     * ORT 1.21 Java API: setCPUArenaAllocator(false) +
     * setMemoryPatternOptimization(false) are the type-safe equivalents of
     * the string config keys "session.enable_cpu_mem_arena"="0" and
     * "session.enable_mem_pattern"="0"; the type-safe methods are preferred
     * because a typo in addConfigEntry silently no-ops, whereas these throw
     * at session creation if unsupported (which they are not in 1.21).
     */
    fun createSessionOptions(
        strategy: DeviceCapability.EpStrategy = resolveStrategy(),
        forceCpu: Boolean = false,
        configure: (OrtSession.SessionOptions) -> Unit = {},
    ): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setInterOpNumThreads(2)
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // See the doc comment on createSessionOptions: disabled to bound
            // resident native memory across the six concurrent ONNX sessions
            // this app holds. Each call to createSessionOptions goes through
            // this funnel, so every session (including the AOT cpuFallback)
            // gets the same low-footprint configuration.
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

            val effectiveStrategy = when {
                // TachiyomiAT: callers that run autoregressive / tiny-op models
                // (manga-ocr decoder) pass forceCpu=true. The NPU is a poor fit
                // there: each decode step is a small graphlet, and NNAPI
                // partitions it into dozens of segments with a CPU<->accelerator
                // sync point on every boundary. On the decoder that loop runs up
                // to 300 times per ROI; the partitioning overhead can blow up
                // memory and destabilize sessions. So the decoder (and its
                // encoder sibling, which is cheap on CPU) must stay on CPU. The
                // AOT inpainting model is the one that benefits from the
                // accelerator (single big generative pass).
                forceCpu -> DeviceCapability.EpStrategy.CPU
                acceleratedEpDisabled -> DeviceCapability.EpStrategy.CPU
                else -> strategy
            }
            when (effectiveStrategy) {
                DeviceCapability.EpStrategy.NNAPI -> registerNnapiSafely(this)
                DeviceCapability.EpStrategy.QNN -> registerQnnSafely(this)
                DeviceCapability.EpStrategy.CPU -> { /* CPU-only, no EP registration */ }
            }
            configure(this)
        }
    }

    /**
     * Register the NNAPI Execution Provider on [options], swallowing any error.
     * NNAPI is built into the base onnxruntime-android artifact (no extra dep)
     * and lets Android route ops to the NPU/GPU/DSP via the device's NNAPI
     * driver with automatic CPU fallback for unsupported ops. This is the safe,
     * general-purpose accelerator path.
     */
    private fun registerNnapiSafely(options: OrtSession.SessionOptions) {
        try {
            // OrtSession.SessionOptions.addNnapi(EnumSet<NNAPIFlags>) in ORT 1.21.
            // An empty EnumSet means "no flags" — the NNAPI driver picks its own
            // defaults (CPU fallback for unsupported ops, etc.), which is the safe
            // general-purpose configuration for our fp32/fp16 ONNX models.
            //
            // We deliberately do NOT set NNAPI_FLAG_USES_FP16: our manga-ocr /
            // detector / AOT models were not quantized for NNAPI, and forcing
            // fp16 on them can silently corrupt output on some drivers. The
            // empty-flag default lets the driver decide per-op.
            options.addNnapi(java.util.EnumSet.noneOf(NNAPIFlags::class.java))
            logcat(LogPriority.INFO) { "ONNX session using NNAPI execution provider" }
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) {
                "NNAPI EP registration failed (likely unsupported/broken driver); falling back to CPU"
            }
            markAcceleratedEpFailed()
            // options remains CPU-only — no action needed, the EP simply wasn't added.
        }
    }

    /**
     * Register the QNN Execution Provider (Qualcomm HTP/NPU). This is a NO-OP
     * scaffold: QNN requires (a) the onnxruntime-android-qnn artifact (not a
     * dependency today — it would replace onnxruntime-android) and (b) the
     * models converted to QNN-quantized format via Qualcomm's qnn-onnx-
     * converter. Feeding unconverted fp32/fp16 ONNX to QNN/HTP is undefined
     * behavior and can crash the NPU until a device reboot.
     *
     * Until both prerequisites are met, this method only logs that QNN was
     * requested but unavailable, and leaves the session on CPU. The method
     * exists so a future cycle can wire it without touching call sites — they
     * already pass EpStrategy.QNN when the experimental pref is on.
     */
    private fun registerQnnSafely(options: OrtSession.SessionOptions) {
        logcat(LogPriority.WARN) {
            "QNN EP requested but not wired (requires onnxruntime-android-qnn artifact + " +
                "QNN-quantized models). Session will run on CPU. See DeviceCapability/TranslationPreferences."
        }
        markAcceleratedEpFailed()
        // Intentionally not calling options.addQNN("QnnHtp", ...) — the artifact
        // and converted models are prerequisites. Left as the integration point.
    }
}
