package eu.kanade.translation.util

import eu.kanade.translation.util.TranslationMemoryBudget.DecodeDecisionKind
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
