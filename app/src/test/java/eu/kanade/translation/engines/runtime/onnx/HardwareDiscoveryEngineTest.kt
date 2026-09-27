package eu.kanade.translation.engines.runtime.onnx

import eu.kanade.translation.engines.runtime.onnx.HardwareDiscoveryEngine.HardwareRoute
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.PaddleOcrExecutionProvider

class HardwareDiscoveryEngineTest {

    @BeforeEach
    @AfterEach
    fun resetEngine() {
        HardwareDiscoveryEngine.resetForTesting()
    }

    @Test
    fun `preflight rejects emulator and falls back to CPU_XNNPACK`() {
        var qnnProbed = false
        var nnapiProbed = false
        val route = HardwareDiscoveryEngine.evaluateRoute(
            isEmulator = true,
            sdkInt = 34,
            supportedAbis = arrayOf("arm64-v8a"),
            isQualcomm = true,
            probeQnn = {
                qnnProbed = true
                true
            },
            probeNnapi = {
                nnapiProbed = true
                true
            },
        )
        route shouldBe HardwareRoute.CPU_XNNPACK
        qnnProbed shouldBe false
        nnapiProbed shouldBe false
    }

    @Test
    fun `preflight rejects SDK below 29 and falls back to CPU_XNNPACK`() {
        var qnnProbed = false
        val route = HardwareDiscoveryEngine.evaluateRoute(
            isEmulator = false,
            sdkInt = 28,
            supportedAbis = arrayOf("arm64-v8a"),
            isQualcomm = true,
            probeQnn = {
                qnnProbed = true
                true
            },
        )
        route shouldBe HardwareRoute.CPU_XNNPACK
        qnnProbed shouldBe false
    }

    @Test
    fun `preflight rejects non-arm64 ABI and falls back to CPU_XNNPACK`() {
        var qnnProbed = false
        val route = HardwareDiscoveryEngine.evaluateRoute(
            isEmulator = false,
            sdkInt = 34,
            supportedAbis = arrayOf("x86_64", "armeabi-v7a"),
            isQualcomm = true,
            probeQnn = {
                qnnProbed = true
                true
            },
        )
        route shouldBe HardwareRoute.CPU_XNNPACK
        qnnProbed shouldBe false
    }

    @Test
    fun `qualcomm snapdragon probes QNN HTP and latches QUALCOMM_QNN_HTP on success`() {
        var qnnProbes = 0
        var nnapiProbes = 0
        val route = HardwareDiscoveryEngine.evaluateRoute(
            isEmulator = false,
            sdkInt = 34,
            supportedAbis = arrayOf("arm64-v8a"),
            isQualcomm = true,
            probeQnn = {
                qnnProbes++
                true
            },
            probeNnapi = {
                nnapiProbes++
                true
            },
        )
        route shouldBe HardwareRoute.QUALCOMM_QNN_HTP
        qnnProbes shouldBe 1
        nnapiProbes shouldBe 0
    }

    @Test
    fun `qualcomm snapdragon falls back to CPU_XNNPACK on QNN HTP probe failure`() {
        var qnnProbes = 0
        val route = HardwareDiscoveryEngine.evaluateRoute(
            isEmulator = false,
            sdkInt = 34,
            supportedAbis = arrayOf("arm64-v8a"),
            isQualcomm = true,
            probeQnn = {
                qnnProbes++
                false
            },
        )
        route shouldBe HardwareRoute.CPU_XNNPACK
        qnnProbes shouldBe 1
    }

    @Test
    fun `non-qualcomm soc probes NNAPI and latches NNAPI on success`() {
        var qnnProbes = 0
        var nnapiProbes = 0
        val route = HardwareDiscoveryEngine.evaluateRoute(
            isEmulator = false,
            sdkInt = 34,
            supportedAbis = arrayOf("arm64-v8a"),
            isQualcomm = false,
            probeQnn = {
                qnnProbes++
                true
            },
            probeNnapi = {
                nnapiProbes++
                true
            },
        )
        route shouldBe HardwareRoute.NNAPI
        qnnProbes shouldBe 0
        nnapiProbes shouldBe 1
    }

