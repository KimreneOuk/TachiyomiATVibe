package eu.kanade.translation.engines.inpainting.aot

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NnapiCapabilityGateTest {
    private val eligible = NnapiCapabilityGate.Snapshot(
        sdk = 29,
        supportedAbis = listOf("arm64-v8a"),
        emulator = false,
        nnapiProviderCompiled = true,
        availableHeapBytes = NnapiCapabilityGate.MIN_AVAILABLE_HEAP_BYTES,
        systemHeadroomBytes = NnapiCapabilityGate.MIN_SYSTEM_HEADROOM_BYTES,
        lowMemory = false,
        healthy = true,
    )

    @Test fun `eligible boundary selects NNAPI`() {
        NnapiCapabilityGate.decide(eligible) shouldBe NnapiCapabilityGate.Decision.eligible()
    }

    @Test fun `API below 29 rejects`() = assertRejected(eligible.copy(sdk = 28), "sdk_below_29")

    @Test fun `non arm64 rejects`() = assertRejected(
        eligible.copy(supportedAbis = listOf("armeabi-v7a")),
        "abi_not_arm64",
    )

    @Test fun `emulator rejects`() = assertRejected(eligible.copy(emulator = true), "emulator")

    @Test fun `missing compiled provider rejects`() = assertRejected(
        eligible.copy(nnapiProviderCompiled = false),
        "nnapi_provider_not_compiled",
    )

    @Test fun `system low memory rejects`() = assertRejected(eligible.copy(lowMemory = true), "system_low_memory")

    @Test fun `heap below boundary rejects`() = assertRejected(
        eligible.copy(
            availableHeapBytes =
            NnapiCapabilityGate.MIN_AVAILABLE_HEAP_BYTES - 1,
        ),
        "heap_headroom",
    )

    @Test fun `system headroom below boundary rejects`() = assertRejected(
        eligible.copy(
            systemHeadroomBytes =
            NnapiCapabilityGate.MIN_SYSTEM_HEADROOM_BYTES - 1,
        ),
        "system_headroom",
    )

    @Test fun `unknown system headroom does not invent a rejection`() {
        NnapiCapabilityGate.decide(eligible.copy(systemHeadroomBytes = null)).eligible shouldBe true
    }

    @Test fun `disabled health rejects`() = assertRejected(eligible.copy(healthy = false), "health_disabled")

    private fun assertRejected(snapshot: NnapiCapabilityGate.Snapshot, reason: String) {
        NnapiCapabilityGate.decide(snapshot) shouldBe NnapiCapabilityGate.Decision.rejected(reason)
    }
}
