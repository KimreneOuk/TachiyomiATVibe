package eu.kanade.translation.engines.runtime.litert

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * GPU-first LiteRT runner for the fixed-shape fp32 vision models with a
 * mandatory one-shot CPU demotion, mirroring PaddleOcrV6LiteRtRunner: an
 * accelerator CompiledModel is attempted at init and, on init OR invoke
 * failure, rebuilt once on XNNPACK CPU; CPU failures propagate. Tensors bind
 * by subgraph index — the deployed flatbuffers are sha256-stamped at
 * deployment, so the validated index order read from the artifacts is pinned.
 */
internal class LiteRtVisionRunner(
    private val modelFile: File,
    private val inputCount: Int,
    private val outputCount: Int,
    preferGpu: Boolean,
    private val numThreads: Int,
    private val onGpuFallback: () -> Unit,
    private val env: Environment,
) : AutoCloseable {

    private var model: CompiledModel? = null
    private var inputBuffers: List<TensorBuffer> = emptyList()
    private var outputBuffers: List<TensorBuffer> = emptyList()
    private var gpuActive = false

    var executionProviderLabel: String = "litert_uninitialized"
        private set

    init {
        try {
            if (preferGpu) {
                try {
                    initializeGpu()
                } catch (failure: Throwable) {
                    closeModel()
                    onGpuFallback()
                    logcat(LogPriority.WARN, failure) {
                        "LiteRT vision GPU init failed on ${modelFile.name}; falling back to CPU"
                    }
                    initializeCpu()
                }
            } else {
                initializeCpu()
            }
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    fun writeInputFloats(index: Int, data: FloatArray) {
        inputBuffers[index].writeFloat(data)
    }

    fun writeInputLongs(index: Int, data: LongArray) {
        inputBuffers[index].writeLong(data)
    }

    fun readOutputFloats(index: Int): FloatArray = outputBuffers[index].readFloat()

    fun readOutputInts(index: Int): IntArray = outputBuffers[index].readInt()

    /**
     * Runs [action] once and, when the GPU delegate is active and throws,
     * rebuilds once on CPU and re-runs [action]. Input writes belong inside
     * [action] because the demotion replaces every buffer.
     */
    @Synchronized
    fun <T> runWithCpuRetry(action: () -> T): T {
        try {
            return action()
        } catch (failure: Throwable) {
            if (!gpuActive) throw failure
            onGpuFallback()
            logcat(LogPriority.WARN, failure) {
                "LiteRT vision GPU invoke failed on ${modelFile.name}; retrying with CPU"
            }
            closeModel()
            initializeCpu()
            return action()
        }
    }

    fun runInference() {
        val activeModel = checkNotNull(model) { "LiteRT vision model is closed" }
        activeModel.run(inputBuffers, outputBuffers)
    }

    private fun initializeGpu() {
        bind(CompiledModel.create(modelFile.absolutePath, CompiledModel.Options(Accelerator.GPU), env), gpu = true)
    }

    private fun initializeCpu() {
        val options = CompiledModel.Options(Accelerator.CPU).apply {
            cpuOptions = CompiledModel.CpuOptions(numThreads = numThreads)
        }
        bind(CompiledModel.create(modelFile.absolutePath, options, env), gpu = false)
    }

    private fun bind(created: CompiledModel, gpu: Boolean) {
        val inputs = created.createInputBuffers()
        val outputs = created.createOutputBuffers()
        require(inputs.size == inputCount && outputs.size == outputCount) {
            "LiteRT ${modelFile.name} tensor counts drifted: " +
                "inputs=${inputs.size}/$inputCount outputs=${outputs.size}/$outputCount"
        }
        model = created
        inputBuffers = inputs
        outputBuffers = outputs
        gpuActive = gpu
        executionProviderLabel = if (gpu) "litert_gpu_delegate" else "litert_xnnpack"
    }

    private fun closeModel() {
        inputBuffers.forEach { runCatching { it.close() } }
        outputBuffers.forEach { runCatching { it.close() } }
        inputBuffers = emptyList()
        outputBuffers = emptyList()
        runCatching { model?.close() }
        model = null
        gpuActive = false
    }

    override fun close() {
        closeModel()
    }
}
