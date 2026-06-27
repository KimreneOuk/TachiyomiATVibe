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
     * Translation ONNX sessions are CPU-only.
     *
     * Stale `translation_onnx_ep` / `translation_experimental_qnn` preferences
     * are intentionally ignored. NNAPI only partitioned a tiny fraction of the
     * AOT graph on tested devices while adding native-driver instability. Future
     * NPU work should be a dedicated Qualcomm QNN/QAIRT backend with converted
     * models, not an automatic fallback inside this CPU path.
     */
    fun resolveStrategy(): DeviceCapability.EpStrategy {
        logcat(LogPriority.INFO) {
            "ONNX EP strategy resolved: CPU-only translation runtime | ${DeviceCapability.describe()}"
        }
        return DeviceCapability.EpStrategy.CPU
    }

    /**
     * Build low-footprint CPU ONNX session options.
     *
     * The CPU memory arena and memory-pattern optimizer stay disabled to bound
     * resident native memory across the detector, OCR, and AOT sessions. Thread
     * count is derived from available CPU cores (half, capped at 4) because
     * higher values have caused device-specific crashes in this codebase — this
     * heuristic needs on-device validation for new hardware.
     */
    fun createSessionOptions(
        forceCpu: Boolean = false,
        configure: (OrtSession.SessionOptions) -> Unit = {},
    ): OrtSession.SessionOptions {
        if (forceCpu) {
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
            configure(this)
        }
    }
}
