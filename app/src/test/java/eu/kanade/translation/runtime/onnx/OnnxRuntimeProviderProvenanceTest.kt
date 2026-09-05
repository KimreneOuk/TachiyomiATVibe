package eu.kanade.translation.runtime.onnx

import eu.kanade.translation.runtime.onnx.OnnxRuntimeProvider.ProviderOptionsBuild
import eu.kanade.translation.runtime.onnx.OnnxRuntimeProvider.RegisteredExecutionProvider
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * T922 Phase 5 (plan §3.4, §6.1, amendment §10.8): provider-label provenance
 * tests for the session-creation routing policy.
 *
 * Drives [OnnxRuntimeProvider.openSessionWithHonestLabel] — the pure core that
 * [OnnxRuntimeProvider.createSessionWithFallback] executes verbatim over the
 * real native surface — with inert String/Int tokens. The ai.onnxruntime
 * classes cannot be constructed (or even class-initialized) off-device: their
 * static initializers load the native library, so — per the plan's seam/fake
 * requirement — no physical ORT model or native runtime is involved.
 *
 * Provenance levels (§10.8):
 * - requested  = what the requested build registers (flags/device route);
 * - registered = the typed [RegisteredExecutionProvider] build result — the
 *   only source of the sunk label;
 * - proven     = ModelRoutingEngine SUPPORTED via recordSuccessfulInference —
 *   covered by ModelRoutingEngineTest and BubbleSegmenterRecoveryPolicyTest.
 */
class OnnxRuntimeProviderProvenanceTest {

    private class Recording {
        val sunkLabels = mutableListOf<String>()
        val openedOptions = mutableListOf<String>()
        val closedOptions = mutableListOf<String>()
        val recordedFailures = mutableListOf<Throwable>()
    }

    private val routeHtp = HardwareDiscoveryEngine.HardwareRoute.QUALCOMM_QNN_HTP
    private val routeXnnpack = HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK

    @Test
    fun `strict qnn registration failure rebuilds on cpu and labels cpu never qnn_htp`() {
        val rec = Recording()
        val registrationError = RuntimeException("addQnn failed: QNN_DEVICE_ERROR_INVALID_CONFIG")

        val result = OnnxRuntimeProvider.openSessionWithHonestLabel<String, Int>(
            route = routeHtp,
            canUseAccelerator = true,
            useXnnpack = false,
            buildRequested = {
                throw OnnxRuntimeProvider.AcceleratorRegistrationException(routeHtp, registrationError)
            },
            buildCpu = {
                ProviderOptionsBuild("cpu-options", RegisteredExecutionProvider.CPU)
            },
            open = { opts ->
                rec.openedOptions += opts
                42
            },
            closeOptions = { opts -> rec.closedOptions += opts },
            sink = { rec.sunkLabels += it },
            recordModelFailure = { rec.recordedFailures += it },
        )

        result shouldBe 42
        // The session is the CPU retry, labelled cpu — never qnn_htp.
        rec.openedOptions shouldContainExactly listOf("cpu-options")
        rec.sunkLabels shouldContainExactly listOf("cpu")
        // Device-level failure: no model routing failure is recorded (the
        // real builder already logged and tripped the circuit breaker).
        rec.recordedFailures shouldContainExactly emptyList()
        rec.closedOptions shouldContainExactly listOf("cpu-options")
    }

    @Test
    fun `xnnpack registration failure resolves to cpu and reaches the provider sink`() {
        val rec = Recording()
        // The real builder resolves a failed XNNPACK registration to the
        // default CPU EP and reports RegisteredExecutionProvider.CPU (previously
        // it only logged). The policy must label that session cpu and the label
        // must reach providerSink.
        val result = OnnxRuntimeProvider.openSessionWithHonestLabel<String, Int>(
            route = routeXnnpack,
            canUseAccelerator = false,
            useXnnpack = true,
            buildRequested = {
                ProviderOptionsBuild("xnnpack-options-lost-ep", RegisteredExecutionProvider.CPU)
            },
            buildCpu = {
                ProviderOptionsBuild("cpu-options", RegisteredExecutionProvider.CPU)
            },
            open = { opts ->
                rec.openedOptions += opts
                7
            },
            closeOptions = { opts -> rec.closedOptions += opts },
            sink = { rec.sunkLabels += it },
            recordModelFailure = { rec.recordedFailures += it },
        )

        result shouldBe 7
        rec.openedOptions shouldContainExactly listOf("xnnpack-options-lost-ep")
        rec.sunkLabels shouldContainExactly listOf("cpu")
        rec.recordedFailures shouldContainExactly emptyList()
    }

    @Test
    fun `non accelerated request on htp capable device is labelled from registration never from active route`() {
        val rec = Recording()
        // Old defect: useXnnpack request while the DEVICE route is
        // QUALCOMM_QNN_HTP sank "qnn_htp" because the label consulted
        // HardwareDiscoveryEngine.activeRoute. The label must come from what
        // actually registered.
        val result = OnnxRuntimeProvider.openSessionWithHonestLabel<String, Int>(
            route = routeHtp,
            canUseAccelerator = false,
            useXnnpack = true,
            buildRequested = {
                ProviderOptionsBuild("xnnpack-options", RegisteredExecutionProvider.XNNPACK)
            },
            buildCpu = {
                ProviderOptionsBuild("cpu-options", RegisteredExecutionProvider.CPU)
            },
            open = { opts ->
                rec.openedOptions += opts
                3
            },
            closeOptions = { opts -> rec.closedOptions += opts },
            sink = { rec.sunkLabels += it },
            recordModelFailure = { rec.recordedFailures += it },
        )

        result shouldBe 3
        rec.sunkLabels shouldContainExactly listOf("xnnpack")
        rec.recordedFailures shouldContainExactly emptyList()
    }

