package eu.kanade.translation.pipeline.memory

import eu.kanade.translation.pipeline.memory.TranslationMemoryBudget.DecodeDecisionKind
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TranslationMemoryBudgetTest {

    @Test
    fun `normal page decodes full quality when it fits heap budget`() {
        val snapshot = snapshot(available = 256L * MIB)

        val decision = TranslationMemoryBudget.chooseDecodeDecision(
            width = 2_000,
            height = 3_000,
            snapshot = snapshot,
        )

        decision.kind shouldBe DecodeDecisionKind.FULL
        decision.sampleSize shouldBe 1
    }

    @Test
    fun `normal page is heap constrained instead of downsampled`() {
        val snapshot = snapshot(available = 32L * MIB)

        val decision = TranslationMemoryBudget.chooseDecodeDecision(
            width = 2_000,
            height = 3_000,
            snapshot = snapshot,
        )

        decision.kind shouldBe DecodeDecisionKind.HEAP_CONSTRAINED
        decision.sampleSize shouldBe 2
    }

    @Test
    fun `huge source page can be size limited when sampled source cap fits heap`() {
        val snapshot = snapshot(available = 512L * MIB)

        val decision = TranslationMemoryBudget.chooseDecodeDecision(
            width = 10_000,
            height = 10_000,
            snapshot = snapshot,
        )

        decision.kind shouldBe DecodeDecisionKind.SOURCE_TOO_LARGE
        decision.sampleSize shouldBe 2
    }

    @Test
    fun `huge source page still defers when source capped sample does not fit heap`() {
        val snapshot = snapshot(available = 64L * MIB)

        val decision = TranslationMemoryBudget.chooseDecodeDecision(
            width = 10_000,
            height = 10_000,
            snapshot = snapshot,
        )

        decision.kind shouldBe DecodeDecisionKind.HEAP_CONSTRAINED
        decision.sampleSize shouldBe 2
    }

    @Test
    fun `one neural session proceeds with its native system reserve`() {
        val reserve = TranslationMemoryBudget.neuralNativeSystemReserveBytes(sessionCount = 1)

        val decision = TranslationMemoryBudget.neuralInpaintDecision(
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
        val oneSessionReserve = TranslationMemoryBudget.neuralNativeSystemReserveBytes(sessionCount = 1)

        val decision = TranslationMemoryBudget.neuralInpaintDecision(
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
        val reserve = TranslationMemoryBudget.neuralNativeSystemReserveBytes(sessionCount = 2)

        val decision = TranslationMemoryBudget.neuralInpaintDecision(
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

    private fun snapshot(available: Long): TranslationMemoryBudget.Snapshot {
        return TranslationMemoryBudget.Snapshot(
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
