package eu.kanade.translation.runtime.onnx

import android.os.Build
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.TranslationHardwareAccelerator
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * TachiyomiAT: Hardware Discovery Engine and Session-Scoped Latching Circuit Breaker.
 *
 * Resolves the inference execution provider (QNN HTP NPU, NNAPI, or CPU) during cold
 * boot initialization and latches the decision in a volatile field. Live page
 * translations incur 0.0ms overhead on subsequent calls.
 *
 * Probes are end-to-end: they create a real strict session (CPU fallback disabled)
 * from [QnnProbeModel], because merely registering an execution provider on
 * SessionOptions cannot fail even when the accelerator backend is broken — the
 * failure surfaces (non-fatally, as silent CPU execution) only during session
 * creation. A latched route therefore means the accelerator genuinely engaged.
 *
 * If an accelerator hits fatal registration or runtime driver errors, the circuit
 * breaker permanently latches [HardwareRoute.CPU_XNNPACK] for the process lifetime.
 */
object HardwareDiscoveryEngine {

    enum class HardwareRoute {
        QUALCOMM_QNN_HTP,
        QUALCOMM_QNN_GPU,
        NNAPI,
        CPU_XNNPACK,
    }

    @Volatile
    var activeRoute: HardwareRoute = HardwareRoute.CPU_XNNPACK
        private set

    @Volatile
    var isResolved: Boolean = false
        private set

    @Volatile
    var circuitBreakerTripped: Boolean = false
        private set

    internal var qnnProbeRunner: () -> Boolean = { probeQnnHtp() }
    internal var gpuProbeRunner: () -> Boolean = { probeQnnGpu() }
    internal var nnapiProbeRunner: () -> Boolean = { probeNnapi() }

    /**
     * Resolves the hardware route once. If already resolved, returns immediately with zero overhead.
     */
    fun resolveRoute(): HardwareRoute {
        if (isResolved) return activeRoute
        synchronized(this) {
            if (isResolved) return activeRoute
            activeRoute = discoverRoute()
            isResolved = true
            return activeRoute
        }
    }

    /**
     * Permanently trips the circuit breaker to CPU_XNNPACK for the rest of the session.
     */
    fun tripCircuitBreaker(reason: String, error: Throwable? = null) {
        logcat(LogPriority.WARN, error) {
            "[hardware_discovery] Circuit breaker tripped permanently (reason=$reason); activeRoute latched to CPU_XNNPACK"
        }
        synchronized(this) {
            activeRoute = HardwareRoute.CPU_XNNPACK
            isResolved = true
            circuitBreakerTripped = true
        }
    }

    internal fun resetForTesting(
        route: HardwareRoute? = null,
        customQnnProbe: (() -> Boolean)? = null,
        customNnapiProbe: (() -> Boolean)? = null,
    ) {
        synchronized(this) {
            qnnProbeRunner = customQnnProbe ?: { probeQnnHtp() }
            nnapiProbeRunner = customNnapiProbe ?: { probeNnapi() }
            circuitBreakerTripped = false
            if (route != null) {
                activeRoute = route
                isResolved = true
            } else {
                activeRoute = HardwareRoute.CPU_XNNPACK
                isResolved = false
            }
        }
    }

    internal fun evaluateRoute(
        isEmulator: Boolean = DeviceCapability.isProbablyEmulator,
        sdkInt: Int = Build.VERSION.SDK_INT,
        supportedAbis: Array<String> = Build.SUPPORTED_ABIS ?: emptyArray(),
        isQualcomm: Boolean = DeviceCapability.isQualcommSnapdragon,
        probeQnn: () -> Boolean = qnnProbeRunner,
        probeGpu: () -> Boolean = gpuProbeRunner,
        probeNnapi: () -> Boolean = nnapiProbeRunner,
    ): HardwareRoute {
        if (isEmulator) {
            logcat(LogPriority.INFO) { "[hardware_discovery] Pre-flight: emulator detected -> CPU_XNNPACK" }
            return HardwareRoute.CPU_XNNPACK
        }
        if (sdkInt < 29) {
            logcat(LogPriority.INFO) { "[hardware_discovery] Pre-flight: SDK $sdkInt < 29 -> CPU_XNNPACK" }
            return HardwareRoute.CPU_XNNPACK
        }
        if (supportedAbis.isEmpty() || supportedAbis.none { it.equals("arm64-v8a", ignoreCase = true) }) {
            logcat(LogPriority.INFO) {
                "[hardware_discovery] Pre-flight: ABI not arm64-v8a (${supportedAbis.joinToString()}) -> CPU_XNNPACK"
            }
            return HardwareRoute.CPU_XNNPACK
        }

        return if (isQualcomm) {
            if (probeQnn()) {
                logcat(LogPriority.INFO) { "[hardware_discovery] Latched Qualcomm QNN HTP route" }
                HardwareRoute.QUALCOMM_QNN_HTP
            } else if (probeGpu()) {
                logcat(LogPriority.INFO) { "[hardware_discovery] HTP probe failed; latched Qualcomm QNN GPU route" }
                HardwareRoute.QUALCOMM_QNN_GPU
            } else {
                logcat(LogPriority.WARN) { "[hardware_discovery] QNN HTP & GPU probes failed; fallback latched CPU_XNNPACK" }
                HardwareRoute.CPU_XNNPACK
            }
        } else {
            if (probeNnapi()) {
                logcat(LogPriority.INFO) { "[hardware_discovery] Latched NNAPI route" }
                HardwareRoute.NNAPI
            } else {
                logcat(LogPriority.WARN) { "[hardware_discovery] NNAPI probe failed; fallback latched CPU_XNNPACK" }
                HardwareRoute.CPU_XNNPACK
            }
        }
    }