    @Test
    fun `non-qualcomm soc falls back to CPU_XNNPACK on NNAPI probe failure`() {
        var nnapiProbes = 0
        val route = HardwareDiscoveryEngine.evaluateRoute(
            isEmulator = false,
            sdkInt = 34,
            supportedAbis = arrayOf("arm64-v8a"),
            isQualcomm = false,
            probeNnapi = {
                nnapiProbes++
                false
            },
        )
        route shouldBe HardwareRoute.CPU_XNNPACK
        nnapiProbes shouldBe 1
    }

    @Test
    fun `resolveRoute latches decision and executes probe only once`() {
        var probeCount = 0
        HardwareDiscoveryEngine.resetForTesting(
            customQnnProbe = {
                probeCount++
                true
            },
        )

        HardwareDiscoveryEngine.isResolved shouldBe false
        val first = HardwareDiscoveryEngine.resolveRoute()
        HardwareDiscoveryEngine.isResolved shouldBe true

        val second = HardwareDiscoveryEngine.resolveRoute()
        val third = HardwareDiscoveryEngine.resolveRoute()

        first shouldBe second
        second shouldBe third
        HardwareDiscoveryEngine.activeRoute shouldBe first
    }

    @Test
    fun `tripCircuitBreaker permanently demotes activeRoute to CPU_XNNPACK`() {
        HardwareDiscoveryEngine.resetForTesting(route = HardwareRoute.QUALCOMM_QNN_HTP)
        HardwareDiscoveryEngine.activeRoute shouldBe HardwareRoute.QUALCOMM_QNN_HTP
        HardwareDiscoveryEngine.circuitBreakerTripped shouldBe false

        HardwareDiscoveryEngine.tripCircuitBreaker("test_fatal_error", RuntimeException("QNN crashed"))

        HardwareDiscoveryEngine.circuitBreakerTripped shouldBe true
        HardwareDiscoveryEngine.activeRoute shouldBe HardwareRoute.CPU_XNNPACK
        HardwareDiscoveryEngine.isResolved shouldBe true

        // Subsequent resolveRoute returns CPU_XNNPACK immediately
        HardwareDiscoveryEngine.resolveRoute() shouldBe HardwareRoute.CPU_XNNPACK
    }

    @Test
    fun `explicit Paddle GPU selection probes GPU without touching HTP`() {
        var htpProbes = 0
        var gpuProbes = 0

        HardwareDiscoveryEngine.resolvePaddleOcrRoute(
            provider = PaddleOcrExecutionProvider.QUALCOMM_QNN_GPU,
            probeHtp = {
                htpProbes++
                error("HTP must not be probed for an explicit GPU choice")
            },
            probeGpu = {
                gpuProbes++
                true
            },
        ) shouldBe HardwareRoute.QUALCOMM_QNN_GPU

        htpProbes shouldBe 0
        gpuProbes shouldBe 1
    }

    @Test
    fun `explicit Paddle GPU probe failure returns unavailable instead of selecting HTP`() {
        var htpProbes = 0

        HardwareDiscoveryEngine.resolvePaddleOcrRoute(
            provider = PaddleOcrExecutionProvider.QUALCOMM_QNN_GPU,
            probeHtp = {
                htpProbes++
                true
            },
            probeGpu = { false },
        ) shouldBe null

        htpProbes shouldBe 0
    }

    @Test
    fun `explicit Paddle CPU selection skips accelerator probes`() {
        var htpProbes = 0
        var gpuProbes = 0

        HardwareDiscoveryEngine.resolvePaddleOcrRoute(
            provider = PaddleOcrExecutionProvider.CPU,
            probeHtp = {
                htpProbes++
                true
            },
            probeGpu = {
                gpuProbes++
                true
            },
        ) shouldBe HardwareRoute.CPU_XNNPACK

        htpProbes shouldBe 0
        gpuProbes shouldBe 0
    }
}
