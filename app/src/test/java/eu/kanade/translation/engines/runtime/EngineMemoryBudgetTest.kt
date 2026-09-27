package eu.kanade.translation.engines.runtime

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class EngineMemoryBudgetTest {

    @Test
    fun `one neural session proceeds with its native system reserve`() {
        val reserve = EngineMemoryBudget.neuralNativeSystemReserveBytes(sessionCount = 1)

        val decision = EngineMemoryBudget.neuralInpaintDecision(
            pageWidth = 2_000,
            pageHeight = 3_000,
            cropWidth = 512,
            cropHeight = 512,
            sessionCount = 1,
            snapshot = snapshot(available = 512L * MIB),
            systemHeadroomBytes = reserve,
        )

        decision.canRun shouldBe true
        decision.mode shouldBe "single_session"
        decision.nativeSystemReserveBytes shouldBe 160L * MIB
    }

    @Test
    fun `dual neural sessions decline when only one-session reserve is available`() {
        val oneSessionReserve = EngineMemoryBudget.neuralNativeSystemReserveBytes(sessionCount = 1)

        val decision = EngineMemoryBudget.neuralInpaintDecision(
            pageWidth = 2_000,
            pageHeight = 3_000,
            cropWidth = 512,
            cropHeight = 512,
            sessionCount = 2,
            snapshot = snapshot(available = 512L * MIB),
            systemHeadroomBytes = oneSessionReserve,
        )

        decision.canRun shouldBe false
        decision.mode shouldBe "dual_session"
        decision.nativeSystemReserveBytes shouldBe 256L * MIB
        decision.reason shouldBe "native_system_reserve"
    }

    @Test
    fun `dual neural sessions proceed at exact deterministic reserve boundary`() {
        val reserve = EngineMemoryBudget.neuralNativeSystemReserveBytes(sessionCount = 2)

        val decision = EngineMemoryBudget.neuralInpaintDecision(
            pageWidth = 2_000,
            pageHeight = 3_000,
            cropWidth = 512,
            cropHeight = 512,
            sessionCount = 2,
            snapshot = snapshot(available = 512L * MIB),
            systemHeadroomBytes = reserve,
        )

        decision.canRun shouldBe true
        decision.nativeSystemReserveBytes shouldBe 256L * MIB
    }

    private fun snapshot(available: Long): EngineMemoryBudget.HeapSnapshot {
        return EngineMemoryBudget.HeapSnapshot(
            maxHeapBytes = 512L * MIB,
            usedHeapBytes = 512L * MIB - available,
            freeHeapBytes = available,
            availableHeapBytes = available,
        )
    }

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
