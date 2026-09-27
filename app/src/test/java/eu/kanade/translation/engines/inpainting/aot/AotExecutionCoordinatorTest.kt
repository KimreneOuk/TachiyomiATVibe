package eu.kanade.translation.engines.inpainting.aot

import eu.kanade.translation.engines.runtime.onnx.HardwareDiscoveryEngine
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AotExecutionCoordinatorTest {

    @Test
    fun `accelerator runtime failure retries the same neural request on CPU`() {
        val request = Any()
        val observed = mutableListOf<Pair<Any, AotExecutionCoordinator.Backend>>()
        val neuralPixels = intArrayOf(1, 2, 3)

        val result = AotExecutionCoordinator.run(
            request = request,
            preferredAccelerator = AotExecutionCoordinator.Backend.QNN_HTP,
            cpuBackend = AotExecutionCoordinator.Backend.XNNPACK,
            attempt = { sameRequest, backend ->
                observed += sameRequest to backend
                if (backend == AotExecutionCoordinator.Backend.QNN_HTP) {
                    AotExecutionCoordinator.Attempt.Failed(IllegalStateException("device execution failed"))
                } else {
                    AotExecutionCoordinator.Attempt.Success(backend, neuralPixels)
                }
            },
            telea = { error("CPU retry should accept the same neural request") },
        )

        observed.map { it.second } shouldBe listOf(
            AotExecutionCoordinator.Backend.QNN_HTP,
            AotExecutionCoordinator.Backend.XNNPACK,
        )
        observed.all { it.first === request } shouldBe true
        result shouldBe AotExecutionCoordinator.Result.Neural(
            backend = AotExecutionCoordinator.Backend.XNNPACK,
            value = neuralPixels,
        )
    }

    @Test
    fun `backend failure is scoped to its engine health monitor`() {
        val failedEngineHealth = NnapiHealthMonitor()
        failedEngineHealth.disableForNativeException()

        val nextEngineHealth = NnapiHealthMonitor()

        failedEngineHealth.isHealthy() shouldBe false
        nextEngineHealth.isHealthy() shouldBe true
    }

    @Test
    fun `neural failures select Telea without a PushPull peer route`() {
        val attempted = mutableListOf<AotExecutionCoordinator.Backend>()
        var teleaCalls = 0

        val result = AotExecutionCoordinator.run(
            request = Unit,
            preferredAccelerator = AotExecutionCoordinator.Backend.NNAPI,
            cpuBackend = AotExecutionCoordinator.Backend.XNNPACK,
            attempt = { _, backend ->
                attempted += backend
                AotExecutionCoordinator.Attempt.Failed(IllegalStateException("${backend.name} failed"))
            },
            telea = {
                teleaCalls++
                "telea-result"
            },
        )

        attempted shouldBe listOf(
            AotExecutionCoordinator.Backend.NNAPI,
            AotExecutionCoordinator.Backend.XNNPACK,
        )
        teleaCalls shouldBe 1
        result shouldBe AotExecutionCoordinator.Result.Telea("telea-result")
    }

    @Test
    fun `only accelerator failure creates a health event when CPU also fails`() {
        val healthEvents = mutableListOf<AotExecutionCoordinator.Backend>()

        val result = AotExecutionCoordinator.run(
            request = Unit,
            preferredAccelerator = AotExecutionCoordinator.Backend.NNAPI,
            cpuBackend = AotExecutionCoordinator.Backend.XNNPACK,
            attempt = { _, backend ->
                AotExecutionCoordinator.Attempt.Failed(IllegalStateException("${backend.name} runtime failure"))
            },
            telea = { "telea-result" },
            onAcceleratorRuntimeFailure = { backend, _ -> healthEvents += backend },
        )

        healthEvents shouldBe listOf(AotExecutionCoordinator.Backend.NNAPI)
        result shouldBe AotExecutionCoordinator.Result.Telea("telea-result")
    }

    @Test
    fun `unavailable accelerator falls through to CPU without a health event`() {
        val attempted = mutableListOf<AotExecutionCoordinator.Backend>()
        val healthEvents = mutableListOf<AotExecutionCoordinator.Backend>()

        val result = AotExecutionCoordinator.run(
            request = Unit,
            preferredAccelerator = AotExecutionCoordinator.Backend.NNAPI,
            cpuBackend = AotExecutionCoordinator.Backend.XNNPACK,
            attempt = { _, backend ->
                attempted += backend
                if (backend == AotExecutionCoordinator.Backend.NNAPI) {
                    AotExecutionCoordinator.Attempt.Unavailable("session_not_initialized")
                } else {
                    AotExecutionCoordinator.Attempt.Success(backend, "cpu-result")
                }
            },
            telea = { error("CPU retry should succeed") },
            onAcceleratorRuntimeFailure = { backend, _ -> healthEvents += backend },
        )

        attempted shouldBe listOf(
            AotExecutionCoordinator.Backend.NNAPI,
            AotExecutionCoordinator.Backend.XNNPACK,
        )
        healthEvents shouldBe emptyList()
        result shouldBe AotExecutionCoordinator.Result.Neural(
            backend = AotExecutionCoordinator.Backend.XNNPACK,
            value = "cpu-result",
        )
    }

    @Test
    fun `fixed CPU out of memory skips dynamic inference and selects Telea`() {
        val attempted = mutableListOf<AotExecutionCoordinator.Backend>()
        var teleaCalls = 0

        val result = AotExecutionCoordinator.run(
            request = Unit,
            preferredAccelerator = null,
            cpuBackend = AotExecutionCoordinator.Backend.XNNPACK,
            dynamicBackend = AotExecutionCoordinator.Backend.DYNAMIC,
            attempt = { _, backend ->
                attempted += backend
                AotExecutionCoordinator.Attempt.Failed(OutOfMemoryError("fixed CPU OOM"))
            },
            telea = {
                teleaCalls++
                "telea-result"
            },
        )

        attempted shouldBe listOf(AotExecutionCoordinator.Backend.XNNPACK)
        teleaCalls shouldBe 1
        result shouldBe AotExecutionCoordinator.Result.Telea("telea-result")
    }

    @Test
    fun `suspicious output diagnostics never select fallback or report backend failure`() {
        val pixels = IntArray(64) { 0xFF808080.toInt() }
        val mask = IntArray(64) { 0xFFFFFFFF.toInt() }
        val stats = AotOutputGuard.inspect(pixels, mask, 8, 8)
        AotOutputGuard.classify(stats) shouldBe true
        val healthEvents = mutableListOf<AotExecutionCoordinator.Backend>()

        val result = AotExecutionCoordinator.run(
            request = Unit,
            preferredAccelerator = AotExecutionCoordinator.Backend.NNAPI,
            cpuBackend = AotExecutionCoordinator.Backend.XNNPACK,
            attempt = { _, backend -> AotExecutionCoordinator.Attempt.Success(backend, pixels) },
            telea = { error("diagnostic output appearance must not select fallback") },
            onAcceleratorRuntimeFailure = { backend, _ -> healthEvents += backend },
        )

        healthEvents shouldBe emptyList()
        result shouldBe AotExecutionCoordinator.Result.Neural(
            backend = AotExecutionCoordinator.Backend.NNAPI,
            value = pixels,
        )
    }

    @Test
    fun `forced CPU route exposes no accelerator candidate`() {
        val candidate = AotExecutionCoordinator.preferredAccelerator(
            HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK,
        )
        val attempted = mutableListOf<AotExecutionCoordinator.Backend>()

        val result = AotExecutionCoordinator.run(
            request = Unit,
            preferredAccelerator = candidate,
            cpuBackend = AotExecutionCoordinator.Backend.XNNPACK,
            attempt = { _, backend ->
                attempted += backend
                AotExecutionCoordinator.Attempt.Success(backend, "cpu-result")
            },
            telea = { error("CPU neural backend should be attempted") },
        )

        candidate shouldBe null
        attempted shouldBe listOf(AotExecutionCoordinator.Backend.XNNPACK)
        result shouldBe AotExecutionCoordinator.Result.Neural(
            backend = AotExecutionCoordinator.Backend.XNNPACK,
            value = "cpu-result",
        )
    }
}
