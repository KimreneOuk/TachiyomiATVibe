package eu.kanade.translation.runtime.onnx

/**
 * An explicit provider request used by the staged Paddle benchmark.
 *
 * Production routing remains owned by [HardwareDiscoveryEngine]. The matrix
 * runner uses this value only to make a cell's requested provider unambiguous
 * and to prevent a failed accelerator session from being retried on CPU.
 */
enum class PaddleOcrProviderTarget {
    CPU,
    QNN_GPU,
    QNN_HTP,
    NNAPI,
    ;

    val route: HardwareDiscoveryEngine.HardwareRoute
        get() = when (this) {
            CPU -> HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK
            QNN_GPU -> HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_GPU
            QNN_HTP -> HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP
            NNAPI -> HardwareDiscoveryEngine.HardwareRoute.NNAPI
        }

    val isAccelerator: Boolean
        get() = this != CPU

    val wireLabel: String
        get() = when (this) {
            CPU -> "cpu"
            QNN_GPU -> "qnn_gpu"
            QNN_HTP -> "qnn_htp"
            NNAPI -> "nnapi"
        }
}

/**
 * Session policy for one provider-matrix cell.
 *
 * Accelerator cells are strict by construction. CPU fallback is therefore an
 * explicit property of the CPU cell, never an implicit recovery path for an
 * accelerator claim.
 */
data class PaddleOcrProviderTestConfiguration(
    val target: PaddleOcrProviderTarget,
    val strictNoCpuFallback: Boolean = target.isAccelerator,
) {

    init {
        require(!target.isAccelerator || strictNoCpuFallback) {
            "Accelerator Paddle matrix cells must disable CPU fallback"
        }
    }

    val route: HardwareDiscoveryEngine.HardwareRoute
        get() = target.route

    val expectedProviderLabel: String
        get() = target.wireLabel

    companion object {
        val matrixTargets: List<PaddleOcrProviderTarget> = listOf(
            PaddleOcrProviderTarget.CPU,
            PaddleOcrProviderTarget.QNN_GPU,
            PaddleOcrProviderTarget.QNN_HTP,
            PaddleOcrProviderTarget.NNAPI,
        )

        fun cpuB1EmergencyFallback(): PaddleOcrProviderTestConfiguration =
            PaddleOcrProviderTestConfiguration(
                target = PaddleOcrProviderTarget.CPU,
                strictNoCpuFallback = false,
            )
    }
}