    @Test
    fun `provider sink receives the accelerator label only after session creation succeeds`() {
        val rec = Recording()
        val creationError = RuntimeException("graph not fully partitionable")

        val result = OnnxRuntimeProvider.openSessionWithHonestLabel<String, Int>(
            route = routeHtp,
            canUseAccelerator = true,
            useXnnpack = false,
            buildRequested = {
                ProviderOptionsBuild("htp-options", RegisteredExecutionProvider.QNN_HTP)
            },
            buildCpu = {
                ProviderOptionsBuild("cpu-options", RegisteredExecutionProvider.CPU)
            },
            open = { opts ->
                if (opts == "htp-options") throw creationError
                rec.openedOptions += opts
                9
            },
            closeOptions = { opts -> rec.closedOptions += opts },
            sink = { rec.sunkLabels += it },
            recordModelFailure = { rec.recordedFailures += it },
        )

        result shouldBe 9
        // The failed accelerator creation NEVER reached the sink: only the
        // successful CPU retry is labelled.
        rec.sunkLabels shouldContainExactly listOf("cpu")
        rec.openedOptions shouldContainExactly listOf("cpu-options")
        // Accelerated creation failure is model-level: recorded once.
        rec.recordedFailures shouldContainExactly listOf(creationError)
        // Both builds were closed (requested build twice: catch + finally).
        rec.closedOptions shouldContainExactly listOf("htp-options", "cpu-options", "htp-options")
    }

    @Test
    fun `successful accelerator creation sinks the registered label exactly once`() {
        val rec = Recording()

        val result = OnnxRuntimeProvider.openSessionWithHonestLabel<String, Int>(
            route = routeHtp,
            canUseAccelerator = true,
            useXnnpack = false,
            buildRequested = {
                ProviderOptionsBuild("htp-options", RegisteredExecutionProvider.QNN_HTP)
            },
            buildCpu = {
                throw IllegalStateException("CPU retry must not run on success")
            },
            open = { opts ->
                rec.openedOptions += opts
                11
            },
            closeOptions = { opts -> rec.closedOptions += opts },
            sink = { rec.sunkLabels += it },
            recordModelFailure = { rec.recordedFailures += it },
        )

        result shouldBe 11
        rec.sunkLabels shouldContainExactly listOf("qnn_htp")
        rec.openedOptions shouldContainExactly listOf("htp-options")
        rec.recordedFailures shouldContainExactly emptyList()
        // The requested build is still closed after a successful open.
        rec.closedOptions shouldContainExactly listOf("htp-options")
    }

    @Test
    fun `cpu attempt failure records no model failure and propagates without sinking a label`() {
        val rec = Recording()
        val cpuError = RuntimeException("cpu creation failed")

        val thrown = assertThrows<RuntimeException> {
            OnnxRuntimeProvider.openSessionWithHonestLabel<String, Int>(
                route = routeXnnpack,
                canUseAccelerator = false,
                useXnnpack = false,
                buildRequested = {
                    ProviderOptionsBuild("cpu-options", RegisteredExecutionProvider.CPU)
                },
                buildCpu = {
                    ProviderOptionsBuild("cpu-retry-options", RegisteredExecutionProvider.CPU)
                },
                open = { _ -> throw cpuError },
                closeOptions = { opts -> rec.closedOptions += opts },
                sink = { rec.sunkLabels += it },
                recordModelFailure = { rec.recordedFailures += it },
            )
        }

        thrown shouldBe cpuError
        // CPU is not an accelerator attempt: no model routing failure.
        rec.recordedFailures shouldContainExactly emptyList()
        // Sink fires only after a successful open — every attempt failed.
        rec.sunkLabels shouldContainExactly emptyList()
    }

    @Test
    fun `cpu retry after failed cpu attempt still labels cpu on success`() {
        val rec = Recording()
        val firstError = RuntimeException("transient creation failure")

        val result = OnnxRuntimeProvider.openSessionWithHonestLabel<String, Int>(
            route = routeXnnpack,
            canUseAccelerator = false,
            useXnnpack = false,
            buildRequested = {
                ProviderOptionsBuild("cpu-options", RegisteredExecutionProvider.CPU)
            },
            buildCpu = {
                ProviderOptionsBuild("cpu-retry-options", RegisteredExecutionProvider.CPU)
            },
            open = { opts ->
                if (opts == "cpu-options") throw firstError
                rec.openedOptions += opts
                5
            },
            closeOptions = { opts -> rec.closedOptions += opts },
            sink = { rec.sunkLabels += it },
            recordModelFailure = { rec.recordedFailures += it },
        )

        result shouldBe 5
        // Pre-existing behavior preserved: a failed CPU attempt is retried
        // once on CPU and the successful session is honestly labelled cpu.
        rec.openedOptions shouldContainExactly listOf("cpu-retry-options")
        rec.sunkLabels shouldContainExactly listOf("cpu")
        rec.recordedFailures shouldContainExactly emptyList()
    }
}
