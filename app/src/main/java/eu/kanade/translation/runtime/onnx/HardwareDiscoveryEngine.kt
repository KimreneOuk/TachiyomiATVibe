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
 * Resolves the optimal inference execution provider (QNN HTP NPU, NNAPI, or CPU XNNPACK)
 * during cold boot initialization and latches the decision in a volatile field.
 * Live page translations incur 0.0ms overhead on subsequent calls.
 *
 * If any hardware accelerator encounters fatal driver errors or compile failures during a
 * session, [tripCircuitBreaker] permanently latches [HardwareRoute.CPU_XNNPACK] for the
 * remaining process lifetime.
 */
object HardwareDiscoveryEngine {

    enum class HardwareRoute {
        QUALCOMM_QNN_HTP,
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
            } else {
                logcat(LogPriority.WARN) { "[hardware_discovery] QNN HTP probe failed; fallback latched CPU_XNNPACK" }
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
            TranslationHardwareAccelerator.AUTO, null -> evaluateRoute()
            else -> evaluateRoute()
        }
    }

    private fun probeQnnHtp(): Boolean {
        var opts: ai.onnxruntime.OrtSession.SessionOptions? = null
        return try {
            opts = ai.onnxruntime.OrtSession.SessionOptions()
            val qnnOptions = buildMap {
                put("backend_type", "htp")
                put("htp_performance_mode", "burst")
                put("htp_graph_finalization_optimization_mode", "3")
                DeviceCapability.qnnSocModel?.let { put("soc_model", it) }
            }
            opts.addQnn(qnnOptions)
            logcat(LogPriority.INFO) { "[hardware_discovery] QNN HTP probe registered OK" }
            true
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "[hardware_discovery] QNN HTP probe failed: ${t.message}" }
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
            opts.addNnapi()
            logcat(LogPriority.INFO) { "[hardware_discovery] NNAPI probe registered OK" }
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
}
