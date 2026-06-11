package eu.kanade.translation.onnx

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

object OnnxRuntimeProvider {

    val environment: OrtEnvironment by lazy {
        logcat(LogPriority.INFO) { "Initializing ONNX Runtime" }
        OrtEnvironment.getEnvironment()
    }

    private val threadCount = maxOf(1, minOf(Runtime.getRuntime().availableProcessors(), 4))

    fun createSessionOptions(block: OrtSession.SessionOptions.() -> Unit = {}): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threadCount)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            block()
        }
    }

    fun close() {
        environment.close()
    }
}
