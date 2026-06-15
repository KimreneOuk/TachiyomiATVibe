package eu.kanade.translation.runtime.onnx

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import tachiyomi.core.common.util.system.logcat

object OnnxRuntimeProvider {

    val environment: OrtEnvironment by lazy {
        logcat { "Creating ONNX Runtime environment" }
        OrtEnvironment.getEnvironment()
    }

    fun createSessionOptions(configure: (OrtSession.SessionOptions) -> Unit = {}): OrtSession.SessionOptions {
        // TachiyomiAT: ONNX Runtime on Android is sensitive to thread count —
        // increasing beyond 2 threads (e.g. with Runtime.availableProcessors())
        // can cause SIGSEGV native crashes on certain devices/SoCs. The
        // hardcoded 2 is intentional and safe across all supported ABIs.
        return OrtSession.SessionOptions().apply {
            setInterOpNumThreads(2)
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            configure(this)
        }
    }
}
