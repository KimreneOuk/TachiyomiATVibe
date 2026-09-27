package eu.kanade.translation.engines.runtime.onnx

/**
 * An explicit provider override for a Paddle OCR session.
 *
 * Normal production routing remains owned by [HardwareDiscoveryEngine] and
 * [PaddleOcrSessionFactory]. The staged benchmark uses this value to bind each
 * matrix cell to one provider, while production uses it for explicit fallback
 * choices such as the CPU B1 emergency route.
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
 * Provider choice and fallback policy for one Paddle session.
 *
 * Accelerator cells are strict by construction. CPU fallback is therefore an
 * explicit property of the CPU cell, never an implicit recovery path for an
 * accelerator claim.
 */
data class PaddleOcrProviderOverride(
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

        fun cpuB1EmergencyFallback(): PaddleOcrProviderOverride =
            PaddleOcrProviderOverride(
                target = PaddleOcrProviderTarget.CPU,
                strictNoCpuFallback = false,
            )
    }
}
