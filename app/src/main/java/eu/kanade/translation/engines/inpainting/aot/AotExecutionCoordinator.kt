package eu.kanade.translation.engines.inpainting.aot

import eu.kanade.translation.engines.runtime.onnx.HardwareDiscoveryEngine

/**
 * Runtime backend order for one AOT request. Output appearance is not an
 * execution event and is intentionally absent from this decision seam.
 */
internal object AotExecutionCoordinator {

    enum class Backend { QNN_HTP, QNN_GPU, NNAPI, XNNPACK, CPU, DYNAMIC }

    sealed interface Attempt<out T> {
        data class Success<T>(val backend: Backend, val value: T) : Attempt<T>
        data class Unavailable(val reason: String) : Attempt<Nothing>
        data class Failed(
            val error: Throwable,
            val runtimeFailure: Boolean = true,
        ) : Attempt<Nothing>
    }

    sealed interface Result<out N, out C> {
        data class Neural<N>(val backend: Backend, val value: N) : Result<N, Nothing>
        data class Telea<C>(val value: C) : Result<Nothing, C>
    }

    fun preferredAccelerator(route: HardwareDiscoveryEngine.HardwareRoute): Backend? = when (route) {
        HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP -> Backend.QNN_HTP
        HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_GPU -> Backend.QNN_GPU
        HardwareDiscoveryEngine.HardwareRoute.NNAPI -> Backend.NNAPI
        HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK -> null
    }

    fun <R, N, C> run(
        request: R,
        preferredAccelerator: Backend?,
        cpuBackend: Backend,
        dynamicBackend: Backend? = null,
        attempt: (request: R, backend: Backend) -> Attempt<N>,
        telea: (request: R) -> C,
        onAcceleratorRuntimeFailure: (Backend, Throwable) -> Unit = { _, _ -> },
    ): Result<N, C> {
        require(cpuBackend == Backend.XNNPACK || cpuBackend == Backend.CPU) {
            "cpuBackend must identify the fixed model's CPU execution provider"
        }
        preferredAccelerator?.let { backend ->
            require(backend == Backend.QNN_HTP || backend == Backend.QNN_GPU || backend == Backend.NNAPI) {
                "preferredAccelerator must be an accelerator backend"
            }
            when (val accelerated = attempt(request, backend)) {
                is Attempt.Success -> return Result.Neural(accelerated.backend, accelerated.value)
                is Attempt.Failed -> if (accelerated.runtimeFailure) {
                    onAcceleratorRuntimeFailure(backend, accelerated.error)
                }
                is Attempt.Unavailable -> Unit
            }
        }

        val cpu = attempt(request, cpuBackend)
        if (cpu is Attempt.Success) return Result.Neural(cpu.backend, cpu.value)

        // An OOM on the fixed CPU path means the request cannot safely allocate
        // another neural run. The classical inpainting path uses a bounded crop.
        val fixedCpuOutOfMemory = cpu is Attempt.Failed && cpu.error is OutOfMemoryError
        if (!fixedCpuOutOfMemory && dynamicBackend != null) {
            when (val dynamic = attempt(request, dynamicBackend)) {
                is Attempt.Success -> return Result.Neural(dynamic.backend, dynamic.value)
                is Attempt.Failed, is Attempt.Unavailable -> Unit
            }
        }

        return Result.Telea(telea(request))
    }
}