    private fun discoverRoute(): HardwareRoute {
        val pref = try {
            Injekt.get<TranslationPreferences>()
                .translationHardwareAccelerator()
                .get()
        } catch (_: Throwable) {
            TranslationHardwareAccelerator.AUTO
        }
        return when (pref) {
            TranslationHardwareAccelerator.CPU_XNNPACK -> {
                logcat(LogPriority.INFO) { "[hardware_discovery] User preference forced CPU_XNNPACK" }
                HardwareRoute.CPU_XNNPACK
            }
            TranslationHardwareAccelerator.QUALCOMM_NPU -> {
                if (qnnProbeRunner()) {
                    logcat(LogPriority.INFO) { "[hardware_discovery] User preference forced QUALCOMM_QNN_HTP (probe passed)" }
                    HardwareRoute.QUALCOMM_QNN_HTP
                } else {
                    logcat(LogPriority.WARN) { "[hardware_discovery] Forced QUALCOMM_NPU probe failed; falling back to CPU_XNNPACK" }
                    HardwareRoute.CPU_XNNPACK
                }
            }
            TranslationHardwareAccelerator.NNAPI -> {
                if (nnapiProbeRunner()) {
                    logcat(LogPriority.INFO) { "[hardware_discovery] User preference forced NNAPI (probe passed)" }
                    HardwareRoute.NNAPI
                } else {
                    logcat(LogPriority.WARN) { "[hardware_discovery] Forced NNAPI probe failed; falling back to CPU_XNNPACK" }
                    HardwareRoute.CPU_XNNPACK
                }
            }
            TranslationHardwareAccelerator.AUTO -> evaluateRoute()
        }
    }

    private fun probeQnnHtp(): Boolean {
        for ((soc, arch) in qnnProbeCombos(DeviceCapability.qnnSocModel, DeviceCapability.qnnHtpArch)) {
            var opts: ai.onnxruntime.OrtSession.SessionOptions? = null
            val qnnOpts = OnnxRuntimeProvider.buildGenericHtpOptions(socModel = soc, htpArch = arch)
            try {
                opts = ai.onnxruntime.OrtSession.SessionOptions()
                opts.addQnn(qnnOpts)
                opts.addConfigEntry("session.disable_cpu_ep_fallback", "1")
                OnnxRuntimeProvider.environment.createSession(QnnProbeModel.MODEL_BYTES, opts).use { session ->
                    logcat(LogPriority.INFO) {
                        "[hardware_discovery] QNN HTP probe OK (options=$qnnOpts): " +
                            "strict session created end-to-end inputs=${session.inputNames}"
                    }
                }
                return true
            } catch (t: Throwable) {
                logcat(LogPriority.WARN, t) {
                    "[hardware_discovery] QNN HTP probe combo failed (options=$qnnOpts): ${t.message}"
                }
            } finally {
                try {
                    opts?.close()
                } catch (_: Throwable) {}
            }
        }
        logcat(LogPriority.WARN) { "[hardware_discovery] All QNN HTP probe combos failed; HTP unusable with this device/runtime combination" }
        return false
    }

    private fun probeQnnGpu(): Boolean {
        var opts: ai.onnxruntime.OrtSession.SessionOptions? = null
        return try {
            opts = ai.onnxruntime.OrtSession.SessionOptions()
            opts.addQnn(mapOf("backend_type" to "gpu"))
            opts.addConfigEntry("session.disable_cpu_ep_fallback", "1")
            OnnxRuntimeProvider.environment.createSession(QnnProbeModel.MODEL_BYTES, opts).use { session ->
                logcat(LogPriority.INFO) {
                    "[hardware_discovery] QNN GPU probe OK: strict session created end-to-end inputs=${session.inputNames}"
                }
            }
            true
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "[hardware_discovery] QNN GPU probe failed: ${t.message}" }
            false
        } finally {
            try {
                opts?.close()
            } catch (_: Throwable) {}
        }
    }

    private fun probeNnapi(): Boolean {
        var opts: ai.onnxruntime.OrtSession.SessionOptions? = null
        return try {
            opts = ai.onnxruntime.OrtSession.SessionOptions()
            opts.addConfigEntry("session.disable_cpu_ep_fallback", "1")
            opts.addNnapi()
            OnnxRuntimeProvider.environment.createSession(QnnProbeModel.MODEL_BYTES, opts).use {
                logcat(LogPriority.INFO) { "[hardware_discovery] NNAPI probe OK: strict session created end-to-end" }
            }
            true
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "[hardware_discovery] NNAPI probe failed: ${t.message}" }
            false
        } finally {
            try {
                opts?.close()
            } catch (_: Throwable) {}
        }
    }

    internal fun qnnProbeCombos(socModel: String?, htpArch: String?): List<Pair<String?, String?>> = buildList {
        // 1. Generic defaults FIRST: backend_type=htp only, letting QNN negotiate automatically
        add(null to null)
        // 2. Explicit fallbacks if device capability has quirks
        if (socModel != null && htpArch != null) add(socModel to htpArch)
        if (socModel != null) add(socModel to null)
    }
}
