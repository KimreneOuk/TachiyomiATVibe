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
    /** CPU with the explicit Paddle matrix XNNPACK registration. */
    CPU,

    /** The production-default CPU provider without XNNPACK registration. */
    CPU_NO_XNNPACK,
    QNN_GPU,
    QNN_HTP,
    NNAPI,
    ;

    val route: HardwareDiscoveryEngine.HardwareRoute
        get() = when (this) {
            CPU, CPU_NO_XNNPACK -> HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK
            QNN_GPU -> HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_GPU
            QNN_HTP -> HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP
            NNAPI -> HardwareDiscoveryEngine.HardwareRoute.NNAPI
        }

    val isAccelerator: Boolean
        get() = !isCpu

    val isCpu: Boolean
        get() = this == CPU || this == CPU_NO_XNNPACK

    /** Null selects ORT's default CPU provider instead of explicitly registering XNNPACK. */
    val sessionRouteOverride: HardwareDiscoveryEngine.HardwareRoute?
        get() = if (this == CPU_NO_XNNPACK) null else route

    val wireLabel: String
        get() = when (this) {
            CPU, CPU_NO_XNNPACK -> "cpu"
            QNN_GPU -> "qnn_gpu"
            QNN_HTP -> "qnn_htp"
            NNAPI -> "nnapi"
        }

    /** Expected registration fact; this is deliberately distinct from the stable provider wire label. */
    val expectedRegisteredProviderLabel: String
        get() = when (this) {
            CPU -> "xnnpack"
            CPU_NO_XNNPACK -> "cpu"
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

    val sessionRouteOverride: HardwareDiscoveryEngine.HardwareRoute?
        get() = target.sessionRouteOverride

    val expectedProviderLabel: String
        get() = target.wireLabel

    companion object {
        val matrixTargets: List<PaddleOcrProviderTarget> = listOf(
            PaddleOcrProviderTarget.CPU,
            PaddleOcrProviderTarget.CPU_NO_XNNPACK,
            PaddleOcrProviderTarget.QNN_GPU,
            PaddleOcrProviderTarget.QNN_HTP,
            PaddleOcrProviderTarget.NNAPI,
        )

        fun cpuB1EmergencyFallback(): PaddleOcrProviderOverride =
            PaddleOcrProviderOverride(
                target = PaddleOcrProviderTarget.CPU,
                strictNoCpuFallback = false,
            )

        fun cpuDefaultNoXnnpack(): PaddleOcrProviderOverride =
            PaddleOcrProviderOverride(
                target = PaddleOcrProviderTarget.CPU_NO_XNNPACK,
                strictNoCpuFallback = false,
            )
    }
}
