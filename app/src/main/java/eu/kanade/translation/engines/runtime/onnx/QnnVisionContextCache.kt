package eu.kanade.translation.engines.runtime.onnx

import android.app.Application
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Bridges [QnnContextCacheManager] into the vision engines' QNN sessions
 * (panel detector, bubble segmenter) using the same staged protocol the AOT
 * inpaint coordinator follows: warm loads pass the promoted wrapper as
 * ep.context_file_path, cold loads compile into a private staging entry that
 * is promoted only after the session was actually served by QNN.
 */
internal object QnnVisionContextCache {

    internal enum class Mode { WARM, COLD }

    class Plan internal constructor(
        private val mode: Mode,
        /** File to pass as ep.context_file_path into the accelerator session options. */
        val contextFile: File,
        private val modelFile: File,
        private val qnnOptions: Map<String, String>,
        private val qnnWireLabel: String,
    ) {
        /**
         * Called with the wire label of the provider that actually served the
         * session. A warm plan served by anything other than QNN means the
         * cache entry could not load — purge it so the next init recompiles.
         * A cold plan always attempts staging promotion, which validates the
         * generated pair and self-cleans when no context was written.
         */
        fun onSessionCreated(actualProviderWire: String) {
            runCatching {
                val app = appContext() ?: return
                when (mode) {
                    Mode.WARM -> if (actualProviderWire != qnnWireLabel) {
                        logcat(LogPriority.WARN) {
                            "[qnn_cache] session for ${modelFile.name} served by '$actualProviderWire'; " +
                                "purging context cache entry (corrupt or unloadable)"
                        }
                        QnnContextCacheManager.invalidate(app, modelFile, qnnOptions)
                    }
                    Mode.COLD -> QnnContextCacheManager.commitStaging(app, modelFile, qnnOptions)
                }
            }.onFailure { e ->
                logcat(LogPriority.WARN, e) { "[qnn_cache] post-commit check failed for ${modelFile.name}" }
            }
        }
    }

    /**
     * Mirror of [OnnxRuntimeProvider.createSessionWithFallback]'s route
     * resolution and accelerator gating: the plan must describe the options
     * the provider will actually register, because those options are part of
     * the cache key. Returns null whenever the session cannot attempt QNN.
     */
    fun plan(
        modelFile: File,
        useAccelerator: Boolean,
        requestedHardwareRoute: HardwareDiscoveryEngine.HardwareRoute?,
    ): Plan? {
        val effectiveAccelerator = useAccelerator ||
            (requestedHardwareRoute != null &&
                requestedHardwareRoute != HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK)
        if (!effectiveAccelerator) return null
        val route = requestedHardwareRoute ?: if (useAccelerator) {
            HardwareDiscoveryEngine.resolveRoute()
        } else {
            HardwareDiscoveryEngine.activeRoute
        }
        if (route == HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK) return null
        // Must stay in lockstep with the option maps registered by
        // createSessionOptionsWithRegistration for the matching route.
        val qnnOptions: Map<String, String>
        val wireLabel: String
        when (route) {
            HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_GPU -> {
                qnnOptions = mapOf("backend_type" to "gpu")
                wireLabel = OnnxRuntimeProvider.RegisteredExecutionProvider.QNN_GPU.wireLabel
            }
            HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP -> {
                qnnOptions = OnnxRuntimeProvider.buildQnnProviderOptions()
                wireLabel = OnnxRuntimeProvider.RegisteredExecutionProvider.QNN_HTP.wireLabel
            }
            else -> return null
        }
        val modelName = ModelRoutingEngine.resolveModelId(modelFile.absolutePath)
        if (!ModelRoutingEngine.isAcceleratorAttemptAllowed(modelName, route)) return null
        val app = appContext() ?: return null
        val warm = QnnContextCacheManager.getValidCachedModel(app, modelFile, qnnOptions)
        return if (warm != null) {
            Plan(Mode.WARM, warm, modelFile, qnnOptions, wireLabel)
        } else {
            val staging = QnnContextCacheManager.prepareStaging(app, modelFile, qnnOptions)
            Plan(Mode.COLD, staging, modelFile, qnnOptions, wireLabel)
        }
    }

    private fun appContext(): Application? = runCatching { Injekt.get<Application>() }.getOrNull()
}
