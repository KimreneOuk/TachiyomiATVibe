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
        return OrtSession.SessionOptions().apply {
            setInterOpNumThreads(2)
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            configure(this)
        }
    }
}
