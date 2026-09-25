package eu.kanade.translation.runtime.onnx

import ai.onnxruntime.OrtSession
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.PaddleOcrExecutionProvider

internal val PaddleOcrExecutionProvider.wireLabel: String
    get() = when (this) {
        PaddleOcrExecutionProvider.CPU -> "cpu"
        PaddleOcrExecutionProvider.QUALCOMM_QNN_GPU -> "qnn_gpu"
        PaddleOcrExecutionProvider.QUALCOMM_QNN_HTP -> "qnn_htp"
    }

internal val HardwareDiscoveryEngine.HardwareRoute.wireLabel: String
    get() = when (this) {
        HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP -> "qnn_htp"
        HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_GPU -> "qnn_gpu"
        HardwareDiscoveryEngine.HardwareRoute.NNAPI -> "nnapi"
        HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK -> "cpu"
    }

/**
 * The resolved provider choice shared by the PaddleOCR v6 recognizer and its
 * line detector. Resolution is done once per recognition-engine lifetime so
 * the pair cannot accidentally benchmark different providers.
 */
data class PaddleOcrProviderResolution(
    val requested: PaddleOcrExecutionProvider,
    val route: HardwareDiscoveryEngine.HardwareRoute,
    val fallbackReason: String? = null,
) {
    val requestedWireLabel: String
        get() = requested.wireLabel

    val resolvedRouteLabel: String
        get() = route.wireLabel

    val usedProbeFallback: Boolean
        get() = fallbackReason != null

    companion object {
        fun cpu(): PaddleOcrProviderResolution = PaddleOcrProviderResolution(
            requested = PaddleOcrExecutionProvider.CPU,
            route = HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK,
        )
    }
}

/**
 * Explicit PaddleOCR-only provider policy.
 *
 * This factory deliberately bypasses [HardwareDiscoveryEngine.activeRoute] and
 * [ModelRoutingEngine]. It probes the selected QNN backend, creates strict
 * no-CPU-fallback accelerator options, and retries a failed accelerator session
 * on a separately built CPU session. Every retry is labelled by the provider
 * that actually opened the session, so a failed GPU/HTP attempt cannot be
 * reported as an accelerator success.
 */
object PaddleOcrSessionFactory {

    fun resolve(
        requested: PaddleOcrExecutionProvider,
    ): PaddleOcrProviderResolution {
        if (requested == PaddleOcrExecutionProvider.CPU) {
            logcat(LogPriority.INFO) {
                "[paddle_provider] requested=cpu resolved=cpu probe=skipped"
            }
            return PaddleOcrProviderResolution.cpu()
        }

        val route = HardwareDiscoveryEngine.resolvePaddleOcrRoute(requested)
        if (route == null) {
            val reason = "${requested.wireLabel}_probe_failed"
            logcat(LogPriority.WARN) {
                "[paddle_provider] requested=${requested.wireLabel} resolved=cpu " +
                    "status=unavailable reason=$reason"
            }
            return PaddleOcrProviderResolution(
                requested = requested,
                route = HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK,
                fallbackReason = reason,
            )
        }

        logcat(LogPriority.INFO) {
            "[paddle_provider] requested=${requested.wireLabel} resolved=${route.wireLabel} " +
                "status=probe_ok"
        }
        return PaddleOcrProviderResolution(requested = requested, route = route)
    }

    /**
     * Creates one PaddleOCR detector or recognizer session using the shared
     * resolved choice. Accelerator options remain strict; CPU is only built as
     * an explicit fallback after the accelerator probe/registration/session
     * attempt fails.
     */
    fun createSession(
        modelPath: String,
        resolution: PaddleOcrProviderResolution,
        configure: (OrtSession.SessionOptions) -> Unit = {},
        providerSink: (String) -> Unit = {},
    ): OrtSession {
        val acceleratorRoute = resolution.route.takeIf { it.isPaddleAccelerator() }
        val canUseAccelerator = acceleratorRoute != null
        if (resolution.fallbackReason != null) {
            logcat(LogPriority.WARN) {
                "[paddle_provider] model=$modelPath requested=${resolution.requestedWireLabel} " +
                    "attempt=cpu reason=${resolution.fallbackReason}"
            }
        }

        return OnnxRuntimeProvider.openSessionWithHonestLabel(
            route = resolution.route,
            canUseAccelerator = canUseAccelerator,
            useXnnpack = false,
            buildRequested = {
                OnnxRuntimeProvider.createSessionOptionsWithRegistration(
                    useAccelerator = canUseAccelerator,
                    useXnnpack = false,
                    routeOverride = acceleratorRoute,
                    tripCircuitBreakerOnRegistrationFailure = false,
                    configure = configure,
                ).let { OnnxRuntimeProvider.ProviderOptionsBuild(it.options, it.registered) }
            },
            buildCpu = {
                OnnxRuntimeProvider.createSessionOptionsWithRegistration(
                    useAccelerator = false,
                    useXnnpack = false,
                    configure = configure,
                ).let { OnnxRuntimeProvider.ProviderOptionsBuild(it.options, it.registered) }
            },
            open = { options -> OnnxRuntimeProvider.environment.createSession(modelPath, options) },
            closeOptions = { options -> options.close() },
            sink = { registered ->
                logcat(LogPriority.INFO) {
                    "[paddle_provider] model=$modelPath requested=${resolution.requestedWireLabel} " +
                        "resolved=${resolution.resolvedRouteLabel} registered=$registered " +
                        "fallback=${resolution.fallbackReason ?: "none"}"
                }
                providerSink(registered)
            },
            // Paddle-only failures must not poison the process-wide model
            // routing map or circuit breaker. The explicit CPU retry is enough
            // for this temporary experiment, and the label above remains the
            // provenance source for the caller.
            recordModelFailure = { error ->
                logcat(LogPriority.WARN, error) {
                    "[paddle_provider] model=$modelPath requested=${resolution.requestedWireLabel} " +
                        "accelerator_session_failed; explicit_cpu_retry"
                }
            },
        )
    }

    private fun HardwareDiscoveryEngine.HardwareRoute.isPaddleAccelerator(): Boolean =
        this == HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_GPU ||
            this == HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP
}
